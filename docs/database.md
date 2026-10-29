# Database Design

This document defines the initial PostgreSQL database design for the simulated banking system.

The database is designed around three main goals:

- Keep PostgreSQL as the source of truth for customer, account and financial data.
- Keep permanent financial history separate from temporary workflow data.
- Use database constraints and transactions to protect important business rules instead of relying only on application code.

The design is intentionally practical. It provides the structure needed for a reliable banking simulation without introducing tables or patterns that do not solve a real project requirement.

---

# 1. Database

**Database:** PostgreSQL

**Primary database provider:** Neon PostgreSQL

Important rules:

- Monetary values use `NUMERIC(19,2)`.
- Java maps monetary values to `BigDecimal`.
- PostgreSQL is the financial source of truth.
- Redis is not used as a financial source of truth.
- Financial records are not physically deleted as part of normal cleanup.
- Foreign keys are used where a permanent relationship should be enforced by the database.
- Application-level validation is still required for rules that depend on multiple states or workflows.

The final physical schema may use PostgreSQL-specific features where they make a business rule clearer or safer, while remaining straightforward to work with through Spring Data JPA and Spring JDBC.

## 1.1 Data Access Split (Spring Data JPA vs Spring JDBC)

Both data-access technologies share the same PostgreSQL connection pool and transaction manager, divided by operation type:

- **Spring Data JPA (`@Entity` + JPA Repositories):** Used for all transactional writes, state-machine transitions, row-level locking (`SELECT ... FOR UPDATE`), optimistic concurrency (`@Version`) and single-resource reads.
- **Spring JDBC (`NamedParameterJdbcTemplate` / `JdbcClient` DAOs):** Used for read-only queries that require dynamic SQL predicates, cross-table `UNION ALL` pagination, bulk row streaming or aggregate reporting. Specifically:
  - Unified, account-scoped and card-scoped transaction history filtering (`financial_transaction` joined with `transaction_account_effect` and `transaction_party`).
  - Asynchronous statement generation queries streaming historical transactions and balance snapshots into PDF/CSV files.
  - Aggregated Pending Requests (`customers/me/pending-requests`) and Manager Work Queue (`work-queue`) read models across the workflow request tables.
  - Admin and Manager financial dashboard summaries, branch customer-activity views and multi-filter `audit_log` search/export queries.

Spring JDBC queries are read-only and map directly to DTOs, bypassing the Hibernate persistence context so large result sets do not retain managed entities in memory.

---

# 2. Main Table Groups

The database is divided conceptually into these groups:

```text
Identity
├── user_account
├── customer
├── employee
└── customer_preferences

Organization
├── branch
├── branch_manager_assignment
└── branch_financial_position

Customer Banking
├── account
├── upi
└── beneficiary

Requests / Workflows
├── money_request
├── scheduled_payment
├── account_closure_request
├── branch_transfer_request
├── funding_request
├── customer_profile_change_request
├── loan_application
├── credit_card_application
└── card_limit_request

Financial / Accounting
├── financial_transaction
├── transaction_party
├── transaction_account_effect
├── branch_financial_movement
├── ledger_account
└── ledger_entry

Cards
├── debit_card
├── credit_card
├── credit_card_billing_cycle
├── credit_card_cycle_payment_allocation
└── credit_card_reward

Loans
├── loan_product
├── loan
└── loan_emi

Trust
├── trust_score
└── trust_score_history

Operations
├── notification
├── notification_delivery
├── statement_request
└── statement_request_account

Security / Audit
├── otp_challenge
├── telegram_link_challenge
├── telegram_update_checkpoint
├── idempotency_record
└── audit_log

Administration
├── bank_configuration
└── configuration_history
```

---

# 3. Identity Tables

## 3.1 `user_account`

Purpose: common authentication identity shared by customers and employees.

Key fields:

- `id` — internal primary key
- `login_id` — unique login identifier
- `password_hash`
- `role` — `CUSTOMER`, `MANAGER`, `ADMIN`
- `status`
- `created_at`
- `updated_at`
- `last_login_at`

Constraints:

- `login_id` is unique.
- Password is never stored in plaintext.
- A user has one active role at a time.

The table does not contain customer banking data or employee-specific fields.

---

## 3.2 `customer`

Purpose: customer-specific identity and profile information.

Key fields:

- `id` — primary key
- `user_account_id` — unique FK to `user_account`
- other customer profile fields
- profile-photo object reference/metadata (when a photo is uploaded)
- `email` — primary communication email, normalized consistently and unique case-insensitively
- `phone_number` — required, non-null and unique simulated Indian mobile number
- `telegram_chat_id` — nullable unique Telegram private-chat ID when linked
- customer lifecycle/status fields
- timestamps

Constraints:

- One customer maps to exactly one `user_account`.
- Email is unique case-insensitively using a unique index on `LOWER(email)`.
- Phone number is `NOT NULL`, unique and validated in the form of exactly 10 digits beginning with 6, 7, 8 or 9.
- A non-null Telegram chat ID is globally unique among customer records so one Telegram account cannot be linked to multiple customers.

The phone number is used only for the customer's default UPI addressing requirement. It is never used for authentication or notification delivery.

---

## 3.3 `employee`

Purpose: employee-specific identity.

Key fields:

- `id`
- `user_account_id` — unique FK
- `employee_number` — unique
- employment/status fields
- timestamps

Managers and Admins do not have a phone-number field.

---

## 3.4 `customer_preferences`

Purpose: customer-specific application preferences.

Relationship:

```text
customer 1 ─── 0..1 customer_preferences
```

This remains a small 1:1 table so preference changes do not clutter the customer profile table.

---

# 4. Organization Tables

## 4.1 `branch`

Purpose: bank branch and its current operational state.

Key fields:

- `id`
- `branch_code` — unique and immutable
- branch name/address fields
- `current_manager_id` — nullable FK to `employee`
- `status`
- timestamps

Important rule:

- Active branch → exactly one current manager.
- Inactive/closed branch → `current_manager_id` may be `NULL`.

`current_manager_id` is an intentional read optimization. Historical assignments are stored separately.

---

## 4.2 `branch_manager_assignment`

Purpose: Manager-to-branch assignment history.

Key fields:

- `id`
- `branch_id`
- `manager_id`
- `assigned_at`
- `ended_at`
- assignment status

The active assignment and `branch.current_manager_id` are updated in the same transaction.

The historical assignment is retained when a Manager is replaced or a branch is decommissioned.

---

## 4.3 `branch_financial_position`

Purpose: current operational financial position of a branch.

A branch has separate positions for concepts such as:

- Branch Funds
- Branch Customer Deposit Position

This is current state. Historical changes belong to `branch_financial_movement` and the accounting ledger.

A branch has one current financial-position record.

---

# 5. Customer Banking Tables

## 5.1 `account`

Purpose: actual customer bank account.

Key fields:

- `id`
- `account_number` — unique and immutable
- `customer_id`
- `branch_id`
- `account_type`
- `balance` — `NUMERIC(19,2)`
- lifecycle status
- suspension/closure timestamps where applicable
- `created_at`
- `updated_at`

Constraints:

- Account number is globally unique.
- `(customer_id, account_type)` is unique across non-closed accounts (`WHERE status <> 'CLOSED'`), allowing a customer to open a new account of the same type after a previous account reaches the final `CLOSED` state.
- Account belongs to exactly one customer and one current branch.
- Balance cannot be negative where the product rules prohibit it.

An account is never physically deleted after creation. Closure changes its lifecycle state to `CLOSED`.

---

## 5.2 `upi`

Purpose: customer UPI address and its current account mapping.

Key fields:

- `id`
- `upi_address` — unique
- `account_id` — nullable when a default UPI is reserved before the customer has an account or has no eligible active destination
- `customer_id`
- `type` — `DEFAULT_PHONE` or `CUSTOM`
- lifecycle/status fields, including `RESERVED` and `ACTIVE` where applicable
- timestamps

Rules:

- One UPI address maps to at most one account at a time.
- The default phone UPI is created during successful customer registration from `phone_number@bank-domain`; it cannot be independently deleted by the customer.
- The first eligible account becomes its receiving account. Until then, the default phone UPI remains reserved with `account_id = NULL` and cannot receive payments.
- Phone-number replacement removes the old default phone UPI and creates the new one in the same PostgreSQL transaction as the phone-number change. The phone number itself is always non-null.
- Default phone UPI is not counted against the custom UPI limit.
- Custom UPI maximum is enforced per account.
- Deleted custom UPI records are physically removed in V1, so an old identifier may later be reused.

The customer reference is useful for the default UPI because its identity belongs to the customer even when its current account destination is temporarily absent.

---

## 5.3 `beneficiary`

Purpose: a customer's saved recipient account.

Key fields:

- `id`
- `customer_id` — owner of the beneficiary
- `recipient_account_id` — referenced recipient account
- beneficiary/cooling status
- `created_at`
- `updated_at`

The beneficiary represents an **account**, not a separate person or payment identity.

The UI can show the account holder's name and, in the details view, the account number and IFSC used to identify the recipient account.

Deleting a beneficiary does not affect the recipient account or financial history.

---

# 6. Temporary Request Tables

## 6.1 `money_request`

Purpose: temporary request for money from one customer to another.

Key fields:

- `id`
- `requester_customer_id`
- `payer_customer_id`
- `requester_upi_id` — nullable FK to `upi(id)` with `ON DELETE SET NULL` (if the requester deletes the custom UPI before acceptance, the FK becomes `NULL` and acceptance is rejected because the current mapping no longer matches)
- `requester_upi_snapshot`
- `requester_account_number_snapshot`
- requested amount
- optional message/reason
- `valid_till`
- status
- timestamps

Important design decision:

The payer is **not bound to one payer account**. The request stores the payer customer/user identity, so the payer can choose any eligible active account when accepting the request.

The requester account number is retained because that account is the intended receiving account.

When accepted, payment and request deletion occur in the same database transaction.

---

## 6.2 `scheduled_payment`

Purpose: temporary future UPI payment instruction.

Key fields:

- `id`
- source customer/account references
- `source_upi_id` — nullable FK to `upi(id)` with `ON DELETE SET NULL`
- `receiver_upi_id` — nullable FK to `upi(id)` with `ON DELETE SET NULL`
- source UPI identity/address snapshot
- receiver UPI identity/address snapshot
- receiver phone-number snapshot when the recipient was resolved through phone-number addressing
- receiver account identity/number snapshot where required for execution validation
- amount
- scheduled time
- execution deadline
- status
- timestamps

Lifecycle:

```text
SCHEDULED → PROCESSING → SUCCESS / FAILED
```

Terminal scheduled-payment records are deleted. The resulting financial transaction, when successful, remains permanently.

---

## 6.3 `account_closure_request`

Purpose: Manager-reviewed account closure request.

Key fields:

- `id`
- `account_id`
- requester/customer reference
- review Manager reference
- reason
- `valid_till` where applicable
- status
- timestamps

Constraint:

- Only one active closure request is allowed for an account.
- The request cannot be modified in place; it must be cancelled/deleted before another active request can be created.

The request is temporary. The Account itself is permanent historical data.

---

## 6.4 `branch_transfer_request`

Purpose: workflow for moving one account between branches.

Key fields:

- `id`
- `account_id`
- `source_branch_id`
- `destination_branch_id`
- current approval state
- source Manager decision
- destination Manager decision
- `valid_till`
- timestamps

Constraint:

- Only one active branch-transfer request is allowed per account.
- A new request requires the previous one to be resolved or deleted.

The account number does not change during a branch transfer.

---

## 6.5 `funding_request`

Purpose: Manager request for additional branch funds.

Key fields:

- `id`
- `branch_id`
- `requesting_manager_id`
- requested amount
- approved amount
- reason
- status
- Admin decision fields
- timestamps

Constraint:

- Only one active funding request is allowed per branch.
- Another request can be created only after the previous request is approved or rejected.
- A Manager managing multiple branches can therefore have one active funding request per branch.

Funding requests do not use normal `valid_till` expiry cleanup.

---

## 6.6 `customer_profile_change_request`

Purpose: Manager-reviewed request for sensitive customer profile changes (such as legal name or KYC address fields).

Key fields:

- `id`
- `customer_id`
- `branch_id` — target branch whose Manager reviews the request
- `review_manager_id` — nullable FK to `employee`
- `requested_changes` — structured JSON/fields representing the requested profile updates
- `reason`
- `status`
- `valid_till`
- timestamps

Constraints:

- Only one active profile-change request is allowed per customer at a time.
- Expired requests pass `valid_till` and are removed by the periodic request cleanup job.

---

# 7. Financial Transaction Tables

## 7.1 `financial_transaction`

Purpose: permanent finalized financial event.

Key fields:

- `id`
- `transaction_reference` — unique business/reference ID
- transaction type
- payment method/rail
- status
- amount — `NUMERIC(19,2)`, represented as `BigDecimal` in Java; all monetary values are in INR
- `fee_amount` — `NUMERIC(19,2) NOT NULL DEFAULT 0.00` for a separately recorded applied fee
- `applied_fee_rate NUMERIC(7,4) NOT NULL DEFAULT 0.0000` — the percentage rate snapshotted when an international card-purchase fee is applied (`2.5000` means `2.5%`)
- `credit_balance_applied_amount NUMERIC(19,2) NOT NULL DEFAULT 0.00` — the card credit balance consumed by a credit-card purchase
- nullable `debit_card_id`, `credit_card_id`, `loan_id` and `credit_card_billing_cycle_id` references where the event relates to those resources
- `merchant_category` where applicable
- `is_international` where applicable
- transaction timestamp
- remark/reference fields where required
- failure reason where applicable
- created timestamp

Card purchase rows identify the exact debit or credit card used. A UPI transaction records the general `UPI` payment method/rail but does not store the particular UPI identifier used to initiate it. A card-linked fee is retained with the same permanent financial event as its purchase.

The table does not contain one overall customer-facing `DEBIT/CREDIT` direction because one transaction may contain both.

A finalized financial transaction is immutable.

---

## 7.2 `transaction_party`

Purpose: participants in a financial transaction.

Key fields:

- `id`
- `financial_transaction_id`
- `party_type` — `ACCOUNT`, `BRANCH`, `CENTRAL_LEDGER`, `EXTERNAL_MERCHANT`, `DEBIT_CARD`, `CREDIT_CARD`, `LOAN`
- party reference
- display-name/reference snapshot

The snapshot keeps historical transaction details understandable even if an operational name later changes.

---

## 7.3 `transaction_account_effect`

Purpose: record the effect of one transaction on one customer account.

Key fields:

- `id`
- `financial_transaction_id`
- `account_id`
- `direction` — `DEBIT` or `CREDIT`
- `previous_balance`
- `updated_balance`
- affected amount

Customer-facing meaning:

- `DEBIT` = money left this account.
- `CREDIT` = money entered this account.

This is separate from accounting debit/credit used by the ledger.

One account effect never exposes another customer's balance.

---

## 7.4 `branch_financial_movement`

Purpose: historical branch-level effect created by a financial transaction.

Key fields:

- `id`
- `financial_transaction_id`
- `branch_id`
- affected position type
- movement direction/amount
- previous position
- updated position

A same-branch transfer normally creates no branch customer-deposit movement. A cross-branch transfer creates the required source and destination branch effects.

---

# 8. Double-Entry Ledger

## 8.1 `ledger_account`

Purpose: accounting accounts used by the bank's internal ledger.

Examples include:

- Central Ledger funding/capital accounts
- Branch Funds
- Customer Deposits
- Loan Receivables
- Credit-Card Receivables
- Customer Credit-Card Balance Liability
- Interest Income
- Fee Income
- Merchant Settlement Clearing
- Rewards Liability
- Rewards Expense

The exact chart of accounts can grow without changing the transaction model.

Key fields:

- `id`
- account code — unique
- account name
- account type
- status
- current balance where maintained operationally
- timestamps

---

## 8.2 `ledger_entry`

Purpose: immutable accounting entry attached to a financial transaction.

Key fields:

- `id`
- `financial_transaction_id`
- `ledger_account_id`
- `entry_type` — `DEBIT` or `CREDIT`
- positive amount
- entry timestamp

### Double-entry rule

Every completed financial movement must balance:

```text
SUM(DEBIT amounts) = SUM(CREDIT amounts)
```

A transaction is not financially complete merely because an Account balance changed.

For example, a simple customer transfer may produce:

```text
Ledger Account A     DEBIT   ₹5,000
Ledger Account B     CREDIT  ₹5,000
```

A more complex transaction may require several ledger entries, such as a fee or interest component, while still keeping total debits equal to total credits.

Operational balances such as `account.balance` are kept for efficient reads. They are not recalculated from the entire ledger for every normal API request.

The ledger provides the accounting record that supports those operational balances.

Ledger entries are immutable and are never edited to correct a financial mistake. Corrections are represented by new compensating financial events.

---

# 9. Card Tables

## 9.1 `debit_card`

Purpose: debit card tied to one eligible account.

Key fields:

- `id`
- `account_id` — FK to `account` with a partial unique index (`WHERE status NOT IN ('REPLACED', 'EXPIRED', 'CLOSED', 'PERMANENTLY_UNUSABLE')`) so historical replaced/expired cards are retained while allowing at most one active/usable debit card per account.
- card number encrypted at rest
- `last_four`
- expiry
- activation/status fields
- limits/settings
- timestamps

CVV is not stored. PIN is never stored in plaintext.

One eligible account can have at most one active debit-card relationship in V1.

---

## 9.2 `credit_card`

Purpose: customer-owned credit card.

Key fields:

- `id`
- `customer_id`
- card number encrypted at rest
- `last_four`
- expiry
- credit limit
- `outstanding_amount` — non-negative current amount owed
- `credit_balance NUMERIC(19,2) NOT NULL DEFAULT 0.00` — unused customer credit created only by excess reward redemption in V1
- `online_enabled` — separate online/e-commerce control, default `FALSE`
- `international_enabled` — separate international purchase control, default `FALSE`
- automatic payment account reference
- activation/status fields
- timestamps

Available `credit_balance` is applied first when a new purchase and its fee are posted. The remaining amount increases `outstanding_amount`. Credit-card bill overpayments do not create `credit_balance` in V1.

A credit card is owned by the customer, not permanently by its payment-source account.

---

## 9.3 `credit_card_application`

Purpose: Manager-reviewed credit-card application.

Key fields:

- `id`
- `customer_id`
- `branch_id` — branch whose Manager reviews the application
- requested/product information
- review Manager reference
- status
- `valid_till`
- decision/reason fields
- timestamps

The customer must have at least one active account in the selected branch when the application is submitted. An application may end without producing a card.

---

## 9.4 `card_limit_request`

Purpose: temporary request to change a debit-card or credit-card limit when the active card policy requires a review workflow.

Key fields:

- `id`
- target card reference
- requester
- requested limit
- decision
- `valid_till`
- timestamps

Exactly one card target is associated with a request. The implementation may use two nullable FKs with a database check constraint or another equivalent representation, but a request must never point to both a debit card and a credit card at the same time.

Only one active request for the same target card is allowed at a time.

---

## 9.5 `credit_card_billing_cycle`

Purpose: permanent billing-cycle history.

Key fields include:

- `id`
- `credit_card_id`
- cycle start date
- cycle end/statement date
- opening outstanding
- spending
- closing outstanding/statement amount
- minimum due
- paid amount
- interest/fees
- payment due date
- status
- automatic-payment attempt timestamp
- overdue assessment timestamp
- timestamps

The statement date and payment due date are separate. Once the cycle is billed, its statement amount, minimum due and due date are frozen. The active cycle may be `OPEN`; a billed cycle can be unpaid, partially paid, paid or overdue according to the current minimum-due rules. Automatic-payment and overdue assessment state is retained so retries cannot apply a duplicate payment or fee.

Only one `OPEN` billing cycle is allowed per credit card, enforced with a partial unique index on `credit_card_billing_cycle(credit_card_id) WHERE status = 'OPEN'`. The statement-closing transaction locks the card and current open cycle, bills that cycle and creates the next open cycle atomically. A repeated or concurrent invocation cannot create a second open cycle.

Billing cycles remain historical financial records.

---

## 9.6 `credit_card_cycle_payment_allocation`

Purpose: record how one permanent credit-card payment financial transaction is allocated across one or more billed cycles.

Key fields:

- `financial_transaction_id` — FK to the permanent payment transaction
- `credit_card_billing_cycle_id` — FK to the affected billing cycle
- allocated amount
- created timestamp

Constraint:

- `(financial_transaction_id, credit_card_billing_cycle_id)` is unique.
- Allocated amounts must be positive and their sum must equal the portion of the payment allocated to billing cycles.
- A manual payment is allocated to the oldest unpaid billed cycle first and can continue to later cycles only after the earlier cycle's remaining amount is covered.
- The backend rejects a payment greater than the total remaining billed amount; it does not create a card credit from bill overpayment in V1.
- Auto-pay targets the full remaining balance of the statement whose due date is being processed and either posts the full amount or creates no payment movement.

---

## 9.7 `credit_card_reward`

Purpose: reward earning/redemption history.

Key fields:

- `id`
- `credit_card_id`
- reward event type
- points/amount
- related transaction reference where applicable
- expiry/redemption data
- timestamp

---

# 10. Loan Tables

## 10.1 `loan_product`

Purpose: current Admin-configured loan product.

Key fields:

- `id`
- product code/name
- amount limits
- interest policy
- tenure limits
- fees
- eligibility configuration
- status
- timestamps

Deactivating a product does not change existing loans.

---

## 10.2 `loan_application`

Purpose: customer loan request and Manager review workflow.

Key fields:

- `id`
- `customer_id`
- `loan_product_id`
- `branch_id` — branch whose Manager reviews the application
- temporary disbursement destination account reference (nullable until selected)
- requested amount/tenure
- offered/approved amount where applicable
- Manager decision
- customer acceptance/decline state
- `valid_till`
- timestamps

One customer cannot have multiple active applications for the same product where the business rule prohibits it.

When a Manager approves a modified loan offer and the customer accepts that offer before `valid_till`, the application automatically triggers loan creation and disbursement without returning to the Manager review queue.

---

## 10.3 `loan`

Purpose: actual customer loan contract.

Key fields:

- `id`
- `customer_id`
- originating application reference
- loan product reference
- `branch_id` — current servicing branch
- final disbursement account
- `emi_payment_account_id` — current account used for automatic EMI deduction, nullable only where the workflow permits no linked account
- principal
- interest rate
- tenure
- EMI amount
- contractual fee/charge fields
- outstanding principal
- lifecycle status
- important contractual timestamps

The loan stores the terms that apply to that specific contract. Later changes to the LoanProduct do not rewrite existing loans.

---

## 10.4 `loan_emi`

Purpose: individual installment in a loan repayment schedule.

Key fields:

- `id`
- `loan_id`
- installment number
- due date
- EMI amount
- principal component
- interest component
- remaining principal
- status
- paid timestamp
- penalty/retry information where applicable

One loan has many EMI records.

---

# 11. Trust Score Tables

## 11.1 `trust_score`

Purpose: current Trust Score for a customer.

Relationship:

```text
customer 1 ─── 1 trust_score
```

Key fields:

- `id`
- `customer_id` — unique
- current score
- timestamps

---

## 11.2 `trust_score_history`

Purpose: historical score changes.

Key fields:

- `id`
- `trust_score_id`
- previous score
- new score
- reason/source
- timestamp

History is append-only.

---

# 12. Notification Tables

## 12.1 `notification`

Purpose: logical notification record for a recipient.

Key fields:

- `id`
- recipient `user_account_id`
- notification type
- title/message/template data
- business reference where applicable
- read/unread state
- `valid_until`
- created timestamp

This table does not contain fixed columns such as `email_status` or `telegram_status`.

---

## 12.2 `notification_delivery`

Purpose: delivery state for one notification through one external channel.

Key fields:

- `id`
- `notification_id`
- `channel` — `EMAIL`, `TELEGRAM`, and future channels
- delivery status
- attempt count
- last attempt timestamp
- provider identifier/status where useful
- error information where useful
- timestamps

Constraint:

```text
UNIQUE(notification_id, channel)
```

This keeps delivery channels independent. Adding WhatsApp, SMS, push, or another supported channel does not require adding new columns to `notification`.

A failed Telegram delivery must not roll back a successful financial transaction or block an independent Email delivery.

---

# 13. Statement Tables

## 13.1 `statement_request`

Purpose: asynchronous customer statement-generation request.

Key fields:

- `id`
- `customer_id`
- `format` — `PDF` or `CSV`
- `from_date` / `to_date`
- `filters`
- `r2_object_key` — nullable path inside the Cloudflare R2 `statements/` folder once generated
- `status` — `REQUESTED`, `PROCESSING`, `READY`, `FAILED`
- `failure_reason` where needed
- `requested_at`, `ready_at`, `expires_at` (`ready_at + 24 hours`)

The generated statement file is stored temporarily in Cloudflare R2 under the `statements/` folder for 24 hours, and its R2 address and 24-hour cooldown are cached in Redis starting when the statement becomes `READY`.

---

## 13.2 `statement_request_account`

Purpose: selected accounts for a statement request.

Key fields:

- `statement_request_id`
- `account_id`

Primary key:

```text
(statement_request_id, account_id)
```

A statement request can therefore include one or multiple accounts.

Only one new statement request is allowed for a customer within the configured 24-hour window.

---

# 14. Security and Audit Tables

## 14.1 `otp_challenge`

Purpose: durable OTP challenge record.

Key fields:

- `id`
- challenge ID/reference — unique
- `user_account_id` — nullable only for registration challenges created before an account exists and synthetic public recovery challenges for unknown accounts
- `purpose`
- `target_email` — destination used for OTP delivery where applicable
- `context_payload JSONB` — short-lived purpose-specific context such as the pending registration snapshot, including only the already-hashed password
- code hash
- status — `PENDING`, `VERIFIED` or `CONSUMED`
- `verified_at`
- `consumed_at`
- expires at
- attempt count
- created/updated timestamps

A check constraint enforces the permitted nullable-user purposes. Registration snapshots do not create identity rows before OTP verification and are deleted during successful consumption or expiry cleanup.

`max_attempts` is intentionally not stored here. The current Admin configuration is the source of truth.

Redis contains the active fast-path OTP; PostgreSQL remains the durable record for status, expiry, consumption and fallback verification.

---

## 14.2 `telegram_link_challenge`

Purpose: durable, short-lived challenge for linking a customer's Telegram private chat to the authenticated bank account.

Key fields:

- `id`
- `customer_id` — FK to `customer`
- `token_hash` — unique hash of the random Telegram deep-link token; the raw token is not stored in PostgreSQL
- `status` — `PENDING`, `MATCHED`, `CONSUMED` or `EXPIRED`
- `matched_telegram_chat_id` — nullable until a valid `/start` update is processed
- `expires_at` — 30 minutes after creation
- `created_at`, `updated_at` and `consumed_at`

Rules:

- Only one unexpired active link challenge is allowed per customer. A repeated link request reuses the active token when the Redis token cache is available. If Redis has lost the raw token, the backend expires the prior unmatched challenge and creates a new challenge rather than storing or reconstructing a raw token from its hash.
- A challenge matches only a valid bank-issued start parameter and is bound to the customer whose authenticated request created it.
- Only the first valid `/start` update for a `PENDING` unexpired challenge can set `matched_telegram_chat_id`; later updates for that challenge cannot replace the first matched chat.
- A matched chat ID is persisted before the global update checkpoint advances.
- The authenticated customer must explicitly verify the match; only then is `customer.telegram_chat_id` updated and the challenge consumed.
- Expired challenges cannot be consumed and are removed by cleanup after expiry.

---

## 14.3 `telegram_update_checkpoint`

Purpose: durable singleton checkpoint and lease for the shared Telegram `getUpdates` queue.

Key fields:

- singleton checkpoint key
- `next_offset` — nullable until the first non-empty batch has been processed
- `lease_owner` — nullable poll-invocation identifier
- `lease_expires_at` — nullable time after which a crashed poll invocation can be replaced
- `updated_at`

A singleton checkpoint row with a fixed singleton key is inserted during database migration/bootstrap; `next_offset` starts as `NULL`. Every poll attempt acquires the lease on this same row, including the first poll before an offset exists. Recognized link matches and the next offset are committed in the same transaction. Redis may cache the offset, but a missing/stale Redis value is recovered from PostgreSQL. The first poll omits `offset` rather than using a negative offset to jump to the newest update. Poll invocations are serialized through the database-backed lease.

---

## 14.4 `idempotency_record`

Purpose: database-level protection against duplicate critical requests.

Key fields:

- `id`
- `user_account_id`
- idempotency key
- operation type/scope
- request/result reference
- status
- timestamps

The exact uniqueness scope will be enforced according to the API operation, but the database remains the final integrity guard after any Redis fast-path check.

---

## 14.5 `audit_log`

Purpose: historical record of important operational, administrative and security actions.

Key fields:

- `id`
- actor `user_account_id` where applicable
- actor role
- branch context where applicable
- action
- `entity_type`
- `entity_id`
- old state/details where appropriate
- new state/details where appropriate
- reason
- timestamp

The audited object is identified using:

```text
entity_type + entity_id
```

rather than adding a nullable foreign-key column for every possible audited table.

Audit logs and application logs are separate concerns.

---

# 15. Administration Tables

## 15.1 `bank_configuration`

Purpose: current global Admin configuration.

There is conceptually one active global configuration record containing grouped settings for areas such as:

- OTP
- transfers
- UPI
- accounts
- loans
- cards
- requests
- notifications
- statements
- branches
- trust scoring
- cleanup schedules

Fixed rules remain fixed and are not exposed as Admin-configurable settings.

---

## 15.2 `configuration_history`

Purpose: historical record of configuration changes.

Key fields:

- `id`
- configuration version/reference
- changed by Admin
- changed values/details
- reason where required
- timestamp

Configuration changes generally affect future operations and do not silently rewrite valid existing data.

---

# 16. Foreign-Key Delete Policy

The stored UPI foreign keys on `money_request.requester_upi_id`, `scheduled_payment.source_upi_id` and `scheduled_payment.receiver_upi_id` use `ON DELETE SET NULL`. Physical deletion of a custom UPI is therefore not blocked by a pending workflow. As part of the confirmed source-UPI deletion flow, future scheduled payments using that UPI as the source are explicitly deleted. Scheduled payments that reference the deleted UPI as the receiver remain in the database and fail deterministic validation at execution time; money requests whose requester-UPI reference is cleared fail validation at acceptance. Reuse of the same textual UPI address must never redirect an existing workflow.

The default approach is **not** to use broad `ON DELETE CASCADE` across financial/domain tables.

Examples:

- Deleting a Customer must not cascade-delete financial transactions.
- Closing an Account must not delete the Account.
- Decommissioning a Branch must not delete historical financial data.
- Ending a Manager assignment must not delete the Manager or branch work.
- Deleting a Beneficiary only removes that saved recipient relationship.
- Deleting a temporary Money Request does not affect the Customers or Accounts involved.

For temporary child records, explicit application-level deletion is preferred where the lifecycle has business meaning.

---

# 17. Important Database Constraints

The following constraints should be enforced at database level wherever practical.

## Identity

```text
user_account.login_id                 UNIQUE
customer.user_account_id              UNIQUE
customer.phone_number                 NOT NULL, UNIQUE
UNIQUE INDEX ON LOWER(customer.email)
customer.telegram_chat_id              UNIQUE WHERE NOT NULL
employee.user_account_id              UNIQUE
employee.employee_number              UNIQUE
```

## Accounts

```text
account.account_number                UNIQUE
(customer_id, account_type)           UNIQUE WHERE status <> 'CLOSED'
```

## UPI

```text
upi.upi_address                       UNIQUE
customer.phone_number                 NOT NULL
customer.phone_number                 CHECK 10 digits, first digit 6-9
one DEFAULT_PHONE UPI per customer    UNIQUE WHERE type = 'DEFAULT_PHONE'
```

Custom UPI format restrictions that require business logic remain service-layer validation.

## Cards

```text
debit_card(account_id)                 UNIQUE WHERE status NOT IN ('REPLACED', 'EXPIRED', 'CLOSED', 'PERMANENTLY_UNUSABLE')
credit_card.credit_balance             CHECK credit_balance >= 0
credit_card.outstanding_amount          CHECK outstanding_amount >= 0
financial_transaction.fee_amount        CHECK fee_amount >= 0
financial_transaction.applied_fee_rate  CHECK applied_fee_rate >= 0.0000
financial_transaction.credit_balance_applied_amount CHECK credit_balance_applied_amount >= 0.00
credit_card_billing_cycle(credit_card_id) UNIQUE WHERE status = 'OPEN'
```

Credit-card ownership is not unique because one customer may own multiple credit cards.

## Trust Score

```text
trust_score.customer_id               UNIQUE
```

## Notification delivery

```text
(notification_id, channel)             UNIQUE
```

## Statement selection

```text
(statement_request_id, account_id)    PRIMARY KEY
```

## Financial integrity

Amounts should have appropriate positive-value constraints where the column represents an amount rather than a signed movement.

Balances should have non-negative constraints where the corresponding business rule requires it.

---

# 18. Active Request Uniqueness

Several workflows require one active request at a time.

The application must enforce these rules, and PostgreSQL should provide the final integrity protection where a partial unique index can represent the rule cleanly.

Examples:

```text
One active closure request per account
One active branch-transfer request per account
One active profile-change request per customer
One active unexpired Telegram link challenge per customer
One active funding request per branch
One active card-limit request per card
```

A typical PostgreSQL approach is a partial unique index on the active state, for example:

```text
UNIQUE(account_id) WHERE status = 'PENDING'
```

The exact status predicate will follow the final state model.

This prevents two concurrent requests from both becoming active even if two API requests arrive at almost the same time.

---

# 19. Double-Entry Financial Integrity

The application transaction that changes financial state must also create the required accounting records.

A normal financial operation should conceptually perform:

```text
1. Validate business rules
2. Lock/recheck affected financial state where required
3. Update operational balances
4. Create FinancialTransaction
5. Create TransactionAccountEffect rows
6. Create BranchFinancialMovement rows where required
7. Create balanced LedgerEntry rows
8. Commit
```

If any required financial step fails, the database transaction must roll back the complete operation.

The database should never contain:

```text
Account balance changed
BUT
ledger not recorded
```

or:

```text
Ledger posted
BUT
account balance not changed
```

for a completed financial operation.

The exact locking strategy is an implementation detail, but PostgreSQL transaction boundaries are mandatory for the financial write.

---

# 20. Concurrency Rules

Database constraints alone are not enough for financial concurrency.

Important operations may require row-level locking or equivalent transaction-safe rechecking.

Examples:

- Two transfers from the same account at the same time
- Two customers attempting to accept/cancel the same request
- Manager approval vs customer cancellation
- Two attempts to create an active request
- Automatic EMI deduction vs manual EMI payment
- Scheduled job retry vs a previous successful execution
- Concurrent branch-manager replacement

The general rule is:

> Re-read and validate the authoritative state inside the transaction before applying a critical state change.

Do not rely on a value read earlier in the request if another transaction could have changed it.

---

# 21. Indexing Strategy

Indexes are added for actual query patterns, not for every column.

The main categories are:

## High-value indexes

Examples:

```text
user_account(login_id)
customer(phone_number)
account(account_number)
account(customer_id)
account(branch_id)
upi(upi_address)
upi(account_id)
financial_transaction(transaction_reference)
transaction_account_effect(account_id, created_at)
ledger_entry(ledger_account_id, created_at)
notification(user_account_id, created_at)
notification_delivery(notification_id, channel)
otp_challenge(user_account_id, purpose, created_at)
telegram_link_challenge(token_hash, status, expires_at)
telegram_link_challenge(customer_id, status)
telegram_update_checkpoint(singleton_key, lease_expires_at)
credit_card_billing_cycle(credit_card_id, status, due_date)
audit_log(entity_type, entity_id, created_at)
```

## Query-driven composite indexes

Composite indexes should match real backend queries.

For example, customer transaction history commonly needs:

```text
(account_id, created_at DESC)
```

rather than separate indexes on both columns if the combined query is the important access pattern.

## Avoid over-indexing

Large financial tables are write-heavy. Every extra index increases storage and write/update cost.

Do not index:

- every status column automatically
- every foreign key without a query/use case
- low-selectivity columns only because they exist

The final index list should be validated against actual repository queries and query plans during implementation/testing.

---

# 22. Retention and Deletion

## Permanent / historical

These should not be removed by normal cleanup:

- Accounts
- Financial transactions
- Transaction account effects
- Transaction parties
- Branch financial movements
- Ledger accounts with financial history
- Ledger entries
- Loan contracts
- EMI history
- Credit-card billing cycles
- Reward history
- Trust score history
- Historical Manager assignments
- Audit records according to the configured audit-retention policy

## Temporary / operational

These can be cleaned according to their lifecycle:

- Money requests
- Scheduled payments
- Closure requests
- Branch-transfer requests
- Customer profile-change requests
- Funding requests after resolution where appropriate
- Loan/card applications after their retention policy
- Notifications after `valid_until`
- Notification deliveries with their parent notification
- Statement requests
- OTP challenges and pending registration context
- Telegram link challenges after expiry/consumption
- Telegram update receipts are not retained separately in V1; the durable checkpoint and atomic challenge update provide retry safety
- Idempotency records after their required retention period
- Other temporary workflow records

Cleanup timing must not determine business validity. For example, an expired request is invalid because its `valid_till` has passed, even if the cleanup job has not removed it yet.

---

# 23. Data Integrity and Source of Truth

The system uses the following authority model:

```text
PostgreSQL
    ↓
Customer / Account / UPI / Financial / Workflow truth

Redis
    ↓
Cache / rate limiting / OTP fast path / short-lived accelerator

R2
    ↓
Profile pictures and temporary statement files (`profile-photos/`, `statements/`)
```

Redis failure must not create incorrect financial state.

R2 failure must not affect unrelated financial or customer APIs.

The frontend never becomes the source of truth for balances, limits, ownership, permissions or financial state.

---

# 24. Database Transaction Boundaries

The following operations should normally be completed in one PostgreSQL transaction where they represent one atomic business operation:

### Account creation

```text
Account creation
+ initial funding
+ required financial records
+ ledger entries
```

### Financial transfer

```text
Validation
+ account balance changes
+ transaction
+ account effects
+ branch effects
+ ledger entries
```

### Money request acceptance

```text
Validate request
+ perform payment
+ create financial records
+ delete request
```

### Customer phone-number change

```text
Lock customer and default phone UPI
+ consume verified OTP challenge
+ validate new unique non-null phone number
+ delete source scheduled payments tied to old default UPI
+ retire old default phone UPI
+ update customer phone number
+ create new default phone UPI with the previous eligible account mapping
```

All listed PostgreSQL changes occur in one database transaction. EventBridge schedule deletion is requested after commit, and each execution rechecks PostgreSQL before processing. Other customers' scheduled payments addressed to the old UPI remain sender-owned and fail at execution if the saved recipient mapping no longer matches.

### Telegram update batch

```text
Acquire global polling lease
+ fetch bounded Telegram update batch
+ store matches to active link challenges
+ advance checkpoint to max(update_id) + 1
```

Challenge matches and checkpoint advancement occur in the same PostgreSQL transaction. Redis is updated only after commit and is not authoritative.

### Registration completion

```text
Lock verified registration challenge
+ recheck login ID/email/phone uniqueness
+ create user_account and customer
+ create preferences, initial Trust Score/history and reserved default phone UPI
+ consume OTP challenge
```

All steps commit atomically.

### Manager replacement

```text
End old assignment
+ create new assignment
+ update branch.current_manager_id
```

### Loan disbursement

```text
Loan activation/disbursement state
+ account balance
+ branch position
+ ledger
+ transaction
```

External notifications are intentionally outside these financial transactions. Notification failure must never roll back a successful financial operation.

---

# 25. Schema Evolution

Database changes should be introduced through versioned migrations.

The project should not rely on Hibernate automatically changing production schema.

A migration tool such as Flyway or Liquibase can be selected during backend setup; the choice does not change the logical schema in this document.

Schema changes should be:

- versioned
- reviewable
- repeatable in new environments
- safe to deploy in the expected application rollout order

Destructive schema changes should be handled carefully when historical data is involved.

---

# 26. Final Design Principles

The database follows these practical rules:

1. PostgreSQL is the source of truth for financial and business state.
2. Financial history is permanent and immutable after finalization.
3. Temporary workflows are separate from permanent financial records.
4. Account/customer/branch relationships are explicit and constrained.
5. Current Manager lookup is optimized with `branch.current_manager_id`, while assignment history remains separate.
6. Customer-facing transaction direction is stored on `TransactionAccountEffect`, not on the transaction header.
7. Accounting debit/credit is kept separate from customer-facing debit/credit.
8. Double-entry accounting is required for completed financial movements.
9. Database constraints protect simple invariants; service logic handles rules requiring workflow/context.
10. Active-request uniqueness is protected against concurrent duplicate creation.
11. Indexes are query-driven and kept selective.
12. Notification delivery is channel-independent through `NotificationDelivery`.
13. Redis and R2 are supporting services and never replace PostgreSQL as the source of financial truth.
14. Historical records are retained when operational objects become inactive or closed.
15. The schema should remain understandable and maintainable rather than introducing complexity only for architectural appearance.