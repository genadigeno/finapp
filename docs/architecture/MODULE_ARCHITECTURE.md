# Module Architecture — First Baseline

The initial architecture baseline: module boundaries, authoritative data ownership, and the
transactional, consistency, event, security and provider boundaries between them.

Governing decisions: [ADR-0001](../adr/ADR-0001-modular-monolith.md) (modular monolith),
[ADR-0006](../adr/ADR-0006-module-boundary-enforcement.md) (boundary enforcement),
[ADR-0008](../adr/ADR-0008-provider-adapters.md) (provider adapters).

---

## 1. Deployment Shape

**One deployable unit: a modular monolith.**

Bounded contexts are domain boundaries. They are *not* an automatic list of microservices
(`BOUNDED_CONTEXTS.md`). A single deployable gives us the one property that is hardest to
recover once lost: the ability to make a state change and its accounting posting atomic in a
single database transaction.

Distribution is a Phase 16 question, answered with measurement, not anticipation.

---

## 2. Module Layering

```
                    ┌───────────────────────────────┐
                    │            app                │   composition root, HTTP, config
                    └───────────────┬───────────────┘
                                    │
     ┌──────────────────────────────┴──────────────────────────────┐
     │                    business modules                          │
     │  identity  party  kyc  consent  accounts  ledger  transfers  │
     │  payments  merchant  checkout  settlement  reconciliation    │
     │  fx  credit  lending  bnpl  risk  accounting  notification   │
     └──────────────────────────────┬──────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │          platform             │   outbox, inbox, idempotency,
                    │                               │   audit, correlation, telemetry,
                    │                               │   error contract, provider SPI
                    └───────────────┬───────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │        sharedkernel           │   Money, CurrencyCode, typed Ids,
                    │                               │   Clock, event envelope
                    │      (no Spring, no JPA)      │
                    └───────────────────────────────┘
```

Dependency direction is strictly downward and acyclic.

**Enforcement, as it actually stands.** Gradle enforces the *direction* structurally today
(`P0-TSK-002`): a module sees only what its build file declares, and a reverse edge fails
configuration with a circular-dependency error. Classpath tests in `sharedkernel` and
`platform` assert that no module output from above appears below, and that `sharedkernel`
carries no Spring artefact.

Gradle cannot express the finer rules — no cross-module internals, no cross-module entity
references, no floating-point money. Those are ArchUnit rules and are **not yet in place**;
they are `P0-TSK-007` and `P0-TSK-008`. Until then the finer boundaries rest on review.

### What may enter `sharedkernel`
Only concepts that are genuinely universal *and* stable: `Money`, `CurrencyCode`, rounding
policy, typed identifiers, the event envelope, `Clock` abstraction.

### What may never enter `sharedkernel`
Any business concept. `Account`, `Customer`, `Payment`, `Transfer` and every other domain
noun belong to exactly one module. A shared kernel that accumulates business types becomes
the coupling sink that a modular monolith exists to prevent.

---

## 3. Module Register

For every module: **Owns** (authoritative state), **Transaction boundary**, **Consistency**,
**Publishes**, **Consumes**, **Security boundary**, **Phase**.

### `platform` — Phase 0
- **Owns:** idempotency records, outbox, inbox, audit records
- **Transaction:** participates in the caller's transaction (outbox, idempotency, audit)
- **Consistency:** strong, always same-transaction with the caller's state change
- **Publishes:** nothing itself; it is the publication mechanism
- **Security:** audit records are `INSERT`/`SELECT` only for the application role
- **Note:** the only module every other module may depend on directly

### `sharedkernel` — Phase 0
- **Owns:** no persistent state
- **Consistency:** n/a — pure value types
- **Security:** no I/O, no secrets, no framework
- **Enforced:** test libraries come from the version catalog, not the Spring Boot BOM, so
  "no Spring Framework dependency" is a fact rather than an argument about whether BOM
  constraints count. `SharedKernelIsolationTest` fails if any Spring artefact reaches the
  classpath.

### `party` — Phase 1
- **Owns:** Party, Customer, profile data
- **Transaction:** own; single-aggregate
- **Consistency:** strong internally; other modules hold `PartyId` references only
- **Publishes:** `PartyRegistered`, `CustomerCreated`, `PartyProfileChanged`
- **Consumes:** `KycDecisionRecorded` (to cache status as a non-authoritative projection)
- **Security:** PII — restricted; ownership-scoped access

### `identity` — Phase 1
- **Owns:** Identity, Credential, MFA enrolment, Device, Session, Role assignment
- **Transaction:** own
- **Consistency:** strong; session revocation is immediate, not eventually consistent
- **Publishes:** `IdentityCreated`, `AuthenticationSucceeded/Failed`, `SessionRevoked`
- **Security:** the platform's highest-sensitivity module. Credential material is isolated
  from `party` profile data and never leaves the module in any form.
- **Note:** `identity` and `party` are deliberately separate. Who can log in and who exists
  as a legal party are different questions with different lifecycles.

### `kyc` — Phase 2
- **Owns:** KYC/KYB Case, Verification Check, Screening Result (evidence), Document
  reference, Beneficial Owner, Risk Rating
- **Transaction:** own; case-scoped
- **Consistency:** the KYC decision is authoritative here. `party` may project it but never
  overrides it.
- **Publishes:** `KycCaseOpened`, `KycDecisionRecorded`, `ScreeningHitRaised`
- **Security:** restricted PII; document access audited; reviewer actions elevated
- **Providers:** sanctions, PEP, adverse media, document verification — all via adapters

### `consent` — Phase 2
- **Owns:** Consent Record, versioned consent text, grant/withdraw history
- **Consistency:** strong; a withdrawn consent takes effect immediately
- **Publishes:** `ConsentGranted`, `ConsentWithdrawn`
- **Note:** consent is not authentication and is not authorization (`CLAUDE.md`
  §Domain Distinctions)

### `ledger` — Phase 3 — *the financial authority*
- **Owns:** Chart of Accounts, Ledger Account, Journal Entry, Journal Line, Balance
  projection, Hold
- **Transaction:** owns the posting transaction. Callers request a posting; the ledger
  decides whether and how it is written.
- **Consistency:** journal entries strongly consistent. Balance projection is derived and
  its staleness bound is documented; authoritative balance decisions read the ledger.
- **Publishes:** `JournalEntryPosted`, `HoldPlaced`, `HoldReleased` — via the outbox, in the
  posting transaction
- **Consumes:** nothing. The ledger does not react to events; it is commanded.
- **Security:** posting authority is privileged. Manual entries require elevation, reason
  codes and four-eyes. Journal tables are `INSERT`/`SELECT` only.
- **Invariants:** `INV-LED-*`, `INV-BAL-*`, `INV-HIST-01`, `INV-REV-01`
- **Hard rule:** no other module writes a posting (`INV-LED-04`, boundary-test enforced)

### `accounts` — Phase 3
- **Owns:** Customer Account, Wallet, account product lifecycle and status
- **Transaction:** own; requests ledger postings for financial effects
- **Consistency:** account status strong; balance read from `ledger`
- **Publishes:** `AccountOpened`, `AccountClosed`, `AccountStatusChanged`
- **Note:** `accounts` owns the *product*; `ledger` owns the *money*. An account's balance
  is not a field on the account.

### `transfers` — Phase 4
- **Owns:** Transfer, Beneficiary, transfer lifecycle history
- **Transaction:** transfer state transition and the ledger posting commit together
  (single database, single transaction — the principal benefit of ADR-0001)
- **Consistency:** strong
- **Publishes:** `TransferInitiated/Completed/Failed/Reversed`
- **Security:** ownership-scoped; step-up authentication for high value or new beneficiary
- **Seams:** limit check, risk decision (Phase 13)

### `payments` — Phase 5
- **Owns:** Payment Intent, Payment Attempt, Authorization, Capture, Refund, Webhook Event
  (raw evidence), Provider State Mapping
- **Transaction:** own. **No transaction spans a provider call.** State is committed before
  the call, and the outcome is applied in a separate transaction.
- **Consistency:** strong internally; provider truth is eventually consistent and may be
  unknown (`INV-LIFE-03`)
- **Publishes:** `PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`,
  `PaymentFailed`, `PaymentStateUnknown`, `RefundCompleted`
- **Security:** webhook signature verification; provider credentials in secret management;
  tokenised instruments only — no PAN, ever
- **Providers:** PSP/processor adapters (ADR-0008)

### `paymentmethods` — Phase 5
- **Owns:** Payment Method token references, instrument metadata
- **Security:** tokens only. Raw card data never enters the platform boundary.

### `merchant` — Phase 6
- **Owns:** Merchant, Merchant Account, Fee Schedule (versioned), Merchant Payout
- **Consistency:** merchant payable derived from `ledger`, never a stored mutable field
- **Publishes:** `MerchantOnboarded`, `FeeAssessed`, `MerchantPayoutInitiated`
- **Security:** strict tenant isolation; payout destination change requires step-up,
  four-eyes and cooling-off

### `checkout` — Phase 6
- **Owns:** Checkout Session, Order
- **Consistency:** sessions expire; expiry is a domain event
- **Publishes:** `CheckoutSessionCreated/Expired`, `OrderPaid`

### `settlement` — Phase 8
- **Owns:** Settlement Batch, Settlement File (raw, checksummed), Settlement Line,
  Expectation
- **Security:** files may contain PII — encrypted, access-controlled

### `reconciliation` — Phase 8
- **Owns:** Match, Match Rule (versioned), Tolerance (versioned), Break, Investigation,
  Resolution
- **Transaction:** resolution and its compensating ledger posting commit together
- **Publishes:** `ReconciliationBreakRaised`, `BreakResolved`, `AdjustmentPosted`
- **Security:** resolution is the most sensitive non-administrative privilege in the
  platform — four-eyes above threshold, reason codes, full audit
- **Hard rule:** no code path deletes a break (`INV-REC-01`, `INV-REC-02`)

### `fx` — Phase 9
- **Owns:** FX Quote, Exchange Rate snapshot, FX Trade, FX Position, Currency configuration
- **Security:** rates are server-authoritative; client-supplied rates rejected

### `crossborder` — Phase 9
- **Owns:** Cross-Border Payment, Corridor policy

### `credit` — Phase 10
- **Owns:** Credit Profile, Bureau evidence, Score, Policy Version, Decision (immutable),
  Reason Codes, Exposure
- **Security:** restricted PII with retention limits; policy activation requires four-eyes
- **Providers:** credit bureau adapters

### `lending` — Phase 11
- **Owns:** Loan Application, Offer, Loan, Repayment Schedule, Accrual Record, Repayment,
  Delinquency State
- **Consistency:** loan balances derived from `ledger` postings
- **Invariants:** `INV-IDEM-02` (accrual idempotency) is critical here

### `bnpl` — Phase 12
- **Owns:** BNPL Agreement, Instalment Plan, Merchant Financing record
- **Note:** references both `merchant` and `lending` concepts but owns neither

### `risk` — Phase 13
- **Owns:** Signal, Rule Set (versioned), Risk Assessment, Risk Decision, Limit, Velocity
  Counter, Alert, Case
- **Consistency:** decisions are advisory inputs to a domain lifecycle. `risk` never writes
  another module's state and never posts to the ledger.
- **Security:** manual override requires reason codes and four-eyes above threshold; AML
  case detail is never exposed on customer-facing surfaces

### `accounting` — Phase 14
- **Owns:** GL Account, GL Mapping Rule (versioned), Accounting Period, Trial Balance
  snapshot, Report Run (immutable)
- **Consistency:** derived read model from `ledger`
- **Hard rule:** **no write access to ledger tables**, verified by database privilege review

### `notification` — Phase 1 onward
- **Owns:** notification records and delivery state
- **Consistency:** eventually consistent; never in a money-moving transaction
- **Consumes:** integration events from many modules

---

## 4. Boundary Rules

### Module boundary
- Each module owns a package root; internals are not accessible across modules.
- A module exposes a published interface (commands, queries) and integration events.
- No cross-module entity or ORM-relationship references. References are typed identifiers.
- Dependency direction is acyclic and enforced.
- Every module applies the `java-library` plugin and uses the `api`/`implementation`
  distinction deliberately. `implementation` keeps a dependency off consumers' compile
  classpaths so a module cannot leak its internals downstream by accident; `api` makes
  exposure a reviewed choice. Where process isolation is absent, controlling transitive
  exposure is one of the few boundary mechanisms that genuinely holds. `platform` exposes
  `sharedkernel` via `api` because kernel value types appear in its own signatures.

### Data boundary
- Schema per module in one PostgreSQL database (ADR-0006).
- **No foreign keys across module schemas.** Referential integrity across contexts is a
  domain concern, enforced at the boundary, not by the database.
- One writer per table. Cross-module reads go through the owning module's API, not by
  querying its tables.

### Transaction boundary
- A transaction never spans a call to an external provider.
- A transaction never spans two modules' authoritative state **except** where a documented
  ADR justifies it — currently only transfer-plus-posting and resolution-plus-adjustment,
  both of which are the explicit reason for choosing a monolith.
- The outbox write always shares the transaction of the state change (`INV-EVT-01`).

### Consistency boundary
- Authoritative state is strongly consistent within its owning module.
- Cross-module state is eventually consistent via events.
- No financial decision reads a projection with an unbounded staleness (`INV-BAL-05`).

### Event boundary
- Domain events stay inside a module. Integration events are published contracts and are
  versioned.
- Publication is via outbox only; no domain code publishes to the broker directly.
- Consumers are duplicate-, delay-, reorder- and replay-safe (`INV-EVT-04`).

### Security boundary
- Authentication at the edge; authorization at the module's published interface, not only at
  the HTTP layer.
- Privileged financial operations (posting, adjustment, break resolution, payout change,
  policy activation, period close) require elevation and produce audit records.
- Data classification governs handling; restricted data does not cross into modules that do
  not require it.

### External provider boundary
- Every external provider sits behind an adapter implementing a domain-owned interface.
- Provider vocabulary, error codes and state strings never appear in the domain or in public
  API contracts.
- Adapters retain raw request/response evidence (`INV-HIST-02`).
- Providers are assumed slow, duplicating, inconsistent, late and unavailable.

---

## 5. Extraction Criteria

A module may be extracted into a separate service only when at least one is demonstrated
with evidence, and an ADR records it:

1. Measured, materially different scaling requirement.
2. Isolation requirement (regulatory, PCI scope, or blast radius) that co-location cannot
   satisfy.
3. Independent deployment cadence blocked by co-location, with evidence of the block.
4. Separate ownership requiring an independent release boundary.

Explicitly **not** valid reasons: the module is large; the domain has a noun; microservices
are conventional; a diagram looks cleaner.

`ledger` is the module least likely to be extracted, because the atomicity it provides to
its callers is the primary architectural asset of this design.

---

## 6. Known Open Questions

Tracked in `CURRENT_STATE.md` §Unresolved Architectural Questions:

- Isolation level and locking strategy for concurrent postings (Phase 3 ADR).
- Whether balance projections live in the ledger schema or a separate read store.
- Whether `accounts` and `wallet` are one module or two.
- Whether `checkout` is a module or part of `merchant`.
- Chart-of-accounts structure and its relationship to the Phase 14 GL.
