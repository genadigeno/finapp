# Architecture Decision Records

Detailed architectural decisions. The human-readable index of *what* was decided is
[`docs/project/DECISIONS.md`](../project/DECISIONS.md); this directory holds the reasoning.

## Rules

- One decision per ADR, using [`ADR_TEMPLATE.md`](ADR_TEMPLATE.md).
- Numbering is sequential and permanent. Numbers are never reused.
- **An accepted ADR is never edited to change its decision.** A changed decision is a new ADR
  that supersedes the old one; the old one is marked `Superseded by ADR-NNNN`.
- Status: `Proposed` → `Accepted` → `Superseded`.
- ADR-0001 through ADR-0028 are `Accepted`, moved there by the Phase 0 review (`P0-DOC-012`),
  once the decisions have been validated by implementation rather than only by argument.
- A decision found in code but absent from this record is architectural debt.

## Index

| ADR | Title | Status | Phase | Concern |
|-----|-------|--------|-------|---------|
| [0001](ADR-0001-modular-monolith.md) | Modular monolith as the initial deployment architecture | Accepted | 0 | Topology |
| [0002](ADR-0002-ledger-authoritative-record.md) | Immutable double-entry journal postings are the authoritative financial record | Accepted | 0 | Financial truth |
| [0003](ADR-0003-monetary-representation.md) | Monetary values are integer minor units with explicit currency and scale | Accepted | 0 | Money |
| [0004](ADR-0004-idempotency-strategy.md) | Idempotency for money-moving commands is enforced at the database | Accepted | 0 | Idempotency |
| [0005](ADR-0005-transactional-outbox.md) | Transactional outbox and inbox for reliable event exchange | Accepted | 0 | Events |
| [0006](ADR-0006-module-boundary-enforcement.md) | Module boundaries are enforced mechanically | Accepted | 0 | Boundaries |
| [0007](ADR-0007-phase-gated-delivery.md) | Phase-gated delivery with a formal status model | Accepted | 0 | Process |
| [0008](ADR-0008-provider-adapters.md) | External providers sit behind anti-corruption adapters | Accepted | 0 | Integration |
| [0009](ADR-0009-balance-as-projection.md) | Balances are derived projections anchored to the ledger | Accepted | 0 | Balances |
| [0010](ADR-0010-audit-trail.md) | The audit trail is a first-class append-only store, distinct from logs | Accepted | 0 | Audit |
| [0011](ADR-0011-forward-only-migrations.md) | Forward-only migrations with module-owned schema history | Accepted | 0 | Schema evolution |
| [0012](ADR-0012-context-to-module-mapping.md) | Context-to-module mapping: deliberate merges with recorded split triggers | Accepted | 0 | Module boundaries |
| [0013](ADR-0013-typed-time-ordered-identifiers.md) | Aggregate identifiers are typed and time-ordered (UUIDv7) | Accepted | 0 | Identifiers |
| [0014](ADR-0014-multi-instance-execution.md) | Every service runs as N concurrent instances | Accepted | 0 | Distributed execution |
| [0015](ADR-0015-api-versioning-and-contract-publication.md) | API versioning in the path, with the contract generated and compared on every build | Accepted | 0 | API evolution |
| [0016](ADR-0016-health-liveness-and-readiness.md) | Liveness and readiness answer different questions, and only readiness consults dependencies | Accepted | 0 | Operability |
| [0017](ADR-0017-tracing-and-correlation-on-spans.md) | Correlation is carried on spans; a trace identifier never replaces it | Accepted | 0 | Observability |
| [0018](ADR-0018-metric-naming-and-cardinality.md) | Metric names are a contract, and no tag value may come from a request | Accepted | 0 | Observability |
| [0019](ADR-0019-default-deny-redaction.md) | A secret is unloggable by default, not redacted by remembering | Accepted | 0 | Security |
| [0020](ADR-0020-secret-management.md) | Secrets are externalised, and the local default is confined to loopback | Accepted | 0 | Security |
| [0021](ADR-0021-security-context-and-the-absent-actor.md) | An unestablished actor is an error, never the system actor | Accepted | 0 | Security |
| [0022](ADR-0022-data-classification-at-the-ceiling.md) | Data is classified per column, at its ceiling, before it holds anything | Accepted | 0 | Data |
| [0023](ADR-0023-transport-security-confined-to-loopback.md) | A database off this machine is reached with verified TLS, or not at all | Accepted | 0 | Security |
| [0024](ADR-0024-single-instance-assumptions-fail-the-build.md) | Single-instance assumptions fail the build | Accepted | 0 | Architecture |
| [0025](ADR-0025-dependency-verification-and-locking.md) | Every resolved artefact is checksum-verified and version-locked | Accepted | 0 | Security |
| [0026](ADR-0026-keeping-pins-fresh.md) | A pin that nothing maintains is a pin that rots | Accepted | 0 | Security |
| [0027](ADR-0027-tests-bring-their-own-database.md) | Tests bring their own database | Accepted | 0 | Testing |
| [0028](ADR-0028-test-tiers-by-requirement.md) | A test tier is what the test needs, not what it proves | Accepted | 0 | Testing |
| [0029](ADR-0029-party-customer-identity-are-three-aggregates.md) | Party, Customer and Identity are three aggregates | Accepted | 1 | Domain |
| [0030](ADR-0030-server-side-sessions-and-assurance-level.md) | Server-side sessions, and assurance is a level rather than a flag | Accepted | 1 | Security |
| [0031](ADR-0031-authorization-model.md) | Roles grant permissions; ownership is checked separately | Accepted | 1 | Security |
| [0032](ADR-0032-credential-storage-and-rotation.md) | Credentials store a derivation and the parameters that produced it | Accepted | 1 | Security |
| [0033](ADR-0033-explicit-sql-and-no-object-relational-mapper.md) | Explicit SQL, and no object-relational mapper | Accepted | 1 | Data |
| [0034](ADR-0034-the-platform-owns-the-correlation-identifier.md) | The platform owns the correlation identifier | Accepted | 1 | Security |
| [0035](ADR-0035-kyc-owns-the-verification-decision.md) | The KYC context owns the verification decision; Party projects it | Accepted | 2 | Domain |
| [0036](ADR-0036-verification-evidence-and-document-storage.md) | Verification evidence verbatim in PostgreSQL; object storage deferred | Accepted | 2 | Data |
| [0037](ADR-0037-consent-is-an-append-only-history.md) | Consent is an append-only history; the current basis is derived | Accepted | 2 | Domain |
| [0038](ADR-0038-provider-verdicts-are-evidence.md) | A provider verdict is evidence; the decision is ours | Accepted | 2 | Security |
| [0039](ADR-0039-posting-concurrency-and-isolation.md) | `READ COMMITTED`; postings are inserts; balance-dependent decisions take the account lock | Accepted | 3 | Data |
| [0040](ADR-0040-chart-of-accounts-structure.md) | A flat account with a typed classification, not a tree | Accepted | 3 | Domain |
| [0041](ADR-0041-balance-projection-placement.md) | The balance projection is transactional, ledger-owned, and never backs a decision | Accepted | 3 | Data |
| [0042](ADR-0042-account-model-four-distinct-concepts.md) | Customer Account, Ledger Account, Wallet and Operational Account are four things | Accepted | 3 | Domain |
| [0043](ADR-0043-transfer-and-posting-commit-together.md) | The transfer and its posting commit in one transaction; no internal saga | Accepted | 4 | Transfers |
| [0044](ADR-0044-transfer-lifecycle-states-are-earned.md) | The transfer lifecycle: states are earned by their producers | Accepted | 4 | Transfers |
| [0045](ADR-0045-payment-intent-and-attempt.md) | Payment intent and payment attempt: two aggregates, three machines | Accepted | 5 | Payments |
| [0046](ADR-0046-no-transaction-spans-a-provider-call.md) | No transaction spans a provider call: dispatch-before-call, UNKNOWN, reconciliation by query | Accepted | 5 | Payments |
| [0047](ADR-0047-webhook-ingestion.md) | Webhooks: authenticated, freshness-bounded, evidence-first, idempotent | Accepted | 5 | Payments |
| [0048](ADR-0048-authorization-is-not-a-posting.md) | Authorization is a payment-domain fact; the ledger's first touch is capture | Accepted | 5 | Payments · Ledger |
| [0049](ADR-0049-first-provider-simulated-card-psp.md) | The first provider is a simulated card-style PSP; nothing is final before settlement | Accepted | 5 | Payments |
| [0050](ADR-0050-fee-model-gross-capture-net-payable.md) | The fee model: gross capture to the payable, fee assessed in the same entry, net payout | Proposed | 6 | Merchant · Checkout · Payments · Ledger |
| [0051](ADR-0051-merchant-payout-accounting.md) | Merchant payout: hold-then-dispatch on the payable, nothing final before settlement | Proposed | 6 | Merchant · Ledger |
| [0052](ADR-0052-merchant-api-identity.md) | Merchant API identity: scoped API keys, tenancy in the statement | Proposed | 6 | Merchant · Identity |
| [0053](ADR-0053-checkout-session-and-order.md) | Checkout session and order: two aggregates, expiry gates dispatch, landed money always wins | Proposed | 6 | Checkout · Merchant · Payments |
| [0054](ADR-0054-merchant-refund-funded-by-its-net.md) | A merchant refund is funded by its net; the only credit it extends is the fee the platform keeps | Proposed | 6 | Merchant · Payments · Ledger |

## Anticipated ADRs

Recorded so the decisions are not made implicitly. Each is written at its phase's entry gate.

| Phase | Decision required |
|-------|------------------|
| 1 | Authentication and session strategy; token lifetime and revocation |
| 1 | Authorization model (RBAC with attribute constraints) and policy evaluation point |
| 2 | Screening adapter design and evidence retention |
| 2 | Consent modelling and consent-dependent capability gating |
| 3 | **Isolation level and concurrency control for postings** — highest-risk open decision |
| 3 | Chart-of-accounts structure and its relationship to the Phase 14 GL |
| 3 | Balance projection placement and rebuild procedure |
| 4 | Transfer/ledger transaction boundary and compensation strategy |
| 5 | Payment intent vs attempt modelling |
| 5 | Unknown-state handling and reconciliation-by-query sweeper |
| 5 | Webhook ingestion, signature verification and deduplication |
| 7 | Rail abstraction and per-rail finality semantics |
| 7 | Dispute and chargeback financial treatment |
| 8 | Matching strategy, rule versioning and tolerance model |
| 8 | Suspense account policy and ageing |
| 8 | Break resolution authority and four-eyes thresholds |
| 9 | FX quote and rate-lock model |
| 9 | Multi-currency accounting, FX position and revaluation |
| 9 | Per-currency-pair rounding policy and residual treatment |
| 10 | Credit policy versioning and decision reproducibility |
| 10 | Bureau adapter and credit-data retention |
| 11 | Interest accrual and day-count convention |
| 11 | Repayment allocation order |
| 12 | BNPL refund and instalment adjustment policy |
| 13 | Synchronous risk evaluation and fail-safe policy |
| 13 | Risk rules versioning; case management model |
| 14 | Operational-ledger-to-GL mapping |
| 14 | Accounting period and close model |
| 16 | Any service extraction or partitioning decision (revisits ADR-0001) |
