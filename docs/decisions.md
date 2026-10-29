# Apex Bank — Project Decisions & Development Planning Baseline

**Document type:** Decision register and implementation-planning reference  
**Project:** Apex Bank — simulated banking system  
**Last updated:** 19 October 2026  
**Status:** Phases 8–10 architecture decisions finalized; implementation workflow decisions recorded.
**Purpose:** Architectural Decision Record (ADR) and engineering baseline for Apex Bank

---

## 1. Important operating rules

1. Use decisions in this file together with the existing requirements, database, API, and system-architecture documents. This file does **not** replace those documents or restate every domain/API contract from them.
2. Later architectural refinements supersede earlier baseline assumptions. In particular, the selected development order is **backend modules first, then frontend implementation and integration**.
3. Keep decisions practical for a solo developer. Do not add distributed-system patterns, tools, or layers unless they solve a real requirement.

## 2. Project baseline and authoritative companion documents

### 2.1 Project identity and scope

- Official bank name: **Apex Bank**.
- Project type: learning/resume-grade simulated banking system; it does not connect to real banking rails, real customers, real card networks, or real money.
- Development model: solo developer, with separate frontend and backend repositories.
- Backend Java package root: `com.apexbank`.
- The technology choices and business constraints already recorded in the project's existing documents remain authoritative unless explicitly changed through a later decision.

### 2.2 Existing project documents

The following documents remain the source of truth for their respective areas:

- `requirements.md` — requirements and business rules.
- `database.md` / `DATABASE.md` — PostgreSQL data model and integrity requirements.
- `api.md` / `API.md` — HTTP API architecture and endpoint contracts.
- `architecture.md` — overall system and infrastructure architecture.

### 2.3 Existing system baseline retained

- Frontend: React, React Router, Redux Toolkit, Axios, Tailwind CSS, deployed to Vercel.
- Backend: Java 25 and Spring Boot 4.1.1, deployed on AWS Lambda.
- API entry point: Amazon API Gateway HTTP API.
- Primary data store: Neon PostgreSQL; PostgreSQL is the source of truth for financial and business state.
- Supporting services already selected in the system architecture include Upstash Redis, Cloudflare R2, Amazon EventBridge Scheduler, SNS, SQS, and CloudWatch.
- Frontend and backend communicate through the established API boundary; the frontend does not connect directly to PostgreSQL, Redis, or R2.
- Backend authorization and validation remain authoritative. Frontend route protection and validation improve user experience but are not security boundaries.

---

# Part I — Phase 8: Frontend Information Architecture

## 3. Shared application model (Decisions 8.1–8.2)

### 8.1 — Shared shell with role-specific navigation: finalized

Use one React application and one shared application shell, with separate navigation and workspace experiences for `CUSTOMER`, `MANAGER`, and `ADMIN`.

- Reuse shared shell elements, UI primitives, layout conventions, common feedback patterns, notification entry, session controls, and logout where appropriate.
- Keep the three roles' navigation and core pages role-specific; do not expose unrelated role workflows simply because the shell is shared.
- The Manager workspace includes a branch selector where branch context is needed.
- A common notification component may be reused, but its notification data and available actions are role-specific.
- Frontend route guards and hidden actions are usability features, not authorization. The backend enforces role, ownership, branch scope, and operation permissions.

### 8.2 — Group related customer features: finalized

Use grouped navigation rather than making every feature a top-level item.

## 4. Customer workspace (Decisions 8.3–8.14)

### 8.3 — Post-login destination

After login, land on the role-specific Dashboard.

### 8.4 — Customer Dashboard

Show, in a clear hierarchy:

- Account summaries and balances.
- Quick actions.
- Recent transactions.
- Pending requests.
- Upcoming EMI/card dues and important alerts.

### 8.5 — Accounts

Use one Accounts page listing the customer's accounts, with a separate details page for each selected account.

### 8.6 — Account actions

Place account-specific actions—such as suspension/closure requests and default-UPI mapping changes—inside the relevant account details page. Shortcuts may appear elsewhere where useful, but the account remains the natural context for its own operations.

### 8.7 — Payments & Transfers

Group payment features under Payments & Transfers with separate entries for:

- Bank transfers.
- UPI payments.
- Own-account transfers.
- Beneficiaries.
- Money requests.
- Scheduled payments.

### 8.8 — Transaction history and statements

Use one unified Transaction History page with supported filters, and a separate Statements page.

The history UI should support filters where the API/resource model allows them, including:

- Account.
- Incoming vs outgoing direction.
- Date range.
- Transfer/payment method, such as UPI or bank transfer.
- Debit-card vs credit-card activity.
- A specific debit or credit card.
- Applicable status and transaction-type categories.

**API alignment:** the existing API contract defines a unified customer transaction-history endpoint and common filters. Exact card/payment-method filter names must agree with `API.md`; do not invent unsupported query parameters. A user should not have to load every transaction at once; the frontend architecture separately specifies infinite scrolling backed by paginated API requests.

### 8.9 — Cards

Use one Cards navigation section, with a card overview and separate debit-card and credit-card details.

### 8.10 — Loans & EMIs

Use one Loans & EMIs section for loan products, applications, loan details, EMI schedules, and repayment actions.

### 8.11 — Pending Requests

Use one consolidated Pending Requests list with filters by request type and status, access to request details, and status/expiry/cancellation information where applicable. Only show cancellation controls when the backend permits cancellation, and confirm before sending the cancellation request.

### 8.12 — Notifications

Place a notification icon in the top navigation/header. It opens a preview and links to the full Notifications page.

### 8.13 — Profile & Settings

Use one grouped navigation area with:

- Profile.
- Security & Login.
- Preferences.
- Telegram Connection.

### 8.14 — Responsive navigation

Use a sidebar on larger screens; allow it to collapse when appropriate. On mobile, use a drawer/hamburger pattern. Adapt the layout to other display sizes rather than forcing desktop navigation into a narrow viewport.

## 5. Manager workspace (Decisions 8.15–8.18)

### 8.15 — Branch Work Queue

Use one Branch Work Queue with filters/categories for profile changes, account closures, loan applications, credit-card applications, card-limit requests, branch transfers, and other Manager review work.

### 8.16 — Branch selector and branch context

Display a persistent branch selector in the header wherever branch context applies. Branch-specific URLs include the branch ID, for example:

`/manager/branches/:branchId/work-queue`

The URL preserves selected branch context across refreshes and supports direct links. The backend must still verify the Manager's current authorization for the selected branch on every protected operation.

### 8.17 — Customer discovery

Provide a dedicated Customers & Search page, also reachable from relevant work-queue items.

### 8.18 — Manager Dashboard

The Dashboard should include:

- A compact pending-work panel showing the **top three requests**.
- A way to expand the panel or navigate to the full work queue without opening each request individually.
- Branch ledger transactions below the top-three pending-work preview.
- Additional useful branch financial summaries, branch activity, operational indicators, and alerts, arranged for clarity rather than crowding.

## 6. Admin workspace (Decisions 8.19–8.23)

### 8.19 — Admin navigation groups

Use the following top-level groups:

- Dashboard.
- Organization.
- Finance.
- Bank Configuration.
- Audit & Exports.

Notifications and Profile & Settings remain accessible through the shared shell as appropriate.

### 8.20 — Organization

Group Branches, Manager Accounts & Assignments, and Assignment History under Organization.

### 8.21 — Finance

Group the bank-wide financial dashboard, Central Ledger, branch funding, and branch financial activity under Finance. Keep Central Ledger visibly distinct from branch financial positions.

### 8.22 — Bank Configuration

Organize settings into practical categories/subsections, with descriptions, current values, valid ranges/validation where applicable, clear explanations of what settings affect, and setting-change history. Avoid controls that do not represent genuine supported configuration.

### 8.23 — Audit & Exports

Keep audit logs and audit exports in a dedicated section separate from ordinary configuration and financial operations.

## 7. Global responsive/UI requirements (applies throughout Phase 8)

- The application must be responsive for mobile, laptop, desktop, and other display sizes.
- Layout, navigation, menus, and panels may be restructured dynamically to fit the available viewport.
- Use collapsible sidebars, drawers/hamburger menus, and compact panels as appropriate; this applies on desktop as well as mobile when it improves clarity.
- Mobile UI must be clean and organized. Do not merely shrink a desktop table/menu to fit.
- Collapsing panels must not make essential functionality inaccessible.
- The visual specifics can be decided during UI implementation; this phase does not prescribe pixel-level design.
- Reuse the UI states and API behavior already covered by the frontend requirements and `API.md` rather than duplicating those contracts here.

---

# Part II — Phase 9: Frontend Architecture

## 8. Language, tooling, and source layout (Decisions 9.1–9.8)

### 9.1 — Language

Use **JavaScript with JSX**, not TypeScript/TSX. Feature barrel files therefore use `index.js` (or another appropriate JavaScript extension), not `index.ts`.

### 9.2 — Build tool

Use **Vite** for the React single-page application.

### 9.3 — Feature-first structure

Organize code by feature. Each feature contains only the subfolders it actually needs; do not create empty boilerplate folders solely to match a template.

Illustrative shape:

```text
src/
├── app/
│   ├── router/
│   ├── store/
│   ├── providers/
│   └── config/
├── features/
│   ├── accounts/
│   │   ├── api/
│   │   ├── components/
│   │   ├── pages/
│   │   ├── hooks/       # only if needed
│   │   ├── utils/       # only if needed
│   │   └── index.js     # approved public exports
│   └── transfers/
│       ├── api/
│       ├── components/
│       ├── pages/
│       └── index.js
└── shared/
    ├── components/
    ├── layouts/
    ├── hooks/
    └── utils/
```

The exact set of feature folders can vary by feature.

### 9.4 — State toolkit

Use **Redux Toolkit**.

### 9.5 — Feature subfolders

Common candidates are `api/`, `components/`, `pages/`, `hooks/`, `utils/`, and `index.js`. Add only what is needed for that feature.

### 9.6 — Application-wide setup

Use `src/app/` for the store, router, providers, and application configuration.

### 9.7 — Shared code

Organize global reusable items under `src/shared/`, grouped by purpose—components, layouts, hooks, and utilities.

### 9.8 — Public feature boundaries

Features should import another feature through its public `index.js` exports rather than reaching into private implementation files. Enforce this convention with ESLint restrictions (for example, `eslint-plugin-import` restricted-path rules) so accidental boundary violations can be caught automatically.

## 9. Data fetching, state, and API handling (Decisions 9.9–9.16)

### 9.9 — API data and caching

Use **RTK Query integrated with Axios**. Implement an Axios-based RTK Query query adapter so the application uses centralized Axios configuration/interceptors while RTK Query manages server-data caching and query state.

### 9.10 — State ownership

- API/server data and its cache: RTK Query.
- Authentication and genuinely shared application state: Redux slices.
- Temporary form inputs and local UI state: React component state where appropriate.
- Do not put every temporary UI detail into global Redux state.

### 9.11 — API modules

Keep endpoint definitions feature-oriented, while using the shared API infrastructure described in Decision 9.13.

### 9.12 — Errors

Normalize common API errors centrally, then show a relevant feature-level message. Field validation errors should appear next to the relevant fields; important or persistent failures should use an appropriate page-level state.

### 9.13 — One shared RTK Query API slice

Create one shared RTK Query API slice for the backend and inject feature-specific endpoint definitions into it. This lets related feature mutations invalidate/refetch tags owned by other features when data is affected, such as a transfer invalidating account-balance data.

### 9.14 — Axios

Use one shared Axios instance for normal backend requests, including common configuration and interceptors.

### 9.15 — Refresh coordination

Coordinate refresh attempts centrally: only one refresh attempt should be in flight, subsequent requests that encounter an expired access token should wait for that attempt, and each original request should be retried at most once. A definitive refresh failure ends the authenticated session. The exact transient/network-error behavior must be consistent with the auth contract in `API.md`; never create an uncontrolled refresh/retry loop.

### 9.16 — Cache updates after mutations

Use RTK Query tags and targeted invalidation/refetching after confirmed successful mutations. Do not show a financial operation as successful until the backend confirms the result. Cache invalidation provides refresh consistency for active queries; it is not by itself a real-time cross-tab notification system.


## 10. Routing and rendering (Decisions 9.17–9.23)

### 9.17 — Role-prefixed URLs

Use role-specific route paths, for example:

- `/customer/accounts`
- `/manager/branches/:branchId/work-queue`
- `/admin/branches`

### 9.18 — Nested layouts

Use nested route layouts: public/authentication pages use their appropriate layouts; authenticated Customer, Manager, and Admin pages use their respective shells.

### 9.19 — Route guards

Implement centralized route guards with role metadata. Redirect unauthenticated or wrong-role users appropriately. Route guards are a UX layer only; backend authorization remains mandatory.

### 9.20 — Lazy loading

Lazy-load larger feature pages where appropriate to reduce the initial application bundle; keep essential application startup elements available.

### 9.21 — Manager branch context in URLs

Represent branch context in branch-scoped paths using the branch ID rather than relying only on Redux state. Browser tabs ordinarily have independent in-memory Redux stores; the URL decision is retained because it preserves context across refreshes and allows direct links.

### 9.22 — Route definitions

Keep centralized route configuration under `src/app/router/`, using nested layouts and feature pages.

### 9.23 — Rendering errors

Use a global error boundary plus route/feature boundaries where useful, so one failed area does not unnecessarily blank the entire application.

## 11. Shared UI implementation (Decisions 9.24–9.31)

### 9.24 — UI component foundation

Use **Tailwind CSS plus shadcn/ui** for customizable, accessible UI primitives such as dialogs, dropdowns and form controls.

### 9.25 — Icons

Use **Lucide React**.

### 9.26 — Forms

Use **React Hook Form with Zod** for form state and schema-based client validation. Backend validation remains final.

### 9.27 — Tables and large lists

Use reusable table components with server-driven pagination and filtering. On mobile, use a responsive layout or card-based rows when a conventional table does not fit.

**Transaction-history exception/behavior:** transaction history uses **infinite scrolling** to load additional pages as the user scrolls. It must not load all transaction history at once. Infinite scrolling is the UI behavior on top of the paginated API; query parameters and page boundaries must match `API.md`.

### 9.28 — Feedback messages

Use shared toast notifications for general success/error feedback, inline messages for field errors, and page-level states for important/persistent failures. Do not rely on browser-native `alert()`/`confirm()` for routine UX.

### 9.29 — Common conventions

Centralize reusable formatters (including INR currency and date/time), design tokens, and shared loading/empty/error states so features behave consistently.

### 9.30 — Status display

Create shared status mappings/components. A mapping should define a human-readable label and the relevant Tailwind classes/visual indicator for known backend business statuses. Include a safe fallback for unknown statuses so the UI does not crash or render undefined labels.

### 9.31 — Confirmation dialogs

Use reusable accessible confirmation dialogs for consequential operations. Submit the API request only after confirmation and display the actual result afterward; do not predict success before the backend responds.

## 12. Frontend environment configuration

### 9.32 — Frontend environment configuration

Use separate local and production configuration through environment variables. In production, the browser uses the same-origin `/api/v1` path via the planned Vercel rewrite. Never put secrets into frontend source or publicly exposed frontend environment variables. Frontend configuration is not a security boundary.

---

# Part III — Phase 10: Backend Architecture

## 13. Backend structure and module boundaries (Decisions 10.1–10.11)

### 10.1 — Architecture style

Use a **feature-first modular backend** with Clean Architecture / Domain-Driven Design-inspired boundaries. Keep a modular monolith unless a real requirement later justifies separating deployment units.

### 10.2 — Business modules

Organize code around the business domains: Authentication, Customer, Accounts, Transfers, UPI, Cards, Loans, Branches, Manager, Admin, Notifications, Statements, Audit, and Configuration. Combine closely related modules where that keeps the design practical; do not create artificial fragmentation.

### 10.3 — Layers inside modules

Use the following layers where applicable:

- `api/` — controller/HTTP contract boundary.
- `application/` — use cases and orchestration.
- `domain/` — business rules and domain concepts.
- `infrastructure/` — persistence and technical integrations.

A module need not contain an empty folder for every layer.

### 10.4 — Dependency direction

Maintain one-way dependency rules. Domain/business rules must not depend on controllers or provider-specific infrastructure. Application services orchestrate domain logic and technical interfaces through appropriate boundaries.

### 10.5 — Controllers

Keep controllers thin: accept/validate the HTTP input, call application services, and map the outcome to an HTTP response. Do not implement business workflows inside controllers.

### 10.6 — DTO/entity separation

Use dedicated request/response DTOs. JPA persistence entities must not be returned directly as public API responses.

### 10.7 — Mapping and boilerplate

Use **MapStruct** for DTO/domain/entity mapping. Pin a compatible dependency version and verify compilation against the selected Java 25 / Spring Boot stack during implementation; do not assume compatibility without a build check.


### 10.8 — Build tool

Use **Maven**.

### 10.9 — Migrations

Use **Flyway** with versioned SQL migrations committed to Git. Applied migrations are immutable; schema changes are made through new migrations.

### 10.10 — Hibernate schema behavior

Use `ddl-auto=validate` in production. Flyway migrations control schema changes; Hibernate must not auto-create or auto-update the production schema.

### 10.11 — Architecture tests

Use **ArchUnit** tests to enforce package/module dependencies and public-boundary rules. A feature should not import another feature's internal implementation directly when the dependency is meant to cross a public module boundary.

## 14. Application services, persistence, and financial invariants (Decisions 10.12–10.21)

### 10.12 — Validation

Use Bean Validation for request-structure constraints such as required values, lengths, formats, and basic numeric restrictions. Application/domain logic must independently enforce business rules; frontend validation is never authoritative.

### 10.13 — Use-case services

Use use-case-oriented application services such as `ExecuteTransferUseCase`, rather than oversized entity-oriented services that accumulate every operation in one class. Keep use cases focused and testable.

### 10.14 — Business-rule location

Represent domain invariants in domain policies/domain concepts where appropriate; application services coordinate the workflow and external dependencies. Keep controller and persistence concerns out of domain rules.

### 10.15 — Transaction boundaries

Place database transaction boundaries around the application operation that must succeed or fail as one database unit. Keep those transactions short. Do not keep PostgreSQL transactions/connections open while waiting for external network calls. External notification delivery must not reverse a successfully committed financial operation.

### 10.16 — Central financial posting

Use a centralized financial posting/application service to coordinate, where required, the financial transaction, account effects, double-entry ledger entries, branch financial movements, and idempotency result within the same PostgreSQL transaction. The exact unit of work must follow the requirements/database/API contracts.

### 10.17 — Concurrency protection

Use explicit database locking for balance-sensitive operations, combined with database constraints and suitable version protection for other mutable resources. Pessimistic locking (for example, `SELECT ... FOR UPDATE` / JPA `PESSIMISTIC_WRITE`) only protects an invariant when every relevant write path follows the locking protocol, the rows are locked before checking/updating balances, and the work occurs in the correct transaction.

### 10.18 — Data access

Use Spring Data JPA for normal persistence and transactional writes, state transitions, entity lifecycle management, and concurrency control. Use carefully scoped JPQL or native PostgreSQL queries when needed for locking, specialized queries, or database-specific guarantees.

Use **Spring JDBC** (`NamedParameterJdbcTemplate` / `JdbcClient`) for the read-only workloads identified in `architecture.md` and `database.md` that need dynamic filters, cross-table `UNION ALL` queries, aggregation, bulk reads, or streaming. Map these read models directly to DTOs where appropriate. Do not replace this documented JPA/JDBC division with a JPA-only approach, and do not use JDBC as the default path for transactional domain writes.

### 10.19 — Idempotency

Use a reusable idempotency service backed by PostgreSQL constraints/records and transaction-safe result persistence. Redis may accelerate duplicate checks, but PostgreSQL is the final integrity guard. The idempotency record/result must be persisted consistently with the corresponding critical operation as defined by the applicable API contract.

### 10.20 — Multi-account lock ordering

For operations that lock multiple accounts, acquire locks in a deterministic order using a stable account identifier (for example, ascending ID), then validate balances and perform the operation in one short transaction. This reduces avoidable deadlocks; it does not eliminate the need to handle database deadlock/serialization failures safely.

### 10.21 — Database retries

Use bounded retries only for classified transient failures and only when retrying the complete operation is safe. Preserve idempotency. Never automatically retry every database error or blindly repeat a financial operation.

## 15. Security, configuration, audit, and observability (Decisions 10.22–10.33)

### 10.22 — Error mapping

Use centralized exception handling, such as `@RestControllerAdvice`, to produce consistent error payloads matching `API.md`, including stable error codes, field errors where applicable, and trace/correlation IDs. Never expose stack traces, SQL details, credentials, or internal provider secrets to clients.

### 10.23 — Authorization

Use Spring Security for route-level authentication and role-level checks. Application services must enforce resource ownership, branch scope, and operation-specific permissions. Database constraints enforce applicable data invariants. Admin/Manager roles must not accidentally inherit Customer permissions.

### 10.24 — Bank configuration service

Use a centralized bank-configuration service to read typed effective settings from PostgreSQL. Cache only where safe, and snapshot applied values where necessary to preserve historical results. Configuration changes must follow the non-retroactive behavior defined in existing project requirements unless a particular rule explicitly says otherwise.

### 10.25 — External integrations

Use dedicated adapters/clients for Redis, Cloudflare R2, Telegram, notification services, and other external integrations. Give them explicit configuration, timeouts, and appropriate failure handling; avoid coupling domain logic directly to provider-specific APIs.

### 10.26 — Scheduled work

EventBridge Scheduler should directly invoke dedicated scheduled-job Lambda handler(s), which call the same application use cases as normal application operations. Do not copy business logic into scheduled jobs or require them to call the public HTTP endpoints. Every scheduled invocation must re-check authoritative database state and be safe against duplicate/delayed invocation. Whether scheduled handlers reuse the same built application artifact or a separate deployment unit is a packaging/deployment detail to verify later.

### 10.27 — Audit logging

Use a dedicated audit service and separate audit records for required security-sensitive/administrative/business operations. Where applicable, capture actor, role, branch context, action, entity type/ID, old/new state, timestamp, and reason. Audit writes must respect the operation's transactional requirements; audit history is not a substitute for application logs.

### 10.28 — Application logs

Use structured logs with trace/correlation IDs and useful failure context. Redact passwords, OTPs, tokens, card secrets, and other sensitive information. Keep operational application logging separate from permanent audit records.

### 10.29 — Typed configuration

Use typed Spring `@ConfigurationProperties` classes with validation for required settings. Fail startup when mandatory configuration is missing/invalid rather than silently operating with unsafe defaults.

### 10.30 — Secrets

Supply secrets through environment variables or secure runtime configuration; never hard-code or commit secrets to Git. Restrict access and encrypt sensitive configuration. A dedicated secrets service may be introduced if rotation/operational needs justify its cost. The final secret-storage choice must be reviewed before deployment.

### 10.31 — Time

Persist/represent absolute timestamps using UTC instants. Inject a shared Java `Clock` into time-sensitive business logic so it can be tested deterministically. Use `Asia/Kolkata` for Indian business-local schedule/cutoff decisions, statement/date presentation, and end-of-day calculations where the relevant policy is India-local. Do not mix the stored instant with the local wall-clock representation.

### 10.32 — Health/readiness endpoints

Expose minimal and deliberately secured health/readiness endpoints using Spring Boot Actuator where appropriate. Do not expose detailed diagnostics or sensitive environment/configuration publicly.

### 10.33 — Integration failure policies

Use explicit timeouts, bounded retries where safe, and a clear failure policy for each integration. Let SNS/SQS delivery retries and DLQs handle asynchronous notification delivery. Do not apply the same retries/circuit-breaker policy mechanically to every operation; financial operations and provider delivery have different safety requirements.

## 16. Backend build/runtime packaging decisions (Decisions 10.34–10.35)

### 10.34 — HTTP runtime on Lambda

Use **AWS Lambda Web Adapter** to run the standard Spring Boot HTTP application. Verify the selected adapter, Java 25, Spring Boot 4.1.1, startup behavior, and deployment artifact together before finalizing deployment. AWS Lambda Web Adapter is selected; **the final ZIP-versus-container packaging choice is deferred** to deployment planning. Do not assume Docker packaging is mandatory solely because the adapter was selected.

### 10.35 — Scheduled invocation path

EventBridge Scheduler invokes dedicated scheduled-job Lambda handler(s) directly, rather than calling internal/public HTTP endpoints. Handlers reuse application use cases and remain idempotent. Final wiring and deployment packaging are deferred to Phase 16.

---

# Part IV — Phase 11: Implementation and development workflow decisions

## 17. Implementation workflow (Decisions 11.1–11.12)


### 11.1 — Implementation starting point

Start with the backend foundation: Maven project, configuration, Spring Security foundation, exception handling, Flyway, database integration, and core infrastructure.

### 11.2 — Increment size

Build complete, testable backend feature increments rather than trying to implement the entire application in one large batch. Finish a feature's relevant database integration, business logic, API behavior, and focused tests before moving on.

### 11.3 — First business domain

After the foundation, implement Authentication and Identity first: registration, login, OTP, session handling, and authorization foundations before customer banking operations.

### 11.4 — Package root

Use `com.apexbank`.

### 11.5 — Environments

Maintain separate `local`, `test`, and `prod` configuration, with database URLs and secrets supplied externally. Use local development infrastructure where practical and test compatibility against the selected managed services.

### 11.6 — Strict test-driven development (TDD)

Follow strict TDD for each new behavior: write a meaningful test first, run it and confirm that it fails for the expected reason, implement the minimum production code needed to pass it, rerun the relevant tests, refactor, and rerun the tests. Do not write the production implementation for a behavior before its test. Apply this loop at an appropriate unit or integration-test level; test infrastructure/bootstrap work may be verified with focused build and configuration tests where a failing behavior test is not applicable. Phase 13 will define and execute the broader integration, end-to-end, concurrency, regression, and release-verification strategy.

### 11.7 — Migration workflow

Maintain versioned, domain-oriented Flyway SQL migrations. Applied migrations are immutable; create a new migration for each subsequent schema change.

### 11.8 — Git commits

Initialize Git before implementation and make commits after verified logical milestones. Keep the main branch in a working state.

### 11.9 — Feature branches

Use feature branches for substantial work units, such as `feature/authentication`; merge only after verification. Keep `main` stable.

### 11.10 — Definition of done for a feature

A backend feature is ready to be treated as complete when its relevant code, migration, API behavior/contracts, validation, error handling, and tests are complete and locally verified. A complete end-user feature also requires its applicable frontend integration to be verified later.

### 11.11 — Backend-first development order

**This is the selected cross-application sequence:** implement all backend modules first; then implement the frontend and integrate it against the completed backend APIs. This decision refines the earlier preference for feature-sized increments: backend modules are still developed and verified incrementally, but the main frontend build/integration phase follows backend completion rather than alternating frontend/backend vertical slices.

### 11.12 — Progress log

Maintain a development progress log containing completed features, current work, verified commands, failures, outstanding tasks, and important decisions. This is in addition to Git history.

## 18. Agreed development flow

1. **Planning gate:** approve the backend-foundation plan and the initial testing/CI approach before starting. Plan and review each later phase before entering it; Phases 12–17 do not all need fully completed plans before the foundation can begin.
2. **Existing repository and environment:** clone and continue using Apex Bank GitHub repository; do not create a replacement repository. Inspect the repository, current branches, and existing files before making changes. Configure safe local/test settings and ensure secrets are not committed.
3. **Backend foundation:** Maven project, app/bootstrap configuration, Spring Security foundation, error mapping, logging/trace IDs, Flyway, PostgreSQL integration, typed configuration, module boundaries, and required base tests. Add a minimal GitHub Actions workflow at this stage to run the Maven build and tests on pushes and pull requests. Run relevant checks locally before pushing, and require passing CI checks before merging into `main`; expand the pipeline in Phase 15.
4. **Authentication and identity:** implement first, as specified in the existing requirements and API contracts.
5. **Remaining backend modules:** implement one module/feature-sized unit at a time. For each unit: review requirements/API/database relationships; add/adjust migrations; write focused tests; run the agreed verification commands; fix issues; commit the verified milestone.
6. **Backend completion gate:** verify all planned backend modules and API contracts are implemented and tested sufficiently to proceed to frontend work. Broader testing and security/reliability work remains governed by Phases 13–14.
7. **Frontend foundation:** Vite + React JavaScript/JSX setup, app/router/store/providers, feature/shared structure, Tailwind/shadcn/ui setup, Axios instance, RTK Query adapter/shared API slice, token-refresh coordination, route guards, shared errors/statuses, responsive shell.
8. **Frontend features:** implement pages and modules incrementally, aligned with the Phase 8 information architecture and the finished backend `API.md` contracts. The frontend should not invent or silently change API behavior.
9. **Integration:** complete the planned frontend/backend and supporting-service integration checks in Phase 12.
10. **Testing and hardening:** execute the broader test strategy (Phase 13) and security/reliability review (Phase 14), fixing issues before release.
11. **Documentation and CI/CD:** finish operational/developer documentation and expand/harden the CI automation introduced during the backend foundation (Phase 15).
12. **Deployment setup and release:** perform cloud setup and deployment only after the relevant plans and release gates are approved (Phase 16).
13. **Production verification:** run the defined smoke tests, financial-integrity checks, monitoring and acceptance criteria (Phase 17).

---

## 19. Decisions intentionally deferred

These are not silently settled by this file and must be handled in the relevant planning phase or during implementation where appropriate:

- Completion of planning for Phases 12–17.
- Actual AWS/cloud resource setup and deployment.
- Final AWS Lambda Web Adapter packaging/wiring (ZIP versus container) and compatibility verification for the selected Java/Spring Boot versions.
- Final implementation-specific dependency versions, including a MapStruct version verified against Java 25.
- Exact transaction-history query parameter names for card-specific/payment-method filters where `API.md` still requires final alignment with resource contracts.
- UI visual details, breakpoints, typography, and pixel-level design; responsive behavior and navigation requirements are already fixed above.
- Any later business/architecture change must be explicitly discussed and recorded rather than assumed.

---

## 20. Change-control rule

When a decision changes, record:

1. The decision ID being changed.
2. The previous decision.
3. The newly approved decision and rationale.
4. Any affected requirements, database, API, frontend, backend, testing, security, or deployment documents.