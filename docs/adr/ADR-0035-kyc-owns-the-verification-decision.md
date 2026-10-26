# ADR-0035 — The KYC context owns the verification decision; Party projects it

Status: Accepted

Date: 2026-09-09

## Context

Phase 2 introduces verification: whether a party may be onboarded to the standard a regulator
requires. Phase 1 deliberately left `party.customer.status` at `PENDING` with nothing moving it —
`MeController` records that a permanently-`PENDING` field was withheld from the API for exactly
this reason. Something must now move it, and the question is **who owns the answer**.

The pressure, as with ADR-0029's `users` table, comes from the simplest first story: put a
`verified` boolean on Customer and have the KYC flow set it. The cost arrives later and is
structural — the decision's evidence, its reproducibility and its lifecycle live in one context
while the authoritative answer lives in another, and by Phase 3 financial capabilities are gating
on a field whose provenance nobody can state.

## Decision

**The KYC/KYB decision is owned by the `kyc` context, as an immutable record on the case.**
`party` holds only a **projection** of the current verification outcome — updated by reacting to
the decision, never computed there, never edited there, and never authoritative for a dispute.
When a later phase asks *"may this party transact?"*, the authoritative answer is the `kyc`
context's current decision; the projection exists so the hot path need not join into `kyc`.

Concretely:

- `kyc.kyc_case` carries the lifecycle; the **decision** is a separate immutable record
  (`INV-KYC-02`) referencing the case, the evidence, the policy basis and the deciding actor.
- `party.customer.status` transitions (`PENDING → ACTIVE`/`REJECTED`) are performed by an `app`
  orchestration reacting to the recorded decision, in the same transaction that records it —
  the registration precedent (`P1-TSK-006`): `app` orchestrates, each module writes its own rows.
- The projection may lag conceptually but not operationally in Phase 2, because the transaction
  is shared; if a later phase moves the projection behind events, `INV-BAL-05`'s rule applies —
  a decision is never made from a projection whose staleness is unbounded.

## Alternatives Considered

### A status field on Customer, written by the KYC flow
Pros: simplest; no projection machinery.
Cons: two writers for one aggregate's status across a module boundary — exactly the shared
mutable ownership `CLAUDE.md` forbids; the decision separates from its evidence; a reviewer
correcting a case cannot re-derive what the customer status should be.

### Customer status queried live from `kyc` on every use
Pros: one authority, no projection.
Cons: every future financial operation joins into `kyc`'s schema or calls its API on the hot
path; couples module availability; and Phase 3+ will gate *every* money movement on it.

### Chosen: decision in `kyc`, projection on Customer
Pros: one authority with evidence attached; hot path reads its own module; disputes replay the
case, not a boolean.
Cons: two representations that can drift — accepted because the shared transaction closes the
window in Phase 2 and a reconciling test asserts they agree.

## Consequences

Positive: the decision is attributable, immutable and reproducible from retained evidence;
Customer keeps single ownership of its own status transitions.
Negative: a reconciliation obligation between decision and projection, carried by test.
Security impact: reviewer actions concentrate in one privileged surface in `kyc`.
Financial impact: none in Phase 2; Phase 3 inherits a queryable onboarding gate.

## Invariants / Constraints

`INV-KYC-02`, `INV-KYC-05`, `INV-LIFE-02`, `INV-LIFE-04`, `INV-HIST-02`.

## Follow-up

When the projection moves behind events (first phase that needs it), the staleness bound must be
stated and monitored (`INV-BAL-05`'s shape).

## Amendment — a list update re-screens beside the decision (`P13-TSK-022`, 2026-10-26)

*The owner's decision O1, "add the kyc door"; the coordinator's default for its semantics and, from the task's second
review, the coordinator's fail-closed rule for an undecided case - both to be ratified by the owner at `P13-DOC-001`.*
When a sanctions or PEP list changes, kyc's decided party must be screened against it - and kyc never re-asks an
answered question, while a new case would re-run identity and documents, need fresh consent, be visible to the customer
and could not be decided for an `ACTIVE` customer. So the decision this ADR places in `kyc` gains one neighbour, never
a rival:

- **A rescreen check.** `CheckPurpose.RESCREEN` (kyc `V011`): a `SANCTIONS` or `PEP` check asked on the customer's
  latest case under risk's permit key and an ordinal (`verification_check_rescreen_once`: one key, one question), its
  ask stamped on the database's clock, dispatched and answered by the run's own choreography
  (`VerificationRunService.rescreen`: the dispatch committed, the provider asked holding no connection, the outcome with
  its evidence and its trail). It never answers a run's required type, never holds a run in flight and never spends its
  retry budget (the run's reads and the one-in-flight index name `purpose = 'ONBOARDING'`), so a stranded rescreen can
  never deadlock an onboarding. The purpose and the key are frozen (`UPDATE` narrowed to the machine's two columns).
- **On a case not yet decided, its hit needs a person - fail-closed** (the second review's IMPORTANT 1,
  `INV-KYC-04`). The assessment of an undecided case reads every check (`CheckStore.allForCase`): a rescreen `HIT` blocks
  the automatic approval, opens its review task and moves the case to `IN_REVIEW` exactly as an onboarding `HIT` does
  (`ChecksAssessment.of`, `needingReview`); the review door shows it with its purpose; the decision's evidence names
  every rescreen answered `CLEAR` or `HIT` before it (`ChecksAssessment.decisionEvidence`, `INV-KYC-02`). The rescreen
  path assesses after its outcome commits (`CaseAssessment.assessRescreened`, on the run's path and the provider's late
  callback alike). On a `READY_FOR_DECISION` case - which cannot move back to review - the task opens and the
  reviewer's decision names the hit, the late onboarding hit's precedent. A rescreen in flight or answered "we do not
  know" neither holds nor proceeds the case: the HIT reaches a person whichever side of the decision it lands
  (below), and holding on it would let a stranded rescreen deadlock an onboarding. A hit that commits while a concurrent
  assessment decides the case is ordered after that decision - the decision rests on exactly what its own transaction
  read - and lands beside it.
- **On a decided case, evidence beside the decision, never a re-decision.** The case's status, the decision and the
  customer's relationship do not move; no review task opens (`assessRescreened` assesses nothing on a terminal case,
  and a decided case's re-assessment reads its onboarding checks, `CheckStore.forCase`, as it always has).
- **The hit is risk's to work.** Risk's rescreening records it as a `SCREENING_HIT` signal and raises the `SCREENING_HIT`
  fact alert - one per party and list version - folded into the party's `AML` case; on a decided case the person who
  works it is risk's investigator, not kyc's reviewer. Whether a sanctions match on a decided party should also open kyc
  work is the owner's to ratify at `P13-DOC-001`.
- **Read where it matters.** `CheckStore.latestDecidedScreenings` - per screening type the decided check asked last,
  onboarding or rescreen, one index probe per type - is risk's screening-hit reading (`KycScreeningClearances`, judged
  per type), so T-eval judges a party a list update found as a hit from the moment kyc decided it.

`INV-LIFE-04`, `INV-KYC-02` and `INV-KYC-04` carry the amendment; a changed circumstance of the *person* still opens a
new case.
