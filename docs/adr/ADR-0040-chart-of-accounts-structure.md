# ADR-0040 — Chart of accounts: a flat account with a typed classification, not a tree

Status: Accepted (2026-09-17, P3-DOC-001)
Date: 2026-09-13
Phase: 3
Context: Ledger, Accounting/GL
Supersedes: nothing. Closes unresolved question 2, open since project initiation.

## Context

Every ledger account needs a classification: what kind of thing it is, which side is its normal
balance, whose it is, and how it rolls up into a financial statement. `INV-LED-06` requires the
type and normal balance to be declared and **never to change after postings exist**, because
reclassifying an account retroactively changes the meaning of every report already issued.

The risk `DELIVERY_PLAN.md` §Phase 3 names is *"chart of accounts modelled too narrowly"*, and
`ROADMAP.md` records the seam: **GL account mapping is introduced in Phase 3 and consumed in
Phase 14.** Whatever is decided here has to survive a general ledger arriving eleven phases
later, without a data migration of posted history — which `INV-HIST-01` would forbid anyway.

## Decision

**A ledger account is a flat row with a typed classification. There is no parent pointer and no
account hierarchy.**

Each `ledger.ledger_account` carries:

| Field | Meaning |
|---|---|
| `account_type` | `ASSET`, `LIABILITY`, `EQUITY`, `REVENUE`, `EXPENSE` — a closed enum, the five that double-entry accounting has |
| `normal_balance` | `DEBIT` or `CREDIT`, **derived from the type and stored**, immutable once posted to |
| `currency` | Explicit, always (`INV-MON-02`). **One currency per account** |
| `owner_kind` | `CUSTOMER`, `OPERATIONAL`, `SUSPENSE` — whose account this is |
| `owner_ref` | The customer account it belongs to, or null for platform-owned accounts |
| `purpose` | A closed enum naming what the account is *for* (`CUSTOMER_WALLET`, `SETTLEMENT_CLEARING`, `FX_POSITION`, `FEE_REVENUE`, `ROUNDING_RESIDUAL`, `SUSPENSE_UNMATCHED`, …) |
| `gl_code` | **Nullable, and the Phase 14 seam** — a free-form external classification code |

**Roll-up is a query over `purpose` and `account_type`, not a walk over a tree.** A trial
balance groups by currency; a balance sheet groups by type; a Phase 14 GL mapping groups by
`gl_code`.

**One currency per account, always.** A multi-currency "account" is a product concept; at the
ledger it is *n* accounts, one per currency. This is the Phase 9 seam: an FX conversion posts
both legs through an FX position account and no entry silently changes currency (`INV-FX-01`).

**`purpose` is a closed enum in code, generating its own `CHECK` constraint** — the
`ConsentPurpose` and `AuditableAction` shape. Adding one is a reviewed act that arrives with
the capability needing it.

**Suspense accounts exist from Phase 3**, unused, as the Phase 8 seam (`INV-REC-05`). Value
parked there must be trackable and ageable before anything can park value there.

## Why

**Why not a tree.** An account hierarchy is the shape every accounting textbook draws, and it
buys one thing: roll-up by ancestry. It costs three.

1. **Recursive queries on the hottest financial path.** A balance sheet becomes a recursive CTE
   over a table whose row count grows with customers.
2. **The hierarchy becomes a second source of truth about classification**, free to disagree
   with `account_type`. When an account's parent says `LIABILITY` and its own type says `ASSET`,
   which is right? The tree invites the question and answers nothing.
3. **Re-parenting is reclassification**, and `INV-LED-06` forbids it after postings exist — so
   the tree would be a structure that must never be edited, which is a strange thing to build.

Roll-up by attribute gives the same reports with a `GROUP BY` and no ancestry to keep
consistent.

**Why `purpose` as well as `account_type`.** `account_type` answers the accountant's question
(which side of the balance sheet). `purpose` answers the engineer's (which account do I post
the fee to?). Collapsing them produces either an `account_type` enum with forty members, which
is not an accounting classification any more, or a codebase that finds accounts by string
matching on names.

**Why `normal_balance` is stored rather than derived at read time.** It is derivable from
`account_type` today, and `INV-LED-06` says it *"never changes after postings exist"* — a
property you can only enforce on something you store. A derived value cannot be constrained.

**Why `gl_code` is nullable and free-form.** Phase 14 owns the GL. Modelling its structure now
would be designing a system against no requirement, and `EXECUTION_PROTOCOL.md` rule 3 forbids
implementing future-phase functionality. A nullable column with a named owner is a seam; a
speculative `gl_account` table with a hierarchy is future-phase work with a different name.

## Consequences

- **A customer's "account" is at least two things**, and ADR-0042 keeps them apart: the product
  the customer sees, and the ledger account(s) recording its position.
- Adding a currency to a customer product means opening another ledger account, not altering one.
- The five-member `account_type` enum is fixed by accounting, not by us; `purpose` grows, and
  each addition is a reviewed decision.
- A Phase 14 GL mapping is a join on `gl_code`, populated by then; no posted history moves.
- **This ADR does not decide the balance sheet's presentation** — only the data it is derivable
  from.

## Alternatives rejected

| Option | Why not |
|---|---|
| Parent-pointer hierarchy | Recursive queries on the reporting path; a second, disagreeable source of classification; re-parenting is forbidden reclassification |
| Nested sets / materialised path | Same objections, plus a structure that must be rebuilt on every insert |
| Free-string account codes (`1000-ASSETS-CASH`) | Classification by substring is unqueryable, unconstrainable, and drifts the first time somebody types a code slightly differently |
| Multi-currency account with per-currency sub-balances | Re-invents the ledger inside a row; makes `INV-LED-01`'s per-currency balance a property of a JSON blob rather than of rows |
| Defer the chart until Phase 14 needs it | Postings reference accounts from the first day of Phase 3; the chart cannot be retrofitted under posted history |
