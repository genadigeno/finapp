# ADR-0042 — Customer Account, Ledger Account, Wallet and Operational Account are four things

Status: Accepted (2026-09-17, P3-DOC-001)
Date: 2026-09-13
Phase: 3
Context: Accounts, Wallet, Ledger
Supersedes: nothing. Confirms the working position in unresolved question 4
(`accounts` and `wallet` are one module) and records its split trigger.

## Context

`CLAUDE.md` §Domain Distinctions forbids collapsing **Wallet / Bank Account / Ledger Account /
Operational Account**, and `GLOSSARY.md` defines each with what it is *not*. Phase 3 is the
phase that makes them real, and it is also the phase where the pressure to collapse them is
strongest, because the simplest first story — *"a customer has an account with a balance"* —
is satisfiable by one table.

This is `ADR-0029`'s situation repeating one layer down. That ADR kept Party, Customer and
Identity apart against the same pressure; the cost of collapsing arrived in the cases the merged
model could not represent. The same test applies here.

## Decision

**Four distinct concepts, and the boundary between the product and the accounting is the
important one.**

| Concept | Is | Is not | Owned by |
|---|---|---|---|
| **Customer Account** | The *product* a customer holds: an agreement with a status, a lifecycle, an owner, terms | A balance; a ledger account | `accounts` |
| **Wallet** | A *stored-value* product — value the platform holds for the customer, spendable | A bank account; a separate accounting mechanism | `accounts` (see module note) |
| **Ledger Account** | An *accounting* position: type, normal balance, one currency, postings | Customer-facing; a product; something a customer "has" | `ledger` |
| **Operational Account** | The *platform's own* ledger account — clearing, fees, FX position, suspense, rounding residual | A customer's; ever owned by a party | `ledger` |

**A Customer Account is not a balance-bearing object.** It *references* the ledger account(s)
recording its position — one per currency (ADR-0040) — and a balance query on the product is a
query against those.

**A customer's money and the platform's money are ledger accounts of different `owner_kind`, and
both are real.** Every customer credit is a platform liability; the double entry has two sides
and both sides are accounts. A model with only customer accounts cannot post anything, which is
the practical reason the distinction is not academic.

**`accounts` and `wallet` stay one module**, confirming `MODULE_ARCHITECTURE.md` §3 M1's working
position. **The split trigger is recorded and specific**: when a wallet acquires a lifecycle
that is not the account's — its own funding rails, its own top-up/withdrawal state machine, its
own limits — it becomes a module. Sharing a module is not sharing a concept: the two are
separate aggregates with separate tables inside it, so the split is a package move rather than
a data migration.

**A Bank Account is out of scope entirely in Phase 3.** It is an *external* account at another
institution, and it arrives with the rails that reach one (Phase 7). Naming it here is what
stops "account" silently meaning three things the first time an external payout is designed.

## Why

**The four cases a collapsed model cannot represent** — the `ADR-0029` test, applied:

1. **A customer product with two currencies.** One product, two ledger accounts. A merged model
   needs either a second product the customer did not open, or a multi-currency balance column,
   which re-invents the ledger inside a row.
2. **The platform's own money.** Fee revenue, an FX position, a suspense balance, the rounding
   residual account `INV-BAL-03` requires. None has a customer, and all need postings.
3. **A closed product with a permanent accounting history.** `INV-HIST-01` forbids deleting
   postings; the product's lifecycle ends and the ledger account's history must not. Separating
   them makes "closed" a property of the agreement, not a reason to touch history.
4. **A statement.** A statement is the product's view *over* the postings. With one table it is
   a view over itself.

**Why the product does not carry the balance.** A balance on the product row is
`balance = balance + amount` waiting to happen: it is the field an author under deadline
increments. `INV-BAL-01` forbids it and ADR-0041 puts the derived number somewhere the ledger
owns, so the product has nothing to increment.

**Why not split `wallet` out now.** It would be a module with one aggregate, the same lifecycle
as its neighbour, and no independent state — the definition of a premature boundary. ADR-0012's
rule is that starting merged is the reversible direction.

## Consequences

- `accounts` depends on `ledger` for balance queries and posting requests; `ledger` never
  depends on `accounts` — it knows `owner_kind` and an opaque `owner_ref`, not what a Customer
  Account is. Enforced by module isolation tests.
- Opening a product is two acts in one transaction: the agreement, and its ledger account(s).
- **No module other than `ledger` writes a posting** (`INV-LED-04`), including `accounts`. A
  product requests a posting through the ledger's command API.
- `GLOSSARY.md`'s ownership column for these terms is confirmed by this ADR rather than changed.

## Alternatives rejected

| Option | Why not |
|---|---|
| One `account` table with a balance | Cannot represent any of the four cases above; puts a mutable balance on the row every author will increment |
| Customer Account *is* the Ledger Account | The platform's own accounts then have no home, and closing a product would mean closing an accounting history `INV-HIST-01` protects |
| Wallet as its own module now | One aggregate, no independent lifecycle, no independent state — a boundary drawn before there is anything to separate |
| Model Bank Account now as a seam | A seam with no consumer for four phases; `EXECUTION_PROTOCOL.md` rule 3. The glossary entry is the seam |
