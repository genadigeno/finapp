# ADR-0107 — The `risk` bounded context: one module for risk, fraud, AML and case management, none of the money, every other domain through ports

Status: Accepted (2026-10-27, `P13-DOC-001` — read against the code and corrected first)
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
   | `MonitoredLedger` | the ledger's journal entry store, ordered by its sequence, read from a watermark *(P13-DOC-001, 2026-10-27: the journal has no sequence column - it is walked by its UUIDv7 id, instance-minted (ADR-0013), behind a settle horizon on the database clock (`JournalEntryStore.entriesAfter`, `app`'s `LedgerMonitoredLedger`); ADR-0111 decision 1's note)* | the monitoring intake (ADR-0111) |
   | the action ports, one per owning domain | identity's suspend / reinstate / revoke-sessions, accounts' suspension, merchant's suspension, crossborder's beneficiary block, payments' held-withdrawal release and denial, kyc's screening request *(P13-DOC-001, 2026-10-27, as built: identity's (`IdentityActions`), merchant's (`MerchantActions`) and payments' (`HeldWithdrawalActions`, and `HeldPaymentActions` for the held card payment) are implemented; accounts has no suspension command and crossborder no beneficiary block, so `AccountActions` and `BeneficiaryActions` stay declared and unimplemented and their kinds unavailable (`ActionKind.available()`; `PHASE_13_PLAN.md` §1.2 item 24); kyc's screening request is no action port - `PartyRescreening` is the rescreening leg's (the row below) and `RESCREEN_PARTY` is never proposed or dispatched (item 93))* | downstream actions (ADR-0112) |
   | kyc's screening ports | `CounterpartyScreenings`; the case-bound party screening *(as built by `P13-TSK-022`, 2026-10-26, widened by the owner's decision O1 "add the kyc door": `PartyRescreening` reshaped - kyc asked under the permit's key `risk.rescreen:<id>` (and its retry ordinal `#n`) - and implemented by `app`'s `KycPartyRescreening` through **kyc's re-screening door**, `VerificationRunService.rescreen`: a post-decision `RESCREEN` check (kyc `V011`) on the latest case of the party's live `ACTIVE` or `PENDING` customer, keyed by the permit (one key, one question, one provider call under ten instances), its ask stamped on the database's clock - on a decided case evidence beside the decision, never a re-decision (ADR-0035's amendment, `INV-LIFE-04` as amended): the case and the customer do not move, no kyc review task opens; on a case not yet decided its hit routes the case to kyc's review exactly as an onboarding hit (the second review's fail-closed rule). Risk records its own questions' answer only - never another question's - one new question per claim while the last answered "we do not know" or was abandoned mid-call, up to the leg's attempt bound, and judges currency on one clock (asked at or after the activation); a `PENDING` customer with no case is `NOT_ONBOARDED`, never an exposure. `CounterpartyScreenings` (a payee abroad by name, country and payee check) is not used for a party. `ActiveParties` was added beside it, over party's store; `CheckStore.latestDecidedScreenings` lets the screening-hit reading see a rescreen, bounded and judged per type)* | rescreening on list updates (plan §12.10) |
   | identity's acting party | the party of an acting person | every self-dealing refusal (plan §11) |
   | the session's assurance | `MULTI_FACTOR` or not, read for `CHALLENGE` and for step-up | T-eval and T-advise (ADR-0108) |
   | `ScreeningClearances` *(implemented by `P13-TSK-005`, 2026-10-23)* | kyc's `PartyStore`, `KycCaseStore` and `CheckStore` reads: the latest decided `SANCTIONS` or `PEP` check of the party's live customer's latest case — three bounded facts (the check's id, hit or not, decided at), empty where none is decided *(since `P13-TSK-022`'s door: over `CheckStore.latestDecidedScreenings` (its second review's MINOR 6 - per type the decided check asked last, one index probe each) - onboarding checks and list updates' rescreens - judged per type, the decided check asked last of each; a hit if either type's is, so a later clear of one list never masks a hit on the other)* | the screening-hit reading (`-004`) |
   | `MonitoringHints` *(added by `P13-TSK-005`, 2026-10-23; implemented by `-019`)* | the monitoring watermark's permit — a completed movement, announced by its owner, pulls `next_sweep_at` forward in the handler's own transaction; `NONE` until `-019` | the five completion hints (`RiskSignalIntake`) |

   The exact port names are each building task's design; `P13-TSK-001` declared them empty of implementation —
   `RiskActingParty`, `SessionAssurance`, `MonitoredLedger`, `RegulatoryFilingProvider`, `ScreeningClearances`,
   `PartyRescreening`, `IdentityActions`, `AccountActions`, `MerchantActions`, `BeneficiaryActions`,
   `HeldWithdrawalActions` — each free to be reshaped by the task that first implements it. No
   port returns a row of another module; each returns a verdict, a reference or a bounded fact. No score, rule name,
   reason text or note crosses out of `risk` through any of them.
   *(P13-DOC-001, 2026-10-27: the table is incomplete as built. Ports `risk` declares and `app` implements that it does
   not list: `HeldPaymentActions` (payments' held card payment, `P13-TSK-010`; `PaymentsHeldPaymentActions`);
   `RiskInputs` (the session's assurance, kyc's clearance and approval, the merchant's standing, read in T-eval;
   `PlatformRiskInputs`); `RiskSubjectParties` and `RiskPartyIdentities` (the party behind a signal's subject, the
   identities a party holds; `IdentitySubjectParties`, `P13-TSK-007`, `-013`); `MonitoredParties` (the party behind a
   monitored account, `P13-TSK-020`; `AccountMonitoredParties`); and `JudgedCommands` (the assessment census's other
   side, `P13-TSK-023`) - whose implementation, `app`'s `PlatformJudgedCommands`, reads the owners' **tables** directly
   (`transfers.transfer`, `payments.withdrawal`, `payments.payment_intent`, `paymentmethods.payment_method`,
   `ledger.ledger_account`, `crossborder.payment`), ids only on the caller's snapshot: a widening of ADR-0091's
   precedent, where `app`'s statement reads an owner's published definer view - recorded as built.)*

4. **Three capabilities, kept distinct inside one module** (M3's condition):
   - **Fraud decisioning** is synchronous, in the caller's transaction, latency-bounded (plan §7.6; ADR-0108).
   - **AML monitoring** is asynchronous and retrospective — a leaderless sweep over the ledger journal in its own
     transactions (ADR-0111).
   - **Case management** serves both and never decides money (ADR-0110).

   They share the policy model and the engine (ADR-0109), the signal store, the velocity windows, the customer risk
   profile and the case model. M3's split triggers stand and are measured: AML acquiring retention or isolation
   obligations fraud does not share, or the monitoring workload harming the synchronous budget — `P13-TST-001` runs
   the sweep beside the storm and measures T-eval's latency per operation (plan §13.3).
   *(P13-DOC-001, 2026-10-27: **not measured.** `P13-TST-001` is postponed by the owner until after the project's
   phases (`POSTPONED_ISSUES.md` PI-004): no latency figure exists, so the second trigger is unread - T-eval's latency
   is metered (`finapp.risk.evaluation.latency{operation}`) and alerted (`RiskEvaluationLatencyOverBudget`), never
   measured under load beside the sweep; for the owner's ratification (`PHASE_13_PLAN.md` §1.2 item 76). The first
   trigger has not fired as built: the regulatory report sits in the one `risk` schema with no retention or isolation
   obligation of its own - kept for ever until a real jurisdiction's record-keeping rules (C4), a debt row
   (`CURRENT_STATE.md` §Known Architectural Debt; Phase 15).)*

5. **None of the money.** `risk` never posts, never holds, never releases a hold of its own and never writes
   another module's row (`INV-RSK-02`). Its schema holds no balance: `amount_minor` + `currency` appear only as the
   judged amount on `INSERT`-only rows and as window sums on the velocity rows, whose `UPDATE` is the one-statement
   upsert bounded by a `CHECK` that a sum never decreases within a window (plan §8). Privileges: `SELECT, INSERT` on
   facts; conditional `UPDATE` only of status, permit, revision and decision columns on the machine tables; no
   `DELETE` for any role. A decision changes a money movement only because the owning domain consulted the seam in
   its own transaction and committed its own outcome; an action is a keyed command through the owning domain's
   existing door (ADR-0112). Every timestamp is `clock_timestamp()` read after the row lock.
   *(P13-DOC-001, 2026-10-27: the window's guard is a trigger, not a `CHECK` - `risk V005`'s
   `velocity_window_counts_forward`, for every writer: the key, currency and scale never change, the count moves
   forward by exactly one per update, the sum never decreases, and a window is counted only while the database clock
   is inside it.)*

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
   *(P13-DOC-001, 2026-10-27: "raises nothing until a monitoring policy is active" holds for the monitoring rules'
   `AML` alerts alone. Two **fact** alerts are raised in production with no policy active, each its own code its rule:
   `OBLIGATION_DEFAULTED` on a loan's or a BNPL obligation's default, folding into a `COLLECTIONS` case (`P13-TSK-025`,
   ADR-0110 decision 9), and `SCREENING_HIT` on a rescreening hit once an operator has activated a list version,
   folding into the party's `AML` case (`P13-TSK-022`, ADR-0111 decision 8).)*

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
   *(P13-DOC-001, 2026-10-27: no device is received at all - no identity event carries one and no seam adapter sends
   one, so the device subject is `ABSENT` on every movement (`NEW_DEVICE` and the `DEVICE` velocity dimension never
   read `PRESENT`; `PHASE_13_PLAN.md` §1.2 item 4). `DeviceHash` declares the function a producer must mirror - an
   unsalted SHA-256 of the `User-Agent`; a keyed hash before any producer exists is for the owner's ratification
   (item 87).)*

10. **Security** (plan §11; identity `V023`). Six permissions — `RISK_INVESTIGATE`, `RISK_CASE_APPROVE`,
    `RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE`, `AML_REPORT_APPROVE`, `AML_REPORT_FILE` — and three roles,
    `RISK_ANALYST` {`RISK_INVESTIGATE`, `RISK_CASE_APPROVE`}, `RISK_OFFICER` {`RISK_POLICY_ADMINISTER`, `RISK_OVERRIDE`},
    `AML_OFFICER` {`AML_REPORT_APPROVE`, `AML_REPORT_FILE`} — a partition, pairwise-disjoint with each other and with
    every existing role (`INV-AUD-03`; one live role per identity); four eyes inside a role between two holders. Built
    by `P13-TSK-001` (identity `V023`): the plan's §11 first gave every desk the analyst's reads, which the pairwise
    rule refuses - the override door and the report draft door serve what their desks must read under their own
    permissions instead. No customer or merchant route reads any `risk` table
    (`NoCustomerSurfaceReadsRiskTest`); the customer sees the operation's generic outcome (C6).
    *(P13-DOC-001, 2026-10-27: "one live role per identity" is not what identity enforces - identity `V010`'s
    `role_assignment_one_live_per_identity` is unique on (identity, role), one live assignment of each **role**, so one
    identity may hold `RISK_ANALYST`, `RISK_OFFICER` and `AML_OFFICER` at once; the roles' permissions are disjoint, the
    persons are not - for the owner's ratification (`PHASE_13_PLAN.md` §1.2 item 86).)*

11. **Schedules and the advisory namespace.** `RiskMonitoringSchedule` (the intake leg, profile recomputes,
    rescreening permits) and `RiskActionSchedule` (approved actions; dispatched actions awaiting an outcome), both
    leaderless, `SKIP LOCKED`, off in test contexts, each with its gauge and its claim failures metered from the first
    task (the Phase 12 → 13 gate's N5); `LEASE_PROTECTED_SCHEDULERS` 29 → 31. Advisory namespace **13** is `risk`'s,
    keyed `hashtext(operation)` for the policy pin and activation (ADR-0109).
    *(P13-DOC-001, 2026-10-27, as built: `RiskMonitoringSchedule` runs the intake leg (the monitoring rules inside each
    batch's claim, `P13-TSK-020`), the profile leg and the case leg (alerts born before `-016`, folded; `P13-TSK-016`);
    `RiskActionSchedule` runs the action leg (approved and dispatched actions, `-012`'s override leg folded in by
    `-017`), the filing leg (`FILED` reports inquired, `P13-TSK-021`) and the **rescreening** leg - moved from the
    monitoring schedule when the owner's decision O1 gave it kyc's provider calls (`P13-TSK-022`), so the action
    schedule is the one whose legs call out and the monitoring schedule calls no one. `LEASE_PROTECTED_SCHEDULERS` is
    31. Namespace 13 also keys T-list's list lock (A0′, `hashtext('screening_list:' || list)`,
    `JdbcScreeningListStore`), and a second namespace, **131**, is (R2a)'s - one transaction-scoped lock per
    (dimension, subject) a judged movement carries (`P13-TSK-006`; ADR-0108 decision 6's amendment).)*

12. **Would this remain correct with ten instances?** Yes, by construction and by counted test. The module keeps no
    state in process: every arbiter is a row, a unique or a lock named in plan §7.4 — one assessment per judged command
    by `UNIQUE (operation, command_ref)` under the caller's lock; windows by a one-statement upsert; alerts by
    `UNIQUE (subject_ref, rule_code, window_start)`; one open case by a partial unique; the watermark by
    `FOR UPDATE SKIP LOCKED`; the profile by its head row lock and `UNIQUE (party_id, version)`. Risk rows are taken
    **after** every money row and only in two shapes — last inside a caller's transaction, or first in a risk-only
    transaction that takes no money row — so no cycle can close through `risk` (ADR-0108 carries the order and the
    argument). Time is the database's, read after the lock. Each row of §7.4 is a database test owed by its task and
    re-run by the storm with two instances and clocks ±5 s.
    *(P13-DOC-001, 2026-10-27: two readings corrected. **The risk rows of a money transaction** are not (R1)–(R3)
    alone (ADR-0108 decision 6's note): T-eval also births its alerts at (R3)'s position and folds them - the subject's
    open case `FOR UPDATE` (R4), then the alert's attach (R5) - behind its timed savepoint, and a `REVIEW` or `HOLD`
    opens or folds into the party's `FRAUD`/`ATO` case at (R4) through `CaseOpener`, untimed (a failure aborts the
    caller: fail closed) - all inside the caller's transaction, after every money row. No cycle closes - every
    risk-only holder of (R4) takes no money row (`DISTRIBUTED_EXECUTION.md` §3's lock-order row) - but the opening can
    wait on a monitoring batch holding the party's case row, its latency unmeasured: for the owner's ratification
    (`PHASE_13_PLAN.md` §1.2 item 95). **The storm's re-run** with two instances and clocks ±5 s did not happen:
    `P13-TST-001` is postponed (`POSTPONED_ISSUES.md` PI-004). The §7.4 rows' database tests were written by their
    tasks and first run in the owner-authorised database pass (2026-10-27), green after test-only repairs; the
    production censuses and proofs (`AssessmentCensus`, `MonitoringCensus`, `VelocityProof`, `RescreeningCensus`,
    `FilingCensus`, `AlertBornOnceCensus`, `CaseOneOpenCensus`, their gauges and `RiskCensusDiverged`) watch the
    arbiters in operation.)*

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
  phase, not an accident (ADR-0108), and the storm measures it. *(P13-DOC-001, 2026-10-27: it did not - the storm is
  postponed (PI-004); the cost is metered and alerted, unmeasured under load, `PHASE_13_PLAN.md` §1.2 item 76.)*
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
  `-008`…`-011`; `CreditRiskSignal` version 2 with `-007`, which retires the `NOT_ASSESSED` answer. *(P13-DOC-001,
  2026-10-27: version 2 (`RiskCreditRiskSignal`) does not retire it - a party risk has not assessed (no profile, or one
  not yet recomputed) still answers `NOT_ASSESSED`, now under seam version 2; only Phase 10's
  `NotAssessedUntilPhase13` left the composition. `CREDIT_SIGNAL` seals no assessment: credit's snapshot records the
  rating and the profile version.)*
- `BOUNDED_CONTEXTS.md` (contexts 20–23) and `MODULE_ARCHITECTURE.md` (M3, M6) carry the planned entries; each
  building task corrects them as built. `DISTRIBUTED_EXECUTION.md` §3 gains the Phase 13 lock order row (ADR-0108).
- The M3 split triggers are re-read at `P13-DOC-001` against `P13-TST-001`'s latency measurement. *(P13-DOC-001,
  2026-10-27: re-read with no measurement - the storm is postponed (PI-004); decision 4's note records both triggers'
  state, the second for the owner (`PHASE_13_PLAN.md` §1.2 item 76).)*
- **Acceptance.** **As built** (`P13-DOC-001`, 2026-10-27): `Proposed` at the Phase 12 → 13 transition (2026-10-22);
  the Phase 13 review read this ADR against the code, corrected it in place above with dated notes, and accepted it.
