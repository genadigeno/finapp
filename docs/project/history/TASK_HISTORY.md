# Task History

The per-task completion records that accumulated behind `## Current Task` - 120 "Previously" blocks, newest first, from `P5-TSK-015` back to project initiation.

**Archive.** These records were moved verbatim out of
[`CURRENT_STATE.md`](../CURRENT_STATE.md) on 2026-09-20 so that the canonical description of
where the project *is* stops carrying the project's entire narrative of where it *has been*.
Nothing was edited, summarised or dropped in the move. Section headings below read as they did
when they were written.

Current state: [`CURRENT_STATE.md`](../CURRENT_STATE.md) ·
Authoritative backlog: [`BACKLOG.md`](../BACKLOG.md)

---

### Previously

**`P5-TSK-015` — the refund command: hold, then post** — `COMPLETE` (2026-09-20). **M5.7
opens at 1 of 2: the phase's money goes both ways, and `P3-TSK-015`'s owed composition
fired.** `PaymentRefund` behind `PAYMENT_REFUND` (joined `LEDGER_OPERATOR` — a permission is
never a column), the reason required (`INV-AUD-03`, asserted verbatim in the operator's own
audit record over HTTP): the dispatch takes the attempt row `FOR UPDATE` FIRST (the pinned
attempt → account order — the `P5-TSK-013` 40P01 lesson applied in advance), judges the
two-rank bound (lock-then-look over `sumNonFailedFor`, the honest 422 **before any hold**;
`V004`'s advisory-locked trigger beneath), places the Phase 3 hold — **`HoldService`'s first
production composition** — and commits before the wire call; completion releases-and-posts
atomically (`payment-refund:<refundId>`, the capture's inverse pair); failure releases with
nothing posted; **ambiguity commits `UNKNOWN` with the hold standing** (`INV-LIFE-03` with
money visibly parked on it), all through `PaymentOutcomes.applyRefund` — the extraction's
fourth consumer — as the platform through the fifth enumerated `enterSystem()` site.

| Acceptance criterion | Evidence |
|---|---|
| Ten concurrent partials accept exactly the bounded set, counted | 3 rows, 3 standing holds, sum 12.00, zero entries, one more cent unspendable — dispatched into ambiguity deliberately (the shared-stub-reference 23505 is a harness artifact, recorded) |
| Held funds unspendable mid-flight (driven) | `UNKNOWN` committed, the hold stands, a competing 1-cent spend refused by `INV-BAL-04` |
| Permissionless session refused with nothing written | 403 over real HTTP, refund and hold counts unmoved |
| The sum bound refuses raw SQL | `23514 payments_refund_is_bounded`, the schema's own rank |

### Eight mutations — one survived its first run, and that is the battery working

All eight ended caught, restores `cmp`-verified: the NAMED hold-dropped; the NAMED
release-dropped-from-completion; the posting dropped (`COMPLETED` beside no entry); the
domain bound dropped (observed: **the layers are three deep** — the hold refused the
sequential overrun before the trigger could; the trigger's rank held by the raw-SQL probe);
**the attempt lock dropped SURVIVED round one** — with the wallet holding exactly the
capture, hold placements serialize on the ACCOUNT lock and `INV-BAL-04` masked the mutation
— so the funded-wallet race was added (the account lock can no longer arbitrate) and the
mutation then failed as the trigger's `23514` where the honest 422 belongs, the gap closed
where it was found; the permission dropped; DECLINED-falls-to-ambiguity; the fail-path
release dropped. The `INV-PAY-05` register row landed with the demonstrations performed. The
gate also found and fixed the no-attempt refusal **inventing a state** (`AUTH_DISPATCHED` of
an attempt that never existed — the refusal now carries none), and reclassified
`JdbcPaymentIntentStore.findById` **`ADMINISTERED`** (the refund made "the identifier is
never a request's" false — the `P1-TSK-028` class). **Verified by targeted tiers —
`:payments:test` 108 / `:platform:test` 171 / `:app:test` 444 / the payment database suites
73 (schema 10, authorization 8, capture 6, endpoints 10, unconfigured 1, webhook 6,
transitions 7, sweeper 8, ambiguity 5, refund 9, refund endpoints 3), 0 failures, fresh runs
— the full battery deliberately skipped on the owner's instruction; no fleet-wide database
or kafka counts claimed.**

**`P5-TST-001` — the ambiguity demonstration** — `COMPLETE` (2026-09-20). **M5.6 CLOSES at
2 of 2: the phase's promise, driven whole and counted.** Five scenarios over the real
chain, all green first run: received-before-lost and timeout-then-success at both stages —
the honest `*_UNKNOWN` commits (capture's with nothing posted), the mid-ambiguity retry
converges with zero wire calls, the sweeper learns the truth, and the payment ends
`SUCCEEDED` with **`requestCount == 1` per wire path and exactly one entry counted by
reference**; and the contradiction — success claimed after the sweeper resolved
`FAILED(NEVER_RECEIVED)` — lands as refused-edge evidence beside an untouched terminal.

| Acceptance criterion | Evidence |
|---|---|
| Every scenario's effect counted, never inferred | One wire operation per path, one transition row per edge, one entry — or none where value never moved — asserted from the tables |
| The `INV-LIFE-03` register rows land with demonstrations performed | The row names the suite, both named mutations, commands and observed results — performed at this gate, early against the phase guard |

### Eight mutations — one survived its first run, and that is the battery working

All eight ended caught, restores `cmp`-verified: the **named** timeout-mapped-to-`FAILED`
(the most-expensive-mistake shape, refused by the demonstration while the harness held the
provider's approval); the **named** sweeper's-transition-made-unconditional (a race
loser's write refused by the schema beneath — the layers meeting);
every-drop-as-`NOTHING_SENT`; the `*_UNKNOWN` marking dropped; the refused-edge gate
dropped (the machine's legality exploded where quiet evidence belongs); sweep evidence
dropped; the mid-ambiguity convergence dropped; and **the 404-fold-as-licence SURVIVED
round one** — the probe's empty-bodied 404 died at the evidence bound before the
status-code fold could matter — so the probe was strengthened to the bodied 404 the
misrouted load balancer actually sends, and the mutation then failed against it. **No
production changes — a pure demonstration task. Verified by targeted tiers —
`:payments:test` 108 / `:platform:test` 171 / `:app:test` 444 / the payment database
suites 61 (schema 10, authorization 8, capture 6, endpoints 10, unconfigured 1, webhook 6,
transitions 7, sweeper 8, ambiguity 5), 0 failures, fresh runs — the full battery
deliberately skipped on the owner's instruction; no fleet-wide database or kafka counts
claimed.**

**`P5-TSK-014` — the reconciliation-by-query sweeper** — `COMPLETE` (2026-09-20).
**M5.6 opens at 1 of 2: ambiguity now resolves on the platform's own initiative.**
`PaymentSweeper` polls for `*_DISPATCHED` rows past their bound and `*_UNKNOWN` rows past
their patience, asks the provider about **our** stored reference holding no connection,
and applies answers through `PaymentOutcomes` — the extraction built for exactly this
consumer. **No lease, no leader, by design and checked**: `PaymentSweeperSchedule` is the
second named exemption to `nothingSchedulesAmbiently`, standing on the rule's *other*
half (idempotent per period), load-bearing-checked in the rule's own suite, registered in
`DISTRIBUTED_EXECUTION.md` §3 beside the relay's lease-half.

| Acceptance criterion | Evidence |
|---|---|
| The stranded and the aged resolve | `AUTH_DISPATCHED` (crash mid-call) → `AUTHORIZED` on an approved query, evidence attributed; `CAPTURE_UNKNOWN` → `CAPTURED` + **one** posting + intent `SUCCEEDED`, a second sweep changing nothing |
| Concurrent sweepers race to one winner counted | Ten sweepers → one entry, one `CAPTURED` transition row — counted in the tables; the tick's tally renamed `applied`/`skipped` on the record after the race showed "resolved" would lie about convergence |
| A sweeper racing the webhook produces one effect | Driven against the REAL webhook resolver — the `P5-TSK-013` placeholder discharged |

### The licence, its limit, and the third reason

An explicit `UNRECOGNISED` — the provider answering *in so many words* — resolves to
`FAILED(NEVER_RECEIVED)`: the third `PaymentFailureReason`, arriving with its producer
exactly as promised, through `PaymentOutcomes.applyUnrecognised` (one named method, no ad
hoc composition). `V007` regenerates the reason `CHECK` (V003 is applied history);
the migration reconciliation re-anchored to the constraint's current definition. **A 500
and a 404 alike mark the honest `*_UNKNOWN` once and never fail** — the
status-code-is-not-an-answer fold proven load-bearing where it pays. A fresh dispatch
inside its bound is not even queried; one poisoned row fails alone (the rows behind it are
other customers' money). The sweep is the **fourth** enumerated `enterSystem()` site.

### Eight mutations, all caught, restores `cmp`-verified

The **named** ambiguity-collapsed-into-the-licence (INDETERMINATE resolved to `FAILED` —
the phase's most expensive direction, refused); the **named** bounds-ignored (the fresh
dispatch swept into churn); the licence dropped; the reason swapped to `DECLINED`;
operation-kind confusion (the query missed, nothing resolved); the platform scope dropped
(structural refusal); `V007` hand-listed short (the reconciliation alone); the anti-stall
catch removed (the poisoned row stalled the healthy one). **Verified by targeted tiers —
`:payments:test` 108 / `:platform:test` 171 / `:app:test` 444 / the payment database
suites 56 (schema 10, authorization 8, capture 6, endpoints 10, unconfigured 1, webhook 6,
transitions 7, sweeper 8), 0 failures, fresh runs — the full battery deliberately skipped
on the owner's instruction; no fleet-wide database or kafka counts claimed.**

### Previously

**`P5-TSK-013` — webhook-driven transitions: idempotent, order-blind** — `COMPLETE`
(2026-09-20). **M5.5 CLOSES at 2 of 2: `INV-LIFE-03`'s question meets its first resolver.** *(Milestone count corrected at the `P5-TSK-014` gate: M5.5 is `P5-TSK-012`…`-013`, two items — the running narrative had miscounted it as "of 3".)* The
scope's named extraction fired: **`PaymentOutcomes`**, the one money-bearing outcome
application every resolver shares — the sync Tx2s became delegations (net −20 lines with a
new component; the hermetic order pins proved the refactor behavior-preserving), the
webhook's effect runs in `P5-TSK-012`'s inbox-handler seam through the same code, from the
attempt's own source state (`from` as a parameter is the order-blindness), and the sweeper
(`P5-TSK-014`) consumes it next.

| Acceptance criterion | Evidence |
|---|---|
| Each ordering scenario counted | Seven end-to-end HTTP flows: the heal (`CAPTURE_UNKNOWN` → webhook → `SUCCEEDED`, balance moved, ONE entry, the customer's GET flipping honestly), duplicate-with-fresh-id converged, `AUTH_UNKNOWN` resolved then the confirm retry chained the capture (the recovery whole), declined failing both rows, the stranded `AUTH_DISPATCHED` healed, out-of-order/late evidence-only, the total mapping refusing unrecognised and unactionable |
| The webhook-resolved capture posts exactly once, under the race | Ten concurrent resolvers, distinct event ids: one entry counted by reference, one transition, ten statements retained |

### The race found a defect, and the task fixed it on the record

Two concurrent deliveries **deadlocked (40P01)**: the evidence `INSERT`'s FK takes
`FOR KEY SHARE` on the attempt row, and the outcome's UNIQUE-column `UPDATE` needs the full
`FOR UPDATE` it blocks. Fixed by the **lock-order rule** — the effect's row lock before any
`KEY SHARE`, in every resolver's transaction (both sync Tx2s included; the latent shape
existed there too once a second resolver arrived) — with "evidence first" clarified as a
COMMIT claim: evidence, dedupe and effect still commit together. Recorded in
`DISTRIBUTED_EXECUTION.md`; the reverting mutation reproduced eight 40P01s.

### Eight mutations, all caught, restores `cmp`-verified

The posting dropped from the extracted branch (`CAPTURED` beside no entry); the
resolvable-state gates dropped (loud 500 where the evidence-only 204 belongs); the
mapping's default made success (`AUTH_UNKNOWN` became `AUTHORIZED` — the
most-expensive-mistake shape); declined dropped; the intent half of `failBoth` dropped in
the extracted copy (the -010 survivor's shape re-guarded); `from` hardcoded (the resolution
silently converged); the platform scope dropped (structural refusal); the lock-order
reverted (eight 40P01s). **Verified by targeted tiers — `:payments:test` 108 /
`:platform:test` 171 / `:app:test` 441 / the payment database suites 48 (schema 10,
authorization 8, capture 6, endpoints 10, unconfigured 1, webhook 6, transitions 7),
0 failures, fresh runs — the full battery deliberately skipped on the owner's instruction;
no fleet-wide database or kafka counts claimed.**

### Previously

**`P5-TSK-012` — webhook ingestion: authenticated, evidence-first, deduplicated** —
`COMPLETE` (2026-09-20). **M5.5 opens at 1 of 3: the forgery surface holds.** ADR-0047's
door — `POST /v1/providers/payments/webhooks`, the platform's second machine-facing route:
HMAC-SHA256 over **`timestamp + "." + raw bytes`** per provider key, constant-time,
verified **before parsing**; a two-sided freshness window (what payments adds over the
`P2-TSK-011` scheme, and why the timestamp lives inside the signed payload); verbatim
evidence + inbox dedupe on `(provider, event id)` committed together; 2xx only after
commit. The `P5-TSK-013` seam is the inbox handler — ingestion transitions nothing.

| Acceptance criterion | Evidence |
|---|---|
| Negative tests per cause, nothing written | Seven forgery shapes one byte-identical 401 — wrong/missing/tampered signatures, missing/stale/future timestamps, the KYC scheme replayed, unsigned garbage — plus the bound's 413s; evidence and inbox counts unmoved (`INV-PAY-01`) |
| Triple delivery | ONE dedupe record, THREE evidence decisions (ADR-0047 §2), every delivery a 2xx |
| Signature proven against published vectors | RFC 4231's own numbers, the composition pinned as `timestamp.body`, constant-time pinned structurally |

### Decisions and registers

**The anti-stall inversion on the record**: authentic-but-unparseable and unmappable are
acknowledged with evidence retained — the evidence row IS the detection; the webhook meter
is plan §15's, deferred to `P5-TSK-017` with the metering ceremony it belongs to.
**Credential seven** (`PaymentWebhookKey`, `/payment-webhook`, `AT_LEAST_32`) arrived as
the one-line `KeySpec` credential five promised, with its own test; the startup guard's
property sites gained credentials five and seven. Attribution by **our** minted reference
(`findByOperationReference`, the `SIGNED_CALLBACK` reasoning). The evidence bound moved to
its retention owner (`ProviderEvidenceStore.MAX_PAYLOAD_BYTES`). The test overlay gained
`finapp.payments.provider.url`; the unconfigured suite opts out with `false`. Contract
baseline +38/−0. `DISTRIBUTED_EXECUTION.md` gained the door's row (arbitration = the inbox
PK, `P0-TSK-021` cited).

### Eight mutations, all caught, restores `cmp`-verified

The **named verification-moved-after-parsing** (unsigned garbage answered 204 with
evidence written — caught by the probe added for exactly it); the timestamp dropped from
the signed payload (the KYC-replay probe alone); the window dropped; `Arrays.equals` for
`isEqual` (structural); evidence-only-when-processed (1 row where 3); the dedupe dropped;
attribution dropped; the evidence bound dropped (the empty body became our 500).
**Verified by targeted tiers — `:payments:test` 108 / `:platform:test` 171 / `:app:test`
441 / the payment database suites 41 (schema 10, authorization 8, capture 6, endpoints 10,
unconfigured 1, webhook 6), 0 failures, fresh runs — the full battery deliberately skipped
on the owner's instruction; no fleet-wide database or kafka counts claimed.**

### Previously

**`P5-TSK-011` — the payment surface over HTTP** — `COMPLETE` (2026-09-20).
**M5.4 CLOSES at 3 of 3: the phase's machinery meets its customer.** Five endpoints on the
established ceremonies — the keyed create with byte-for-byte replay, session + ownership
with one 404 across stranger's/unknown/malformed on every `{id}` route, and the
asynchronous-outcome contract shape held honestly — plus the one behavioral novelty the
task owned: **the surface is the capture's chainer** after a synchronous `AUTHORIZED`
(`PaymentCapture`'s own recorded contract), on the converged answer too, so a client
retry finishes an `AUTHORIZED` stranded by a crash between the confirm's outcome and the
chain. The chain adds no new arbitration — racing chainers are `P5-TSK-010`'s counted
ten-way race.

| Acceptance criterion | Evidence |
|---|---|
| The acceptance chain over real HTTP | attach → create → confirm → the simulated provider authorises and captures → `SUCCEEDED` in one customer-visible call → the balance moves → the statement shows the entry → the chain walked by stored identifier both ways |
| A retried create replays byte-for-byte | Proven **after** the payment succeeded: the view renders the recorded judgement (`REQUIRES_CONFIRMATION`), never a re-read — the named mutation's catcher |
| A changed request is the distinct 409 | `api.Conflict` (`INV-IDEM-03`); keyless the interceptor's 422 |
| Honestly `PROCESSING` | Twice: a lost authorization response (`AUTH_UNKNOWN` beneath, nothing chained) and a lost capture response (`CAPTURE_UNKNOWN`, **nothing posted, balance `0.00`**) — both `200`s whose body tells the truth (`INV-LIFE-03` at the contract) |

### Decisions and registers

**`PaymentsErrorCode` is the refusals only** — a judged failure is a body fact
(`200 FAILED` with the mapped reason; the provider's planted decline code asserted absent —
`INV-PAY-03` needle-tested). `payments.ProviderUnavailable` (503) is the unconfigured
deployment's honest answer (the `ObjectProvider` decision paid), distinct in kind from the
in-body `FAILED(PROVIDER_UNAVAILABLE)`. The view carries **no ledger and no attempt
identifiers** (recorded absence — the entry's reference IS the attempt id; `P5-TSK-016`'s
derived-totals view owns any revisit). The instrument fold was made **total at the bridge**
in scope: a well-formed v4 UUID is malformed here (ADR-0013) and answers the one empty —
found when the probe's 500 exposed the unguarded `PaymentMethodId.of`. Contract baseline
extended and reviewed: **393 added lines, zero removed**; the 8 `BREAKING` labels are all
`required` flags on the brand-new paths/schemas themselves.

### Eight mutations, all caught, restores `cmp`-verified

The **named capture-chain-dropped** (the acceptance chain alone: `SUCCEEDED` expected, the
never-captured `PROCESSING` answered); the **named replay-from-a-re-read** (the
post-success replay leaked the world's state); the GET's ownership predicate dropped; the
malformed-instrument fold reverted (the uniform 422 became our 500 — the in-scope fix
proven load-bearing); the cancel window's 409 swallowed; the unconfigured 503 dropped;
the chain made unconditional (capture on `AUTH_UNKNOWN` — the loud 500 where the honest
`PROCESSING` belongs); the list's ownership predicate dropped. **Verified by targeted
tiers — `:payments:test` 86 / the payment database suites 35 (schema 10, authorization 8,
capture 6, endpoints 10, unconfigured 1) / `:app:test` 438, 0 failures, fresh runs — the
full battery deliberately skipped on the owner's instruction; no fleet-wide database or
kafka counts claimed.**

### Previously

**`P5-TSK-010` — the capture command: the ledger's first touch** — `COMPLETE`
(2026-09-20). **M5.4 at 2 of 3: money moves.** The same dispatch-before-call choreography
as the confirmation, with the genuinely new thing held at its centre: **the `CAPTURED`
transition, the `payment-capture:<attemptId>` posting (DR clearing / CR wallet) and the
intent's `SUCCEEDED` are one transaction** (ADR-0048) — proven, not described.

| Acceptance criterion | Evidence |
|---|---|
| One entry per attempt, ten-way race | One wire operation, nine converged, one journal entry counted by the attempt-id reference — the conditional dispatch's row count in front, the posting's idempotency claim as the third wall |
| A rolled-back outcome leaves no posting, no transition | An injected event failure rolls Tx2 back and the rollback takes the posting, the `CAPTURED` and the `SUCCEEDED` with it — the atomicity probe, and the named posting-hoisted-out mutation's catcher |
| The balance moves and is explainable | Replay-from-zero equals the captured amount over the real ledger — `INV-BAL-02` extended with no new mechanism |
| A posting failure fails the outcome loudly | No savepoint, deliberately the transfer's inverse: the provider HAS captured, so no state that hides un-posted money may commit |

### Decisions and registers

**"PSP_CLEARING" is the chart's `SETTLEMENT_CLEARING`** — resolved on the record (the
account P3-TSK-003 actually seeded per currency; a second clearing purpose would split
the captured-but-unsettled position lifecycles §5 says this balance IS). Capture is the
platform's act end to end (`PaymentCaptureDispatched` catalogued — ADR-0046 §1's
initiation record, the platform's where the authorization's rode the person's confirm;
one new `enterSystem()` enumeration). Ambiguity commits `CAPTURE_UNKNOWN` with nothing
posted; declined and refused-connection fail both rows honestly (no retry policy until
Phase 7). The real participant chain ran end to end — party → customer → wallet product →
ledger wallet → instrument row — **paying `P5-TSK-009`'s recorded `JdbcPaymentParticipants`
deferral**. Captured is not settled (`INV-SET-01`): nothing moves clearing onward.

### Eight mutations — one survived its first run, and that is the battery working

All eight ended caught, restores `cmp`-verified byte-identical: the **named
posting-hoisted-out** (the hoisted entry survived the rollback — exactly the state the
atomicity probe refuses); ambiguity-posts (the DB probe AND the hermetic no-database
tripwire); the intent's `SUCCEEDED` dropped; the dispatch made unconditional (the ten-way
race, the schema trigger erroring the losers); evidence dropped; **the intent half of
declined dropped — SURVIVED round one**, exposing that the probe asserted the in-memory
result and never the intent ROW; the gate strengthened the probe and the mutation then
failed against it; the platform actor dropped (structural refusal); the
reference-not-stored-before-send (caught by `P5-TSK-008`'s stage-facts `CHECK` — the
layers meeting). **Verified by targeted tiers — `:payments:test` 86 / the payment
database suites 24 / `:app:test` fresh green, 0 failures — the full battery deliberately
skipped on the owner's instruction; no fleet-wide database or kafka counts claimed.**

### Previously

**`P5-TSK-009` — the authorization command: dispatch-before-call** — `COMPLETE`
(2026-09-20). **M5.4 opens at 1 of 3: ADR-0046 is production code, on the money path for
the first time** — create (keyed `payment.create`, the actor in the fingerprint), confirm
as the whole choreography in one method through the one `TransactionRunner` seam, cancel
winning only the confirmation window, and the outcome applied as the platform through the
module's first enumerated `enterSystem()` site.

| Acceptance criterion | Evidence |
|---|---|
| The two-transaction shape, proven by a crash | The crash probe: a provider dying mid-call leaves the intent `PROCESSING` and the attempt `AUTH_DISPATCHED` with its stored reference — visible, nothing else — and the hermetic order pin sees Tx1 committed and no transaction active at the wire |
| Every harness outcome drives its committed state | Approved → `AUTHORIZED` (promise recorded, trim-hostile body retained verbatim, decrypted and checksum-verified); declined → `FAILED(DECLINED)` + intent `FAILED` atomically; timeout/unknown-state → `AUTH_UNKNOWN`, intent honestly `PROCESSING`; connection-refused → `FAILED(PROVIDER_UNAVAILABLE)` — knowledge |
| A retried confirm converges | Zero provider calls on the retry, the stranded dispatch left to the sweeper (`INV-PAY-04` end to end) |
| Ten instances confirming produce one attempt | Counted in the table: one row, one wire operation, nine converged |

### What the slice carried with it

The stores (conditional transitions with the machine's own legality check in the writer),
the `PaymentParticipants` port and its app implementation (the registered
TokenReference → InstrumentToken bridge, one expression), `EvidenceCipher` (the at-rest
mechanism's third restatement) with credential six (`FINAPP_PAYMENT_EVIDENCE_KEY`,
`EXACTLY_32`) and credential five (`ProviderApiKey`) finally consumed — both
startup-guarded; four audit actions catalogued and emitted (outcome application as the
platform — a provider's answer has no session); three events through the outbox in their
facts' transactions; ten ownership-register entries with the stranger's one-empty-answer
negative test named by method; `V006` — the gate-one-task-later finding: the P5-TSK-008
histories carried `transfer_event`'s person-only `actor_id uuid` into a domain whose
outcome transitions are the platform's (`'system'` is not a UUID), fixed by moving the
three `*_event` tables to the `audit_record` actor model while provably empty.

### What deliberately did not arrive

Capture (`P5-TSK-010`, next), endpoints (`-011`), webhooks (`-012`/`-013`), the sweeper
(`-014`), refund command (`-015`), meters (`-017`). `JdbcPaymentParticipants`' real-chain
exercise is `P5-TSK-011`'s end-to-end (a composition of already-proven reads); outbound
request bytes are not captured by the port (evidence = everything received — ADR-0049's
answer types; flagged to the phase audit rather than smuggled in as an adapter change).
No ledger effect anywhere — the commands import no posting type, structurally (ADR-0048).

### Eight mutations, all caught by the intended assertion, restores byte-identical

**The named commit-after-call inversion** (the hermetic order pin AND the crash probe —
nothing stranded); the conditional transition made unconditional (the ten-way race, with
`V002`'s trigger turning the losers into errors — the layers meeting); timeout-as-failure
(the verdict suite and the converge test); the outcome applied as the person (the history
actor test); evidence retention dropped; the actor dropped from the fingerprint (**the
gate's own stranger-replay probe, added when the gate found the binding asserted nowhere —
caught alone**); the intent half of the failure dropped; the ownership predicate dropped.
**Verified by targeted tiers — `:payments:test` 81 / `:platform:test` 171 / `:app:test`
438 / the payment database suites 18, 0 failures, fresh runs — the full battery
deliberately skipped on the owner's instruction; no fleet-wide database or kafka counts
claimed.**


### Previously

**`P5-TSK-008` — the payments schema: intent, attempt, refund, evidence** — `COMPLETE`
(2026-09-20). **M5.3 CLOSES at 3 of 3: the three machines are pinned in code AND schema**
— `V002`–`V005`: intent, attempt, refund, their append-only histories and the encrypted
provider evidence, every generated artefact reconciled against its one definition by
`PaymentsMigrationTest`, every constraint exercised against raw SQL from scratch by
`PaymentsSchemaDatabaseTest` (10 tests, prepared statements only — exactly the writer the
schema must bind, no store existing yet).

| Acceptance criterion | Evidence |
|---|---|
| Every constraint exercised against raw SQL | Every named coherence `CHECK` planted-and-refused `23514` **as the migrator** (the two-shapes `FAILED` rule and the stored over-capture included); edges and freezes refused `P0001` for both roles; the smuggled-edge probe refused by the `NULL → value` payload rule; evidence UPDATE/DELETE refused `42501`/app and `P0001`/migrator |
| The reconciliations hold | Three machines × three status columns, exact trigger edge sets with terminal-absence halves, `ddl()`/`nullableDdl()` verbatim, reference shapes from the types' `MAX_LENGTH`s, `Refund.MAX_REASON_LENGTH`, `PspWireClient.MAX_EVIDENCE_BYTES`, the one-live predicate from `sqlTerminalValueList()`, every grant set pinned, the advisory namespace pinned |
| The bound refuses an over-refund for every writer | To-the-penny accepted, one unit past refused for app AND migrator, non-`CAPTURED` subject refused, a `FAILED` refund frees its budget — and **ten concurrent partials of 300 against 1000 accept exactly 3**, the write-skew shape closed by `pg_advisory_xact_lock(3, hashtext(attempt_id))` in the `BEFORE INSERT` trigger |

### The grants are the design, and the aggregates' assertions arrived at the privilege

The intent's `UPDATE` grant is **one column** — P5-TSK-006's per-field assertion made
privilege; the attempt's is status plus exactly the payload columns its doors carry —
P5-TSK-007's recorded wider-grant statement reconciled, with the sharper trigger rule the
grant cannot hold: **a recorded provider fact moves only from `NULL` to a value**, which
is what refuses a capture-amount edit smuggled inside a legal edge. The evidence is
append-only for every writer including the migrator (`INV-HIST-02`), ciphertext-shaped by
`CHECK` (GCM tag arithmetic, nonce, key version — the `kyc_document` ceremony), with the
cipher itself arriving beside its first writer (P5-TSK-009, the `ProviderApiKey`
precedent). The unattributable webhook is retained with both subjects `NULL` — "we could
not attribute it" is itself the fact an investigation starts from. Deliberate refusals
recorded in the migration headers: no not-a-PAN `CHECK`s on reference columns (a
provider's all-numeric reference is legitimate; the PCI boundary is `paymentmethods`'),
plain provider-reference `UNIQUE`s until routing adds the provider column (Phase 7).

### What deliberately did not arrive

Stores, commands, endpoints, events, audit actions, meters (`P5-TSK-009`…`-017`); the
evidence cipher and its `KeySpec` (with the first writer); the sweeper's aged-rows index
(with its query, `P5-TSK-014`); the webhook inbox table (rides platform's, by plan).
Registers fed by the task: 64 `DATA_CLASSIFICATION.md` rows at the ceiling
(`ColumnClassificationTest` green, database tier), advisory-lock **namespace 3**
registered, the `DISTRIBUTED_EXECUTION.md` §3 row for the schema's arbiters. Side
deliveries with their own tests: `MoneyColumns.nullableDdl()` (platform — a monetary fact
that arrives with a later transition), `Refund.MAX_REASON_LENGTH`,
`PaymentFailureReason.sqlValueList()`. Phase 5 `MUTATION_TESTING.md` rows stay deferred
to the phase audit per the guard's reached-phase rule.

### Eight schema mutations, all caught by the intended assertion, restores byte-identical

The one-live index dropped (ten-way race + reconciliation); the index made total (freed
slot + reconciliation); the sum check dropped (three tests — the acceptance mutation);
**the advisory lock alone removed — caught by the ten-way race ALONE with every
sequential test green, the P2-TSK-015 write-skew shape demonstrated live**; the
`NULL → value` rule removed (the smuggled-edge probe alone); the intent grant widened
(the `information_schema` sweep, which catches a widening without anyone remembering);
the evidence trigger dropped (the migrator halves alone — the grants still bound the
app); `AUTHORIZED → FAILED` smuggled into the trigger (the edge reconciliation alone).
**Verified by targeted tiers — `:payments:test` 69 / `:platform:test` 171 / `:app:test`
435 / `PaymentsSchemaDatabaseTest` 10 / `ColumnClassificationTest` 5, 0 failures, fresh
runs — the full battery deliberately skipped on the owner's instruction; no fleet-wide
database or kafka counts claimed.**


### Previously

**`P5-TSK-007` — the `PaymentAttempt` and `Refund` aggregates and machines** — `COMPLETE`
(2026-09-20). **M5.3 continues at 2 of 3: ADR-0045's other two machines are code** — the
seven-state attempt (eleven edges, both `*_DISPATCHED` states durable on purpose, both
`*_UNKNOWN` states `INV-LIFE-03` made concrete twice over) and the four-state refund,
each on the established ceremony: one constructor every path shares, per-outcome doors
through one machine check, `rehydrate` refusing corrupt rows ahead of `P5-TSK-008`'s
`CHECK`s.

| Acceptance criterion | Evidence |
|---|---|
| Exhaustive sweeps over both machines | 7 × 6 and 4 × 3 cross-products derived from `permittedTransitions()` — 31 + 7 illegal pairs refused at the aggregates themselves (`INV-LIFE-02`), 11 + 5 legal edges landing where the machines say; both terminal sets swept separately by name (`INV-LIFE-04`) |
| Deliberately-absent states asserted absent | `values()` pinned **exactly** on both enums — no `VOIDED`, `REQUIRES_ACTION`, `CLEARING`/`SETTLED`, no `REQUESTED`/aggregate-refund states — plus `AUTHORIZED`'s ONLY exit pinned by name: `AUTHORIZED → FAILED` is `VOIDED`'s job and `VOIDED` has no producer until Phase 6 |
| Coherence refused on rehydrate | Both directions everywhere the scope names it: mapped reason ⇔ `FAILED`, the issuer's promise one fact, captured pair ⇔ `CAPTURED`, refund reference ⇔ `COMPLETED`, the unreachable FAILED-with-a-promise-but-no-capture-dispatch shape refused, stored over-capture refused with the `INV-AUD-02` needle (`98_76`/`98_77` planted, currency named, values asserted absent) |

### The payload-carrying doors, and what each aggregate honestly cannot judge

Unlike the intent, **attempt transitions carry their payloads** — the fact arrives with
the answer that established it (the issuer's promise as one fact, the capture reference
minted by `dispatchCapture`) — asserted per field, so `P5-TSK-008` reads its wider
`UPDATE` grant off the asserted shape rather than rediscovering it. **`INV-PAY-05`'s
domain half split honestly in two**: capture ≤ authorized is row-local and lives in the
one constructor (trust-the-database structurally impossible, the mutation proving it);
refund-sum ≤ captured is cross-row, judged at `Refund.create` with the sibling sum an
explicit argument whose javadoc names the contract — read under the command's lock on
the attempt row (`P5-TSK-015`) — because a rehydrated refund cannot see its siblings;
the concurrent half is named to the schema trigger (`P5-TSK-008`, the `V009` pattern).
`CAPTURED` and `COMPLETED` join `SUCCEEDED` under the recorded stable-and-terminal
reading. The mapped `PaymentFailureReason` arrives with exactly the two values whose
producers exist in shipped javadoc (`DECLINED`, `PROVIDER_UNAVAILABLE`); the sweeper's
`UNRECOGNISED`-resolution value waits for its producer (`P5-TSK-014`) — the
deliberately-few licence, applied to reasons.

### What deliberately did not arrive

The schema (`P5-TSK-008`, next); store, commands, endpoints, events, audit actions,
meters (`P5-TSK-009`…`-017`); a refund failure-reason column (plan §8 gives the row
none — the provider's answer lives in retained evidence); provider-side expiry metadata
(no column, no consumer — recorded on `AUTHORIZED`'s javadoc where the lifecycle doc's
mention meets the plan's omission); `VOIDED` and multi-attempt retry (producers arrive
Phase 6/7). No `DISTRIBUTED_EXECUTION.md` §3 row — no runtime state (third occurrence
of the recorded absence-is-the-design class); cross-instance arbitration is the
one-live-attempt index, the conditional transitions and the lock-then-look sum, each
named to its owner. Phase 5 `MUTATION_TESTING.md` register rows deferred to the phase
audit per the guard's reached-phase rule — the battery lives in the gate evidence.

### Eight mutations, all caught by the intended assertion, restores byte-identical

The machine check removed from a door (both sweeps); `CAPTURED` given an edge (**the
pin by name** — and unlike the intent, the coherence rules make even the derived sweep
object, because a captured pair cannot survive into `FAILED`); `AUTHORIZED → FAILED`
smuggled in (the pin, plus the sweep failing on the FAILED-shape rule — that rule
proven load-bearing); the capture bound dropped; the bound moved to the capture door
only — trust-the-database (**the rehydrate case ALONE**); the refusal made to name the
amounts (the needle alone — `Money`'s rendering carries the value, again); the refund
sum bound dropped; the refund's reference ⇔ `COMPLETED` dropped (the rehydrate test
alone). **Verified by targeted tiers — `:payments:test` 56 / `:app:test` 435, 0
failures, fresh runs — the full battery deliberately skipped on the owner's
instruction; no fleet-wide database or kafka counts claimed.**



### Previously

**`P5-TSK-006` — the `PaymentIntent` aggregate and machine** — `COMPLETE`
(2026-09-20). **M5.3 opens at 1 of 3: ADR-0045's intent machine is code** — five states,
four edges, every state producer-earned and durably observable (unlike the transfer's
`INITIATED`, creation commits `REQUIRES_CONFIRMATION`, which is what gives `CANCELLED`
its producer), held at the aggregate by one constructor every path shares.

| Acceptance criterion | Evidence |
|---|---|
| Every invalid transition rejected | The cross-product sweep derived from `permittedTransitions()` — 5 states × 4 doors, all 16 illegal pairs refused by the aggregate itself (`INV-LIFE-02`), the 4 legal ones landing where the machine says |
| Both terminals and the stable state swept | `CANCELLED`, `FAILED` and `SUCCEEDED` each refuse every door, swept separately by name — the accept's own wording is what the test says (`INV-LIFE-04`) |
| `INV-AUD-02` needle-asserted on refusals | The positivity refusal names the fact and the currency, never the value (`9876`/`98.76` planted and asserted absent) — at birth and at rehydrate, because it is the same constructor |

### The machine is pinned, and the SUCCEEDED mutation is why

Each state's transition set is pinned exactly — `SUCCEEDED`'s **no outgoing edge by
name** (the backlog's own property), nothing transitioning TO `REQUIRES_CONFIRMATION`
(birth the only door), the terminal set exactly `{SUCCEEDED, FAILED, CANCELLED}` —
because a sweep derived from the machine **follows the machine**: the mutation giving
`SUCCEEDED` an edge (refund-state-on-the-intent, the exact mistake ADR-0045 refuses)
left the cross-product sweep green and was caught by the pin, the stable-state sweep
and the SQL-literal pin. **The one interpretive decision is on the record in the enum's
javadoc**: `SUCCEEDED` is ADR-0045's *stable* state AND in the terminal set —
`isTerminal()` stays the structural derivation (the `TransferStatus` idiom),
`INV-LIFE-04`'s own text names refund as a new operation out of a terminal state, and
nothing downstream wants a live/stable split (no one-live index on the intent, plan §8)
— the inverse of `TransferStatus`'s recorded `COMPLETED` exclusion, argued where
`P5-TSK-008`'s reviewer will meet it.

### The intent's coherence is status-independent, and that is the design

Every status-dependent payload lives where its fact lives — the mapped reason on the
attempt (`PAYMENT_LIFECYCLES.md` §2), the capture's posting evidence on the attempt,
refund totals on the refund rows — ADR-0045's one-fact-one-place applied to the field
set. So nothing but `status` ever changes after birth (asserted per field), which is
what lets `P5-TSK-008` narrow the `UPDATE` grant to that one column; what the one
constructor holds is presence and strict positivity, at birth and on read-back alike.
Typed `LedgerAccountId` for the wallet (the declared edge's purpose); party, customer
and instrument raw `UUID`s — the instrument raw **because `payments` has no edge to the
PCI module at all**, so the typed id is structurally unimportable (the `Transfer`
precedent, sharpened). A **class rather than a record, load-bearing**: a record's
generated `toString` renders `Money`, and payment amounts are `RESTRICTED-FINANCIAL`.
The SQL fragments are pinned by literal until `P5-TSK-008`'s reconciliation consumes
them — a generator nothing verifies is dead code carrying confident javadoc
(`P1-TSK-013`).

### What deliberately did not arrive

The attempt and refund machines (`P5-TSK-007` — and with them every payments failure
reason: the intent's `FAILED` carries no copy of the attempt's); the schema
(`P5-TSK-008`); store, commands, endpoints, events, audit actions, meters
(`P5-TSK-009`…`-017`, the licence in `package-info`, whose "what exists so far"
paragraph this task updated — the recurring staleness class, paid by the task that
caused it); amount-currency ⇔ wallet-currency agreement (the command's authoritative
resolution and `P5-TSK-008`'s composite FK — an aggregate holding the account *id*
structurally cannot check it). No `DISTRIBUTED_EXECUTION.md` §3 row, and the absence is
the design: no runtime state of any kind (the `P4-TSK-003` precedent) — cross-instance
arbitration is the schema's every-writer trigger and the commands' conditional row
counts, named to their owners.

### Eight mutations, all caught by the intended assertion, restores byte-identical

The machine check removed from the door (the sweep, plus the terminal sweep);
`SUCCEEDED` given an edge (**the pin — the derived sweep followed the machine**, which
is the assertion's reason to exist); a terminal given an exit; the positivity refusal
dropped (both needle tests); the refusal made to name the amount (the needle alone —
which also proved `Money`'s rendering does carry the value); `sqlTerminalValueList`
hand-listed without `SUCCEEDED` (the literal pin alone); positivity checked at birth
only — trust-the-database (**caught by the rehydrate test ALONE**, proving it
load-bearing beyond the dropped-check mutation); the birth status changed. Restores
verified by `cmp` against backup copies, never `git checkout --`. **Verified by
targeted tiers — `:payments:test` 41 and `:app:test` 435 with every guard green over
the new types, 0 failures, fresh runs — the full battery deliberately skipped on the
owner's instruction; no fleet-wide database or kafka counts claimed.**



### Previously

**`P5-TSK-005` — the payment-method endpoints and the step-up point** — `COMPLETE`
(2026-09-20). **M5.2 CLOSES at 2 of 2: a person can attach, list and detach an instrument
over HTTP, and nothing raw ever touches the platform** — the body carries only the
provider's one-time grant, display metadata is the provider's answer (a client
structurally cannot lie about brand or last4), and a card-number-shaped grant is turned
away at the boundary before any exchange (`INV-PAY-02` at the surface).

| Acceptance criterion | Evidence |
|---|---|
| The whole flow over real HTTP | `PaymentMethodEndpointDatabaseTest` (8 tests) against the `SimulatedProvider` wired through the deployment property: attach 201 with provider-sourced metadata → listed → detach 204 with the row surviving `DETACHED` → the repeat converging, the detach staying **one act counted in `audit_record` and `outbox_event`** |
| The step-up refusal with nothing written | The enrolled identity at `PASSWORD`: 403 `identity.AssuranceRequired`, **zero rows and zero exchanges** (`requestCount` 0 — the fail-fast runs before the wire); the positive control proves the factor over the whole real MFA flow and lands 201, audited as the **person** with a null summary |
| The outage attach fails clean | The honest **503 `paymentmethods.TokenisationUnavailable`** — the platform's first 5xx domain code, reasoned in `ERROR_CONTRACT.md` (retryable, our side, no client remedy) — with nothing written; the refused grant the actionable 422 `paymentmethods.InstrumentNotTokenised` |
| Ownership one-404 as an equality | `aStrangersPaymentMethodIdIsOne404OnDelete`: stranger's, unknown and malformed byte-identical under `normalized()`, the owner's row unmoved — the negative test the ownership register names by exact method |

### The exchange sits between two transactions, and the step-up is checked in both

Tx1 fails fast (party + conditional step-up, read-only — a refused caller costs no
exchange, **proven by the mutation**: the fail-fast alone removed keeps the 403 and moves
`requestCount` 0 → 1, so the two checks are genuinely two); the exchange holds **no
database connection** (`P1-TSK-026`); Tx2 re-checks the step-up authoritatively, then
`attachOrConverge` + audit + event in one transaction (`INV-EVT-01`), the created path
only. The tokenisation port is **total** — `TOKENISED`/`REFUSED`/`UNAVAILABLE`, default
never success, and an answer whose token `TokenReference` refuses (a PAN-shaped one
included) is `UNAVAILABLE`, never stored. **No credential on the exchange, deliberately**
(the Phase 2 verification-adapter precedent); the absent provider keeps a stable contract
(`ObjectProvider`, absent = the same 503).

### The classifier caught a real breaking change, and it was withdrawn

A second `list` handler renamed the beneficiary surface's **published** `operationId` to
`list_1` — the `P2-TSK-006` `view_1` trap verbatim, on an endpoint this task never
touched. Fixed by naming the new method `listPaymentMethods`; the baseline is then
**+41 lines, zero removed**, the three remaining `BREAKING` labels the classifier erring
safe on the brand-new path's own `required` members (reviewed, the standing precedent).
**And the platform's own guard found the empty event**: `EventPayload.of()` with no field
is refused by its own invariant, so every successful attach was our 500 until the payload
carried the enumerated `status` (the sibling emitters' shape) — caught by the suite
before commit. `secretsAreWrapped` fired on a `Pattern` named `TOKEN_FIELD` and got the
accurate-rename answer again (`REFERENCE_FIELD`, named for what it yields).

### Eight mutations, all caught by the intended assertion, restores byte-identical

Both step-up checks neutralised (403 → 201); the Tx1 fail-fast alone removed (caught by
`requestCount`); the converged attach audited too (`1 but was: 2`); the detach act
dropped (1 → 0); the adapter's default branch made to tokenise (**caught by the
reference-carrying unknown-status probe** — the `P5-TSK-003` lesson pre-applied, the
body carrying a usable instrument a trusting default would store); the grant's PAN-shape
refusal dropped; the attach event dropped (outbox 1 → 0); the listing's live filter
dropped (the detached row re-appearing). One cut on analysis and recorded: Tx2's
re-check removed **alone** is invisible behind the fail-fast (the enrol-during-exchange
interleaving, held by the stated design — the `P3-TSK-014` class). **Verified by
targeted tiers — `:app:test` 435, `:paymentmethods:test` 30, the endpoint suite 8, all
0 failures, fresh runs — the full battery deliberately skipped on the owner's
instruction; no fleet-wide database or kafka counts claimed.**


### Previously

**`P5-TSK-004` — the `PaymentMethod` aggregate and schema** — `COMPLETE` (2026-09-20).
**M5.2 opens at 1 of 2: the PCI boundary has its subject** — and the part genuinely this
task's, on the `Beneficiary`/`V003` ceremony's fifth performance, is that **`INV-PAY-02`
landed at `DB-CONSTRAINT` rank**: not "we don't store a PAN" but *a PAN cannot physically
be stored in any column of this schema*.

| Acceptance criterion | Evidence |
|---|---|
| The race counted | Ten instances attaching one instrument: **one live row counted in the table**, one `created`, nine converged **onto the winner's row id** — the partial unique (party, token) index arbitrating behind the savepoint, the `P4-TSK-006` protocol verbatim |
| The sweep green | **The PAN sweep**: every text column derived from `information_schema`, three card-number shapes (bare, hyphenated, 15-digit) planted per column, each refused `23514` by that column's own `CHECK` — token not-a-PAN, brand's digit-free charset, suffix exactly-four — so a text column added later without a PAN-refusing shape *fails the sweep*; non-text columns recorded structurally unable. The not-a-PAN `CHECK` dropped is **the acceptance mutation**, and the sweep fails naming the token column |
| The freeze proven as the migrator | Resurrection refused (`P0001`), and the **smuggled-edge probe** — a token edit hidden inside the legal detach edge, the only shape that isolates the freeze from the edge check — refused too; the coherence pair and unknown status refused from scratch at `CHECK` rank |
| The machine swept exhaustively | The cross-product derived from `values()`/`permittedTransitions()`, the machine **pinned** (one edge, `DETACHED` terminal, nothing transitions TO `ACTIVE` — birth the only door), coherence refused on rehydrate in both directions |

### The wrapped token, restated where the import is forbidden

`TokenReference` is the `InstrumentToken` mechanism **restated, not imported** — the PCI
module sees no business sibling in either direction, so the wrapped-token discipline is
restated the way `DocumentCipher` restates `SecretCipher`'s, with the duplication as the
isolation's recorded cost. Component named `secret` (the `RawPassword` idiom: the name is
the control), `expose()` a registered unwrapping method, and two whitelist entries — the
type (validates, re-exposes) and `JdbcPaymentMethodStore` (writes the column; the one
production caller). And one rule the in-flight twin does not need: **a digits-and-separators
value is refused** — `4111-1111-1111-1111` is a formatted card number, not a token — at the
domain and at `DB-CONSTRAINT` rank both. `secretsAreWrapped` fired on the pattern constant
named for the card number, and the **accurate-rename** answer applied
(`DIGITS_AND_SEPARATORS`, named for what it matches — the `REQUIRED_ENVIRONMENT_VARIABLE`
precedent).

### The token column is plaintext, and the decision is on the record

Hashing is unavailable — the token must be *presented* to the provider, not compared — and
what bounds a leaked column is that a token alone charges nothing: the provider requires
the confined API credential, held outside the database (`P5-TSK-002`/`-003`), and tokens
are revocable by detach. Encryption at rest under the generalised key mechanism is the
recorded seam (one migration plus the `DocumentCipher` shape); the plan mandates it for
`provider_evidence` and deliberately not here. The migration header carries the reasoning
where the next reviewer will meet it.

### The registers fed by the task, not by the next audit

**`DISTRIBUTED_EXECUTION.md` §3 gained its row in this task** — the register-decay class's
fifth occurrence was precisely `P4-TSK-006`'s sibling table never getting one until the
exit review's hand-diff; not repeated. `DATA_CLASSIFICATION.md` gained the ten columns at
their ceiling (`token_reference` and `display_suffix` `RESTRICTED-PII` — the instrument
reference and a partial instrument identifier), the ownership register gained `detach`
(`OWNER_SCOPED`, `party_id = ?` in the statement, negative test named), and every register
guard is green over the additions. **Expiry is month AND year** — the plan's §8 shorthand
("expiry month") corrected on being met, because a month without a year is not display
metadata anyone can render.

### What deliberately did not arrive

Endpoints, step-up, the simulated tokenisation exchange, audit actions and events (all
`P5-TSK-005`'s, the surface whose design fixes them — the `P4-TSK-006` → `-007` split);
beans (the licence); the surface reads (`findOwned`, the listing — named in the store's
javadoc; `V002`'s by-party index is already there for them); a history table (**the plan's
own reading**: "append-only history discipline as for beneficiaries" means the detached row
survives as the evidence, and `Beneficiary` has no event table either); token encryption
(the seam above). No `DOD-FIN` — an instrument reference moves no money.

### Eight mutations, all caught by the intended assertion, restores byte-identical

The one-live index dropped (`Expected size: 1 but was: 10`); the index made total
(**caught twice**: the freed slot and the hermetic reconciliation); the trigger's edge
check removed (resurrection succeeds); the frozen-identity check removed (exactly the
smuggled-edge probe); **the not-a-PAN `CHECK` dropped — the `INV-PAY-02` acceptance
mutation, the sweep failing on the token column**; the detach's ownership predicate dropped
(**caught twice**: the stranger's detach behaviourally, and `OwnershipIsScopedTest` naming
the missing predicate); the aggregate's machine check removed (the sweep, plus the
double-detach rendering probe); the coherence `CHECK` dropped (the raw-SQL refusal, with
the hermetic reconciliation pinning it too). **Verified by targeted tiers —
`:paymentmethods:test`, `:app:test` with every register guard green,
`PaymentMethodDatabaseTest` (6 tests) and `ColumnClassificationTest` fresh over the new
schema — the full battery deliberately skipped on the owner's instruction; no fleet-wide
database or kafka counts claimed.**

### Previously

**`P5-TSK-003` — the provider port and the simulated card PSP adapter** — `COMPLETE`
(2026-09-20). **M5.1 CLOSES at 3 of 3**, and its stated acceptance — *both modules and
schemas exist with their floors; the credential confinement is one mechanism; the provider
port speaks every harness failure mode* — holds by demonstration on all three clauses. The
`P0-TSK-037` harness meets the payment caller it was built for.

| Acceptance criterion | Evidence |
|---|---|
| Every harness mode drives a defined port outcome | The matrix, every row a test: approved / declined / approved-sans-reference / unknown state / malformed / garbage / bodyless and bodied 5xx / timeout / refused connection / received-then-lost / slow-but-in-time / oversized — and **misbehaviour is a result, never an exception** (the `P2-TSK-009` totality rule, with money on it now), proven by the rethrow mutation failing three tests at once |
| The unknown-state answer maps to indeterminate | **Twice**: the harness's own unknown-state body, and a reference-carrying variant the mutation analysis demanded — because the harness body carries no reference, so a default-branch-approves mutation would have dodged the easy half. The `INV-PAY-03` acceptance mutation is caught by exactly that probe |
| A re-dispatched operation presents the same reference | **Asserted at the wire**, not at the record: the harness gained `headerValues` (strings only — its no-WireMock-in-the-signature rule), and two authorize calls with one request show one `Idempotency-Key` value twice. `INV-PAY-04` lives in the provider's dedupe, which sees headers, and a fresh-reference-per-dispatch mutation fails here and on the capture path |

### The verdict vocabulary draws the money's one distinction

`APPROVED` · `DECLINED` · `NOTHING_SENT` · `INDETERMINATE`, and each is one edge of the
attempt machine. **Only a refused connection (`ConnectException`) is `NOTHING_SENT`** — an
RST arrived, nothing was transmitted, the operation cannot have happened, so the caller
commits `FAILED(PROVIDER_UNAVAILABLE)` (the lifecycle document's own sentence). A connect
*timeout* is silence, and silence is ambiguity: every other transport failure and every
unparseable answer is `INDETERMINATE`, because erring that way costs one sweeper query
while erring toward failure is the double-effect direction. **`APPROVED` requires the
parsed provider reference** — an approval the capture cannot act on is not knowledge we
can use — enforced in the answer's constructor and mapped to `INDETERMINATE` by the
client. The query's `UNRECOGNISED` (the sweeper's licence to resolve a stranded dispatch
to `FAILED`) is earned **only by an explicit parsed answer**: a 404 is a status code, and
a misrouted load balancer must not fail a live operation. **A finer decline taxonomy is
deliberately absent** — reason vocabulary with no consumer (ADR-0044's doctrine applied to
reasons); the provider's own code lives in the retained evidence, `INV-PAY-03`'s one home.

### `secretsAreWrapped` fired four times on my own code, and the rule won every time

The first version held the instrument token as a `String` and the bearer header as a
field, and the rule refused all of it. The token became **`InstrumentToken`** — the
`RawPassword` idiom exactly: a record wrapping `Sensitive<String>` whose component is
*named* `secret` so the existing rule enforces the wrapping for ever, charset validated at
construction (`[A-Za-z0-9_-]`, so the wire needs no escaping machinery), `expose()`
registered as an unwrapping method in `SecretsAreUnwrappedInOnePlaceTest` so every caller
is a named entry — and the only production caller is the adapter putting the token on the
provider wire, which is the one place it legitimately goes (`INV-PAY-02`: the token IS
what we hold instead of raw card data, and the provider is who it is for). The client
holds `byte[] key` — the `CallbackSignature`/`DocumentCipher` idiom, `key` being the one
name ADR-0019 deliberately keeps outside the vocabulary — with the header value built per
request and stored nowhere. And the raw BEL byte that slipped into a test literal was made
an explicit `\u0007` escape before it shipped (the `P1-TSK-016` invisible-character
lesson, caught in self-review this time).

### The fifth credential arrived as a declaration, which is what `P5-TSK-002` was for

`ProviderApiKey` in `app.payments`: one `KeySpec` — `FINAPP_PAYMENT_PROVIDER_KEY`, suffix
`/payment-provider` (the webhook key's coming `/payment-webhook` stays distinct),
`AT_LEAST_32` on the `CallbackKey` argument (a bearer secret has no valid-but-weaker
interpretation) — plus `ProviderApiKeyTest`, because the `P2-TSK-011` finding was a
credential class with no test whose confinement nobody would notice removed. The adapter
takes decoded bytes through its constructor (the `DocumentKey`/`DocumentCipher` split);
the consuming bean and the startup-guard extension are `P5-TSK-009`'s, when the bean
exists to decode at startup.

### What deliberately did not arrive

**No beans** — the unconsumed-wiring licence (`P1-TSK-007`: two beans were once deleted
precisely because nothing consumed them); `P5-TSK-009` wires, and what this task fixed so
it wires without a naming decision is recorded in the adapter's javadoc:
`finapp.payments.provider.url` with **no default** (the `KycBeans` shape — an unconfigured
deployment carries no adapter aimed at nothing, and the backlog's "deployable simulated
endpoint (the Phase 2 verification-provider shape)" resolves, on inspection of what
Phase 2 actually built, to exactly that conditional wiring plus whatever a demo stands
up), `finapp.payments.provider.timeout` defaulting `PT2S`. No store, no table, no events,
no audit action, no meters (`P5-TSK-017`'s, with `providerName()` as the tag value's one
definition, waiting). **No `DISTRIBUTED_EXECUTION.md` §3 row, and the absence is the
design**: the adapter is stateless — no lock, no cache, no scheduler, no clock read — and
what makes ten instances dispatching the same operation safe is the reference in the
signature, not coordination.

### Seven mutations, all caught by the intended assertion, restores byte-identical

The default branch made to approve when a reference is present (the `INV-PAY-03`
acceptance — caught by the reference-carrying unknown-state probe, added first because
the analysis showed the plain probe could not see it); the `IOException` catch-all
classified as knowledge (timeout-becomes-failure, the phase's named most expensive
assumption — caught by the timeout and received-then-lost tests, whose `requestCount`
oracle is the point); a fresh reference minted per dispatch (the `INV-PAY-04` acceptance —
caught at the wire, twice); the `APPROVED`-requires-reference coherence dropped; non-200
evidence dropped (`INV-HIST-02`); the `Authorization` header dropped (a credential nothing
sends is decorative); the client rethrowing instead of mapping (totality — three tests at
once). **Verified by targeted tiers — `:payments:test` 34, `:app:test` 435,
`:platform:test` 170, 0 failures, all fresh runs — the full battery deliberately skipped
on the owner's instruction; no fleet-wide database or kafka counts claimed.**

### Previously

**`P5-TSK-002` — the per-credential confinement, generalised** — `COMPLETE`
(2026-09-20). **M5.1 is 2 of 3, and the debt row that fired at `P2-TSK-011` is paid**:
four hand-written copies of the ADR-0020 shape are one mechanism, before the fifth and
sixth credentials arrive to become specs rather than classes.

| Acceptance criterion | Evidence |
|---|---|
| One definition of the confinement | `ConfinedCredential` + `KeySpec` in `app.security`, beside `DatabaseEndpoint` (which stays the one loopback answer, passed in — the beans changed not at all). The five axes the four copies actually varied on became the spec: name, confinement name, environment variable, domain suffix, `EXACTLY_32`/`AT_LEAST_32`, refusal tail |
| All four guards green with **no test edited** | `MfaKeyTest`, `DocumentKeyTest`, `CallbackKeyTest`, `DatabaseCredentialGuardTest`(+`Startup`) untouched and green — the equivalence proof, available because behaviour was preserved byte for byte: signatures, exception types, message texts, derived local bytes (MFA's suffix empty, its bytes predating the suffix idea) |
| Removing the confinement fails every consumer's guard test | **Performed as the acceptance mutation**: `localDefaultPermitted` ignored in the shared decode fails the confinement tests of MfaKey, DocumentKey, CallbackKey **and** the new spec at once — the property that makes one definition better than four |

### The generalisation found the drift it exists to prevent, already present

**The marker literal lived twice in Java** — `DatabaseCredentialGuard.MARKED_LOCAL_DEFAULT`
and `MfaKey.MARKED_LOCAL_DEFAULT`, two constants holding the same published string, with
`DocumentKey` and `CallbackKey` referencing the second — while `MfaKey`'s own javadoc
claimed *"this repository holds exactly one published default"*. Nothing reconciled the two
(the configuration-file rule covers YAML/Kotlin/SQL, not Java constants). Now there is one
literal, in `ConfinedCredential`, with both long-standing public constants kept as
references — and the reference chain is pinned by the new suite's `isSameAs` check, whose
mutation (the guard's constant drifted back to a second literal) is caught twice.

### What the new suite covers, and only that

`ConfinedCredentialTest` deliberately tests the **next-consumer** half no existing suite
can: a fifth spec — shaped like `P5-TSK-012`'s webhook key — derives local bytes
domain-separated from **all three** existing keys pairwise, inherits the confinement naming
its own variable, never echoes a configured value, and gets both length rules (the AES-128
trap and HMAC's longer-is-permitted) from the mechanism. Everything else stays proven where
it always was.

### Six mutations, all caught by the intended assertion, restores byte-identical

The confinement removed (the acceptance, above); the domain suffix dropped (both existing
domain-separation tests plus the pairwise new one); `EXACTLY_32` relaxed past 16 bytes;
`AT_LEAST_32` tightened to exactly-32 (CallbackKey's longer-is-permitted half — the two
length rules proven to be genuinely two); the base64 refusal made to echo the value
(`INV-AUD-02` at the mechanism); the marker reference drifted. **Verified by targeted
tiers — `:app:test` green including every security architecture rule over the reshaped
classes — the full battery deliberately skipped on the owner's instruction; no fleet-wide
database or kafka counts claimed.**

### Previously

**`P5-TSK-001` — the `payments` and `paymentmethods` modules and schemas** —
`COMPLETE` (2026-09-20). **Phase 5 is `IN_PROGRESS`; M5.1 opens at 1 of 3.** The module
shape's fifth and sixth performances — and the part genuinely this phase's is that the
build graph now carries **two different boundary decisions at once**: an edge that must
exist and an edge that must not.

| Acceptance criterion | Evidence |
|---|---|
| Build green with both modules | **1161 hermetic tests, 0 failures** — the +4 the two isolation tests' own methods; both lockfiles identical to `transfers`'s but for the header; verification metadata unchanged |
| Both floors proven live | Throwaway `postgres:18.6` with the real role script: migrate → validate → re-migrate idempotent for both schemas; owner `finapp_migrator`; ACL exactly `{finapp_migrator=UC, finapp_app=U}`, no `PUBLIC` entry; `USAGE` and **not** `CREATE`; zero application tables; each history = Flyway's marker + one versioned row |
| Both isolation asymmetries demonstrated | `payments → ledger` declared with the reverse edge refused by **Gradle configuration as a cycle** (planted, demonstrated); **`payments → paymentmethods` refused by `PaymentsModuleIsolationTest` alone** — planted and caught by exactly *payments must not depend on paymentmethods*, the load-bearing probe, because **no cycle backs the PCI refusal and the test is the only control** |
| The planted probes caught | Six of six, restores verified byte-identical: the two planted `double`s each caught **naming their module** (run once per module — one catch cannot vouch for the other's coverage), `payments → party`, `accounts → payments`, the cycle, the PCI edge |

### The PCI boundary is the design's crux, and it is a test, not a cycle

Every earlier sibling refusal was either backed by a Gradle cycle (`ledger → transfers`)
or symmetric coupling hygiene. `payments → paymentmethods` is neither: adding the edge
would configure and compile cleanly, so the isolation test's forbidden-list entry is the
**only** thing between the provider-facing module and the instrument boundary — which is
why the probe for exactly that entry was the one this task could not skip, and why
`paymentmethods` is now the most isolated business module on the platform (no business
sibling in either direction; `platform` and `sharedkernel` only). The instrument will
resolve through a port `app` implements (`P5-TSK-009`), the established shape.

### What deliberately did not arrive

No tables (`P5-TSK-004`/`-008`), no aggregates (`P5-TSK-006`/`-007`), no provider port
(`P5-TSK-003`), no beans, no endpoints, no events, no meters, **no audit-action enums**
(the deliberately-few licence, recorded in both `package-info` files with the owning
tasks named), and no credential — the provider keys arrive through `P5-TSK-002`'s
generalised confinement. `DISTRIBUTED_EXECUTION.md` §3 gains **no row, and the absence
is the design**: the task introduces no runtime state of any kind (the `P4-TSK-001`
precedent). All seven sibling isolation tests gained both modules in their forbidden
lists — the one-directional-decay lesson, sixth application at design time.

### Process notes, recorded

The first planted-`double` probe **never compiled**: PowerShell's `utf8` wrote a BOM and
javac refused the file — a mutation must compile to prove anything (`P1-TSK-026`'s rule),
re-planted clean. The stale-XML trap was met and dodged: after the probe runs, the on-disk
test results mixed the fleet run with the deliberate failures, so the final count came
from a fresh fleet run rather than from whatever the last probe left behind (the
reports-for-work-it-did-not-do class). And the `build-logic` Kotlin RC3→GA lockfile drift
appeared for the **fifth** time and was reverted on the standing precedent.

### Previously

**Phase 4 → Phase 5 transition** — **CONDUCTED** (2026-09-20).
[`reviews/PHASE_4_TO_5_TRANSITION.md`](reviews/PHASE_4_TO_5_TRANSITION.md)

| Part | Outcome |
|---|---|
| Phase 4 completion audit, 18 categories | **18 `PASS`** |
| Financial correctness audit, 11 properties | **11 `PASS`** — each against its database-rank mechanism |
| Multi-instance audit | **`PASS`** — fresh zero-occurrence single-instance sweep over Phase 4 code; every contended decision arbitrated by PostgreSQL with a counted race |
| Atomicity / idempotency / persistence audits | `PASS` — no atomicity assumed across a boundary that lacks it |
| Architecture audit | No drift; **the register-decay check — now a named audit step — found the register current, the first phase boundary where it did** |
| Security, reconciliation-readiness audits | `PASS`; limits stated and owned |
| Testing audit | **The full battery, fleet-wide, for the first time in the phase: 1157 hermetic / 729 database / 14 kafka, 0 failures** — closing the exit review's recorded criterion-7 deviation |
| Phase 4 verdict | **`COMPLETE`** (confirming `P4-DOC-001`) |
| Phase 5 entry gate | **All twelve criteria hold → `READY`** |

**The audit's one significant finding was produced by closing the deviation, which is what
the deviation-recording discipline is for.** The first fleet-wide `build databaseTest
kafkaTest` since Phase 3 **failed**: every `com.finapp.app.transfers` database suite —
suites that each passed their targeted, fresh-JVM runs all phase — died with `FATAL:
remaining connection slots are reserved`, and **zero assertion failures anywhere**. The
mechanism is `P1-TSK-004`'s finding arriving in the test fleet: Spring caches every distinct
context configuration for the JVM's life, each cached context holds a **fixed** pool of 8
(`minimum-idle` equals `maximum-pool-size`, by design), the suites' own raw connections sit
on top, and the per-JVM container ran the image default `max_connections=100` — so the
fleet of cached contexts could not fit, and the suites that run alphabetically last paid.
Invisible to every targeted run, structurally: a fresh JVM caches too few contexts to
matter. **Repaired in the harness** (`DatabaseUnderTest` provisions `max_connections=400`,
with the finding recorded at the line), which weakens no production claim — the production
relationship `instances × pool ≤ max_connections − reserved` is asserted against the
declared deployment configuration by `ConnectionPoolSizingGuard` and its build test, not
against a container whose only client is one test JVM. Battery re-run: **green, 0
failures** — and the review's honest wording (*"no fleet-wide count claimed"*) is
vindicated in the sharpest way: the count that was not claimed did not, at that moment,
exist to claim.

**Two decisions closed that Phase 5 could not start without** (questions 6 and 9, plus the
three ADRs the register anticipated) and **one overdue question found and ruled**: question
10 (*which jurisdiction-neutral compliance abstractions belong in the MVP*, due Phase 2)
sat open for three phases after Phase 2's plan and delivery answered it — the
stale-second-copy class in the unresolved table again, moved to Resolved with provenance.

### Previously

**`P4-DOC-001` — Phase 4 review record** — `COMPLETE` (2026-09-19).
**The gate passes and Phase 4 is `COMPLETE` at 14 of 14**
([`reviews/PHASE_4_REVIEW.md`](reviews/PHASE_4_REVIEW.md)).

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`** — **area 2 walks a transfer**, which is what this phase was for: the customer's instruction as the economic event, `TransferExecution` as the domain operation, one local transaction as the financial transaction (ADR-0043), a `POSTING` entry whose reference carries the transfer id, balanced per-currency lines, both balances as a transactional projection, and a reversal that corrects by referencing rather than editing — every step naming its code and its test |
| Universal criteria (12) | **12 `PASS`**, one with a recorded deviation (below) |
| Financial supplement (F1–F8) | **8 `Met`** — re-assessed at the gate, never inherited; F5 with its Phase-4 reading stated rather than glossed |
| Phase 4-specific criteria | **16 `PASS`** — 6 original + 10 from the transition's extension, **read from the gate at review time** rather than from a remembered count |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — six contended decisions, each with its PostgreSQL arbiter and its counted race |
| **Verdict** | **Phase 4 `COMPLETE` (2026-09-19)** |

### The flip surfaced nothing, and that was pre-paid twice

Conducted in the `P2-DOC-001` order — assess → corrections → **flip (the
guarded act)** → battery → finalise — and **the post-flip battery is green:
1157 hermetic tests, 0 failures across all ten modules**, including both
guards the flip arms. `MutationDemonstrationTest` now derives **five**
`Phase: 4` invariants from the catalogue and finds every row;
`PlannedMetersExistTest`'s derived rule now unions `PHASE_4_PLAN.md` §15's
meter table and the pinned Phase-4 test becomes the harmless second reading
`P4-TSK-011` predicted. Neither was luck: `P4-TST-002` landed every demanded
row and **probed the flip** — simulated `COMPLETE`, battery green, then one
row removed to prove the demanded set had genuinely grown (*currently 4*) —
and `P4-TSK-011` landed the meters behind a guard designed to hand over with
no edit. **Second phase running that the gate machinery finished its work
before the gate rather than at it.**

**And the flip was proven non-vacuous against the real status**, not only the
simulated one: a guard still reading *phase 3* would pass exactly as loudly as
one enforcing phase 4, so after the flip one `Phase: 4` row was removed — the
build fails naming the invariant and reporting **`(currently 4)`** — then
restored byte-identical, with the tier green again.

### Two area-7 findings, both in the record rather than the code

**The component register had no `transfers.beneficiary` row.**
`DISTRIBUTED_EXECUTION.md` §3 is an **enforced exemption set** rather than a
description — ADR-0024's rules permit process-local state only where it names
a component — so an absent row is a component whose next author finds no
precedent. The table has a partial unique one-live index arbitrating a
ten-way race, a conditional removal whose row count is the outcome, and an
every-writer freeze trigger: exactly the shape every sibling has a row for.
**The fifth occurrence of the register-decay class, and the first *inside* a
phase rather than at its boundary** — which sharpens the pattern rather than
softening it, because `P4-TSK-009` added its own row precisely as the
register's note asks while `P4-TSK-006`'s table never got one.

**And `P4-TSK-008`'s backlog block recorded *Completion notes* where every
sibling records *Gate evidence***, with its eight-mutation sweep, its one
survivor and its one cut written only into this document. The backlog is the
record, and a reader comparing two documents is not a mechanism. Both
corrected in the review.

**The ADR index needed no repair, and that is a result rather than an
absence**: the second-copy status decay found by hand at three consecutive
gates is now `P4-TSK-002`'s build failure naming the ADR, and this gate's
acceptance of ADR-0043 and ADR-0044 flipped both copies with the guard
reconciling them.

### One criterion met with a recorded deviation, stated rather than waived

Criterion 7 asks for the full suite against real infrastructure; the owner's
standing instruction for this phase skips `build databaseTest kafkaTest`. So
the **hermetic** tier — where the flip's own guards live — was run
**fleet-wide** (1157, 0 failures), and the database and kafka tiers were
verified per task, suite by suite, throughout. **No fleet-wide database or
kafka count is claimed for Phase 4**, and the limit is recorded in the
review's area 8 with an owner rather than glossed.

### What the phase delivered

Money moves between customers. 1 new module with the build-graph asymmetry
that makes its top risk structurally unreachable, 3 tables, 4 migrations, 7
operations on 5 paths, 2 aggregates, 4 audit actions all emitted, 3 terminal
events, 3 error codes, 1 permission on an existing role (a permission is
never a column — ADR-0031), 4 meters and a dashboard row, 2 ADRs `Accepted`,
**0 new invariants** (the catalogue stays at **82**; 5 in scope, 5 register
rows), 14 of 14 backlog items across 8 milestones, and **83 mutations — every
one caught by the intended assertion, 4 cut on analysis, 2 survived mid-task
and each improved a test, 0 survived wrongly.**

### Previously

**`P4-TST-002` — The `Phase: 4` register rows** — `COMPLETE` (2026-09-19).
**M4.7 CLOSES at 3 of 3; only the exit review remains — and the audit found
the gap it was written to find.**

| Acceptance criterion | Evidence |
|---|---|
| All register-guard checks green over the new rows | All **nine** `MutationDemonstrationTest` checks, over §2's 87 rows against 82 catalogued invariants |
| The demanded set verified **token-exactly** against the catalogue | **Five**, derived with the guard's own regex rather than read off a plan — `INV-IDEM-01` (transfers), `INV-CON-02` (landed by `P4-TST-001`), `INV-LIFE-01/-02/-04`; the four remaining rows landed here |
| Every named class and method exists | The guard proves it, including the three `transfers`-module classes it reaches through `app`'s declared `dependsOn(":transfers:testClasses")` |
| Restores byte-identical | Every one, verified by **comparison** and never by `git checkout --` (§5's own rule, which has destroyed uncommitted work three times here) |

### The finding: the caller had no concurrent-duplicate test

`INV-IDEM-01`'s **Verify** line reads *concurrent-duplicate integration
tests*, and this phase's exit criterion 2 reads *proven under concurrent
submission from two threads*. What existed at the transfers boundary was the
**sequential** retry — which exercises the **replay** path, because by then
the record is committed and a duplicate is a lookup. The concurrent proofs
lived only at the kernel and at the posting boundary.

Writing the row against the sequential test would have been **the false row
this register refuses** — worse than a missing one, because it is believed —
so the demonstration was **performed** rather than recorded (the `P3-TST-002`
precedent: the audit finds what was not done, and does it).
`TransferExecutionDatabaseTest#tenConcurrentIdenticalKeysProduceOneTransfer`
races ten instances on **one key** behind a `CyclicBarrier`, each with its own
connection, `SecurityContext` and correlation flow (`P0-TST-009`): one distinct
transfer id across every judged result, exactly one executed, and **one effect
counted in four tables** — one `transfers.transfer` row, one audit record, one
outbox event, one history edge — with the source settled at `7.00` and the
destination at `3.00`, which is the assertion a lost claim cannot satisfy
however the return values read. **The losers' second legal outcome is
accepted**: the honest `IdempotencyInProgressException`, because demanding
*nine replays* would make the assertion a statement about how fast the winner's
transaction happens to be; every other exception fails the test.

### The mutation is concurrency-only, which is what makes the test load-bearing

A pre-flight read substituted for the unique constraint — `SELECT` first,
`ON CONFLICT DO NOTHING` after, the shape this codebase's own comments warn
about since `P0-TSK-016` — leaves **all five sequential tests green** and fails
**exactly** the new one. So it is not a second copy of `P0-TST-004`'s
constraint drop, which fails seventeen. **Its observed shape is recorded rather
than assumed**: the losers do not commit a second transfer, they abort with
`IdempotencyStorageException` (*the claim was no longer in progress when its
outcome was recorded*) — so a retry that should have replayed reaches the
customer as a **500**, and the effect count survives only because those
transactions roll back. Caught by the clause that permits one loser outcome
besides a replay and no other.

### Two findings about the guard itself

**It would never have demanded the `INV-IDEM-01` row.** The check keys on the
invariant **identifier**, and that identifier already carries the kernel and
posting rows — so the transfers-context row is owed by `PHASE_GATES.md`
§Phase 4's extended list and by doctrine (the existing rows prove the
*mechanism*, not *this caller*), and by nothing the build can say. The three
`INV-LIFE` rows are the ones the flip would have failed on.

**And §6 item 4 claimed more than the guard does** — *every test class named in
either register* — while the class- and method-existence checks read §2 only.
Corrected to what is actually checked, with the reason widening it is not the
answer: §4's prose columns are full of backticked tokens that are not tests
(`INV-CON-02`, `P3-TSK-014`, `lockOwnedForUpdate`, `23514`), so scanning them
would fail the build on a method name quoted in an explanation. The same
*documentation describing behaviour that does not exist* class this register
was built to catch, found in its own §6.

### The flip is pre-paid, and that was probed rather than asserted

Phase 4's status line simulated `COMPLETE`, the battery green — then, the
non-vacuity half, **one new row removed**, which fails naming the invariant and
reports *(currently 4)*. So the demanded set really did grow and the four rows
are what makes the flip green; `P4-DOC-001`'s post-flip battery is pre-paid the
way `P3-TSK-021` pre-paid Phase 3's. Both files restored byte-identical.

**No production code changed**, no migration, no contract change, no new audit
action or event. One §3 paragraph records that `INV-LIFE-01` names **seven**
operations and this phase delivers one, and that `transfers.beneficiary`'s
real two-state machine is **deliberately not claimed** under any `INV-LIFE`
row — a saved destination is not a money-moving operation, and a row that reads
stronger than it is would be the false row again. **Verified by targeted
tiers — `:app:test` 427 and `:transfers:test` 31, 0 failures, plus the
execution database suite (6 tests) green, failing only the new test under the
mutation, and green again after the restore — the full battery deliberately
skipped on the owner's instruction; no fleet-wide counts claimed.**

### Previously

**`P4-TST-001` — Conservation under sustained concurrent movement** —
`COMPLETE` (2026-09-19). **M4.7 is 2 of 3 — and the composition
demonstration found a real defect, which is what this class of item is
for.**

| Acceptance criterion | Evidence |
|---|---|
| Every mid-storm trial-balance sweep reads zero per currency | `TransferConservationDatabaseTest`: the **global** sweep each round, safe by construction rather than by luck — a committed imbalance is impossible, `V004`'s deferred triggers judging every entry at COMMIT for every writer — with a `currenciesVerified ≥ 1` guard so a sweep that saw nothing cannot pass |
| Every verification verdict `CLEAN`/`IN_FLIGHT`, never `DRIFTING` | Per account, the `P3-TST-001` precedent: a global `verify()` would couple this test to every other suite's leftovers, which is a flake rather than a property |
| The final sum equals the starting sum **exactly**, counted from the tables and independently recomputed | **Three readings that must reconcile**: the journal's own sum over the pair; an independent recomputation from `transfers.transfer` applied to the starting balances — two tables that never see each other agreeing to the minor unit, `INV-LED-04`'s chain as arithmetic; and the outcome tally, where every loser is a committed `FAILED(INSUFFICIENT_FUNDS)` (`INV-CON-02`'s own wording) with nothing posted |
| No source ever negative | Asserted **mid-storm**, from one statement so the pair is one snapshot — which is the assertion that caught two of the four mutations |

### The finding: money moving both ways deadlocked, 783 times

The storm's first honest run produced **783 deadlocks (`40P01`) against
203 domain outcomes**. The execution locked the **source** row
`FOR UPDATE` while the posting's foreign key takes `FOR KEY SHARE` on
the destination regardless (`P3-TSK-014`'s recorded mechanism), so
A→B holding `FOR UPDATE(A)` and needing `KEY SHARE(B)`, against B→A
holding `FOR UPDATE(B)` and needing `KEY SHARE(A)`, is a cycle. **The
one-directional drain could not reach it** — a cycle needs two
directions — and `TransferExecution`'s own javadoc had said for three
tasks that *"the destination is deliberately never locked"*, which was
true of the explicit lock and false about what happens. **Money was
never at risk**: a deadlocked transaction writes nothing, so
conservation held exactly through all 783. What did not hold is
`INV-CON-02`'s clause that the loser fails with a **domain outcome** —
an infrastructure abort is not one, and it reaches a customer as a 500.

**Remedied in scope, deliberately, and the scope decision is on the
record.** The fix is the idiom this module's sibling already names —
`lockOwnedForUpdate` orders by id *"so two multi-account closers cannot
deadlock"* (`P3-TSK-009`) — applied to the multi-account operation that
is a transfer: both participants locked in one fixed order, ten lines.
The alternative was an `INV-CON-02` register row — **this item's own
deliverable** — that could not be written honestly; that is
`P3-TST-003`'s unwritable-row shape, resolved the other way here
because there the missing mechanism was a whole four-eyes lifecycle and
here it is a lock order. The order is Java's `UUID` order and
deliberately **not** PostgreSQL's byte order: a deadlock-free protocol
needs every *instance* to agree, not the database (`P3-TSK-008`'s
recorded disagreement, harmless here). The storm went from **206
seconds with 79% aborts to 4.5 seconds with none** — the aborts were
also the throughput. Recorded where the next reader will meet it:
`DISTRIBUTED_EXECUTION.md` §3's `transfers.transfer` row and the
ADR-0039 lock-set sentence, `PHASE_4_PLAN.md` §7 (which had **no row
for bidirectional movement at all**), and **ADR-0039's own follow-ups**
— its protocol is stated per *account decision* and was silent about
operations touching two accounts.

### The surviving mutation that corrected the test

The availability decision derived **before** the lock grant — the
gate criterion's own named mutation — **survived the first draft of
this storm**, and was caught only by `P4-TSK-005`'s drain. The reason
is the test's, not the code's: amounts of 1.00–3.00 against 10.00
balances never bring an account near zero, and a stale read differs
from a fresh one only at the boundary, so the sustained suite was
proving *less* than its one-directional sibling. With 7.00 and 9.00 in
the rotation the boundary is contested continuously and the mutation is
caught **mid-storm at −5.00 in round 2** — an account driven negative,
which is the sharpest form the assertion could take. **Four mutations,
all caught by the intended assertion, restores byte-identical**: the
ordering removed (495 deadlocks return, named by the outcome tally);
availability derived pre-lock (above); the posting **over**-moving one
minor unit (caught by the negative-balance assertion — recorded as the
different assertion it is, rather than claimed for the one it was aimed
at); and the posting **under**-moving (caught by *"A's journal position
is exactly what its completed transfers say"*, `expected: 2 but was:
0`) — which is how the two-table reconciliation was established as
load-bearing rather than assumed. The `INV-CON-02` row landed in
`MUTATION_TESTING.md` §2 — the first `Phase: 4` row; the other four are
`P4-TST-002`'s, read from the catalogue — with §5 teeth re-proven
(*every method the register names exists on its class* failing on a
corrupted reference) and restored byte-identical.

### One process finding, recorded

**A compile failure was read as a test result.** A `-Werror` warning
failed `compileTestJava`, and the result-reading script parsed the
previous run's XML and reported a verdict — identical numbers to the
run before, which is what gave it away. The harness now refuses to
report unless `> Task :app:databaseTest` actually appears in the log,
and deletes prior results first. That is the *"reports success for work
it did not do"* class this repository keeps meeting, this time in the
machinery rather than the work — the fourth occurrence, after
`P1-TSK-026`'s cmd trap, `P1-TSK-027`'s tree-breaking harness and
`P0-TSK-038`'s stale UP-TO-DATE read.

### Previously

**`P4-TSK-011` — The meters and the dashboard row** — `COMPLETE` (2026-09-19).
**M4.7 opens at 1 of 3: `PHASE_4_PLAN.md` §15 is real**, and the phase's
critical flow is a published fact rather than a query over the audit trail.

| Acceptance criterion | Evidence |
|---|---|
| A freshly started instance publishes every series | The pinned Phase-4 guard in `PlannedMetersExistTest` (the `P2-TSK-020`/`P3-TSK-020` shape, third performance): §15's table parsed and held against the plain no-database context — exactly the "freshly started instance" the acceptance names; the derived guard takes over at the flip with no edit |
| A replayed transfer lands `replayed` with `completed` unchanged | `TransferMetersDatabaseTest`, over real HTTP against the wired beans: the acting judgement counts once, its byte-identical retry moves `replayed` only — and the mutation counting the replay as `completed` fails exactly there |
| A conflict lands `conflict` | The same key with a different amount: the 409, `conflict` +1, **every outcome series unchanged** — the security signal is one series an alert can watch, never a tag filter |
| Dashboard queries resolve | The *Transfers* row (outcomes, latency from `_count`/`_sum`/`_max`, conflicts on their own panel, beneficiary lifecycle), every query resolved by `DashboardQueriesResolveTest` against a live scrape |
| Mutation sweep over the counting discipline | **Eight mutations, all caught by the intended assertion, restores byte-identical**; one cut on analysis and recorded |

### The counting discipline, and where the counter had to live

**The counter anchors in `TransferService`, not a command decorator,
because post-commit is not achievable inside the command**: the
execution runs in the caller's transaction (ADR-0043), so a decorator
counts before the commit it cannot see. The outcome crosses the
transaction boundary (the `AccountService.openedNow` shape) and is
counted after the commit, **from the `TransferResult`'s own
vocabulary** — the count cannot drift from the judgement. `reversed`
counts the acting reversal only (the machine's 409 losers land on
`refused`); **`refused` is defined on the record**, since the plan only
listed it: a transfer command the platform declined to judge with
nothing written — the resolution refusals and the reversal machine's
409, never the caller's own 422s, which named no coherent command. A
metric is invisible to the caller (`P1-TSK-029`'s recovery-meter
argument), so it may count what the byte-identical responses hide: a
rise in `refused` is somebody probing destinations. The latency timer
wraps the execution path in a `finally` — every outcome, the injected
clock — because a timer recording only successes flatters exactly the
incident an operator is trying to see. Beneficiary counters take the
acting-call discipline verbatim: converged creates (the
different-display-name converge included) and converged removals count
nothing.

### What else the task settled

One `TransferMetrics` class in `app.telemetry` (the `AccountMetrics`
shape — public because the counting seam lives in `app.transfers`, the
vocabulary methods rather than a registry handle), eager in
`TelemetryConfiguration`; `TransferService` and `BeneficiaryService`
gained **required constructor parameters** (no defaulted overload — the
wiring updated at the composition root). **No `DISTRIBUTED_EXECUTION.md`
§3 row, and the absence is the design**: per-instance counters are
non-authoritative readings with no coordination question (the
`AccountMetrics` precedent). No `INV-MON-01` exemption owed — no gauge,
so no `ToDoubleFunction` anywhere. No contract change, no new audit
action, no events. The plan's refused value-by-state meters and the
subjectless stuck-detector stay refused with their §15 provenance. The
sweep: replay-as-completed; `refused` dropped (**caught twice** — the
refusal test and the reversal test's refused half); `conflict` dropped;
`reversed` hoisted above the acting check (`expected: 1.0 but was:
2.0`); eager registration made lazy (the pinned Phase-4 guard failing
by name — the `P1-TSK-029` defect, caught by the guard built against
it); a dashboard series renamed (`DashboardQueriesResolveTest`'s
designed message); a converged beneficiary create counted; latency made
success-only (the refused-still-times assertion). The
count-inside-the-transaction mutation **cut on analysis and recorded**:
after the judgement returns, nothing reachable rolls the transaction
back, so the discipline is held by the code shape and the stated rule
(the `P3-TSK-014` class). **Verified by targeted tiers — the full
`:app:test` hermetic tier with every guard green,
`TransferMetersDatabaseTest`, `DashboardQueriesResolveTest` — the full
battery deliberately skipped on the owner's instruction; no fleet-wide
counts claimed.**

### Previously

**`P4-TSK-010` — The limit and risk seams** — `COMPLETE` (2026-09-19).
**M4.6 closes: the seams are contracts Phase 13 can honour**, not
sentences about ones it could not.

| Acceptance criterion | Evidence |
|---|---|
| Removing either parameter fails compilation, demonstrated | Performed: the `limits` parameter removed and the field defaulted to a permit-all — `TransferBeans` fails with *constructor cannot be applied to given types* (the composition root itself), the test call sites beside it; restored byte-identical. The skipped control does not compile, which is the control this phase ships |
| The defaults are exercised on every transfer test | Every execution path consults both seams, and the refusing-default mutation proves it: `PermitAllUntilPhase13` made to refuse fails `TransferSeamsTest.theDefaultPermitsEverything` by name |
| The in-lock contract is asserted — a decorator probe | `TransferSeamDatabaseTest.bothSeamsObserveTheSourceLockHeld`: each seam, when consulted, attempts `SELECT … FOR UPDATE NOWAIT` on the source account row **from its own second connection** — the held lock answers `55P03`, deterministically, and the hoisted-above-the-lock mutation is caught by *where* the seam ran, which no outcome assertion over a permit-all could see |
| No Phase 13 logic anywhere, verified by the seams' size | Mechanised: the default holds **no field**, each port declares **one method**, the verdict is exactly `PERMIT`/`REFUSE` — a counter, a cache or a third operation is a failing test, not a review observation |

### What the skeletons lacked, and the hardening that closed it

`P4-TSK-005` shipped `check(Transfer)` — **no unit of work and no way to
refuse**. A Phase 13 limit could neither anchor a counter to durable
state in the execution's transaction (`INV-CON-03`: an authoritative
check anchored to durable state — the in-lock contract was a sentence,
not a seam) nor answer anything but permit without a contract change,
which is exactly what a seam exists to prevent. Both ports are now
generic over the unit of work (the `TransferStore<T>` shape) and return
**`SeamVerdict.PERMIT`/`REFUSE`** — two values and no reason,
deliberately: the refusal→reason mapping is the **execution's**, fixed
per seam, so a limit implementation can never commit the risk
vocabulary. `FailureReason` gained `LIMIT_REFUSED` and `RISK_REFUSED`
**with their producers** (ADR-0044's doctrine): the execution's mapping
arms, exercised by a refusing decorator — a seam refusal is a committed
`FAILED` with nothing posted (zero entries referencing the transfer,
counted), replayed by the claim (`CommandResult.failed`, asserted), and
when both seams refuse the limit's reason wins by consultation order
(asserted). **`V004` widened the reason `CHECK`** — the role-ceremony
shape on a genuine column constraint — and `TransferMigrationTest`'s
reason check moved to the **latest-definition derivation** with `V002`'s
five-value literal pinned as history: the applied-history lesson the
test's own javadoc predicted it would have to learn, learned on
schedule.

### What else the task settled

`DISTRIBUTED_EXECUTION.md` §3 gained both seam rows — stateless by
contract today, and the row states where Phase 13's authority must
live: durable rows on the passed unit of work, judged under the source
lock, never process memory. `ERROR_CONTRACT.md`'s
committed-outcomes-are-not-error-codes prose gained the two reserved
reasons. No contract change (the view's `failureReason` is a plain
string), no new audit action (the `TRANSFER_EXECUTED` record carries
the reason as it carries every other), no meters (plan §15 is
`P4-TSK-011`'s), no ownership-register entries owed (the seams take the
aggregate and the connection, no `EntityId`). **Seven mutations, all
caught by the intended assertion, restores byte-identical** — the seams
hoisted above the lock (the NOWAIT probe: *expecting true but was
false*), the limit verdict ignored (`FAILED` became `COMPLETED`), the
risk verdict ignored, the reasons swapped (both reason assertions,
symmetrically), `V004` narrowed back to five values (the
latest-definition reconciliation naming it), the default made refusing,
and a state field added (the size guard). **Verified by targeted
tiers — the full `:app:test` hermetic tier with every guard green,
`:transfers:test`, and the seam database suite — the full battery
deliberately skipped on the owner's instruction; no fleet-wide counts
claimed.**

### Previously

**`P4-TSK-009` — The reversal** — `COMPLETE` (2026-09-19). **M4.5
closes: the privileged, reasoned correction exists** — one transaction,
lock-then-look on the transfer row, the referencing entry through
`ReversalService`, and the original untouched (`INV-REV-01`).

| Acceptance criterion | Evidence |
|---|---|
| The original entry byte-identical after reversal | `TransferReversalDatabaseTest.theReversalAcceptanceChainHolds`: the whole entry row and every line captured as **PostgreSQL's own renderings** before the reversal and compared equal after (the `P3-TSK-016` idiom) — on top of the standing `DB-PRIVILEGE` immutability, the claim about *this* reversal specifically |
| Both balances restored exactly | Source back at `"10.00"`, destination at `"0.00"`, each read over its owner's own balance endpoint |
| Ten concurrent reversals → one entry, one move, counted | One 201 and nine 409s over HTTP, with the reversal-entry, history and audit counts read **from the tables**; plus the deterministic interleaving — the loser observed **Lock-waiting** on the transfer row's `FOR UPDATE` in `pg_stat_activity`, resuming onto the winner's commit to the machine's refusal with **nothing posted** |
| `REVERSED`/`FAILED` → 409, nothing at the ledger | One `transfers.NotReversible` for both (and for the race's loser) — named for what is *checked*, the machine's edge via `canTransitionTo(REVERSED)`, judged from the locked row **before any ledger work**; the ledger's own refusal is the layer beneath, demonstrated by the machine-check mutation surfacing `V009`'s over-reversal bound |
| Permissionless session refused with nothing written | The transfer's **own customer**'s 403 (`INV-AUD-03`), every count zero, status still `COMPLETED` — the operator's acceptance chain the positive control, the denial audited by the interceptor |

### The design decisions, each on the record

**Lock-then-look is the arbiter, and the backlog's "the conditional's
row count arbitrates" was corrected by the design**: the conditional
`UPDATE` needs the reversal entry id, which does not exist until the
posting — so post-then-move would let every racer do ledger work and
surface the losers as `V009` over-reversals instead of the machine's
409. `TransferStore.lockById` (`FOR UPDATE`, the `P2-TSK-015` idiom —
the store's own javadoc deferred exactly this to this task) serialises;
the conditional's row count is the recorded **belt** (its
made-unconditional mutation cut on analysis: invisible under the held
lock); `V002`'s trigger edges bind raw SQL and the ledger bound sits
beneath. **No idempotency key, deliberately** (the `P3-TSK-021`
approval precedent): `COMPLETED → REVERSED` happens at most once ever,
so the machine is the idempotency — a retry gets the 409 and the view
carries the reversal. **The reversal posts unconditionally**: no
availability judgement on the destination, whose wallet legitimately
goes negative (`P3-TSK-008` — the true position; gating a correction on
the recipient's spending would let spending make correction
impossible). A destination product closed since the transfer surfaces
the catalogued `ledger.AccountNotPostable` with the whole transaction
rolled back — the recorded corner. **The scope's `V015` was a drift,
corrected on being met**: the `V014` ceremony replaces the ROLE
constraint, and this task adds a permission to an existing role — a
permission is never a column (ADR-0031), so there is no migration to
write and `RoleAssignmentMigrationTest` holds the constraint unchanged.

### What else the task settled

`TRANSFER_REVERSE` joined `LEDGER_OPERATOR` (one money-operating
population; `RoleNameTest`'s exact set updated, the pairwise
disjointness and no-orphaned-permission assertions derived and
untouched). The view gained `reversalEntryId` and `reversedAt` — the
reversal's own chain-walk key beside `journalEntryId`, rendered by the
GETs and **never by the POST's replay path** (byte-for-byte survives
the reversal, exactly as `P4-TSK-008` predicted); the operator's
identity is deliberately not disclosed to the customer — the trail's
fact, not the view's. **`ReversalService` got its first bean** in
`LedgerBeans` (the `PostingService` precedent — the `P1-TSK-007`
licence expiring with its first composition-root consumer), observed by
the real `PostingObserver` so a reversal counts on
`finapp.ledger.posting`. `lockById` classified **`ADMINISTERED`** (the
operator names somebody else's transfer from the URL; the standing
check is the permission at the boundary — the `P1-TSK-028` class);
`TransferReversalRequest` cites `SuspensionRequest`'s bounds (the
`P2-TSK-012` idiom) and joined the credential-sink pinned set; the
reason travels in the body, never a query string (`INV-AUD-02`).
Contract baseline **+92/−0** (the new path, the reusable
`transfers.NotReversible` response, the request schema, the two view
fields — reviewed). `DISTRIBUTED_EXECUTION.md` §3 gained the
`transfers.transfer` row **by the task rather than by the next
transition audit** — the register-decay note finally heeded by a task
instead of repaired after one. **Eight mutations, all caught by the
intended assertion, restores byte-identical** (`FOR UPDATE` dropped —
caught twice, blocked-observation and race counts; the machine check
removed — 409 becoming our 500; directions not swapped —
`ReversalBound` refusing the mirror; audit dropped; reason dropped —
`AuditRecord`'s constructor refusing by name; event dropped; history
dropped; `@RequiresPermission` removed — 403 becoming 201), one cut on
analysis and recorded. **Verified by targeted tiers — the full
`:app:test` hermetic tier with every guard green, `:transfers:test`,
`:identity:test`, and the transfers-package database suites — the full
battery deliberately skipped on the owner's instruction; no fleet-wide
counts claimed.** Process note: a scripted backlog edit duplicated a
755-line region — caught by `diff --stat` and occurrence counts rather
than by reading the region it targeted, and repaired by
restore-and-reapply with anchors asserted unique.

### Previously

**`P4-TSK-008` — `POST /v1/transfers`, status and list** — `COMPLETE`
(2026-09-18). **M4.4 closes: the first customer-visible money movement
is over HTTP**, and the contract already has the asynchronous-outcome
shape Phase 5 will inherit — a `FAILED` judgement is a `201` whose body
says so, never an HTTP error.

| Acceptance criterion | Evidence |
|---|---|
| End to end over HTTP: money moves, both balances move, both statements show the entry's lines, the chain walked by identifier | `TransferEndpointDatabaseTest.theAcceptanceChainHolds`: register → verify → open → fund by a real posting → `POST` 201 `COMPLETED` → source `"7.00"`, destination `"3.00"` (each side under its **own** token) → both statements carry the entry id **and the transfer id** (the entry's `reference` — the identifier chain in both directions, plan §12) → `GET /v1/transfers/{id}` answers the judgement. **The accept's "one customer with two accounts" corrected on the record**: `ProductType` has one value and the one-live index (`P3-TSK-012`) makes a second open **converge onto the first** — proven by the first run's correct `FAILED(SELF_TRANSFER)` — so the demonstration is two verified customers |
| A retried key replays the original body **byte-for-byte** | Raw bodies compared equal — the view renders the **replayed judgement** (`TransferResult`, the original status and reason) plus columns `V002`'s trigger freezes for every writer, so `P4-TSK-009`'s reversal cannot leak into a replay. **One idempotency claim, the command's**: the accounts precedent stacked an HTTP executor over a domain *converge*; stacking one over a domain *executor* would be two claims and two fingerprints for one boundary |
| Reused key + different payload = the distinct 409; keyless = the interceptor's 422 | Both driven; the keyless mutation lands on the framework's 400, caught |
| A removed beneficiary refuses new transfers (M4.3's fourth clause) | The live entry executes; removed → **422 `transfers.UnknownDestination` with nothing written, counted in the table** — and the refusal is byte-identical across unknown, a stranger's, malformed and removed (equality between the causes), so the endpoint is an oracle over nobody's address book |
| No body shape a 500 | Twelve shapes swept — plus the inexact amount on an **otherwise-valid** request, because the sweep's inexact shape names an unknown destination whose refusal **masks a silent rounding**: the rounding mutation survived the sweep and is caught only by the valid-pair probe (the `P2-TSK-016` lesson) |

### The surface's own decisions, each on the record

**`transfers.UnknownSource` (422), not a 404**: a body field the caller
must correct — a 404 describes the request URI. One byte-identical answer
for unknown, not-yours and malformed (the resolution port's one empty,
`INV-IDN-07` at a port), distinct from `UnknownDestination` because the
remedies differ. **The view carries no account identifiers, deliberately**:
the row stores *ledger* accounts — internal vocabulary, and the
destination's is a third party's (the `P3-TSK-018` no-counterparty rule);
the chain walk rides `journalEntryId`. **The beneficiary arm is a
per-decision authoritative read** (`party_id = ?`, status `ACTIVE`
required), with one recorded corner: a *retry* whose beneficiary was
removed in between is refused rather than replayed — the resolution runs
before the claim can answer, nothing is written, and `INV-IDEM-01`'s
financial half holds absolutely. **The stale Phase 4 backlog header**
(`READY` since 2026-09-17) — the stale-second-copy class `P3-DOC-001`
found in three earlier phases — corrected on being met.

### What else the task settled

The store's deferred reads arrived with their surface, each classified on
arrival: `findOwned` (`OWNER_SCOPED`, `customer_id = ?` — `V002` named
the column for this read on the day it was created), `findById`
(`AUTHORITATIVE_ID` — the execution's own result in the same
transaction), `listFor` (newest first, the `transfer_by_customer` index).
`Transfer.MAX_REFERENCE_LENGTH` became the bound's one definition (DTO
references it directly; the migration reconciliation holds it to `V002`'s
`CHECK`). The execution command left the unconsumed-wiring licence:
`TransferBeans` composes it from the shared beans, and **`PostingService`
gained its bean in `LedgerBeans`** — the consumer its comment predicted,
observed by the real `PostingObserver` so a transfer's posting counts on
`finapp.ledger.posting`. No events owed (`P4-TSK-005` wired both
terminals); no meters (plan §14 is `P4-TSK-011`'s); no audit action (the
command's `TRANSFER_EXECUTED` is the record; GETs are a person's own
reads, the `SessionQueries` stance). Contract baseline **+180/−0**; the
six `BREAKING` labels are the classifier erring safe on the brand-new
path's own `required` members (reviewed, the standing precedent).
**Eight mutations performed, all caught by the intended assertion
(the ownership predicate caught twice: behaviourally and by the build
rule; the rounding mutation survived once and strengthened the suite
before being caught), restores byte-identical; one cut on analysis and
recorded** — the POST view's status-from-row swap is behaviourally
invisible until `P4-TSK-009` gives the row a second writer. **Verified by
targeted tiers — the full `:app:test` hermetic tier with every guard
green, `:transfers:test`, and the transfer, execution, beneficiary and
adjustment database suites — the full battery deliberately skipped on the
owner's instruction; no fleet-wide counts claimed.**

### Previously

**`P4-TSK-007` — The beneficiary endpoints, and the step-up point** —
`COMPLETE` (2026-09-18). **M4.3 closes: the saved destination is created
under the second factor, listed, removed.** The surface lives in `app` —
the only module that sees both halves the step-up joins (`identity`'s
enrolments and assurance, `transfers`' creation).

| Acceptance criterion | Evidence |
|---|---|
| An enrolled identity on a `PASSWORD` session is refused with the actionable step-up code and nothing written; succeeds at `MULTI_FACTOR` | The **whole flow over real HTTP** — register → log in → enrol → confirm → refused **403 `identity.AssuranceRequired`** with zero rows counted → the factor proven over `/v1/authentications/mfa` → 201, audited as the **person** (the `P1-TSK-027` lesson: two green halves compose only when something drives them together) |
| An unenrolled identity creates at `PASSWORD` | 201 — and the retry carrying a **different display name** converges 201 onto the same row and the existing name (`P4-TSK-006`'s recorded consequence proven at the surface), one row, **one** audit record |
| A stranger's beneficiary id is a 404 on `DELETE` | Stranger's, unknown and malformed: **one 404 asserted as an equality between the causes**; the owner's `DELETE` converges 204 with one `BeneficiaryRemoved` record |
| Both audit actions emitted, leaving `NOT_YET_EMITTED` | `transfers.BeneficiaryAdded`/`BeneficiaryRemoved` catalogued and **emitted on arrival** — the registry and completeness guards green, `NOT_YET_EMITTED` still exactly the three Phase-15 `outbox.*` actions |

### The step-up is a domain check, and the refusal is structural

`MULTI_FACTOR` required **exactly of an identity that has a factor** —
the `CredentialChange` conditional restated at this orchestration
(`P1-TSK-033`'s pattern; a static annotation would lock out every
password-only customer), judged from an authoritative per-decision read
inside the creation's own transaction, **before any write**. The refusal
writes nothing structurally: the `ApiException` rolls the transaction
back — which is also why the planned moved-after-the-write mutation was
**cut on analysis and recorded** (the `P3-TSK-017` claim-before-validate
shape). The value threshold stays deliberately absent (a per-currency
versioned policy artefact with nothing to calibrate it — plan §11).

### The refusal vocabulary, each shape earning its answer

**`transfers.UnknownDestination` (422), the module's first error code**:
one byte-identical answer for unknown and malformed alike
(malformed-equals-absent for a **third party's** identifier — the
`kyc.OwnerNotEligible` shape), disclosing only what any transfer naming
the destination would disclose. The step-up refusal is deliberately
**not** a transfers code — `identity.AssuranceRequired` is the assurance
vocabulary's own, however many surfaces demand it. **No beneficiary
not-found code**: one `api.NotFound` across a stranger's, an unknown and
a malformed id (normalised only for the correlation id and the caller's
own echoed `instance` path), while the owner's already-removed row
converges on 204 — told apart by the new `findOwned` (`OWNER_SCOPED`,
`party_id = ?`), whose entry the ownership guard demanded with its
negative test by name. The domain's name rule surfaces as the caller's
422 naming `displayName` and never the `RESTRICTED-PII` value — with the
probe's control character travelling as a **JSON escape**, because a raw
control byte is the parser's 400 and never reaches the rule under test.

### What else the task settled

The `transfers` module's first beans (`BeneficiaryBeans`), wiring
`JdbcTransferParticipants` for its first composition-root consumer — the
execution command stays unbeaned until `P4-TSK-008` (the licence). The
contract baseline: **135 added lines, zero removed**; the five
`BREAKING` labels are the classifier erring safe on the brand-new path's
own `required` members (the `P1-TSK-006` precedent, reviewed).
`BeneficiaryCreateRequest` bounds the name by referencing
`Beneficiary.MAX_DISPLAY_NAME_LENGTH` **directly** (the
`SuspensionRequest` idiom — no reconciliation test owed) and joined the
credential-sink pinned set with its claim. No events (plan §10), no
meters (plan §14's row is the observability task's), no verification
gate (a Party's convenience, deliberately). **Eight mutations, all
caught by the intended assertion, restores byte-identical.** **Verified
by targeted tiers — the full `:app:test` hermetic tier and the two
beneficiary database suites — the full battery deliberately skipped on
the owner's instruction; no fleet-wide counts claimed.**

### Previously

**`P4-TSK-006` — The `Beneficiary` aggregate and schema: `V003`** —
`COMPLETE` (2026-09-18). **M4.3 opens: the saved destination exists** —
the `Hold`/`CustomerAccount` pair of precedents composed: a two-state
terminal machine (`ACTIVE → REMOVED`) whose schema half is `V003`, with
the one-live slot, the every-writer freeze, and port-validated creation.

| Acceptance criterion | Evidence |
|---|---|
| Ten concurrent creates of one destination produce one live row, nine converged | Ten instances (own connection each): exactly one created, nine converged **onto the winner's row id** — the partial unique index on (party, destination) over the non-terminal states arbitrating behind the savepoint converge — **one row counted in the table**, never inferred from return values |
| A removed slot is re-creatable (`INV-LIFE-04`'s freed-slot asymmetry) | Remove, save again: a **new** aggregate through the freed slot — two rows counted, the `REMOVED` one surviving as evidence (the `customer_account` asymmetry, not the login-identifier one) |
| Raw SQL cannot resurrect a `REMOVED` row | As the **migrator**: `V003`'s every-writer trigger (the ledger `V008` shape) refuses the resurrection — and the probe that isolates the frozen half is **an edit smuggled inside the legal removal edge**, because a status-preserving edit is refused by the edge check too, and a probe two controls catch proves neither. The frozen-check mutation was caught by exactly that probe |

### The design decisions, each on the record

**Owned by a Party, not a Customer** — a saved destination is a person's
address book entry and outlives any one commercial relationship (the
consent-record precedent; plan §5's own ownership line) — and by **raw
`UUID`s**, because `party` and `accounts` own the typed identifiers and
`transfers` cannot see them (the `Transfer.customerId` precedent), with
no cross-schema FK (ADR-0029). **Creation converges on the natural key**:
"save this destination" means one saved row however many times and
however concurrently it is said — and a retry carrying a different
display name **converges onto the existing name, recorded rather than
discovered**: renaming is remove-and-recreate, a new aggregate through
the freed slot, never an edit (the trigger freezes the name for every
writer). **Removal is a conditional `UPDATE`** whose row count converges
retries and folds a stranger's attempt and already-removed into one
indistinguishable `false` — `party_id = ?` in the statement (ADR-0031),
which obliged `OwnershipIsScopedTest`'s predicate vocabulary to gain
**`party_id = ?`** (the designed edit-forces-decision path), and the
guard then demanded the negative test by name before passing.
**The destination is validated through `TransferParticipants.destination`
for existence only, deliberately**: postability goes stale by design — a
product suspended today may post tomorrow — and re-judging it belongs to
each transfer's own execution (the backlog's scope sentence; removal
racing a transfer is plan §7's accepted race). The display name restates
`PartyName`'s rule (non-blank, bounded, the five Unicode categories no
name contains) because module isolation forbids the import — the
`DocumentCipher` restated-mechanism precedent — classified
`RESTRICTED-PII` (a person names people) and never in a `toString` or
message, needle-asserted.

### What deliberately did not arrive

No endpoints, no step-up, no audit actions (`transfers.BeneficiaryAdded`
/`BeneficiaryRemoved` arrive with `P4-TSK-007`, the surface whose design
fixes them — plan §11's own assignment), no events (plan §10 names only
the three transfer facts), no meters (`finapp.transfers.beneficiary` is
plan §14's), no beans (the unconsumed-wiring licence). `V003`'s grants:
`SELECT, INSERT` + `UPDATE (status, removed_at)`, no `DELETE` — a
beneficiary's end is a status, never an absence — swept per column from
`information_schema` with the removal `UPDATE` as the positive control.
**Eight mutations, all caught by the intended assertion, restores
byte-identical** — the index dropped (`Expected size: 1 but was: 10`,
the counted drain), the trigger's edge check removed, the frozen check
removed (caught by exactly the smuggled-edge probe), the coherence
`CHECK` dropped, the ownership predicate dropped (**caught twice**:
behaviourally and by the build rule), the machine check removed, the
index made total (**caught twice**: the freed slot refused, and the
hermetic reconciliation), the constructor coherence dropped. **Verified
by targeted tiers — 27 hermetic `:transfers:test`, the beneficiary and
classification database tests, the ownership guard — the full battery
deliberately skipped on the owner's instruction; no fleet-wide counts
claimed.**

### Previously

**`P4-TSK-005` — The execution command: one transaction, the lock, the
outcome** — `COMPLETE` (2026-09-17). **M4.2 closes: the movement exists.**
The phase's High-risk task: one transfer judged and committed with its
money in one local transaction (ADR-0043) — claim → resolve → judge →
source lock → availability in-lock → seams → post → outcome, all on the
caller's connection.

| Acceptance criterion | Evidence |
|---|---|
| The ten-way drain, counted in the tables | Ten instances (own connection, scope and flow each) draining 1000-affordable in 300s: exactly **3 `COMPLETED`, 7 `FAILED(INSUFFICIENT_FUNDS)`**, source settled 100 and never negative, destination 900, **the pair summing to the funded 1000 to the minor unit** (`INV-CON-02`) |
| An injected failure at the last write leaves *nothing* | A throwing outbox decorator: no row, no entry, no history, no audit — **and no claim: the same key then executes afresh** rather than replaying a failure that never committed (`P3-TSK-006`'s property as ADR-0043's demonstration) |
| A retry replays the stored outcome, success and failure both | The `FAILED` refusal commits, so its claim survives and its retry learns it — `CommandResult.failed`, the Phase 0 executor's own documented rejected-transfer case, meeting its intended caller three phases later. Same key + different amount → `IdempotencyConflictException` (`INV-IDEM-03`; the fingerprint binds the actor and the money's meaning) |
| The availability decision proven inside the lock | **The named mutation performed and caught**: hoisted outside the lock — still locks, still checks, still loses the race (the `P3-TST-002` shape) — the drain fails with the textbook over-acceptance, `expected: 3 but was: 10` |

### Two additions to proven ledger code, each earned by its second caller

**`AvailableBalance`** — the extraction of `HoldService`'s
derive-plus-standing-holds computation, whose second caller has now
arrived (the `PostingEffect` precedent): "what can this account spend?"
has one answer however many commands ask. `HoldService` delegates with its
constructor unchanged, `HoldDatabaseTest` the behavioural equivalence
proof; the **in-lock contract is the first line of the javadoc**, because
called without the lock it is exactly the check that passes every
sequential test and loses the race. **`LedgerAccountStore.findAllOwned`**
— the lock-free sibling of `lockOwnedForUpdate`'s read, because no
currency-blind product→wallet resolution existed and a
`CURRENCY_MISMATCH` refusal must still commit carrying the real accounts.

### The refusals, each a committed outcome with its committed shape

`SELF_TRANSFER` first — **the precedence is forced by the aggregate's pair
rule**, any other reason with an equal pair being a shape the constructor
refuses: the coherence design teaching the execution. `CURRENCY_MISMATCH`
carries both real wallets (currency-blind resolution's purpose).
`DESTINATION_NOT_POSTABLE` twice over: seen at resolution, and **the
mid-flight `V007` path proven sequentially and deterministically** — a
resolution decorator lies about postability, the posting trigger refuses,
and the **savepoint** turns the aborted transaction state into a committed
`FAILED`, named with certainty because the source was verified under our
own lock. Boundary mistakes (unknown source, somebody else's source — one
indistinguishable refusal, `INV-IDN-07` at a port) throw with nothing
written, the rollback taking the claim with it. The destination is
resolved **through the ledger alone** (product and ledger status close
together, `P3-TSK-014` — recorded as today-exact), so no unowned
`accounts` read exists and the counterparty-disclosure question never
arises.

### What else the task settled

`transfers.TransferExecuted` arrives with the command whose design fixes
it (no reason — the `FAILED` case's "why" is the enumerated reason on the
row; registry and completeness guards green); terminal events only, with
identifiers and enum names and never an amount; posting and value dates
are today by the injected clock, the explicit `DOMAIN_MODEL.md` §Time
decision, revisited by scheduled transfers; the posting's own idempotency
key is `transfer:<id>` (the `ledger.adjust.approve` precedent), so a
replayed transfer never re-enters the posting; the seams
(`TransferLimitCheck`, `TransferRiskDecision`) are **required parameters
with no defaulted overload**, consulted in-lock, implemented by
`PermitAllUntilPhase13` until `P4-TSK-010` hardens them. The
container-clock trap met again on schedule (`GREATEST()`, `P1-TSK-031`).
**Seven mutations, all caught by the intended assertion, restores
byte-identical.** Verified by targeted tiers plus the full architecture
tier (`OwnershipIsScopedTest` demanded no entries — the store API takes
aggregates, never bare identifiers, verified against the detector); **the
full battery deliberately skipped on the owner's instruction; no
fleet-wide counts claimed.** Process note: the Gradle daemon was
externally stopped mid-run twice; both runs were repeated and read from
fresh executions.

### Previously

**`P4-TSK-004` — The transfer schema: `V002`** — `COMPLETE` (2026-09-17).
**M4.2 is 2 of 3: the machine the aggregate holds in Java now binds every
writer** — raw SQL, an operator, the migrator — at `DB-CONSTRAINT` rank.

| Acceptance criterion | Evidence |
|---|---|
| Raw SQL cannot store an unknown status, an illegal edge, a reasonless `FAILED` or an entryless `COMPLETED` | Each refused from scratch (`23514`/`P0001`) through the per-JVM harness, with the two coherent shapes — a completed transfer and the `SELF_TRANSFER` refusal — as positive controls so every refusal is the constraint named and not a broken INSERT |
| The per-column grant sweep with a positive control | Derived from `information_schema` minus the four reversal columns (the `P0-TST-007` idiom, so a column added later is swept without anyone remembering); `DELETE` denied on both tables; the history's identity column probed with `DEFAULT` (the `P2-TSK-017` lesson); the positive control is the reversal `UPDATE` succeeding **as the app role through exactly the granted columns** — trigger edge, coherence `CHECK`s and narrowed grant proven sufficient in one act |
| Classification guard green | 22 columns at their ceiling — `reference` `RESTRICTED-PII` (free text a person writes), `failure_reason` `CONFIDENTIAL` (`INSUFFICIENT_FUNDS` is a fact about a person's finances, not an enumeration technicality) |

### The trigger is the V010 shape, both halves

Everything outside the reversal columns is **frozen for every writer** —
what the customer was told happened is what stays recorded, and the
migrator's own `amount_minor` update is refused — and a status may move
only along **exactly the machine's edges**, the conditions generated from
`permittedTransitions()` and reconciled by `TransferMigrationTest` (which
also asserts terminal states appear as **no** edge's source). The
`INITIATED`-source edges are present because the machine has them and
simultaneously dead at rest — an update out of a planted `INITIATED` row
cannot supply its reason or entry, those columns being frozen — the layers
agreeing, recorded rather than glossed. **The grants force
insert-carries-outcome**: a `FAILED` outcome cannot arrive by `UPDATE`,
its reason column being ungranted, so the only update this table will
ever legitimately see is the reversal.

### Stricter at rest than the aggregate, deliberately

The pair `CHECK` — `(source = destination) ⇔ (failure_reason IS NOT
DISTINCT FROM 'SELF_TRANSFER')` — refuses an equal-pair `INITIATED` row
that the aggregate admits in memory, because `INITIATED` is never durably
observed (ADR-0043/0044): at rest the one legal equal pair is the
committed record of refusing exactly that mistake. `journal_entry_id` is
`UNIQUE` — one transfer per posted entry, whichever instance wrote it.
History (`transfer_event`) is append-only at the privilege with
server-assigned order (the `consent_record` lesson), FK-anchored to its
transfer, and its edge validity deliberately unconstrained: evidence must
record what a defective writer actually did; the authoritative row's
trigger is the control.

### Verified by targeted tiers, on the owner's instruction

**The full battery was deliberately skipped this task** — verification is
`:transfers:test` (the four-artefact migration reconciliation among it)
plus the schema and classification database tests, all green with both
tasks proven executed rather than cached. No fleet-wide tier counts are
claimed. **Eight mutations, all caught by the intended assertion,
restores byte-identical** — the status list narrowed and the `MoneyColumns`
fragment edited (caught hermetically by the reconciliation); the trigger's
edge check removed, the frozen check silenced, the reason, entry and pair
`CHECK`s each dropped, and the grant widened table-wide (each caught
against a from-scratch database).

### Previously

**`P4-TSK-003` — The `Transfer` aggregate and its lifecycle** — `COMPLETE`
(2026-09-17). **M4.2 opens at 1 of 3: ADR-0044 is code**, and the module
created two tasks ago holds its first domain type — hermetic only, no
store, no schema, no service, by scope.

| Acceptance criterion | Evidence |
|---|---|
| Every invalid transition rejected by the aggregate, swept from the cross-product | The sweep derived from `values()` with per-outcome transition doors, expectation read from `permittedTransitions()` — a state or edge added later is swept without anyone remembering (`INV-LIFE-01/-02`) |
| Both terminals swept separately | From `FAILED` and from `REVERSED`, every transition door throws (`INV-LIFE-04`) |
| `COMPLETED`'s single outgoing edge as a machine property | The machine **pinned exactly** — `containsExactly(REVERSED)` — because a sweep that trusts the machine cannot notice the machine changing; plus the property that **nothing transitions TO `INITIATED`**: no state permits it and no method targets it, birth being the only door |

### One constructor holds every invariant, and every path shares it

Birth (`initiate`), the three per-outcome transitions — `complete(entry)`,
`fail(reason)`, `reverse(entry, actor, clock)`, each routed through the
same machine check (`INV-LIFE-02`'s one door, split only because each
carries a distinct payload) — and `rehydrate`, so a corrupt row (a
`FAILED` without its reason, a `COMPLETED` without its entry) is refused
on read-back as defence in depth ahead of `V002`'s `CHECK`s. Coherence
both directions per rule: reason ⇔ `FAILED`; entry ⇔ money moved
(`REVERSED` **keeps** the original entry — the reversal is more evidence,
not less); the reversal triple ⇔ `REVERSED`, all three or none, because a
reversal without its actor or instant is an unattributable correction.
**Only `reverse` reads the clock**: the reversal is the only transition
that stamps the row — there is deliberately no `statusChangedAt`, since
the reversal columns are the narrowed `UPDATE` grant's whole vocabulary
(plan §8) and transition instants are the history table's evidence
(`P4-TSK-004`).

### The pair rule: the one interpretive decision, resolved on the record

The backlog's flat *"source ≠ destination"* tensions with `SELF_TRANSFER`
as a **committed** failure reason (`P4-TSK-005`'s scope commits sibling
refusals; ADR-0044's own doctrine forbids an enum value with no producer)
— a committed `FAILED(SELF_TRANSFER)` row necessarily stores the equal
pair. Resolved as **coherence with the machine, exactly like the reason**:
the equal pair is legal in exactly two shapes — `INITIATED` (the caller's
unjudged input; judging is the execution's act) and `FAILED(SELF_TRANSFER)`
(the committed record of refusing exactly that mistake, which conversely
*requires* the equal pair — a self-transfer refusal naming two different
accounts is incoherent). Money-moved states and every other reason require
inequality — an equal-pair completed transfer would mean the balanced
no-op entry plan §14.6 exists to prevent. Consequence the constructor
enforces for free: an equal-pair `INITIATED` transfer has exactly one
legal exit, `fail(SELF_TRANSFER)` — proven by driving both wrong exits.

### Typed where the boundary permits, raw where it forbids

`sourceAccount`/`destinationAccount` are ledger's `LedgerAccountId` and
the entry ids `JournalEntryId` — **the `transfers → ledger` edge's first
use**, and the identity-chain material §12's reconciliation will walk.
`customerId` and the actors are raw `UUID`, because `party`/`identity` own
the typed ids and this module cannot see them (the
`CustomerAccount.customerId` precedent, reasoning recorded verbatim).
`INV-AUD-02` at the type: the positivity refusal names the fact and the
currency, never the value, needle-asserted with the value planted to be
findable. The module's `package-info` *"nothing implemented"* paragraph
was updated by the task that made it stale — the recurring class, caught
at design time.

**Seven mutations, all caught by the intended assertion, restores
byte-identical** — the machine check removed from `complete()`,
`COMPLETED` made terminal, `FAILED` given an exit, the reason coherence
dropped, the entry coherence dropped, the money-moved pair rule dropped,
the amount leaked into the refusal message. **1133 hermetic tests, 683
database tests, 14 kafka tests — the +6 the new test's own methods.**

### Previously

**`P4-TSK-002` — The ADR governance registers, build-reconciled** —
`COMPLETE` (2026-09-17). **M4.1 closes: 2 of 2.** The twice-carried
governance item paid as work: an ADR's status is a fact written in two
places with nothing reconciling them — the exact mechanism behind every
register decay this repository has catalogued — and the pattern's own
conclusion (*the registers with build guards have not decayed once*) is
now applied to the register where the pattern was twice found by hand.

| Acceptance criterion | Evidence |
|---|---|
| A mutated index status fails the build naming the ADR | `ADR-0043: the README index says 'Accepted' while the file says 'Proposed'` — the ADR and both values in the message |
| A missing index row and an orphan row each fail | ADR-0044's row deleted → the bijection fails; a planted ADR-0099 row → "unexpected: [0099]" |
| Teeth proven by mutation, restores byte-identical | **Five mutations, all caught by the intended assertion** — the two above, the file-side flip on ADR-0044, the doc-only re-run probe, and a corrupted row form caught by the structural check **naming the line** |

### Four checks, each owning its defect

`AdrRegistersAreReconciledTest`: the **bijection** (every ADR file exactly
one index row, every row a file, and every row's link resolved to the file
it names — a row whose link points at nothing is the orphan case wearing a
working number; `ADR-0001` as the non-vacuity anchor, the row that can
never legitimately leave); **status agreement** on the leading token,
because the file form legitimately carries provenance (`Status: Accepted
(2026-09-17, P3-DOC-001)`) the index column does not; the **closed
vocabulary** (`Proposed`/`Accepted`/`Superseded`, the README's own rules
line) on both copies, because an equality-only check is satisfied by a
typo present in both; and the **outer structural check** that every
row-looking line inside `## Index` parses as a row — the `P1-TSK-024`
fix-one-level-out lesson applied at design time rather than found by a
gate. A file with no readable `Status:` line is a failure, never a skip.

### The section bounding proved itself mid-sweep

The orphan-row mutation's first plant appended to the end of the README —
which put it in the *Anticipated ADRs* section, where the parser rightly
ignored it. Re-planted inside `## Index`, it is caught. The miss was the
bounding working, and it is recorded because a plant that "fails to fail"
for the right reason is exactly what distinguishes a bounded parser from
an over-reading one (the `P0-TSK-037` regex lesson, from the other side).

### What the guard deliberately does not cover, recorded

`DECISIONS.md` — curated prose, not a status copy; a coverage check over
prose is a false precision (the backlog's own scoping). The index's title,
phase and concern columns — prose, out of scope rather than smuggled in.
And a future `Superseded by ADR-NNNN` status line **fails the parser
loudly**, forcing the format decision at the moment it first has a subject
— the direction to err in (`P1-TSK-025`'s reasoning). The documents joined
the declared `:app:test` inputs as a **file tree, not a list** — a new ADR
file re-runs the guard without anyone remembering — proven by the probe:
the doc-only mutation re-ran the task rather than reporting `UP-TO-DATE`.
**1127 hermetic tests, 683 database tests, 14 kafka tests — the +4 the new
test's own methods.**

### Previously

**`P4-TSK-001` — The `transfers` module and schema** — `COMPLETE`
(2026-09-17). **Phase 4 is `IN_PROGRESS`; M4.1 opens at 1 of 2.** The
established module shape, fourth performance — and the part genuinely this
phase's is the build graph: the edges that make the phase's top risk (the
transfer module writing postings) structurally unreachable before any
transfer code exists.

| Acceptance criterion | Evidence |
|---|---|
| `build databaseTest` green with the module present | **1123 hermetic, 683 database, 14 kafka tests** — the two new tests are `TransfersModuleIsolationTest`'s pair |
| A planted `double` fails the floating-point rules naming the module | Caught naming exactly `transfers.Planted.amount is double (INV-MON-01)` — **after the task's finding, below** |
| Both isolation directions and the cycle demonstrated | `transfers → party` fails the new isolation test; `accounts → transfers` fails `AccountsModuleIsolationTest`; the planted `ledger → transfers` edge fails Gradle configuration outright — `:ledger:compileJava → :transfers:compileJava → :ledger:compileJava` |
| The schema floor proven live | Throwaway `postgres:18.6`: migrate → validate → re-migrate idempotent; owner `finapp_migrator`; ACL exactly `{finapp_migrator=UC, finapp_app=U}`, no `PUBLIC` entry; `USAGE` and **not** `CREATE` for the app role; zero application tables; history = Flyway's schema-creation marker + one versioned row — checked against the `ledger`/`accounts` shape rather than assumed |

### The finding: a module off `app`'s classpath is a module no rule protects

The planted-`double` probe **survived its first run** — exit 0, nothing
named — because nothing had added `implementation(project(":transfers"))`
to `app`, and `ProductionModules` derives the swept set from `app`'s
classpath. The comment beside `app`'s business-module block predicts this
in as many words (*"a business module must be on app's classpath or
ProductionModules cannot derive it and every rule silently stops
protecting it"*) — and the probe is what turned the sentence into a
demonstrated fact rather than a warning nobody had tested. With the edge
added, the probe fails naming the module. **The module-creation
checklist's untested step was the classpath edge, not the schema**: the
`DATA_MIGRATIONS.md` procedure covers the Flyway half end to end (all five
steps followed, none stale this time) and says nothing about the classpath
half, which is recorded in `app/build.gradle.kts` where the next module's
author will meet it.

### The asymmetry is structural, and the refusal is the point

`transfers → ledger` is declared **with the module** (first consumer
`P4-TSK-003`, two tasks away), because the edge is the deliverable: with it
in the graph, `ledger → transfers` is a Gradle dependency cycle —
demonstrated, not asserted. **`transfers → accounts` is refused**: the
transfer resolves the caller's products through a port `app` implements
(the `AccountHolderVerification` shape, `P4-TSK-005`), because the module
that owns the product and the module that moves the money must not become
one dependency ball. All six sibling isolation tests gained `transfers` in
their forbidden lists — the one-directional-decay lesson (`P2-TSK-003`),
applied at design time for the fourth time.

### What deliberately did not arrive

No `TransfersAuditAction` enum (the deliberately-few licence, recorded in
`package-info.java`: the actions arrive with the aggregates and commands
whose designs fix their meaning — `P4-TSK-005`/`-007`/`-009`); no table
(`P4-TSK-004`), no aggregate (`P4-TSK-003`), no service, no bean, no
endpoint, no event, no meter. `DISTRIBUTED_EXECUTION.md` §3 gains **no
row**, and the absence is the design: the task introduces no runtime state
of any kind. Housekeeping: `transfers/gradle.lockfile` identical to
`accounts`'s but for its header; `gradle/verification-metadata.xml`
unchanged; no `build-logic` lockfile drift this run.

### Previously

**Phase 3 → Phase 4 transition** — **CONDUCTED** (2026-09-17).
[`reviews/PHASE_3_TO_4_TRANSITION.md`](reviews/PHASE_3_TO_4_TRANSITION.md)

| Part | Outcome |
|---|---|
| Phase 3 completion audit, 16 categories | **16 `PASS`** |
| Financial correctness audit, 14 properties | **14 `PASS`** — every property against its database-rank mechanism, not application logic |
| Multi-instance audit | **`PASS`** — every contended decision arbitrated by PostgreSQL and raced with counted outcomes; the fresh single-instance sweep zero across Phase 3 code |
| Atomicity / idempotency / persistence audits | `PASS` — no atomicity assumed across a boundary that lacks it; nothing rests on JVM memory |
| Architecture audit | No drift; **one governance decay — the fourth of its class — repaired** |
| Security, reconciliation-readiness, testing audits | `PASS`; 1121 hermetic / 683 database / 14 kafka green on a fresh post-transition run |
| Phase 3 verdict | **`COMPLETE`** (confirming `P3-DOC-001`) |
| Phase 4 entry gate | **All twelve criteria hold → `READY`** |

### The transition's own finding: the register decayed again, on schedule

**`DISTRIBUTED_EXECUTION.md` §3 had no Phase 3 rows at all** — while Phase 3
introduced the platform's most contended shared state (the journal, the
projection, holds, the adjustment proposal). This is the **fourth**
occurrence of the register-decay class, one transition after the pattern
was named by the transition that repaired Phase 2's identical gap — which
sharpens the conclusion rather than embarrassing it: the registers with
build guards have not decayed once, and this one's guard checks only the
process-local direction. Repaired with **ten rows plus the Phase 3 note**
(one genuinely new coordination twist: `V009`'s advisory lock is taken
*inside a trigger*, binding raw SQL; two deliberate absences are the
design — postings take no lock, verifiers take no lock), and the check is
now a named transition-audit step.

### Two decisions taken, because Phase 4 cannot start without them

**ADR-0043** closes unresolved question 5 (High, open since initiation):
the transfer state transition and its posting commit in **one local
transaction** — the seam `PostingService` was built with — a failed
transfer is a *committed domain outcome* with no posting, "compensation"
means the business reversal and nothing else, and **no internal saga
exists**, with the boundary at which that answer changes named (an outcome
a third party decides is a payment, Phase 5's lifecycle). The rejected
designs each manufacture the failure they exist to handle: a durable
`INITIATED` needs a sweeper, a lease and a stranded state; an
outbox-mediated posting puts a customer-visible `COMPLETED` ahead of the
money.

**ADR-0044** derives the lifecycle instead of copying it: four states
(`INITIATED → {COMPLETED, FAILED}`, `COMPLETED → REVERSED`), every state
earned by a producer. `VALIDATED`/`AUTHORIZED` are preconditions, not
durable facts; `PROCESSING` is the payment lifecycle's; `CANCELLED` arrives
with scheduled transfers and the window that makes it meaningful.
`COMPLETED` is **stable, not terminal** — one outgoing edge, driven by the
reversal command — a recorded reading of `INV-LIFE-04` chosen over storing
one fact in two places. The events follow the machine: terminal facts
publish; `TransferInitiated` does not (it would commit beside its own
outcome), corrected in the delivery plan and module register with
provenance.

### Rulings the transition owed

The `LEDGER_READ` declaration is **struck** with provenance rather than
scheduled (the capability exists as the job and gauge; a surface with no
consumer is dead contract). The twice-carried ADR-index item becomes
**`P4-TSK-002`** — a build guard, as work, in M4.1. The step-up trigger
moves to **beneficiary creation** (a value threshold is a versioned policy
artefact with nothing to calibrate it — the `P3-TSK-021` argument); the
stuck-transfer detector is recorded **subjectless** under ADR-0043.

### What the transition produced

`PHASE_4_PLAN.md` (18 sections, twelve failure scenarios, four meters,
eight milestones); ADR-0043 and ADR-0044 (`Proposed`, both index copies);
the Phase 4 gate criteria **extended with ten measurable bullets**
(conservation under the ten-way drain, the injected-failure atomicity
probe, in-lock availability, reversal byte-identity, step-up, the
compiler-required seams, traceability, the catalogue-read register rule);
14 backlog items across M4.1–M4.8, each with acceptance criteria; the §3
register repair; the roadmap's current position rewritten (it was frozen
at 2026-09-13); and the three delivery-plan/module-register corrections.
**No application code was written**, which is the constraint a transition
is performed under.

### Previously

**`P3-DOC-001` — Phase 3 review record** — `COMPLETE` (2026-09-17).
**The gate passes and Phase 3 is `COMPLETE`**
([`reviews/PHASE_3_REVIEW.md`](reviews/PHASE_3_REVIEW.md)).

| | Outcome |
|---|---|
| Review areas (8) | **8 `PASS`** — area 2 assessed **with a subject for the first time in the programme**: the four-eyes adjustment walked economic event → domain operation → financial transaction → journal entry → lines → balances, every step naming its code and test |
| Universal criteria (12) | **12 `PASS`** |
| Financial supplement (F1–F8) | **8 `Met`** — binding for the first time, re-assessed at the gate rather than inherited; F5 met with its Phase-3 vacuity stated |
| Phase 3-specific criteria | **16 `PASS`** — the gate lists sixteen where the backlog's scope said "nine" (pre-extension text; recorded as area-7 finding 5) |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — nine contended decisions, each with its PostgreSQL arbiter and its counted race |
| **Verdict** | **Phase 3 `COMPLETE` (2026-09-17)** |

Conducted in the `P2-DOC-001` order — assess → land corrections → **flip
the status (the guarded act)** → re-run the full battery — and **the
post-flip battery was green with nothing surfaced**: `MutationDemonstrationTest`
derived Phase 3's nineteen demanded invariants from the catalogue and found
every row, and `PlannedMetersExistTest`'s derived guard took over §15's
table from the pinned one, because `P3-TST-003` predicted the flip's one
failure and `P3-TSK-021` pre-paid it. Six area-7 findings, five corrected
in the review and one recorded with an owner: the plan's unbuilt
`LEDGER_READ` surface (→ the transition), two stale `LEDGER_MODEL.md`
adjustment spots (corrected — the document's own front matter assigned them
to this review), **three stale backlog phase headers** (Phase 0
`IN_PROGRESS`, Phases 2 and 3 `READY` — the ADR-index second-copy decay in
a second artefact, all corrected), this document's §Next Task stale at
`P3-TSK-011` across eleven tasks while §Current Task stayed correct
(replaced), the backlog's "nine" criteria (recorded), and the ADR files
plus index both `Proposed` (both copies flipped, `DECISIONS.md` already
correct — an improvement on Phase 2, where it omitted the whole phase).
**Everything counted, nothing quoted**: 138 mutations, probes and
demonstrations across the 22 items that performed them, plus three §5
guard-teeth re-proofs; one survivor across the phase, **correctly**
(`P3-TSK-003`'s defence-in-depth predicate), zero wrongly. ADR-0039…0042
`Accepted` on the standing precedent that criterion 10 is a precondition of
the gate, not a reward for passing it. **1121 hermetic tests, 683 database
tests, 14 kafka tests, counted from the post-flip battery.**

### Previously

**`P3-TSK-021` — Four-eyes on manual adjustments** — `COMPLETE`
(2026-09-17). **M3.8 is 3 of 4: `INV-AUD-04`'s Phase 3 element exists, the
register carries all nineteen `Phase: 3` rows, and the battery survives
the status flip.**

| Acceptance criterion | Evidence |
|---|---|
| Approver ≠ initiator at `DB-CONSTRAINT` where representable | Twice over: `V010`'s `CHECK (status <> 'APPROVED' OR decided_by <> proposed_by)` — the invariant's own Enforce clause, plain because the table is new — and a **deferred constraint trigger** (the `V004` mechanism) refusing any `ADJUSTMENT` entry COMMIT without an approved proposal, raw SQL bound, history untouched (`INV-HIST-01`) |
| Self-approval refused, with a negative test | `AdjustmentEndpointDatabaseTest.selfApprovalIsRefused`: 409 `ledger.SelfApprovalRefused`, **nothing written** — no entry, proposal still visibly `PROPOSED` — and a second person approves the very same proposal as the positive control; the aggregate refuses too (`INV-LIFE-02`), hermetically |
| The threshold defined | **Every adjustment** — `INV-REV-04` permits thresholds, but a threshold is a per-currency amount policy (a versioned artefact, `INV-HIST-04`) with nothing to calibrate it and the `INV-MON-04` cross-currency trap beneath; unconditional is a strengthening, the de-minimis threshold a recorded future policy artefact whose seam is the proposal row |
| The `MUTATION_TESTING.md` §2 row | Landed, with §3 rewritten as the resolution record — the row the register's own doctrine refused to let `P3-TST-003` write now names the self-approval rejection tests the catalogue's Verify line demands |

### Four-eyes is two authenticated acts, never one request with two names

An `approverId` field would be a name anyone can type, not an authorised
act. So the adjustment became a lifecycle: `POST /v1/ledger/adjustments`
now **proposes** (`ledger.adjustment_proposal` + lines, `PROPOSED →
{APPROVED, REJECTED}`, both terminal) and posts **nothing** — the reviewed
BREAKING contract change, because no client exists and a parallel
one-person write kept for compatibility would keep the invariant violated.
`GET …/{id}` shows an approver exactly what they would approve — and the
payload is **frozen by trigger for every writer**, so approve-what-you-read
is structural rather than procedural (TOCTOU closed at the schema).
`POST …/{id}/approval` by a **different** `LEDGER_ADJUST` holder builds the
entry from the stored rows and posts it through `PostingEffect` in the
approval's own transaction. `DELETE …/{id}` rejects — or, for the
initiator, **withdraws, deliberately**: removing an action needs no second
person, because the invariant's clause governs the approval. One permission
for both acts (`P2-TSK-004`'s rule: the trust decision is one; the control
is person-distinctness; maker/checker is a recorded seam).

### The approval carries no idempotency key, and the machine is why

The one-way lifecycle **is** the idempotency (`INV-IDEM-01` through state,
the `P2-TSK-008` natural-key argument): approval is lock-then-look
(`FOR UPDATE` on the proposal row, the `P2-TSK-015` idiom) with the
conditional decision as belt, so `PROPOSED → APPROVED` happens at most once
ever — ten concurrent approvals produce **exactly one entry, counted in the
table**, with every response converging on it — and the same approver's
retry replays the recorded entry id. There is no request body to
fingerprint. Propose keeps the full machinery (scope `ledger.adjust`,
fingerprint binding actor + reason + lines), because a duplicated
*proposal* is the duplicate-effect vector.

### ADR-0010's "second actor column" debt dissolved rather than paid

Two acts, two audit records, each with one actor:
`ledger.AdjustmentProposed` (new, **reason required** — the justification
enters the trail at the moment the initiator writes it) and
`ledger.AdjustmentPosted` naming the **approver**, whose act the posting is
(ADR-0021's honesty rule) — the entry's `actor_id` is the approver, and the
initiator is one join away on the proposal row, reachable from the entry's
`idempotency_scope` (`ledger.adjust.approve:<proposalId>`).
`ledger.AdjustmentRejected` carries no reason: declining to move value
needs no justification, and the record names who. A refused self-approval
writes nothing, deliberately — the permission layer audits denials, and the
proposal stays standing. The posting meter observes the **approval**
(posted / converged-replayed / refused); a proposal moves no meter, because
it writes no journal — the meter's own description stays true.

**Seven mutations, all caught by the intended assertion, restores
byte-identical** — the domain self-check dropped (`V010`'s CHECK turns the
409 into our 500), the CHECK dropped, the deferred trigger dropped, the
lock made a plain read, the freeze trigger dropped, the proposed-audit
dropped, the entry's actor made the initiator. **1121 hermetic tests, 683
database tests, counted.**

### Previously

**`P3-TSK-020` — The six planned meters, eagerly registered** — `COMPLETE`
(2026-09-17). **`PHASE_3_PLAN.md` §15 is real: a freshly started instance
publishes every series** — the M3.8 acceptance's first half, held by a
pinned guard rather than asserted.

| Acceptance criterion | Evidence |
|---|---|
| Every §15 meter registered eagerly and unconditionally | The **pinned Phase-3 guard** in `PlannedMetersExistTest` (the `P2-TSK-020` shape): the plan's own table held against the plain no-database context — exactly the "freshly started instance" the milestone names; the derived guard takes over at the flip. `finapp.ledger.posting{outcome}`, `finapp.ledger.posting.latency`, `finapp.ledger.trial.balance` (existing), `finapp.ledger.projection.drift` (existing), `finapp.ledger.hold.active`, `finapp.accounts.account{outcome}` |
| The wired beans, not a test's registry | Proven through real HTTP: an adjustment lands posted +1; its replay lands replayed +1 with posted unchanged; an unbalanced refusal lands refused +1; every command timed — the test that catches a bean measuring nothing |
| A dashboard row whose queries resolve | *Ledger and accounts — financial correctness*, six panels, resolved against a live scrape — the deferred drift and trial-balance panels land here; timer panels read `_count`/`_sum`/`_max`, never `_bucket` (the `P1-TSK-029` lesson) |

### The write path's observer is a required parameter, deliberately

`finapp.ledger.posting` counts **every journal-write command** — posting,
reversal, adjustment — through a new `PostingObserver` port on the commands
themselves, because the write path is one (`INV-LED-04`) and a count
incremented per door is a count a new door silently loses (the
`MeteredKycCaseStore` argument). The parameter is **required, with no
defaulted overload**: a balanced path is the one a later author optimises
away, so Phase 4's transfer wiring is forced by the compiler to decide.
Latency comes from the **injected `Clock`** — `nanoTime()` is ambient time,
and `P0-TSK-029` already rejected the tempting exception. A command counted
`posted` may still be rolled back by the caller that owns the commit:
recorded as the monitoring approximation it is, never financial truth. The
account counters take the other discipline (`P2-TSK-020`): incremented
**after the commit and only for the acting call** — a converged retry, a
replayed key and the losers of a concurrent close are never throughput,
mutation-proven in both directions.

### P3-TSK-015's owned remainder landed with its owner

`ProjectionVerification` now compares `holds_minor` against the fold of the
`ACTIVE` hold rows **through the kernel** (`JournalEntry.sum`, never a SQL
`SUM`), read in the **same statement** as the projection row — one
snapshot, so no watermark is needed: a hold transaction updates
`holds_minor` and its row atomically under the account lock, and a single
statement cannot see half of that. An unverifiable fold is `DRIFTING`,
because unverifiable is not clean. **The extension found its enabling fix
in advance**: `HoldDatabaseTest`'s corruption test left its planted
`holds_minor` corruption committed in the shared container — harmless
until the comparison existed, permanent global drift after — so the test
restores the row to the fold of what stands. `finapp.ledger.hold.active`
reads through the new `HoldStore.countActive` (no `EntityId` parameter, so
the ownership guard demands no entry), cached at the cheap-read floor, NaN
never zero, `max()` never `sum()`.

**Eight mutations, all caught by the intended assertion, restores
byte-identical** — the observer un-wired, a replay counted as posted, a
refusal not counted, the eager outcome series narrowed to one, the unknown
hold reading made zero, the holds comparison dropped, the opened count made
unconditional, the closed count on presence. `LedgerMetrics$HoldCached`
joined the floating-point exemption set (the same Micrometer case, sixth
time); the resolver's non-series vocabulary gained `currency`. **1107
hermetic tests, 676 database tests, counted.**

### Previously


**`P3-TST-003` — The financial supplement F1–F8, demonstrated** —
`COMPLETE` (2026-09-17). **The exit gate's evidence is prepared before the
review needs it** — the `P2-TST-001` posture, applied to the strictest gate
in the programme.

| Acceptance criterion | Evidence |
|---|---|
| Each of F1–F8 assessed with a named test | [`reviews/PHASE_3_FINANCIAL_SUPPLEMENT.md`](reviews/PHASE_3_FINANCIAL_SUPPLEMENT.md): every criterion **met** against named tests — F5 met with its Phase-3 vacuity stated rather than glossed (no external event produces a financial effect this phase; the inbox mechanism that will bind is the Phase 0/2-proven one) — and every `Class#method` the table names is also named by a `MUTATION_TESTING.md` §2 row, so the register guard holds the supplement's references to the code on every build (verified by script) |
| The register rows, the set read from the catalogue | **Nineteen `Phase: 3` invariants, where the plan's §6 table lists seventeen** — `INV-REC-05` and `INV-AUD-04` sit outside it, the exact drift the scope sentence predicted. Fourteen new §2 rows plus two extension rows (`INV-BAL-03`'s posting half; a fourth `INV-CON-01` row for the account-lifecycle context) and a second `INV-IDEM-01` row for the financial boundary; all nine `MutationDemonstrationTest` checks green over them |
| Demonstrations real, not asserted | **The audit found every recordable demonstration already performed by its owning task's sweep** — the rows record, they do not invent; `INV-BAL-05`'s row lands here, earlier than `P3-TST-002`'s recorded deferral, because this item's scope demands every row and what the deferral postponed was the record, not the work. §5 teeth re-proven: one method reference corrupted, the guard failed naming exactly it, restored byte-identical |

### The sixteenth row cannot be written, and that is the headline finding

`INV-AUD-04` is `Phase: 3` in the catalogue, the manual adjustment exists,
and its mechanism is **deliberately unbuilt** — no defined threshold, no
second approver, four-eyes recorded as ADR-0010's debt by `P3-TSK-017`,
whose act fired the debt row's own trigger. A row naming the adjustment
suite's tests would be a **false claim**, and the register's doctrine is
that a false row is worse than a missing one. The consequence is mechanical
and known **in advance** this time (the `INV-HIST-02` lesson pre-applied):
`MutationDemonstrationTest` derives its demanded set from the catalogue, so
the battery **fails naming `INV-AUD-04` the moment Phase 3 flips
`COMPLETE`**. Recorded in the register's §3; the mechanism and its row are
**`P3-TSK-021`**'s (four-eyes on manual adjustments, created by this item),
scheduled before `P3-DOC-001`. **No production code shipped.**

### Previously

**`P3-TSK-019` — The trial-balance job: zero per currency, or an incident** —
`COMPLETE` (2026-09-17). **M3.7 closes: `INV-ACC-01` is a continuously
published fact, and the primary continuous correctness signal exists.**

| Acceptance criterion | Evidence |
|---|---|
| An injected imbalance is detected and alerted | `TrialBalanceDatabaseTest`: three raw-SQL shapes in one open transaction — USD equal-raw-sums-at-different-scales (1500@2 vs 1500@3), EUR debit-excess, GBP credit-excess of the same decimal value — **each flagged per currency**, with a committed positive control proving the detection was the imbalance; the hermetic half proves the flagged currency reads 1 while its siblings stay 0 |
| Safe under concurrent posting | Sweeps racing four live posters (≥8 sweeps overlapping ≥40 commits) read zero every time — and **no `IN_FLIGHT` verdict exists, by design**: one statement reads one snapshot, a snapshot never contains half an entry, every committed entry balances at COMMIT |
| NaN when unreadable | An unsweepable ledger reports absent on **every** series, never zero — zero means *verified balanced*, so a comforting zero would silence the one alert the gauge exists to fire |

### The injection rides the deferral

V004's balance constraint is `INITIALLY DEFERRED`, so an open transaction
holds raw unbalanced rows the constraint has not yet judged — exactly what a
trigger-less writer's committed rows look like to the sweep's one `SELECT`.
The test inserts, sweeps on the same connection, and rolls back: nothing
commits, no trigger is disabled, no cleanup can leak corruption into the
sibling suites' global sweeps.

### Where the sweep deviates from the Money fold, and why that is admissible

`P3-TSK-008`'s two failure modes are **structurally closed at the one
statement**: `scale` is a grouping key (no SQL addition crosses a scale
boundary) and `SUM(bigint)` is `numeric` (arbitrary precision, read back as
`BigDecimal` exactly); the per-currency combination is exact decimal
arithmetic with no rounding to be implicit. `Money` itself is deliberately
not used, because a system-wide group sum can legitimately exceed `long` and
`Money`'s refusal would turn a large **balanced** ledger into a false
incident — the one failure a monitoring job must not produce. What leaves
the class is **verdicts and currency codes, never an amount**
(`INV-AUD-02`, the `ProjectionVerification` stance) — and there is no
repair path, structurally: the class issues exactly one `SELECT`, and
repair is a reasoned adjustment (`P3-TSK-017`).

### The gauge, and the deliberate tag decision

`finapp.ledger.trial.balance{currency=...}` — the plan §15 name exactly —
eager per `SupportedCurrencies` (`P1-TSK-029`), a currency found only in
history registering at discovery; the scrape is the schedule (30s cache
floor, no leader, no lease, nothing ambient, no §3 question); WARN names
currency codes only, rate-limited by the floor. **`currency` joined
`ALLOWED_TAG_KEYS`** — the designed edit-forces-decision path: bounded by
ISO 4217, a category shared by everyone that structurally cannot name a
person or a resource, and the plan's own table says "per currency" (the
`purpose` precedent, not the refused `stage`). `LedgerMetrics$TrialCached`
joined the floating-point exemption set — the same Micrometer-gauge case a
fifth time. Dashboard row deferred to M3.8 with the drift panel, per
`P3-TSK-010`'s recorded deferral.

### One process finding, recorded

The first battery invocation **never ran**: a `grep -c` returning zero
matches (the desired answer) broke the `&&` chain before gradle started,
and the exit code read as the battery's. The missing log file is what
caught it — the build-never-ran class (`P1-TSK-026`, `P2-TSK-009`), met
this time in the shell chaining rather than the harness, and the battery
was re-run for real.

**Six mutations, all caught by the intended assertion, restores
byte-identical** — the zero comparison neutralised, the currency buckets
collapsed, the scale dropped from the decimal conversion, the direction
sign dropped (caught by the positive control), the unknown reading made
zero, the eager registration removed. **1105 hermetic tests, 674 database
tests.**

### Previously

**`P3-TSK-018` — `GET /v1/me/accounts/{id}/statement`: the period statement,
derived from postings** — `COMPLETE` (2026-09-17). **M3.7 opens: every figure
a customer is shown traces to journal lines** (`INV-ACC-02`'s drill-down
shape, three phases early).

| Acceptance criterion | Evidence |
|---|---|
| Derived from postings, reconciling to the lines | `StatementEndpointDatabaseTest`: opening `"10.00"` from the pre-period history, the period's two lines (both boundary days inclusive), closing `"11.50"` — **and the closing held to an independent `BigDecimal` recomputation over raw SQL rows**, never certified through `Money` |
| Ownership | The `/v1/me` shape: `Session → Identity → live Customer → findOwnedBy` (`customer_id = ?` in the statement); not-yours, unknown and malformed one 404, **asserted as an equality between the causes** |
| The caller's own mistakes are theirs | Inverted period and malformed date are specific 422s naming the parameter and never echoing the value; a missing parameter is the framework's 4xx; no shape our 500 |

### The closing is computed, and that is the design's crux

Under `READ COMMITTED` the opening read (`derive` at
`AsOf.postingDate(from − 1)`) and the period-lines read are two snapshots —
so a third read ("derive as of `to`") could disagree with the lines. The two
ranges are **disjoint predicates**, so no interleaved commit can land in
both or between them, and `closing = opening + settle(debits, credits)`
holds **structurally under any concurrency** rather than by scheduling luck.
The derivation is composed, never copied (`JournalEntry.sum`'s scale-aware
identity, the one statement of the sign convention); no lock is taken
anywhere, because a statement must never contend with the write path it
reports on. No migration: `journal_line_by_account` already serves the read.

### What the statement discloses, and what it never does

Per line: the entry id (the drill-down key), posting and value dates, type,
direction, amount as a decimal string, and the caller's own `reference`.
**Never the counterparty account, never the `reason`** — free text written
by a person is `RESTRICTED-PII` audit material, and both exclusions are
asserted (an adjustment appears as its `ADJUSTMENT` line with its amount,
and its justification appears nowhere). `kind: "DERIVED"` says which numbers
these are — the symmetric answer to the balance endpoint's `"PROJECTION"`.
Deliberately not audited (a person's own read of their own account — the
`SessionQueries` stance); a `CLOSED` product's statement stays readable
(`INV-HIST-01`). Contract +36/−0; the three `BREAKING` labels are
`required = true` on the brand-new operation's own parameters, the
classifier erring safe, reviewed.

### Three stale records settled at their sources

The `BalanceProjection`/`BalanceProjectionTest` javadocs and the ownership
register's `derive` entry had named `P3-TSK-018` as "the display query"/"the
balance endpoint" — that surface was `P3-TSK-013`'s, the one-task plan drift
recorded then and corrected nowhere. Corrected where each lived;
`JdbcStatementDerivation.periodLines` joined the ownership register with the
disclosing surface's provenance named, and `JournalEntryStore.findById`'s
javadoc stops predicting a statement caller that arrived through its own
range reader instead.

**Seven mutations, all caught by the intended assertion, restores
byte-identical** — the period's upper bound made exclusive, the opening
derivation dropped, the period net dropped, the sides swapped, the reason
leaked into the reference, the inverted-period refusal dropped (the port's
refusal surfacing as our 500, caught by the 422 assertion), the account
predicate neutralised (caught deterministically: balanced entries make the
leaked net exactly zero). **1102 hermetic tests, 672 database tests.**

### Previously

**`P3-TSK-017` — `POST /v1/ledger/adjustments`: reason, permission, audit** —
`COMPLETE` (2026-09-17). **M3.6 closes: the platform's highest-risk financial
action exists, and every control the invariants demand is on it**
(`INV-REV-04`, `INV-AUD-03`).

| Acceptance criterion | Evidence |
|---|---|
| Negative authorization | `AdjustmentEndpointDatabaseTest`: a valid session without the role is a 403 with **nothing written**, and the operator's 201 is the positive control so the refusal is not blanket |
| Missing reason 422 | Refused at the boundary with nothing written — and the bound lives in **three reconciled places** (the DTO's `@Size(max = AuditRecord.MAX_REASON_LENGTH)`, `V004`'s `CHECK`, `AuditRecord` itself), held together by `AdjustmentRequestTest` with the `P1-TSK-028` field-target lesson applied |
| Every adjustment audited | `ledger.AdjustmentPosted` — **not** `JournalEntryPosted`: `PostingEffect` derives the action from the entry's kind, so the adjustment's reason regime cannot be skipped — naming the **person** and carrying the justification, in the adjustment's own transaction |

### The permission met its first real check site

`@RequiresPermission(LEDGER_ADJUST)` — exactly as `P3-TSK-007` recorded it
would, with `@RequiresIdempotencyKey` beside it (money-moving:
`P0-TSK-017`'s header finally meeting the money it was built for). **The
first request body ever to carry amounts** enters as decimal strings parsed
**exactly**: an amount not representable at the currency's scale is the
caller's 422 naming the line and field, never a rounding (`INV-MON-03` at
the inbound boundary, `INV-MON-01`'s reasoning in the other direction).

### The fingerprint binds the actor and the reason

ADR-0004's owning principal plus `INV-IDEM-03`'s sharpest case: a second
operator replaying a logged key gets a 409 — and so does the **same** key
with a **different justification**, because the reason is what makes an
adjustment defensible and two requests differing only there must never
silently collapse into one record. The unbalanced 422 is decided **before
any claim**, proven at HTTP: the same key then carries the corrected
request to a 201.

### What else the gate settled

Three codes catalogued (`ledger.UnbalancedAdjustment` 422,
`ledger.UnknownAccount` 422 — the store translating the line FK's `23503`,
the `V007` pattern — `ledger.AccountNotPostable` 409); the contract gained
the path with 68 added lines and **zero removed** (`BREAKING` labels the
classifier erring safe on a brand-new path, reviewed); both ledger audit
actions have left `NOT_YET_EMITTED`. **Four-eyes recorded, not implied**: no
threshold check exists, and the javadoc says so (`INV-AUD-04`, ADR-0010's
debt). One planned mutation was **cut on analysis and recorded**:
claim-before-validate is behaviourally invisible in the caller-transaction
model — the refusal's rollback takes the claim with it either way — so the
ordering is architectural discipline rather than a testable boundary here.

**Seven mutations, all caught by the intended assertion, restores
byte-identical** — the permission removed, the reason's `@NotBlank` dropped,
the action derivation dropped, the actor dropped from the fingerprint, the
unbalanced mapping removed, the key requirement removed, the reason dropped
from the fingerprint. **1102 hermetic tests, 668 database tests.**

### Previously

**`P3-TSK-016` — Reversal: a new effect referencing the original** — `COMPLETE`
(2026-09-17). **M3.6 opens: a mistake is corrected by a new entry, never an
edit** (`INV-HIST-01`) — and the platform can now undo a posting without
touching a single committed byte.

| Acceptance criterion | Evidence |
|---|---|
| Over-reversal refused | `ReversalDatabaseTest`: partials 30 + 70 accepted, one more minor unit refused (`OverReversalException`, amount-free — `INV-AUD-02`) with nothing written; raw SQL refused by `V009`'s trigger (`23514`) — the writer the domain never sees |
| Concurrent partial reversals sum correctly | Ten instances reversing 400 against 1000: **exactly two accepted, reversed total 800 counted in the table**; the deterministic interleaving observes the loser **Lock-waiting** on the advisory serializer, resuming onto the winner's committed rows and refusing |
| The original byte-identical before and after | Captured as PostgreSQL's own renderings — `e::text` and every line by `seq` — before the reversal, compared equal after, on top of the standing `DB-PRIVILEGE` immutability |

### The bound's arbiter, and the backlog sentence corrected on the record

The item said *"a predicate in the statement"* — and for insert-vs-insert
that predicate re-evaluates against the statement snapshot and cannot see a
concurrent uncommitted sibling: exactly the `P2-TSK-015` write-skew. The
row-lock arbiters are unavailable **by the phase's own privilege design** (no
`UPDATE` on `journal_entry`, so no `FOR UPDATE` on the original; a mutable
reversed-total row would be a second authority for a number the immutable
rows already define). So `V009`'s `BEFORE INSERT` trigger takes
`pg_advisory_xact_lock(2, hashtext(original))` — **namespace 2, registered**
— and sums prior reversal lines per `(account, direction)` pair under it,
scale-guarded, for **every** writer. The domain half (`ReversalBound`,
hermetic) refuses deterministically before any idempotency claim; each layer
suffices alone, proven by removing both (the `P1-TSK-018` defence-in-depth
form, recorded rather than tidied away).

### The effect extracted, earned by its second caller

`PostingEffect` — journal append, audit, outbox, projection last — with
`PostingService` delegating unchanged and `ReversalService` as the second
caller (the `CheckOutcomeTrail` rule: a write set copied per command drifts
in exactly one of its copies). Its own idempotency scope (`ledger.reverse`),
replay proven one-entry. **No new audit action or event type, decided on the
record**: the act is *a journal entry was posted* — `entryType` travels as
data, `reverses_entry_id` is where an investigator joins the correction to
its original, and a second vocabulary would name one fact twice. No reason
field: the reason regime is the adjustment's (`INV-REV-04`, `P3-TSK-017`),
which V004's implication CHECK was written to leave free. A reversal of a
`REVERSAL` is refused — domain and schema — because a chain would make the
bound's subject ambiguous; `Direction.opposite()` arrived with its promised
first caller.

**Seven mutations, all caught by the intended assertion, restores
byte-identical** — the trigger's refusal dropped, the advisory serializer
removed (the blocked-observation precondition), domain check and trigger both
dropped, `opposite()` made identity, the implication CHECK dropped, a replay
re-entering the effect, the attribution losing its reference. **1100 hermetic
tests, 660 database tests.**

### Previously

**`P3-TST-002` — `INV-CON-01` and `INV-BAL-04` under contention** — `COMPLETE`
(2026-09-17). **M3.5 closes: the contention point's register rows are landed
where the exit review will look**, before it needs them — the posture that
keeps the review a check rather than a scramble.

| Acceptance criterion | Evidence |
|---|---|
| The register rows for both invariants | `MUTATION_TESTING.md` §2 gains `INV-CON-01` (third row — the holds context joins the Phase 1 pair) and `INV-BAL-04`, plus the item's §4 row; each names its tests by `Class#method` with the observed result, all `Recorded` form honestly since every mutation edits production code |
| The named mutations demonstrated | The lock removed and the projection read recorded from `P3-TSK-015`'s sweep; **the check moved outside the lock performed by this item**, because dropped and moved are different defects |
| The rows held to the code | All nine `MutationDemonstrationTest` checks green over the new rows; teeth re-proven per §5 — one method reference corrupted, the guard failed naming exactly it, restored byte-identical |

### The audit's finding: one named mutation had not been performed

`P3-TSK-015`'s sweep performed the availability check **dropped**; this item
names the check **moved outside the lock** — a different defect and the
sharper one: the moved form still locks, still checks, and still loses the
race (the `P2-TSK-015` write-skew shape wearing hold clothes), which is
exactly the mutation that passes every sequential test. Performed here — the
derivation and the standing-holds fold hoisted above `lockForUpdate`, judged
after the grant — and **caught by both intended assertions in the way that
vindicates the test's design**: the blocked-observation precondition stays
green, because the lock is still taken, and the **outcome half** fails — the
loser resumes, judges its pre-lock snapshot, and wrongly accepts two holds of
600 in 1000 — while the ten-way race admits more than was available. A test
asserting only the coordination would have passed the moved check; only the
outcome half catches it; only the coordination half catches the removed lock.
**The two halves catch different defects, which is why the test carries
both.**

### Deferred in writing, with the precedent named

`INV-BAL-05`'s own row is the exit review's (the `P2-TST-001` handling of
`INV-KYC-06`): its demonstration — the corrupted-`holds_minor` test that made
the projection-as-decision-input behaviourally catchable — is already
performed and recorded inside the `INV-BAL-04` row, so what the review lands
is the record, not the work.

**No production code shipped.** The performed mutation was restored
byte-identical (asserted, in a `finally`); the deliverable is the register
and this state. **1090 hermetic tests, 653 database tests, 14 kafka tests.**

### Previously

**`P3-TSK-015` — `Hold`: place and release against available balance** — `COMPLETE`
(2026-09-17). **M3.5 opens at 1 of 2 — the phase's sharpest contention point,
decided by the account row's lock.** `INV-BAL-04` is real: a hold cannot make
available balance negative, and no account may permit it (the invariant's
permission clause has no subject — recorded, not smuggled in as a flag).

| Acceptance criterion | Evidence |
|---|---|
| The ten-way race respects available balance | `HoldDatabaseTest`: ten instances placing 1000 against 3000 — exactly three accepted, seven refused, **counted in the table**, `holds_minor` equal to the fold of the active rows |
| The lock proven load-bearing by removing it | The `FOR UPDATE` dropped (M1) fails the deterministic interleaving's **blocked-observation precondition** — the loser must be seen Lock-waiting in `pg_stat_activity` before the winner commits, the `P0-TST-004`/`P2-TSK-015` idiom |
| Release restores availability exactly | The full amount is placeable again; a retried release **converges** — the conditional's row count gates the decrement, the record and the event, so nothing is written twice |
| Crash mid-placement is all-or-nothing | The rollback leaves no hold row, no projection change, no record, no event |

### The protocol is P3-TSK-014's lock-mode analysis, reused

Place and release take `SELECT ... FOR UPDATE` on the **account row** (ADR-0039)
— the mode that conflicts with every in-flight posting's `FOR KEY SHARE` and
with every sibling placer — then derive **from authoritative rows in fresh
statements**: settled through `BalanceDerivation` (the definition), standing
holds through the `ACTIVE` rows of the new `ledger.hold`, folded through
`Money.plus` and never a SQL `SUM` (`P3-TSK-008`'s argument). The boundary is
exact: a hold of exactly available is accepted — `INV-BAL-04` forbids
*negative*, and zero is not negative — one minor unit more is refused naming
account and currency, **never an amount** (`INV-AUD-02`).

### INV-BAL-05 became behaviourally catchable, closing its own honest-limit class

`P3-TSK-014` recorded that swapping its derivation for the projection is
invisible because the projection is transactional. For holds the swap **is**
catchable: corrupt `holds_minor` through the app role's own narrow grant, and
a decision reading the projection refuses a hold the authoritative rows
accept. The test plants exactly that corruption, the decision is unmoved, and
the projection-read mutation is caught by it — the drift the verification job
exists to detect can no longer have the decision as its victim.

### What else arrived with the aggregate

`V008` carries the representable halves at `DB-CONSTRAINT` rank: amount
strictly positive, status/release-instant coherence, currency bound to the
account's by composite FK (the `V005` mechanism), and a trigger making
`RELEASED` **terminal for every writer** — with `V006`'s `holds_minor >= 0`
CHECK backing the release decrement. `ledger.HoldPlaced`/`ledger.HoldReleased`
joined both registries (no reason — the posting's argument; emitted by the
acting call only, so a converged release records nothing). The **close gained
its standing-holds check**: postings are not gated by holds, so settled can
reach zero while a reservation stands, and `P3-TSK-014`'s close — built when
holds were structurally zero — would have closed the product over it; refused
now as `AccountNotEmpty` under the same lock, from the hold rows. The
display's `available = settled - holds` became load-bearing (`P3-TSK-013`'s
recorded limit, closed by test). Plan §8's *"partial unique where active"*
corrected with provenance: uniqueness needs a subject, and the
one-active-per-commanding-reference dimension is Phase 4's — the index is
partial and deliberately not unique.

### Recorded remainders, each with an owner

Hold expiry (a rail/product rule — Phase 5's authorization lifecycle);
capture (release-plus-posting in the capturing flow — Phase 4/5);
`holds_minor` joining the verification job's comparison and the
`finapp.ledger.hold.active` gauge (M3.8's observability pass); the
`INV-CON-01`/`INV-BAL-04` register rows (`P3-TST-002`, next).

**Eight mutations, all caught by the intended assertion, restores
byte-identical** — the account `FOR UPDATE` dropped, the availability check
dropped, the decision reading `holds_minor` instead of the rows, the release
decrement dropped, the converged release acting again, the audit dropped, the
event dropped, the freeze trigger dropped. **1090 hermetic tests, 653
database tests.**

### Previously

**`P3-TSK-014` — Closing an account, without closing its history** — `COMPLETE`
(2026-09-17). **M3.4 closes: the milestone acceptance holds end to end over
HTTP.** `CLOSED` ends the agreement; the accounting history survives untouched
(`INV-HIST-01`), and the account stops accepting postings for every writer.

| Acceptance criterion | Evidence |
|---|---|
| Closing with a non-zero balance refused | `AccountClosingDatabaseTest`: the refusal writes nothing — product and ledger account stay `ACTIVE`, no record, no event — and its message and the 409 (`accounts.AccountNotEmpty`) name **no amount** (`INV-AUD-02`) |
| Posting to a closed account refused under the account lock | Through the domain (the named `LedgerAccountNotPostableException`, nothing committed) **and** by raw SQL against a from-scratch database — `V007`'s trigger is the DB-CONSTRAINT-rank half, binding the writers the domain never sees |
| History intact and readable afterwards | Same line count, same stored values, readable through the application role's own `SELECT` — and over HTTP: the closed account's balance endpoint still answers |
| The milestone acceptance | `AccountEndpointDatabaseTest`: open → balance → posting moves it → emptied → `DELETE` 204 → list shows `CLOSED` → repeat converges 204 |

### The race is closed by lock-mode analysis, not by hope

Every in-flight posting holds **`FOR KEY SHARE`** on its accounts' rows — the
`journal_line` FK takes it, and `V007`'s trigger read takes it explicitly —
and the subtlety is that a plain status `UPDATE`'s `FOR NO KEY UPDATE` does
**not** conflict with it: the obvious close implementation races. So the
closer takes `SELECT … FOR UPDATE` — the mode that conflicts — then looks
(`P2-TSK-015`). **Both interleavings proven deterministically**, the loser
observed Lock-waiting in `pg_stat_activity`: a posting in flight blocks the
close, whose fresh-statement derivation then sees the money and refuses; a
close in flight blocks the posting's trigger read, which on resume re-fetches
the row the close committed — `CLOSED` — and refuses. That second
interleaving is the exact race `P3-TSK-006` recorded that a lock-free status
read loses, and the mutation making the read lock-free is caught by it.

### The zero-balance check is a decision, so it derives inside the lock

Never the projection (`INV-BAL-05`, ADR-0041 rule 2) — and recorded honestly:
swapping the derivation for the projection is behaviourally invisible, because
the projection is transactional and never behind, so that property is held by
the stated design and review rather than by a runnable mutation.

### The deferred store methods arrived with their first caller

`lockOwnedBy` and `moveStatus` on both stores — exactly as their javadocs
promised when `P3-TSK-012`/`P3-TSK-002` deferred them — each classified on
arrival (`OWNER_SCOPED` with the closing test as its named negative;
`AUTHORITATIVE_ID` naming the locked owned read; the ledger's `NOT_OWNED` on
the recorded stance). `ACCOUNT_CLOSED` arrived with the design that fixed it:
no reason — the withdrawal argument, a person's exit from their own agreement
— emitted by the closing call only; ten concurrent closes produce one
transition, one record, one event, nine converged, and the freed slot admits a
successor agreement (`INV-LIFE-04`'s asymmetry). The plan's §9 table had no
close endpoint row while its own milestone line says "open/query/close over
HTTP" — corrected with provenance.

**Seven mutations, all caught by the intended assertion, restores
byte-identical** — the zero-check dropped, the trigger dropped, the trigger's
read made lock-free, the closer's `FOR UPDATE` dropped (each lock proven
load-bearing by its own interleaving), the converged path acting again, the
audit dropped, the ledger close dropped. **1079 hermetic tests, 645 database
tests.**

### Previously

**`P3-TSK-013` — the account endpoints** — `COMPLETE` (2026-09-17). The product
meets HTTP: open, list, and a balance that **says which number it is** — and
the milestone's acceptance now holds up to its last clause (closing, `P3-TSK-014`).

| Acceptance criterion | Evidence |
|---|---|
| End to end over HTTP: open, read, see it change after a posting | `AccountEndpointDatabaseTest`: `POST` 201 → listed → balance `"0.00"` (zero at the account's own scale, never a bare 0) → a real `PostingService` credit → settled and available `"12.50"`, holds `"0.00"` — nothing polled, because the projection is transactional (ADR-0041) |
| Two customers reach exactly their own | Each list exactly its owner's; A's balance URL with B's id, an unknown id and a malformed id are **one 404, asserted as an equality between the causes** |
| No request shape yields a 500 | Eight body shapes swept, all the caller's 4xx |
| The OpenAPI diff reviewed and accepted | 222 added lines, zero removed; the `BREAKING` labels are the classifier erring safe on brand-new required fields/enum/params — and the generated document caught `operationId: "open_1"` and a raw generic `list` before the baseline was born, both renamed |

### The first published amounts, and what they say

`{settled, holds, available}` per currency, each a **decimal string** — a JSON
number is a `double` in every careless client, and `INV-MON-01`'s reasoning
does not stop at our own boundary — under `kind: "PROJECTION"`, because a
response that just said "balance" would mean whichever number its reader
assumed. `available = settled − holds` is `INV-BAL-04`'s presentation;
`BalanceDisplay`/`JdbcBalanceDisplay` is `ledger`'s **second declared
projection reader** (the `P3-TSK-010` stance honoured: display only, never a
decision's input, no lock anywhere — the display must never contend with the
write path it mirrors). *(The `P3-TSK-009/-010` javadocs had named `P3-TSK-018`
as the display query while the plan's API table put the balance read here — a
one-task drift, recorded.)* **One honest limit recorded**: the available
formula is mutation-untestable while holds are structurally zero —
`P3-TSK-015`'s tests own it.

### The ownership guard met the second module, twice

`findOwnedBy` (the balance's `{id}`, with `customer_id = ?` in the statement)
was classified `OWNER_SCOPED` — and the guard's own predicate check hardcoded
`identity_id = ?` under a javadoc saying *"one name, because one module owns
every table this rule covers"*: true when written, false the moment a second
module owned a table. Widened to the documented ownership-predicate set, and
`revokeOwned`'s *"the only operation whose resource identifier comes from the
request"* corrected the same way — the stale-singular class, caught by the
guard demanding what its own vocabulary could not then accept.

### Idempotency at two layers, and the replay is the original

The executor stores the first 201's body and replays it **byte for byte**
(`INV-IDEM-01` — a retry learns what its request did, not what the world looks
like now); a reused key with a different request is a 409 conflict, the
fingerprint binding the **party** (ADR-0004's owning principal), so a stranger
replaying a logged key gets a conflict and never somebody else's account; a
keyless request is the interceptor's 422; and `P3-TSK-012`'s converge remains
the layer beneath. Two codes catalogued: `accounts.AccountOpeningRefused`
(409, cause-blind, the `ConsentRequired` shape) and
`accounts.UnsupportedCurrency` (422, names the caller's own correctable value).

**Eight mutation runs, all caught, restores byte-identical** — the ownership
predicate dropped (caught twice: behaviourally and by the widened build rule),
`@RequiresSession` removed (caught twice: the 401s and
`EveryEndpointDeclaresARuleTest`), the fingerprint made constant, the
absent-row zero made a throw, `@RequiresIdempotencyKey` removed, the
malformed-id fold removed. **1079 hermetic tests, 639 database tests.**

### Previously

**`P3-TSK-012` — `CustomerAccount`: the product, gated on verification** — `COMPLETE`
(2026-09-17). **Phase 2's projection meets its first consumer**: a verified
customer may hold an account, an unverified one may not, and the agreement's
money side exists from the same commit (ADR-0042).

| Acceptance criterion | Evidence |
|---|---|
| A KYC-approved customer opens an account | `CustomerAccountDatabaseTest`: the agreement (`ACTIVE` from birth), its `CUSTOMER_WALLET` ledger account — **`LIABILITY`/`CREDIT` asserted**, because a wallet typed `ASSET` would state that customer money is the platform's own — the audit record naming the person and one announcement, in **one commit**; the rolled-back open leaves none of the four |
| A rejected one is refused | One uniform refusal writing nothing — and `PENDING` and `REJECTED` are structurally indistinguishable, since rejection freed the one-live slot; the exception rolls the transaction back, so the refusal writes nothing by construction |
| Ten concurrent opens produce one | Ten connections, own `SecurityContext`/`CorrelationContext` each: one agreement, one ledger account, one record, one event, nine converged — counted in the tables; the partial unique index arbitrates, the savepoint keeps the losers alive |
| Every invalid transition refused by the aggregate | The cross-product sweep, derived from `permittedTransitions()`; `CLOSED` the one terminal, asserted as a property of the machine |

### The gate is a per-decision authoritative read, and the identifier is its answer

`AccountHolderVerification` (the `CaseKindResolver` shape — `accounts` cannot
see `party`) resolves the party's live customer filtered to `ACTIVE` inside the
opening's own unit of work, and **the account's `customer_id` is that answer,
never a caller's** — ADR-0031's defect has nothing to act on when no identifier
is trusted. A customer closed on another connection is refused on this one's
very next open (no cache, the `ConsentGate` discipline). **One accepted race,
stated**: open vs a concurrent customer closure — the `P2-TSK-008` class,
bounded because the product carries no balance and money-moving flows gate at
their own lock (Phase 4); the cross-module `SELECT … FOR UPDATE` on the party
row was rejected as exactly the coupling the boundary exists to prevent.

### Two design decisions taken on the record

**Open creates `ACTIVE` directly** — the gate is opening's only precondition,
so `PENDING` has no producer (the `STRONG`-assurance precedent: the machine is
under guard, not the state's popularity), and `CLOSED` is reachable from
`ACTIVE` only, with widening recorded as `P3-TSK-014`'s decision. **The
wallet-aggregate tension in the plan resolved rather than smuggled**: plan §4
called Wallet a *product type* and a *separate aggregate* in one breath — the
backlog's own one-per-customer-per-product-type rule puts the type on
`CustomerAccount`, no task builds a second aggregate, and ADR-0042's
premature-boundary argument applies verbatim one level down; provenance note in
the plan, the split trigger unchanged.

### `AccountsAuditAction` arrives with the aggregate, as P3-TSK-011 recorded

`ACCOUNT_OPENED` only — emitted by the creating call in the opening transaction
(a converged retry is not a second act), no reason (a person's own act on their
own relationship); `ACCOUNT_CLOSED` stays `P3-TSK-014`'s. The product is
**currency-less** (ADR-0042: it references the ledger accounts, one per
currency); `open` takes the initial currency, refused unless postable
(`SupportedCurrencies` — an account the chart cannot serve must not exist).

### The container clock drift, met by this task's own test

The grant sweep's positive control wrote `status_changed_at = now()` against a
JVM-clock `opened_at` and the ordering constraint refused it — the constraint
was right (`P1-TSK-031`, again), the fixture corrected to the
`LedgerAccountDatabaseTest` idiom. Grants proven per column with a positive
control: `UPDATE` on exactly `(status, status_changed_at)`, identity columns
and `DELETE` refused at the privilege.

**Eight mutation runs, all caught by the intended assertion, restores
byte-identical** — the gate call dropped, the eligibility filter widened past
`ACTIVE`, the ledger creation dropped, the converged path acting again, the
one-live index dropped (caught **twice**: hermetic reconciliation and the
ten-way race), the transition check removed, the `UPDATE` grant made
table-wide. **1079 hermetic tests, 633 database tests.**

### Previously

**`P3-TSK-011` — The `accounts` module and schema** — `COMPLETE` (2026-09-17).
**M3.4 opens: the P3-TSK-001 shape applied to `accounts`**, and the one thing
genuinely new in the third run of this play is the platform's first
business-sibling compile-time edge — declared so that its direction is a
property of the build graph rather than of anyone's discipline.

| Acceptance criterion | Evidence |
|---|---|
| `build databaseTest` green with the module present | 1071 hermetic, 626 database, 14 kafka tests |
| A planted `double` in `accounts` fails the floating-point rules | Caught naming exactly `accounts.Planted.amount is double (INV-MON-01)` — the derived module set reached the new module with no rule edited |
| A cross-module dependency fails the isolation test | Both directions: `accounts → party` caught by the new test; `party → accounts` caught by party's — the failure naming the transitively-arriving `ledger` first (list order; any edge to `accounts` drags its defining `ledger` dependency along, so the plant is caught unconditionally — recorded rather than glossed) |
| The ADR-0042 asymmetry, both directions | The positive half pinned (`accounts` must see `ledger`, `platform`, `sharedkernel`); the negative half both tested (`ledger` forbids `accounts`, and so do all four other siblings — the P2-TSK-003 decay closed at design time again) **and structural: a planted `ledger → accounts` edge fails Gradle configuration as a circular dependency**, demonstrated |

### The privilege floor is the deliverable, and it was proven live

`V001`: `REVOKE ALL FROM PUBLIC`, `USAGE` alone to `finapp_app`, **no
`ALTER DEFAULT PRIVILEGES`** — the first table this schema will hold is exactly
one whose `UPDATE` must be column-narrowed to `(status, status_changed_at)`
(plan §8), so each table's grants arrive with the migration that creates it.
Throwaway PostgreSQL: migrate → validate → re-migrate idempotent, owner
`finapp_migrator`, ACL exactly `{finapp_migrator=UC, finapp_app=U}`, no
`PUBLIC` entry, zero tables, `USAGE` and **not** `CREATE` for the app role.
The history holds Flyway's own schema-creation marker plus one versioned row —
checked against `ledger`'s identical shape rather than assumed.

### One deliberate deviation from the shape, and it follows the licence

**No `AccountsAuditAction` enum.** `P3-TSK-001` declared `LedgerAuditAction`
because the plan names both its actions outright; for `accounts` the plan names
`AccountOpened`/`AccountClosed` as **events** (§10) and no audit action at all,
so under the deliberately-few licence the lifecycle actions arrive with the
aggregate whose design fixes their meaning — the `kyc.CaseOpened`/`P2-TSK-005`
precedent, recorded in `package-info.java` where the next reader will look.

### The documented procedure was stale at its last step

`DATA_MIGRATIONS.md` §"Adding a schema-owning module" step 5 instructed adding
the module to a CI sequence that `P1-TSK-003` deliberately removed — CI runs
`flywayMigrate`/`flywayValidate` unqualified, so a new schema-owning module is
covered with no edit. Found by **following** the procedure, corrected with
provenance: a checklist whose last step sends the reader to edit a list that no
longer exists is the drift class this repository keeps meeting, arriving inside
its own how-to.

### Housekeeping recorded rather than absorbed

`accounts/gradle.lockfile` identical to `ledger`'s but for its header line;
`gradle/verification-metadata.xml` unchanged (the new module adds no artefact
the build did not already trust); the `build-logic` Kotlin RC3→GA lockfile
drift met and reverted a **fourth** time; and this document's own M3.4 block
counted three tasks where the epic holds four — corrected, with the note where
the count is used. The `P1-TSK-026` cmd trap (bare `gradlew.bat`) voided one
run and was caught by the absent build marker.

**Five probes, all caught by the intended guard, every restore byte-identical.**
**1071 hermetic tests, 626 database tests.**

### Previously

**`P3-TST-001` — `INV-BAL-02` under sustained concurrent posting** — `COMPLETE`
(2026-09-16). **M3.3 closes: the acceptance holds by demonstration.** The pieces
were each proven alone — `P3-TSK-009`'s ten-way race, `P3-TSK-010`'s
deterministic in-flight interleave — and the `P1-TSK-027` lesson is that two
green halves compose only when something drives them together. This item is the
composition, sustained.

| Acceptance criterion | Evidence |
|---|---|
| The register row lands with its named mutation proven caught | `MUTATION_TESTING.md` §2 gains `INV-BAL-02` and §4 gains the item's row. The mutation — **the projection updated outside the posting transaction** (upserts rerouted onto a private autocommit connection, the async-projector defect in its smallest form) — fails **five tests across both suites, the two intended among them**: the rolled-back posting deterministically, and the sustained storm's drift-free-throughout sweep |
| Replay-from-zero equals the projection throughout | Ten instances posting continuously; the storm is ended by the **verifier**, which sweeps until ≥25 verdicts complete *and* ≥200 entries commit — overlap by construction, never scheduling luck (`P0-TST-004`) — and every mid-storm verdict is `CLEAN` or `IN_FLIGHT`, never `DRIFTING`; afterwards `CLEAN`, with the row equal to the posters' own committed tally, tracked outside the kernel |
| A projection rebuild while postings continue | Three mid-storm rebuilds, each preceded by a row corruption so it provably rewrites, each followed by required live commits so the next runs against real traffic; the storm ends `CLEAN` with the tally exact |

### The rebuild procedure is recorded where it is proven

Rebuild tooling deliberately did not arrive (`P3-TSK-009`'s gate) — a rebuild is
the migrator's act, and the safe procedure under live posting is
**lock-then-look**: `SELECT … FOR UPDATE` on the projection row, then recompute
and overwrite in a *fresh* statement. A single `UPDATE` with recomputing
subqueries is deliberately not the procedure, because a blocked update
re-evaluates its subqueries against the statement's **original** snapshot
(`P2-TSK-015`'s write-skew finding) and would silently lose exactly the posting
it blocked on. Under the lock, a posting committed before it is in the
recomputation and its delta is replaced rather than double-counted; one still in
flight is absent and its delta applies after — `rebuilt + delta` exact either
way.

### The first draft's storm was a gust, and the run said so

A 10-sweep/30-entry floor was satisfied in ~300 ms on a warm container. The
floors were raised to 25 sweeps and 200 committed entries — still seconds, and
"sustained" is now a fact about the exit condition rather than a word in a test
name.

### The register guard's teeth, re-proven per §5

One method reference corrupted (backup **copy**, never `git checkout --`);
`every method the register names exists on its class` failed naming exactly it;
restored and verified byte-identical; green again.

**The named mutation, performed once and recorded** (the Recorded form — a
production-code change cannot live in the suite). **1069 hermetic
tests, 626 database tests.**

### Previously

**`P3-TSK-010` — The verification job and the drift metric** — `COMPLETE`
(2026-09-16). The comparison — not anybody's confidence — is the evidence the
projection is right (`INV-BAL-02`, ADR-0041 rule 2): every posted account
recomputed through the derivation that defines the balance and compared to
`ledger.account_balance`, continuously, with `finapp.ledger.projection.drift`
publishing the disagreement count at an alerting threshold of **zero**.

| Acceptance criterion | Evidence |
|---|---|
| An injected drift is detected and reported | `ProjectionVerificationDatabaseTest`: a `posted_minor` corruption and, separately, a `last_entry_seq` corruption — each injected through the app role's own narrow `UPDATE` grant, the closest stand-in for the writer nobody wrote — each reads `DRIFTING`, with positive controls proving the detection was the corruption |
| The job is safe to run while postings continue | The seq-bracketed read, proven **deterministically**: a derivation decorator commits a concurrent posting mid-comparison (the `P1-TSK-012` idiom — no sleeps, no timing luck); the verdict is `IN_FLIGHT`, never false drift, and the next run is `CLEAN` |
| NaN when unreadable, never zero | `LedgerMetricsTest` — and the rule bites hardest on this meter, because zero means *verified clean*, so a comforting zero would silence the one alert the gauge exists to fire |

### The seq-bracketed read is the answer to "safe while postings continue"

Per account: read the row's watermark, count `DISTINCT entry_id`, derive `Latest`,
read the watermark again. Every domain-path entry commits **atomically** with its
seq bump (`P3-TSK-009`), so a stable bracket means no entry committed
mid-comparison and every read saw one applied set; an unstable one is `IN_FLIGHT`
— tolerated by the watermark, never by a time window (plan §14.6), settled next
run. **No lock is taken anywhere**: the verifier must never contend with the
write path it audits, and locking the hot projection row for a whole-history fold
would make monitoring the cause of the incident.

### What counts as drift, and what is done about it

A row absent while lines exist — **the raw-SQL bypass `P3-TSK-009` recorded as its
honest limit, now detected and proven** with planted entries; the watermark
disagreeing with the applied-entry count; settled numbers differing under
scale-including equality (`INV-MON-05`); or an underivable history with a standing
projection number, because unverifiable is not clean. **Reported, never
repaired** — plan §14.12's rule: a ledger that corrected itself would destroy the
evidence of what went wrong; repair is a reasoned adjustment (`P3-TSK-017`). A
non-zero reading WARN-logs the drifting account identifiers, bounded and
amount-free (`INV-AUD-02`), rate-limited by the cache itself.

### The scheduling question answered by needing no schedule

The backlog offered idempotent-per-run or lease-protected and warned a new
`DISTRIBUTED_EXECUTION.md` §3 exemption is a decision. The design needs neither:
**the scrape is the schedule** — the sweep runs when a scrape finds the cached
reading past its 30-second floor (six times the sibling gauges', because this read
walks every posted account), the `OutboxBacklog` shape. Nothing schedules
ambiently, so `nothingSchedulesAmbiently` sees nothing and no exemption question
arises; the sweep is read-only and idempotent, every instance verifies
independently, and all publish the same fleet-wide figure (`max()`, never
`sum()`). The per-instance cache is a non-authoritative *reading* — the
`IdentityMetrics$Cached` recorded stance, no §3 row.

### The first reader of the projection arrived and said what it returns

`BalanceProjection`'s javadoc demanded it, and `ProjectionVerification` answers:
**verdicts and counts, never a balance** — no public method hands `Money` or a
`DerivedBalance` out, pinned by `BalanceProjectionTest` alongside the write
port's single `void` method. `INV-BAL-05` survives the read's arrival: nothing
read from the projection can become a decision's input. The display query
(`P3-TSK-018`) must come and say the same.

### What deliberately did not arrive

The other five §15 meters and the dashboard row (M3.8's, with the phase's
observability); rebuild tooling; the trial-balance job (M3.7); any repair path;
`INV-BAL-02`'s mutation-register row (`P3-TST-001` owns it by its own backlog
text). `LedgerMetrics`/`$Cached` joined the floating-point exemption set as the
same Micrometer-gauge case a fourth time; `OwnershipIsScopedTest` demanded the
three SQL-bearing helpers and got honest `NOT_OWNED` entries.

**Six mutations, all caught by the intended assertion** — the settled comparison
dropped, the seq-vs-count check dropped, the absent row made clean, the in-flight
bracket dropped (false drift under concurrency), the underivable refusal
swallowed, and the gauge's NaN made zero. **1069 hermetic tests,
624 database tests.**

### Previously

**`P3-TSK-009` — The transactional projection** — `COMPLETE` (2026-09-16).
**M3.3 is 2 of 3.** `ledger.account_balance` exists and is never *behind*: updated
in the posting's own transaction, so it is current or absent along with the fact
(ADR-0041) — and `INV-BAL-05`'s rule that **no decision reads it** is structural
rather than documented, because no read exists to call.

| Acceptance criterion | Evidence |
|---|---|
| Projection and derivation agree under sustained concurrent posting | `BalanceProjectionDatabaseTest`: ten instances, own connections, one **fresh** account so the race covers the first row's `ON CONFLICT` convergence as well as the increment — afterwards the projection equals the derivation, the watermark equals the applied-entry count, and no increment is lost (100+…+1000 = 5500, counted in the table) |
| Updated in the posting transaction | The apply is `PostingService.postOnce`'s **last** write — entry, audit, outbox, projection, one unit of work; a rolled-back posting leaves the projection unchanged, a replayed command applies nothing, and a refused posting commits nothing at all |
| Owned and written only by `ledger` | The table's grants (`SELECT, INSERT`, `UPDATE` column-narrowed to the accumulating columns, no `DELETE`), and the one write seam is the `BalanceProjection` port inside `ledger` |

### "No decision reads it", made structural

The port declares exactly one method and it returns `void`: nothing in Java can read
the projection, because no read exists. `BalanceProjectionTest` pins that shape
hermetically, so a read added in a hurry is a build failure until somebody decides —
the arriving readers are named (`P3-TSK-010`'s comparison, `P3-TSK-018`'s display
query), and each must say what kind of number it returns (ADR-0041's consequence).

### The delta folds through the kernel; the accumulation is one guarded SQL addition

Per account, the entry's sides fold through `JournalEntry.sum` and are signed by
`BalanceDerivation.settle` — the sign convention still stated once. The row's
`posted_minor + delta` then happens in SQL, admissible where `P3-TSK-008`'s
history-wide `SUM` was not because both failure modes that argument names are
structurally closed at this one addition: cross-scale accumulation is refused by the
update's scale-match condition, and `bigint` overflow **raises** in PostgreSQL
rather than wrapping (`INV-MON-06`). **Not read-modify-write**: the increment
re-reads under the row's own exclusive lock (the `P1-TSK-011` counter shape), so ten
concurrent postings serialise and compose; multi-account entries lock projection
rows in one fixed order, so no two postings can deadlock. The lock is taken **last**
in the transaction, deliberately — ADR-0041's accepted contention, held for the
shortest window the transaction allows.

### The watermark answers `AsOf`'s caveat by not being an entry id

Entry ids order by mint while commits interleave, so an id watermark would make
"everything through entry X" a set no later observer can reconstruct — false drift,
or masked drift. `last_entry_seq` is the **ordinal of the last entry applied to this
row**, serialised by the row's own lock, with no ordering semantics to be wrong
about: "is this projection current?" is `last_entry_seq = COUNT(DISTINCT entry_id)`
over the account's lines, and the comparison tolerates in-flight entries by the
count, never by a time window (`PHASE_3_PLAN` §14.6). The answer now sits in
`AsOf`'s javadoc where the question was recorded.

### A scale-divergent posting is refused wholly — a deliberate strengthening

An entry at a persisted scale diverging from the account's history was previously
postable and merely made the balance underivable later. With the projection in the
write path it is refused **at posting time** (`UnderivableBalanceException`, naming
the fact and never a sum — `INV-AUD-02`), and nothing commits: no entry, no claim,
no projection change. A posting the projection cannot follow must not commit beside
a projection now permanently behind. The raw-SQL writer still bypasses the
projection entirely — which is exactly the drift `P3-TSK-010`'s comparison exists
to detect, stated rather than papered over.

### What deliberately did not arrive

Holds stay zero (`holds_minor` written 0, constrained non-negative — `P3-TSK-015`
populates it); no verification job or drift metric (`P3-TSK-010`); no read path, no
endpoint (`P3-TSK-018`), no bean, no new audit action (projection maintenance is the
platform's derived bookkeeping of the already-audited posting — the `P2-TSK-014`
licence). `V006` gains `scale` over ADR-0041's sketch, because a persisted amount
without its scale is uninterpretable (`INV-MON-05`), and the row's currency is bound
to the account's by composite FK (the `V005` mechanism). `OwnershipIsScopedTest`
demanded classification for both new SQL-bearing methods and got honest `NOT_OWNED`
entries — the detector locating the private helper again, the `P1-TSK-021` finding
holding shape.

**Six mutations, all caught by the intended assertion** — the apply dropped from the
effect, the sign inverted, the accumulation made an overwrite, the watermark frozen,
the scale guard dropped, and the refusal swallowed (the posting committing beside a
projection that had not followed it). **1064 hermetic tests,
621 database tests.**

### Previously

**`P3-TSK-008` — Balance derived from postings** — `COMPLETE` (2026-09-16).
**M3.3 opens, 1 of 3.** The phase's defining question answered: the authoritative
number is computed from the rows and nothing else (`INV-BAL-01`), reproducible from
zero (`INV-BAL-02`) — and it is the **definition** everything later is checked
against, which is why its own verification is against independent arithmetic.

| Acceptance criterion | Evidence |
|---|---|
| Replay from zero reproduces the balance for every account type and both normal balances | `BalanceDerivationDatabaseTest`: real postings over the four reachable types (ASSET/LIABILITY/REVENUE/EXPENSE — `EQUITY` has no `AccountPurpose`, so no account of that type can exist to post to), each held to a recomputation through **independent `BigDecimal` arithmetic over raw SQL rows**, never through `Money`; the hermetic sweep covers all five types via `AccountType.values()` |
| An account with no postings is zero in its own currency, never a bare `0` | A JPY wallet answers zero-at-scale-0 and a GBP wallet zero-at-scale-2 — the empty answer still says what kind of number it is (`INV-MON-02`) |
| The derivation is the definition the projection is checked against | One computation, stated once: `BalanceDerivation.settle` is the only statement of the sign convention, and `P3-TSK-009`'s backlog names it as the comparator |

### The sums fold through `Money`, never through a SQL `SUM`

An aggregate computed in SQL is a second implementation of monetary arithmetic
outside the kernel: it silently adds minor units across scales (the implicit rescale
`INV-MON-03` forbids) and silently widens past `long` where `Money` refuses
(`INV-MON-06`). Folding through `Money.plus` makes those refusals **structural** —
the derivation cannot commit the errors because the operations do not exist. The
cost, streaming every line, is the honest cost of replay-from-zero and exactly what
the verification job pays anyway; the fast path is the projection, by design
(ADR-0041). One statement reads the lines, so one snapshot: the derivation is
internally consistent and sees **committed postings only** — an uncommitted posting
on another connection is proven invisible, deterministically.

### Where a debit finally means something

`Direction`'s javadoc has said since `P3-TSK-004` that a debit is not "money in"
and that the two vocabularies meet at the balance derivation. They now do, in one
statement: settled = normal-side sum − opposite-side sum, positive meaning the
account has grown in its own terms. **Negative is a legal state, not an error** — an
asset posted mostly by credit reads negative, and refusing it would be overdraft
policy (`P3-TSK-015`'s hold logic) wearing an accounting identity's clothes.

### A mixed-scale history refuses loudly, and the refusal names no amount

`V004` guarantees one scale per currency **per entry**; nothing guarantees it per
account across history — `P3-TSK-005`'s own raw-SQL plant proves such rows are
storable. Summing raw units across scales is meaningless and normalising is the
rounding `INV-MON-03` forbids, so the only honest answer is the domain's own:
**refuse**, as `UnderivableBalanceException`, naming the account, the currency and
the fact — never a sum, because the message reaches logs and balances are
`RESTRICTED-FINANCIAL` (`INV-AUD-02`; `Money`'s own diagnostics render amounts, which
is right for the kernel and wrong here, so the kernel's refusals are translated).
Beside it, the case that must NOT refuse: a **one-sided** persisted-scale history is
a balance, not a mixed history — the scale-aware zero identity (`P3-TSK-004`'s
recorded property) applied at the subtraction, with `JournalEntry.sum` going
package-private for its second caller rather than growing a copy that drifts.

### The entry cut's two traps, stated where the next task will read them

`AsOf.throughEntry` compares entry ids **in SQL only**: PostgreSQL orders `uuid`
bytewise while `java.util.UUID.compareTo` compares signed longs and disagrees, so a
Java-side comparison is forbidden by comment where the temptation would arise. And
ids order by **mint** while commits interleave, so an id cut is a replay boundary
over committed rows, not a linearisation point — `P3-TSK-009`'s watermark design
must confront that, and the caveat sits in `AsOf`'s javadoc where it will be read.

### What deliberately did not arrive

No migration — `V004` indexed this exact read by name when it created the table. No
bean (nothing consumes the derivation until `P3-TSK-009`/`-010` — the `P1-TSK-007`
licence), no endpoint (`P3-TSK-018`), no meters (`P3-TSK-010`), no audit action (a
derived read is the platform's own bookkeeping). `OwnershipIsScopedTest` refused
`derive` until classified: `NOT_OWNED`, because a ledger account may be the
platform's or a customer's, so ownership is a property of the **surface** that
discloses the number — the arriving surfaces named.

**Six mutations, all caught by the intended assertion** — the sign convention
inverted, the empty-account zero hardcoded to one currency, the posting-date
predicate neutralised, the entry cut made exclusive, the mixed-scale refusal
swallowed (a partial sum returned quietly), and `ofPersisted` swapped for
`ofMinorUnits` (the stored scale re-derived — the `INV-MON-05` shape).
**1062 hermetic tests, 616 database tests.**

**`P3-TSK-007` — `LEDGER_POST` and `LEDGER_ADJUST` permissions, and the ledger role**
— `COMPLETE` (2026-09-14). **M3.2 closes: 4 of 4.** Posting authority is a privileged
capability rather than an ambient one, and the third privileged population arrives
with its least-privilege split proven from every direction.

| Acceptance criterion | Evidence |
|---|---|
| Negative authorization test per new permission | `DenyByDefaultDatabaseTest.thePopulationsArePairwiseDisjoint`: an administrator and a reviewer each refused by a ledger probe, the operator refused by theirs — six refusals, four positive controls so none is blanket (`INV-AUD-03`) |
| The role granting everything fails the build | The acceptance mutation, **caught twice**: `RoleNameTest`'s exact-grant assertion hermetically, and the HTTP pairwise-disjointness test independently — two controls blind in different directions |
| Cross-population both ways | A ledger operator granted through the **real** roles endpoint is refused by both administrative endpoints; the disjointness test covers the other four direction-pairs |

### Two permissions, one role — and the asymmetry is the design

The permission vocabulary is precise because splitting a permission later means
re-auditing every check site: `P3-TSK-017`'s adjustment endpoint checks
`LEDGER_ADJUST` specifically, where `INV-REV-04`'s reason regime attaches and
`INV-AUD-04`'s four-eyes stays recorded debt. The role bundles both because a role
exists when a distinct trust decision does (`P2-TSK-004`'s rule) and Phase 3 has one
ledger-operating population; splitting a role later is a new role and a migration.

### What deliberately carries neither permission: the posting command

ADR-0031 puts permission at the boundary, and `PostingService` is the in-process
command path (`INV-LED-04`'s one write path), invoked by the platform's own
orchestrations under the **flow's** actor — a Phase 4 transfer runs as the customer,
and a customer moving their own money holds no ledger permission. The permissions gate
the HTTP surfaces where a *person* commands a posting, so both ship with **probe
endpoints and no production caller** — the `KYC_REVIEW` precedent, stated rather than
smuggled, with `P3-TSK-017` named as the first real check site.

### The machinery built for this day did its job with no edits

`V014` is the `V013` ceremony — the constraint replaced from
`RoleName.sqlValueList()`, and `RoleAssignmentMigrationTest`'s latest-constraint
derivation reconciled it **without being touched**, `V010`'s pinned history untouched
too. Disjointness generalised to **pairwise over `values()`** so the fourth role is
held to the property without anyone editing the test — the stale-list defect, closed
the way this repository closes it. The contract gained one request-enum value,
labelled `BREAKING` by the classifier's blanket rule and accepted on review (a client
that never sends the value cannot be broken by it — the `P2-TSK-004` precedent). The
self-elevation limit is restated, not re-argued: `ROLE_ASSIGN` can still self-grant
`LEDGER_OPERATOR`, and what the split buys is a recorded grant in the trail.

**Six mutations, all caught by the intended assertion** — the operator granting
everything (twice: hermetic and HTTP), the administrator gaining `LEDGER_POST`, the
reviewer gaining `LEDGER_ADJUST`, the operator losing `LEDGER_ADJUST`, and `V014`
keeping the old two-role list while the enum holds three.
**1059 hermetic tests, 609 database tests.**

**`P3-TSK-006` — The posting command: idempotent, atomic, audited, announced** —
`COMPLETE` (2026-09-13). **M3.2 is 3 of 4.** One command, one financial effect, whatever
the caller does — and the one write path `INV-LED-04` permits now exists.

| Acceptance criterion | Evidence |
|---|---|
| Ten-way race, one effect | One executed, nine replayed with the same entry id, one row **counted in the table** — never inferred from a return value |
| Crash between commit and publication → republished, same `eventId` | The outbox row commits with the entry (asserted, with the flow's correlation); redelivery-with-the-same-`eventId` over that table is `P0-TST-005`'s proven property, cited in the assertion rather than re-proven |
| Injected failure at the last write → nothing at all | The failing outbox writer leaves no entry **and no claim** — the retry re-attempts rather than replaying a failure that never committed |
| A rolled-back posting leaves no outbox row | Asserted (`INV-EVT-01`'s second half) |

### The command joins the caller's transaction, and that is Phase 4's seam

*The transfer state transition and the ledger posting commit together* only works if
`PostingService.post(unitOfWork, command)` joins a transaction rather than opening one —
so "the ledger owns the posting transaction" (`MODULE_ARCHITECTURE.md`) means the ledger
decides the posting's **write set** (entry, lines, audit record, outbox row, idempotency
record — together or not at all), while the boundary is the caller's.

### Validate, then claim, then effect

`JournalEntry.balanced` runs before the idempotency claim, so an unbalanced request never
consumes its key; the minted-and-discarded entry id on a replay costs nothing (ADR-0013's
recorded stance). The fingerprint covers the money and its meaning — type, dates,
reference, per-line account/direction/amount/currency/scale — and deliberately excludes
actor and correlation, because a retry arrives on a new request with a new correlation
and must still replay. The stored response carries the entry id, so every caller learns
which entry exists, however many times it asked.

### The audit action is emitted at last, and the event carries no amount

`ledger.JournalEntryPosted` leaves `NOT_YET_EMITTED` — written with actor (from
`SecurityContext.require()`, never defaulted), correlation (flow-root cause resolved the
`OrganisationRegistration` way), and a change summary of identifiers and counts. The
event's payload is the enumerated entry type and nothing else: a consumer needing the
amount reads the posting (`INV-AUD-02`).

### The command's validation question, answered one rank stronger

What must the command check about the accounts it posts to? For currency: nothing — the
domain deliberately cannot see the mismatch (a line holds an identifier), so **`V005`
binds a line's currency to its account's by composite FK** (the kyc `V008` kind-binding
precedent), refusing a USD line on a JPY account for every writer before the first real
posting exists. For status: **a recorded remainder with its owner** — every reachable
account is `ACTIVE` (no store writes status yet), and *posting to a closed account
refused under the account lock* is `P3-TSK-014`'s own design, because a lock-free status
read here would be the check that passes every test and loses the race to close.

**Six mutations, all caught by the intended assertion** — the audit write dropped, the
outbox write dropped, the fingerprint made constant, the key silently made per-call (the
`P2-TSK-002` dedupe-key-per-delivery shape, here making every retry a second posting),
the actor defaulted to the platform, the composite FK dropped.
**1058 hermetic tests, 608 database tests.**

### Previously

**`P3-TSK-005` — Postings persisted: balanced by constraint, immutable by privilege** —
`COMPLETE` (2026-09-13). **M3.2 is 2 of 4.** The database refuses what the domain refuses —
against the writer the domain never sees — and `DB-PRIVILEGE` finally carries
`INV-LED-03` and `INV-HIST-01`, the moment the phase's first task laid the floor for.

| Acceptance criterion | Evidence |
|---|---|
| Dropping the balance enforcement fails a test | The trigger-dropped mutation is caught by the direct-SQL commit refusal, against a from-scratch database |
| Granting `UPDATE` fails a test | The `P0-TST-007` sweep — every column of both tables from `information_schema` — catches the widened grant |
| A raw SQL unbalanced entry is impossible | Every statement succeeds and the COMMIT throws `check_violation` naming `INV-LED-01` — the deferral is the design, and the mutation making the trigger immediate breaks every legal append, proven |

### The named design problem: why the balance is a deferred constraint trigger

A `CHECK` sees one row and the rule spans an entry's lines; PostgreSQL's `CHECK`s cannot
defer, `ASSERTION` is unimplemented, and sum-columns fail multi-currency while needing
`UPDATE` on an insert-only table. So: two `CONSTRAINT TRIGGER`s, `DEFERRABLE INITIALLY
DEFERRED`, judging the whole entry at COMMIT — the only moment it is whole, and a
mechanism that binds raw SQL. **Two, not one**: a zero-line entry balances vacuously and a
line trigger never fires for it — `P3-TSK-004`'s finding arriving at the schema exactly as
predicted — so the line trigger owns balance-and-scale and an entry-anchored trigger owns
`INV-LED-02`. The scales clause is probed with the one shape only it catches: equal raw
sums at different scales (1500@2 vs 1500@3 — 15.00 against 1.500).

### Immutable twice over, and the second layer binds the owner

The application role holds `SELECT, INSERT` and nothing else — swept per column. And an
unconditional `BEFORE UPDATE OR DELETE` trigger refuses the migrator too (stronger than
the task asked, on the freeze-trigger precedent): nothing edits history, whoever it is,
and archival that ever must move rows drops the trigger by reviewed migration.

### Three findings on the way

**The driver rounds; the test expected truncation.** `timestamptz` stores microseconds and
PostgreSQL ROUNDS the nanoseconds — found by a round-trip assertion failing by exactly one
microsecond. The claim is now the column's own: within its microsecond resolution.
**`OwnershipIsScopedTest` refused `findById` until classified** — on the day the
`OutboxRelay` entry predicted ledger identifiers would surface, which it now records as a
prediction that held. `NOT_OWNED`, with the sentence that is true: a journal entry's lines
may touch many parties' accounts and the entry belongs to none of them; the surfaces that
control disclosure (`P3-TSK-016`, `P3-TSK-018`) must come and say so.
**`PostingAttribution` carries the actor's id, not a typed `Actor`** — the column holds an
id, the audit record of the same command types it authoritatively, and a typed copy here
would be a guess on read-back.

### `INV-MON-05` is proven where re-derivation would hide

`BIGINT` extremes round-trip at scales 0/2/3 — and a row planted by raw SQL at an
off-default scale (USD at 3) reads back at 3, because every store-written line is at the
currency's current scale and a rehydrate that re-derived scale would pass the whole rest
of the suite (the `P0-TSK-038` `INV-MON-05` finding, met one layer up and closed by the
`ofMinorUnits`-instead-of-`ofPersisted` mutation being caught).

### The self-armed guards went live on schedule

The classification freeze's posted branch now runs against the real `journal_line`: the
stand-in table was dropped from `LedgerAccountDatabaseTest` (it would have collided) and
the fixture is a real balanced entry — the re-proof the stand-in's comment promised. The
seam test's line count queries a real table from this migration on.

**Eight mutations, all caught by the intended assertion** — the balance trigger dropped,
the line-count trigger dropped, the scales clause dropped, `UPDATE` granted, the
append-only trigger dropped, the deferral removed (every legal append breaks — deferral is
load-bearing, not decoration), the generated money shape hand-edited, and the rehydrate
re-deriving scale. **1057 hermetic tests, 600 database tests.**

### Previously

**`P3-TSK-004` — `JournalEntry` and `JournalLine`: the balance rule at the domain** —
`COMPLETE` (2026-09-13). **M3.2 opens, 1 of 4.** The phase's first High-risk task: an
unbalanced entry cannot be **constructed**, so `INV-LED-01` has no call site at which to be
forgotten.

| Acceptance criterion | Evidence |
|---|---|
| Every unbalanced shape throws | The named cases — plain imbalance, the cross-subsidy (balanced in total, unbalanced per currency), single and empty line sets, zero and negative amounts, mixed scales — each the smallest example of its class, plus the sweep |
| Property test over generated line sets | 2000 trials over JPY(0)/USD(2)/BHD(3): seeds repaired to balance all construct and re-verify against an **independent `BigDecimal` implementation** (the sweep must not certify `Money` with `Money`); every one-minor-unit perturbation throws; coverage asserted, not hoped — all three scales heavily exercised and over a quarter of trials multi-currency |

### Direction carries the sign, and that choice is what the schema can inherit

Amounts are strictly positive (a zero line asserts nothing; a negative one is a credit
wearing a debit's clothes) and `Direction` is an enum — so "unbalanced" is **two sums that
must be equal**, never a subtraction that happens to be non-zero, which is the property
`INV-LED-01` actually states and the one `P3-TSK-005`'s constraint can carry.

### The sums fold through `Money.plus`, and two properties come out structural

Cross-currency addition is impossible (`INV-MON-04`), and **mixed scales within one
currency refuse rather than normalise** — silently rescaling a line to make the sum
computable is the implicit rounding `INV-MON-03` forbids. The cross-sides mix (debits at
scale 2, credits at scale 3, equal value) surfaces as *unbalanced* under `Money`'s own
scale-including equality — 1.50 and 1.500 are different stored facts (`INV-MON-05`) — and
the fold's zero identity is scale-aware so a persisted-scale line is summable without the
identity's own scale becoming an artefact, which its mutation proves is load-bearing.

### The empty entry is why `INV-LED-02` is its own check

A zero-line entry balances **vacuously** — every currency's two sums are equal at zero — so
a balance check alone waves it through. The line-count refusal runs first and separately,
and its mutation is caught by exactly the empty-entry half of the test, which is the
load-bearing half.

### The three dates arrive as the model demands

Posting date and value date are **required inputs** — the factory demands both, so a
component wanting a clock-derived accounting date has no overload to do it with; the
substitution `DOMAIN_MODEL.md` §Time warns about now takes an explicit
`LocalDate.now(clock)` at the caller, where a review can see it. No ordering between the
three is imposed: back-dated corrections and late settlement are both legal shapes.

### No amount reaches a rendering or a message

`INV-AUD-02` at the type: a record's generated `toString` prints every component, so
`JournalLine` overrides it (account, direction, currency — never the amount);
`UnbalancedJournalEntryException` names the currency and the fact, never the sums, because
an exception message reaches logs and journal amounts are `RESTRICTED-FINANCIAL`. Both
asserted with a searchable needle, and the leak mutation is caught.

### What deliberately did not arrive

Attribution, entry type, reference and reason (the persisted record's fields — `P3-TSK-005`
and `-006` fix their semantics); `seq` (persistence's; the list order here is the order);
`Direction.opposite()` and `sqlValueList()` (dead until reversal and the migration — each
arrives with its caller, the `P1-TSK-013` rule); any coupling to `SupportedCurrencies`
(whether a currency is *postable* is the command's question against the chart, not a
property of the value).

**Six mutations, all caught by the intended assertion** — the balance check removed, the
per-currency grouping collapsed onto one bucket, the line-count refusal removed, the
positivity refusal removed, the amount leaked into a rendering, the fold's zero identity
de-scaled. **1049 hermetic tests, 592 database tests.**

### Previously

**`P3-TSK-003` — The operational chart, seeded by migration** — `COMPLETE` (2026-09-13).
**M3.1 closes, 3 of 3.** The platform's own accounts exist before anything can post,
because a double entry needs both sides — and the task's one open design question is
answered explicitly rather than implied by a migration.

| Acceptance criterion | Evidence |
|---|---|
| `resolve(purpose, currency)` answers for every combination | `OperationalChartDatabaseTest`: all fifteen resolve through the application role against a from-scratch database, each row's derivations checked on the way back |
| A missing seed fails the build | Proven twice by one mutation: the removed row fails the hermetic reconciliation **and** the resolve suite — and a gap at run time throws naming purpose and currency, never an empty `Optional` a caller forgets |

### The supported-currency set now exists, and it is a definition rather than an accident

No document defined which currencies the platform operates in — the backlog said *"per
supported currency"* and nothing said which. **`SupportedCurrencies` (EUR, GBP, USD) is the
single definition**, in `ledger`, with the seed derived from it and reconciled in both
directions: a currency added without its seed rows fails the build (proven by mutation), and
seed rows outside the definition fail the stray-row count. Three currencies, deliberately —
one would let per-purpose assumptions creep in unexercised, and the per-currency structure
is the Phase 9 seam — and jurisdiction-neutral, because a national default is a decision
Phase 3 has no business taking. "Supported" means **postable**: every currency here has a
residual account before any allocation posts (`INV-BAL-03`) and a suspense account before
Phase 8 needs one (`INV-REC-05`); `CurrencyCode` still accepts all of ISO 4217, because a
historical row must read back whatever the current list says (`INV-MON-05`).

### The seed's mechanics each close a defect that would have arrived silently

**Ids are hand-minted UUIDv7 literals**: `gen_random_uuid()` is v4, and the first resolve
would have thrown at rehydrate (`LedgerAccountId.of` validates v7 — the `P1-TSK-028` lesson
applied at authoring time, and pinned by a test over every literal's version nibble).
Deterministic ids across environments are a feature for operational accounts — a runbook can
name them. **Timestamps are literals, never `now()`**: a seed whose `created_at` varies per
environment records the deployment schedule, not the chart. **The types are the seed's
decision, recorded and pinned**: clearing ASSET, fees REVENUE, FX position ASSET, residual
EXPENSE, suspense LIABILITY (value we hold that is not ours is value owed to somebody) —
and the mutation that flips one **coherently** (suspense to ASSET/DEBIT, satisfying every
schema `CHECK`) is caught only by the pinned contract, which is why the pin exists.

### One mutation survived, correctly, and the reason is recorded

`findOperational` losing its `owner_ref IS NULL` predicate changes nothing today: the
purpose→owner-kind→owner-ref `CHECK` chain makes an owned row with an operational purpose
unstorable, so the rows the predicate would exclude cannot exist. The predicate stays as
recorded defence in depth — it is the partial index's own, and it is what keeps the answer
right if a future purpose is ever held by more than one kind.

### The seam test arms itself on schedule

*Nothing posts to `FX_POSITION` or `SUSPENSE_UNMATCHED` in Phase 3* is asserted the only
honest way available before a posting table exists: zero journal lines reference the seam
accounts, with the count guarded by `to_regclass` — structurally true today, and **live from
the day `P3-TSK-005` creates the table**, the freeze trigger's own pattern. Probing also
re-met that trigger's lesson in the test itself: PostgreSQL parses a whole statement at
prepare time, so the probe and the count are two statements, never one `CASE` around an
absent name.

**Eight mutations: seven caught by the intended assertion, one survived correctly** — a
seed row removed (caught in both tiers), a stray row added, a currency added with no seed,
the coherent type flip, a v4 id, the owned-purpose guard removed, and the predicate above.
**1040 hermetic tests, 592 database tests.**

### Previously

**`P3-TSK-002` — `LedgerAccount`: typed, single-currency, and unchangeable once posted to**
— `COMPLETE` (2026-09-13). **M3.1 is 2 of 3.** The chart's row exists, and `INV-LED-06`
holds at the database against every writer, including ones nobody has written yet.

| Acceptance criterion | Evidence |
|---|---|
| Reclassifying a posted-to account is refused by the database | `LedgerAccountDatabaseTest`: the migrator — the role the grant does not bind — corrects an *unposted* account's classification (the positive control), a stand-in `journal_line` row lands, and the same coherent update is refused with `check_violation`. The trigger-neutralised mutation is caught |
| A `CHECK` rejects an unknown type | Driven as the migrator with raw SQL, beside two sharper refusals: a known-but-incoherent pair (an `ASSET` that grows by credit) and a platform purpose owned by a customer |
| The enum and the constraint cannot drift | `LedgerAccountMigrationTest` reconciles **seven** generated fragments — five value lists and two coherence rules — each with one definition in code |

### Two derivations, no free choices

`normal_balance` derives from the type (`AccountType.normalBalance()`, the backlog's own
requirement: an invariant constrains only what is stored) — and the survey showed
**`owner_kind` is the same case**: a wallet is a customer's by definition and an FX position
the platform's, so letting a caller pick the pair independently only creates invalid states.
Both are derived in one function, stored, and held to their derivation by a generated
`CHECK` (`sqlNormalBalanceRule()`, `sqlOwnerKindRule()`) — so a writer that never ran our
code cannot store an `ASSET` that grows by credit or hand the platform's fee revenue to a
customer.

### The freeze has two layers, and the second waits for a table that does not exist

The identity fields — purpose, currency, owner, creation instant — are frozen
**unconditionally**, stricter than the task's letter and recorded in the migration: an
unposted account with the wrong currency is corrected by closing it and opening another,
never by editing it. The **classification** (type, normal balance) freezes exactly when a
`ledger.journal_line` references the account — `INV-LED-06` as stated. That table is
`P3-TSK-005`'s, so the trigger probes it through `to_regclass()` and `EXECUTE`: absent, the
account is vacuously unposted and the branch sleeps; created, the branch goes live with no
migration touching the function. Deferring the trigger to `P3-TSK-005` instead would leave a
window where the schema permits exactly what the invariant forbids. The demonstration
creates the stand-in table as the migrator and drops it in `finally`; `P3-TSK-005` must
re-prove the trigger against the real table, and its backlog tests say so.

### The grant and the trigger are blind in different directions

The application role holds `SELECT, INSERT` and `UPDATE (status, status_changed_at)` —
proven per column with a positive control, and the widened-grant mutation is caught. The
trigger is for the writers the grant does not bind: the migrator, an operator, a tool. Two
controls, one invariant, neither substituting (the `P0-TST-007` lesson).

### Only owned accounts are constructible in code

The one factory is `LedgerAccount.owned`; the operational chart is seeded **by migration**
(`P3-TSK-003`), a reviewed platform artefact — the consent-text reasoning — so
`OPERATIONAL`/`SUSPENSE` rows reach Java only through `rehydrate`, and `createOrConverge`
refuses an unowned aggregate outright. Ten instances creating one owned account produce one
row with nine converged (the `openOrConverge` idiom behind a savepoint); a second partial
unique index gives the operational chart one account per purpose and currency, because the
`ChartOfAccounts` lookup must resolve to *an* account and `ROUNDING_RESIDUAL` must stay
**designated** (`INV-BAL-03`). The store is deliberately two methods — no `findById`, no
status move, no operational create — each absence naming the task whose design earns it.

**`OwnershipIsScopedTest` demanded no entry, verified rather than assumed**: neither store
method carries an `EntityId` parameter, so there is nothing for the detector to classify —
`P3-TSK-014`'s status move will be the first, and the guard will refuse it until classified.

**Six mutations, all caught by the intended assertion** — the derivation inverted, the
aggregate's transition check removed, the coherence `CHECK` dropped, the freeze trigger's
posted-probe neutralised, the unique index dropped, the grant widened.
**1035 hermetic tests, 588 database tests.**

### Previously

**`P3-TSK-001` — The `ledger` module, its schema, and the privilege floor** — `COMPLETE`
(2026-09-13). **Phase 3 is `IN_PROGRESS`; M3.1 is 1 of 3.** No money yet, and none intended:
the deliverable is the thing every later `DB-PRIVILEGE` claim in the phase rests on.

| Acceptance criterion | Evidence |
|---|---|
| `build databaseTest` green with the module present | 1027 hermetic tests, 584 database tests |
| A planted `double` in `ledger` fails the floating-point rules | **Probe not performed** (owner's direction). Coverage evidenced instead: the app-level sweep reached `LedgerAuditAction` — the registry test failed on its row — through the derived module set the floating-point rules also analyse |
| A cross-module dependency fails the isolation test | **Probe not performed.** `ledger` forbids all four siblings and `app`, all four siblings forbid `ledger`, and both `LedgerModuleIsolationTest` tests pass |
| Migration applies to an empty database, validates, re-applies | Throwaway PostgreSQL: migrate → validate → migrate, one history row; owner `finapp_migrator`, ACL `finapp_app=U`, no `PUBLIC`, zero tables; `ColumnClassificationTest` green over the new schema |

### The owner is the control, and the schema came first for that reason

`INV-LED-03`, `INV-HIST-01` and `INV-LED-04` are all enforced at `DB-PRIVILEGE`: the
application role will hold `INSERT` and `SELECT` on the journal tables and nothing else. A
privilege is a control only when the objects belong to a role that cannot bypass it, so the
migrator owns the schema, the application gets `USAGE` and nothing more, and — deliberately —
**no `ALTER DEFAULT PRIVILEGES`**: a default would hand every future table here the same set,
and the tables that matter most in this schema are exactly the ones that must *not* receive
`UPDATE` or `DELETE`. Each table's grants arrive in the migration that creates it, where a
reviewer reads them beside the table.

### Two audit actions, and the one that requires a reason is the invariant speaking

`ledger.JournalEntryPosted` (no reason — a posting is commanded by a flow whose own records
carry the why, and `INV-LED-05` already makes the entry attributable) and
`ledger.AdjustmentPosted` (**reason required**, because `INV-REV-04` says so in as many
words). Holds, reversals and account creation are absent on purpose — their designs belong to
their tasks. Both are in `NOT_YET_EMITTED` naming `P3-TSK-006` and `P3-TSK-017`. The posting
action shares its code with the event the same posting will publish, on the
`identity.AuthenticationSucceeded` precedent: one fact, named once, in two registries.

**The registry caught my own catalogue row**: `ledger.AdjustmentPosted` was written `Yes`
where the convention is `**Yes**`, and `AuditableActionRegistryTest`'s reason-required
reconciliation failed the first battery. The code and the document disagreed about the one
action whose reason the catalogue mandates, and the build refused it before anyone could.
**The one-line fix was not re-run, and the four mutation probes were not performed** — the
owner stopped that run and closed the gate without it; the next full battery confirms the fix.

### Isolation both ways, applied at design time

`P2-TSK-003`'s gate found sibling isolation had silently become one-directional. This task did
not wait to rediscover it: `ledger` forbids all four business siblings and `app`, and `party`,
`identity`, `kyc` and `consent` each forbid `ledger`.
For this module the edge matters more than for any before it: a ledger that depends on the
modules that command it is the first step to accounting rules computed against someone else's
model (`INV-LED-04`).

Housekeeping recorded rather than absorbed: the `build-logic` lockfile Kotlin RC3→GA drift met a
third time and reverted; `ledger/gradle.lockfile` byte-identical to `consent`'s; the
verification metadata unchanged. Two comments that had said *"no business modules exist yet"*
for five phases, and §Active Work, which still named `P2-TSK-001` as next, are corrected.

### Previously

**Phase 2 → Phase 3 transition** — **CONDUCTED** (2026-09-13).
[`reviews/PHASE_2_TO_3_TRANSITION.md`](reviews/PHASE_2_TO_3_TRANSITION.md)

| Part | Outcome |
|---|---|
| Phase 2 completion audit, 16 categories | **16 `PASS`** |
| Multi-instance audit | **`PASS`** — every contended decision arbitrated by PostgreSQL and raced in a test |
| Architecture audit | No drift in the architecture; **one decay in the governance record**, repaired |
| Security and privacy audit | `PASS`, with five limits stated and owned |
| Testing audit | 1025 hermetic / 584 database / 14 kafka / 147 architecture, green on a fresh run |
| Phase 2 verdict | **`COMPLETE`** (confirming `P2-DOC-001`) |
| Phase 3 entry gate | **All twelve criteria hold → `READY`** |

### The transition's own finding: an enforced exemption set with no Phase 2 entries

`DISTRIBUTED_EXECUTION.md` §3's component register ended at `SessionRevocation` — Phase 1 —
while Phase 2 introduced **eleven** pieces of shared state. That matters more than an incomplete
table, because §3 is **an enforced exemption set**: ADR-0024's build rules permit process-local
state only where this register names it, so an absent row is a component whose next author finds
no precedent to extend and no recorded reason why none is needed.

Repaired, with the note the audit actually earned: **Phase 2 introduced no coordination
primitive of its own.** Every one of the eleven is one of four protocols Phase 0 already proved
— a unique index as arbiter, a conditional `UPDATE` whose row count is the outcome, a
privilege that makes a write impossible, and lock-then-look where a snapshot cannot be trusted.
**Third occurrence of this class** (`P0-TSK-032`'s review, then four more at the last
transition), and the pattern is now explicit enough to name: *a register maintained by discipline
decays at exactly the boundaries where nobody is looking — which is why the ones with build
guards behind them have not.*

### Four decisions taken, because Phase 3 cannot start without them

Each is irreversible once postings exist, which is the whole reason a transition takes them
before the first one is written. Two are worth reading twice because the obvious answer is the
wrong one.

**`SERIALIZABLE` was rejected** (ADR-0039), which reads as the less safe choice and is not: it
would put a retry loop around every money-moving command, and that is exactly where *"the
database committed but the response was lost"* becomes two effects. Postings are **inserts**, so
under `READ COMMITTED` there is no lost update to have; the operations that genuinely need mutual
exclusion — a hold, an overdraft check — are a small enumerable set, and they take
`SELECT … FOR UPDATE` on the **account row**, stating the requirement at the site that has
it.

**The balance projection may not back a hold** (ADR-0041). An asynchronous projector is the
standard answer and buys a lag that `INV-BAL-05` then forces us to bound, monitor and exclude
from every decision path — three mechanisms and a metric to avoid one `UPDATE` in an
already-open transaction. So the projection updates **in the posting's own transaction** and
serves display only; a decision derives from the postings inside the lock. The cost is stated
rather than hidden: postings contend on their accounts' projection rows, and the
operational-account hot row has a recorded mitigation.

The other two: a **flat, typed chart** with roll-up by attribute rather than a hierarchy
(ADR-0040 — a tree buys roll-up by ancestry and costs recursive queries on the reporting
path, a second disagreeable source of classification, and re-parenting that `INV-LED-06` forbids
anyway), and **four account concepts kept apart** (ADR-0042, ADR-0029's test applied one layer
down: four cases a collapsed model cannot represent).

### No new invariant group, and that is the difference from the last two transitions

Phase 0 → 1 created `INV-IDN` and Phase 1 → 2 created `INV-KYC`/`INV-CNS`, both because
those phases' properties existed only as gate prose. **Phase 3 needs none**: `INV-LED`,
`INV-BAL`, `INV-REV`, `INV-CON-01`, `INV-ACC-01` and `INV-HIST-01` were catalogued at project
initiation, because Phase 3 is what the catalogue was written for. The platform stays at **82
invariants**. What Phase 3 inherits instead is the exit review's finding, stated in the plan
where it will be read: **the in-scope set is whatever `FINANCIAL_INVARIANTS.md` marks
`Phase: 3`**, never what the plan remembers creating.

### Previously

**`P2-DOC-001` — the Phase 2 exit review** — `COMPLETE` (2026-09-13). **The gate passes
and Phase 2 is `COMPLETE`** ([`reviews/PHASE_2_REVIEW.md`](reviews/PHASE_2_REVIEW.md)).

| | Outcome |
|---|---|
| Review areas (8) | **7 `PASS`, 1 `NOT APPLICABLE`** — area 2 has no subject and says so |
| Universal criteria (12) | **12 `PASS`** |
| Financial supplement (F1–F8) | **Not applicable** — Phase 2 moves no money; stated rather than skipped |
| Phase 2-specific (6) | **6 `PASS`** |
| *"Correct with 10 concurrent instances?"* | **`PASS`** — eight contended decisions, each arbitrated by PostgreSQL and each raced in a test |
| **Verdict** | **Phase 2 `COMPLETE` (2026-09-13)** |

### The flip found an invariant nobody had counted, and that is the review's best finding

**Phase 2 has eleven invariants, not ten.** `INV-HIST-02` — *external evidence is retained
verbatim* — is `Phase: 2 (screening), 5 (providers), 8 (files)` in the catalogue, so it has
belonged to this phase since the transition wrote it, and it sits in neither of the two groups
the transition created. **Nothing found it for four days**: not the plan, not the transition,
not the eighteen tasks that ran mutation sweeps, and not this review's own area-6 assessment,
which read the two groups, counted nine rows against ten invariants and concluded the set was
complete but for `INV-KYC-06`.

What found it was **recording the phase `COMPLETE`**. `MutationDemonstrationTest` derives its
demanded set from the catalogue rather than from any phase document, and the battery failed
naming exactly one missing element. That is `P1-TSK-024`'s finding repeating — *"there are
nine Phase 1 invariants, because `INV-AUD-03` is `Phase: 1 onward` and is not in the `INV-IDN`
group at all"* — one phase later, in the same shape: **a group is a convenience for readers;
it is not the set.**

The property was never unprotected: evidence has been retained verbatim since `P2-TSK-009`, the
append-only grant has been at `DB-PRIVILEGE` since that migration, and both owning tasks caught
the evidence-dropped mutation in their own sweeps. What was missing was the *record* that the
test has teeth — the exact gap the register exists to close, since an invariant with a test
and no demonstration is indistinguishable from one whose test cannot fail. Row landed,
demonstration **performed** (the run path's evidence append dropped is caught by *"a clean run
takes the case to APPROVED with retained evidence"*), battery green.

### The review closed three criteria rather than waiving them

**Criterion 3** wanted register rows for **two** invariants. `INV-KYC-06`'s was deferred *in
writing* by `P2-TST-001` to this review, so landing it was declared scope rather than an invented
fix; `INV-HIST-02`'s was missed by everyone, above. Both were **performed, not inferred**: the
audit write dropped from `DocumentAccess.read` is caught by exactly the intended assertion, and
content persisted in the clear by the `information_schema` sweep — the `P0-TSK-038` review's
own finding (it found a row whose *observed* column had been reasoned to) applied to the last
rows of the set.

**Criterion 8** found **four** drifts — two by hand-diffing the plan against the
implementation, the method that found the real defects in both prior reviews, and two more
produced by the review's own ADR acceptance: the **ADR index keeps a second copy of every
status** (all four still read `Proposed` after the files said `Accepted`), and
**`DECISIONS.md` indexed ADR-0001…0034 and none of Phase 2's four** — the document whose
job is to answer *"what has this platform decided?"* silently omitted an entire phase. Both are
one mechanism: a fact about an ADR written in three places with nothing reconciling them, and
deriving the index from the ADR files is carried to the transition rather than built mid-gate. The sharper one: `PHASE_2_PLAN.md` §11
still promised an event reaching a consumer **"exactly once per fact"** — a guarantee
`P2-TSK-001`'s design explicitly refused, because the `EventPublisher` port's own javadoc refuses
it and **an adapter claiming exactly-once invites consumers to skip their inbox**. The
correction had landed in the backlog and in this document and never in the plan. The second: the
same table named M2.6 *"Phase review"* holding one item, so the plan **specified its own
observability in §10 and then omitted it from the only milestone that could deliver it**.

### A counting discrepancy, recorded rather than silently resolved

This document's M2.3 block says *"3 of 3"*, counting the milestone's numbered tasks; `BACKLOG.md`
groups **four** items under the M2.3 epic, `P2-TST-001` among them. Neither is wrong about
anything real and the totals reconcile at 23 either way — but two documents counting one
milestone differently is the drift class `P1-DOC-001` found inside its own tables, so the
convention is now stated where it is used.

### Everything counted, and one of this review's own numbers was wrong first

The mutation total was written as *"95 across 23 tasks"* from memory and is in fact **135**
mutations, probes and demonstrations across the twenty-one tasks that performed them — caught
by counting the change log rather than trusting the draft, which is `P1-DOC-001`'s finding
(three of its numbers were wrong for being inherited) arriving inside the review that cites it.
**Three mutations survived across the phase**, each producing a finding: the converged-guard
whose real subject was a *distinct* event rather than a wire duplicate (`P2-TSK-007`), the
reviewer-door re-route no test exercised (`P2-TSK-015`), and the at-least-one-owner check the
no-500 sweep could never reach (`P2-TSK-016`). A fourth is recorded as **correctly** surviving:
a refusal-only consent cache, which `INV-CNS-03` says nothing about.

### ADR-0035…0038 accepted, on the precedent that criterion 10 is a precondition

All four are implemented, tested and load-bearing — KYC owning the decision with Party
projecting it, evidence and documents verbatim-and-encrypted behind a port, consent as an
append-only history, and a provider verdict as evidence resolved by a person rather than by
silence. Holding them at `Proposed` because some other criterion was open would be theatre; the
decisions were taken and built.

### Previously

**`P2-TSK-020` — The six planned meters, eagerly registered** — `COMPLETE` (2026-09-13).
**M2.6 is 1 of 2; only the phase review remains.** `PHASE_2_PLAN.md` §10's table is real: a
freshly started instance with nothing configured — no database reachable, no provider
endpoint — publishes every series, which is `P1-TSK-029`'s rule and the state the phase
review will find already guarded.

| Acceptance criterion | Evidence |
|---|---|
| A freshly started instance publishes every §10 series | The pinned test in `PlannedMetersExistTest`: Phase 2's §10 table parsed and held against the plain context — the exact "freshly started instance" the criterion names — plus `MetricConventionTest` green over the new names and tags |
| `PlannedMetersExistTest` will hold them from the day the phase completes | The same check, performed early: the derived guard keys on phases recorded `COMPLETE` deliberately, so the pinned copy is what holds the six between now and the flip, and a harmless second reading of the same table after it |
| Dashboard queries resolve | A new *KYC and consent — verification flow* row (five panels); `DashboardQueriesResolveTest` green against a live scrape, and the renamed-series mutation caught |

### The survey found three meters already built — and two of them behind a condition

`finapp.kyc.check`, `finapp.kyc.review.queue` and `finapp.kyc.provider.latency` predate this
task (`P2-TSK-009`/`-010`/`-011`) — but the first and third were registered only inside
`@ConditionalOnProperty("finapp.kyc.provider.url")` beans, so the exact context the guards
boot published neither: **the `P1-TSK-029` defect wearing a condition**. An instance without
a provider endpoint owes a healthy zero, not an absence that looks like a quiet system.
Closed with one definition (`KycMeters`) that the conditional owners build through and
`KycMetrics` — unconditional — registers a second time at startup: registration is idempotent
for an identical name and tag set, and the single definition is what makes the double
registration drift-proof.

### `finapp.kyc.case` counts at the store seam, not per door

Cases are created through **three** doors and decided through **two**, and every one goes
through the one `kycCaseStore` bean — so a `MeteredKycCaseStore` decorator counts there:
`opened` when `openOrConverge` answers `created` (a converged retry, a duplicate delivery and
the losers of a race are never throughput — `INV-KYC-03`'s discipline at the meter), and
`approved`/`rejected` when a move into a terminal status **wins** (the conditional's row count
already makes exactly one of N deciders the winner, so one decision is one increment
whichever door recorded it). Tag values are derived from the machine — `opened` plus each
terminal status — so a new terminal state registers its own series. Five per-door increment
sites, one of them in a module with no metrics dependency, was the alternative; a sixth door
added later would have been a count silently lost.

### The `purpose` tag, and the widening the allow-list exists to force

`finapp.consent.grant` and `finapp.consent.withdrawal` are counted in `ConsentService`,
eager per purpose, incremented **after the commit and only for the recorded act** — a refused
grant increments nothing, because no act occurred (the audit rule, applied to the meter).
`MetricNames.ALLOWED_TAG_KEYS` gains `purpose`: the designed edit-forces-decision path,
bounded by the closed public `ConsentPurpose` enum, which names a category of processing
shared by everyone and can never name a person or a resource. The `P1-TSK-029` refusal of
`stage` does not transfer — there, two meters carried the signal and no widening was needed;
here the plan's own table names these two meters "by purpose", and a per-purpose name split
would invent series the plan does not carry.

### The series nothing ever increments is the eagerness probe

Name-level guards cannot see per-tag eagerness: a registration that quietly became lazy or
per-acted-purpose keeps every meter NAME alive while a series vanishes. So the control is the
series no suite ever increments — `finapp.consent.withdrawal{purpose=screening}` — asserted
to exist anyway, and the mutation registering only the acted purpose is caught by it.

### One harness finding, kept for the next sweep

The mutation harness first matched intended-assertion names against Gradle's failure lines by
**method name**, and Gradle prints `@DisplayName` — so four genuinely-intended catches were
labelled "not by the intended assertion" until the expectations were rewritten to display-name
substrings. The verdicts were read from the failure lines rather than trusted.

**Seven mutations, all caught by the intended assertion** — a converged open counted as
throughput, the decorator un-wired from the bean, the unconditional registration removed, a
non-terminal move counted as a decision, a refused grant counted, a dashboard series renamed,
and the per-purpose registration made incomplete.
**1025 hermetic tests, 584 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-006` — `POST /v1/me/kyc` and `GET /v1/me/kyc`** — `COMPLETE` (2026-09-13).
**M2.2 closes, 7 of 7 — and with it every implementation milestone of the phase.** The
customer's half of onboarding: the caller ensures their own case exists and reads its status,
no identifier anywhere in either request — and opening is the first consent-gated capability
over HTTP, so the gate's refusal earned its error code here.

| Acceptance criterion | Evidence |
|---|---|
| A registered, consented person reaches an open case | `KycCaseEndpointDatabaseTest`: grant over HTTP → POST → 201 `{"status":"OPEN"}`, one case row, audited **as the person** (actor the identity, proven not `system`), announced once |
| A second POST is the same case | 201 again, same body; still one case, one record, one announcement — creation is distinguished by the records, never the answer |
| Consent-absent refusal | `409 consent.ConsentRequired`, nothing written — the exception rolls the transaction back, so the refusal writes nothing *structurally* |
| The hit-invisibility assertion | A case in `IN_REVIEW` and a case in `CHECKS_IN_PROGRESS` answer **byte-identically** (`{"status":"IN_PROGRESS"}`), with no review vocabulary anywhere |

### The gate's refusal earned its code, and the mapping is global on purpose

`consent.ConsentRequired` (409, actionable: grant and retry) joins the catalogue as the third
consent code — **one code for three causes, deliberately**: no history, a latest withdrawal
and a grant lapsed by a re-consent-demanding version are indistinguishable to every caller
(`INV-CNS-01`), so the code names the remedy and never the cause — proven at the surface as
an **equality between the causes**: the never-granted person's refusal and the withdrawn
person's are byte-identical with only the correlation identifier excluded. The mapping lives
in `ApiErrorHandler` (the `IdempotencyConflictException` shape) rather than in the
controller, so every later consent-gated surface answers with the same code for free.

### Two extractions, each earned by a second caller arriving — neither invented for one

The tipping-off shaping moved out of `KybController` into `CustomerFacingCaseStatus`: one
definition of a security-control mapping, because a control copied per controller is one that
drifts in exactly one of its copies — and the exhaustive `switch` makes a new case status a
compile failure until somebody decides which side of the disclosure line it sits on. And the
record-and-announce block moved out of `CustomerOpenedOpensCase` into `CaseOpeningTrail` (the
`CheckOutcomeTrail` rule, `P2-TSK-011`) — with one deliberate difference from that precedent:
the **actor stays the door's own**. A check outcome is the platform's act through every door;
a case opening is not — the consumer door is the platform's policy act (its enumerated
`enterSystem()` site unchanged) while the endpoint door is the **person's own act** under the
interceptor's proven scope, and the mutation wrapping this door's record in `enterSystem()`
is caught by the actor assertion.

### The contract classifier caught a real breaking change

A second handler method named `view` had springdoc rename the KYB surface's **published**
`GET /v1/me/kyb` operationId to `view_1` — a breaking rename of an existing operation, on an
endpoint this task never touched. Flagged `BREAKING`, and **withdrawn rather than accepted**:
the new method is `viewCase`, KYB keeps `view`, and the accepted baseline is 75 added lines,
zero removed, all `COMPATIBLE`.

### What a decided customer's POST means, stated rather than discovered

`openOrConverge` converges only while a case is open; a decision frees the one-open-case
slot (`P2-TSK-005`'s demonstrated freed slot), so a decided customer's POST opens a
**successor** case — changed-circumstances re-verification is a new case by design
(`INV-LIFE-04`), gated by consent exactly like the first, and the read then answers for the
latest. Tested, not implied. The GET is deliberately **not** consent-gated: reading the
status of one's own case is a mirror of processing, and the processing is what consent
governs.

### Three small findings on the way

The status-move fixture met the **container clock drift** (`P1-TSK-031`'s shape, met again):
a case's `opened_at` is the service's JVM clock and the fixture's `now()` the container's,
which runs behind — the constraint that refused it was right, and the fixture pins
`GREATEST(now(), opened_at)`. `KycCaseStore.Opening`'s javadoc claimed *"`POST /v1/me/kyc`
answers 'created' versus 'you already had one'"* — and the built endpoint deliberately does
not (201 both ways; the records distinguish) — corrected before it shipped as the next
member of the phase's recurring javadoc class. And the trail's first version null-checked
the unit of work, breaking the hermetic consumer fixture that drives the door with fakes and
no connection: the unit of work is the caller's, unchecked deliberately.

**Six mutations, all caught by the intended assertion** — the gate call dropped, the
converged path recording too, the shaping leaking `IN_REVIEW`, the platform recorded as the
opener, the refusal mapping removed, the announcement dropped.
**1019 hermetic tests, 584 database tests, 14 kafka tests.**

### Previously

**`P2-TST-002` — Consent withdrawal blocks the capability, across instances** — `COMPLETE`
(2026-09-13). **M2.5 closes.** The four `INV-CNS` register rows landed, and the acceptance was
**performed** — which is what found that the demonstration was proving one level too low and
resting on autocommit.

| Acceptance criterion | Evidence |
|---|---|
| The demonstration fails when the gate's authoritative read is replaced by a cached value — performed, not asserted | Performed: the gate memoised per (party, purpose) is caught by the race **and** by the capability test. Two further probes bound the claim rather than confirming it — see below |
| The register row recorded | `INV-CNS-01`…`04` in §2 and the item's row in §4, each naming its tests by `Class#method`. The guard's teeth re-proven per §5: one method reference corrupted, `everyNamedMethodExists` failed naming exactly it, restored from a backup **copy** and verified byte-identical |
| The gated capability refused on another instance | `ConsentWithdrawalBlocksTheCapabilityDatabaseTest`: the real consumer, real stores, real gate and real writers, opening **nothing** — no case, no audit record, no announcement — after a withdrawal committed on another instance, with a positive control |

### Performing the acceptance changed the test twice

The demonstration shared **one** `ConsentGate` across both simulated instances, so it caught
the cached-read mutation **by accident**: instance A memoises before B withdraws either way.
`SimulatedInstance`'s own rule is never to share the thing whose sharing hides the defect, and
for a process-local cache that thing is the gate — one bean per deployed instance. Giving each
instance its own gate then made the test **fail**, and the reason was the second finding: it
had been resting on **autocommit**, so B's withdrawal had never been a committed fact and the
commit boundary was asserted nowhere. It now straddles the commit, which is the invariant's own
wording — *"from the transaction that records a withdrawal"*: while uncommitted A still permits
(an instance refusing there would be reading dirty), and on A's very next decision after the
commit it refuses. No sleep, no polling, no timing luck.

### And the demonstration was aimed one level below the bullet it serves

It proved the **gate's answer** flipped across instances and left the **capability** to a
composition argument over two green tests — the `P1-TSK-027` shape, where both halves worked,
nothing joined them, and every suite passed. M2.5's bullet names the *capability*, so the
capability is now what gets driven: the real `CustomerOpenedOpensCase`, wired as `KycBeans`
wires it, against a party whose basis instance B had already seen.

### Two probes bound the claim rather than confirming it

**A cache hidden one layer down, in `JdbcConsentStore`, survived the field detector** — a
`Map<String, Boolean>` names no consent type — while the race caught it. So the behavioural
test is the load-bearing control for that shape and `NoProcessLocalConsentStateTest` is the
second, blind in a different direction rather than a duplicate. **A cache that remembers only
refusals survived the race, correctly**: withdrawal still takes effect, and `INV-CNS-03` says
nothing about a stale *refusal*. The symmetric defect is real all the same — a person who has
just granted stays blocked — and it is caught at the other door, by the kafka test whose second
half opens a case for a party refused moments earlier. **Neither test covers a cache alone**,
and §3 records that rather than leaving a reader of either to assume it does.

**1019 hermetic tests, 577 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-019` — The consent gate, and the first capability behind it** — `COMPLETE`
(2026-09-13). `INV-CNS-01` becomes a mechanism: opening a KYC case requires a current
`KYC_PROCESSING` grant, and the milestone's demonstration — withdrawal blocking the
capability across instances — is performed.

| Acceptance criterion | Evidence |
|---|---|
| `P2-TST-002`'s demonstration is possible and performed | `ConsentGateDatabaseTest.withdrawalOnOneInstanceRefusesOnAnother`: two connections standing for two instances (`P0-TST-009`), a withdrawal committed on one, and the other's **very next decision** refuses — not after a TTL, not after a restart |
| Absent, withdrawn and stale bases each refuse | Walked through one party's history so the SAME gate flips exactly when the history says so: absence refuses, a grant permits (and is purpose-scoped), a withdrawal refuses indistinguishably from the absence; a grant lapsed by a seeded re-consent-demanding version refuses on the gate's next decision, with no restart anywhere in between |
| The cache detector | `NoProcessLocalConsentStateTest` — the `NoProcessLocalSessionStateTest` shape for `ConsentRecord`/`ConsentText` retention, with module coverage and fixture teeth, and an **empty** permitted set as the healthy state. Its limit stated: a bare Boolean cache is invisible to it, and the behavioural race is what covers that — proven, because the memoizing-gate mutation was caught by the race |
| `DOD-SEC` | The negatives are the refusals: an unconsented party's event causes **nothing to exist** — no case, no audit record, no announcement, the store not even asked (hermetic), and the same holds end to end through the real broker |

### The gated capability had two doors, and that was the design's crux

The eager registration consumer (`P2-TSK-007`) opens a case for a person who **cannot** yet
hold a grant — a grant needs a session, a session needs the registration the event announces —
and a gate with an ungated second door is not a gate. So the consumer asks too, through a
`kyc` port (`CaseOpeningConsent`, the `CaseKindResolver` shape, because `kyc` cannot see
`consent`) that `app` implements over the new `PartyStore.partyOfCustomer` and the gate. A
refusal is a **skip, never a stall**: acknowledged, logged with correlation, nothing written —
the platform's own correct decision must not get the poison-record treatment, and the case
opens when the consented person acts (`P2-TSK-006`, now unblocked) or eagerly when the party
already holds a basis (consent is the party's fact and outlives any one customer).

### `P2-TSK-007`'s headline changed, and the change is recorded rather than smuggled

*"Registration alone yields exactly one open case"* became *"registration alone opens
nothing — and that refusal is the milestone's acceptance working at the eager door; the same
party consented opens exactly one."* The kafka suite was restructured on consented fixtures,
keeping every property it held (the inbox absorbing duplicates, distinct events converging
silently, the race against the direct open), and its first test drives the whole deployed
chain twice: HTTP registration → relay → broker → consumer → consumed and acknowledged with
**nothing written**, then a grant and a distinct redelivery → exactly one case, audited and
announced.

### The KYB door is deliberately outside the gate

`KybService` opens the organisation's KYB case inside the registration transaction, and it
stays ungated, with the reasoning recorded at the call site: the declared gated capability is
a **person's** KYC case under `KYC_PROCESSING`, whose text covers *"my identity data"* — an
organisation cannot consent, and a lawful-basis regime for organisational verification is a
later phase's decision, not one to smuggle in under a text that does not cover it.

### What deliberately did not arrive

No error code for the gate's refusal (`ConsentNotGrantedException` names the purpose and
nothing more — which of the three causes refused is exactly what `INV-CNS-01` keeps
indistinguishable; the code is `P2-TSK-006`'s, declared by the surface that shapes it), no
events, no meters (`P2-TSK-020`), no second gated capability, and no gating of screening —
nothing in Phase 2 declares it gated.

**Five mutations, all caught** — the consumer opening without asking the gate (caught
hermetically: the store stays untouched, because an open attempted and converged away would
still have been an attempt to process without a basis), the gate permitting everything,
`require` swallowing the refusal, the adapter asking about the **customer** where the party
belongs (caught through the real broker: the grant is the party's, so the wrong key finds no
basis and the case never opens), and the gate memoizing per (party, purpose) — caught by the
cross-instance race, which is `P2-TST-002`'s cached-read demonstration arriving early.
**1019 hermetic tests, 576 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-018` — Consent endpoints** — `COMPLETE` (2026-09-13). The store meets HTTP: the
`/v1/me` shape carries the consent lifecycle, the beans arrive with their first consumer,
and both audit actions get their first emitters.

| Acceptance criterion | Evidence |
|---|---|
| The lifecycle over HTTP with the audit trail naming the person | `ConsentEndpointDatabaseTest`: grant 201 → basis true → withdraw 204 → basis false → re-grant 201 — one audit record per act, actor the **person's** identity (never the platform), each record's target a consent record proven to exist for that party, the grant's summary naming the pinned version and never the words |
| Stale grant refused when the current version demands re-consent | Seeded v2 (`requires_reconsent=true`, the migrator idiom): grant v1 → `409 consent.ReconsentRequired`, **nothing written** — no record and no audit record, because no act occurred; grant v2 → 201. And the conditional honoured rather than over-tightened: a stale version whose successors never demanded re-consent stays grantable, proven beside it |
| Absence vs withdrawal indistinguishable to a caller of the query | Byte-identical `GET` bodies for a never-granted person and a granted-then-withdrawn one — the equality between the causes, over HTTP |
| `DOD-SEC`: a negative test per control | The one control is authentication: three 401s. The sharper claims point the other way — **withdrawal refusable by nothing but authentication** (no prior grant → 204 + a real fact + audited; repeated → the same), and the ten-shape no-500 sweep plus a garbage `{purpose}` on the DELETE, nothing stored by any of them |

### The path variable that is not an identifier

Every other `/v1/me` surface takes no path variable at all, and `DELETE /v1/me/consents/{purpose}`
does — the plan's own route. It stays inside the ownership-by-absence rule because `{purpose}` is
a **closed enum naming a category of processing shared by everyone**: it cannot name a resource,
a person, or anything of anybody else's, so there is still nothing an attacker can point at a
victim. Mechanically the question never even reaches `OwnershipIsScopedTest`: the consent store
takes a raw `UUID`, not an `EntityId` subtype, so the detector demands no register entry —
verified against the detector rather than assumed.

### The grant carries the version the person was shown

The obvious alternative — the server grants against whatever is current — records a consent to
words the platform merely *hopes* the person saw: a client that cached the text yesterday would
silently consent its user to today's revision. So `POST` carries `textVersion`, and the refusal
rule is **the derivation's own clause applied before the fact exists** (`ConsentStore.assessGrant`,
one statement, one snapshot): refused exactly when a later version records `requires_reconsent`,
because recording such a grant would write a "consent" that consents to nothing while the client
walks away believing a basis exists. Two codes joined the catalogue —
`consent.ReconsentRequired` (409, actionable: fetch the current text and re-present) and
`consent.UnknownTextVersion` (422, a client defect the composite FK backs as defence in depth) —
and **deliberately no withdrawal code**, because there is no withdrawal failure for one to name.

### Withdrawal is unrefusable, and that is proven as the property rather than observed

The client supplies no version (the record pins the version current when the person withdrew —
`INV-CNS-04`'s unconditional half, decided server-side), no prior grant is required (honest
history, absence equals withdrawal to every caller), and the append has no losing branch. Both
plausible refusal causes are driven: a withdrawal with no grant before it and a repeated one each
answer 204, append a real fact, and are audited — because each is an act. The mutation making
withdrawal conditional on a prior basis (a 204 silently writing nothing) is caught.

### What the query publishes, and what it deliberately does not

One row per purpose: `granted` — the derivation's answer and nothing more, no "withdrawn at", no
latest action, nothing that would distinguish a withdrawal from an absence — plus the current
text version **and the words themselves**, because the words are what a person consents to and
without them no client can present a grant flow. `consent_text.body` is the platform's first
genuinely PUBLIC column, and this is the publication it was classified for.

### What deliberately did not arrive

No gate (`P2-TSK-019`, where `ConsentGate.require` reads authoritative state per decision), no
meters (`finapp.consent.*` are `P2-TSK-020`'s by plan §10), **no events** — the gate may not read
a cache (`INV-CNS-03`), so nothing would consume one and a consent event would be transport with
no consumer — and no `Idempotency-Key`: not money-moving, and retries append new facts that
converge (ADR-0037), asserted as two rows from two identical POSTs. The contract gained two paths
and two schemas — 178 added lines, zero removed — and `ConsentGrantRequest` joined the
credential-sink pinned set, which the completion battery caught having been missed: the set is
every schema reachable from a request body, and the entry records that the request carries no
secret and no PII.

**Eight mutations, all caught** — the grant audit dropped, the withdrawal audit dropped, the
stale-version refusal dropped, withdrawal made conditional on a prior basis, the audit written as
the platform (`enterSystem` around the write — the person must be the actor, because consent is
the most personal act on the platform), the GET derivation inverted, the unknown-version refusal
dropped (the composite FK answering with our 500 where the boundary owes a 422), and a withdrawal
recorded as a GRANT. One first ran VOID against `-Werror`'s unreferenced-try-resource refusal and
was re-planted with the established `@SuppressWarnings("try")` idiom — a mutation must compile to
prove anything (`P1-TSK-026`'s rule). **1016 hermetic tests, 573 database tests, 14 kafka
tests.**

### Previously

**`P2-TSK-017` — Consent texts and the append-only record** — `COMPLETE` (2026-09-12).
M2.5 opens with ADR-0037 made real: the history IS the store, the current basis is derived,
and both are proven at the privilege level.

| Acceptance criterion | Evidence |
|---|---|
| The history is the store, proven immutable | `ConsentHistoryDatabaseTest`: `UPDATE` denied on **every column** of `consent_record` (the list from `information_schema`, the P0-TST-007 idiom), `DELETE` and `TRUNCATE` denied — and `consent_text` refuses the application role's `INSERT`, `UPDATE` and `DELETE` outright |
| Derivation under interleaved records | grant → withdraw → grant flips the basis each time; **absence equals withdrawal as an equality between the causes** (`INV-CNS-01`'s shape); purpose-scoping proven; ten instances append with no locks and no losers, and the derived answer agrees with the raw highest-`seq` row |
| Version-pinning refused null | `23502` — and the NOT NULL is load-bearing **beside** the composite FK, because SQL lets a NULL slip past a composite FK (proven by the nullability mutation) |
| The order is server-assigned | A grant inserted first (lower `seq`) with a **later** timestamp, committing **last**, loses to the higher-`seq` withdrawal — one held-transaction test killing the clock ordering and the commit-order intuition together |

### Texts are unwritable by the application entirely

The immutability question for `consent_text` had a stronger answer than a grant narrowing:
the application never writes a text at all. A consent text is a reviewed platform artefact,
and a forward-only migration (ADR-0011) is exactly the reviewed, immutable channel such an
artefact arrives through — `V002` seeds v1 for both purposes, a wording change is a **new
version in a new migration**, and `requires_reconsent` is a recorded property of the version
(`INV-CNS-04`), never a guess. The application role holds `SELECT` alone. The record table is
the `audit_record` model — `SELECT, INSERT`, no trigger needed, the privilege IS the
immutability (`INV-CNS-02`).

### The pin is unforgeable twice over

`text_version` is `NOT NULL` on **both** kinds — a withdrawal pins the version current when
the person withdrew, because the invariant's reference is unconditional — and the composite
FK `(purpose, text_version) → consent_text (purpose, version)` is the V008 lesson applied: a
record cannot pin another purpose's text, an artefact the person was never shown. The two are
**independently** load-bearing: SQL's composite-FK semantics skip the check when any column
is NULL, so dropping the NOT NULL lets an unpinned record through the FK — the mutation
proves it, and it is why the schema carries both.

### The order of the history is the server's, and the test holds the race open

`seq BIGINT GENERATED ALWAYS AS IDENTITY`: client-supplied values are refused, so no
instance's clock or counter can decide which of two racing facts is later — the backlog's
`P0-TST-009` citation, honoured in the schema rather than in discipline. The derivation
orders by `seq` and never `recorded_at`, and the test is deterministic rather than timed: one
connection inserts a grant (lower `seq`, **later** `recorded_at`) and holds its transaction
open while another commits a withdrawal (higher `seq`, earlier clock); after the grant
finally commits, the basis is still withdrawn. A derivation ordered by timestamp resurrects
the grant; one confused by commit order does too; both die on one test.

### The sweep met the identity column's own gate

`GENERATED ALWAYS` refuses `UPDATE ... SET seq = seq` before the privilege check ever runs —
a refusal, but the wrong one to rest on, because `seq = DEFAULT` is the one update the
identity mechanism admits, and it would **re-order history**, handing an old withdrawal the
newest position. The per-column sweep probes `seq` with `DEFAULT` so the refusal it asserts
is the grant's.

### What deliberately did not arrive

No bean (nothing consumes the store until `P2-TSK-018` — the P1-TSK-007 unconsumed-wiring
licence), no endpoints, no audit emission (`consent.ConsentGranted`/`ConsentWithdrawn` stay
`NOT_YET_EMITTED` naming 018), no events, no meters (P2-TSK-020's), no consent expiry
(ADR-0037's recorded follow-up). `consent_text.body` is the platform's **first genuinely
PUBLIC column** — the words shown to every customer — which makes the classification
scheme's every-level-used check honest rather than technically satisfied.

**Nine mutations, all caught first time** — UPDATE granted on the record table and INSERT on
texts (both against a from-scratch database), the composite FK dropped, both reads
re-ordered by `recorded_at` separately, the re-consent clause dropped, the GRANT predicate
dropped (a withdrawal deriving a basis), `text_version` made nullable, and `seq` made
`BY DEFAULT` (caught hermetically by the migration reconciliation). **1016 hermetic
tests, 564 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-016` — The KYB endpoints** — `COMPLETE` (2026-09-12). M2.4 closes: the graph
`P2-TSK-015` built is carried over HTTP by the organisation's acting person — and *who may
act for an organisation*, the task's stated design question, is answered at the boundary.

| Acceptance criterion | Evidence |
|---|---|
| The M2.4 milestone criterion end to end | `KybEndpointDatabaseTest.theActingPersonCarriesTheGraphOverHttp`: register (201, the KYB case in the same commit) → declare (audited against the **proven person**) → the gate refuses readiness while the owner is unanswered → the owner's verification answers → readiness → the reviewer decides → the acting person reads `APPROVED` |
| Over HTTP; ownership | The `/v1/me` absence shape, one hop further: Session → Identity → `organisationRegisteredBy` — the statement carries `registrant_party_id = ?` — → `findLatestFor`. Two acting persons' declarations provably land on their own organisations only |
| A stranger cannot declare owners onto another's case | They have nothing to name it with: no endpoint takes a case identifier, and the stranger's own chain resolves to a 404 with nothing written |
| `DOD-SEC`: negative tests for every control | Unauthenticated ×3; the stranger 404; cross-organisation isolation; the uniform owner refusal (four causes, byte-identical); the frozen-set 409; the stake 422; the tipping-off shaping with no "REVIEW" vocabulary in the body; no body shape a 500 |

### The design question was answered by building its missing precondition

**No production path created `ORGANISATION` parties** — `PartyRegistration` hardcodes
`PERSON` — so "the registering identity" had no subject: the `P1-TSK-023` shape, a missing
precondition of the requirement rather than a missing endpoint. `POST /v1/me/organisations`
is the minimum that gives it one: a logged-in person registers the organisation they act
for, and party `V006` records their Party as its one registrant under a **total**
`UNIQUE (registrant_party_id)` — the Phase 2 scope bound (one organisation per person,
ever), the concurrency arbiter (ten racing registrations, one row) and the convergence key,
one index. The table is append-only; delegation, multiple representatives and registrant
replacement are recorded as Phase 6+.

### Convergence replays the original 201, and the gate corrected my 200

Registration converges — a same-name repeat is one intent — and my first design answered
the repeat with a 200 beside the creation's 201. **The generated contract said otherwise**:
springdoc published `"200"` alone for a `ResponseEntity` handler (the `P1-TSK-006` trap,
found again by reading the document), and the honest fix was not machinery but alignment —
the platform's convergence idiom (`P2-TSK-008`'s documents endpoint, the idempotency replay
itself) already answers a converged retry with the **original** status. One 201, however
many times the request arrived; what distinguishes creation is the records — only the
creating path audits (`party.OrganisationRegistered`, actor the proven person, never
`enterSystem()`) and publishes, asserted as **one record however many arrivals**. A
different name is `409 api.Conflict` (`INV-IDEM-03`'s shape), the stored name never echoed.
The KYB case opens **inside the registration transaction**, so the acting person's first
view has a case to show.

### One refusal for third parties; specific refusals for your own graph

`ownerPartyId` names a third party, so unknown, organisation, unregistered and **malformed**
are one byte-identical `422 kyc.OwnerNotEligible` — a split would make the declaration
endpoint an oracle over other people's registrations (`INV-IDN-07`'s reasoning applied to a
body field; malformed-equals-absent, `P1-TSK-016`). The caller's own graph stays specific,
because specificity there discloses nothing and each refusal calls for different behaviour:
`kyc.OwnerAlreadyDeclared` (409, append-only honesty), `kyc.CaseNotAcceptingOwners` (409,
the frozen set), `kyc.StakeExceedsWhole` (422). The at-least-one-qualification invariant is
told at the boundary as a 422 rather than thrown from the aggregate as a 500.

### The shaped view is the control, and the reviewer sees everything

The acting person's view collapses `CHECKS_IN_PROGRESS` **and** `IN_REVIEW` to
`IN_PROGRESS` — which of the two a case is in is exactly what tipping-off forbids
disclosing (plan §6) — and an owner's verification appears only as a `verificationPending`
boolean: never its status, outcome or case identifier. The reviewer's case file gains the
graph **whole** (verification case id, real status), because the owner rows are the KYB
decision's evidence (`INV-KYC-02`) and the person who must defend the decision cannot
defend evidence they cannot see. `ownersOf` serves the status raw; shaping is the caller's.

### The sweep's survivor found a test naming the wrong party

The mutation dropping the at-least-one boundary check **survived**: the no-500 sweep's
no-qualification shape named an *unknown* party, so eligibility refused it with the uniform
422 before the aggregate was ever constructed, and the boundary check was exercised by
nothing. Re-aimed at an **eligible** owner — the only kind that can reach the aggregate's
`IllegalArgumentException` — the mutation is caught, and the test now says why the shape
must name a real person.

**Nine mutations, eight caught first time and the ninth after its survivor strengthened the
suite** — the name-conflict check dropped (silent convergence), the KYB case opening dropped
from the registration transaction, the uniform refusal split, the shaping dropped
(`IN_REVIEW` leaking), the owners dropped from the reviewer's file, the ownership predicate
neutralised in `organisationRegisteredBy` (caught by the cross-organisation isolation test),
the registration audit dropped (caught by the one-record assertion), the lost race erroring
instead of converging, and the boundary check above. **1006 hermetic tests, 557
database tests, 14 kafka tests.**

### Previously

**`P2-TSK-015` — KybCase and the beneficial-ownership graph** — `COMPLETE` (2026-09-12).
M2.4 opens with the phase's namesake capability: an `ORGANISATION` customer's case is a KYB
case, and what makes it *"not a KYC Case with a flag set"* (the glossary's own words) is the
graph — `kyc.beneficial_owner` rows linking the case to natural-person Parties, each pinned
to that person's **own KYC case** as its verification, with the decision gated on every
owner's answer being terminal.

| Acceptance criterion | Evidence |
|---|---|
| An organisation decides only on a fully verified ownership graph | `KybCaseDatabaseTest.theGraphGatesTheDecision`, end to end: the gate holds while the owner's case is undecided; the owner's all-clear re-routes the parent to `READY_FOR_DECISION` undecided; the reviewer decides; the organisation's customer goes `ACTIVE` |
| An unverified owner blocks `READY_FOR_DECISION` | The gate test, plus `anEmptyGraphIsNotAVerifiedGraph` — a KYB case with **no** owner rows is not vacuously verified, because the gate demands at least one owner AND no non-terminal verification |
| An owner added during review re-routes | `anOwnerAddedDuringReviewReRoutes` — the case leaves `IN_REVIEW` only once the late owner's verification is terminal |
| Graph termination, bounded and recorded | Depth 1 **structurally**: composite FKs admit only KYC-kind cases as verifications and only KYB-kind cases as owners' parents, and `anOrganisationOwnerIsRefused` / `anUnverifiableOwnerIsRefused` prove the two recorded Phase-2 bounds at the orchestration |
| The declaration racing readiness is seen | `aDeclarationRacingReadinessIsSeen`, deterministic: the declarer holds the case lock uncommitted, the mover is **observed blocked** in `pg_stat_activity`, and on release refuses — both interleavings |

### One case machine, two kinds — and the kind is unwritable

`kyc_case.case_kind` (`KYC`|`KYB`), fixed at open by the `CaseKindResolver` port (`app`
implements it over `PartyStore.kindOfCustomer`, because `kyc` cannot see `party`), and
**immutable at DB-PRIVILEGE**: V008 revokes the table-wide `UPDATE` and re-grants exactly
`(status, status_changed_at)` — the V004/V005 column-narrowing, applied here because a
KYB→KYC flip is the one write that would disarm the ownership gate silently. A second table
was refused: the machine, the checks, the review surface and the decision are one lifecycle,
and the plan says so — one machine; KYB adds the ownership precondition to decisioning.

### The write-skew the backlog's own sentence could not close

The backlog required the readiness check as *"a predicate in the transition statement, not a
read-then-act"* — necessary and not sufficient. The declaration INSERTs into
`beneficial_owner` while readiness UPDATEs `kyc_case` with `NOT EXISTS` subqueries —
different rows, so no write-write conflict — and under READ COMMITTED a blocked UPDATE's
re-check re-runs its **subqueries against the statement's original snapshot**: a just-committed
owner is invisible, and an organisation could be decided over a graph it had just grown.
Closed by **lock-then-look on both sides**: declaration and mover each take
`SELECT … FOR UPDATE` on the case row first, then act in a fresh statement whose snapshot
postdates the lock grant. The predicate stays in the statement; the lock is what makes its
snapshot trustworthy. Proven deterministically, not by timing luck: the two-connection test
observes the mover Lock-waiting in `pg_stat_activity` (the `P0-TST-004` idiom — with the JDBC
lesson that the placeholder renders as `$1`, so the filter matches the statement's shape).

### Readiness is ANSWERED, not APPROVED — and KYB never auto-decides

The gate demands every owner's verification **terminal either way**: a REJECTED owner still
answers the question, and it is precisely then that an automatic approval must be impossible —
so `KycDecision.automatic` refuses the kind at the domain (`INV-LIFE-02`'s
rejected-by-the-aggregate, beside the orchestration's own skip), because an organisation's
decision is a reviewer's judgement over its ownership graph (`INV-KYC-04`'s shape). The
frozen set is the other half of `INV-KYC-02`: declarations are accepted only in
OPEN/CHECKS_IN_PROGRESS/IN_REVIEW, checked under the same lock, and owner rows are
append-only (`SELECT, INSERT` grants, proven per column) — so once a case is
`READY_FOR_DECISION` its owner set can never change, the decision move needs no re-predicate,
and **the owner rows of the case ARE the decision's evidence**, with no join table to drift.

### The re-route is the third readiness event

A parent's readiness can now change when **somebody else's case** decides. An owner's
terminal decision re-attempts the parents' readiness post-commit through both doors:
`CaseAssessment.assess` re-routes after a CLEAR_TO_PROCEED commit, and
`DecisionRecording.byReviewer` re-routes after `RECORDED` **and** `ALREADY_DECIDED` — the
`P2-TSK-012` 409-path-heals shape, so a crash between an owner's decision and its re-route
is healed by the reviewer's retried request. Recursion is structurally depth-1 (only
KYC-kind cases are verifications, and they carry no owners), and the recording reaches the
assessment through an `ObjectProvider`, because `CaseAssessment` is provider-URL-conditional
while recording a reviewer's decision is not — in a deployment with no providers, no check
ever runs and no parent can be waiting.

### The sweep's one survivor moved a hook off the HTTP boundary

The reviewer-door re-route first lived in `ReviewController.decide`, and the mutation
removing it **survived**: both re-route tests drove the assess door — the owner decided
automatically — so the controller's call was exercised by nothing, and a KYB parent waiting
on an owner whose verification went to a person would have stalled until some unrelated
re-assessment healed it. The fix is a **move rather than a test for the controller**: the
hook now sits on `DecisionRecording.byReviewer` itself, post-commit, because the recording
already has a second caller (this very suite) and a consequence that lives only on the HTTP
boundary is one a second caller silently loses — the platform's
every-aggregate-meets-a-second-caller rule, applied to an orchestration.
`anOwnerDecidedByAReviewerReRoutes` drives an owner decided by a reviewer through the
recording, and the re-aimed mutation is caught.

### Two Phase-2 bounds, recorded rather than hidden

An `ORGANISATION` owner is refused — the depth-1 recursion bound, enforced by the composite
FK as well as the orchestration — and an owner who is not a registered customer with a case
is refused (`OWNER_NOT_VERIFIABLE`): declaration never auto-opens cases. Both are honest
narrowings of the glossary's *"recursive graph"*, recorded in the javadoc and here with the
`P1-TSK-005` tension acknowledged: full recursion arrives when a real KYB regime demands it,
as its own decision.

### The completion battery found one real interaction, and it was the designed loudness

The full battery's kafka tier failed 3 of 4 `RegistrationOpensCaseKafkaTest` tests: the race
test's crafted `party.CustomerOpened` named a customer **with no rows**, which the consumer's
new kind resolution refuses loudly — and a throwing handler **stalls the partition** by the
block-don't-skip design, timing out every test scheduled after it. In production the event
commits in the customer's own transaction, so a rowless customer is a broken invariant and
the stall is the poison-record behaviour working; the fixture now inserts the party and
customer rows directly (no registration, so no third opener), keeping the race's subject
intact. The masked exit code that nearly hid it (`| tail` reporting the pipeline's tail) is
the second time this session; the battery is now read by grepping for `BUILD FAILED`.

**Ten mutations, all caught by the intended assertion** — the ownership gate neutralised in
the mover, the mover's lock dropped (caught by the race test's blocked-observation
precondition — the deterministic protocol assertion doing exactly what an outcome assertion
could not), the at-least-one-owner clause neutralised, the KYB refusal dropped from the
automatic policy (hermetic), the accepting-status predicate dropped, the verification-kind
composite FK dropped from V008 (caught against a from-scratch database), the declaration
audit dropped, the stake-sum bound removed, and the re-route dropped from each door
separately — nine caught first time, and the reviewer door's **survivor** is the finding
above. **1006 hermetic tests, 546 database tests, 14 kafka tests.**

### Previously

**`P2-TST-001` — The KYC gate criteria, demonstrated** — `COMPLETE` (2026-09-10). The
Phase 2 gate's first three bullets held by demonstration, and the `INV-KYC-01`…`05`
register rows landed in `MUTATION_TESTING.md` §2 — before the exit review needs them,
which is what keeps that review a check rather than a scramble.

| Acceptance criterion | Evidence |
|---|---|
| Each demonstration recorded in `MUTATION_TESTING.md` §2 with its named test | Five §2 rows (`INV-KYC-01`…`05`) plus the item's own §4 row, each naming the mutation, the catching tests by `Class#method`, and the observed result — all `Recorded` form honestly, since every mutation changed production code or a migration and cannot live in the suite |
| The demonstrations are real, not asserted | **The audit found none missing**: every one was performed by its owning task's sweep (`P2-TSK-005`, `-009`, `-010`, `-011`, `-013`, `-014`); this task recorded rather than re-performed, per its own description |
| The rows are held to the code | `MutationDemonstrationTest`'s checks 3–7 apply to every present row immediately — all nine guard tests green, every named class and method verified to exist. Teeth re-proven per the §5 convention: one method reference corrupted, `everyNamedMethodExists` failed naming exactly `INV-KYC-05 -> CustomerProjectionDatabaseTest has no theDecisionAndTheProjectionCannotDriftX`, restored from a byte-identical backup copy, green again |

**One machinery hazard, caught before commit**: the probe's PowerShell round-trip
(`Get-Content` without `-Encoding utf8` on a UTF-8 file) re-encoded every non-ASCII
character in the register as mojibake — the §Local Environment class of trap, met in a new
tool. The backup-copy discipline §5 prescribes for exactly this step is what contained it:
the restore was byte-identical, verified by comparison rather than assumed.

**Deliberately not landed**: `INV-KYC-06`'s row (its demonstrations exist from
`P2-TSK-008`; the row lands with the exit review), the `INV-CNS-*` rows (owning tasks
`TODO`), and `P2-TST-002`'s §4 row (its own item). The register guard begins demanding
all of them the moment Phase 2's status flips `COMPLETE` — the flip is the guarded act.

### Previously

**`P2-TSK-014` — The projection: a decision moves `customer.status`** — `COMPLETE`
(2026-09-10). The `app` orchestration ADR-0035 describes, and the onboarding gate every
later financial phase queries: a decision's customer moves `PENDING → ACTIVE`/`REJECTED`
in the decision's own transaction, on both doors.

| Acceptance criterion | Evidence |
|---|---|
| An approved case's customer is `ACTIVE` | `CustomerProjectionDatabaseTest`, both doors: the reviewer's endpoint and the automatic all-clear run each leave the customer `ACTIVE`, dated by the decision rather than the fixture |
| Atomically | The projection is the recording's **last write**, and the atomicity probes target it: an injected failure and a backend killed mid-recording (the `P1-TSK-012` deterministic-kill idiom) each leave *nothing* — no decision, no audit record, case still `READY_FOR_DECISION`, customer still `PENDING` |
| The reconciliation sweep proves the pair cannot drift | Both directions: every decision's customer moved as the outcome says, and — the sharp direction — **no customer under verification left `PENDING` without a decision authorizing it**, scoped to customers with a KYC case because a customer with no case has no pair to reconcile |

### `REJECTED` joined the customer machine, and a guard broke on schedule

The plan and ADR-0035 both name `PENDING → ACTIVE`/`REJECTED`, and Phase 1's machine had
no `REJECTED` — mapping it onto `CLOSED` would have overloaded one terminal with two
meanings ("refused" and "ended" are different facts) and made the projection unfaithful to
the decision it mirrors (`INV-KYC-05`). So `CustomerStatus` gained the terminal,
`PENDING`-only `REJECTED`, and **`PartyEnumMigrationTest`'s one-terminal assertion —
written by `P1-TSK-005` to break the day a second terminal arrived — broke on schedule**:
it now derives the latest `CHECK` and the one-live index predicate from the enum's new
`sqlTerminalValueList()` (the `RoleAssignmentMigrationTest` applied-history lesson), with
V002's originals pinned as history. Rejection **frees the party's one-live slot** — the
same asymmetry argument as `CLOSED`: re-onboarding after changed circumstances is a *new*
Customer (`INV-LIFE-04`) — proven behaviourally by the insert V005's widened predicate
admits, and `findLiveCustomerFor`'s SQL is built from the same derivation so the read and
the index cannot disagree.

### The grant premise was corrected rather than propagated

The backlog said the `party` grant *arrives* with this capability. It had arrived in
**V002**, table-wide, before any writer existed ("a status legitimately changes"). So
`V005` **narrows** it — `REVOKE UPDATE` then `GRANT UPDATE (status, status_changed_at)`,
the V004 column-narrowing — proven by the per-column denial sweep with its positive
control, and provable at all only because nothing else ever issued that UPDATE.

### A lost projection is loud, and the mapping lives where the boundary is

The one reachable cause of a lost projection conditional is a customer closed
mid-verification, and the answer is a **loud failure of the whole transaction**: recording
the decision beside an unmoved projection would be the silent drift `INV-KYC-05` forbids,
and the case stays decidable once the contradiction is resolved — proven by the
closed-customer test (500, nothing written). The `DecisionOutcome → CustomerStatus` mapping
lives in `app` because `kyc` cannot see `party`: the projection is precisely the
cross-context fact the orchestration exists to carry. `JdbcPartyStore.moveCustomerStatus`
asks the machine before any SQL (`INV-LIFE-02`) and joined the ownership register
(`ADMINISTERED`: the identifier comes from the kyc case row, never a request, and the
conditional plus the same-transaction `kyc.DecisionRecorded` record stand in for the
ownership predicate). **No new audit action, deliberately** — the projection is the
platform's derived bookkeeping of the already-audited decision, one join away
(`customer → kyc_case → decision`), the `P2-TSK-010` licence.

**Seven mutations, all caught by the intended assertion** — the projection dropped from
both doors, the conditional from-status removed, the lost-move failure swallowed, the
mapping inverted, the index predicate kept narrow (caught against a from-scratch
database), the grant not narrowed, `status_changed_at` not written.
**996 hermetic tests, 532 database tests, 14 kafka tests. M2.3 closes: 3 of 3.**

### Previously

**`P2-TSK-013` — The decision: immutable, attributable, policy-pinned** — `COMPLETE`
(2026-09-10). `KycDecision` (`V007`), the automatic policy for all-clear cases, and
`POST /v1/kyc/cases/{id}/decision` for reviewed ones — `INV-KYC-02` made real: the answer
to *"may this party transact?"* is now one row and its referenced evidence, permanently.

| Acceptance criterion | Evidence |
|---|---|
| One immutable decision per case | `DecisionDatabaseTest`: a ten-way reviewer race lands one 204, nine 409s, one row, one record; a second decision is a 409 with the first untouched; `UPDATE`/`DELETE` denied on **every column** of both tables (`P0-TST-007` idiom), with no `UPDATE` grant at all so the privilege IS the immutability (the `audit_record` model) |
| Reproducible from what it references | The replay test rebuilds the decision's inputs from the check rows the record **references** and the pinned policy code, and re-derives the stored outcome — `INV-CRD-01`'s regime, three phases early |
| Attributable, both actor cases | A reviewer's decision names the person in `decided_by` and in the audit record; the automatic one is `basis=AUTOMATIC`, `decided_by NULL`, actor `system` — coherence enforced by CHECK **and** constructor, so the pair cannot disagree |
| Policy-pinned | The **case's own** `policy_version`, copied by the factory — never `CURRENT` re-read at decision time (`INV-HIST-04`) |

### The automatic policy lives on the domain factory, and the refusal is the control

`KycDecision.automatic` refuses a case with any non-`CLEAR` check — loudly, nothing
constructed — because the silent alternative is an automatic approval of a case that owed a
person a judgement (`INV-KYC-04`). Proven **at the domain** (`INV-LIFE-02`'s
rejected-by-the-aggregate principle): production reachability alone would make the refusal
untestable, since the assessment's `BLOCKED`-first ordering never routes a non-clean case
to the automatic branch. That ordering is also what keeps the two doors honest: a reviewed
case's `IN_REVIEW → READY_FOR_DECISION` stays durable — awaiting a person is a real state —
while the all-clear path never exhibits it.

### The decision rides the assessment's own transaction, deliberately

The `P2-TSK-009` assess-after-commit rule exists so an assessor sees *other* transactions'
committed outcomes; the automatic decision reads only what its own transaction already
read, so that argument does not apply — and atomicity buys the absence of a stranded
all-clear-and-undecided window entirely: `CHECKS_IN_PROGRESS → READY_FOR_DECISION →
APPROVED`, the decision row, its evidence references and the audit record commit together
or not at all. The `CIP → RFD` move's result is deliberately not the gate on the decision:
the decision's **own** conditional (`RFD → APPROVED`) arbitrates, so a duplicate callback's
re-assessment converges and one terminal exists however many times the fact arrived. Four
assertions in two suites moved from `READY_FOR_DECISION` to `APPROVED` — the `P2-TSK-010`
stopgap-superseded precedent, recorded in each.

### The conditional move is the arbiter; the schema is defence in depth

`moveStatus(READY_FOR_DECISION → terminal)`'s row count decides who records — N concurrent
deciders, reviewer or automatic, produce one decision and N−1 losers told the truth, with
the two 409 details named for what is checked ("already decided" stops a reviewer, "not
ready" tells them to wait — the `NOT_ACTIVE` lesson). The **total** `UNIQUE (case_id)`
index behind it would only ever fire on an invariant already violated, which is why the
store's insert is plain and loud rather than a converge. Evidence references are a join
table with real FKs — evidence appended after the decision (`P2-TSK-008`'s accepted race)
is now **mechanically** outside what the decision rested on.

### The sixth enumerated `enterSystem()` site

The automatic decision's audit names the platform — nobody is present, and attributing the
approval to whichever customer's callback completed the last check would record them as
having approved themselves. The scope wraps the audit write only, so the reviewer path
through the same class can never inherit it — and the mutation that made it do so was
caught by the acceptance suite's attribution assertions, with the hermetic site
enumeration as the independent second control. `kyc.DecisionRecorded` leaves
`NOT_YET_EMITTED`, emitted on both paths.

**Seven mutations, all caught by the intended assertion** — the arbiter ignored, the
policy predicate removed, the evidence references dropped, the audit dropped, the
automatic hook dropped, `UPDATE` granted in `V007` (caught against a from-scratch
database), the reviewer's audit written as the platform.
**995 hermetic tests, 524 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-012` — Review tasks and the reviewer endpoints** — `COMPLETE` (2026-09-10).
`GET /v1/kyc/cases/{id}` and `POST /v1/kyc/cases/{id}/reviews/{taskId}/resolution`, both
behind `@RequiresPermission(KYC_REVIEW)`: the judgement a non-clean check owed a person,
recorded exactly once, with a reason, audibly.

| Acceptance criterion | Evidence |
|---|---|
| A review task resolves exactly once | `ReviewDatabaseTest`: a ten-way concurrent race lands one 204, nine 409s, one audit record — and the recorded reviewer is one of the racers, so the row and its record agree |
| By an authorized person | A valid session holding no role is refused by both endpoints (`INV-AUD-03`), and the task stays `OPEN` |
| With a reason | A missing one is a 422 naming the field; the bound is `AuditRecord.MAX_REASON_LENGTH` in three reconciled copies — the boundary (`ResolutionRequest`, citing `SuspensionRequest`'s now-`public` constants across a package), the `V006` `CHECK`, and the record itself |
| Audibly | `kyc.ReviewResolved` names the reviewer and rides the resolve's transaction; the 409 loser writes nothing, and one resolution is one record |

### Resolution is state, not a method — and the statement is the protocol

The conditional `UPDATE`'s three predicates are each load-bearing and each mutation-proven:
`id` names the task, `case_id` refuses another case's task named through the wrong URL (a
uniform 404, with nothing written), and `status = 'OPEN'` is the concurrency arbiter. A
`RESOLVED` row then **freezes whole** — a `BEFORE UPDATE` trigger permits exactly
`OPEN → RESOLVED` and nothing else, so the record a decision will rest on cannot be edited
by ANY writer (`INV-KYC-02`, the credential-freeze precedent) — and the `UPDATE` grant is
column-narrowed to `(status, resolved_by, resolved_at, resolution_reason)`, the `V004`
narrowing, with the identity columns proven permission-denied.

### The exit is in the statement, after the commit, and the 409 path heals

`IN_REVIEW → READY_FOR_DECISION` is `moveStatusWhenNoOpenTasks`: conditional on
`status = 'IN_REVIEW'` **and** `NOT EXISTS (… status = 'OPEN')` — the predicate
`P2-TSK-010` recorded in the store's javadoc, now real. It runs in a **separate transaction
after the resolve commits** (the `P2-TSK-009` assess-after-commit argument: two instances
resolving a case's last two tasks inside their own transactions would each see the other's
still open and nobody would move the case), and the **already-resolved path re-attempts it
too** — the `P2-TSK-011` duplicate-heals shape, proven by fixture: a crash landing between
a resolve's commit and its exit leaves the case visibly `IN_REVIEW` with nothing open, and
the reviewer's retried request (409) is what completes it.

### The read is audited, because the reviewer is the insider surface

`kyc.CaseRead` in the same unit of work as the read — `INV-KYC-06`'s trail-of-who-looked
one level up, the control on the person with every right to look. A guessed identifier
writes no record (identifiers that were never real must not enter the permanent trail),
and malformed and absent are one 404. The response is **references only** — check and task
identifiers, statuses, and resolutions reviewers themselves wrote; no evidence bytes, no
document content, because the plan declares no content endpoint and none was invented.

### An audit action was renamed before its first emission, and one guard broke on schedule

`kyc.ScreeningHitResolved` became **`kyc.ReviewResolved`**: a task arises from any check's
`HIT` **or** an exhausted `INDETERMINATE`, so the old name could describe a resolution that
never concerned a screening hit — corrected while the registry entry had no emitter, which
is the only moment a rename is free. `kyc.CaseRead` joined the registry with the read.
`ScreeningRunDatabaseTest`'s grant test — written by `P2-TSK-010` to break the day the
`UPDATE` grant arrived — broke on schedule and now pins the grant's boundary instead. And
one test of this task's own was wrong before it shipped: a `doesNotContain` over the whole
V006 file matched the migration's own **prose comment** naming the rejected constraint
(the `P1-TSK-021` string-matching lesson) — statements-only now, with a vacuity control.

**Seven mutations, all caught by the intended assertion** — the resolve conditional made
unconditional, the belongs-to-case predicate removed, the exit's `NOT EXISTS` removed, the
resolution audit dropped, the 409-path healing removed, the freeze trigger removed, the
read audit dropped. **983 hermetic tests, 514 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-011` — Provider callbacks, deduplicated** — `COMPLETE` (2026-09-10). The
platform's first machine-facing endpoint: `POST /v1/providers/kyc/callbacks`, where an
asynchronous provider answer completes a check **exactly once** however many times and
however concurrently it arrives.

| Acceptance criterion | Evidence |
|---|---|
| Duplicate and concurrent callbacks → one transition | `ProviderCallbackDatabaseTest`: a triple delivery and a ten-way concurrent race each land one completion, one evidence row, one audit record, one inbox row — every response 204 or the inbox's honest 409 |
| A late callback is evidence, never a transition | A terminal `INDETERMINATE` stays terminal (`INV-LIFE-04`), the answer is retained verbatim (`INV-HIST-02`), zero audit records, and the delivery is acknowledged — a refusal would make a correct provider retry a fact we will never accept |
| Malformed callbacks are the caller's 4xx, never our 500 | Eleven signed shapes swept — broken JSON, missing/blank/oversized/bad-charset deliveryId, missing/malformed/v4/unknown checkId, missing status, oversized body — each its own status, nothing written |
| The unsigned stranger writes nothing | Missing and wrong signature are one uniform 401; the check stays `DISPATCHED`, no evidence, no audit, no inbox row |

### The signature is the control, and a checksum was rejected by name

The callback is the input that **clears sanctions screenings**, so a "signature" anyone can
compute would be an open door to check-outcome forgery — qualitatively worse than any other
unauthenticated surface here. HMAC-SHA256 over the raw bytes, verified before parsing,
before any read, in constant time (`MessageDigest.isEqual`, asserted **structurally**
because no behavioural test can see timing); proven against **RFC 4231's own vectors**, the
`P1-TSK-017` vetted-library reasoning verbatim. The limits are stated in
`SECURITY_ARCHITECTURE.md` rather than implied: one static key, no rotation, no per-provider
keys — Phase 5's webhook work — and **no replay window at the signature layer, deliberately**:
a replayed callback is byte-identical, so it is exactly the duplicate the inbox absorbs, and
a freshness check would defend against a harmless replay with a clock agreement.

### Two dedupe layers, blind in different directions — designed in, not found by a gate

The `P2-TSK-007` lesson applied at design time: the **inbox** absorbs *identical*
deliveries (the provider retrying one delivery reuses its `deliveryId`; the primary key on
`(consumer, dedupe_key)` lets one instance run the handler), and the **conditional
completion** absorbs *distinct* deliveries of the same result — a re-send under a fresh id,
or a callback racing the synchronous run. `CONTENDED` is honoured as the inbox's contract
demands: **not acknowledged** (409), so the provider redelivers and the dedupe absorbs or
the redelivery lands, correct whichever way the race went. Evidence is appended **always**
— the late answer and the losing racer are both genuine provider statements an
investigation wants — where the run path retains evidence only on a win, and the difference
is recorded as deliberate.

### The callback is the healer, and assessment heals one deeper

A check stranded `DISPATCHED` by a crash mid-call was `P2-TSK-009`'s visible-by-design
remainder — and the provider *received* that request, because dispatch commits first. Its
callback is how the stranded fact completes without a sweeper, which is why the fixtures
build `DISPATCHED` checks through the stores: that state IS the scenario. After the delivery
commits, the case is assessed in a separate transaction — and a **duplicate** re-assesses
too, healing a crash that landed between a delivery's commit and its assessment.

### Composition earned by a second caller, not invented for one

The `P1-TSK-033` shape: `CaseAssessment` (assess + atomic tasks-then-move routing) and
`CheckOutcomeTrail` (audit + eager counters) extracted from `VerificationRunService` when
the callback became their second caller — and the **enumerated `enterSystem()` site moved**
to `CheckOutcomeTrail.record`, one site whichever door the outcome arrives through, because
two copies of that justification would drift. The site count stays five.
`CheckOutcome.fromWire` normalises the wire status with the port's own totality: default
`INDETERMINATE`, never success.

### The guards fed

`OwnershipIsScopedTest` gained its **seventh class**, `SIGNED_CALLBACK` — every existing
label would say something false about an external system naming a resource under a boundary
HMAC — with `JdbcCheckStore.findById` its first entry, resting on the signature, the
platform-minted identifier (dispatch-before-call), and every reachable write being a
conditional transition, with the nothing-written test named as the proof. `CallbackKey` is
the **fourth per-credential loopback confinement**: the debt row's own trigger, met — the
row now says the generalisation is due as its own work rather than predicting a next
arrival. `DatabaseCredentialGuardStartupTest` broke because a production-like configuration
now has **four** credentials — the `P1-TSK-017` precedent, met a third time. The OpenAPI
baseline gained the endpoint (nine additions, zero removals; the two `BREAKING` labels are
the classifier erring safe on a brand-new path — the `P1-TSK-002`/`P1-TSK-006` precedents,
reviewed and accepted). `inbox_message.dedupe_key` met its **first external supplier**, and
the `INTERNAL`-classification-as-requirement is enforced at the boundary with the
idempotency-key charset; `DATA_CLASSIFICATION.md` §5 records it.

### The gate's own findings

**`CallbackKey` had no test** — the exact `P1-TSK-017` finding (*"`MfaKey` had no test at
all, so removing the loopback confinement left everything green"*), about to repeat on the
fourth hand-written instance of the shape. `CallbackKeyTest` closes it — confinement,
domain separation from both sibling keys, and the at-least-32-bytes rule with its
deliberate difference from `DocumentKey`'s exactly-32 stated — and the
confinement-removed mutation is caught. **And this task's own structural assertion was
vacuous as first written**: `doesNotContain("java/util/Arrays.equals")` can never match,
because a class file's constant pool holds the class name and the method name as separate
UTF-8 entries — corrected to the entries that actually appear, the reasoning
`TotpVerifierTest` had already encoded in its descriptor check.

**Seven mutations, all caught by the intended assertion** — signature verification
bypassed, inbox dedupe bypassed (effect run directly), the conditional completion made
unconditional (a late callback reopening a terminal), the evidence append dropped, the
post-commit assessment dropped, the constant-time comparison swapped for `Arrays.equals`,
and the `CallbackKey` confinement removed.
**979 hermetic tests, 502 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-010` — Screening: sanctions, PEP, adverse media** — `COMPLETE` (2026-09-10). The
gate's screening criteria, on the machine `P2-TSK-009` built: one `ScreeningAdapter` with
three type-and-path-binding static factories (a mismatch is unconstructible), the
`ReviewTask` the plan names as *the explicit work item a non-clean case becomes*, and the
routing that gives a blocked case its exit — into a person's queue, never past one.

| Acceptance criterion | Evidence |
|---|---|
| A hit case cannot terminate without a person | Behaviourally: a sanctions hit → `IN_REVIEW` with exactly one `OPEN` task on the hit check, and `IN_REVIEW` has no exit in this build. Structurally: the machine has no edge from `IN_REVIEW` to a terminal, and `ChecksAssessment` decides `HIT` first — not even a later `CLEAR` of its own type un-blocks |
| HIT → `IN_REVIEW` with a task, idempotently | `ScreeningRunDatabaseTest`: re-run converges (no second task, no re-asked question); ten racing instances produce one task per hit check, one transition, and `requestCount sum == checkCount` |
| Adverse-media INDETERMINATE handling | Three unknowns exhaust `INDETERMINATE_RETRY_BUDGET`; the fourth run asks nothing more, and the case is in review with one task on the newest unknown |

### The convergence rule was corrected, and that is the task's sharpest edit

`P2-TSK-009`'s `requestOrConverge` converged on a check of the type in **any** state — which
made an `INDETERMINATE` unresolvable on the run path, against ADR-0038's
resolution-is-a-new-check. Now an under-budget unknown invites a **new** check (the retry,
arbitrated by the same partial in-flight index and savepoint path), and at the budget the run
converges and the assessment routes the type to a person: **after three unknowns the platform
stops asking machines** — silence resolves nothing, in either direction. The budget count is
compared with `>=`, so the recorded redundant-question race overshooting routes to review
*sooner*, never later.

### One task per check, ever — and the routing is atomic

`UNIQUE (check_id)` on `kyc.review_task` is **total**, not partial: the resolution of *that
question* is permanent evidence, a wrong resolution is a new review event, and changed
circumstances are a **new check** with its own task. Totality is also the concurrency arbiter
— N assessors reaching `BLOCKED` insert `ON CONFLICT DO NOTHING`, and exactly one wins per
check. Tasks are inserted and the conditional move to `IN_REVIEW` made in **one
transaction**, so the state and its work item commit together and `P2-TSK-012`'s exit
condition ("every task resolved") can never be vacuously true on arrival; creation is
unconditional on case status, so a late `HIT` joining an in-review case still gets its task.
**Recorded for `P2-TSK-012`, in the store's javadoc**: the `IN_REVIEW → READY_FOR_DECISION`
move must be conditional on "no `OPEN` task" *in the statement*.

### The deliberate asymmetry, tested both ways

A `CLEAR` of a type never un-blocks that type's `HIT` (a hit is an *answer* that demands a
person); a later `CLEAR` **does** satisfy an exhausted type (exhaustion is the *absence* of
an answer, and an answer arriving ends the absence). `needingReview` is empty exactly when
the assessment is not `BLOCKED`, pinned by test, so the enum and the task-set derivation
cannot disagree about whether a case needs review.

### What deliberately did not arrive

No new audit action — task creation is the platform's derived bookkeeping of an
already-audited check outcome; the reason-required act is the **resolution**
(`kyc.ScreeningHitResolved`, still `NOT_YET_EMITTED`, `P2-TSK-012`'s). No resolution columns
and no `UPDATE` grant — the grant arrives with the capability, proven by permission-denied
now (the `V004` precedent). No events, no endpoints. No `forCase` read — a read with no
caller is dead code carrying confident javadoc (the `P1-TSK-013` finding); it arrives with
the reviewer surface.

### The guards fed

`finapp.kyc.review.queue` arrives with the queue it measures — the plan-named gauge
(`PHASE_2_PLAN.md` §10), `IdentityMetrics`' shape: over the database, fleet-wide, NaN when
unreadable and never zero, cached behind a floor; two more entries in the `INV-MON-01`
exemption set, argued as the same Micrometer-gauge case a third time. Five new columns
classified at their ceiling — `review_task.status` is the tipping-off column one table
further down: a task *existing* says screening raised something. The ownership register
needed **no** new entries, and that was verified rather than assumed: `openForCheck` takes
the aggregate and `countOpen` takes nothing, so neither carries a resource identifier the
detector keys on. `VerificationRunDatabaseTest`'s hit expectation moved from "stays
`CHECKS_IN_PROGRESS`" to "routes to `IN_REVIEW`" — the `P2-TSK-009` stopgap superseded by the
capability that was always going to supersede it.

**Six mutations, all caught by the intended assertion** — task creation removed, the move to
`IN_REVIEW` removed, `ON CONFLICT` removed, convergence-never-retries (the `P2-TSK-009`
regression restored), the budget unbounded, and exhausted-no-longer-blocks.
**960 hermetic tests, 495 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-009` — VerificationCheck, the provider port, and the simulated verifier** —
`COMPLETE` (2026-09-09). The harness `P0-TSK-037` built has waited two phases for exactly this
caller: the check machine (`REQUESTED → DISPATCHED → {CLEAR | HIT | INDETERMINATE}`), the
`VerificationProvider` port (our vocabulary in, our vocabulary out — ADR-0008), two adapters
over the simulated wire, and the run choreography that keeps all of it honest under N
instances.

| Acceptance criterion | Evidence |
|---|---|
| A clean simulated run takes a case to `READY_FOR_DECISION` with retained evidence | `VerificationRunDatabaseTest`: two providers answer clear over real HTTP, the case row reads `READY_FOR_DECISION`, the evidence rows hold the bytes received, and the audit trail carries `kyc.CheckCompleted` with `actor_id = 'system'` |
| Every `SimulatedProvider` outbound mode drives a defined outcome | `VerificationAdapterTest`, the full matrix: clear, hit, unknown state, timeout (bounded), unavailable, 5xx, malformed, garbage, lost response (provably received — `requestCount == 1`), slow-but-in-time |

### The terminals ARE the outcomes, and INDETERMINATE never flaps

One machine, not a status beside an outcome column free to disagree with it.
`INDETERMINATE` is **terminal**: its resolution is a **new check** of the same type (ADR-0038),
so a check never flaps, the evidence of the failed attempt stays true, and the partial unique
index — in-flight states only — is what makes the successor insertable. `INV-LIFE-03` arrives
three phases before its catalogued owner, and the port's contract is **total**: provider
misbehaviour is a result, never an exception, with the mapping's default branch
`INDETERMINATE` — never success — and whatever bytes arrived retained verbatim
(`INV-HIST-02`), encrypted with the same at-rest treatment as documents (ADR-0036 groups them).

### The choreography: dispatch durable, call connectionless, outcome atomic, assessment after

Per check, two transactions with the provider call between them. The dispatch (conditional
`REQUESTED → DISPATCHED`, row count is the outcome) **commits before the provider is asked**,
so a crash mid-call leaves a visible `DISPATCHED` fact to reconcile, never an unknown — and
only the instance whose conditional won calls the provider, which is what makes
`requestCount == checkCount` assertable under a ten-way race. The call holds **no database
connection** (`P1-TSK-026`'s failure shape). The outcome transaction writes the conditional
completion, the evidence, the audit record and the counter together or not at all.

**And the assessment is a separate transaction after the outcome commits, which is
load-bearing**: two instances completing a case's last two checks simultaneously would each
assess inside their own outcome transaction, each see the other's check still `DISPATCHED`,
and nobody would move the case. Assessed afterwards, the last assessor sees every committed
outcome, both may attempt the transition, and the conditional `moveStatus` lets exactly one
win — which is also what heals a crash landing between an outcome and the case transition.

### The case moves only by our assessment, and a HIT wins every tie

No branch maps a provider verdict onto the case (`INV-KYC-01`). `ChecksAssessment` reads the
whole: **`BLOCKED` on any `HIT` is decided first** — not even a later `CLEAR` of the same type
un-blocks, because a hit is resolved by a *person* (`INV-KYC-04`), and the routing that gives a
blocked case its exit is `P2-TSK-010`'s, so a hit case stays honestly in
`CHECKS_IN_PROGRESS`. An empty required-type set is **refused**: no requirement can never mean
"proceed" — it would decide a case by absence of questions.

### The residual race, recorded rather than locked away

`requestOrConverge`'s pre-flight read is the business rule (one question per type), not a
substitute for the index (`P1-TSK-006`'s distinction) — and an instance reading just before
another's terminal commit can insert a redundant second question, because the terminal check
has left the partial index. That race produces a wasted provider call and an extra answer,
**never a wrong one** (an extra CLEAR changes no assessment; an extra HIT only blocks harder).
A check stranded `DISPATCHED` by a crash is likewise visible-by-design; the sweeper that
re-drives it is recorded remainder, not silent absence.

### The guards fed

The **fifth enumerated `enterSystem()` site** — nobody is present when a machine records what
a machine answered, and attributing the outcome to the customer would record them as having
assessed themselves; justification in `SECURITY_ARCHITECTURE.md`. `kyc.CheckCompleted`
catalogued (no reason — recording an outcome is taken for and against nobody; the
reason-required acts are the decision and the resolution). Fourteen new columns classified at
their ceiling — `verification_check.status` is the sharper tipping-off column, the evidence
ciphertext and checksum `RESTRICTED-PII` per the document reasoning. `OwnershipIsScopedTest`
refused four unclassified store methods and the register gained them, all `AUTHORITATIVE_ID`
on `findOpenFor` — and the `moveStatus` entry's *"no production caller yet"* went stale this
task and was corrected. The v4/v7 identifier trap was met **and caught in self-review**:
`UUID.randomUUID()` in `appendEvidence` became `EvidenceId`, minted by the runner.

**Six mutations, all caught by the intended assertion** — dispatch-before-call inverted and
the duplicate-dispatch conditional removed (both by the ten-instance race's call-per-check
equality), a HIT allowed past the assessment, an unknown verdict mapped to CLEAR, the
evidence write dropped, the audit write dropped. The sweep's first run reported all six
**VOID** — `cmd` refused the bare `gradlew.bat` name, so the build never started — caught by
the harness's build-actually-ran assertion, which exists because of `P1-TSK-026`'s identical
finding; re-run with the absolute path, all six caught.
**945 hermetic tests, 491 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-008` — Documents: captured, encrypted, checksummed, access-audited** — `COMPLETE`
(2026-09-09). The most sensitive bytes the platform holds before card data, held the way
ADR-0036 decided: in PostgreSQL behind the `DocumentStore` port (the object-storage seam),
AES-256-GCM under `FINAPP_DOC_KEY`, SHA-256 of the bytes received recorded at capture and
re-verified on every read.

| Acceptance criterion | Evidence |
|---|---|
| Content readable only through the audited path | Structurally — content is not a field on any type, and the one method returning it has one production caller, `DocumentAccess`, which writes `kyc.DocumentContentRead` in the same unit of work — and behaviourally: the mutation removing the audit call is caught |
| Every listed refusal proven | The `information_schema` column sweep, GCM tamper, wrong-key, oversized/foreign-type/bad-base64 at the boundary, no-open-case, unauthenticated — each its own test, none a 500 |

### Content-addressed convergence is the idempotency mechanism

`UNIQUE (case_id, checksum_sha256)` plus the savepoint idiom: a retry after a lost response, a
double-tap, and ten instances racing the same bytes all land on **one row and one 201** — the
lost-response case *is* the idempotency case, answered by the checksum rather than by an
`Idempotency-Key` (not money-moving, and content addressing is stronger). Different bytes append
freely; evidence is never edited (`INV-HIST-02`), so the grants are `SELECT, INSERT` and nothing
else, and deletion stays Phase 15's recorded retention/erasure tension.

### The cipher is `SecretCipher`'s mechanism, deliberately not its class

Module isolation forbids `kyc` seeing `identity`; moving the class to `platform` would refactor
proven Phase 1 code and pull an `expose()` call site out of the set
`SecretsAreUnwrappedInOnePlaceTest` pins to `identity` — a security-rule modification to save
sixty lines — and it speaks `Sensitive<String>` where a document is bytes. So `DocumentCipher`
restates the mechanism over `byte[]`: fresh 96-bit nonce per encryption (a fixed-nonce mutation
is caught), key version per row (`INV-HIST-04` applied to a key), tamper and wrong-key one
indistinguishable refusal. **GCM answers "is this ciphertext the one this key wrote"; the
checksum answers "are these the bytes received at capture"** — separated by a test that
substitutes a ciphertext the same key genuinely wrote, which only the checksum can catch.

### The third per-credential confinement, and the debt row met its trigger early

`DocumentKey` is `MfaKey`'s shape: the marked local default **referenced** from
`MfaKey.MARKED_LOCAL_DEFAULT` so the repository keeps exactly one published literal,
domain-separated locally (`/doc`) so two concerns never share key bytes, confined to loopback
via `DatabaseEndpoint`. The debt row had predicted the third credential as *"Phase 5's provider
adapters"* — it arrived here instead, and the row's premise is corrected rather than left
stale; generalising inside a document task would be rule 4's smuggled refactor.

### The ownership chain, one hop longer — and no read endpoint, by plan

`POST /v1/me/kyc/documents` is the `/v1/me` shape: no path variable, no body field names a case
or customer — Session → Identity → the party's **live** Customer (a new `PartyStore` read whose
predicate is the one-live-relationship index's own) → that customer's open case. A decision
racing the upload is an **accepted race, stated**: evidence may land on a just-decided case,
harmless because a decision references its evidence explicitly (`INV-KYC-02`). No open case is
`409 kyc.NoOpenCase` — a distinct code because it is actionable (`P1-TSK-018`'s test). The read
path has **no HTTP caller yet**: the reviewer surface is `P2-TSK-012`'s, the customer gets no
download endpoint at all, and building the audited path now is what makes the property true from
the first day content exists.

### The guards fed

`OwnershipIsScopedTest` refused three unclassified methods and the register gained
`findLiveCustomerFor` (`SESSION_DERIVED`), `findByChecksum` (`AUTHORITATIVE_ID` on
`findOpenFor`) and `readContent` (`ADMINISTERED`, with `P2-TSK-012` named as the arrival that
must come and say so). `DatabaseCredentialGuardStartupTest` broke because a production-like
configuration now has **three** credentials — the `P1-TSK-017` precedent, met again. Ten new
columns classified at their ceiling, the ciphertext at `RESTRICTED-PII` *of what it decrypts
to*, and the checksum too — a possession oracle. `kyc.DocumentContentRead` leaves
`NOT_YET_EMITTED`; a stale class javadoc ("nothing here is emitted yet", false since
`P2-TSK-007`) corrected on the way.

**Six mutations, all caught by the intended assertion.**
**920 hermetic tests, 486 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-007` — The first production consumer: a registration opens a case** — `COMPLETE`
(2026-09-09). `DELIVERY_PLAN.md` §Phase 2.8's *downstream contexts react*, real for the first
time: HTTP registration → outbox → relay schedule → Kafka → consumer loop → inbox → case row,
the deployed chain, driven whole.

| Acceptance criterion | Evidence |
|---|---|
| Registration alone yields exactly one open case, through the real broker | The app booted with relay **and** consumer enabled — the two workers every other suite disables — and the case appears, audited and announced, with no test call in the middle |

### The naming drift, and what reading the producer bought

The backlog said the consumer handles `party.CustomerRegistered` — that is the **audit
action's** code; the events registration publishes are `party.PartyRegistered` and
`party.CustomerOpened`. Corrected rather than propagated. And `party.CustomerOpened`'s
aggregate **is the Customer**, so the handler reads no payload at all:
`ReceivedEvent.aggregateId()` is the reactive key — the envelope's metadata-only principle
(`P0-TSK-018`) paying off at the platform's first real consumer.

### Created announces; converged is silent — and the gate proved the second half properly

Only the delivery that *created* the case writes `kyc.CaseOpened` (its first emitter — the
`NOT_YET_EMITTED` entry leaves the list) and publishes `kyc.KycCaseOpened`, caused by the
consumed event so the causal tree keeps its shape. A converged delivery records nothing: two
opening records on one case is the ambiguity `INV-KYC-03` exists to prevent.

**The mutation sweep found the kafka-level test asserting less than the sweep assumed.**
Removing the converged-guard survived the wire-duplicate test — because an exact duplicate
never reaches the handler at all: the **inbox absorbs it by `eventId`**, and the guard's real
subject is a **distinct** event converging on an existing case, which is precisely what the
consumer racing `POST /v1/me/kyc` will produce. That path is now driven end to end — one case,
one audit record, one announcement — and the mutation is caught by exactly that test.

### The platform is the actor, and the enumeration met its predicted class

A consumer has no authenticated caller; the registration's own records name that flow's actor,
and opening the case is the platform's policy act. The fourth `enterSystem()` site joined the
enumeration with its justification in `SECURITY_ARCHITECTURE.md` — recorded as the *class* of
site every future consumer with an audited effect will be, each coming to say so for itself.
What ties the record to the person is the correlation (the producing flow's, entered by the
shell) and the target, which names the customer. One small charset collision surfaced:
`KycPolicyVersion.CURRENT` carried a dot, which `EventPayload` refuses — the label now uses
dashes, the type unchanged.

**Six mutations: five caught first time, one survived and strengthened the suite.**
**903 hermetic tests, 477 database tests, 14 kafka tests.**

### Previously

**`P2-TSK-005` — The KycCase aggregate and its lifecycle** — `COMPLETE` (2026-09-09). The
phase's spine: one verification of one customer, `OPEN` to a terminal decision state, with the
machine on the enum, the rules in the aggregate (`INV-LIFE-02`), and the one-open-case rule in
the database — a rule *across* aggregates of the same type, which only the database can
arbitrate (`P1-TSK-005`'s reasoning, verbatim).

| Acceptance criterion | Evidence |
|---|---|
| Every invalid transition rejected by the aggregate | The cross-product sweep, derived from the machine; both terminals swept separately |
| One open case under contention | Ten instances, ten connections: one row created, nine **converged** onto it, counted in the table |

### Convergence is the open semantics, and the savepoint is what makes it possible

The losers of the race are handed the winner's case, not an error — "ensure my case exists" is
what `POST /v1/me/kyc` and the registration consumer both mean, and racing them against each
other is `P2-TSK-007`'s test. Behind a savepoint, because the unique violation aborts the
transaction and the caller's other writes must survive the lost race; a pre-flight `SELECT` is
recorded as not a substitute (`P1-TSK-006`, verbatim).

### Two generated schema artefacts, and the second is the sharper one

The status `CHECK` is `sqlValueList()`, the `P0-TSK-022` pattern — and the **one-open-case index
predicate is `sqlTerminalValueList()`**, so "terminal" and "frees the slot for a successor" are
one definition. A state added to the machine without a decision about which side of the
predicate it sits on either lets a customer hold two open cases (the duplicate-case harm
`INV-KYC-03` names) or blocks their successor case forever — both silent without the
reconciliation, both proven caught. The freed slot is itself demonstrated: a case walked to
`APPROVED` admits a new one, because changed circumstances open a *new* case and the decided
one stays decided (`INV-LIFE-04`, `INV-KYC-02`'s defensibility).

### The policy version is pinned where it is still free

`policy_version NOT NULL`, fixed at open: which regime a case is assessed under is a fact
unrecoverable if not recorded when the case is born (`INV-HIST-04`). Today it is a platform
label (`KycPolicyVersion.CURRENT`); the artefact it names arrives with decisioning
(`P2-TSK-013`), which pins the same version on the decision record.

### The guards fed, each with a claim

`OwnershipIsScopedTest` refused the unclassified `moveStatus` and the register gained its entry
(`AUTHORITATIVE_ID` on `findOpenFor`, with `P2-TSK-012`'s reviewer surface named as the
`ADMINISTERED` arrival that must come and say so) — and the ownership-predicate vocabulary
gained the **third entry its own javadoc predicted would need writing down**: `customer_id = ?`,
the kyc schema's owner column. Six columns classified before they hold data; `kyc.CaseOpened`
declared with the aggregate whose design fixed its meaning.

**Five mutations, all caught by the intended assertion.** **901 hermetic tests, 477 database
tests, 10 kafka tests.**

### Previously

**`P2-TSK-004` — `KYC_REVIEW` permission and the `KYC_REVIEWER` role** — `COMPLETE`
(2026-09-09). A second role and third permission, and the closing of a limit `P1-TSK-020`
recorded in as many words: *"the role→permission mapping cannot be meaningfully mutated until a
second role exists."* This is the second role, and the previously-impossible mutation failing
the build is the acceptance criterion, met twice over.

| Acceptance criterion | Evidence |
|---|---|
| The `P1-TSK-020` limit closes | `KYC_REVIEWER` granting everything is caught by `RoleNameTest` **and** independently by the HTTP cross-population test — two controls blind in different directions |
| Least privilege, both directions | A reviewer (granted through the **real** roles endpoint) is refused by both administrative endpoints; an administrator is refused by the `KYC_REVIEW` probe |
| Migration/enum reconciliation | `V013` regenerated from `RoleName.sqlValueList()`; the reconciliation now derives the **latest** constraint definition and pins `V010` as history |

### The first real least-privilege split, stated honestly

`KYC_REVIEWER` holds exactly `KYC_REVIEW`; `ADMINISTRATOR` gains nothing. The two grants are
**disjoint**, asserted as its own property — neither exact-set assertion alone says the sets do
not overlap. What the split does *not* buy is stated as `P1-TSK-028` stated self-elevation: an
administrator holding `ROLE_ASSIGN` can grant themselves `KYC_REVIEWER`, and what the split buys
is that the escalation is a **recorded grant in the trail** rather than a capability that was
silently always there. The permission is the first whose actions live outside `identity`;
authorization stays there per ADR-0031's recorded merge, one foreign-domain permission not
reaching its split trigger.

### The reconciliation test had to learn that applied migrations are history

`RoleAssignmentMigrationTest` pinned the constraint to `V010` — right at one role, wrong the
moment a second exists, because `V010` cannot be edited (ADR-0011) and the constraint moves by
**replacement** in a new migration. It now derives the highest-numbered migration defining
`role_assignment_role_is_known` and reconciles that against the enum — so a widened constraint
without the enum, or an enum value without its migration, fails whichever came first — and
separately pins `V010`'s original literal, because history keeping its shape is its own claim.
**The derivation's first version threw on this module's own jar**: a module's tests see its
resources inside the jar `java-library` packs, not a directory — the `P0-TSK-036` finding
arriving as a file, and it failed in the loud direction, which is the direction to err in.

### The contract gained one request-enum value, reviewed rather than waved through

`RoleAssignmentRequest.role` publishes `RoleName`, so the diff is one added enum value labelled
`BREAKING` — the classifier's blanket rule for enum additions, erring safe by design. Reviewed:
this is a **request** enum, and an existing client that never sends the new value cannot be
broken by its existence; accepted on the `P1-TSK-032` precedent of reviewing the label rather
than obeying it.

**Six mutations (the acceptance one counted twice), all caught by the intended assertion.**
**891 hermetic tests, 473 database tests, 10 kafka tests. M2.1 closes: 5 of 5.**

### Previously

**`P2-TSK-003` — `kyc` and `consent` module skeletons** — `COMPLETE` (2026-09-09). The
`P1-TSK-003` shape applied twice: two modules on the documented direction, two schemas each
owned by the migrator with default-deny privileges (`REVOKE ALL FROM PUBLIC`, `USAGE` only to
`finapp_app`, no tables), isolation tests in both directions, audit-action enums with their
catalogue rows, two new lockfiles from the §7a one-invocation regeneration.

| Acceptance criterion | Evidence |
|---|---|
| Build green with both modules | 888 hermetic + 471 database tests, locking enforced |
| Every existing sweep provably covers them | Five probes, all caught: a planted `double` in each module, a cross-module dependency, a deleted catalogue section, an unclassified migrated column |

### The privilege floor is the deliverable, and it is why the schemas come first

Everything Phase 2 will claim at `DB-PRIVILEGE` rank — immutable decisions (`INV-KYC-02`),
append-only evidence (`INV-HIST-02`), append-only consent history (`INV-CNS-02`), audited
document reads (`INV-KYC-06`) — is only *available* at that rank because the migrator owns the
objects and each table's grants arrive with the migration that creates it. A schema created
casually later, under the wrong owner, forecloses the strongest enforcement the catalogue knows
for the phase whose product is defensible decisions.

### Five audit actions, declared under the deliberately-few licence

The licence is `P1-TSK-003`'s: declare what the design states outright, never what a later
task's design will shape. `kyc.DecisionRecorded` and `kyc.ScreeningHitResolved` require a
reason because **the invariants themselves say so** (`INV-KYC-02`'s `NOT NULL` reason,
`INV-KYC-04`'s justified resolution); `kyc.DocumentContentRead` deliberately does not — the
trail of *who looked* is `INV-KYC-06`'s control, and a mandatory reason on a routine review
read produces a column of `"review"`. `consent.ConsentGranted`/`ConsentWithdrawn` are a
person's own acts, and demanding a justification at the moment of withdrawal would be pressure
applied exactly where none may exist. **Case opening and check outcomes are absent on
purpose** — their emitters are `P2-TSK-005`/`-007` and `P2-TSK-009`'s designs. All five joined
`AuditCompletenessTest.NOT_YET_EMITTED` naming their owning tasks, so "deliberately not built
yet" stays distinguishable from "somebody removed the call".

### The gate's finding: sibling isolation had quietly become one-directional

The new modules' isolation tests forbid every sibling — and checking the precedent showed the
Phase 1 tests do not: `PartyModuleIsolationTest` forbade `identity` and `app` only, so from the
moment a third business module existed, `party` could have grown a compile-time dependency on
`kyc` with nothing failing. That edge matters most in exactly that direction: `INV-KYC-05`
makes customer status a *projection* of the KYC decision, and a `party`→`kyc` dependency is the
first step toward computing it. Both Phase 1 tests now forbid all three siblings — the
stale-list defect, in the tests that exist to catch structural drift.

### The build-logic lockfile drift, met again and reverted again

The `--write-locks` run rewrote `build-logic/gradle.lockfile` from kotlin `2.4.20-RC3` to
`2.4.20` — the `kotlinAbiValidationCompatClasspath` configuration floats to the newest kotlin,
so any regeneration on any task picks up whatever shipped since. Reverted on the `P2-TSK-001`
precedent: a toolchain version movement is not this task's dependency change. The verification
metadata did not change at all — the new modules add no artefact the build did not already
trust, which is what made a warm regeneration sufficient.

**Five probes, all caught by the intended guard** — including the cross-module dependency
caught by the isolation test itself rather than by lock resolution, and the unclassified
column caught against a real database with the mutated migration applied from scratch.
**888 hermetic tests, 471 database tests, 10 kafka tests.**

### Previously

**`P2-TSK-002` — The first consumer path: Kafka in, inbox dedupe, effect once** — `COMPLETE`
(2026-09-09). The inbox (`P0-TSK-021`) meets a real transport for the first time: a Kafka
consumer shell hands records through the flow's correlation to `InboxConsumer`, and a duplicate,
a redelivery, a crash and a rebalance all produce **one effect per fact** — proven against a
real broker, with the effect counted in a side-effect table rather than inferred from anything's
return value.

| Acceptance criterion | Evidence |
|---|---|
| At-least-once transport, exactly-once effect | The relay's own crash duplicate — two records, one `eventId` — replayed on purpose; one effect |
| Demonstrated under restart | DB commit lands, the offset commit never does (a consumer whose acknowledgements are silently lost — byte-for-byte what the broker sees from a real crash); the restarted group redelivers, the dedupe absorbs it |
| Demonstrated under rebalance | A second member joins mid-stream; four events, four effects, no `event_id` twice |
| Offset-commit-after-effect **asserted, not described** | A journalling consumer pins the order `effect-committed` → `offsets-acknowledged`; inverting it in code fails exactly that test |

### The load-bearing ordering, and what is honestly not atomic

Per record, per interested handler, one database transaction commits the effect together with
its dedupe record; **the broker offset is committed only after that**, with auto-commit disabled
because auto-commit acknowledges on the next poll regardless of what happened. The two commits
cannot be atomic and the design does not pretend: every failure between them resolves as a
redelivery into the dedupe — the safe direction — while the reverse ordering would lose records
silently. A record that cannot be handled is **seeked back to, never skipped** (the relay's
block-don't-skip rule on the consuming side: a gap in the stream is undetectable), so a poison
record stalls its partition loudly at a bounded retry rate; dead-letter tooling stays Phase 15
debt.

### The seam is Kafka-free, and the rule that guards it got its second exemption

`InboxEventHandler` and `ReceivedEvent` carry no broker type, so a consuming module never sees
the client — `P2-TSK-007` registers a bean and nothing more. The client itself lives in
`platform.inbox.kafka`, the second exemption to `NoDirectBrokerPublicationRulesTest`, whose
condition was always broader than its name: consuming directly past the inbox is the symmetric
defect to publishing past the outbox (`INV-IDEM-04` / `INV-EVT-01`). The exemption is
exact-match — the inbox *parent* package where `InboxConsumer` lives stays forbidden — and both
directions are proven by fixture, including a consumer-client probe class the producer-side
precedent now has a twin of.

### No scheduler, no lease, and why that is not the relay's exemption

Each consuming module gets one loop — a plain thread whose pacing is the poll's own bounded
blocking, so `nothingSchedulesAmbiently` has nothing to see and needed no second exemption.
Work-sharing is Kafka's group protocol; **correctness is the inbox primary key** — during a
rebalance two instances can hold the same in-flight record, and the database arbitrates, which
is the design's normal case rather than a hazard. Group offsets are registered in
`DISTRIBUTED_EXECUTION.md` §3 as explicitly non-authoritative: losing them replays the topic
into the dedupe. Groups derive from `consumerName()`'s module segment, so extraction takes a
module's offsets with it.

### The debt row whose trigger was this task, paid

`finapp.inbox.consumption` by outcome — processed, duplicate, contended, failed — registered
**eagerly** (`P1-TSK-029`'s rule), asserted at zero before any record has ever arrived. The
inbox-metrics debt row named *"the first live consumer"* as its trigger, and this is it.
`INV-MON-01` fired on `Counter.increment(double)` on the way, and the exemption entry is the
third of the **same case** — ints end to end, the double at the registry boundary only.

### The gate found two stale architecture-document claims, both left by the previous task

`EVENT_ARCHITECTURE.md` still said the transport adapter was *"deliberately absent"* and
`MODULE_ARCHITECTURE.md` §6 still said the broker-rule exemption was *"a module"* and *"still
empty after `P0-TSK-020`"* — both true when written, both stale from the day `P2-TSK-001`
landed, and neither caught by any guard, because the equivalence test pins rule names rather
than prose about their exemption sets. Corrected with provenance. One defect was caught by
review before any run: the kafka-tier probe reads used the application role against a
bootstrap-owned probe table it holds no grant on.

**Five mutations, all caught by the intended assertion** — the ordering inverted, the seek-back
removed, a contended record acknowledged, a failed handler committing its dedupe record, and the
dedupe key made per-delivery. **884 hermetic tests, 471 database tests, 10 kafka tests.**

### Previously

**`P1-TSK-033` — `POST /v1/me/credential`: a logged-in person changes their own password** —
`COMPLETE` (2026-09-09). The endpoint the plan declared for the whole phase and `P1-DOC-002`'s
recount found owned by nobody — the ninth backlog defect of that class, and the one with a real
capability gap behind it.

| Acceptance criterion | Evidence |
|---|---|
| Current password re-proven; a stolen session is not enough | `matchCurrent`; wrong current password → uniform 401, **counted toward lockout** (proven: ten wrong attempts lock, then even the correct one is refused) |
| New credential derived outside the transaction | `prepare` mints it with no connection held (`P1-TSK-026`) |
| Every other session revoked, the caller's own rotated | `ChangePasswordDatabaseTest`: other token dead, pre-change token dead, rotated token live |
| Audited against the person | `identity.CredentialChanged`, `sessionsEnded=n`, never the password |
| Ten instances → one credential | The conditional supersede is the arbiter |

### Composition, not new mechanism — and the one real decision was the plan conflict

Every piece existed: `CredentialVerifier` (gaining a `matchCurrent` that re-proves by identity with
**no upgrade-on-use**, because the credential is about to be superseded), `Credential.forPassword`,
the conditional `CredentialStore.supersede`, `SessionRevocation.revokeAllExcept`,
`SessionRotation.rotate`. The task contributed the order and the transaction — a wiring file and a
domain service, nothing more.

**The plan's endpoint row read `MULTI_FACTOR`, which taken literally makes the endpoint unreachable
for every password-only customer.** The requirement is conditional on whether a factor exists, and
a boundary annotation is static per handler — the exact `P1-TSK-019` re-enrolment finding. So the
check lives in the domain: an MFA-enrolled identity must present a `MULTI_FACTOR` session (refused
`identity.AssuranceRequired` — actionable, so distinguished from the uniform 401), and one without
changes at `PASSWORD`. `PHASE_1_PLAN.md` §7 corrected with the provenance noted.

### The rotated session is returned, and the refusals are two shapes for a reason

A password change rotates the caller's own session (`P1-TSK-015`'s fixation defence — a change is
what you do after suspecting theft, so the identifier you hold must be replaced too), so the
response carries the replacement or the customer is logged out by their own action. It reuses
`AuthenticatedSession` — the same fact, a session handed to its owner in the response that created
it — rather than a fourth near-identical record. A wrong current password, a lock, a lost supersede
race and a concurrently-killed session are **one** uniform 401; the MFA step-up is a distinct 403,
because that one is actionable.

### The gate found one test asserting less than it claimed

The wrong-current-password test asserted the FAILED audit record but not that the lockout **counter**
incremented — and the `refuse` helper writes the audit either way, so a mutation removing
`recordFailureFor` survived. Strengthened to drive the account to its lockout threshold and prove
the correct current password is then refused, which no audit assertion could have shown. **Five
mutations, all caught by the intended assertion.** And I met the v4/v7 identifier lesson twice more —
in the seeded MFA fixture — the `P1-TSK-028` finding, now thoroughly personal.

**874 hermetic tests, 471 database tests.**

### Previously


### Just completed

**`P2-TSK-001` — The broker adapter: outbox events reach Kafka** — `COMPLETE` (2026-09-09).
The events the outbox has held durably-and-unread since 2026-09-06 are published.

| Acceptance criterion (as corrected) | Evidence |
|---|---|
| One publication on the non-crash path, envelope and bytes intact | `KafkaOutboxDeliveryKafkaTest.aCommittedEventIsDelivered` — consumed off a real broker, eleven headers, verbatim bytes |
| Per-aggregate order under concurrent relays | Two relay instances, one aggregate, one partition, broker order = event order |
| Crash between ack and mark **redelivers with the same `eventId`** | The duplicate is demonstrated, not hidden — at-least-once per ADR-0005, dedupable by the inbox |
| Broker unavailability blocks, backs off, bounded | Failure recorded in `last_error` naming the class and never the payload |
| Non-loopback plaintext refused at startup | `KafkaTransportGuard` — ADR-0023's promise, kept by the task that added the client |

### The acceptance was corrected before it was met, and that is the first thing worth keeping

The backlog promised *"observable on the broker exactly once per fact"* — a promise the
`EventPublisher` port's own javadoc refuses, because an adapter claiming exactly-once invites
consumers to skip their inbox. The design corrected it to honest at-least-once and the crash test
**shows** the duplicate: same `finapp.eventId` on both copies, which is precisely what makes the
consumer inbox able to absorb it (`INV-IDEM-04`).

### The wire format, settled where two documents deferred it

Value = payload bytes verbatim; the ten envelope fields + media type as `finapp.*` record
headers, so a consumer can route and deduplicate an event it cannot parse — the envelope's own
metadata-only principle, extended to the wire; key = the aggregate, so one aggregate rides one
partition and the relay's ordering is one consumers actually observe; **one topic per producing
module**, with the revisit trigger recorded. Producer configuration (`acks=all`, idempotence,
bounded `max.block`/`delivery.timeout`) lives in the adapter's `connect` factory — **the
composition root passes strings and never sees a Kafka type**, which is what let the broker
rule's exemption stay one package wide.

### Two build rules were modified, and each modification carries its own proof

**The broker rule's exemption was narrowed from the recorded "module" to the outbox package.**
The original javadoc chose module granularity; adding the exemption showed that would have let
every platform concern — audit writer, API layer, correlation kernel — touch a broker client
silently. Package granularity keeps the recorded reason (the relay may grow classes) and a
sibling-package violation fixture proves the precision in both directions.

**`nothingSchedulesAmbiently` gained its first exemption**, and it is the case the rule's own
`because` clause carves out: `OutboxRelaySchedule` polls on every instance **deliberately** —
no leader — because each poll takes the rule's required lease, the per-aggregate advisory lock,
in PostgreSQL. Register row in `DISTRIBUTED_EXECUTION.md` §3; proven load-bearing in both
directions; the set names classes, never packages, so the next scheduler is a decision.

### The background worker meets the test suite as a choice, not a leak

`EventingBeans` would have started a live relay under every `@SpringBootTest`, mutating outbox
rows mid-assertion. The schedule is property-gated (`matchIfMissing = true`, so a deployed
instance polls unconfigured) and the app test suite disables it in an
`application.properties` overlay — a `.properties` file deliberately, because a test-resources
`application.yaml` would shadow the real one. The kafka tier runs the schedule on purpose,
which is the difference between disabling a control and choosing when a worker runs.

### Three findings against my own work, en route

Kafka 4.x refused my `delivery.timeout = request.timeout` equality (linger's default is no
longer zero) — found by constructing a producer, not by reading. My first
return-before-ack mutation was caught by **compilation** (orphaned catch clauses) and was
rewritten to compile, whereupon `theWaitIsBounded` caught it — the `P1-TSK-026` rule applied to
this gate's own sweep. And I met the v4/v7 identifier lesson (`P1-TSK-028`) personally:
`UUID.randomUUID()` in a fixture, refused as malformed by `EntityId`.

### Debt movements

The broker-adapter row **closes** (trigger reached 2026-09-06, paid here). The relay-metrics row
**pays in full**: `finapp.outbox.publication` by outcome, eager, fed from `RelayPollResult`,
asserted present before any flow. The Kafka-plaintext row's premise changed: the first client
arrived **with its guard**, and the row narrows to Redis plus the deployed-TLS posture
(Phase 15). Nine inert `kotlin-2.4.20` verification entries from an aborted `build-logic`
resolution are recorded as explained: trust entries for artefacts nothing resolves, left because
regeneration merges and never prunes (§7a's recorded behaviour).

**Five mutations, all caught by the intended assertion.** 874 hermetic tests, 465 database
tests, and the new **kafka tier: 5 tests** against a real broker.

### Just completed

**Phase 1 → Phase 2 transition** — **CONDUCTED** (2026-09-09).
[`reviews/PHASE_1_TO_2_TRANSITION.md`](reviews/PHASE_1_TO_2_TRANSITION.md)

| Part | Outcome |
|---|---|
| Phase 1 completion audit, 17 categories | **17 `PASS`** (operational readiness scoped to Phase 15's remit) |
| Distributed-system audit | **No single-instance assumption found**; the seven questions answered with mechanisms, not adjectives |
| Security audit | Pass, with five weaknesses stated and owned rather than glossed |
| Architecture consistency | No drift in the architecture; **four decays in the governance record**, fixed |
| Testing audit | 864 hermetic + 465 database tests green, fresh run; adequacy argued from the mutation register and coordination-asserting tests, not from green |
| Phase 1 verdict | **`COMPLETE`** (confirming `P1-DOC-002`) |
| Phase 2 entry gate | **All twelve criteria hold → `READY`** |

### The transition's own finding: the gate machinery would have broken at the boundary

Both phase-derived guards keyed on the highest phase **named** in this document. Naming Phase 2 —
this transition's required act — would have demanded Phase 2's planned meters and invariant
demonstrations **before any Phase 2 code exists**, and `PlannedMetersExistTest` would have
silently **stopped checking Phase 1's plan** the same moment. Both now key on phases recorded
**`COMPLETE`**: the status flip is the guarded act (criteria 3 and 6 enforced at exactly the
moment they apply), and every completed phase's plan stays checked for ever. Proven in both
directions by probe — `READY` widens nothing, a simulated `COMPLETE` fails both guards.

**And the probe's first run passed against a build that had not run**: neither this document nor
the phase plans were declared `:app:test` inputs, so the guards that derive the phase from them
could go stale against them — the `P0-TSK-023` class, in the two newest document-backed guards.
Both are inputs now.

### What the transition produced

The Phase 2 plan (`PHASE_2_PLAN.md`); ADR-0035…0038 (`Proposed`); the `INV-KYC-01`…`06` and
`INV-CNS-01`…`04` groups — Phase 2's six prose gate bullets given stable IDs, ranked enforcement
and named verification, the Phase 0 → 1 precedent, taking the platform to **82 invariants**; 24
backlog items across six milestones with the two exit-review leftovers scheduled first; and the
governance-record repairs above. **No application code was written**, which is the constraint a
transition is performed under.

### Previously

**`P1-DOC-002` — the Phase 1 exit review, re-run** — `COMPLETE` (2026-09-09). **The gate passes
and Phase 1 is `COMPLETE`.**

| | Outcome |
|---|---|
| Universal criteria (12) | **12 `PASS`** — criteria 1 and 6 re-assessed against the code, the other ten re-checked |
| Phase 1-specific (6) | **6 `PASS`** — the `PARTIAL` closes, every registered action emitted or declared |
| **Verdict** | **Phase 1 `COMPLETE` (2026-09-09)** |

### Everything recounted, nothing inherited — and the recount earned its keep

The first review had three numbers wrong for quoting them, so the re-run counted: **17** endpoints
(12 at the review), **20** auditable actions, **864 + 465** tests, 10 tables, 8 aggregates, 72
invariants. Counting the endpoints against the plan's §7 table is what surfaced the finding below.

### The ninth backlog defect of the class, found in the review's own table

**`POST /v1/me/credential` is declared by the plan, built by nothing, and owned by nobody** — and
the first review's area 7 table had attributed it to *"`P1-TSK-026` (`TODO`)"*, an item whose
description reads *"Extend `POST /v1/registrations`"* and never included it. **A false owner is
worse than no owner**, for the reason a false exemption is worse than none: it reads as handled, so
nobody asks. The review that found the eighth defect of this class committed the ninth in the same
table.

The capability gap is stated honestly: a person with a stolen password and **no verified channel**
cannot replace their credential through the platform — revocation ends the attacker's sessions, not
their knowledge, and recovery needs a channel registration does not create. Recorded as
**`P1-TSK-033`**, scheduled by the Phase 1 → 2 transition; it blocks no criterion, by the review's
own `P1-TSK-028`/`-030` precedent. And `IdentityAdministration`'s javadoc — which cited *"a
credential change"* as an incident-response tool — is corrected: **the ninth javadoc this phase to
assert something the code does not do.**

### The broker adapter was ruled on rather than stepped around

`PHASE_1_PLAN.md` §12 calls it a *"genuinely required minimal foundation"* and it does not exist.
The re-run rules it non-blocking, with the reasoning in the addendum: the phase objective needs no
event delivery; the events are durable and unread (`INV-EVT-01` holds); the load-bearing half of
§12's own reasoning — the wire format — **was** delivered (`EventPayload`, `P1-TSK-006`); and an
adapter with no consumer anywhere cannot be exercised end to end, which is the exit gate's own
standard for a deliverable. The plan is corrected where it was wrong, and ownership passes to the
Phase 1 → 2 transition — `DELIVERY_PLAN.md` §Phase 2.8 holds the first consumers.

### Three debt rows owned by "Phase 1" resolved, because a `COMPLETE` phase cannot own open debt

Broker adapter → the Phase 1 → 2 transition. Registration throttling → **Phase 15**, merged in
argument with per-source rate limiting, because the missing input is identical — a deployment
topology and a trusted-proxy declaration, an unauthenticated endpoint having no identity to key on.
And the loopback-credential row's trigger — *"the second credential"* — was **reached and
handled**: `P1-TSK-017`'s MFA key carries its own tested loopback confinement, so the remaining
generalisation is re-owned to Phase 5, the third credential.

### Previously

**`P1-TSK-032` — Reinstatement: the other half of suspension** — `COMPLETE` (2026-09-09).
`DELETE /v1/identities/{id}/suspension`, and suspension stops being a one-way door.

| Acceptance criterion | Evidence |
|---|---|
| A negative authorization test | `reinstatementRefusesWithoutTheRole` — 403, and the refusal audited |
| Non-`SUSPENDED` reinstatement is a conflict | `ACTIVE` and `CLOSED` both 409, and `CLOSED` stays closed |
| The audit record names the administrator | `identity.IdentityReinstated`, actor ≠ subject, reason carried |
| Ten instances produce one transition | `concurrentReinstatementsProduceOneTransition` |
| A reinstated identity can authenticate again | Driven end to end, over HTTP, with a registered person |

### The acceptance had to be driven with a registered person, not a fixture row

*"Can authenticate again afterwards"* is unreachable from the suite's usual fixture — a directly
inserted identity has no credential and could never authenticate in the first place, so the test
would have asserted an absence it could not distinguish from the defect. The subject is registered
over HTTP, logs in, is suspended (login refused), is reinstated, and logs in again.

**And the pre-suspension session stays dead.** The suspension revoked it, revocations do not
un-happen (`INV-HIST-01`), and a resurrected bearer token would come back to life in whoever's
hands last held it — possibly the attacker whose activity is why the account was suspended.
Reinstatement restores the ability to log in, never the sessions.

### Both self-refusal decisions were revisited together, as the backlog required

**Self-suspension stays refused, on a corrected argument.** The recorded reason was the one-way
door, and reinstatement removed it — but only conditionally: the door is two-way when a *second*
administrator exists, and the platform does not guarantee one. The last administrator
self-suspending is still locked out, with the remedy being `README.md` §5e's out-of-band action.
The trail argument is untouched.

**Self-reinstatement gets its own `SELF` branch, and it is nearly dead code — deliberately.** A
suspended identity holds no live session, so a person cannot present a session while `SUSPENDED`
except in the race where they are suspended mid-request. The branch is kept for that race and for
the property the class protects: no administrative record ever names one party twice. Tested at
the domain, because HTTP cannot reach it.

### One permission for both directions, and a DELETE that carries a body

**The permission is `IDENTITY_SUSPEND`** — `ROLE_ASSIGN`'s own *"grant or revoke"* shape. The
administrator trusted to impose a suspension is the administrator trusted to lift one, and a third
permission held by the only role that exists would be vocabulary with no decision behind it.

**The reason travels in the request body of a `DELETE`**, which is unusual and correct: it is
required — a quiet reinstatement is how an accomplice undoes an incident response — and it is free
prose that may name a person or an incident, so a query parameter would put it into access logs,
proxies and browser history (`INV-AUD-02`).

### `NOT_SUSPENDED` is named for what is checked

`ACTIVE` and `CLOSED` both land on the 409, and only the first could honestly be called *"already
done"* — the `NOT_ACTIVE` lesson from `P1-TSK-028`'s gate, applied at design time rather than
found by it. `CLOSED` is terminal (`INV-LIFE-04`): the conditional `moveStatus(from = SUSPENDED)`
refuses it at the write, the aggregate independently (`INV-LIFE-02`), and the test proves the
status afterwards rather than only the response code.

### The guards this endpoint met, and what each demanded

`OpenApiContractTest` presented a 14-line diff — all additions, zero removals — whose three
`BREAKING` labels are the classifier erring safe on `required` fields of a brand-new schema, the
`P1-TSK-006` precedent. `CredentialReachesNoEmittedSinkTest` refused the new request body until it
was declared in the bounded exemption list. `AuditableActionRegistryTest` and
`AuditCompletenessTest` required `identity.IdentityReinstated` to be catalogued and emitted before
the build would pass. `PHASE_1_PLAN.md` §7 gains the endpoint row with its provenance stated, so
`P1-DOC-002`'s recount counts it rather than trips over it.

**Four mutations, all caught by the intended assertion** — the wrong from-status, the `SELF` check
removed, the audit call removed, the permission annotation removed. 864 hermetic tests, 465
database tests.

### Previously

**`P1-TSK-031` — Two fixtures read `now()` twice and assume it moves forwards** — `COMPLETE`
(2026-09-09).

| Acceptance criterion | Evidence |
|---|---|
| The suite is not sensitive to a backwards clock correction | Every insert-then-update fixture back-dates its INSERT; a simulated 30-minute correction passes |

**The shape was in five files, not two** — surveyed rather than trusted. The named
`AuthenticationCostsTheSameDatabaseTest`, plus `AuthenticationEndpointDatabaseTest`,
`CredentialVerificationDatabaseTest` and `RecoveryAbuseDatabaseTest` against
`identity_status_change_is_not_before_creation`, and `PartyAndIdentitySchemaDatabaseTest`'s
customer fixture against its twin `customer_status_change_is_not_before_opening`. Every other
timestamp-ordering constraint in all three schemas is reached only by a single statement, an
already back-dated write, or one a privilege refuses — so the exposure ends here.

**The INSERT is back-dated by one hour and the UPDATE stays at `now()`.** The update models what a
production write does and stays honest; the backlog's alternative — both columns from one
statement — cannot fix an insert-then-*update* pair, whose two clock reads are in different
statements by construction. One hour dwarfs the observed 225–429 ms corrections.

**Proven in-suite, with its own vacuity control.** One test rather than several, deliberately:
a back-dated row accepts an update whose `status_changed_at` is thirty minutes in the past —
absurdly worse than reality — and the same update against a row written at plain `now()` is still
refused with `23514`. A pass therefore proves the back-dating is load-bearing *and* the constraint
is alive; without the second half, a dropped constraint would satisfy the first.

**No build rule scans test sources for the pattern**, recorded rather than glossed: a Low-risk
`Cx: S` fixture item does not buy machinery (`EXECUTION_PROTOCOL` rule 4, the `P1-TSK-025`
precedent) — the remedy is a convention with three precedents and one test that demonstrates it.

864 hermetic tests, 460 database tests.

### Previously

**`P1-TSK-030` — `GET /v1/me` and `PATCH /v1/me`** — `COMPLETE` (2026-09-08). The last endpoint the
plan declared and nobody owned.

| Acceptance criterion | Evidence |
|---|---|
| Both exist, ownership enforced | `ProfileEndpointDatabaseTest`; and the endpoints take no identifier at all |
| An audit record for the change | Named against the person, with the **field** and not the value |
| A control character refused at the boundary | `422` naming the field, and nothing stored |

### Ownership is enforced by there being no parameter, and that changes what the test can be

Neither endpoint takes a path variable, a query parameter or a body field naming a party. ADR-0031's
defect is *trusting an identifier out of the request*, and here **there is none to trust** — the
chain is `Session.identityId() → Identity.partyId() → PartyId`, entirely derived.

So an attacker cannot name a victim, the usual negative ownership test is **impossible to write**,
and the test proves the *resolution chain* instead: two identities, each reading and writing exactly
their own party. That is a weaker shape of test for a stronger shape of control, and saying so is
better than implying the two are the same.

### The catalogue description promised what the classification forbids

`PartyAuditAction.PARTY_PROFILE_CHANGED` read *"recording what was held before and after"*. Those
values are display names — **`RESTRICTED-PII`**, and `DATA_CLASSIFICATION.md` calls
`party.display_name` *"the clearest RESTRICTED-PII column on the platform"*. `change_summary` is
**`RESTRICTED-FINANCIAL`**.

**Those are peers, not a hierarchy.** A name written there sits outside the PII rules — retention,
subject access, erasure — and ADR-0022 is explicit that a column cannot be reclassified once it
holds data. The record says *which field* changed, by whom and when; `PartyRegistration` had already
made exactly that choice, and this task's description contradicted it. Corrected in the enum and in
`AUDITABLE_ACTIONS.md`.

**The consequence is stated rather than glossed**: there is no name history in Phase 1 and this does
not create one. *Who changed it and when* is an audit question and is answered; *what it used to be*
is a history question the plan asks for no capability to answer.

### `AUTHORITATIVE_ID` was tried, and the guard refused it — correctly

The read in the chain is `JdbcIdentityStore.findById`, which **`P1-TSK-028` classified
`ADMINISTERED`** because an administrator names its subject from a URL. Citing it as an
owner-constrained read would have been false, and `OwnershipIsScopedTest` said so in those words:
*"every operation citing it inherits the gap."*

A sixth class, **`SESSION_DERIVED`**, records what is actually true — the identifier comes from a
proven `Session` **held in memory**, which is `P1-TSK-021`'s recorded uncheckable case arriving:
*"SessionRotation holds a proven Session object rather than reading one, so there is no statement to
inspect."* Its entry names the **endpoint** rather than a read, and a new assertion checks the one
mechanically checkable thing that is also the real control: that endpoint's handlers accept **no
request-supplied identifier**. An endpoint with nothing to name a resource with cannot be pointed at
somebody else's.

### Two guards were written to break on this day, and both did

`partyHasNothingToScope` asserted that `party` owned no resource-scoped operation. And
`PartyAndIdentitySchemaDatabaseTest`'s grant assertion said, in as many words, *"nothing about a
party changes yet; **the grant arrives with the capability**"*. Neither quietly widened; both broke.

### `PATCH` failed with SQLState 42501 before a line of it had been reviewed

The application role had no `UPDATE` on `party.party` — `V002` was written when nothing ever changed
one. That is `P0-TSK-022`'s privilege model working: the grant *is* the enforcement, so widening one
is a migration with a stated argument rather than a line in a service class.

**`V004` grants `UPDATE (display_name)` and nothing else.** `kind` and `registered_at` stay
unwritable, because they are facts rather than fields — what a Party *is*, and when it came into
existence. Column-level is the mechanism `P0-TST-007` found can widen a privilege **invisibly**;
used here deliberately to *narrow*, and the assertion that checks its narrowness is what makes that
visible to a reader auditing table privileges.

### Absence and explicit null are the same thing, and the limit is recorded rather than found later

A record cannot distinguish *field absent* from `"displayName": null`. That costs nothing while
`display_name` is `NOT NULL` and can never be cleared — both are refused, one `422`. **The first
genuinely nullable field cannot be expressed by this shape** and needs a wrapper type or JSON Merge
Patch, which is a decision for the task that has one rather than machinery built now.

### The completion gate found a javadoc of mine asserting the opposite of what the code does

`updateProfile` said it returns the profile because <em>"the value is normalised by `PartyName` on
the way in, so it is not necessarily what was sent"</em>. **`PartyName` normalises nothing**, and its
own documentation refuses to in as many words: *"Sanitising input at construction to defend an output
is how a value gets silently corrupted for every consumer to protect one."*

**The eighth javadoc in this phase to assert something the code does not do**, and mine again. The
decision stands on a different and true argument — a `PATCH` that returned nothing makes a client
guess, and it cannot assume the stored value equals what it sent — so the reason was corrected rather
than the behaviour.

### And one test replaced several

Eight `PATCH` body shapes were driven and **none produces a 500** — the probe `P1-TSK-010`'s gate
established. It earns its place twice, because it is also where the **absent versus explicit null**
decision is actually checked rather than only documented: a record cannot distinguish them, and both
must be the same `422`. One test rather than eight, deliberately — the property is *no shape is our
fault*, and splitting it per shape multiplies assertions without adding a claim.

It also showed `{"displayName": 12345}` is **coerced and stored as `"12345"`**, which is Jackson
doing what `SensitiveSerialization` documents for the same reason and is a valid display name by
every rule that applies to one.

**Seven mutations, all caught.** 864 hermetic tests, 459 database tests.

### Previously

**`P1-TSK-028` — The two administrative endpoints** — `COMPLETE` (2026-09-08). The only two
endpoints in the phase behind `@RequiresPermission`, and the two `PHASE_1_PLAN.md` §7 listed that
nobody owned.

| Acceptance criterion | Evidence |
|---|---|
| Both refuse a session holding no role | `bothEndpointsRefuseASessionWithNoRole`, with positive controls |
| Each refusal is audited | `everyRefusalIsAudited`, against the person who attempted it |
| A negative **ownership** test per endpoint | Self-suspension and self-elevation, each its own test |
| Ten instances produce one transition | `SuspensionConcurrencyDatabaseTest` |

### The blocking finding: suspension did not suspend anybody

`JdbcSessionStore.findByToken` filters on the **session's** status and never joins
`identity.identity`; `CredentialVerifier` refuses a suspended identity only at *authentication*. So
a suspension stopped the next login and left the session an attacker is holding **working until its
absolute bound expired** — while the administrator received a success response.

`suspend` now revokes every session in the same transaction, which is `INV-IDN-03`'s reasoning: an
eventually-revoked session is an unrevoked session. Asserted with the **same token** across the
suspension, because a fresh one would prove only that a suspended identity cannot log in — which was
already true and is not what suspension is for.

**Joining identity status into the session lookup was the alternative and was rejected**: a second
table in the hottest query on the platform, on every authenticated request, to enforce once per
request what a revoke enforces once per decision. Revocation is also the honest model — the sessions
really are over.

### Ownership is inverted, and that is the shape of the whole task

Everywhere else in `identity` the rule is *the resource must belong to the caller*. Here it is the
opposite — **the subject must not be the actor** — and it cannot live in `@RequiresPermission`,
which is static per handler and knows nothing about which identity the path names.

**What refusing self-elevation buys is stated honestly rather than overclaimed.** It is **not** a
containment control: an administrator holding `ROLE_ASSIGN` can escalate through a second account
they control, and nothing here stops that. What it buys is that the trail **never contains a
self-loop** — every escalation names two parties, and a self-grant reads like a system action rather
than a decision somebody took. That is `INV-AUD-04`'s four-eyes principle pointing the same way,
with four-eyes itself still unbuilt.

Refusing self-**suspension** is a different argument: there is no reinstatement endpoint, so it is a
one-way door out of the platform. That absence is now `P1-TSK-032` rather than a silent gap.

### `OwnershipIsScopedTest` gained a fifth class, because this task broke its assumption

It excluded `IdentityId` from being a *resource* identifier on the reasoning that it **is** the
owner. True of every operation written before — a customer acting on their own identity — and false
of an administrative one, where the identifier comes from a URL and names a different person.

The exclusion is now conditional on the statement reaching `identity.identity` **by primary key**,
and three methods are classified `ADMINISTERED`, each naming the check that stands in for the
missing ownership predicate. `lockIdentity` is labelled by the **weaker** of its two provenances,
because a label must be one thing and naming the safer path would describe the caller that needs no
protection.

### A pre-existing blind spot in that rule, closed

`statementOf` read only a method's own string literals, so a statement built from a **table-name
constant** put `identity.identity` nowhere and `referencesTheOwner` could not see an owner that was
plainly there. It had never been reached, because every earlier statement happened to mention
`identity_id` in a literal of its own.

Inlining the constant to satisfy the detector was the alternative — a change made to please a rule
rather than to state a property, which is the failure this class exists to prevent, one level up.

### The first administrator cannot be created through the API, and that is a decision

`ROLE_ASSIGN` is held only by `ADMINISTRATOR`, so the first assignment cannot come through the
endpoint that requires it. A **bootstrap endpoint** would be a privileged surface with nothing in
front of it; a **seeded migration row** would put an administrator into every environment including
production, permanently. So it is an out-of-band operator action, documented in `README.md` §5e —
and the consequence is recorded rather than hidden: **that first grant has no actor in the audit
trail**, because no authenticated actor performed it.

### A mutation survived and found a test passing for the wrong reason

`anUnknownSubjectIs404` used `UUID.randomUUID()`, and `IdentityId.of` validates **UUIDv7**
(`P0-TSK-012`) — so a v4 was refused as **malformed** and never reached the service. The test proved
only that a v4 is rejected, and the endpoint's actual behaviour for an unknown-but-well-formed
identity was untested in both endpoints.

**Established by tracing rather than by reasoning**: four hypotheses were wrong before the server's
own log line — *"A malformed identity identifier was presented"* — settled it. Driven with a
well-formed identifier now, and the mutation is caught.

### The fixture met the documented clock drift, and the constraint was right

`identity_status_change_is_not_before_creation` refused a row whose `created_at` came from the
**container's** clock while the suspension wrote `status_changed_at` from the **JVM's**. That is
`P1-TSK-031`'s finding, met here for the first time by production code rather than by another
fixture. Both fixtures back-date explicitly — `OutboxRelayTest.backDate`'s established remedy.

### The completion gate found an outcome name that can be false, and a claimed test that did not exist

**`Suspension.ALREADY_SUSPENDED` was a claim rather than a description.** A `CLOSED` identity
reaches that branch too, and it is not suspended — it is gone permanently, so a caller reading
*"already suspended"* would conclude the operation had effectively succeeded. The client detail had
been accurate all along; only the enumeration lied. Renamed to `NOT_ACTIVE`, named for what is
**checked** rather than for the commonest cause, and the `CLOSED` path now has its own test.

**`SuspensionRequest`'s javadoc said *"a test asserts they still agree"* about the reason bound, and
no such test existed** — the **seventh** occurrence of that pattern in this phase, after `V005`'s
enum claim, `AuthenticationRequest`'s bounds claim, `RequiresSession`'s fail-closed claim,
`secretsAreWrapped`'s exemption, `V010`'s migration test and `AuditCompletenessTest`'s false
exemption. The drift is not cosmetic: a boundary wider than `AuditRecord.MAX_REASON_LENGTH` would
fail at the **last write**, as a 500, after the status transition and the session revocations had
already been performed inside a transaction that then rolls back.

**And the test's first version failed on correct code**, which is the more useful outcome: `@Size`
does not declare `RECORD_COMPONENT` among its targets, so the compiler propagates it to the field
and not to the component — a component-level lookup returns null for a constraint that is present
and working.

**The closed tag vocabulary caught the new test too.** `@Tag("unit")` is not a tier: the default
tier selects by **exclusion**, so a unit test carries no tag at all. `P0-TSK-036` closed that
vocabulary precisely so a plausible-looking tag cannot schedule nothing, and it worked.

**Nine mutations, all caught** — two added by the gate. 863 hermetic tests, 453 database tests.

### Previously

**`P1-TSK-026` — Registration takes a credential** — `COMPLETE` (2026-09-08). The bootstrap gap
`P1-TSK-006` recorded against itself is closed: a person who registers can log in.

| Acceptance criterion | Evidence |
|---|---|
| Exactly one active credential | Asserted over HTTP, and by **using** it to authenticate |
| The plaintext is in no persisted or emitted representation | Every column of every table in all three schemas, derived from `information_schema` |
| The fingerprint is unchanged by the password | Structurally — `canonicalForm` cannot see one — and behaviourally |
| Equivalent work for a taken and a free identifier | One derivation each, counted |
| The `openapi.json` diff labelled `BREAKING`, decision recorded | Two lines, reviewed; the reasoning is on the controller |

### The headline is asserted end to end, because the defect closed was a gap between two working halves

`P1-TSK-006` created a Party, a Customer and an Identity and no credential, and **every suite
passed**, because each half was tested against its own fixture. `P1-TSK-027` met the same shape one
layer up. So *"a credential row exists"* is deliberately **not** the headline assertion here — it
passes against a credential stored under the wrong identity, the wrong algorithm, or a status
nothing can verify, and the `SUPERSEDED` mutation proves that is not hypothetical.

`aRegisteredPersonCanAuthenticate` registers over HTTP, logs in over HTTP with the password it
registered, and opens `GET /v1/sessions` with the token that comes back. Nothing in that chain is
inserted by the test, and `aFabricatedPasswordOpensNothing` is its negative control.

### The derivation is structurally unconditional rather than balanced

`IdentityRegistration.prepare` mints the `IdentityId` and derives; `create` takes the result. **You
cannot call the second without having called the first**, so no database outcome can decide whether
the expensive work happens. A balanced pair of code paths is one a later author optimises away —
the same preference as `P1-TSK-027`'s *the level is not a parameter*.

Minting the identifier early is what that costs, and it is unremarkable rather than a concession:
ADR-0013 makes identifiers application-minted, so one that is minted and discarded costs nothing.
**Splitting `Credential.forPassword` back apart was refused** — `P1-TSK-007`'s gate removed that
split precisely because a caller could pass parameters that did not produce the derivation.

### The item's stated reason for that ordering is weaker than it reads, and the correction is recorded

The backlog calls it an enumeration control. **A success answers `201` and a collision `422`, in one
round trip** — already distinguishable, and necessarily so, because an endpoint that claims a name
must say when the name is taken. Equal work is defence in depth here; `P1-TSK-006`'s real property
is the narrower one it asserts, that a collision is indistinguishable from *any other refusal*.

**The load-bearing reason is operational.** ~46 ms of CPU and ~19 MiB per derivation (ADR-0032) must
not be paid while holding one of eight pooled connections (`P1-TSK-004`), or a registration flood
becomes connection-timeout errors pointing at a database that is perfectly healthy.
`theDerivationIsOutsideTheTransaction` is the only test that catches `prepare` being moved beside
the insert it feeds — every other assertion still sees exactly one derivation.

**And the debt row was updated rather than left**: this endpoint is now the same CPU-and-memory
amplifier `POST /v1/authentications` is, and unlike that one it needs no existing account.

### An Identity is no longer constructible without a credential

The credential-less path is **removed**, not deprecated: leaving it would leave the defect reachable,
and Phase 1 has no legitimate caller for it.

### The fingerprint decision was kept, and its consequence is stated rather than inherited

`canonicalForm` takes no password, asserted **structurally** — there is nothing to vary, so the only
way to break the property is to change the signature, and a second *overload* is caught too.

The consequence, now written down and asserted behaviourally: **a retry with the same key and a
different password replays** rather than being refused as an `INV-IDEM-03` conflict. That reads as a
weakening and is the right trade — the alternative stores an offline-crackable derivation of every
registration password for the life of the record (`INV-IDN-01`) — and the residual is bounded by the
empty response body, so a caller who changed the password learns only that the request succeeded.

### A short password is a 422, which is the opposite of authentication's answer

There a short password is an ordinary authentication failure, because a second response shape is an
enumeration risk. Here it is a value the caller **chose** and must be able to correct, and the
refusal is decided before any lookup, so it discloses nothing about any account.

**`@Size` could not express it.** Bean Validation cannot see inside `Sensitive`, and a constraint
that unwrapped it would put a plaintext in `app` — which `SecretsAreUnwrappedInOnePlaceTest` would
fail the build over, correctly. So `RegistrationService` maps `RawPassword`'s refusal, writing the
client detail rather than passing the exception's message on, so a future change to that message
cannot become a change to what a stranger is told.

### The leak sweep covers every table in every schema

`P1-TSK-007` proved the credential row does not hold the plaintext. What this task adds is a password
crossing an **HTTP boundary** into an idempotency record, an audit record and an outbox row — three
sinks credential storage never touched, two of which reach systems with different access control
(`INV-AUD-02`). The table and column lists are derived from `information_schema`, so a table added
in Phase 2 is swept without anyone remembering.

### The mutation harness reported seven proofs it had never measured

**The most instructive failure of this task, and again it was in the machinery rather than the
work.** The harness invoked `./gradlew.bat`, which cmd answers *"'.' is not recognized"* with exit
1 — so every mutation read as `CAUGHT` against a build that had never run. `P1-TSK-027`'s finding in
a new disguise, and found the same way: by asking **why** a mutation was caught rather than trusting
the verdict.

The harness now asserts the build actually started, and prints the test that failed. All seven were
re-run, and **two were rewritten** because the corrected harness showed they were caught by
**compilation** rather than by an assertion — removing the `prepare` call does not compile, and
changing `canonicalForm`'s signature breaks its callers. Neither proved anything about the assertion
it was aimed at. Restated as a `prepare` wrapped in a transaction and a second `canonicalForm`
overload, both compile and both are caught by the intended test.

### The completion gate found two claims nothing asserted, and one probe of mine that was wrong

**The credential identifier was put into the audit change summary and the event payload, and
nothing looked at either.** An investigator asking *which credential did this registration produce?*
should read one row rather than join by timestamp — and a summary that silently stopped naming it
would read exactly like one that never did. Asserted as the **property** rather than as a rendering,
because a free-text summary is searched by substring and never by equality; that is `P1-TSK-027`'s
finding, where a test pinned a rendering and the test was the thing that was wrong. Both mutations
are caught.

**No password shape produces a 500**, driven over real HTTP across eleven shapes — the probe
`P1-TSK-010`'s gate established for authentication, applied to the endpoint that just gained a
secret. Two are decided by **different mechanisms** and both are now pinned: a number is *coerced*
and succeeds, while an object never reaches the deserialiser at all, so it is a `400` rather than a
`422`.

**And the coercion is now asserted to be symmetric across the two endpoints**, which nothing had
checked because until this task there was no registration secret to be asymmetric with. The failure
it forecloses is a customer who registers successfully and can never log in — the exact state this
task exists to close, arriving through a different door.

### The gate's own first probe was wrong, and finding that out is the point of running one

It reported that a password containing a NUL, a newline or a tab is refused with `400`. It is not:
**the Java source held real control characters**, so the request body was invalid JSON and every
answer was about the document rather than about the password. That is `P1-TSK-016`'s finding
reproduced by me, and Java's lexer makes it awkward to avoid — `\uXXXX` is processed *before* string
escapes, so the obvious spelling does not mean what it reads as.

**Recorded rather than worked around**, on that task's own reasoning: what HTTP can drive is
asserted, and the character-level question belongs to the domain type rather than to the boundary.
The claim it would have supported is unnecessary anyway — the plaintext is never persisted, only an
ASCII Argon2 encoding of it, and both endpoints share one DTO, one deserialiser and one
`RawPassword`, so anything registrable is authenticatable by construction.

**Eleven mutations, all caught.** One of them, an unwrapped `Sensitive<String>` on the DTO, is
recorded as caught by **compilation** rather than by `secretsAreWrapped` — so a second, compiling
mutation was written to prove the rule actually covers this type, and it fires.

860 hermetic tests, 438 database tests.

### Previously

**`P1-TSK-025` — the `architectureTest` tier runs none of the `@ArchTest` rules** — `COMPLETE`
(2026-09-08).

| Acceptance criterion | Evidence |
|---|---|
| A `double` planted in production code fails `./gradlew architectureTest` | Exit **0** before the fix, **1** after — same planted code, same command |

### The rules were not missing from the tier. They were in the wrong one.

The item recorded them as absent. Measured, they were **running in `unitTest`** — which selects by
*exclusion* and therefore takes anything carrying no tag, while `architectureTest` selects by
*inclusion* and got none of them.

| Suite | `architectureTest` | `unitTest` |
|---|---|---|
| **`ModuleBoundaryRulesTest`** | **no result file at all** | 8 |
| `NoFloatingPointMoneyRulesTest` | 2 | 5 |
| `NoSingleInstanceAssumptionRulesTest` | 8 | 5 |
| four others | — | 10 |

**28 rule fields, and `ModuleBoundaryRulesTest` did not appear at all** — it has no `@Test` method,
so it was not a suite reporting zero cases, it was a suite that was not there. That is the oldest
rule suite in the repository, the one enforcing `app → platform → sharedkernel`.

### Root cause, established by disassembling the engine rather than reading documentation

`javap` on `AbstractArchUnitTestDescriptor.findTagsOn`:

```
1: ldc  #81   // class com/tngtech/archunit/junit/ArchTag
```

**ArchUnit's engine reads `@ArchTag` and nothing else.** JUnit's `@Tag` is invisible to it, so every
`@ArchTest` field carried no tag. The fix is `@ArchTag("architecture")` beside the existing
`@Tag("architecture")` on all seven `@AnalyzeClasses` suites — the mechanism ArchUnit provides for
exactly this, never used here because nobody had asked what its engine does with a tag.

### Why no guard saw it, and this is the part worth keeping

`theTiersPartitionTheHermeticSuite` asserts a **sum**, and the sum was right: every rule was in
exactly one tier. **A check on a total cannot see a misallocation that preserves the total.** Same
class as `P1-TSK-024`'s register rows the parser silently skipped and `P0-TST-008`'s rule that could
not fail — a control reporting coverage it does not have.

### The guard states the property, and its limit is recorded rather than left to be found

`everyArchUnitSuiteIsTaggedForBothEngines` requires the `@Tag` and `@ArchTag` value sets to be
**equal** for every `@AnalyzeClasses` class — *both engines must agree which tier this class is in*,
rather than *the fix has been applied*.

It does not assert that ArchUnit reads `ArchTag`; that is a fact about a dependency and checking it
would mean disassembling one on every build. **If ArchUnit ever read `@Tag`, this guard would demand
an annotation that had become unnecessary — and that is the direction to err in.** A false
requirement is a build failure somebody investigates; a false pass is silence (`P0-TSK-026`'s
reasoning for the contract classifier).

### A better guard was investigated and rejected on a measured fact

Discovering with `includeTags("architecture")` in-process, through the JUnit Platform Launcher, is
the property itself rather than a proxy for it. **`junit-platform-launcher` is not on
`testRuntimeClasspath`** — verified, because Gradle injects it into the worker separately — so it
would need a new dependency plus verification-metadata and lockfile regeneration. Disproportionate
for a Low-risk `Cx: S` item (`EXECUTION_PROTOCOL` rule 4), and recorded rather than silently not
done.

### The gate met the documented clock drift, and checked before calling it a flake

The database tier failed twice during this task's gate and neither failure was the work.

**First, Testcontainers' Ryuk reaper could not start** - a Docker-side condition; the same tier had
passed minutes earlier.

**Then a `CHECK` constraint fired on a fixture**, and that one was diagnosable:
`identity_status_change_is_not_before_creation` refused a row whose `status_changed_at` was **225 ms
before** its `created_at`. `CURRENT_STATE.md` §Local Environment Prerequisites says to check
`SELECT now()` before treating such a failure as a defect, so it was checked: three successive
container `now()` calls came back **429 ms apart** while three host `date` calls were 33 ms apart.
The container's clock runs fast and is corrected backwards, exactly as recorded.

**The constraint is right; the fixture is fragile.** `AuthenticationCostsTheSameDatabaseTest.suspend`
inserts with `created_at = now()` and then updates `status_changed_at = now()` - two statements, two
clock reads, and nothing requiring the second to be later. The tier passed on re-run.

**Recorded as `P1-TSK-031` rather than fixed here**, which is this task's own precedent: `P1-TSK-003`
found `P1-TSK-025` by an acceptance probe and recorded it rather than fixing it in passing.

### Previously

**`P1-TSK-029` — The four missing Phase 1 meters** — `COMPLETE` (2026-09-08). **Criterion 6
closes.**

| Acceptance criterion | Evidence |
|---|---|
| All six meters exist and are named correctly | `PlannedMetersExistTest`, which reads the plan's own table |
| Criterion 6 passes | The same test, on every build, rather than at a gate |

### The blocking finding: three of the four planned names could not be registered

`MetricNames.NAME` is `finapp\.[a-z][a-z0-9]*(?:\.[a-z][a-z0-9]*)+` — **no underscores**. The plan
named `mfa_challenge`, `session_lifetime` and `active_sessions`, so publishing them as written would
have failed the build. **A plan asserting something the platform's own convention forbids** — the
same class of drift this phase has found eight times, here between a plan written at the transition
and a mechanism built in Phase 0.

**The convention wins and the correction is free**, which is what makes it the right way round:
Micrometer translates a name to the backend's idiom, so `finapp.identity.mfa.challenge` and
`finapp.identity.mfa_challenge` produce the **identical** Prometheus series. The dotted form loses
nothing and keeps siblings sorting together.

### The worse finding: the meters that "existed" did not exist until the flow ran

`MeterRegistry.counter(name, tags)` creates the meter on the **first call**. Every counter in the
platform was written that way, so a freshly started instance published **no series at all** for
authentication, lockout or registration — they appeared only after somebody had logged in, been
locked out, or registered.

**An alert written on `rate(finapp_identity_lockout_total[5m])` therefore had nothing to evaluate at
exactly the moment it was needed.** A counter that starts existing when the thing it counts happens
is a delayed notification, not monitoring. So criterion 6 was worse than the review found: not *"two
of six exist"* but *"two of six exist once the flow has run"*.

All counters are now registered at construction, one per outcome value. `PlannedMetersExistTest`
boots a context and runs **nothing**, so it can only pass against eager registration — which makes
it the guard for this as well as for the names.

### Recovery is two meters, because widening the tag allow-list was refused

The plan says *"counter by stage"*, and `stage` is not in `MetricNames.ALLOWED_TAG_KEYS`. It would
satisfy ADR-0018's actual rule — a bounded set fixed at compile time — but **the allow-list exists
to make that an explicit decision rather than an autocomplete**, and a naming exists that needs no
widening. Using `type` for a stage would be the dishonest rename declined for `sharedSecret`
(`P1-TSK-017`) and `ACTIVE_CREDENTIAL_OF` (`P1-TSK-023`).

Two meters also serve the signal better: §Security signals names *"recovery initiation rate"*, which
is now one series rather than a filtered sum.

**And the initiation counter sees what the response deliberately hides.** `POST /v1/recoveries`
answers `202` for an unknown identifier, an unverified channel and cooling-off alike — that is
`INV-IDN-07` working. A metric is never visible to the caller, so it can and must tell them apart: a
rise in `refused` is somebody walking a list of identifiers.

### `session.active` counts LIVE sessions, and the obvious query is wrong

There is no `EXPIRED` status and no sweep (ADR-0030, `P1-TSK-013`), so `status = 'ACTIVE'` counts
sessions **nobody can use** — and it is wrong in the **reassuring** direction, reporting live
customers indefinitely while every one of them had been logged out for hours. `countLive` uses
`findLive`'s own predicate, so the gauge and the lookup cannot disagree about what a session is.

Read from the database rather than from a counter this application keeps, for `P0-TSK-029`'s
reasons; **`NaN` when unreadable, never zero**, because a zero says "nobody is logged in" at the
moment nothing can be known and an alert on a drop to zero would stay silent through the outage. And
every instance reports the same fleet-wide figure, so the dashboard uses `max()` — summing ten
replicas would report ten times the truth.

### `session.lifetime` measures one population and says so

**Expired sessions cannot appear in the sample, by construction**: expiry is derived and never
observed. Bulk revocation is excluded because forty sessions ended by one credential change is *one
decision*, and supersession because a rotated session was **replaced**, not ended. All three are
stated in the meter's description, its javadoc and a test — a metric that silently measures a biased
subset is the *"reports coverage it does not have"* failure this repository keeps meeting.

**Feeding it changed one method rather than adding a query.** `SessionStore.revokeOwned` returns the
lifetime the same `UPDATE` computes, and the boolean it used to return is `isPresent()` — so no
information is lost and no second source of truth appears. Reading the session first was refused:
that adds a query to a security-critical operation purely to feed a metric, and **monitoring must
not change the shape of the thing it monitors**.

### Two guards refused the new code and both were right

`INV-MON-01` caught the gauge's `double` — Micrometer's `Gauge` is a `ToDoubleFunction`. The
exemption set grew from two entries to four, and the argument recorded is that they are the **same
case** rather than a new one: a count of rows, published through the only instrument Micrometer
offers. The count itself is a `long` all the way to the registry boundary, because *that* half was
avoidable and *"it is only a metric"* is the reasoning that spreads the habit.

**`TestTaxonomyTest` produced a design improvement rather than a tag.** It placed the gauge's unit
test in the database tier, correctly — it saw a `DataSource` being asked for a connection and cannot
tell a reflective proxy from a pool, nor should it try. Rather than declare a heavier tier for a test
that needs no database, `IdentityMetrics` now takes a **connection source**, which is
`OutboxBacklog`'s shape and keeps `javax.sql` out of a class that reads one query.

### Verified against a running instance, and it proved the failure path by accident

`DOD-OBS` requires verification on a running instance rather than only in code. Scraping
`/actuator/prometheus` on a freshly started application shows **every one of the six meters present
at zero**, on an instance where nobody has logged in, been locked out, registered, challenged a
factor or begun a recovery:

```
finapp_identity_authentication_total{outcome="authenticated"} 0.0
finapp_identity_authentication_total{outcome="refused"}       0.0
finapp_identity_lockout_total                                 0.0
finapp_identity_mfa_challenge_total{outcome="elevated"}       0.0
finapp_identity_mfa_challenge_total{outcome="refused"}        0.0
finapp_identity_recovery_initiation_total{outcome="accepted"} 0.0
finapp_identity_recovery_initiation_total{outcome="refused"}  0.0
finapp_identity_recovery_completion_total{...}                0.0
finapp_identity_session_lifetime_seconds_count                0
finapp_identity_session_active                                NaN
finapp_party_registration_total{...}                          0.0
```

**That is the eager-registration finding, demonstrated rather than argued.** Before this task the
same scrape published none of them.

**And `session_active` reported `NaN`, which was not staged.** The local compose database is behind
the repository — `identity.session` does not exist there, and `flywayMigrate` refuses with a
checksum mismatch on `identity` V003, so an earlier session edited a migration after applying it
locally (`P0-TSK-015`'s recorded trap). The tests never saw it because `P0-TSK-035` gives each test
JVM its own container with the real migrations applied from scratch.

So the accident is a **positive result**: with its table unreadable the gauge published **absent
rather than zero**, and logged a warning naming neither a session identifier nor the statement. That
is the designed failure behaviour, observed live and unplanned — the case a written test can only
simulate. The positive control, that the gauge reports a real count, is
`DashboardQueriesResolveTest`, which scrapes a running application against a real database.

**The local database is not reset here.** Wiping a developer's data to make a scrape look tidier is
not this task's to do, and nothing in the build depends on it.

### Previously

**`P1-TSK-027` — Authentication issues a session** — `COMPLETE` (2026-09-08). **M1.2 closes;
criterion 1 closes.**

| Acceptance criterion | Evidence |
|---|---|
| M1.2's acceptance met end to end | The token a login returns opens `GET /v1/sessions` over HTTP, with nothing inserted by the test |
| The failure shape is unchanged | `everyFailureLooksTheSame` and `AuthenticationCostsTheSame` pass untouched — only the success path moved |

### The property was verified the way the failure demanded, and the obvious check would not have done

The review's finding was never *"no session row is written"*. It was that **a real client could not
obtain one while the test suite could** — every suite exercising the eight protected endpoints
inserted a session row directly, which is exactly why the gap survived twenty-four tasks.

So a test asserting that a token came back in the response would have repeated the same blindness one
layer up. `aLoginProducesAUsableSession` **uses** the token on a protected endpoint, and
`aFabricatedTokenOpensNothing` is its negative control — without which an interceptor that admitted
everything would satisfy the headline assertion perfectly.

### A session IS issued when a second factor is enrolled, and the strict-looking answer is wrong

Withholding one until MFA completes reads as safer and makes **step-up unreachable**:
`MfaChallenge.elevate` takes a *current* session, so there would be nothing to elevate. That is the
same shape of defect as the one this task closes — two mechanisms that each work and are not joined.

**Assurance being a level rather than a boolean (ADR-0030) is what makes the composition safe**, and
it is asserted rather than argued: the login's session opens `GET /v1/sessions` and is refused by a
handler requiring `MULTI_FACTOR`. The response is also byte-comparable whether or not MFA is
enrolled, because a body that gained an `mfaRequired` flag would tell an attacker holding a stolen
password what to attack next (`INV-IDN-07`).

### The level is not a parameter, which is stronger than every caller passing the right one

`SessionIssue` hard-codes `AssuranceLevel.PASSWORD`. A caller able to ask for `MULTI_FACTOR` here
would have found the bypass `INV-IDN-05` exists to prevent, and no amount of reviewing call sites is
as good as the level not being askable.

### One audit record, not two — and one transaction

The session identifier goes into `AUTHENTICATION_SUCCEEDED`'s change summary rather than becoming a
second `SESSION_ISSUED` record: one economic event, one entry. Issued inside the authentication
transaction and inside the **same** security scope, so a session that exists always has the record of
the login that produced it, and a rolled-back login leaves neither.

### `MfaBypassPathsAreEnumeratedTest` predicted this task by name and failed until it arrived

Its javadoc since `P1-TSK-019`: *"`P1-TSK-027` will add the second path and must come here and say
so."* It did, and the guard's standing claim — *"the only way a session comes into existence is a
proven second factor"* — is rewritten, because that read as strength and was in fact the defect.

### The contract change is BREAKING, and the backlog had called it additive

`204` → `201` breaks a client written against `204`. The classifier said so, the diff was reviewed
line by line, and the change was accepted: nothing consumes this API, and the alternative is a `/v2`
for an endpoint whose first version was never usable (ADR-0015). **The backlog entry's own
description was wrong**, and is corrected there rather than quietly — a plan mislabelling its own
change is what the byte-for-byte comparison exists to catch.

`produces = application/json` was declared explicitly, because springdoc publishes `*/*` without it —
the defect `P1-TSK-016`'s gate found on the session endpoints.

### The second `secretsAreWrapped` exemption arrived WITH its test

`P1-TSK-018` added the first and its gate found the javadoc claiming a test that did not exist. **An
exemption is a claim that a guard's subject is safe by other means, so imaginary means make it a hole
with a paragraph in front of it.** `AuthenticatedSessionTest` was written alongside the entry, and a
mutation removing the masking `toString` is caught.

### Two of my own tests were wrong, and the second bounds what this can be tested at all

**The audit assertion pinned a rendering.** It required `session=<bare uuid>` and the platform renders
`SessionId(uuid)` — the convention across all five existing change summaries. The **test** was wrong:
an investigator searches a free-text summary by substring, never by equality, and the wrapped form
says which kind of identifier it is. Now asserted as the property — the record labels a session, and
the identifier is findable — pinning no rendering.

**A hostile `User-Agent` could not be driven at all.** The JDK's `HttpClient` refuses any header value
outside printable ASCII, so the bidirectional override never left the client. Recorded rather than
worked around: the character-level rule is `DeviceDescriptionTest`'s subject, reaching it needs raw
bytes on a socket, and that is *why* the rule lives on the domain type rather than at the boundary.
What HTTP can drive — an over-long header — is asserted here.

### The mutation harness broke production code and manufactured a defect that did not exist

**The most instructive failure of this task, and it was entirely in the machinery that checks the
work** — the seventh time in this project a mutation has reported something it did not measure, and
the first where the harness left the tree broken.

The plant-verification assertion — *the mutation must be visible in the file before the build runs*
— **fired correctly** on a mutation that legitimately wraps the original line rather than replacing
it. The script exited on that assertion, and the restore was on the happy path only. So it left
`SessionIssue.issue` with `sessions.insert` disabled.

**Everything measured afterwards was measuring that.** The full suite reported **seven failures**;
three separate reproductions confirmed them; a probe was written; and the reported symptom —
*"the audit record commits and the session row does not, in one transaction on one connection"* —
was impossible, which is what finally pointed at the harness rather than at the code.

**Three consequences, and the second is the one that matters.**

- The seven failures were not real. All three suites pass together against restored code.
- **All ten mutation results were void and were re-run.** They had executed against a codebase whose
  session insert was already disabled, so the suite was red whatever the mutation did — every
  `CAUGHT` was a coincidence. A harness that cannot leave the tree clean does not merely fail to
  prove things; it **manufactures proofs**, which is worse.
- The restore is now in a `finally` and writes back the string read at the top, so there is no
  backup file to be orphaned either.

**Why it took so long to see.** The suite passed alone at 14 of 14 *before* the mutations, and every
run after them was broken — so the evidence looked exactly like a test that passes in isolation and
fails beside its neighbours, which is a real and familiar class of defect. Two hypotheses about
Spring context caching were pursued before the impossible symptom ruled the code out.

### Previously

**`P1-DOC-001` — Phase 1 review record** — `COMPLETE` (2026-09-08). **M1.7 closes.**

**The review finds the exit gate does not pass, and that is what conducting one is for.**

| | Outcome |
|---|---|
| Review areas (8) | 7 `PASS`, 1 **`NOT APPLICABLE`** — area 2 has no subject and says so |
| Universal criteria (12) | 10 `PASS`, **2 `FAIL`** |
| Phase 1-specific (6) | 5 `PASS`, 1 `PARTIAL` |
| **Verdict** | **Phase 1 remains `IN_PROGRESS`** |

### Criterion 1: no production path issues a first session

Traced through the code rather than inferred. `Session.issue` is called only by `SessionRotation`;
`SessionRotation.rotate` only by `MfaChallenge.elevate`; `elevate` requires a `current` session; and
`MfaChallengeController` is `@RequiresSession`. `POST /v1/authentications` answers **`204` with no
body**.

**So a real client cannot obtain a session by any route**, and the eight endpoints
`PHASE_1_PLAN.md` §7 marks `Auth: session` are unreachable. Every test that exercises them inserts a
session row directly.

`MfaBypassPathsAreEnumeratedTest` has said so since `P1-TSK-019` — *"an accident of sequencing rather
than a design goal"* — and the criterion demands deliverables **exercisable end to end**. Proving an
identity and holding a session are both built; nothing joins them.

### Criterion 6: four of six meters do not exist

`authentication` and `lockout` exist. `mfa_challenge`, `session_lifetime`, `recovery` and
`active_sessions` do not — and they are exactly the phase's critical flows.

**`finapp.identity.recovery` is the one that matters most**, and the plan says why in its own
annotation: *"recovery is the ATO vector; its rate is a security signal."* A takeover campaign is a
rise in recovery initiations, visible today only by querying the audit trail — **which is evidence,
not monitoring**. `INV-AUD-01` is satisfied and criterion 6 is not; the distinction is why the
platform has both.

### Three documentation drifts, found by hand-diffing what no guard covers

- **The plan declares fifteen endpoints; twelve exist.** Three absentees are owned by open tasks, and
  **`GET /v1/me` and `PATCH /v1/me` are owned by nobody** — the eighth backlog defect of this class in
  Phase 1, and the first found by a **review** rather than by the task that tripped over it. Now
  `P1-TSK-030`.
- **An exemption resting on a false statement.** `AuditCompletenessTest`'s entry for
  `party.ProfileChanged` read *"`PHASE_1_PLAN.md` does not list one."* **It lists `PATCH /v1/me`.** I
  wrote that entry in `P1-TSK-022` and it was untrue — the `P1-TSK-018` shape, in my own register: an
  exemption is a claim that something is safe by other means, so a false claim is a hole with a
  paragraph in front of it. Corrected.
- **An imprecision of mine in this very document.** §Next Task said the phase-specific criteria *"name
  seven identity properties and there are eight"*; `PHASE_GATES.md` §5 lists **six bullets**, and the
  claim conflated them with the transition's seven `INV-IDN` properties. Corrected — which is why a
  review checks claims rather than inheriting them.

### ADR-0029…0034 accepted despite the open failures

Phase 0's recorded reasoning, unchanged: **criterion 10 is a precondition of the gate rather than a
reward for passing it.** Holding ADR-0030 at `Proposed` because four meters are missing would be
theatre — the decisions were taken, implemented and tested, and neither failure is contingent on any
of them.

### Area 2 has no subject, and says so

*"Walk one real posting end to end"* — Phase 1 creates none. Reporting a pass would be reporting on
something that does not exist, so it states the absence, exactly as Phase 0's review did. A reader
comparing records must be able to tell *assessed and clean* from *had no subject*.

### What the phase produced

2 modules, **10** tables, 12 endpoints, **8** aggregates and entities, 19 auditable actions, 8 new
invariants (**72** total), 6 ADRs, **847 hermetic and 404 database tests**, **25 of 31** backlog
items.

**Three of those numbers were wrong in the first draft of the review**, and the completion gate found
them: the table count was the *migration* count, the aggregate count was the *plan's*, and the
backlog count went stale the moment the review completed one item and created two. All three had been
**quoted rather than counted** — which is exactly the drift the review found in area 7. A review
record is not exempt from the rule it enforces.

### Previously

**`P1-TSK-024` — Extend the mutation register to Phase 1** — `COMPLETE` (2026-09-08).

| Acceptance criterion | Evidence |
|---|---|
| Every Phase 1 invariant has a recorded demonstration | Nine of them, and the guard requires them |
| The guard covers them | Extended to *every phase reached*, with the phase derived |
| A Phase 1 invariant with no row fails the build | Proven by mutation |

### The acceptance as written was too narrow, in two ways

**There are eight `INV-IDN-*`, not seven** — `P1-TSK-017` added `INV-IDN-08` mid-phase, because a
TOTP secret cannot satisfy `INV-IDN-01`. The task's own text was already stale.

**And there are nine Phase 1 invariants**, because `INV-AUD-03` is `Phase: 1 onward` and is **not in
the `INV-IDN` group at all**. A guard extended to `INV-IDN-*` — which is what the item asked for —
would have missed it. Extending to *"every invariant of every phase reached"* is what finds it, and
that is the difference between implementing the sentence and implementing the property.

**Two had no row**: `INV-IDN-02`, the one the task is named for, and `INV-AUD-03`. Both were already
demonstrated by `P1-TSK-007` and `P1-TSK-020`; the rows record work done rather than work invented.

### The finding: nine rows did not parse, so the guard had been skipping them

The row grammar admitted **exactly one** backticked reference and nothing after it. The register is
written with lists and trailing prose — `` `A`, `B#c` `` and `` `A` (nine tests, one per route) ``.

**Nine rows matched nothing, eight of them written during Phase 1 by me.** They were not reported as
broken; they were simply absent, so `everyNamedTestExists` and `everyNamedMethodExists` never looked
at them. A row naming a renamed test would have sat there reading as a live proof.

**A register whose rows the guard cannot read reports coverage it does not have** — `P0-TST-008`'s
finding, in the artefact built to prevent that exact class of defect.

**Proven precisely rather than argued.** A reference in *second* position naming a test that does not
exist **survives** the old parser and is **caught** by the widened one. The first attempt at that
demonstration was wrong and checking it is what showed so: the plant happened to be reachable as a
*first* reference, so it was caught either way and proved nothing about the widening.

### The current phase is derived, not written down

From `CURRENT_STATE.md` §Current Phase, whose stated role is to be *the canonical description of
where the project is*. A constant would be the stale list this repository closes by derivation
everywhere else, and it would need editing again at Phase 2 — which is precisely the *"extension not
needed again"* the task asked for.

The **highest** phase the section names rather than the one marked `IN_PROGRESS`: a status word is
prose that changes shape between phases, and a phase that has been reached does not stop having been
reached when it completes.

### §4 generalised, and the two phases declare their test items differently

Phase 0 gives `P0-TST-*` their own headings; Phase 1 names `P1-TST-*` **inside task headings**
(`**P1-TSK-009 — `P1-TST-001`: credentials never leak**`). Anchoring to either shape finds nothing
for the other and passes vacuously, so the pattern finds the identifier wherever a bold heading
declares it — and the next phase's shape as well.

### One mutation survived and found a defect in this task's own new assertion

`everyRowNamesATest` exists so that a row the parser cannot read is a **failure** rather than a
silent omission. Its first version read the references merged **per invariant** — and `INV-IDN-06`
has two rows, so emptying one left the merge non-empty and the mutation walked through.

A check defeated by the very merging that makes the rest of the guard convenient is a check
reporting coverage it does not have. It is per **row** now, and the mutation is caught.

### The completion gate found the same defect one level out, in this task's own fix

`everyRowNamesATest` catches a row that **parses** and names nothing. It cannot catch a row that
fails the row pattern **entirely** — a typo in the form column, an extra pipe, a reflowed line —
because such a row is not in the map at all.

**Probed rather than reasoned about**: changing one row's form from `In-suite` to `Insuite` left the
build **green**, and that invariant stayed covered only because it happens to have sibling rows.

So the outer check had to be structural too: every §2 line that *looks like* a row must parse as one.
Leaving it open would have reproduced this task's own finding — a parser that silently drops what it
cannot read — in the fix for that finding.

**Seven mutations, all caught.** 847 hermetic tests, 404 database tests.

### Previously

**`P1-TSK-023` — Account recovery** — `COMPLETE` (2026-09-08). **M1.6 closes.**

| Acceptance criterion | Evidence |
|---|---|
| Every abuse case refused, each its own test | Nine tests in `RecoveryAbuseDatabaseTest`, one per route |
| `INV-IDN-06` fails when the channel-verification check is removed | Dropping `verified_at IS NOT NULL` from the join fails two |

### The blocking finding: `INV-IDN-06` had no subject

It forbids recovery *"without proving control of a **previously registered and verified**
channel"* — and **no channel existed anywhere in the platform.** No `EmailAddress` type, no table,
no verification flow, and grep confirmed **no backlog task owning one**. `PHASE_1_PLAN.md` asserts
that an Identity carries *"a separate, changeable, separately-verified email"*; that was aspirational.

**Seventh backlog defect of this class in Phase 1, and the most consequential.** The others were
missing endpoints. This was a missing **precondition of the invariant**: without a channel, recovery
cannot satisfy `INV-IDN-06` at all, only appear to. Built here on the `P1-TSK-016` precedent —
*without it this task has no deliverable* — scoped to the minimum, and recorded rather than absorbed.

### Recovery issues no session, and that is the sharpest decision here

The conventional design logs you in on completion. **Rejected**, because `INV-IDN-06`'s second clause
is *"recovery never lowers the assurance required to reach an account"* — and a session handed out on
completion **is** that lowering: an attacker holding the mailbox would skip the credential *and*
whatever stood behind it.

Recovery replaces the credential and stops. The customer authenticates normally afterwards, so MFA
applies in full, and a compromised mailbox still meets the second factor.

**So the fifth abuse case cannot be attempted rather than merely refused.** *"Recovery used to reach
an operation requiring `MULTI_FACTOR`"* has nothing to attempt it with, and
`MfaBypassPathsAreEnumeratedTest`'s statement — *nothing new creates a session* — stays true. That
guard listed recovery as a **recorded remainder** for precisely this question; it now carries the
answer.

### Bound to the credential it was raised against

The dangerous form of *"concurrent recovery and login"*: an attacker initiates at T0, the customer
notices and changes their password at T1, and the attacker completes at T2 and wins **having watched
the customer do the one thing they thought would save them**.

Completion is conditional on the active credential still being the one recorded at initiation — a
**predicate in the statement**, not a procedure, because a predicate cannot be forgotten by a future
credential-change caller. Its false positive (upgrade-on-use supersedes a weak credential on login)
fails **closed** and is recorded.

### Every refusal is the same refusal, in one statement

Unknown identifier, no verified channel, cooling-off — all `202`, and all decided by a single
`INSERT … SELECT`, so the **work** is equivalent too. A version that looked the identity up first
would run a different number of queries for an account that exists, which is the timing channel
`P1-TSK-008` found in authentication. This endpoint needs nothing to call, so it is the first one an
attacker probes.

### The token is delivered nowhere, and that is a seam rather than a gap

`PHASE_1_PLAN.md` §8 records it: the channel adapter is Phase 15's. The response is `202` with no
body — returning the token would hand it to whoever asked, so channel control would prove nothing.

**The outbox event carries identifiers only, and the guard would not have stopped a token.**
`EventPayload`'s charset is `[A-Za-z0-9_-]`, which base64url satisfies perfectly — `P1-TSK-009`
recorded that limit in as many words (*"a charset, not a secret detector"*). What keeps the token out
is that no event declares a field for it.

### Four existing guards refused the new code, and all four were right

| Guard | What it caught |
|---|---|
| `SecretsAreUnwrappedInOnePlaceTest` | The controllers were unwrapping a token and an address. **The rule had the better argument** — a plaintext in `app` is a plaintext outside the module that owns secrets — so the boundary now passes `Sensitive<String>` straight through |
| `SystemActorCallSitesAreEnumeratedTest` | The third `enterSystem()` site, one task after that guard was written |
| `OwnershipIsScopedTest` | Two unclassified persistence methods, one task after that guard was written |
| `OpenApiContractTest` | Four undeclared routes; the baseline is **141 added lines and zero removed** |

### `secretsAreWrapped` fired four times and each answer was different

- `TOKEN_LIFETIME`, a `Duration` — **a duration cannot hold a secret**, so a structural narrowing,
  the same shape as `P1-TSK-017`'s primitive exclusion. `SESSION_IDLE_TIMEOUT` and
  `CREDENTIAL_MAX_AGE` are names anybody would write.
- `credentialId`, an `Optional<CredentialId>` — a **real gap** in the existing compositional
  exclusion, which reads the *raw* type and so could not see through `Optional`. `Optional<String>
  password` still fails, which is the boundary that matters. Needed on the accessor as well as the
  field, because a record produces both.
- `ACTIVE_CREDENTIAL_OF`, a SQL fragment — **the code changed, not the rule.** Renaming to dodge the
  vocabulary was available and was refused (`P1-TSK-017`'s reasoning); it is a method taking an
  argument now, which is not a stored value at all and reads better at the call sites.

### One assertion of mine was a stale list, and it was one task old

`bothSurvivorsAreUnauthenticated` matched package names as a proxy for *"unauthenticated"*, and it
broke the first time a third unauthenticated surface appeared — by my own hand, one task later. A
proxy needing an edit whenever the codebase grows is the stale-list defect this repository closes by
derivation everywhere else.

Replaced with the property it was reaching for: **a method handed a proven `Session` must not claim
the platform.** What remains uncheckable is stated — whether a path is authenticated is a property of
the call graph, not of a name.

### Two mutations survived first, and each found a real gap

**A test passing for the wrong reason.** `aCancelledTokenIsRefused` looked up *the latest* recovery
request — which, after the customer's own initiation, is the customer's. The attacker's token was
being refused on the token match rather than on the status, so the mutation removing
`status = 'INITIATED'` walked straight through. The fixture now holds the attacker's own request.

**Nothing had ever suspended an identity.** Removing `i.status = 'ACTIVE'` changed nothing. It
matters more than a missing case usually does: suspension is the most consequential thing one person
can do to another's account, and recovery ignoring it would let the suspended party undo an
administrator's decision through the front door.

**Eleven mutations: ten caught, one survived correctly** — re-verification, unreachable because the
token is cleared on success, which the mutation removing *that* proves is load-bearing. 845 hermetic
tests, 396 database tests.

### Previously

**`P1-TSK-022` — Actor-attributed audit** — `COMPLETE` (2026-09-08). **M1.5 closes, 3 of 3.**

| Acceptance criterion | Evidence |
|---|---|
| `INV-AUD-01` holds for every Phase 1 privileged action | `AuditCompletenessTest` — emitted, or declared not to be with the owning task |
| `enterSystem()` reduced to the platform acting, **each justified** | `SystemActorCallSitesAreEnumeratedTest` — two sites, a third fails the build |
| An audit record per privileged action naming the real actor | `AuditNamesTheActorDatabaseTest` — asserted over the **rows**, not per action |

### Six of the seven implementation clauses were already true

Probed rather than assumed, which is what made the task worth doing: the interceptor establishes a
scope per authenticated request (`P1-TSK-016`); **all thirteen** audit sites call
`SecurityContext.require()` and **none** defaults; every writer takes the caller's `Connection`;
immutability sits at `DB-PRIVILEGE` (`P0-TSK-022`); an unestablished actor is refused; fifteen
actions are catalogued and reconciled three ways.

Restating any of that would be duplication that drifts — the `P1-TSK-012` precedent. So the
deliverable is the three clauses **nothing checked**, and each is an acceptance criterion in its own
right.

### The gap `P0-TSK-023` recorded against itself, closed

`AuditableActionRegistryTest` reconciles the registry with the catalogue in three directions, and its
own javadoc says what it cannot do: *"it cannot detect a privileged action that writes no audit
record at all."*

**That is the failure that matters**, because a registry agreeing with a catalogue while nothing
emits half of it looks complete **from both sides** — and the Phase 15 completeness report would be
checked against exactly that list. Two Phase 1 actions were silently unemitted.

Now every action is held against production code: **emitted**, or **declared not to be** with the
task that will emit it. *"Deliberately not built yet"* and *"somebody removed the audit call"* stop
being indistinguishable, and an action that **stops** being emitted fails the build.

**Five are declared unemitted**: `identity.IdentitySuspended` (`P1-TSK-028` owns the endpoint, and
inventing one to give the action a caller would be a surface chosen to suit a test),
`party.ProfileChanged` (no Phase 1 capability), and the three `outbox.*` actions already recorded as
Phase 15 debt.

### "Each is justified" was a claim about a set, and grep is not a control

ADR-0021 called `enterSystem()` *"the greppable list of places Phase 1 must revisit"*. Grep is a
thing somebody has to remember to run, and the site added in Phase 4 will not be in anybody's memory
of this review.

**Two sites survive, both on unauthenticated paths**, where naming a guessed identity would put an
unproven claim in a permanent record:

| Site | Why the platform is the honest answer |
|---|---|
| `RegistrationService.register` | The caller is unauthenticated; attributing to the Party it creates is circular and unavailable on the refusal path |
| `AuthenticationService.attempt` | The **failure** branch — the login identifier may name nobody at all |

The authentication justification existed **only as a code comment**; `SECURITY_ARCHITECTURE.md` now
carries it.

### The enumeration is at method granularity, and that gap is closed where it exists

`AuthenticationService.attempt` holds **both** branches — success establishes
`Actor(identityId, CUSTOMER)`, failure claims the platform — so it is enumerated once. Replacing the
success branch with `enterSystem()` would change nothing the enumeration can see, while recording
the platform as having logged somebody in.

A separate assertion requires that method to still call the real-actor form. Proven: that mutation
now fails.

### The headline property was asserted per action and never over the trail

Each earlier task asserted its own record. **Nothing asserted that no record written under an
authenticated request names the platform** — a different claim, about the trail rather than one
operation, and the one that fails when somebody adds an audit call in a hurry.

Scoped by the requests' correlation identifiers so it can actually fail, and driven across **two
aggregates**, because a sweep confined to sessions would prove the property for the code that
happened to be written most carefully.

**`GET /v1/sessions` is deliberately excluded, and that is stated rather than left as an omission**:
listing your own sessions is not privileged and `SessionQueries` records the decision not to audit
it. Asserting over an endpoint that writes nothing would make the sweep quietly smaller than it looks.

### Which assertion is load-bearing was established by probing, not claimed

The blank-target mutation is caught by **`AuditRecord.bounded` refusing construction**, not by the
field assertions — verified, because the failure was `theSweepIsBroad` reporting a missing operation
rather than a blank one. So those three assertions are recorded as **defence in depth**: they are
what would catch a writer that stopped going through the domain type.

**The correlation assertion is the one nothing else makes.** A record carrying an identifier that
belongs to no flow satisfies every `NOT NULL` and every `CHECK`, and it is precisely the record an
investigator cannot use — worse than an absent one, because a search returns a row and stops.

### The completion gate found a claim my own test did not support

**The display name said *"and reason where the registry needs it"*, and nothing looked at the
column.** Sixth occurrence of this pattern in Phase 1 — after `V005`'s enum claim,
`AuthenticationRequest`'s bounds claim, `RequiresSession`'s fail-closed claim,
`secretsAreWrapped`'s exemption and `V010`'s migration test.

**And the claim is unassertable over this sweep rather than merely missing**, which is the more
useful half. `AuditRecord`'s constructor **refuses** a record whose action requires a reason and has
none, so one cannot reach the table — and the only two actions requiring one, `IdentitySuspended`
and `RoleAssigned`, have **no production caller** in Phase 1. Renamed to what it does, with the
reason written down.

### And the coverage guard had deviated from its siblings again

`P1-TSK-021`'s gate found exactly this one task ago, and both new rule suites repeated it: a sweep
over `com.finapp` with nothing asserting every module is reached. Closed in both, using
`ProductionModules` — which had to become public, because these suites live in `com.finapp.app.audit`
and duplicating the derivation is the drift that helper exists to prevent.

Proven load-bearing: narrowing either sweep now fails, five tests in one and six in the other.

**Ten mutations, all caught** — two added by the gate. 844 hermetic tests, 384 database tests.

### Previously

**`P1-TSK-021` — Ownership checks in the domain** — `COMPLETE` (2026-09-08).
**M1.5 is 2 of 3.**

| Acceptance criterion | Evidence |
|---|---|
| A negative ownership test per resource-scoped operation | Four, already present — probed rather than assumed |
| Each fails when the ownership check is removed | Dropping `AND identity_id = ?` fails **six** tests |
| The recorded limit — *no build rule detects a missing ownership check* | **Narrowed**: `OwnershipIsScopedTest` now fails the build on an unclassified one. ADR-0031 amended |

### The acceptance criterion was already met, and checking rather than assuming is the contribution

`P1-TSK-016` put `identity_id = ?` in the **statement** for session listing and revocation;
`P1-TSK-017` resolved the MFA enrolment *from* the session's identity, and its ownership test is
written in its strongest form — the attacker presents a **valid** code for the victim's enrolment.

So restating any of that would be duplication that drifts rather than coverage — the `P1-TSK-012`
precedent. **The deliverable is the part the task's own text names as missing**, and `INV-IDN-05`
taught why one milestone earlier: *a list of tests is a snapshot*, and the operation added in
Phase 4 will not be in it.

### Five correct statements look exactly like the defect, which is why the rule classifies

Surveying `identity` found five statements targeting a row **by primary key with no owner
predicate** — `revoke`, `touch`, `confirm`, `consumeStep`, `supersede`. All five are correct.

The difference is **provenance**: an identifier that came from an owner-constrained read is safe,
and one that came from a request is not. In SQL they are indistinguishable. A rule that merely
forbade the shape would have produced five false positives on its first run, and ADR-0019's own
reasoning is that a rule with an exemption list is a rule somebody turns off.

So every persistence method taking a resource identifier is classified `OWNER_SCOPED`,
`AUTHORITATIVE_ID` or `NOT_OWNED`, and an unclassified one fails the build.

### The rule found two things I had written down wrongly

**It located the `private` helper, not the public method.** `revokeAllForExcept` delegates to
`revokeAll`, so the statement is in the helper — and a rule that stopped at the public method would
be one that ordinary method extraction dodges. The detector was more accurate than my register.

**And it surfaced `OutboxRelay.markPublished`** — a platform row with no owner at all. That produced
the `NOT_OWNED` class, written down as the escape hatch it is: like `AUTHORITATIVE_ID` it is a
review artefact, and its value is that labelling a customer-owned table with it requires somebody to
type a sentence that is false. It also means the sweep covers **every** module rather than a list of
the ones that matter today, so Phase 3's ledger identifiers surface on the day they are declared.

### The first version of the rule survived its own mutation, and the reason is worth recording

Removing `AND identity_id = ?` from `revokeOwned` left the build **green**. The rule searched the
whole method body — which contains the comment *"`identity_id = ?` IS the ownership check"*.

**A `contains` over source text matches prose.** The rule was reporting a control it did not have,
which is worse than none because it is believed (`P0-TST-008`). It reads **string literals only**
now: SQL is only SQL if it is inside a literal, and no comment can satisfy that.

Third occurrence of this class in two tasks — *right about the property, wrong about where to look*
— after `P1-TSK-020`'s permission-column assertion needed two corrections for the same reason.

### One gap in my own rule, closed before it shipped

An `AUTHORITATIVE_ID` entry rests entirely on the named read being owner-constrained, and the first
version asserted only that the read **existed**. Five of the seven entries rest on that class, so a
read that quietly stopped being scoped would make every operation citing it unscoped at once, with
the register still reading as a control.

Now the named provenance must itself carry an ownership predicate wherever it issues SQL. The
vocabulary is two entries and each is a real proof: `identity_id = ?` names the owner, and
`token_hash = ?` is the session lookup — a bearer credential, so presenting it **is** the proof.

**The one that cannot be checked is stated rather than papered over**: `SessionRotation` holds a
proven `Session` object rather than reading one, so there is no statement to inspect. Checking it
would require knowing where the caller's `Session` came from, which is a taint question.

### `party` owns no resource-scoped operation, and that is asserted

It has no store at all: registration creates a Party and a Customer, and nothing reads either by an
identifier a caller supplied. So there is no ownership surface — and the assertion is what turns
that from an assumption into something that fails the build when it stops being true.

### The completion gate found the rule was aimed at the wrong half of the defect

**The shape this repository has actually shipped is a method that takes an owner and never uses
it.** `P1-TSK-016` found exactly that in `SessionRevocation.revoke`: the statement was
`WHERE id = ? AND status = 'ACTIVE'`, so any caller could end any session by identifier while the
audit record asserted an owner nobody had verified.

The rule as written could not see it, because it keys on a **resource** identifier. So the second
half asks the complementary question — **a persistence method handed an `IdentityId` must name the
owner in its statement.** Probed before it was claimed: **twelve of thirteen** satisfy it, and the
thirteenth is `lockIdentity`, whose statement is against `identity.identity` where `id = ?` *is* the
owner. That is a structural fact about the schema, so it is stated as part of the rule rather than
carried as an exemption.

**It also covers what the first half structurally cannot.** `findLiveFor` takes no resource
identifier, so nothing in the original rule would notice it losing its scope — and that is a **bulk
disclosure**, every session of every customer, rather than one row. Proven: the mutation is caught
by the build rule *and* by the behavioural suite.

### And the coverage guard had deviated from its four siblings

Every rule suite here carries `everyModuleWithProductionCodeIsAnalysed` against
`ProductionModules.onClasspathWithProductionClasses()`. This one had a bare `isNotEmpty()`.

The set-equality assertion on the register happens to protect `identity` and `platform` — narrowing
the sweep drops their entries and fails — but **`party` is protected by nothing**:
`partyHasNothingToScope` would pass vacuously over a sweep that never reached it. That is the
`P0-TSK-008` finding, with the corrected idiom sitting in four files alongside. Deviating from a
sibling idiom is what hid `secretsAreWrapped`'s inversion in the first place.

### A latent defect in my own helper, exposed by widening its input

Adding the second half made `statementOf` run over every persistence method instead of two small
ones, and it **overflowed the stack** on the first long body: the obvious string-literal pattern
`(?:[^"\]|\.)*` backtracks catastrophically. Replaced with the unrolled-loop form, which is
linear.

A regex that is correct on the input its author happened to try is the same class of defect as a
rule that is correct on the module its author was thinking of — and it would have arrived as a
mysterious CI failure the first time somebody wrote a long method.

**Ten mutations, all caught** — two added by the gate. The predicate removal and the listing scope
are each caught **twice**, by the build rule and by the behavioural suite, which is the `P1-TSK-020`
argument that two controls blind in different directions are not a duplication. 834 hermetic tests,
381 database tests.

### Previously

**`P1-TSK-020` — Roles, permissions and the boundary check** — `COMPLETE` (2026-09-07).
**M1.5 opens, 1 of 3.**

| Acceptance criterion | Evidence |
|---|---|
| Deny-by-default demonstrated by an endpoint with no declaration | `/probe/undeclared` refused `403` **with a valid session** |
| A negative authorization test for every protected endpoint | One per declaration class, plus a positive control so refusal is not blanket |

### The acceptance criterion is asserted in its strongest form, and that choice matters

The obvious version presents **no** session to the undeclared endpoint. It would pass against an
implementation that merely required authentication and had no deny-by-default rule at all — the
weak-test shape `P1-TSK-017`'s gate met with the MFA ownership case. So the criterion is driven
with a **valid** session: the property is about the *declaration*, not about the caller, and the
answer must still be no.

### Deny-by-default is enforced twice, and neither replaces the other

The interceptor refuses at run time, because ADR-0031 says *refused*. `EveryEndpointDeclaresARuleTest`
fails the **build**, because a deployment defect discovered by a customer receiving a 403 has been
discovered too late.

They are blind in different directions: a static sweep cannot see a handler registered at run time,
and a runtime check cannot fail a build. Same argument ADR-0020 makes for keeping the secret scanner
beside the configuration rule — *"a net, not the control"*, in both directions.

**The routes come from `RequestMappingHandlerMapping`**, which is what actually dispatches, rather
than from a list. The stale-list defect this repository has met in CI's task list, in a coverage
guard and in a privilege check, closed the way it has been closed each time.

### `P1-TSK-016` had recorded that an undeclared endpoint fails closed *by accident*

It threw at `SecurityContext.require()` deep inside the handler — **a 500 standing in for a security
decision**. Three things were wrong with that: it is our fault reported for their request, which
`ERROR_CONTRACT.md` §3 forbids; a client may retry it for ever; and it depends on the handler
happening to need an actor, so a handler that never touched `SecurityContext` was simply reachable.

Now `403` with an **error**-level log naming the handler, because it is a deployment defect and only
an operator can fix it.

### Permissions are resolved per request, never stamped on the session

A role carried on a session survives its own revocation until that session expires, and *"remove
their access now"* becomes a promise the architecture cannot keep — `INV-IDN-03`'s reasoning applied
to authorization. The revocation test presents the **same** session token across the revoke; issuing
a new one would prove only that a fresh session reads fresh roles.

### A denial is audited, and it is written after the scope opens

A **permitted** privileged action is audited by the operation itself. A **refused** one has no
operation to do it, so without a record a probe for privileged endpoints is indistinguishable from
silence — which is `INV-AUD-03`'s *"tested from the attacker's direction"* as a durable artefact
rather than a test.

The check runs **after** `SecurityContext` is established, deliberately: an audit record needs an
actor, and checking first would attribute the refusal to the platform — the wrong party, permanently
(`P0-TSK-032`, `INV-HIST-03`).

### `api.Forbidden`, deliberately not a distinct code

`P1-TSK-018` argued the opposite for `identity.AssuranceRequired`, and the distinction is
actionability: *step up and retry* is something a client can do, and *you hold no role* is not. A
client cannot grant itself a role, so a special code would advertise a remedy that does not exist.

### Sixth backlog defect of this class, and it is the one that leaves the annotation unused

`PHASE_1_PLAN.md` §7 lists `POST /v1/identities/{id}/suspension` and
`POST /v1/identities/{id}/roles` — the only two endpoints in the phase that would carry
`@RequiresPermission` — and **no task owns either**. `P1-TSK-022` writes the audit record for a
suspension nothing builds.

Recorded and carried as **`P1-TSK-028`** rather than invented here: an admin endpoint added to give
the annotation a production caller would be a **security surface chosen to suit a test**, which is
`P1-TSK-018`'s recorded reasoning for shipping `@RequiresAssurance` with a probe endpoint.

### The rule immediately refused eight of our own endpoints, and that was the right answer

Six test probe controllers and the two unauthenticated production endpoints. **Exempting test
sources was refused**: a probe controller *is* a mapped endpoint, and a rule that stops at the test
boundary is one whose coverage nobody can state. All eight are annotated, which is the decision
being written down rather than inferred.

### One mutation was a no-op, and re-aiming it is the finding

`RoleName.permissions()` returning **all** permissions **survived** — and correctly, because there
is exactly one role and it already holds both. That is not a gap in the test; it is a mutation that
changes nothing. Re-aimed at the role granting **nothing**, it is caught.

**The limit is stated rather than papered over**: the role→permission mapping cannot be meaningfully
mutated until a second role exists. It becomes testable at `P1-TSK-028`.

### The completion gate found an endpoint that reads as protected and is public

**A handler declaring both `@Unauthenticated` and `@RequiresPermission(ROLE_ASSIGN)` was answered
`200`, with no session at all.** Probed rather than reasoned about, and **both** guards passed it:
the interceptor returns early on `@Unauthenticated` before either check, and the build guard is
satisfied by *any one* declaration being present.

**The harm is not that it is public — it is that it reads as protected.** A reviewer grepping for
`@RequiresPermission` finds it and stops looking. That is `P1-TSK-016`'s `SessionRevocation.revoke`
finding in a new place: a declaration asserting a control nobody applies is **worse than an absent
one**, because the absent one invites the question.

**And it is one copy-paste away.** `P1-TSK-028` adds the only two endpoints carrying
`@RequiresPermission`, in a codebase where two production endpoints already carry
`@Unauthenticated`.

**Refused rather than resolved to the stricter reading.** Silently honouring the protective
annotation would be safe *and* would hide the mistake for ever, which is how the next contradiction
survives review. A handler declares exactly one rule; a contradiction is a deployment defect, so it
is refused and logged like an undeclared handler. Closed in **both** places, because neither guard
caught it.

### A migration comment named a test that does not exist — fifth occurrence this phase

`V010` says *"RoleAssignmentMigrationTest fails the build if they drift"*. **No such test existed** —
after `V005`'s enum claim, `AuthenticationRequest`'s bounds claim, `RequiresSession`'s fail-closed
claim and `secretsAreWrapped`'s exemption. `P1-TSK-013`'s gate found precisely this, and
`P1-TSK-007` had already established the pattern.

The drift is not cosmetic: a second `RoleName` without a matching constraint is a value the domain
produces and the database refuses — failing at the **last write**, on the table that decides who may
do anything privileged.

**And `RoleName` had no test at all**, which for the type holding the platform's entire authorization
policy is the wrong number. `AssuranceLevel`'s finding, repeated.

### Two dead methods, disposed of differently — the `P1-TSK-013` shape

Both enums shipped a `sqlValueList()` with **zero callers**.

- `RoleName.sqlValueList()` is **kept and made load-bearing**: the migration test is its consumer,
  and writing that test is what the migration already claimed had been done.
- `PermissionName.sqlValueList()` is **deleted**. A permission is never a column — ADR-0031 puts the
  mapping in code — so there is no constraint it could generate and there will not be one. A helper
  producing SQL for a column that does not exist implies permissions are persisted somewhere, which
  is the opposite of the decision.

### `assign`'s concurrency claim had no test

Its comment says *"ten instances granting the same role produce one assignment and nine are told
they lost"*, and nothing exercised it — while `CLAUDE.md`'s multi-instance rule is not conditional on
the operation being financial.

**What would go wrong is not untidiness.** Two live rows for one identity and role make revocation
**partial**: `revoke` is a conditional `UPDATE` whose row count is the outcome, so it would report
success having revoked one of them and the identity would keep the role. *"Remove their access now"*
would return success and be false — the failure the per-request resolution exists to prevent,
arriving through a different door. Ten instances, ten connections; the crash half asserted too.

### One test was right about the property and wrong about where to look, twice

*"There is no permission column"* first read the whole file — which fails on the migration's own
`--` comments — then the comment-stripped file, which still fails on the `COMMENT ON TABLE` body,
a SQL statement rather than a comment. It reads the `CREATE TABLE` block now, with a vacuity guard,
because a `doesNotContain` over an empty extraction is the one failure a negative assertion cannot
report on its own.

**Thirteen mutations, all caught** — six added by the gate. 825 hermetic tests, 381 database tests.

### Previously

**`P1-TSK-019` — `P1-TST-003`: MFA cannot be bypassed** — `COMPLETE` (2026-09-07).
**M1.4 closes, 3 of 3.**

| Acceptance criterion | Evidence |
|---|---|
| Each path requires the factor or cannot produce a `MULTI_FACTOR` session | One named test per path, five paths |
| The suite fails if the level check is replaced by a boolean | `assuranceIsALevelAndNotABoolean`; a mutation turning `atLeast` into equality is caught |

### The probe found a real bypass, and it is the most serious defect of the milestone

**A `PASSWORD` session could begin a second enrolment with an attacker-controlled secret.** Driven
against a running instance rather than reasoned about:

```
BEGIN  /v1/me/mfa            → 201, and a NEW attacker-controlled secret is issued
CONFIRM /v1/me/mfa/confirmation → IdentityStorageException, unhandled → 500
```

Three things were wrong, in increasing order of seriousness:

1. A **500 for a caller's action**, which `ERROR_CONTRACT.md` §3 forbids and which a client may
   retry for ever.
2. The refusal came from a **partial unique index**, not from a check — `MfaEnrolmentService.begin`
   never looked for an existing factor.
3. **Nothing had ever decided that replacing a confirmed factor should be protected.** Remove that
   index for any reason and a stolen password becomes enough to **swap somebody's authenticator** —
   `INV-IDN-05`'s bypass in its purest form, guarded by a constraint that exists for a different
   purpose.

**The rule now: replacing a confirmed factor requires that factor.** Adding a *first* one does not,
because it cannot — you cannot demand a second factor to enrol your first. Refused at `begin` rather
than at `confirm`, so the attacker never receives a secret and a legitimate customer is told at the
operation they initiated rather than after copying a QR code.

**The check is in the domain, not in `@RequiresAssurance`**, because the requirement is *conditional
on whether a factor exists* and an annotation is static per handler. Declaring it there would forbid
first enrolment outright.

### A list of tests is a snapshot; the guard is what survives Phase 4

`INV-IDN-05` says *"every real bypass is a path nobody enumerated"* — so five tests satisfy its
letter and not its point, because the path added next phase will not be among them and nobody will
remember.

`MfaBypassPathsAreEnumeratedTest` holds the enumeration **against the code**: every production call
site that creates a session or changes its assurance must be named, with the reason it is not a
bypass. A new one fails the build. That is the `P0-TSK-037` shape, applied to routes instead of
provider failure modes.

**What it says today is worth reading twice.** Nothing in production calls `Session.issue`. The only
path that creates a session row is `SessionRotation`, reached only from `MfaChallenge.elevate`, which
refuses without a verified code — so **the only way a session currently comes into existence is a
proven second factor**. That is an accident of sequencing rather than a design goal, and it is
exactly why the guard is written now: `P1-TSK-027` will add the second path and must come here and
say so.

### The paths, and what was cited rather than rewritten

| Path | Outcome |
|---|---|
| An older `PASSWORD` session | **New test.** Assurance is a property of a *session*, never of an identity |
| A refresh | **New test.** Five requests move the idle bound and never the level |
| Re-enrolment | **Defect, fixed** — above, with a positive control proving the rule is conditional |
| Recovery | **Does not exist** (M1.6). A recorded remainder; asserting absence over an unmapped route would pass vacuously |
| A direct call to a session-issuing endpoint | **New test**, plus the guard |

Three properties from `P1-TSK-018` are **cited, not duplicated** — a `PENDING` factor satisfying
nothing, replay refusal, and the `MULTI_FACTOR` requirement. `P1-TSK-012` established that a second
copy of a working assertion is duplication that drifts.

### The level-not-a-boolean criterion needed a value with no producer

`STRONG` has no route that reaches it, and that is not an obstacle: what is under test is the
**mechanism** being a level (ADR-0030), so the test constructs one directly. A boolean cannot
express *"more assured than required"*, so `assuranceIsALevelAndNotABoolean` is the assertion that
cannot be written against `mfaCompleted` — and the mutation turning `atLeast` into equality is
caught by it.

### One test's premise was superseded, and the replacement is stricter

`anActiveFactorSurvivesANewEnrolment` asserted that starting a new enrolment leaves a confirmed
factor untouched. That behaviour is now unreachable at `PASSWORD` — the operation is refused
entirely. Rewritten to assert both halves: refused at `PASSWORD`, and at `MULTI_FACTOR` it proceeds
with the existing factor still `ACTIVE`, so a customer setting up a new phone keeps a working second
factor until they confirm the new one.

### The completion gate found two claims and one gap in my own guard

**The refused replacement writes an audit record, and nothing asserted it.** Its javadoc calls that
record *"the trace of somebody with a stolen password trying to swap a second factor"* — and a
refusal is otherwise indistinguishable from a customer tapping the wrong button, so without it an
attack leaves nothing behind. Now asserted, including that the record **survives**: it is written
inside the transaction and the 403 is thrown after it commits, which is precisely the ordering
`P1-TSK-010` had to get right by *returning* its refusal rather than throwing it.

**The guard checked that origin TYPES exist, not that the METHODS do.** Found by probing rather than
reading: a renamed store method — or a bogus entry added later — would leave the type resolving
perfectly while the origin silently matched nothing, and every path through it would become
invisible. That is a guard reporting coverage it does not have, which is worse than none because it
is believed (`P0-TST-008`). Closed, and proven by an entry naming a method that does not exist.

**Nine mutations, all caught** — three added by the gate. 812 hermetic tests, 370 database tests.

### Previously

**`P1-TSK-018` — Challenge, verification and assurance elevation** — `COMPLETE` (2026-09-07).
**M1.4 is 2 of 3.**

| Acceptance criterion | Evidence |
|---|---|
| An operation requiring `MULTI_FACTOR` refuses a `PASSWORD` session | `assuranceIsRequiredAndSatisfied`, with the elevated session as its positive control |
| A replayed code is refused | Four tests: same code, earlier code, across instances, and the code that confirmed the enrolment |
| Elevation rotates the identifier | The old token is dead on the next request |

### A challenge has nothing to consume, which is why this needed a new mechanism

`P1-TSK-017` made confirmation replay-safe by consuming its `PENDING` row — a second attempt found
nothing to confirm. **A challenge leaves the factor `ACTIVE` and has no state to spend**, and a TOTP
code stays valid for roughly ninety seconds under the ±1 window. Without something new, a code
captured in that window is replayable and *"one-time password"* is simply false.

**The last accepted time step is recorded on the enrolment**, and anything at or before it is
refused — a conditional `UPDATE` whose row count is the outcome, so two instances presenting one
code produce one success. That is **stronger than refusing exact repeats**, which is what RFC 6238
§5.2 actually asks for: a code from the *previous* step is still inside the window and still
arithmetically valid, and it is spent too.

Storing consumed *codes* was the alternative: unbounded, needs a sweep, and strictly weaker.

**Confirmation now consumes its step as well**, which it did not before. Otherwise the code that
confirms an enrolment at step N is still usable for a challenge at step N — a replay across two
operations, and the RFC does not care which operation the first use was.

### Throttling is not optional, and it had been a claim with no test

A six-digit code with a ±1 window is **three valid values in a million** per attempt. RFC 4226 §7.3
requires throttling; without it a challenge is exhausted by automation.

**MFA failures share the account's existing lockout budget, deliberately.** An attacker guessing
codes is by definition somebody who already has the password, so separate counters would hand them
a second fresh budget for no benefit. The statement is the one `AuthenticationThrottle` already
uses, differing only in how the row is sourced — **copying it was refused**, because its reset
condition is two clauses a completion gate had to establish by probing, and a second copy is one
that drifts.

**A replay is refused but not counted.** The common cause is a client retrying after a network
timeout with the same code; counting it would let a flaky connection lock somebody out of their own
account.

### The response carries a session token, and three guards said no

Elevation **rotates** the identifier, so a response that withheld the replacement would log the
customer out at the moment they proved a second factor. There is no second artefact to carry it —
unlike `P1-TSK-017`'s `sharedSecret`, which was removed because the provisioning URI already held
the secret — and wrapping is unavailable, because the platform's serialiser renders `Sensitive` as
a mask and the client would receive «redacted».

**One decision, three enforcement points**, not three concessions: `secretsAreWrapped`'s field
check, its accessor check, and the published-contract guard each encode *"secrets do not leave"*,
and a session token is the one value whose purpose is to leave.

- `secretsAreWrapped` gained its **first exemption**, one entry, with the claim stated. ADR-0019
  built it with none on the principle that a rule with escape hatches grows them, so this was added
  because a case arrived the rule *cannot express* rather than one it merely inconveniences.
- **The exemption permits serialisation and nothing else.** The other harm — a record's generated
  `toString` printing a live token into a log — is closed by an override, and asserted. An exemption
  permitting both would be a hole rather than a decision.
- The contract guard was **narrowed precisely** for the second time: its own reason is about URLs
  and headers, and a response body over TLS is where every session token in the world is delivered.

**Renaming the field to slip past the vocabulary was refused** — the option `P1-TSK-017` declined
for `sharedSecret`, and no more honest for being easier.

### The acceptance criterion needs an operation, and Phase 1 deliberately has none

`PHASE_1_PLAN.md` §66: *"Nothing in Phase 1 requires `MULTI_FACTOR` for a specific action, because
Phase 1 has no high-value action. Consumed by Phase 4."* So `@RequiresAssurance` ships with a probe
endpoint and no production caller. Inventing a requirement to give the annotation something to do
would be a security control chosen to suit a test.

**A distinct error code, not a bare 403**: a client must be able to tell *"you may never do this"*
from *"step up and retry"*, which call for different behaviour. And `@RequiresAssurance` implies
`@RequiresSession`, because a handler declaring only the level would otherwise be reachable
**unauthenticated** — the exact inversion of what its author asked for, and silent.

### Two mutations survived, and each found something

**A detail added to every refusal walked straight through.** `everyRefusalLooksTheSame` compares the
causes to *each other*, so a uniform disclosure kept them equal. Equality between causes proves they
are indistinguishable and says nothing about what they **jointly** disclose. Now asserted against
the contract: the body carries **no `detail` member at all**, which is stronger than a generic one
because there is nothing for a future author to make specific.

**Accepting a `PENDING` factor survived alone**, because `consumeStep` filters on `ACTIVE` as well —
defence in depth, the `P1-TSK-014` shape. Verified rather than assumed: with **both** guards removed
the new test catches it, so `aPendingFactorSatisfiesNothing` is load-bearing regardless of which
one is present.

### The fixture was unrealistic, and the mechanism said so

Every challenge test failed at first because the fixture confirmed with the *current* code, and
confirmation consumes that step. That is the mechanism working: a person confirms an enrolment and
challenges later, not in the same thirty seconds. Time cannot be moved here, so the fixture moved
instead — it confirms with the previous step's code.

### The completion gate found an exemption resting on a test that did not exist

**`secretsAreWrapped`'s new exemption said the `toString` harm was closed *"and `ElevatedSessionTest`
asserts it"*. No such test existed.** That is the fourth occurrence of this pattern in Phase 1 — after
`V005`'s enum claim, `AuthenticationRequest`'s bounds claim and `RequiresSession`'s fail-closed claim
— and it matters more here than in any of them: **an exemption is a claim that a guard's subject is
safe by other means**, so if those means are imaginary the exemption is a hole with a paragraph in
front of it. Written, and the mutation removing the override is caught.

**The exemption also had no staleness guard**, which `P1-TSK-015` established as the rule. It now
asserts the named field is one the rule *would otherwise flag* — not merely that it exists, because
an entry for a field the vocabulary no longer matches is dead weight that reads as a live decision.

### And a contention test whose stated reason was wrong twice

The gate added *"ten instances presenting one code produce exactly one elevation"*, claiming it
exercised `consumeStep`'s conditional. **A mutation removing that conditional survived.** So the
claim became *"rotation's conditional revoke is what serialises"* — and **removing that survived
too.**

The truth is that **two independent guards each suffice**, and the test catches the defect only when
both are gone, which was then verified. That is `P1-TSK-014`'s finding again, and it is recorded
rather than tidied away: an outcome-only test cannot name which mechanism produced the outcome, and
a comment that names one is a claim the suite does not support. `aReplayedCodeIsRefused` is where
`consumeStep` specifically is proven, because a **later** replay has no rotation race to hide behind.

**Thirteen mutations: eleven caught, two survived correctly** — both revealing defence in depth
rather than a gap. 809 hermetic tests, 363 database tests.

### Previously

**`P1-TSK-017` — TOTP enrolment** — `COMPLETE` (2026-09-07). **M1.4 opens, 1 of 3.**

| Acceptance criterion | Evidence |
|---|---|
| Partial enrolment leaves assurance unchanged | `aStartedEnrolmentIsNotUsable`; `findActive` filters in the **statement** |
| A partially enrolled factor never satisfies a challenge | Same, plus the aggregate's `isUsable()` agreeing with the query |
| The secret appears in no response, log or event | Column sweep from `information_schema`; `EventPayload` declares no field for it |

### `INV-IDN-01` cannot apply here, and saying so was the task's central decision

`INV-IDN-01` requires that a credential be stored so the original cannot be recovered, and a password
satisfies it because verification compares **derivations**. **A TOTP secret cannot.** The server
computes the expected code *from* the secret on every challenge, so holding it is the mechanism
rather than a shortcut — there is nothing to compare a derivation against.

Leaving that implicit was the worse option: a reader finding a recoverable secret in an identity
table would have to guess whether it was a defect. So irreversibility is unavailable and
**confidentiality replaces it** — the new **`INV-IDN-08`**, with `INV-IDN-01` gaining an explicit
scope note. The platform now has **72 invariants**.

**And the harm it defends against is different, in one way worse.** A leaked TOTP secret lets an
attacker generate valid codes indefinitely while the customer's authenticator keeps working — so
nothing looks wrong to anybody. A stolen password is at least changeable; a silently cloned second
factor defeats the control that exists to survive a stolen password. That is why the ciphertext is
**authenticated** (AES-256-GCM) rather than merely encrypted: an attacker with *write* access must
not be able to substitute a secret they control.

### The secret is emitted, once, and the plan says it never is

`PHASE_1_PLAN.md` §184 says *"never emitted"* and `INV-AUD-02` forbids credentials in API responses.
**Neither can be met literally**: the customer must receive the secret or MFA cannot work, and the QR
code *is* the secret in base32. So the deliverable is the tightest honest bound — emitted **once**, in
the response to the request that created it, to the **proven owner**, and never retrievable
afterwards. A customer who loses it re-enrols.

### The build rule was right about a field, and the code changed rather than the rule

`secretsAreWrapped` flagged a `String sharedSecret` on the response record, and **wrapping was not
available either**: the platform's serialiser renders `Sensitive` as a mask, so the customer would
have received «redacted». The field is **gone** — the `otpauth://` URI is the canonical artefact every
authenticator consumes, a client that wants a manual-entry string parses it out, and the same secret
in two fields is one more place for it to be logged. `P1-TSK-007`'s lesson, applied: a second
security-rule modification in one task is a signal to reconsider the design.

The *first* modification was kept, and it is structural: **a primitive cannot hold a secret**, so an
`int` named `SECRET_BYTES` — a length — is excluded, the same shape as the existing enum-constant
exclusion.

### "Vetted library" deviated from, with the reason stated rather than assumed

JDK primitives plus RFC 6238's arithmetic. **No library implements the primitive** — HMAC-SHA1 comes
from the JDK either way; what a TOTP library adds is counter arithmetic, truncation and base32. And
**RFC 6238 Appendix B and RFC 4226 Appendix D publish test vectors**, so correctness is demonstrated
against the specification's own numbers rather than against a library's reputation. Twenty-two vectors
are in the suite. **No primitive is invented**, which is what the instruction protects against.

### Four mutations survived first, and each found something real

- **The store's conditional confirm was invisible.** Removing `AND status = 'PENDING'` changed nothing
  sequentially, because `findPending` already returns empty the second time. It is load-bearing only
  under **concurrency** — ten instances that all read `PENDING`, all verify the same valid code, and
  all try to confirm. Without it, one enrolment produces ten audit records and ten events.
- **`MfaKey` had no test at all**, so removing the loopback confinement left everything green:
  `INV-IDN-08`'s *"a key not in the database"* would have become *"a key every reader of this
  repository has"*.
- **`SecretCipher` had no test at all**, and the defect its key-length check prevents is quiet:
  **AES accepts a 16-byte key** and gives AES-128, so a caller passing one gets a cipher weaker than
  the class documents, with nothing failing.
- **Constant-time comparison cannot be caught behaviourally.** `equals` and `isEqual` return the same
  answer; the difference is only timing, and a wall-clock test measures the machine (`P1-TSK-008`).
  Asserted **structurally** instead — the class must reference the constant-time comparison and must
  not reference `String.equals`, the shape `P1-TSK-015` used for the unreachable plaintext.

### Three build rules fired, and all three were right

**`INV-MON-01` caught `Math.pow`** in the RFC truncation. `Math.pow` returns a `double`, and a
codebase that permits one floating-point expression *"because it is not money"* is a codebase where
the rule has an exception list. Integer arithmetic is also simply more correct here.

**`ColumnClassificationTest`** refused thirteen unclassified columns — ADR-0022's guarantee working
exactly as designed. **`CredentialReachesNoEmittedSinkTest`** refused the widened request-body set
until it was declared.

### Two smaller findings

**`identity` gained test fixtures**, and `Authenticator` — what a customer's phone does — lives there
so that generating codes never becomes production API. Its appearance in the unwrap whitelist is
worth recording: **that sweep covers test fixtures**, which is more coverage rather than less.

**`DatabaseCredentialGuardStartupTest` began failing**, and it was my guard being right: the test
constructs a production-like configuration, and since this task a production-like configuration has
**two** credentials. Supplying an MFA key restores its premise and isolates each assertion to the
database guard — the `P0-TSK-031` lesson that a probe must be isolated to its own subject.

### The completion gate found the boundary was never driven

**Every test went through the service; nothing exercised the endpoints over HTTP.** That left four
boundary decisions unasserted, and each is a place a defect would be invisible to a service-level
test: whether `@RequiresSession` is wired at all, whether a wrong code is a **422 rather than a
500**, whether the identity really comes from the session, and whether the request bounds reject
before any domain work.

`MfaEndpointDatabaseTest` closes it, and the ownership case is written in its **strongest** form —
the attacker presents a **valid** code for the victim's enrolment. A weaker version using a wrong
code would pass against an implementation with no scoping at all.

**Also unbacked: *"never retrievable afterwards"***, a javadoc claim with nothing behind it. Now
asserted — there is no read path, and beginning again issues a *different* secret, which is what
makes "a customer who loses it re-enrols" the whole bound rather than a hope.

**And eleven request shapes are driven to prove none produces a 500** — the `P1-TSK-010` probe
applied to this body, because a 500 says our side failed for something only the caller can fix, and
a client may retry it for ever.

**Three more mutations, all caught**: removing `@RequiresSession`, reporting success for a wrong
code, and exporting the login identifier into the QR label — where it would be rendered in the app,
screenshotted into support tickets and synced to whatever backs that app up.

**Fourteen mutations, all resolved.** 804 hermetic tests, 350 database tests.

### Previously

**`P1-TSK-016` — Session and device endpoints** — `COMPLETE` (2026-09-07). **M1.3 closes.**

| Acceptance criterion | Evidence |
|---|---|
| A negative ownership test — one identity cannot list or revoke another's | `SessionOwnershipDatabaseTest`, 9 tests |
| It fails when the check is removed | Dropping `AND identity_id = ?` fails **three** tests |

### Two defects found before any code was written, and both blocked the task

**`SessionRevocation.revoke` took an `owner` and never checked it.** The statement underneath was
`WHERE id = ? AND status = 'ACTIVE'`, so **any caller could end any session by identifier** — while
the audit record confidently asserted an owner nobody had verified. That is exactly the defect
ADR-0031 names: *a legitimate capability used against somebody else's resource, where every check
passes and nothing is logged as a denial.* It is **worse than an absent parameter**, because the
signature reads as though ownership is enforced and the trail is then wrong rather than silent.
`P1-TSK-014` built it that way because it had no caller; this task is the first.

**Nothing in the platform could authenticate a request, and no task owned that.**
`PHASE_1_PLAN.md` §7 marks **eight** endpoints `Auth: session`, and the three tasks that look like
they own the mechanism do not: `P1-TSK-020` (permission) and `P1-TSK-021` (ownership) both
*presuppose* a caller, and `P1-TSK-027` hands a token **out** rather than consuming one. *Who is
calling?* is a third thing sitting below both checks. **Fifth backlog defect of this class in
Phase 1, and the widest** — it blocks eight endpoints rather than one.

Built here, because `GET /v1/sessions` means **my** sessions and without it the task has no
deliverable at all. It is this task's precondition, not future-phase work.

### The `P1-TSK-020` dependency was wrong, and that is the inverse of `P1-TSK-010`'s finding

All three endpoints are available to **every** session-holder against **their own** resources. There
is no role gate; the control is ownership. Delivered in full without roles. Where `P1-TSK-010`'s
`Deps` line was right and its milestone boundary wrong, here the milestone is right and the `Deps`
line names a task this one does not need.

### Ownership lives in the `WHERE` clause, never in a comparison

`revokeOwned` and `findLiveFor` both carry `identity_id = ?` in the statement. Not a
load-compare-act, for two reasons that both matter: the compare-then-act is a TOCTOU race, and
ADR-0031 requires the check against **authoritative state** rather than against a row read a moment
earlier.

`SessionQueries` takes the **proven `Session`**, never an `IdentityId`. That signature is
deliberately awkward: an identifier parameter would be satisfied just as well by one read out of the
request, which is the defect itself. Making the caller hold a proven session leaves the unsafe
version nothing to pass.

### Not yours and does not exist are one answer

Both are `404`, byte-identical — and so is a malformed identifier. Reporting `403` for the first
would confirm that a session identifier belongs to **somebody**, turning the endpoint into an oracle
over other people's sessions. `INV-IDN-07`'s reasoning, which is about a response shape rather than
about passwords.

The same at the door: absent header, wrong scheme, unknown token, revoked, idle-expired and
absolutely expired are **one** `401`, asserted as an **equality between the causes** rather than each
against a remembered expectation.

### The platform's first real inbound actor

ADR-0021 called `enterSystem()` *"the greppable list of places Phase 1 must revisit"*. Every request
through the interceptor now establishes a scope naming the **proven identity**, so an audit record
written under it attributes the action to a person rather than to the platform.

An **interceptor, not a filter**, for both of `P0-TSK-017`'s reasons: a filter runs before handler
selection, so it could not read `@RequiresSession` without a second copy of the routing table, and
it runs outside the exception handler, so its refusal would be the container's page rather than the
error contract.

### Two mutations survived first, and each found a real gap

**Accepting a bare `Authorization` header survived**, because the raw-header shape in the refusal
list used a **revoked** session — refused either way, so it proved nothing about the scheme. Only a
*live* session's plaintext makes that assertion load-bearing.

**Leaving the security scope open survived**, because nothing asserted it was closed. A scope left on
a pooled worker means the next unrelated request runs as that customer and writes audit records
naming the wrong person, permanently (`INV-HIST-03`) — the leak `P0-TSK-032` built `SecurityContext`
to prevent. Now asserted in both directions, including the path where the handler **throws**, which
is why the close is in `afterCompletion` and not `postHandle`.

### Device: sanitised, never refused — the opposite of `PartyName`, on purpose

`V005` gave `device` no bound and no charset because nothing populated it; this task is the one its
comment names. The value is `RESTRICTED-PII`, caller-supplied and rendered back to a person, so a CR
in it is a forged log line and a bidirectional override is a list that lies about which session is
which — the `P1-TSK-006` finding, in the last column on the table that takes a caller's string.

**It sanitises rather than rejects, and the difference is principled.** A display name is the
person's own data, so refusing it tells them to fix something they chose. A `User-Agent` is a header
they did not choose and cannot edit, so **a login must never fail because a browser sent something
odd**; letting an unscored convenience label refuse an authentication would invert its importance
completely. `V007` adds the `CHECK` anyway, deliberately narrower than the domain rule and saying so.

### Three findings in this task's own tests

**The source contained invisible control characters.** `DeviceDescriptionTest` held literal NUL, BEL,
RLO and BOM characters, so tests read as though they exercised ordinary strings while in fact
exercising control characters — unreviewable, and their outcomes coincidental. Found by probing why a
test passed when its visible content said it should not. Every one is now an explicit escape.

**The schema refused a fixture, correctly.** Back-dating only `idle_expires_at` violates
`session_bounds_follow_issue`: a session cannot be written already expired. The constraint was right
and the fixture was wrong.

**A `HandlerMethod` built on `new Object()` carried no annotation**, so the interceptor skipped it —
the guard being right about a fixture that named the wrong bean type.

### The completion gate found two claims nothing backed, and one assertion too loose to catch its own defect

**`RequiresSession`'s javadoc said the fail-closed behaviour "is asserted rather than claimed", and
nothing asserted it.** The pattern this repository keeps meeting — a comment naming a test that does
not exist, after `V005`'s enum claim and `AuthenticationRequest`'s bounds claim. Closed: a handler
reached without the interceptor refuses rather than answering unscoped, and a mutation returning
`null` instead of throwing is caught.

**"Fails closed" on an unreadable database was described and never proven.** That is the
`P1-TSK-012` precedent verbatim, and this task made the claim without its test.

**And the test written for it was too loose to catch its own defect.** A mutation swallowing the
storage failure and reporting `401` **survived**, because it also throws. It is a real defect rather
than a cosmetic one: reporting `401` tells a client their good session is invalid, so a database
blip logs **every user out**, and the operator sees a spike in refusals pointing away from the
cause. It also asserts *"this session is not live"* when the truth is *"we cannot tell"* — which is
`INV-LIFE-03`'s principle applied outside payments. The assertion now requires the failure to
propagate **as itself**, and the mutation is caught.

**The headline claim was asserted at the mechanism and not at the outcome.** The interceptor test
proves `SecurityContext` holds the right actor; `INV-AUD-01` is about the durable **record**, and an
actor nothing writes down is a mechanism with no effect. A revocation over HTTP is now asserted to
produce an audit record naming the **person**, with the session as its target — and a mutation
retargeting it at the identity is caught.

**Thirteen mutations, all caught** — three of them added by the gate. 762 hermetic tests, 334
database tests.

### Previously

**`P1-TSK-015` — Rotation on privilege change** — `COMPLETE` (2026-09-07).

| Acceptance criterion | Evidence |
|---|---|
| The pre-rotation identifier is refused afterwards | `theOldIdentifierIsRefused` |
| The elevated session is a different identifier | `elevationProducesANewIdentifier` |
| No privilege change leaves the identifier unchanged | Both, plus the structural assertion below |

### The completion gate found the untested half of the feature

**Every rotation test elevated `PASSWORD` → `MULTI_FACTOR`.** The task builds *two* cases, and the
second — the **credential change**, which rotates at the **same** level — had no test at all. That is
not a thin spot: an implementation returning early when the level is unchanged would have passed
every other test in the class, and it fails in precisely the situation where fixation is most
dangerous. **A password change is what somebody does after suspecting their session was stolen**, so
leaving the identifier intact there hands the thief the account they were being locked out of.

Closed by `aSameLevelRotationStillReplacesTheIdentifier`, which asserts both halves — a new
identifier *and* a dead predecessor, because a replacement issued beside a still-live original is not
a rotation. Proven by the mutation that motivated it: `if (current.assurance() == toAssurance) return`
fails **exactly one** test, the new one.

**And a parameter was half-ignored, which is a trap rather than a nuance.** `rotate` took a whole
`SessionPolicy` and used only its idle half — the absolute bound is inherited, so there was nothing
for a policy to say about it. A caller passing `SessionPolicy.current()` would reasonably expect both
to apply, and the javadoc saying otherwise is not a control. Narrowed to a `Duration`, so the
signature states what it actually consumes. Same class as the dead aggregate methods the
`P1-TSK-013` gate found: something declared, plausible, and not doing the job its name implies.

**Login needed no code, and that is asserted rather than implemented.** Classic fixation is the
attacker planting an identifier the victim then authenticates *with*. This platform cannot be
attacked that way: a token comes from `SecureRandom` inside the server and there is no path by which
a client supplies one. So the deliverable for that case is a **test that the mechanism refuses** —
adding a rotation step would have implemented a property already true, and hidden that it was.

### The decision nothing had written down

**Rotation preserves the absolute bound.** If it reset it, anyone able to trigger a rotation could
hold a session indefinitely — step up, rotate, step up again — and the absolute lifetime would be
advisory. That is exactly the failure `P1-TSK-013` closed on the *idle* bound with
`LEAST(…, absolute_expires_at)`, returning through a different door. **A step-up must not extend how
long you can stay logged in**; a customer wanting a fresh bound authenticates again, which is an
issue rather than a rotation.

**Revoke first, issue only if the revoke won.** The ordering *is* the concurrency property. Inserting
first and revoking after would leave a loser's session live — a rotation handing out two usable
identifiers where there should be one. Ten instances produce exactly one replacement.

**Audited as a rotation, never as a revocation**, naming both identifiers. An investigator must be
able to tell *"this session was ended"* from *"this session was replaced"*; a rotation logged as a
revocation reads as a logout that never happened.

### One mutation survived, correctly, and sharpened the test

Deriving the new token from the old **survived** — because it derived from the *hash*, which an
attacker never holds. The dangerous version is reusing the old **plaintext**, and that is
**structurally unreachable**: `SessionRotation` only ever receives a `Session`, which carries the
hash. Now asserted reflectively rather than left as a claim, and a mutation adding that parameter is
caught.

**The `NoProcessLocalSessionStateTest` exemption list gained its first entry**, with the claim stated:
`Rotated.session` is a per-call return value, not a retained cache. A staleness guard was added
alongside it, because an exemption naming a field that no longer exists is one nobody can evaluate.

**Six mutations. Five caught, one survived correctly.** 755 hermetic tests, 308 database tests.

### Previously

**`P1-TSK-014` — Revocation, immediate and multi-instance** — `COMPLETE` (2026-09-07).

| Acceptance criterion | Evidence |
|---|---|
| `INV-IDN-03` demonstrated to fail when a session cache is introduced | Caught **twice** — the behavioural test fails, and `NoProcessLocalSessionStateTest` fails independently |
| Revoke on one instance, refused on another | `SimulatedInstance` × 2, own connection each |
| Credential change ends every **other** session | `revokeAllForExcept`, spare asserted |

### The hard reading of §8 was broken, and it was verified broken before the fix

`PHASE_1_PLAN.md` §8 states it as an absolute: *"Revocation wins. A session must never survive a
concurrent revoke."* The easy reading — a lookup racing a revoke — was already true.

**The hard one was not.** A session **issued** concurrently with a revoke-all was still live
afterwards: the insert lands after the revoke has selected its rows, so the revoke never sees it. An
attacker holding the old password keeps a live session across the password change, and **every
revoke-then-look-up test passes**. Probed and confirmed before any machinery was written.

### One explicit lock, not two — and a surviving mutation established that

Bulk revocation takes `FOR UPDATE` on the identity row. An explicit lock was written on the issuing
side too, on the reasoning that both sides of a race must take it. **A mutation removing it
survived**, and the pair of mutations explains why: removing the `FOR UPDATE` from revocation is
caught, removing this is not. PostgreSQL takes **`FOR KEY SHARE` on the referenced row for every
insert with a foreign key**, and the two conflict — the serialisation already existed, supplied by
`identity_id REFERENCES identity.identity (id)`.

Removed rather than left in. A redundant lock reads as *the mechanism* and hides the real one, so the
next person to drop the foreign key would see a lock two lines away and conclude the serialisation
was safe.

### The race test asserts the coordination, not the outcome

Its first version asserted the end state, and the lock-removal mutation survived it — **with** the
lock the insert serialises after the revoke and the new session is live; **without** it the insert
races and the new session is also live. Same rows, opposite mechanisms. It now waits for PostgreSQL
to report the issuer **blocked**, which is the `P0-TST-004` idiom and is deterministic rather than
timed.

**One audit record per operation, never per session.** Forty sessions ended by one decision is one
record with the count in its change summary. The rows carry `revoked_at` and answer *when each
ended*; the trail answers *who decided*.

**`V006` adds the partial by-identity index** `V005` deliberately omitted because no query needed
one. Bulk revocation is that query, added by the task that states it.

### The completion gate sharpened the headline assertion and covered two audit paths

**The lock-wait observation was not scoped to the issuer.** It counted *any* backend waiting on a
lock in the database — a different claim from *"the insert is blocked"*, satisfied by anything else
contending, and passing while saying nothing about the property. It now matches the issuer's own
statement text, and the mutation that removes the `FOR UPDATE` is still caught against the sharper
assertion.

**Only the bulk path had its audit asserted.** Three claims nothing covered: a single revocation
writes a record at all; its target is the **session** rather than the identity, because that is what
was acted on; and a revocation that ended **nothing** writes nothing — a record for a caller's guess
at a session identifier would put identifiers that were never real into the trail. Both mutations
against those are caught.

**Seven mutations. Five caught, one caught twice, one survived correctly** — having removed redundant
code. 754 hermetic tests, 299 database tests.

### Previously

**`P1-TSK-013` — Session aggregate and issuance** — `COMPLETE` (2026-09-07). The platform's
authenticated presence, and the first thing every protected endpoint from here on will hang off.

| Acceptance criterion | Evidence |
|---|---|
| No process-local session state, asserted | `NoProcessLocalSessionStateTest`; a `Map<String, Session>` field on the production store fails the build |
| Expiry bounds enforced | Each bound asserted **alone**, because a suite testing them together passes against an implementation checking only one |
| Expired indistinguishable from revoked | Three situations, one `Optional.empty()` |

**The token is stored hashed, and the plan never said so.** A session identifier is a **bearer
credential** — whoever holds it is the customer — so a database leak with plaintext tokens hands an
attacker every live session with *no work at all*, which is worse than the credential table where
Argon2 at least buys time. `PHASE_1_PLAN.md` §5 already requires the recovery token to be *"hashed at
rest"*; the same argument applies here and simply had not been made.

**SHA-256, deliberately not Argon2**, and that is not an inconsistency with ADR-0032. A password
needs a work factor because it is **low-entropy** — a human chose it. A 256-bit random token has
nothing to guess, so a work factor would buy no security while costing ~46 ms on *every
authenticated request*.

**Two identifiers, two jobs.** `SessionId` is a UUIDv7 for foreign keys, logs and audit records; the
token is 32 random bytes. A UUIDv7 **encodes its creation time**, which is exactly the structure
ADR-0030 forbids in a presented value — and exactly what `EntityId` exists to provide.

**There is no `EXPIRED` status, and that is the sharpest modelling decision here.** A stored one
needs a sweep to write it, and between the moment a session expires and the moment that sweep runs
the database would say `ACTIVE` about a session that is not. Every consumer would check the bounds
anyway, making the status a second answer free to disagree with the first. Derived, there is one.

**Both bounds live on the row**, so a policy change cannot retroactively extend sessions issued under
the old one — `INV-HIST-04`'s reasoning, and tested by issuing under a strict policy and touching
under a generous one.

**Touching is clamped by `LEAST(…, absolute_expires_at)`.** Without it an attacker holding a stolen
token and using it steadily would keep the session alive for ever, and the absolute lifetime would be
advisory.

### The build made two decisions I had made differently

**`secretsAreWrapped` fired on `Session.tokenHash`, and the rule had the better argument.** My
javadoc said a hash is not the secret — it is what remains after the secret was thrown away. True,
and beside the point: `INV-AUD-02` is about what reaches a **log**, and a token hash in a log is a
precise identifier of one customer's live session, which is the single most useful thing to somebody
reading log archives. Wrapped. Third time this phase the right answer was to change the code rather
than the rule.

**`SecretsAreUnwrappedInOnePlaceTest` refused the new unwrap sites until they were named**, which is
the guard doing exactly its job: the set is now six production classes, still all in `identity`,
which is the property that list exists to keep true.

**One defect in my own guard, found by running it.** The cache detector matched by substring, so
`SessionStatus` and `SessionId` were flagged — both *contain* the string `Session`. Word-boundary
matching on the **generic** signature, because a `Map<String, Session>` has a raw type of `Map` and
the cache lives in the parameters.

### The completion gate found three things, and the first is the pattern this phase keeps repeating

**A migration comment claiming a test that covers a different enum.** `V005` said
*"IdentityEnumMigrationTest fails the build if they drift"* about `AssuranceLevel` and
`SessionStatus`; that test covers `IdentityStatus` and nothing else, and **no test reconciled either
new enum with its constraint**. `P1-TSK-007` had established the pattern — `CredentialMigrationTest`
reconciles all three credential enums — and this task simply did not follow it. What could have
drifted is not cosmetic: a fourth `AssuranceLevel` without a matching constraint is a value the
domain produces and the database refuses, failing at the last write after the derivation, for a
reason no error message would explain. `SessionMigrationTest` now closes it, proven by adding a
level and watching the build fail.

**Two aggregate methods were dead code carrying confident javadoc.** `isLiveAt` and
`idleBoundAfterUseAt` were never called — `JdbcSessionStore` does both checks in SQL. That is worse
than no code: the javadoc describes a safety property, and the next reader believes the aggregate
enforces it.

- `idleBoundAfterUseAt` is **deleted**. It duplicated the SQL's `LEAST(…)` with no caller and no
  second consumer.
- `isLiveAt` is **kept and made load-bearing**, because it is the *definition* of liveness and the
  SQL is an implementation of it. Without it the domain rule would live only in a `WHERE` clause,
  where no reader and no architecture rule would ever find it.

**`AssuranceLevel` had no test at all** — the type `INV-IDN-05` names as its own enforcement
mechanism. Now swept over every pair, with the declaration order pinned, because `ordinal()` carries
the meaning and swapping two constants changes every comparison in the platform while compiling
cleanly.

**Eight mutations, all caught.** 754 hermetic tests, 289 database tests.

### Previously

**`P1-TSK-012` — `P1-TST-002`: authentication failure modes** — `COMPLETE` (2026-09-06).

**Two of the four scenarios were already met, and checking rather than assuming is what made the task
worth doing.** *Invalid credential* is `everyFailureLooksTheSame` plus `everyFailingPathDoesTheWork`;
*lockout* is `AuthenticationLockoutDatabaseTest`'s twelve tests. Restating either would be
duplication that drifts, not coverage. So the deliverable is the two that were genuinely missing.

### Concurrent login and credential change

**The risk is not the obvious one.** *Does the old password still work for a moment after a change?*
— it may, the window is milliseconds, and every platform accepts that. The sharp risk is that the
login **reinstates the replaced password**: upgrade-on-use supersedes the credential it read and
inserts a re-derivation of the password just used, so run against a credential the customer has
already replaced it undoes their change, silently, with nothing failing.

**The first version of the test proved nothing, and two mutations said so.** It read the credential,
waited for the change, then called `verify` — but `verify` does its **own** read, so it saw the new
credential, the old password did not match, and the upgrade path was never reached. The change now
lands **inside** the verifier's window through a store decorator.

**Then a third mechanism had to be separated out.** The correct end state is produced by *three*
things: the conditional supersede, the append-only trigger, and the partial unique index. An
outcome-only assertion cannot tell them apart — which is why a mutation making `supersede`
unconditional still survived. The test now asserts the **coordination**: no insert attempted, and
nothing discarded. Together those say the login was *told* it lost rather than finding out by
failing.

### Database unavailable, fails closed

**Fails closed means two things and both are asserted**: no success reported, **and** no durable
trace claiming otherwise. A platform that returned a failure while having committed the audit record
of a success would be worse than one that crashed — the trail would say a person logged in,
permanently, under `INV-HIST-03`.

**The kill was racing a one-millisecond derivation.** A 60 ms sleep before terminating the backend
meant the transaction had already committed and the test disrupted nothing. Deterministic now: the
decorator asks the connection for its own backend identifier and terminates it from inside the flow.
That is `P1-TSK-002`'s lesson — wait on the condition, never for a duration.

**"No session issued" has no subject yet**, and the test says so rather than asserting absence over
an empty table — the vacuity `P1-TSK-009` refused. It asserts *what is committed*, which is the
mechanism that will withhold a session the moment there is one.

### The completion gate found a negative assertion checking nothing

**Proven, not suspected.** Making the failure-counter predicate unsatisfiable left every negative
assertion passing — the query could have been pointing at nothing and the suite would have stayed
green. A query that finds nothing because it is wrong is indistinguishable from one that finds
nothing because there is nothing.

**The positive control could not have caught it**, and that is the more useful half. It drives a
*successful* authentication — and a success **clears** the counter, so it can never demonstrate that
this query selects anything. Only a **failed** authentication leaves the row, so only a failure is a
control for it. Added.

**And the third "trace" was a restatement.** `committedAnythingFor` was derived from the other two
assertions — a check dressed as an independent one, while the **outbox** was not covered at all.
That is a real gap: an announcement of a login that did not happen reaches consumers and cannot be
retracted. It is now the third assertion, scoped to the identity so it can actually fail, and it is
proven non-vacuous by the same probe.

**Three mutations and two vacuity probes, all caught. Five consecutive runs green.**
737 hermetic tests, 281 database tests.

### Previously

**`P1-TSK-011` — Brute-force and credential-stuffing controls** — `COMPLETE` (2026-09-06).

**The task is two controls with two keys, and separating them is the whole design.**

| | Key | Stops | May it change cost or response? |
|---|---|---|---|
| **Lockout** | the identity | credential *guessing* | **No** |
| **Rate limit** | the source | resource *exhaustion* | Yes — it says nothing about any account |

Conflating them produces the defect the instinct leads straight to: refuse a locked account *without*
paying for a derivation, which is the CPU relief lockout appears to be for — and is an
**account-existence oracle**. Attempt often enough against any identifier; if it exists it locks, if
not nothing happens, and afterwards the locked one answers in a millisecond while the unknown one
still costs ~46 ms. `INV-IDN-07` lost to the control added beside it. So **a lock costs exactly what
every other failure costs**, asserted by counting derivations.

### Per-source is not built, and building it would be harmful

`SYSTEM_ARCHITECTURE.md` §Multi-Instance Execution commits to **N replicas behind a load balancer**,
so `getRemoteAddr()` is the balancer: every user shares one bucket, the threshold is reached in
seconds, and **authentication goes down for everyone**. `X-Forwarded-For` is caller-supplied, and
ADR-0034 settled that caller-supplied values are not trusted — there is no trusted-proxy
configuration anywhere here. The missing input is a **deployment topology**, not effort. Recorded as
debt with that trigger.

### `INV-CON-03`, three phases early

*"Limits enforced non-atomically are limits that do not exist."* Catalogued at Phase 13; enforced
here because ADR-0032 makes verification expensive and names lockout as part of the same design.

**One row per identity, not one row per attempt.** An append-only attempt log is this platform's
usual idiom and is wrong here: counting rows in a window is a read-then-count, so ten concurrent
attempts at the threshold all read nine and all proceed. The whole protocol is one statement —
`INSERT … ON CONFLICT DO UPDATE … RETURNING` — where the post-increment count is produced **by the
write**. Ten simulated instances, own connection each, produce exactly ten.

**Keyed on the login identifier, resolved by a subselect inside the same statement.** The obvious
alternative — look the identity up, then record if found — runs one query when the account is absent
and two when it is present, which is a timing difference that discloses existence. It also keeps
`VerificationOutcome` opaque: having verification *report* which identity it tried would put back
exactly the field `P1-TSK-008` removed, and its reflective guard would fail.

**The lock is time-bounded and self-healing, with no operator unlock.** Lockout is itself an attack —
anyone who knows a login identifier can lock its owner out by failing ten times — and a lock needing
an operator to clear it converts that cheap attack into a support-desk denial of service, and gives
an insider a standing reason to touch other people's accounts.

### The platform's own guard found the one defect

`recordFailure` wrote its audit record **outside** the security scope, and every lockout test failed
with *"no actor has been established for this flow"*. That is `P0-TSK-032`'s refusal to default the
actor doing precisely its job: a default would have accepted the mistake silently and recorded the
wrong party — permanently, under `INV-HIST-03`.

### The completion gate found the lock was permanent

**The most serious defect this phase has produced, and the suite passed over it.** After a lock
expired, **one** failure re-locked the account for another full period — so an account locked once
was locked **for ever**, at one attempt per lock period. That is precisely the attack the design
claims to avoid and the migration's own comment says cannot happen.

**Why the tests missed it.** `anExpiredLockClearsItself` authenticates *successfully* after the lock
expires, and a success deletes the row — so the path where the next attempt is another **failure**
was never exercised.

**Why the code was wrong.** The reset was guarded on `locked_until IS NULL AND the window elapsed`.
Since `window_started_at` always precedes `locked_until`, an expired lock implies an expired window
— so the `IS NULL` half blocked the reset at exactly the moment it was due.

**And the first fix was still wrong, which the new test caught.** Relaxing it to *"no live lock and
the window elapsed"* works only because the shipped policy makes window and lock both 15 minutes.
That is coincidence, not equivalence: `LockoutPolicy(3, 60min, 1min)` is legal and expires the lock
while the window is live, restoring the permanent lockout. The rule had to be stated as two
independent clauses:

- **a served lock ends the run**, whatever the window says;
- **an elapsed window ends it**, provided no lock is live — without which an attacker waits out the
  window instead of the lock.

Both are now proven load-bearing by separate mutations, and a test runs under a policy whose window
and lock differ, so the coincidence cannot hide it again.

**Seven mutations, all caught.** 737 hermetic tests, 272 database tests.

### Previously

**`P1-TSK-010` — `POST /v1/authentications`, enumeration-safe** — `COMPLETE` (2026-09-06). The
platform's second endpoint, and the first that can say *no* without saying *why*.

| Acceptance criterion | Evidence |
|---|---|
| One response shape for every failure (`INV-IDN-07`) | `everyFailureLooksTheSame` — the four causes asserted **equal to each other** |
| Equivalent cost for every failure | `everyFailingPathDoesTheWork` — five paths, one derivation each, **counted** |
| A success is audited and announced | Audit record with a real actor; `identity.AuthenticationSucceeded` |
| A failure is audited **and committed** | The refusal is returned, never thrown |

**The plan contradicts itself about the session, and the contradiction is recorded rather than
resolved by an implementation task.** `P1-TSK-010` declares `Deps: P1-TSK-013`, which is `TODO`;
`PHASE_1_PLAN.md` §11 puts *session issuance* in **M1.2's scope** and makes M1.2's acceptance *"an
identity authenticates and receives a session"* — while numbering the session tasks into M1.3.
Fourth backlog defect of this class. Unlike `P1-TSK-006`'s, here the **`Deps` line is right and the
milestone boundary is wrong**: "that person can authenticate" is not a milestone without a session.

**So this delivers the endpoint and not the session.** A success returns **204 with no body**, and
**M1.2 cannot close on `P1-TSK-012`**. Carried as the new **`P1-TSK-027`**.

**The refusal is returned, never thrown, and that is structural.** A failed authentication writes an
audit record in the same transaction — the only way credential stuffing is visible at all — so
throwing to produce the 401 would roll it back and destroy it. Proven: making the service throw
fails the suite.

**Not idempotent, and that is stronger than "the money-moving clause is vacuous."** An idempotency
key is explicitly *not a secret* (`API_CONVENTIONS.md` §6), so a stored success keyed on one would
let anybody who saw the key in a proxy log **replay a successful authentication**. The mechanism
that makes registration safe would make this endpoint an authentication bypass.

**The platform's first real actor.** A success is attributed to `Actor(identityId, CUSTOMER)`; a
failure to the platform, because there may be no identity at all. The asymmetry is *correct
information* rather than a channel — registration's uniformity argument does not transfer, because
there the success actor would have been circular and here it is proven. `PHASE_1_PLAN.md` §5 says
the `enterSystem()` call sites are revisited in this phase; this is the first one with a better
answer available.

### The build found a real defect in the request DTO, and it was right

`secretsAreWrapped` rejected `String password` — twice, once for the component and once for the
accessor a serialiser reads. **The rule was right, and this is the most important subject it has
had.** A record's generated `toString` prints every component, so `log.info("{}", request)` would
print a customer's password: no getter call, no concatenation, nothing a reviewer stops at. This DTO
is the exact place a plaintext enters the platform, which makes it the first place it could leave.

Wrapping it required a Jackson **deserialiser** — the symmetric half of `P0-TSK-030`'s masking
serialiser, which had never been needed because no request body had ever carried a secret.

### Two contract defects, both found by generating the document rather than reasoning about it

**The wrapper published as an empty schema.** `Sensitive<T>` has no accessible property, so springdoc
emitted `password: {$ref: SensitiveString}` pointing at `{}` — a generated client would model a
password as an untyped object and would not know to send a string. Every other check passed over it:
it parses, it diffs, and `everyReferenceResolves` is satisfied because the schema exists. Fixed by
telling springdoc the wire type, in **test scope**, because the running application ships no
documentation library (ADR-0015) — and a new guard, `everyPublishedSchemaSaysWhatItIs`, now fails
the build on any empty schema, because the next wrapper will not be called `Sensitive`.

**`P1-TSK-009`'s contract guard fired on this task, one task later — which is what it was for.** It
was right to fire and wrong about why: it forbade a credential-named member *anywhere*, and an
authentication request body must declare a password or no client can call the endpoint. The precise
property is narrower and more useful: **a secret may be sent, never returned, and never put where a
URL or a header goes** — those reach access logs, proxies and browser history. The permitted set is
pinned to two named request schemas, so widening it is visible.

### One mutation survived and produced a new test

Making a malformed password fail **without doing the derivation** left all four responses
byte-identical and changed only how long one of them took. `everyFailureLooksTheSame` could never
have caught it — it compares responses, and the disclosure is through the clock.

That is `P1-TSK-008`'s own finding — *assert by counting work, not by reading a clock* — reproduced
one task later by the person who wrote it down. Closed by `AuthenticationCostsTheSameDatabaseTest`,
which counts derivations across **five** failing paths and one success.

**A second mutation survived correctly**, and that is worth separating from a miss: making the
refusal echo the attempted identifier changed only the **log** message, because `ApiException` keeps
that separate from what a client is told (`P0-TSK-024`). The mutation was aimed at the wrong field.
Re-aimed at the first step a diverging response would actually need — a `reason` field on
`VerificationOutcome` — it is caught by `P1-TSK-008`'s reflective guard.

### The completion gate found a documented test that did not exist

`AuthenticationRequest` restates `LoginIdentifier`'s bounds as literals, and its javadoc said *"a
test asserts they still match."* **No such test existed** — while the sibling `RegistrationRequest`
had one all along, which is what made the sentence read as true.

The drift it claims to guard is real: if the domain charset narrowed, the boundary would accept a
value the domain then refuses, `new LoginIdentifier(...)` would throw inside the service, and the
caller would get `api.InternalError` — **our fault reported for their input**, which
`ERROR_CONTRACT.md` §3 forbids and which a client may retry for ever. `AuthenticationRequestTest`
now sweeps **every code point the boundary admits** rather than sampling, because nobody guesses in
advance which character the mismatch will be.

### Three more, all found by probing shapes the code was not designed against

**No request shape produces a 500** — eight of them driven over real HTTP: absent, null, empty,
numeric, object, array and oversized passwords, and a login identifier the charset refuses. All land
on 400, 401 or 422. Now pinned by test, including the two decided by *different* mechanisms: a
number is **coerced** and fails authentication, an object **never reaches the deserialiser** and is a
400.

**A "hardening" fix that was unreachable code.** The gate first added a null-check to the
deserialiser for the object case, with a comment explaining what it handled. Probing Jackson
directly showed it throws `MismatchedInputException` **before** the deserialiser runs — so the branch
never executed and its comment described a mechanism that is not the real one. Removed, and the
comment now states what was measured. A fix that reads correctly and does nothing is the shape this
repository keeps meeting; the only thing that separates it from a real one is running it.

**The correlation exclusion was a hole, not an allowance.** `everyFailureLooksTheSame` strips the
correlation identifier before comparing, so its *absence* would have been invisible — four responses
missing it entirely would still compare equal. The raw value is now asserted present first.

**The throttling debt was named in the design and had not been recorded.** Now a row: this endpoint
is a **CPU and memory amplifier**, because ADR-0032 makes each attempt cost ~46 ms and ~19 MiB *by
design* — the work factor that protects a stolen credential store is the same work factor an attacker
spends for free.

**Six mutations, all resolved.** 737 hermetic tests, 260 database tests.

### Previously

**`P1-TSK-009` — `P1-TST-001`: credentials never leak** — `COMPLETE` (2026-09-06).

**The stated acceptance criterion was already met, and checking rather than assuming is what made
the task worth doing.** *"Fails when a credential field is added without wrapping"* is
`secretsAreWrapped`, which `P0-TST-008` found **structurally incapable of failing** and then fixed.
Probed here against **real production code** rather than the fixture — an unwrapped `String
lastPassword` planted in `CredentialVerifier` fails the build twice, once for the field and once for
the accessor. Implementing that criterion again would have been a second copy of a working rule.

**So the deliverable is the gap between the task's two clauses**, which are not the same claim. The
field rule governs what a **type stores**. It cannot see a secret held only in a **local**, one
inside a message the platform did not write, the **MDC**, an **event payload**, a **metric tag** or
a **span attribute** — none of which is a field on our types.

| Sink | What was added |
|---|---|
| **Log** | `CredentialNeverReachesALogTest` — `RawPassword` and `Credential` logged the careless way, through the **real ECS encoder on captured output**, with a negative control |
| **Log (production)** | `CredentialVerifierLogsNothingSensitiveDatabaseTest` — the platform's *one* production log call on the credential path, driven by a real injected failure against a real PostgreSQL |
| **Event** | `EventPayload` **cannot carry a derivation at all**, asserted rather than left coincidental |
| **Response** | The published contract declares no member whose name says it holds a secret |
| **Span, metric** | **Cited, not duplicated** — `MetricConventionTest` and ADR-0017 already fail the build on these |

**Three of the five named sinks have no credential-carrying producer yet**, and that is a reason to
write the guard now rather than to defer it — but **the artefact has to be the right one**. An
assertion that a credential is absent from an empty event stream passes vacuously, which is the
"green while checking nothing" shape this repository has met six times. So for a sink with no
producer this asserts **the mechanism that will refuse the producer**, which has a subject today and
becomes load-bearing the moment `P1-TSK-010` or `P1-TSK-026` lands.

**The recorded Phase 0 debt was answered rather than carried forward.** *"No output scrubber for
text the platform does not control"* named its trigger as *"a business module logging real flows"*
and its owning phase as Phase 1; `identity` is that module. **The scrubber is not built**: it is a
deny-list, and to recognise a secret it must be *given* the secret — so the plaintext travels
**further**, into a filter on every log statement, rather than less far.

**What replaces it is the opposite shape and is checkable.** A plaintext reaches a sink only if
something first *unwraps* it, and every unwrap is a call to `expose()` — named to be found,
deliberately. `SecretsAreUnwrappedInOnePlaceTest` pins that set to **four production classes, all in
`identity`**, so a new unwrap anywhere fails the build. It reads method **references** as well as
calls, because a method reference is an `invokedynamic` with no call site — the bypass the
`P0-TSK-013` review found in the ambient-time rule.

**The limits are stated rather than glossed**, in the tests themselves:

- **`EventPayload` is a charset, not a secret detector.** A password of `hunter2` satisfies
  `[A-Za-z0-9_-]` perfectly and would be published. What actually keeps credentials out of events is
  that no event declares a credential field — a `P1-TSK-010` design property, not this one. A test
  asserts the *acceptance*, so the limit is visible rather than inferred.
- **The unwrap whitelist says where, not what happens next.** Inside `identity` a plaintext could
  still be handed to a log call and nothing mechanical would catch it.

**One mutation reported SURVIVED and had never landed** — the fourth occurrence of that class here.
The planted contract member targeted `"loginIdentifier" : {`, and the document is formatted
`"loginIdentifier": {`, so the replacement matched nothing and the guard was reported as toothless
when it had never been tested. Re-run with the marker **asserted present before the edit**, it is
caught. Checking that a mutation actually landed is the only thing that separates a proof from a
reassuring message.

### The completion gate found the rule's vocabulary was one third dead

**Measured, not read.** `secretsAreWrapped` splits a field name on camel-case boundaries and
compares each **word** against a vocabulary — and that vocabulary uses **compound** forms where the
bare word has innocent uses, exactly as ADR-0019 and the rule's own javadoc describe. Those two
facts cannot both be delivered: the splitter turns `apiKey` into `[api, Key]`, so a compound entry
can never match.

**Six of the twenty-two entries were structurally unreachable** — `apikey`, `privatekey`,
`signingkey`, `cardnumber`, `mfacode`, `sessionid`. `secretKey` was caught, but only by accident,
because `secret` is separately an entry.

**Proven in both directions against the build**, not argued: a production `String cardNumber` field
**passed cleanly** before the fix and fails after it. That is the PAN field ADR-0019 added
specifically so that widening PCI scope *"fails the build rather than arriving quietly"*, and
`sessionId` — Phase 1's next subject, three tasks away — was in the same state.

**This is `P0-TST-008`'s finding again, one layer in.** That review found the rule *could not fail
at all*; this found that a third of what it claims to check, it does not. A control reporting
coverage it does not have is worse than none because it is believed — and `P1-TST-001` exists
precisely because this rule's teeth were once imaginary.

Closed by matching **adjacent word pairs** as well as single words. Precise rather than fuzzy:
`idempotencyKey` yields the pair `idempotencykey`, which is not in the vocabulary and stays clean,
as do `companyName` and `spinLock`. Substring matching would have caught the six and reintroduced
exactly the false positives ADR-0019 excluded `key` to avoid. One fixture per dead entry, because an
aggregate probe reporting "caught" says nothing about which of six it caught.

### Three further gate findings, all in this task's own work

**The coverage guard could not see a module leave the sweep.** It asserted
`contains("identity", "platform", "sharedkernel")` where every sibling rule suite asserts
**equality** against `ProductionModules.onClasspathWithProductionClasses()`. Narrowing the sweep so
`app` was never analysed left it green — and `app` is where a credential would most plausibly reach
a response, since that is where the controllers are. The **exact** finding the `P0-TSK-008` review
made, with the corrected idiom sitting two files away. Deviating from a sibling idiom is what hid
`secretsAreWrapped`'s inversion in the first place.

**The contract guard read keys and not values.** OpenAPI declares a parameter as
`{"name": "token", "in": "query"}` — the secret name is a **value** there, so a key-only scan
reported a clean contract for `GET /v1/things?token=…`. Found by probing shapes it was not designed
against. Its remaining limit is now asserted rather than implied: it matches names, so a credential
in an `example` **value** is not caught, and the control for that is the repository-wide secret scan.

**The secret vocabulary had already drifted.** This task's second copy was missing `signingkey` and
`cvv2`, so a `signingKey` property could have reached the published contract while the build rule
forbade the field holding it. Reconciled by test rather than merged — the two lists stay where they
are and disagreeing fails the build.

**One mutation reported SURVIVED and had never landed, twice.** First the contract member, whose
marker did not match the document's formatting; then three vocabulary probes whose backup file had
silently failed to be written, so every result read `NOT CAUGHT` against unmutated code. Fifth and
sixth occurrences of that class here. Every mutation in this task is now applied with the plant
**asserted present before the build runs**.

**Eleven mutations, all caught.** 732 hermetic tests, 250 database tests.

### Previously

**`P1-TSK-008` — Verification and upgrade-on-use** — `COMPLETE` (2026-09-06). The platform can
decide whether somebody knows the secret, and strengthens the credential while it is legitimately in
hand.

| Acceptance criterion | Evidence |
|---|---|
| A credential under weak parameters verifies and is upgraded | `aWeakCredentialIsUpgraded`: old superseded, new at policy, same password still works |
| Timing for an absent identity is equivalent to a wrong credential (`INV-IDN-07`) | `everyFailingPathDoesTheWork`, counting derivations rather than reading a clock |
| The store converges without a forced reset | The same test; nothing is asked of the customer |

**Every failing path performs a full Argon2id verification** — four of them: no identity, an
identity that cannot authenticate, an identity with no credential, and a wrong password. The middle
two are the ones an implementation skips, and they are the ones that matter: **a suspended account
answering instantly tells an attacker both that it exists and that it is suspended.** That is
`INV-IDN-07` lost through the timing channel rather than the response body, which is the harder half
to notice and the harder half to test.

**Asserted by counting work, not by reading a clock.** A wall-clock timing test is flaky and
measures the machine; a counting deriver is deterministic and measures the property actually at
stake — *did the expensive path run at all?* Each failing path is asserted to perform exactly one
verification.

**`VerificationOutcome` carries no reason, and that is structural.** A failure has no reason code, no
status, no `Optional` that is empty in one case and populated in another — and every failure is
literally the *same object*, so not even reference identity distinguishes them. A caller branches on
whatever it is handed; a `reason` field is an enumeration oracle with a delay fuse, harmless the day
it is added and a second response shape the day somebody maps it to a message. A test derives the
type's members reflectively, so adding one fails the build.

**An upgrade failure never fails a correct authentication.** The customer typed the right thing;
refusing them because a background optimisation collided would be a self-inflicted outage. The
upgrade sits behind a savepoint and every failure of it is discarded — proven by injecting one and
asserting both that the login succeeds *and* that the credential is left intact rather than
superseded with no replacement, which would lock the person out permanently.

**This is the platform's first genuine read-then-write**, so `P1-TSK-007`'s claim that there is "no
read-then-write anywhere" does not extend here and is not relied on. What makes it safe is that the
write is **conditional**: `supersede` moves the row only while it is still `ACTIVE`, and its row
count is the outcome.

### Two defects found while building it

**An auto-commit connection made the upgrade silently not happen.** `setSavepoint` throws on such a
connection, and the upgrade's catch-all discarded it as an ordinary collision — so every login would
have verified correctly and upgraded **nothing, permanently**, with a warning nobody reads and no
test failing. A control reporting success for work it did not do, which is the shape this repository
keeps meeting. Closed by refusing an auto-commit connection **up front**, with a message about the
mistake rather than about the mechanism — the `JdbcInboxRecordStore` precedent.

**A surviving mutation showed the concurrency test asserted the outcome rather than the
coordination.** Ignoring the conditional supersede's answer *still* produced exactly one upgrade —
because the nine losers then collided with the partial unique index and the catch-all swallowed it.
Same result, worse mechanism, and the javadoc's claim that *"the index is never even reached"* would
have been false with nothing failing. The test now counts **inserts attempted**: one, not ten.

**A third security-rule false positive, answered by narrowing the rule compositionally.**
`secretsAreWrapped` fired on a `CredentialStore` collaborator field and on a private factory
returning a `RawPassword`. Renaming was not available — the `identity` module's collaborators are
named after credentials because that is what they are for. The narrowing is principled rather than
convenient: **a field whose type is one of our own types is already checked at its own declaration**,
so wrapping the reference protects nothing, and `Sensitive<RawPassword>` would double-wrap a type
whose whole job is to wrap. It touches no JDK type, which is where a secret actually lives, and it is
**proven load-bearing** — removed, the rule fires three times again.

### The completion gate found a leak in the mechanism protecting the secret

**PostgreSQL puts the entire refused row in a `CHECK` violation's `DETAIL`**, and the driver puts
that in `SQLException.getMessage()`. Probed rather than reasoned about:

```
ERROR:  new row for relation "credential" violates check constraint
        "credential_derivation_is_encoded"
DETAIL:  Failing row contains (…, ARGON2ID, 1024, 1, 1, hunter2-my-actual-secret-password, …)
```

Our storage exceptions carried that `SQLException` as a cause, so **the constraint that exists to
stop a plaintext being stored caused the plaintext to be logged when it fired** — `INV-AUD-02`
defeated by the mechanism protecting `INV-IDN-01`.

**It was never confined to credentials.** The same `DETAIL` carries a person's name out of
`party.party` (`display_name`, `RESTRICTED-PII`, and `V003`'s control-character `CHECK` fires on
exactly the input a caller controls) and a login identifier out of `identity.identity`
(`CONFIDENTIAL`, because it carries existence). Three tables, one defect, introduced progressively
across `P1-TSK-005`, `-006` and `-007` — each new `CHECK` constraint made it reachable in one more
place.

**Closed by making the safe path the only path.** `DatabaseFailure.describe` keeps the operation,
the identifier and the **SQLState** — five characters saying which class of failure — and drops the
`SQLException` entirely. The three storage exceptions **no longer have a constructor taking a
cause**, so attaching one is a compile error rather than a decision somebody makes at 5pm. The cost
is real and accepted: an operator loses the driver's own frames on these tables, which is a smaller
loss than a log aggregator holding passwords with months of retention.

**The guard that survives is the reflective one.** An earlier version drove the `party` path and
could pass vacuously — it guarded on whether anything was thrown at all, which is the "green while
checking nothing" shape this repository has now met six times. The constructor check cannot: it
fails the moment the unsafe path exists again, proven by mutation.

**And the correlation-sink guard fired for the eighth time**, on the new `platform.persistence`
package, forcing a decision about whether correlation reaches it before the package could land. It
does not — the concern shapes what a log line *says* and carries no identifier of its own.

**Seven mutations, all caught** — after one survived and improved a test.

718 hermetic tests, 249 database tests.

### Previously

**`P1-TSK-007` — Credential storage** — `COMPLETE` (2026-09-06). The platform can hold a secret it
cannot recover, and can say per credential how strongly it was protected.

| Acceptance criterion | Evidence |
|---|---|
| No persisted or emitted representation contains the input (`INV-IDN-01`) | `CredentialNeverLeaksDatabaseTest`; dropping the encoded-form `CHECK` fails it |
| Parameters recorded (`INV-IDN-02`) | `credential_derivation`, algorithm and three cost factors, all `NOT NULL`; making one nullable fails a test |
| A credential is superseded, never updated | A `BEFORE UPDATE` trigger; removing it fails two tests |
| Both invariants demonstrated to fail when broken | **Seven mutations, all caught** |

**The decision this task exists for is not which algorithm — it is where the parameters live**
(ADR-0032). A platform whose work factor is a global setting cannot raise it: changing the setting
changes what *new* credentials use, nothing records what the old ones used, and the only exits are a
forced reset for every customer or a guess. Recorded per credential, *"how strongly was this one
protected?"* is answerable permanently and *"which are below current policy?"* is an **indexed
query** — which is the whole reason the parameters are columns as well as being inside the encoded
derivation.

**`INV-IDN-01` landed at `DB-CONSTRAINT`, which is stronger than the task asked for.** The derivation
column will not accept a value that is not in its algorithm's encoded form, so **a plaintext password
cannot physically be stored** — not by a migration, not by an operator, not by code nobody has
written yet. `DB-CONSTRAINT` outranks `DOMAIN` and `STATIC` in the catalogue, and this is the
platform's most consequential secret, so it gets the strongest mechanism rather than the most
convenient one. The leak test asserts against **every column of the row**, with the column list
derived from `information_schema` rather than listed — the obvious version of that test checks the
column its author was thinking of and would pass against an implementation that wrote the password
somewhere else as well.

**Measured, not asserted: ~46 ms per derivation** at m=19456 / t=2 / p=1. ADR-0032 asks for
parameters chosen against a *stated* verification time, and a stated time nobody measured is not
stated. The assertion is a **floor, not a ceiling** — a ceiling is a flaky test on a loaded machine,
whereas a derivation completing in under a millisecond is the failure actually worth catching. 46 ms
is at the fast end of the usual target and is deliberately **not** raised here: raising the work
factor is a capacity decision belonging beside the rate limiting ADR-0032 already names as part of
the same design (`P1-TSK-011`), and 19 MiB *per concurrent derivation* means ten simultaneous logins
on one instance is ~190 MiB.

**The library needs more at run time than its POM declares, and only running it found that.**
`spring-security-crypto` 7.1.1 lists exactly one dependency — an *optional* assertj. It in fact needs
**BouncyCastle** to derive and **spring-core** to verify, each arriving as a separate
`NoClassDefFoundError` from a test using the real encoder: one at construction, one at `matches`. A
test double would have found neither, and the failure would have arrived at the first real login. So
`identity` does take a Spring Framework runtime dependency — recorded plainly rather than described
away, because the tidy description ("a standalone jar") was mine and was wrong three times running.

**The cold regeneration earned its place on its first outing since `P0-TSK-042`.** Regenerating the
verification metadata against a **warm** cache recorded three new components; the mandated **cold**
run added a fourth — `jackson-base-2.21.5.pom`, a **descriptor, not a jar**, which is exactly the
signature that finding identified. Proven complete by a second run against a separate empty home
with enforcement on and no write flags.

**Two existing security rules fired, and they got different answers.** `secretsAreWrapped` flagged
`CredentialType.PASSWORD` — an **enum constant**, which is a value of its own enum type and can
never be a secret. Renaming it was the alternative, and `PASSWORD` is exactly what that constant
should be called; every future `TokenType.BEARER` hits the same thing. So the rule gained a
**structural exclusion for enum constants**, with the `P0-TSK-041` precedent (the synthetic
`$VALUES` array, excluded for the same reason), and the exclusion is **proven load-bearing**: removed,
the rule fires again.

**The second one I answered by deleting my own code, and that is the more useful finding.** The rule
then flagged `passwordDeriver()` and `credentialStore()` — `@Bean` factory methods, not accessors.
That was the *second* security-rule modification in one task, which is a signal worth heeding rather
than pushing through. The honest answer was that **the wiring should not exist**: nothing consumes
either bean, `EXECUTION_PROTOCOL.md` rule 3 says a seam only, and the port is the seam. Removing the
two beans removed the false positives without touching a control. A third false positive — a
`COMMENT ON` body reading as `secret: <value>` — was answered by rewording prose, which cost nothing;
the rule's inability to tell a SQL comment from a credential assignment is recorded rather than
widened.

**Scope kept, with the owning task named for each omission.** Verification and upgrade-on-use are
`P1-TSK-008` — `isWeakerThan` exists and is tested and nothing calls it. The endpoint and
registration integration are `P1-TSK-026`. Session revocation on change is M1.3. One value in each
enum, because `EXECUTION_PROTOCOL.md` rule 3 forbids WebAuthn now and the enum *existing* is the
seam ADR-0032's follow-up needs.

**The completion gate found the defect that matters, and it was in what the design got right on
paper.** `Credential.derived` took the algorithm, the parameters **and** the finished derivation as
three independent arguments. A probe passed `DerivationParameters.current()` alongside a derivation
produced at m=1024/t=1/p=1 and it was accepted: the columns said the credential was strong, the
encoded string said it was weak, and **`isWeakerThan(current())` answered `false`**.

That is `INV-IDN-02` satisfied in form and defeated in substance. The invariant is not *"the columns
are populated"* - it is *"the recorded parameters are the ones that produced this derivation"* - and
a credential that misreports its strength is **worse than one recording nothing**, because an
upgrade campaign skips it while believing it was assessed. The duplication ADR-0032 Option D takes
deliberately was, until the gate, duplication that nothing reconciled - which is the exact risk this
task's own commentary described and did not close.

Closed by construction rather than by a check: `Credential.forPassword` takes the **deriver** and the
plaintext, so the algorithm, the parameters and the derivation all come from one place and there is
no argument left for a caller to get wrong. `derived` is gone; the two ways in are now *derive a new
one* and *rehydrate a row the database already validated*.

**One javadoc claim was corrected rather than left tidy.** `isWeakerThan` asserted that "any factor
being lower" means weaker, which is true of memory and iterations and **not** of parallelism - more
lanes spread the same total work rather than adding to it. Parallelism stays in the comparison,
because the goal is convergence on current policy rather than strength alone, and the cost of
including it is an occasional unnecessary re-derivation at the one moment the plaintext is
legitimately in hand.

**Eight mutations, all caught.**

713 hermetic tests, 235 database tests.

### Previously

**`P1-TSK-006` — `POST /v1/registrations`, idempotent** — `COMPLETE` (2026-09-06). The platform's
**first endpoint**, its **first domain events**, its **first emitted audit records**, and the first
real user of `P0-TSK-017`'s `@RequiresIdempotencyKey`.

| Acceptance criterion | Evidence |
|---|---|
| Atomicity — a failure leaves no Party, Customer, Identity, audit row or outbox row | `RegistrationAtomicityDatabaseTest`; an intermediate commit fails all four of its tests |
| A retry with the same key creates nothing more and replays the original response | `aRetryIsIdempotent`, `aReplayIsNotAnnounced` |
| A differing fingerprint on a known key is a distinct conflict (`INV-IDEM-03`) | `409 api.Conflict`, and nothing created |
| A collision is indistinguishable from an unrelated failure (`INV-IDN-07`) | One refusal shape; a mutation that says *why* fails the test |

**Delivered without the credential leg.** The task declares `Deps: P1-TSK-007`, which is `TODO`, and
the instruction was to implement this task alone. Two consequences are recorded rather than absorbed,
and carried as **`P1-TSK-026`**: a registered Identity **cannot yet acquire a credential** —
`POST /v1/me/credential` needs a session, a session needs authentication, authentication needs a
credential — and adding a required `password` later is a **`BREAKING`** change to a published `/v1`
contract, on the platform's first endpoint. Neither is fatal, since there is no client; both are
worse if left implicit.

**The response body is empty, and that is a security decision rather than laziness.**
`API_CONVENTIONS.md` §6 states that the idempotency key **is not a secret and is not redacted**, so
anyone who has seen a key — from a proxy log, an access log, a client's own logging — can replay this
unauthenticated endpoint and receive whatever it returns. Publishing the Party, Customer and Identity
identifiers would hand a stranger three identifiers belonging to someone else. Nothing in Phase 1's
API surface consumes them. There is no replay header for the same reason: telling the caller it was a
replay tells a replaying stranger that the login identifier exists.

**Registration is permanently the one endpoint whose idempotency scope cannot carry a principal**,
because it is the endpoint that creates one — ADR-0004 asks for the command type **and** the owning
principal. The residual is stated rather than glossed: an attacker holding a key *and* knowing the
exact login identifier and display name can obtain a replay, and what bounds it is precisely the
empty body, so what they learn is that the request succeeded and nothing more.

**The credential is deliberately excluded from the request fingerprint, and stays excluded.**
`request_fingerprint` is a durable single-round SHA-256; hashing a body containing a password would
store an offline-crackable derivation of it — `INV-IDN-01` violated by the idempotency mechanism.
`RequestFingerprint` leaves the choice of significant fields to each command precisely so a command
can make that call.

**A savepoint is what makes a collision reportable at all.** A taken login identifier arrives as a
unique-index violation, and PostgreSQL *aborts the transaction* when it raises one — so without a
savepoint nothing further could be written, the idempotency outcome included, and the client's retry
would re-run the command rather than replay its refusal. **A pre-flight `SELECT` is not a substitute
and is documented as such**: two instances would both see the identifier free, both insert, and one
would get `23505` anyway. A pre-check makes the defect rarer, not absent, which is worse.

**`enterSystem()` — and this one stays.** The caller is unauthenticated, so the platform is the only
honest actor. Attributing the action to the Party it creates is circular and, decisively,
*unavailable on the refusal path* where nothing was created; an actor that differs between success
and failure is worse than a uniform honest one. What carries the information is the audit record's
**target**, which is the attempted login identifier on both paths — the one place `PHASE_1_PLAN.md`
§10 permits an attempted identifier to appear. "Revisit every `enterSystem()`" reads as "remove every
`enterSystem()`", and `SECURITY_ARCHITECTURE.md` now says why that is wrong here.

**`app` orchestrates and owns nothing.** Registration spans two bounded contexts and belongs wholly
to neither; either module hosting it would have to depend on the other, which the isolation tests
forbid. So `app` contributes **two calls and a transaction**, and each module writes its own rows,
its own events and its own audit record. `MODULE_ARCHITECTURE.md` §Transaction boundary listed the
permitted cross-module transactions and **was stale** — it named only transfer-plus-posting and
resolution-plus-adjustment, neither of which exists yet; registration is the first of the three to be
real.

**`EventPayload` is a builder with a charset, not an object mapper, and it earned that on its first
run.** `INV-AUD-02` keeps personal data out of event payloads, and a general mapper would serialise
`put("displayName", name)` happily. It rejects any value that is not an identifier or an enumerated
name — and it immediately caught a real mistake, because `EntityId.toString()` renders `PartyId(uuid)`
rather than a bare UUID. Its limit is written down: an event needing richer structure needs the
wire-format decision taken, not worked around here.

**Causation at a flow root had no answer and now has one.** `Correlation` leaves `causationId` null
at a root, deliberately, so a root is distinguishable from a cycle; `EventEnvelope` requires it
non-null. The request is the cause — a value that looks self-referential and is not, because the
correlation identifier is on the idempotency record and on the audit record of the same transaction,
so the chain terminates at something real rather than at nothing.

**Two defects in the published contract, both found by generating it rather than reasoning about
it.** springdoc published **`"200": "OK"`** for an endpoint that has never returned 200, because a
`ResponseEntity` gives it no status to read — a generated client would have treated the real response
as unexpected. Fixed with `@ResponseStatus(CREATED)`, which is the only form that reaches the
document. And it tagged the operation **`registration-controller`**, publishing an internal class
name that an ordinary rename would turn into a contract diff; stripped, for the same reason `servers`
already was.

**A third defect was in the contract harness itself.** `OpenApiDocument` *replaced* the whole
`components` node, which was correct while `paths` was empty and silently wrong the moment a handler
declared a request body: the published document referenced
`#/components/schemas/RegistrationRequest`, which had just been discarded. Caught by
`everyReferenceResolves` — a guard the `P0-TSK-026` review added against exactly this class of
defect, working two tasks later.

**The `BREAKING` labels on the contract diff were reviewed and accepted.** `/paths` going from `{}`
to populated, a new schema's `required` list, and `requestBody: required` are all additions of
structure that did not exist; no client can be broken by an endpoint that was never there.
`PHASE_1_PLAN.md` §7 says additive endpoints are compatible, and the classifier erring in the safe
direction is the design (`P0-TSK-026`: a false BREAKING is visible and fixable, a false COMPATIBLE
fails at the customer).

**Seven mutations. One survived, and it found a real gap in a security test.**
`aReplayIsNotAnnounced` compared response header **names**, so an injected `Idempotent-Replay:
false`/`true` walked straight through — the header name is identical on both, and the value is the
whole disclosure. It now compares names *and* values, excluding only the correlation identifiers and
`Date`, which differ per request by design. All seven are caught now.

**One defect in my own test, found by the full tier rather than in isolation.** The
referential-integrity check asked whether *any* orphaned identity existed anywhere, and
`PartyAndIdentitySchemaDatabaseTest` creates orphans **on purpose**, to prove ADR-0029's missing
foreign key really is missing. Both facts are true and about different things: the schema permits an
orphan, and the registration transaction does not produce one. Scoped to the registration under test.

**One guard was generalised rather than extended.** `FinappApplicationTest` listed the three modules
allowed to contribute beans, and `party` and `identity` now legitimately do. The allowed set is
**derived from the classpath** instead — the stale-list defect this repository has met in CI's task
list, in a coverage guard and in a privilege check, closed the way it has been closed each time.

**The completion gate found two more, both by probing rather than reading.**

**A NUL byte in `displayName` produced `500 api.InternalError`** — a caller's mistake reported as a
platform failure, which `ERROR_CONTRACT.md` §3 forbids and which the `P0-TSK-024` review already
fixed once for a different input. PostgreSQL cannot store U+0000 in a `text` column at all, so the
driver rejected it three layers below the boundary. The same probe showed CR, LF, tab and a
bidirectional override being accepted **into a `RESTRICTED-PII` column** — a forged log line waiting
for the first component that ever prints a name, which is the weak point `DATA_CLASSIFICATION.md` §5
names in this exact scheme. Closed in three places: `PartyName` (five Unicode categories), the
request boundary (so a caller gets `422` naming the field), and `V003` as a `CHECK`, because
`DEFINITION_OF_DONE.md` §1.3 says an invariant a database constraint can carry is enforced there and
the application is not the only thing that will ever write that table. **The constraint is
deliberately narrower than the domain rule and says so**: a POSIX class expresses the C0/C1 ranges
exactly, and one written to *look* like parity while silently missing three categories would be
worse, because the next reader would trust it. `PartyName` still refuses a charset restriction, and
that is not a contradiction — what is excluded is in nobody's name, which is the same test that
rejects an allow-list of scripts.

**The `409 api.IdempotencyInProgress` branch was never exercised**, and the ten-way race hid it:
printing the status distribution showed **all ten racers got 201**, because the winner commits in
milliseconds and the losers replay. An untested error path on an `INV-LIFE-03` contract — *an
unknown outcome is reported as unknown, never assumed failed*. Now driven deterministically by
writing the row a crashed or still-running instance leaves behind: a committed `IN_PROGRESS` claim
with a live lease and a **matching fingerprint**, since a different one would produce
`api.Conflict` and the test would pass for the wrong reason.

**And the gate nearly repeated a trap this repository has already recorded.** The first attempt to
demonstrate the new constraint dropped it from the **compose** database — which the test harness
never uses, since `P0-TSK-035` gives each test JVM its own container. That is the false pass the
`P1-TSK-003` review found. Mutating the migration instead is what actually proves it.

**Eleven mutations, all caught.**

686 hermetic tests, 215 database tests.

### Previously

**`P1-TSK-005` — Party, Customer and Identity aggregates** — `COMPLETE` (2026-09-05). The phase's
highest-risk task: `DELIVERY_PLAN.md` §17 names collapsing the three as Phase 1's top risk.

| Acceptance criterion | Evidence |
|---|---|
| The three are separately persisted with distinct lifecycles, proven by a test that fails if any two are merged | `ThreeAggregatesAreSeparateTest`; a status added to `Party` fails it |
| Invalid transitions rejected **by the aggregate** (`INV-LIFE-02`) | `CustomerLifecycleTest`, `IdentityLifecycleTest` — every state pair enumerated from the machine, not listed by hand |
| A closed Customer cannot be reopened (`INV-LIFE-04`) | Asserted separately from the sweep; `CLOSED` made non-terminal fails four tests |
| Every column classified | Fifteen new rows in `DATA_CLASSIFICATION.md` §4; the guard is green |

**Three aggregates, two modules, three tables, two schemas.** The separation test is written as the
four shapes a merged model **cannot represent** rather than as an abstract claim: a person who is
not a customer (a beneficial owner), a customer who is not a person (an organisation), one Party
holding a retired login and its replacement, and lifecycles that move independently.

**Two invariants are enforced only by the database, because no aggregate can enforce them.** At most
one *live* relationship per party, and a login identifier used once ever, are rules **across**
aggregates of the same type — an aggregate sees only itself, so only the database arbitrates
between two concurrent transactions, which ADR-0014 says is the normal case rather than the
exception.

**The two uniqueness rules deliberately point opposite ways**, and that asymmetry is the sharpest
decision here. A closed relationship frees the party for a new one — a partial index, because
re-establishing a relationship is legitimate. A closed login **never** frees its identifier — a
total index, because reissuing it would let a new person authenticate with a name that appears in
someone else's audit history, making every record naming it ambiguous about which person it meant.

**`identity.identity.party_id` carries no `REFERENCES` clause**, and the cost is stated rather than
hidden: the database will accept an identity for a party that does not exist. What prevents it is
the registration transaction writing both in one commit (`P1-TSK-006`), which is a property a test
can assert — not the schema. An FK there would be coupling neither Gradle nor ArchUnit can see, and
would turn ADR-0001's stated escape into a data migration.

**`Party` has no lifecycle**, which reads as an omission and is the design. Existence has no states,
and every state people reach for — inactive, closed, archived — is a statement about a
*relationship* or a *login*, each of which has its own table. A status on `Party` would mean one
fact recorded in two places, free to disagree.

**`LoginIdentifier` is deliberately not an email address.** An identifier that is also a contact
channel cannot be changed without changing how someone logs in, nor verified without blocking login.
The charset excludes `@` specifically, so the confusion cannot arrive silently through the first
person who types an address.

**One deliberate non-change, recorded.** `IllegalCustomerTransitionException` carries its states but
not the identifier, because an exception is serializable and `EntityId` is not — and making it so
would oblige every existing identifier type to declare a `serialVersionUID`, a change to proven
Phase 0 code this task has no business making (`EXECUTION_PROTOCOL.md` rule 4). It is the third time
this project has met that requirement, after `CurrencyCode` and `IdempotencyKey`.

**The completion gate found two gaps, both in what the work claimed rather than in what it did, and
both closed.**

- **`DOD-KERNEL` requires behaviour under concurrency proven by integration test, and there was
  none.** Worse than a missing test here: the whole argument for putting these two rules in the
  database is that *only the database can arbitrate between two concurrent transactions*, and that
  claim was asserted **sequentially** - tested in the one mode where it is not the interesting one.
  `PartyAndIdentityConcurrencyDatabaseTest` now races ten instances, each with its own connection
  (the `P0-TST-009` convention), and adds the crash half: a rolled-back attempt must not consume
  the uniqueness slot.
- **`INV-AUD-02` was claimed and asserted nowhere.** `PartyName` and `LoginIdentifier` mask
  themselves and `Party`/`Identity` omit them, and nothing tested any of it - while a record's
  generated `toString` prints every component, so the override was the only thing standing between
  a name and a log line. This is the accidental-safety shape `P0-TSK-030` found with Jackson:
  correct today, silent the day somebody changes the shape.

**The concurrency harness was wrong on its first version, and the symptom was that it passed.** It
held every racer at a barrier until all ten had attempted, so the winner's transaction would stay
open across the others' inserts - but the losers were blocked *inside* their insert, waiting for the
winner's lock, and could never reach the barrier. It expired every time, and the suite passed in 30
seconds per race by timing out. The overlap needs no arranging: releasing the racers together and
letting the database block them **is** the contention. 12 seconds now.

Seven mutations, all caught.

### Previously

**`P1-TSK-004` — connection-pool sizing for N instances** — `COMPLETE` (2026-09-04). Closes
recorded debt and transition risk **R6**.

| Acceptance criterion | Evidence |
|---|---|
| The relationship is written down and checked rather than assumed | `DISTRIBUTED_EXECUTION.md` §4a; `ConnectionPoolSizingGuard` at startup; `ConnectionPoolSizingIsConfiguredTest` in the build |

**The rule:** `instances × maximum-pool-size ≤ server max_connections − reserved`. Shipped as
10 × 8 = 80 against 100 − 12 = 88.

**The defaults fail it, which is why this is a guard and not a note.** Hikari's default pool is 10
and PostgreSQL's `max_connections` is 100, so ten instances exhaust the server before a single
connection does any work — and ADR-0014 says N is never 1. The instances that lose the race fail
readiness with *connection is not available*, which reads as the pool being too small or the
database being slow. It is neither.

**The obvious repair is the wrong one, and that is the finding.** Dividing `max_connections` by the
instance count treats the limit as a budget to spend; it is a ceiling not to hit. Every connection
is a backend process, and PostgreSQL throughput stops improving once the cores are busy — past that,
extra connections queue **inside** the database, where the queueing is invisible to the application
and appears as latency on every query rather than as a pool timeout on one. So the pool is sized
small for throughput and "does the fleet fit" is a separate question asked afterwards.

**Checked in two places, because they are two claims.** The guard proves the rule at startup; the
test proves the *shipped numbers* satisfy it in the build. A guard alone would leave a violating
configuration to be found by a rolling restart, one instance at a time.

**Verified against a running instance**, which `DOD-OBS` requires, in all three directions: the
shipped configuration starts; `FINAPP_DB_INSTANCES=20` is refused with the arithmetic and the fix in
the message; and raising `max_connections` to 200 is accepted — so the guard never forces the pool
to be the thing that gives way.

**The gate found one inaccuracy, in what the test claimed about itself.** The guard is a bean, so a
violating configuration fails the test's *context* before any assertion in it is reached — which
means the "shipped numbers fit" assertion can only ever be evaluated in the case where the guard
already passed. The build does fail, and the guard is what fails it; the test's unique contribution
is the other two assertions, that the pool is fixed-size and that the guard is wired at all. The
javadoc said otherwise, and a later reader who deleted the guard believing the test covered it would
have removed the only thing that does.

**Two limits stated rather than implied.** The guard cannot verify `max_connections` against the
live server and does not try — it runs before the pool is used, and one that queried the database
would fail for a database that is merely down; it is a *declaration*, and a wrong declaration is a
wrong answer. And the arithmetic assumes each instance holds its **full** pool, which is why
`minimum-idle` equals `maximum-pool-size` and why a test asserts that rather than trusting it.

### Previously

**`P1-TSK-003` — `party` and `identity` module skeletons** — `COMPLETE` (2026-09-04). The first
modules other than `platform` to own a schema.

| Acceptance criterion | Evidence |
|---|---|
| `./gradlew build` green with both modules | 619 hermetic, 173 database |
| `ProductionModules` coverage includes them | A `double` planted in `PartyAuditAction` fails **two** floating-point rules in `:app:test` — proven, not assumed |

**Three schemas now exist**, all owned by `finapp_migrator` and never a superuser, each with its
own Flyway history, each `REVOKE ALL ... FROM PUBLIC` with `finapp_app` granted `USAGE` and nothing
else — checked against a live database rather than asserted. Migrations apply to an empty database,
validate, and re-apply idempotently, which is what CI does.

**No cross-module dependency, enforced structurally.** `PartyModuleIsolationTest` and
`IdentityModuleIsolationTest` assert neither module sees the other nor `app`, with a non-vacuity
half asserting each *does* see `platform` and `sharedkernel`. That is ADR-0029's boundary at the
classpath: ArchUnit's `entitiesAreNotReferencedAcrossModules` catches the reference, and this
catches the dependency that would make one possible.

**The tests exist because a guard demanded them.** `TestTaxonomyTest` failed with *"a module
contributing no test classes means the sweep did not reach it"* — a module added without tests is
a module the sweep silently skips, and the guard would not let that pass.

**CI's `:platform:flywayMigrate` was a list of one**, and two more schema-owning modules made it
stale. Now unqualified, so Gradle runs the task in every project that has it and a fourth module is
covered without anyone remembering — the `:platform:databaseTest` shape the `P0-TSK-027` review
found.

**Three auditable actions catalogued** — one in `party`, two in `identity`, reconciled in both
directions by the existing registry test. Deliberately few: a registry may list an action before
its code exists, but not before its *design* does, so authentication, session revocation and
credential change are left to the tasks that build them.

**One defect, found by applying the migration rather than reading it**: an unescaped apostrophe in
a schema `COMMENT` (`the platform's`), which PostgreSQL rejected at SQLState 42601.

**Two new lockfiles, and four stale counts corrected.** Locking is per project (ADR-0025), so two
new projects mean two new `gradle.lockfile`s, generated together with the verification metadata in
one invocation as `README.md` §7a requires. Four documents said "three projects" or "six
lockfiles"; there are now eight files. They are restated as **the rule** - one per project, plus
one per settings buildscript - which cannot go stale the next time a module is added. The counts
had been correct when written, which is exactly how this class of defect arrives.

**The completion gate found two more instances of the same defect class**, both created by adding
the schemas, and both fixed here because the next task walks straight into them:

- `ColumnClassificationTest` queried `table_schema = 'platform'`, so ADR-0022's guarantee — *a
  migration adding an unclassified column fails the build* — had silently become true for **one
  schema in three**, immediately before the task that creates the platform's first bulk
  `RESTRICTED-PII` columns in the other two.
- `DatabaseUnderTest` applied `platform`'s migrations **only**, so every database test ran against
  a database in which two thirds of the schemas did not exist.

Both now derive their set from the system rather than naming it. With CI's
`:platform:flywayMigrate`, that is **three** hardcoded names in one task, each correct when
written and stale the moment there were two — which is why the fix in every case is derivation, not
a longer list.

**Proven, not assumed**: an unclassified column added to `party` by migration now fails the
classification guard. It passed silently before, twice — the first probe was planted in the compose
database, which the harness does not use, and reported a false pass. A cross-module dependency added
to `party` fails its isolation test.

**And one pre-existing defect found by the acceptance probe** — recorded as `P1-TSK-025`, not fixed
here. See Known Architectural Debt.

### Previously

**`P1-TSK-002` — constrain the correlation identifier** — `COMPLETE` (2026-09-04). Closes the
widest-reaching disclosure channel in the platform, recorded as debt since `P0-TSK-033` and named
risk **R1** by the transition.

| Acceptance criterion | Evidence |
|---|---|
| No caller-controlled value reaches an unbounded-retention sink | `CallerCorrelationIsNotPropagatedTest`, asserted at the source every sink reads from |
| The debt row is closed | §Known Architectural Debt |

**Decision (ADR-0034): the platform mints the correlation identifier on every request and never
adopts an inbound one.** A well-formed caller value becomes a *client reference* — echoed in
`X-Client-Correlation-Id`, carried nowhere else.

**The finding is that narrowing the charset does not work**, and it is the option the task offered
first. A date of birth, a phone number and an account number are alphanumeric, so any charset still
able to carry a UUID or a W3C trace value carries them too. Of the four values `P0-TSK-033` probed,
narrowing to `[A-Za-z0-9_-]` would have stopped `jane.doe@example.com` and `acct:GB29NWBK…` and
**left `customer-1990-05-14` and `447700900123`** — a fix that closes the debt row and leaves half
the risk. A lexical control cannot express the property; the control had to be structural.

**The test asserts at the source rather than sink by sink.** All four durable columns, the MDC and
the span attribute read from one `CorrelationContext`, so what that context holds during a request
is the property — and it covers sinks that do not exist yet. The span is checked separately because
it is stamped by a span processor rather than by anything reading the context on that thread. The
four probed values are the test data on purpose: a synthetic `client-flow-77` would prove the
mechanism and not the risk.

**The client keeps its join.** It logs the identifier we return, and `X-Client-Correlation-Id` lets
a gateway match a response to a request it no longer holds a connection for. What is deliberately
lost is searching *our* logs by a caller-chosen string — precisely the property that made the
disclosure possible.

**A recorded flake was closed on the way past.** `doesNotContain("bad")` fails about one run in 137,
because a UUIDv7 hex string contains `bad` roughly 0.7% of the time; the intent was right and the
method was wrong. Replaced by pinning the *shape* — a platform-minted UUIDv7 — which nothing derived
from caller input can satisfy.

**A flake I introduced was caught by CI on the first run and not locally** - the fifth finding of this class, and the clearest. `correlationAttributes()` was sampled immediately, but the server span ends *after* the response is written, so the client can hold a complete response while the span it produced has not reached the exporter. It raced in the worst direction: `isNotEmpty` on an empty list fails, but the `noneMatch` beside it **passes** over an empty list - so on a loaded machine the guard would have stopped checking the span sink while still reporting green. `RecordedSpans`' own javadoc had predicted it. Fixed by waiting on the **condition** rather than for a duration, with the bound generous because exceeding it is a failure and never a pass; five consecutive runs green, and the mutations re-proven to still fail with the wait in place, since a wait must not be what makes a test pass. `TracingTest` has the same latent race and has not yet been bitten - recorded, not fixed here. Four mutations, all caught. The MDC leak was caught **twice**, the second time by
`onlyCorrelationContextWritesTheMdc` from `P0-TST-008` — defence in depth working without being
asked to.

### Previously

**`P1-TSK-001` — ADR: data-access mechanism** — `COMPLETE` (2026-09-04). **Phase 1's first task,
and it closes unresolved question 12**, open since `P0-TSK-011` and brought forward from Phase 3 by
the transition.

| Acceptance criterion | Evidence |
|---|---|
| ADR-0033 exists in `Proposed` | [ADR-0033](../adr/ADR-0033-explicit-sql-and-no-object-relational-mapper.md) |
| Explains the interaction with append-only tables and the application role's privileges | The Context and Option A sections; it is the decisive argument rather than a consideration |
| Unresolved question 12 closed in `CURRENT_STATE.md` | Moved to §Unresolved Architectural Questions → *Resolved since* |

**Decision: explicit SQL through `JdbcClient`. No ORM, no persistence context, no generated
repositories.** No new dependency — `spring-jdbc` has been on the runtime classpath since
`P0-TSK-027` added a `DataSource` for the readiness check.

**The decisive argument is the privilege model, not taste.** `INV-HIST-03`, `INV-HIST-01` and
`INV-LED-03` are enforced at `DB-PRIVILEGE` by `finapp_app` holding **no `UPDATE` and no
`DELETE`** — and that is worth exactly as much as the guarantee that nothing emits a statement
nobody wrote. Hibernate's dirty checking emits `UPDATE` on its own initiative, at a flush point
decided by code far from the write, so whether the forbidden statement is issued depends on whether
an entity happened to be dirty. That is the shape of defect that passes every test and fails in
production. `DB-PRIVILEGE` ranks second to `DB-CONSTRAINT` in the catalogue, but it is the
strongest mechanism available for *forbidding an operation*: a `CHECK` constraint cannot express
"this role may not `UPDATE`".

**Spring Data JDBC came far closer and was rejected on two concrete behaviours**, not general
unease: `save()` deletes and re-inserts child collections, and against superseded credentials those
children *are* the history; and application-minted UUIDv7 identifiers arrive non-null, so it
defaults to `UPDATE` on a new aggregate — the `Persistable.isNew()` trap, sitting precisely on the
registration path. Both are workaroundable; needing a workaround on the **first** aggregate is the
signal.

**The seam it closes was explicit in the code.** Four kernel ports are generic over the unit of
work, and three said *"a JDBC `Connection` today, whatever the Phase 3 decision produces later"*.
`T` is now `Connection` permanently. The type parameter **stays** — removing it is a refactor of
proven Phase 0 code with no correctness benefit (`EXECUTION_PROTOCOL.md` rule 4) — and all five
javadocs were corrected, because they described a decision that had moved phases and then been
taken.

**Enforced rather than recorded**, which `DOD-ARCH` requires. `NoObjectRelationalMapperTest` fails
the build if a JPA, Hibernate or Spring Data artefact reaches the application's **runtime**
classpath, catching one that arrives transitively behind a starter — the way it would actually
arrive. Writing it **found a real gap**: `MODULE_ARCHITECTURE.md` §6 had forbidden JPA in
`sharedkernel` only, and `sharedkernel` is not where anyone would add an ORM.

**A false positive was caught before commit, and it is the finding worth keeping.** The first
forbidden list matched `hibernate-` and **failed on the real classpath**: `hibernate-validator` is
Bean Validation, arrives with `spring-boot-starter-validation` from `P0-TSK-025`, and has nothing
to do with persistence. A rule that forbids a correct dependency is a rule somebody turns off —
ADR-0019's own reasoning for keeping `key` out of the `secretsAreWrapped` vocabulary. The list now
names the ORM's own artefacts, and a test keeps the carve-out honest by asserting it is still
needed, the way `P0-TSK-041` proved its two exemptions load-bearing.

**No new shared state.** `DISTRIBUTED_EXECUTION.md` §3 gains no row, and that absence is the
argument: every Phase 0 concurrency protocol — claim-by-insert, bounded `lock_timeout`, conditional
`UPDATE … WHERE`, transaction-scoped advisory locks, savepoints, writing on the caller's connection
— stays expressible unchanged. Four mutations, all caught.

### Just completed

**Phase 0 → Phase 1 transition** — **CONDUCTED** (2026-09-04).
[`reviews/PHASE_0_TO_1_TRANSITION.md`](reviews/PHASE_0_TO_1_TRANSITION.md)

| Part | Outcome |
|---|---|
| Phase 0 gate audit, 13 areas | 12 `PASS`, 1 `PARTIAL` (invariants — see the finding below) |
| 12 universal exit criteria | 11 `PASS`, **criterion 7 `FAIL`** — never run in CI |
| Phase 0 verdict | **remains `IN_PROGRESS`**; remediation task `P0-TSK-042` created |
| Phase 1 established | Objective, 3 contexts, 6 aggregates, 9 tables, 14 endpoints, 7 milestones |
| Phase 1 backlog | 25 items at task granularity, each with acceptance criteria |
| Phase 1 decisions | ADR-0029…0032, all `Proposed` |
| Phase 1 status | **`PLANNED`**, entry gate satisfied except criterion 1 |

**The transition's own finding, and it is the reason a transition is a separate act rather than a
formality.** Phase 1's seven identity properties existed **only as exit-criteria prose** in
`PHASE_GATES.md` — no stable ID to cite, no enforcement mechanism ranked by strength, no named
verification method, and no row in `MUTATION_TESTING.md`. Every other property on this platform
gets all four. The asymmetry was backwards, because Phase 1 is the phase whose *product* is
security, and it would have meant writing credential-handling code against prose. Catalogued as
`INV-IDN-01`…`INV-IDN-07`, taking the platform to **71 invariants**.

**Phase 1 is `PLANNED` rather than `READY`, deliberately, and against the letter of the request.**
Eleven of twelve entry criteria are met; criterion 1 — *the previous phase is `COMPLETE`* — is not,
because Phase 0's criterion 7 fails. `PHASE_GATES.md` §1 forbids `READY` while a hard dependency is
not `COMPLETE`, and the criterion in question is the one that proves the gates execute at all.
Marking it `READY` would be the failure §1 names. It flips on the first green CI run with no
further planning work.

**Four decisions were taken rather than deferred into implementation**, because each is
irreversible once data exists: Party/Customer/Identity as three aggregates (ADR-0029); server-side
sessions with assurance as a *level* rather than an MFA boolean (ADR-0030); permission at the
boundary **and** ownership in the domain, always both (ADR-0031); and credentials storing the
derivation *with* the parameters that produced it (ADR-0032). A fifth — the data-access mechanism,
unresolved question 12 — is `P1-TSK-001` rather than an ADR written here, because it is a Phase 1
decision and writing it during the transition would have been Phase 1 work under another name.

### Previously

**`P0-TSK-017` - `Idempotency-Key` header handling** - `COMPLETE` (2026-09-03).
**The Phase 0 backlog is now 62 of 62.**

| Acceptance criterion | Evidence |
|---|---|
| A declared endpoint rejects a request without the header | 422 `api.IdempotencyKeyRequired`, with the handler proven never entered |
| Key format validated | Blank, over-long and outside the charset all refused, at the bound the store enforces |
| Never logged as sensitive data | Not wrapped, not redacted, accepted keys pass through intact, rejected ones are never echoed |
| Recorded in audit | **Corrected** - no subject; transfers to Phase 4. See below |

**An interceptor, not a filter, and that is load-bearing twice.** A filter runs before the
dispatcher has chosen a handler, so it could not know whether *this* endpoint declares the
requirement without a second, drifting copy of the routing table. And a filter runs outside
`@ExceptionHandler`, so its rejection would be the container's default page rather than the error
contract - the problem `P0-TSK-025` had to work around by rendering the contract by hand inside its
filters. Rejection happens **before the handler is entered**, asserted by counting handler entries:
for a money-moving command, the half of the work done before a late rejection is the half that
matters.

**The requirement is declared, not defaulted.** `@RequiresIdempotencyKey` on a handler or its
controller. Requiring the header everywhere would force it onto reads, where it means nothing and
would train clients to send a value nobody uses; the annotation is also the greppable list of
endpoints claiming to move money.

**A real gap was found by following `DATA_CLASSIFICATION.md` §5**, which classifies this column as a
caller-supplied identifier. `IdempotencyKey` bounds length and blankness because those are the
table's `CHECK` constraints, and carries **no charset** - so a caller could put CR/LF into a value
the platform logs, stores durably and will put on an audit record. A newline in it is a forged log
line. Closed with the same default-deny charset the correlation identifier uses, unit-tested rather
than driven over HTTP because the JDK's own `HttpClient` refuses to *send* CR/LF - and a hostile
client writing raw bytes to a socket is not bound by that politeness.

**The audit clause was corrected rather than approximated.** The registry now exists, but
`AuditRecord` has no field for a key and **nothing in Phase 0 writes an audit record in an HTTP
flow** - the three registered platform actions are outbox operations and none is emitted. Adding a
column now would be a schema change nothing populates, and unlike actor attribution no history is
lost by waiting, which is the test ADR-0010 applies. Transferred to Phase 4.

### Previously

**`P0-DOC-012` - Phase 0 review record** - `COMPLETE` (2026-09-03).
**`P0-EPIC-12` closes with it.**

| Acceptance criterion | Evidence |
|---|---|
| Review record covering all eight review areas | [`reviews/PHASE_0_REVIEW.md`](reviews/PHASE_0_REVIEW.md) |
| ADRs moved to `Accepted` | ADR-0001…0028, all 28 |

**The review finds the exit gate does not pass, and that is what conducting one is for.** Ten of
the twelve universal criteria hold. Criterion 11 fails on three HIGH/CRITICAL Tomcat CVEs and
criterion 7 on a suite that has never run in CI. `PHASE_GATES.md` §4 prescribes the consequence -
the phase **remains `IN_PROGRESS`** - and §1 is explicit that moving backwards from review is
normal while *"shipping through a failed gate"* is the failure.

**Two of the eight areas could not be conducted as written, and say so rather than reporting a
pass.** Area 2 asks for one real posting walked end to end and Phase 0 creates none; what it can
verify - the kernel a posting will be built from - it does. Area 5 enumerates three registered
privileged actions and finds **none of them is emitted**, which is the most important sentence in
that section.

**The ADRs were accepted despite the open failures, and the reasoning is recorded.** Criterion 10
is a *precondition* of the gate rather than a reward for passing it: the gate requires the ADRs to
be accepted, so accepting them is work toward it. Holding ADR-0003 at `Proposed` because Tomcat has
a CVE would be theatre - the decisions were taken, implemented and tested, and none is contingent
on either failure.

**Two documentation drifts, found by hand-diffing what no guard covers.** The pinned-version table
in `SYSTEM_ARCHITECTURE.md` omitted Prometheus, Grafana and WireMock - the first two being
`compose.yaml` images that `verifyInfrastructureVersions` guards, so the table under-reported the
coverage of the check described in the same section. Both closed.

### Previously

**`P0-DOC-011` - Domain glossary** - `COMPLETE` (2026-09-03).

| Acceptance criterion | Evidence |
|---|---|
| Every term in `DOMAIN_MODEL.md` defined | All 55, each with an `Is:`, a `Not:` and an owning module |
| "Do not collapse" pairs explicitly contrasted | All eight groups, under headings repeating `CLAUDE.md`'s wording exactly |

**Comparing the two lists mechanically found they disagree.** Seven terms are forbidden from being
collapsed that the canonical list never names - `Authentication`, `Transaction`,
`Operational Account`, `Underwriting`, `Customer Payment`, `Merchant Settlement`, and `KYC`, which
is the *process* and distinct from the canonical `KYC Case`. A glossary covering only the canonical
list would have left undefined exactly the terms the rule is about. It is therefore the **union** of
both lists, and `DomainGlossaryTest` enforces that in both directions - so the glossary also cannot
become a second home for vocabulary its owning document should define.

**The `Not:` line is the deliverable, not decoration.** A definition alone does not stop a collapse:
two definitions can each be correct and still be applied to the same thing by two people. Naming the
concept a term is confused with is what makes a violation something a reviewer can point at.

**`external` is an owner, not a blank.** A PSP is a company we contract with; modelling one as our
own state is the first step towards a domain that belongs to a vendor (ADR-0008).

**Nothing in the glossary is implemented, and it says so** - checked rather than assumed: no
production class is named for any of the 62 terms. Phase 0 delivers the kernel and zero business
capability, so `DOD-DOC`'s ban on aspirational statements presented as current fact bites here more
than anywhere.

**A spelling was settled.** The canonical list had `Installment` once, against twenty uses of
`Instalment` elsewhere including the module register that assigns its ownership. Corrected.

Nine mutations, all caught - after the guard found two defects in itself: `^` without
`Pattern.MULTILINE` (the defect the `P0-TSK-033` review found in a register parser, reproduced
here) and a `### Term` inside a fenced code block being read as a definition.

### Previously

**`P0-TSK-038` - Mutation-style invariant verification convention** - `COMPLETE` (2026-09-03).
**`P0-EPIC-11` closes with it.**

| Acceptance criterion | Evidence |
|---|---|
| Convention documented | [`MUTATION_TESTING.md`](MUTATION_TESTING.md) |
| Applied to every `P0-TST-*` item | All nine, and all **17** Phase 0 invariants besides - enforced, not asserted |

**The task's criterion is the narrower of two obligations.** It asks for the nine `P0-TST-*` items;
`PHASE_GATES.md` criterion 3 asks for **every in-scope `INV-*`** to have a test that fails when the
invariant is broken. The register covers both, and `MutationDemonstrationTest` checks it on every
build - which converts criterion 3 from something a human verifies once at the gate into something
the build verifies continuously.

**A demonstration has two admissible forms, and the distinction is the whole point.** An *in-suite*
proof - a fixture that violates the rule, asserted to be rejected - runs on every build and cannot
rot. A *recorded* procedure proves the test had teeth **on the day it was written**; it is what a
mutation must be when it drops a constraint, widens a grant or edits production code, because a
build must not do those things to itself. The register labels each row, and the guard verifies that
an in-suite row names a **method that still exists** - so a claim of continuous proof cannot point
at something renamed away.

**The audit found a real gap, which is what an audit is for.** `INV-MON-05` (precision preserved in
persistence) had a test and **no recorded demonstration** - the identifier appeared nowhere in the
project's records. Closed by performing the demonstration rather than asserting it: re-deriving the
scale from the currency in `MoneyColumns.read` fails **exactly one** test, and notably **not** the
general round-trip test, which writes amounts whose scale already matches the currency's current
minor units so re-derivation gives the same answer. That is why the register names a **method**
rather than only a class.

**Two limits recorded rather than glossed.** `INV-AUD-01` is demonstrated only in half - the
registry proves a *recorded* action is catalogued and cannot detect a privileged action that writes
no record at all, which needs Phase 15's audit-completeness verification. And nothing checks that a
*recorded* procedure still reproduces; that residual risk is exactly why the form column exists.

Seven mutations, all caught, each by the intended assertion. Review added two more guards - the
register was checked in one direction only - and found one row whose observed result had been
inferred rather than recorded; nine in total.

### Previously

**`P0-TSK-037` - WireMock harness for provider adapters** - `COMPLETE` (2026-09-03).

| Acceptance criterion | Evidence |
|---|---|
| Reproduces every `CLAUDE.md` §Failure Engineering mode involving a provider | Seven modes, each driven through a real HTTP client over a real socket; and the claim is *enforced* rather than asserted - see below |

**A provider is unreliable in both directions, so the harness has two halves.** Outbound is the
provider's API, a real HTTP server we call: timeout, unavailable, 5xx, delayed, malformed body,
garbage, unknown state, the retry sequence, and the request that is **received** before the
response is lost. Inbound is the provider calling **us** - a duplicated webhook (`INV-IDEM-04`) and
a late settlement (`INV-SET-03`) are the provider acting on its own schedule, and no amount of
stubbing its API reproduces them. A simulator with only the first half cannot reach the modes that
cost the most.

**The single most useful thing the harness offers is `requestCount`.** It separates two failures
that are identical from the caller's side - a request that never arrived, and one that arrived and
was acted on before the answer was lost. That distinction is exactly why `INV-LIFE-03` requires an
explicit indeterminate state rather than a guess in either direction.

**The criterion is a checkable claim, so it is checked.** `ProviderFailureCoverageTest` holds three
links that must all hold at once: every bullet in `CLAUDE.md` §Failure Engineering is classified as
a provider concern or explicitly not one, with the reason and where it *is* covered; every provider
concern names a harness method that **exists**; and every such method is **actually called** by the
suite that proves the harness. The third link is the one that stops a mode being covered on paper.
Seven mutations, all caught - including a bullet added to `CLAUDE.md`, which also proved the new
build-input declaration works.

**Two defects, both found by the guard's own assertions rather than by review.**
- The section regex **read straight past** `## Failure Engineering` into `## Definition of Done`,
  returning "auditability" and "observability" as failure modes - because `DOTALL` makes `.` match
  newlines, so a single `- .*` swallows the rest of the file. Replaced by line-walking with an
  explicit stop at the next heading, which **cannot** over-read; that is better than a guard
  against over-reading, and the vacuity check now also asserts the set is bounded.
- A literal match reported that **ADR-0008 had stopped requiring "malformed response"**. It had
  not: the ADR wraps mid-phrase. Whitespace-normalised.

**WireMock is the standalone artefact, and that was measured.** It relocates Jetty and Jackson
under `wiremock/` - zero classes at `org/eclipse/jetty` - so the harness cannot change which
servlet container Spring Boot picks for every `@SpringBootTest` in `app`, and it resolves to
exactly **one** lockfile entry rather than a tree. Version **3.13.2**, the current stable: Maven
Central's `<latest>` *and* `<release>` markers both point at `4.0.0-beta.38`, so "the newest
version" and "the newest version you should use" are different answers here.

**`unit` tier, decided on measurement**: the whole provider suite costs 1.3 seconds including
server start. WireMock needs a loopback port and nothing else - no container, no Docker, no
external service.

**No new ADR**, deliberately: ADR-0008 already decided the harness exists and lists the modes, so
this is its recorded follow-up rather than a new decision. ADR-0008's follow-up section now says so
and points at [`TESTING.md`](TESTING.md) §5a.

### Previously

**`P0-TSK-036` - Test taxonomy and conventions** - `COMPLETE` (2026-09-03).

| Acceptance criterion | Evidence |
|---|---|
| Tiers runnable independently | `unitTest`, `architectureTest`, `sliceTest`, `databaseTest`, each registered once in the convention plugin. All four run and pass alone |
| Documented | [`TESTING.md`](TESTING.md), enforced against the build in both directions |
| CI runs all tiers | Asserted by test, and asserted **unqualified** - the `:platform:databaseTest` defect the `P0-TSK-027` review found |

**A tier is what a test needs in order to run, and nothing else.** That is the only axis on which
membership can be decided mechanically, and it is the one that matters for scheduling: a tier mixing
requirements produces a task costing what its heaviest member costs and failing wherever that
member's infrastructure is absent. Grouping by *intent* reads better in a document and cannot be
checked.

**Two of the five names the task asks for are deliberately not tiers.** `contract` is a *kind*, and
its members have different requirements - `OpenApiContractTest` needs a Spring context,
`ColumnClassificationTest` needs a database - so making it a tier would group two requirements under
one name. `integration` is replaced by `database`, which says what is integrated with; a Kafka
client gets its own tier rather than being folded into a word that would then mean two things.

**The tiers partition the hermetic suite exactly**: 418 + 54 + 68 = 540 = `test`. `build` still runs
all three hermetic tiers, so nothing left CI's coverage as a side effect of the split.
`./gradlew unitTest` is ~14 seconds against `build`'s minute.

**The split ships with its guard, because splitting one task into four multiplies the ways to make
the stale-list mistake.** `TestTaxonomyTest` holds the Gradle declaration, `TestTier`, every class's
tag, `TESTING.md` and CI to each other. Nine mutations, all caught - **after the first one
survived**.

**Four defects, every one found by the task's own guards rather than by review.**
- **The first mutation survived.** Removing `@Tag("database")` from a platform test left the guard
  green, because it reads sibling modules' compiled test classes from disk and nothing had told
  Gradle that - so it read a **stale class file**. Closed with `dependsOn` and a declared input,
  derived from the subprojects rather than listed. Same defect class as the seven document-input
  lines beside it, one module across.
- **`ModuleBoundaryRulesTest` was skipped entirely** - the oldest and most fundamental rule suite
  here, the one enforcing `app -> platform -> sharedkernel`. ArchUnit executes `@ArchTest`
  **fields** under a class-level `@AnalyzeClasses`, and it declares no `@Test` method at all.
- **`MoneyTest` was skipped**, because every test method lives in a `@Nested` class and the outer
  class - where the tag has to go, since JUnit inherits it downward - carries no marker.
- **A false positive narrowed the detection.** `NoDirectBrokerPublicationRulesTest`'s fixture
  declares `OutboxWriter<java.sql.Connection>` to model production's shape and opens nothing.
  Detection now keys on **acquisition** - `DriverManager`, a `DataSource`, a container, the harness
  - because a rule that pushes a hermetic ArchUnit suite into the database tier is a rule somebody
  turns off.

**Twelve Spring-context tests and nine ArchUnit suites turned out to be in the default tier**, which
was invisible before there was anything to be in the wrong tier of.

**Two further guards added by review**, both from asking what CI actually executes: `build` runs
`test` and **never the tier tasks**, and an empty tier task passes in a second having selected
nothing and written no result file - so `noTierIsEmpty` checks repository-wide. And an unrecognised
`@Tag` is *ignored* rather than rejected, so the vocabulary is now closed, with an empty non-tier
list.

**The recorded duplication is paid down.** Thirteen test classes opened connections through their own
private helper; all now use `DatabaseRoles`, and what they had been copying was a connection as the
**superuser**. All 173 database tests still pass, unchanged.

---

