# ADR-0133 — Contention measured before any mechanism, and the whole-history folds bounded by a commit-order checkpoint

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Ledger · Merchant · Platform (operated under load)
Rests on: ADR-0009, ADR-0039, ADR-0041, ADR-0119, ADR-0126, ADR-0127; ledger `V034` (the journal's commit-order position);
`PHASE_16_PLAN.md` §5.4, §6.3–§6.5 and §6.7; `CAPACITY_MODEL.md` §2 (W1–W8) and §3.4; `THREAT_MODEL.md` §6 item 55.

## Context

`PHASE_16_PLAN.md` §6.4 lists the platform's known hot rows and the mechanisms that would relieve one, in the order they would be
chosen (sub-accounts by purpose, a shorter hold, a separately decided projection exception), and forbids building any of them before a
measurement names the row. The measurement is the concurrent-financial profile's (E38) and the soak's (E39). The owner postponed every
`kind` run of Phase 16 on 2026-11-13 (`POSTPONED_ISSUES.md` PI-025), so no row has been measured under this phase's profiles. The only
contention figures are Phase 12's two-instance storm: `LENDING_CAPITAL` 65 waits, p95 142 ms, max 1,070 ms; `BNPL_MERCHANT_FEE_INCOME`
12 waits, max 43 ms; no deadlock (`CURRENT_STATE.md`'s debt row on the capital row).

One limit *has* been measured, and it is not a hot row. E30 at three replicas (2026-11-13, commit `89b8fc60`) named the database's CPU
the first limit, and four fifths of its statement time was the merchant API's payable read (`CAPACITY_MODEL.md` §3.4).
`MerchantPayable` reads through `JdbcPositionBreakdown`: one statement over **every** line of the merchant payable account, with three
correlated subqueries per line (the proposal's origin, the first named counterparty, the posting-key namespace). On an idle core of the
test-only server it cost 41.9 ms for 4,039 lines, about 10 µs a line (`fold-explain.txt`). The cost of each read grows with the account's
lifetime history, without bound.

Derived at the production target (W4: 2,000 merchants, 100 payments a day each, two payable lines per capture; half of W4's 400,000
merchant API reads a day are payable reads, 5.56 a second in the busiest hour): about 200 lines a day per merchant, so 2 ms of a core per
read per day of history. After a month a read costs about 60 ms and the busiest hour about a third of a core; after a year, 0.73 s a
read and about four cores. These are derived numbers, not measurements. They show the read becomes a limit at the target within months,
whatever the rig.

Reading the code finds the same class on the decision path. `AvailableBalance.underLock` derives the settled balance from every line
of the account (`JdbcBalanceDerivation`, ADR-0009 and ADR-0039: "the definition, never the projection") while the caller holds the
account's row lock. So a hot account's lock hold time grows with its history. It is not measured: E38's transfer pair measures it, the
pair's waits read against the pair's growing history.

## Decision

1. **Contention is measured, never assumed, and the measurement is built.** E38 drives the concurrent-financial profile (many clients on
   few accounts: every transfer between one pair, every checkout to one merchant, at the engineering workload and at the target's burst)
   at seven replicas and at three. A 250 ms sampler of `pg_locks` resolves every wait to its row: a balance or account row to its
   account's purpose, currency and id, an advisory lock to its namespace and key, a table lock to its table. The server's own log records
   every wait of a second or more and every deadlock and serialisation failure with its statements, and every deadlock the server counted
   must have its record there. Before the load the sampler is shown a wait it must place, and the log must hold it too. E39 holds the
   engineering workload for eight hours: the named tables, autovacuum, the checkpoints, the WAL, the top statements' plans through
   auto_explain's window, and a transactional index build's write stop on the journal (`THREAT_MODEL.md` §6 item 42 (b)). E40 measures
   the sweeps' failure records in a provider storm. The runs are owed under PI-025. `OPERATIONS_RUNBOOK.md` §11.48 says how they run and
   how they are read.
2. **No hot-row mechanism is decided now.** No row has been measured past its objective at the production target, so the order of
   `PHASE_16_PLAN.md` §6.4 stands and nothing is built. Sub-accounts by purpose (ADR-0041's first remedy), a shorter hold, a projection
   exception, and the capital row's tranches (`DECISIONS.md` §Deferred) are each decided by a later ADR that names the measured row,
   its wait distribution and the throughput at which the wait passes the operation's objective. "No mechanism needed at the target" is
   **not** claimed: it waits on E38's run. The never-list of §6.4 binds every future mechanism. That rules out an asynchronous balance, a
   balance mutated outside a posting, a weaker isolation for money and a cache in front of a decision.
3. **The whole-history folds are bounded by a commit-order checkpoint.** This is proposed here, and its build is the owner's to place
   (`THREAT_MODEL.md` §6 item 55). A checkpoint is the fold of one account's lines (for the breakdown, its buckets) over every line whose
   entry's commit-order position (`txid`, ledger `V034`) lies below a watermark *W*. *W* is `pg_snapshot_xmin` of the snapshot that read
   them, so every transaction below *W* has finished and every committed line below it was visible to that read. No line can ever be
   added below *W* afterwards, and no clock or writer can choose *W*. A read is then the latest valid checkpoint's buckets plus every
   line at or above its watermark, folded through `Money` in one statement, exactly as today. The terms still sum to the position by
   construction, and the API's contract (`DERIVED`, the lifetime terms) is unchanged. A read whose new lines pass a threshold stores a
   new checkpoint at its own snapshot's watermark, best-effort and idempotent: `INSERT … ON CONFLICT DO NOTHING`, in a savepoint that
   never fails the read. Ten instances racing write at most a few equal or overlapping checkpoints, each correct. What it needs, each
   built and proven before it merges:
   - `ledger.journal_line` gains its entry's `txid`, stamped by trigger like `V034`'s, and an index on `(ledger_account_id, txid)`. That
     is a schema change on the platform's hottest table and one more index on every line insert, which costs the resource E30 named the
     first limit. Its cost is measured by E38 and E39 before and after.
   - The checkpoint rows carry the classification rules' version and the caller's precedence and namespaces. A change to
     `PositionBreakdown.Bucket#of` (P14-TSK-023 moved `MANUAL` lines live) bumps the version and so retires every checkpoint.
   - **A logical restore resets the counter below the restored `txid`s** (`V034`'s own warning). A checkpoint taken before it would read
     valid again once the counter passed its watermark, and miss every line written in between. So a checkpoint is valid only while it is
     the restored cluster's own: it records the cluster it was taken on, and a read refuses any other and folds whole.
   - The proofs (`ProductSourceProofs`, the BNPL three-way) keep reading the full fold, so a checkpoint never verifies itself.
   - Database cases: the split property (a checkpoint plus the lines above it equals the whole fold, under concurrent posting), the
     racing writers, the version bump and the restore refusal. A hermetic property test of the fold's split comes beside them.

   The same mechanism is the candidate for `AvailableBalance` once E38 measures the pair's hold time growing with its history, decided
   then by its own ADR. A decision is under its lock, so its correctness argument is stricter.

## Alternatives Considered

### Make the payable read's statement cheaper (the plan)
Pros: no schema change; the three correlated subqueries can be cut to one join per entry, perhaps a third of today's cost.
Cons: still linear in the account's history; it postpones the limit by months, it does not remove it (`PHASE_16_PLAN.md` §5.4: the
cheapest change *that removes it*).

### A projection of the buckets, maintained in each posting's transaction
Pros: an O(1) read.
Cons: one more row updated per posting on the merchant payable, which is already a hot row (E30: its upserts serialised at a mean of
14 ms). It is a second mechanism for the same figure beside the lines, the disagreement `MerchantPayableController`'s javadoc refuses.
ADR-0041 records why the projection is never the authority. Rejected.

### Bound the API instead: the position from the projection, the terms over a window
Pros: no ledger change.
Cons: it changes what every merchant reads (lifetime terms become a period's, and `DERIVED` becomes `PROJECTION`). A merchant-facing
contract change is the owner's and the merchants', not a capacity fix. It is kept as item 55's option (ii).

### A checkpoint per locked accounting period (the posting cutoff, ADR-0114)
Pros: the cutoff is enforced by the database, and a restore keeps it.
Cons: the cutoff can move back (`MOVE_BACK`), which voids every checkpoint after it. The lines since the cutoff still need an index by
account and date that `journal_line` lacks. The month-long delta keeps the read linear within the month. The commit-order watermark is
finer, needs no period, and `V034` already proved its argument for the source-join census.

## Consequences

Positive: the hot rows are measured before anything is changed, and the measurement fails loudly when it sees nothing. The one measured
limit has a bounded design with its hazards named, the restore's above all. The decision path's own whole-history fold is named before
it is measured.

Negative: until the owner places item 55 the payable read stays linear in history. E30's first limit stands, and each merchant's read
grows. The soak (E39) leaves the payable read out for that reason.

Operational impact: the `kind` overlay's test-only server preloads `auto_explain`, inert until a run opens its window. The runs set
logging on the run's own server by `ALTER SYSTEM`. No deployed overlay changes.

Security impact: none to the platform. The harness's plans and lock records stay in the run's artefacts on the machine that ran it. No
parameter's value is logged (`auto_explain.log_parameter_max_length` 0). A custom plan's conditions can show the run's generated values.
The payable read's cost growing with history is a resource-exhaustion residual (T14): a merchant key, or a stolen one, spends the
database's CPU in proportion to the merchant's history (item 55).

Financial impact: none now. The proposed checkpoint keeps every term a fold of immutable lines through `Money`, never a balance written
outside a posting.

## Invariants / Constraints

`INV-ACC-01`, `INV-LED-04` (one write path), `INV-MON-03` and `INV-MON-06` (the fold refuses mixed scales and overflow), `INV-BAL-04`
and `INV-BAL-05` (available balance under the lock), and §6.4's never-list. A checkpoint is derived data beside the journal and never the
journal. Deleting every checkpoint changes no figure, only the read's cost.

## Follow-up

- Run E38, E39 and E40 (PI-025). Write a hot-row mechanism's ADR only if a row passes its objective at the target. Decide the migrator's
  mode for a non-transactional build (item 42 (b)) from E39's write stop.
- The owner places item 55. The recommendation: build decision 3 in its own task, with its database cases run before it merges, ahead
  of any real environment that would hold a merchant's months of history.
