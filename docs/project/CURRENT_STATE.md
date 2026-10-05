# Current Project State

**This document is the canonical description of where the project is.**
Conversation history is not. Read this first in every session
([`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Working Session Procedure).

**History lives in [`history/`](history/)** — per-task records, closed milestones, completed
capabilities and the change log. This document stays current; the archives stay archived.

Last updated: 2026-10-01 (`P8-TSK-021` — pull acquisition; **M8.7 at 1 of 4**, next `P8-TSK-022`) *(this line read `P8-TSK-002` from that gate until `P8-TSK-013`'s record found it — the stale-second-copy class, in the document's own dateline)*

---

## Current Phase

**Phase 0 — Domain and Architecture Foundation**
Status: ✅ **`COMPLETE`** (2026-09-04) — **all twelve exit criteria hold.**

**Phase 1 — Identity and Customer Foundation**
Status: ✅ **`COMPLETE`** (2026-09-09) — **all twelve universal and all six phase-specific exit
criteria hold**, ruled by the re-run of the exit review
([`reviews/PHASE_1_REVIEW.md`](reviews/PHASE_1_REVIEW.md), addendum of 2026-09-09, `P1-DOC-002`).

The path there is the gate model working as designed: the review of 2026-09-08 (`P1-DOC-001`)
failed the gate on two criteria; the remediations landed the same day (`P1-TSK-027`, `P1-TSK-029`);
and the phase stayed `IN_PROGRESS` for a day **on purpose**, because a phase becomes `COMPLETE`
when a **review** says so, never because its remediation landed. The re-run re-assessed all twelve
criteria with **recounted** evidence — the first review had three numbers wrong for inheriting them
— and found one more defect of the phase's recurring class on the way (`P1-TSK-033`, below).

**What the phase delivered**: a Party can exist, become a Customer, hold an Identity, prove it over
HTTP, hold a session with a recorded assurance level, and have every privileged action authorised
and audited — 17 published endpoints, 10 tables in two new schema-owning modules, 20 auditable
actions, 8 new invariants (72 platform-wide), 6 ADRs, 864 hermetic and 465 database tests, and no
money anywhere in it, by design.

**Two items leave the phase open-eyed rather than silently**: `P1-TSK-033`
(`POST /v1/me/credential`, planned and unbuilt, found by the re-run's recount) and the broker
adapter (owned now by the Phase 1 → 2 transition, whose phase holds the first consumers). Neither
is named by any exit criterion; both are recorded with owners.

**Phase 2 — KYC/KYB and Consent**
Status: ✅ **`COMPLETE`** (2026-09-13) — **all twelve universal and all six phase-specific
exit criteria hold**, ruled by the exit review
([`reviews/PHASE_2_REVIEW.md`](reviews/PHASE_2_REVIEW.md), `P2-DOC-001`). Entry gate passed
2026-09-09, all twelve criteria
([`reviews/PHASE_1_TO_2_TRANSITION.md`](reviews/PHASE_1_TO_2_TRANSITION.md)); started the same
day with `P2-TSK-001`, and closed four days later at **23 of 23** backlog items.

**Two criteria were not passing when the review opened, and the review closed both rather than
waiving them.** Criterion 3 wanted a mutation-register row for `INV-KYC-06`, which `P2-TST-001`
had deferred *in writing* to this review — landed, and **performed rather than inferred**
(the `P0-TSK-038` finding, applied to the last row of the set). Criterion 8 found two drifts in
`PHASE_2_PLAN.md` §11, one of them a **delivery guarantee the architecture deliberately
refuses to make**: the milestone table still promised *"exactly once per fact"* through Kafka,
which `P2-TSK-001`'s design corrected at the time in the backlog and here but not in the plan.
An adapter claiming exactly-once invites consumers to skip their inbox.

**The flip is itself the guarded act — and flipping it found an invariant nobody had
counted.** Since the transition's guard redesign, recording a phase `COMPLETE` changes what the
build demands, so the review's order was **land the row → flip the status → re-run the
full battery**. The battery then **failed**, naming one missing element: **`INV-HIST-02`**, which
is marked `Phase: 2 (screening), 5 (providers), 8 (files)` in the catalogue and therefore belongs
to Phase 2 while sitting in neither of the phase's named groups. **Phase 2 has eleven invariants,
not ten** — and the plan, the transition, this document and the review's own first draft all
said ten, because each counted the two groups the transition *created*. That is `P1-TSK-024`'s
finding repeating (`INV-AUD-03` was missed the same way): **a phase's invariants are what the
catalogue says they are, not what its plan remembers creating.** The property was never
unprotected — evidence has been retained verbatim since `P2-TSK-009` and the append-only
grant sits at `DB-PRIVILEGE` — what was missing was the record that the test has teeth, which
is exactly the gap the register exists to close. Row landed, demonstration **performed**, battery
green. Had the flip come after the final battery rather than before it, the phase would have been
recorded `COMPLETE` on a build about to fail, and the failure would have surfaced in Phase 3
attributed to whatever touched the tree first.

**What the phase delivered**: a Party verified to the standard a regulator requires, with the
evidence retained and the decision defensible — 2 new modules, 11 tables, 13 migrations, 13
endpoints, 9 auditable actions, 10 new invariants (**82** platform-wide, **11 in scope**), 4
ADRs, 1025 hermetic / 584 database / 14 kafka tests, and **no money anywhere in it, by design**.

Planned in [`PHASE_2_PLAN.md`](PHASE_2_PLAN.md): a Party verified to the standard a regulator
requires, with evidence retained and the decision defensible — KYC/KYB cases, screening with
human-resolved hits, encrypted access-audited documents, an append-only consent history with an
enforcement gate, and the platform's first broker adapter and consumer. Decisions in
ADR-0035…0038 (`Proposed`); properties in the new `INV-KYC-01`…`06` and `INV-CNS-01`…`04` groups
(**82 invariants** platform-wide). 24 backlog items across six milestones; the two items the exit
review left open — the broker adapter and `P1-TSK-033` — are scheduled first, in M2.1.

**The transition repaired its own gate machinery before using it**: both phase-derived guards
(`MutationDemonstrationTest`, `PlannedMetersExistTest`) keyed on the highest phase *named*, which
would have demanded Phase 2's meters and demonstrations at entry and silently dropped Phase 1's
plan from the checked set. They now key on phases recorded **`COMPLETE`**, so the status flip is
the guarded act — and `CURRENT_STATE.md` plus the phase plans are now declared build inputs,
because the probe that found this passed against a build that had not run (the `P0-TSK-023`
class, again).

*(The two paragraphs above had drifted to the end of this section, after Phase 5's;
moved back to Phase 2 by the Phase 6 review, `P6-DOC-001`.)*

**Phase 3 — Accounts and Financial Ledger**
Status: ✅ **`COMPLETE`** (2026-09-17) — **all twelve universal criteria, all eight F1–F8
supplement criteria — binding for the first time — and all sixteen phase-specific criteria
hold**, ruled by the exit review
([`reviews/PHASE_3_REVIEW.md`](reviews/PHASE_3_REVIEW.md), `P3-DOC-001`). Entry gate passed
2026-09-13, all twelve criteria
([`reviews/PHASE_2_TO_3_TRANSITION.md`](reviews/PHASE_2_TO_3_TRANSITION.md)); started the
same day with `P3-TSK-001` and closed four days later at **25 of 25** backlog items across
eight milestones, all `CLOSED`.

**The review's area 2 had a subject for the first time in the programme, and the posting was
walked**: an operator's correction as the economic event, propose-then-approve as the domain
operation, the approval's transaction as the financial transaction, the `ADJUSTMENT` journal
entry with the approver as its actor, balanced per-currency lines, and the balance reaching
the customer three ways that each say which number they are — every step naming its code and
its test. **The financial supplement F1–F8 was re-assessed at the gate rather than inherited
and every criterion is met**, F5 with its Phase-3 vacuity stated.

**The flip was the guarded act, and this time it surfaced nothing** — the review ran in the
`P2-DOC-001` order (assess → corrections → **flip** → full battery) and the post-flip battery
was green, because `P3-TST-003` had predicted the one failure the flip would have produced
(`INV-AUD-04`'s missing register row) and `P3-TSK-021` pre-paid it. The gate machinery found
its defect **before** the gate instead of at it. **One mutation survived across the whole
phase, correctly** (`P3-TSK-003`'s defence-in-depth predicate); zero survived wrongly.

**What the phase delivered**: money exists — a verified customer opens an account, receives
balanced immutable postings, sees the balance as a transactional projection, a
replay-from-zero derivation and a reconciling statement, holds funds against it, has mistakes
corrected by referencing reversals and four-eyes adjustments without one committed byte
changing, and closes the product with the accounting intact, while the trial balance is
continuously asserted zero per currency. 2 new modules, 8 tables, 13 migrations, 9 operations
on 7 new paths, 8 auditable actions all emitted, 2 permissions + 1 role, 5 event types, 5
aggregates, 4 ADRs (`Accepted`, platform → 42), 0 new invariants (the catalogue was written
for this phase; 19 in scope, 19 register rows), 138 mutations across 22 items with 1 correct
survivor, and **1121 hermetic / 683 database / 14 kafka tests** after the flip.

**The transition's four decisions carried the phase and are now `Accepted`**: `READ COMMITTED`
with postings as inserts and balance-dependent decisions taking the account lock (ADR-0039);
the flat typed chart (ADR-0040); the transactional projection no decision may read (ADR-0041);
the four account concepts (ADR-0042). **`DB-PRIVILEGE` finally carries `INV-LED-03` and
`INV-HIST-01`** — the mechanism built in `P0-TSK-022` met the tables it was built for.

**One item left the phase open-eyed rather than silently**: plan §9 declared
`GET /v1/ledger/accounts/{id}` and `GET /v1/ledger/trial-balance` behind a `LEDGER_READ`
permission, and none of the three was built or owned by any task — the recurring
unowned-declaration class, found by the review's hand-diff. **Ruled by the Phase 3 → 4
transition: the declaration is struck** — the trial-balance capability exists as the
continuous job and gauge, and an operational read surface with no consumer is dead contract;
it arrives with the operator tooling that consumes it, as its own decision.

**Phase 4 — Internal Transfers**
Status: ✅ **`COMPLETE`** (2026-09-19) — **all twelve universal criteria, all eight F1–F8
supplement criteria re-assessed at the gate, and all sixteen phase-specific criteria hold**,
ruled by the exit review ([`reviews/PHASE_4_REVIEW.md`](reviews/PHASE_4_REVIEW.md),
`P4-DOC-001`). Entry gate passed 2026-09-17, all twelve criteria
([`reviews/PHASE_3_TO_4_TRANSITION.md`](reviews/PHASE_3_TO_4_TRANSITION.md)); started the
same day with `P4-TSK-001` and closed two days later at **14 of 14** backlog items across
eight milestones, all `CLOSED`.

**Area 2 had a transfer to walk, which is what this phase was for**: a customer's instruction
as the economic event, `TransferExecution` as the domain operation, one local transaction as
the financial transaction (ADR-0043), a `POSTING` journal entry whose reference carries the
transfer id, balanced per-currency lines debiting the source wallet and crediting the
destination, both balances moving as a transactional projection, and a reversal that corrects
by referencing rather than editing — every step naming its code and its test. **The financial
supplement F1–F8 was re-assessed at the gate rather than inherited, and every criterion is
met**, F5 with its Phase-4 reading stated: no external event produces a financial effect here,
and the mechanism that will bind is the Phase 0/2-proven inbox.

**The flip surfaced nothing, and that was pre-paid twice rather than lucky**: `P4-TST-002`
landed a register row for every invariant the catalogue marks `Phase: 4` and **probed the
flip** — simulated `COMPLETE`, battery green, then one row removed to prove the demanded set
had grown — and `P4-TSK-011` landed §15's meters with a pinned guard that the derived one
takes over at the flip with no edit. The gate machinery found its work done before the gate
instead of at it, for the second phase running.

**The review's own findings were two, both in the record rather than the code**: the
component register had no `transfers.beneficiary` row — the register-decay class's **fifth**
occurrence and the first *inside* a phase rather than at its boundary — and `P4-TSK-008`'s
backlog block carried *Completion notes* where every sibling carries *Gate evidence*, with its
eight-mutation sweep written only into this document. Both corrected in the review.

**One criterion is met with a recorded deviation, stated rather than glossed**: criterion 7
asks for the full suite against real infrastructure, and the owner's standing instruction
skips `build databaseTest kafkaTest`. The hermetic tier — which is where the flip's own
guards live — was run **fleet-wide**; the database and kafka tiers were verified per task by
targeted suites throughout. **No fleet-wide database or kafka count is claimed for this
phase.**

Planned in [`PHASE_4_PLAN.md`](PHASE_4_PLAN.md): **the first customer-visible money
movement** — a verified customer moves funds between two platform accounts, with the
ADR-0044 lifecycle, idempotency at the financial boundary, every command audited, a
privileged reasoned reversal, second-factor beneficiary creation, and the limit/risk
**seams** as compiler-required parameters Phase 13 will implement. Decisions in ADR-0043
(the transfer and its posting commit in **one transaction** — no internal saga; unresolved
question 5 closed) and ADR-0044 (four states, each earned by a producer — no `PROCESSING`,
no `CANCELLED`, no fiction), both `Accepted` at this gate. 14 backlog items across 8 milestones
(M4.1–M4.8); the in-scope invariants are whatever the catalogue marks `Phase: 4` — **five**
at planning time (`INV-IDEM-01` transfers element, `INV-CON-02`, `INV-LIFE-01/-02/-04`), no
new group needed for the second transition running. Deliberately the *easy* half of moving
money — both legs internal, no third party — so Phase 5 changes one variable at a time.

**Phase 5 — Payment Infrastructure**
Status: ✅ **`COMPLETE`** (2026-09-21) — **all twelve universal criteria, all eight F1–F8
supplement criteria, and all nineteen phase-specific criteria hold** (8 original + 11 added
by the Phase 4 → 5 transition, counted from the gate at review time), ruled by the exit
review ([`reviews/PHASE_5_REVIEW.md`](reviews/PHASE_5_REVIEW.md), `P5-DOC-001`). Entry gate
passed 2026-09-20, all twelve criteria
([`reviews/PHASE_4_TO_5_TRANSITION.md`](reviews/PHASE_4_TO_5_TRANSITION.md)); started the
same day with `P5-TSK-001` and closed at **21 of 21** backlog items across nine milestones.

**Two criteria were not free, and the review did the work rather than asserting it.**
Criterion 10 demanded every ADR `Accepted` while ADR-0045…0049 all read `Proposed`, deferred
across the phase with this audit named as owner: each was **read against the code that now
exists** — ADR-0046's leaderless sweeper, ADR-0047's evidence-first door, ADR-0048 §4's
hold-then-post down to the posting key — found to describe it, and accepted. Criterion 7
cannot be met as written while the owner's standing instruction skips
`build databaseTest kafkaTest`: assessed `PASS` **with the deviation recorded** — the
hermetic tier run fleet-wide after the flip, the database and kafka tiers verified per task,
and **no fleet-wide count claimed**.

*(The deviation was then **closed by the Phase 5 → 6 transition** the same day: the full
battery ran fleet-wide — 1323 hermetic / 829 database / 14 kafka, 0 failures after one
repair — so Phase 5, like Phase 4, ends with a genuine fleet-wide count after all.)*

**The flip was the guarded act, and it surfaced nothing because three items pre-paid it**:
`P5-TST-002` landed every `Phase: 5` invariant row and probed the flip, `P5-TSK-017` landed
§15's meters behind a pinned guard the derived rule takes over with no edit, and
`P5-TST-003` closed the one item the probe deliberately left red. Proven non-vacuous against
the real status afterwards, not only the simulated one.

**What the phase delivered**: money that enters and leaves through a party that can fail in
every way a third party can — attach, pay, confirm, capture, refund, webhooks, and a
leaderless reconciliation sweeper — with an honest `*_UNKNOWN` state, an idempotency
reference stored before anything is sent, a bounded refund that reserves the customer's
funds, and a ledger whose first touch is capture. 2 owning modules, 8 tables, 8 migrations,
6 payment operations plus the machine-facing webhook door, 6 auditable actions, 9 error
codes, 7 event types, 6 meters, 5 ADRs (`Accepted`), 11 `Phase: 5` invariants of the
platform's 87, and **no raw PAN anywhere by construction**. The external
world arrives: money movement whose outcome is decided by an unreliable third party, with
`INV-LIFE-03` live for the first time. Planned in [`PHASE_5_PLAN.md`](PHASE_5_PLAN.md);
decisions in ADR-0045–0049 (`Accepted` at the exit review): the intent/attempt model and its three machines,
**no transaction spans a provider call** (dispatch-before-call, `UNKNOWN` modelled,
reconciliation by query with no lease), webhooks (authenticated before parsing,
freshness-bounded, evidence-first, order-blind), **authorization is a payment-domain fact
and the ledger's first touch is capture** (unresolved question 6 closed — DR `PSP_CLEARING`
/ CR wallet, with the refund holding its funds at dispatch), and the first provider — a
simulated card-style PSP whose finality is nothing-final-before-settlement (question 9
closed; `INV-REV-03` stays subjectless until the second rail). The transition catalogued
**`INV-PAY-01`…`05`** — Phase 5's gate properties given stable IDs before code is written
against prose, the `INV-IDN`/`INV-KYC` precedent — taking the platform to **87
invariants**; the in-scope set is whatever the catalogue marks `Phase: 5`, **eleven** at
planning time. 21 backlog items across nine milestones (M5.1–M5.9);
[`PAYMENT_LIFECYCLES.md`](../domain/PAYMENT_LIFECYCLES.md) rewritten from its stub to the
decided model. First task: **`P5-TSK-001`**, `READY`.

*(The two paragraphs above sat under the Phase 6 heading from the Phase 5 → 6
transition until the Phase 6 review, `P6-DOC-001`, moved them back to the phase they
describe.)*

**Phase 6 — Checkout and Merchant Platform**
Status: ✅ **`COMPLETE`** (2026-09-24) — **all twelve universal criteria, all eight F1–F8
supplement criteria re-assessed at the gate, and all sixteen phase-specific criteria hold**
(6 original + 10 added by the Phase 5 → 6 transition, counted from the gate at review time),
ruled by the exit review ([`reviews/PHASE_6_REVIEW.md`](reviews/PHASE_6_REVIEW.md),
`P6-DOC-001`). Entry gate passed 2026-09-21, all twelve criteria
([`reviews/PHASE_5_TO_6_TRANSITION.md`](reviews/PHASE_5_TO_6_TRANSITION.md)); started the
same day with `P6-TSK-001` and closed at **18 of 18** backlog items across seven milestones.

**The review found more than it was sent, and waived none of it.** Routed eight inputs, it read
every ADR, register and document against the code and mapped every gate criterion, failure
scenario and contended decision to its test. It found a suspended merchant's open offer still
payable, a unique index ADR-0053 named and no migration built, every paid order announced as
`PAYMENT_PENDING`, a zero never-received bound accepted, five confined credentials naming
variables nothing binds, no pin on which permission a route requires, the session's claim shared
across merchants and its records naming no key, the red chart test in two stale copies (with a
recorded fix that would have dropped a seeded account), the four-eyes cooling-off proven by a
read rather than a dispatch, four contended decisions with no race test, and drift in six of
eight ADRs. Each was corrected here, and each correction was broken on purpose to prove its
test: eighteen probes, seventeen caught, the one survivor a correct second rank.

**The flip was the guarded act, and it surfaced nothing** — `P6-TST-002` had simulated it — and
it was proven non-vacuous against the real status: with `INV-HIST-04`'s rows removed the guard
fails naming it and reporting `(currently 6)`.

**Criterion 7 carries the recorded deviation**: the hermetic tier fleet-wide after the flip
(**1550 tests across 14 modules, 0 failures**) and the database tier over every merchant and checkout suite, the chart suite and
the conservation suites (**224 tests across 23 suites, 0 failures**); **no fleet-wide database or kafka count is claimed**, and the
Phase 6 → 7 transition inherits the full battery.

**What the phase delivered**: a merchant the platform does not own can sell through it and be
paid — onboarded behind KYB, priced by an immutable versioned schedule pinned when the offer is
made, credited the gross and charged its fee in the capture's one entry, refunded out of its own
payable, and paid out under the bound to a destination two operators approved — with tenancy in
every statement. 2 owning modules, 17 tables, 14 migrations, 29 operations on 25
paths, 23 auditable actions, 22 error codes, 8 event types, 7 meters, 5 permissions and 1 role,
8 ADRs (`Accepted` at the review; ADR-0055 is cross-cutting and the owner's to accept), and 9
`Phase: 6` invariants of the platform's 94.

**Confirmed by the Phase 6 → 7 transition's independent audit, after repair** (2026-09-24,
[`reviews/PHASE_6_TO_7_TRANSITION.md`](reviews/PHASE_6_TO_7_TRANSITION.md)). The second pass found
what the review had not: **two CRITICAL defects** — a taken-over refund whose re-send met a refused
connection was concluded `FAILED` and its hold released, though the first send may have paid; and
a customer could close the wallet an open top-up credits, the card then captured into a posting
the ledger refused — and **nine IMPORTANT ones**. Under the gate's rule that no CRITICAL or
IMPORTANT Phase 6 defect crosses a phase boundary, each was repaired, tested and broken on purpose
(thirty-three probes, all caught), and the gate was run again with the full battery fleet-wide,
before repair and after. The status did not move; the review's verdict now rests on a corrected
phase.

**Phase 7 — Cards, Wallets, A2A and Instant Payments**
Status: ✅ **`COMPLETE`** (2026-09-28) — **all twelve universal criteria, all eight F1–F8
supplement criteria re-assessed at the gate, and all twenty-one phase-specific criteria hold**
(5 original + 16 added by the Phase 6 → 7 transition, counted from the gate at review time),
ruled by the exit review ([`reviews/PHASE_7_REVIEW.md`](reviews/PHASE_7_REVIEW.md), `P7-DOC-001`)
— criterion 7 and the Testing bullet's fleet-wide battery **with the deviation recorded** (the
owner's standing instruction; the Phase 7 → 8 transition inherits the run). Six read-only audits
found a withdrawal posting to a clearing position it named, a "recorded descriptor" that was one
integer while every resolver read the build (ruled: a rail's money semantics are frozen per
`RailId`), bank details printable by three port records, an unstated lock-order dependency
(ruled and guarded), a debt row closed unpaid, nineteen gate claims with a partial or missing
test and drift in all four ADRs — every one corrected and probed (twenty-four runs, twenty-three
caught at once, the survivor a test weaker than its name, tightened and caught), and ADR-0059…0062
`Accepted`. (Started 2026-09-26 with `P7-TSK-001`; entry gate passed 2026-09-24,
all twelve criteria, by the Phase 6 → 7 transition ([`reviews/PHASE_6_TO_7_TRANSITION.md`](reviews/PHASE_6_TO_7_TRANSITION.md)).) Planned
in [`PHASE_7_PLAN.md`](PHASE_7_PLAN.md); decisions in ADR-0059…0062 (`Accepted` at the review): a payment rail
declares its capabilities and the domain acts on them, never on a rail's name — three interaction
models (two-step, push, book), finality modelled per rail, and each external rail its own clearing
position; routing is a versioned policy decided once per payment, pinned and explainable, and
never re-routed after an ambiguous dispatch; a dispute is its own lifecycle whose chargeback never
takes more than the capture credited, every stage posting once; account-to-account payments run on
a provider-neutral push rail, bank details and aliases never enter, and an instant payment is final
on acceptance and settled on the scheme's cycle. Card issuing is external, and the wallet stays in
`accounts`. The transition catalogued **`INV-RAIL-01`…04 and `INV-DSP-01`…03**, taking the
platform to **101 invariants**; the in-scope set is whatever the catalogue marks `Phase: 7`, **ten**
at planning. 18 backlog items across eight milestones (M7.1–M7.8);
[`RAIL_AND_DISPUTE_LIFECYCLES.md`](../domain/RAIL_AND_DISPUTE_LIFECYCLES.md) states the machines;
context 29, Disputes, merged into `payments`. **18 of 18 items complete** (2026-09-28):
M7.1–M7.8 closed — the rails declared and routed, the card rail completed, the instant rail,
pay-ins by bank with their returns, the wallet as an instrument, **disputes end to end**
with `P7-TSK-014`, **observability and demonstration** with the rail and dispute meters
(`P7-TSK-015`), the multi-rail conservation storm (`P7-TST-001`) and the dispute battery
(`P7-TST-002`), and **the gate** with `P7-DOC-001`, the exit review; then **the Phase 7 → 8
transition** (below). *(This sentence said "first task `P7-TSK-001` complete, next `P7-TSK-002`
`READY`" until `P7-TSK-012`'s gate found it ten tasks stale — the stale-second-copy class a sixth
time.)*

**Confirmed by the Phase 7 → 8 transition's independent audit, after repair** (2026-09-28,
[`reviews/PHASE_7_TO_8_TRANSITION.md`](reviews/PHASE_7_TO_8_TRANSITION.md)). Eight read-only
audits, every finding verified adversarially, found what the review had not: **two CRITICAL
defects** — a withdrawal's own confirmation echoed to the pay-in door parked as INBOUND value for
money that went out, and the pay-in inquiry sweep crediting an amount the scheme had not executed
two minutes after the callback refused it — and **twelve IMPORTANT ones** (one execution credited
and parked with no arbiter, value on a concluded pay-in dropped, an expired initiation asked for
ever, a poison delivery, the parking's missing facts, authorizations left standing, a merchant
closed with a winnable chargeback, a customer's grant concatenated into a credentialed request, an
undispatched checkout intent confirmable around its checks, a dispute deadlock across two entries,
a permit renewal that could stand still, and the provider hop's TLS enforced nowhere), plus a
second presentment absorbed as a repeat. Under the gate's rule, each was repaired, tested and
broken on purpose — thirty-four probe runs over thirty-three breaks, thirty-three caught at once,
the survivor a rule no test drove, tightened and caught — together with the MINOR correctness,
concurrency and security findings; the rest recorded with owners. The fleet-wide battery the phase
had deferred ran before repair (green but for three failures the harness's 512 MiB heap caused,
repaired first) and after: `build databaseTest kafkaTest` again, fleet-wide: `build` green at 1745 hermetic tests across 14 modules, 0 failures, re-run after the last document landed; the database tier at 1241 tests across 149 suites, 0 failures — its first post-repair run failed one test and the app tier's re-run a second, each repaired and re-run (the transport guard refused the `false` that switches a provider off; the storm's harness PSP could not answer the void the card redirect now sends) before the app tier re-ran whole and fresh at 1077 tests across 130 suites; and the kafka tier at 14 tests across 4 suites, 0 failures. The status did not move; the
review's verdict now rests on a corrected phase.

**Phase 8 — Settlement and Reconciliation**
Status: ✅ **`COMPLETE`** (2026-10-01) — **all twelve universal criteria, all eight F1–F8
supplement criteria re-assessed at the gate, and all twenty-eight phase-specific criteria hold**,
ruled by the exit review ([`reviews/PHASE_8_REVIEW.md`](reviews/PHASE_8_REVIEW.md), `P8-DOC-001`) -
criterion 7 and the Testing bullet with their recorded deviation (no fleet-wide database or kafka
count, on the owner's standing instruction). Entered `IN_PROGRESS` 2026-09-28 (`P8-TSK-001`) — entry
gate: all twelve criteria hold, by the Phase 7 → 8 transition
([`reviews/PHASE_7_TO_8_TRANSITION.md`](reviews/PHASE_7_TO_8_TRANSITION.md) §13).
Planned in [`PHASE_8_PLAN.md`](PHASE_8_PLAN.md); decisions in ADR-0064…0073 (`Accepted` by the exit review; ADR-0063
is `X-TSK-005`'s): settlement holds evidence and reconciliation holds expectations;
each clearing position is discharged in two evidence hops, the counterparty's report and then the
bank; every settling completion opens its expectation in the transaction that posted it; files are
screened, authenticated and stored encrypted; matching is versioned, pinned and snapshotted per
decision with no tolerance on an amount in a position; fourteen break types with an immutable
lifecycle; suspense owned, aged and never permanent; resolutions four-eyes through the ledger's
adjustment machinery; amounts never enter metrics; a payout return applied from settlement
evidence. The transition catalogued nine invariants, taking the platform to **110**, and the
Phase 8 set is **twenty-two**. 27 backlog items across eight milestones (M8.1–M8.8);
[`SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md`](../domain/SETTLEMENT_AND_RECONCILIATION_LIFECYCLES.md)
states nine machines and one born-once fact. **27 of 27 items complete** (M8.1 `CLOSED` at 3 of 3,
M8.2 `CLOSED` at 4 of 4, M8.3 `CLOSED` at 6 of 6, M8.4 `CLOSED` at 2 of 2, M8.5 `CLOSED` at 1 of 1,
M8.6 `CLOSED` at 4 of 4, M8.7 `CLOSED` at 4 of 4, M8.8 `CLOSED` at 3 of 3): the modules
and floors (`P8-TSK-001`), the source register, the file store and the door screen
(`P8-TSK-002`), the upload door and attestation (`P8-TSK-003`), the expectation register with
the card openers (`P8-TSK-004`), every other opener with the register that proves them
(`P8-TSK-005`), the adjustment closure (`P8-TSK-006`), the opening position with the two
published verdicts (`P8-TSK-007`) and the PSP format with the parse leg and the decline
(`P8-TSK-008`), the acceptance — hop 1's recognition, the remittance expectation and
the reconciliation intake (`P8-TSK-009`), the break and suspense records with their
writers, proofs and gauges (`P8-TSK-010`) the matcher — ordered allocation with
decision snapshots, the run leg and the explanation doors (`P8-TSK-011`) — and the fee
checks and counterparty corrections with the first `EVIDENCED` resolutions (`P8-TSK-012`) —
and grace, ageing, rematch and late evidence, time made explicit, closing the milestone
(`P8-TSK-013`) — and the investigator's desk: break reads, the case file, the trace
and the settlement-status trail (`P8-TSK-014`) — and four-eyes resolution through the
ledger, every break type now closable, closing the milestone (`P8-TSK-015`) — and the bank
statement: cash recognised against each attributed position, remittances matched, the cash
proof holding, closing M8.5 (`P8-TSK-016`) — and the instant scheme's cycle report,
`INSTANT_CLEARING` discharged by the scheme's own evidence per cycle and to cash through the bank,
opening M8.6 (`P8-TSK-017`) — and the payout provider's report, `PAYOUT_CLEARING` discharged by
the provider's evidence and the bank's debit (`P8-TSK-018`) — and payout returns, a merchant fact
applied from that evidence by a leaderless worker, the payable restored once (`P8-TSK-019`) — and
Phase 7's unmatched confirmations owning their suspense, adopted once, released only by a person,
closing M8.6 (`P8-TSK-020`) — and pull acquisition: each source's evidence fetched over its own
confined credential and accepted without a second person, every silent source visible, opening
M8.7 (`P8-TSK-021`) — and rule-set administration, reprocessing, readmission, requeue and
replay (`P8-TSK-022`), batch repudiation (`P8-TSK-023`) and the meters, dashboard row and
operator reports (`P8-TSK-024`), closing M8.7 — and the settlement and reconciliation storm
(`P8-TST-001`), the break and resolution battery (`P8-TST-002`) and the exit review that
ruled the phase complete (`P8-DOC-001`), closing M8.8. *(This count read "21 of 27 … next
`P8-TSK-022`" from the flip until the Phase 8 → 9 transition's records found it — the
stale-second-copy class, again in the copy beside the line the flip did update.)* *(Its count read "15 of 27 … M8.4 `CLOSED`"
through `P8-TSK-016`'s gate, until `P8-TSK-017`'s record found it — the stale-second-copy class,
in the count beside the sentence that gate did update.)* *(This paragraph read "2 of 27 items complete
(M8.1 at 2 of 3) ... next `P8-TSK-003`" through `P8-TSK-003`'s and `P8-TSK-004`'s gates, until
`P8-TSK-005`'s record found it: the stale-second-copy class again, in the paragraph neither gate's
record reached.)*

**Phase 9 — FX and Cross-Border Payments**
Status: 🔵 **`IN_PROGRESS`** (2026-10-03, `P9-TSK-001`) — entry gate: all twelve criteria hold, by the Phase 8 → 9
transition ([`reviews/PHASE_8_TO_9_TRANSITION.md`](reviews/PHASE_8_TO_9_TRANSITION.md) §13).
Planned in [`PHASE_9_PLAN.md`](PHASE_9_PLAN.md); decided in ADR-0074…0083 (`Proposed`); the
machines in [`FX_AND_CROSS_BORDER_LIFECYCLES.md`](../domain/FX_AND_CROSS_BORDER_LIFECYCLES.md):
the platform is principal; a quote is a frozen posting plan priced by one pure function, the
conversion booked at acceptance in one local transaction with a decoupled back-to-back cover;
cross-border payments are holds and corridor acceptances, beneficiary screening before pricing,
returns credited in the currency received; clearings are keyed by counterparty; callbacks are
hints; reconciliation never converts currency and gains causes, never types. Ten new invariants
and thirteen restated take the platform to **120**. Thirty backlog items across nine milestones
(M9.1–M9.9) plus `X-TSK-013`…`-015`. **12 of 30 items complete** (M9.1, M9.2 and M9.3 closed): the modules and floors
(`P9-TSK-001`), the conversion arithmetic (`P9-TSK-002`), JPY and BHD postable (`P9-TSK-003`), multi-currency
wallets (`P9-TSK-004`), reference rates (`P9-TSK-005`), the FX provider port (`P9-TSK-006`), the
pricing policy (`P9-TSK-007`), the quote (`P9-TSK-008`), wallet conversion (`P9-TSK-009`),
counterparty-keyed clearing positions (`P9-TSK-010`), the FX provider's source (`P9-TSK-011`) and the
FX cover (`P9-TSK-012`); next **`P9-TSK-013` — FX explained and settled to cash** — `READY`
([§Current Task](#current-task) is kept current).

## Current Milestone

The active milestone is the one named in [§Current Task](#current-task) below, which is the
section this document keeps current. The closed milestone records for Phases 0-4 — every
milestone's stated acceptance and the demonstration that met it — are archived verbatim in
[`history/MILESTONE_HISTORY.md`](history/MILESTONE_HISTORY.md).

*(Until 2026-09-20 this section carried those closed records inline, under two separate
`## Current Milestone` headings — a structural drift that made "current" mean "every milestone
since M0.1". Moved, not edited.)*

## Current Task

**`P9-TSK-013` — FX explained and settled to cash** — `READY`: marked by
`P9-TSK-012`'s completion gate (2026-10-05). **Not started.**

### Just completed

**`P9-TSK-012` — The FX cover** — `COMPLETE` (2026-10-05). **M9.3 CLOSES AT 4 OF 4: each accepted
quote is covered with its provider exactly once, however the provider answers** (ADR-0077 §§2-9,
PHASE_9_PLAN.md §§12.4(b)/(f) and 12.5; `INV-FX-06`, `INV-FX-08`, `INV-FX-09`, `INV-PAY-04`, `INV-LIFE-03`).
**`fx V007`**: `cover_execution` - PK `cover_id`, `UNIQUE (provider_code, provider_trade_ref)`, `UNIQUE
journal_entry_id`, the plan's legs copied from the quote and checked at birth for every writer, realised =
executed - plan and `executed_off_plan` by `CHECK`, append-only but for the entry attached once (a deferred
trigger refuses a commit without it); the cover machine restated - `-> EXECUTED` only with the fact,
`REJECTED -> DISPATCHED` only once attempt n+1's `T(n+1)` is stored, `requote_failures` (the backoff) and
`caused_by_event_id` (the acceptance). **Ledger `V023`**: `FX_REALISED_GAINS`/`FX_REALISED_LOSSES` in five
currencies, closed to free adjustment. **The domain**: `CoverLines` (the plan's legs closed exactly onto the
provider's OWN clearing, the difference realised in that leg's currency - §12.4(b) and (f) posted
exactly); `FxCoverOutcomes` (T-d: the answer, the inquiry and the hinted inquiry through one applier under
the lock order, only the current attempt's definitive answer concluding, the execution fact, the entry
`fx-cover:<id>`, both leg expectations through `FxSettlementExpectations`, `fx.FxCoverExecuted`/`Rejected`
and the `fx.CoverExecuted` audit - one commit); `FxCoverDispatch` (send and re-send the SAME `T`, inquire an
`UNKNOWN` cover first, requote only after a definitive rejection at a fresh firm quote inside the band of the
quote's pinned policy against a fresh reference, void an unwanted rejected cover - no transaction spans a
provider call). **The composition**: `FxCoverSchedule` (leaderless, one claiming statement, `FOR UPDATE SKIP
LOCKED`), the post-commit `FxCoverNudge`, `FxCoverMetrics` (`finapp.fx.cover{provider,type,outcome}`,
`.latency`, `.unknown.active`/`.age`, `.open.age`, `.sweeper.enabled`), and the callback door `POST
/v1/providers/fx/webhooks` (HMAC under `FINAPP_FX_WEBHOOK_KEY`, freshness, evidence first, the inbox, then an
authenticated inquiry - a callback is a hint). **Carried from `-011`, resolved**: a cover leg's reconciliation
key is qualified by its currency at both sides (`CoverLegKey`), so a cover's two legs sharing `T` hold two
keys and both settle. **THE BUILD'S FINDS, FIXED**: a requote race loser was reported as a refusal; every leg
now runs in the cover's own correlation and actor scope (the posting, inbox and idempotency layers require
them); the simulator's trade references were reused across instances (a real provider never reuses one).
**PROBES** (TWELVE PROBES, TWELVE CAUGHT - X11, the sweep claiming whatever the permit's age, SURVIVED its first run because the ten-sweeper race claims within one instant; a pacing assertion was added to `FxCoverRaceDatabaseTest` and caught it on the re-run), every restore byte-identical (sha256-verified; `MUTATION_TESTING.md` §2 +11 rows). **Multi-instance PASS** - ten sweepers send one cover once; a lost response plus ten
sweepers and ten inquirers leave one execution, one fact, one entry; ten appliers of one rejection one
successor; ten appliers of one answer one fact; ten callback deliveries plus a sweep one effect; the PK and the
trade-reference unique refuse with every trigger off (all counted). **NEXT**: `P9-TSK-013` `READY`.
**Verified** by fresh runs - the fleet-wide hermetic tier 2452 across 399 suites and 18 modules; the architecture tier 155 across 27; the
app hermetic tier with every document guard 714 across 134 within it; the task's own database suites run green while built - FxCoverDatabaseTest 10, FxCoverRaceDatabaseTest 4, FxCallbackDuplicateDatabaseTest 4, the conversion and FX source suites, fx database 60 across 12 and FxMatchingDatabaseTest 4 - and the twelve probes against them; the final database tiers SKIPPED on the owner's instruction (2026-10-05: the code assumed correct, refactored after the phase), ALL 0 FAILURES
- the fleet-wide database and kafka tiers likewise skipped.

### Previously

The per-task completion records — 205 blocks, from `P9-TSK-011` back to project initiation
(`X-TSK-016` cross-cutting, standing between `P9-TSK-011` and `P9-TSK-010`; `X-TSK-005` cross-cutting, standing between `P7-TSK-015` and `P7-TSK-014`; `X-TSK-004`
cross-cutting, standing between `P7-TSK-001` and the Phase 6 → 7 transition) — are archived in
[`history/TASK_HISTORY.md`](history/TASK_HISTORY.md).
*(This pointer read "130 blocks, from `P6-TSK-005`" through four archivals — corrected by
`P6-TSK-015`'s gate — and then "145 blocks, `X-TSK-004` newest" through five more, corrected
by `P7-TSK-007`'s gate: the stale-second-copy class, this time in the pointer whose last
correction note was sitting right beside the staleness. It then read "183 blocks, from
`P8-TSK-019`" through `P8-TSK-020`'s and `P8-TSK-021`'s archivals — corrected by
`P8-TSK-022`'s gate, the same class a third time. It then read "190 blocks, from `P8-TST-002`"
through the Phase 8 exit review, the transition and `P9-TSK-001`…`-003`, while
`TASK_HISTORY.md`'s own header moved on - corrected by `P9-TSK-004`'s gate, a fourth time.)*
Each records what the task delivered, the mutations performed, and the findings made on the way.

---

## Completed Capabilities

**Business capabilities: none in Phase 0** — by design. What each closed phase delivered is
archived verbatim in
[`history/COMPLETED_CAPABILITIES.md`](history/COMPLETED_CAPABILITIES.md).

## Active Work

**Phase 8 is `IN_PROGRESS`** (2026-09-28) — 21 of 27 items complete; **M8.1 `CLOSED` at 3 of 3;
M8.2 `CLOSED` at 4 of 4; M8.3 `CLOSED` at 6 of 6; M8.4 `CLOSED` at 2 of 2; M8.5 `CLOSED` at 1 of 1;
M8.6 `CLOSED` at 4 of 4; M8.7, Operating it, at 1 of 4** — every counterparty's evidence discharges
its clearing, each net reaching cash through the bank, every unmatched confirmation owns its
suspense, and each source's evidence is pulled over its own credential, every silent source
visible; next `P8-TSK-022`, rule-set administration, reprocessing, readmission, run requeue and
replay ([§Current Task](#current-task) is kept current). *(This paragraph read "10 of 27 … next
`P8-TSK-011`" from `P8-TSK-010`'s gate until `P8-TSK-013`'s record found it — the
stale-second-copy class, again in a paragraph no gate's record reached.)* Phase 7 is `COMPLETE` — 18 of 18
items, M7.1–M7.8 closed, ruled by `P7-DOC-001` and confirmed after repair by the Phase 7 → 8
transition ([`reviews/PHASE_7_TO_8_TRANSITION.md`](reviews/PHASE_7_TO_8_TRANSITION.md)). *(This
paragraph read "6 of 18 items complete; M7.1, Rail foundations, opens at 1 of 3 (`P7-TSK-001`).
Next: `P7-TSK-002`" — its count last touched by `P7-TSK-006`, its milestone and pointer never after
`P7-TSK-001` — until `P7-TSK-014`'s gate found it: the stale-second-copy class a seventh time, in
the section `P7-TSK-012`'s repair of the current-phase sentence did not reach.)*

**Phase 6 is `COMPLETE`** (2026-09-24) — 18 of 18 items across seven milestones, ruled by
`P6-DOC-001`'s exit review and confirmed after repair by the transition's independent audit: M6.1 `CLOSED` at 3 of 3; M6.2 at 2 of 2 (`P6-TSK-004`, `-005`); M6.3
at 4 of 4 (`P6-TSK-006`…`-008`, `-014`); M6.4 at 3 of 3 (`P6-TSK-009`, `-010`, `-015`); M6.5 at
2 of 2 (`P6-TSK-011`, `-012`); M6.6 at 3 of 3 (`P6-TSK-013`, `P6-TST-001`, `P6-TST-002`); **M6.7
`CLOSED` at 1 of 1** (`P6-DOC-001`). **Phase 5 is `COMPLETE`** (2026-09-21), ruled by `P5-DOC-001` and
confirmed by the Phase 5 → 6 transition's independent audit.

**Cross-cutting — `X-TSK-001`, Lombok adoption** (2026-09-23; it belongs to no phase, gates no
phase exit and does not displace the phase's current task). **`COMPLETE`** (2026-09-24).
- **Done:** Lombok is the project standard for Java boilerplate, at compile time only
  (ADR-0055, `Proposed`; `.claude/rules/java-lombok.md`). The Phase 1–6 code is converted: 152
  production classes in nine module batches, each proved byte-identical by `javap` (0
  differences; 428 null checks before and after). New code follows the rule.
- **Closed:** acceptance criterion 5, all tiers green. The one failure predated the task and the
  Phase 6 review fixed it (`P6-DOC-001`); the fresh full database tier the criterion waited on ran
  at the Phase 6 → 7 transition, on untouched `82b2179` (1027 database tests, 0 failures, beside
  1550 hermetic and 14 kafka) and again after its repairs, so `X-TSK-001` is `COMPLETE` with no
  further work, as recorded. ADR-0055 stays `Proposed`, its acceptance the owner's. The plan and its results are
  [`tasks/CROSS-CUTTING-LOMBOK-REFACTOR.md`](tasks/CROSS-CUTTING-LOMBOK-REFACTOR.md) §21.

**Cross-cutting — `X-TSK-004`, a second verified contact channel refused** (2026-09-24,
owner-directed; it belongs to no phase and does not displace `P7-TSK-001`). **`COMPLETE`**
(2026-09-24). It pays the Phase 15 debt row the transition recorded: a verification that would give
an identity a second verified channel of a kind is `409 identity.VerifiedChannelAlreadyExists`
rather than a `500`. The refusal writes nothing, and recovery stays on the channel verified first
(`INV-IDN-06`). The narrative is in [`history/TASK_HISTORY.md`](history/TASK_HISTORY.md), the entry
in [`BACKLOG.md`](BACKLOG.md).

**Cross-cutting — `X-TSK-005`, database fixtures stamp from the clock that judges them**
(2026-09-27, owner-directed, chipped by `P7-TSK-014`'s gate; it belongs to no phase and does not
displace `P7-TSK-015`). **`COMPLETE`** (2026-09-27).
- **The fixtures.** Every `now()` in the database suites (464 reads) was mapped to its table,
  column and judge. Ten suites were fixed in three shapes:
  - a database stamp the domain later judged (`P7-TSK-014`'s shape, again in the tenancy battery);
  - two clocks in one statement, which let the bank-detail and self-approval refusals pass on the
    wrong constraint (demonstrated);
  - two database reads with a second or less between them, on a clock measured stepping back
    1.7 s at once.
- **A pre-existing leak.** It was not a clock: `PaymentRefundDatabaseTest` failed deterministically
  after `DisputeNotificationDatabaseTest` in one JVM, on `capture_provider_reference UNIQUE`. It was
  reproduced on unmodified `HEAD`, then fixed.
- **The production question**, decided and not implemented: **ADR-0063** (`Proposed`) says the
  database orders an aggregate's facts, and a later fact's stamp is `max(now, latest)`, so the
  twenty-one ordering `CHECK`s hold by construction on every instance. `X-TSK-006` is `PLANNED`
  and waits on the owner's acceptance.
- **Verified** by fresh runs: 106 tests across nine `app` suites and 23 across two `platform`
  suites, 0 failures.

The narrative is in [`history/TASK_HISTORY.md`](history/TASK_HISTORY.md), the entries in
[`BACKLOG.md`](BACKLOG.md).

The last work performed was **`P7-DOC-001`, the Phase 7 exit review** (2026-09-28): six read-only
audits, the corrections they called for made and probed — twenty-four runs, twenty-three caught at
once, the survivor a test tightened and caught — before the flip; ADR-0059…0062 `Accepted`; the
post-flip battery the fleet-wide hermetic test task green at 1740 tests across 14 modules, 0 failures, and 408 targeted database tests across 38 suites, 0 failures (every Phase 7 suite, the multi-rail storm and the dispute battery among them), plus the platform classification guard - the full battery deliberately skipped on the owner's instruction, no fleet-wide database or kafka counts claimed. Before it, Phase 7's seventeen tasks
(2026-09-26 to 2026-09-28, `P7-TSK-001` to `P7-TST-002` — each recorded in
[`history/TASK_HISTORY.md`](history/TASK_HISTORY.md)); the first was **`P7-TSK-001`**: the rail
port and capability descriptor (ADR-0059 §1 made real with no behaviour change), the card rail
declared by its adapter, `payment_attempt.rail` as a frozen birth fact (payments `V011`), the
capability decision points moved onto the stored rail's declaration, and `INV-RAIL-01`'s static
rule — eight probes, all caught. *(This line named `P7-TSK-001` as the last work until the Phase 7
review found it sixteen tasks stale.)* Before that, **`X-TSK-004`** (2026-09-24, above), and before it, **the Phase 6 → Phase 7
transition** (2026-09-24): Phase 6 audited
independently, two CRITICAL and nine IMPORTANT defects repaired and probed (thirty-three probes, all
caught), the full battery fleet-wide before repair and after, and Phase 7 initialised — ADR-0059…0062
`Proposed`, `INV-RAIL` and `INV-DSP` catalogued (**101 invariants**), `PHASE_7_PLAN.md`,
`RAIL_AND_DISPUTE_LIFECYCLES.md` and 18 backlog items. Before that, **`P6-DOC-001`, the Phase 6
exit review** (2026-09-24): eight
inputs decided, six read-only audits of the ADRs, registers, documents, gate evidence and
privileged routes, and the corrections they called for made and probed — eighteen probes, seventeen caught, the one survivor a correct second rank — before the
flip; ADR-0050…0054 and ADR-0056…0058 `Accepted`; the post-flip battery **1550 tests across 14 modules, 0 failures** hermetic
and **224 tests across 23 suites, 0 failures** targeted database, with no fleet-wide database or kafka count claimed. Before that, the **Phase 5 → Phase 6 transition** (2026-09-21):

**Cross-cutting — `X-TSK-007`, session liveness on the database clock** (2026-09-27; it belongs to
no phase, gates no phase exit and displaces no phase task). **`IN_PROGRESS`: implemented and
tested; the completion gate is next.**
- **Done:** a session's bounds are stamped and judged on the database's clock, both halves. The
  lookup, listing, gauge, touch and rotation share one predicate at `now()`. Issue writes `now()`
  plus the policy, rotation copies the predecessor's absolute bound from its row, and the touch
  never moves the idle bound back. `issued_at` and `revoked_at` stay business time. `V016` adds
  `live_from`, moves the "not expired when written" rule onto one clock, and makes the lifetime
  metric `now() - live_from`. ADR-0030 is amended, and the `DISTRIBUTED_EXECUTION.md` register row
  is now true.
- **Evidence:** 11 new skewed-instance and 3 build-rule tests. Eleven mutations were caught and
  registered under `INV-IDN-03`, the original defect among them. The fresh database tier ran 802
  app tests with one failure, the pre-existing `OperationalChartDatabaseTest` (§Blockers).
- **Open:** the gate; and the merge with the main line (`claude/audit-context-efficiency-50b206`).
  This branch is on master's base because moving it was refused as a shared-resource change. The
  merge needs X-TSK-002…006 ordered before this entry in the backlog, and a check that the main
  line has no identity `V016` of its own. *(Both done at the merges: the Phase 8 branch had one,
  its reconciliation operator role, renumbered `V017` - and its controller role `V018` - since
  this `V016` was already on `origin/master`.)*
- **Recorded, not done:** `X-TSK-008`, the same shape elsewhere (§Known Architectural Debt).

**Cross-cutting — `X-TSK-009`, provider receipt after a timeout** (2026-09-27; it belongs to no
phase and does not displace `P6-TSK-011`). **`IN_PROGRESS`: implemented and demonstrated,
awaiting the completion gate.**
- **Done:** `SimulatedTokenisationAdapterTest#aTimeoutIsUnavailable` no longer depends on timing.
  Before the change it failed in 9 of 10 fresh `test` runs. The test read the provider's count at
  the instant a 200 ms client timeout fired, before the provider had recorded the request. The
  harness gains `awaitRequestCount`, a bounded wait for which giving up is a failure. The test now
  awaits receipt, with a 2 s timeout (the main line's value, kept at the merge), and `TESTING.md`
  §5a records the rule.
- **Open:** the completion gate, and an owner decision on three tests with the same race and wider
  margins (listed in the backlog entry).

The last work performed was the **Phase 5 → Phase 6 transition** (2026-09-21):
Phase 5 confirmed by independent audit, the first fleet-wide full battery of the phase
(**1323 hermetic / 829 database / 14 kafka, 0 failures** — after finding and repairing the
one failure in 2,166: `refund.dispatch_key` unclassified in `DATA_CLASSIFICATION.md` §4
since `P5-TSK-016`, the guard living in a tier no targeted run covers — the `RoleNameTest`
class recurring within a day of being written down), ADR-0050–0053 `Proposed`, the
`INV-MER` group catalogued (**93 invariants**), `PHASE_6_PLAN.md` and 16 backlog items
across seven milestones, `CHECKOUT_MERCHANT_LIFECYCLES.md` written, the glossary and gate
extended, and questions **7 and 8 closed** (the fee model decided before any posting
exists — the restatement risk retired). Before that, `P5-DOC-001`, the **Phase 5 exit
review** (2026-09-21), and the **Phase 4 → Phase 5 transition** (2026-09-20):
Phase 4 confirmed by independent audit, the first fleet-wide full battery of
the phase (1157 / 729 / 14, 0 failures — after finding and repairing the
test-harness connection ceiling that had made the fleet-wide database tier
structurally unable to run), ADR-0045–0049 `Proposed`, the `INV-PAY` group
catalogued (87 invariants), `PHASE_5_PLAN.md` and 21 backlog items across
nine milestones, `PAYMENT_LIFECYCLES.md` rewritten, and questions 6, 9 and
the overdue 10 closed.

*(This section named `P2-TSK-001` as next until `P3-TSK-001`'s gate — stale across the whole of
Phase 2, found by re-reading the document the gate updates.)*

*(This section had said "Phase 1 is `READY` but not started" since 2026-09-04 — pre-existing drift
the re-run's criterion 9 check caught, corrected here rather than left because a review about
documentation reflecting reality must not leave its own document stale.)*

## Blockers

**None.** The full battery ran fleet-wide at the Phase 6 → 7 transition, green before its repairs
and after them, so `X-TSK-001`'s last criterion is met and nothing waits on a database tier.

~~**Still flaky, unchanged**: `SimulatedTokenisationAdapterTest#aTimeoutIsUnavailable` fails about
one run in three with no change to its module (seen by `X-TSK-001`).~~ — **resolved** by
`P7-TSK-007` (`0f9c72a`), which bounded the await; the entry stood unstruck until the Phase 7
review, `P7-DOC-001`, read it against the test.

*Earlier blockers, resolved:*

~~**Flake fixes stranded on unmerged branches**~~ — **resolved 2026-09-28** by the owner's merges
into `master`: every commit named here is now its ancestor, `DisputeNotificationDatabaseTest`
seeds its disputes from the test's clock, and `WithdrawalTest` judges the amount once the
identifier is taken out. As it stood (found by the Phase 7 review, recorded, not merged -
the owner's standing instruction: another session's branch is merged by its own session). None
of `816f850` (`claude/reverent-galileo-77fca4`, the flake sweep `P7-TSK-015` chipped), `effd89f`
(`claude/xenodochial-bell-b49823`, `X-TSK-005` with ADR-0063 and a planned `X-TSK-006`),
`47c5d36` (needle anchoring), `647c088` (a fixture fix), `bb86186` (`X-TSK-007`) or `52992cd`
(a superseded deflake) is an ancestor of this branch or of `master`. Two flaky patterns they
address stand here: `DisputeNotificationDatabaseTest`'s bounded-listing test seeds 101 disputes
one server-second apart in autocommit under a comment that still says "microsecond-distinct",
and `WithdrawalTest` asserts `doesNotContain("2500")` against a `toString` carrying a UUIDv7 (the
short-needle class). Neither has failed in this review's runs; each is its branch's to land.

~~**The full database tier is not green**~~ — **resolved 2026-09-24** by the Phase 6 review
(`P6-DOC-001`), as far as a targeted tier can show it. `OperationalChartDatabaseTest` was red on
untouched `HEAD` since `P6-TSK-003`, found by `X-TSK-001`'s full run and invisible to every
targeted tier for eleven tasks. **It was two stale copies, and the recorded fix was wrong.**
`P6-TSK-003` retired "only customers own accounts" from the seed's hermetic guard but not from
`ChartOfAccounts.resolve`, which answered a merchant payable asked of the operational chart as a
missing seed, nor from the database test, whose loop died on `MERCHANT_PAYABLE` before it reached
`PAYOUT_CLEARING`. The fix recorded here ("skip every non-`OPERATIONAL` kind") would have dropped
the seeded, unowned `SUSPENSE_UNMATCHED` from the test. Both copies now take
`requiresOwnerRef()`, the seed guard's own predicate, and `ChartOfAccountsTest` pins the
partition in the hermetic tier the instruction leaves running. Probed: the guard reverted, and a
planted seed gap the database test could not reach before.

~~**The suite has never run in CI.**~~ — **resolved 2026-09-04** by `P0-TSK-042`. The remote is
`https://github.com/genadigeno/finapp`, and the four jobs run on every push to `master`. Run
[33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202) is green on all four.
This closes exit criterion 7, the Phase 0-specific "build green in CI from a clean clone", and the
`DOD-BUILD` item outstanding against `P0-TSK-001`–`005` since the first week.

**It took three runs, and *green locally* versus *green in CI* turned out to be exactly the
distinction the criterion exists for.** Two defects, neither reachable from this machine:

1. **`gradlew` was committed mode `100644`.** Four jobs died on `Permission denied`, exit 126.
   `core.filemode` is false on Windows, so nothing here could observe it — and `P0-TSK-001` had
   enforced *LF line endings* on that same file so *"Linux CI is not broken by a Windows
   checkout"*, reasoning about the file's bytes and not its mode.
2. **`gradle/verification-metadata.xml` was complete for a warm cache only.** Gradle does not
   re-read metadata descriptors it has already parsed, so generation over a warm
   `GRADLE_USER_HOME` records fewer artefacts than a cold resolution needs. Cold regeneration added
   **10 components and 23 artefacts, every one a parent POM or a BOM `.module`** — not one jar,
   which is what identifies the mechanism rather than guessing at it. The file had been complete
   for this machine and incomplete for CI and for any new developer. `README.md` §7a now
   regenerates against a temporary home.

A third finding belongs to the secret scan rather than the build, and is recorded under Known
Architectural Debt: gitleaks met this repository's own history for the first time and produced one
false positive.

~~**The `dependency-scan` CI gate fails.**~~ — **resolved 2026-09-03.** Three **CRITICAL**
advisories in `org.apache.tomcat.embed:tomcat-embed-core:11.0.24`, which Spring Boot 4.1.1 brings:
`CVE-2026-65182` (security-constraint bypass), `CVE-2026-65905` (DIGEST authenticator replay) and
`CVE-2026-68525` (FORM authentication bypass).

Fixed by pinning Tomcat to **11.0.25** in the version catalog and applying it as a dependency
**constraint** — Spring Boot 4.1.1 is the latest stable 4.1.x, so there was no patch release to
move to, and 4.2.0-M1 is a milestone. A constraint rather than `force`, so a future Boot managing
11.0.26 still wins. The scan now reports **zero** vulnerabilities, and the 68 slice tests boot a
real Tomcat 11.0.25, so compatibility is proven rather than assumed.

**The exposure was recorded honestly rather than overstated**: all three are authentication and
authorization bypasses, and Phase 0 has no authentication at all. Practically unexploitable here —
but the gate does not grade on exploitability, and Phase 1 brings exactly what they attack.

**One claim was corrected by probing.** The first version of the build comment said the lockfile
would reject removing the constraint. It does not: with the block deleted, resolution still yields
11.0.25 because the lock applies its own `{strictly 11.0.25}`. The lock *keeps* the version; it
does not object to the loss. A regression needs both the deletion and a lock regeneration, and the
`dependency-scan` job is the control.


`P0-TSK-004` (CI pipeline) was recorded as blocked. The 2026-08-31 task completion review
found the blocker was a defect in the backlog, not in the work: `P0-TSK-004` declared
dependencies on `P0-TSK-011` (Money persistence mapping) and `P0-TSK-036` (test taxonomy),
neither of which is required to run a build with its tests. Because both are scheduled after
several tasks carrying `DOD-BUILD`, whose "CI green" criterion they could therefore never
satisfy, the plan contained an unsatisfiable requirement. Dependencies corrected to
`P0-TSK-001, P0-TSK-002`; CI is now startable and closes the outstanding `DOD-BUILD` gap
across all four completed tasks.

---

## Local Environment Prerequisites

Machine-specific setup that the repository deliberately does **not** contain. The build must
work on any machine without local edits (`DOD-BUILD`: "no developer-machine-specific
assumptions"), so anything below belongs in `GRADLE_USER_HOME`, never in the repo.

**TLS interception by antivirus (this development machine).** AVG "Web/Mail Shield"
intercepts HTTPS and re-signs it with its own root CA. Windows trusts that CA; the JDK's
bundled `cacerts` does not. Java tooling therefore fails with:

```
PKIX path building failed ... unable to find valid certification path to requested target
```

while `curl` and the browser work — which makes it look like a Gradle fault rather than a
TLS-trust one. Resolved in `~/.gradle/gradle.properties` (outside the repo):

```properties
org.gradle.jvmargs=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Xmx2g -XX:MaxMetaspaceSize=512m
```

That covers the Gradle daemon. Bootstrapping the distribution runs in a separate JVM that
reads `GRADLE_OPTS`, so on a machine with no Gradle distribution cached also export:

```
GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"
```

Alternatives: import the AVG root into the JDK `cacerts` with `keytool`, or disable HTTPS
scanning in AVG.

**Resolved for CI (`P0-TSK-004`):** this is specific to this machine. GitHub-hosted runners
perform no TLS interception, so the workflow needs no equivalent setting. If CI ever moves
to a self-hosted runner behind an intercepting proxy, that runner needs the same treatment —
in its own environment, never in the repository.

**Git Bash rewrites container paths.** Running a command inside a container with an absolute
path from Git Bash (MSYS) silently rewrites it:

```
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh ...
  -> exec: "C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh": no such file
```

Prefix with `MSYS_NO_PATHCONV=1`, or use PowerShell. This affects interactive use only —
health checks and container entrypoints run inside Docker and are unaffected.

**`clean` fails with "Unable to delete directory".** On Windows an orphaned Gradle daemon
keeps module jars open, so `clean` cannot remove `build/`. It is leftover state, not a repo
defect. `./gradlew --stop` handles the usual case; a daemon whose `GRADLE_USER_HOME` has been
deleted survives that and must be killed by PID:

```
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'GradleDaemon' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**The container clock gains on the host and is stepped back past it.** Measured on 2026-09-27
(`P7-TSK-014`, then `X-TSK-005`), with a container answering host pings at a 1.3 ms half round
trip: the host's wall clock is steady (0 µs against its monotonic clock over 30 s), and the
Docker VM's clock **gains 55–61 ms a second** on it. It is then **stepped back 1.7 s at once**,
reading from 650 ms ahead of the host to more than a second behind: a sawtooth about 28 s long,
ahead about a third of the time. The step is roughly what the VM gained since the last one, so it
grows with the gain: ~1.6 s every ~27 s at `P7-TSK-015`'s gate (gaining ~77 ms a second), and
2.6–2.8 s every ~27 s when re-measured the same day (gaining ~100 ms a second). To measure it,
print busybox `adjtimex`'s `time.tv_sec`/`time.tv_usec` twice a second from a throwaway `alpine`
container — busybox `date +%N` prints no fraction. *(This entry said "drifts behind the host and
is corrected backwards", observed at 542 ms, until measured. The direction was wrong, and the step
three times larger than written.)* Two consequences:
- PostgreSQL's `now()` is not monotonic across two transactions. A row written before a step and
  read after it can carry a `now()`-derived timestamp *in the future*. This made the relay suite
  fail about one run in fourteen during `P0-TSK-020`, always as "the relay published nothing",
  never anywhere near the clock.
- The database is a **second clock** beside the JVM's, up to a second apart either way. A fixture
  stamping with `now()` a row the domain then judges on the JVM's clock fails whenever the two
  disagree in the wrong direction: `P7-TSK-014`'s merchant closed "before" it was created.

This is a property of the local Docker VM, not of the code. The platform's coordination is built
for it: leases, eligibility and retention are set **and** compared by the server, so a step
affects both sides equally. What it breaks is a *test* that stamps with one clock and is judged by
another, or by a later read of the same stepping clock - including one that assumes a lease, a
lock or a permit one second in the server's past has lapsed. Such fixtures stamp from the clock
that judges them, or back-date by minutes, never seconds (`DISTRIBUTED_EXECUTION.md` §5,
`X-TSK-005`; `OutboxRelayTest.backDate`, the expired-lease and crashed-flight claims, the served
lock), and a fixture whose rows must keep their order takes every stamp from one read of one
clock. In miniature, it is also what two production instances' clocks do to the ordering
`CHECK`s, which ADR-0063 decides.

A time-dependent test failing intermittently on this machine is worth checking against the
clocks before it is treated as a defect. To measure: a container from a Debian-based image
(`postgres:18.6` is local) answering pings with GNU `date +%s.%N`, never BusyBox's, which drops
`%N` and draws a whole-second sawtooth that is not there.

**Resetting local infrastructure.** `docker compose down` keeps data; `docker compose down -v`
discards it. A reset is required after changing Kafka's `CLUSTER_ID`, or when moving to a new
PostgreSQL major version without running `pg_upgrade` — the volume is formatted for the major
version that created it.

---

## Partially Satisfied Definition of Done

Recorded so it is not mistaken for a completed criterion.

| Task | DoD item not yet met | Owning task |
|------|---------------------|-------------|
| ~~`P0-TSK-001` — `P0-TSK-005`~~ | ~~`DOD-BUILD` requires "CI green"~~ — **closed 2026-09-04** by `P0-TSK-042`. All four jobs green on a runner, from a clean checkout: run [33803262202](https://github.com/genadigeno/finapp/actions/runs/33803262202). Outstanding since the first week, and closing it found two defects local runs could not reach. | — |
| `P0-TSK-004` | The CycloneDX SBOM covers the whole resolved dependency set, test scope included (21 of ~61 components). Plugin 3.4.1 exposes no configuration filter. Adequate for vulnerability scanning — test libraries execute on CI runners, so they are legitimately in scope — but it means a HIGH/CRITICAL advisory in a test-only library fails the build though nothing vulnerable ships, and **the SBOM must not be published as shipping provenance in this form** because it overstates what is deployed. | Phase 15 (supply chain and provenance) |
| ~~`P0-TSK-004`~~ | ~~CI actions and scanner images are pinned by SHA/digest with no automated update path, so the pins will rot.~~ — **closed** by `P0-TSK-040` (`COMPLETE` 2026-09-02: Dependabot for the SHA-pinned actions and catalogues, a weekly check for the scanner digests); struck by the Phase 8 exit review, `P8-DOC-001`, which found the row standing after its owner closed | — |
| ~~`P0-TSK-002`~~ | ~~Boundary enforcement partial~~ — **closed**. Cross-module internals and entity references by `P0-TSK-007`; `INV-MON-01` by `P0-TSK-008`. | — |
| ~~`P0-TSK-014`~~ | ~~Correlation must reach four sinks; the trace one is unverifiable~~ - **closed** by `P0-TSK-028`. All four sinks are now asserted: the log (`P0-TSK-014`), the outbox row (`P0-TSK-019`), the audit record (`P0-TSK-022`) and the trace, where every span carries `finapp.correlation_id`. The clause survived four tasks and a milestone because `CorrelationSinkCoverageTest` refused to let a new platform concern land unclassified - which is what closing on arrival rather than on memory means. | — |
| ~~`P0-TSK-003`, `P0-TSK-005`~~ | ~~Local PostgreSQL runs as the cluster superuser, so the database-privilege invariants cannot be exercised~~ — **closed** by `P0-TSK-022`. `finapp_migrator` and `finapp_app` exist, both `NOSUPERUSER`; Flyway connects as the migrator and every table grants the application role only the DML it requires. `INV-HIST-03` is now enforced and proven; `INV-LED-03` and `INV-HIST-01` have the mechanism they need and close when the ledger tables exist (Phase 3). | — |
| ~~`P6-TSK-012`~~ | ~~`DOD-FIN` 1.11, "failure and stuck states are detectable and alertable": the payout's own series were not registered~~ — **closed 2026-09-23** by `P6-TSK-013`. `finapp.merchant.payout.unknown.active` and `.age` alert on an `UNKNOWN` payout and on a `DISPATCHED` one past the sweep's own bound, eager from a plain context, and read against the real schema by the running instance; the payout judgements are counted too. | — |

---

## Known Architectural Debt

Debt is recorded here as it is deliberately accepted, with: what was deferred, why, what risk it
carries, what triggers paying it down, and the owning phase.

| Deferred | Why | Risk carried | Trigger | Owning phase |
|---|---|---|---|---|
| ~~**22 Phase 8 database-tier cases are red on master**~~ — **paid 2026-10-05 by `X-TSK-016`**: six fixtures repaired, one test timing defect fixed, and the own-container suites given a database of their own by the build; the whole `:app:databaseTest` tier green from a fresh run (the record in §Current Task). *As recorded:* (found 2026-10-05 by `P9-TSK-010`, which ran the reconciliation, settlement, storm and payout database selection and found the identical 22 failures on unchanged master) - among them `OpeningPosition`'s walk refusing a non-UUIDv7 journal entry id a suite plants in the shared container (`JdbcPayoutReturnStore.map`), `MerchantPayoutDatabaseTest` still expecting three currencies after JPY and BHD joined (`P9-TSK-003`), the bank-statement and payout cash suites rejecting `CONFLICTING_BATCH`, and the two storms | The fleet-wide database tier has been skipped by the owner's standing instruction since early Phase 9, so the drift accumulated unseen; repairing other suites' fixtures is outside `P9-TSK-010`'s scope, and its own changes were proven not to add a single failure | The Phase 8 proofs (`INV-REC-06`, `INV-SET-06`) are unverified at the database rank until repaired; a real regression could hide among known-red cases | Offered as its own task 2026-10-05; at the latest the Phase 9 exit gate, which runs every tier | 9 |
| **Time decided across instances outside the session store** (`X-TSK-008`). Five stores still compare a stored bound with a caller's instant: recovery tokens, contact-channel verification, checkout expiry, the fee schedule in force, and payout-destination cooling-off. `CHECK`s in most schemas order two business timestamps that different instances write. Two session leftovers remain: the touch extends by the current policy, and bulk revocation counts expired sessions. Found by `X-TSK-007`'s design, 2026-09-27 | `X-TSK-007` was scoped by the owner to sessions, and folding in five modules' stores and every schema's ordering constraints would have made one task of many. The routing store is `X-TSK-005`'s | **Varies by site, none silent.** The fee schedule can price a transaction at an activation boundary under the version a skewed instance believes in force; its pinned version keeps the fee explainable but not right, so it goes first, with `X-TSK-005`'s fix as the template. Recovery, contact-channel and cooling-off windows stretch or shrink by the skew. The ordering `CHECK`s fail closed, refusing a legitimate revocation, release or dispatch as a `500` | Owner scheduling. The fee schedule is due before the next change to it | Cross-cutting; Phase 15 (security hardening) at the latest |
| ~~**`PositionProof` reads a credit-normal clearing's balance with the wrong sign**~~ — **paid 2026-09-30** by `P8-TSK-018`, the first task whose proofs met it (and independently the same day on the main line by `734d6f5`, whose `readFrom` form the merge kept): the verdict now negates a CREDIT-normal position's settled balance, so every position reads DR−CR like its remainders — proven with an open payout's expectation in `PayoutSettlementCashDatabaseTest`, the reverted negation caught by that suite (`MUTATION_TESTING.md` §2). *As recorded:* it compares `derive(...).settled()`, signed by the account's NORMAL balance, against remainders signed DR−CR; `PAYOUT_CLEARING` is seeded `LIABILITY`/`CREDIT` (ledger `V012`), so any open merchant-payout expectation fails the verdict (ledger +X against remainders −X) | Found in passing by `P8-TSK-015`'s verification, when the merchant storm shared the proofs' container; outside the resolution machine's scope, and the proof suites' own container group never includes a suite that leaves a payout expectation open | `finapp.reconciliation.position.proof` and the positions report read a FALSE failure for `PAYOUT_CLEARING` whenever a payout is in flight — an alert that cries wolf hides the real break it exists to show | Its own task, flagged 2026-09-30: derive DR−CR explicitly (or negate for a credit-normal position), with a test holding an open payout expectation and its probe | **PAID — `P8-TSK-018`** |
| ~~**The rematch worklist's KEY clause re-selects residuals it can never allocate**~~ - **paid 2026-10-02** by the Phase 8 -> 9 transition's repair round: a rematch that cannot act records an examination decision and the reach it read before judging (`match_reach`, reconciliation `V016`), each reach examined once, a residual that can never allocate leaving the worklist while the leg drains (`MatchingLegCorrectionsDatabaseTest#residentsThatCannotActNeverStarveTheLeg`, probed) — `JdbcMatchingStore.REMATCH_PREDICATE`'s key clause selects a residual whose key reaches an expectation opened after its latest decision whatever that expectation's remainder, and joins `expectation_key` by value alone, not kind; `Matching.rematchOne` writes no decision on a non-allocating verdict, so such a residual is re-locked on every tick until a person resolves its break, and a source holding a chunk's worth (200) of them keeps its other residuals out of the leg *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: re-owned to Phase 15. Availability only - a re-selected residual re-locks and allocates nothing, value is never moved; the remainder condition the anchored clause carries is the model, but a keyed reach answered by a lower-priority key needs its own design, which is `P8-TST-001`'s recorded question too. Its trigger task `P8-TSK-022` closed without paying it, and the review records that rather than inherit the decay)* | Found by `P8-TSK-019`'s tests agent, which fixed the same shape in the new anchored clause (the remainder condition) and left the pre-existing clause alone: changing the key clause is the matcher's own change, not the return's | Availability only — an `UNMATCHED` residual still meets its grace; a `PARKED` one waiting to be unparked by late evidence is the exposed case; no money moves wrongly, since the rematch decides under the locks by kind | The first source whose parked residuals reach a chunk, or `P8-TSK-022`'s requeue and reprocess work on the legs | Phase 15 |
| ~~**Three database suites run in their own container by convention, and the fleet-wide tier co-locates them** *(the storm's half PAID by `P8-TSK-020`: its at-rest assertion now reads zero unattributed suspense lines and zero unowned items, which no earlier suite can move; the register rebuild's half stands)* — `MultiRailConservationStormDatabaseTest`'s at-rest count reads every `SUSPENSE_UNMATCHED` line in the database as a Phase 7 parking, and `SettlementAcceptanceDatabaseTest`'s register rebuild deletes every unheld expectation and restores only those production can re-derive; so each holds only when no parking suite and no fixture-funded suite ran before it in one database. `SchemeCycleCashDatabaseTest`, `PayoutSettlementCashDatabaseTest` and `PayoutReturnDatabaseTest` are therefore run apart, while `databaseTest` runs ONE JVM, and so one container, for the whole tier~~ — **merged 2026-10-01** by the Phase 8 exit review into the storm's own-container row below, which now names every suite run apart and owns them together (Phase 15) | Found by `P8-TSK-019`'s gate, which ran its suite inside the twelve-suite proof group and met both: the funding it could fix (each fixture capture now opens its expectation through the live port), the parks it cannot — they are what the fallback cases prove  *(An alternative exists: `b756cfbf` on `claude/dazzling-chatelet-924cc5` scoped the storm's and the acceptance suite's register rebuild to their own rows. It was merged as history only, on 2026-10-03, because it predates the rows `P8-TSK-011`…`-015` added that hold expectations - see the change log.)* | Test-tier only: every suite passes where it runs; the risk is a fleet-wide run reporting failures no code causes, or order-dependence hiding a real one | The next fleet-wide `databaseTest` run, or the Phase 8 review — scope the storm's count to the parkings' own entries and the rebuild's comparison to the rows it deleted | 8 |
| ~~**`SimPspCsvFormat` crashes past its 100-defect cap**~~ — **paid 2026-09-30** on the main line by `e119982` (outside the task loop): each record's defects gathered apart and judged locally, with `SimPspCsvFormatTest#defectOverflowRejectsWhole` (120 bad records, rejected `MALFORMED` with exactly 100 defects) red first. *As recorded:* `detail()` decides a record was read cleanly by `defects.size() > before`, which the capped list can no longer move once 100 defects stand, so the 101st malformed record reaches `magnitude.minorUnits()` on null and throws | Found in passing by `P8-TSK-017`'s delegated adapter build (the scheme adapter gathers each entry's defects apart for exactly this reason); a frozen v1 adapter outside the scheme task's scope | **Liveness, never money**: a corrupt file of more than a hundred defects stays `RECEIVED`, backed off and retried, visible on `finapp.settlement.file.age`, instead of being rejected whole — nothing is posted or matched from it | Its own task, flagged 2026-09-30: gather each record's defects apart, decide from the local list, with a hermetic regression of 120 bad records | 8 |
| **One corrupt (non-UUIDv7) `payment_attempt.id` stalls every instance's whole card sweep** — `findSweepable` rehydrates typed ids while LISTING candidates, so the refusal (`EntityId`, ADR-0013) throws before the per-row containment (`P5-TSK-014`'s one-failing-row discipline) ever starts. Surfaced by `P7-TSK-003`'s battery when raw test fixtures minted v4 ids and poisoned every later suite's sweep | No domain writer can produce one — `EntityId` refuses at birth and every store insert goes through it — so the exposure is a raw writer (migrator, operator SQL) corrupting an id, which today would also be caught by nothing else | Bounded: the sweep crashes loudly and repeatedly rather than resolving wrongly; money is not misjudged, it is unattended — the same failure a poisoned row causes, one rank earlier. *(The Phase 7 review widened this row: Phase 7's four new sweeps list the same way - `JdbcWithdrawalStore`'s, `JdbcDisputeResponseStore`'s, `JdbcRefundStore.findSweepableReturns` and `JdbcPaymentAttemptStore.findResolvableInitiations` - so one corrupt id stalls that sweep likewise.)* *(`X-TSK-016` widened it again: `OpeningPosition`'s walk pages `JdbcPayoutReturnStore`, whose `map` refuses a non-v7 `journal_entry_id` through `JournalEntryId.of` - one v4 row planted by `PayoutReturnDatabaseTest`'s schema case made every later opening backfill in the JVM answer `500`, and every suite that runs one fail. Surfaced the same way, by a fixture's v4 ids; the fixture now mints v7, the production check unchanged.)* | The fixtures now mint v7 (the immediate repair); every candidate list should skip-and-count an unrehydratable row exactly as the per-row loop does | Phase 15 |
| **`payment_intent.wallet_account_id` holds a merchant payable for a merchant-bound payment.** The column's own comment defines it as *the wallet's ledger account - where the capture will credit*, so its MEANING is right and its NAME is narrower than its meaning (`P6-TSK-005`) | Renaming a column of applied history needs a new migration plus the every-writer trigger's recreation on the platform's most critical table, and the first PRODUCTION writer of a merchant-bound intent does not exist yet - `P6-TSK-007` brings it. Renaming before its real consumer exists would be guessing at what the consumer wants to call it | **Naming only, and bounded**: nothing reads it as a wallet - the capture credits whatever account it names, and the settlement REFUSES a capture whose credit account is not the pinned merchant's payable, so a mismatch is loud rather than silent. The cost is a reader of the schema being misled | **Re-owned by the Phase 6 review (`P6-DOC-001`)**: `P6-TSK-007`, this row's trigger, completed without the rename. The next migration that must recreate `payment_intent`'s every-writer trigger anyway carries the rename with it | **PAID — `P7-TSK-002`** (payments `V012` renamed the column under the recreated trigger; every reader, writer, test and register row follows the new name, the old one kept only in applied history and provenance notes) |
| **Every session actor is audited as `CUSTOMER`, including operators.** `SessionAuthenticationInterceptor` enters `new Actor(identityId, ActorType.CUSTOMER)` for every authenticated session, so an operator's privileged acts — a manual adjustment, a transfer reversal, a refund, a merchant suspension, an API-key revocation — are recorded with the wrong actor TYPE. Found at `P6-TSK-002`'s implementation, while asserting that issuance names its operator: the test expected `EMPLOYEE` and the trail said `CUSTOMER` *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: carried, and widened. Four-eyes is provable from the rows today: every distinctness rule compares identity ids, never the type (`resolution_four_eyes_distinct`, `rule_set_activation_is_four_eyes`, the attester against every submitter, ledger `V010`), so the mislabel can make neither two people one nor one person two. New in Phase 8: the `*_by_type` columns on `resolution` (and the rule-set, run and file rows) are FROZEN by trigger, so the fix, when it lands, corrects new rows only - history reads `CUSTOMER` permanently; that the actor held the permission at the time is reconstructible through `identity.role_assignment` and the `identity.RoleAssigned` audit, untested)* *(The Phase 8 → 9 transition records that Phase 9's operator acts - quote reads, trade reversals, corridor administration - inherit the same mislabel; owner unchanged)* | The identifier is right — `actor_id` is the acting identity, so every record still names the person and `INV-AUD-01`'s attributability holds. What is wrong is the vocabulary that says which POPULATION acted, which is the field an auditor filters on to answer *what did staff do*. Correcting it means deriving the type from the identity's roles at authentication time and touches every audited session path on the platform — not a merchant task's to change, and not a change to make without its own negative tests | **Bounded but real**: no record is missing and none names the wrong person; a report separating staff activity from customers' cannot be built from `actor_type` alone today, and `ActorType.EMPLOYEE`'s own javadoc (*a human acting in an operational or administrative capacity*) describes a value nothing currently produces | An audit-completeness review, or the first report that must distinguish staff from customers | Phase 15 (audit completeness verification) |
| **Four repudiation shapes are refused, not compensated** (`P8-TSK-023`; the fourth added 2026-10-02 by the Phase 8 -> 9 transition's repair round, its REC-7) - a batch holding a correction `OFFSET`, one whose parked line a later batch's correction offset released, an allocation whose expectation a person already closed `RESOLVED_BY_ADJUSTMENT`, and a bank item matched to the batch's remittance that a person already `RESOLVED` answer `reconciliation.RepudiationNotSupported` before anything is written, all four proven at proposal and at approval (`BatchRepudiationDatabaseTest#theFourUncompensatedShapesAreRefused`) *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: the trigger naming this review is struck - the review met no such batch - and the row re-owned from Phase 9, which is foreign exchange and has no reconciliation scope in `PHASE_GATES.md`, to Phase 15; the trigger is now the first such batch met in operation alone)* | Each needs a compensation of its own (re-parking an offset original's excess, reopening a person's closure, a resolved item's reopening edge) that no Phase 8 task designs; refusing is honest, half-doing would break a proof | A fabricated batch of one of these shapes is contained by its breaks and resolutions only, as before this task | The first such batch met in operation, or the Phase 8 exit review | Phase 15 |
| ~~**Broker adapter behind `EventPublisher`.**~~ - **closed 2026-09-09** by `P2-TSK-001`. `KafkaEventPublisher` publishes every outbox event to Kafka - payload bytes verbatim, envelope as record headers, aggregate as the record key, one topic per producing module - and `OutboxRelaySchedule` polls on every instance, safely, because the per-aggregate advisory lock is the lease (`DISTRIBUTED_EXECUTION.md` §3). Delivery is at-least-once with `finapp.eventId` as the consumer dedupe key, and the crash duplicate is DEMONSTRATED in `KafkaOutboxDeliveryKafkaTest` rather than hidden. | - | - | - | - |
| **Outbox retention.** Published rows are never deleted | `V005` says a published row may be deleted once retained long enough for diagnosis; the sweep is a scheduled job with its own cluster-safety question, and no task owned it | Unbounded table growth. The partial pending index does **not** grow with it — published rows leave it — so the cost is storage and vacuum, not relay latency | Table size becoming operationally material | Phase 15 (data retention and deletion) |
| ~~**Relay metrics.**~~ - **paid in full 2026-09-09** (`P0-TSK-029` the gauges, `P2-TSK-001` the counters): `finapp.outbox.publication` by outcome (published, failed, deadlettered), registered eagerly and fed from `RelayPollResult` by the schedule that now actually runs. The eager series is asserted before any flow in `OutboxRelayScheduleKafkaTest` | Nothing schedules a relay, so those meters would be structurally always zero - which reads as "nothing is failing" rather than "nothing is running" | The remaining risk is narrower: a relay that is running but failing is visible as a growing backlog, not as a failure count | A scheduled relay | Phase 3 |
| **Inbox retention sweep.** Records are never deleted | The sweep is a scheduled job with its own cluster-safety question, and `V007` deliberately adds no `expires_at` index until its predicate is written | Unbounded growth of a table whose only index is its primary key. **Not** a correctness risk in this direction: a record that is never swept deduplicates forever, and it is early expiry that admits a duplicate (`DATA_MIGRATIONS.md` §9) | Table size becoming operationally material, or the first consumer going live | Phase 15 (data retention and deletion) |
| ~~**Inbox metrics.**~~ - **paid in full 2026-09-09** by `P2-TSK-002`, whose trigger this row named: *"the first live consumer"*. `finapp.inbox.consumption` by outcome (processed, duplicate, contended, failed), registered eagerly and fed from `ReceiverPollResult` by the consumer loops; asserted present at zero before any record has ever arrived | - | - | - | - |
| **Audit retention and archival.** Records are never deleted, and the application role cannot delete them | ADR-0010 is explicit that deletion is not an option and that archival must preserve queryability - which is a Phase 15 deliverable, not a sweep | Unbounded growth of a table written on every privileged action. **Not** a correctness risk: the inability to delete is the invariant working, and archival must preserve the trail rather than trim it | Table size becoming operationally material | Phase 15 (retention and archival) |
| ~~**Four-eyes approver is not modelled.**~~ - **dissolved 2026-09-17** by `P3-TSK-021`, and *dissolved* is the accurate word: the anticipated "second actor column" was never added, because a four-eyes action is **two acts, each with one actor** - `ledger.AdjustmentProposed` names the initiator with the justification, `ledger.AdjustmentPosted` names the approver - and the pairing lives on `ledger.adjustment_proposal` (approver ≠ initiator at `DB-CONSTRAINT`, plus a deferred trigger refusing any unapproved `ADJUSTMENT` COMMIT). ADR-0010's follow-up now records that later phases' four-eyes actions should look at the proposal row's shape before adding columns | - | - | - | - |
| **The three registered platform actions are not emitted.** `outbox.EventAbandoned`, `outbox.EventRetryAuthorised`, `outbox.EventDiscarded` | Two describe the manual procedure in `EVENT_ARCHITECTURE.md` §Handling an abandoned event, performed today with raw SQL; the third is a relay decision currently only logged. Wiring them is a change to `P0-TSK-020`'s relay and to tooling that does not exist | An abandoned event - consumers permanently not receiving a fact that happened - is recorded only in logs, which ADR-0010 is explicit do not count as an audit trail. This is exactly the gap the registry exists to make visible | Dead-letter tooling, or the relay taking an `AuditWriter` | Phase 15 (dead-letter handling), or sooner if the relay is revisited |
| ~~**No ingress correlation filter.**~~ — **closed** by `P0-TSK-025`. `CorrelationFilter` establishes a scope per request at `HIGHEST_PRECEDENCE` and echoes the identifier in `X-Correlation-Id`; every response carries it, error or not. | — | — | — | — |
| ~~**The ingress filter must wrap error handling.**~~ — **closed** by `P0-TSK-025`. The filter is ordered outside the dispatcher and its scope closes only after the whole chain, error handling included. | — | — | — | — |
| ~~**Thirteen test classes open connections through their own private helper.**~~ — **closed** by `P0-TSK-036`. All thirteen now use `DatabaseRoles`, so the property names and the driver call have one definition. What they had been copying was a connection as the **superuser**, which `DatabaseRoles.bootstrap()` now documents as the wrong default and confines to tests making no privilege claim. All 173 database tests pass unchanged. | — | — | — | — |
| **Redis is plaintext with no enforcement; Kafka is now guarded.** `P2-TSK-001` brought the first Kafka client and, with it, `KafkaTransportGuard` - a non-loopback bootstrap over `PLAINTEXT` refuses startup, which is ADR-0023's recorded promise kept on schedule. TLS/SASL themselves remain Phase 15's deployment posture, and the guard's limit is stated in `SECURITY_ARCHITECTURE.md` | There is still no Redis client, so a Redis guard would guard nothing | **Bounded**: the local broker is loopback-only and the guard holds the boundary; Redis carries no risk until a client exists | The first Redis client; a deployed broker for the TLS posture | Phase 15 |
| ~~**A caller can put personal or financial data into the correlation identifier.**~~ - **closed 2026-09-04** by `P1-TSK-002` / ADR-0034. The platform now mints the identifier on every request and never adopts an inbound one; a well-formed caller value is echoed in `X-Client-Correlation-Id` and reaches no sink. **Narrowing the charset was the obvious repair and does not work** - a date of birth, a phone number and an account number are alphanumeric, so any charset still able to carry a UUID carries them; of the four probed values it would have stopped two and left two. The control had to be structural. | - | - | - | - |
| ~~**No production code establishes a security scope.**~~ - **closed 2026-09-06** by `P1-TSK-006`. `RegistrationService` establishes one for `POST /v1/registrations`, and the actor is `enterSystem()` because the caller is **unauthenticated** - which is a call site that *stays* after Phase 1 revisits it, not one to be removed. The alternative, attributing the action to the Party it creates, is circular and is unavailable on the refusal path where nothing was created; an actor that differs between success and failure is worse than a uniform honest one. The information is carried by the audit record's **target** instead - the attempted login identifier, on both paths. | - | - | - | - |
| ~~**The loopback confinement is per credential, not a general mechanism.**~~ — **paid 2026-09-20** by `P5-TSK-002` | **The trigger is now met**: `P2-TSK-011`'s callback signing key (`FINAPP_KYC_CALLBACK_KEY`, `CallbackKey`) arrived as the **fourth** credential, again confined and tested in `MfaKey`'s shape rather than by generalising, because folding a refactor of three proven guards into a callback task is `EXECUTION_PROTOCOL.md` rule 4's case — but the row's own trigger ("the fourth credential, or Phase 5's provider adapters — whichever asks first") has fired, so the generalisation is **due as its own piece of work** rather than the next task's side effect | **Low but no longer shrinking.** The build rule remains general - a fifth credential cannot arrive as a literal - and four hand-written instances of one shape is exactly the count at which the copies start to drift | **Paid in full (2026-09-20, `P5-TSK-002`)**: one mechanism (`ConfinedCredential.KeySpec`), the four guards re-expressed over it with their untouched suites as the equivalence proof, and the acceptance mutation — the confinement removed — failing every consumer at once. The fifth and sixth credentials arrive as one-line specs | — |
| ~~**No output scrubber for text the platform does not control.**~~ - **answered 2026-09-06** by `P1-TSK-009`, and the answer is that the scrubber is **not built**. A scrubber is a deny-list over emitted text, and to recognise a secret it must be *given* the secret - which makes the plaintext travel **further**, into a filter invoked on every log statement in the platform, rather than less far; it also produces exactly the false confidence ADR-0019 warns about, since a deny-list that misses one shape is indistinguishable from one that misses none. **What replaces it is the opposite shape and is checkable**: a plaintext can only reach any sink if something first *unwraps* it, and every unwrap is a call to `expose()` - named to be found, deliberately. `SecretsAreUnwrappedInOnePlaceTest` pins that set to **four production classes, all in `identity`**, so a new unwrap anywhere fails the build and forces a decision. **The residual is stated rather than closed**: inside `identity` a plaintext could still be handed to a log call and nothing mechanical would catch it - bounded by the set being four classes rather than a codebase, and by the one production log call on that path being asserted quiet against a real database. |
| **The scrape endpoint widens the unauthenticated surface to three.** `/actuator/prometheus` joins health and info *(Re-owned by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: Phase 0 is `COMPLETE` and the endpoint is still open - `application.yaml` exposes `health,info,prometheus` - so the owner had closed without paying it.)* | `DOD-OBS` requires the dashboard to render live data from a running instance, which needs a scrape endpoint, and there is no authentication anywhere yet | A scrape publishes JVM internals, HTTP route templates and pool statistics - a description of the running system rather than its secrets. The **content** is constrained by a build failure: no tag may carry a request-influenced value | `P0-EPIC-10` landing | Phase 15 |
| **The operational endpoints are unauthenticated.** `/actuator/health/*` and `/actuator/info` are reachable by anyone who can reach the port *(Re-owned by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: Phase 0 is `COMPLETE` and the endpoint is still open - `application.yaml` exposes `health,info,prometheus` - so the owner had closed without paying it.)* | `DOD-API` requires a negative authentication test for every new surface, and there is no authentication anywhere in the platform yet - `P0-EPIC-10` is the epic that brings it. Building one authentication mechanism for the actuator alone would be a second scheme to retire | **Low, and bounded by what is published.** The bodies are pinned by exact-match test to a status and, for the aggregate, its group names; details, components, environment, JVM and OS are all off, and twelve other endpoints are proven absent. What remains is that an unauthenticated caller can learn the instance is up and which build it runs | `P0-EPIC-10` landing, at which point `show-details: when-authorized` also becomes available | Phase 15 |
| ~~**Connection-pool sizing is not reasoned about across instances.**~~ - **closed 2026-09-04** by `P1-TSK-004`. The relationship `instances x pool <= max_connections - reserved` is declared as configuration and enforced by `ConnectionPoolSizingGuard` at startup, with the shipped numbers additionally checked in the build. **The obvious repair - divide `max_connections` by the instance count - is the wrong one**: that treats the limit as a budget to spend when it is a ceiling not to hit, and PostgreSQL throughput stops improving once the cores are busy, after which extra connections queue *inside* the database where the queueing is invisible. The pool is sized small for throughput and the fleet check is a separate question asked afterwards. `DISTRIBUTED_EXECUTION.md` §4a. | - | - | - | - |
| ~~**`@ArchTest` rules do not run in the `architectureTest` tier.**~~ - **closed 2026-09-08** by `P1-TSK-025`, and the defect was worse than this row described: the rules were not missing from the tier, they were **in the wrong one**. `unitTest` selects by *exclusion*, so it took all **28** untagged rule fields; `architectureTest` selects by *inclusion* and got none - and `ModuleBoundaryRulesTest`, which has no `@Test` method at all, produced **no result file** there: not a suite that ran zero cases, a suite that did not appear. **Root cause established by disassembling the engine**: `AbstractArchUnitTestDescriptor.findTagsOn` loads exactly one annotation, `com.tngtech.archunit.junit.ArchTag`, and cannot see JUnit's `@Tag`. Fixed with `@ArchTag` beside `@Tag` on all seven suites. **No existing guard could see it because the partition check asserts a SUM, and the sum was right** - every rule was in exactly one tier. | - | - | - | - |
| **No per-source rate limiting.** Lockout bounds *guessing* per identity; nothing bounds the *volume* one source can generate | **Building it now would be harmful, not merely premature.** `SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution commits to N replicas behind a load balancer, so `getRemoteAddr()` is the balancer: every user shares one bucket, the threshold is reached in seconds, and authentication goes down for everyone. `X-Forwarded-For` is caller-supplied and ADR-0034 settled that such values are not trusted; no trusted-proxy configuration exists. The missing input is a deployment topology, not effort (`P1-TSK-011`) | **Resource exhaustion, and it is the platform's most expensive unauthenticated operation**: ADR-0032 makes each attempt cost ~46 ms and ~19 MiB *by design*, so the work factor protecting a stolen credential store is the one an attacker spends for free. Ten concurrent attempts is ~190 MiB on one instance. `INV-IDN-07` still holds - every response is identical, so flooding discloses nothing - and lockout now bounds what an attacker learns, though not what they cost. Bounded today only by the fact that nothing is deployed | A deployment topology and a trusted-proxy declaration | Phase 15 |
| **`POST /v1/registrations` is unauthenticated and unthrottled.** Anyone who can reach the port can create Parties, Customers and Identities without limit | There is no rate-limiting mechanism anywhere on the platform. `P1-TSK-011` builds one for **authentication** - failure counting and lockout keyed on an identity - and none of that applies to an endpoint whose whole point is that no identity exists yet. Building a second, differently-shaped mechanism here before that one exists would be designing the general case from one example | **Resource exhaustion, not disclosure - and `P1-TSK-026` made it materially worse, which is recorded rather than left for somebody to notice.** Every response is still identical whatever is sent, so flooding discloses nothing (`INV-IDN-07` holds). What changed is the cost: a required password means **every** request now performs an Argon2id derivation, ~46 ms of CPU and ~19 MiB, *before* anything can refuse it (ADR-0032) - so this endpoint has become the same CPU-and-memory amplifier `POST /v1/authentications` already is, and unlike that one it needs no existing account. It also still fills three tables and the outbox, and the idempotency key does not help since a flooder generates a fresh one. Bounded today only by the fact that nothing is deployed | **Re-owned by `P1-DOC-002` (2026-09-09)**: `P1-TSK-011`'s mechanism is keyed on an identity, and an unauthenticated endpoint has none - the only usable key is the source, so this row's missing input is per-source rate limiting's missing input, a deployment topology and a trusted-proxy declaration. Merged with that row's trigger | Phase 15 |
| **Dead-letter tooling.** Resolving an abandoned event is a manual `UPDATE` | The mechanism is needed now; the tooling is a Phase 15 concern | An operator resolving a stalled aggregate acts by hand against a live table. Acceptable only because the outbox is transport, not financial history (`INV-EVT-02`) — the same action against a ledger table would not be. The procedure is documented in `EVENT_ARCHITECTURE.md` §Handling an abandoned event | Abandonment occurring in practice | Phase 15 |
| **The ownership detector is widened in the tenant's two packages only.** In `com.finapp.merchant` and `com.finapp.checkout`, `OwnershipIsScopedTest` counts a bare `UUID` as a resource identifier and a same-class helper's SQL as the caller's (`P6-TST-001`); everywhere else it still keys on `EntityId` and on direct `prepareStatement` calls | Applied platform-wide, the same widening surfaces 31 more methods in ten modules, and reclassifying other modules' reads is not a merchant task's work (`P6-TSK-009`'s gate said the same) | A by-value read or a helper-split statement outside the tenant packages ships unclassified. Bounded: the tenant-column guard keeps every `merchant_id` / `merchant_ref` statement inside the widened packages, and each customer module's behavioural negatives still run | `X-TSK-002` scheduled, or a new by-value read on a customer-facing surface | Cross-cutting (`X-TSK-002`) |
| **The conditional step-up is a private six-line method in FIVE services.** `BeneficiaryService`, `PaymentMethodService`, `PayoutDestinationOperations` (`P6-TSK-011`), `WithdrawalService` (`P7-TSK-008`) and `CheckoutService`'s wallet door (`P7-TSK-011`) each restate `P4-TSK-007`'s *`MULTI_FACTOR` exactly when a factor is active* - the row said three until the Phase 7 review, `P7-DOC-001`, found that its trigger fired twice in Phase 7 and was paid by neither task | Extracting it would have meant refactoring unrelated services inside a task about payout destinations, withdrawals or wallet payments (the smallest-coherent-change rule); five identical copies of a security check are a maintainability risk, not a correctness one - each surface's own step-up test pins its copy (`BankAccountRegistrationDatabaseTest#theStepUpGateLeavesTheKeyUnburned`, `WithdrawalDatabaseTest#theSecurityNegativesHold`, the Phase 4 and Phase 6 suites) | A fix to one copy that misses the others: a step-up rule that differs by surface | **Re-owned by the review**: any change to the rule itself, or a sixth caller - then extract one component and move all callers in one change, each surface's existing step-up test the equivalence proof. A review is not where five services are refactored | Phase 15 (value-based step-up arrives with Phase 13's policy; the extraction is due no later than the first change it makes) |
| **Most idempotency scopes are constants.** ADR-0004 says a claim's scope names the command and the owning principal. *(Recounted by the Phase 7 review, `P7-DOC-001`, where the row said "every scope but three": Phase 7 added three principal-scoped claims - `payments.withdrawal:`+customer, `payment-method-register:`+party and `dispute.respond:`+actor - and one constant, `payments.routing.version`, an operator's act that moves no money.)* The payout and, since the Phase 6 review, the checkout session do, and since the Phase 6 → 7 transition the checkout's payment claims in its own `checkout.payment` rather than the public `payment.create` (its key is derived, so a client could predict it); and `accounts.open`, `payment.create`, `payment.refund`, `transfer.execute`, `merchant.onboard`, `merchant.api-key.issue`, the destination proposal and registration do not | Changing a scope moves a command's namespace, so a retry that spans the deploy misses its claim and runs again: a second offer for a session, but a second money movement for a transfer, which has no second rank behind its claim. *(This said "for a refund or a transfer" until the Phase 6 → 7 transition: the refund's `V008` dispatch key is a second rank, and a retry that misses its claim now converges on the refund its key carries — but that key is unscoped by principal, which `X-TSK-003` must account for.)* Each money-moving scope needs a transition path and its own test, which is not a phase review's to build (`P6-DOC-001`) | **Interference, not leakage**: the fingerprints name the caller, so nothing is replayed to the wrong principal; one client's key value refuses another's request as `api.Conflict`, and tells the second the value is taken | `X-TSK-003` scheduled, or the first client-visible collision | Cross-cutting (`X-TSK-003`) |
| ~~**Payments' stuck-dispatch blindness, and refund resolution by query.**~~ — **paid 2026-09-24** by the Phase 6 → 7 transition, which found the gap behind a CRITICAL defect and could not leave it: the sweep resolves refunds by query and re-drives one the provider never saw under a send permit, chains a stranded `AUTHORIZED` to capture, and both stuck gauges count a `DISPATCHED` operation (and an `AUTHORIZED` one) past the sweep's bound. *As recorded:* **Payments' stuck-dispatch blindness, and refund resolution by query.** Phase 5's gauges (`finapp.payments.unknown.*`) count only `*_UNKNOWN`, so a payment or refund whose instance crashed mid-dispatch is invisible whenever the sweep is down; and an `UNKNOWN` refund is resolved only by webhook — Phase 5 deferred refund sweeping to "Phase 6 or a sweeper extension", and Phase 6 built the payout's sweep but not the refund's | The payout's gauge and sweep are the shape both should take (`P6-TSK-013`, `P6-TSK-012`), but they are Phase 5's components, and a phase review is not where their machinery changes (`P6-DOC-001`) | **An unalerted stuck refund**: its hold stands, so the money is parked and visible in the balance but not paged; a crashed payment dispatch is equally silent until an instance's sweep runs | The Phase 6 → 7 transition's planning, or the first stuck refund in practice | Phase 7 |
| **The step-up on a payout destination change is conditional.** An operator with no enrolled factor proposes and approves a destination without one — `P4-TSK-007`'s pattern (ADR-0056 §3), which the Phase 6 gate's "requires step-up authorization" was read against at the review (`P6-DOC-001`) | A factor is optional for every identity on the platform, and requiring one of privileged roles is a policy across every operator surface, not a destination task's | **A weaker second rank, not an open door**: two distinct operators, the 72-hour cancellable cooling-off and the audit trail stand regardless, so one compromised operator cannot redirect a merchant's money alone | A privileged-access review, or the first operator population with money-redirecting rights and no factor | Phase 15 |
| **A refused step-up is not audited.** `403 AssuranceRequired` on a payout destination's proposal or approval, as on the Phase 4 surfaces that share the conditional step-up, leaves no audit record - nor, since Phase 7, on a bank-account registration, a withdrawal or a wallet payment (the review widened the row) | Recording refusals below the permission check is an audit-completeness question across every step-up surface, not a destination task's | **An attempt without its trail**: an operator without a second factor trying a four-eyes act is refused but not recorded; the permission refusals above it are | Audit completeness verification | Phase 15 |
| ~~**A second verified contact channel answers `500`.**~~ - **closed 2026-09-24** by `X-TSK-004`, pulled forward from Phase 15 at the owner's direction. *(Found by the Phase 6 → 7 transition's audit, in Phase 1's code.)* The decision this row left open is taken: **refused, not replaced** (`INV-IDN-06`). A channel is added with a session alone, so a verification that displaced the verified one would let a stolen password redirect recovery, with no step-up and no word to the address replaced. The store now verifies behind a savepoint and answers the index's `23505` with `VerifiedChannelAlreadyExistsException`, which the endpoint returns as `409 identity.VerifiedChannelAlreadyExists`. The refusal writes nothing, and ten instances racing verify exactly one. `EmailAddress`'s javadoc no longer cites a unique index on the address; `V011`'s column comment still does, and stays, because an applied migration is not edited. **The path was latent over HTTP** (nothing delivers a challenge before Phase 15's notifier) and live through the module's API, so it would have gone live with the notifier unchanged. Changing the verified channel is deferred to Phase 15 with that notifier (`DECISIONS.md` §Deliberately Deferred) | - | - | - | - |
| ~~**Three interleavings on the confirmation have no race test.**~~ — **paid 2026-09-28** by the Phase 7 review, `P7-DOC-001`, where the owning task had closed without paying it (the storm drove none of the three). Each is raced deterministically, the loser observed waiting on its lock: a cancel and a confirm on the intent row, both ways (`PaymentAuthorizationDatabaseTest#aCancelAndAConfirmRacingTheWindowHaveOneWinner`); a detach landing mid-confirmation and before it (`PaymentEndpointDatabaseTest#aDetachRacingAConfirmationIsJudgedAtTheAct` - two serial orders, the instrument judged at the act); and a merchant's withdrawal of the offer holding the session row, then six raced rounds each telling one story (`CheckoutFlowDatabaseTest#aWithdrawalRacingAConfirmationLeavesOneStory`). Each probed (`MUTATION_TESTING.md` §2) | — | — | — | — |
| **A poisoned row can delay a sweep.** The payments and payout sweeps - and Phase 7's withdrawal, pay-in initiation, return and dispute-answer sweeps (the review widened the row) - take the oldest candidates first and log a failing row and continue, but a row that fails every tick stays at the head of the queue and takes a slot of every batch | Skipping a failing row needs a failure count or a backoff column, a schema change on the platform's most contended tables | **Delay, not loss**: every other row is still reached, one batch slot short, and the failing row is logged every tick | A row failing repeatedly in practice, or Phase 15's runbooks | Phase 15 |
| **A trailing instance's transition is refused by the ordering checks.** Twenty-one tables order two business stamps (a later fact's must not precede an earlier one's) that different instances take from their own clocks. When instance B trails instance A by more than the time between the facts, B's transition throws, and the result is an unmodelled `500`, or the rollback of the enclosing transaction, which can be a capture's (`X-TSK-005`) | The remedy changes domain code in eleven modules and three money-moving send protocols. ADR-0063 decides it (`Proposed`), and implementing it waits on the owner's acceptance rather than riding a fixture audit | **Fail-closed availability, not financial correctness**: nothing moves wrongly and nothing is lost. The refused act is retried (by a client, a provider's redelivery or a sweep) and succeeds once the clocks' difference is behind it. NTP keeps production skew to milliseconds, so the window is the fastest machine-driven pairs of facts | ADR-0063 accepted | Cross-cutting (`X-TSK-006`) |
| **Suites that sweep with a fixed capture body.** The card sweep chains every stranded authorization in the shared test database, and `capture_provider_reference` is `UNIQUE`. A suite whose capture stub answers one fixed reference therefore collides with another suite's leftover authorization. `PaymentRefundDatabaseTest` did, after `DisputeNotificationDatabaseTest`; it was found by `X-TSK-005`'s battery and fixed | Auditing every sweeping suite's stubs is a test-isolation audit, not a clock one. The instance that failed was fixed with the harness's own `succeedsWithMintedReference`, whose javadoc names this collision | **An order-dependent red in the database tier**, never in production: a suite passes alone and fails after another | The next such failure, or the next fleet-wide battery | Cross-cutting |
| **Chargeback debt has no reserve, and a customer's receivable no collection.** A merchant payable driven negative by a chargeback after a payout is merchant debt, recovered only from later captures before any payout (`INV-MER-07` as amended); a customer wallet driven negative by a charged-back top-up the customer spent is a receivable, recorded and never written off (ADR-0061 §5). No reserve is held against either and nothing collects the receivable. ADR-0061 said both were "recorded with an owner"; neither was, until the Phase 7 review, `P7-DOC-001` | Reserves are a merchant-risk capability with their own policy, statements and consent; collection needs dunning and a customer-contact channel. Both are out of Phase 7 (`PHASE_7_PLAN.md` §17) | **Credit risk, bounded and visible, not a correctness break**: a counterparty is never charged more than it was credited (`INV-DSP-01`), every negative position is on the books and counted by `finapp.ledger.negative.positions`, and nothing is absorbed. What is missing is the recovery | The negative-position gauge reading above zero in operation, or the first payout blocked by a chargeback's debt | Phase 13 |
| **Two push-attempt states have no producer.** `EXECUTION_DISPATCHED` and `EXECUTION_UNKNOWN` are declared in the `PUSH` machine and carried by the generated `CHECK`s and edge trigger, and no code writes them: the outbound pushes are the withdrawal and the return's refund row (ADR-0059 §2 as corrected by the Phase 7 review) | Removing a state from applied schema history is a migration on the platform's most critical table for no behaviour; the states cost nothing while unwritten, and the gauges read them harmlessly | **ADR-0044's rule bent**: a state without a producer invites a reader to believe it can occur. The ADR, the lifecycle document, the plan and the enum's javadoc now all say "reserved, no producer" | The first outbound push that is an attempt (a rail whose sends belong to an intent), or the next migration recreating the attempt's edge trigger - then either produce them or drop them | The phase adding the next push rail |
| **The card webhook door applies an attempt's outcome from its unlocked attribution read.** A delivery racing another resolver converges on the row's truth and is acknowledged, the statement retained as evidence (the Phase 7 → 8 transition's audit, #30/#51/#55/#68; the instant door's twin is repaired - its applier locks, re-reads and parks value on a concluded row) *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: re-owned to Phase 15. Its trigger - Phase 8's matcher needing the flag - never fired: the matcher types the contradiction from settlement evidence, so the door's converging acknowledgement left no reconciliation gap; the observed-break trigger stands)* | The conditional edges already keep the money right on the card: a stale source makes the transition refuse and the loser converge; what is lost is only the signal that a PSP contradicted itself | A contradiction between a late card statement and the row goes unflagged beyond the retained evidence | A card reconciliation break traced to an unflagged contradiction, or Phase 8's matcher needing the flag | Phase 15 |
| **The idempotency claim row is locked first in Tx1 and last in Tx2.** A lease takeover during a flight slower than the lease can meet the original's Tx2 in a `40P01` (refund, withdrawal, dispute answer; #52) | Needs a slow flight, an expired lease and an exact overlap; the abort rolls back whole and the retry or sweep converges | Availability only: one aborted transaction, never money | An observed `40P01` on these paths | Phase 15 |
| **Advisory namespace 3 keys on a 32-bit `hashtext(attempt_id)`.** Two attempts whose keys collide share a lock (#53) | Every path takes the attempt's row lock before namespace 3, so the lock orders agree for one attempt; a cross-attempt collision is one in four billion | A false contention or, on a collision, a `40P01` — availability only | Any observed contention on the refund or dispute bound | Phase 15 |
| **A dispute answer's re-send does not re-check the dispute's stage.** The takeover and the sweep re-send a response the PSP never saw even after the dispute resolved (#60) | The PSP is the evidence's legitimate recipient and declines an answer on a resolved dispute; nothing moves money | Evidence transmitted after it could matter; one wasted call | A dispute resolution observed racing a re-send | Phase 13 |
| **Posting once-ness rests on the idempotency record beyond the parking's claim.** `journal_entry.idempotency_scope` is not `UNIQUE`; a planned retention sweep of the claims would remove the second rank (#61) | No retention sweep exists yet; every Phase 7 posting runs in an acting branch behind its own arbiter, and the parking now behind the scheme-execution claim | None while the records stand | The first idempotency-record retention sweep | Phase 15 |
| **Refused and failed Phase 7 acts leave no audit outcome, and merchant dispute acts are audited without the API key** (#63, #64) *(Widened 2026-10-02 by the Phase 8 -> 9 transition's gate, its SEC-05: Phase 8's refused four-eyes attempts - a self-approval, a stale echo, a refused kind - share it, logged and counted but not audited)* | Every Phase 7 and Phase 8 audit record is `SUCCEEDED`; refusals are logged and metered | An auditor cannot reconstruct attempted-and-refused acts from the audit trail alone | The audit completeness review of Phase 15 | Phase 15 |
| ~~**The dashboard carries no pay-in or suspense row, and no alert rules exist** (#71)~~ — **paid 2026-10-01** - by `P8-TSK-020` and `P8-TSK-024`: the rails row carries pay-ins, a "Suspense open, and the oldest" panel exists, and seventeen alert rules live in `infra/prometheus/rules/settlement-reconciliation.yml` (`SuspenseItemAged`, `SuspenseItemUnowned`, ...), resolved against a live scrape by `AlertRulesResolveTest`. Struck by the Phase 8 exit review | The gauges exist and are scraped; the alert rules are deployment configuration | `INV-REC-05`'s "alerted" rests on a log line and a gauge nobody pages on | Phase 8's suspense ageing (ADR-0070), which owns the suspense alerts | Phase 8 |
| ~~**The keyed and value-date rematch clauses compare two instances' clocks**~~ - **paid 2026-10-02** by the Phase 8 -> 9 transition's repair round: the reach is judged on ROWS - a candidate of a decision of the item, or a reach a rematch examination recorded (`match_reach`, `V016`) - with no clock in the clause (`MatchingLegCorrectionsDatabaseTest#aReachStampedBeforeItsCommitIsStillRematched`, the expectation's transaction held open across the parking; probed) — `JdbcMatchingStore.REMATCH_PREDICATE`'s key and value-date group clauses still select on `e.opened_at > LATEST_DECISION`, the expectation stamped by one instance's clock and the decision by another's (`P8-TST-001`'s storm found and fixed the same defect in the operation-anchored clause) *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: scheduled to Phase 15 - it needs a design (a clock-free keyed reach), value is never wrong and grace is the backstop)* | A clock-free form for a keyed reach needs a design: "not yet seen as a candidate" re-selects lines whose expectation is reachable only by a lower-priority key | An expectation opened within the instances' skew after a line's decision is not rematched; the line waits for its 72-hour grace and may raise a false break - value never wrong | A design for a clock-free keyed reach, or a measured skew that matters | Phase 15 |
| **A key collision's recurrence after resolution is suppressed** - the refinement `P8-TSK-013` was given at design (a collision that recurs after its break was resolved raises again) was never built: `KeyCollisionBreaks` converges on the one-open unique and suppresses the recurrence (found by the Phase 8 exit review's javadoc audit, `P8-DOC-001`, 2026-10-01; only ADR-0069 and the task history said "recorded", no register row owned it) | A recurrence leg is a sweep design of its own | A collision recurring after a person resolved it is not re-raised; the colliding keys still resolve nothing automatically, so no value moves on them | A collision observed to recur in operation | Phase 15 |
| **`V015` proves an approved resolution stands behind every closure, not that it is that break's own** - a raw writer could close a break with a RESOLVED event naming some unrelated APPROVED resolution (the Phase 8 exit review's correction, `P8-DOC-001`, 2026-10-01: before `V015` no database rule tied a closure to any record) | Tying the two per kind - `break_id` equal, a sibling on the same expectation, the offset item's break, a closure row - is a rule per resolution shape beyond an exit review's correction | Only a raw writer holding `finapp_app` can exploit it, and every domain path writes the true relation | A raw-writer audit, or the first closure found naming a foreign resolution | Phase 15 |
| ~~**Person-written reasons reach the database unscreened, and no every-column needle sweep covers `reconciliation`**~~ - **paid in full 2026-10-02** by the Phase 8 -> 9 transition's repair round: one shared screen (`sharedkernel`'s `InstrumentShapes` - Luhn-valid card numbers contiguous, spaced or dashed; account identifiers contiguous or in printed groups under mod-97; the platform's own UUIDs masked) at every reason door and its PL/pgSQL twin as CHECKs (settlement `V012`, reconciliation `V019`, a 4,000-text corpus proving the two ranks agree), with the every-column needle sweep over settlement, reconciliation and platform (`PersonWrittenReasonsAreScreenedDatabaseTest`; six probes, each rank caught alone) - decline, readmission, verification, content-read, reprocess and requeue reasons are blank- and length-checked only (`@NotBlank @Size`, `RunAdministration.reason()`), while note bodies, evidence targets, reclassification and resolution narratives pass `NoteScreen`'s PAN/IBAN shapes; settlement has its every-column sweep (`FileReceptionDatabaseTest#needleInSettlementColumns`), reconciliation relies on named sinks (found by the Phase 8 exit review's security audit, `P8-DOC-001`) | Screening every free-text reason is a cross-cutting rule, and the audit record's `reason` is already handled at the `RESTRICTED-PII` ceiling, so no written rule is broken today | An operator could paste a card number into a reason that lands in `file_event.reason` (reclassified `CONFIDENTIAL` by the review), `batch_event.reason`, `reconciliation_batch(_event).reason` or `audit_record.reason` | The first screened-field incident, or the Phase 15 data-handling review | Phase 15 |
| ~~**Claimant order differs by leg**~~ - **paid 2026-10-02** by the Phase 8 -> 9 transition's repair round: the rematch leg and the grace leg (within its expired set) now serve `(source_sequence, line_no, id)` (`MatchingLegCorrectionsDatabaseTest#theRematchLegServesClaimantOrder`, `#theGraceLegServesClaimantOrder`, both probed); ACROSS legs the sweep order still decides, now stated by INV-REC-04 and ADR-0068 section 4 as the rule rather than implied away — the run and reprocess legs serve the claimants of one expectation in `(source_sequence, line_no)`, the rematch leg in `(line_no, id)` across the source's runs, the grace leg in `(grace_until, id)` (`JdbcMatchingStore`; found by the Phase 8 exit review's documentation audit, `P8-DOC-001`, 2026-10-01) | The documents described one shared claimant order; each leg orders its own worklist, and making them agree is a matcher design change beyond an exit review | Which of two claimants a late or graced expectation allocates to can depend on the leg that reaches it first - value is never wrong (the uniques and the deferred Sigma triggers arbitrate), attribution can be | A break census disagreeing with claimant order in operation, or the duplicate-takes-a-return row being paid (the same ordering) | Phase 15 |
| ~~**A duplicate line can take a payout return before the genuine line**~~ - **paid 2026-10-02** by the Phase 8 -> 9 transition's repair round: every late leg judges with the item's true fingerprint, a park owned by a REPEATED_FINGERPRINT break and any item with a DUPLICATE decision are off the late legs' worklists (`PayoutMatchingDatabaseTest#theGenuineReturnedLineTakesTheReturn`, probed) — the anchored clause's "not yet seen" reading (`P8-TST-001`) puts a later report's repeat of a returned line, parked DUPLICATE after the return opened, on the rematch worklist, and claimant order (`line_no` across runs) may let it allocate first *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: scheduled to Phase 15 - attribution only, value conserved; the fix (a DUPLICATE verdict counting as having seen the reach) needs a test against the claimant order, which the review records rather than rushes)* | The old clause had the same race when both decisions came before the return; the fix widens its window; no test covers it | Attribution wrong (the genuine line parks at grace and a break is raised), value conserved | Have a DUPLICATE verdict count as having seen the reach, with a test | Phase 15 |
| **The reconciliation tables' owner can TRUNCATE** — every table refuses DELETE to every writer by trigger and to `finapp_app` by privilege, but row triggers do not fire on TRUNCATE (`P8-TST-002`'s schema-wide scan) | Statement-level TRUNCATE triggers on 31 tables were beyond a test task | A migrator or owner session could empty history | A statement-level `BEFORE TRUNCATE` refusal per table, by migration | Phase 15 (operational hardening) |
| ~~**A reclassification can strand a break**~~ - **paid 2026-10-02** by the Phase 8 -> 9 transition's repair round: `BreakCaseFile.reclassify` refuses (422, nothing written) unless the frozen cause keeps an exit on the target type - an acknowledgement, a kind over parked value, or the cause's own evidence closer still selecting it (`BreakCaseFileDatabaseTest#aReclassificationNeverStrandsTheBreak`, probed) — a `TIMING_DIFFERENCE` reclassified onto `PROCESSING_ERROR` stands on a decision subject no kind or evidence can close; a `STATEMENT_GAP` reclassified likewise is no longer found by `StatementChain`'s filling (`P8-TST-002`'s second gate pass; older than the task) *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: scheduled to Phase 15 - neither stranded shape owns a suspense item, so `INV-REC-05`'s "never permanent" is not engaged; the break stays visible and alertable. The fix refuses a reclassification onto a type with no exit for its subject)* | Out of the battery's scope | A break that can never close, visible but unresolvable | Refuse reclassifications to a type with no exit for the subject | Phase 15 |
| ~~**The settlement and reconciliation storm runs in its own container**~~ — **paid 2026-10-05 by `X-TSK-016`**, ahead of its Phase 15 owner, by its own trigger: `@Tag("own-container")` and `ownContainerDatabaseTest` run nine suites each in a JVM and container of its own (`TESTING.md` §5); the pull and readmission suites, which this row also named, pass in the shared JVM and stay there. *As recorded:* like the cash suites (`BankStatementCashDatabaseTest` - also the only demonstration of INV-SET-01's Phase 8 wiring, which this row omitted until the Phase 8 -> 9 transition's gate, its T-4 - `PayoutReturnDatabaseTest`, `BatchRepudiationDatabaseTest`, `UnmatchedConfirmationSuspenseDatabaseTest`, the pull and readmission suites, and since `P9-TSK-003` `JpyAndBhdPostableDatabaseTest`, which activates the seeded sources' v2 rule sets so every later opener on them pins v2), `SettlementReconciliationStormDatabaseTest` reads the whole database in its exact censuses and counts, so a fleet-wide `databaseTest` co-locating it fails it with no code wrong *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: widened to every suite run apart - the storm, `SchemeCycleCashDatabaseTest`, `PayoutSettlementCashDatabaseTest`, `PayoutReturnDatabaseTest`, `BatchRepudiationDatabaseTest`, `ResolutionBatteryDatabaseTest`, the E2E, pull and readmission suites - and the merged row above's two co-location causes (the Phase 7 storm's at-rest count and the register rebuild's comparison). The exit review ran no fleet-wide `databaseTest` on the owner's standing instruction, so the tier's co-location was not exercised)* | Global proofs and censuses are the point of a storm | A fleet-wide run reports false failures | `databaseTest` running such suites in their own JVM or container | Phase 15 |
| **Acceptance holds `settlement.source` `FOR UPDATE` for the whole acceptance** - every upload, pull and parse of that source waits while one batch is accepted (the Phase 8 -> 9 transition's gate, its MI-6) | The source row IS the acceptance's arbiter for the gapless sequence and the statement chain, taken first in the one order every writer shares - narrowing it means a new arbiter, not a smaller lock | Intake latency on a busy source during long acceptances; correctness is unaffected | The ingestion latency's accept stage (`finapp.settlement.batch.accepted`) sustaining above the alert threshold | Phase 15 |
| **Explanation reads cannot answer "why" from the external side** - no item/line/run-to-decision route exists, and the decision view omits the stored verdict and basis (the Phase 8 -> 9 transition's gate, its REC-11) | The investigation doors were built subject-first (break, resolution, case file); the decision's basis is stored whole and replayable (`P8-TSK-022`), so nothing is lost, only unexposed | An investigator walks SQL for a line-first question | The investigation-tooling review of Phase 15 | Phase 15 |
| **The app composition owns reconciliation intake rules it should only call** - the statement intake's typing and remittance construction and the recorder's cause/classification mapping live in `app`, where reconciliation's own module suites never exercise them (the Phase 8 -> 9 transition's gate, its ARCH-P8-02) | Moving them behind reconciliation entry points is a boundary refactor, not a repair; the rules are tested end to end through the app database suites today | A second caller (Phase 9's corridor intake) could re-implement a rule differently and diverge silently | `P9-TSK-014`, the first second caller of the settlement-source composition | Phase 9 |
| **A circumstantial duplicate park never returns to time's worklist** - an EXPECTATION_EXHAUSTED duplicate's park stays off the rematch worklist even after a repudiation restores the remainder it once found exhausted (the re-gate's NEW-REC-1, 2026-10-03) | The blanket DUPLICATE exclusion is what stops a repeated line taking a payout return ahead of the genuine one (REC-1); narrowing it by cause re-opens that door | The parked value waits visibly under its DUPLICATE_EXTERNAL break until a person opens a REPROCESS run (whose leg re-judges the fingerprint and allocates) or resolves it - honest, surfaced state | A DUPLICATE_EXTERNAL break outliving a repudiation of its expectation's settling batch | Phase 15 |
| **A transfer can pre-empt an in-flight instant execution the platform cannot yet name** - a scheme line carrying only SCHEME_REF (the optional end-to-end reference omitted) resolves through `scheme_execution_claim`, which is written at completion, so the lookup answers UNKNOWN while the execution is in flight and a four-eyes transfer is admitted; the late completion then posts its own credit (the re-gate's NEW-IDEM-1, 2026-10-03) | The in-flight execution holds no stored reference the lookup could resolve; refusing every UNKNOWN scheme-rail transfer would strand every genuinely unknown line | The double credit is NOT silent: the completion opens an expectation that can never reach the RESOLVED item, goes overdue, and surfaces as MISSING_EXTERNAL for a person's claw-back | The completion side learning to detect a person-resolved item (the payments-side design ADR-0071 section 2 names) | Phase 15 |
| **A rematch residual that faults every tick leaves no durable record** - the rematch leg's containment rolls back and logs WARN, unlike the reprocess leg's ERRORED examination, so a permanently faulting residual re-heads the worklist each tick with only logs to show for it (the re-gate's NEW-ATOM-1, 2026-10-03) | A durable exclusion would strand transient faults, which time's leg must retry; the drain rule (the transition's repair) keeps the leg progressing past it | One chunk slot occupied; an operator reads WARN logs, not a row | Repeated "A rematch candidate was skipped" warnings for one item across ticks | Phase 15 |
| ~~**The Phase 6 fee engine prices 0- and 3-minor-unit currencies by a 2-minor assumption**~~ - **paid 2026-10-04** by `P9-TSK-003`, and the row's premise corrected: the engine never assumed two decimals (`FeeCalculation` derives every rounding from the currency's own scale, and `FeeCalculationTest` covered 0/2/3 hermetically since Phase 6); what was deferred was the LEDGER-LEVEL fee batch, which could run only the currencies the chart held - now all five (`MerchantCaptureDatabaseTest#theFeeBatchConservesEveryMinorUnit`, 600 assessments). Was: the 0/3-minor fee-batch deferral recorded at Phase 6, now load-bearing: Phase 9 makes JPY and BHD postable (the Phase 8 → 9 transition, ADR-0074 D27) | Deferred at Phase 6 because no such currency was postable; Phase 9 ends that | A JPY fee batch priced before the repair would round at the wrong scale | `P9-TSK-003`, which pays it in the task that makes the currencies postable | Phase 9 (`P9-TSK-003`) |
| **The Phase 5–7 send permits are instance-stamped** - `last_dispatched_at` and the permit renewals are stamped from the instance clock, correct only while every rail's outcome-deadline margin exceeds the maximum instance skew (the Phase 8 → 9 transition; ADR-0057 §4's premise, `PHASE_9_PLAN.md` §7/§12.5) | The margin holds today by orders of magnitude; restamping five stores is `X-TSK-013`'s one change | A skewed instance could re-send inside another's live flight only if skew approached the deadline margin | `X-TSK-013`, scheduled in M9.9 before the storm | Phase 9 (M9.9) |
| **Pre-Phase-9 amount events carry no explicit scale** - minor-unit strings with a currency leave the scale implicit, readable only through the currency's definition (the Phase 8 → 9 transition; `PHASE_9_PLAN.md` §12.2) | Phase 9's events carry `<x>Scale` from birth; earlier producers change under `X-TSK-014` | A JPY amount on a pre-Phase-9 event shape would read at the wrong scale after `P9-TSK-003` | `X-TSK-014`'s trigger: the first pre-Phase-9 producer that can carry a 0- or 3-minor currency | Phase 15 (`X-TSK-014`) |
| **The Phase 5 and Phase 7 providers adopt a callback's own outcome** - ADR-0083 makes a verified callback a hint, adopted only from an authenticated inquiry; the PSP and instant-rail pipelines predate it (the Phase 8 → 9 transition, D25) | Re-plumbing two live pipelines is its own task with its own failure modes | A forged-but-verified callback class is already excluded by HMAC; the hint rule is defence in depth | `X-TSK-015`'s trigger: the first provider whose callback authenticity the platform cannot verify | Phase 15 (`X-TSK-015`) |
| **Funds owed to a closed customer by a parked corridor return rest in suspense** - a cross-border return whose customer has closed parks under its break with no payable party (the Phase 8 → 9 transition; `PHASE_9_PLAN.md` §12.9's returns) | The Phase 8 resolution kinds already hold the value visibly; paying it out needs the escheatment design | Value waits in `SUSPENSE_UNMATCHED`, aged and owned, until a person resolves it | The client-money/escheatment review | Phase 15 |
| **Small Phase 7 residues**: the card door's refund attribution is not scoped to its rail (#34/#75); a VOIDED payment renders `FAILED` with no reason and the redirect's void dispatch writes no dispatch audit (#35); a withdrawal's replay is gated by the resolver (#37); a book refund to a payer wallet closed since is refused with no remedy (#40); provider vocabulary outside the adapters (#46); one event version shared by every payments event (#49); the merchant drill-down's label for adjustments (#50); the routing counter counting inside Tx1 (#69); `MANUAL` capture latent in two readings (#62/#70); the storm's reach (#79/#80) *(Ruled by the Phase 8 exit review, `P8-DOC-001`, 2026-10-01: Phase 8 owns none of these: its one item (#71) is the dashboard row above, paid. The rest stay with their owners per the transition record)* | Each MINOR and none a financial-correctness defect: defence in depth, rendering, reporting or latent until a producer exists | Named per item in the transition record | Each item's own trigger in the transition record §11 | Phases 13 and 15 |

None of these is financial-correctness debt.

Per [`EXECUTION_PROTOCOL.md`](EXECUTION_PROTOCOL.md) §Architectural Debt,
**financial-correctness debt is never accepted** — an invariant is either protected or the
work is not done.

Note: items in [`DECISIONS.md`](DECISIONS.md) §Deliberately Deferred are scoping decisions,
not debt.

---

## Unresolved Architectural Questions

Ordered by when they must be answered. Each requires an ADR before the work that depends on
it begins.

| # | Question | Must resolve by | Risk if unresolved |
|---|----------|-----------------|--------------------|
| 11 | Fail-safe policy for risk evaluation: block or allow on unavailability | Phase 13 | High — a wrong default is either an outage or an open door |

Resolved since:
- ~~8. Fee model: who pays, when recognised, gross vs net settlement~~ →
  [ADR-0050](../adr/ADR-0050-fee-model-gross-capture-net-payable.md) (Phase 5 → 6
  transition, 2026-09-21). The merchant pays; revenue recognised at capture; **gross to
  the books, net to the merchant, in one journal entry** — with the fee computed once and
  the net derived by subtraction so no rounding residual can exist (`INV-MER-04`), and the
  schedule version pinned per assessment (`INV-MER-03`)
- ~~7. Whether `checkout` is its own module or part of `merchant`~~ →
  [ADR-0053](../adr/ADR-0053-checkout-session-and-order.md) (same transition). The working
  position confirmed: own module — the order outlives everything, so M2's merge trigger is
  demonstrably not met; the trigger stays recorded as the watchdog
- ~~6. Accounting treatment of authorization (memo/hold) vs capture (posting)~~ →
  [ADR-0048](../adr/ADR-0048-authorization-is-not-a-posting.md) (Phase 4 → 5 transition,
  2026-09-20). Authorization is a payment-domain fact with **no ledger effect** — the
  issuer holds the customer's external funds, so neither a memo posting (entries for
  never-money) nor a wallet hold (the wrong subject) states anything true; **the ledger's
  first touch is capture** (DR `PSP_CLEARING` / CR wallet), and the refund is the mirror
  that *does* hold, because there the funds at risk are wallet funds
- ~~9. Which payment rail to simulate first, and its finality semantics~~ →
  [ADR-0049](../adr/ADR-0049-first-provider-simulated-card-psp.md) (same transition). A
  simulated card-style PSP — the maximal exercise of the lifecycle distinctions, so the
  port cannot ship too thin — with nothing final before settlement; `INV-REV-03` stays
  subjectless until the second rail (Phase 7)
- ~~10. Which jurisdiction-neutral compliance abstractions belong in the MVP~~ —
  **answered by the Phase 1 → 2 transition's plan and Phase 2's delivery** (KYC/KYB cases,
  screening with human-resolved hits, consent as an append-only history —
  jurisdiction-neutral behind provider adapters and versioned policy, ADR-0035…0038), and
  found still sitting in the open table three phases later by the Phase 4 → 5 transition
  — the stale-second-copy class in this table, again (questions 1–4 sat the same way for
  a phase). Ruled resolved with this provenance rather than silently deleted
- ~~5. Transfer/ledger transaction boundary and compensation strategy~~ &rarr;
  [ADR-0043](../adr/ADR-0043-transfer-and-posting-commit-together.md) (Phase 3 → 4
  transition, 2026-09-17). One local transaction; a failed transfer is a committed domain
  outcome; compensation is the business reversal and nothing else; **no internal saga** —
  and the boundary at which that answer changes is named (Phase 5's payment lifecycle).
  The question's own "determines whether a saga is ever needed internally" is answered: no,
  not while ADR-0001 holds
- ~~1–4. Posting isolation and locking · chart structure · projection placement ·
  `accounts`/`wallet` as one module~~ &rarr; ADR-0039, ADR-0040, ADR-0041, ADR-0042 (Phase
  2 → 3 transition, 2026-09-13; `Accepted` at `P3-DOC-001`). *These four rows sat in the
  open table for a full phase after their ADRs were taken — found by the Phase 3 → 4
  transition while moving question 5, the stale-second-copy class in this document's own
  §Unresolved table, corrected here*
- ~~Data-access mechanism: JPA/Hibernate, Spring Data JDBC, or plain JDBC?~~ &rarr; [ADR-0033](../adr/ADR-0033-explicit-sql-and-no-object-relational-mapper.md) (`P1-TSK-001`, 2026-09-04). Explicit SQL through `JdbcClient`; no ORM. Open since `P0-TSK-011`, scheduled for Phase 3, brought forward because Phase 1 creates nine tables

Resolved during initiation:
- ~~Which modules form the initial modular-monolith cut?~~ → [`MODULE_ARCHITECTURE.md`](../architecture/MODULE_ARCHITECTURE.md)
- ~~Deployment topology?~~ → ADR-0001
- ~~Money representation?~~ → ADR-0003
- ~~Idempotency mechanism?~~ → ADR-0004
- ~~Reliable event publication?~~ → ADR-0005
- ~~Is balance authoritative or derived?~~ → ADR-0009

---

## Next Task

**`P9-TSK-013` — FX explained and settled to cash** — `READY` (the Current
Task), marked by `P9-TSK-012`'s completion gate.
*(This section read "`P8-TSK-009` — `READY`" from `P8-TSK-008`'s gate until
`P8-TSK-013`'s record found it — the stale-second-copy class, in the section whose
whole job is to mirror.)*

### Superseded: the Phase 7 → 8 transition lead (read until 2026-09-28)

The transition was the next act after `P7-DOC-001`; it is complete (§Just completed).

### Superseded: the Phase 6 → 7 transition lead (read until 2026-09-24)

**The Phase 6 → Phase 7 transition** — see [§Current Task](#current-task), which this section
mirrored. *(It named `P6-DOC-001`, the Phase 6 review record, from `P6-TSK-015`'s gate until that
review completed.)*

### Superseded: the Phase 6 review lead (read until 2026-09-24)

**`P6-DOC-001` — the Phase 6 review record** — see
[§Current Task](#current-task), which this section mirrors. *(This section named `P6-TSK-001`
from the Phase 5 → 6 transition until `P6-TSK-015`'s gate — stale across the eleven tasks
completed from `P6-TSK-001` to `P6-TSK-010`, the stale-second-copy class `P3-DOC-001` named. The superseded lead is kept below.)*

### Superseded: the Phase 6 opening lead (read until 2026-09-23)

**`P6-TSK-001` — the `merchant` and `checkout` modules and schemas.**
Phase 6's first task, first for the standing reason — the privilege floor is
what every later grant claim rests on — and for the phase-specific one: the
build-graph decisions (`merchant → ledger` declared so payable reads and payout
postings are commanded and never written; `checkout → payments` **refused** so
the purchase-experience module cannot reach provider machinery; both
`checkout ↔ merchant` edges refused) are the structure that keeps the phase's
named risks — a stored merchant balance, a god-orchestrator checkout, tenancy
as an afterthought — unreachable before any merchant code exists. Scope,
acceptance and DoD profiles in the backlog entry; the module registers in
`MODULE_ARCHITECTURE.md` §3 already carry both modules' nine attributes, M2's
provisional status confirmed by ADR-0053.

**What Phase 6 inherits, already scheduled**: the refund-sweep deferral finds
its sibling in the payout sweep (`P6-TSK-012`'s query resolution — the recorded
owner "Phase 6 or a sweeper extension" now has a phase); `INV-AUD-04` — in the
catalogue since initiation, subjectless for five phases — goes live on the
payout destination (`P6-TSK-011`); and the `refund.dispatch_key` lesson is in
`P6-TSK-001`'s own scope: classification rows land in the task that creates the
columns.

*(Its `INV-AUD-04` sentence was wrong when written: `P3-TSK-021` gave the invariant its first
subject in Phase 3. Found by `P6-TSK-011`'s design; kept verbatim, as read.)*

### Superseded: the Phase 5 → 6 transition

*(This section described the transition until it was conducted on 2026-09-21.
Its stated inheritance — the deviation the transition "may choose to close by
running one full battery" — was exercised: the transition ran the full battery,
which failed on exactly one of 2,166 tests — a `V008` column unclassified in the
data-classification register, the guard living in a tier no targeted run covers —
repaired, and green fleet-wide: 1323 / 829 / 14.)*

### Superseded: P5-TSK-001

*(This section named `P5-TSK-001` from the Phase 4 → 5 transition until Phase 5 closed on
2026-09-21. It was conducted, and the inheritance it stated — the per-credential confinement
generalisation, the `P3-TSK-015` hold-then-capture composition, and the `P0-TSK-037` provider
harness meeting its caller — was exercised by `P5-TSK-002`, `P5-TSK-015` and `P5-TSK-003`
respectively.)*

### Superseded: the Phase 4 → 5 transition

*(This section described the transition until it was conducted on 2026-09-20.
Its stated inheritance — "nothing owed … one deviation (no fleet-wide
database or kafka count), which the transition may choose to close by running
one" — was exercised: the transition ran the full battery, which failed for a
test-harness reason, was repaired, and is green fleet-wide for the first time
in the phase.)*

### Superseded: P4-DOC-001

*(This section named `P4-DOC-001` until its gate on 2026-09-19 — the review
that flipped the phase. Its two findings were the missing
`transfers.beneficiary` register row and `P4-TSK-008`'s thin backlog
evidence, both corrected in the review rather than waived.)*


### Superseded: P4-TST-002

*(This section named `P4-TST-002` until its gate on 2026-09-19. Its
audit found the set is five and four rows were owed — and, the finding
that changed what the item was, that the transfers caller had no
concurrent-duplicate test while `INV-IDEM-01`'s Verify line and the
phase's criterion 2 both name one; performed rather than recorded.)*

### Superseded: P4-TST-001

*(This section named `P4-TST-001` until its gate on 2026-09-19. Its
text is kept below, because the gate's finding changed what the item
turned out to be: the "recorded from `P4-TSK-005`'s sweep" conditional
resolved to **record** as written — that sweep did perform the moved
form — and the item then performed it again against the sustained
composition, where it **survived** until the storm's amounts were
sharpened.)*

The phase's composition demonstration (the `P3-TST-001` posture): ten
instances transferring A→B and B→A continuously — mixed amounts, some
designed to lose — while the projection verification and trial-balance
sweeps run; ended by the sweeps' floors, never by time. Plus the
register row for `INV-CON-02` with its named mutation (the availability
check moved outside the lock — recorded from `P4-TSK-005`'s sweep,
which **performed exactly the moved form** and caught it by the drain's
over-acceptance; dropped and moved are different defects, the
`P3-TST-002` lesson). Accept: every mid-storm trial-balance sweep reads
zero per currency; every verification verdict `CLEAN`/`IN_FLIGHT`,
never `DRIFTING`; the final sum over both accounts equals the starting
sum **exactly**, counted from the tables and independently recomputed;
no source ever negative. Risk: Medium, Cx: M per the backlog. DoD:
`DOD-TEST`, `DOD-FIN`.

### Superseded: P4-TSK-011

*(This section named `P4-TSK-011` until its gate on 2026-09-19.)*

### Superseded: P4-TSK-010

*(This section named `P4-TSK-010` until its gate on 2026-09-19.)*

### Superseded: P4-TSK-009

*(This section named `P4-TSK-009` until its gate on 2026-09-19.)*

### Superseded: P4-TSK-008

*(This section named `P4-TSK-008` until its gate on 2026-09-18.)*

### Superseded: P4-TSK-007

*(This section named `P4-TSK-007` until its gate on 2026-09-18.)*

### Superseded: P4-TSK-006

*(This section named `P4-TSK-006` until its gate on 2026-09-18. Its
"depends on `P4-TSK-004`" was a drift against the backlog's own
`Deps: P4-TSK-001` — noted on replacement rather than left.)*

### Superseded: P4-TSK-005

*(This section named `P4-TSK-005` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-004

*(This section named `P4-TSK-004` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-003

*(This section named `P4-TSK-003` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-002

*(This section named `P4-TSK-002` until its gate on 2026-09-17.)*

### Superseded: P4-TSK-001

*(This section named `P4-TSK-001` until its gate on 2026-09-17.)*

### Superseded: the transition itself

*(This section named the Phase 3 → 4 transition until it was conducted on
2026-09-17, and `P3-TSK-011` — eleven tasks stale — before that, until
`P3-DOC-001` replaced it.)*

### Superseded earlier: the Phase 2 → 3 transition

*(This section named the Phase 2 → 3 transition until it was conducted on 2026-09-13.)*

**Phase 3 is where money arrives.** The financial supplement F1–F8 binds for the first time;
`INV-LED-*`, `INV-BAL-*` and `INV-CON-01` become live; and mistakes become permanent, because
`INV-HIST-01` forbids editing financial history — a posting written wrongly is corrected by a
compensating entry and the wrong one stays visible for ever. The cost of a design error here is
not rework; it is a permanent record of the error. Not a backlog task: a transition is its own act,
performed under the constraint that **no application code is written**, and it is what makes
Phase 3 `READY` rather than merely planned.

It must produce what Phase 0 → 1 and Phase 1 → 2 produced: a completion audit of the
closed phase, a distributed-systems and security audit, `PHASE_3_PLAN.md`, the backlog
elaborated to task granularity with acceptance criteria, and — the part that cannot be
deferred into implementation — the **three High-risk decisions at the top of §Unresolved
Architectural Questions**: the isolation level and locking strategy for concurrent postings
(`INV-CON-01`), the chart-of-accounts structure and its relationship to the Phase 14 GL
(`INV-ACC-04`), and balance-projection placement (ADR-0009). Each is irreversible once postings
exist, which is the whole reason a transition takes them before the first one is written.

*(A second copy of the "Phase 3 is where money arrives" paragraph sat here until
`P3-DOC-001` — a duplication inside the already-superseded block, removed by the review
rather than left.)*


## Change Log

The dated change log is archived verbatim in
[`history/CHANGE_LOG.md`](history/CHANGE_LOG.md).

**Append new entries there**, newest first, in the established format. It is not loaded into
context by default, which is why it can stay as detailed as it has been.
