# Phase 4 Plan — Internal Transfers

**Status:** `READY` — entry gate passed 2026-09-17
([`reviews/PHASE_3_TO_4_TRANSITION.md`](reviews/PHASE_3_TO_4_TRANSITION.md))
**Decisions:** ADR-0043 (the transfer and its posting commit together; no internal saga),
ADR-0044 (the transfer lifecycle: states are earned by their producers) — both `Proposed`,
accepted at the exit review
**Gate:** [`PHASE_GATES.md`](PHASE_GATES.md) §5 Phase 4, plus the financial supplement F1–F8,
which binds this phase exactly as it bound Phase 3

---

## 1. Objective

**The first complete, customer-visible money movement.** A verified customer moves funds
between two accounts the platform controls — their own, or a saved beneficiary's — with an
explicit lifecycle, idempotency enforced at the financial boundary, every command audited,
and a privileged reversal path that corrects without mutating. Entirely on the Phase 3
ledger: the transfer *requests* a posting; it never writes one (`INV-LED-04`).

Deliverables:

1. The `transfers` module, schema and privilege floor.
2. The `Transfer` aggregate with the ADR-0044 lifecycle, exhaustively enforced.
3. The execution command: one transaction from idempotency claim to outbox row (ADR-0043),
   with the availability decision derived **inside the source account's lock**.
4. The `Beneficiary` aggregate and its endpoints, with second-factor creation.
5. The transfer HTTP surface: initiate, status, list — the asynchronous-outcome contract
   shape even though execution is synchronous.
6. The reversal: a privileged, reasoned command posting a referencing reversal entry and
   moving the transfer to `REVERSED`, atomically.
7. The limit and risk **seams**: ports that are required parameters of the execution
   command, default-permit documented, contractually evaluated inside the lock (Phase 13
   implements them).
8. The phase's meters, dashboard row, register rows and exit review.

## 2. Why this phase is shaped by two decisions already taken

**ADR-0043**: both legs are internal and both modules share one database, so the transfer
state transition and the journal entry commit in **one local transaction** — the seam
`PostingService` was built with (`P3-TSK-006`: `post(unitOfWork, command)` joins the
caller's transaction). There is no saga, no compensating machinery and no durable
intermediate state; a failed transfer is a committed domain outcome, not an exception leak.

**ADR-0044**: the lifecycle is four states — `INITIATED → {COMPLETED, FAILED}`,
`COMPLETED → REVERSED` — and every conventional state it omits is omitted because nothing
here can produce it. `PROCESSING` belongs to Phase 5's payments, `CANCELLED` to scheduled
transfers, `VALIDATED`/`AUTHORIZED` to no durable fact at all.

The consequence worth stating up front: **most of Phase 4's correctness machinery already
exists and is proven.** The posting write set, the reversal command, the idempotency
executor, the account lock protocol, the availability derivation, the audit and outbox
discipline — all Phase 0/3 deliverables with their own register rows. Phase 4's genuinely
new work is one aggregate pair, one atomic command composition, and the discipline of not
re-implementing what it can call.

## 3. Bounded contexts

| Context (register #) | Module | Owns in this phase | May not touch |
|---|---|---|---|
| Transfers (8) | `transfers` (new) | Beneficiary, Transfer, transfer lifecycle history | Journal tables (requests postings through the ledger's command API); any other module's state |
| Ledger (7) | `ledger` | Unchanged — `PostingService` and `ReversalService` gain their first external-module callers | — |
| Accounts (5) | `accounts` | Unchanged — the product rows the transfer resolves | — |
| Party (1) | `party` | Unchanged — beneficiaries belong to a Party | — |

**Module edges** (the build graph, ADR-0042's discipline continued): `transfers → ledger`
(to command postings and reversals) and `transfers → accounts` **is refused** — the transfer
resolves the caller's products through a port `app` implements (the `CaseKindResolver` /
`AccountHolderVerification` shape), because `accounts` owning the product and `transfers`
owning the movement must not become one dependency ball. Every existing sibling forbids
`transfers` and `transfers` forbids every sibling except `ledger`; a planted
`ledger → transfers` edge is a Gradle cycle, demonstrated at the module task.

## 4. The transfer model

| Concept | Is | Is not |
|---|---|---|
| **Transfer** | Movement of funds between two accounts the platform controls, with a lifecycle | A Payment (its outcome is never a third party's — the glossary's distinguishing property); a posting (it *causes* one) |
| **Transfer instruction** | The validated command the execution runs — request DTO plus resolved accounts | A durable aggregate. A *scheduled* instruction would be one, and is out of scope |
| **Source account** | A `CustomerAccount` product of the caller's live Customer, resolving to its ledger account | An account named by an unverified request identifier — ownership is a predicate in the statement |
| **Destination account** | Another platform account: one of the caller's own, or a beneficiary's | An external account (Phase 5+) |
| **Beneficiary** | A saved destination belonging to a Party, resolving (in Phase 4) to an internal account | A Party, an Account, or a trust decision — a new one is a risk signal and its creation is the step-up point |
| **Transfer status** | ADR-0044's machine | A workflow log — the history table is that |
| **Transfer limit** | A **seam**: `TransferLimitCheck`, default permit, Phase 13's to implement | A table, a counter or any Phase 4 logic |
| **Transfer authorization** | Session + ownership of the source + (for beneficiary creation) the second factor | A permission — moving your own money is not privileged; **reversing** somebody's transfer is |
| **Transfer attempt** | Not modelled, deliberately | An internal transfer has no per-provider tries; attempts are the payment lifecycle's vocabulary (Phase 5) |
| **Idempotency record** | The platform executor's row, scope `transfer.execute`, fingerprint binding actor + source + destination + amount + currency + reference | Anything transfer-specific — `INV-IDEM-01` is the kernel's |
| **The ledger transaction** | One `POSTING` entry: debit the source wallet, credit the destination wallet, same currency and amount | A new entry type — the transfer identity travels in the entry's `idempotency_scope` and reference, not in new ledger vocabulary |

**Transfer vs payment vs posting vs settlement, in one line each**: a *transfer* is internal
movement whose outcome the platform alone decides; a *payment* is movement whose outcome a
third party decides (Phase 5); a *posting* is the accounting record either causes
(`INV-LED-04`: only the ledger writes it); *settlement* is value actually moving between
institutions (Phase 8) — an internal transfer settles by construction, which is §12's point.

## 5. The lifecycle (ADR-0044)

```
INITIATED ──execute──> COMPLETED ──reverse──> REVERSED   (terminal)
     └────execute────> FAILED                            (terminal)
```

- `INITIATED` exists in the aggregate and the history rows, never in a committed snapshot —
  the execution transaction carries the transfer from birth to outcome.
- `FAILED` carries an enumerated `failure_reason` (`INSUFFICIENT_FUNDS`,
  `DESTINATION_NOT_POSTABLE`, `SOURCE_NOT_POSTABLE`, `CURRENCY_MISMATCH`, …), `NOT NULL`
  exactly when failed (the `V010` decision-matches-status pattern), and **no posting**.
- `COMPLETED` is stable with exactly one outgoing edge; `REVERSED` and `FAILED` are
  terminal, enforced by the aggregate, the generated status `CHECK` and a transition
  trigger — three ranks, every writer.
- The history table (`transfer_event`) is append-only: every transition, its instant (the
  injected clock), and its actor.

## 6. Financial invariants Phase 4 must preserve

**The in-scope set is whatever the catalogue marks `Phase: 4` — read from
[`FINANCIAL_INVARIANTS.md`](../domain/FINANCIAL_INVARIANTS.md), never from this table**
(the standing lesson, paid for twice). At planning time that set is **five**:

| Invariant | What it demands here | Where it will be enforced |
|---|---|---|
| `INV-IDEM-01` (transfers element) | Same command, same key, one movement | The platform executor at the financial boundary; the register's existing rows gain a **transfers-context row**, because the kernel row proves the mechanism and not this caller |
| `INV-CON-02` | Two racing requests to move the same funds → exactly one movement, the other a domain outcome | The source account's `FOR UPDATE` + availability derived inside it; the ten-way drain race counted in the table |
| `INV-LIFE-01` | The explicit machine | ADR-0044; aggregate + schema |
| `INV-LIFE-02` | Invalid transitions rejected **by the aggregate** | The exhaustive cross-product sweep, plus the schema trigger for writers the domain never sees |
| `INV-LIFE-04` | Terminal states terminal | `FAILED` and `REVERSED` swept; `COMPLETED → REVERSED`'s single edge is ADR-0044's recorded reading |

Continuously binding invariants the phase exercises without owning: `INV-LED-01…05` (every
transfer posting balances, is immutable, is attributable), `INV-BAL-04/-05` (availability =
settled − holds, derived inside the lock, never the projection), `INV-REV-01/-02` (the
reversal references and is bounded), `INV-AUD-01/-03` (every command audited; the reversal's
authority negatively tested), `INV-EVT-01/-04`.

## 7. Multi-instance architecture

Assume ten instances throughout. The contended decisions and their arbiters:

| Contention | Arbiter |
|---|---|
| Ten transfers draining one source | The source account row's `SELECT … FOR UPDATE` (ADR-0039's enumerated set, third member) — lock, then derive settled − active holds in fresh statements, then post. The funds checked are the funds debited, in one transaction |
| Ten transfers **into** one destination | No destination lock and no decision — credits need no availability answer; the projection row's own lock serialises the increment in `PostingEffect`'s fixed order |
| A transfer racing the destination's close | Already arbitrated by Phase 3's lock-mode analysis: the posting's `FOR KEY SHARE` trigger read vs the closer's `FOR UPDATE`, both interleavings proven (`P3-TSK-014`) — the transfer inherits the refusal as `DESTINATION_NOT_POSTABLE` |
| Duplicate submission, two nodes | The idempotency claim's unique constraint; the loser replays the winner's stored outcome |
| Same key, different payload | `INV-IDEM-03`: fingerprint conflict, 409, nothing moved |
| Retry after timeout | The claim decides: an `IN_PROGRESS` holder is reported, a committed outcome replays |
| Reversal raced ten ways | The transfer row's conditional `COMPLETED → REVERSED` move (row count is the outcome) **plus** the ledger's own reversal bound under `V009`'s advisory trigger — two ranks |
| Beneficiary deactivation racing a transfer | **Accepted race, stated**: the transfer resolves a live beneficiary at decision time; deactivation is the removal of a saved convenience, not a security control (risk-driven blocking is Phase 13's seam). Bounded because the destination account's own postability is still judged at posting |
| Limit seam under contention | The seam's **contract** requires evaluation inside the source lock, so Phase 13's implementation inherits atomicity instead of building it — the decision that prevents `INV-CON-03`'s race from being designed in now and discovered then |
| Restart / partial failure | No in-memory state anywhere; ADR-0043's one transaction leaves nothing partial |
| Publication failure | The outbox; at-least-once with `eventId` dedupe (`INV-EVT-04`) |

**Transaction boundary** (ADR-0043): claim → transfer row → history → posting write set
(entry, lines, audit, outbox, projection) → transfer outcome — one connection, one commit.
**Consistency**: strong for everything financial; nothing in this phase is eventually
consistent except event delivery, which carries no financial authority (`INV-EVT-02`).

## 8. Data architecture

Schema `transfers`, owner `finapp_migrator`, default-deny floor (`V001`, the established
shape). Tables (`V002`, `V003`):

- `transfers.transfer` — id (UUIDv7), the caller's customer id (ownership predicate
  material), source/destination ledger-account references, the `MoneyColumns` shape,
  reference, status, `failure_reason` (`NOT NULL` ⇔ `FAILED`), the posted entry id
  (`NOT NULL` ⇔ `COMPLETED`/`REVERSED`, `UNIQUE`), the reversal entry id (`NOT NULL` ⇔
  `REVERSED`), initiated_by, timestamps from the injected clock. Status `CHECK` and the
  transition trigger generated from the enum; grants `SELECT, INSERT` +
  `UPDATE (status, reversal_entry_id, reversed_by, reversed_at)` column-narrowed.
- `transfers.transfer_event` — the append-only history: transfer id, seq, from/to status,
  actor, instant. `SELECT, INSERT` only.
- `transfers.beneficiary` — id, owning party id, display name, destination account
  reference, status (`ACTIVE`/`REMOVED`, terminal), created/removed instants. One live row
  per (party, destination) by partial unique index; grants column-narrowed to the removal
  columns.

Every column classified at its ceiling before the migration lands; a beneficiary display
name is `RESTRICTED-PII` (a person names people).

## 9. API architecture

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /v1/transfers` | session + `@RequiresIdempotencyKey` | Body: source account, destination (`accountId` **or** `beneficiaryId`, exactly one), amount as a decimal string, currency, reference. Answers `201` with the transfer view — status included, so the contract already has the asynchronous-outcome shape |
| `GET /v1/transfers/{id}` | session, ownership | Not-yours/unknown/malformed one 404 |
| `GET /v1/transfers` | session | The caller's own, newest first |
| `POST /v1/transfers/{id}/reversal` | session + `TRANSFER_REVERSE` | Reason required (bounded in the three reconciled places); 201 with the reversed view; a second reversal 409 |
| `POST /v1/beneficiaries` | session; **`MULTI_FACTOR` when a factor is enrolled** | The step-up point (see §11) |
| `GET /v1/beneficiaries` | session | The caller's live beneficiaries |
| `DELETE /v1/beneficiaries/{id}` | session, ownership | Removal converges; the row survives `REMOVED` (evidence) |

Insufficient funds is **not an HTTP error**: the `POST` answers `201` with status `FAILED`
and the reason — the command was accepted and its domain outcome recorded. Malformed input,
unknown accounts and refused ownership remain the caller's 4xx with nothing written.

## 10. Event architecture

`transfers.TransferCompleted`, `transfers.TransferFailed`, `transfers.TransferReversed` —
the terminal facts, each committed in the transaction that produced it, payload identifiers
and enumerated names only (`INV-AUD-02`). **`TransferInitiated` is deliberately not
published** (ADR-0044): under ADR-0043 it would commit beside its own outcome, and an event
that always accompanies its successor is one fact named twice. The delivery plan's and
module register's four-event lists are corrected with provenance by the transition. No
Phase 4 consumer exists; the events are the phase's outbound contract, at-least-once, dedupe
by `eventId`.

## 11. Security and audit

- **Initiation is not privileged**: a customer moving their own money holds no permission
  (the `P3-TSK-007` reasoning); the controls are the session, the ownership chain
  (Session → Identity → live Customer → `customer_id = ?` in the statement — a closed
  customer's products are unreachable by construction), and the seams.
- **Beneficiary creation is the step-up point**: `MULTI_FACTOR` required **when a factor is
  enrolled** — the conditional-assurance domain check (`P1-TSK-033`'s pattern; a static
  annotation would lock out password-only customers). *This deliberately corrects the
  delivery plan's "step-up for high-value or new-beneficiary transfers"*: a value threshold
  is a per-currency versioned policy artefact with nothing to calibrate it (the
  `P3-TSK-021` threshold argument, verbatim), so the structural trigger ships and the value
  trigger is a recorded seam on the risk port.
- **Reversal is privileged**: `TRANSFER_REVERSE`, granted to `LEDGER_OPERATOR` (one
  money-operating population — a new role is a new trust decision nothing here takes),
  reason required, negatively tested (`INV-AUD-03`). Four-eyes is **not** required:
  `INV-AUD-04` names manual adjustments, and a reversal is bounded by the original — it can
  return money only whence it came; the proposal-row seam exists if a later phase decides
  otherwise.
- **Audit actions** (deliberately few, the established licence): `transfers.TransferExecuted`
  (no reason — the transfer row carries the why; emitted for `COMPLETED` **and** `FAILED`,
  because a committed refusal is an act), `transfers.TransferReversed` (**reason
  required**), `transfers.BeneficiaryAdded`, `transfers.BeneficiaryRemoved` (a person's own
  acts, no reason). All named here arrive with their tasks and join `NOT_YET_EMITTED` until
  emitted.

## 12. Reconciliation

Internal transfers are **self-reconciling** — both legs commit in one entry, so there is
nothing external to disagree with — but traceability is the obligation that stays: economic
event → transfer row → journal entry → lines → balances, every link a stored identifier.
The entry's `idempotency_scope` is `transfer.execute:<key-hash>`-shaped via the executor,
the transfer row stores the entry id (`UNIQUE` — one movement, one entry), the entry's
reference carries the transfer id, and the reversal entry references both the original entry
(`reverses_entry_id`) and the transfer (`reversal_entry_id` on the row). An investigator
walks the chain in either direction without a timestamp join. Statements already disclose
the lines (Phase 3's derivation reads all entry types).

## 13. Testing strategy

The tiers as established. The load-bearing suites:

- **Hermetic**: the lifecycle cross-product sweep (from the machine, not a list); the
  fingerprint composition; the seams' default behaviour.
- **Database**: the ten-way drain (counted in the table: exactly the affordable transfers
  succeed, the rest `FAILED(INSUFFICIENT_FUNDS)`, source never negative, **total value
  conserved** — the sum over both accounts unchanged); the injected failure at the last
  write leaving *nothing* (ADR-0043's demonstration); duplicate submission from two
  connections; retry-replays-outcome; reversal raced ten ways to one reversal entry;
  reversal-of-reversed refused at both ranks; the availability decision proven **inside**
  the lock (the `P3-TST-002` moved-outside-the-lock mutation shape, re-aimed);
  close-racing-transfer both interleavings inherited-and-cited rather than re-proven.
- **HTTP**: end to end — a verified customer with two accounts moves money, both balances
  move, both statements show the lines, a stranger's ids are one 404, no body shape a 500.
- **Register**: every `Phase: 4` catalogue invariant a §2 row with its demonstration
  performed (`P4-TST-002`), before the review needs it.

## 14. Failure scenarios

| # | Scenario | Required behaviour |
|---|---|---|
| 1 | Client timeout, then retry | The claim replays the stored outcome; one movement (`INV-IDEM-01`) |
| 2 | Crash mid-execution | One transaction: nothing exists — no row, no claim, no posting; the retry executes fresh |
| 3 | "Posting succeeds but transfer state fails" | **Unrepresentable** (ADR-0043) — asserted by the injected-failure probe, not handled by code |
| 4 | Double submission from two nodes | One claim wins; the loser replays (`INV-CON-02` via `INV-IDEM-01`) |
| 5 | Insufficient funds | `FAILED(INSUFFICIENT_FUNDS)` committed, nothing posted, event published, retry replays the refusal |
| 6 | Source and destination the same account | Refused at validation — a self-transfer moves nothing and would mint a balanced no-op entry |
| 7 | Destination closed mid-flight | The posting's trigger read blocks on the closer's lock and refuses on resume (`P3-TSK-014`'s proven interleaving) → `FAILED(DESTINATION_NOT_POSTABLE)` |
| 8 | Currency mismatch (either leg) | Refused at validation; the composite FK (`V005`) backs it for every writer |
| 9 | Same key, different payload | 409 conflict, nothing moved (`INV-IDEM-03`) |
| 10 | Reversal raced / repeated | One reversal entry, one state move; the second caller a 409 with the first untouched |
| 11 | Beneficiary removed racing a transfer | The accepted race (§7), bounded by destination postability |
| 12 | Broker down at commit | The outbox holds the fact durably; the relay publishes when it can (`INV-EVT-01`) |

## 15. Observability

| Meter | Type | Notes |
|---|---|---|
| `finapp.transfers.transfer` | counter by `outcome` (`completed`, `failed`, `reversed`, `replayed`, `refused`) | Counted at the command seam, post-commit, acting call only — the `P3-TSK-020` discipline |
| `finapp.transfers.transfer.latency` | timer | The injected clock, never `nanoTime()` |
| `finapp.transfers.beneficiary` | counter by `outcome` (`added`, `removed`) | Post-commit, acting call only |
| `finapp.transfers.conflict` | counter | `INV-IDEM-03` fingerprint conflicts — the delivery plan's "idempotency-key collision rate", and a security signal (a stranger replaying logged keys) |

All eager from a fresh instance, absent-not-zero where a gauge appears, dashboard row
resolving against a live scrape. **Two delivery-plan §10 items corrected with provenance**:
*value*-by-state meters are refused — an aggregate money figure in a telemetry store is a
financial number outside the ledger's authority (`INV-EVT-02`'s reasoning; counts and
latencies carry the operational signal) — and the *stuck-transfer detector* has no subject
under ADR-0043 (no durable intermediate state exists to be stuck; it arrives with the first
asynchronous execution path).

## 16. Milestones

| Milestone | Contains | Acceptance |
|---|---|---|
| **M4.1 — Foundations** | `P4-TSK-001`, `P4-TSK-002` | The module and privilege floor exist; the ADR governance registers are build-reconciled |
| **M4.2 — The movement exists** | `P4-TSK-003` … `P4-TSK-005` | Ten instances draining one account: exactly the affordable transfers succeed, total value conserved, counted in the table |
| **M4.3 — Beneficiaries** | `P4-TSK-006`, `P4-TSK-007` | A saved destination is created under the second factor, listed, removed — and a removed one refuses new transfers |
| **M4.4 — Over HTTP** | `P4-TSK-008` | A verified customer moves money end to end over HTTP; both statements show the lines; a retry replays |
| **M4.5 — Reversal** | `P4-TSK-009` | A completed transfer is reversed by an operator with a reason; the original entry byte-identical; a second reversal refused |
| **M4.6 — The seams** | `P4-TSK-010` | Both ports are required parameters; the build fails without a decision; defaults documented |
| **M4.7 — Observability and demonstration** | `P4-TSK-011`, `P4-TST-001`, `P4-TST-002` | Every §15 meter live from a fresh instance; the sustained conservation demonstration; all `Phase: 4` register rows landed |
| **M4.8 — The gate** | `P4-DOC-001` | The exit review; the financial supplement re-assessed; the flip survives the battery |

## 17. What Phase 4 must NOT implement

| Not built | Owner |
|---|---|
| External rails, providers, payment intents/attempts, webhooks | Phase 5 |
| Scheduled / recurring transfers, and with them `CANCELLED` and the acceptance/execution split | Later phase, as one design (ADR-0044) |
| Cross-currency transfers, FX | Phase 9 |
| Fees on transfers | Phase 6 |
| Real limit or risk logic behind the seams | Phase 13 |
| Batch or bulk transfers | Unowned; not before Phase 5 |
| Transfer requests between customers (request-to-pay) | Unowned; a rail question |
| Value-threshold step-up policy | The risk seam's owner (Phase 13), as a versioned policy artefact |

## 18. Risks

| Risk | Mitigation |
|---|---|
| The transfer module writes postings directly | `INV-LED-04`'s boundary tests + the privilege floor: `transfers`' role grants never touch journal tables |
| Idempotency at the HTTP layer only | The claim is the executor's, inside the execution transaction, at the financial boundary — the interceptor merely demands the header |
| "No funds" leaks as a 500 | ADR-0044's `FAILED` state with its reason; the no-500 body sweep |
| The availability check drifts outside the lock | The `P3-TST-002` mutation shape is a named planned mutation for `P4-TSK-005` |
| The seams get defaulted overloads and Phase 13 silently skips them | Required parameters, no overload — the `PostingObserver` compiler-enforced precedent |
| Scope creep toward payments | §17, and the glossary's Transfer/Payment line quoted at the module boundary |
