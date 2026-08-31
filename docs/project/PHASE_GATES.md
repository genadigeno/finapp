# Phase Gates

Formal entry and exit control for every phase in
[`DELIVERY_PLAN.md`](DELIVERY_PLAN.md).

A phase is **not** complete because code compiles, because tests pass, or because a demo
works. Completion is defined by the gate below.

---

## 1. Status Model

Every phase, epic and task carries exactly one status.

| Status | Meaning | Entry condition | Permitted transitions |
|--------|---------|-----------------|----------------------|
| `PLANNED` | Defined in the delivery plan; not yet assessed for readiness | Exists in `DELIVERY_PLAN.md` | → `READY`, → `BLOCKED` |
| `READY` | Entry gate passed; work may begin | Entry gate §2 satisfied | → `IN_PROGRESS`, → `BLOCKED` |
| `IN_PROGRESS` | Active implementation | A task has started | → `IN_REVIEW`, → `BLOCKED` |
| `BLOCKED` | Cannot proceed | Blocker recorded in `CURRENT_STATE.md` with owner and required resolution | → previous status once resolved |
| `IN_REVIEW` | Implementation believed complete; formal review under way | All exit-gate items claimed complete | → `COMPLETE`, → `IN_PROGRESS` |
| `COMPLETE` | Exit gate passed and recorded | Exit gate §3 and phase-specific §5 satisfied | terminal (reopen requires an ADR) |

Rules:

- Exactly one phase may be `IN_PROGRESS` or `IN_REVIEW` at a time.
- A phase may not become `READY` while any hard dependency is not `COMPLETE`.
- `IN_REVIEW` → `COMPLETE` is an explicit decision recorded in `CURRENT_STATE.md`. It never
  happens implicitly by finishing a task.
- Moving backwards from `IN_REVIEW` to `IN_PROGRESS` is normal and expected. It is not a
  failure; shipping through a failed gate is.

---

## 2. Universal Entry Gate

A phase may become `READY` only when **all** hold:

1. All hard dependency phases are `COMPLETE`.
2. The phase's section in `DELIVERY_PLAN.md` is current and specific — not aspirational.
3. Bounded contexts and aggregates in scope are identified.
4. The invariants the phase must protect are identified by ID from
   [`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md).
5. The lifecycle/state machines in scope are drafted.
6. Transaction and consistency boundaries are stated.
7. Idempotency requirements are stated for every money-moving command in scope.
8. External dependencies and their failure modes are listed.
9. Security, audit and reconciliation implications are stated.
10. Backlog items exist at task granularity with acceptance criteria.
11. Any architectural decision required to start has an ADR in at least `Proposed` status.
12. `CURRENT_STATE.md` names the phase as the active phase.

---

## 3. Universal Exit Gate

A phase may become `COMPLETE` only when **all twelve** hold. Each must be demonstrable, not
asserted.

| # | Criterion | Evidence required |
|---|-----------|-------------------|
| 1 | Required functionality exists | Every phase deliverable implemented and exercisable end to end |
| 2 | Architectural boundaries respected | Boundary tests pass; no cross-module violation; no undeclared dependency |
| 3 | Required invariants tested | Every in-scope `INV-*` has at least one test that fails if the invariant is broken |
| 4 | Failure cases handled | Every failure scenario in the phase's §12 has a test or a documented, accepted rationale |
| 5 | Security requirements implemented | Authn/authz enforced and negatively tested; no secrets in source; sensitive data classified |
| 6 | Observability exists | Metrics, traces and structured logs for the phase's critical flows; correlation propagated |
| 7 | Integration tests pass | Full suite green against real infrastructure via Testcontainers |
| 8 | Documentation reflects reality | Domain/architecture docs match the implementation, not the intention |
| 9 | `CURRENT_STATE.md` updated | Accurately describes completed capability and next phase |
| 10 | Relevant ADRs exist | Every architectural decision taken during the phase is recorded and `Accepted` |
| 11 | No unresolved critical issues | Known-issues list contains no `critical` or `high` severity item |
| 12 | Formal phase review conducted | Review record written against `DEFINITION_OF_DONE.md` |

### Financial-phase supplement

Any phase that can affect money, balances, settlement, fees, credit exposure or accounting
(Phases 3, 4, 5, 6, 7, 8, 9, 11, 12, 14) additionally requires:

| # | Criterion |
|---|-----------|
| F1 | Trial balance sums to zero per currency across all postings created by the phase |
| F2 | Every balance touched is reproducible by replaying postings from zero |
| F3 | Every money-moving command has a tested idempotency guarantee at the financial boundary |
| F4 | Every reversal/compensation path is implemented and tested; no path mutates history |
| F5 | Duplicate external event delivery is proven to produce no second financial effect |
| F6 | Concurrency tests exist for every contended financial resource |
| F7 | No floating-point type appears anywhere in a monetary code path (statically verified) |
| F8 | Reconciliation implications are implemented or explicitly deferred with a named owning phase |

---

## 4. Phase Review

Conducted at `IN_REVIEW`, before `COMPLETE`. Output is a written record appended to
`CURRENT_STATE.md` history or stored under `docs/project/reviews/`.

The review covers, in order:

1. Domain correctness — do the implemented concepts match the domain model?
2. Financial correctness — walk one real posting end to end: economic event → domain
   operation → financial transaction → journal entry → lines → balances.
3. Boundary integrity — did any module reach into another's state?
4. Failure behaviour — pick two failure scenarios and trace them through the code.
5. Security — enumerate privileged actions and confirm each is authorised and audited.
6. Test quality — do the tests fail when the invariant is deliberately broken?
7. Documentation drift — diff docs against implementation.
8. Architectural debt — record anything knowingly deferred, with the phase that owns it.

A review that finds a gate failure returns the phase to `IN_PROGRESS`.

---

## 5. Phase-Specific Exit Criteria

These are **in addition** to §3 (and the financial supplement where applicable).

### Phase 0 — Domain and Architecture Foundation
- Multi-module Gradle build green in CI from a clean clone.
- ArchUnit rules fail the build on a deliberately introduced boundary violation (verified by
  temporarily introducing one).
- `Money` covered by property tests: associativity of addition, currency-mismatch rejection,
  rounding correctness for 0/2/3 minor-unit currencies, overflow rejection.
- No floating-point type in any monetary path — statically enforced.
- Idempotency kernel: concurrent identical keys produce one effect; same key with a
  different request fingerprint is rejected.
- Outbox: process killed between commit and publish; relay recovers and publishes exactly the
  committed events; consumer sees at-least-once with dedupe.
- Inbox: duplicate inbound event produces one effect.
- Audit table rejects `UPDATE` and `DELETE` at the database privilege level.
- One request produces a correlated trace, log line and event carrying the same
  `correlationId`.
- ADR-0001..ADR-0010 all `Accepted`.

### Phase 1 — Identity and Customer Foundation
- Every protected endpoint has a passing negative authorization test.
- Credentials verifiably never appear in logs, events or API responses.
- MFA cannot be bypassed by any tested path; session revocation is immediate and tested.
- Account recovery cannot be used to take over an account under the tested abuse cases.
- Every privileged action produces an audit record with actor, time, operation, target,
  correlation and outcome.
- Party, Customer and Identity are separately persisted with distinct lifecycles.

### Phase 2 — KYC/KYB and Consent
- KYC state machine rejects every invalid transition (exhaustively tested).
- Provider verdict is stored as evidence and is never itself the decision.
- Duplicate provider callback produces no duplicate decision.
- Consent withdrawal demonstrably blocks the dependent capability.
- Documents are access-controlled, encrypted and every access is audited.
- Reviewer decisions require elevated authorization, a reason code, and are audited.

### Phase 3 — Accounts and Financial Ledger
The strictest gate in the programme.
- Unbalanced journal entry is impossible: rejected by the domain **and** by a database
  constraint.
- `UPDATE`/`DELETE` on a posted journal entry or line is denied at the database level.
- Balance recomputed from all postings equals the projection, verified continuously and
  under sustained concurrent posting.
- Concurrent postings to the same account produce no lost update (tested at the chosen
  isolation level).
- Hold cannot exceed available balance; release restores availability exactly.
- Reversal produces a new entry referencing the original; the original is byte-identical
  after reversal.
- Trial balance is zero per currency, asserted by an automated job with alerting.
- Posting is idempotent under concurrent identical keys.
- No module other than Ledger can write a posting (enforced by boundary test).

### Phase 4 — Internal Transfers
- Transfer state machine rejects every invalid transition.
- Duplicate submission with the same idempotency key produces exactly one financial effect,
  proven under concurrent submission from two threads.
- Same key with a different payload is rejected with a distinct error, not silently accepted.
- Insufficient funds is a domain outcome with a defined state, not an exception leak.
- Ledger posting and transfer state transition are atomic, or the compensating path is
  implemented and tested.
- Limit and risk seams exist as interfaces with documented default behaviour and no Phase 13
  logic.

### Phase 5 — Payment Infrastructure
- Provider timeout followed by a successful provider outcome is handled correctly and tested.
- `UNKNOWN` is a modelled state with a reconciliation-by-query sweeper that resolves it.
- Duplicate and out-of-order webhooks produce no duplicate financial effect.
- Webhook signature verification and replay-window rejection are tested.
- No provider vocabulary appears in the domain model or the public API contract (verified by
  review and boundary test).
- Raw PAN never persisted anywhere (verified by schema and code review).
- Every provider interaction retains external evidence sufficient for Phase 8.
- Refund cannot exceed the captured amount, including under concurrent partial refunds.

### Phase 6 — Checkout and Merchant Platform
- Cross-merchant data access is impossible (negative tests per endpoint).
- Merchant payable is derived from ledger postings, not a stored mutable field.
- Fee rounding produces no ledger imbalance across a high-volume test batch.
- Payment completing after checkout expiry is handled deterministically.
- Payout destination change requires step-up authorization, four-eyes and is audited.
- Fee schedule version used is pinned per transaction and recorded.

### Phase 7 — Cards, Wallets, A2A and Instant Payments
- At least two rails with materially different finality semantics are implemented.
- Reversal attempted on an irrevocable rail is rejected by the domain, not attempted and
  failed at the provider.
- Rail routing decisions are deterministic, version-pinned and explainable from stored data.
- Dispute lifecycle including duplicate chargeback notification produces correct, single
  financial effects.
- Chargeback on an already-refunded payment is handled without double-debiting.

### Phase 8 — Settlement and Reconciliation
- Every break type in `RECONCILIATION_MODEL.md` is detectable and covered by a test.
- Duplicate settlement file ingestion produces no duplicate matches or postings.
- No code path deletes or overwrites a break; resolution is always a new record plus a
  compensating posting.
- Every match records the rule version and tolerance that produced it.
- Resolution above threshold requires four-eyes, a reason code, and is audited.
- Suspense balances are aged, reported and alertable.
- Matching job crash mid-batch resumes without duplicate or lost matches.

### Phase 9 — FX and Cross-Border Payments
- Trial balance is zero **per currency**, including after conversions.
- Rounding residual is explicitly posted to a designated account; no value is created or
  destroyed across a high-volume conversion test.
- Expired or stale quotes are rejected; expiry is a modelled event.
- Rates are server-authoritative; client-supplied rates are rejected.
- Currencies with 0, 2 and 3 minor units are all covered by tests.

### Phase 10 — Credit Decisioning
- Replaying a stored decision's inputs against its pinned policy version reproduces the
  identical outcome and reason codes.
- Every decline carries reason codes sufficient for adverse-action explanation.
- Bureau access without recorded consent is rejected and tested.
- Policy changes require four-eyes and are audited; active policy version is always
  identifiable for any point in time.
- Decisions are immutable.

### Phase 11 — Lending
- Accrual is idempotent per period: rerunning produces no additional accrual, proven under
  crash-and-restart.
- Amortisation schedule totals reconcile exactly to principal plus interest with zero
  rounding leakage.
- Repayment allocation order is versioned, explicit and tested including partial and
  overpayment.
- Month-end, leap-year and day-count edge cases are covered.
- Every loan balance is derivable from ledger postings.

### Phase 12 — BNPL
- Refund at every point in the instalment lifecycle produces correct, value-preserving
  adjustments.
- Full refund closes the agreement and leaves no residual obligation or stranded value.
- Chargeback plus refund on the same order cannot double-credit the customer.
- Merchant financing and customer obligation are created atomically or with a tested
  compensating path.
- Three-way reconciliation (merchant settlement, customer obligation, ledger) balances.

### Phase 13 — Risk, Fraud and AML
- Fail-safe behaviour on risk-service unavailability is explicit per operation and value
  band, and is tested.
- A blocked money movement produces correct compensating postings and strands no value.
- Velocity counters are correct under concurrency and survive counter-store loss safely.
- Rule set version used is recorded on every decision.
- Manual override requires reason codes, four-eyes above threshold, and is audited.
- AML case detail is not exposed on any customer-facing surface (tipping-off control).

### Phase 14 — Accounting and Financial Reporting
- Trial balance zero per currency across the entire posting history, continuously verified.
- Any GL figure drills down to the individual journal lines that compose it.
- Posting into a closed period is rejected; prior-period adjustment path is implemented.
- Reports are reproducible: regenerating a past report produces identical output.
- Unreconciled breaks and suspense balances are explicitly disclosed at close.
- No reporting component holds write access to ledger tables (verified by privilege review).

### Phase 15 — Production Hardening
- Threat model complete; identified critical and high risks mitigated or formally accepted.
- Dependency, container and secret scanning green in CI.
- SLOs defined for every critical flow with alerting and linked runbooks.
- Every runbook rehearsed at least once with the result recorded.
- Audit trail verified complete against the defined list of auditable actions.
- Backup restore rehearsed with post-restore trial-balance verification passing.
- Distributed tracing covers customer → request → domain → provider → ledger → settlement →
  reconciliation for at least one real flow.

### Phase 16 — Scale, Resilience and Disaster Recovery
- Load test at target volume with financial invariants asserted **during** load, not only
  after.
- Chaos experiments (node loss, DB failover, broker outage, cache loss, network partition)
  each leave the ledger correct.
- Measured RPO and RTO meet documented targets.
- Post-DR verification procedure executed successfully: full reconciliation and trial
  balance before money movement resumes.
- Any service extraction or partitioning decision is backed by measurement and an ADR.
- Read replicas provably never serve authoritative financial reads.
