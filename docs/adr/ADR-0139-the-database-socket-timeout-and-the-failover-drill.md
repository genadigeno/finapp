# ADR-0139 — The data source's socket timeout, and the rules of the local database failover drill

Status: Proposed
Date: 2026-11-13
Phase: 16
Context: Platform · every module that reads the database
Rests on: ADR-0014, ADR-0016, ADR-0127, ADR-0128; `THREAT_MODEL.md` §6 items 42 (h) and 43 (g); owner decision C7 of
`PHASE_16_PLAN.md` §2.2; `OPERATIONS_RUNBOOK.md` §11.40 and §11.54; `RESILIENCE_EXPERIMENTS.md` E05, E06, E14, E56 and E57.

## Context

**The socket timeout.** The data source set the driver's `connectTimeout` (5 s) and no `socketTimeout`, on a note that the number
belonged "with Phase 3, where the queries exist". After a failover a connection the network black-holed never answers: a request or a
tick holding one waited until its pod was replaced, holding its thread and one of the pool's seven connections, and the runbook's only
remedy was a person evicting the pod (§11.40 step 3). Two reads already bounded their own wait by a network timeout on their connection
(the schema guard's 3 s, the abuse counters' 2 s). Item 42 (h) was decided on 2026-11-12: correct the note, and decide the number in
Phase 16's failover drill, from the longest statement under load and the failover's black-holed connection.

Reading the code for that decision found three things its options had not said:

1. **The longest statement bound the code arms on the writer's pool is four minutes, not 30 s.** The shared report snapshots
   (`AccountingBeans.reportSnapshots`: the final statement desk, the report run desk, the close's final trial balance) open the writer's
   connections armed at `finapp.accounting.report.generation-timeout` (PT4M). Item 42 (h)'s option (i) named "the accounting checks'
   30 s"; a socket timeout chosen from that would have failed those reads every time they ran long.
2. **The reporting pool takes the writer's driver properties** (`ReportingPool`), so the writer's socket timeout is report
   generation's too, armed at the same four minutes.
3. **The load scope recorded no statement's longest execution**, only totals and means (`load/statements.sql`).

The statement bounds the code arms are seven registered properties (`CONFIGURATION_REGISTER.md`: the checks 30 s, the gauges 2 s, the GL
reads 10 s, report generation 4 min, operational reports 30 s, the source census 30 s, the risk proofs 5 s) and risk's evaluation
budget (250 ms, a constant), armed in six classes and described in one interface. The statements that arm none (requests, ticks, sweeps) were measured once past the
platform's break, at three replicas (E30, `CAPACITY_MODEL.md` §3.4): request p99 at most 22 s, the slowest statement's mean 2.7 s. The
runs that would measure them at the sustained profile and the black-holed connection itself were postponed by the owner on
2026-11-13 (`POSTPONED_ISSUES.md` PI-025).

**The failover drill** (owner decision C7): a test-only primary and synchronous standby (`FIRST 1`) on `kind`, promoted by the drill and
reached by moving the Service. Four things were the drill's to decide (routed by the toolkit's task): the promotion's rule, the new
primary's `synchronous_standby_names`, that the harness's reads follow the promoted server, and that the old primary never returns
beside it.

## Decision

1. **`socketTimeout: 300`** (seconds) in `spring.datasource.hikari.data-source-properties`, which the reporting pool takes too.
   - *Floor*: every statement bound the code arms, on either pool, plus a minute for the server to answer a statement it cancelled.
     The longest is the report generation budget (four minutes), so five minutes.
   - *Ceiling*: a black-holed connection is given up with ten minutes of the in-region recovery objective left (ADR-0128 tier 1: 15
     minutes to money movement) for the promotion, the Service's move and a person's step.
   - *Held*: `DatabaseSocketTimeoutTest` reads the value from `application.yaml`, the bounds from the register with their code defaults
     and the recovery objective from ADR-0128; it discovers the classes that arm `statement_timeout` and the bound properties their beans
     read, so a new arming site or a new bound fails it until it is weighed against this value; and it holds that no overlay moves a
     bound out of its sight.
   - *Measured, owed*: the load scope now records each statement's longest execution (`pg-statements_longest.tsv`), which E29's owed
     sustained run reads; E57 measures a black-holed connection given up within the timeout and, as its control, still held without
     it, over the whole fleet; E14 (the network-faults task's) measures it for one replica cut by a DROP in its own namespace, on this
     tree under the timeout. **Revision rule**: if an owed run measures a statement that arms no bound running past half the timeout, or E57's leg with the
     timeout does not free within it, this decision is revisited by a change to this record, never silently.
2. **A cut statement is the platform's existing ambiguous case.** When the driver gives a connection up, the request or tick fails as
   any lost connection fails it: an open transaction is rolled back by the server when it next reads the closed socket, and a `COMMIT`
   whose answer was lost is "the database committed and the answer was lost", which idempotency keys, leases and the inbox already
   answer. No new failure class is added; a forever-hold is removed.
3. **The two reads with their own network timeouts keep them** (3 s, 2 s): they are on paths that must answer in seconds, and Hikari
   puts the pool's value back when the connection returns.
4. **The drill's promotion rule.** The old primary is fenced first (its StatefulSet scaled to zero) and its pod must be gone before
   anything is promoted: never two primaries. The standby is then promoted only if the primary named it synchronous at the drill's
   last read before the loss **and** the standby holds the last commit the drill saw acknowledged. Otherwise the promotion is refused,
   the experiment's stop condition, and the old primary is brought back; a standby behind is never promoted by the drill, because it
   would lose what the primary acknowledged (§11.35's restore is the path for data that is gone).
5. **The new primary runs alone**: `synchronous_standby_names` reset to none. A primary that named its lost standby would make every
   commit wait for a server it does not have. Its commits are durable on its own WAL; until a new standby is built, the loss of its
   volume would lose what it committed since the promotion. The reference's managed service rebuilds a standby; the drill builds none
   (it is the run's last experiment), and says so.
6. **Every read after the move is of the promoted server** (the harness's `PG_POD`), and the old primary stays fenced for the rest of
   the run: the fence and the promotion are the drill's acts, logged and never undone by the fault registry's exit trap.

## Alternatives Considered

### A shorter timeout (60 s) with the report snapshots raising their own connection's network timeout
A held OLTP connection would be freed in a minute rather than five. It touches every arming site across three modules, and one missed
site, or one added later without the raise, fails a proof or a census every time it runs, which is the failure the decided item names
as the worse one. Rejected for now; the revision rule can reopen it with measurements.

### Leave it unset until the postponed runs
Keeps the forever-hold the drill exists to bound, for as long as the runs are postponed. Rejected.

### Promote whenever the primary is lost, as an automatic failover would
Promotes a standby behind after an asynchronous episode and loses acknowledged commits silently. Rejected: the promotion's check is the
drill's reason to exist, and its probe (E56) shows what an unchecked promotion loses.

## Consequences

Positive: a black-holed connection is freed within five minutes on every replica, without a person; the drill can neither split the
record nor promote a standby behind. Negative: a statement that legitimately runs past five minutes and arms no bound of its own would
now fail; none is known, and the owed measurement is the check. Operational impact: §11.40 step 3 (evict a replica still failing)
becomes the step for a replica still failing past the timeout. Security impact: none; `verify-full` holds after the move because the
standby presents the primary's certificate. Financial impact: none on any posting rule; the drill checks that nothing acknowledged is
lost and no key takes a second effect across a failover.

## Invariants / Constraints

`INV-IDEM-01`, `INV-ACC-01`, `INV-BAL-02` and `INV-HIST-01` are what the drill's census and journal comparison hold across a failover.

## Follow-up

The runs: E05, E06, E57 and the probe E56 at three replicas, and E29's longest statement, owed under PI-025. The answer a client gets
while the database is away (`500` today) is put to the owner (`THREAT_MODEL.md` §6 item 61).
