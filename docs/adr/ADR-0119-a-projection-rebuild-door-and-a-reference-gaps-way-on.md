# ADR-0119 — A four-eyes projection rebuild, and a reference gap's way on: the real last observation, disclosed

Status: Proposed
Date: 2026-11-06
Phase: 14
Context: Ledger (the balance projection) · Accounting (valuation rate sets, translation) · FX (read only)
Supersedes: in part, ADR-0116 decision 2's capture rule, for a pair whose reference observation is older than the
staleness bound (decision 2 below); nothing else of ADR-0116. Builds what ADR-0041 rule 2 promised ("the rebuild is a
first-class operation", safe while postings continue). Rests on the owner's acceptance of `P14-TSK-012`'s item (g)
(2026-11-06, `DECISIONS.md` "The close's open questions..."), `INV-BAL-01`, `INV-BAL-02`, `INV-BAL-05`, `INV-LED-03`,
`INV-LED-04`, `INV-ACC-11`, `INV-AUD-04`.

## Context

Two states hold a month's close with no product door (`P14-TSK-012`'s gate, item (g)):

1. **`PROJECTION_VERIFIED` failing with `PROJECTION_DRIFT`.** `ledger.account_balance` is derived data, written in the
   posting's own transaction (ADR-0041 rule 1). Drift means the journal and its display copy disagree - a raw writer
   bypassed the projection, a hand edited it, a defect. The journal is the authority. The way on must move the
   projection to the journal, never the journal to the projection: an adjustment would post real entries to make the
   authoritative balance agree with a corrupted copy, which is `CLAUDE.md` rules 5 and 11 inverted. ADR-0041 rule 2
   promised a rebuild and none was built; `ProjectionVerification` reports drift and never repairs it, because a ledger
   that corrected itself would destroy the evidence of what went wrong.
2. **A reference gap beyond the staleness bound.** ADR-0116 decision 2 values each currency at the reference's last
   observation strictly before the period end, refusing the whole capture when any pair's is older than the bound
   (`P14-TSK-014`: 96 hours by default, at most 336). A reference silent for longer leaves the month with no set,
   `VALUATION_SET_APPROVED` waiting for good, and no door.

## Decision

1. **A projection rebuild is a person's four-eyes ledger act, never automatic.** One account at a time.
   - **Proposal** (`LEDGER_ADJUST`, keyed, reasoned): records the drift the proposer observed - the projection row's
     figures (or its absence) and the figures the journal and the `ACTIVE` holds derive, read by the verification's own
     seq-bracketed protocol, unlocked. Refused when the reading is clean, when an entry committed mid-reading, when a
     rebuild of the account already stands, or when the history cannot be derived at the row's scale. One `PROPOSED`
     rebuild per account (a partial unique index).
   - **Approval** (`LEDGER_ADJUST` and `MULTI_FACTOR`, keyed, a second person): **recomputes at its own moment, under the
     posting's own locks, and writes what it computed** - never what the proposal saw. The lock order is the posting's:
     the rebuild row `FOR UPDATE`, then the account `FOR KEY SHARE` (the journal line foreign key's mode), then the
     projection row `FOR UPDATE`, then the derivation, the applied-entry count and the `ACTIVE` holds in statements that
     start after that grant, under `READ COMMITTED` (enforced). Every posting to the account must take the same row before
     it commits and every hold must take the account `FOR UPDATE`, so nothing that changes the sum can commit while the
     rebuild holds them: a posting committed before the grant is in the sum, one committing after it composes on the
     written row. No advisory lock is taken: the rebuild inserts no journal entry, and the cutoff guards the journal's
     dated content.
   - **What it writes**: `posted_minor`, `holds_minor`, `last_entry_seq` (the applied-entry count) and `updated_at` - the
     application role's existing column grant (V006); the identity columns (account, currency, scale) are never written,
     and a scale the row cannot follow refuses (`INV-MON-03`). A missing row with lines is inserted (`ON CONFLICT DO
     NOTHING`, then the row re-locked if a posting created it first). A row whose account has no entries cannot say so
     (`last_entry_seq >= 1`) and refuses: deleting it is the migrator's act, as V006 says.
   - **Nothing to correct**: `UNCHANGED`, the projection untouched, the act recorded.
   - **The record** (`ledger.projection_rebuild`, `V031`): the proposer's observation, the approval's figures before and
     after, both persons, the instants; four eyes by the domain and a `CHECK`; `INSERT`, `SELECT` and the decision's
     columns only, no `DELETE`. The approver attests that the account's projection be made what the journal says at the
     approval's moment; the two drifts (proposed and found) are both recorded and may differ when postings or another
     repair intervened.
   - **It never touches a journal line**: no posting command, no `PostingEffect`, no journal store; only `SELECT` on the
     journal tables. Only the ledger writes the projection (`INV-LED-04`'s shape).
   - **The check is not decided here**: `PROJECTION_VERIFIED` is re-judged by its own re-run, by the verification.
2. **A reference gap's way on is the real last observation, admitted by exception and disclosed.** For a pair named in
   a capture exception (`ACCOUNTING_CLOSE_PROPOSE`, keyed, reasoned), the set holds **the reference's last observation of
   `EUR/<currency>` strictly before the period end, whatever its age**, flagged beyond the bound, its source, snapshot id,
   `observed_at` and `received_at` kept, provided that: it is beyond the bound (a pair with an admissible observation is
   no gap), and its snapshot id is the one the capturer named (the refusal's detail names it), so no person picks an
   older observation they prefer. Every unnamed pair is judged by ADR-0116's bound as before. A pair the reference never
   observed before the end stays refused: there is no real word to admit. The set records how many pairs it excepts
   (`reference_gaps`, `V012`), and the database admits a flagged rate only within that count, beyond the bound, before
   the end.
   - **Approval** by a different person through its own door, `ACCOUNTING_CLOSE_APPROVE` and `MULTI_FACTOR`, which
     re-reads the reference and refuses if the named observation is no longer the last before the end. The ordinary door
     refuses a set holding a gap, and the gap door a set holding none.
   - **Disclosure**: the set's receipt and read state, per rate, whether it is beyond the bound and its age in whole
     hours at the period end; every translated report content (the trial balance, the balance sheet, the income
     statement - the three definitions that translate, through `Translation.section`) names each such pair, its snapshot,
     `observed_at`, its age and the bound, in the `translation` member every served translated figure travels with. A set
     with no gap writes exactly the bytes it wrote before, so every issued FINAL regenerates byte-identical; a FINAL
     pinning a gap set regenerates byte-identical too, the age being a function of two frozen instants.
   - **A typed rate is rejected**: it has no source, no snapshot and no `observed_at` anyone can check (`INV-ACC-11`).

## Alternatives Considered

### An adjustment for drift
Posts real entries to agree with a corrupted copy: value moved to hide a defect. Rejected.

### An automatic repair by the verification job
Destroys the evidence of what went wrong and repairs with nobody answerable. Rejected (ADR-0041's own reasoning).

### The rebuild summing first and locking after, or writing the proposal's figure
Either loses a posting that commits in between (a lost update on the projection). Rejected; the hermetic controls and
the owed latched race demonstrate it.

### A rate a person types, with provenance
Invented data: no reference anyone can re-read, and `INV-ACC-11`'s provenance becomes a person's word. Rejected while the
reference holds a real observation; a pair with none at all stays refused (an owner item).

### Widening the bound for the period
Admits every stale pair silently and changes a configuration the next month inherits. Rejected.

## Consequences

Positive: both wedges have a recorded, four-eyes way on; nothing in the journal moves; the evidence is kept.
Negative: a gap set, once approved, stands for its period (no supersession, ADR-0116's follow-up); a rebuild of a hot
account holds its projection row for a full history fold. Operational impact: runbook sections 10.17 and 10.18.
Security impact: two `MULTI_FACTOR` approvals. Financial impact: none on balances or the journal.

## Invariants / Constraints

`INV-BAL-01`, `INV-BAL-02`, `INV-BAL-05`, `INV-LED-03`, `INV-LED-04`, `INV-MON-03`, `INV-ACC-11`, `INV-AUD-04`.

## As built

`P14-TSK-026` (2026-11-06). **Decision 1**: ledger `V031` (`ledger.projection_rebuild`), `ProjectionRebuild` (the aggregate
and the one definition of drift the two readings use, the verification's), `ProjectionRebuilds` (the three keyed acts),
`ProjectionRebuildRows` and `JdbcProjectionRebuildRows` (the lock-and-write seam: `FOR KEY SHARE`, `FOR UPDATE`, the
derivation through `JdbcBalanceDerivation`, the holds folded through the kernel, `INSERT ... ON CONFLICT DO NOTHING`, the
four-column `UPDATE`; `READ COMMITTED` asserted), `JdbcProjectionRebuildStore`; in `app`, `/v1/ledger/projection-rebuilds`
(`ProjectionRebuildController`, `LedgerProjectionRebuilds`), `LEDGER_ADJUST` on all four routes and `MULTI_FACTOR` on the
approval, and `finapp.ledger.projection.rebuild{outcome}`. Five `ledger.*` codes (the adjustment's titles name an adjustment, so
none is borrowed) and three audit actions, `ledger.ProjectionRebuildProposed` (reasoned), `ledger.ProjectionRebuilt`,
`ledger.ProjectionRebuildRejected`; the figures before and after on the row, never in a summary. **Who**: the ledger operator
proposes and a second ledger operator approves; an accountant who sees the drift cannot rebuild and a controller cannot, and
a ledger operator closes no month (`LEDGER_ADJUST` reused, not a new permission: an owner item). **Decision 2**: accounting
`V012` (`valuation_rate_set.reference_gaps`, `valuation_rate.beyond_bound`, V005's two functions replaced with every check they
held, the count frozen), `ValuationRate.beyondBound`, `ValuationRateSet.judgeAcrossGap` and `requireStillTheLast`,
`ValuationRateSets.captureAcrossGap` and `approveAcrossGap`, `ValuationDoorMismatch`, `Translation.section`'s two conditional
members; in `app`, `POST .../valuation-rate-sets/reference-gap` (`ACCOUNTING_CLOSE_PROPOSE`) and
`POST .../{setId}/reference-gap-approval` (`ACCOUNTING_CLOSE_APPROVE`, `MULTI_FACTOR`), the receipt and the read carrying
`referenceGaps`, `beyondBound` and `ageHours`; `accounting.ReferenceGapRefused`, `accounting.ValuationDoorMismatch`, and the
audit actions `accounting.ValuationSetCapturedAcrossGap` and `accounting.ValuationSetApprovedAcrossGap`. **Which readers
translate**: the trial balance, the balance sheet and the income statement, each through `Translation.section`; their content is
what every serving of a translated figure carries (the report run's content, the controller's FINAL statement read), so the
disclosure is in each. The database cases are written and owed (C5): `ProjectionRebuildDatabaseTest`,
`ValuationReferenceGapDatabaseTest`. Status stays `Proposed` until the phase's exit review.
