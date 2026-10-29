# System Architecture

This document defines the V1 system architecture for the simulated banking system.

The architecture connects the frontend, backend, database, external AWS services and supporting infrastructure into one system. It also defines the main data boundaries, communication patterns, reliability rules and responsibilities of each component.

The design is intentionally practical. The project is a learning/resume-grade banking simulation, so the architecture should demonstrate good backend engineering without introducing distributed-system patterns that are not justified by the project.

---

# 1. Architecture Goals

The architecture is designed around these goals:

- Keep PostgreSQL as the source of truth for business and financial data.
- Keep financial operations strongly consistent within database transaction boundaries.
- Keep the backend authoritative for authentication, authorization and business rules.
- Separate permanent financial history from temporary workflow data.
- Support asynchronous work without making notifications or other non-critical processing part of financial transactions.
- Make scheduled operations retry-safe and idempotent.
- Keep the system inexpensive to operate.
- Keep the architecture understandable for a solo developer.
- Allow individual components to fail without unnecessarily taking down unrelated parts of the application.
- Keep frontend and backend independently deployable.

The architecture does not attempt to reproduce the infrastructure of a real production bank. Real banking rails, real card networks, real payment processors and real regulatory infrastructure are outside the project scope.

---

# 2. Architecture Overview

The system consists of a browser-based frontend, a serverless Spring Boot backend, a PostgreSQL database and a small number of supporting managed services.

```text
                         User
                           │
                           ▼
             ┌───────────────────────────┐
             │          Vercel           │
             │ React / React Router      │
             │ Redux / Axios             │
             │ Tailwind CSS              │
             └─────────────┬─────────────┘
                           │ HTTPS / JSON APIs
                           ▼
                ┌─────────────────────┐
                │ AWS API Gateway     │
                │ HTTP API            │
                └──────────┬──────────┘
                           │
                           ▼
                ┌─────────────────────┐
                │ AWS Lambda          │
                │ Spring Boot 4.1.1   │
                │ Java 25             │
                └───────┬─────┬───────┘
                        │     │
             ┌──────────┘     └───────────────┐
             ▼                                ▼
   ┌─────────────────────┐          ┌─────────────────────┐
   │ Neon PostgreSQL     │          │ Upstash Redis       │
   │ Business/financial  │          │ Cache / rate limit  │
   │ source of truth     │          │ OTP fast path       │
   └─────────────────────┘          └─────────────────────┘

                        Supporting services

   ┌─────────────────────┐       ┌─────────────────────┐
   │ EventBridge         │       │ SNS                 │
   │ Scheduler           │       │ Notification fanout │
   └──────────┬──────────┘       └──────────┬──────────┘
              │                             │
              ▼                             ▼
        Lambda / API               ┌────────┴────────┐
        scheduled jobs             ▼                 ▼
                              SQS Email Queue   SQS Telegram Queue
                                    │                 │
                                    ▼                 ▼
                              External provider  Telegram provider

   ┌─────────────────────┐       ┌─────────────────────┐
   │ Cloudflare R2       │       │ AWS CloudWatch      │
   │ Binary objects      │       │ Application logs    │
   └─────────────────────┘       └─────────────────────┘
```

The backend is the central application boundary. Supporting services handle infrastructure concerns such as scheduling, notification delivery, object storage and logging.

---

# 3. Application Components

## 3.1 Frontend

**Technology:** React, React Router, Redux, Axios, Tailwind CSS

**Deployment:** Vercel

The frontend is deployed on Vercel and uses a same-origin rewrite for `/api/:path*` to proxy requests to the AWS API Gateway HTTP API. The browser calls the Vercel origin; the frontend does not call PostgreSQL, Redis, R2 or other infrastructure directly.

The frontend is one application with role-based experiences for:

- CUSTOMER
- MANAGER
- ADMIN

The frontend is responsible for:

- Rendering the user interface.
- Collecting user input.
- Performing user-friendly validation.
- Calling backend APIs.
- Managing local UI state.
- Displaying loading, success, error and pending states.
- Hiding actions that are not relevant to the current role.

The frontend is not authoritative for:

- Authorization
- Financial balances
- Transaction success
- Account state
- Transfer limits
- Approval decisions
- Security-sensitive business rules

The frontend can improve the user experience by checking these conditions early, but the backend must always verify them again.

---

## 3.2 API Gateway

**Technology:** Amazon API Gateway HTTP API

API Gateway provides the public HTTP entry point for the backend.

Responsibilities include:

- Receiving HTTPS requests from the frontend.
- Routing requests to the backend Lambda function.
- Handling the public API boundary.
- Providing AWS-managed API infrastructure without requiring a continuously running server.

Business rules remain in the backend rather than in API Gateway configuration.

HTTP API is used instead of introducing a more complex API Gateway setup because the project primarily needs a straightforward HTTP API and does not require unnecessary gateway features.

---

## 3.3 Backend Application

**Technology:** Java 25 + Spring Boot 4.1.1

**Runtime:** AWS Lambda

The backend is the main application and business-logic boundary.

Its responsibilities include:

- Authentication and authorization.
- Request validation.
- Business rules.
- Account and customer operations.
- Transfer and payment processing.
- UPI operations.
- Card and loan workflows.
- Manager and Admin operations.
- Financial transaction creation.
- Double-entry ledger processing.
- Idempotency handling.
- Database transaction management.
- Scheduling coordination.
- Notification initiation.
- Audit logging.
- Application logging.

The backend should be structured internally into clear layers rather than treating the Lambda function as one large handler.

A practical internal structure is:

```text
API / Controller
      │
      ▼
Application / Service
      │
      ├── Domain/business rules
      ├── Transaction boundaries
      └── Coordination
      │
      ├───────────────────────────────┐
      ▼                               ▼
Spring Data JPA Repositories    Spring JDBC Read DAOs
(Writes, state transitions,     (Dynamic filters, UNION read models,
 locking, entity invariants)     statements, dashboard aggregations)
      │                               │
      └───────────────┬───────────────┘
                      ▼
                 PostgreSQL
```
---

## 3.3.1 Hybrid Persistence Approach (Spring Data JPA + Spring JDBC)
The backend uses both Spring Data JPA and Spring JDBC (NamedParameterJdbcTemplate / JdbcClient) against the same PostgreSQL data source, splitting responsibilities by workload type:

- **Spring Data JPA (Writes, State Transitions and Core Domain Logic):** Used for transactional mutations, entity lifecycle management, relationship integrity and concurrency control (pessimistic row locking and optimistic version checks). Operations such as account creation, balance transfers, card state changes, loan disbursements and workflow approvals use JPA repositories.

- **Spring JDBC (Dynamic Search, Cross-Table Read Models and Bulk Reads):** Used for read-heavy endpoints with dynamic filter combinations, cross-table UNION ALL read models and bulk row streaming. This includes unified and account-scoped transaction history filtering, statement generation queries, customer Pending Requests aggregation, Manager work-queue summaries, Admin financial dashboards and audit-log search/export.

Using Spring JDBC for dynamic read queries avoids complex JPA Criteria API builders and bypasses Hibernate first-level cache overhead, mapping result sets directly into lightweight read DTOs.

Supporting clients such as Redis, R2 and notification services remain outside the financial source-of-truth path unless they are explicitly required for that operation.

---

# 4. Data Stores and Ownership

## 4.1 Neon PostgreSQL

PostgreSQL is the primary and authoritative data store.

It owns:

- Users and identity records.
- Customers and employees.
- Branches.
- Accounts.
- UPI records.
- Beneficiaries.
- Requests and workflows.
- Financial transactions.
- Account effects.
- Branch financial movements.
- Double-entry ledger records.
- Cards.
- Loans and EMIs.
- Trust scores.
- Notifications and delivery records.
- OTP challenge records and short-lived registration context.
- Telegram link challenges, current Telegram linkage and the durable global Telegram update checkpoint/lease.
- Idempotency records.
- Audit logs.
- Bank configuration.

If Redis or another supporting service disagrees with PostgreSQL, PostgreSQL wins.

---

## 4.2 Upstash Redis

Redis is an accelerator, not a source of financial truth.

Primary uses:

- Caching frequently requested non-financial data.
- Rate limiting.
- Active OTP fast path.
- Tracking rotated (`token:used:{tokenId}`) and revoked (`token:revoked:{tokenId}`) JWT refresh tokens for their 2-hour lifetime.
- Storing the 24-hour statement cooldown and Cloudflare R2 file address for generated statements.
- Short-lived temporary data where appropriate.

Redis should not be used to store the authoritative account balance, transaction history, ledger state or other financial state.

If Redis is unavailable:

- Financial correctness must remain intact.
- JWT refresh-token verification catches the Redis error, logs a warning, and gracefully falls back to verifying the self-contained JWT signature so active users are not logged out.
- Cache-dependent reads and statement lookups should fall back to PostgreSQL where practical.
- Rate-limiting behaviour should fail according to the operation's safety requirements.
- OTP verification should continue according to the durable OTP design where possible.
- Telegram link challenges and matched Telegram chat IDs are recovered from PostgreSQL when their Redis cache entries are missing.
- A missing/stale Telegram update-offset cache is recovered from the durable PostgreSQL checkpoint; Redis loss must not cause the poller to jump to the newest Telegram update or discard pending messages.

---

## 4.3 Cloudflare R2

R2 is used for binary object storage separated into dedicated folders/prefixes:

- `profile-photos/` for customer profile pictures.
- `statements/` for temporary generated account statement files (PDF/CSV), which are automatically deleted 24 hours after generation via R2 lifecycle rules or background cleanup.

R2 failure should not make unrelated banking operations fail.

---

# 5. External Infrastructure Components

## 5.1 EventBridge Scheduler

Amazon EventBridge Scheduler is used as the external trigger for scheduled backend work.

It is used for operations such as:

- Account suspension reactivation.
- Account closure auto-close processing.
- Manager deactivation after the configured grace period.
- Scheduled UPI payment execution.
- Credit-card monthly statement closing and next-cycle creation.
- Credit-card due-date automatic payment and overdue assessment.
- Other critical scheduled jobs defined by the requirements.

The scheduler is only a trigger.

The database remains authoritative for whether the operation is still valid and whether it has already been completed.

The application must therefore treat every scheduled invocation as potentially duplicated or delayed.

There is no dependence on a permanently running application process.

---

## 5.2 Amazon SNS

SNS is used to fan out application notifications to the appropriate asynchronous delivery path.

The backend creates the logical notification in PostgreSQL and then initiates external delivery.

Notifications are not allowed to determine whether a financial transaction succeeds.

For example:

```text
Financial operation
       │
       ├── PostgreSQL financial commit
       │
       └── Notification initiation
                    │
                    ▼
                   SNS
```

A notification failure must not roll back a successfully completed financial operation.

---

## 5.3 Amazon SQS

SQS provides durable asynchronous queues for external notification delivery.

The V1 design uses separate delivery paths for different external channels:

```text
SNS
 ├── Email queue ───► Email worker/provider
 │       └── DLQ
 │
 └── Telegram queue ─► Telegram worker/provider
         └── DLQ
```

Separate queues prevent a problem with one external provider from unnecessarily blocking another notification channel.

Each queue has a dead-letter queue for messages that repeatedly fail processing.

---

## 5.4 AWS CloudWatch

CloudWatch is the central destination for application logs from AWS-hosted components.

Application logging uses:

- SLF4J
- Spring Boot's default Logback setup
- Lombok `@Slf4j`
- Structured JSON logging where practical

Logs should contain enough context to investigate an operation without exposing passwords, OTP values, card secrets, authentication tokens or other sensitive information.

Application logs and audit logs are separate concerns.

---

# 6. Communication Model

The system uses two main communication patterns.

## 6.1 Synchronous communication

Used when the caller needs an immediate result.

```text
Browser
   │
   │ HTTPS
   ▼
API Gateway
   │
   ▼
Lambda / Spring Boot
   │
   ▼
PostgreSQL
   │
   ▼
Response
```

Examples:

- Login.
- Customer profile reads.
- Account reads.
- Beneficiary management.
- Normal transfer requests.
- Account operations.
- Manager approvals.
- Admin configuration changes.

The response should communicate the actual backend result rather than assuming success from the request being accepted by the network.

---

## 6.2 Asynchronous communication

Used when work can safely happen after the main operation or needs independent retry handling.

```text
Backend
   │
   ▼
SNS
   │
   ├──► Email SQS ───► Worker ───► Provider
   │
   └──► Telegram SQS ─► Worker ───► Provider
```

Typical asynchronous work includes external notification delivery.

Scheduled work also uses an asynchronous trigger model:

```text
EventBridge Scheduler
          │
          ▼
     Backend endpoint
          │
          ▼
      PostgreSQL
```

The database decides whether the scheduled operation should actually be performed.

---

## 6.3 Lambda and Database Connection Management

AWS Lambda can create multiple concurrent execution environments, so the backend must not assume that one application instance owns one permanent database connection.

The application should use the connection configuration recommended for the selected Neon PostgreSQL setup and avoid creating unnecessary connections for every small operation.

Initial HikariCP baseline for the selected Neon pooled endpoint:

```yaml
maximum-pool-size: 2
minimum-idle: 0
idle-timeout: 30000
max-lifetime: 300000
connection-timeout: 10000
```

The JDBC URL should use Neon's pooled endpoint for the serverless workload. `prepareThreshold=0` is not assumed to be mandatory for every driver/pooler combination; it is used only if integration testing against the selected PostgreSQL JDBC driver and Neon pooled endpoint confirms that it is required. If used, it is appended to the JDBC URL rather than applied as a domain-level rule.

Important rules:

- Use the Neon connection endpoint/configuration appropriate for serverless workloads.
- Keep database connections scoped to the application/runtime rather than treating them as permanently available resources.
- Do not assume a warm Lambda execution environment will always be reused.
- Release connections back to the pool correctly when they are no longer needed.
- Keep database transactions short and focused on the business invariant they protect.

The values above are the initial deployment baseline and should be checked against the actual database connection limit and observed workload.

---

## 6.4 Telegram Short-Polling Integration

V1 uses client-triggered Telegram `getUpdates` polling instead of a webhook or a permanently running polling thread. The authenticated customer initiates polling by selecting Verify in the banking UI. Because Telegram exposes one shared update queue for the bot, only one backend poll invocation may consume it at a time.

The poller uses `limit=100` to fetch batches of up to 100 updates. `offset` is a Telegram update-ID checkpoint, not a count of messages. For each batch, the backend filters private-chat `/start` messages and accepts a token only when it matches an active, unexpired bank-issued link challenge. Ordinary messages, malformed parameters and unknown/expired tokens are ignored.

Recognized challenge matches and advancement of the next update offset are persisted in one PostgreSQL transaction. The durable checkpoint is authoritative; Redis may cache the offset and valid token/chat matches only after the database commit. If Redis has no checkpoint or a stale value, the backend reads and reconciles the PostgreSQL checkpoint. If no checkpoint exists yet, the first poll omits `offset` and processes the earliest available unconfirmed updates. The backend never uses `offset=-1` for recovery because that can discard earlier updates.

A database-backed lease serializes poll requests and permits recovery after a crashed invocation. Each API invocation drains batches only up to a fixed batch/time budget. If updates remain, the API returns a pending result and the customer may retry verification; already-committed results remain durable and are not lost. A later `getUpdates` call advances Telegram's acknowledgement using the durable `next_offset`, after the previous batch has been persisted.

After a valid start token is matched, the Telegram chat ID is stored against the challenge in PostgreSQL and may be cached in Redis. Only the first valid match can be associated with a pending challenge. The customer's verify request consumes the matched challenge in one transaction and sets the unique `customer.telegram_chat_id`. The challenge is single-use and expires after 30 minutes. If Redis loses the raw active token, the previous unmatched challenge is expired and a new token is generated; the database stores only the token hash. Tokens and chat IDs are never written to application logs. Telegram notification delivery remains on the existing asynchronous delivery path and does not use a second independent `getUpdates` consumer.

---

# 7. Financial Operation Architecture

Financial operations receive the strongest consistency requirements in the system.

A typical financial operation follows this general flow:

```text
Request
  │
  ▼
Authentication
  │
  ▼
Authorization
  │
  ▼
Validation
  │
  ▼
Idempotency check
  │
  ▼
Business-rule checks
  │
  ▼
Database transaction
  │
  ├── Update account effects
  ├── Create FinancialTransaction
  ├── Create ledger entries
  ├── Update branch financial positions when required
  └── Persist idempotency result
  │
  ▼
Commit
  │
  ├── Response to caller
  └── Notification initiation
```

The exact tables involved depend on the operation.

The important architectural rule is that the financial state and its accounting records are committed together in the appropriate PostgreSQL transaction boundary.

Notifications, cache updates and other non-critical side effects must not be allowed to make a successfully committed financial operation appear unsuccessful.

---

# 8. Double-Entry Accounting Architecture

Financial accounting is represented separately from customer-facing transaction effects.

The main concepts are:

```text
FinancialTransaction
        │
        ├── TransactionParty
        ├── TransactionAccountEffect
        ├── BranchFinancialMovement
        │
        └── LedgerEntry
               │
               └── LedgerAccount
```

Customer-facing account effects use:

- `DEBIT` — money left the account.
- `CREDIT` — money entered the account.

Ledger entries use accounting debit/credit semantics.

For every finalized accounting transaction:

```text
Total Ledger Debits = Total Ledger Credits
```

Operational account balances are maintained for efficient reads. The application does not rebuild the complete ledger every time a customer opens an account page.

Corrections are performed using compensating/reversal financial events rather than editing finalized financial history.

---

# 9. Transaction Boundaries

Database transactions are used where multiple records must change together to preserve a business invariant.

Important examples include:

### Account creation

Account creation and all records that must exist for the newly created account are committed together.

### Transfer

The relevant account effects, financial transaction, ledger entries, branch movements and idempotency result are committed atomically where applicable.

### Money request acceptance

The payment and removal/finalization of the accepted money request occur in the same database transaction.

### Manager replacement

The current branch manager and corresponding assignment history are updated atomically.

### Loan disbursement

Loan disbursement records and associated financial movements are committed together according to the loan workflow.

The exact transaction boundary is determined by the invariant being protected, not simply by the number of service methods involved.

---

# 10. Idempotency and Retry Safety

Critical operations must be safe against duplicate requests and repeated execution.

The architecture uses two levels:

```text
Request
  │
  ├── Redis early duplicate check where useful
  │
  ▼
PostgreSQL IdempotencyRecord
  │
  ▼
Final business operation
```

Redis can reduce unnecessary repeated processing, but PostgreSQL is the final guard.

This is especially important for:

- Financial operations.
- Scheduled payments.
- Scheduled state transitions.
- Notification processing where duplicate external delivery must be controlled.
- Other critical APIs defined by the API contract.

A scheduler retry must never blindly perform the operation again. It must first verify the current database state and idempotency information.

---

# 11. Concurrency and Consistency

The application must assume that two requests can attempt to change the same business object at the same time.

Examples include:

- Two transfers from the same account.
- Two users or processes attempting to accept the same money request.
- Two managers attempting conflicting approval actions.
- A scheduled job running while a user cancels the related request.
- A branch manager being replaced while another branch operation is in progress.

Concurrency protection is therefore implemented through a combination of:

- Database transactions.
- Appropriate row locking or optimistic concurrency where justified.
- Unique constraints.
- Partial unique indexes for active-request rules where suitable.
- Idempotency records.
- Current-state validation immediately before the protected update.

The database is the final consistency boundary.

---

# 12. Scheduled Job Architecture

Scheduled jobs are triggered externally rather than by a permanently running Spring process.

```text
EventBridge Scheduler
        │
        ▼
Backend invocation
        │
        ▼
Read current state from PostgreSQL
        │
        ├── no longer applicable → stop safely
        │
        └── applicable
               │
               ▼
        idempotent operation
               │
               ▼
          PostgreSQL commit
```

Important scheduled operations include:

- Suspension reactivation.
- Account closure completion.
- Manager deactivation after the configured grace period.
- Scheduled UPI payment execution.
- Cleanup jobs for temporary records.

The scheduler itself is not treated as the source of truth. If a schedule fires late, twice or after a related request has been cancelled, the backend must safely evaluate the current database state.

---

# 13. Notification Architecture

Notifications are split into a logical notification and channel-specific delivery records.

```text
Business event
      │
      ▼
Notification
      │
      ├── Email NotificationDelivery
      │
      └── Telegram NotificationDelivery
```

Delivery is asynchronous.

The financial operation must not wait for external notification delivery to succeed.

A notification delivery can therefore move independently through states such as pending, sent or failed without changing the financial result that originally caused the notification.

Adding another channel later should be possible by adding another delivery path rather than adding fixed status columns to the main notification table.

---

# 14. Authentication and Authorization Architecture

Authentication identifies the user. Authorization determines what the authenticated user is allowed to do.

The architecture follows:

```text
Request
  │
  ▼
Authentication
  │
  ▼
Authenticated identity + role
  │
  ▼
Authorization
  │
  ▼
Business operation
```

Authorization is enforced on the backend.

Role checks alone are not always sufficient. Some operations also depend on:

- Customer ownership.
- Branch ownership/work scope.
- Account state.
- Manager assignment.
- Request state.
- Product eligibility.
- Financial rules.

The frontend may hide unauthorized controls, but this is only a usability measure and never a security boundary.

Session management uses a two-token stateless JWT model:

- **Access Token:** 5-minute JWT kept in frontend memory and sent via `Authorization: Bearer <access-token>`.
- **Refresh Token:** 2-hour JWT kept in an `HttpOnly` session cookie (deleted when the browser window closes) containing `userId` and a unique `tokenId`. The cookie is host-only (no `Domain` attribute), `Secure`, `HttpOnly`, uses `Path=/api/v1/auth`, and omits `Max-Age` and `Expires`.
- **Redis Accelerator:** Redis records used and revoked refresh `tokenId` values for 2 hours to enforce Refresh Token Rotation and logout revocation without database lookups. If Redis is unreachable, signature verification of the refresh JWT proceeds with a logged warning so availability is preserved.

---

# 15. OTP Architecture

OTP challenges are durable in PostgreSQL while Redis provides the active fast path.

```text
OTP generation
      │
      ├── PostgreSQL challenge
      │
      └── Redis active OTP
```

On verification:

```text
Verification request
      │
      ▼
Redis active challenge
      │
      ├── valid → continue
      │
      └── invalid/expired → reject
```

The durable PostgreSQL challenge remains the authoritative challenge record and is cleaned up according to the OTP lifecycle.

Resending an OTP replaces the previous active challenge rather than creating multiple simultaneously valid challenges for the same protected operation.

OTP values must never be written to application logs.

The backend decides whether a protected operation requires OTP verification, including when a transfer exceeds the active Admin-configured OTP threshold. If a verified `verificationChallengeId` is not supplied, the backend creates and sends a purpose-bound challenge and returns the required challenge state. The frontend only presents the verification flow and resubmits the operation after verification; it does not make the security decision.

---

# 16. API and Domain Boundaries

The backend should expose domain-oriented APIs rather than exposing database tables directly.

For example:

```text
Frontend
   │
   ▼
Account API
   │
   ▼
Account application/service logic
   │
   ▼
Account repository
   │
   ▼
PostgreSQL
```

The frontend should not know how a transfer is represented internally across:

- FinancialTransaction
- TransactionAccountEffect
- LedgerEntry
- BranchFinancialMovement

It should receive a stable API response that represents the business result.

Similarly, database schema changes should not automatically become API contract changes unless the externally visible behaviour actually changes.

Detailed endpoint definitions belong in the API contract documentation rather than this architecture document.

---

# 17. Security Architecture

Security is treated as a backend responsibility first.

Core principles:

- HTTPS is used for client-to-backend communication.
- Passwords are stored using strong adaptive hashing.
- Authentication credentials and tokens are not logged.
- OTP values are not logged.
- Card secrets are protected at rest.
- CVV is not stored.
- Card PINs are never stored in plaintext.
- Backend authorization is mandatory for protected operations.
- Customer ownership and branch scope are verified server-side.
- Input validation is performed on the backend even when the frontend validates the same input.
- Sensitive information is not returned unnecessarily through APIs.
- Audit records are separate from normal application logs.

The project does not claim PCI compliance or real banking regulatory compliance.

---

# 18. Logging and Audit Architecture

Application logging and audit logging have different purposes.

```text
Application activity
       │
       ├── CloudWatch application logs
       │
       └── AuditLog for security/business history
```

### Application logs

Used for:

- Debugging.
- Operational investigation.
- Error diagnosis.
- Performance investigation.

### Audit logs

Used for:

- Important administrative actions.
- Sensitive business changes.
- Approval/rejection actions.
- Configuration changes.
- Other auditable operations.

Audit records use a generic `entity_type + entity_id` reference so one audit table can cover different domain objects without creating many nullable foreign keys.

Sensitive values should not be copied into logs merely because they are available to the application.

---

# 19. Failure Isolation

A failure in one supporting service should not unnecessarily bring down the entire system.

Examples:

| Component | Failure behaviour |
|---|---|
| Redis | Cache miss/fallback where possible; financial truth remains in PostgreSQL |
| R2 | Profile-picture/object operation may fail; unrelated APIs continue |
| Email provider | Email delivery retries through SQS; financial operation remains successful |
| Telegram provider | Telegram delivery retries through SQS; other channels continue |
| Notification queue | Financial operation remains independent; failed messages move through retry/DLQ handling |
| EventBridge invocation | Scheduled operation can retry safely because database state is authoritative |
| Frontend | Backend remains authoritative and independently deployable |

PostgreSQL failure is different because PostgreSQL is the system's business and financial source of truth. Operations requiring it should fail rather than silently invent or approximate financial state.

---

# 20. Data Consistency Rules

The following boundaries are fixed architectural rules:

```text
PostgreSQL
└── Business and financial truth

Redis
└── Cache / rate limit / short-lived accelerator

R2
└── Binary objects

Frontend
└── Presentation and client-side state
```

No supporting service may silently become an alternate source of truth.

In particular:

- Account balances are not authoritative in Redis.
- Transaction history is not authoritative in the frontend.
- Financial records are not reconstructed from notification records.
- Scheduled jobs do not become authoritative because EventBridge has a schedule.
- Object storage does not become the source of customer/profile business data.

---

# 21. Deployment Architecture

The frontend and backend are deployed independently. The Vercel rewrite forwards `/api/:path*` to API Gateway so the browser uses a same-origin API path. The backend uses Spring's framework forwarded-header handling only behind the configured trusted proxy path; it must not blindly trust arbitrary client-supplied `X-Forwarded-For` values. Rate limiting combines an authenticated-user key with a client IP derived only from a trusted forwarding chain, and direct API Gateway access must not bypass equivalent protection. The initial Spring configuration uses `server.forward-headers-strategy: framework`; the forwarding chain must be configured so untrusted direct client headers are not accepted as authoritative.

```text
Frontend repository
        │
        ▼
      Vercel

Backend repository
        │
        ▼
AWS deployment
 ├── API Gateway HTTP API
 ├── Lambda
 ├── EventBridge Scheduler
 ├── SNS
 ├── SQS + DLQs
 └── CloudWatch

External managed services
 ├── Neon PostgreSQL
 ├── Upstash Redis
 └── Cloudflare R2
```

The backend repository is the canonical location for system-level documentation.

The frontend repository should contain focused frontend documentation rather than maintaining a second complete copy of backend architecture and requirements.

---

# 22. Environment Separation

The project should keep deployment configuration separate from application code.

Environment-specific values include things such as:

- Database connection details.
- Redis connection details.
- R2 credentials/configuration.
- Notification provider credentials.
- JWT/authentication configuration.
- AWS resource identifiers.
- External service endpoints.

Secrets must not be committed to Git.

The exact V1 environment strategy can remain simple because this is a solo project. A practical setup is to maintain separate development and production configuration values without introducing a large configuration-management platform unless the project later needs it.

---

# 23. Cost and Resource Strategy

The architecture intentionally favours managed/serverless services because the project should remain close to zero cost while learning useful production concepts.

The main cost-control principles are:

- Use Lambda instead of a continuously running backend server.
- Use API Gateway HTTP API rather than a more expensive or feature-heavy gateway configuration.
- Keep PostgreSQL usage within the selected Neon plan limits.
- Keep Redis usage within the selected Upstash limits.
- Keep R2 usage limited to required binary objects.
- Use SQS/SNS for small asynchronous workloads instead of maintaining worker servers.
- Use EventBridge Scheduler instead of a continuously running scheduler.
- Avoid adding infrastructure merely for theoretical scalability.

Free-tier and pricing limits should be verified against current provider pricing before public deployment because provider plans can change.

AWS billing alerts/budgets should be configured before production-style public usage.

---

## 23.1 Runtime and Performance Considerations

The serverless runtime introduces a few practical considerations that should influence implementation without changing the business architecture.

### Cold starts

Spring Boot on Lambda can have a startup cost when a new execution environment is created. The project should first measure actual behaviour rather than introducing native compilation or other optimization infrastructure prematurely.

### Request duration

Synchronous APIs should perform only the work required to produce their response. Long-running or retryable external work should use the asynchronous architecture where appropriate.

### Database access

Database queries should be kept focused and indexed according to real access patterns. Core transactional writes use Spring Data JPA, while multi-filter history searches, statement generation reads and cross-table summary views use Spring JDBC DTO projections to keep memory consumption and query execution fast inside Lambda. A Lambda-based backend does not remove the need for careful database design.

### Caching

Redis should be introduced only for reads or short-lived operations where repeated database access creates a real benefit. Cache invalidation must never be allowed to corrupt authoritative data.

---

# 24. Scaling Approach

The system uses services that can scale without requiring the application to maintain its own server fleet.

The first scaling priority is correctness, not maximum throughput.

If usage grows, scaling should happen in this order:

1. Identify the actual bottleneck.
2. Measure database, Lambda and external-service behaviour.
3. Optimize the relevant query or operation.
4. Add a cache only where repeated reads justify it.
5. Adjust service capacity/limits where necessary.
6. Introduce additional architecture only when a real problem requires it.

The project does not introduce microservices, Kubernetes, Kafka, DynamoDB or other infrastructure simply because they are common in large systems.

The V1 backend remains one modular Spring Boot application.

---

# 25. Why This Is a Modular Monolith

Although the application is deployed as a Lambda-based serverless backend, the business system remains one modular backend application.

Conceptually it contains modules such as:

```text
Backend
├── Identity / Authentication
├── Customer
├── Accounts
├── Transfers / Payments
├── UPI
├── Cards
├── Loans
├── Branches
├── Manager Operations
├── Admin Operations
├── Notifications
├── Statements
├── Audit
└── Configuration
```

These modules communicate inside the same application and share the PostgreSQL database through clearly defined domain responsibilities.

This is preferred over microservices for V1 because:

- The project is maintained by one developer.
- The domain is already large enough without adding deployment complexity.
- Financial consistency is easier to manage inside one application and database.
- Separate services would add operational overhead without a clear benefit at the expected scale.

The code should still maintain module boundaries so individual areas remain understandable.

---

# 26. Architectural Decisions and Explicit Non-Goals

The following choices are intentional.

| Decision | Reason |
|---|---|
| PostgreSQL as source of truth | Strong relational consistency and suitable financial data model |
| Hybrid Spring Data JPA + Spring JDBC | JPA handles transactional writes, locking and entity state cleanly; Spring JDBC handles dynamic multi-filter searches, cross-table read models and bulk statement reads without Criteria API complexity or persistence-context memory overhead |
| Lambda backend | Low operational overhead and suitable for a low-cost project |
| API Gateway HTTP API | Simple public HTTP boundary |
| Redis as accelerator | Useful for caching, rate limiting and OTP without becoming financial truth |
| EventBridge Scheduler | External reliable scheduling trigger without a permanent process |
| SNS + SQS | Decoupled notification delivery and retry handling |
| Separate notification queues | Failure isolation between delivery channels |
| R2 for limited objects | Simple low-cost object storage |
| CloudWatch for logs | Native AWS operational visibility |
| Modular monolith | Appropriate complexity for a solo developer and this project scope |
| No DynamoDB in V1 | PostgreSQL already satisfies the domain's data requirements |
| No Kubernetes in V1 | Unnecessary operational complexity |
| No Kafka in V1 | Notification/event volume does not justify it |
| No GraalVM Native Image in V1 | Not required for the initial architecture |
| No microservices in V1 | Would add complexity without a current business need |

These decisions can be revisited if actual project requirements change, but technology trends alone are not a reason to replace them.

---

# 27. Important Architectural Invariants

The following rules must remain true across the implementation:

1. PostgreSQL is the source of truth for business and financial state.
2. Redis must never become the financial source of truth.
3. Frontend validation never replaces backend validation.
4. Frontend role checks never replace backend authorization.
5. Finalized financial transactions are immutable.
6. Financial corrections use compensating events rather than editing history.
7. Double-entry ledger records must remain balanced.
8. Critical financial operations must be idempotent.
9. Scheduled operations must be safe to retry.
10. Notification delivery must not determine financial success.
11. Audit logging is separate from application logging.
12. Permanent financial history is not removed by normal operational cleanup.
13. Temporary workflow data must follow its defined lifecycle.
14. Supporting-service failures must not silently create incorrect financial state.
15. Database transaction boundaries must protect the business invariant they are intended to protect.

---

# 28. Architecture Boundary

This document defines the system architecture and major component responsibilities.

It does not define every API endpoint, database column or UI component.

Those details belong in their respective documents:

```text
requirements.md
    └── What the system must do

architecture.md
    └── How the major system components work together

database.md
    └── How persistent data is structured

api.md
  └── How clients communicate with the backend

```

---

# 29. Final Architecture

The V1 system can be summarized as:

```text
                         ┌───────────────┐
                         │     User      │
                         └───────┬───────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │          Vercel         │
                    │ React / React Router    │
                    │ Redux / Axios           │
                    │ Tailwind CSS            │
                    └────────────┬────────────┘
                                │ HTTPS
                                ▼
                         ┌───────────────┐
                         │ API Gateway   │
                         │   HTTP API    │
                         └───────┬───────┘
                                 │
                                 ▼
                    ┌─────────────────────────┐
                    │ AWS Lambda              │
                    │ Java 25 / Spring Boot  │
                    │ Modular Monolith        │
                    └───────┬─────────┬───────┘
                            │         │
                 ┌──────────┘         └───────────┐
                 ▼                                ▼
       ┌──────────────────┐              ┌──────────────────┐
       │ Neon PostgreSQL  │              │ Upstash Redis    │
       │ Source of Truth │              │ JWT / OTP / cache│
       └──────────────────┘              └──────────────────┘

       Scheduled work                         Notifications
            │                                      │
            ▼                                      ▼
    ┌─────────────────┐                    ┌──────────────┐
    │ EventBridge     │                    │     SNS      │
    │ Scheduler       │                    └──────┬───────┘
    └────────┬────────┘                           │
             │                            ┌──────┴──────┐
             ▼                            ▼             ▼
         Backend                    Email SQS     Telegram SQS
                                          │             │
                                          ▼             ▼
                                       Provider      Provider

       ┌──────────────────┐              ┌──────────────────┐
       │ Cloudflare R2    │              │ AWS CloudWatch  │
       │ Photos / Statements│              │ Application Logs│
       └──────────────────┘              └──────────────────┘
```

The architecture keeps the financial core simple and strongly controlled while using managed services for the supporting concerns that benefit from them.

The most important design principle is that infrastructure should support the business model rather than define it: PostgreSQL protects the financial state, the Spring Boot backend enforces the rules, and supporting services handle caching, scheduling, notifications, objects and operational visibility without becoming alternate sources of truth.