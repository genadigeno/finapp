# ADR-0151 — No module leaves the monolith: the service-extraction evaluation, each criterion read against the evidence, and what would reopen it

Status: Proposed
Date: 2026-11-15
Phase: 16
Context: every module (the deployment topology) · Platform · Risk · Reconciliation and Settlement · Payment Methods
Rests on: ADR-0001, ADR-0006, ADR-0107, ADR-0108, ADR-0126, ADR-0133, ADR-0134, ADR-0136, ADR-0142, ADR-0145; `MODULE_ARCHITECTURE.md`
§3 M3 and §7; `CAPACITY_MODEL.md` §3.4, §3.5, §4a.4 and §5.3; `PHASE_16_PLAN.md` §1.3 and §17; `BACKLOG.md` `P16-TSK-030`.

## Context

ADR-0001 built the platform as one deployable over one PostgreSQL database because of one property: **a money movement's domain
state, its journal entry and its outbox event commit in one transaction**, so no saga, compensation or reconciliation of our own
partial failures stands between a request and its books (`INV-EVT-01`, `INV-LED-04`). It promised to revisit that "at Phase 16
with load and capacity measurements", and said an extraction needs a superseding ADR citing the evidence and one of the criteria
`MODULE_ARCHITECTURE.md` §7 names:

1. a measured, materially different scaling requirement;
2. an isolation requirement (regulatory, PCI scope, blast radius) that co-location cannot satisfy;
3. an independent deployment cadence blocked by co-location, with evidence of the block;
4. separate ownership requiring an independent release boundary.

The large module, the noun, convention and a cleaner diagram are named there as **not** reasons.

Two things had to be true before the question could be asked honestly. **The boundaries had to hold**: an extraction moves a
boundary that is only as real as its enforcement, and `P15-REM-001` (2026-11-15) made ADR-0006's rules hold what they say - a
module's SQL classes private to it, `app`'s direct schema reads pinned and `SELECT` only, every module's SQL naming its own schema,
no foreign key across schemas, only the ledger appending to the journal. **And there had to be evidence.** What Phase 16 has:

- **Measured** (`CAPACITY_MODEL.md` §3.4, `P16-TSK-007`): two runs on the reference machine's `kind` at three replicas - E30's
  stepped stress profile (2026-11-13, commit `89b8fc60`) and the isolation profile, each operation alone at the target's peak
  (commit `f021383f`). The fleet carried 0.39 of the production target's peak within every objective (step 3). **The first limit
  was the single database writer's CPU** (E30's step 4). The replicas used 0.50-0.52 of their cores, were throttled in under 0.1% of
  their CPU periods and kept 324-326 live threads (§4a.4). Two bottlenecks the runs found are the merchant API's whole-history payable
  read, a database cost (ADR-0133, its bound `P16-REM-001`), and the outbox relay's per-replica drain, a platform mechanism (ADR-0136).
- **Read or derived, not measured**: the contended rows, locks and plans (§3.5; E38, E39, E40 owed under `POSTPONED_ISSUES.md`
  PI-025); the ten-replica runs (`P16-TSK-014`, owed); risk's evaluation latency beside its monitoring sweep (`P13-TST-001`, the
  storm, postponed by the owner, PI-004).
- **Not drivable on `kind`**: credit decisions, lending and BNPL, which need a bureau no `kind` deployment can configure (§3.4).

**What the measured costs say about modules.** §3.4's costs are per operation, and no money movement belongs to one module: an internal
transfer is `transfers`' command, `accounts`' and `ledger`'s rows, `risk`'s synchronous judgement and `platform`'s idempotency, audit
and outbox, in one transaction (138 statements and 8.8 commits per transfer; a card payment 246 and 24, through `payments`,
`paymentmethods`, `merchant`, `ledger`, `risk` and `platform`). The replica CPU each costs (29 ms a transfer, 86 a card payment, 68
a sign-in, the credential hash) is horizontal and was nowhere near a limit; the database time is one writer's, shared by all of them.
The one operation that is nearly one module's, sign-in (`identity` with `platform`'s session and audit), spends replica CPU on its
hash, which scales by adding replicas. **There is no module whose cost scales apart from the others**, and the one resource that did
saturate is the one an extraction does not divide.

## Decision

**1. No module is extracted.** ADR-0001 stands, unsuperseded. Each candidate was read against all four criteria:

| Module | 1 Scaling (measured) | 2 Isolation | 3 Cadence | 4 Ownership | Verdict |
|---|---|---|---|---|---|
| `risk` | Its synchronous judgement is inside every movement's measured latency (E30: transfer p99 347 ms, card payment 601 ms, all objectives held at step 3); no figure separates it, and the latency beside the sweep is PI-004's | M3's trigger 1, AML-only retention or isolation: the regulatory report is a retention class with its period open (`P15-TSK-029`, C12), satisfiable per table inside the module; no chosen jurisdiction demands isolation (C4) | One release train; no release held | One owner (`.github/CODEOWNERS`) | Not met |
| `paymentmethods` | Resolution of an instrument inside the payment's transaction; not separable | **§7 names it the likeliest, on PCI scope.** It holds structurally no card data: a token, a display suffix and an expiry month, PAN-shaped values refused per column (`TokenReference`, the package's own refusals), tokenisation at the provider (ADR-0008's adapter). No cardholder data, no PCI scope to isolate | As above | As above | Not met; the trigger is named below |
| `identity` | The credential hash is the sign-in's replica CPU (68 ms), horizontal, under any limit at the target's 6.67 sign-ins a second | Credentials hashed, keys in the secret store (`SECRET_MANAGEMENT.md`); no separate regime | As above | As above | Not met |
| `settlement`, `reconciliation` | Measured as too little work to separate (a PSP file accepted in 5 s, its batch reconciled in 6) | None beyond the platform's | As above | As above | Not met |
| `accounting` | Not driven separately; its reports read one snapshot of the journal; ADR-0142 routes no read replica at the target and names what would overturn it | None beyond the platform's | As above | As above | Not met |
| `ledger` | Its writes are the shared writer's | None | As above | As above | Not met; §7: the least likely, because its atomicity is the design's asset |
| Every other module (`party`, `kyc`, `consent`, `accounts`, `transfers`, `payments`, `merchant`, `checkout`, `fx`, `crossborder`, `credit`, `lending`, `bnpl`) | Inside the operations above, or not drivable on `kind` | None beyond the platform's | As above | As above | Not met |

**2. What an extraction would cost.** Each is a transaction ADR-0001 keeps whole that would become a protocol:

| If extracted | The atomicity lost |
|---|---|
| `risk` | The fraud decision recorded in the caller's transaction, under the caller's row locks, its breach a recorded fail-safe decision (ADR-0107 decision 4, ADR-0108): it becomes a remote call inside a money transaction - a single point of failure for all movement - or an asynchronous decision after the money moved |
| `paymentmethods` | The instrument read and the payment intent's creation on one unit of work (`PaymentParticipants<Connection>`): a detached or replaced instrument can no longer be refused in the same transaction that would have used it |
| `ledger` | Every posting with its domain state and its outbox event (`INV-EVT-01`; `INV-LED-04`'s sole writer): every money-moving command becomes a saga with compensations |
| `settlement`, `reconciliation` | A file's acceptance with its batch run and items (`ReconciliationIntake`, on the acceptance's own connection), and every recognition or resolution with its posting |
| `accounting` | A report run's reproducibility from one snapshot of the journal, and the posting cutoff moved by the ledger's API against the journal's own lock (ADR-0113) |
| `identity` with `party` | Registration: `app` sequencing the party, the customer and the identity in one transaction (`P1-TSK-006`) |

**3. The measured limit is answered without an extraction.** The single writer's CPU is answered by a larger server (ADR-0134), then
by ADR-0142's overturning measurements (a partitioned table, a routed replica), each an ADR of its own; the payable read by its
bound (ADR-0133, `P16-REM-001`); the relay's drain by ADR-0136. Splitting the database per module would divide the writer and
cost every atomicity in decision 2 at once: refused.

**4. What would reopen it** - any one, each by an ADR citing its evidence:

- **Criterion 1**: a run (PI-025's E38, E39 and E77 among them) or a production signal showing one module's work saturating the
  replicas, or its own pool or broker, while the database is below its limit - a limit an extraction would actually divide.
- **Criterion 2**: raw card data entering the platform (`paymentmethods`, PCI scope - ADR-0001's own named trigger); a chosen
  jurisdiction demanding that AML data or processing be isolated from fraud's or from the platform's (M3's trigger 1, as an
  obligation rather than a retention period).
- **M3's trigger 2**: `RiskEvaluationLatencyOverBudget` firing with the monitoring sweep's share of the evaluation's latency shown, or
  PI-004's storm measuring it. Its first answer is a split of `risk` into modules, not an extraction (M3).
- **Criteria 3 and 4**: a second team owning a module, or a release demonstrably held by the shared train.

**5. Risk stays one module, in the monolith** (M3; ADR-0107 decision 4). Trigger 1 has not fired: the retention obligation is a
class of the platform's retention policy, kept per table inside `risk`; its destruction schedule and legal hold wait on a real
jurisdiction (C12, C4). Trigger 2 is unmeasured and stays with PI-004 and the production alert.

**6. The reconciliation intake joins stay in `app`.** `ReconciliationIntake` (settlement's accepted batches) and
`ReconciliationExpectationRecorder` (payments' and merchant's expectations) are joins between modules that cannot compile against
each other, so the composition root hosts them; C15 re-dated their move behind reconciliation's entry points to this evaluation,
"where module boundaries move". None moves, the divergence the row feared never materialised (`P9-DOC-001`), and the move changes no
behaviour. It belongs to the extraction of `settlement` or `reconciliation`, where these joins would become the integration contract.

**7. Per-service identity and mTLS stay deferred**: there is one deployable, so no service-to-service hop exists to authenticate
(`SECRET_MANAGEMENT.md`, deferred to an extraction).

## Alternatives Considered

### Extract risk's synchronous evaluation as a remote service with a latency budget
Pros: isolates the evaluation's cost from the money transaction's connection; the shape the phase's name suggests.
Cons: the seams are in-lock. An out-of-process call inside a money transaction is a single point of failure for every movement,
and a timeout becomes an outage rather than the recorded fail-safe decision it is now (ADR-0108). There is no measurement that the
evaluation harms anything: trigger 2 is unread. Refused, as ADR-0107 refused it for Phase 13.

### Extract `paymentmethods` pre-emptively, because PCI scope is expensive to add later
Pros: §7's reasoning - the one isolation boundary costlier after the fact.
Cons: there is no cardholder data to isolate; the module is tokenised by construction, and its refusals keep it so. An extraction
now pays the instrument's atomicity with the payment for a scope that does not exist. The module's separation from the outset is
what keeps the later extraction cheap; that is already true. Refused; the trigger is decision 4's.

### Split the database per module to relieve the measured writer limit
Pros: divides the one resource that saturated.
Cons: every atomicity in decision 2 at once - the reason ADR-0001 exists. The limit was reached at 0.39 of the target's peak on a
reference machine whose database had one core; the target's answer is a larger server first (ADR-0134). Refused.

### Defer the decision until PI-025's runs and PI-004's storm
Pros: more evidence.
Cons: the backlog asks for a decision from the phase's measurements, and the evidence that exists points one way: the saturated
resource is shared and no module's cost is separable. The owed runs are not lost - each is a reopening trigger in decision 4, read
when it runs. Refused.

## Consequences

Positive:
- The platform keeps the property it was designed around: no money-moving command is a saga, no posting is separable from its domain
  state, and the operational model stays one deployable, one database, one set of runbooks.
- The extraction question has a written answer with its triggers, so a future extraction starts from evidence, not from this
  document's absence.

Negative:
- All modules still scale and deploy together; the database is still a shared failure domain (ADR-0001's stated cost, addressed by
  Phase 16's availability and recovery work, ADR-0129, ADR-0149, ADR-0150).
- The evaluation rests on two runs at three replicas on one machine. The ten-replica, contention and soak runs are owed (PI-025), and
  risk's latency beside the sweep is unread (PI-004). A result from any of them is read against decision 4.

Operational impact: none - no deployment, configuration or runbook changes.

Security impact: one process remains one blast radius. Card data is kept out of scope by tokenisation rather than by service
isolation, as ADR-0001 chose; its arrival is the named trigger. No service identity is needed while no service hop exists.

Financial impact: **the decisive factor, unchanged.** `INV-EVT-01` and `INV-LED-04` hold by construction rather than by
compensation; no reconciliation of the platform's own partial failures is introduced.

## Invariants / Constraints

`INV-EVT-01`, `INV-LED-04`, `INV-BAL-01`; ADR-0006's boundaries as `P15-REM-001` holds them - an extraction stays feasible because
they hold, and stays unbuilt because nothing measured demands it.

## Follow-up

- `P16-TSK-031` (the distributed-system audit and the operational review) reads this decision beside its own.
- Each owed run, when it runs (PI-025's; PI-004's storm), is read against decision 4; a trigger fired is an ADR before any change.
- `MODULE_ARCHITECTURE.md` §7 and M3, ADR-0001's Follow-up, ADR-0107 decision 4, `DECISIONS.md` §Deferred "Service extraction",
  `SECRET_MANAGEMENT.md`'s per-service identity row and `CURRENT_STATE.md`'s two debt rows carry this decision as dated notes.
- **Acceptance**: `Proposed` until the Phase 16 exit review (`P16-DOC-001`) reads it against the evidence on record then.
