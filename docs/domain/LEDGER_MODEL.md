# Ledger Model

The ledger is the **authoritative financial record**. Every balance, statement, report and
reconciliation in this platform is derived from it, and nothing else is financial truth
(`CLAUDE.md` rule 12, `INV-EVT-02`).

*Decided by the Phase 2 → 3 transition (2026-09-13) in ADR-0039…0042; implemented by Phase 3
and updated by `P3-DOC-001` (2026-09-17) against what was built. Two spots had gone stale on the
adjustment — `P3-TSK-021` replaced the one-person write with two authenticated acts after they
were written — and were corrected as review findings rather than silent edits
([`reviews/PHASE_3_REVIEW.md`](../project/reviews/PHASE_3_REVIEW.md) area 7).*

---

## 1. The concepts, and what each is not

| Concept | Is | Is **not** |
|---|---|---|
| **Chart of Accounts** | The set of ledger accounts and their classification | A hierarchy. It is flat, with roll-up by attribute (ADR-0040) |
| **Ledger Account** | An accounting position: type, normal balance, one currency | A customer's product; something a customer "has" |
| **Operational Account** | A ledger account holding the platform's own position | A customer's account. Mixing them is how a shortfall becomes invisible |
| **Journal Entry** | The atomic accounting unit: ≥2 lines that balance per currency | A transaction, a payment, or a transfer — those are *economic events* that cause entries |
| **Journal Line** | One account, one direction, one amount | A signed number. Direction carries the sign |
| **Debit / Credit** | A `Direction` on a line | "Money in" / "money out". Which of those a debit means depends on the account's normal balance |
| **Posting** | The act of writing a balanced entry durably | A balance update |
| **Reversal** | A *new* entry with directions swapped, referencing the original | An edit, a delete, or a negation in place |
| **Adjustment** | A manual entry proposed with a reason by one authorised person and posted by a second who approves it (`INV-AUD-04`) | A correction any one person may make alone |
| **Suspense** | An account where unmatched value is parked, aged and reported | A resting place. `INV-REC-05` requires it be temporary |
| **Currency** | Explicit on every account, line and balance | Ever implied, defaulted, or converted silently |

## 2. The three dates

`DOMAIN_MODEL.md` §Time governs, and the ledger is where getting it wrong becomes permanent.

| Date | Decides | Comes from |
|---|---|---|
| **System time** (`created_at`) | When the machine wrote the row | The injected `Clock`. **Never a business fact** |
| **Posting date** | Which accounting period the entry falls in | A **domain input** to the command |
| **Value date** | When value is available / interest accrues | A rail, product or contract rule — also an **input** |

**Posting date and value date are never clock reads.** A component that derives a posting date by
calling the clock has silently decided that execution time and accounting date are the same
thing, and no rule can catch that substitution — it is a design-review question, which is why it
is written here.

## 3. The rules a posting must satisfy

1. **It balances, per currency** (`INV-LED-01`) — enforced by the domain *and* by the database,
   because the domain is not the only writer a schema will ever have.
2. **It has at least two lines** (`INV-LED-02`). A single-sided posting is unbalanced value
   movement by definition.
3. **It is immutable once written** (`INV-LED-03`, `INV-HIST-01`) — at `DB-PRIVILEGE`: the
   application role holds no `UPDATE` and no `DELETE`.
4. **It is attributable** (`INV-LED-05`): actor, correlation, causation, `NOT NULL`.
5. **It is idempotent at the financial boundary** (`INV-IDEM-01`): one command, one key, one
   effect.
6. **Its publication commits with it** (`INV-EVT-01`): the outbox row is in the same transaction.
7. **Only the ledger writes it** (`INV-LED-04`). Other modules *request* postings through a
   command API.

## 4. Balances

**A balance is derived from postings and is never an independent authority** (`INV-BAL-01`,
ADR-0002, ADR-0009). Four distinct numbers, never collapsed:

| Balance | Definition |
|---|---|
| **Settled / ledger** | Sum of posted lines, signed by the account's normal balance |
| **Pending** | Value from entries not yet final — a Phase 5 seam; nothing creates one yet |
| **Holds** | Sum of active holds |
| **Available** | `settled − holds` (`INV-BAL-04`) |

**Replaying every posting from zero reproduces the balance exactly** (`INV-BAL-02`). That is the
operational definition of an explainable balance, and it is verified continuously rather than
believed.

A **projection** exists for read performance (ADR-0041): a table in the ledger schema, updated in
the posting's own transaction, never behind. **No financial decision reads it** — a hold or an
overdraft check derives its number from the postings inside the account lock, because a decision
made from a projection is a balance-as-truth model wearing a different word (`INV-BAL-05`).

## 5. Concurrency

ADR-0039. Two halves, and conflating them is the mistake:

- **Postings are inserts.** No row is updated, so under `READ COMMITTED` there is no lost update
  to have. No lock is taken on any account.
- **Decisions that depend on a balance take `SELECT … FOR UPDATE` on the account row**, then
  derive, then act. This covers holds and any future non-overdraw check, and nothing else.

`SERIALIZABLE` is deliberately not used: it would put a retry loop around every money-moving
command, which is where "the database committed but the response was lost" becomes two effects.

## 6. Correction

**Financial history is never edited** (`INV-HIST-01`). A mistake is corrected by a new entry:

- a **reversal** references the original, swaps directions, and is bounded by what remains
  un-reversed (`INV-REV-01`, `INV-REV-02`). The original is byte-identical afterwards — asserted,
  not assumed;
- an **adjustment** is **two authenticated acts** (`P3-TSK-021`): an initiator *proposes* —
  reason required (`INV-REV-04`), nothing posts — and a **second** `LEDGER_ADJUST` holder
  *approves*, which posts the entry in the approval's own transaction. Approver ≠ initiator
  holds at `DB-CONSTRAINT` (`INV-AUD-04`), and there is no approver column on the entry because
  there are two acts, each with one actor, paired on the proposal row. Four-eyes applies to
  **every** adjustment — a threshold is a versioned per-currency policy artefact with nothing to
  calibrate it yet, recorded as a future seam on the proposal row;
- a **rounding residual** is posted to a designated account, never absorbed (`INV-BAL-03`).
  Absorbed residual is money creation or destruction, at scale.

## 7. What a posting must be explainable as

`CLAUDE.md` requires this chain to be traceable for any monetary change, and every link is a row:

```
economic event → domain operation → financial transaction → journal entry
              → debit/credit lines → resulting balances → settlement → reconciliation
```

Phase 3 builds the middle: entry, lines, balances. The economic event is whatever later phase
causes the posting; settlement and reconciliation are Phases 5 and 8. **Suspense accounts exist
from Phase 3** so Phase 8 can park unmatched value without corrupting customer balances.

## 8. The trial balance

Total debits equal total credits **for every currency, at all times** (`INV-ACC-01`) — the
system-level expression of `INV-LED-01` and the primary continuous correctness signal. A job
asserts it with alerting, and it **never self-corrects**: a ledger that repairs itself has
destroyed the evidence of what went wrong.
