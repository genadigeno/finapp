# ADR-0107 — The `risk` bounded context: one module for risk, fraud, AML and case management, none of the money, every other domain through ports

Status: Proposed
Date: 2026-10-22
Phase: 13
Context: Risk · Fraud · AML (Transaction Monitoring) · Case Management · Platform · every money-moving module, through its seams
Supersedes: nothing. Applies ADR-0001 (a modular monolith; extraction is Phase 16's measured decision) and ADR-0024
(no correctness in process memory; an authoritative check reads durable state in-lock) and `MODULE_ARCHITECTURE.md`
M3 and M6 (fraud and AML share one module, case management is in it). Rests on `PHASE_13_PLAN.md` §1, §1.1, §2.1,
§3.1, §3.2, §7.5, §8, §10, §11 and `INV-RSK-01`, `-02`, `-08`, `-09`.

## Context

1. **The seams exist and answer nothing.** Since Phase 4 the money-moving commands have carried required-parameter
   seams with no defaulted overload — `TransferLimitCheck` and `TransferRiskDecision` (`P4-TSK-010`),
   `CrossBorderLimitCheck` and `CrossBorderRiskDecision` (`P9-TSK-016`) — and credit has consumed a `CreditRiskSignal`
   that answers `NOT_ASSESSED` (`P10-TSK-008`; ADR-0084 §5: the score is `risk`'s, credit never computes one). `app`
   wires `PermitAllUntilPhase13`. Two money-moving commands never had a seam at all: the wallet withdrawal and the
   checkout payment confirmation.
2. **The seam contract is already decided and binding** (plan §2.1): a seam's verdict is two values with no reason,
   the mapping to a committed reason is the caller's; an authoritative check is anchored to durable state read
   through the caller's unit of work, in-lock — never a cache, never process memory (`INV-CON-03`, ADR-0024).
3. **The module question was answered before this phase.** `MODULE_ARCHITECTURE.md` M3 merges fraud decisioning
   and AML monitoring into one module on the condition that the two stay distinct capabilities, with named split
   triggers; M6 places case management there; `ROADMAP.md` Refinement 3 keeps onboarding screening in `kyc` and
   gives ongoing monitoring and rescreening to `risk`. This ADR applies those decisions and records what they cost.
4. **The platform's rules bind the shape**: no shared mutable ownership of authoritative state; Kafka, caches and
   projections are not financial truth; the ledger journal is the one financial truth; every important action is
   auditable with actor, time, target, reason, correlation and outcome.
5. **The named risk.** `DELIVERY_PLAN.md` §Phase 13 §17 names an out-of-process risk call inside a money
   transaction as a single point of failure for all movement. The seams run in-lock, so where the evaluation runs is a
   decision about every money movement's availability, not only about `risk`'s.

## Decision

1. **One new module, `risk`, one schema.** Schema `risk`, Flyway `risk/V001…`, `REVOKE ALL FROM PUBLIC`; it owns
   bounded contexts 20 (Risk), 21 (Fraud), 22 (AML / Transaction Monitoring) and 23 (Case Management) as M3 and M6
   decided. Its ownership table is plan §3.1; nothing in it is co-owned. Canonical names (`GLOSSARY.md`): **Risk
   Assessment** is the record of one evaluation, **Risk Decision** its outcome, **Risk Score** its number, **Alert**
   the born-once observation, **Case** the investigation, **Disposition** its end, **Regulatory Report** the filing
   artefact — never "the SAR" in code; the jurisdiction names it.

2. **Build edges: `risk → platform, sharedkernel` only.** No ledger edge — `risk` posts nothing — and no edge to any
   business module. No module depends on `risk`. `RiskModuleIsolationTest` refuses every business module and `app`
   with planted probes; every sibling's isolation test refuses `risk`. The consumers' seams stay declared where they
   are (`transfers.TransferLimitCheck`, `crossborder.CrossBorderRiskDecision`, `credit.CreditRiskSignal`, payments'
   new `WithdrawalRiskDecision` and `PaymentRiskDecision`) and are implemented in `app` as adapters
   (`RiskTransferRiskDecision`, `RiskWithdrawalRiskDecision`, …; plan §12.1) over `risk`'s one channel-neutral
   evaluation API. The seams' Phase 4 contract — two values, no reason — is unchanged; the richer decision lives in
   `risk`'s assessment and the adapter maps it (ADR-0108).

3. **Every other domain is reached through ports `risk` declares and `app` implements** (the crossborder, lending
   and bnpl precedent), each in the caller's unit of work where the call is part of a transaction:

   | Port (declared by `risk`) | Over | Used by |
   |---|---|---|
   | `MonitoredLedger` | the ledger's journal entry store, ordered by its sequence, read from a watermark | the monitoring intake (ADR-0111) |
   | the action ports, one per owning domain | identity's suspend / reinstate / revoke-sessions, accounts' suspension, merchant's suspension, crossborder's beneficiary block, payments' held-withdrawal release and denial, kyc's screening request | downstream actions (ADR-0112) |
   | kyc's screening ports | `CounterpartyScreenings`; the case-bound party screening *(as built by `P13-TSK-022`, 2026-10-26: `PartyRescreening` reshaped - kyc asked under the permit's key `risk.rescreen:<id>`, kyc's answer as of now - and implemented by `app`'s `KycPartyRescreening` over the case-bound screening as `app` reaches it today: the latest decided `SANCTIONS` or `PEP` check of the party's live `ACTIVE` customer's latest case, in flight, none, or the party inactive, on a read-only snapshot of its own; risk records it only when kyc decided it at or after the list version's activation. kyc never re-asks an answered question and a list update's re-verification is a new case it does not open for an `ACTIVE` customer, so the request binds nothing until kyc grows a re-screening door - an owner item; `CounterpartyScreenings` (a payee abroad by name, country and payee check) is not used for a party. `ActiveParties` was added beside it, over party's store)* | rescreening on list updates (plan §12.10) |
   | identity's acting party | the party of an acting person | every self-dealing refusal (plan §11) |
   | the session's assurance | `MULTI_FACTOR` or not, read for `CHALLENGE` and for step-up | T-eval and T-advise (ADR-0108) |
   | `ScreeningClearances` *(implemented by `P13-TSK-005`, 2026-10-23)* | kyc's `PartyStore`, `KycCaseStore` and `CheckStore` reads: the latest decided `SANCTIONS` or `PEP` check of the party's live customer's latest case — three bounded facts (the check's id, hit or not, decided at), empty where none is decided | the screening-hit reading (`-004`) |
   | `MonitoringHints` *(added by `P13-TSK-005`, 2026-10-23; implemented by `-019`)* | the monitoring watermark's permit — a completed movement, announced by its owner, pulls `next_sweep_at` forward in the handler's own transaction; `NONE` until `-019` | the five completion hints (`RiskSignalIntake`) |

   The exact port names are each building task's design; `P13-TSK-001` declared them empty of implementation —
   `RiskActingParty`, `SessionAssurance`, `MonitoredLedger`, `RegulatoryFilingProvider`, `ScreeningClearances`,
   `PartyRescreening`, `IdentityActions`, `AccountActions`, `MerchantActions`, `BeneficiaryActions`,
   `HeldWithdrawalActions` — each free to be reshaped by the task that first implements it. No
   port returns a row of another module; each returns a verdict, a reference or a bounded fact. No score, rule name,
   reason text or note crosses out of `risk` through any of them.

4. **Three capabilities, kept distinct inside one module** (M3's condition):
   - **Fraud decisioning** is synchronous, in the caller's transaction, latency-bounded (plan §7.6; ADR-0108).
   - **AML monitoring** is asynchronous and retrospective — a leaderless sweep over the ledger journal in its own
     transactions (ADR-0111).
   - **Case management** serves both and never decides money (ADR-0110).

   They share the policy model and the engine (ADR-0109), the signal store, the velocity windows, the customer risk
   profile and the case model. M3's split triggers stand and are measured: AML acquiring retention or isolation
   obligations fraud does not share, or the monitoring workload harming the synchronous budget — `P13-TST-001` runs
   the sweep beside the storm and measures T-eval's latency per operation (plan §13.3).

5. **None of the money.** `risk` never posts, never holds, never releases a hold of its own and never writes
   another module's row (`INV-RSK-02`). Its schema holds no balance: `amount_minor` + `currency` appear only as the
   judged amount on `INSERT`-only rows and as window sums on the velocity rows, whose `UPDATE` is the one-statement
   upsert bounded by a `CHECK` that a sum never decreases within a window (plan §8). Privileges: `SELECT, INSERT` on
   facts; conditional `UPDATE` only of status, permit, revision and decision columns on the machine tables; no
   `DELETE` for any role. A decision changes a money movement only because the owning domain consulted the seam in
   its own transaction and committed its own outcome; an action is a keyed command through the owning domain's
   existing door (ADR-0112). Every timestamp is `clock_timestamp()` read after the row lock.

6. **What `risk` owns and what it does not** (plan §3.1). Owned: the signal (born once per source event), the
   policy version and its rules, the assessment with its score and decision, the override, the velocity window, the
   customer risk profile (versioned, append-only; `INV-RSK-10`), the monitored transaction (born once per journal
   entry), the alert, the case, the investigator action, the disposition, the escalation, the risk action's record,
   the screening list version and the regulatory report. Not owned: the Screening Result (`kyc`'s — `risk` reads
   clearances and records a signal); the transfer, withdrawal, payment, cross-border payment, BNPL application and
   loan (their modules consult the seams and keep their lifecycles); the targets of actions (identity, account,
   merchant, beneficiary); collections operations (a later collections context — C3 opens a case only); write-off
   and provisioning (`accounting`, Phase 14).

7. **Production ships closed to judging** (plan §1.1). Until a policy version is activated under four eyes and
   `OPERATIONS_RUNBOOK.md` §9's activation gate is passed, every seam answers `ALLOW` with reason `NO_POLICY_ACTIVE`,
   recorded as an assessment — the control ran and had no policy — so the first activation changes behaviour and not
   the shape of any record. The monitoring sweep runs from the first deployment and raises nothing until a monitoring
   policy is active. The filing adapter is simulated (ADR-0111).

8. **Events and consumption** (plan §10). Outbox, the platform envelope, producer `risk`, event version 1, additive
   evolution, every event in its state change's transaction. No event carries a reason text, a score, a rule name, a
   note or any PII — ids, codes and statuses only (`INV-RSK-08`). `TransactionBlocked` is not a risk event: the
   blocked movement's owner announces its own outcome and `RiskDecisionMade` carries the judgement. Events from
   identity, kyc, accounts, crossborder, merchant, lending, bnpl, transfers and payments are consumed through
   `InboxEventHandler`s named `risk.<component>` as signal sources and as hints that pull a sweep's permit forward —
   never for correctness; the sweep reads rows. *(As built by `P13-TSK-005`, 2026-10-23: every handler is
   `app`'s `RiskSignalIntake` under the one consumer name `risk.signals`, one instance per event type; only events
   that exist are consumed — identity's `CredentialChanged`, `RecoveryInitiated`, `RecoveryCompleted`,
   `IdentitySuspended`, `accounts.AccountOpened`, crossborder's `BeneficiaryRegistered` / `Activated` / `Blocked`,
   `merchant.MerchantOnboarded`, lending's and bnpl's delinquency and default events as signals; the five completions as
   hints recording no signal (the journal's `monitored_transaction` is the movement's record). The handler reads the
   payload — the first consumer to — through `EventPayload.fields`, the writer's exact inverse, and records what the
   event said and nothing read from the producer's tables; the events name their own aggregate, never the party, so
   `SubjectKind` gained `IDENTITY` and `CREDIT_AGREEMENT`. `RiskSignalRecorded` carries the signal id, kind, subject
   kind and reference, caused by the consumed event. The plan's §10 erratum records what it named that does not exist.)* *(Extended by `P13-TSK-014`, 2026-10-25:
`payments.RefundCompleted`, `payments.ChargebackReceived` and `bnpl.BnplApplicationSubmitted` consumed as signals about the
`PARTY`; payments' two name only the refund or the dispute and the intent, so their payer and merchant are attributed
from risk's **own** sealed assessment of that payment - still nothing read from the producer's tables - and a payment
risk never judged records none. `RiskSignalRecorded` unchanged: it names the party, never the merchant or the intent.)*

9. **Data classification** (plan §8). Assessment inputs and rule hits `CONFIDENTIAL`; signal attributes at their
   source's level (`device` `RESTRICTED-PII` — only identity's hash of the device description is ever received, OQ-4);
   case notes and evidence references `CONFIDENTIAL`, screened for instrument shapes; the report narrative
   `RESTRICTED-PII`; nothing in `risk` is `INTERNAL` except identifiers and timestamps.

10. **Security** (plan §11; identity `V023`). Six permissions — `RISK_INVESTIGATE`, `RISK_CASE_APPROVE`,
    `RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE`, `AML_REPORT_APPROVE`, `AML_REPORT_FILE` — and three roles,
    `RISK_ANALYST` {`RISK_INVESTIGATE`, `RISK_CASE_APPROVE`}, `RISK_OFFICER` {`RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE`},
    `AML_OFFICER` {`AML_REPORT_APPROVE`, `AML_REPORT_FILE`} — a partition, pairwise-disjoint with each other and with
    every existing role (`INV-AUD-03`; one live role per identity); four eyes inside a role between two holders. Built
    by `P13-TSK-001` (identity `V023`): the plan's §11 first gave every desk the analyst's reads, which the pairwise
    rule refuses - the override door and the report draft door serve what their desks must read under their own
    permissions instead. No customer or merchant route reads any `risk` table
    (`NoCustomerSurfaceReadsRiskTest`); the customer sees the operation's generic outcome (C6).

11. **Schedules and the advisory namespace.** `RiskMonitoringSchedule` (the intake leg, profile recomputes,
    rescreening permits) and `RiskActionSchedule` (approved actions; dispatched actions awaiting an outcome), both
    leaderless, `SKIP LOCKED`, off in test contexts, each with its gauge and its claim failures metered from the first
    task (the Phase 12 → 13 gate's N5); `LEASE_PROTECTED_SCHEDULERS` 29 → 31. Advisory namespace **13** is `risk`'s,
    keyed `hashtext(operation)` for the policy pin and activation (ADR-0109).

12. **Would this remain correct with ten instances?** Yes, by construction and by counted test. The module keeps no
    state in process: every arbiter is a row, a unique or a lock named in plan §7.4 — one assessment per judged command
    by `UNIQUE (operation, command_ref)` under the caller's lock; windows by a one-statement upsert; alerts by
    `UNIQUE (subject_ref, rule_code, window_start)`; one open case by a partial unique; the watermark by
    `FOR UPDATE SKIP LOCKED`; the profile by its head row lock and `UNIQUE (party_id, version)`. Risk rows are taken
    **after** every money row and only in two shapes — last inside a caller's transaction, or first in a risk-only
    transaction that takes no money row — so no cycle can close through `risk` (ADR-0108 carries the order and the
    argument). Time is the database's, read after the lock. Each row of §7.4 is a database test owed by its task and
    re-run by the storm with two instances and clocks ±5 s.

## Alternatives Considered

### A rules engine in `platform`
Pros: one cross-cutting mechanism every module could call directly, no ports.
Cons: the engine is a domain capability with versioned policy, cases and regulatory obligations, not a mechanism;
`platform` is depended on by everything and must own no business lifecycle (M4's argument). Refused.

### Risk evaluation as a remote service with a latency budget
Pros: isolates the evaluation's cost from the money transaction's connection; the microservice shape the phase's
name suggests.
Cons: the seams are in-lock; an out-of-process call inside a money transaction is a single point of failure for all
movement (`DELIVERY_PLAN.md` §Phase 13 §17). In the modular monolith the evaluation is a method on the caller's
connection, the budget is a statement timeout on `risk`'s reads, and a breach becomes a recorded fail-safe decision
rather than an outage (ADR-0108). Refused for Phase 13; extraction is Phase 16's measured decision (ADR-0001).

### Separate `fraud` and `aml` modules
Cons: both need the engine, the signals, the windows and the case model; two schemas would hold two copies of one
subject's history. Refused by M3, whose split triggers are kept and measured instead.

### Velocity counters in Redis
Pros: cheap increments, no row contention on the money path.
Cons: a counter that can be lost is not an authority, and a counter outside the money transaction can be passed
twice by two concurrent movements (`INV-CON-03`; the seam contract). Refused: windows are rows upserted in the judged
command's transaction (ADR-0108, `INV-RSK-03`).

### A ledger edge, so `risk` can place and release its own holds
Cons: a second poster of a wallet's hold, outside the owning domain's lock and lifecycle; "risk moves no money"
would be a convention rather than a build rule. Refused: a `HOLD` is the withdrawal's own committed state with its
own hold kept, and a release is payments' command (ADR-0108, ADR-0112; `INV-RSK-02`).

## Consequences

Positive:
- One channel-neutral judgement behind five seams; the seam contract and every consumer's lifecycle are unchanged.
- "Risk moves no money" is mechanical: no ledger edge, no money column, no `DELETE`, no write to another schema.
- The three capabilities can be split later along M3's triggers without moving any other module's rows.

Negative:
- Every money movement pays T-eval's cost on its own connection; the budget and its breach are a decision of this
  phase, not an accident (ADR-0108), and the storm measures it.
- A wide port surface in `app` — one port per owning domain for actions, the ledger port, kyc's ports — each landing
  with the task that first needs it.
- Production judges nothing until an activation; an accepted limitation recorded up front (plan §1.1).

Operational impact: two leaderless schedules with gauges and claim-failure meters; advisory namespace 13; the
activation gate in `OPERATIONS_RUNBOOK.md` §9; `finapp.risk.*` meters with `rule` added to the tag allow-list (C9).
Security impact: six permissions, three disjoint roles; self-dealing fails closed; no customer surface reads `risk`;
evidence held as references resolved through audited reads.
Financial impact: none directly — `risk` posts nothing. Indirectly, every refused, held or challenged movement is the
owning domain's committed outcome with its reserved reason.

## Invariants / Constraints

`INV-RSK-01` (one sealed assessment per judged command), `INV-RSK-02` (risk moves no money and writes no other
domain's row), `INV-RSK-08` (tipping-off), `INV-RSK-09` (monitored transactions born once from the journal),
`INV-RSK-10` (the profile append-only), `INV-CON-03` (durable authority), `INV-AUD-02`/`-03`/`-04`, `INV-EVT-04`,
ADR-0001, ADR-0024, ADR-0084 §5.

## Follow-up

- `P13-TSK-001` builds the boundary: the module, `risk V001`'s floor (`REVOKE ALL FROM PUBLIC`, no table),
  `RiskModuleIsolationTest`, the closed vocabularies (`RiskOperation`, `RiskDecision`, `RuleKind`, `AlertKind`,
  `CaseKind`, `CaseStatus`, `Disposition`, `ActionKind`, `SubjectKind`, `WindowKind`), `RiskAuditAction` and
  `RiskErrorCode` with their register rows, identity `V023`, `NoCustomerSurfaceReadsRiskTest`, `RiskFlowScope`, the
  sweeper property names and the ports' interfaces declared empty of implementation.
- The ports land with the tasks that first use them: `MonitoredLedger` with `-019`; the action ports with `-017`
  (payments' held-withdrawal release with `-012`); kyc's screening ports with `-022`; the seam adapters with
  `-008`…`-011`; `CreditRiskSignal` version 2 with `-007`, which retires the `NOT_ASSESSED` answer.
- `BOUNDED_CONTEXTS.md` (contexts 20–23) and `MODULE_ARCHITECTURE.md` (M3, M6) carry the planned entries; each
  building task corrects them as built. `DISTRIBUTED_EXECUTION.md` §3 gains the Phase 13 lock order row (ADR-0108).
- The M3 split triggers are re-read at `P13-DOC-001` against `P13-TST-001`'s latency measurement.
- **Acceptance.** `Proposed` at the Phase 12 → 13 transition (2026-10-22); the Phase 13 review (`P13-DOC-001`) reads
  this ADR against the code, corrects it in place with dated notes, and accepts it.
