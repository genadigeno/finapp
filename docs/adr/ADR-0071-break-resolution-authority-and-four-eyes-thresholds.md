# ADR-0071 — Break resolution authority and four-eyes thresholds

Status: Proposed (2026-09-28, the Phase 7 → 8 transition)
Date: 2026-09-28
Phase: 8
Context: Reconciliation · Ledger · Identity · Settlement
Supersedes: nothing. Writes the Phase 8 decision `docs/adr/README.md` anticipates — "Break
resolution authority and four-eyes thresholds" — and defines the threshold that `INV-REC-03`
("four-eyes above threshold"), `INV-AUD-04` ("break resolutions above threshold") and `INV-REV-04`
("above defined thresholds") each leave undefined. Extends `P3-TSK-021`'s adjustment four-eyes
(ledger `V010`, `AdjustmentService`) with an origin and a closed reason code (ledger `V015`)
without changing its unconditional threshold. Adds two roles to ADR-0031's model under
`RoleName`'s pairwise-disjoint doctrine. Amends `INV-REC-03`, `INV-REV-04`, `INV-AUD-04` and
`INV-REC-05`. Builds on ADR-0065 (the accounts a resolution posts to), ADR-0068 (the rule set and
the `MANUAL` decision), ADR-0069 (the break, its types and `EVIDENCED` as a stored resolution) and
ADR-0070 (suspense ownership).

The decision in one sentence: a break closes only by evidence or by a template-bound, reason-coded
resolution posted through the ledger's own adjustments; two people decide whenever value is at
issue or the resolution posts, and reconciled positions take no free adjustment. *(The title was
this sentence until the Phase 7 → 8 transition's consistency review, B10, gave it the anticipated
title.)*

## Context

ADR-0069 decides what a break is and ADR-0070 decides who owns parked value. This ADR decides how a
break **ends**: who may close it, by which act, with which ledger lines, under what reason, and
when two people must agree. Every resolution that posts moves money out of a counterparty's
position or out of suspense and into profit or loss, a customer's wallet or a merchant's payable.
It is the most profitable act available to an insider in Phase 8, and the delivery plan asks for
it directly: "four-eyes, reason codes, value thresholds" (`DELIVERY_PLAN.md` Phase 8 §9); "four-eyes,
a reason code and an audit above threshold" (`PHASE_GATES.md` Phase 8 exit criteria).

The repository already fixes most of the shape, and leaves three holes:

- **One four-eyes implementation exists** (`P3-TSK-021`): an initiator proposes and nothing posts;
  a different `LEDGER_ADJUST` holder approves and the `ADJUSTMENT` entry posts in the approval's
  own transaction. Approver ≠ initiator holds at the domain, as `V010`'s CHECK
  `adjustment_proposal_approver_is_not_initiator`, and through the deferred trigger
  `adjustment_entry_is_approved`, which refuses any `ADJUSTMENT` entry without an `APPROVED`
  proposal. `adjustment_proposal_permits_only_decision` freezes every payload column by name, so the
  approver approves what they read.
- **Its threshold is every adjustment, deliberately.** "A threshold is a per-currency amount
  policy — a versioned artefact … a de-minimis threshold arrives later as its own pinned policy
  artefact, with the proposal row as its seam" (`AdjustmentService.java:34-39`; `LEDGER_MODEL.md`
  §6).
- **Hole 1: there are no reason codes anywhere.** The adjustment's reason is free prose, although
  `INV-REV-04` says "records a reason code".
- **Hole 2: the generic door approves anything.** `AdjustmentController` exposes `POST
  /v1/ledger/adjustments`, `GET /{id}`, `POST /{id}/approval` and `DELETE /{id}`, all under
  `LEDGER_ADJUST`, and the approval accepts any proposal id (the transition's finding F-a). A
  proposal a reconciliation flow created could be approved around the break's own checks.
- **Hole 3: free adjustments reach reconciled positions.** Nothing stops a manual adjustment on
  `SETTLEMENT_CLEARING` or `SUSPENSE_UNMATCHED`. Three existing suites do exactly that
  (`AdjustmentEndpointDatabaseTest`, `AdjustmentProposalSchemaDatabaseTest`,
  `StatementEndpointDatabaseTest`). Once Phase 8 explains every clearing position by its open
  items (`INV-REC-06`) and gives every suspense unit an owning break (`INV-REC-09`), such an
  adjustment creates unexplained clearing or unowned suspense.
- **Roles.** `RoleName`'s grants are pairwise disjoint on purpose (`RoleName.java:30-35`, pinned by
  `RoleNameTest`'s exact-grant assertions). `LEDGER_OPERATOR` holds `LEDGER_ADJUST` among seven
  money-operating permissions. "A permission exists when a distinct trust decision does", and
  four-eyes is person-distinctness, not a second permission (`P2-TSK-004`, ADR-0056 §2).
- **A known audit debt.** Every session actor is audited as `CUSTOMER`, operators included
  (`CURRENT_STATE.md` Known Architectural Debt, owned by Phase 15). Actor ids are right; the
  population field is not.

The three designs weighed at the transition differed on exactly the undefined parts:

- **A** set a commercial value threshold below which one person suffices, added reason codes
  additively, and refused free adjustments on reconciled positions.
- **B** made every human act four-eyes, added six-eyes above a value band, closed F-a, and made one
  role hold everything; its reason code was a nullable column.
- **C** required four-eyes when value is at issue, made the reason code a required request field
  (which breaks the generic API), and split two overlapping roles.

## Decision

1. **A break ends in one of two ways, and nothing is ever edited or deleted.**
   - **By evidence:** the platform records a zero-residual allocation or offset as an `EVIDENCED`
     resolution naming the decision and the park that explain the break (ADR-0069's amendment of
     `INV-REC-02`). It is the only resolution no person decides.
   - **By a person:** one of seven template-bound kinds, with a closed reason code and a narrative,
     posted — when it posts — through `ledger.AdjustmentService` as a proposal of origin
     `RECONCILIATION`.

   A counterparty's correction is new evidence (a `COUNTERPARTY_ADJUSTMENT` line in a later
   batch), never an edit. A reversal is used only by repudiation, through `ReversalService`
   (`INV-REV-01`). An adjustment on a reconciled position happens only through a resolution
   (point 7). No resolution originates an external movement: return-to-sender of unattributed
   funds is a new payment capability, deferred.

   **The Resolution** is an aggregate of `reconciliation`: `reconciliation.resolution` plus
   `resolution_event` (reconciliation `V006`, `P8-TSK-012` for the platform's kind,
   `P8-TSK-015` for the person's kinds; the `REPUDIATE_BATCH` kind and its batch subject arrive
   with reconciliation `V009`, `P8-TSK-023`, beside the external item's `REPUDIATED`, its
   `MATCHED → UNMATCHED` reopening (a bank item of another batch whose allocation named the
   repudiated batch's remittance expectation), the expectation's reopening edges and the
   suspense item's `REPUDIATION` origin — each state with its producer). Its subject is
   **exactly one** of `break_id` or `settlement_batch_id` (the latter for `REPUDIATE_BATCH` only,
   from `V009`).
   *(The migration numbers are the transition's consistency review's, A8: `V008` is
   `P8-TSK-022`'s `run_replay`.)* It carries `kind`,
   `reason_code`, `narrative`, `status`, `four_eyes`, `proposed_amount_*`, `residual_version`,
   `target_account_id`, `offset_item_id`, `chosen_expectation_id`, `proposed_by/at`,
   `decided_by/at`, `adjustment_proposal_id UNIQUE`, `journal_entry_id UNIQUE` and `rule_set_id`
   (the version active for the subject's source when it is proposed). Decision columns are
   written once; there is no `DELETE` grant.

   ```
   PROPOSED ──approve (decided_by ≠ proposed_by when four-eyes)──▶ APPROVED (terminal)
   PROPOSED ──reject (another RESOLVE holder, reasoned)──────────▶ REJECTED (terminal)
   PROPOSED ──withdraw (the proposer; the platform on evidence)───▶ WITHDRAWN (terminal)
   born APPROVED: EVIDENCED (the platform) and a zero-value ACKNOWLEDGE (one person)
   ```

   - **One live proposal per subject:** partial `UNIQUE (break_id) WHERE status = 'PROPOSED'`,
     and the same per settlement batch (from `V009`).
   - **The break's machine follows** (ADR-0069; `SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`): a
     proposal moves the break from `OPEN` or `INVESTIGATING` to `RESOLUTION_PROPOSED`; a rejection
     or a withdrawal returns it to `INVESTIGATING`; an approval moves it to `RESOLVED`, which is
     terminal. `EVIDENCED` reaches `RESOLVED` from `OPEN`, `INVESTIGATING` or
     `RESOLUTION_PROPOSED`. A zero-value `ACKNOWLEDGE` reaches it from `OPEN` or `INVESTIGATING`.
     Proposing against a resolved break is `reconciliation.BreakTerminal`.
   - **`EVIDENCED` is the platform's only.** `CHECK (kind <> 'EVIDENCED' OR (proposed_by = system
     AND status = 'APPROVED'))`. The domain refuses the kind at the door
     (`reconciliation.ResolutionKindNotAllowed`) and the platform proposes no other kind.
   - **A person never resolves a break whose residual is not zero after the resolution's effect**,
     except by `ACKNOWLEDGE`, whose types carry no residual in a position (a timing difference, a
     fee already expensed, a key collision whose expectations both stand).

2. **Eight kinds, each a template. The proposer chooses the kind, the reason code, the narrative
   and — per kind — the target, the offset item or the chosen candidate. The lines are derived
   from the subject's current remainder, never typed.** The request carries no amount and no
   account other than a transfer's target. P is the subject's own position.

   | Kind | Applies to | Lines (entry `ADJUSTMENT`, scope `ledger.adjust.approve:<proposalId>`, unless stated) | Approvers |
   |---|---|---|---|
   | `EVIDENCED` | Any break a zero-residual allocation or offset explains | None of its own: the allocation's unpark or the offset is the posting (`recon-suspense:<parkId>`, a system `POSTING`); the stored resolution names the decision and the park | The platform only |
   | `ACKNOWLEDGE` | `TIMING_DIFFERENCE`, `FEE_MISMATCH`, `DUPLICATE_INTERNAL` | None | 1 when the value at issue is 0; otherwise 2 |
   | `WRITE_OFF` | An INBOUND remainder in P; a DEBIT suspense item | DR `RECONCILIATION_LOSSES` / CR P (or CR `SUSPENSE_UNMATCHED`) | 2 |
   | `TRANSFER_TO_ACCOUNT` | A CREDIT suspense item; an OUTBOUND remainder in P | DR `SUSPENSE_UNMATCHED` (or DR P) / CR a named `CUSTOMER_WALLET` or `MERCHANT_PAYABLE` | 2 |
   | `OFFSET_SUSPENSE` | A CREDIT and a DEBIT suspense item of equal amount and currency | None: `SUSPENSE_UNMATCHED` already nets them; both items released | 2 |
   | `RECOGNISE_GAIN` | A CREDIT suspense item older than the pinned `gain_min_age_days` | DR `SUSPENSE_UNMATCHED` / CR `RECONCILIATION_GAINS` | 2 |
   | `MANUAL_MATCH` | `AMBIGUOUS_MATCH` | A `MANUAL`-origin decision allocates the item to one of its stored candidates; the unpark posts as any late allocation's does (a system `POSTING`) | 2 — it stands in for the engine |
   | `REPUDIATE_BATCH` | An accepted settlement batch proven fabricated or mis-normalised | `ReversalService` on the recognition entry (scope `ledger.reverse`, key `settlement-batch:<batchId>`); append-only counter-allocations; unparks (ADR-0065 §10) | 2 |

   - **A kind's lines decide where it applies.** ADR-0069's per-type table is the one authority on
     which kinds a break type admits: every type that owns suspense lists `WRITE_OFF` (a DEBIT item)
     and `RECOGNISE_GAIN` (a CREDIT item, after the minimum age), except that `REVERSAL_MISMATCH`,
     `REFUND_MISMATCH` and `CURRENCY_MISMATCH` admit no gain *(the transition's consistency review,
     A1)*. A kind listed for a break type is admissible only when the subject has the side its
     lines require, and is refused otherwise (`reconciliation.ResolutionKindNotAllowed`), as is a
     kind the type's row does not list. A CREDIT suspense item is never written off (that
     would be a gain), and a DEBIT item is never recognised as a gain. A statement-cause
     `SETTLEMENT_MISMATCH` has no remainder or suspense item to post against, so only evidence
     closes it.
   - **The whole residual, or nothing.** A resolution disposes of the subject's entire current
     remainder. A partial explanation arrives as evidence and shrinks the remainder first.
   - **The accounts a template can name are closed:** P, `SUSPENSE_UNMATCHED`,
     `RECONCILIATION_LOSSES`, `RECONCILIATION_GAINS`, and a transfer's owned target. No template
     names `CASH_AT_BANK` (cash is never adjusted to fit, `INV-SET-06`), `PROCESSING_COSTS` (a fee
     recovery is the counterparty's rebate line), or any position other than the subject's own
     (`INV-SET-05`, `INV-RAIL-04`: a resolution never moves value between two counterparties).
   - **The transfer's target** is a `CUSTOMER_WALLET` or `MERCHANT_PAYABLE`, in the subject's
     currency, judged at proposal and again at approval (`reconciliation.ResolutionTargetRefused`
     for any other kind or currency). It is read `FOR SHARE` before any posting — the chargeback
     precedent (ADR-0061 §5) — and never upgraded. A target that stopped being postable — a
     merchant closed since, whose close now closes its payable's ledger account (the transition's
     repairs) — fails the approval at `V007` (`409 ledger.AccountNotPostable`) with nothing
     written, and the resolution stays `PROPOSED`. A merchant's breakdown shows the line as
     `reconciliationAttributed` and a customer's statement labels it `RECONCILIATION_ATTRIBUTION`,
     so the counterparty's own record names the act. Both read every payable or wallet line of a
     `RECONCILIATION`-origin `ADJUSTMENT` entry, whatever it faces — ADR-0073 §6's origin rule —
     and `P8-TSK-015` builds them with this kind, its first poster. *(The transition's
     consistency review, A12, A13 and C10.)*
   - **`OFFSET_SUSPENSE`** is the uncorrelated offset. A counterparty correction that names its
     original is offset automatically and resolves `EVIDENCED` (ADR-0068); an offset nothing
     correlates stays with two people. Both items are released and **both owning breaks close in
     the approval**: the offset item's break is locked with the subject's (break rows sorted by
     id, ahead of the resolution row, then both items sorted by id), its `break_event` names this
     resolution, and the offset is refused (`reconciliation.ResolutionTargetRefused`) while that
     break carries a live proposal of its own (ADR-0070 §3).
   - **`RECOGNISE_GAIN`** is judged at proposal and again at approval, in SQL on the database
     clock, against the item's stored `opened_on` and the pinned version's `gain_min_age_days`
     (seeded 90). Before then: `reconciliation.GainNotYetEligible`.
   - **`MANUAL_MATCH`** names `chosen_expectation_id`, which must be a candidate in the item's
     stored `AMBIGUOUS_MATCH` decision snapshot. At approval the allocation goes through
     `allocate(E)` like every other leg, under the source's namespace-4 lock, and its decision
     replays as its recorded choice applied to its snapshot (ADR-0068 §9).
   - **Closing.** An approved resolution that removes an item's parked value moves the item to
     `RESOLVED` and releases its suspense item (a `suspense_release` naming the resolution). One
     that removes an expectation's remainder raises its `resolved_minor` and moves it to
     `RESOLVED_BY_ADJUSTMENT`. The position proof (`INV-REC-06`) holds across both: a `WRITE_OFF`
     credits P and raises `resolved` by the same amount.

3. **The threshold, defined: every resolution with value at issue or a posting is four-eyes.** A
   zero-value, zero-posting `ACKNOWLEDGE` is one person's. `EVIDENCED` is the platform's. There is
   no de-minimis band and no value-banded second approver.
   - In the Phase 8 taxonomy the single-person path is, in practice, exactly the acknowledgement of
     a `TIMING_DIFFERENCE`, whose value at issue is 0. A `FEE_MISMATCH` exceeds a non-negative
     tolerance, and a `DUPLICATE_INTERNAL` carries the colliding expectation's amount, so both need
     two people.
   - **It keeps the ledger's rule rather than carving an exception into it.** `V010` already
     refuses a one-person `ADJUSTMENT` entry, and a lower reconciliation threshold would need a
     carve-out beneath it. It defines `INV-REC-03`'s threshold without introducing a new policy
     artefact.
   - **Why no value band.** A per-currency amount policy is a versioned artefact with nothing to
     calibrate it yet, and a cross-currency one is `INV-MON-04`'s trap
     (`AdjustmentService.java:34-39`, restated). A band also invites structuring: many small
     resolutions under the line. The proposal row and `resolution.four_eyes` stay the seam for a
     future pinned de-minimis or banded policy.
   - **The severity threshold is not an approval threshold.** The rule set's per-currency
     `high_value_minor` (1,000.00 in EUR, GBP and USD in v1, owner decision O7, ADR-0069) escalates
     a break's severity, and so its alert, by one level. It changes nobody's authority.
   - **`four_eyes` is derived, never chosen.** A CHECK binds it: `four_eyes = (kind <> 'EVIDENCED'
     AND NOT (kind = 'ACKNOWLEDGE' AND proposed_amount_minor = 0))`. A raw writer therefore cannot
     turn off the second person by clearing a flag.

4. **Two different people, at every rank the act reaches.**
   1. **The reconciliation domain** refuses an approval by the proposer
      (`reconciliation.SelfApprovalRefused`). Nothing is written, and the resolution stays
      `PROPOSED` for a second person — the ledger's own rule, which `approveOwned` shares.
   2. **`CHECK (status <> 'APPROVED' OR NOT four_eyes OR decided_by <> proposed_by)`** on
      `resolution`, beside point 3's derivation.
   3. **Ledger `V010`** beneath, for the three kinds that post through an adjustment (`WRITE_OFF`,
      `TRANSFER_TO_ACCOUNT`, `RECOGNISE_GAIN`): the ledger proposal carries the same two actor ids,
      so `adjustment_proposal_approver_is_not_initiator` and `adjustment_entry_is_approved` judge
      the same two people.

   The value-bearing kinds that post no adjustment (a non-zero `ACKNOWLEDGE`, `OFFSET_SUSPENSE`,
   `MANUAL_MATCH`, `REPUDIATE_BATCH`) hold at the first two ranks. Distinctness is by actor id. The
   `CUSTOMER` actor-type debt does not weaken it, because the resolution row holds both ids; it is
   flagged for the gate.

5. **Reason codes are closed at both ranks; the narrative is required and never leaves
   reconciliation.**
   - **Reconciliation's codes** are the enum `ResolutionReasonCode`:
     `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR`, `DUPLICATE_BY_COUNTERPARTY`,
     `FUNDS_ATTRIBUTED`, `UNATTRIBUTABLE_AGED`, `TIMING_CONFIRMED`, `FEE_ACCEPTED_AS_CHARGED`,
     `FEE_RECOVERED`, `AMBIGUITY_RESOLVED_BY_EVIDENCE`, `IMMATERIAL_DIFFERENCE`, `LOSS_ACCEPTED`,
     `EVIDENCE_REPUDIATED`, and `EVIDENCE_RECEIVED` (the platform's only). Each kind admits a
     subset, and a code outside it is `reconciliation.ReasonCodeNotAllowed`. The subsets below are
     this ADR's proposal. `P8-TSK-015` pins them with one test per kind, and changing one is a
     reviewed code change.

     | Kind | Admitted reason codes |
     |---|---|
     | `EVIDENCED` | `EVIDENCE_RECEIVED`, which no other kind admits |
     | `ACKNOWLEDGE` | `TIMING_CONFIRMED`, `FEE_ACCEPTED_AS_CHARGED`, `FEE_RECOVERED`, `IMMATERIAL_DIFFERENCE`, `INTERNAL_PROCESSING_ERROR`, `COUNTERPARTY_ERROR_CONFIRMED` |
     | `WRITE_OFF` | `LOSS_ACCEPTED`, `IMMATERIAL_DIFFERENCE`, `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR`, `UNATTRIBUTABLE_AGED` |
     | `TRANSFER_TO_ACCOUNT` | `FUNDS_ATTRIBUTED`, `INTERNAL_PROCESSING_ERROR`, `COUNTERPARTY_ERROR_CONFIRMED` |
     | `OFFSET_SUSPENSE` | `DUPLICATE_BY_COUNTERPARTY`, `COUNTERPARTY_ERROR_CONFIRMED`, `INTERNAL_PROCESSING_ERROR` |
     | `RECOGNISE_GAIN` | `UNATTRIBUTABLE_AGED` |
     | `MANUAL_MATCH` | `AMBIGUITY_RESOLVED_BY_EVIDENCE` |
     | `REPUDIATE_BATCH` | `EVIDENCE_REPUDIATED` |

   - **The narrative** (1..1000 characters, always required) is `CONFIDENTIAL`. It is readable on
     the resolution by `RECONCILIATION_INVESTIGATE` holders, and it is never logged, evented or
     audited in its body — the rule for break notes. The audit record's reason is the kind and the
     reason code. For a posting kind, the ledger proposal's required `reason` (`V010`, and `V004`'s
     `journal_entry_adjustment_has_reason` on the entry) is an identifier-only statement derived by
     reconciliation — the resolution id, its kind and its code — never the narrative, because
     `ledger.AdjustmentProposed` and `ledger.AdjustmentPosted` audit that reason.
   - **Ledger `V015`** (`P8-TSK-006`) gives `adjustment_proposal` two columns, both added with
     defaults:
     - `reason_code`, closed: `MANUAL_CORRECTION`, `RECONCILIATION_WRITE_OFF`,
       `RECONCILIATION_TRANSFER`, `RECONCILIATION_GAIN`, `UNCODED` *(this draft also listed
       `RECONCILIATION_OFFSET`; dropped at `P8-TSK-006`'s design — the recorded input below,
       decided before `V015` shipped)*;
     - `origin`: `MANUAL` | `RECONCILIATION`.

     `ADD COLUMN … DEFAULT` fires no update trigger, so history stays valid and existing rows read
     `UNCODED` / `MANUAL` (`INV-HIST-01`). A `BEFORE INSERT` trigger refuses `UNCODED` on every new
     proposal. `adjustment_proposal_permits_only_decision` names each payload column explicitly, so
     `V015` re-states it to freeze both new columns. The origin and the code agree — a
     `RECONCILIATION_*` code exactly when the origin is `RECONCILIATION` — at the domain and by a
     CHECK that history satisfies. `WRITE_OFF` posts `RECONCILIATION_WRITE_OFF`,
     `TRANSFER_TO_ACCOUNT` posts `RECONCILIATION_TRANSFER`, and `RECOGNISE_GAIN` posts
     `RECONCILIATION_GAIN`.
   - **Additive under ADR-0015.** The generic `POST /v1/ledger/adjustments` assigns
     `MANUAL_CORRECTION` server-side. Its request shape is unchanged, and `INV-REV-04`'s "reason
     code" is realised for every new adjustment.

6. **The resolution, its ledger proposal and its entry are one-to-one, and each door refuses the
   other's proposals.**
   - **Propose** (one transaction, keyed per principal): the resolution row; for a posting kind,
     `AdjustmentService.proposeOwned` with origin `RECONCILIATION`, reference = the resolution id,
     and the derived lines; the break's transition; the audit records. Nothing posts.
   - **Approve** (one transaction): `approveOwned` posts the `ADJUSTMENT` entry; the suspense
     releases, the item or expectation's terminal state, the break's `RESOLVED`,
     `reconciliation.BreakResolved` and the audit records commit with it. Or, for
     `REPUDIATE_BATCH`, `ReversalService` and ADR-0065 §10's steps.
   - **Reject or withdraw:** `rejectOwned` rejects the ledger proposal in the same transaction.
   - **At the database:** `APPROVED` ⇔ the ledger proposal is `APPROVED` ⇔ its entry exists, held
     by `UNIQUE adjustment_proposal_id` and `UNIQUE journal_entry_id` on `resolution` and by
     `V010`'s CHECK and deferred trigger. There are no cross-schema foreign keys (ADR-0064). The
     backstop for a raw `RECONCILIATION`-origin entry that no resolution names is the completeness
     verifier: every clearing and suspense line must be owned by a suspense item or belong to an
     entry reconciliation or settlement knows, a resolution's among them
     (`finapp.reconciliation.line.unattributed`, which must read 0).
   - **Origin refusal (F-a, closed).** The generic `POST /v1/ledger/adjustments/{id}/approval` and
     `DELETE /v1/ledger/adjustments/{id}` refuse a `RECONCILIATION`-origin proposal (`409
     ledger.AdjustmentOriginMismatch`). `approveOwned` and `rejectOwned` refuse a `MANUAL` one. A
     route test and a static test hold both directions. Without this, a `LEDGER_ADJUST` holder
     could post a resolution's lines while the break still stood `RESOLUTION_PROPOSED`, and
     neither the stale check nor the break's state would be consulted.
   - **Who the ledger sees.** The reconciliation roles hold no `LEDGER_ADJUST`. The owned methods
     are module calls made under `RECONCILIATION_RESOLVE`, not routes, and the ledger proposal
     records the reconciliation proposer and approver as its initiator and approver.

7. **Reconciled positions are closed to free adjustments, at the domain and at the database.**
   `AccountPurpose.reconciledPositions()` is the three clearings, `SUSPENSE_UNMATCHED`,
   `CASH_AT_BANK`, `PROCESSING_COSTS`, `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS`. A
   `BEFORE INSERT` trigger on `adjustment_proposal_line` refuses any `MANUAL`-origin line on one of
   them (`V015`; the purpose list re-stated by each of `V016`–`V018` as its purposes arrive), and
   the domain refuses it first (`422 ledger.AdjustmentOnReconciledPosition`). The three suites that
   adjust clearing and suspense move to non-reconciled accounts in `P8-TSK-006`.
   - A free adjustment on a clearing position would change DR−CR with no expectation to explain it,
     and on suspense would create a unit no break owns. Together with point 6's origin refusal, the
     break machine is the **only** path by which a person moves value on a reconciled position.
   - It follows that `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` are posted by nothing but
     approved resolutions: they are reconciled positions, so only a `RECONCILIATION`-origin
     proposal reaches them, and only through `approveOwned`.

8. **The approver approves what was proposed, and a change underneath refuses the approval.**
   - **Frozen at proposal:** `proposed_amount`, the break's `residual_version`, and the ledger
     proposal's lines, with both dates set to `proposed_on`, the proposal's business date. Nothing
     is read from a clock at approval, so a retried or late approval computes the same entry
     fingerprint and converges (ADR-0065 §6's discipline).
   - **`residual_version`** bumps on every allocation, park, release or reclassification that
     touches the break's subject.
   - **At approval** the transaction locks the break, then the resolution, then the subject rows,
     sorted. It re-derives the lines from the current remainder and re-reads the version. If either
     differs from the frozen values: `409 reconciliation.ResolutionStale`, nothing written, the
     resolution still `PROPOSED`. The proposer withdraws and re-proposes, which is two acts again.
     This carries `V010`'s "the approver approves what they read" one level up: the ledger freezes
     the lines, and reconciliation freezes the residual they were derived from.

9. **Evidence wins, and every race has a PostgreSQL arbiter.**

   | Contention | Arbiter | Loser |
   |---|---|---|
   | Two proposals for one subject | partial `UNIQUE (break_id) WHERE status = 'PROPOSED'` (and per batch); the propose key, scope `reconciliation.resolve:<actorType>:<actorId>` (per principal from birth, `X-TSK-003`'s rule) | `409 reconciliation.ResolutionAlreadyProposed`, or a replay of the same key |
   | Ten racing approvals; approve against reject | break `FOR UPDATE` → resolution `FOR UPDATE` → conditional `PROPOSED → APPROVED`; `UNIQUE adjustment_proposal_id`; `V010`'s `UNIQUE journal_entry_id` | The same approver's retry converges on the entry; anyone else gets `409 reconciliation.ResolutionNotPending` |
   | `EVIDENCED` against a pending approval | the break row, taken first by both | First wins. Evidence first: the pending resolution becomes `WITHDRAWN` by the platform and its ledger proposal is rejected through `rejectOwned`, in the evidence's transaction. Approval first: the evidence finds the break `RESOLVED`, and a late line parks as a recovery (`DUPLICATE_EXTERNAL`), closed four-eyes by `RECOGNISE_GAIN` or `TRANSFER_TO_ACCOUNT` |
   | An allocation between proposal and approval | `residual_version` under the break lock | `409 reconciliation.ResolutionStale` |
   | A suspense item released twice | item `FOR UPDATE`; `CHECK released_minor ≤ amount_minor` | 409 |
   | A manual match against the engine | `UNIQUE (external_item_id, expectation_id) WHERE reverses_allocation_id IS NULL`; the deferred Σ triggers | `409 reconciliation.RecordAlreadyMatched` |

   - **Lock order** (DISTRIBUTED_EXECUTION §3's Phase 8 row): (1) advisory namespace 4 for the
     source, when the act allocates, parks or unparks (`MANUAL_MATCH`, `REPUDIATE_BATCH`); (2) break
     rows, then the resolution row; (3) expectation, external item and suspense item rows, each
     sorted by id; (4) the merchant payout row, taken by the return worker only and by no
     resolution; (5) the transfer target's ledger account `FOR SHARE`; (6) inside `approveOwned`,
     the ledger proposal row, then the projection rows sorted by account id. **The posting is
     last.** A transaction posting several entries over shared rows — a repudiation's approval,
     which posts the recognition's reversal and its unparks — pre-locks the union of the
     platform's rows it will touch in the projection's own order before its first posting
     (`PostingService.lockBalancesInOrder`, `DISTRIBUTED_EXECUTION.md` §3's multi-entry lock-order
     rule, which the transition's repairs made for dispute postings, ADR-0061 §5), and takes any
     runtime counterparty's `ACCOUNT` row — `FOR SHARE` where a share lock suffices — before any
     projection row. *(Aligned to the row by the Phase 7 → 8 transition's consistency review, B9;
     the multi-entry rule is the repairs'.)* A transfer's target is a runtime account, share-locked
     at (5) before its entry takes any projection row, so the order never rests on seed order,
     which `DISTRIBUTED_EXECUTION.md` §3 holds load-bearing only for `SETTLEMENT_CLEARING`
     (`V003`). The generic door can lock a reconciliation proposal before refusing it, but it takes
     nothing after that lock, so no cycle exists.
   - **Crash mid-approval:** one transaction; nothing survives.
   - **Would this remain correct if ten instances approved concurrently?** PASS, resting on
     counted ten-way tests (scenario 10, `P8-TST-002`), not claimed by construction.

10. **Authority: four permissions, two pairwise-disjoint roles.**

    | Permission | Trust decision | Held by |
    |---|---|---|
    | `SETTLEMENT_INGEST` | Introducing or attesting evidence that will move money | `RECONCILIATION_OPERATOR` |
    | `RECONCILIATION_INVESTIGATE` | Reading both sides (raw files audited per access) and investigating | `RECONCILIATION_OPERATOR` |
    | `RECONCILIATION_RESOLVE` | Money-moving, template-bound correction under four-eyes, including repudiation | `RECONCILIATION_OPERATOR` |
    | `RECONCILIATION_ADMINISTER` | Deciding what counts as a match (rule sets); reprocessing, requeueing, readmission, the backfill | `RECONCILIATION_CONTROLLER` |

    - `RECONCILIATION_OPERATOR` is admitted by identity `V016` in `P8-TSK-003`, holding the first
      two permissions. `RECONCILIATION_RESOLVE` joins it in `P8-TSK-015`.
      `RECONCILIATION_CONTROLLER` is admitted by identity `V017` in `P8-TSK-007`. Each permission
      arrives with its first route. `RoleNameTest`'s exact grants hold the disjointness.
    - **The split is the point: whoever can loosen a tolerance cannot resolve the breaks it would
      hide.** A controller who widened a fee tolerance or lengthened a grace window cannot then
      close what the change let through. The controller never resolves at all: a blocked run is
      requeued under `ADMINISTER`, and its break closes `EVIDENCED` once the evidence is processed.
    - A person who needs both holds both roles, and that grant is a recorded act in the trail. This
      is the self-elevation limit `RoleName` already states, not a new gap.
    - **`LEDGER_OPERATOR` is not extended**, so the desk that moves money does not reconcile it,
      and the reconciliation roles hold no ledger permission.
    - **Four-eyes is distinct identities, not distinct permissions** (`P3-TSK-021`, ADR-0056 §2).
      The approver holds `RECONCILIATION_RESOLVE` like the proposer. Rejection is any other
      holder's, and it is reasoned. Withdrawal is the proposer's.
    - **Recorded for Phase 15, not enforced here:** that no person holds `LEDGER_OPERATOR` together
      with a reconciliation role, and that the resolver is not the actor of the operation the
      break concerns.

11. **The doors, the audit and the signals carry identifiers, codes and counts — never an amount or
    a narrative.**

    | Operation | Door | Idempotency | Audit |
    |---|---|---|---|
    | Propose | `POST /v1/operator/reconciliation/breaks/{id}/resolutions {kind, reasonCode, narrative, targetAccountId?, offsetItemId?, chosenExpectationId?}` | Key; one live per break | `reconciliation.ResolutionProposed` (reason); `ledger.AdjustmentProposed` for a posting kind |
    | Propose a repudiation | `POST /v1/operator/reconciliation/batches/{settlementBatchId}/repudiation {reasonCode, narrative}` | Key; one live per batch | `reconciliation.ResolutionProposed` |
    | Approve | `POST /v1/operator/reconciliation/resolutions/{id}/approval` | State | `reconciliation.ResolutionApproved` (+ `ledger.AdjustmentPosted`) |
    | Reject | `POST /v1/operator/reconciliation/resolutions/{id}/rejection {reason}` | State | `reconciliation.ResolutionRejected` (reason) |
    | Withdraw | `DELETE /v1/operator/reconciliation/resolutions/{id}` — the row moves to `WITHDRAWN`; nothing is deleted | State | `reconciliation.ResolutionWithdrawn` |

    - Every route checks `RECONCILIATION_RESOLVE`, has a `RoutePermissionRegisterTest` row and its
      negative tests, and is additive in OpenAPI under v1 (ADR-0015). Approval is synchronous.
    - **Approval carries no key, deliberately** — the ledger approval's argument restated: the
      resolution's one-way machine is the idempotency (`INV-IDEM-01` through state), and the same
      approver's retry converges on the recorded entry.
    - The platform's closure is audited acting-only as `reconciliation.BreakResolvedByEvidence`;
      a loser records nothing. A zero-value `ACKNOWLEDGE` is one act and one reasoned record.
    - **Event:** `reconciliation.BreakResolved` (breakId, resolutionId, kind, reasonCode,
      journalEntryId?); for a repudiation, `settlement.SettlementBatchRepudiated`. The planned
      `AdjustmentPosted` event is dropped: it collides with the audit action
      `ledger.AdjustmentPosted`, and `BreakResolved.journalEntryId` with
      `ledger.JournalEntryPosted` already carries it.
    - **Meters** (ADR-0072; counts only): `finapp.reconciliation.resolution` {`type`, `outcome`:
      approved, rejected, withdrawn, evidenced, stale}, `finapp.reconciliation.resolution.latency`
      {`type`}, and `finapp.reconciliation.adjustment` {`type`}. Counters count committed facts,
      after commit.
    - **Errors** (`ERROR_CONTRACT`): `reconciliation.BreakNotFound`, `BreakTerminal`,
      `ResolutionAlreadyProposed`, `ResolutionNotPending`, `SelfApprovalRefused`,
      `ResolutionKindNotAllowed`, `ReasonCodeNotAllowed`, `ResolutionTargetRefused`,
      `ResolutionStale`, `RecordAlreadyMatched`, `GainNotYetEligible`; `ledger.AdjustmentOriginMismatch`
      and `ledger.AdjustmentOnReconciledPosition`.
    - **Classification:** narratives and notes `CONFIDENTIAL`; amounts `RESTRICTED-FINANCIAL`,
      never in logs, traces, metrics or events. `resolution`'s columns are classified in
      DATA_CLASSIFICATION §4 in the same change (`ColumnClassificationTest`).

12. **Owner decisions this ADR carries.** The owner's open decisions were settled at the transition
    on the design's recommendations, each recorded as one the owner may revisit.
    - **O1, roles:** two pairwise-disjoint roles, `RECONCILIATION_OPERATOR` {`SETTLEMENT_INGEST`,
      `RECONCILIATION_INVESTIGATE`, `RECONCILIATION_RESOLVE`} and `RECONCILIATION_CONTROLLER`
      {`RECONCILIATION_ADMINISTER`}, rather than one role holding all four (point 10). *(Settled
      2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by the owner.)*
    - **O5, the gain minimum age:** `RECOGNISE_GAIN` only after 90 days (`gain_min_age_days` in
      rule set v1), and always four-eyes (point 2; ADR-0070). *(Settled 2026-09-28 at the Phase 7 →
      8 transition, on the recommendation; revisitable by the owner — as rule-set content, by a new
      version under four-eyes, not a migration.)*
    - **O7, the high-value threshold:** 1,000.00 per currency is a severity threshold and never an
      approval threshold (point 3; ADR-0069). *(Settled 2026-09-28 at the Phase 7 → 8 transition,
      on the recommendation; revisitable by the owner.)*
    - **O2, payout returns:** applied automatically by a worker, with this ADR's four-eyes
      `TRANSFER_TO_ACCOUNT` as the fallback for a return that cannot apply (`REVERSAL_MISMATCH`,
      cause `RETURN_NOT_APPLICABLE`; ADR-0073). *(Settled 2026-09-28 at the Phase 7 → 8
      transition, on the recommendation; revisitable by the owner.)*
    - **O6, the cut order:** `P8-TSK-021`, then `P8-TSK-019`, then `P8-TSK-023`, keeping
      `P8-TSK-023` if possible. If it is cut, `REPUDIATE_BATCH` does not exist in Phase 8, and a
      fabricated batch that passed the controls is unwound item by item by the other kinds.
      *(Settled 2026-09-28 at the Phase 7 → 8 transition, on the recommendation; revisitable by
      the owner.)*

    O3 (door refusal of PII-bearing files) and O4 (the simulated bank opens at zero) are recorded
    in ADR-0066 and ADR-0065.

## Alternatives Considered

### A commercial value threshold below which one person resolves (A)
Pros: fewer second approvals on small differences, so less operator load and fewer rubber stamps.
Cons: a per-currency amount policy with nothing to calibrate it, and a cross-currency threshold is
`INV-MON-04`'s trap (`AdjustmentService.java:34-39`). It invites structuring — many small
resolutions under the line. It also needs a carve-out beneath `V010`, which refuses any one-person
`ADJUSTMENT` entry. The seam is kept (point 3) for a pinned policy when there is something to
calibrate it.

### Four-eyes on every human act, and six-eyes above a value band (B)
Pros: the strongest control; even a zero-value acknowledgement is reviewed.
Cons: a second person confirming a zero-value timing difference protects no value and trains
approvers to click through, which is the rubber-stamp risk this phase records. A value-banded
third approver is a new policy scope with no calibration, and it was ruled out at the transition.
Deferred, with the same seam.

### The platform proposes `EVIDENCED`, and a person confirms (A)
Pros: a human sees every closure.
Cons: evidence that leaves a zero residual needs no judgement. Queueing it for a person adds noise
and delay to the desk that must answer real breaks, and the stored resolution already names the
decision and park that explain it. Recorded against `INV-REC-02` as the alternative (ADR-0069).

### Typed lines: resolve breaks through the generic adjustment
Pros: flexible for cases no template foresaw.
Cons: an operator could type an amount other than the residual, or an account other than the
subject's position. The approver would then be judging prose, not a derivation, and a free line on
a clearing or suspense account breaks `INV-REC-06` and `INV-REC-09`. The generic door would also
bypass the break's state and the stale check (F-a). Templates make the lines a function of the
record.

### A nullable reason-code column (B)
Pros: no trigger and no change to existing writers.
Cons: new proposals could still be uncoded, so `INV-REV-04` would be realised only by convention.

### A required reason code in the generic request (C)
Pros: a code on every adjustment, typed by the person.
Cons: a new required field breaks `POST /v1/ledger/adjustments` under ADR-0015. The chosen rule
(point 5) assigns `MANUAL_CORRECTION` server-side and refuses uncoded inserts at the database,
which gives the same guarantee additively.

### Leave free adjustments on reconciled positions, or refuse them only in the domain
Pros: the ledger desk keeps a direct fix for a mis-posted clearing line.
Cons: a free line on a clearing position is unexplained clearing, and on suspense it is a unit no
break owns. A domain-only refusal leaves raw writers and future code paths unbound. A defect
posted to a reconciled position is instead corrected through the break that owns it.

### Let the generic door approve reconciliation proposals (the status quo, F-a)
Pros: one approval surface.
Cons: the resolution's lines would post while the break stood `RESOLUTION_PROPOSED`. The stale
check, the break's state and the one-to-one binding would all be bypassed, and the resolution
would no longer be the record of the act.

### One role holding all four permissions (B)
Pros: one population, simple staffing.
Cons: the person who sets the tolerances resolves the breaks those tolerances hide.

### Two overlapping roles (C)
Pros: the policy/resolution split, with shared investigation.
Cons: it breaks `RoleName`'s pairwise-disjoint doctrine, which `RoleNameTest` pins. Investigation
does not need to be shared: a controller who needs it holds both roles, as a recorded grant.

### Extend `LEDGER_OPERATOR` with the reconciliation permissions
Pros: no new role.
Cons: the desk that moves money would reconcile it — the conflict reconciliation exists to expose.

### Reverse a write-off automatically when its line arrives late
Pros: the books correct themselves.
Cons: it undoes two people's approved decision with nobody deciding. The late line instead parks as
a recovery with its break and closes four-eyes. Deferred.

## Consequences

Positive:
- No single person can move value out of a counterparty's position or out of suspense. Every
  value-bearing act needs two actor ids at the domain and the resolution CHECK, and at `V010` as
  well for the adjustment kinds.
- A resolution's lines are a function of the record. The approver judges a kind, a code and a
  derived amount, never prose. The approval refuses when the residual moved underneath.
- `INV-REV-04`'s reason code exists for every new adjustment, generic ones included, with no change
  to the generic request.
- The break machine is the only path by which a person changes a reconciled position.
  `RECONCILIATION_LOSSES` and `RECONCILIATION_GAINS` move only by approval, so profit and loss from
  reconciliation is always a named, two-person decision.
- Evidence closes explained breaks with no queue, and it wins every race against a pending approval.
- The roles keep policy apart from its use: the person who loosens a tolerance cannot close what the
  loosening hides.

Negative:
- Every value-bearing resolution needs two people, down to a one-cent fee difference; only a
  zero-value timing difference is single-person. A desk with one `RECONCILIATION_RESOLVE` holder
  cannot close any value-bearing break. The ageing alerts are the signal, and staffing at least two
  is an operational requirement.
- A resolution disposes of the whole residual. A partly explained remainder waits for evidence.
- A busy subject can refuse approvals as stale until it is quiet, and each refusal costs a withdrawal
  and a re-proposal.
- Both of a resolution's dates are its proposal date. An approval days later posts on the proposal
  date, deliberately, so that it converges. What happens to a proposal whose period has closed is
  Phase 14's question, recorded.
- The value-bearing kinds that post no adjustment hold four-eyes at two ranks, not three.
- Two colluding `RECONCILIATION_RESOLVE` holders can still attribute suspense to an account one of
  them controls. The residual is recorded, as ADR-0056 records it for destinations. Detection is the
  trail, the `reconciliationAttributed` term in the payable breakdown, the customer statement's
  `RECONCILIATION_ATTRIBUTION` label, and Phase 15's exclusion rules.
- The ledger desk loses its direct fix on clearing, suspense, cash and the reconciliation P&L
  accounts. Three existing suites move to non-reconciled accounts.

Operational impact: `finapp.reconciliation.resolution` (its `stale` outcome shows churn),
`.resolution.latency` from raise to resolution, and `finapp.reconciliation.adjustment`, alongside
ADR-0069's break-age alerts per severity. The case file and the identifier trace run from the break
to the resolution, the adjustment proposal and the journal entry. The span is
`reconciliation.resolve`.
Security impact: two disjoint roles, four permissions, each route with its negative tests; the
generic ledger door closed to reconciliation proposals, and reconciliation's door closed to manual
ones; narratives kept out of logs, events and audit bodies, with the closed code as the audit's
reason; a transfer only to an active owned account in the subject's currency, share-locked, never
an operational account; the actor-type debt flagged for the gate.
Financial impact: two P&L accounts posted only by approval (`RECONCILIATION_LOSSES` EXPENSE,
`RECONCILIATION_GAINS` REVENUE, ledger `V017`); no tolerance, threshold or free adjustment absorbs
value; cash is never adjusted to fit; no resolution nets two counterparties or originates an
external movement.

## Invariants / Constraints

`INV-REC-03` (amended: Enforce adds `DB-CONSTRAINT` — resolution, proposal and entry one-to-one;
four-eyes whenever value is at issue or the resolution posts; closed reason codes at both ranks;
stale approval refused), `INV-AUD-04` (amended: its implemented subjects gain break resolutions,
batch repudiation, upload attestation and rule-set activation, and "above threshold" for a break
resolution means value at issue or a posting), `INV-REV-04` (amended: reason codes realised by
ledger `V015`, the generic route assigning `MANUAL_CORRECTION`), `INV-REC-05` (amended: Enforce
names `RECOGNISE_GAIN` after the pinned minimum age, four-eyes), `INV-REC-02` (`EVIDENCED` as a
stored resolution, ADR-0069's amendment), `INV-REC-06`, `INV-REC-09`, `INV-SET-05`, `INV-SET-06`,
`INV-RAIL-04`, `INV-REV-01`, `INV-LED-01`, `INV-LED-04`, `INV-HIST-01`, `INV-LIFE-04`,
`INV-IDEM-01`, `INV-IDEM-03`, `INV-AUD-01`, `INV-AUD-02`, `INV-AUD-03`, `INV-MON-04`.

## Follow-up

- `P8-TSK-012` — **implemented** (2026-09-30), the first slice: reconciliation
  `V006` births `resolution` (+`_event`) with the vocabulary stated whole in code
  (`ResolutionKind`, `ResolutionStatus`, `ResolutionReasonCode`) and the `CHECK`s
  deliberately narrowed to the produced subset — kind `EVIDENCED`, status
  `APPROVED`, reason `EVIDENCE_RECEIVED` — exactly as the plan's §8 slices
  `V006`/`V007`: a narrowed rank, not a narrowed vocabulary, regenerated by `V007`
  when `P8-TSK-015` brings the person kinds. `EVIDENCED` is the platform's at three
  ranks (the writer's system-actor path; the `proposed_by_type = 'SYSTEM'` `CHECK`
  refusing every raw writer; `-015`'s door to refuse the kind), born `APPROVED` with
  no person deciding, written by the transaction whose zero-residual allocation or
  offset explained the break; the closure converges on the break's own conditional
  `→ RESOLVED` — a racing or replayed writer records NOTHING (the raise-loser
  precedent). The narrative is the platform's identifiers-only line (`decision=…,
  park=…`), screened for PAN and IBAN shapes at the database like a break note.
  Both tables are append-only for EVERY writer until `V007` narrows the `UPDATE` for
  the person machine. LOCK-ORDER FACT for `P8-TSK-015`'s design (recorded in
  `DISTRIBUTED_EXECUTION.md` §3): every writer that today locks a committed break
  row holds the source's namespace-4 advisory first, and the chunk's settling top-up
  locks the break BEFORE the expectation-closing allocation — the approval door
  must take the same advisory before its break → resolution → subject locks,
  or restate the register's row. The withdraw-pending-proposal-on-evidence clause has
  no producer yet (no `PROPOSED` rows exist) and lands with `-015`.
- **`P8-TSK-015` — implemented** (2026-09-30): the person's machine. Ledger `V017` adds
  `RECONCILIATION_LOSSES` (EXPENSE) and `RECONCILIATION_GAINS` (REVENUE) per currency, both
  reconciled positions (the binding function's list re-stated), so only an approved resolution
  posts there. Reconciliation `V007` regenerates the kind, status and reason `CHECK`s from the
  enums (every kind but `REPUDIATE_BATCH`, every code but `EVIDENCE_REPUDIATED` — `V009`'s),
  adds the generated (kind, reason) pairing, §3's derived `four_eyes`, the one-to-one ledger
  binding (a posting kind names its proposal, an approved one its entry) and each kind's operand,
  and replaces `V006`'s blanket freeze with the machine's every-writer trigger (payload frozen,
  `PROPOSED →` the three terminals only, what an approval produced named once, no delete) under a
  narrowed `UPDATE` grant; it also carries `P8-TSK-014`'s design input — a break's type moves only
  in `OPEN`/`INVESTIGATING`, and a suspense owner only onto an owning type. `ResolutionTemplates`
  is §2's one pure seat (the admission table, the side rule, the whole-residual amount, the lines);
  `ResolutionMachine` proposes, approves, rejects and withdraws, each one transaction in §9's
  order; the four doors are `RECONCILIATION_RESOLVE`'s, the proposal keyed per principal with the
  shape screened before the claim and the stored receipt carrying no narrative; the evidence
  writer withdraws a pending proposal and rejects its ledger half (§9's first row, produced);
  `MerchantPayable.reconciliationAttributed` and the statement's `RECONCILIATION_ATTRIBUTION`
  read by origin first. **Deviations and decisions, each recorded:** (a) **every** resolution
  command takes the source's namespace-4 advisory first, not only the allocating kinds — §9's
  register fact made uniform (an offset spanning two sources takes both, sorted); (b) the
  **remainder siblings**: a partial allocation raises `AMOUNT_MISMATCH` on the expectation and
  ageing later raises `MISSING_EXTERNAL` on the same remainder, so a disposal of an expectation's
  remainder closes every such sibling with the same resolution (its `break_event` naming it, one
  `BreakResolved` each — the offset's two-break precedent), and a second live proposal across the
  siblings is `ResolutionAlreadyProposed` — found during implementation, since otherwise the second
  break would stand open over a value no person kind could still dispose of; (c) the break's own
  history records only its terminal edge — the proposal, rejection and withdrawal edges are the
  resolution's own history (`resolution_event`), one record per act; (d) withdrawal is the
  proposer's alone (`reconciliation.NotTheProposer`, a code beyond §11's list) and a rejection is
  never the proposer's (`SelfApprovalRefused`); (e) a manual match's decision, unpark park and
  entry are named on the resolution at approval (`V007` admits `decision_id` and `park_id` once,
  like the entry), the candidate must absorb the WHOLE parked value (the rematch rule — a partial
  unpark never happens; a remainder that moved is `ResolutionStale`), and a settling manual match
  closes the candidate's open `MISSING_EXTERNAL` by evidence (`P8-TSK-013`'s L1);
  `RecordAlreadyMatched` maps the pair unique but the pre-checks refuse first, so it is defensive;
  (f) a break owning more than one open suspense item is refused (`ResolutionKindNotAllowed`: a
  template disposes of one); (g) the expectation's history gains `RESOLVED` (`V007` regenerates
  the event list); (h) no bespoke `reconciliation.resolve` span (the `P8-TSK-008` precedent: the
  request span and the audit trail carry the act) and the meters are `P8-TSK-024`'s. The two
  recorded questions below are **decided**: no step-up (the ledger's adjustment approval takes
  none; the step-up debt row is unchanged), and a refused self-approval writes nothing (the
  `AdjustmentService` precedent — a `DENIED` record would be the destination approval's shape, not
  this machine's).


- Until Phase 8's first task lands, nothing in this ADR is implemented; every statement is the
  decided design, corrected by the tasks that build it.
- `P8-TSK-006` (ledger `V015`: `reason_code`, `origin`, the uncoded-insert trigger, the binding
  over `reconciledPositions()`, the re-stated freeze; `AdjustmentReasonCode`; `proposeOwned`,
  `approveOwned`, `rejectOwned`; the generic door's `MANUAL_CORRECTION` and origin refusal; the
  three suites moved). `P8-TSK-003` (`RECONCILIATION_OPERATOR`, identity `V016`). `P8-TSK-007`
  (`RECONCILIATION_CONTROLLER`, identity `V017`). `P8-TSK-012` (the `resolution` table with the
  platform's `EVIDENCED` only, and the first `reconciliation.BreakResolved`). `P8-TSK-015` (ledger
  `V017`, `RECONCILIATION_RESOLVE`, the six person kinds besides repudiation, reason codes, the
  frozen payload and `ResolutionStale`, evidence withdrawing a pending proposal,
  `reconciliation.BreakResolved` extended to the person's kinds, and `reconciliationAttributed`
  with the customer statement's `RECONCILIATION_ATTRIBUTION` label, point 2). `P8-TSK-019` (the
  payout return's fallback). `P8-TSK-020` (unmatched confirmations released only by resolution).
  `P8-TSK-023` (`REPUDIATE_BATCH`; reconciliation `V009` admitting the kind, the batch subject and
  its one-live unique, the external item's `REPUDIATED` and its `MATCHED → UNMATCHED` reopening
  (a bank item of another batch), the expectation's reopening edges, and the suspense item's
  `REPUDIATION` origin).
  `P8-TST-002` (every break type crossed with every admissible
  kind; concurrent approvals; evidence against approval both ways; offsets and claw-backs; write-off
  then recovery; four-eyes negatives at every rank; the threshold's edge — a zero-value
  acknowledgement by one person, a one-unit value refused without a second; immutability by
  privilege and trigger; the two P&L accounts posted only by approvals). `P8-DOC-001`.
- `MUTATION_TESTING.md` §2 rows for `INV-REC-03`, `INV-AUD-04` and `INV-REV-04` (Phase 8 token),
  each verdict read from the failing testcases.
- ~~**Recorded for `P8-TSK-006`'s design, not decided here.** `RECONCILIATION_OFFSET` is in the closed
  set the design lists, but no Phase 8 kind produces it: `OFFSET_SUSPENSE` posts nothing, and a
  correction offset is a system `POSTING`. The platform does not keep producerless states, so the
  task decides before `V015` is written — migrations are forward-only — whether to drop the member or
  to record why it stays.~~ **Decided at `P8-TSK-006`'s design (2026-09-29): dropped.** The closed
  set shipped with five members; the `CHECK` is generated from `AdjustmentReasonCode`, so a future
  offset-posting kind widens it with its own forward migration (the `V014` pattern) — the same act
  keeping a dead member would still have required someone to police.
- **`P8-TSK-006` — implemented** (2026-09-29): ledger `V015` as §5 scopes it (the code and origin
  columns with history defaults, the uncoded-insert refusal, the generated pairing `CHECK`, the
  freeze re-stated over both columns, and the binding trigger over
  `AccountPurpose.reconciledPositions()` — `SETTLEMENT_CLEARING`, `PAYOUT_CLEARING`,
  `INSTANT_CLEARING`, `SUSPENSE_UNMATCHED`); the domain judges the binding **before the
  idempotency claim** and re-judges it at a `MANUAL` approval (the pre-`V015` legacy row's one
  honest answer); §6's F-a origin refusal at both doors (`ledger.AdjustmentOriginMismatch`, with
  `proposeOwned`/`approveOwned`/`rejectOwned` confined to `com.finapp.reconciliation` by a static
  rule); the generic door assigns `MANUAL_CORRECTION` server-side with the request shape unchanged.
  The resolution machine that calls the owned methods stays `P8-TSK-015`'s.
- **Recorded for `P8-TSK-015`'s design, not decided here** *(decided at `P8-TSK-015`: no step-up;
  a refused self-approval writes nothing — the implemented note above)*.
  - Whether proposal and approval take `P4-TSK-007`'s conditional step-up. The ledger's adjustment
    approval does not today, and a sixth caller is the step-up debt row's extraction trigger
    (`CURRENT_STATE.md`).
  - Whether a refused self-approval also commits a `DENIED` audit record, as ADR-0056 §8's
    destination approval does, or writes nothing, as `AdjustmentService` does.
- **Recorded for `P8-TSK-016`'s design, not decided here** (ADR-0070's input). A remittance paid
  without its reference parks as an unattributed bank credit, and no kind above attributes a
  parked bank line to a counterparty's clearing position. Widening `MANUAL_MATCH` to unattributed
  bank items would be an amendment of this ADR's template table, still four-eyes. The other honest
  path, a four-eyes `WRITE_OFF` of the remittance and a later `RECOGNISE_GAIN` of the credit, needs
  no amendment.
- Recorded, not scheduled: a pinned de-minimis or value-banded approval policy (seam: the proposal
  row and `resolution.four_eyes`); automatic reversal of a write-off on late evidence;
  return-to-sender of unattributed funds; Phase 15's role-exclusion and resolver-is-not-actor rules;
  Phase 14's treatment of a proposal whose period closed before approval.
- Annotations at the transition: `LEDGER_MODEL.md` §6 (the adjustment gains an origin and a reason
  code; the threshold statement stands); DELIVERY_PLAN's Phase 8 addendum (the `AdjustmentPosted`
  event dropped).
- The Phase 8 review reads this ADR against the code before accepting it.
