# Banking System — Project Requirements

**Document:** Project Requirements  
**Status:** Requirements baseline updated after domain-model and business-rule review  
**Project type:** Learning/resume-grade simulated banking system  
**Development model:** Solo developer — separate frontend and backend repositories  
**Frontend:** React, React Router, Redux, Axios, Tailwind CSS
**Backend:** Java 25 + Spring Boot 4.1.1  
**Target deployment:** Vercel frontend, API Gateway HTTP API, AWS Lambda backend, Neon PostgreSQL, Upstash Redis, Cloudflare R2, Amazon EventBridge Scheduler, Amazon SNS, Amazon SQS, AWS CloudWatch  

---

# 1. Purpose

This project is a simulated banking platform built for learning and for use as a resume project.

The system models common banking workflows such as accounts, transfers, UPI, cards, loans, EMIs, branches, approvals, financial records, notifications and bank-wide administration.

The system does not connect to real banking rails, real customers, real card networks or real money. UPI and other payment flows are simulated inside the application.

The main goal is to build the project as one connected system. Frontend behaviour, backend rules, financial records and administrative policies should match each other.

The project is intentionally designed to stay low-cost. Technology should only be added when it solves a real problem or gives useful learning value.

---

# 2. Product Scope

The application has three roles:

- **Customer** — uses banking products and performs customer financial operations.
- **Manager** — handles branch-level customer work and reviews customer requests.
- **Admin** — handles bank-wide operations, branches, managers, central funds and bank policies.

There is one frontend application. The available screens and actions depend on the logged-in role.

```text
Banking Application
├── Customer experience
├── Manager experience
└── Admin experience
```

Frontend and backend are separate applications and communicate through APIs.

---

# 3. Identity and Role Model

## 3.1 Customer vs Employee

Customer and employee are different domain concepts.

- Customers are not employees.
- A normal customer cannot be promoted to Manager.
- Admin creates Manager accounts.
- Admin can create another Admin account when required.
- Manager accounts are employee accounts.
- Managers cannot be promoted to Admin.
- Managers and Admins do not participate in the customer payment system.

A shared authentication/identity mechanism may be used internally, but customer and employee responsibilities must remain separated in the domain.

The shared authentication record uses an internal database ID and a separate user-facing `login_id`. Customer and employee records reference the shared authentication record rather than storing duplicate login identities.

Employee records also have a separate immutable `employee_number`.

Passwords are stored as strong adaptive password hashes and are never stored in reversible form. Argon2id is the preferred password hashing approach.

## 3.2 Roles

A person has one active role at a time:

- CUSTOMER
- MANAGER
- ADMIN

## 3.3 Authorization

Backend authorization is final.

The frontend can hide pages and buttons based on role, but the backend must check the permission again for every protected operation.

---

# 4. Customer Requirements

## 4.1 Authentication

Customer login uses:

- User ID + password

Email is the main communication channel for 2FA, OTPs and critical customer communication.

Successful customer login requires the configured second authentication step.

The system does not use phone numbers for login, OTP delivery, authentication or security notifications.

Forgot password:

- Requires strong verification.
- Ends in a fresh login.
- A successful password reset must not automatically log the customer in.

Forgot User ID requires strong verification and sends the required information through the approved communication channel.

Password change requires the current password and the configured second authentication step.

OTP rules include:

- Expiry
- Length
- Maximum attempts
- Resend cooldown
- Temporary blocking after repeated failures

Customer security policies may be configurable by Admin where explicitly provided. Employee/Admin authentication has a stricter protected policy.

### Session and JWT token architecture

Authentication uses stateless JWT tokens for both access and refresh tokens:

- **Access Token:** Short-lived JWT (5-minute expiry) containing the authenticated user's identity and role, held in frontend memory and sent in the `Authorization: Bearer <access-token>` header.
- **Refresh Token:** JWT (2-hour expiry) signed by the backend containing `userId` and a unique `tokenId`, stored in a host-only `Secure`, `HttpOnly` session cookie using `Path=/api/v1/auth` and `SameSite=Lax`, without `Domain`, `Max-Age` or `Expires`, so the browser removes it when the window closes.
- **Normal refresh flow:** When the access token expires, the frontend calls the refresh endpoint using the `HttpOnly` refresh cookie. The backend verifies the JWT signature and claims without requiring a database lookup.
- **Redis rotation and revocation tracking:** Redis is used as an accelerator to track Refresh Token Rotation (`token:used:{tokenId} = true`) and explicit logouts (`token:revoked:{tokenId} = true`) for the 2-hour token lifetime.
- **Redis failure resilience:** If Redis is temporarily unavailable during token refresh or logout, the backend catches the Redis error, logs a warning, and falls back to verifying the cryptographic JWT signature so active users are not logged out and the application continues operating without downtime.

The frontend never decides whether a second factor or OTP is required for an operation. The backend enforces all 2FA and OTP rules and informs the frontend when verification is required.

### OTP storage and verification

OTP records are stored in PostgreSQL as the durable record and Redis as the fast active-OTP store.

An OTP challenge includes a unique challenge identity and a `purpose` so that an OTP is valid only for the operation it was created for. Where the challenge authorizes a specific operation payload, it is also bound to the relevant request fingerprint and idempotency key so a verified challenge cannot authorize a different amount, recipient, destination or operation.

The OTP record does not store `max_attempts`. The maximum number of attempts comes from the active Admin configuration. The OTP record stores the current `attempt_count`.

OTP challenges use the lifecycle `PENDING → VERIFIED → CONSUMED`. A challenge may remain `VERIFIED` only for a short configured consumption window. The protected operation validates the owner, purpose, `VERIFIED` status, expiry, and any bound request context, then marks the challenge `CONSUMED` in the same PostgreSQL transaction as the protected state change. A verified challenge cannot be used twice.

When a new OTP is requested for the same authenticated user and purpose:

- If a current `PENDING` challenge was created within the resend-cooldown window, the backend reuses its challenge metadata rather than overwriting the code that was just dispatched.
- After the resend cooldown, a permitted resend replaces the pending OTP in one PostgreSQL transaction and updates the Redis active-OTP key. The previous challenge/code is no longer valid.
- A `VERIFIED` challenge is not replaced by a resend; it must be consumed by its intended operation or expire.
- Public flows for which no account exists yet may use a nullable `user_account_id` and a persisted synthetic challenge so their responses do not reveal whether an account exists.

Normal verification uses Redis when Redis is available, but PostgreSQL remains authoritative for challenge status, expiry, attempt count, purpose, consumption and the verified-consumption window. A successful Redis code match is not enough to report verification success if the durable PostgreSQL status update fails.

If Redis is unavailable, verification falls back to PostgreSQL and uses the current valid challenge for the user and purpose. Expiry and the configured maximum-attempt policy are still enforced.

OTP generation and verification are rate-limited. The system also limits repeated resend attempts so that a customer cannot generate large numbers of OTPs in a short period.

For a public Forgot User ID flow, successful verification consumes the challenge and initiates the asynchronous email containing the customer's login ID. For registration, successful OTP verification marks the challenge `VERIFIED`; the following registration-completion endpoint consumes it while creating the customer records atomically. For other protected operations, the challenge is consumed by the operation it authorizes.

OTP rows and abandoned registration contexts that remain because of an expired or abandoned flow are removed later by the scheduled cleanup job.

## 4.2 Customer profile

Customer profile includes:

- Customer ID
- User ID
- Legal/full name
- Date of birth
- Primary email
- One simulated Indian mobile number used only for UPI payments
- Address
- Telegram linkage, when connected
- Profile picture
- Nickname
- Preferences

Rules:

- Customer ID is immutable.
- User ID is immutable.
- Email is the primary customer communication identity.
- Email is normalized consistently and is unique case-insensitively among customer records.
- Each customer has exactly one simulated mobile number. The mobile-number field is required and cannot be `NULL`.
- The simulated mobile number is not real-world ownership verified.
- The mobile number is used only for UPI payment addressing.
- Mobile number format is validated on frontend and backend.
- Simulated Indian mobile number format is 10 digits and starts with 6, 7, 8 or 9.
- Mobile number is globally unique among customer records.
- Mobile number replacement requires an OTP sent through the approved non-SMS channel, which is the customer's current verified email.
- A mobile-number change retires the old default phone UPI and creates the new phone-derived UPI identifier in the same PostgreSQL transaction as the phone-number change.
- After replacement, the old number and old default phone UPI address are released and may be used by another customer.
- Customers are not required to provide their real personal mobile number for this simulated project.
- Changing the primary email requires OTP verification through the current verified email and a second OTP sent to the new email. The new email is committed only after both checks succeed.
- Telegram linkage changes use the dedicated authenticated link/unlink flow. Linking requires an email-based OTP step-up and proof of access to the Telegram account through the single-use bot deep-link challenge; unlinking requires an email-based OTP step-up.
- Legal name and other sensitive profile changes use a Manager-reviewed profile-change request workflow scoped to the customer's selected active account branch.
- Only one active profile-change request is allowed per customer at a time, and each request stores a `validTill` timestamp using the active Admin-configured request expiry policy.
- Expired profile-change requests cannot be approved and are removed by the scheduled request cleanup job.
- Telegram is optional and can be unlinked later.
- Profile picture can be removed and replaced with a default avatar.
- Profile picture files must be JPEG, PNG or WEBP and must not exceed 2 MB (2,097,152 bytes). The frontend checks the size before upload, and the backend independently validates the actual uploaded file size, media content and supported format before storing it.
- Nickname is only a display preference.

Managers and Admins have no mobile-number field and no option to add one.

Admin does not directly modify an individual customer's profile or normal account operations.

## 4.3 Customer communication

Customer notifications can be delivered through:

- In-app notifications
- Email
- Telegram when linked

SMS is not part of the system.

Critical security and financial notifications cannot be disabled.

Non-critical notification preferences can be configured by the customer.

Email is the default communication channel for OTPs and other critical customer communication.

Telegram is optional and must never be the only channel required for a critical authentication/security action. A customer must unlink an existing Telegram account before linking a different one. Telegram link tokens and update-processing state have a 30-minute customer challenge lifetime; PostgreSQL remains authoritative if Redis is unavailable.

Telegram linkage uses client-triggered long polling through `getUpdates`; V1 does not configure a webhook or run a permanently running polling thread. One serialized poller consumes the shared update queue. `limit=100` means up to 100 updates per batch; `offset` is an update ID checkpoint, not a count of updates. Only a valid `/start` parameter matching an active unexpired bank-issued challenge is retained; ordinary messages and unknown/expired tokens are ignored. The bot start parameter uses an allowed prefix/suffix and a cryptographically secure random token within Telegram's 64-character limit.

Each batch's recognized challenge matches and the next update checkpoint are committed together in PostgreSQL before the checkpoint cache in Redis is updated. The durable checkpoint is recovered from PostgreSQL when Redis is missing or stale. The poller never uses `offset=-1` to recover state because that can discard earlier unprocessed updates. Polling uses a single database-backed lease and a bounded number of batches/time per API invocation; if the queue is not exhausted within that bound, the endpoint returns a pending result and the customer can retry verification. Only the first valid `/start` update can match a pending, unexpired challenge. Redis caches the raw active token for link reuse; if that cache is lost, the prior unmatched challenge is expired and a new challenge is generated because the raw token is intentionally not recoverable from its database hash. Binding is completed only when the authenticated customer verifies their own matched challenge, and a Telegram chat may be linked to at most one customer.

---

## 4.4 Customer Registration and Onboarding

Customer registration uses two public registration endpoints, with the shared OTP challenge-verification endpoint used between them (`POST /api/v1/auth/register`, `POST /api/v1/auth/challenges/{challengeId}/verify`, then `POST /api/v1/auth/verify-registration`).

Registration initiation validates the format and current uniqueness of `loginId`, email and simulated Indian mobile number, hashes the password with Argon2id, and creates a purpose-bound `REGISTER_CUSTOMER` OTP challenge. The pending registration snapshot, including the already-hashed password, is stored only in the PostgreSQL challenge context. Redis stores the active OTP fast-path value, not the pending registration snapshot. The backend does not create `user_account`, `customer`, `customer_preferences`, Trust Score or UPI rows before OTP verification succeeds.

The generic purpose-bound OTP verification endpoint verifies the registration code and moves the challenge from `PENDING` to `VERIFIED` for the short consumption window. `POST /api/v1/auth/verify-registration` then rechecks uniqueness under database constraints and atomically consumes the verified challenge and creates `user_account`, `customer`, `customer_preferences`, the initial `trust_score`, an initial `trust_score_history` entry and the reserved default phone UPI record with no account mapping until the customer's first account is created.

The registration completion response does not automatically authenticate the customer. Registration ends in a clean state and requires the customer to log in through the normal authentication flow. Repeated completion requests for the same successful registration must not create duplicate records and should return the same logical result where the original idempotency context is available.

---

# 5. Accounts

## 5.1 Account types

The account types are defined by the backend.

The customer can have at most one account of the same account type.

For the current V1 account types, this means:

- One Savings Account
- One Current Account

The account type is selected from the account types returned by the backend. The frontend does not hard-code the available account types.

If another account type is added later, the account-creation flow should continue to work using the same account-type rule.

An account that is suspended or waiting for final closure still counts toward the one-account-per-type rule until it reaches the final CLOSED state.

## 5.2 Account ownership

Each account belongs to exactly:

- One Customer
- One Branch at a time

A customer can have accounts in different branches.

A customer does not have a separate permanent home branch. The account's current branch is the relevant branch relationship.

## 5.3 Account creation

Account creation does not require Manager approval.

Customer chooses:

- Account type
- Branch

The backend generates the account number.

The first account automatically becomes the account used by the default phone UPI.

Creating another account does not automatically change the default phone UPI mapping.

The account receives the configured initial amount from the bank.

Initial funding:

- Is configured separately for the available account types.
- Comes from the Central Ledger.
- Is not created by the frontend.
- Is recorded as a financial event.
- Uses the current Admin setting when the account is created.
- Does not change existing accounts when the setting is changed later.

## 5.4 Primary account

Each customer can have at most one account mapped to the default phone UPI.

This account is treated as the customer's primary account only for the purpose of the default phone UPI.

The primary account has no other special business meaning in the system.

The customer can change the default phone UPI mapping to another eligible active account.

If the current default-UPI account is suspended and another active account exists, the customer can move the default phone UPI to that active account so phone-number payments can continue to be received.

If there is no other active account, the customer can still suspend the current account. The default phone UPI remains reserved for the customer but is not usable while it has no active destination account.

The default phone UPI becomes usable again when it is mapped to an eligible active account.

---
# 6. Account Suspension

Suspension is different from permanent closure.

### Customer suspension

A customer may suspend an eligible account.

Rules:

- Suspension is immediate after required authentication.
- Maximum suspension duration is permanently capped at 90 days.
- Customer may choose a predefined duration or a custom duration within the 90-day limit.
- Backend enforces the 90-day limit.
- Primary-account status does not prevent suspension.
- Account remains visible and readable.
- Financial activity on the account is blocked while the account is suspended.
- Account-specific UPI sending and receiving are blocked while suspended.
- Related payment/card behaviour follows the account suspension rules.
- Customer can reactivate the account before expiry using the configured strong authentication/OTP flow.
- The account automatically becomes ACTIVE when the suspension duration ends.
- The exact suspension end date and time are stored in the system.
- A one-time EventBridge Scheduler schedule is created for the suspension end time so the account can be made ACTIVE without user intervention.
- The scheduled operation is idempotent and must not create a second business effect if it is invoked again.
- If the customer reactivates the account early, the related suspension record is resolved and the scheduled task is removed or otherwise made harmless.

The backend must not depend on Redis for the long suspension window.

The stored suspension end time remains authoritative. If a scheduled invocation is delayed and an account-related operation reaches the account after the suspension end time, the backend can reconcile the overdue suspension before continuing so that the account does not remain frozen only because the scheduler was delayed.

A customer cannot suspend an account when the suspension would leave an incompatible scheduled payment or automatic credit-card payment relationship unresolved. The customer must resolve the conflict first.

### Manager suspension

A Manager may suspend a customer account within the Manager's branch authority.

Manager suspension requires:

- Reason
- Audit record
- Customer notification

Manager may reactivate an account where the workflow allows it.

Manager suspension may remove incompatible scheduled payments according to the defined product rules.

---

# 7. Account Closure

Closure is a separate lifecycle from suspension.

### Request phase

Customer requests closure.

The closure request is an expirable customer request and stores a `validTill` value using the active Admin-configured request expiry policy.

Only one active closure request is allowed for an account at a time. If an active closure request already exists, the customer must cancel/delete it before creating another closure request. A customer may not modify an active closure request in place; the existing request must be deleted first.

Before approval, the backend checks the account for conditions such as:

- Zero balance
- No active loan obligation that depends on the account
- No outstanding credit-card obligation that blocks closure
- No unresolved financial conflict
- Other applicable backend checks

While the closure request is only waiting for Manager approval/rejection:

- The account remains operational.
- Incoming money is allowed.
- Customer can cancel the closure request.

If the closure request passes its `validTill` time before Manager action, it can no longer be approved and is removed by the request cleanup process.


### Approval

Manager can approve or reject.

Rejection produces a notification and may include the Manager's reason.

Approval moves the account into the closure cooling state.

When approval moves the account into cooling, the current Account Closure cooling period is used and its exact end date and time are stored.

The initial Account Closure cooling period for V1 is **90 days**. Admin can change this setting later for future closure cooling periods.

Changing this setting later does not change an existing account's already-stored closure cooling end time.

A one-time EventBridge Scheduler schedule is created for the closure cooling end time.

### Closure cooling

During cooling:

- Account is non-operational.
- New incoming money is rejected.
- Historical information remains available according to access rules.
- Customer gets a visible countdown.
- Customer can reactivate the account using OTP/strong authentication before cooling ends.
- If the cooling period reaches its end, the scheduled job changes the account to CLOSED.
- The scheduled close operation is idempotent so a repeated invocation cannot close the account twice or create another financial effect.

After the cooling period expires:

- Account becomes CLOSED.
- Account cannot be reactivated.
- Financial records remain permanently.
- A new account of the same type may be created after the previous account reaches the final CLOSED state.

The backend should use the stored closure cooling end time as a safety check if an operation reaches the account after the scheduled close time but before the scheduler has run.

The account itself is not physically deleted. The final state is CLOSED and the financial history remains available to the system.

---
# 8. Customer Financial Dashboard

After login, a customer reaches Home.

Home can show:

- Combined balance of active accounts
- Savings balance
- Current balance
- Primary indicator
- Recent activity
- Credit-card summary
- Loan summary
- UPI summary

The combined balance is built from active customer accounts and is not treated as a separate financial balance stored independently.

Customer can view:

- Combined activity
- Account-specific activity

Unified activity is a combined view of existing records, not a second financial source of truth.

---

# 9. Transfers

Transfers are inside the simulated bank only.

External bank transfers are outside current scope.

## 9.1 Account + IFSC transfer

Customer enters:

- Account number
- IFSC
- Amount
- Optional remark

Frontend validates the input for user experience.
Backend validates everything again.

Destination is verified before final payment authentication.

Typical flow:

```text
Details → Recipient Verification → Review → Authentication → Submit
```

## 9.2 UPI transfer

Normal UPI flow:

```text
UPI ID → Verify recipient → Show recipient details → Amount → Review → Submit with UPI PIN
```

The backend evaluates whether the transfer amount exceeds the active Admin-configured OTP threshold. If the amount is within the threshold, the backend verifies the UPI PIN and processes the transfer directly. If the amount exceeds the threshold, the backend automatically triggers and sends a purpose-bound OTP challenge and returns a response indicating that OTP verification is required. After the customer verifies the challenge, the customer submits the transfer with the verified `verificationChallengeId` to complete the payment.

## 9.3 Phone-number payment

Phone-number payment is not a separate transfer engine.

The phone number is converted into the customer's default UPI ID by adding the bank domain:

```text
9876543210
      ↓
9876543210@bank-domain
      ↓
default UPI lookup
      ↓
recipient account
      ↓
normal UPI payment flow
```

The generated UPI ID is an internal detail of the flow. The customer interface shows the phone number and verified recipient details rather than requiring the customer to enter the generated UPI ID.

Before payment, the system shows enough recipient information for the sender to confirm the intended person.

## 9.4 Own-account transfer

A customer can transfer money between their own eligible accounts.

The same source and destination account are rejected.

Backend performs the final validation.

## 9.5 Transfer confirmation

Before money movement, the customer sees:

- Source account
- Recipient
- Amount
- Transfer method
- Remark where applicable
- Fees where applicable
- Destination details

For every transfer method, the backend determines whether OTP verification is required, including when the amount exceeds the active Admin-configured OTP threshold. The frontend never decides whether a second factor is required. If OTP verification is required, the backend creates a purpose-bound challenge and returns the required challenge state; the transfer is completed only after the customer verifies the challenge and submits the operation with `verificationChallengeId`.

Successful financial activity cannot be cancelled through normal customer UI.

---

# 10. Financial Transactions and Records

Financial records are part of the permanent financial history of the system.

Financial records and accounting records are never deleted as a normal cleanup action.

Historical financial records are not directly edited after they are finalized.

If a financial correction is required, the correction is represented through a new compensating/reversal financial event instead of rewriting the old financial history.

### 10.1 Final financial transaction

A `FinancialTransaction` is the permanent financial record of a completed financial operation.

It is created only after the financial outcome is known. A finalized financial transaction is immutable.

Possible final statuses are:

- SUCCESS
- FAILED

Failed financial transactions are retained as part of the financial history. A failed financial transaction does not create accounting movement for money that was not moved.

### 10.2 Transaction details

Transaction details may include:

- Transaction ID
- Date/time
- Amount
- Sender
- Recipient
- Source account
- Destination account
- Transfer method
- Status
- Remark
- Fees
- Failure reason where applicable

Customer transaction history and accounting/branch ledger history are separate concepts.

Customer transaction history does not need to show whether a financial transaction originally came from a money request or scheduled payment.

# 11. Transaction Limits

Admin can configure applicable transaction policies such as:

- Minimum transfer amount
- Maximum per-transaction amount
- Per-rail limits
- Daily monetary limits
- Daily transaction-count limits where required
- OTP threshold
- Fees

Frontend uses the configured values for early validation, but backend is authoritative.

Lowering a limit does not invalidate previously completed valid transactions.

---

# 12. Beneficiaries

A Beneficiary represents a bank account that the customer has saved as a recipient for future transfers.

When viewing a beneficiary, the customer can see the account holder's name. Opening the beneficiary's profile/details can show the account number and IFSC information that identifies the beneficiary account.

Beneficiaries are primarily used for Account + IFSC recipients.

Lifecycle:

```text
Enter beneficiary
    ↓
Verify
    ↓
OTP
    ↓
30-minute cooling period
    ↓
Usable beneficiary
```

Rules:

- Maximum beneficiary count is Admin-configurable.
- Cooling period is fixed at 30 minutes.
- Beneficiary is visible during cooling but cannot be used for transfer.
- Nickname can be added after cooling.
- Beneficiary cannot be edited directly; delete and recreate.
- Duplicate beneficiaries are blocked.
- Delete is immediate.
- Existing beneficiaries remain valid when Admin lowers the new maximum.
- New beneficiaries are blocked until the customer's count becomes valid under the new maximum.

Beneficiary history may remain linked to past financial activity, but the current usable-beneficiary record is not a permanent financial identity.

---

# 13. Money Requests

Customers can request money from another customer using the simulated UPI system, including phone-based addressing.

The money request stores the requester's receiving UPI/account relationship and the requested payer's customer/user identity that existed when the request was created. The payer is not fixed to one account and may choose any eligible active account when accepting the request.

Lifecycle:

```text
PENDING → PAID / DECLINED / CANCELLED
```

Rules:

- Amount must be greater than zero.
- Receiver is identified and shown before request creation.
- Optional remark is supported.
- The request stores the requester's UPI ID and the account mapped to that UPI ID at the time of request creation.
- The request stores the requested payer's UPI ID and the requested payer's customer/user identity at the time of request creation.
- The request does not store a fixed payer account number because the requested payer may choose any eligible active account when accepting the request.
- Stable internal UPI/account identities needed for the requester's receiving side are stored so that a reused UPI string cannot redirect an old request to another customer.
- The request stores `validTill` using the active Admin-configured money-request validity period.
- Requester can cancel while the request is pending. The request is then deleted.
- Requested payer can reject while the request is pending. The request is then deleted.
- Requested payer can accept while the request is pending.
- When the requested payer accepts, the request expiry is checked before payment.
- The current mapping of the requester's stored UPI identity is checked again before payment.
- The requested payer's customer/user identity is checked before payment, and the payer may choose any of their eligible active accounts as the payment source.
- The requester's current mapped account must match the account recorded when the request was created. If the requester's mapping no longer matches, the payment is rejected and the request is removed.
- Payment is completed before the request is deleted when the requested payer accepts.
- Partial payment is not supported.
- If no user action is taken, the request remains in the database until its `validTill` time and is later removed by the request cleanup job.
- Cleanup does not need to happen exactly at `validTill` because request deletion is not time-critical.
- If a stale request survives cleanup and someone tries to process it after `validTill`, the backend rejects the action rather than allowing the expired request to be paid.
- Money-request deletion does not remove or change any permanent financial transaction that was already created.
- The resulting financial transaction does not store that the payment came from a money request.
- Concurrent actions must allow only one valid state transition.

There is no separate `EXPIRED` state for money requests. Expiry is checked using `validTill`.

---
# 14. UPI

UPI is available only inside the simulated bank.

## 14.1 Default/universal phone UPI

Each customer has one default phone-based UPI identity based on the current phone number:

```text
phoneNumber@bank-domain
```

Rules:

- Only customers have this type of UPI identity.
- It is automatically created by the backend.
- It is mapped to one customer account for receiving phone-number payments.
- That mapped account is the customer's primary account only for the purpose of the default phone UPI.
- The primary account has no other special business meaning in the system.
- Customer does not manually edit the bank suffix.
- Customer does not manually create this default identifier.
- It is not counted toward the custom UPI limit.
- The textual phone-based UPI is generated by the backend as `phoneNumber@bank-domain`, is shown where needed for addressing, and is used internally when a sender chooses phone-number payment.
- A customer cannot independently edit or delete the default phone UPI. It is created automatically with the customer and replaced automatically with the phone-number change.
- A completed financial transaction records the general payment method/rail as `UPI`; it does not record the particular UPI identifier used to initiate the payment. Temporary requests and scheduled payment instructions may retain the stable UPI/account identities needed to prevent a stale address from redirecting a future payment.
- The default phone UPI identity remains reserved for the customer even when it has no active account mapped to it.
- If there is no active account, the default phone UPI is not functional and cannot receive money.
- When a customer changes the primary account for the default phone UPI, the mapping is changed to the new eligible active account.
- If the mapped account becomes suspended or otherwise unavailable, phone-number payments to that default UPI cannot be completed until the UPI is mapped to an eligible active account again.

When the customer replaces the phone number:

- The old default phone UPI database record is deleted, releasing the old address, and the new phone-derived UPI record is created automatically.
- The new phone number, old UPI removal, new UPI creation and deletion of the customer's own future scheduled payments that use the old default UPI as their source are committed in one PostgreSQL transaction after OTP verification and any required confirmation.
- External EventBridge schedule removal happens after the database commit. Every scheduled invocation rechecks the authoritative PostgreSQL row/state, so a schedule that has not yet been removed cannot execute a deleted payment.
- Future scheduled payments owned by other customers and addressed to the old phone/UPI are not silently redirected or automatically deleted from the senders' requests. At execution, the backend validates that the saved recipient identity, phone/UPI relationship and account mapping still match. If they do not match, the payment fails and the sender receives a notification through the configured communication channel.
- The phone change uses email-based OTP authentication, not SMS.

---
## 14.2 Custom UPI IDs

One account can have multiple custom UPI IDs.

Current maximum is 3 custom UPI IDs per account, and this maximum is Admin-configurable.

Rules:

- Customer chooses the prefix.
- Bank suffix/domain is added by the backend.
- Prefix character and length rules are fixed product rules unless explicitly changed later.
- UPI ID must be unique.
- One UPI ID points to exactly one account at a time.
- A custom UPI cannot be moved from one account to another.
- To use another account, the customer deletes the old UPI and creates a new one.
- One custom UPI may be marked primary for an account.
- A custom UPI cannot use an entirely numeric 8–12 digit prefix before the bank domain because that pattern is reserved for phone-number payment addressing.
- Default phone UPI is not counted in the custom UPI limit.
- When the default phone UPI is mapped to an account, that account can therefore have up to 3 custom UPI IDs plus the default phone UPI.

## 14.3 UPI PIN

UPI PIN setup, change and reset require strong authentication.

Wrong attempts are limited by policy.

Repeated failures temporarily block the relevant payment/PIN operation.

Attempt and block events are recorded as required for security/audit purposes.

UPI PIN is never stored in plain text.

---

# 15. Scheduled UPI Payments

Customers can schedule future UPI payments within the allowed scheduling range.

Only UPI transfers support future scheduling in the current scope.

The Admin may configure the allowed future scheduling range and related amount/count limits.

Flow:

```text
Recipient → Amount → Date/Time → Review → Authenticate → Scheduled
```

A customer can cancel a scheduled payment while cancellation is allowed.

Editing a schedule is done by cancelling the existing schedule and creating a new one.

### Scheduled payment record

The temporary scheduled-payment record stores the exact identities needed for the future payment, including:

- Source account
- Source UPI identity
- Receiver UPI identity
- Receiver account identity
- Amount
- Scheduled date/time
- Execution deadline
- Current processing state

The system does not re-resolve the receiver from the current phone number at execution time.

This prevents a reused phone number or reused UPI string from receiving money that was originally intended for another customer.

### Source UPI deletion

If a customer deletes a UPI ID that is used as the source of future scheduled payments:

- The UI gives a clear warning before deletion.
- The warning tells the customer that those scheduled payments will also be deleted.
- After confirmation, the UPI is deleted and the affected scheduled payments are deleted.

If the customer replaces the phone number that generated the default UPI, the old default UPI is removed and the same source-schedule rule applies.

### Receiver UPI deletion

If the receiver deletes a UPI ID that has future payments scheduled to it:

- Those scheduled payments become invalid for execution.
- When they are processed, they fail rather than being redirected.
- The sender receives a clear notification explaining that the recipient's UPI ID/phone-based address may have been deleted or changed.

If another customer later uses the same phone number or creates the same textual UPI ID, old scheduled payments must not be redirected to the new customer.

At execution time, the backend verifies the stored UPI identity and its current account relationship before moving money.

### Execution timing

Scheduled customer payments use a fixed 10-minute late-execution grace period.

The 10-minute value is a product rule and is not Admin-configurable.

The backend can process the payment when:

```text
status = SCHEDULED
AND current time >= scheduled time
AND current time <= execution deadline
```

A job must not execute indefinitely just because its scheduled time is in the past.

After the permitted execution window passes, the payment is treated as missed/failed according to the final status rules.

### Processing safety

Scheduled execution uses:

```text
SCHEDULED → PROCESSING → SUCCESS / FAILED
```

Only one concurrent invocation may acquire the `SCHEDULED → PROCESSING` transition.

The financial movement and final scheduled-record handling must be committed together in the same database transaction.

The scheduled-payment record is a temporary operational record. Once the payment reaches a terminal result, the record is deleted.

The resulting financial transaction remains as permanent financial history.

The transaction history does not need to expose that a transaction originally came from a scheduled payment.

If the same scheduled event is delivered again after successful completion, there must be no second financial effect.

---

# 16. Debit Cards

Customers explicitly request debit cards.

Initial debit-card issuance does not require Manager approval. Backend eligibility rules decide whether the card can be issued.

Rules:

- One debit card per eligible bank account in the current scope.
- Debit card belongs to exactly one account.
- The card cannot be moved to another account.
- Card activation is required.
- PIN is explicitly created and protected.
- Block/unblock is customer controlled.
- Lost/stolen cards become permanently unusable.
- Replacement card is supported.
- Expired cards become unusable while their history remains.
- Account closure deactivates the related debit card.
- Branch transfer does not break the card-account relationship.

Customer controls include:

- Online/e-commerce ON/OFF
- International ON/OFF
- Domestic per-transaction limit
- Domestic monthly limit
- International per-transaction limit
- International monthly limit

Online and international access are OFF by default and the customer must explicitly enable them.

Limit defaults and allowed ranges are configurable by Admin according to card policy.

Higher customer limits may require Manager approval where the configured rule requires it.

Debit-card purchases are simulated inside the banking system and have a dedicated history linked to the specific debit card used. A purchase records the merchant category and whether it is international, validates card/account state, PIN, online/international controls, transaction limits and required OTP policy, and creates its financial/accounting effects atomically.

Domestic and international monthly usage is counted across the relevant debit-card purchase history for the same bank account, including replaced cards, so replacing a card cannot reset the monthly usage total. Per-transaction controls remain attached to the active card.

An international card-purchase fee is calculated using the active Admin-configured percentage rate, with an initial rate of 0%. The rate and fee applied to a purchase are retained with the permanent transaction. The fee is bank-level Fee Income, not Branch Funds or Central Ledger funding.

Wrong card PIN attempts can cause temporary blocking.

Card expiry period is Admin-configurable for newly issued cards.

There are no separate card tiers such as Classic, Gold or Platinum in the current scope.

---

# 17. Credit Cards

Customer explicitly applies for a credit card.

Credit-card application requires Manager review.

The credit-card application is an expirable customer request and stores a `validTill` value using the active Admin-configured request expiry policy.

If the application passes its `validTill` time before the required action is completed, it can no longer continue through the normal request flow and is removed by the request cleanup process.

Lifecycle includes:

```text
APPLICATION → REVIEW → APPROVED / REJECTED → ISSUED → ACTIVATED
```

Rules:

- Rejection includes reason when provided.
- Customer must activate the card.
- PIN is set through authenticated flow.
- Customer can block/unblock the card using the defined strong-authentication rules.
- Credit card is owned by the Customer, not by a bank account.
- A credit card is not permanently affiliated with any one payment account.
- An account can be used as a source for credit-card bill payments.
- Multiple credit cards may exist for one customer subject to configured product rules.
- Each credit card has its own rewards/financial state.
- Each credit card has separate online/e-commerce and international controls, both OFF by default. The customer must explicitly enable a control before that purchase type is allowed.
- Simulated credit-card purchases are recorded as permanent financial transactions linked to the card and current billing cycle. The purchase validates card status, PIN, required online/international controls, credit availability and any required OTP policy.
- Credit-card purchases increase the current open billing-cycle spending and apply any available `credit_balance` before increasing `outstanding_amount`. The `credit_balance` is only created when reward redemption exceeds the outstanding amount; credit-card bill overpayments do not create it in V1.
- An international card-purchase fee is calculated using the active Admin-configured percentage rate, with an initial rate of 0%. The applied rate/fee is retained with the purchase and credited to bank-level Fee Income.

Credit-card limit increase requests are also expirable customer requests and store a `validTill` value using the active Admin-configured request expiry policy. If the request passes its `validTill` time before it is resolved, it can no longer continue and is removed by the request cleanup process.

## 17.1 Credit-card payments

Customer can pay the credit-card bill from any eligible active account. Manual payments are allocated to the oldest unpaid billed cycle first and can continue to the next cycle if the payment exceeds the remaining amount of the oldest cycle. The allocation of one payment across cycles is retained in the database.

Manual payments and automatic payments lock the credit card and affected billing cycle records, re-read the remaining amount and create only one financial effect. A manual payment cannot exceed the total remaining billed amount; credit-card bill overpayments do not create `credit_balance` in V1. Competing payment requests cannot debit the customer's account twice for the same card obligation.

The payment is reflected in:

- Credit-card payment history
- Paying account financial history
- Relevant branch financial movement

Partial payment is allowed subject to the minimum-due rules.

Missing the minimum due can create applicable fees/interest, overdue state, notifications and Trust Score impact according to policy.

Credit-card closure is allowed only after outstanding obligations are cleared.

## 17.2 Automatic payment account

A credit card may have an automatic payment account.

The relationship is to the payment source account, not to ownership of the credit card.

The automatic payment account can be changed using the defined strong-authentication flow.

If the configured account becomes invalid, the automatic-payment relationship must be resolved according to the product rules before an incompatible account operation completes.

On the billing due date, automatic payment attempts to pay the full remaining amount of the relevant billed statement from the configured account. The automatic payment is all-or-nothing; insufficient funds or another payment failure creates no partial debit. If no eligible account is linked or the payment fails, the backend records the failed attempt without creating financial movement. At the end of the due date in `Asia/Kolkata`, the cycle becomes overdue if `paid_amount` is still below `minimum_due`. Late fees, interest and Trust Score effects are posted only once according to the active policy. If a scheduled invocation is delayed, the backend reconciles the due date from PostgreSQL and does not repeat an already completed attempt or fee assessment.

## 17.3 Billing cycles

Billing Cycle is a permanent historical financial entity.

Billing cycles are monthly. Admin configures the statement day and the due-date offset. These settings apply when a new cycle is established; they do not rewrite an existing cycle. If a month does not contain the configured statement day, the statement date is the last calendar day of that month. The statement date (`cycle_end_date`) and payment due date are separate milestones.

At the statement date, the open cycle transitions to `BILLED`, the closing statement amount, minimum due and due date are frozen, and a new `OPEN` cycle is created for subsequent purchases. Manual payment or automatic payment reduces the billed cycle's remaining unpaid amount. The overdue cutoff uses `Asia/Kolkata`.

A billing cycle stores the financial state that applied to that cycle, including information such as:

- Cycle start/end dates and statement date
- Spending
- Opening outstanding
- Closing outstanding
- Minimum due
- Paid amount
- Payment due date
- Interest
- Fees
- Reward points
- Automatic-payment attempt time
- Overdue assessment time/information

A cycle whose minimum due remains unpaid after the due-date cutoff transitions to `OVERDUE`; a cycle whose minimum due is satisfied but whose full statement remains unpaid retains its unpaid/partial-payment state and follows the applicable interest policy. Each due-date attempt and overdue fee/interest assessment is idempotent.

Historical billing cycles are not rewritten because Admin later changes credit-card policy.

## 17.4 Rewards

Rewards have their own earning/redemption history.

Reward rules can include category-based points and Admin-configured rates.

Redeeming points reduces the credit-card amount due when applicable. If a reward redemption exceeds the current amount due, the excess is retained as `credit_balance`, represented by Customer Credit-Card Balance Liability and applied to future card purchases. In V1, excess reward redemption is the only operation that creates `credit_balance`; bill overpayments are rejected.

Reward earning records the reward event and accrues its monetary value by debiting Rewards Expense and crediting Rewards Liability. On redemption, Rewards Liability is debited; the amount applied to existing card debt credits Credit-Card Receivables, and any excess credits Customer Credit-Card Balance Liability. These entries balance, and the reward event, card balance change and ledger entries commit atomically.

Reward corrections are represented through appropriate compensating/reversal records.

Reward expiry is Admin-configurable for future rewards.

---

# 18. Loans

Loan types are configured by Admin and are returned by the backend.

A loan product can contain:

- Name
- Description
- Active/inactive state
- Minimum amount
- Maximum amount
- Interest rate
- Minimum tenure
- Maximum tenure
- Processing fee
- Early-closure/prepayment charge rule
- Age eligibility
- Trust Score requirement

Customer flow:

```text
Select Loan Type
      ↓
Amount + Tenure
      ↓
EMI Preview
      ↓
Application
      ↓
Manager Review
      ↓
Approve / Reject
```

Multiple pending applications for the same customer and same loan product are blocked where the existing application is still pending.

Each loan application identifies a `branchId` whose Manager reviews it. The customer must hold at least one `ACTIVE` account in that branch when submitting a loan or credit-card application. If a loan application supplies a disbursement account, that account must be active, belong to the customer and belong to the selected application branch. The application and the eventual loan retain the branch relationship required for review and servicing.

## 18.1 Manager-approved amount

Manager may approve an amount different from the requested amount within bank policy.

Flow:

```text
Manager approves different amount
        ↓
Customer notified
        ↓
Customer accepts / declines
        ↓
If accepted → Automatic loan processing and disbursement
```

Because the Manager has already made a final approved offer, customer acceptance directly processes and disburses the loan without returning the application to the Manager review queue.

Customer cannot cancel after Manager approval under the current rules.

If the customer declines the modified offer, the application ends in the customer-declined outcome.

Loan applications and Manager-approved offers store a `validTill` expiry timestamp. If the application or offer expires before the required action is taken, it can no longer be processed and is removed by the scheduled cleanup worker.

## 18.2 Loan terms

Once a loan becomes a loan account, it stores its own contractual financial terms and its current servicing `branchId`. The loan also stores its current EMI payment account separately from its servicing branch. Changing the EMI payment account does not by itself move the loan's servicing branch.

When a branch is decommissioned or servicing ownership is migrated, the loan's current servicing branch is updated according to the branch migration rules. Existing financial transactions, branch movements, ledger entries and audit history retain the branch context that applied when each event occurred.

Existing loans are not changed by later Admin changes to the general product policy.

Examples of stored loan terms include:

- Principal
- Interest rate
- Tenure
- EMI amount
- Applicable charges
- Outstanding amounts

## 18.3 Loan disbursement

Loan disbursement goes to an eligible active customer account.

The destination account is checked again immediately before disbursement.

Customer can change the destination account before disbursement using the required authentication.

If there is not enough branch lending/operational funding for the disbursement, the Manager may request additional branch funding rather than automatically creating an invalid financial position.

After branch funding is received, the Manager must explicitly review the loan again before final approval/disbursement.

Loan disbursement changes both customer financial state and branch financial state through the required accounting entries.

---

# 19. EMI

When a loan becomes active/disbursed, its EMI schedule is generated and stored.

Each EMI record contains the relevant schedule values for that installment, such as:

- Installment number
- Due date
- EMI amount
- Principal portion
- Interest portion
- Remaining principal after the installment
- Payment status
- Paid date
- Penalty where applicable
- Retry information where applicable

The project uses reducing-balance interest for loans.

A separate InterestMethod domain object is not required.

Each loan has exactly one automatic EMI deduction account.

The account cannot simply be removed from the relationship. It can be replaced with OTP/strong authentication and the replacement is performed atomically.

### Automatic EMI deduction

Typical flow:

```text
Due date
   ↓
Attempt deduction
   ↓
Success → Paid
   ↓
Failure → Notify → Retry according to policy
```

The system must not automatically switch to an unrelated account just because the automatic deduction account has insufficient funds.

If the customer manually pays before a retry, the retry must not deduct again.

### EMI payment

A normal EMI payment must cover the full EMI amount.

Partial EMI payment is not supported.

Multiple future EMIs may be paid in advance according to the allocation rules.

### Early loan closure

Partial prepayment is not supported.

A customer has two practical repayment paths:

```text
Pay EMIs according to the schedule

OR

Pay the entire remaining amount and close the loan early
```

For early full closure:

- Remaining scheduled interest that would not have been earned is not charged.
- Applicable early-closure/prepayment charge is charged.
- The system does not create multiple historical EMI schedule versions for partial prepayment because partial prepayment is not available.

Closed loans remain permanently in financial history.

---

# 20. Trust Score

Each customer has exactly one Trust Score.

The score is customer-level, not account-level, loan-level or card-level.

Range:

`1–100`

Initial value is Admin-configurable.

Trust Score is changed by backend business rules.

Customer cannot directly edit it.

Manager cannot directly edit it.

Admin cannot directly type a customer's score.

Trust Score can be affected by multiple factors, for example:

Positive factors:

- Timely EMI payments
- Full loan repayment
- Timely credit-card payment
- Other defined positive behaviour

Negative factors:

- Missed EMI
- Late EMI
- Missed credit-card minimum due
- Other negative financial behaviour
- Repeated applications where configured

The final scoring formula is a later implementation decision.

Trust Score history is retained so that score changes can be understood later.

Manager can view a customer's Trust Score for loan/credit-card review where permitted.

Admin does not have direct access to individual customer Trust Scores.

Trust Score does not automatically replace Manager decision-making.

---

# 21. Branches

Each branch always has exactly one Manager assigned while the branch is active.

A Manager may manage multiple branches.

Admin cannot create an active branch without a Manager assignment.

The `Branch` record keeps a nullable `current_manager_id` for fast lookup of the branch's current Manager. While a branch is active, `current_manager_id` must reference exactly one active Manager; when the branch becomes inactive/closed, `current_manager_id` may be `NULL`.

`BranchManagerAssignment` stores the historical Manager-to-branch assignment history. The current manager reference and the active assignment must be updated atomically so they cannot become inconsistent.

Manager assignment/replacement must be atomic so the branch never becomes managerless during the operation.

Branch code is immutable.

A closed/deactivated branch is not reused for new operations, but its historical information remains.

## 21.1 Multi-branch Manager

If a Manager has more than one branch:

- Manager selects the branch after login.
- Only the selected branch's work/data is visible.
- Switching the selected branch does not require another login.
- There is no combined cross-branch Manager work queue.

If a Manager has only one branch, it is selected automatically.

## 21.2 Branch work ownership

Operational work belongs to the branch, not to the Manager who happened to review it.

If a Manager is replaced:

- Existing pending branch work stays with the branch.
- New Manager sees the existing pending work.
- Previous Manager loses access immediately.

---

# 22. Branch Transfer of Customer Accounts

A branch transfer is requested and approved per account.

Only one active branch-transfer request is allowed for an account at a time. If an active request already exists, the customer must cancel/delete the existing request before creating another branch-transfer request. A customer may not modify an active branch-transfer request in place; the existing request must be deleted first.

The branch-transfer request is an expirable customer request and stores a `validTill` value using the active Admin-configured branch-transfer request validity period.

If the request passes its `validTill` time before the required approvals are completed, it can no longer continue through the normal request flow and is removed by the request cleanup process.

If a customer has multiple accounts, each account requires its own branch-transfer request.

The appropriate current branch Manager is determined from the account's current branch.

Flow:

```text
Customer request
      ↓
Current branch Manager
      ↓
Destination branch Manager
      ↓
Both approve
      ↓
Scheduled execution
      ↓
Migration
```

Both Manager approvals are required.

If either rejects:

- The transfer does not complete.
- Customer is notified.
- Rejection reason is shown when available.

Customer can cancel while cancellation is still allowed.

Once both approvals are complete and the transfer is scheduled, it cannot be edited or cancelled through the normal customer flow.

The account number does not change during branch transfer.

Cards and loans attached to the account/customer remain attached.

Customer/account-related data moves to the destination branch as defined by the migration rules.

The migration runs during the defined low-traffic execution period.

Affected customer financial writes are temporarily frozen during actual migration so that data does not change halfway through the move.

Reads can remain available where safe.

Pending work that belongs with the affected customer/branch follows the migration rules and is not silently lost. Nonterminal loan applications, credit-card applications and customer profile-change requests scoped to the branch move to the destination branch when their scope must follow the customer's active account migration. Their status, existing decisions, expiry and audit history are preserved. Approved-but-unaccepted loan offers keep their existing decision and expiry; branch migration does not create a new approval or reset the offer lifetime.

---

# 23. Branch Financial Model

Branch financial state is divided into two different concepts.

## 23.1 Branch Funds

Branch Funds are money available to the branch for operational and lending purposes.

Rules:

- Branch Funds cannot become negative.
- Admin/central authority allocates funds to branches.
- Loan disbursement reduces Branch Funds.
- Loan repayment increases Branch Funds.
- Other defined branch operational movements can also change Branch Funds.
- Branch Funds are not the same as customer deposits.

## 23.2 Branch Customer Deposit Position

This is the total money currently held in customer accounts belonging to that branch.

It is separate from Branch Funds.

A branch may therefore have:

```text
Branch Funds
and
Branch Customer Deposit Position
```

without treating them as the same balance.

## 23.3 Branch-level money movement history

Branch-level history records money movement between branch financial positions.

It does not care whether the customer used:

- UPI
- Account + IFSC
- Debit card
- Credit card
- Another payment rail

The branch ledger is about where money moved, not the customer-facing payment method.

### Same-branch transfer

If sender and receiver accounts are in the same branch:

- Customer transaction history is updated.
- No branch-level movement/history is created because the money remains inside the same branch.
- Branch Customer Deposit Position does not change.

### Cross-branch transfer

If sender and receiver accounts are in different branches:

- A branch-level movement is created.
- Source branch customer deposit position decreases.
- Destination branch customer deposit position increases.
- The same business transaction may create two branch movement sides: source branch negative, destination branch positive.

This does not mean the customer has two separate customer transactions. Customer history and branch accounting history remain separate.

### 23.4 Accounting ledger

The accounting model uses a lightweight chart of accounts and ledger accounts.

A financial transaction can create multiple immutable ledger entries. Each ledger entry has an explicit debit or credit and an amount.

Financial positions are represented through ledger accounts such as applicable Central Ledger, Branch Funds, Customer Deposits, Loan Receivables, Credit-Card Receivables, Customer Credit-Card Balance Liability, Merchant Settlement Clearing, Fee Income, Rewards Liability, Rewards Expense and income/expense positions. The exact account list can grow with the project without changing the transaction model.

Customer account balances and branch financial positions are operational balances backed by the accounting ledger. Balances are not recalculated from the entire historical ledger for every normal read.

Customer account effects are stored separately for each affected account and contain the previous and updated balance for that account. Each account effect also records a customer-facing direction of `DEBIT` or `CREDIT`, where `DEBIT` means money left that account and `CREDIT` means money entered that account. One effect does not expose another customer's balance.

The `FinancialTransaction` itself does not have one overall customer-facing debit/credit direction because a single transaction can contain both debit and credit account effects. Accounting `DEBIT`/`CREDIT` on `LedgerEntry` remains a separate accounting concept.

Branch financial effects are also recorded separately for each affected branch position.

A business financial transaction may therefore have:

```text
FinancialTransaction
      ├── Ledger Entries
      ├── Account Effects
      └── Branch Effects where applicable
```

Every accounting movement must remain balanced through double-entry accounting.

---

# 24. Central Ledger

The Central Ledger is the only place where simulated money may be introduced from nowhere.

Only Admin can inject new central funds.

Every injection:

- Requires a reason.
- Is recorded permanently.
- Is audited.
- Changes the Central Ledger balance.
- Is balanced through the accounting ledger using the configured system capital/funding position.

Central Ledger cannot become negative.

Central Ledger funding to a branch creates the required corresponding central and branch financial entries.

Financial records related to Central Ledger activity are permanent and immutable after finalization.

---

# 25. Branch Creation Funding

When Admin creates a branch:

- A Manager must be assigned.
- Initial Branch Funds are defined.
- Funds come from Central Ledger.
- Central Ledger decreases.
- Branch Funds increase.
- The operation is recorded financially.
- Branch creation, Manager assignment and initial funding should not leave a half-created branch if one part fails.

The initial branch-funding setting applies to new branches only.

Changing the setting later does not change existing branch funding.

---

# 26. Branch Funding Requests

Manager may request additional Branch Funds from Admin.

Funding requests are not automatically expired or deleted.

Only one active funding request is allowed per branch at a time. A Manager may create another funding request for that branch only after the previous request has been approved or rejected. Because each active branch has exactly one Manager, this means a Manager can have at most one active funding request for each branch they currently manage. A Manager managing multiple branches may therefore have one active funding request per branch. A funding request cannot be modified in place; a new request can be created only after the previous request is resolved.

Request contains:

- Amount
- Optional reason

Only one active funding request is allowed per branch.

Admin can approve:

- Less than requested
- Exactly requested
- More than requested

The final approved amount must not make Central Ledger negative.

A funding request is not tied to one particular loan.

If a loan needs additional branch funds:

```text
Loan review
   ↓
Insufficient Branch Funds
   ↓
Manager requests funding
   ↓
Admin approves funding
   ↓
Manager explicitly reviews the loan again
```

The loan is not automatically approved simply because funding arrives.

Funding requests remain in the database until Admin approves or rejects them.

Notifications related to a pending funding request remain available to Admin and are not removed by normal notification-expiry cleanup while the funding request is still pending.

When Admin approves or rejects the funding request, the related operational notifications can be removed as part of resolving the request.

---
# 27. Branch Deletion / Decommissioning

Admin can close/decommission a branch.

The branch is not physically removed from historical financial data.

Before decommissioning completes:

- Customers/accounts/products must be moved to an existing active destination branch.
- Relevant pending work must be handled/moved according to migration rules.
- Branch Funds must be returned to Central Ledger first.
- Branch financial state must be brought to the required zero/settled position.
- Destination branch must be valid and active.
- A destination Manager must exist.

Affected financial writes are frozen during the migration where required.

Branch decommissioning must not leave customers, accounts, loans or financial records half-migrated.

After completion:

- Branch becomes inactive/closed.
- Branch code is not reused.
- Historical branch ledger remains.
- Historical Manager assignment remains.
- Former Manager loses access to the closed branch immediately.

If the former Manager has another active branch, the employee account remains active.

If the former Manager has no remaining branch assignment, the employee follows the defined employee-account removal process.

---

## 27.1 Financial and Historical Record Retention

Financial records are permanent.

Accounting ledger entries are permanent and immutable.

Financial transaction history is not removed by notification cleanup or ordinary operational cleanup jobs.

Closing an account or branch must not remove its financial history.

Historical records must remain understandable even if the related customer, employee or operational object is later inactive.

Customer-facing access to some non-financial historical information may end according to product rules, but the underlying financial record remains.

This requirement does not claim legal compliance with any particular banking or records-retention law.

---

# 28. Manager Portal

Manager workspace is branch-isolated.

For the selected branch, Manager can access as applicable:

- Customer search
- Customer details
- Customer profile-change workflows
- Account closure requests
- Account state
- Loan applications
- Credit-card applications
- Card-limit requests
- Branch-transfer requests
- Branch financial position/history
- Funding requests
- Branch customer activity
- Branch work queue
- Manager-relevant notifications

Manager does not have unrestricted bank-wide access.

Manager cannot use administrative authority to perform customer banking actions for themselves.

Manager work belongs to the branch.

Manager cannot directly change a customer's Trust Score.

Manager can view Trust Score where the product allows it for loan/credit-card review.

### 28.1 Manager deactivation

A Manager who has no active branch assignment follows the defined employee deactivation process.

The deactivation grace period is Admin-defined, with a minimum of 15 days and a maximum of 90 days.

A one-time scheduled task can be created for the deactivation deadline. If the Manager is assigned to an active branch again before the deadline, the old scheduled task must become harmless.

If the deadline is reached while the Manager still has no active branch assignment, the employee/user account becomes inactive. Historical employee and branch-assignment records remain.

If a Manager still has another active branch assignment, the employee account remains active.

---

# 29. Admin Portal

Admin handles bank-wide operations.

Admin responsibilities include:

- Branch management
- Manager management
- Central Ledger
- Branch funding
- Bank-wide financial dashboard
- Configuration/policy management
- Administrative audit
- Other bank-wide operational tasks

Admin does not directly perform ordinary customer-service operations on an individual customer's account/profile.

Admin cannot directly:

- Edit individual customer profile fields
- Act as a customer for financial transfers
- Manually edit a customer's Trust Score
- Approve individual customer requests that belong to Manager workflow unless a separate Admin override is explicitly defined

---

# 30. Admin Financial Dashboard

Admin can see bank-wide financial information.

For each branch, Admin can see at least:

- Branch Funds
- Branch Customer Deposit Position
- Loan disbursement activity
- Loan repayment activity
- Credit-card payment activity
- Funding received from Central Ledger
- Other relevant branch financial activity

Admin can also see a combined bank-wide view.

The Central Ledger remains separately visible from branch financial positions.

Individual customer Trust Scores are not shown in the Admin financial dashboard.

---

# 31. Admin Configuration Center

Admin has a centralized configuration area for bank-wide policies.

Settings are grouped into practical categories such as:

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

Every configurable setting should have:

- Description
- Current value
- Suitable input/control type
- Allowed range where applicable
- Validation rules
- Clear explanation of what the setting affects
- Audit/history of changes

Only useful settings should be exposed. The Admin Control Center should not contain settings only for the sake of having more controls.

### Fixed values

Some rules are fixed by the product and are not ordinary Admin settings, including:

- Bank name
- Bank theme
- Customer password format
- Customer suspension maximum of 90 days
- Beneficiary cooling period of 30 minutes
- Scheduled-payment late grace period of 10 minutes
- Heavy-job execution grace period of 24 hours
- Phone-number format rules
- Profile-picture maximum size of 2 MB (2,097,152 bytes) and supported image formats
- No-SMS design

### Typical Admin-configurable values

Some Admin-configurable values will have initial V1 values supplied during the first deployment/setup. These initial values are used until Admin changes them later.

Admin may configure, where defined:

- Support email/phone
- OTP expiry/length/attempts/resend cooldown
- Transfer limits and fees
- Transfer OTP threshold
- Daily transfer limits
- Initial account funding
- Interest rates and posting policies
- Loan products and loan limits
- Loan fees and tenure ranges
- Credit-card product settings
- Credit-card limits, billing and fee policies, including monthly statement day and due-date offset
- International debit/credit card-purchase fee rate, initially 0%, applied to future transactions only
- Reward rates and expiry
- Debit-card expiry and limit defaults
- Custom UPI maximum per account
- UPI PIN attempt/lock policy
- Beneficiary maximum count
- Money-request expiry/limits
- Customer request expiry periods for supported expirable request types such as customer profile-change requests, loan applications/offers, credit-card applications, card-limit requests, account closure requests and branch-transfer requests
- Account Closure cooling period for future closure approvals
- Scheduled-payment future scheduling range and limits
- Branch transfer request expiry/policies where defined
- Statement generation schedule
- Statement retention
- Notification retention
- Cleanup schedule/frequency
- Trust Score initial value and scoring factors/weights
- Bank capital lending ratio
- Branch lending limits
- Branch funding-related limits

Admin cannot change the fixed 10-minute customer scheduled-payment grace period or 24-hour heavy-job grace period.

---

# 32. Configuration Governance — Existing Valid Data Is Protected

The general rule is:

> New Admin policy applies to future operations unless a product rule explicitly says otherwise.

Examples:

- Lowering beneficiary maximum does not delete existing beneficiaries.
- Lowering future scheduling range does not automatically delete an existing valid scheduled payment.
- Lowering a card-limit maximum does not silently rewrite an already valid card limit unless the product rule explicitly requires it.
- Changing Trust Score weights does not recalculate old score history.
- Changing reward rates affects future earning, not already-earned rewards.
- Changing loan product terms does not rewrite existing loans.
- Changing credit-card product terms does not rewrite existing contractual financial records.

Notification retention is stored per notification using its own valid-until date. A later Admin change affects newly created notifications and does not extend old notification expiry dates.

The same approach is used for expirable customer request periods. When a request is created, its `validTill` value is calculated using the active Admin setting at that time. A later Admin change does not change the existing request's stored `validTill` value.

The initial values for Admin-configurable policies are supplied during initial deployment/setup. These are starting values and can be changed later by Admin according to the configuration rules.

Transaction and financial history do not use a short retention policy.

---

# 33. Financial Integrity Requirements

## 33.1 Double-entry ledger

Every financial movement must balance through the accounting ledger.

A financial operation cannot be treated as complete by only changing an account balance.

Customer balances and branch financial positions are backed by the required accounting records.

## 33.2 Backend authority

Backend is authoritative for:

- Balance
- Transaction state
- Fees
- Interest
- EMI calculations
- Loan eligibility
- Card eligibility
- Trust Score
- Limits
- UPI ownership
- Financial ledger changes
- Security decisions

Frontend validation is only a convenience layer.

## 33.3 Permanent and immutable financial history

Financial records remain permanently available to the system.

Accounting ledger entries are immutable.

Finalized financial transaction history is immutable.

Corrections are new compensating/reversal events rather than edits to the old financial record.

Financial records are not deleted by cleanup jobs.

## 33.4 Canonical calculations

Financial calculations are centralized.

The same business rule should not be reimplemented differently in different controllers/services.

Examples include:

- EMI calculations
- Interest
- Fees
- Penalties
- Rewards
- Trust Score changes
- Transfer limits

## 33.5 Rate limiting

Backend rate limiting is required for sensitive operations.

Likely protected areas include:

- Login
- OTP generation/resend
- Password recovery
- Money transfers
- UPI payment authentication
- Other high-risk endpoints

Frontend throttling is not a security control.

Redis may be used for rate-limiting counters where appropriate, but Redis is not the source of financial truth.

Redis failure should be treated as a cache miss or temporary loss of the optional accelerator behaviour, not as a reason to crash the whole application. Financial correctness must continue to depend on PostgreSQL.

If Cloudflare R2 is unavailable while loading an optional object such as a profile picture, the affected UI element may show an error or fallback avatar, but unrelated APIs and other parts of the application must continue working.

## 33.6 Database indexing

Database indexes should be added carefully.

Indexes are mainly used where the system has read-heavy query patterns that need them.

Do not add indexes everywhere only because a column can be searched. Extra indexes increase insert/update cost and storage, so write-heavy financial tables should not be over-indexed.

## 33.7 Hybrid data-access boundary

The backend uses a hybrid persistence strategy over PostgreSQL:

- **Spring Data JPA** is used for transactional writes, account balance updates, double-entry ledger creation, workflow state transitions and entity concurrency locking.
- **Spring JDBC** is used for dynamic multi-filter search queries, cross-table pending-request/work-queue read models, dashboard aggregations, audit-log searches and streaming statement generation reads.

Read-only search and reporting queries map directly to DTO projections rather than loading large collections of managed JPA entities into memory.

---

# 34. Idempotency and Retry-Safe APIs

Critical state-changing requests must be safe against duplicate submission.

Applicable operations include:

- Transfers
- Loan applications
- Credit-card applications
- Account closure requests
- Branch-transfer requests
- Funding requests
- Money requests
- Other critical state-changing operations

Where appropriate, the API uses an `Idempotency-Key` header.

The database remains the final integrity guard.

A cache may be used for an early duplicate check, but correctness cannot depend only on Redis.

Retrying the same operation with the same idempotency key must not create a second financial effect or second business operation.

Disabling a frontend button is not enough to guarantee this.

Scheduled account suspension/reactivation and account closure processing must also be safe if the scheduler invokes the target more than once.


---

# 35. Concurrency, Race Conditions and Consistency

Two users or processes may try to change the same record at the same time.

The backend must make the final state deterministic and valid.

Examples:

```text
Customer cancel loan
        VS
Manager approve loan

Customer cancel money request
        VS
Receiver pay request

Customer cancel scheduled payment
        VS
Scheduled execution

Two transfers against the same balance

Manager replacement
        VS
Branch work operation
```

Only one valid state transition should be accepted when two actions conflict.

For financial operations, balance updates and ledger entries must be protected from double spending or inconsistent writes.

The exact locking/versioning mechanism is a backend implementation decision.

---

# 36. Scheduled Job Reliability

Business-critical scheduled work must not depend on an application instance staying alive.

The application runs on AWS Lambda, so in-process schedulers are not the source of truth for scheduled jobs.

Amazon EventBridge Scheduler is used to trigger scheduled work.

Applicable jobs include:

- Scheduled UPI payment execution
- Branch-transfer execution
- EMI automatic deduction/retry
- Credit-card monthly statement closing and next-cycle creation
- Credit-card due-date automatic payment
- Credit-card overdue assessment
- Statement generation
- Notification dispatch/retry processing
- Notification cleanup
- Expirable request cleanup
- Account suspension automatic reactivation
- Account closure finalization
- Other future scheduled operations

Every job must be safe if invoked more than once.

The event only tells the application to run a job. The backend reloads authoritative state from PostgreSQL before processing it.

Scheduled financial jobs use database-side state guards and idempotent processing.

For one-time account lifecycle schedules such as suspension reactivation and account closure, the schedule is created when the relevant lifecycle state is entered and targets the stored end date/time.

The scheduled operation uses the specific account lifecycle record or request identity so an old scheduler invocation cannot change a newer suspension or closure state for the same account.

One-time schedules should use automatic deletion after completion where appropriate. Retry policies are used when a target invocation fails. The system should not intentionally run the successful business operation multiple times just to confirm that it worked because that would create unnecessary Lambda invocations and cost. Duplicate delivery is handled by idempotent database state checks.

If the related lifecycle state is already completed, the scheduled invocation becomes a no-op and does not create another business effect.

When a customer cancels or resolves a scheduled account lifecycle operation before its scheduled time, the application should remove the now-unneeded schedule where possible. The database state remains the final protection if deletion of the AWS schedule and the database change cannot happen atomically.

## 36.1 Scheduled execution windows

Each scheduled-job type can have a deliberate execution policy.

Current fixed product values:

- Customer scheduled payments: 10-minute late grace period
- Heavy jobs such as statement generation: 24-hour execution window

These two values are not Admin-configurable.

Account suspension and account closure use their stored end date/time for the business transition. The scheduler is used so that the transition does not depend on user login or another unrelated API request.

## 36.2 Scheduled cleanup jobs

Notification cleanup and expirable-request cleanup are not time-critical.

A cleanup Lambda runs at an Admin-defined time and interval and checks the relevant tables for records whose stored `validUntil` or expiry value has passed.

The cleanup job may process multiple temporary record types in one invocation, including notifications, expirable customer requests (money requests, profile-change requests, account closure requests, branch-transfer requests, loan applications/offers, credit-card applications and card-limit requests) and expired OTP records, so that separate cleanup schedulers are not needed.

For this low-volume learning project, the cleanup interval can later be increased by Admin after active testing, including to a longer interval such as 15 or 30 days where appropriate.

Cleanup may happen later than the configured validity period. This is acceptable for V1 as long as the backend checks `validTill` before allowing an expired request to be processed.

Funding requests are excluded from automatic request cleanup because they remain pending until Admin resolves them.

Running the cleanup job again must be safe.
# 37. Audit Logging

Critical operations create audit records.

Examples:

- Manager decisions
- Customer profile approval/change
- Loan approval/rejection
- Credit-card approval/rejection
- Branch-transfer decisions
- Branch creation/decommissioning
- Manager assignment/replacement
- Funding decisions
- Central Ledger injection
- Trust Score changes
- Critical configuration changes
- Account state changes
- Security-sensitive operations

Audit record may contain:

`Actor | Role | Branch context | Action | Entity type | Entity ID | Old state | New state | Timestamp | Reason`

The audited entity is identified using a generic `entity_type` + `entity_id` reference rather than separate nullable foreign-key columns for every possible audited entity.

For example:

```text
entity_type = LOAN_APPLICATION
entity_id   = 8421
```

The exact database column types and allowed entity-type values are implementation details, but the reference must identify the historical business object that the audit action concerned.

Audit history is a separate concern from normal operational data.

Audit records are not silently changed when an operational record changes later.

---

## 37.1 Audit Log Export

Admin users can export audit information across the bank-wide scope.

- Audit log export is restricted to the Admin role.
- Export itself is audited.
- Export does not modify the source records.

## 37.2 Application Logging

Application logging is separate from audit logging.

The backend uses:

- SLF4J as the logging API
- Spring Boot's default Logback implementation
- Lombok `@Slf4j` to reduce logger boilerplate
- AWS CloudWatch as the centralized logging service in deployment

Logs should use structured data where useful so that application errors and important events can be searched and filtered in CloudWatch.

Application logs are used for debugging, failure investigation and operational diagnosis. They are not treated as a replacement for permanent financial or audit records.

Sensitive data such as passwords, OTP values, PINs, full card numbers and other secrets must not be written to application logs.

---

# 38. Critical Operation Freeze

Some operations temporarily freeze financial writes to keep the system consistent.

Examples:

- Branch migration
- Branch decommissioning
- Account closure finalization
- Other operations where simultaneous writes could create inconsistent state

During a freeze:

- Safe reads may continue.
- Unsafe writes are rejected, delayed or safely handled according to the workflow.
- Customer sees a normal business message instead of a raw technical error.

Frontend-only freezing is not enough. Backend and database controls are required.

---

# 39. Statements

Account statements support:

- PDF
- CSV
- One account
- Multiple selected accounts combined
- Date range
- Incoming/outgoing filters where applicable

Combined statements are chronological and identify the source account.

Statement generation is asynchronous and runs during the Admin-configured low-load night schedule.

Statement lifecycle:

`REQUESTED → PROCESSING → READY / FAILED`

### Storage in Cloudflare R2 and Redis address caching

Cloudflare R2 stores binary objects in separate folders/prefixes by purpose:

- `profile-photos/` for customer profile pictures
- `statements/` for generated account statement files (PDF/CSV)

Keeping statements in a dedicated `statements/` folder allows an R2 lifecycle rule or background cleanup worker to automatically delete generated statement files 24 hours after generation without affecting customer profile pictures.

The system does not keep a statement fingerprint in V1.

### 24-hour cooldown and night-generation timing rules

To avoid any mismatch between when a customer requests a statement (for example, at 4:00 PM) and when the batch job generates the file at night (for example, at 12:00 AM):

1. **While `REQUESTED` or `PROCESSING` (4:00 PM to 12:00 AM):** The customer cannot submit another statement request because an active request is already pending generation. If the customer attempts a new request, the API informs them that their statement request is currently scheduled/processing.
2. **When generation completes (`READY` at 12:00 AM):**
   - The generated PDF/CSV file is uploaded to Cloudflare R2 under `statements/` (configured for automatic deletion after 24 hours).
   - The R2 file location/key and the customer's 24-hour cooldown timer are stored in Redis with a **24-hour TTL starting from generation completion (`readyAt`)** (and `ready_at` / `expires_at` are recorded on `statement_request` in PostgreSQL as the fallback source of truth if Redis is unavailable).
3. **During the 24-hour `READY` window:**
   - The customer cannot request a new statement and is shown the exact remaining cooldown time before a new statement can be requested.
   - The customer has two actions available for the existing statement: **Download** the statement or **Resend** it to their email. Both operations read the R2 file address from Redis (falling back to PostgreSQL if Redis is unavailable) and stream/fetch the file from Cloudflare R2.
4. **After the 24-hour `READY` window expires:** The Redis keys expire, the R2 file is automatically deleted, and the customer can request a new statement.

A statement request is marked successful after the generated statement has been stored in R2 and accepted by the email delivery provider for sending.

Statements require authenticated access and use the fixed bank identity/branding.

Statement data comes from permanent financial transaction history and includes the previous balance before a transaction and the updated balance after a transaction. The batch generator queries and streams these records using Spring JDBC projections rather than loading full JPA entity graphs into memory. These balance snapshots are not shown in the normal customer transaction history view.

The statement request is separate from the resulting financial transactions. Transaction records do not contain a field saying that the transaction came from a statement request, money request or scheduled payment.

# 40. Notifications

Notifications are an operational/customer-communication feature, not the permanent financial history.

Notification areas include:

1. Transaction-related notifications
2. Other/general notifications

A `Notification` represents the logical notification that should be available to the recipient. It stores notification-level information such as:

- Priority
- Read/unread state
- Title
- Message
- Notification type
- Deep-link target where useful
- Internal reference type/id where useful
- Created date
- Valid-until date

Delivery-channel state is not stored as fixed columns on `Notification`.

A separate `NotificationDelivery` record represents delivery through one external channel. It stores the relevant channel, delivery status, attempt/retry information and provider-related timestamps or identifiers where useful.

Example:

```text
Notification #100
    |
    +-- NotificationDelivery
    |      channel = EMAIL
    |      status  = SENT
    |
    +-- NotificationDelivery
           channel = TELEGRAM
           status  = FAILED
```

Adding a future channel such as WhatsApp or another supported provider therefore requires a new delivery-channel implementation/consumer, not new channel-specific columns on `Notification`.

In-app availability/read state remains part of the `Notification` itself because the in-app notification is the application record shown to the user rather than an external provider delivery.

Internal reference IDs/types are for backend use and are not shown to customers as technical identifiers.

## 40.1 Notification channels

Customer channels:

- In-app
- Email
- Telegram when linked

SMS is not supported.

A failed optional delivery channel does not invalidate the in-app notification record.

Critical notifications have a mandatory communication path and cannot be disabled.

Notification delivery uses asynchronous AWS messaging:

```text
Notification
     ↓
    SNS
   /   \
  ↓     ↓
SQS     SQS
Email   Telegram
  ↓       ↓
Lambda  Lambda
  ↓       ↓
Provider Provider
```

Each external delivery channel has its own queue/consumer path and dead-letter queue so that failure or slowdown in one channel does not block another channel.

`NotificationDelivery` stores the state for the corresponding channel. The database model does not need channel-specific columns when another delivery channel is introduced.

Notification processing must be idempotent because asynchronous delivery may be retried or delivered more than once.

The notification record is created as part of the relevant database operation where applicable, but notification publication/delivery must not determine whether the underlying financial transaction succeeds or fails. A notification failure must not roll back a successful financial transaction.

A separate transactional outbox is not used in V1. The notification and its delivery records provide the persistent state needed for retry and recovery. A retry process can find delivery records that still need channel delivery and attempt publication again.

Notification delivery is considered successful when the relevant delivery provider accepts the message for sending. Final inbox/device delivery is not required for the delivery record to be marked successful.

## 40.2 Notification retention

Notifications are temporary records.

Default notification retention is **10 days**.

When a notification is created, the backend calculates and stores its `validUntil` date using the active Admin retention setting.

Notifications related to a pending Manager funding request are an exception. They remain available until the funding request is approved or rejected and are not removed by normal notification-expiry cleanup while that request is pending.

Example:

```text
Retention = 10 days
Notification created = October 1
validUntil = October 11
```

If Admin later changes retention to 30 days, the October 1 notification remains valid until its already-stored date.

A notification created after the change uses the new retention period.

This keeps existing notifications on their old expiry without requiring policy-version lookup during cleanup.

## 40.3 Notification cleanup

Notification deletion does not need to happen exactly at the expiry moment.

A scheduled cleanup job can run at an Admin-defined interval suitable for this low-volume learning project.

The cleanup query removes notifications whose `validUntil` date has passed, except notifications tied to funding requests that are still pending.

The cleanup job may remove a notification later than the configured retention period. This is acceptable because notification deletion is not time-critical.

Notifications may remain after being read until they are eligible for cleanup.

Read/unread actions include:

- Mark one as read
- Mark all as read
- Unread count

# 41. Frontend Requirements

Frontend uses:

- React
- React Router
- Redux (state management)
- Axios (HTTP client and interceptors)
- Tailwind CSS

Frontend should have:

- Shared navigation and role-based route protection via React Router
- Centralized application and auth-token state in Redux
- Centralized API communication and automatic token-refresh handling via Axios interceptors
- Consistent layout, spacing and responsive design using Tailwind CSS
- Reusable React components
- Role-based experiences for Customer, Manager and Admin
- Clear loading, error and empty states
- A customer Pending Requests section that aggregates active customer-owned workflow requests, including account closure/branch transfer, loan applications and offers at any nonterminal stage, credit-card applications/limit requests, profile changes, money requests, scheduled payments and other supported request types. Each item shows its current status, expiry where applicable, and whether cancellation is permitted.
- A cancellation control is shown only where the backend allows cancellation. The frontend first displays a confirmation dialog describing the cancellation; the cancellation API request is sent only after the customer confirms.
- Profile-photo size validation before upload, with the same 2 MB maximum enforced independently by the backend
- Telegram link/unlink instructions and retryable pending/success/error states
- Basic accessibility
- Backend-driven data

The frontend should not contain business rules that belong to the backend.

Frontend may perform validation to improve user experience, but backend validation is always final.

---

# 42. Frontend ↔ Backend Contract

Frontend and backend are separate deployables. The browser uses a same-origin Vercel rewrite for `/api/:path*` to the AWS API Gateway HTTP API, so the frontend does not need cross-origin credential handling for normal API requests. The backend must still validate authentication and authorization on every request.

For each API, the contract should define the required details, including when applicable:

- HTTP method
- Path
- Authentication
- Headers
- Request data
- Response data
- Status codes
- Error format
- Pagination
- Filters
- Business status
- Idempotency behaviour
- Validation rules

The frontend must not assume that a request succeeded until the backend confirms the result.

Backend responses should contain enough information for the frontend to display a clear user-facing result without exposing internal implementation details.

---

# 43. Data and State Boundaries

The main domain concepts are:

```text
CUSTOMER
├── identity/profile
├── one Trust Score
├── one simulated mobile number for UPI
├── one Default Phone UPI mapping
└── preferences

ACCOUNT
├── Customer
├── Branch
├── balance/state
├── UPI IDs
└── transactions

DEBIT CARD
└── exactly one Account

CREDIT CARD
├── Customer
├── billing cycles
├── rewards
└── payment-source relationships

LOAN
├── Customer
├── contractual terms
├── disbursement account
└── EMI schedule

UPI
└── one Account at a time

BENEFICIARY
└── customer recipient relationship

MONEY REQUEST
├── requester UPI/account snapshot
├── requested payer customer/user identity
├── validTill
└── temporary request state

BRANCH
├── exactly one Manager while active
├── Branch Funds
├── Customer Deposit Position
└── branch work

CENTRAL LEDGER
└── central simulated funds

CUSTOMER PROFILE CHANGE REQUEST
├── requested sensitive profile changes
├── branch reference
├── validTill
└── temporary request state

FINANCIAL TRANSACTION
├── permanent financial history
├── transaction parties
├── ledger entries
├── account effects
└── branch effects where applicable

SCHEDULED PAYMENT
└── temporary future-payment instruction

NOTIFICATION
├── temporary customer communication record
└── NotificationDelivery records for external delivery channels

STATEMENT
└── temporary statement request with 24-hour R2 file storage (`statements/` folder) and Redis location cache

OTP CHALLENGE
└── durable challenge record with Redis as the active fast path

IDEMPOTENCY RECORD
└── persistent protection against duplicate critical requests

AUDIT LOG
└── separate historical operational/security record
     └── generic audited-entity reference (entity type + entity ID)
```

Financial/accounting records are authoritative within their financial domain.

Redis is not a source of financial truth.

Redis is used for caching, rate limiting and other short-lived accelerator behaviour. If Redis is unavailable, the application treats the relevant lookup as a cache miss where possible.

Cloudflare R2 stores profile pictures under `profile-photos/` and temporary generated statement files under `statements/`. Statement files are automatically deleted 24 hours after generation and are not permanent financial records.

If R2 is unavailable while a profile picture or statement file is requested, the related operation may fail or show an appropriate fallback while unrelated APIs and other application functions continue working.

DynamoDB is not used in the initial architecture.

---

## 43.1 Data retention boundaries

Financial records:

- Permanent
- Immutable after finalization
- Never removed by notification/operational cleanup

Notifications:

- Temporary
- Have a stored `validUntil` except for notifications tied to pending funding requests
- Removed by periodic cleanup after expiry
- Funding-request notifications remain until the related funding request is resolved

Expirable customer/user requests:

- Temporary operational records
- Have a stored `validTill`
- Their validity is decided from `validTill`, not from when cleanup happens
- Removed by periodic cleanup after expiry
- Different request types can use different Admin-configured validity periods

Funding requests:

- Temporary operational records until Admin approves or rejects them
- Do not have `validTill`
- Are not removed by automatic request cleanup

Scheduled payments:

- Temporary operational instructions
- Deleted after terminal processing/cancellation/failure according to the scheduling rules
- Do not create a separate permanent "scheduled-payment history"

OTP challenges:

- Durable short-lived challenge records in PostgreSQL
- Redis stores the current active OTP for the fast verification path
- Successful verification removes the corresponding PostgreSQL challenge
- Expired or abandoned challenges are removed by periodic cleanup

Idempotency records:

- Temporary records used to prevent duplicate processing of the same critical request
- Kept for the period required by the related operation/retry policy
- Removed by cleanup when they are no longer needed

Statements:

- Statement requests are temporary operational records
- Generated statement files are stored in Cloudflare R2 under the `statements/` folder and automatically deleted after 24 hours
- The R2 file address and 24-hour customer cooldown are cached in Redis with a 24-hour TTL starting when the statement becomes `READY`
- Underlying financial records remain permanent

Audit records:

- Retained according to the audit-retention rules
- Financial/audit integrity must not depend on customer-facing notification retention

---

## 43.2 Dormancy

A traditional Dormant account state is not part of the current project.

It can be added later as a separate account lifecycle if the project needs it.

---

# 44. Important Invariants

1. One Customer has exactly one Trust Score.
2. One Customer has at most one non-closed Account of the same account type; an account stops counting toward this rule only after it reaches the final `CLOSED` state.
3. Supported account types come from the backend and are not hard-coded in the frontend.
4. Each active Branch has exactly one Manager.
5. One Manager may manage multiple branches.
6. Branch work belongs to the Branch, not to the previous Manager.
7. Customer branch transfer is requested per Account.
8. The account number does not change during branch transfer.
9. Manager access is limited to the selected assigned branch.
10. Admin does not directly manage individual customer operations.
11. Central Ledger is the only place where simulated money may be injected from nowhere.
12. Central Ledger cannot become negative.
13. Branch Funds cannot become negative.
14. Branch Funds and Branch Customer Deposit Position are separate concepts.
15. Same-branch customer transfers do not create branch-level money movement.
16. Cross-branch transfers create branch-level movement between the two branches.
17. Branch financial history does not depend on the customer-facing payment method.
18. Every accounting movement is double-entry.
19. Financial records are permanent.
20. Accounting ledger entries are immutable.
21. Finalized financial records are immutable and are not directly edited.
22. Financial corrections are represented by new compensating/reversal entries.
23. Admin policy changes do not normally retroactively rewrite valid existing financial/contractual state.
24. Loan and credit-card contractual terms remain stable after they become active/issued.
25. One account can have multiple UPI IDs.
26. One UPI ID maps to exactly one Account at a time.
27. A Beneficiary represents one recipient Account for the customer.
28. Customer phone number is unique and is used only for UPI payment addressing.
29. Managers and Admins have no phone-number field.
30. Phone-number payments reuse the existing default UPI mechanism.
31. The account mapped to the default phone UPI is the customer's primary account only for that UPI purpose.
32. Primary-account status does not prevent account suspension or closure.
33. A default phone UPI remains reserved to the customer even when it has no active account mapped to it.
34. A default phone UPI with no active destination cannot receive money.
35. A reused phone number or UPI string must never redirect an old scheduled payment.
36. A money request stores the requester's UPI/account relationship from the time the request was created and stores the requested payer's customer/user identity.
37. A money request does not bind the requested payer to one account; the payer may choose any eligible active account when accepting the request.
38. A money request is rejected if the requester's current UPI-to-account mapping no longer matches the stored request data.
39. Expirable customer/user requests store `validTill` and do not need a separate `EXPIRED` state.
40. Expirable request validity is checked when the request is processed.
41. Request cleanup can happen later than `validTill` without making an expired request usable.
42. Funding requests are not automatically deleted and remain until Admin approves or rejects them.
43. Only one active branch-transfer request is allowed for an account at a time; a new request requires the previous request to be resolved or deleted.
44. Only one active account-closure request is allowed for an account at a time; a new request requires the previous request to be resolved or deleted.
45. Only one active funding request is allowed per branch; another request can be created only after the previous request is approved or rejected.
46. Scheduled payment execution is idempotent.
47. Duplicate scheduled execution cannot create duplicate financial movement.
48. Scheduled payment rows are temporary and are deleted after terminal processing.
49. Notification rows are temporary and are deleted after expiry cleanup unless they are tied to pending funding requests.
50. Notification retention does not affect financial-record retention.
51. Redis failure must not create incorrect financial state and is treated as a cache miss where possible.
52. R2 failure for optional objects such as profile pictures must not bring down unrelated APIs or application functions.
53. A customer cannot request a new statement while a statement request is pending or within 24 hours after a statement becomes ready.
54. The 24-hour statement cooldown and R2 file address are stored in Redis using a 24-hour TTL.
55. Generated statement files are stored in Cloudflare R2 under a dedicated statements folder for 24 hours and are not permanent financial records.
56. Previous and updated balance snapshots are retained for statement generation but are not shown in normal transaction history.
57. Transactions do not record whether they were created from a money request, scheduled payment or statement request.
58. Scheduled account suspension and closure operations are idempotent and safe to retry.
59. Concurrent conflicting requests must produce one valid final state.
60. Critical scheduled jobs must be safe to rerun.
61. Database indexes are added only where they solve real read/query needs and are not added everywhere by default.
62. A final FinancialTransaction is created only after its financial result is known.
63. Failed financial transactions are retained and do not create accounting movement for money that was not moved. 
64. Customer account balance effects are stored separately for each affected account using previous and updated balances. 
65. Branch financial effects are stored separately from customer transaction history. 
66. Statement fingerprinting is not used in V1. 
67. Notification delivery failure must not roll back a successful financial transaction. 
68. Notification stores logical notification state; channel-specific delivery state is stored in separate NotificationDelivery records. 
69. Notification processing is asynchronous through SNS and independent SQS delivery paths for each external channel. 
70. OTP maximum attempts come from Admin configuration and are not stored redundantly in each OTP challenge. 
71. Only the current pending OTP for a user and purpose is stored in the Redis active-OTP key. 
72. A duplicate OTP request within the resend cooldown reuses the active challenge; an allowed resend after the cooldown replaces the pending challenge and invalidates the previous code. A verified OTP challenge remains valid only for its short consumption window. 
73. PostgreSQL remains authoritative for OTP challenge status and consumption. Redis may validate the code quickly, but verification is not reported successful unless the durable status transition is committed. 
74. OTP cleanup is scheduled at an Admin-defined interval. 
75. Manager deactivation after loss of all active branch assignments uses a bounded Admin-defined grace period. 
76. Application logging and audit logging are separate concerns. 
77. Passwords, OTP values, PINs, full card numbers and other sensitive secrets must not be written to application logs. 
78. Audit records identify their audited business object using a generic entity type and entity ID reference rather than one nullable foreign-key column for every possible audited entity.
79. Only one active customer profile-change request is allowed per customer at a time, and expired requests are removed by the scheduled request cleanup job.
80. Customer email is unique case-insensitively, and customer mobile number is required, non-null, unique and format-validated.
81. Registration does not reserve login IDs, email addresses or phone numbers in customer identity tables before email OTP verification succeeds.
82. OTP challenges move through `PENDING`, `VERIFIED` and `CONSUMED`; a verified challenge is purpose-bound, expires after a short consumption window and can be consumed only once.
83. The default phone UPI identifier is automatically generated from the customer's current phone number and bank domain and cannot be independently deleted by the customer.
84. Phone-number replacement, retirement of the old default phone UPI, creation of the new default phone UPI and deletion of affected sender-owned source scheduled payments occur in one PostgreSQL transaction.
85. A future payment addressed to a changed/deleted receiver phone UPI is never redirected. The recipient identity and current account mapping are revalidated at execution, and an invalid scheduled payment fails with a sender notification.
86. A finalized financial transaction records the generic payment method `UPI` rather than the particular UPI identifier used to initiate it.
87. Frontend and backend both enforce the 2 MB (2,097,152-byte) profile-photo limit and the supported image formats; backend file-content validation is final.
88. Every card purchase is a permanent, balanced financial transaction linked to the card used. Debit-card monthly usage is counted across replaced cards associated with the same account.
89. International card-purchase fees are calculated using the active Admin-configured percentage, initially 0%, and are credited to bank-level Fee Income rather than Branch Funds or Central Ledger funding.
90. Credit-card available credit balance is consumed before new card spending increases outstanding amount. V1 does not create a credit balance from bill overpayments.
91. Credit-card statement date and payment due date are separate. Manual/automatic payments are serialized, and automatic payment/overdue fee assessment is idempotent.
92. Each loan application and credit-card application has a branch review scope, and each loan contract has a current servicing branch and separate EMI payment account.
93. Customer Pending Requests is an aggregated view over existing request resources, not an independent source of truth. Cancellation requests use the relevant type-specific API and require frontend confirmation where cancellation is available.
94. Telegram update ingestion is globally serialized. Matching results and the durable update checkpoint are committed atomically in PostgreSQL; Redis is only a cache for tokens, matches and checkpoint data.
95. Telegram checkpoint recovery loads the durable PostgreSQL checkpoint and never jumps to the latest queued update with `offset=-1`.
96. Telegram update processing is idempotent and bounded per API invocation; a pending response allows the UI to retry without losing already-committed matches.
97.  Telegram chat IDs and link tokens are not written to application logs, and one Telegram chat can be linked to at most one customer.
98.  The refresh cookie is host-only, Secure and HttpOnly, omits the Domain attribute, uses `Path=/api/v1/auth`, and is not persisted with `Max-Age` or `Expires`.


---
# 45. Explicitly Deferred Decisions

These decisions belong to later implementation/design work and should not be forced into this requirements document:

- Exact entity classes and JPA mappings
- Exact persistence mapping of aggregate boundaries to tables/classes
- Exact state-machine classes/enums beyond agreed business states
- Exact Spring Data JPA repository queries and Spring JDBC DAO SQL statements
- Exact locking implementation
- Exact Redis key design
- Detailed OpenAPI schemas
- Exact DTO class structure
- Exact email provider implementation
- Exact Telegram library/provider implementation
- Exact CI/CD workflow
- Exact Lambda handler implementation details
- Exact EventBridge schedule names/payload format
- Exact R2 upload implementation
- Final Trust Score formula
- Final penalty/escalation numbers where not already fixed
- Final reward redemption mechanics

The requirement is that there is one current global configuration with change history; the implementation should avoid many unnecessary tiny configuration tables.

---

# 46. Development Order

The project will continue in this order:

```text
Requirements Baseline
        ↓
Domain Model & Business Rules
        ↓
State Machines
        ↓
System Architecture
        ↓
Database/Data Model
        ↓
API Contract Design
        ↓
Frontend Information Architecture
        ↓
Frontend Architecture
        ↓
Backend Architecture
        ↓
Implementation
        ↓
Integration
        ↓
Testing
        ↓
Security / Reliability Hardening
        ↓
CI/CD and Documentation
        ↓
Deployment
```

Each stage should use the decisions made in the previous stage rather than re-inventing business rules.