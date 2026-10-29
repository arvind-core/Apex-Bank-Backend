# API Architecture and Contract Design

This document defines the V1 HTTP API architecture for the simulated banking system.

The API is the contract between the separate frontend and backend applications. It describes how clients identify resources, authenticate requests, submit business operations, receive results, handle errors and work with paginated data.

The design follows the project requirements and database model. It is intentionally practical: the API should be consistent and safe without introducing unnecessary API gateways, service layers or distributed patterns.

---

# 1. Purpose

The API must provide a stable boundary between the frontend and backend.

The frontend should not need to know how the backend stores data internally. The backend should expose business-oriented resources and operations rather than database tables.

The API must therefore define:

- HTTP method
- Path
- Authentication
- Authorization
- Headers
- Request body/query parameters/path parameters
- Validation rules
- Business rules
- Response body
- HTTP status code
- Error format
- Pagination and filtering where applicable
- Idempotency behaviour for critical operations
- Business state transitions where relevant

The backend remains authoritative for every protected operation.

---

# 2. API Boundary

The public API follows this path:

```text
Frontend
   ↓ HTTPS
API Gateway HTTP API
   ↓
AWS Lambda
   ↓
Spring Boot application
   ↓
Application services
   ↓
PostgreSQL / supporting services
```

The frontend never calls PostgreSQL, Redis, R2 or other backend infrastructure directly.

---

# 3. API Versioning

V1 endpoints use the prefix:

```text
/api/v1
```

Example:

```text
GET /api/v1/accounts
```

The version is explicit in the path so future breaking API changes can be introduced without silently changing the meaning of existing endpoints.

V1 does not attempt to support multiple versions unless a real breaking-change requirement appears.

---

# 4. Content Type

Requests containing JSON use:

```http
Content-Type: application/json
```

Successful JSON responses use:

```http
Content-Type: application/json
```

Binary/file responses are used only where an endpoint explicitly requires them.

---

# 5. Authentication

Protected endpoints use an authenticated user context via a short-lived JWT access token (5-minute expiry) kept in frontend memory:

```http
Authorization: Bearer <access-token>
```

Session renewal uses a 2-hour stateless JWT refresh token (containing `userId` and a unique `tokenId`) stored in an `HttpOnly` session cookie (without `Max-Age` or `Expires`, so it is deleted when the browser window closes). The cookie is host-only, omits the `Domain` attribute, uses `Path=/api/v1/auth`, and is marked `Secure` and `HttpOnly` with `SameSite=Lax` for the same-origin Vercel rewrite.

Redis tracks rotated (`token:used:{tokenId}`) and logged-out (`token:revoked:{tokenId}`) refresh tokens for 2 hours. If Redis is temporarily unavailable, the backend logs a warning and follows the documented refresh-token failure policy. Client IP rate limits use forwarded headers only when derived from the configured trusted Vercel proxy path; arbitrary client-supplied forwarding headers are not trusted.

Authentication establishes the current user. Authorization decides whether that user may perform the requested operation.

Authentication must not be treated as permission by itself.

---

# 6. Authorization

Authorization is checked by the backend for every protected operation.

The main roles are:

```text
CUSTOMER
MANAGER
ADMIN
```

Role checks alone are not sufficient when ownership or branch scope also matters.

Examples:

- Customer can access only their own customer resources and eligible accounts.
- Manager can access customer/work data only within an authorized branch context.
- Admin can access bank-wide administrative resources but cannot use Admin access to act as an ordinary customer or directly edit an individual customer's profile unless a separate capability is explicitly defined.

The frontend may hide unauthorized actions, but that is only a user-interface convenience.

---

# 7. Resource Naming

Resource paths use nouns and plural resource names where practical.

Examples:

```text
/accounts
/accounts/{accountId}
/accounts/{accountId}/transactions
/upis
/money-requests
/loans
/credit-cards
/branches
```

HTTP methods express the operation instead of putting verbs into paths in most cases.

Examples:

```text
GET    /accounts/{accountId}
POST   /accounts
PATCH  /accounts/{accountId}
DELETE /beneficiaries/{beneficiaryId}
```

Business actions that represent an explicit state transition or cannot be expressed clearly as normal CRUD can use action-style endpoints.

Examples:

```text
POST /accounts/{accountId}/suspend
POST /accounts/{accountId}/reactivate
POST /account-closure-requests/{requestId}/approve
POST /money-requests/{requestId}/accept
```

This keeps normal resource manipulation separate from important business transitions.

---

# 8. Public IDs

API responses should expose stable resource identifiers without exposing database implementation details.

The public API should not require clients to understand database primary-key strategy.

A resource identifier should remain stable for the life of that resource.

Account numbers are a separate business identifier and remain immutable according to the banking requirements.

---

# 9. HTTP Methods

The normal V1 mapping is:

| Method | Main use |
|---|---|
| GET | Read resources or search/list resources |
| POST | Create a resource or execute a business action |
| PATCH | Partial update of mutable resource data |
| PUT | Full replacement only where the operation genuinely represents replacement |
| DELETE | Remove temporary/user-owned resources where the requirements permit deletion |

Financial history and finalized financial records are not updated or deleted through normal APIs.

When a financial correction is needed, the backend creates a new compensating/reversal event instead of editing the old record.

---

# 10. Common Success Status Codes

The API uses status codes to communicate the outcome clearly.

| Status | Use |
|---|---|
| 200 OK | Successful read or synchronous successful operation |
| 201 Created | A new resource was successfully created |
| 202 Accepted | The request was accepted but final processing is still pending/asynchronous |
| 204 No Content | Successful operation with no response body |

A pending business workflow should not automatically be reported as `200 OK` merely because the HTTP request itself was received successfully.

For example, an operation that creates a durable pending job may return `202 Accepted` when the final result is not yet known.

A normal request resource whose creation itself completed successfully can still return `201 Created` with a business state such as `PENDING`.

---

# 11. Common Error Status Codes

The V1 API uses status codes according to the actual failure.

| Status | Typical meaning |
|---|---|
| 400 | Malformed or invalid request syntax/data |
| 401 | Authentication required or authentication failed |
| 403 | Authenticated user is not allowed to perform the operation |
| 404 | Resource does not exist or is not visible to the caller |
| 409 | Request conflicts with the current resource/business state |
| 422 | Request is structurally valid but violates business validation |
| 429 | Rate limit exceeded |
| 500 | Unexpected server failure |
| 503 | Required service is temporarily unavailable |

The final endpoint contract may narrow these meanings where a more precise response is useful.

The API should not return `200 OK` for a failed operation merely to simplify frontend handling.

---

# 12. Error Response Format

Errors use one consistent JSON structure.

Baseline shape:

```json
{
  "code": "ACCOUNT_SUSPENSION_NOT_ALLOWED",
  "message": "The account cannot be suspended while an incompatible scheduled payment exists.",
  "details": null,
  "fieldErrors": [],
  "traceId": "..."
}
```

Fields:

- `code` — stable machine-readable application error code.
- `message` — safe user-facing explanation.
- `details` — optional additional structured information.
- `fieldErrors` — optional validation errors for individual request fields.
- `traceId` — request/correlation identifier useful for support and CloudWatch investigation.

Internal stack traces, SQL errors, provider credentials and other internal implementation details must never be returned to clients.

### Field validation example

```json
{
  "code": "VALIDATION_FAILED",
  "message": "One or more fields are invalid.",
  "details": null,
  "fieldErrors": [
    {
      "field": "amount",
      "code": "MUST_BE_POSITIVE",
      "message": "Amount must be greater than zero."
    }
  ],
  "traceId": "..."
}
```

---

# 13. Response Design

Responses expose business data through DTOs rather than database entities.

The API should return only fields that the client needs.

Database-only fields such as internal audit metadata, password hashes, provider secrets or internal implementation identifiers must not appear in normal customer responses.

The same resource should use consistent field names across related endpoints.

For example, an account response should not call the same concept `accountNo` in one endpoint and `number` in another.

JSON field naming follows `camelCase`.

Example:

```json
{
  "accountId": "...",
  "accountNumber": "...",
  "accountType": "SAVINGS",
  "status": "ACTIVE",
  "balance": 25000.00
}
```

All monetary values in the system are in `INR` and are serialized in JSON as plain decimal numbers (mapped to `BigDecimal` in the Java backend). The frontend formats and displays the `INR` currency symbol.

---

# 14. Pagination

List endpoints that can return many records use a consistent pagination model.

V1 uses page-based pagination for normal list/search endpoints because it is straightforward for the frontend and sufficient for the project's expected scale.

Baseline query parameters:

```text
page=0
pageSize=20
```

The backend enforces a maximum page size.

Baseline response shape:

```json
{
  "items": [],
  "page": 0,
  "pageSize": 20,
  "totalElements": 120,
  "totalPages": 6
}
```

Large or high-volume financial history endpoints should be evaluated separately if the real workload ever makes cursor pagination preferable. V1 does not introduce cursor pagination without a practical reason.

---

# 15. Filtering and Sorting

Filtering uses query parameters rather than separate endpoints for every combination.

Example:

```text
GET /api/v1/accounts/{accountId}/transactions?page=0&pageSize=20&direction=CREDIT&from=2026-10-01&to=2026-10-05
```

Filters must be explicitly supported by the endpoint contract. Unsupported query parameters should not silently change business behaviour.

Sorting should have a documented default order.

Transaction history defaults to newest-first ordering unless an endpoint contract defines another order.

---

# 16. Idempotency

Critical state-changing operations use the `Idempotency-Key` header where applicable.

Example:

```http
Idempotency-Key: 0f4f...
```

Typical operations include:

- Financial transfers
- Loan applications
- Credit-card applications
- Account closure requests
- Branch-transfer requests
- Funding requests
- Money requests
- Other critical state-changing operations

The idempotency record is ultimately protected by PostgreSQL. Redis may provide an early duplicate check, but Redis is not the final guard.

The same authenticated user, operation scope and idempotency key must resolve to the same logical request rather than creating a second business effect.

The exact key scope, retention period and response-replay behaviour will be finalized in the individual endpoint contracts.

---

# 17. Concurrency and State Transitions

Important business operations are represented as explicit state transitions.

Examples:

```text
SCHEDULED → PROCESSING → SUCCESS / FAILED

PENDING → PAID / DECLINED / CANCELLED

REQUESTED → APPROVED / REJECTED / CANCELLED
```

The backend must validate the current state before applying a transition.

Concurrent requests must not be able to apply two incompatible terminal transitions.

Where necessary, the backend uses database constraints, transactions and appropriate row/version protection.

The API should communicate a state conflict as a meaningful error rather than pretending the operation succeeded.

A typical example is returning `409 Conflict` when another request has already completed the state transition.

---

# 18. Business Status vs HTTP Status

HTTP status and business status represent different things.

Example:

```text
HTTP 201 Created
business status = PENDING
```

means the request resource was successfully created and currently waits for later action.

Another example:

```text
HTTP 202 Accepted
business status = PROCESSING
```

means the API accepted work whose final result is not yet known.

The frontend must not infer business state from the HTTP status alone.

---

# 19. Branch Context

Manager APIs are branch-scoped.

When a Manager manages multiple branches, the API must receive enough context to identify the selected branch safely.

The frontend may remember the selected branch, but the backend verifies that the Manager is currently authorized for that branch.

A Manager must never be able to change the selected branch identifier to obtain access to an unrelated branch.

A closed/decommissioned branch is not valid for normal new operational work.

Existing branch work remains owned by the branch and can be handled by its replacement Manager according to the requirements.

---

# 20. Financial API Rules

Financial APIs have stricter guarantees than ordinary CRUD APIs.

For a financial operation, the backend must coordinate as one logical unit where required:

```text
Validate
   ↓
Authorize
   ↓
Check limits / state
   ↓
Acquire required concurrency protection
   ↓
Change balances / financial positions
   ↓
Create financial transaction
   ↓
Create accounting ledger entries
   ↓
Create required branch movement
   ↓
Persist idempotency result
   ↓
Commit
```

Non-financial side effects such as email or Telegram delivery must not be allowed to roll back a successfully committed financial operation.

The exact transaction boundary is defined by the database and financial design.

---

# 21. Notification API Boundary

Financial operations create the required notification records only after the business result is known or within the defined application workflow.

External notification delivery is asynchronous.

The delivery architecture is:

```text
Backend
  ↓
Notification
  ↓
SNS
  ↓
Channel-specific SQS queue
  ↓
External provider
```

Notification delivery failure does not reverse a completed financial operation.

The API does not wait for an email or Telegram provider to confirm delivery before declaring a financial operation successful.

---

# 22. Authentication / OTP API Boundary

OTP-related endpoints are security-sensitive.

They must enforce:

- Purpose-bound challenges
- Expiry
- Attempt limits
- Resend cooldowns
- Rate limiting
- Strong authentication rules where required

The durable OTP challenge is stored in PostgreSQL, with Redis used as the active fast path.

The API must not expose the generated OTP value in normal production responses.

The frontend never decides independently whether OTP verification is required. When an authenticated operation requires a second factor (either by fixed policy or because a transaction amount exceeds the Admin-configured OTP threshold) and no verified `verificationChallengeId` is supplied, the backend automatically creates and sends a purpose-bound OTP challenge and responds with `202 Accepted` (`status = "SECOND_FACTOR_REQUIRED"`, `challengeId` and `expiresAt`). The client verifies the OTP via the challenge verification endpoint and then submits the operation with `verificationChallengeId`.

---

# 23. File and Statement API Boundary

Profile pictures are binary objects stored through the backend/R2 path.

The frontend should not treat R2 as the business data source.

Statements are generated through a temporary workflow.

Generated PDF/CSV files are stored temporarily in Cloudflare R2 under the dedicated `statements/` folder, separate from `profile-photos/`, and are automatically deleted 24 hours after generation. When a statement becomes `READY`, Redis stores the R2 file address and the customer's 24-hour request cooldown with a 24-hour TTL starting from `readyAt`; PostgreSQL retains `ready_at`, `expires_at` and `r2_object_key` as the fallback source of truth.

The API contract should distinguish between:

- requesting/generating a statement,
- checking statement request status,
- obtaining a temporary generated file where applicable.

---

# 24. Initial API Resource Groups

The V1 API is organized around the business domains rather than database tables.

```text
Authentication
├── registration initiation/completion
├── login
├── second-factor verification
├── refresh access token
├── logout
├── purpose-bound OTP challenge verification/resend
├── forgot user ID
├── forgot password
└── password change

Customer
├── profile
├── primary email change
├── profile photo
├── customer Pending Requests
├── preferences
├── notifications
└── statements

Accounts
├── account creation
├── account details
├── account list
├── suspension
├── reactivation
├── closure requests
└── branch transfer

Transactions
├── account + IFSC transfer
├── UPI transfer
├── phone-number payment
├── own-account transfer
├── recipient verification
└── transaction history/details

UPI
├── list UPI IDs
├── create custom UPI
├── delete custom UPI
├── default phone UPI mapping
└── UPI PIN operations

Beneficiaries
├── list
├── create
└── delete

Money Requests
├── create
├── list
├── accept
├── decline
└── cancel

Scheduled Payments
├── create
├── list
└── cancel

Debit Cards
├── view
├── issuance/status
└── card-related account operations

Credit Cards
├── application
├── details
├── simulated purchases and purchase history
├── payments
├── automatic payment account
├── billing cycles
├── rewards
└── limit request

Loans
├── available loan products
├── pre-disbursement destination account
├── applications
├── loan details
├── EMI schedule
├── EMI payment
└── early closure / prepayment

Manager
├── branch context
├── assigned-branch listing
├── customer search
├── customer details
├── approval queues
├── account closure review
├── loan review
├── credit-card review
├── card-limit review
├── branch-transfer review
├── funding requests
└── branch financial views

Admin
├── branches
├── loan products
├── managers
├── central ledger
├── branch funding
├── bank-wide financial dashboard
├── configuration
└── audit/export operations
```

This inventory is the starting point for the detailed endpoint contracts.

---

# 25. Endpoint Contract Format

Every detailed endpoint in V1 should be documented using the following structure:

```text
Endpoint
Method
Path
Purpose
Authentication
Authorization
Required headers
Path parameters
Query parameters
Request body
Validation rules
Business rules
Success response
Success HTTP status
Business status
Possible errors
Pagination/filtering
Idempotency
Concurrency/state transition
Side effects
```

Not every endpoint needs every section. Sections that do not apply should be omitted rather than filled with meaningless text.

---

# 26. API Design Principles

The V1 API follows these practical rules:

1. Business rules belong in the backend, not the frontend.
2. API responses expose DTOs, not database entities.
3. HTTP status codes communicate the actual HTTP result.
4. Business status is communicated explicitly when an operation has a workflow state.
5. Critical mutations are protected against duplicate submission.
6. Financial operations are transactionally consistent.
7. Permanent financial history is immutable.
8. Errors use stable machine-readable codes.
9. Pagination and filtering are consistent across list endpoints.
10. Branch authorization is enforced on every Manager request.
11. External notification delivery is asynchronous and never becomes the source of financial truth.
12. Supporting services can fail without corrupting PostgreSQL business state.
13. The API remains understandable to a solo developer and avoids unnecessary abstraction.

---

# 27. Detailed Contract Work

The sections below define the concrete endpoint contracts in the following domain order:

```text
Authentication
    ↓
Customer/profile
    ↓
Accounts
    ↓
Transactions and UPI
    ↓
Beneficiaries and money requests
    ↓
Scheduled payments
    ↓
Cards
    ↓
Loans / EMI
    ↓
Manager APIs
    ↓
Admin APIs
    ↓
Statements / notifications / audit
```

The exact paths, request DTOs, response DTOs, status mappings and error codes will be finalized domain-by-domain using the current requirements and database model.

---

# 28. Authentication API Contracts

Authentication is a separate API concern from customer profile and banking operations.

The authentication API identifies the user and completes the required authentication steps. After authentication succeeds, normal protected APIs use the authenticated user context.


## 28.1 Login

```http
POST /api/v1/auth/login
```

Purpose:

Start authentication using the user's login ID and password.

Authentication:

Public endpoint.

Request:

```json
{
  "loginId": "customer123",
  "password": "..."
}
```

Validation:

- `loginId` is required.
- `password` is required.
- Request fields must satisfy the authentication input format.
- The endpoint is rate-limited.

Behaviour:

1. Locate the authentication identity without revealing whether an account exists.
2. Verify the password.
3. Apply login security rules and temporary blocking where configured.
4. If the required second authentication step is pending, create a purpose-bound challenge.
5. Do not issue a fully authenticated access token before the required authentication flow is complete.

Invalid credentials:

```http
401 Unauthorized
```

The response should use a generic authentication error and should not reveal whether the login ID exists.

Second factor required:

```http
200 OK
```

Example:

```json
{
  "status": "SECOND_FACTOR_REQUIRED",
  "challengeId": "...",
  "expiresAt": "2026-10-06T18:30:00Z"
}
```

The challenge identifier is not an authentication token by itself.

Possible errors include:

- `AUTHENTICATION_FAILED`
- `AUTHENTICATION_TEMPORARILY_BLOCKED`
- `RATE_LIMIT_EXCEEDED`
- `VALIDATION_FAILED`

## 28.2 Verify Second Factor

```http
POST /api/v1/auth/verify-second-factor
```

Purpose:

Complete an authentication flow that is waiting for the configured second factor.

Authentication:

Public, but requires a valid pending authentication challenge.

Request:

```json
{
  "challengeId": "...",
  "code": "123456"
}
```

Validation:

- Challenge must exist.
- Challenge must be for an authentication purpose.
- Challenge must not be expired.
- Maximum attempts must not be exceeded.
- Challenge must belong to the authentication flow being completed.

Success:

```http
200 OK
```

Example:

```json
{
  "status": "AUTHENTICATED",
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-06T18:35:00Z",
  "user": {
    "userId": "...",
    "role": "CUSTOMER"
  }
}
```

The OTP/code itself is never returned. On successful authentication, the backend issues a 5-minute access token and sets the 2-hour JWT refresh token in an `HttpOnly` session cookie.

Possible errors include:

- `INVALID_CHALLENGE`
- `CHALLENGE_EXPIRED`
- `INVALID_OTP`
- `OTP_ATTEMPTS_EXCEEDED`
- `RATE_LIMIT_EXCEEDED`

## 28.3 Current Authenticated User

```http
GET /api/v1/auth/me
```

Purpose:

Return the authenticated user's basic identity and role information used by the frontend to initialise the application experience.

Authentication:

Required.

Success:

```http
200 OK
```

Example:

```json
{
  "userId": "...",
  "role": "CUSTOMER",
  "status": "ACTIVE"
}
```

The endpoint should not become a replacement for customer, manager or admin domain APIs. It returns authentication-context information only.

## 28.4 Forgot User ID

```http
POST /api/v1/auth/forgot-user-id
```

Purpose:

Start the strong-verification flow required to recover a customer's User ID.

Authentication:

Public.

Request:

```json
{
  "email": "customer@example.com"
}
```

The exact verification mechanism remains part of the security design, but the workflow must use the approved strong-authentication channel.

Response:

```http
202 Accepted
```

The response should not reveal whether the email belongs to a registered customer. If the email is unknown, the backend persists a synthetic `FORGOT_USER_ID` challenge with no `user_account_id` and an unmatchable random code hash. Verification and resend responses follow the same public contract as a real challenge and must not reveal account existence. A successful real `FORGOT_USER_ID` verification consumes the challenge and initiates asynchronous email delivery of the customer's login ID; a synthetic challenge produces no email.

Example:

```json
{
  "status": "VERIFICATION_REQUIRED",
  "challengeId": "...",
  "expiresAt": "..."
}
```

After successful verification, the required User ID information is delivered through the approved communication flow.

## 28.5 Forgot Password

```http
POST /api/v1/auth/forgot-password
```

Purpose:

Start the strong-verification password-recovery flow.

Authentication:

Public.

Request:

```json
{
  "loginId": "customer123"
}
```

Response:

```http
202 Accepted
```

The response should avoid account-enumeration leaks. If the login ID is unknown, the backend persists a synthetic `FORGOT_PASSWORD` challenge with no `user_account_id` and an unmatchable random code hash. Verification and resend responses remain consistent with real challenges, and the synthetic challenge never results in a password-reset operation.

Example:

```json
{
  "status": "VERIFICATION_REQUIRED",
  "challengeId": "...",
  "expiresAt": "..."
}
```

## 28.6 Reset Password

```http
POST /api/v1/auth/reset-password
```

Purpose:

Set a new password after the password-recovery verification flow has successfully completed.

Authentication:

Requires a valid password-reset challenge/token produced by the recovery workflow.

Request:

```json
{
  "challengeId": "...",
  "newPassword": "..."
}
```

Validation:

- Challenge must be valid and intended for password reset.
- Challenge must not be expired or already consumed.
- New password must satisfy the product password policy.

Success:

```http
204 No Content
```

A successful password reset ends the recovery flow and does not automatically log the customer in.

Any previous authentication state that must be invalidated is handled by the security implementation.

Possible errors include:

- `INVALID_CHALLENGE`
- `CHALLENGE_EXPIRED`
- `PASSWORD_POLICY_VIOLATION`
- `CHALLENGE_ALREADY_CONSUMED`

## 28.7 Change Password

```http
POST /api/v1/auth/change-password
```

Purpose:

Change the password for an already authenticated user.

Authentication:

Required.

Request:

```json
{
  "currentPassword": "...",
  "newPassword": "...",
  "verificationChallengeId": "..."
}
```

The second-factor requirement must use a purpose-bound challenge according to the active security policy. The API must not treat the presence of a client-supplied challenge ID as proof of successful verification.

Success:

```http
204 No Content
```

Possible errors include:

- `CURRENT_PASSWORD_INVALID`
- `SECOND_FACTOR_REQUIRED`
- `INVALID_CHALLENGE`
- `PASSWORD_POLICY_VIOLATION`
- `RATE_LIMIT_EXCEEDED`

## 28.8 Refresh Access Token

```http
POST /api/v1/auth/refresh
```

Purpose:

Issue a new 5-minute access token and rotate the 2-hour refresh token using the `HttpOnly` refresh token cookie.

Authentication:

Requires a valid `HttpOnly` refresh token cookie.

Behaviour:

- Decode and verify the refresh token JWT signature and expiration.
- Check Redis to ensure `tokenId` is neither used nor revoked. If Redis is unreachable, log a warning and proceed using the verified JWT signature.
- Mark the current `tokenId` as used in Redis (`token:used:{tokenId} = true`) for its remaining TTL.
- Issue a new 5-minute access token in the response body and set a new 2-hour `HttpOnly` refresh token cookie.

Success:

```http
200 OK
```

```json
{
  "accessToken": "...",
  "tokenType": "Bearer",
  "expiresAt": "2026-10-06T18:35:00Z"
}
```

## 28.9 Logout

```http
POST /api/v1/auth/logout
```

Purpose:

Revoke the active refresh token and clear the `HttpOnly` refresh token cookie.

Authentication:

Authenticated user or valid refresh cookie.

Behaviour:

- Extract `tokenId` from the refresh token JWT and store `token:revoked:{tokenId} = true` in Redis for its remaining 2-hour lifetime, catching and logging any Redis connectivity error without failing the logout response.
- Clear the `HttpOnly` refresh token cookie.

Success:

```http
204 No Content
```

## 28.10 Verify Purpose-Bound OTP Challenge

```http
POST /api/v1/auth/challenges/{challengeId}/verify
```

Purpose:

Verify an OTP code for a purpose-bound challenge triggered by the backend. For most protected operations, the client then submits the verified `challengeId` as `verificationChallengeId` to the protected operation, which consumes it in the same database transaction as the authorized state change. `FORGOT_USER_ID` is consumed by this verification flow and triggers the asynchronous recovery email; `CUSTOMER_EMAIL_CHANGE_CURRENT` starts the second OTP stage by creating a new challenge for the requested email.

Authentication:

Required for authenticated operations; the challenge must belong to the authenticated user. Public authentication flows continue to use their specific authentication challenge contract.

Request:

```json
{
  "code": "123456"
}
```

Success:

```http
200 OK
```

```json
{
  "challengeId": "...",
  "purpose": "UPI_TRANSFER",
  "status": "VERIFIED",
  "expiresAt": "2026-10-06T18:35:00Z"
}
```

The protected operation must still validate the challenge owner, purpose, `VERIFIED` state, short consumption window, request fingerprint where applicable and expiry. It marks the challenge `CONSUMED` in the same PostgreSQL transaction as the protected change. Merely supplying a challenge identifier does not prove that verification succeeded.

## 28.11 Resend OTP Challenge

```http
POST /api/v1/auth/challenges/{challengeId}/resend
```

Purpose:

Resend an OTP for a pending challenge after the configured resend cooldown. If the same user/purpose has a pending challenge inside the cooldown window, the backend returns the existing challenge metadata rather than rotating the code. Once resend is permitted, the PostgreSQL challenge and Redis active-OTP value are updated consistently. A `VERIFIED` challenge is not replaced by a resend.

Authentication:

Required for authenticated operations; the challenge must belong to the authenticated user. Public authentication flows continue to use their specific authentication challenge contract.

Success:

```http
200 OK
```

```json
{
  "challengeId": "...",
  "status": "RESENT",
  "expiresAt": "2026-10-06T18:35:00Z"
}
```

---

## 28.12 Customer Registration

Customer registration uses two public registration endpoints, with the shared OTP challenge-verification endpoint between them. It does not create customer identity rows before OTP verification.

### Start registration

```http
POST /api/v1/auth/register
```

The request includes the proposed `loginId`, password, full name, date of birth, primary email, required simulated Indian mobile number and required profile fields. The backend normalizes and validates the identifiers, checks current uniqueness, hashes the password with Argon2id, stores the short-lived pending registration snapshot in `otp_challenge.context_payload`, and sends a purpose-bound `REGISTER_CUSTOMER` OTP to the email. It does not reserve the login ID, email or phone number by creating identity rows.

The response is `202 Accepted` with `status = "VERIFICATION_REQUIRED"`, `challengeId` and `expiresAt`.

### Complete registration

```http
POST /api/v1/auth/verify-registration
```

Required header:

```http
Idempotency-Key: <stable-key-for-this-registration-completion>
```

Request:

```json
{
  "verificationChallengeId": "..."
}
```

The customer first verifies the `REGISTER_CUSTOMER` OTP through `POST /api/v1/auth/challenges/{challengeId}/verify`. That endpoint validates the challenge purpose and expiry and transitions it from `PENDING` to `VERIFIED` for the short consumption window; it does not create the customer identity rows or consume the challenge. The client then submits `POST /api/v1/auth/verify-registration` with the verified challenge ID and an `Idempotency-Key`. This endpoint rechecks login ID, case-insensitive email and phone uniqueness under database constraints, and atomically creates `user_account`, `customer`, `customer_preferences`, initial `trust_score`, initial `trust_score_history`, and the reserved `DEFAULT_PHONE` UPI record. The same transaction marks the OTP challenge `CONSUMED` and removes its registration context. A retry with the same idempotency key returns the same registration result without creating another identity.

Success:

```http
201 Created
```

The response confirms registration but does not issue a logged-in session. The customer must log in normally. Repeating completion for the same idempotency context must not create duplicate identity rows.

Possible errors include `INVALID_CHALLENGE`, `CHALLENGE_EXPIRED`, `CHALLENGE_ALREADY_CONSUMED`, `LOGIN_ID_ALREADY_IN_USE`, `EMAIL_ALREADY_IN_USE`, `MOBILE_NUMBER_ALREADY_IN_USE`, `VALIDATION_FAILED` and `IDEMPOTENCY_CONFLICT`.

# 29. Authentication Authorization Rules

Authentication identifies the caller. Authorization applies additional checks.

The minimum authorization layers are:

```text
Authenticated user
        ↓
Role permission
        ↓
Resource ownership / branch scope
        ↓
Current resource state
        ↓
Business rule
```

Examples:

| Operation | Required role | Additional scope |
|---|---|---|
| Customer account read | CUSTOMER | Account belongs to customer |
| Customer transfer | CUSTOMER | Source account belongs to customer and is eligible |
| Customer closure request | CUSTOMER | Account belongs to customer |
| Manager loan review | MANAGER | Loan/account is within selected managed branch scope |
| Manager account closure approval | MANAGER | Account belongs to selected branch |
| Admin branch management | ADMIN | Bank-wide admin permission |
| Admin central-fund injection | ADMIN | Bank-wide admin permission + required verification |

The backend must derive ownership and branch authority from trusted server-side data. A client cannot establish permission by submitting another customer's ID, account ID or branch ID.

---

# 30. Authentication Security Rules

The following are API-level requirements rather than implementation-specific decisions:

- Authentication endpoints are rate-limited.
- OTP endpoints are rate-limited.
- OTPs are never logged.
- Passwords are never logged.
- Tokens and secrets are never written to normal application logs.
- Authentication errors should avoid unnecessary account enumeration.
- Sensitive authentication operations require purpose-bound verification.
- Authentication challenges expire and cannot be reused after consumption.
- Password reset does not automatically authenticate the user.
- Backend authorization remains authoritative even when the frontend hides an action.
- Security-sensitive operations should carry the request/correlation ID through application logs without exposing secret values.

---

# 31. Authentication Idempotency and Retry Behaviour

Authentication initiation is not treated like a financial mutation.

Therefore normal login requests do not require an `Idempotency-Key`.

OTP verification is naturally retry-sensitive because an incorrect retry must not create a new authentication result. The OTP challenge itself tracks attempts and consumption state.

Password reset should consume its reset challenge exactly once.

Retrying the same completed reset challenge must fail safely rather than changing the password a second time.

---

# 32. Authentication State Flow

The baseline authentication flow is:

```text
                 ┌──────────────────┐
                 │ POST /auth/login │
                 └────────┬─────────┘
                          │
                    credentials
                          │
                  ┌───────▼────────┐
                  │ Verify password │
                  └───────┬────────┘
                          │
                second factor needed?
                    ┌─────┴─────┐
                   yes           no*
                    │             │
          ┌─────────▼─────────┐   │
          │ Create challenge  │   │
          └─────────┬─────────┘   │
                    │             │
          POST /verify-second    │
                    │             │
          ┌─────────▼─────────┐   │
          │ Verify challenge  │   │
          └─────────┬─────────┘   │
                    └──────┬──────┘
                           ▼
                    Authenticated
                           │
                    Access credential
```

`no*` is only applicable where the active security policy genuinely does not require the second authentication step for that particular flow. The final policy is defined by the security design.

Recovery flow:

```text
Forgot user ID / forgot password
              ↓
     Verification challenge
              ↓
       Strong verification
              ↓
      Recovery action/result
```

---

# 33. Customer and Account APIs

This section defines the V1 contract for customer self-service and account management.

The main rule is that the customer API exposes customer-owned operations directly only where the requirements allow direct action. Sensitive profile changes use a separate approval workflow and must not be treated as ordinary `PATCH` operations.

Customer resources are always resolved from the authenticated user. The client must not be able to select an arbitrary `customerId` to change or read another customer's data.

---

## 33.1 Current Customer Profile

The response includes profile-photo availability and a short-lived display URL when a custom profile photo exists.

```http
GET /api/v1/customers/me
```

Purpose:

Return the authenticated customer's current profile and the customer-level information needed by the frontend.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Success:

```http
200 OK
```

Baseline response:

```json
{
  "customerId": "...",
  "userId": "customer123",
  "fullName": "...",
  "dateOfBirth": "1999-01-01",
  "email": "customer@example.com",
  "mobileNumber": "9876543210",
  "address": {
    "line1": "...",
    "line2": "...",
    "city": "...",
    "state": "...",
    "postalCode": "...",
    "country": "IN"
  },
  "nickname": "...",
  "telegram": {
    "linked": true
  },
  "profilePhoto": {
    "available": true
  }
}
```

The response may be extended with other customer-owned display information later, but it must not expose sensitive internal authentication fields.

The simulated mobile number is returned because it is part of the customer's UPI addressing information. It must not be reused by the frontend for login, authentication or security communication.

---

## 33.2 Direct Customer Profile Update

```http
PATCH /api/v1/customers/me
```

Purpose:

Update profile fields that the current requirements permit the customer to change directly without Manager approval.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Important rule:

This endpoint must not become a general-purpose customer-profile editor. Fields classified as legal, KYC or otherwise sensitive must use the profile-change approval workflow instead.

At the current baseline, `nickname` is explicitly a customer-controlled display preference and is a direct-update candidate.

Example request:

```json
{
  "nickname": "Arvind"
}
```

Success:

```http
200 OK
```

Example response:

```json
{
  "customerId": "...",
  "nickname": "Arvind"
}
```

Validation:

- Unknown fields must not be silently accepted.
- Read-only fields such as `customerId` and `userId` must be rejected.
- Sensitive fields must be rejected from this endpoint rather than silently applying a different workflow.

Possible errors:

- `VALIDATION_FAILED`
- `FIELD_NOT_EDITABLE_DIRECTLY`
- `FORBIDDEN`

---

## 33.3 Customer Profile Change Request

```http
POST /api/v1/customer-profile-change-requests
```

Purpose:

Create a Manager-reviewed request for a sensitive customer profile change.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Idempotency:

Required.

The request is a workflow resource, not a direct profile update. The `branchId` identifies the branch whose Manager reviews the request and must match one of the customer's selected active account branches.

Baseline request shape:

```json
{
  "branchId": "...",
  "changes": {
    "fullName": "New Legal Name"
  },
  "reason": "Legal name update"
}
```

The exact sensitive-field list must follow the finalized profile/KYC rules. The API must reject fields that are not supported by this workflow.

Success:

```http
201 Created
```

Example response:

```json
{
  "requestId": "...",
  "status": "PENDING",
  "createdAt": "...",
  "validTill": "...",
  "requestedChanges": {
    "fullName": "New Legal Name"
  }
}
```

The request does not change the live customer profile until the required Manager workflow completes successfully.

Possible errors:

- `VALIDATION_FAILED`
- `REQUEST_NOT_ALLOWED_IN_CURRENT_STATE`
- `IDEMPOTENCY_CONFLICT`

### Get customer profile-change request

```http
GET /api/v1/customer-profile-change-requests/{requestId}
```

Authentication:

Required.

Authorization:

`CUSTOMER` only; the request must belong to the authenticated customer.

The response includes the request status, requested changes, reason, review branch, `createdAt`, `validTill` where applicable and Manager decision information that is safe for the customer to view. The current customer profile is returned separately and is not changed by reading this request.

Success:

```http
200 OK
```

Customer request cancellation follows the request lifecycle defined for sensitive profile changes. Approval/rejection remains a Manager operation.

---

### Cancel customer profile-change request

```http
DELETE /api/v1/customer-profile-change-requests/{requestId}
```

The authenticated customer can cancel only their own profile-change request while it remains `PENDING` and before a Manager decision. Cancellation removes the temporary request; it does not alter the current customer profile or delete audit records. The UI must ask the customer to confirm before sending this request.

Success:

```http
204 No Content
```

Possible errors include `PROFILE_CHANGE_REQUEST_NOT_FOUND`, `PROFILE_CHANGE_REQUEST_NOT_CANCELLABLE` and `REQUEST_STATE_CONFLICT`.

---

## 33.4 Mobile Number Change

```http
POST /api/v1/customers/me/mobile-number/change
```

Purpose:

Replace the customer's simulated Indian mobile number used for UPI payment addressing.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Strong authentication:

Required through the approved non-SMS verification flow.

Idempotency:

Required because the operation changes identity/addressing data and may remove future scheduled payments tied to the old default UPI.

Request:

```json
{
  "mobileNumber": "9123456789",
  "verificationChallengeId": "...",
  "confirmScheduledPaymentDeletion": true
}
```

Validation:

- Exactly 10 digits.
- First digit must be 6, 7, 8 or 9.
- Number must be unique among customers.
- Phone number is required and cannot be set to `NULL` or removed.
- The verification challenge must have purpose `CUSTOMER_PHONE_NUMBER_CHANGE`, belong to the customer, be `VERIFIED` and remain inside its short consumption window.

Business behaviour:

1. The current mobile-number/default-UPI relationship is identified.
2. The customer is warned that future scheduled payments using the old source/default UPI will be deleted according to the product rules.
3. The change is applied only after the required confirmation is present.
4. In one PostgreSQL transaction, affected customer-owned source scheduled-payment rows are deleted, the old default phone UPI row is deleted, the customer's phone number is updated, the new phone-derived default UPI row is created with the previous eligible receiving-account mapping, and the verified challenge is consumed.
5. After commit, the backend requests removal of external EventBridge schedules corresponding to deleted rows. A delayed removal is harmless because each scheduled invocation rechecks PostgreSQL state before processing.
6. Other customers' scheduled payments addressed to the old phone/UPI are not deleted from those senders' records. At execution, receiver identity and current account mapping must match the saved values; otherwise the payment fails and a notification is queued for the sender.

If confirmation has not been supplied and affected scheduled payments exist, the operation must not partially update the phone number.

Possible errors:

- `INVALID_MOBILE_NUMBER`
- `MOBILE_NUMBER_ALREADY_IN_USE`
- `STRONG_AUTHENTICATION_REQUIRED`
- `SCHEDULED_PAYMENT_CONFIRMATION_REQUIRED`
- `VERIFICATION_FAILED`
- `CONCURRENT_PROFILE_CHANGE`

The mobile number is never used as a login identifier or OTP-delivery address. It is required for every customer and cannot be removed independently; a customer changes it through the verified mobile-number change operation.

---

## 33.5 Primary Email Change

### Start primary email change

```http
POST /api/v1/customers/me/email/change
```

Request:

```json
{
  "newEmail": "new-address@example.com"
}
```

The backend normalizes the new email and checks case-insensitive uniqueness before starting the flow. It sends a purpose-bound `CUSTOMER_EMAIL_CHANGE_CURRENT` OTP to the current verified email and stores the normalized new email in the short-lived challenge context. The current profile email does not change at this stage.

The public/current-user response returns `202 Accepted`, `status = "VERIFICATION_REQUIRED"`, `challengeId` and `expiresAt`.

When the customer verifies this first challenge through `POST /api/v1/auth/challenges/{challengeId}/verify`, the backend consumes the current-email challenge and creates a second purpose-bound challenge sent to the new email. The verification response identifies the new challenge and states that verification of the new email is required. The second challenge is marked `VERIFIED` through the same challenge verification endpoint.

### Complete primary email change

```http
PUT /api/v1/customers/me/email
```

Request:

```json
{
  "email": "new-address@example.com",
  "verificationChallengeId": "..."
}
```

The backend verifies the authenticated customer, purpose `CUSTOMER_EMAIL_CHANGE_NEW`, verified state, short consumption window and exact normalized target email. It rechecks case-insensitive uniqueness and atomically updates the primary email and consumes the challenge. The email is not changed if any check fails. The new email is used for subsequent OTP and critical communication; the old email is released after the transaction commits.

Possible errors include `EMAIL_ALREADY_IN_USE`, `INVALID_CHALLENGE`, `CHALLENGE_EXPIRED`, `CHALLENGE_ALREADY_CONSUMED`, `EMAIL_VERIFICATION_REQUIRED` and `CONCURRENT_PROFILE_CHANGE`.

---

## 33.6 Telegram Link

```http
POST /api/v1/customers/me/telegram-link
```

Purpose:

Start the optional Telegram account-linking flow after required email-based OTP step-up verification.

Authentication:

Required.

Authorization:

`CUSTOMER` only. If a Telegram account is already linked, the customer must unlink it before starting a new linkage.

The first request without a verified challenge returns `202 Accepted` with `status = "SECOND_FACTOR_REQUIRED"`, `challengeId` and `expiresAt`. The backend reuses the same active pending challenge within the resend cooldown. After the customer verifies the email OTP and resubmits the request with the `verificationChallengeId`, the backend creates or reuses the customer's 30-minute Telegram link challenge.

Success:

```http
201 Created
```

Example:

```json
{
  "botLink": "https://t.me/bank_bot?start=BANK_<secure-token>_TG",
  "expiresAt": "...",
  "status": "PENDING"
}
```

The deep-link start parameter contains only a cryptographically secure random token with a fixed bank prefix/suffix and remains within Telegram's 64-character limit. The raw token is short-lived and must not be logged. PostgreSQL stores its hash and expiration; Redis caches the token-to-customer mapping and a per-customer active-token lock for 30 minutes. A repeated request reuses the token while that cache is available. If Redis has lost the raw token, the backend expires the previous unmatched challenge and issues a new one because the raw token cannot be recovered from its hash.

The frontend explains that the customer must open the bank bot link and press **Start**, then return to the banking UI and select Verify. The bot's `/start` update does not link the Telegram account by itself.

## 33.6.1 Verify Telegram Link

```http
POST /api/v1/customers/me/telegram-link/verify
```

Purpose:

Process the shared Telegram update queue and finish the current customer's linking flow after the user has pressed **Start** in the bank bot.

Authentication:

Required; the customer identity is taken from the verified JWT and is never accepted as a query parameter.

The endpoint first checks whether the current customer's active challenge has a durably matched chat ID. If not, it attempts to acquire the global database-backed poll lease and processes up to the configured batch/time budget using Telegram `getUpdates?limit=100&offset=<next_offset>` when a checkpoint exists. The first poll omits `offset`. Only updates whose private-chat `/start` parameter exactly matches an active, unexpired bank-issued challenge are retained. Only the first valid match can set a pending challenge's matched chat ID; later `/start` messages cannot replace it. Ordinary text and unknown, malformed or expired tokens are ignored.

Each processed batch stores challenge matches and `next_offset = highest_update_id + 1` in the same PostgreSQL transaction. A subsequent Telegram poll with the next offset acknowledges previously persisted updates. The checkpoint in PostgreSQL is authoritative. Redis caches the checkpoint and matched-chat result only after commit; if Redis is missing or stale, the backend recovers from PostgreSQL. The backend never uses `offset=-1` for checkpoint recovery.

After a match is found for the authenticated customer's active challenge, the backend rechecks ownership, expiry and the uniqueness of the Telegram chat, consumes the challenge and updates `customer.telegram_chat_id` in one database transaction.

Success:

```http
200 OK
```

```json
{
  "status": "LINKED",
  "telegram": {
    "linked": true
  }
}
```

If no match is available or the invocation reaches its batch/time budget while updates remain, return `202 Accepted` with `status = "PENDING"` and instruct the frontend that the customer may select Verify again. If another invocation holds the global polling lease, return the same pending result rather than competing for the shared Telegram queue.

Possible errors include `TELEGRAM_LINK_NOT_FOUND`, `TELEGRAM_LINK_EXPIRED`, `TELEGRAM_ACCOUNT_ALREADY_LINKED`, `TELEGRAM_CHAT_ALREADY_LINKED`, `TELEGRAM_POLL_TEMPORARILY_UNAVAILABLE` and `CONCURRENT_TELEGRAM_POLL`.

---


## 33.7 Telegram Unlink

```http
POST /api/v1/customers/me/telegram-link/unlink
```

Purpose:

Remove the customer's optional Telegram linkage so another Telegram account can be linked later.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Request:

```json
{
  "verificationChallengeId": "..."
}
```

The operation requires an email-based OTP challenge with purpose `TELEGRAM_UNLINK`. If no verified challenge is supplied, the backend returns `202 Accepted` with `status = "SECOND_FACTOR_REQUIRED"`. After OTP verification, the endpoint consumes the challenge and removes the current `telegram_chat_id` atomically. The customer can then start a separate Telegram-link request.

Success:

```http
204 No Content
```

Unlinking Telegram must not disable or break critical security communication because email remains the approved critical channel.

---

## 33.8 Customer Preferences

```http
GET /api/v1/customers/me/preferences
```

```http
PATCH /api/v1/customers/me/preferences
```

Purpose:

Read or update non-critical customer notification and display preferences.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

`GET` success:

```http
200 OK
```

`PATCH` success:

```http
200 OK
```

Example:

```json
{
  "marketingEmailEnabled": false,
  "nonCriticalTelegramEnabled": true
}
```

Critical security and financial notifications are not customer-disableable and must not be accepted as writable fields.

Possible errors:

- `VALIDATION_FAILED`
- `PREFERENCE_NOT_EDITABLE`

---

## 33.8.1 Customer Pending Requests

```http
GET /api/v1/customers/me/pending-requests
```

Purpose:

Return an aggregated, paginated view of the authenticated customer's active workflow requests for the Pending Requests section. It is a read model over the existing request resources (queried directly as a read-only Spring JDBC projection across the active request tables) and does not create a second source of truth.

The collection can include eligible active account-closure and branch-transfer requests, customer profile-change requests, loan applications and offers in any nonterminal review/customer-action state, credit-card applications and limit requests in any nonterminal state, money requests and scheduled payments. Each item identifies its `requestType` and `requestId`, displays the current status, `createdAt`, `validTill` where applicable, a concise summary and `cancellationAllowed`.

Only requests owned by the authenticated customer are returned. The detail view uses the existing type-specific GET endpoint, including the customer-owned profile-change request detail endpoint. Cancellation continues to use the existing type-specific endpoint; the frontend displays a confirmation dialog first and sends no cancellation request until the customer confirms. Requests in a non-cancellable state remain visible but do not display a cancel control.

Success:

```http
200 OK
```

The response uses the common page-based pagination model. Supported filters include `requestType`, `page`, `pageSize` and `sort`.

---

## 33.9 Profile Photo APIs

Profile photos are optional customer profile images. They are stored in Cloudflare R2 through the backend, while PostgreSQL stores the active object reference and relevant metadata.

The frontend never uploads directly to R2 and never receives R2 credentials.

### 33.9.1 Upload profile photo

```http
POST /api/v1/customers/me/profile-photo
```

Authentication:

- CUSTOMER

Content type:

```http
Content-Type: multipart/form-data
```

The customer uses this endpoint only when no current custom profile photo exists. The frontend rejects files larger than 2 MB (2,097,152 bytes) before upload.

The backend independently enforces the same 2 MB file-size limit and validates the actual image content, not only the filename or declared MIME type. Only JPEG, PNG and WEBP are accepted. The server's multipart/request limit allows for multipart overhead but the image file itself must not exceed 2,097,152 bytes. The backend stores the binary object in R2 only after validation and records the active object reference/metadata in PostgreSQL.

If a custom profile photo already exists, the request is rejected and the customer uses the change endpoint instead.

Success:

```text
201 Created
```

The response returns the photo metadata and a short-lived display URL/reference where needed. Raw R2 object keys and credentials are never exposed.

### 33.9.2 Change profile photo

```http
PUT /api/v1/customers/me/profile-photo
```

Authentication:

- CUSTOMER

Content type:

```http
Content-Type: multipart/form-data
```

The frontend and backend enforce the same 2 MB (2,097,152-byte) maximum and JPEG/PNG/WEBP content rules for replacement images. The backend validates the actual content before storing the new image, switches the customer's active PostgreSQL photo reference to the new object, and then removes the old R2 object as a separate cleanup step where possible. An invalid/oversized upload must not replace or delete the existing photo.

If old-object deletion fails, the profile must still reference only the new photo. The old object must not become an additional active profile image.

Success:

```text
200 OK
```

### 33.9.3 Remove profile photo

```http
DELETE /api/v1/customers/me/profile-photo
```

Authentication:

- CUSTOMER

The backend removes the active photo reference from PostgreSQL and deletes the corresponding R2 object where possible. The customer profile then falls back to the default avatar.

Success:

```text
204 No Content
```

Calling this operation when no custom photo exists is safe.

### 33.9.4 Profile photo in the customer profile

The existing:

```http
GET /api/v1/customers/me
```

response includes a compact photo representation when a photo exists:

```json
{
  "profilePhoto": {
    "available": true,
    "url": "<short-lived-display-url>",
    "updatedAt": "2026-10-06T12:00:00Z"
  }
}
```

The API must not expose a permanent public R2 object URL or raw storage key.

### 33.9.5 Profile photo in phone-recipient verification

The existing phone-recipient verification API can include the recipient's optional profile photo after successful phone-number resolution.

The photo is a presentation-only identity aid. It does not authorize the payment.

Example response:

```json
{
  "transferMethod": "PHONE",
  "verified": true,
  "recipient": {
    "phoneNumber": "9876543210",
    "accountHolderName": "Example Customer",
    "profilePhoto": {
      "available": true,
      "url": "<short-lived-view-url>",
      "expiresAt": "2026-10-06T12:05:00Z"
    }
  }
}
```

When the recipient has no profile photo, `available` is `false` and no URL is returned.

The short-lived URL lets the customer view the photo when they choose to open it. The API does not create a permanent public customer-photo URL.

R2 failure during optional photo retrieval must not make an otherwise valid recipient verification fail. In that situation the photo may be omitted or returned as unavailable while recipient verification continues normally.

# 34. Account Metadata APIs

The account type and branch lists used during account creation are backend-driven.

The frontend must not hard-code available account types or branch choices.

## 34.1 Available Account Types

```http
GET /api/v1/account-types
```

Purpose:

Return account types currently available for customer account creation.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Success:

```http
200 OK
```

Example:

```json
{
  "items": [
    {
      "code": "SAVINGS",
      "name": "Savings Account",
      "available": true
    },
    {
      "code": "CURRENT",
      "name": "Current Account",
      "available": true
    }
  ]
}
```

The backend determines availability and eligibility. The frontend only displays the returned choices.

## 34.2 Active Branches for Account Creation

```http
GET /api/v1/branches?status=ACTIVE
```

Purpose:

Return active branches that may be selected during account creation.

Authentication:

Required.

Authorization:

`CUSTOMER` only for this customer-facing use case.

Success:

```http
200 OK
```

Example item:

```json
{
  "branchId": "...",
  "branchCode": "BR001",
  "name": "Indore Main Branch",
  "city": "Indore",
  "state": "Madhya Pradesh",
  "active": true
}
```

The branch list is reference data. Detailed Manager branch operations use separate Manager APIs.

---

# 35. Customer Account List

```http
GET /api/v1/accounts
```

Purpose:

Return all accounts owned by the authenticated customer that are visible under the customer account rules.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Query parameters:

```text
status=ACTIVE
accountType=SAVINGS
page=0
pageSize=20
```

All filters are optional. The backend validates supported values.

Success:

```http
200 OK
```

Example:

```json
{
  "items": [
    {
      "accountId": "...",
      "accountNumber": "123456789012",
      "accountType": "SAVINGS",
      "status": "ACTIVE",
      "branch": {
        "branchId": "...",
        "branchCode": "BR001",
        "name": "Indore Main Branch"
      },
      "balance": 25000.00,
      "isPrimaryForDefaultPhoneUpi": true
    }
  ],
  "page": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

The `isPrimaryForDefaultPhoneUpi` flag is only a convenience representation of the default phone UPI mapping. It does not give the account any additional business privileges.

---

# 36. Get Account Details

```http
GET /api/v1/accounts/{accountId}
```

Purpose:

Return the current details of one customer-owned account.

Authentication:

Required.

Authorization:

`CUSTOMER` only, and the account must belong to the authenticated customer.

Success:

```http
200 OK
```

The response includes, as applicable:

- Account ID
- Account number
- Account type
- Account status
- Current branch
- Current operational balance
- Default phone-UPI primary indicator
- Relevant suspension end time
- Relevant closure cooling end time
- Relevant lifecycle/request information needed by the customer UI

The API must not expose another customer's balance or account information.

Not found or not visible:

```http
404 Not Found
```

The backend may intentionally use the same externally visible response for a non-existent resource and an account not owned by the caller to avoid revealing resource existence where appropriate.

---

# 37. Create Account

```http
POST /api/v1/accounts
```

Purpose:

Create a new customer account and perform the configured initial funding operation.

Authentication:

Required.

Authorization:

`CUSTOMER` only.

Idempotency:

Required.

Request:

```json
{
  "accountType": "SAVINGS",
  "branchId": "..."
}
```

Validation:

- `accountType` must be returned by the backend as an available type.
- `branchId` must identify an active eligible branch.
- The customer must not already have an account of the requested type unless the previous account has reached final `CLOSED` state.
- The customer must satisfy any additional account-eligibility rules enforced by the backend.

Business transaction:

Account creation and initial funding must be one logical financial/database operation.

The backend:

1. Validates the account type and branch.
2. Checks the one-account-per-type rule under concurrency protection.
3. Generates the immutable account number.
4. Creates the account.
5. Applies the current account-type-specific initial funding configuration.
6. Records the financial transaction.
7. Creates the required accounting ledger entries.
8. Updates the required branch/central financial positions.
9. Applies the default phone UPI mapping when this is the customer's first account.
10. Commits the complete operation atomically.

If any required part fails, the operation must not leave a partially created funded account.

Success:

```http
201 Created
```

Example response:

```json
{
  "accountId": "...",
  "accountNumber": "123456789012",
  "accountType": "SAVINGS",
  "status": "ACTIVE",
  "branch": {
    "branchId": "...",
    "branchCode": "BR001",
    "name": "Indore Main Branch"
  },
  "balance": 10000.00,
  "isPrimaryForDefaultPhoneUpi": true
}
```

Possible errors:

- `ACCOUNT_TYPE_UNAVAILABLE`
- `ACCOUNT_TYPE_ALREADY_EXISTS`
- `BRANCH_NOT_AVAILABLE`
- `ACCOUNT_CREATION_NOT_ALLOWED`
- `INSUFFICIENT_CENTRAL_LEDGER_FUNDS`
- `FINANCIAL_OPERATION_FAILED`
- `IDEMPOTENCY_CONFLICT`
- `CONCURRENT_ACCOUNT_CREATION`

The initial funding amount is determined by the current Admin configuration at creation time and is not retroactively affected by later configuration changes.

---

# 38. Suspend Account

```http
POST /api/v1/accounts/{accountId}/suspend
```

Purpose:

Immediately suspend an eligible account for a customer-selected duration.

Authentication:

Required.

Authorization:

`CUSTOMER` only for the customer self-service endpoint. Manager suspension is a separate Manager API.

Strong authentication:

Required according to the configured security policy.

Idempotency:

Required.

Request:

```json
{
  "durationHours": 72,
  "verificationChallengeId": "..."
}
```

The API may support a predefined-duration identifier instead of `durationHours`; the backend remains responsible for calculating the exact end time.

Validation:

- Account belongs to the customer.
- Account is currently eligible for suspension.
- Account is not already in a state where suspension is invalid.
- Suspension duration is positive.
- Suspension cannot exceed the fixed 90-day maximum.
- Required strong authentication succeeds.
- No incompatible scheduled-payment or automatic credit-card-payment relationship remains unresolved.

Business result:

- Account becomes `SUSPENDED` immediately.
- Exact suspension end time is stored.
- Account remains readable.
- Financial activity and account-specific UPI sending/receiving are blocked while suspended.
- A one-time EventBridge Scheduler schedule is created for reactivation.

Success:

```http
200 OK
```

Example:

```json
{
  "accountId": "...",
  "status": "SUSPENDED",
  "suspendedUntil": "2026-10-09T18:00:00Z"
}
```

Possible errors:

- `ACCOUNT_NOT_ELIGIBLE_FOR_SUSPENSION`
- `SUSPENSION_DURATION_INVALID`
- `SUSPENSION_CONFLICT`
- `STRONG_AUTHENTICATION_REQUIRED`
- `ACCOUNT_STATE_CONFLICT`
- `CONCURRENT_ACCOUNT_UPDATE`

A scheduled reactivation must be harmless if the customer manually reactivates the account earlier.

---

# 39. Reactivate Account

```http
POST /api/v1/accounts/{accountId}/reactivate
```

Purpose:

Reactivate an account that is temporarily suspended or in closure cooling where the requirements permit reactivation.

Authentication:

Required.

Authorization:

`CUSTOMER` only for customer self-service. Manager reactivation is a separate Manager operation.

Strong authentication:

Required for customer reactivation according to the security policy.

Idempotency:

Required.

Request:

```json
{
  "verificationChallengeId": "..."
}
```

Business rules:

- Suspended account: reactivation is allowed before the suspension end time.
- Closure cooling account: reactivation is allowed before cooling ends using the required strong-authentication flow.
- Closed account: reactivation is never allowed.
- If the account is already `ACTIVE`, the API should treat the request as an idempotent/no-op result where appropriate rather than creating another state transition.
- If an old scheduled task remains, it must become harmless.

Success:

```http
200 OK
```

Example:

```json
{
  "accountId": "...",
  "status": "ACTIVE"
}
```

Possible errors:

- `ACCOUNT_NOT_REACTIVATABLE`
- `ACCOUNT_CLOSED`
- `REACTIVATION_WINDOW_EXPIRED`
- `STRONG_AUTHENTICATION_REQUIRED`
- `ACCOUNT_STATE_CONFLICT`

The backend uses the stored lifecycle end time as authoritative state. If a scheduled invocation was delayed, an overdue lifecycle may be reconciled before the operation proceeds.

---

# 40. Account Closure Request APIs

Account closure is a Manager-approved workflow, so creation of the request does not itself close or freeze the account.

## 40.1 Create Closure Request

```http
POST /api/v1/account-closure-requests
```

Request:

```json
{
  "accountId": "...",
  "reason": "No longer needed"
}
```

Authentication:

Required.

Authorization:

`CUSTOMER` only, with ownership validation on `accountId`.

Idempotency:

Required.

Validation/business rules:

- Account must belong to the customer.
- Account must be in an eligible state for closure request creation.
- Required pre-checks include zero balance and other applicable loan, credit-card and financial obligations.
- Only one active closure request is allowed for the account.
- A new request requires the previous request to be resolved or deleted.
- The request receives `validTill` according to the active Admin-configured request validity policy.
- Creating the request does not stop normal account operation.
- Incoming money remains allowed while the request is waiting for Manager action.

Success:

```http
201 Created
```

Example:

```json
{
  "requestId": "...",
  "accountId": "...",
  "status": "PENDING",
  "createdAt": "...",
  "validTill": "..."
}
```

Possible errors:

- `ACCOUNT_NOT_ELIGIBLE_FOR_CLOSURE`
- `ACTIVE_CLOSURE_REQUEST_EXISTS`
- `ACCOUNT_STATE_CONFLICT`
- `REQUEST_VALIDATION_FAILED`
- `IDEMPOTENCY_CONFLICT`

## 40.2 List Customer Closure Requests

```http
GET /api/v1/account-closure-requests
```

Query parameters:

```text
accountId={accountId}
status=PENDING
page=0
pageSize=20
```

Success:

```http
200 OK
```

The endpoint returns requests visible to the authenticated customer only.

## 40.3 Get Closure Request

```http
GET /api/v1/account-closure-requests/{requestId}
```

Authentication:

Required.

Authorization:

`CUSTOMER` only for requests owned by the customer.

Success:

```http
200 OK
```

The response should include the current request state, `validTill`, creation time, approval/rejection information where applicable, and the account reference.

## 40.4 Delete / Cancel Closure Request

```http
DELETE /api/v1/account-closure-requests/{requestId}
```

Purpose:

Delete an active customer closure request while cancellation is still allowed.

Authentication:

Required.

Authorization:

`CUSTOMER` only, and only the request owner may cancel it.

Success:

```http
204 No Content
```

The request is removed rather than changing the account itself.

The endpoint must reject deletion if the request has already entered a non-cancellable later stage.

Possible errors:

- `CLOSURE_REQUEST_NOT_FOUND`
- `CLOSURE_REQUEST_NOT_CANCELLABLE`
- `REQUEST_STATE_CONFLICT`

An automatically expired request is removed by the cleanup process and does not need a persisted `EXPIRED` state.

---

# 41. Account Branch-Transfer Request APIs

Branch transfer is also a workflow resource. The customer requests the transfer, the current branch Manager reviews it, then the destination branch Manager reviews it, and only after both approvals does the scheduled migration proceed.

## 41.1 Create Branch-Transfer Request

```http
POST /api/v1/branch-transfer-requests
```

Request:

```json
{
  "accountId": "...",
  "destinationBranchId": "...",
  "reason": "Moving to another branch"
}
```

Authentication:

Required.

Authorization:

`CUSTOMER` only, with ownership validation on `accountId`.

Idempotency:

Required.

Validation:

- Account belongs to the customer.
- Destination branch exists and is active.
- Destination branch is different from the current branch.
- Account is eligible for branch transfer.
- Only one active branch-transfer request exists for the account.
- The request receives `validTill` from the active Admin-configured branch-transfer validity policy.

Success:

```http
201 Created
```

Example:

```json
{
  "requestId": "...",
  "accountId": "...",
  "currentBranch": {
    "branchId": "...",
    "branchCode": "BR001"
  },
  "destinationBranch": {
    "branchId": "...",
    "branchCode": "BR002"
  },
  "status": "PENDING_CURRENT_BRANCH_APPROVAL",
  "validTill": "..."
}
```

The account number does not change.

## 41.2 List Branch-Transfer Requests

```http
GET /api/v1/branch-transfer-requests
```

Query parameters:

```text
accountId={accountId}
status={status}
page=0
pageSize=20
```

Success:

```http
200 OK
```

Only requests belonging to the authenticated customer are visible through this customer endpoint.

## 41.3 Get Branch-Transfer Request

```http
GET /api/v1/branch-transfer-requests/{requestId}
```

Success:

```http
200 OK
```

The response includes the current workflow status, source/destination branches, approval progress, creation time, `validTill`, scheduled execution information when applicable, and the final result when completed.

## 41.4 Delete / Cancel Branch-Transfer Request

```http
DELETE /api/v1/branch-transfer-requests/{requestId}
```

Purpose:

Cancel/delete the customer request while customer cancellation is still allowed.

Success:

```http
204 No Content
```

Cancellation is not allowed after both required Manager approvals are complete and the transfer has entered the scheduled/non-editable stage.

Possible errors:

- `BRANCH_TRANSFER_NOT_FOUND`
- `BRANCH_TRANSFER_NOT_CANCELLABLE`
- `BRANCH_TRANSFER_STATE_CONFLICT`

If the request reaches `validTill` before the required approvals complete, cleanup removes it. The cleanup operation must not migrate the account.

---

# 42. Customer and Account Authorization Matrix

| Endpoint group | Customer | Manager | Admin |
|---|---:|---:|---:|
| `GET /customers/me` | Yes | No | No |
| `PATCH /customers/me` | Yes | No | No |
| Customer profile-change requests | Own customer | Review through Manager API | No direct edit |
| Customer mobile-number change | Own customer | No | No |
| Customer preferences | Own customer | No | No |
| Account list/details | Own accounts | Branch-scoped Manager API instead | No direct customer operation |
| Account creation | Yes | No | No |
| Customer suspension/reactivation | Own account | Separate Manager operation within branch | No direct customer operation |
| Closure request creation/cancellation | Own account | Review through Manager API | No direct approval by default |
| Branch-transfer request creation/cancellation | Own account | Review through Manager API | No direct approval by default |

Admin bank-wide access does not imply direct customer-service authority. That boundary follows the requirements and should remain visible in the API design.

---

# 43. Customer and Account Error Code Baseline

The following stable machine-readable codes are reserved for these contracts where applicable:

| Code | Meaning |
|---|---|
| `ACCOUNT_TYPE_UNAVAILABLE` | Requested account type cannot currently be created |
| `ACCOUNT_TYPE_ALREADY_EXISTS` | Customer already has an account of the requested type |
| `ACCOUNT_NOT_FOUND` | Account does not exist or is not visible to the caller |
| `ACCOUNT_NOT_ELIGIBLE_FOR_SUSPENSION` | Current account state or rules block suspension |
| `SUSPENSION_DURATION_INVALID` | Requested suspension duration is outside allowed bounds |
| `SUSPENSION_CONFLICT` | Another scheduled/automatic relationship blocks suspension |
| `ACCOUNT_NOT_REACTIVATABLE` | Current account state does not allow reactivation |
| `ACCOUNT_CLOSED` | Final closed state cannot be reactivated |
| `ACTIVE_CLOSURE_REQUEST_EXISTS` | An active closure request already exists |
| `CLOSURE_REQUEST_NOT_CANCELLABLE` | Closure request has reached a non-cancellable stage |
| `ACCOUNT_NOT_ELIGIBLE_FOR_CLOSURE` | Account fails closure pre-checks |
| `ACTIVE_BRANCH_TRANSFER_EXISTS` | An active branch-transfer request already exists |
| `BRANCH_TRANSFER_NOT_CANCELLABLE` | Branch-transfer request has reached a non-cancellable stage |
| `BRANCH_NOT_AVAILABLE` | Branch is missing, inactive or otherwise unavailable |
| `MOBILE_NUMBER_ALREADY_IN_USE` | Simulated customer mobile number is already assigned |
| `INVALID_MOBILE_NUMBER` | Mobile number violates the fixed format |
| `SCHEDULED_PAYMENT_CONFIRMATION_REQUIRED` | Required confirmation is missing before affected schedules are deleted |
| `FIELD_NOT_EDITABLE_DIRECTLY` | Field requires the appropriate approval workflow |
| `STRONG_AUTHENTICATION_REQUIRED` | Additional verification is required |
| `REQUEST_STATE_CONFLICT` | Requested workflow action conflicts with the current state |
| `CONCURRENT_ACCOUNT_UPDATE` | Concurrent update prevented the requested operation from being applied safely |
| `IDEMPOTENCY_CONFLICT` | Reused idempotency key does not match the original request |
| `INSUFFICIENT_CENTRAL_LEDGER_FUNDS` | Required central funding cannot be completed without making the central ledger negative |
| `FINANCIAL_OPERATION_FAILED` | Required financial operation could not be completed safely |

The list is a baseline, not a reason to create a unique error code for every possible sentence. Error codes should represent stable client-relevant conditions.

---

# 44. Customer and Account State Flows

## 44.1 Account lifecycle

```text
                 ┌───────────┐
                 │  ACTIVE   │
                 └─────┬─────┘
                       │
            customer / manager suspension
                       │
                       ▼
                 ┌───────────┐
                 │ SUSPENDED │
                 └─────┬─────┘
                       │
              reactivation / expiry
                       │
                       ▼
                 ┌───────────┐
                 │  ACTIVE   │
                 └───────────┘

ACTIVE
  │
  │ Manager approves closure request
  ▼
CLOSURE_COOLING
  │             │
  │ reactivation│ cooling ends
  ▼             ▼
ACTIVE        CLOSED
```

A closure request waiting for Manager action does not itself change the account lifecycle:

```text
ACTIVE account
      +
PENDING closure request
```

The same principle applies to branch transfer: the account remains in its current branch until the approved migration actually executes.

## 44.2 Closure request lifecycle

```text
PENDING
  ├── CUSTOMER DELETE/CANCEL ──→ removed
  ├── MANAGER REJECT ──────────→ terminal/reconciled
  └── MANAGER APPROVE ─────────→ CLOSURE_COOLING
```

Expired pending requests are removed by cleanup rather than requiring a stored `EXPIRED` state.

## 44.3 Branch-transfer request lifecycle

```text
PENDING_CURRENT_BRANCH_APPROVAL
            │
            ├── reject ──→ rejected
            │
            └── approve
                    ↓
PENDING_DESTINATION_BRANCH_APPROVAL
            │
            ├── reject ──→ rejected
            │
            └── approve
                    ↓
             SCHEDULED
                    │
                    ▼
             MIGRATING/PROCESSING
                    │
             ┌──────┴──────┐
             ▼             ▼
         COMPLETED       FAILED
```

The exact persisted request-state names may be adjusted to match the final implementation enum, but the business transitions above are the required behaviour.

---

# 45. Customer and Account Concurrency Rules

The following operations require database-backed concurrency protection:

### Account creation

Two simultaneous account-creation requests for the same customer and account type must result in at most one account.

The partial unique `(customer_id, account_type)` index for accounts whose status is not `CLOSED` remains the final database protection.

### Suspension/reactivation

A manual reactivation and scheduled automatic reactivation must never create two independent lifecycle transitions.

The stored suspension identity/end time is used so an old scheduled invocation cannot reactivate a newer suspension incorrectly.

### Closure request creation

Two simultaneous closure requests for the same account must result in at most one active request.

The database active-request uniqueness constraint remains the final guard.

### Branch-transfer request creation

Two simultaneous branch-transfer requests for the same account must result in at most one active request.

### Closure approval vs customer cancellation

If Manager approval and customer cancellation race, only one valid terminal transition may succeed. The losing request receives a clear state-conflict response.

### Branch-transfer approval vs cancellation

The same rule applies to the branch-transfer workflow. Once the request reaches the non-cancellable scheduled stage, customer cancellation must fail safely.

### Account operations during lifecycle boundaries

An operation reaching an account after its stored suspension or closure end time must not rely solely on the scheduler having already run. The backend may reconcile the overdue lifecycle before applying the new operation.

---

# 46. Customer and Account Side Effects

The following side effects must remain separate from the core customer API response where they are asynchronous:

- Email notifications
- Telegram notifications
- Notification delivery retries
- Scheduled lifecycle execution
- Scheduled branch migration execution

The API response confirms the business operation or workflow-resource creation, not successful external message delivery.

For account creation specifically, the financial operation itself is synchronous from the database perspective: the API must not report account creation as successful until the account and its required initial funding/financial records have committed successfully.

---

# 47. Transactions and UPI APIs

This section defines the V1 API contract for customer money movement, transaction history and UPI management.

The design follows the financial rules already defined in the project:

- Transfers are simulated inside the bank.
- PostgreSQL is the financial source of truth.
- Finalized `FinancialTransaction` records are permanent and immutable.
- Customer-facing account effects use `DEBIT` and `CREDIT` to describe what happened to that account.
- Accounting debit/credit remains inside the ledger model and is not exposed as the same concept.
- Critical financial requests require idempotency protection.
- Backend validation is authoritative.
- A successful financial operation is not rolled back because notification delivery fails.

The API deliberately does not expose the underlying ledger tables or database transactions directly.

---

# 48. Transfer API Model

The V1 transfer API supports four customer-facing transfer types:

```text
ACCOUNT_IFSC
UPI
PHONE
OWN_ACCOUNT
```

They all follow the same broad business sequence:

```text
Enter transfer details
        ↓
Verify recipient / destination
        ↓
Review
        ↓
Authenticate where required
        ↓
Submit with Idempotency-Key
        ↓
Backend validation
        ↓
Concurrency protection
        ↓
Financial transaction
        ↓
Ledger entries
        ↓
Branch movement where required
        ↓
Commit
```

Frontend validation is only a convenience. The backend repeats all important validation before money movement.

A transfer between the same source and destination account is rejected.

Customer-facing transfer APIs never allow the caller to choose the final transaction status. Status is determined by backend processing.

---

# 49. Recipient Verification

Recipient verification is performed before final transfer submission.

It is a read/verification operation, not a financial operation.

The verification response may be used by the frontend to display the recipient details during the review screen.

The final transfer submission must still revalidate the destination. A previous successful verification must not be treated as permanent authorization or as a guarantee that the destination cannot change.

## 49.1 Verify Account + IFSC Recipient

```http
POST /api/v1/transfers/verify-recipient
```

Request:

```json
{
  "transferMethod": "ACCOUNT_IFSC",
  "accountNumber": "1234567890",
  "ifsc": "BANK0001234"
}
```

The backend verifies that the destination account and supplied IFSC identify a valid recipient inside the simulated bank.

The response contains only the recipient details required for confirmation.

```json
{
  "transferMethod": "ACCOUNT_IFSC",
  "verified": true,
  "recipient": {
    "accountNumber": "1234567890",
    "ifsc": "BANK0001234",
    "accountHolderName": "Example Customer"
  }
}
```

No money is moved by this endpoint.

Typical failures include:

- `400` malformed account/IFSC input
- `404` destination does not exist or is not available
- `422` destination fails business eligibility rules

## 49.2 Verify UPI Recipient

```http
POST /api/v1/transfers/verify-recipient
```

Request:

```json
{
  "transferMethod": "UPI",
  "upiAddress": "alex@bank-domain"
}
```

The backend verifies that the UPI address currently resolves to a valid eligible destination.

A reused or changed UPI string must resolve to the current owner only. Historical payment records are not redirected by a later reuse of a deleted UPI address.

Example response:

```json
{
  "transferMethod": "UPI",
  "verified": true,
  "recipient": {
    "upiAddress": "alex@bank-domain",
    "accountHolderName": "Example Customer"
  }
}
```

The API does not expose the recipient's balance.

## 49.3 Verify Phone Recipient

Phone-number payment uses the customer's default phone UPI mechanism. It is not a separate financial rail.

```http
POST /api/v1/transfers/verify-recipient
```

Request:

```json
{
  "transferMethod": "PHONE",
  "phoneNumber": "9876543210"
}
```

The backend converts the number internally to the customer's default phone UPI address and resolves the current destination.

The generated UPI address is an implementation detail of the flow and does not need to be shown as the user's payment input.

Example response:

```json
{
  "transferMethod": "PHONE",
  "verified": true,
  "recipient": {
    "phoneNumber": "9876543210",
    "accountHolderName": "Example Customer"
  }
}
```

Phone-number verification must not be used for login, OTP delivery or security authentication.

---

# 50. Account + IFSC Transfer

```http
POST /api/v1/transfers/account-ifsc
```

Authentication:

- Authenticated CUSTOMER

Required header:

```http
Idempotency-Key: <unique-key>
```

Request:

```json
{
  "sourceAccountId": "acc-123",
  "destinationAccountNumber": "1234567890",
  "destinationIfsc": "BANK0001234",
  "amount": 5000.00,
  "remark": "Monthly payment",
  "verificationChallengeId": "..."
}
```

`verificationChallengeId` is optional and is supplied only after the backend has required and verified the purpose-bound challenge.

The client does not provide:

- transaction status,
- resulting balances,
- ledger entries,
- fees calculated by the backend,
- branch movements.

The backend determines all of these.

Validation includes, as applicable:

- Source account belongs to the authenticated customer.
- Source account is eligible for transfer.
- Destination account exists and matches the supplied IFSC.
- Source and destination are not the same account.
- Amount is positive and within configured limits.
- Daily and per-transaction limits are available to the customer.
- Required authentication has been completed.
- Required balance is available.
- Required financial/operational state is valid.

Immediate intra-bank transfers are processed synchronously inside the PostgreSQL transaction. If processing completes successfully, the API returns the created financial result.

Example successful result:

```json
{
  "transactionReference": "TXN-...",
  "status": "SUCCESS",
  "transferMethod": "ACCOUNT_IFSC",
  "amount": 5000.00,
  "sourceAccountId": "acc-123",
  "destinationAccountNumber": "1234567890",
  "destinationIfsc": "BANK0001234",
  "fees": 0.00,
  "remark": "Monthly payment",
  "transactionAt": "2026-10-06T18:30:00Z"
}
```

A new finalized transaction resource may return:

```text
201 Created
```

Business validation failures use `409` or `422` as appropriate and do not create a successful financial effect.

---

# 51. UPI Transfer

```http
POST /api/v1/transfers/upi
```

Authentication:

- Authenticated CUSTOMER

Required header:

```http
Idempotency-Key: <unique-key>
```

Request:

```json
{
  "sourceAccountId": "acc-123",
  "destinationUpiAddress": "alex@bank-domain",
  "amount": 2500.00,
  "remark": "Dinner",
  "upiPin": "••••",
  "verificationChallengeId": "..."
}
```

Payment authentication follows the UPI rules.

The transfer requires the customer's UPI PIN.

When the amount exceeds the active Admin-configured OTP threshold and no verified `verificationChallengeId` is supplied, the backend automatically triggers and sends the purpose-bound OTP challenge and returns `202 Accepted` with `status = "SECOND_FACTOR_REQUIRED"`, `challengeId` and `expiresAt`. After successful OTP verification, the customer resubmits the transfer with `verificationChallengeId`.

The frontend must not decide independently whether OTP is needed.

The backend verifies:

- Source-account ownership
- Account state
- Destination UPI ownership and current mapping
- Amount and limits
- Available balance
- UPI PIN validity
- OTP requirement and verification where applicable
- Duplicate/idempotent submission
- Concurrent balance changes

After any required OTP challenge is verified, the transfer is processed synchronously and returns the finalized financial result, consistent with account transfers.

The response must not expose the customer's UPI PIN or any secret used to verify it.

---

# 52. Phone-Number Payment

```http
POST /api/v1/transfers/phone
```

Authentication:

- Authenticated CUSTOMER

Required header:

```http
Idempotency-Key: <unique-key>
```

Request:

```json
{
  "sourceAccountId": "acc-123",
  "destinationPhoneNumber": "9876543210",
  "amount": 1500.00,
  "remark": "Dinner split",
  "upiPin": "••••",
  "verificationChallengeId": "..."
}
```

If the amount exceeds the active Admin-configured OTP threshold and no verified `verificationChallengeId` is supplied, the backend automatically triggers a purpose-bound OTP challenge and returns `202 Accepted` with `status = "SECOND_FACTOR_REQUIRED"`, `challengeId` and `expiresAt`. After successful verification, the customer resubmits the payment with `verificationChallengeId`.

The backend performs the following resolution:

```text
Phone number
      ↓
customer default phone UPI
      ↓
current eligible account mapping
      ↓
normal UPI transfer rules
```

The phone payment does not create a separate transaction type solely because the sender entered a phone number.

After resolution, the same financial movement and transaction model used by the normal UPI flow is used.

The backend validates the phone-number format even if the frontend already did so.

No phone number is used for authentication or OTP delivery.

---

# 53. Own-Account Transfer

```http
POST /api/v1/transfers/own-account
```

Authentication:

- Authenticated CUSTOMER

Required header:

```http
Idempotency-Key: <unique-key>
```

Request:

```json
{
  "sourceAccountId": "acc-123",
  "destinationAccountId": "acc-456",
  "amount": 10000.00,
  "remark": "Move savings",
  "verificationChallengeId": "..."
}
```

`verificationChallengeId` is optional and is supplied only when required by backend policy after the challenge has been verified.

Both accounts must belong to the authenticated customer and be eligible for the operation.

The source and destination account must be different.

The financial operation still follows the normal balance, limit, authentication, idempotency and ledger rules.

The backend does not expose this as two independent customer deposits/withdrawals. It is one financial transaction with the required account effects and accounting entries.

---

# 54. Transfer Response and Financial Result

Transfer APIs do not return raw database entities.

The response is a business-oriented financial result.

Common fields are:

| Field | Meaning |
|---|---|
| `transactionReference` | Stable transaction/reference identifier |
| `status` | `SUCCESS` or `FAILED` for the finalized financial result |
| `transferMethod` | Customer-facing transfer method |
| `amount` | Requested financial amount |
| `fees` | Applied fee amount, if any |
| `transactionAt` | Final transaction timestamp where available |
| `remark` | Customer remark where applicable |
| destination summary | Required recipient information |

The exact response DTO can differ by transfer type where a field is not meaningful.

The API never exposes another customer's balance.

A successful financial operation is final from the customer's normal UI perspective and cannot be cancelled through the ordinary customer API.

---

# 55. Transaction Details

```http
GET /api/v1/transactions/{transactionReference}
```

Authentication:

- CUSTOMER for the customer's own transaction
- Additional role-specific rules will be defined in Manager/Admin transaction APIs

The backend verifies that the caller is allowed to view the referenced transaction.

For customer access, the response reads the finalized `FinancialTransaction` record.

A response includes, where applicable:

```json
{
  "transactionReference": "TXN-...",
  "status": "SUCCESS",
  "amount": 5000.00,
  "transferMethod": "UPI",
  "transactionAt": "2026-10-06T18:30:00Z",
  "sourceAccount": {
    "accountId": "acc-123"
  },
  "destination": {
    "accountHolderName": "Example Customer",
    "upiAddress": "alex@bank-domain"
  },
  "fees": 0.00,
  "remark": "Dinner"
}
```

The response must not expose internal ledger-account IDs, another customer's balance, UPI PIN material or other secret data.

---

# 56. Customer Transaction History

## 56.1 Unified customer history

```http
GET /api/v1/transactions
```

The endpoint returns a combined customer-facing history from finalized `FinancialTransaction` records.

The unified view is not a second financial source of truth. Because callers can combine multiple optional filters across accounts, instruments, dates and transaction metadata, the backend executes these history searches through read-only Spring JDBC queries that map directly to response DTOs without hydrating JPA entity graphs.

Supported filters include the requirement-defined categories where applicable:

- `accountId`
- account/customer financial instrument scope
- outgoing vs incoming
- date range
- transfer method
- status
- transaction type
- pagination

Examples:

```text
GET /api/v1/transactions?accountId=acc-123&page=0&pageSize=20

GET /api/v1/transactions?direction=OUTGOING&page=0&pageSize=20

GET /api/v1/transactions?from=2026-09-01&to=2026-10-01&page=0&pageSize=50
```

The final filter names for credit-card transactions, debit-card transactions and loan EMI activity must match the final resource model rather than exposing database table names.

## 56.2 Account-scoped history

```http
GET /api/v1/accounts/{accountId}/transactions
```

The endpoint is a convenience for account-specific history.

The authenticated customer must own the account or otherwise have permission to view it.

The same pagination and common transaction filters apply where meaningful.

## 56.3 Pagination response

The normal response follows the common pagination contract already defined in this document:

```json
{
  "items": [],
  "page": 0,
  "pageSize": 20,
  "totalElements": 125,
  "totalPages": 7
}
```

The backend determines the safe maximum `pageSize`.

---

# 57. Transaction History Semantics

The transaction history endpoint must preserve the distinction between customer-facing and accounting concepts.

For customer account effects:

```text
DEBIT  = money left the account
CREDIT = money entered the account
```

For example, in a transfer:

```text
Sender account     DEBIT
Receiver account   CREDIT
```

The accounting ledger may contain its own DEBIT/CREDIT entries, but these are not the same field and should not be mixed into the customer-facing DTO.

Customer history does not need to expose whether a transaction originally came from:

- a money request,
- a scheduled payment,
- or a statement-generation request.

Those are workflow origins, not required customer-facing transaction classifications.

Failed finalized financial transactions remain visible according to the customer's access rules because financial history is permanent.

---

# 58. UPI Management APIs

UPI identifiers are customer-managed resources, except for the automatically maintained default phone UPI.

The API distinguishes:

```text
DEFAULT_PHONE
CUSTOM
```

The default phone UPI is controlled by the customer's phone-number lifecycle and account mapping rules.

Custom UPI IDs are created and deleted by the customer.

---

# 59. List Customer UPI IDs

```http
GET /api/v1/upis
```

Authentication:

- CUSTOMER

Returns the customer's UPI identities and their current status/mapping information.

The response should distinguish the default phone UPI from custom UPI IDs.

Example:

```json
{
  "items": [
    {
      "upiId": "9876543210@bank-domain",
      "type": "DEFAULT_PHONE",
      "accountId": "acc-123",
      "status": "ACTIVE"
    },
    {
      "upiId": "arvind@bank-domain",
      "type": "CUSTOM",
      "accountId": "acc-123",
      "status": "ACTIVE",
      "isPrimary": true
    }
  ]
}
```

The default phone UPI may remain reserved for the customer while temporarily having no active account destination.

---

# 60. Create Custom UPI ID

```http
POST /api/v1/accounts/{accountId}/upis
```

Authentication:

- CUSTOMER who owns the account

Required header:

```http
Idempotency-Key: <unique-key>
```

Request:

```json
{
  "prefix": "arvind"
}
```

The backend adds the fixed bank domain/suffix.

The customer does not provide the suffix.

Validation includes:

- Account belongs to the caller.
- Account is eligible for UPI creation.
- Prefix satisfies fixed character/length rules.
- Prefix is not an entirely numeric 8–12 digit value reserved for phone-addressing semantics.
- Resulting UPI address is unique.
- The configured custom UPI limit for the account is not exceeded.

The API returns the newly created UPI resource.

```text
201 Created
```

The default phone UPI is excluded from the custom UPI count.

---

# 61. Delete Custom UPI ID

```http
DELETE /api/v1/upis/{upiId}
```

Authentication:

- CUSTOMER who owns the UPI/account

Custom UPI records are physically deleted in V1.

Before deletion, the backend checks for future scheduled payments that use the UPI as a source. The customer-facing flow must show the warning required by the scheduled-payment rules before deletion is finally confirmed.

The deletion does not modify any permanent financial transaction that already used the UPI.

If deletion succeeds:

```text
204 No Content
```

The deleted UPI string may later be reused because V1 physically removes deleted custom UPI records.

Old scheduled payments must not be redirected to a later owner of the same textual UPI address.

---

# 62. Set Custom Primary UPI

```http
POST /api/v1/upis/{upiId}/set-primary
```

Authentication:

- CUSTOMER who owns the UPI

The selected UPI must be a custom UPI owned by the customer's eligible account.

Only one custom UPI may be primary for an account.

This setting is separate from the customer's default phone UPI account mapping. The word `primary` in these two concepts must not be treated as the same database relationship.

Successful response:

```text
200 OK
```

The resulting resource representation identifies the selected UPI as primary.

---

# 63. Change Default Phone UPI Account Mapping

The customer's default phone UPI is controlled through the account-mapping operation already introduced in the Customer and Account API section.

Conceptually:

```http
PATCH /api/v1/customers/me/default-upi
```

Request:

```json
{
  "accountId": "acc-456"
}
```

The target account must be:

- owned by the customer,
- active,
- eligible for default phone UPI mapping.

The default phone UPI itself is not recreated merely because the mapping changes. Its current destination account is changed.

If there is no eligible active account, the default phone UPI may remain reserved without an active destination and therefore cannot receive phone-number payments.

A suspended account is not protected from suspension merely because it is currently the default-UPI account.

---

# 64. UPI PIN APIs

UPI PIN management is part of the UPI/security boundary.

## 64.1 Setup UPI PIN

```http
POST /api/v1/upis/pin/setup
```

Requires strong authentication.

## 64.2 Change UPI PIN

```http
POST /api/v1/upis/pin/change
```

Requires the existing PIN plus the configured strong authentication flow.

## 64.3 Reset UPI PIN

```http
POST /api/v1/upis/pin/reset
```

Requires the configured strong authentication flow.

The exact request fields and verification sequence will be finalized with the security-hardening work, but the API contract must guarantee:

- PIN is never stored in plaintext.
- PIN material is never returned in a normal response.
- Wrong attempts are limited by policy.
- Repeated failures can temporarily block the relevant operation.
- Reset/change/setup does not use the customer's phone number as an authentication channel.

---

# 65. Transfer Limits and Backend Validation

Before final financial processing, the backend evaluates applicable limits.

These can include:

- minimum amount,
- maximum amount per transaction,
- rail-specific limit,
- daily monetary limit,
- daily transaction count where configured,
- applicable fees,
- OTP threshold.

The frontend may fetch/display configuration-backed limits for early validation, but the backend recalculates or rechecks them at submission time.

A customer must not be able to bypass limits by modifying a frontend request or by replaying an older request.

Changing a limit later does not invalidate previously completed transactions.

---

# 66. Financial Transaction Atomicity

A completed transfer is treated as one database transaction where the required records change together.

Typical boundary:

```text
Validate
   ↓
Authorize
   ↓
Check current state
   ↓
Protect against concurrency
   ↓
Update account balances
   ↓
Create FinancialTransaction
   ↓
Create TransactionAccountEffect rows
   ↓
Create required TransactionParty rows
   ↓
Create BranchFinancialMovement rows when applicable
   ↓
Create balanced LedgerEntry rows
   ↓
Persist idempotency result
   ↓
Commit
```

If any required financial part fails before commit, the whole financial operation is rolled back.

Notification delivery is outside this atomic financial boundary.

The exact service/repository implementation remains a backend implementation concern; the API contract depends on the resulting all-or-nothing business behaviour.

---

# 67. Same-Branch and Cross-Branch Transfer Behaviour

The API does not require the customer to provide branch financial information.

The backend determines branch effects from the source and destination accounts.

### Same branch

```text
Customer account A
      ↓
Customer account B
```

The customer transaction contains the required account effects.

No branch customer-deposit movement is created for the transfer because the money remains within the same branch.

### Cross branch

```text
Source branch
     ↓
Destination branch
```

The backend creates the required branch-level movement:

- source branch customer deposit position decreases,
- destination branch customer deposit position increases.

The customer still sees one financial transaction, not two customer transactions.

---

# 68. Transfer Error Code Baseline

The following codes are reserved for the transfer/UPI APIs where applicable:

| Code | Meaning |
|---|---|
| `SOURCE_ACCOUNT_NOT_FOUND` | Source account does not exist or is not visible to the caller |
| `SOURCE_ACCOUNT_NOT_ELIGIBLE` | Source account cannot be used for the requested transfer |
| `DESTINATION_ACCOUNT_NOT_FOUND` | Account + IFSC destination is unavailable |
| `DESTINATION_UPI_NOT_FOUND` | UPI address does not resolve to a valid destination |
| `DESTINATION_PHONE_NOT_FOUND` | Phone-number payment cannot resolve to an active default UPI destination |
| `SAME_ACCOUNT_TRANSFER` | Source and destination are the same account |
| `INVALID_TRANSFER_AMOUNT` | Amount is zero, negative or otherwise invalid |
| `TRANSFER_LIMIT_EXCEEDED` | Configured transaction/daily limit has been exceeded |
| `INSUFFICIENT_FUNDS` | Required source balance is not available |
| `TRANSFER_AUTHENTICATION_REQUIRED` | Required authentication has not been completed |
| `INVALID_UPI_PIN` | Supplied UPI PIN is invalid |
| `UPI_OPERATION_BLOCKED` | UPI/PIN operation is temporarily blocked by security policy |
| `RECIPIENT_VERIFICATION_FAILED` | Destination could not be safely verified |
| `UPI_NOT_ELIGIBLE` | UPI identifier cannot currently be used for the requested operation |
| `UPI_LIMIT_EXCEEDED` | Custom UPI limit for the account has been reached |
| `UPI_PREFIX_INVALID` | Custom UPI prefix violates product rules |
| `UPI_ALREADY_EXISTS` | Requested UPI address is already in use |
| `UPI_NOT_FOUND` | UPI resource is not available to the caller |
| `UPI_DELETION_CONFIRMATION_REQUIRED` | Required scheduled-payment deletion confirmation is missing |
| `TRANSACTION_NOT_FOUND` | Transaction reference is not available to the caller |
| `FINANCIAL_OPERATION_FAILED` | Financial processing could not complete safely |
| `INSUFFICIENT_BRANCH_FUNDS` | Branch-side funds required by the operation are not available |
| `CONCURRENT_TRANSFER_CONFLICT` | Concurrent financial activity prevented the requested operation from being safely applied |

Stable error codes should represent client-relevant business conditions rather than every possible message.

---

# 69. Transfer Concurrency Rules

Financial operations must be protected against concurrent requests.

Examples include:

```text
Transfer A against account balance
        VS
Transfer B against same account balance

Transfer
        VS
Account suspension

Transfer
        VS
Account closure/cooling boundary

Scheduled payment execution
        VS
Customer transfer

Duplicate HTTP request
        VS
Original request
```

Only one valid financial result may be committed when two operations compete for the same constrained state.

The final mechanism may use database locking, optimistic version checks, conditional updates, unique constraints or another appropriate implementation technique. The API contract does not force one locking technology.

The important observable rule is that the customer must never receive two successful effects from one idempotent operation and the account must never become financially inconsistent because of a race.

---

# 70. Transfer Idempotency Rules

`Idempotency-Key` is required on critical customer transfer creation endpoints.

The idempotency scope must include enough request context to prevent accidental reuse across unrelated operations.

For a repeated request:

```text
Same key + same operation/request
        ↓
Return the original result
```

For:

```text
Same key + different request payload
        ↓
409 Conflict
IDEMPOTENCY_CONFLICT
```

The database remains the final guard even if Redis is used for an early duplicate check.

An idempotent retry must not:

- debit the account twice,
- create duplicate branch movement,
- create duplicate ledger effects,
- create a second financial transaction,
- or create a second business operation.

---

# 71. UPI Ownership and Identifier Reuse

The backend must validate the current mapping of a UPI identifier before a new payment moves money.

A textual UPI address is not enough to identify the historical recipient forever.

For permanent financial history, transaction party/effect information and other required snapshots remain tied to the transaction that actually happened.

For temporary future-payment workflows, the scheduled-payment and money-request contracts store stable UPI/account identities to prevent a later reuse of the same text from redirecting an old payment. Stored UPI foreign keys use `ON DELETE SET NULL`, so deleting a custom UPI is not blocked by PostgreSQL. The source-UPI deletion flow also deletes future scheduled payments that use the deleted UPI as the source. Scheduled payments that reference the deleted UPI as the receiver remain but fail deterministic execution validation, and money requests whose requester-UPI reference is cleared fail acceptance validation. In all cases, later reuse of the same text must not redirect an old workflow.

This distinction is important:

```text
Current UPI lookup
        ≠
Historical transaction identity
```

---

# 72. Transaction and UPI Side Effects

A successful transfer can cause:

```text
Account balance updates
        ↓
FinancialTransaction
        ↓
TransactionAccountEffect
        ↓
LedgerEntry
        ↓
BranchFinancialMovement where applicable
```

After the financial commit, asynchronous notification work may create/deliver:

```text
Notification
    ↓
NotificationDelivery
    ↓
SNS
    ↓
SQS
    ↓
External provider
```

Notification failure must not reverse the financial transaction.

---

# 73. Beneficiary APIs

Beneficiaries are saved recipient **accounts** used primarily for Account + IFSC transfers. A beneficiary is owned by the authenticated customer and does not create a new financial identity for the recipient.

The backend remains responsible for verifying that the recipient account is valid and eligible before a beneficiary is created.

## 73.1 Verify recipient account

```http
POST /api/v1/beneficiaries/verify-recipient
```

Purpose: verify an Account + IFSC recipient before the customer saves it.

Request:

```json
{
  "accountNumber": "123456789012",
  "ifsc": "SIMU0001234"
}
```

The response should contain only the recipient information required for confirmation, such as:

```json
{
  "verificationId": "...",
  "accountNumber": "123456789012",
  "accountHolderName": "Rahul Sharma",
  "ifsc": "SIMU0001234",
  "status": "VERIFIED"
}
```

The verification result is intended for the immediate beneficiary-creation flow. It must not be treated as permanent proof that the account can always receive money later.

Typical responses:

- `200 OK` — recipient verified
- `400 Bad Request` — malformed account/IFSC input
- `404 Not Found` — recipient account cannot be found
- `422 Unprocessable Entity` — recipient is not eligible

## 73.2 Create beneficiary

```http
POST /api/v1/beneficiaries
```

Authentication: CUSTOMER

The request must complete the required recipient verification and OTP step.

Request:

```json
{
  "verificationId": "...",
  "verificationChallengeId": "..."
}
```

The beneficiary enters the fixed 30-minute cooling period after successful creation.

Response:

```json
{
  "beneficiaryId": "...",
  "status": "COOLING",
  "usableAt": "2026-10-07T00:30:00Z",
  "recipient": {
    "accountNumber": "123456789012",
    "accountHolderName": "Rahul Sharma",
    "ifsc": "SIMU0001234"
  }
}
```

HTTP status: `201 Created`.

The exact maximum beneficiary count comes from Admin configuration at creation time. Lowering that configuration does not invalidate existing beneficiaries. New creations remain blocked until the customer is again within the configured limit.

A duplicate beneficiary for the same customer and recipient account is rejected.

## 73.3 List beneficiaries

```http
GET /api/v1/beneficiaries
```

Authentication: CUSTOMER

The API returns only beneficiaries owned by the authenticated customer.

Baseline response:

```json
{
  "items": [
    {
      "beneficiaryId": "...",
      "status": "USABLE",
      "nickname": "Home Account",
      "recipient": {
        "accountNumber": "123456789012",
        "accountHolderName": "Rahul Sharma",
        "ifsc": "SIMU0001234"
      },
      "usableAt": "2026-10-07T00:30:00Z"
    }
  ],
  "page": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

Cooling beneficiaries remain visible but have `status = COOLING` and cannot be used for transfers.

## 73.4 Get beneficiary

```http
GET /api/v1/beneficiaries/{beneficiaryId}
```

Authentication: CUSTOMER

Returns the beneficiary when it belongs to the authenticated customer. A beneficiary owned by another customer is not exposed.

## 73.5 Update beneficiary nickname

Beneficiary identity details are not directly editable. The customer must delete and recreate the beneficiary to change the recipient account.

The nickname is the limited mutable field explicitly allowed by the requirements, and it can be added after the cooling period.

```http
PATCH /api/v1/beneficiaries/{beneficiaryId}
```

Request:

```json
{
  "nickname": "Home Account"
}
```

The backend rejects nickname changes while the beneficiary is still cooling.

HTTP status: `200 OK`.

## 73.6 Delete beneficiary

```http
DELETE /api/v1/beneficiaries/{beneficiaryId}
```

Authentication: CUSTOMER

Deletion is immediate. It removes only the saved beneficiary relationship. The recipient account and existing financial history are unaffected.

HTTP status: `204 No Content`.

Before deletion, the frontend may need to warn the customer when the beneficiary is referenced by future scheduled payments, according to the scheduled-payment rules. The backend performs the final validation and deletion.

## 73.7 Beneficiary error codes

| Code | Meaning |
|---|---|
| `BENEFICIARY_NOT_FOUND` | Beneficiary does not exist or is not visible to the caller |
| `RECIPIENT_ACCOUNT_NOT_FOUND` | Recipient account cannot be found |
| `RECIPIENT_ACCOUNT_NOT_ELIGIBLE` | Recipient account cannot currently be used |
| `BENEFICIARY_ALREADY_EXISTS` | Customer already saved that recipient account |
| `BENEFICIARY_LIMIT_EXCEEDED` | Current configured beneficiary maximum would be exceeded |
| `BENEFICIARY_COOLING` | Beneficiary is still in its 30-minute cooling period |
| `BENEFICIARY_NICKNAME_NOT_ALLOWED` | Nickname cannot currently be changed |
| `BENEFICIARY_VERIFICATION_EXPIRED` | Recipient verification is no longer valid for creation |
| `BENEFICIARY_OTP_REQUIRED` | Required OTP verification has not been completed |

---

# 74. Money Request APIs

A money request is a temporary request for another customer to make a simulated UPI payment to the requester.

The request permanently identifies the intended receiving side through the requester's UPI/account relationship captured at creation time. The payer side identifies the requested customer, not a fixed payment account.

This allows the requested payer to choose any eligible active account when accepting the request while still preventing an old request from being redirected to a different recipient account after UPI mappings change.

## 74.1 Create money request

```http
POST /api/v1/money-requests
```

Authentication: CUSTOMER

The requester supplies the recipient/payer UPI address or phone-number addressing information through the normal UPI recipient-resolution flow. The backend verifies the intended payer before creating the request.

Request:

```json
{
  "payerUpiId": "rahul@bank-domain",
  "amount": 500.00,
  "remark": "Dinner share"
}
```

A phone-number request may use the phone-number addressing input supported by the UPI API instead of requiring the customer to enter the generated default UPI text manually.

The backend stores:

- requester customer identity,
- requester UPI identity/address,
- requester account identity/number snapshot,
- requested payer customer/user identity,
- amount,
- remark where supplied,
- `validTill`,
- request status.

The payer's account is deliberately not fixed at creation time.

Response: `201 Created`.

```json
{
  "moneyRequestId": "...",
  "status": "PENDING",
  "amount": 500.00,
  "validTill": "2026-10-08T00:00:00Z",
  "payer": {
    "customerId": "...",
    "displayName": "Rahul Sharma"
  },
  "receiver": {
    "accountNumber": "123456789012"
  },
  "remark": "Dinner share"
}
```

The exact `validTill` duration comes from the active Admin-configured money-request validity policy.

Idempotency: `Idempotency-Key` is required. Reusing the same key with the same request returns the original result. Reusing it with a materially different request returns `409 Conflict` with `IDEMPOTENCY_CONFLICT`.

## 74.2 List money requests

```http
GET /api/v1/money-requests
```

Authentication: CUSTOMER

The customer can retrieve requests relevant to them. The API should support filtering by direction and pending/available operational state, for example:

```text
direction=INCOMING
direction=OUTGOING
status=PENDING
```

The response uses the standard pagination envelope.

The API does not expose another customer's hidden request information merely because that customer is the payer/requester on a request.

## 74.3 Get money request

```http
GET /api/v1/money-requests/{moneyRequestId}
```

Authentication: CUSTOMER

Accessible only when the authenticated customer is the requester or requested payer.

The response shows the current request status and the information needed by the user to act on the request.

The API should calculate whether the request is still valid from `validTill`. It must not require a persisted `EXPIRED` state.

## 74.4 Accept money request

```http
POST /api/v1/money-requests/{moneyRequestId}/accept
```

Authentication: CUSTOMER

Only the requested payer may call this endpoint.

Request:

```json
{
  "sourceAccountId": "...",
  "upiPin": "••••",
  "verificationChallengeId": "..."
}
```

The `verificationChallengeId` is supplied only after the backend has required and verified the purpose-bound OTP challenge. If the payment amount exceeds the active Admin-configured OTP threshold and a verified challenge ID is not yet supplied, the backend automatically triggers the challenge and returns `202 Accepted` with `status = "SECOND_FACTOR_REQUIRED"`, `challengeId` and `expiresAt`.

Before payment, the backend checks atomically:

1. request is still pending,
2. current time has not passed `validTill`,
3. requester's stored UPI identity still maps to the same account captured at creation,
4. requested payer matches the authenticated customer,
5. supplied source account belongs to that customer and is eligible for payment,
6. source balance and transfer limits permit the payment,
7. any required UPI/transfer authentication is valid,
8. no concurrent action has already completed the request.

The payer can choose any eligible active account; it is not required to use an account that existed when the request was created.

Payment and money-request deletion occur in the same PostgreSQL transaction.

On successful payment, the response returns the resulting financial transaction information because the temporary money-request record is removed.

HTTP status: `200 OK`.

Example:

```json
{
  "status": "PAID",
  "transactionId": "...",
  "amount": 500.00,
  "processedAt": "2026-10-07T19:15:10Z"
}
```

No partial payment is supported.

The resulting financial transaction does not record that it originated from a money request.

Idempotency: `Idempotency-Key` is required because accepting a request causes financial movement. Repeated identical requests return the original result and must never create a second debit/credit or ledger movement.

## 74.5 Decline money request

```http
POST /api/v1/money-requests/{moneyRequestId}/decline
```

Only the requested payer may decline the request.

The request is deleted after the successful decline operation. No financial movement occurs.

HTTP status: `204 No Content`.

Idempotency is recommended for the state-changing operation so client retries do not create ambiguous results.

## 74.6 Cancel money request

```http
POST /api/v1/money-requests/{moneyRequestId}/cancel
```

Only the requester may cancel while the request is still pending.

The request is deleted. No financial movement occurs.

HTTP status: `204 No Content`.

Idempotency is recommended for the state-changing operation.

## 74.7 Money request expiry

Money requests do not have a persisted `EXPIRED` state.

At processing time:

```text
now > validTill
      ↓
reject action
      ↓
remove stale request when appropriate
```

A cleanup job may remove expired requests later. Cleanup timing does not determine whether the request is payable.

Attempting to accept an already-expired request returns a business conflict such as:

```text
409 Conflict
MONEY_REQUEST_EXPIRED
```

## 74.8 Money request error codes

| Code | Meaning |
|---|---|
| `MONEY_REQUEST_NOT_FOUND` | Request does not exist or is not visible to the caller |
| `MONEY_REQUEST_SELF_REQUEST` | Customer cannot request money from themselves |
| `MONEY_REQUEST_INVALID_AMOUNT` | Amount is zero, negative or otherwise invalid |
| `MONEY_REQUEST_PAYER_NOT_FOUND` | Requested payer cannot be resolved |
| `MONEY_REQUEST_EXPIRED` | Request is past `validTill` |
| `MONEY_REQUEST_NOT_PENDING` | Request is no longer available for the requested action |
| `MONEY_REQUEST_UNAUTHORIZED_ACTOR` | Caller is not allowed to perform the action |
| `REQUESTER_UPI_CHANGED` | Requester's stored UPI no longer maps to the original account |
| `REQUESTER_ACCOUNT_UNAVAILABLE` | Intended receiving account is no longer eligible |
| `SOURCE_ACCOUNT_NOT_ELIGIBLE` | Payer-selected source account cannot be used |
| `INSUFFICIENT_FUNDS` | Payer does not have sufficient available funds |
| `TRANSFER_LIMIT_EXCEEDED` | Payment would exceed configured limits |
| `TRANSFER_AUTHENTICATION_REQUIRED` | Required additional authentication is missing |
| `INVALID_TRANSFER_AUTHENTICATION` | Supplied transfer authentication is invalid |
| `MONEY_REQUEST_CONCURRENT_CONFLICT` | Another valid action won the race for the request |

---

# 75. Beneficiary and Money Request Financial Boundaries

Beneficiary operations do not themselves move money.

Money-request creation, cancellation and decline also do not create financial movements.

Only acceptance creates a financial payment. Its financial boundary is the same type of atomic boundary used by normal UPI/account transfers:

```text
Validate request
      ↓
Validate payer source account
      ↓
Validate requester UPI/account mapping
      ↓
Check limits/authentication
      ↓
Update balances
      ↓
Create FinancialTransaction
      ↓
Create account effects
      ↓
Create ledger entries
      ↓
Create branch movement when applicable
      ↓
Delete Money Request
      ↓
Commit
```

Notification is outside the financial transaction boundary.

If notification delivery fails, the successful payment remains successful.

---

# 76. Beneficiary and Money Request Concurrency

Important race conditions include:

```text
Create beneficiary
    VS
Create duplicate beneficiary

Delete beneficiary
    VS
Transfer using beneficiary

Money request accept
    VS
Money request cancel

Money request accept
    VS
Money request decline

Money request accept
    VS
Request cleanup

Money request accept
    VS
Another accept retry
```

The database and service layer must ensure that only one valid state transition wins.

In particular, the money-request acceptance path must not allow two successful payments for one request.

---

# 77. Scheduled Payment APIs

Scheduled payments are temporary instructions for future UPI payments.

Only UPI payments can be scheduled in V1. Account + IFSC transfers are immediate-only and do not have a scheduled-payment API.

The scheduled-payment API is responsible for creating, viewing and cancelling the future instruction. Actual payment execution is handled by the scheduled-job path and follows the normal financial transaction architecture.

```text
Customer API
     ↓
Scheduled Payment record
     ↓
EventBridge Scheduler
     ↓
Lambda job handler
     ↓
PostgreSQL authoritative checks
     ↓
Normal UPI payment service
     ↓
FinancialTransaction + LedgerEntry
```

The API must never treat the EventBridge schedule itself as the business source of truth.

---

## 77.1 Create Scheduled Payment

```http
POST /api/v1/scheduled-payments
```

Authentication:

- Customer authentication required.
- The customer can create a schedule only for an eligible source account/UPI identity they own.
- Required transaction authentication/UPI authentication is performed according to the active payment policy.

Headers:

```http
Authorization: Bearer <access-token>
Idempotency-Key: <unique-key>
Content-Type: application/json
```

Example request:

```json
{
  "sourceAccountId": "acc-123",
  "sourceUpiId": "alex@bank-domain",
  "recipient": {
    "type": "UPI_ID",
    "value": "rahul@bank-domain"
  },
  "amount": 2500.00,
  "scheduledAt": "2026-10-15T10:30:00+05:30",
  "remark": "Monthly payment",
  "upiPin": "••••",
  "verificationChallengeId": "..."
}
```

If the amount exceeds the active Admin-configured OTP threshold and no verified `verificationChallengeId` is supplied, the backend automatically triggers a purpose-bound OTP challenge and returns `202 Accepted` with `status = "SECOND_FACTOR_REQUIRED"`, `challengeId` and `expiresAt`. The scheduled-payment request is created only after the required verification has succeeded.

A phone-number recipient may also be accepted by the public API through the existing phone-payment addressing flow:

```json
{
  "recipient": {
    "type": "PHONE_NUMBER",
    "value": "9876543210"
  }
}
```

When a phone number is used, the backend resolves it to the customer's default phone UPI during schedule creation and stores the required stable UPI/account identities. The scheduled job does not later re-resolve the phone number.

The source UPI and receiver UPI identities must represent the relationships that are valid at creation time.

Request validation includes:

- Amount must be greater than zero.
- Source account must be owned by the authenticated customer.
- Source account must be eligible for UPI scheduling.
- Source UPI must belong to the source account and be usable for scheduling.
- Recipient must resolve to a valid intended recipient at creation time.
- Source and destination cannot be the same account.
- Scheduled time must be in the allowed future scheduling range.
- Scheduled amount/count limits must be satisfied.
- Applicable transfer/UPI policy must be satisfied.
- The current account/UPI state must allow scheduling.
- A conflicting schedule must not bypass existing source-UPI deletion rules or account restrictions.

Successful creation returns `201 Created`.

Example response:

```json
{
  "id": "sp-123",
  "status": "SCHEDULED",
  "sourceAccountId": "acc-123",
  "sourceUpiId": "alex@bank-domain",
  "receiverUpiId": "rahul@bank-domain",
  "receiverAccountId": "acc-987",
  "amount": 2500.00,
  "scheduledAt": "2026-10-15T10:30:00+05:30",
  "executionDeadline": "2026-10-15T10:40:00+05:30",
  "createdAt": "2026-10-06T23:10:00+05:30"
}
```

The execution deadline is derived by the backend from the fixed 10-minute late-execution grace period. The client does not choose this value.

The response does not expose internal EventBridge schedule identifiers unless there is a genuine operational reason to do so.

---

## 77.2 List Scheduled Payments

```http
GET /api/v1/scheduled-payments
```

Authentication:

- Customer only.
- Results are limited to the authenticated customer's own scheduled payments.

Supported filters may include:

- `status`
- `sourceAccountId`
- `fromDate`
- `toDate`

Pagination should follow the common API pagination contract.

Because scheduled-payment records are temporary, this endpoint represents currently retained operational schedules rather than permanent historical payment history.

A successfully executed or terminally failed schedule is no longer expected to appear here after the terminal-processing cleanup.

---

## 77.3 Get Scheduled Payment Details

```http
GET /api/v1/scheduled-payments/{scheduledPaymentId}
```

Authentication:

- Customer only.
- The scheduled payment must belong to the authenticated customer.

Response includes the information needed by the customer to understand the pending future payment, including:

- scheduled payment ID,
- current status,
- source account,
- source UPI identity,
- receiver details available under the normal UPI visibility rules,
- amount,
- remark,
- scheduled time,
- execution deadline,
- created time.

A terminal scheduled-payment record may no longer exist because terminal records are deleted. In that case the API returns `404 Not Found`. The resulting financial transaction remains available through transaction-history APIs when a financial transaction was created.

---

## 77.4 Cancel Scheduled Payment

```http
DELETE /api/v1/scheduled-payments/{scheduledPaymentId}
```

Cancellation is allowed only while the scheduled payment remains cancellable.

The backend must re-check current state before deleting it.

A successful cancellation returns:

```http
204 No Content
```

After cancellation:

- No financial movement is created.
- The temporary scheduled-payment record is deleted.
- The external scheduler should be removed where practical.
- If scheduler deletion fails, the database state remains authoritative and a later invocation becomes a harmless no-op.

Cancellation racing with execution must allow only one valid outcome.

```text
SCHEDULED
   ├── cancel wins → deleted, no payment
   └── execution wins → PROCESSING → SUCCESS / FAILED
```

The API must never report cancellation success after financial processing has already won the race.

---

## 77.5 Editing a Scheduled Payment

There is no direct update endpoint for scheduled-payment contents.

A customer changes a schedule by:

```text
DELETE /scheduled-payments/{id}
        ↓
POST /scheduled-payments
```

This keeps the scheduled instruction immutable after creation except for its controlled lifecycle transitions.

A new Idempotency-Key is used for the new schedule creation request.

---

# 78. Scheduled Payment Execution Contract

Actual execution is not a normal public customer endpoint.

It is triggered by EventBridge Scheduler and handled by the backend job-processing path.

The external scheduler event should contain only enough information to identify the intended scheduled-payment record/job.

The backend must reload the authoritative record from PostgreSQL before doing any financial work.

Conceptually:

```text
EventBridge
    ↓
Job invocation
    ↓
Load scheduled payment
    ↓
Validate current state
    ↓
Acquire SCHEDULED → PROCESSING
    ↓
Validate execution window
    ↓
Validate source account / source UPI
    ↓
Validate receiver UPI / receiver account relationship
    ↓
Validate limits / account state / payment rules
    ↓
Execute normal UPI financial operation
    ↓
Create FinancialTransaction + accounting entries
    ↓
Delete scheduled payment
    ↓
Commit
```

---

## 78.1 Execution Window

The scheduled payment is executable only when:

```text
status = SCHEDULED
AND current time >= scheduledAt
AND current time <= executionDeadline
```

The customer scheduled-payment grace period is fixed at 10 minutes and is not Admin-configurable.

If execution occurs after `executionDeadline`:

- The scheduled payment must not move money.
- It is treated as missed/failed according to the final status handling.
- The temporary scheduled-payment record is deleted after terminal processing.
- No financial transaction is created for money that was never moved.

The backend must not execute a payment indefinitely simply because the scheduler was delayed.

---

## 78.2 Receiver Identity Protection

The scheduled record stores stable internal identities for the receiver rather than relying only on a reusable textual identifier.

At execution time, the backend verifies that:

```text
stored receiver UPI identity
          ↓
currently exists / is valid
          ↓
still maps to the stored receiver account
```

If the relationship no longer matches, including when `receiver_upi_id` was set to `NULL` after physical UPI deletion:

- The payment fails.
- The payment is not redirected to another account/customer.
- The temporary schedule is removed after terminal processing.
- The sender receives an appropriate notification explaining that the recipient address is no longer valid for this scheduled payment.

The same protection applies when the original address was reached through a phone-number payment request.

A reused phone number or reused textual UPI ID must never redirect an old scheduled payment.

---

## 78.3 Source UPI Protection

When a customer deletes a UPI ID used by future scheduled payments, the customer-facing deletion flow warns that those schedules will also be deleted.

After confirmed deletion:

```text
UPI deletion
     ↓
Delete affected future schedules
     ↓
Remove related external schedules where practical
```

If the customer replaces the phone number that generated the default UPI, the old default UPI follows the same source-schedule deletion rule.

This is handled before normal scheduled execution rather than waiting for the scheduler to discover the deletion.

---

## 78.4 Processing State Transition

Scheduled execution uses:

```text
SCHEDULED
    ↓
PROCESSING
    ↓
SUCCESS / FAILED
```

Only one concurrent invocation may acquire the `SCHEDULED → PROCESSING` transition.

The exact database locking/versioning mechanism is an implementation decision, but the observable API and financial behaviour must satisfy this invariant.

A second invocation that sees the record already in `PROCESSING` or already terminal must not create another financial effect.

---

## 78.5 Financial Transaction Boundary

Scheduled payment execution uses the same financial service used by the corresponding immediate UPI payment flow.

It must not create a second, separate financial engine just for schedules.

Financial execution follows the established boundary:

```text
ScheduledPayment
      ↓
normal UPI payment business rules
      ↓
FinancialTransaction
      ↓
TransactionAccountEffect
      ↓
LedgerEntry / branch movement where applicable
```

The financial transaction and terminal scheduled-payment handling are committed in the same PostgreSQL transaction.

The resulting financial transaction is permanent.

The scheduled-payment record is temporary.

Transaction history does not need to expose that the transaction originated from a scheduled payment.

---

## 78.6 Retry and Duplicate Invocation Behaviour

EventBridge/Lambda delivery may result in more than one invocation attempt.

The application therefore treats scheduled execution as retry-safe.

Examples:

```text
Invocation A
    ↓
SCHEDULED → PROCESSING

Invocation B
    ↓
sees PROCESSING
    ↓
no second payment
```

or:

```text
Invocation A
    ↓
SUCCESS + financial transaction + scheduled row deletion

Invocation B
    ↓
scheduled record no longer exists
    ↓
no-op
```

A duplicate invocation must never create a second transfer, second ledger movement, or second debit from the source account.

---

# 79. Scheduled Payment Error Contracts

Common errors include:

| Code | Meaning |
|---|---|
| `SCHEDULE_NOT_FOUND` | Scheduled payment does not exist or is no longer retained |
| `SCHEDULE_NOT_CANCELLABLE` | Payment has already entered a non-cancellable state |
| `SCHEDULE_TIME_INVALID` | Scheduled time is outside the allowed range |
| `SCHEDULE_LIMIT_EXCEEDED` | Amount/count/future-range scheduling policy is exceeded |
| `SOURCE_ACCOUNT_NOT_ELIGIBLE` | Source account cannot be used for the scheduled payment |
| `SOURCE_UPI_INVALID` | Source UPI does not belong to or cannot be used by the source account |
| `RECIPIENT_NOT_FOUND` | Recipient cannot be resolved |
| `RECIPIENT_ADDRESS_CHANGED` | Stored recipient identity no longer maps to the original account |
| `SELF_TRANSFER_NOT_ALLOWED` | Source and destination resolve to the same account |
| `SCHEDULE_ALREADY_PROCESSING` | Another execution already owns processing |
| `SCHEDULE_EXPIRED` | Execution window has passed |
| `PAYMENT_NOT_ALLOWED` | Current account or payment policy prevents execution |
| `INSUFFICIENT_FUNDS` | Source balance is insufficient at execution time |
| `RATE_LIMIT_EXCEEDED` | Request is temporarily rate limited |

Internal scheduler failures should not be exposed as raw AWS/provider errors to the customer.

---

# 80. Scheduled Payment Notification Boundary

Customer notifications are outside the financial transaction itself.

Relevant notifications may include:

- Scheduled payment created
- Scheduled payment failed
- Scheduled payment missed
- Recipient UPI became invalid
- Future schedules removed because source UPI was deleted
- Other customer-visible lifecycle events where useful

Notification delivery failure does not roll back a successful financial transaction.

The normal notification architecture remains:

```text
Application
    ↓
SNS
    ↓
Channel-specific SQS queue
    ↓
Consumer
    ↓
Email / Telegram
```

---

# 81. Scheduled Payment API Security

Scheduled payments are financial instructions and require the same backend authorization discipline as immediate UPI payments.

The backend must verify:

- authenticated customer,
- source account ownership,
- source UPI ownership,
- destination validity,
- scheduling permissions,
- payment limits,
- account lifecycle state,
- configured authentication requirements,
- idempotency where applicable.

Frontend controls are not sufficient for any of these checks.

The API must not allow a customer to retrieve, cancel or manipulate another customer's scheduled payment by changing an ID in the URL.

Sensitive values such as authentication secrets, UPI PINs and tokens must never appear in application logs.

---

# 82. Scheduled Payment Concurrency Review

Important races include:

```text
Customer cancels schedule
        VS
Scheduled execution

Customer deletes source UPI
        VS
Scheduled execution

Receiver deletes UPI
        VS
Scheduled execution

Two scheduler invocations
        VS
One scheduled payment

Customer creates duplicate schedule request
        VS
API retry

Scheduler fires after cancellation but before AWS schedule deletion
        VS
Database state
```

The database state is authoritative in every case.

The desired result is always one valid final state with no duplicate financial effect.

External scheduler deletion is therefore an operational optimization, not the correctness mechanism.

---

# 83. Scheduled Payment API Summary

| Operation | Method | Endpoint | Auth | Result |
|---|---|---|---|---|
| Create schedule | `POST` | `/api/v1/scheduled-payments` | CUSTOMER | `201 Created` |
| List schedules | `GET` | `/api/v1/scheduled-payments` | CUSTOMER | `200 OK` |
| Get schedule | `GET` | `/api/v1/scheduled-payments/{id}` | CUSTOMER | `200 OK` |
| Cancel schedule | `DELETE` | `/api/v1/scheduled-payments/{id}` | CUSTOMER | `204 No Content` |

There is intentionally no public customer-facing `POST /scheduled-payments/{id}/execute` endpoint. Execution belongs to the scheduled job path and must use the established internal financial service.

---

# 84. Debit Card API

Debit cards are account-linked customer resources.

A debit card belongs to exactly one eligible bank account. The account-to-card relationship is permanent for the life of that card and is not changed by branch transfer.

Initial debit-card issuance does not require Manager approval. The backend performs the final eligibility checks before issuing a card.

## 84.1 Request a debit card

```http
POST /api/v1/debit-cards
```

Authentication:

- CUSTOMER

The request identifies the eligible source account.

Example:

```json
{
  "accountId": "acc_123"
}
```

The backend verifies at least:

- Account belongs to the authenticated customer
- Account is eligible for a debit card
- Account is in an allowed lifecycle state
- No active/usable debit card already exists for the account; replaced, expired, closed or permanently unusable cards remain historical records and do not block a replacement card
- Any configured product/eligibility rules are satisfied

Success:

```text
201 Created
```

The response returns the newly issued card representation without exposing protected card secrets.

A repeated request using the same idempotency key must not issue another card.

## 84.2 Get debit card

```http
GET /api/v1/debit-cards/{cardId}
```

Authentication:

- CUSTOMER

Authorization:

- Card must belong to the authenticated customer.

Response can include:

- Card ID
- Masked card number
- Last four digits
- Card status
- Expiry information
- Linked account reference
- Online/e-commerce setting
- International setting
- Current transaction/monthly limits
- Activation state

Full card number, PIN and other protected secrets are never returned through normal card-detail APIs.

## 84.3 List debit cards

```http
GET /api/v1/debit-cards
```

Authentication:

- CUSTOMER

The response is a customer-scoped collection. Pagination is not required for normal V1 usage unless the final product rules allow a sufficiently large card count.

## 84.4 Activate debit card

```http
POST /api/v1/debit-cards/{cardId}/activate
```

Authentication:

- CUSTOMER

The card must be issued and not permanently unusable.

The activation operation requires the configured authentication/verification level.

Successful activation:

```text
200 OK
```

The operation is idempotent from the customer's perspective: activating an already active card must not create another business effect.

## 84.5 Set or change debit-card PIN

```http
PUT /api/v1/debit-cards/{cardId}/pin
```

Authentication:

- CUSTOMER

The request body contains the new PIN and any required verification material.

The API must not return the PIN in the response.

The backend stores/protects the PIN according to the security design and never stores it as plain text.

Wrong PIN attempts may temporarily block the relevant card operation according to policy.

## 84.6 Block a debit card

```http
POST /api/v1/debit-cards/{cardId}/block
```

Authentication:

- CUSTOMER

The reason may be one of the supported product values, for example:

- CUSTOMER_REQUEST
- SUSPECTED_FRAUD
- LOST
- STOLEN

A lost/stolen card becomes permanently unusable according to the product rules and must use the corresponding terminal card state rather than a normal reversible block.

## 84.7 Unblock a debit card

```http
POST /api/v1/debit-cards/{cardId}/unblock
```

Authentication:

- CUSTOMER

Only cards in a reversibly blocked state may be unblocked.

The backend rechecks account/card eligibility and the configured authentication requirement.

## 84.8 Update debit-card usage controls

The customer can change online/e-commerce and international usage settings.

```http
PATCH /api/v1/debit-cards/{cardId}/controls
```

Example:

```json
{
  "onlineEnabled": true,
  "internationalEnabled": false
}
```

Online and international access are OFF by default.

Backend validates the requested changes against card state and policy.

## 84.9 Request debit-card limit change

```http
POST /api/v1/debit-cards/{cardId}/limit-requests
```

Authentication:

- CUSTOMER

Whether the request is applied immediately or requires Manager approval depends on the configured card policy.

The API contract must not allow the frontend to assume that a requested limit is automatically effective.

Possible result:

```text
201 Created
```

with a business status such as:

```json
{
  "status": "PENDING_MANAGER_REVIEW"
}
```

when approval is required.

When Manager review is required, the request is persisted in card_limit_request targeting the debit card.

## 84.10 Replace debit card

```http
POST /api/v1/debit-cards/{cardId}/replacement
```

Authentication:

- CUSTOMER

Replacement rules:

- The old card is made unusable according to the replacement flow.
- The new card remains linked to the same account.
- Card history is retained.
- Protected card secrets are not returned in ordinary responses.

A replacement operation must be protected against duplicate submission with idempotency handling.

## 84.10.1 Simulated Debit-Card Purchase

```http
POST /api/v1/debit-cards/{cardId}/purchases
```

Authentication:

- CUSTOMER

Request example:

```json
{
  "amount": 2500.00,
  "merchantName": "Example Merchant",
  "merchantCategory": "GENERAL",
  "isInternational": true,
  "onlinePurchase": true,
  "pin": "••••",
  "verificationChallengeId": "..."
}
```

The backend validates card/account ownership, activation/status, account eligibility, PIN, online/international controls, transaction and monthly limits, merchant category, available account balance and required OTP policy. The active card's per-transaction controls apply. Monthly domestic/international usage is aggregated across the purchase history of all current and replaced debit cards linked to the same account, so replacement cannot reset the account's monthly usage.

The base purchase and any international fee are posted as one permanent financial transaction. The active Admin-configured international purchase fee rate is a percentage of the base purchase amount, initially 0%, rounded to two decimal places. The applied fee is recorded separately in `fee_amount` and credited to bank-level Fee Income.

For a purchase of amount `A` with fee `F`, the customer account and its branch's Customer Deposit Position decrease by `A + F`. The balanced ledger debits Customer Deposits by `A + F`, credits Merchant Settlement Clearing by `A`, and credits Fee Income by `F`. The transaction records the card used, merchant category and international flag. All financial effects commit atomically.

Success:

```http
200 OK
```

An `Idempotency-Key` is required.

---

## 84.11 Debit-card transaction history

```http
GET /api/v1/debit-cards/{cardId}/transactions
```

Authentication:

- CUSTOMER

Supported query parameters include where applicable:

```text
page
pageSize
from
to
direction
status
```

The final response uses the common paginated response format defined earlier in this document.

Debit-card transaction history is customer-facing history. It filters permanent financial transaction records by the specific `debit_card_id` used and does not replace the permanent financial transaction and accounting records. Monthly-limit checks aggregate current and replaced cards linked to the same account.

---

# 85. Debit Card Lifecycle

The API exposes only transitions that are valid for the current card state.

A simplified lifecycle is:

```text
ISSUED
   ↓
ACTIVATED
   ↓
ACTIVE ↔ BLOCKED
   ↓
REPLACED / EXPIRED / CLOSED
```

For a lost/stolen card:

```text
ACTIVE / BLOCKED
      ↓
LOST_OR_STOLEN
      ↓
PERMANENTLY_UNUSABLE
```

The exact enum names may be finalized with implementation, but the public API must expose a stable business status rather than internal database implementation details.

---

# 86. Credit Card API

Credit cards are customer-owned resources and are not permanently owned by any single payment account.

A credit card can use an eligible active account as a bill-payment source. The source account can be changed through the defined authenticated workflow.

Credit-card applications require Manager review.

## 86.1 Apply for a credit card

```http
POST /api/v1/credit-card-applications
```

Authentication:

- CUSTOMER

The application is an expirable customer request. The request includes `branchId`, which identifies the Manager review queue.

The backend validates that the customer has at least one `ACTIVE` account in `branchId`, validates configured eligibility, and prevents duplicate active applications where the product rule disallows them.

The request uses an `Idempotency-Key` header.

Success:

```text
201 Created
```

Example response:

```json
{
  "applicationId": "cca_123",
  "status": "APPLICATION",
  "validTill": "2026-10-10T12:00:00Z",
  "createdAt": "2026-10-06T12:00:00Z"
}
```

## 86.2 List credit-card applications

```http
GET /api/v1/credit-card-applications
```

Authentication:

- CUSTOMER

Supports filtering by application status where useful.

## 86.3 Get credit-card application

```http
GET /api/v1/credit-card-applications/{applicationId}
```

Authentication:

- CUSTOMER

Customer can see only their own application.

The response can include:

- Application state
- Requested information
- Review outcome
- Rejection reason where applicable
- Approved terms where applicable
- `validTill`
- Timestamps

## 86.4 Available credit cards

The final product may expose a product/catalog endpoint when more than one credit-card product exists.

V1 should only introduce a separate product resource if the existing Admin credit-card configuration requires customer-facing selection. Otherwise, the application uses the configured credit-card policy directly without introducing an unnecessary catalog API.

---

# 87. Credit Card Resource

After approval and issuance, the actual card becomes a separate resource.

## 87.1 List credit cards

```http
GET /api/v1/credit-cards
```

Authentication:

- CUSTOMER

## 87.2 Get credit card

```http
GET /api/v1/credit-cards/{cardId}
```

Authentication:

- CUSTOMER

The response can include:

- Card ID
- Masked card number
- Last four digits
- Status
- Expiry
- Credit limit
- Available credit (remaining credit limit plus current `credit_balance` under the V1 credit-balance rule)
- Current outstanding
- Current `credit_balance`
- Online/e-commerce enabled state
- International enabled state
- Minimum due
- Payment due date
- Automatic payment source account
- Reward balance

Sensitive secrets are never returned through the normal resource endpoint.

## 87.3 Activate credit card

```http
POST /api/v1/credit-cards/{cardId}/activate
```

The customer must complete the configured activation/authentication flow before the card becomes usable.

## 87.4 Set or change credit-card PIN

```http
PUT /api/v1/credit-cards/{cardId}/pin
```

The PIN is never returned by the API and is never persisted in plain text.

## 87.5 Block credit card

```http
POST /api/v1/credit-cards/{cardId}/block
```

## 87.6 Unblock credit card

```http
POST /api/v1/credit-cards/{cardId}/unblock
```

Unblock is allowed only from a reversible blocked state and after the configured authentication checks.

## 87.7 Update credit-card controls

```http
PATCH /api/v1/credit-cards/{cardId}/controls
```

Request:

```json
{
  "onlineEnabled": false,
  "internationalEnabled": false
}
```

The customer may update only these two controls on their own credit card. Both controls default to `false`. The backend rejects unknown fields, validates the current card state and records the change through the normal audit path. Disabling a control does not alter completed purchase history.

---

## 87.8 Simulated Credit-Card Purchase

```http
POST /api/v1/credit-cards/{cardId}/purchases
```

Authentication:

- CUSTOMER

Request example:

```json
{
  "amount": 2500.00,
  "merchantName": "Example Merchant",
  "merchantCategory": "GENERAL",
  "isInternational": true,
  "onlinePurchase": true,
  "pin": "••••",
  "verificationChallengeId": "..."
}
```

The backend validates ownership, card activation/status, PIN, online/international controls, merchant-category rules, credit availability and required OTP policy. `verificationChallengeId` is consumed only when the applicable second factor has been verified.

The purchase and any international fee are one permanent financial transaction. The active Admin-configured international purchase fee rate is a percentage of the base purchase amount, initially 0%, rounded to two decimal places. The applied `feeAmount` and effective rate are snapshotted for the completed transaction.

The backend applies available `credit_balance` against the purchase and fee first, records the amount consumed in `credit_balance_applied_amount`, reduces `credit_balance` by that amount, and increases `outstanding_amount` only by the remaining amount. Billing-cycle spending records the gross purchase and fee; closing outstanding reflects the actual amount owed after applied credit balance and payments. It creates no customer-account effect or branch customer-deposit movement at purchase time. The ledger debits Credit-Card Receivables for the portion added to the outstanding amount and debits Customer Credit-Card Balance Liability for any consumed credit balance; it credits Merchant Settlement Clearing for the base purchase and Fee Income for the fee. Ledger debits and credits must balance.

Success:

```http
200 OK
```

The result includes the transaction reference, base amount, applied fee and rate, any `creditBalanceAppliedAmount`, payment method, timestamp and current card/billing result. An `Idempotency-Key` is required.

---

## 87.9 Credit-Card Purchase History

```http
GET /api/v1/credit-cards/{cardId}/transactions
```

Returns only purchases for the authenticated customer's card, with common pagination and date/category filters. The response derives from permanent financial transaction records and includes purchase amount, separately recorded fee, merchant/category information, international flag, transaction date and billing-cycle reference where available.

---

# 88. Credit Card Limit APIs

Credit-card limit changes use a separate request workflow when Manager review is required.

## 88.1 Request a limit increase

```http
POST /api/v1/credit-cards/{cardId}/limit-requests
```

Authentication:

- CUSTOMER

Request body:

```json
{
  "requestedLimit": 100000,
  "reason": "Increased monthly spending requirement"
}
```

The backend verifies:

- Card belongs to customer
- Card is eligible for a limit request
- Requested amount is valid under product policy
- No conflicting active limit request exists for the card

The active request stores `validTill` using the current Admin-configured request expiry policy.

Success:

```text
201 Created
```

## 88.2 Get limit requests

```http
GET /api/v1/credit-cards/{cardId}/limit-requests
```

The customer sees only their own request history that remains available through the operational model.

## 88.3 Get a limit request

```http
GET /api/v1/credit-cards/{cardId}/limit-requests/{requestId}
```

The response includes the request status and resolution information.

There must be at most one active limit request for a credit card.

---

# 89. Credit Card Payment APIs

A customer can pay a credit-card bill from any eligible active customer account.

The payment is a financial operation, not merely a credit-card field update.

## 89.1 Make a credit-card payment

```http
POST /api/v1/credit-cards/{cardId}/payments
```

Authentication:

- CUSTOMER

Request body:

```json
{
  "sourceAccountId": "acc_123",
  "amount": 5000.00,
  "remark": "Monthly card payment"
}
```

The backend verifies:

- Card belongs to customer
- Source account belongs to customer
- Source account is eligible and active
- Payment amount is greater than zero and does not exceed the total remaining unpaid billed amount
- Payment is allocated to the oldest unpaid billed cycle first
- Payment is allowed under card rules
- Sufficient funds exist where required
- Current card/billing state is valid

Partial payment is allowed subject to the applicable minimum-due rules. The requested amount must not exceed the total remaining unpaid billed amount across the customer's cycles. V1 rejects an overpayment with `422 Unprocessable Entity` and `PAYMENT_EXCEEDS_OUTSTANDING`; an overpayment does not create `credit_balance`. The backend allocates the payment to the oldest unpaid billed cycle first and continues to the next cycle only when the earlier cycle's remaining amount is covered. The per-cycle allocations are stored against the permanent financial payment record.

Both manual payment and the due-date automatic-payment job lock the credit card and affected billing cycle, re-read the remaining unpaid amount and create only one debit/payment effect under concurrency. The auto-payment job attempts the full remaining statement balance on the cycle's due date. Automatic payment is all-or-nothing: insufficient funds or another failure creates no partial debit and no financial movement. Failed attempts do not create financial movement. At the end of the due date in `Asia/Kolkata`, if `paid_amount < minimum_due`, the cycle is marked `OVERDUE`; late fees, interest and Trust Score effects are posted only once.

The financial effects include:

```text
Source account financial movement
        ↓
Credit-card payment / outstanding reduction
        ↓
Required ledger entries
        ↓
Any required branch financial movement
```

All required effects are committed atomically.

The resulting financial transaction is permanent and immutable after finalization.

The API uses an `Idempotency-Key` because duplicate submission must not create a second payment.

## 89.2 Get credit-card payment history

```http
GET /api/v1/credit-cards/{cardId}/payments
```

Supports the common pagination and date/filter conventions.

Payment history should be derived from the underlying permanent financial/payment records rather than from a separate duplicate financial source of truth.

---

# 90. Automatic Credit Card Payment Account

## 90.1 Get current automatic-payment account

The credit card resource can include the current source account reference.

## 90.2 Set or replace automatic-payment account

```http
PUT /api/v1/credit-cards/{cardId}/automatic-payment-account
```

Request:

```json
{
  "accountId": "acc_456"
}
```

Authentication:

- CUSTOMER

The backend verifies that the selected account is an eligible active account belonging to the customer.

The relationship is to the payment source account, not to ownership of the credit card.

Changing this relationship is an authenticated state change and must be atomic.

If the currently selected account becomes invalid later, the automatic-payment relationship must be resolved according to the credit-card/account lifecycle rules rather than silently switching to another account.

---

# 91. Credit Card Billing Cycle APIs

Billing cycles are permanent historical financial records.

## 91.1 List billing cycles

```http
GET /api/v1/credit-cards/{cardId}/billing-cycles
```

Authentication:

- CUSTOMER

Supports pagination and optional date filtering.

## 91.2 Get billing cycle

```http
GET /api/v1/credit-cards/{cardId}/billing-cycles/{cycleId}
```

The response can include:

- Cycle start/end dates and statement date
- Opening outstanding
- Spending
- Closing outstanding
- Minimum due
- Paid amount
- Interest
- Fees
- Reward points
- Automatic-payment attempt status/time
- Overdue information
- Payment due date

Historical billing-cycle values are not rewritten because Admin later changes the general product policy. The cycle statement date and payment due date are separate values. Admin-configured monthly statement day and due-date offset are applied to new cycles; if the statement day does not exist in a month, the final calendar day is used.

---

# 92. Credit Card Rewards APIs

Rewards have their own earning and redemption history.

## 92.1 Get reward balance

```http
GET /api/v1/credit-cards/{cardId}/rewards
```

Authentication:

- CUSTOMER

## 92.2 Get reward history

```http
GET /api/v1/credit-cards/{cardId}/rewards/history
```

Supports pagination and optional date filtering.

## 92.3 Redeem rewards

```http
POST /api/v1/credit-cards/{cardId}/rewards/redemptions
```

Authentication:

- CUSTOMER

Request body can contain the points/amount to redeem according to the final reward rules.

A redemption is a state-changing financial/reward operation and uses idempotency protection.

Reward earning and redemption create permanent reward events and balanced ledger entries. Reward earning debits Rewards Expense and credits Rewards Liability. Redemption debits Rewards Liability and credits Credit-Card Receivables for the portion applied to existing debt; an excess reward value credits Customer Credit-Card Balance Liability and increases `credit_balance`. The reward event, card balance change and ledger entries commit atomically.

Reward corrections use compensating/reversal records rather than editing old reward history.

---

# 93. Credit Card Closure

A credit card cannot be closed while outstanding obligations remain.

The customer-facing closure endpoint is:

```http
POST /api/v1/credit-cards/{cardId}/closure
```

Authentication:

- CUSTOMER

The backend checks outstanding obligations before allowing closure.

If the card is eligible for closure, the card transitions to its terminal closed state according to the card lifecycle rules.

The API must not delete billing cycles, payment history, reward history or other permanent financial records.

---

# 94. Card Security and API Rules

The card APIs follow these rules across debit and credit cards:

### Sensitive data

Normal APIs return masked card identifiers only.

Full simulated card numbers are encrypted at rest and are not included in ordinary collection/detail responses.

CVV is not stored as a normal database field. The V1 implementation uses the previously defined protected derivation approach.

PIN values are never stored or logged in plain text.

### Authorization

Every card API verifies ownership server-side.

A customer cannot access another customer's card by changing an ID in the URL.

### Authentication for sensitive actions

The stronger configured authentication level is required for actions such as:

- PIN changes
- Card block/unblock where policy requires it
- Automatic-payment account changes
- Limit changes
- Reward redemption where required
- Other high-risk card operations

### Logging

Logs must never contain:

- Full card numbers
- PINs
- CVVs
- Authentication secrets
- Raw OTP values

Audit records are separate from application logs.

### Financial atomicity

A card-related financial operation is not considered successful merely because the card's business status changed.

Where money moves, the required account/card/branch/ledger records are committed in one database transaction.

---

# 95. Card Error Rules

Common card-specific error codes include:

| Error code | Meaning |
|---|---|
| `CARD_NOT_FOUND` | Card does not exist or is not visible to the caller |
| `CARD_NOT_ELIGIBLE` | Card/product eligibility rules are not satisfied |
| `CARD_ALREADY_ISSUED` | The account already has its allowed debit card |
| `CARD_NOT_ACTIVE` | Operation requires an active card |
| `CARD_ALREADY_ACTIVE` | Activation was already completed |
| `CARD_BLOCKED` | Card is currently blocked |
| `CARD_PERMANENTLY_UNUSABLE` | Card cannot be reactivated or reused |
| `CARD_EXPIRED` | Card has passed its expiry |
| `PIN_OPERATION_BLOCKED` | PIN operation is temporarily blocked |
| `INVALID_CARD_LIMIT` | Requested card limit is outside policy |
| `ACTIVE_LIMIT_REQUEST_EXISTS` | Another card-limit request is already pending |
| `CREDIT_CARD_APPLICATION_EXPIRED` | The application is no longer valid |
| `CREDIT_CARD_OBLIGATION_OUTSTANDING` | Card cannot be closed while obligations remain |
| `INVALID_PAYMENT_ACCOUNT` | Selected source account cannot be used for the card payment |
| `INSUFFICIENT_FUNDS` | Source account cannot fund the requested payment |
| `REWARD_REDEMPTION_NOT_ALLOWED` | Reward redemption is not valid under current rules |

Existing common errors such as `FORBIDDEN`, `CONFLICT`, `VALIDATION_ERROR` and `RATE_LIMITED` are still used where they are more appropriate than a card-specific code.

---

# 96. Card Concurrency and Idempotency

Important races include:

```text
Customer requests debit card
        VS
Previous issuance already exists

Customer activates card
        VS
Second activation request

Customer blocks card
        VS
Card payment attempt

Customer replaces card
        VS
Card operation in progress

Customer pays credit-card bill
        VS
Automatic payment attempt

Customer changes automatic-payment account
        VS
Automatic deduction job

Customer submits limit request
        VS
API retry / duplicate submission

Manager resolves limit/application request
        VS
Customer cancellation or expiry
```

The final state must remain valid and only one conflicting transition may win.

For financial operations, database transaction boundaries and the existing idempotency model provide the final protection.

The frontend must never rely on button disabling or local state alone to prevent duplicate card operations.

---

# 97. Card API Summary

| Operation | Method | Endpoint | Auth | Result |
|---|---|---|---|---|
| Request debit card | `POST` | `/api/v1/debit-cards` | CUSTOMER | `201 Created` |
| List debit cards | `GET` | `/api/v1/debit-cards` | CUSTOMER | `200 OK` |
| Get debit card | `GET` | `/api/v1/debit-cards/{id}` | CUSTOMER | `200 OK` |
| Activate debit card | `POST` | `/api/v1/debit-cards/{id}/activate` | CUSTOMER | `200 OK` |
| Change debit PIN | `PUT` | `/api/v1/debit-cards/{id}/pin` | CUSTOMER | `200 OK` |
| Block debit card | `POST` | `/api/v1/debit-cards/{id}/block` | CUSTOMER | `200 OK` |
| Unblock debit card | `POST` | `/api/v1/debit-cards/{id}/unblock` | CUSTOMER | `200 OK` |
| Update debit controls | `PATCH` | `/api/v1/debit-cards/{id}/controls` | CUSTOMER | `200 OK` |
| Request debit limit change | `POST` | `/api/v1/debit-cards/{id}/limit-requests` | CUSTOMER | `201 Created` / direct result |
| Replace debit card | `POST` | `/api/v1/debit-cards/{id}/replacement` | CUSTOMER | `201 Created` |
| Debit transactions | `GET` | `/api/v1/debit-cards/{id}/transactions` | CUSTOMER | `200 OK` |
| Apply for credit card | `POST` | `/api/v1/credit-card-applications` | CUSTOMER | `201 Created` |
| List credit-card applications | `GET` | `/api/v1/credit-card-applications` | CUSTOMER | `200 OK` |
| Get credit-card application | `GET` | `/api/v1/credit-card-applications/{id}` | CUSTOMER | `200 OK` |
| List credit cards | `GET` | `/api/v1/credit-cards` | CUSTOMER | `200 OK` |
| Get credit card | `GET` | `/api/v1/credit-cards/{id}` | CUSTOMER | `200 OK` |
| Activate credit card | `POST` | `/api/v1/credit-cards/{id}/activate` | CUSTOMER | `200 OK` |
| Change credit PIN | `PUT` | `/api/v1/credit-cards/{id}/pin` | CUSTOMER | `200 OK` |
| Block credit card | `POST` | `/api/v1/credit-cards/{id}/block` | CUSTOMER | `200 OK` |
| Unblock credit card | `POST` | `/api/v1/credit-cards/{id}/unblock` | CUSTOMER | `200 OK` |
| Request credit limit increase | `POST` | `/api/v1/credit-cards/{id}/limit-requests` | CUSTOMER | `201 Created` |
| List credit limit requests | `GET` | `/api/v1/credit-cards/{id}/limit-requests` | CUSTOMER | `200 OK` |
| Get limit request | `GET` | `/api/v1/credit-cards/{id}/limit-requests/{requestId}` | CUSTOMER | `200 OK` |
| Pay credit-card bill | `POST` | `/api/v1/credit-cards/{id}/payments` | CUSTOMER | `200 OK` |
| Credit-card payment history | `GET` | `/api/v1/credit-cards/{id}/payments` | CUSTOMER | `200 OK` |
| Set automatic-payment account | `PUT` | `/api/v1/credit-cards/{id}/automatic-payment-account` | CUSTOMER | `200 OK` |
| List billing cycles | `GET` | `/api/v1/credit-cards/{id}/billing-cycles` | CUSTOMER | `200 OK` |
| Get billing cycle | `GET` | `/api/v1/credit-cards/{id}/billing-cycles/{cycleId}` | CUSTOMER | `200 OK` |
| Get reward balance | `GET` | `/api/v1/credit-cards/{id}/rewards` | CUSTOMER | `200 OK` |
| Get reward history | `GET` | `/api/v1/credit-cards/{id}/rewards/history` | CUSTOMER | `200 OK` |
| Redeem rewards | `POST` | `/api/v1/credit-cards/{id}/rewards/redemptions` | CUSTOMER | `200 OK` |
| Close credit card | `POST` | `/api/v1/credit-cards/{id}/closure` | CUSTOMER | `200 OK` |

---

# 98. Loan and EMI APIs

This section defines the customer-facing API contract for loan products, loan applications, active loans and EMI operations.

The API follows the existing financial rules:

- Loan products are configured by Admin.
- Existing loans keep their own contractual terms.
- Loan applications require Manager review.
- A customer may be offered a different approved amount.
- A modified offer must be accepted or declined by the customer before final processing.
- Loan disbursement is performed only after the required Manager processing.
- EMI schedules are generated from the loan's contractual terms.
- EMI payment is a financial operation and must use the normal transaction/idempotency rules.
- Partial EMI payment is not supported.
- Multiple future EMIs may be paid in advance according to the allocation rules.
- Partial prepayment of the loan principal is not supported.
- Early full closure is supported and applies the configured early-closure/prepayment charge.
- Automatic EMI deduction uses one configured account per loan and never silently switches to another account.

This section defines the customer-facing loan resources and the financial/EMI operation boundaries that those Manager APIs act upon.

---

## 98.1 List Loan Products

```http
GET /api/v1/loan-products
```

Purpose:

Return the currently available loan products that a customer can consider for a new application.

Authentication:

- CUSTOMER

The response is backend-driven. The frontend must not hard-code the available loan types.

A product can expose information such as:

- Product ID/code
- Name
- Description
- Minimum amount
- Maximum amount
- Interest rate
- Minimum tenure
- Maximum tenure
- Processing fee
- Early-closure/prepayment charge rule
- Eligibility summary where safe to display

Only active products available for new applications should be returned by the normal customer endpoint.

Success:

```http
200 OK
```

---

## 98.2 Get Loan Product

```http
GET /api/v1/loan-products/{productId}
```

Purpose:

Return the current details of one active loan product.

Authentication:

- CUSTOMER

A customer cannot use this endpoint to access inactive/internal product configuration that is not intended for customer display.

Success:

```http
200 OK
```

Possible errors:

- `LOAN_PRODUCT_NOT_FOUND`
- `LOAN_PRODUCT_NOT_AVAILABLE`

---

# 99. Loan Application APIs

## 99.1 Create Loan Application

```http
POST /api/v1/loan-applications
```

Authentication:

- CUSTOMER

Request:

```json
{
  "loanProductId": "loan-product-123",
  "branchId": "branch-123",
  "disbursementAccountId": "acc-123",
  "requestedAmount": 250000.00,
  "requestedTenureMonths": 36
}
```

Optional fields can be added only where required by the product workflow. The server determines the actual applicable interest rate, fees and eligibility rules from the current product/configuration.

Validation includes:

- Product exists and is available for new applications.
- Requested amount is within current product limits.
- Requested tenure is within current product limits.
- Customer satisfies current eligibility rules.
- Customer has at least one `ACTIVE` account in `branchId`; this branch scopes the Manager review queue.
- If `disbursementAccountId` is supplied, it belongs to the customer, is active/eligible and belongs to `branchId`.
- Required customer/account state is valid.
- Another active application for the same customer and product does not already exist where the business rule blocks duplicates.

Success:

```http
201 Created
```

Example response:

```json
{
  "applicationId": "loan-app-123",
  "loanProductId": "loan-product-123",
  "requestedAmount": 250000.00,
  "requestedTenureMonths": 36,
  "status": "PENDING_REVIEW",
  "createdAt": "2026-10-06T18:30:00Z",
  "validTill": "2026-10-20T18:30:00Z"
}
```

The `validTill` value is calculated using the active Admin policy at creation time. Later configuration changes do not modify an existing application's stored expiry.

The application is an operational request, not a financial transaction. No account balance or ledger movement occurs when the application is created.

Idempotency:

Required. Use the common `Idempotency-Key` header.

Possible errors:

- `LOAN_PRODUCT_NOT_AVAILABLE`
- `LOAN_AMOUNT_OUT_OF_RANGE`
- `LOAN_TENURE_OUT_OF_RANGE`
- `LOAN_NOT_ELIGIBLE`
- `ACTIVE_LOAN_APPLICATION_EXISTS`
- `VALIDATION_ERROR`
- `IDEMPOTENCY_CONFLICT`

---

## 99.2 List My Loan Applications

```http
GET /api/v1/loan-applications
```

Authentication:

- CUSTOMER

Supports common pagination and optional filters such as:

- `status`
- `loanProductId`
- `createdFrom`
- `createdTo`

The endpoint returns only applications owned by the authenticated customer.

Success:

```http
200 OK
```

---

## 99.3 Get Loan Application

```http
GET /api/v1/loan-applications/{applicationId}
```

Authentication:

- CUSTOMER

The response should contain enough information for the customer to understand the current application state, including where applicable:

- Requested amount
- Requested tenure
- Current status
- Current Manager-reviewed amount/offer
- Offer expiry
- Customer acceptance requirement
- Rejection reason
- Created/updated timestamps

A customer cannot access another customer's application.

Success:

```http
200 OK
```

Possible errors:

- `LOAN_APPLICATION_NOT_FOUND`
- `FORBIDDEN`

---

## 99.4 Cancel Loan Application

```http
POST /api/v1/loan-applications/{applicationId}/cancellation
```

Authentication:

- CUSTOMER

The operation is allowed only while the application is in a state where customer cancellation is permitted.

The API must reject cancellation after Manager approval when the current workflow no longer permits customer cancellation.

This is an operational state change and does not create a financial record.

Idempotency:

Required because this changes persistent workflow state.

Success:

```http
200 OK
```

Possible errors:

- `LOAN_APPLICATION_NOT_FOUND`
- `LOAN_APPLICATION_NOT_CANCELLABLE`
- `LOAN_APPLICATION_ALREADY_RESOLVED`
- `IDEMPOTENCY_CONFLICT`

---

## 99.5 Accept Manager-Approved Loan Offer

When the Manager approves an amount different from the originally requested amount, the customer must explicitly accept or decline the offer before final processing.

```http
POST /api/v1/loan-applications/{applicationId}/offer-acceptance
```

Request:

```json
{
  "decision": "ACCEPT"
}
```

Allowed decisions at this endpoint are:

- `ACCEPT`
- `DECLINE`

Authentication:

- CUSTOMER
- Required strong authentication where the active policy requires it

Validation:

- Application belongs to the customer.
- A Manager-approved customer offer exists.
- Offer has not expired.
- Application has not already been resolved.

For `ACCEPT`:

```text
Manager-approved offer
        ↓
Customer accepts
        ↓
Automatic loan processing and disbursement
```

Because the Manager has already made a final approved offer, customer acceptance directly processes and disburses the loan without returning the application to the Manager review queue. Loan applications and Manager-approved offers store `validTill`; expired applications/offers cannot be processed and are removed by the scheduled cleanup worker.

For `DECLINE`:

```text
Manager-approved offer
        ↓
Customer declines
        ↓
Customer-declined outcome
```

Success:

```http
200 OK
```

The exact final application status names must match the backend enum defined during implementation.

Idempotency:

Required.

Possible errors:

- `LOAN_APPLICATION_NOT_FOUND`
- `LOAN_OFFER_NOT_AVAILABLE`
- `LOAN_OFFER_EXPIRED`
- `LOAN_OFFER_ALREADY_DECIDED`
- `LOAN_APPLICATION_ALREADY_RESOLVED`
- `VALIDATION_ERROR`
- `IDEMPOTENCY_CONFLICT`

---

## 99.6 Set or Change Disbursement Account

The customer may choose or replace the account that will receive a loan disbursement before final processing.

```http
PUT /api/v1/loan-applications/{applicationId}/disbursement-account
```

Authentication:

- CUSTOMER

Request:

```json
{
  "accountId": "acc-123",
  "verificationChallengeId": "challenge-123"
}
```

The backend verifies:

- Application belongs to the authenticated customer.
- Application is still eligible for a destination-account change.
- Selected account belongs to the customer.
- Selected account is active and eligible to receive the disbursement.
- Required strong authentication has succeeded.

The operation is not allowed after final processing/disbursement has started.

The application stores the temporary destination and its `branchId` review scope. After disbursement, the final `loan.disbursement_account` becomes the historical contractual destination. The loan's servicing `branchId` is stored separately from its `emiPaymentAccountId`; changing the EMI payment account to an account in another branch does not automatically change the loan's servicing branch.

Success:

```text
200 OK
```

Submitting the same valid account again is idempotent.

# 100. Loan APIs

## 100.1 List My Loans

```http
GET /api/v1/loans
```

Authentication:

- CUSTOMER

Supports pagination and useful filters such as:

- `status`
- `loanProductId`
- `fromDate`
- `toDate`

Only loans belonging to the authenticated customer are returned.

Success:

```http
200 OK
```

---

## 100.2 Get Loan Details

```http
GET /api/v1/loans/{loanId}
```

Authentication:

- CUSTOMER

The response can include:

- Loan ID
- Loan product
- Principal
- Interest rate
- Tenure
- EMI amount
- Outstanding principal
- Outstanding amount
- Applicable charges
- Loan status
- Disbursement date
- Next due EMI
- Automatic EMI deduction account summary

The response must use the loan's stored contractual terms rather than recalculating historical contract values from the current LoanProduct configuration.

Success:

```http
200 OK
```

Possible errors:

- `LOAN_NOT_FOUND`
- `FORBIDDEN`

---

## 100.3 Get Loan Summary

```http
GET /api/v1/loans/{loanId}/summary
```

Authentication:

- CUSTOMER

Decide whether the endpoint is in scope. If yes, replace that sentence with a direct purpose, such as: “Returns loan summary data for dashboard and loan-detail views.” If no, remove the endpoint section and references to it.

Summary can include:

- Original principal
- Outstanding principal
- Total amount already paid
- Next EMI
- Number of EMIs paid
- Number of EMIs remaining
- Current overdue state where applicable
- Early-closure amount/availability summary where applicable

Success:

```http
200 OK
```

---

## 100.4 Change Loan EMI Deduction Account

Each loan has exactly one automatic EMI deduction account.

```http
PUT /api/v1/loans/{loanId}/emi-payment-account
```

Request:

```json
{
  "accountId": "account-456",
  "verificationChallengeId": "challenge-123"
}
```

Authentication:

- CUSTOMER
- Strong authentication required

Validation:

- Loan belongs to the authenticated customer.
- Selected account belongs to the customer.
- Selected account is eligible and active.
- Account is not otherwise incompatible with the loan's payment rules.
- Required strong-authentication challenge is valid and intended for this operation.

The replacement is atomic. There must never be a persisted intermediate state where the loan has no payment account because the old relationship was cleared before the new one was successfully stored.

The system must not automatically choose a different account when the configured account later has insufficient funds.

Success:

```http
200 OK
```

Possible errors:

- `LOAN_NOT_FOUND`
- `INVALID_PAYMENT_ACCOUNT`
- `ACCOUNT_NOT_ELIGIBLE`
- `ACCOUNT_NOT_ACTIVE`
- `SECOND_FACTOR_REQUIRED`
- `INVALID_CHALLENGE`
- `CONFLICT`

---

# 101. EMI APIs

## 101.1 List Loan EMIs

```http
GET /api/v1/loans/{loanId}/emis
```

Authentication:

- CUSTOMER

Supports pagination and filters such as:

- `status`
- `dueFrom`
- `dueTo`

The response contains the stored schedule values for each installment, including:

- EMI ID
- Installment number
- Due date
- EMI amount
- Principal component
- Interest component
- Remaining principal after installment
- Status
- Paid date
- Penalty where applicable
- Retry information where applicable

The API reads the stored EMI schedule. It does not regenerate historical EMI values from the current interest configuration.

Success:

```http
200 OK
```

---

## 101.2 Get EMI Details

```http
GET /api/v1/loans/{loanId}/emis/{emiId}
```

Authentication:

- CUSTOMER

The EMI must belong to the specified loan and the loan must belong to the authenticated customer.

Success:

```http
200 OK
```

Possible errors:

- `LOAN_NOT_FOUND`
- `EMI_NOT_FOUND`
- `FORBIDDEN`

---

## 101.3 Pay EMI / Advance Future EMIs

A single payment endpoint is used for normal EMI repayment so that the financial movement rules remain centralized.

```http
POST /api/v1/loans/{loanId}/emi-payments
```

Request for the current due EMI:

```json
{
  "sourceAccountId": "account-456",
  "paymentMode": "DUE",
  "verificationChallengeId": "challenge-123"
}
```

Request for advance payment:

```json
{
  "sourceAccountId": "account-456",
  "paymentMode": "ADVANCE",
  "numberOfEmis": 3,
  "verificationChallengeId": "challenge-123"
}
```

Authentication:

- CUSTOMER
- Strong authentication according to the active financial-operation policy

Rules:

- Source account must belong to the customer and be eligible for the payment.
- Source account must be active at the time of payment.
- Partial EMI payment is not supported.
- A DUE payment covers a complete eligible EMI according to the current allocation rules.
- ADVANCE payment can cover multiple future EMIs according to the loan's allocation rules.
- The backend determines the final principal/interest allocation.
- The frontend must not calculate or submit authoritative principal/interest splits.
- If an EMI was already paid through an earlier manual payment or automatic deduction, a retry must not deduct it again.
- The payment updates the loan, EMI schedule and financial records atomically.

Successful financial payment produces the normal permanent financial transaction.

Idempotency:

Required.

Success:

```http
200 OK
```

Example response:

```json
{
  "paymentReference": "TXN-123",
  "loanId": "loan-123",
  "paidEmis": [
    "emi-1001",
    "emi-1002",
    "emi-1003"
  ],
  "totalPaid": 45000.00,
  "loanOutstandingPrincipal": 180000.00,
  "status": "SUCCESS"
}
```

Possible errors include:

- `LOAN_NOT_FOUND`
- `LOAN_NOT_PAYABLE`
- `EMI_ALREADY_PAID`
- `INVALID_PAYMENT_ACCOUNT`
- `ACCOUNT_NOT_ACTIVE`
- `INSUFFICIENT_FUNDS`
- `PARTIAL_EMI_NOT_ALLOWED`
- `INVALID_ADVANCE_PAYMENT`
- `SECOND_FACTOR_REQUIRED`
- `INVALID_CHALLENGE`
- `IDEMPOTENCY_CONFLICT`
- `CONCURRENT_LOAN_OPERATION`

EMI payment processing is completed synchronously inside the PostgreSQL financial transaction. Asynchronous scheduling applies to automatic EMI execution, not to an immediate customer payment request.

---

## 101.4 Get EMI Payment History

```http
GET /api/v1/loans/{loanId}/payments
```

Authentication:

- CUSTOMER

Supports pagination and date filtering.

The endpoint returns permanent financial payment records associated with the loan. It does not create a second payment-history source of truth.

Success:

```http
200 OK
```

---

# 102. Early Full Loan Closure APIs

Partial prepayment is not supported.

The supported early-closure path is full repayment of the remaining amount followed by closure.

## 102.1 Get Early-Closure Quote

```http
GET /api/v1/loans/{loanId}/early-closure-quote
```

Authentication:

- CUSTOMER

Purpose:

Calculate the current amount required for early full closure, including applicable charges and excluding future interest that would no longer be earned.

The quote is informational and is not itself a financial mutation.

The response can include:

- Outstanding principal
- Applicable early-closure/prepayment charge
- Other currently applicable charges
- Total amount required for closure
- Quote timestamp

Because the loan balance can change before the actual payment, the quote is not a guarantee that the final payment amount will remain identical.

Success:

```http
200 OK
```

---

## 102.2 Close Loan Early

```http
POST /api/v1/loans/{loanId}/early-closure
```

Request:

```json
{
  "sourceAccountId": "account-456",
  "verificationChallengeId": "challenge-123"
}
```

Authentication:

- CUSTOMER
- Strong authentication required

The backend recalculates the final closure amount inside the financial operation. It must not trust an amount previously displayed by the frontend.

Rules:

- Loan must be eligible for early closure.
- Source account must belong to the customer and be active/eligible.
- Required outstanding principal and applicable charges must be available.
- Future interest that would not have been earned is not charged.
- Applicable early-closure/prepayment charge is charged.
- All loan, account and accounting effects are committed atomically.
- The loan becomes closed only when the required financial settlement succeeds.

The EMI schedule is not rewritten to simulate partial prepayment because partial prepayment is not supported. After full closure, remaining future EMI records follow the final loan-closure handling defined by the data/lifecycle model while historical financial records remain permanent.

Idempotency:

Required.

Success:

```http
200 OK
```

Possible errors include:

- `LOAN_NOT_FOUND`
- `LOAN_NOT_ELIGIBLE_FOR_CLOSURE`
- `INVALID_PAYMENT_ACCOUNT`
- `ACCOUNT_NOT_ACTIVE`
- `INSUFFICIENT_FUNDS`
- `LOAN_ALREADY_CLOSED`
- `SECOND_FACTOR_REQUIRED`
- `INVALID_CHALLENGE`
- `CONCURRENT_LOAN_OPERATION`
- `IDEMPOTENCY_CONFLICT`

---

# 103. Automatic EMI Execution Boundary

Automatic EMI deduction is not initiated by the customer API.

The execution path is:

```text
EventBridge Scheduler / scheduled job trigger
                 ↓
          Lambda job handler
                 ↓
        Reload loan + EMI state
                 ↓
     Verify configured payment account
                 ↓
        Acquire valid state/lock
                 ↓
          Attempt deduction
              /      \
          success    failure
             ↓          ↓
          Paid      retry / policy
             \          /
              ↓        ↓
             Financial result
```

The scheduler is only a trigger. PostgreSQL remains authoritative.

The job must be idempotent and safe to retry.

A manual EMI payment that completes before an automatic retry must prevent the automatic retry from deducting the same EMI again.

The system must never fall back to another customer account just because the configured automatic-deduction account has insufficient funds.

Automatic EMI payment, account balance change, loan/EMI state change and required ledger entries are committed within the appropriate database transaction boundary.

Notification failures do not roll back a successful EMI payment.

---

# 104. Loan Financial Atomicity

Loan financial operations follow the existing transaction architecture.

For an operation that moves money, the required changes are committed together:

```text
Customer account balance
        +
Loan principal / outstanding state
        +
EMI state where applicable
        +
Branch financial position where applicable
        +
FinancialTransaction
        +
LedgerEntry records
```

Either the financial operation succeeds as one valid result or the database transaction is rolled back.

Permanent financial records are not edited to repair a failed later operation. Corrections use the existing compensating/reversal model.

---

# 105. Loan and EMI Error Codes

| Error code | Meaning |
|---|---|
| `LOAN_PRODUCT_NOT_FOUND` | Requested product does not exist |
| `LOAN_PRODUCT_NOT_AVAILABLE` | Product is not available for a new application |
| `LOAN_AMOUNT_OUT_OF_RANGE` | Requested amount is outside product policy |
| `LOAN_TENURE_OUT_OF_RANGE` | Requested tenure is outside product policy |
| `LOAN_NOT_ELIGIBLE` | Customer does not currently satisfy eligibility rules |
| `ACTIVE_LOAN_APPLICATION_EXISTS` | Another active application already exists where duplicates are blocked |
| `LOAN_APPLICATION_NOT_FOUND` | Application does not exist or is not visible to the caller |
| `LOAN_APPLICATION_NOT_CANCELLABLE` | Current application state does not permit cancellation |
| `LOAN_OFFER_NOT_AVAILABLE` | No active Manager-approved offer is awaiting customer action |
| `LOAN_OFFER_EXPIRED` | Customer offer passed its validity period |
| `LOAN_OFFER_ALREADY_DECIDED` | Offer has already been accepted or declined |
| `LOAN_NOT_FOUND` | Loan does not exist or is not visible to the caller |
| `LOAN_NOT_PAYABLE` | Loan cannot currently accept an EMI payment |
| `EMI_NOT_FOUND` | EMI does not belong to the specified loan or is not visible |
| `EMI_ALREADY_PAID` | Requested EMI has already been settled |
| `PARTIAL_EMI_NOT_ALLOWED` | Payment would settle less than the required EMI amount |
| `INVALID_ADVANCE_PAYMENT` | Requested advance-payment allocation is not valid |
| `LOAN_NOT_ELIGIBLE_FOR_CLOSURE` | Loan cannot currently be closed early |
| `LOAN_ALREADY_CLOSED` | Loan is already in its terminal closed state |
| `INVALID_PAYMENT_ACCOUNT` | Selected account cannot be used for this loan operation |
| `ACCOUNT_NOT_ACTIVE` | Selected source account is not currently active |
| `INSUFFICIENT_FUNDS` | Source account does not have sufficient funds |
| `CONCURRENT_LOAN_OPERATION` | Another conflicting loan operation won the state transition |
| `SECOND_FACTOR_REQUIRED` | Required strong authentication has not been completed |
| `INVALID_CHALLENGE` | Supplied authentication challenge is invalid or for another purpose |
| `IDEMPOTENCY_CONFLICT` | Same idempotency key was used with different request data |

Common validation, authorization and rate-limit errors remain available where a more specific loan error is not useful.

---

# 106. Loan and EMI Concurrency / Idempotency Rules

Important races include:

```text
Customer submits loan application
        VS
retry / duplicate submission

Manager approves loan
        VS
application expiry

Customer accepts Manager offer
        VS
offer expiry

Customer changes disbursement/payment account
        VS
final processing / automatic deduction

Manual EMI payment
        VS
automatic EMI retry

Two manual payments for the same EMI

Advance EMI payment
        VS
scheduled EMI deduction

Early closure
        VS
EMI deduction
```

The backend must permit only one valid conflicting state transition.

Financial operations use database transaction boundaries and the persistent idempotency record as the final integrity protection.

Redis may provide an early duplicate check but cannot be the final correctness mechanism.

Scheduled EMI processing must reload authoritative state from PostgreSQL before acting.

---

# 107. Loan API Summary

| Operation | Method | Endpoint | Auth | Result |
|---|---|---|---|---|
| List loan products | `GET` | `/api/v1/loan-products` | CUSTOMER | `200 OK` |
| Get loan product | `GET` | `/api/v1/loan-products/{productId}` | CUSTOMER | `200 OK` |
| Create loan application | `POST` | `/api/v1/loan-applications` | CUSTOMER | `201 Created` |
| List loan applications | `GET` | `/api/v1/loan-applications` | CUSTOMER | `200 OK` |
| Get loan application | `GET` | `/api/v1/loan-applications/{applicationId}` | CUSTOMER | `200 OK` |
| Cancel loan application | `POST` | `/api/v1/loan-applications/{applicationId}/cancellation` | CUSTOMER | `200 OK` |
| Accept/decline approved offer | `POST` | `/api/v1/loan-applications/{applicationId}/offer-acceptance` | CUSTOMER | `200 OK` |
| List loans | `GET` | `/api/v1/loans` | CUSTOMER | `200 OK` |
| Get loan | `GET` | `/api/v1/loans/{loanId}` | CUSTOMER | `200 OK` |
| Get loan summary | `GET` | `/api/v1/loans/{loanId}/summary` | CUSTOMER | `200 OK` |
| Change EMI payment account | `PUT` | `/api/v1/loans/{loanId}/emi-payment-account` | CUSTOMER | `200 OK` |
| List EMIs | `GET` | `/api/v1/loans/{loanId}/emis` | CUSTOMER | `200 OK` |
| Get EMI | `GET` | `/api/v1/loans/{loanId}/emis/{emiId}` | CUSTOMER | `200 OK` |
| Pay due/advance EMIs | `POST` | `/api/v1/loans/{loanId}/emi-payments` | CUSTOMER | `200 OK` |
| EMI payment history | `GET` | `/api/v1/loans/{loanId}/payments` | CUSTOMER | `200 OK` |
| Get early-closure quote | `GET` | `/api/v1/loans/{loanId}/early-closure-quote` | CUSTOMER | `200 OK` |
| Close loan early | `POST` | `/api/v1/loans/{loanId}/early-closure` | CUSTOMER | `200 OK` |

---

# 108. Manager API Scope and Branch Context

Manager APIs are branch-scoped.

A Manager may have more than one active branch assignment, but each request is evaluated against one selected branch context.

For the public API, the branch is represented explicitly in the path:

```text
/api/v1/manager/branches/{branchId}/...
```

This avoids relying on hidden application state for authorization and makes the branch scope visible in the API contract.

The backend must verify that:

- The authenticated user has the `MANAGER` role.
- The Manager is currently assigned to the requested branch.
- The branch is active for normal branch operations.
- The requested customer/account/request belongs to the selected branch context where the operation requires branch ownership.

A Manager's historical access to a branch does not remain valid after the assignment ends.

If a Manager changes the selected branch in the frontend, no new authentication is required, but subsequent requests use the new branch ID and are authorized again by the backend.

There is no combined cross-branch Manager work queue.

---

# 109. Manager API Principles

Manager APIs operate on branch work and review workflows rather than exposing unrestricted customer CRUD. The Manager can review or perform only the operations explicitly allowed by the business rules.

In particular, Manager APIs must not allow a Manager to:
- perform customer financial transfers as an administrator,
- directly edit a customer's Trust Score,
- bypass another required approval stage,
- access customers or accounts outside the Manager's current branch scope,
- continue using a closed branch,
- approve their own customer-side financial operation through Manager authority.

Where a request belongs to the branch rather than to a specific Manager, reassignment of the Manager does not change ownership of the pending work. The new Manager receives the existing pending work after taking over the branch.

## 109.1 List assigned branches

```http
GET /api/v1/manager/branches
```

Authentication:
- MANAGER

Returns the active branches currently assigned to the authenticated Manager. This endpoint exists so a multi-branch Manager can select a branch context after login. The response contains only selectable branch summaries. Selecting one in the frontend does not itself grant authorization; every branch-scoped API request is authorized again by the backend.

Example:
```json
{
  "branches": [
    {
      "branchId": "branch-123",
      "branchCode": "BR001",
      "name": "Main Branch",
      "status": "ACTIVE"
    }
  ]
}
```


---

# 110. Manager Customer Search and Details APIs

Managers need branch-scoped customer lookup for branch operations and request review.

## 110.1 Search customers

```http
GET /api/v1/manager/branches/{branchId}/customers
```

Authentication:

- `MANAGER`
- Manager must be assigned to `{branchId}`

Supported query parameters can include:

```text
q
page
pageSize
sort
```

The search is limited to customers who have relevant banking relationships with the selected branch.

The API should return only fields appropriate for the Manager role. It should not expose internal authentication data, secrets or unrelated bank-wide information.

Example response:

```json
{
  "items": [
    {
      "customerId": "cst_123",
      "displayName": "Arvind Dhakar",
      "userId": "customer123",
      "accountCount": 2,
      "activeAccountCount": 2,
      "status": "ACTIVE"
    }
  ],
  "page": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

## 110.2 Get customer details

```http
GET /api/v1/manager/branches/{branchId}/customers/{customerId}
```

The response may include, where relevant to Manager work:

- Customer profile information permitted to Manager
- Account summaries belonging to the selected branch
- Pending requests requiring Manager action
- Trust Score where the product permits it for loan/credit-card review
- Relevant recent branch activity

The API must not expose authentication secrets or unrelated bank-wide information.

## 110.3 Get customer accounts in branch

```http
GET /api/v1/manager/branches/{branchId}/customers/{customerId}/accounts
```

Only accounts currently associated with the selected branch are returned.

If the customer has accounts in other branches, those accounts are not silently included in the branch-scoped result.

---

# 111. Manager Profile-Change Review APIs

Certain sensitive customer profile changes require Manager review.

The review workflow must preserve the separation between:

```text
Customer requests change
        ↓
Manager reviews request
        ↓
Approve / Reject
        ↓
Customer profile updated only when approved
```

## 111.1 List profile-change requests

```http
GET /api/v1/manager/branches/{branchId}/profile-change-requests
```

Optional filters:

```text
status
customerId
requestType
from
until
page
pageSize
sort
```

## 111.2 Get profile-change request

```http
GET /api/v1/manager/branches/{branchId}/profile-change-requests/{requestId}
```

## 111.3 Approve profile change

```http
POST /api/v1/manager/branches/{branchId}/profile-change-requests/{requestId}/approval
```

Request:

```json
{
  "decision": "APPROVE",
  "reason": "Verified customer request"
}
```

## 111.4 Reject profile change

The same action endpoint supports rejection:

```json
{
  "decision": "REJECT",
  "reason": "Supporting information does not match the request"
}
```

The request is resolved through the persisted `customer_profile_change_request` workflow. The backend applies the approved changes only after verifying the request's current state, `valid_till`, branch scope and reviewing Manager authorization.

Approval and rejection are state-changing operations and should use `Idempotency-Key`.

Concurrent decisions are resolved so that only one valid terminal decision is applied.

---

# 112. Manager Account State APIs

Managers can perform the account state actions permitted by the requirements within their branch authority.

## 112.1 Suspend account

```http
POST /api/v1/manager/branches/{branchId}/accounts/{accountId}/suspension
```

Request:

```json
{
  "duration": {
    "value": 30,
    "unit": "DAYS"
  },
  "reason": "Repeated operational issue"
}
```

Rules:

- The account must belong to the selected branch at the time of the operation.
- The fixed maximum suspension duration of 90 days is enforced by the backend.
- A reason is required.
- Required security checks are performed by the backend.
- The operation creates the required audit record.
- Customer notification is created after the business action succeeds.
- Manager suspension must not affect unrelated accounts.

If the account is already suspended, the API does not create a second active suspension. A conflict or idempotent repeat response is returned according to the common API policy.

## 112.2 Reactivate account

```http
POST /api/v1/manager/branches/{branchId}/accounts/{accountId}/reactivation
```

The backend verifies that the account is in a manager-reactivable state and belongs to the selected branch.

The operation is idempotent and safe against concurrent customer/scheduler state changes.

The stored lifecycle timestamps remain authoritative for scheduled operations.

---

# 113. Manager Account Closure Review APIs

Customer closure requests are reviewed by the Manager assigned to the account's current branch.

## 113.1 List closure requests

```http
GET /api/v1/manager/branches/{branchId}/account-closure-requests
```

Supported filters:

```text
status
accountId
customerId
page
pageSize
sort
```

## 113.2 Get closure request

```http
GET /api/v1/manager/branches/{branchId}/account-closure-requests/{requestId}
```

## 113.3 Approve/reject closure request

```http
POST /api/v1/manager/branches/{branchId}/account-closure-requests/{requestId}/decision
```

Request:

```json
{
  "decision": "APPROVE",
  "reason": "Account satisfies closure conditions"
}
```

or:

```json
{
  "decision": "REJECT",
  "reason": "Outstanding obligation prevents closure"
}
```

Before approval, the backend rechecks the account's current financial and lifecycle conditions.

It must not rely only on the values observed when the customer originally submitted the request.

Approval moves the account into the closure cooling lifecycle and stores the exact cooling end time.

The cooling period initially uses the V1 90-day setting; the active policy at the time of approval applies to that newly created cooling period.

The API must not return a successful approval if the closure request has already expired, been cancelled, or been resolved by another valid action.

---

# 114. Manager Loan Application APIs

Loan applications belonging to a Manager's branch are reviewed through branch-scoped APIs.

## 114.1 List loan applications

```http
GET /api/v1/manager/branches/{branchId}/loan-applications
```

Supported filters can include:

```text
status
customerId
loanProductId
from
until
page
pageSize
sort
```

## 114.2 Get loan application

```http
GET /api/v1/manager/branches/{branchId}/loan-applications/{applicationId}
```

The response can include:

- Requested amount
- Requested tenure
- Loan product details applicable to the application
- EMI preview/details where permitted
- Customer information needed for review
- Trust Score where permitted
- Relevant account information
- Application status
- Existing review history

The API must not expose unnecessary authentication/security secrets.

## 114.3 Review loan application

```http
POST /api/v1/manager/branches/{branchId}/loan-applications/{applicationId}/decision
```

Possible decisions include:

```text
APPROVE
APPROVE_WITH_DIFFERENT_AMOUNT
REJECT
```

Example:

```json
{
  "decision": "APPROVE_WITH_DIFFERENT_AMOUNT",
  "approvedAmount": 750000.00,
  "reason": "Approved within lending policy"
}
```

The backend validates the approved amount against the active bank policy and the application's current state.

If the Manager's approved amount differs from the requested amount, the application enters the customer offer-acceptance stage described by the loan API contract. When the customer accepts the offer before `validTill`, the backend automatically processes and disburses the loan without returning it to the Manager queue.

The Manager is not allowed to bypass the customer acceptance stage.

If branch funds are insufficient for disbursement, the Manager may use the branch funding workflow. Funding receipt does not by itself approve or disburse a loan. An already accepted and unexpired final offer remains eligible for automatic processing when the required funds are available and all other business checks pass; it does not return to the Manager review queue.

Decision operations use `Idempotency-Key` and must be safe against application expiry, customer cancellation where permitted, and duplicate Manager submission.

---

# 115. Manager Credit-Card Application APIs

## 115.1 List credit-card applications

```http
GET /api/v1/manager/branches/{branchId}/credit-card-applications
```

Optional filters:

```text
status
customerId
from
until
page
pageSize
sort
```

## 115.2 Get application

```http
GET /api/v1/manager/branches/{branchId}/credit-card-applications/{applicationId}
```

## 115.3 Approve/reject application

```http
POST /api/v1/manager/branches/{branchId}/credit-card-applications/{applicationId}/decision
```

Request:

```json
{
  "decision": "APPROVE",
  "reason": "Application satisfies configured eligibility policy"
}
```

or:

```json
{
  "decision": "REJECT",
  "reason": "Application does not satisfy current eligibility policy"
}
```

The backend rechecks the application's current validity and eligibility before applying the decision.

A late or already-resolved application cannot be approved by retrying an old request.

---

# 116. Manager Card-Limit Request APIs


## 116.1 List debit-card limit requests

```http
GET /api/v1/manager/branches/{branchId}/debit-card-limit-requests
```

Returns debit-card limit requests requiring Manager review for cards whose linked accounts belong to the selected branch.

Supported filters can include:

```text
status
customerId
cardId
from
until
page
pageSize
sort
```

## 116.2 Get debit-card limit request

```http
GET /api/v1/manager/branches/{branchId}/debit-card-limit-requests/{requestId}
```

The Manager can access the request only within the selected branch scope.

## 116.3 Approve or reject debit-card limit request

```http
POST /api/v1/manager/branches/{branchId}/debit-card-limit-requests/{requestId}/decision
```

Example:

```json
{
  "decision": "APPROVE",
  "approvedLimit": 100000.00,
  "reason": "Limit increase approved within policy"
}
```

The decision is accepted only when the request is still pending, has not passed `validTill`, and the new limit satisfies the active debit-card policy.

The operation uses `Idempotency-Key`.


## 116.4 List credit-card limit requests

```http
GET /api/v1/manager/branches/{branchId}/card-limit-requests
```

Credit-card limit increase requests are reviewed by the Manager.

## 116.5 Get credit-card limit request

```http
GET /api/v1/manager/branches/{branchId}/card-limit-requests/{requestId}
```

## 116.6 Approve/reject credit-card limit request

```http
POST /api/v1/manager/branches/{branchId}/card-limit-requests/{requestId}/decision
```

Example approval:

```json
{
  "decision": "APPROVE",
  "approvedLimit": 250000.00,
  "reason": "Limit increase approved within policy"
}
```

The backend validates the approved limit against the active policy and the existing credit-card state.

Only one active card-limit request per credit card is allowed.

A request that has expired, been resolved, or been superseded cannot be approved through a duplicate submission.

---

# 117. Manager Branch-Transfer Approval APIs

Branch transfer is an account-level workflow with two different branch approvals.

```text
Source branch Manager
        ↓
Destination branch Manager
        ↓
Both approved
        ↓
Scheduled execution
```

The API uses the selected branch context so the same request can be visible to the appropriate source or destination branch Manager without giving either Manager unrelated bank-wide access.

## 117.1 List branch-transfer requests

```http
GET /api/v1/manager/branches/{branchId}/branch-transfer-requests
```

The result contains requests for which the selected branch is currently responsible for an approval stage.

## 117.2 Get branch-transfer request

```http
GET /api/v1/manager/branches/{branchId}/branch-transfer-requests/{requestId}
```

## 117.3 Approve/reject current branch stage

```http
POST /api/v1/manager/branches/{branchId}/branch-transfer-requests/{requestId}/decision
```

Request:

```json
{
  "decision": "APPROVE",
  "reason": "Transfer approved"
}
```

A Manager can approve only the stage belonging to the selected branch.

The source branch Manager cannot approve the destination branch stage, and vice versa.

If either stage rejects the request, the transfer ends without migration.

Once both approvals are complete and the execution is scheduled, normal customer cancellation/editing is no longer allowed.

The actual branch migration is executed separately from the approval API and remains idempotent.

---

# 118. Manager Funding Request APIs

Managers can request additional Branch Funds for the selected branch.

## 118.1 Create funding request

```http
POST /api/v1/manager/branches/{branchId}/funding-requests
```

Request:

```json
{
  "requestedAmount": 500000.00,
  "reason": "Additional lending capacity required"
}
```

Rules:

- The request belongs to the selected branch.
- Only one active funding request is allowed for a branch.
- The Manager does not decide the final funding amount.
- Admin approval is required before Branch Funds increase.
- Funding is not automatically tied to a particular loan.
- Receiving funding does not automatically approve or disburse a pending loan.

## 118.2 List funding requests

```http
GET /api/v1/manager/branches/{branchId}/funding-requests
```

## 118.3 Get funding request

```http
GET /api/v1/manager/branches/{branchId}/funding-requests/{requestId}
```

The Manager can see the request's status and approved amount when available.

Admin approval/rejection is defined in the Admin API section, not in the Manager contract.

---

# 119. Manager Branch Financial APIs

Managers may view the financial information allowed for their selected branch.

## 119.1 Branch financial position

```http
GET /api/v1/manager/branches/{branchId}/financial-position
```

The response can contain separate values for:

- Branch Funds
- Branch Customer Deposit Position

These must remain separate concepts in the API as they are in the database and accounting model.

## 119.2 Branch financial movement history

```http
GET /api/v1/manager/branches/{branchId}/financial-movements
```

Supported filters can include:

```text
from
until
movementType
page
pageSize
sort
```

Branch history represents branch-level financial movement rather than replacing customer transaction history.

A Manager cannot use this API to modify financial records.

Historical finalized financial records are read-only.

---

# 120. Manager Branch Customer Activity APIs

Managers may view branch-level customer activity required for branch operations.

```http
GET /api/v1/manager/branches/{branchId}/customer-activity
```

Supported filters can include:

```text
customerId
accountId
activityType
from
until
page
pageSize
sort
```

The API provides a branch-scoped operational view of existing records.

It must not become a second source of financial truth or create a duplicate transaction-history model.

---

# 121. Manager Work Queue API

The Manager dashboard needs one branch-scoped summary of work requiring attention.

```http
GET /api/v1/manager/branches/{branchId}/work-queue
```

The response may group counts or small summaries for:

- Account closure requests
- Loan applications
- Credit-card applications
- Credit-card limit requests
- Branch-transfer approvals
- Profile-change requests
- Other Manager-owned branch workflows

The work queue is a read model over the underlying request records.

It is not a separate source of truth and does not replace the individual request APIs.

Where possible, the response should provide identifiers that allow the frontend to navigate directly to the underlying request.

---

# 122. Manager Notification Boundary

Managers use the shared notification endpoints defined in Sections 144–149. The backend returns only notifications belonging to the authenticated Manager and applies the same ownership and read-state rules used for other roles. Manager-specific duplicate notification endpoints are not added.

Notification operations do not control financial success and do not roll back completed Manager business actions.

---

# 123. Manager API Error Cases

In addition to the common API error model, Manager endpoints use business-specific conflict/authorization responses where appropriate.

Common cases include:

```text
403 MANAGER_ROLE_REQUIRED
403 BRANCH_ACCESS_DENIED
404 BRANCH_NOT_FOUND
404 RESOURCE_NOT_FOUND_IN_BRANCH_SCOPE
409 REQUEST_ALREADY_RESOLVED
409 REQUEST_ALREADY_EXPIRED
409 REQUEST_STAGE_NOT_OWNED_BY_MANAGER
409 BRANCH_NOT_ACTIVE
409 ACCOUNT_NOT_IN_BRANCH
409 ACCOUNT_STATE_CONFLICT
409 FUNDING_REQUEST_ALREADY_EXISTS
409 CREDIT_CARD_LIMIT_REQUEST_ALREADY_EXISTS
422 INVALID_DECISION
422 INVALID_APPROVED_AMOUNT
422 INVALID_SUSPENSION_DURATION
```

The API should avoid revealing whether a resource exists in another branch when doing so would disclose information outside the Manager's authorized scope. The implementation may therefore return a scoped `404` rather than a branch-access-specific response for some lookups.

---

# 124. Manager Concurrency and Idempotency

Manager workflows are concurrency-sensitive because multiple events can target the same request.

Typical races include:

```text
Manager approve closure
        VS
Customer cancels closure

Manager approve loan
        VS
Application expires

Source Manager approve transfer
        VS
Request becomes invalid

Destination Manager approve transfer
        VS
Source Manager rejects/cancels

Manager suspend account
        VS
Customer changes account state

Two Manager submissions for the same request
```

The backend must permit only one valid conflicting transition.

Critical Manager state-changing actions should use:

```http
Idempotency-Key: <unique-key>
```

The persistent database idempotency record is the final duplicate guard.

Redis may provide an early duplicate check but cannot be the only protection.

A repeated request with the same idempotency key must not create a second approval, second suspension, second funding request, second financial effect or any other duplicate business action.

---

# 125. Manager Financial Safety Boundary

Manager APIs that review financial applications are not financial execution APIs unless the requirements explicitly make the Manager action itself a financial operation.

For example:

```text
Manager approves loan
        ↓
Customer/loan workflow continues
        ↓
Final disbursement service
        ↓
FinancialTransaction + ledger movement
```

Approval should not directly mutate customer balances unless the specific business rule defines that operation as the disbursement step itself.

Similarly:

```text
Manager approves funding request
        ↓
Admin approval
        ↓
Financial funding operation
```

The API boundary therefore keeps workflow approval separate from the later financial movement whenever the requirements define separate stages.

All actual financial movements continue to use the established transaction and double-entry accounting rules.

---

# 126. Manager Audit Requirements

The following Manager operations create audit records as required:

- Sensitive customer profile-change decisions
- Account suspension/reactivation performed by Manager
- Account closure approval/rejection
- Loan application decisions
- Credit-card application decisions
- Credit-card limit decisions
- Branch-transfer approval/rejection
- Funding request creation and other controlled actions
- Other security-sensitive or business-critical Manager operations

The audit record identifies the acting employee, role, branch context, action, affected entity, timestamp and reason/details where appropriate.

Application logs and audit records remain separate.

A Manager cannot modify or delete the audit record through normal business APIs.

---

# 127. Manager API Summary

| Operation | Method | Endpoint | Scope | Result |
|---|---|---|---|---|
| Assigned branches | `GET` | `/api/v1/manager/branches` | Manager | `200 OK` |
| Search customers | `GET` | `/api/v1/manager/branches/{branchId}/customers` | Selected branch | `200 OK` |
| Customer details | `GET` | `/api/v1/manager/branches/{branchId}/customers/{customerId}` | Selected branch | `200 OK` |
| Customer accounts | `GET` | `/api/v1/manager/branches/{branchId}/customers/{customerId}/accounts` | Selected branch | `200 OK` |
| Profile-change requests | `GET` | `/api/v1/manager/branches/{branchId}/profile-change-requests` | Selected branch | `200 OK` |
| Profile-change details | `GET` | `/api/v1/manager/branches/{branchId}/profile-change-requests/{requestId}` | Selected branch | `200 OK` |
| Profile-change decision | `POST` | `/api/v1/manager/branches/{branchId}/profile-change-requests/{requestId}/approval` | Selected branch | `200 OK` |
| Suspend account | `POST` | `/api/v1/manager/branches/{branchId}/accounts/{accountId}/suspension` | Account branch | `200 OK` |
| Reactivate account | `POST` | `/api/v1/manager/branches/{branchId}/accounts/{accountId}/reactivation` | Account branch | `200 OK` |
| Closure requests | `GET` | `/api/v1/manager/branches/{branchId}/account-closure-requests` | Selected branch | `200 OK` |
| Closure details | `GET` | `/api/v1/manager/branches/{branchId}/account-closure-requests/{requestId}` | Selected branch | `200 OK` |
| Closure decision | `POST` | `/api/v1/manager/branches/{branchId}/account-closure-requests/{requestId}/decision` | Selected branch | `200 OK` |
| Loan applications | `GET` | `/api/v1/manager/branches/{branchId}/loan-applications` | Selected branch | `200 OK` |
| Loan application details | `GET` | `/api/v1/manager/branches/{branchId}/loan-applications/{applicationId}` | Selected branch | `200 OK` |
| Loan decision | `POST` | `/api/v1/manager/branches/{branchId}/loan-applications/{applicationId}/decision` | Selected branch | `200 OK` |
| Credit-card applications | `GET` | `/api/v1/manager/branches/{branchId}/credit-card-applications` | Selected branch | `200 OK` |
| Credit-card application details | `GET` | `/api/v1/manager/branches/{branchId}/credit-card-applications/{applicationId}` | Selected branch | `200 OK` |
| Credit-card application decision | `POST` | `/api/v1/manager/branches/{branchId}/credit-card-applications/{applicationId}/decision` | Selected branch | `200 OK` |
| Debit-card limit requests | `GET` | `/api/v1/manager/branches/{branchId}/debit-card-limit-requests` | Selected branch | `200 OK` |
| Debit-card limit request details | `GET` | `/api/v1/manager/branches/{branchId}/debit-card-limit-requests/{requestId}` | Selected branch | `200 OK` |
| Debit-card limit decision | `POST` | `/api/v1/manager/branches/{branchId}/debit-card-limit-requests/{requestId}/decision` | Selected branch | `200 OK` |
| Card-limit requests | `GET` | `/api/v1/manager/branches/{branchId}/card-limit-requests` | Selected branch | `200 OK` |
| Card-limit details | `GET` | `/api/v1/manager/branches/{branchId}/card-limit-requests/{requestId}` | Selected branch | `200 OK` |
| Card-limit decision | `POST` | `/api/v1/manager/branches/{branchId}/card-limit-requests/{requestId}/decision` | Selected branch | `200 OK` |
| Branch-transfer requests | `GET` | `/api/v1/manager/branches/{branchId}/branch-transfer-requests` | Approval stage | `200 OK` |
| Branch-transfer details | `GET` | `/api/v1/manager/branches/{branchId}/branch-transfer-requests/{requestId}` | Approval stage | `200 OK` |
| Branch-transfer decision | `POST` | `/api/v1/manager/branches/{branchId}/branch-transfer-requests/{requestId}/decision` | Approval stage | `200 OK` |
| Create funding request | `POST` | `/api/v1/manager/branches/{branchId}/funding-requests` | Selected branch | `201 Created` |
| List funding requests | `GET` | `/api/v1/manager/branches/{branchId}/funding-requests` | Selected branch | `200 OK` |
| Funding request details | `GET` | `/api/v1/manager/branches/{branchId}/funding-requests/{requestId}` | Selected branch | `200 OK` |
| Branch financial position | `GET` | `/api/v1/manager/branches/{branchId}/financial-position` | Selected branch | `200 OK` |
| Branch financial movements | `GET` | `/api/v1/manager/branches/{branchId}/financial-movements` | Selected branch | `200 OK` |
| Branch customer activity | `GET` | `/api/v1/manager/branches/{branchId}/customer-activity` | Selected branch | `200 OK` |
| Manager work queue | `GET` | `/api/v1/manager/branches/{branchId}/work-queue` | Selected branch | `200 OK` |

---

# 128. Admin APIs

Admin APIs provide bank-wide operational control.

The Admin role is broader than the Manager role, but it is not a shortcut into ordinary customer operations.

Admin APIs are used for:

- Branch management
- Manager/employee management
- Central Ledger operations
- Branch funding decisions
- Bank-wide financial dashboards
- Bank policy/configuration management
- Administrative audit access and export

Admin does **not** directly perform normal customer banking actions such as:

- Editing an individual customer's profile
- Performing a customer's transfer
- Manually changing a customer's Trust Score
- Acting as a customer
- Approving a Manager-owned customer workflow unless an explicit Admin override is later introduced as a separate product rule

The backend remains authoritative for all Admin permissions.

---

# 129. Admin Authorization Boundary

All Admin endpoints require an authenticated Admin identity.

The frontend may display Admin screens only to Admin users, but that is not an authorization mechanism.

Every Admin request must be authorized by the backend.

```text
Authenticated user
        ↓
Role = ADMIN
        ↓
Permission check
        ↓
Admin API
```

Admin access is bank-wide within the capabilities explicitly defined by the requirements.

Admin APIs must not inherit customer or Manager permissions accidentally. A shared authentication mechanism may identify the user, but authorization is evaluated according to the Admin capability being requested.

Sensitive Admin state-changing operations must create an audit record.

---

# 130. Admin Branch APIs

Admin owns the bank-wide branch lifecycle.

## 130.1 List branches

```http
GET /api/v1/admin/branches
```

Supports:

- Pagination
- Active/inactive filtering
- Search by branch code/name where supported
- Sorting by supported operational fields

Response contains branch-level operational information, including where applicable:

- Branch ID
- Branch code
- Branch name
- Location details
- Active/inactive state
- Current Manager summary
- Branch Funds summary
- Customer Deposit Position summary

The endpoint does not expose unrestricted individual customer data.

---

## 130.2 Branch details

```http
GET /api/v1/admin/branches/{branchId}
```

Returns the bank-wide administrative view of the selected branch.

It may include:

- Branch identity
- Current Manager
- Branch status
- Financial position summaries
- Assignment information
- Operational indicators
- Pending branch-level work counts

Historical Manager assignment remains available through the dedicated assignment/history endpoint.

---

## 130.3 Create branch

```http
POST /api/v1/admin/branches
```

Example request:

```json
{
  "branchCode": "BR001",
  "name": "Central Branch",
  "location": {
    "address": "..."
  },
  "managerId": "..."
}
```

The exact branch profile fields are finalized with the implementation model, but the API must require the information necessary to create an active branch.

Business rules:

- Branch code must be unique and immutable after creation.
- An active branch must have exactly one Manager.
- The selected Manager must be eligible for branch assignment.
- Initial Branch Funds come from the Central Ledger.
- Initial funding uses the Admin configuration applicable to new branches.
- Branch creation, Manager assignment and initial funding must not leave a partially created branch.
- The financial movement is recorded permanently.
- The operation is audited.

Because branch creation includes financial movement, the entire business operation must be atomic from the application's point of view.

Recommended response:

```http
201 Created
```

---

## 130.4 Update branch operational details

```http
PATCH /api/v1/admin/branches/{branchId}
```

Only mutable non-financial branch attributes may be changed through this endpoint.

Branch code is immutable.

Changing Manager assignment is handled through a dedicated assignment endpoint rather than silently changing a branch's Manager through a generic PATCH.

Financial values such as Branch Funds are not directly editable through this endpoint.

---

## 130.5 Current Manager

```http
GET /api/v1/admin/branches/{branchId}/manager
```

Returns the branch's current Manager.

For an active branch, the response must identify exactly one current Manager.

A closed/inactive branch may have no current Manager.

---

## 130.6 Assign or replace Manager

```http
POST /api/v1/admin/branches/{branchId}/manager
```

Example request:

```json
{
  "managerId": "...",
  "reason": "Manager replacement"
}
```

Business rules:

- The selected Manager must be eligible for the assignment.
- The branch must not become managerless while active.
- Current Manager reference and historical assignment record must be updated atomically.
- Existing pending branch work remains with the branch.
- The previous Manager loses access to the branch immediately after replacement.
- Historical assignment remains available.
- The operation is audited.

This endpoint is an administrative workflow operation, not a generic foreign-key update.

---

## 130.7 Manager assignment history

```http
GET /api/v1/admin/branches/{branchId}/manager-assignments
```

Returns historical Manager assignments for the branch.

Supports pagination where required.

Historical entries are not modified merely because a later assignment occurs.

---

## 130.8 Branch decommissioning

```http
POST /api/v1/admin/branches/{branchId}/decommission
```

Example request:

```json
{
  "destinationBranchId": "...",
  "reason": "Branch consolidation"
}
```

The decommissioning workflow must validate before migration begins:

- Customers/accounts/products have a valid destination where required.
- Pending work is handled according to migration rules.
- Branch Funds are returned to Central Ledger first.
- Branch financial state reaches the required settled state.
- Destination branch is active.
- Destination branch has a valid Manager.
- The migration will not leave financial or domain records half-migrated.

Because the actual migration is performed during the defined low-traffic execution period, the final implementation may execute this operation asynchronously.

Where an asynchronous implementation is used, the API must return a business-visible operation state rather than pretending that the branch is already closed.

```http
202 Accepted
```

After successful completion:

- Branch becomes inactive/closed.
- Branch code is never reused.
- Historical branch financial information remains.
- Historical Manager assignments remain.
- Former Manager loses branch access immediately.

The operation is audited.

---

# 131. Admin Manager / Employee APIs

Manager accounts are employee accounts and are distinct from customers.

Admin manages the employee lifecycle relevant to Manager administration.

## 131.1 List Managers

```http
GET /api/v1/admin/managers
```

Supports:

- Pagination
- Active/inactive filtering
- Assignment filtering
- Search by supported employee fields

Response may include:

- Employee ID
- User ID
- Name
- Role
- Employee status
- Assigned branch summaries
- Assignment count
- Relevant lifecycle dates

No customer phone-number field exists for Manager accounts.

---

## 131.2 Manager details

```http
GET /api/v1/admin/managers/{managerId}
```

Returns the administrative employee view, including current branch assignments and employee status.

It does not turn the Manager into a customer-domain object.

---

## 131.3 Create Manager

```http
POST /api/v1/admin/managers
```

The request creates an employee account with Manager role and the required employee identity information.

Business rules:

- A Manager is an employee, not a Customer.
- Manager creation does not create a customer account.
- Manager creation and initial branch assignment can be performed as one controlled operation where the product flow requires it.
- Employee/Admin authentication policies are stricter than customer authentication policies.
- The operation is audited.

The exact employee fields and initial credential-delivery mechanism are finalized in the authentication/security implementation.

---

## 131.4 Manager branch assignments

```http
GET /api/v1/admin/managers/{managerId}/branches
```

Returns the Manager's active branch assignments and relevant assignment history where requested.

A Manager may manage multiple branches.

---

## 131.5 Manager deactivation

A Manager with no active branch assignment follows the employee deactivation process.

The deactivation grace period is Admin-defined between 15 and 90 days.

```http
POST /api/v1/admin/managers/{managerId}/deactivation
```

Example request:

```json
{
  "action": "SCHEDULE",
  "reason": "No active branch assignment"
}
```

The API must not deactivate an employee who still has an active branch assignment.

The scheduled deadline is stored as authoritative application state.

A one-time scheduled operation may be created for the deadline.

If the Manager is assigned to an active branch again before the deadline, the old scheduled operation becomes harmless.

If the deadline is reached while there is still no active branch assignment, the employee/user account becomes inactive.

Historical employee and branch assignment records remain.

---

## 131.6 Manager activation/reactivation

```http
POST /api/v1/admin/managers/{managerId}/activation
```

The exact allowed reactivation scenarios follow employee lifecycle policy.

The backend must verify that the Manager is eligible to become active before changing the employee state.

The operation is audited.

---

## 131.7 Create Admin

```http
POST /api/v1/admin/admins
```

Purpose:

Create a new employee account with the `ADMIN` role when an additional bank administrator is required. Managers are never promoted to Admin.

Authentication & Authorization:

`ADMIN` only, with strong authentication (`verificationChallengeId`) and mandatory audit logging.

The backend creates a separate employee identity and assigns the `ADMIN` role. The operation does not create a customer profile or customer payment capabilities.

Idempotency:

Required.

Success:

```http
201 Created
```

---

# 132. Central Ledger APIs

Central Ledger is the only place where simulated money can be introduced from nowhere.

Only Admin can perform that operation.

## 132.1 Central Ledger position

```http
GET /api/v1/admin/ledger/central/position
```

Returns:

- Current Central Ledger balance
- Relevant bank-wide summary values
- Timestamp of the current position

The balance cannot be negative.

---

## 132.2 Central Ledger movement history

```http
GET /api/v1/admin/ledger/central/movements
```

Supports pagination and supported financial filters.

History is permanent.

The endpoint returns financial history rather than editable balance records.

---

## 132.3 Inject simulated central funds

```http
POST /api/v1/admin/ledger/central/injections
```

Example request:

```json
{
  "amount": 1000000.00,
  "reason": "Initial bank capital"
}
```

Required rules:

- Amount must be greater than zero.
- Reason is required.
- Operation requires Admin authorization.
- Operation is permanent and audited.
- Central Ledger balance must remain valid.
- Required financial/accounting entries must be created.
- No customer account is directly modified by the injection.

Because this is a financial operation, the API must support idempotency.

```http
Idempotency-Key: <unique-key>
```

Recommended success response:

```http
201 Created
```

The response returns the resulting financial reference and updated Central Ledger position needed by the frontend.

---

# 133. Admin Funding Request APIs

Funding requests are created by Managers and resolved by Admin.

Funding requests do not have automatic expiry.

## 133.1 List funding requests

```http
GET /api/v1/admin/funding-requests
```

Supports:

- Pagination
- Branch filtering
- Status filtering
- Date filtering where supported
- Sorting

Pending funding requests remain available until Admin resolves them.

---

## 133.2 Funding request details

```http
GET /api/v1/admin/funding-requests/{requestId}
```

Returns request information such as:

- Request ID
- Branch
- Requesting Manager
- Requested amount
- Reason
- Current status
- Created time
- Decision information where resolved

---

## 133.3 Approve or reject funding request

```http
POST /api/v1/admin/funding-requests/{requestId}/decision
```

Approve example:

```json
{
  "decision": "APPROVE",
  "approvedAmount": 75000.00,
  "reason": "Approved within available branch funding"
}
```

Reject example:

```json
{
  "decision": "REJECT",
  "reason": "Insufficient central funds"
}
```

Business rules:

- Approved amount may be less than, equal to, or greater than the requested amount.
- Approved amount must satisfy all Admin-configured limits.
- Central Ledger must not become negative.
- The funding request must still be pending when the decision is made.
- Concurrent Admin decisions must allow only one valid final transition.
- Approval creates the required permanent financial movement.
- Rejection creates the required operational/audit outcome without creating a funding movement.
- Pending funding-request notifications remain available until the request is resolved.
- The operation is audited.

Because approval may create financial movement, the request must support idempotent submission.

---

# 134. Admin Loan Product APIs

Loan products are persisted domain resources and need explicit Admin management APIs. They are not represented only as arbitrary configuration keys.

## 134.1 List loan products

```http
GET /api/v1/admin/loan-products
```

Returns loan products visible to Admin, with filtering by active/inactive status where useful.

## 134.2 Get loan product

```http
GET /api/v1/admin/loan-products/{productId}
```

Returns the product's configured lending terms and operational status.

## 134.3 Create loan product

```http
POST /api/v1/admin/loan-products
```

Request includes the product values defined by the requirements, such as amount limits, interest rate, tenure range, processing fee, early-closure/prepayment charge rule, age eligibility and Trust Score requirement.

The operation uses `Idempotency-Key`.

Success:

```text
201 Created
```

## 134.4 Update loan product

```http
PATCH /api/v1/admin/loan-products/{productId}
```

Updates fields allowed by current product policy, including activation/deactivation where applicable.

Changes affect future applications and do not rewrite the contractual terms of existing loans.

Stale Admin updates should be rejected through the same optimistic-concurrency/version approach used for other configuration updates.

# 135. Admin Financial Dashboard APIs

The financial dashboard is read-only from an API perspective.

Admin must never update financial balances through dashboard endpoints.

## 135.1 Bank-wide financial summary

```http
GET /api/v1/admin/dashboard/financial-summary
```

Returns the combined administrative view of relevant bank-wide financial information.

Possible summary areas include:

- Central Ledger position
- Total Branch Funds
- Branch Customer Deposit Position totals
- Loan disbursement activity
- Loan repayment activity
- Credit-card payment activity
- Branch funding received
- Other defined bank-level financial activity

Individual customer Trust Scores are not part of this dashboard.

---

## 135.2 Branch financial dashboard

```http
GET /api/v1/admin/dashboard/branches/{branchId}/financial
```

Returns the selected branch's administrative financial view, including where applicable:

- Branch Funds
- Branch Customer Deposit Position
- Relevant movement summaries
- Loan disbursement activity
- Loan repayment activity
- Credit-card payment activity
- Funding history

The branch financial data remains read-only through dashboard APIs.

---

## 135.3 Bank-wide financial activity

```http
GET /api/v1/admin/dashboard/financial-activity
```

Supports pagination and appropriate date/type filters.

This endpoint is an aggregated reporting view and does not become a second financial source of truth.

---

# 136. Admin Configuration Center APIs

Admin configuration is centralized.

The API treats configuration as controlled business policy rather than generic key/value storage exposed directly to clients.

The current requirement is one active bank-wide configuration with history.

## 136.1 List configuration categories

```http
GET /api/v1/admin/configuration/categories
```

Returns only supported configuration categories.

Current categories include:

- Bank/support
- Authentication/OTP
- Transactions
- Accounts
- UPI
- Beneficiaries
- Money Requests
- Debit Cards
- Credit Cards
- Loans
- EMI
- Trust Score
- Rewards
- Notifications
- Statements
- Branch Operations
- Financial/Lending Policies
- Scheduled Job Cleanup

The API should expose only useful settings.

---

## 136.2 List settings in a category

```http
GET /api/v1/admin/configuration/categories/{category}/settings
```

Each setting should expose enough information for the Admin UI to understand and validate the change, including:

- Setting key
- Display name
- Description
- Current value
- Input/control type
- Allowed range/options where applicable
- Unit where applicable
- Whether the setting is configurable
- Whether it affects future operations only
- Current configuration version

Sensitive implementation details are not exposed.

---

## 136.3 Get one setting

```http
GET /api/v1/admin/configuration/settings/{settingKey}
```

Returns the current active setting and its metadata.

---

## 136.4 Update a configurable setting

```http
PATCH /api/v1/admin/configuration/settings/{settingKey}
```

Example request:

```json
{
  "value": 50000,
  "expectedVersion": 12,
  "reason": "Increase daily transfer limit"
}
```

Rules:

- Backend validates the new value.
- Cross-setting validation is performed where required.
- Fixed product rules cannot be changed through this API.
- Current valid data is not silently rewritten unless the product rule explicitly says otherwise.
- A configuration history record is created.
- The active configuration version changes atomically with the update.
- The operation is audited.
- `expectedVersion` is used to detect stale Admin updates and prevent lost changes.

Recommended responses:

```text
200 OK
409 Conflict
422 Unprocessable Content
```

`409 Conflict` is appropriate when another Admin has changed the active configuration since the caller last read it.

`422 Unprocessable Content` is appropriate when the supplied value is syntactically valid but violates a business validation rule.

---

## 136.5 Configuration history

```http
GET /api/v1/admin/configuration/history
```

Supports filtering by:

- Setting key/category
- Actor
- Date range
- Configuration version

History is append-oriented and exists separately from application logs.

---

## 136.6 Restore/revert configuration

A direct "restore old version" endpoint is intentionally **not** defined in V1.

A rollback should be represented as a new valid configuration change so that the history clearly shows what happened.

This keeps the configuration audit trail understandable and avoids rewriting historical configuration records.

---

# 137. Admin Audit APIs

Audit records are separate from application logs and customer-facing notifications.

## 137.1 Search audit records

```http
GET /api/v1/admin/audit-logs
```

Supports appropriate filters such as:

- Actor
- Role
- Entity type
- Entity ID
- Action
- Branch context where applicable
- Date range

Pagination is required.

Admin access is bank-wide within the defined audit scope.

---

## 137.2 Audit record details

```http
GET /api/v1/admin/audit-logs/{auditLogId}
```

Returns the stored audit information without modifying the source record.

The API may show old/new details where they were recorded for that event and where the requesting Admin is authorized to see them.

---

## 137.3 Export audit information

```http
POST /api/v1/admin/audit-logs/export
```

Example request:

```json
{
  "from": "2026-10-01T00:00:00Z",
  "to": "2026-10-06T23:59:59Z",
  "entityType": "BRANCH",
  "format": "CSV"
}
```

The export does not modify the underlying audit records.

The export action itself creates an audit record.

The exact export delivery/storage mechanism is an implementation concern and must not turn audit data into permanent object-storage copies unless that is explicitly chosen later.

---

# 138. Admin Configuration: Fixed vs Configurable Values

The API must distinguish between values that Admin can change and product rules that are fixed.

Examples of fixed rules include:

- Bank name
- Bank theme
- Customer password format
- Customer suspension maximum of 90 days
- Beneficiary cooling period of 30 minutes
- Scheduled-payment late grace period of 10 minutes
- Heavy-job execution grace period of 24 hours
- Phone-number format rules
- No-SMS design

These values should not appear as ordinary writable configuration settings.

Admin-configurable examples include:

- Transfer limits and fees
- OTP policy values
- Initial account funding
- Loan product settings
- Credit-card product settings
- Credit-card billing policy, including monthly statement day and due-date offset
- International debit/credit card-purchase fee rate (percentage, initially 0%, applied to future transactions only)
- Reward rates and expiry
- Debit-card defaults
- Custom UPI maximum
- Beneficiary maximum count
- Money-request expiry/limits
- Scheduled-payment future range and limits
- Branch-transfer request policies
- Statement generation/retention policy
- Notification retention
- Trust Score initial value and scoring factors
- Branch lending/funding policies
- Cleanup schedule/frequency

The API must not expose a generic endpoint such as:

```http
PATCH /api/v1/admin/configuration
```

with an unrestricted JSON object that allows arbitrary keys.

Settings must be individually recognized by the backend so that type checking, allowed ranges and cross-setting validation are enforceable.

---

# 139. Admin Error Cases

Common Admin API errors include:

| HTTP Status | Example condition |
|---|---|
| `400` | Malformed request or invalid syntax |
| `401` | Missing/invalid authentication |
| `403` | Authenticated user is not allowed to perform the Admin operation |
| `404` | Branch, Manager, funding request, audit record or configuration setting not found |
| `409` | Conflicting Manager assignment, stale configuration version, duplicate active decision, or concurrent state change |
| `422` | Valid request shape but violates a business/configuration rule |
| `429` | Admin operation rate limit exceeded where applicable |
| `500` | Unexpected server error |
| `503` | Required external dependency unavailable for an operation that cannot safely complete without it |

The normal error envelope from the API-wide contract remains in use.

Admin errors must not expose internal SQL errors, stack traces, credentials, infrastructure identifiers or other sensitive implementation details.

---

# 140. Admin Idempotency and Concurrency

Idempotency applies to Admin operations where repeating the same request could duplicate a business or financial effect.

At minimum this includes:

- Central Ledger fund injection
- Funding request approval when approval creates money movement
- Branch creation when it includes initial funding
- Other financial state-changing Admin operations

Use:

```http
Idempotency-Key: <unique-key>
```

The database remains the final integrity guard.

Redis may provide an early duplicate check, but correctness must not depend on Redis.

Concurrent Admin decisions must follow the same rule as other workflows:

```text
Read authoritative state
        ↓
Validate current state
        ↓
Acquire valid transition
        ↓
Perform business operation
        ↓
Commit atomically
```

Examples include:

```text
Two Admins approve the same funding request

Admin replaces Manager
        VS
Manager performs branch operation

Admin changes configuration
        VS
Another Admin changes the same setting

Branch decommissioning
        VS
Other branch operations
```

Only one valid final state may be committed.

---

# 141. Admin Financial Safety Boundary

Admin dashboard and operational APIs must never bypass the established financial model.

A financial Admin operation such as Central Ledger injection or branch funding must create the required financial and accounting records rather than simply changing a balance field.

The established rules continue to apply:

```text
Financial operation
      ↓
FinancialTransaction
      ↓
Account / branch effects where applicable
      ↓
LedgerEntry records
      ↓
Double-entry balance
```

Central Ledger and Branch Funds must never become negative.

Financial history remains permanent and immutable after finalization.

Corrections are compensating financial events rather than edits to finalized history.

---

# 142. Admin Audit Boundary

The following operations require audit records where applicable:

- Branch creation
- Branch update where materially relevant
- Manager assignment/replacement
- Manager creation
- Manager activation/deactivation
- Branch decommissioning
- Central Ledger injection
- Funding request decisions
- Configuration changes
- Audit export
- Other security-sensitive Admin operations

Audit records identify, where applicable:

`Actor | Role | Branch context | Entity | Old state | New state | Timestamp | Reason`

Admin cannot use normal business APIs to edit or delete an audit record.

Application logging and audit logging remain separate concerns.

---

# 143. Admin API Summary

| Operation | Method | Endpoint | Result |
|---|---|---|---|
| List branches | `GET` | `/api/v1/admin/branches` | `200 OK` |
| Branch details | `GET` | `/api/v1/admin/branches/{branchId}` | `200 OK` |
| Create branch | `POST` | `/api/v1/admin/branches` | `201 Created` |
| Update branch | `PATCH` | `/api/v1/admin/branches/{branchId}` | `200 OK` |
| Current Manager | `GET` | `/api/v1/admin/branches/{branchId}/manager` | `200 OK` |
| Assign/replace Manager | `POST` | `/api/v1/admin/branches/{branchId}/manager` | `200 OK` |
| Manager assignments | `GET` | `/api/v1/admin/branches/{branchId}/manager-assignments` | `200 OK` |
| Decommission branch | `POST` | `/api/v1/admin/branches/{branchId}/decommission` | `202 Accepted` |
| List Managers | `GET` | `/api/v1/admin/managers` | `200 OK` |
| Manager details | `GET` | `/api/v1/admin/managers/{managerId}` | `200 OK` |
| Create Manager | `POST` | `/api/v1/admin/managers` | `201 Created` |
| Manager branches | `GET` | `/api/v1/admin/managers/{managerId}/branches` | `200 OK` |
| Schedule Manager deactivation | `POST` | `/api/v1/admin/managers/{managerId}/deactivation` | `200 OK` |
| Activate Manager | `POST` | `/api/v1/admin/managers/{managerId}/activation` | `200 OK` |
| Create Admin | `POST` | `/api/v1/admin/admins` | `201 Created` |
| Central Ledger position | `GET` | `/api/v1/admin/ledger/central/position` | `200 OK` |
| Central Ledger movements | `GET` | `/api/v1/admin/ledger/central/movements` | `200 OK` |
| Inject Central Ledger funds | `POST` | `/api/v1/admin/ledger/central/injections` | `201 Created` |
| Funding requests | `GET` | `/api/v1/admin/funding-requests` | `200 OK` |
| Funding request details | `GET` | `/api/v1/admin/funding-requests/{requestId}` | `200 OK` |
| Funding decision | `POST` | `/api/v1/admin/funding-requests/{requestId}/decision` | `200 OK` |
| Bank financial summary | `GET` | `/api/v1/admin/dashboard/financial-summary` | `200 OK` |
| Branch financial dashboard | `GET` | `/api/v1/admin/dashboard/branches/{branchId}/financial` | `200 OK` |
| Bank financial activity | `GET` | `/api/v1/admin/dashboard/financial-activity` | `200 OK` |
| Configuration categories | `GET` | `/api/v1/admin/configuration/categories` | `200 OK` |
| Category settings | `GET` | `/api/v1/admin/configuration/categories/{category}/settings` | `200 OK` |
| Get setting | `GET` | `/api/v1/admin/configuration/settings/{settingKey}` | `200 OK` |
| Update setting | `PATCH` | `/api/v1/admin/configuration/settings/{settingKey}` | `200 OK` |
| Configuration history | `GET` | `/api/v1/admin/configuration/history` | `200 OK` |
| Audit search | `GET` | `/api/v1/admin/audit-logs` | `200 OK` |
| Audit details | `GET` | `/api/v1/admin/audit-logs/{auditLogId}` | `200 OK` |
| Audit export | `POST` | `/api/v1/admin/audit-logs/export` | `202 Accepted` |

---

# 144. Notification API Overview

Notifications are customer communication records, not financial records.

The customer-facing Notification API provides access to the logical notification record. It does not expose internal SNS topics, SQS queue names, DLQ state, provider message IDs, delivery-attempt counters or other infrastructure details.

A notification may represent an event that is delivered through one or more supported channels:

```text
Notification
     ├── In-app
     ├── Email
     └── Telegram (when linked)
```

A failed optional external delivery must not remove or invalidate the in-app notification.

Notification records use their stored `validUntil` value for retention. Cleanup may happen later than the expiry time.

Funding-request notifications are the exception: they remain available while the related funding request is pending.

---

# 145. List Notifications

```http
GET /api/v1/notifications
```

### Authentication

Authenticated customer/manager/admin user according to the notification visibility rules.

The service returns only notifications belonging to the authenticated user and within that user's allowed scope.

### Query parameters

```text
page
pageSize
read
priority
notificationType
from
until
```

`read` may be used to retrieve only unread or only read notifications.

Results are ordered newest first unless another ordering option is explicitly added later.

### Response

```json
{
  "items": [
    {
      "id": "notification-id",
      "priority": "HIGH",
      "read": false,
      "type": "TRANSACTION_SUCCESS",
      "title": "Transfer successful",
      "message": "Your transfer was completed successfully.",
      "createdAt": "2026-10-06T10:15:00Z",
      "validUntil": "2026-10-16T10:15:00Z",
      "deepLink": "/transactions/transaction-id"
    }
  ],
  "page": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

The response does not expose technical internal reference fields such as database table names or infrastructure identifiers.

---

# 146. Get Notification Details

```http
GET /api/v1/notifications/{notificationId}
```

Returns the notification when it belongs to the authenticated user and is still retained.

The operation is read-only and is not idempotency-sensitive.

Possible responses:

| Condition | Status |
|---|---:|
| Notification found and accessible | `200 OK` |
| Notification does not exist or is outside the user's scope | `404 Not Found` |

A notification that has already been cleaned up is treated as not found.

---

# 147. Mark One Notification as Read

```http
POST /api/v1/notifications/{notificationId}/read
```

The operation marks the logical notification as read.

It does not change delivery status for email or Telegram and does not trigger another external delivery.

### Responses

```text
200 OK
```

when the notification is successfully marked as read.

If it is already read, the operation remains safe and returns the current notification representation rather than creating another event.

`404 Not Found` is returned when the notification is not accessible to the authenticated user.

---

# 148. Mark All Notifications as Read

```http
POST /api/v1/notifications/read-all
```

Marks all currently readable/unread notifications in the authenticated user's allowed scope as read.

The operation is not a financial operation.

### Response

```json
{
  "updatedCount": 8
}
```

```text
200 OK
```

Calling the operation again is safe. It simply returns `updatedCount = 0` when there are no unread notifications left.

---

# 149. Unread Notification Count

```http
GET /api/v1/notifications/unread-count
```

### Response

```json
{
  "count": 5
}
```

```text
200 OK
```

The count is based on retained notification records available to the authenticated user.

---

# 150. Notification Delivery Boundary

Notification creation and delivery are handled separately from the customer-facing API response.

The normal flow is:

```text
Business event
      ↓
Create Notification
      ↓
Create NotificationDelivery rows
      ↓
SNS
      ↓
SQS
      ↓
Email / Telegram provider
```

The notification API does not wait for optional email/Telegram delivery to complete before returning a successful notification creation result from the originating business operation.

Financial operations never depend on notification delivery succeeding.

The customer may see the logical notification even when one external channel later fails.

Provider-specific retry and DLQ handling remain an infrastructure concern.

---

# 151. Notification Preferences Boundary

Customer notification preferences are managed separately from the notification resource.

Critical security and financial notification classes cannot be disabled.

Optional notification categories may be configurable through the existing customer-preferences API.

A preference change does not rewrite notifications that were already created.

---

# 152. Notification Error Handling

Common errors include:

| Code | HTTP Status | Meaning |
|---|---:|---|
| `NOTIFICATION_NOT_FOUND` | `404` | Notification does not exist or is outside the caller's scope |
| `NOTIFICATION_ACCESS_DENIED` | `403` | Caller is not allowed to access the notification |
| `INVALID_NOTIFICATION_FILTER` | `400` | Query/filter value is invalid |
| `NOTIFICATION_OPERATION_FAILED` | `500` | Unexpected internal failure |

The API never exposes provider credentials, raw queue errors, internal exception traces or infrastructure identifiers.

---

# 153. Statement API Overview

Statements are generated from permanent financial transaction history using read-only Spring JDBC queries to stream matching transaction and balance-snapshot rows into the file generator.

They are not a second financial data store.

Supported formats:

- PDF
- CSV

Supported selection:

- One account
- Multiple selected accounts

Supported filtering includes the defined date range and incoming/outgoing filters.

Statement generation is asynchronous:

```text
REQUESTED
    ↓
PROCESSING
    ↓
READY / FAILED
```

The generated statement file (PDF or CSV) is stored temporarily in Cloudflare R2 under the dedicated `statements/` folder (separate from `profile-photos/`) and is automatically deleted 24 hours after generation. When generation completes (`READY`), the R2 file address and the customer's 24-hour request cooldown are stored in Redis with a 24-hour TTL (`readyAt + 24 hours`).


## 153.1 List Statement Requests

```http
GET /api/v1/statements
```

Authentication:

- CUSTOMER

Returns the authenticated customer's statement-request records within the operational retention period.

Supported filters can include:

```text
status
format
from
until
page
pageSize
sort
```

This is a request-history view only. It does not create a second copy of the financial history or permanently retain generated statement files.

Success:

```text
200 OK
```
---

# 154. Create Statement Request

```http
POST /api/v1/statements
```

### Request

```json
{
  "accountIds": [
    "account-id-1",
    "account-id-2"
  ],
  "format": "PDF",
  "from": "2026-09-01",
  "until": "2026-10-01",
  "direction": "ALL"
}
```

`accountIds` must contain one or more accounts owned/controlled by the authenticated customer according to the statement access rules.

### Validation

The backend validates:

- At least one account is selected.
- Every selected account belongs to the authenticated customer.
- The date range is valid.
- The requested format is supported.
- The requested filters are valid.
- The customer has not exceeded the 24-hour statement-request rule.

The frontend may perform the same checks for usability, but the backend is authoritative.

### Cooldown and pending-request validation

- If the customer already has a statement request in `REQUESTED` or `PROCESSING` state (for example, requested at 4:00 PM and waiting for the night generation schedule at 12:00 AM), a new request is rejected with `409 Conflict` (`STATEMENT_ALREADY_PENDING`).
- Once the statement is generated at night (`READY`), a 24-hour cooldown key (`statement:cooldown:{customerId}`) and the R2 file address key (`statement:file:{statementRequestId}`) are written to Redis with the same 24-hour TTL (`readyAt + 24 hours`).
- If the customer attempts to create a new statement request during that 24-hour `READY` window, the backend returns `429 Too Many Requests` (`STATEMENT_REQUEST_LIMIT_REACHED`) along with `retryAfterSeconds` / `nextAllowedAt` so the UI can display the exact remaining time.

### New request response

```json
{
  "statementRequestId": "statement-request-id",
  "status": "REQUESTED",
  "format": "PDF",
  "requestedAt": "2026-10-06T10:15:00Z"
}
```

```text
202 Accepted
```

The request has been accepted for asynchronous generation. It does not mean the file is already ready.

---


# 155. Statement Request Idempotency

`POST /statements` should use `Idempotency-Key` for a critical state-changing submission.

The same authenticated customer using the same key with the same request body must not create multiple statement-request records.

The database remains the final idempotency guard.

Redis may accelerate duplicate detection, but Redis is not the final correctness mechanism.

A successful duplicate submission may return the original statement-request representation.

Reusing the same idempotency key with a materially different request body returns a conflict/error rather than silently changing the original request.

---

# 156. Statement Request Status

```http
GET /api/v1/statements/{statementRequestId}
```

### Response example

```json
{
  "statementRequestId": "statement-request-id",
  "status": "PROCESSING",
  "format": "PDF",
  "requestedAt": "2026-10-06T10:15:00Z",
  "readyAt": null
}
```

For a ready statement:

```json
{
  "statementRequestId": "statement-request-id",
  "status": "READY",
  "format": "PDF",
  "requestedAt": "2026-10-06T10:15:00Z",
  "readyAt": "2026-10-06T10:16:10Z"
}
```

For a failed generation:

```json
{
  "statementRequestId": "statement-request-id",
  "status": "FAILED",
  "format": "PDF",
  "failureCode": "STATEMENT_GENERATION_FAILED"
}
```

Technical exception details are not returned.

---

# 157. Download Generated Statement

```http
GET /api/v1/statements/{statementRequestId}/file
```

The endpoint is available only when the request is in `READY` state and `now < expires_at`.

### Behaviour

```text
READY + valid R2 object address
        ↓
Resolve R2 object address from Redis
        ↓
Fall back to statement_request.r2_object_key in PostgreSQL if Redis is unavailable
        ↓
Stream/fetch file from Cloudflare R2
```

When supported by the selected format, the response uses the appropriate content type and file download headers.

Possible responses:

| Condition | Status |
|---|---:|
| File ready and available | `200 OK` |
| Request still processing | `409 Conflict` |
| Generation failed | `409 Conflict` |
| R2 file has expired or is unavailable | `410 Gone` |
| Request not accessible | `404 Not Found` |

A missing R2 file does not mean the underlying financial history is missing.

---

# 158. Resend Existing Statement

```http
POST /api/v1/statements/{statementRequestId}/resend
```

This operation allows the customer to send an already-generated statement again while the R2 file is still available and `now < expires_at`.

The backend resolves the R2 object address from Redis (falling back to `statement_request.r2_object_key` in PostgreSQL if Redis is unavailable) and streams/fetches the file from Cloudflare R2 (`statements/` folder). The system reuses the existing file instead of regenerating the statement.

A resend is not treated as a new statement-generation job.

### Response

```text
202 Accepted
```

The response means the resend request was accepted for asynchronous email delivery. It does not mean the email provider has already completed delivery.

If the R2 file is no longer available:

```text
410 Gone
```

The API does not silently regenerate the file as part of resend.

---

# 159. Statement Completion Rule

The statement workflow considers the operational request successful only after the generated statement has been accepted by the configured email delivery provider for sending to the customer, according to the project requirement.

The API therefore distinguishes between:

```text
Statement generation completed
        ≠
Email-provider acceptance completed
```

The exact internal delivery acknowledgment remains outside the public API.

A repeated scheduler invocation must not regenerate or resend the same statement unexpectedly once the statement request has reached its protected terminal handling state.

---

# 160. Statement Security

Statement APIs require authentication and ownership/scope checks.

A customer cannot request or download another customer's statement by guessing a statement-request ID.

Statement data is generated from the authenticated customer's permitted financial history.

The API must not expose another customer's account number, balance, transaction or statement data through authorization mistakes.

Statement files are short-lived and are not treated as permanent records.

---

# 161. Statement Errors

Common errors include:

| Code | HTTP Status | Meaning |
|---|---:|---|
| `STATEMENT_INVALID_REQUEST` | `400` | Statement criteria are invalid |
| `STATEMENT_ACCOUNT_NOT_ALLOWED` | `403` | One or more selected accounts are outside the caller's scope |
| `STATEMENT_NOT_FOUND` | `404` | Statement request does not exist or is outside the caller's scope |
| `STATEMENT_NOT_READY` | `409` | File cannot be downloaded because generation is not complete |
| `STATEMENT_FILE_EXPIRED` | `410` | Generated file is no longer available in the short-lived cache |
| `STATEMENT_REQUEST_LIMIT_REACHED` | `429` | Customer is inside the 24-hour request window |
| `STATEMENT_GENERATION_FAILED` | `500` | Statement generation failed |
| `STATEMENT_SERVICE_UNAVAILABLE` | `503` | Required statement-generation capability is temporarily unavailable |

---

# 162. Statement and Financial History Boundary

Statement generation is read-oriented from a financial perspective.

It must never:

- modify a finalized financial transaction,
- modify accounting ledger entries,
- change account balances,
- delete financial history,
- or create a second financial record merely because a statement was requested.

The statement simply presents permanent financial history in the requested format.

Previous and updated balance snapshots may be calculated/retained for statement generation, but these are presentation data and are not exposed as a second balance source of truth in normal transaction history.

---

# 163. Notification and Statement Summary

| Operation | Method | Endpoint | Result |
|---|---|---|---|
| List notifications | `GET` | `/api/v1/notifications` | `200 OK` |
| Notification details | `GET` | `/api/v1/notifications/{notificationId}` | `200 OK` |
| Mark notification read | `POST` | `/api/v1/notifications/{notificationId}/read` | `200 OK` |
| Mark all read | `POST` | `/api/v1/notifications/read-all` | `200 OK` |
| Unread count | `GET` | `/api/v1/notifications/unread-count` | `200 OK` |
| Create statement request | `POST` | `/api/v1/statements` | `202 Accepted` |
| List statement requests | `GET` | `/api/v1/statements` | `200 OK` |
| Statement status | `GET` | `/api/v1/statements/{statementRequestId}` | `200 OK` |
| Download statement | `GET` | `/api/v1/statements/{statementRequestId}/file` | `200 OK` |
| Resend statement | `POST` | `/api/v1/statements/{statementRequestId}/resend` | `202 Accepted` |

---

# 164. Cross-Cutting API Summary

This is the final end-to-end review of the V1 HTTP API against the current requirements, architecture and database model.

## 164.1 Endpoint coverage result

The API now covers all client-visible business capabilities defined for the current V1 scope.

| Area | Result |
|---|---|
| Authentication and password flows | Complete |
| Customer profile and preferences | Complete |
| Customer profile photo and R2-backed storage | Complete |
| Account metadata and account lifecycle | Complete |
| Account closure and branch-transfer workflows | Complete |
| Transfers and recipient verification | Complete |
| Transaction history and details | Complete |
| UPI management and PIN operations | Complete |
| Beneficiaries | Complete |
| Money requests | Complete |
| Scheduled UPI payments | Complete |
| Debit cards | Complete |
| Credit cards | Complete |
| Loan products and loan applications | Complete |
| Pre-disbursement loan destination | Complete |
| Loans and EMI operations | Complete |
| Manager branch context and branch work | Complete |
| Manager approvals/reviews | Complete |
| Manager debit-card limit review | Complete |
| Admin branches/managers | Complete |
| Admin loan products | Complete |
| Central Ledger and funding | Complete |
| Admin financial dashboard | Complete |
| Admin configuration/history | Complete |
| Audit search/details/export | Complete |
| Notifications | Complete |
| Statements | Complete |

## 164.2 Profile-photo flow

```text
Customer profile
      ↓
POST   /customers/me/profile-photo     upload
PUT    /customers/me/profile-photo     change
DELETE /customers/me/profile-photo     remove
      ↓
Cloudflare R2
      ↘
       PostgreSQL object reference/metadata
```

The customer's own profile receives a short-lived display URL when a photo exists.

When a customer verifies a phone-number recipient before payment, the response may include the recipient name and optional profile-photo view URL. The customer can open the photo through that temporary URL.

The profile photo remains presentation data. It is not an authentication factor, payment authorization factor or financial source of truth.

## 164.3 Recipient verification review

Phone-number verification continues to use the existing default-phone-UPI resolution:

```text
Phone number
    ↓
Customer default phone UPI
    ↓
Current eligible destination account
    ↓
Recipient details
    ├── Name
    ├── Other permitted confirmation details
    └── Optional profile photo
```

The photo preview does not replace final recipient revalidation at payment time.

## 164.4 Authentication and authorization consistency

All protected endpoints use the common authenticated-user context.

Ownership is checked for customer resources.

Branch assignment is checked for Manager resources.

Admin APIs are limited to the explicit bank-wide administrative capabilities defined by the requirements.

Frontend role checks are only convenience checks; the backend remains authoritative.

## 164.5 Pagination/filtering consistency

Collection endpoints that can grow use the common pagination model:

```text
page
pageSize
sort
```

Endpoint-specific filters are explicitly documented and validated by the backend.

The API never accepts arbitrary sort expressions or database query fragments from the client.

The common response shape is:

```json
{
  "items": [],
  "page": 0,
  "pageSize": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

Small bounded collections may omit pagination when the product itself provides the bound.

## 164.6 Error consistency

The common error envelope and status-code rules apply across all resources.

```text
400  invalid request
401  authentication failure
403  not authorized
404  not found/not visible
409  current-state/concurrency conflict
422  business validation failure
429  rate limit exceeded
500  unexpected internal failure
503  required dependency temporarily unavailable
```

The API never exposes stack traces, SQL errors, credentials, raw OTP values, PINs, CVVs or unrelated infrastructure identifiers.

## 164.7 Idempotency consistency

Critical state-changing requests use:

```http
Idempotency-Key: <unique-key>
```

This includes financial transfers, payment acceptance, important request submissions, card/loan financial operations, critical Admin financial changes and other operations where duplicate submission could create a duplicate business effect.

The PostgreSQL guard is final. Redis can accelerate duplicate detection but does not replace the database guard.

## 164.8 Concurrency consistency

The API contracts explicitly account for conflicting operations such as:

```text
transfer vs transfer
money-request accept vs cancel/decline
scheduled-payment cancel vs execution
loan offer/customer action vs final processing
card payment vs automatic payment
account suspension/reactivation vs scheduled expiry
branch-transfer approval vs cancellation
funding approval vs duplicate approval
Manager replacement vs branch work
configuration update vs stale Admin update
```

Only one valid state transition may win where operations conflict.

## 164.9 Financial integrity consistency

The API does not bypass the established financial model.

```text
Request
  ↓
Business validation
  ↓
PostgreSQL transaction
  ↓
Operational/account effects
  ↓
FinancialTransaction
  ↓
LedgerEntry
```

Finalized financial history is immutable and is never removed by normal API operations.

Financial corrections use new compensating/reversal events.

## 164.10 Asynchronous operation consistency

The API distinguishes between accepting a request and completing the underlying asynchronous work.

This applies to areas such as:

- Scheduled payments
- Statement generation
- Statement email resend
- Branch decommissioning
- Manager/Admin workflows that intentionally schedule future actions

Where the final result is not known at request time, the API returns the appropriate pending/accepted response and exposes the relevant resource state through its read endpoint where available.

## 164.11 Supporting-service failure boundaries

Redis remains an accelerator and cache, not the financial source of truth.

R2 stores profile-photo objects and temporary statement files; its failure may degrade the related photo/statement operation but must not break unrelated banking operations.

Notification provider failures do not reverse completed financial operations.

## 164.12 Out of Scope for V1

- Direct Admin customer-edit/impersonation APIs
- Direct Trust Score editing APIs
- Financial transaction edit/delete APIs
- Permanent scheduled-payment history APIs
- SMS APIs
- Physical account/branch deletion APIs
- Generic unrestricted configuration key/value updates
- Customer-facing SNS/SQS/DLQ/provider-management APIs
- Permanent public customer-photo URLs