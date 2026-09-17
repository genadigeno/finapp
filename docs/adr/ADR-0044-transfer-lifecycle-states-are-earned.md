# ADR-0044 — The transfer lifecycle: states are earned by their producers

Status: Proposed
Date: 2026-09-17
Phase: 4
Context: Transfers
Supersedes: nothing. Companion to ADR-0043, whose transaction boundary decides which states
can exist at all.

## Context

`INV-LIFE-01` requires every money-moving operation to have an explicit, enumerated lifecycle,
and Phase 4's transfer is the first operation the catalogue marks it for. The conventional
transfer machine is rich — `INITIATED → VALIDATED → AUTHORIZED → PROCESSING → COMPLETED`,
with `FAILED`, `CANCELLED` and `REVERSED` around it — and most of those states are copied
from systems whose execution crosses a network boundary mid-flight.

ADR-0043 removed that boundary: a Phase 4 transfer executes in **one local transaction**. A
state that begins and ends inside one uncommitted transaction is never observable by any
other connection, any other instance, or any client — it is a comment wearing a status's
clothes. And a state that no command can produce is worse: `P3-TSK-012` established the rule
(*"`PENDING` has no producer"*) that a machine models what the system does, not what a
diagram remembers other systems doing.

## Decision

**Four states, five edges, and every state has a producer:**

```
INITIATED ──> COMPLETED ──> REVERSED
     └──────> FAILED
```

| State | Produced by | Durable? | Terminal? |
|---|---|---|---|
| `INITIATED` | The aggregate's birth, inside the execution transaction | **Never observed durably** — it exists in the aggregate and in the lifecycle history rows, not in any committed snapshot | No |
| `COMPLETED` | The execution command: the posting committed | Yes | **No — stable, with exactly one outgoing edge** (below) |
| `FAILED` | The execution command: a domain refusal (insufficient funds, destination not postable, …), committed **with its enumerated reason and no posting** | Yes | Yes |
| `REVERSED` | The reversal command: a new referencing journal entry (`INV-REV-01`) and the state move, one transaction | Yes | Yes |

**The states deliberately absent, each with the reason:**

- **`VALIDATED`, `AUTHORIZED`** — preconditions of the execution command (ownership, session,
  the seams, the availability derivation inside the lock), not durable facts. A validation
  that fails before the claim is the caller's 4xx with nothing written; after it, the outcome
  is `FAILED`. A state observable by nobody models nothing.
- **`PROCESSING`** — the state of an operation whose outcome somebody else decides. An
  internal transfer's outcome is never anybody else's (the glossary's own Transfer/Payment
  distinction), so the state has no producer until an asynchronous execution path exists.
  Phase 5's payment lifecycle owns that shape (`INV-LIFE-03`).
- **`CANCELLED`** — a cancellation needs a window between acceptance and execution, and
  ADR-0043 closed it. The state arrives with scheduled/recurring transfers (explicitly out of
  Phase 4 scope), produced by the command that creates the window.

**`COMPLETED` is stable, not terminal, and that is a deliberate reading of `INV-LIFE-04`.**
The invariant's point is monotonic history: no reopening, no un-completing, no transition
that makes an issued statement retroactively wrong. `COMPLETED → REVERSED` is a one-way edge
into another terminal, driven by its own command, whose ledger effect is a **new** entry —
the original posting is byte-identical afterwards and the customer's statement gains a line
rather than losing one. The alternative — calling `COMPLETED` terminal and recording the
reversal as a nullable reference beside it — stores one fact ("what happened to this
transfer?") in two places free to disagree, which is the two-answers defect this platform
refuses everywhere else. A reversal of a `REVERSED` or `FAILED` transfer is refused by the
aggregate (`INV-LIFE-02`) and has nothing to reverse at the ledger either (`P3-TSK-016`'s
chain refusal).

**The machine is enforced in three places**: the aggregate rejects every invalid transition
(the exhaustive cross-product sweep, derived from the machine); the schema's status `CHECK`
and the transition trigger are generated from the enum (`sqlValueList()`, the established
ceremony) so raw SQL cannot store an unknown state or an illegal edge; and the lifecycle
history table records every transition with the injected clock, append-only, so the path a
transfer took is evidence rather than memory.

## Why

A lifecycle is a contract with every future caller — the status endpoint, Phase 13's risk
hooks, an operator tool — and each fictional state in it is a branch somebody eventually
writes code for. The cost of adding a state later, with the command that produces it, is one
enum value and one migration under the established generated-constraint ceremony; the cost of
carrying an unreachable one now is a machine whose tests assert edges nothing can drive and
whose readers cannot tell design from decoration.

The API contract still models asynchronous outcome — `POST /v1/transfers` answers with a
status field and `GET /v1/transfers/{id}` exists from day one — so the day a state joins the
machine, no client contract changes. The contract's shape does not promise synchrony; the
machine simply refuses to pretend asynchrony it does not have.

## Consequences

- Exactly one snapshot-visible non-terminal state exists (`COMPLETED`), so "stuck transfer"
  is unrepresentable (ADR-0043's consequence, restated from the machine's side).
- `FAILED` carries an enumerated reason column, `NOT NULL` exactly when the state is
  `FAILED` (the `V010` decision-matches-status pattern), because a refusal without its reason
  is a support ticket the platform caused.
- The events follow the machine: **the terminal facts publish** (`TransferCompleted`,
  `TransferFailed`, `TransferReversed`). `TransferInitiated`, listed by the delivery plan and
  the module register, is **not published**: under ADR-0043 it would commit in the same
  transaction as its own outcome, and an event that always accompanies its successor is one
  fact named twice. Both documents are corrected with provenance; the event joins the
  vocabulary with the first observable initiated state.

## Alternatives rejected

- **The eight-state conventional machine** — five of its states have no producer here
  (above); each is a fiction with a test asserting nothing.
- **`COMPLETED` terminal + a reversal reference column** — one fact in two places (above).
- **A separate `TransferReversal` aggregate** — the ledger's reversal entry already *is* the
  financial record (`INV-REV-01`); a second aggregate would model the same fact twice, and
  the transfer's own state answers the customer's question.

## Follow-ups

- `P4-TSK-003` implements the machine and its exhaustive sweep; `P4-TSK-004` generates the
  schema artefacts from it.
- The delivery plan's and module register's event lists corrected with provenance
  (`P3 → P4` transition).
- When scheduled transfers arrive, `CANCELLED` and the acceptance/execution split arrive
  together, as one design.
