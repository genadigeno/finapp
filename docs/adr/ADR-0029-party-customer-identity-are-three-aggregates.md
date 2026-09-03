# ADR-0029 — Party, Customer and Identity are three aggregates

Status: Proposed

Date: 2026-09-03

## Context

`CLAUDE.md` §Domain Distinctions forbids collapsing *Identity / Authentication / Authorization /
Customer / KYC*. `GLOSSARY.md` defines all five and states what each is **not**.
`DELIVERY_PLAN.md` §17 names collapsing them as Phase 1's top risk: *"very expensive to unpick
later"*.

None of that decides the **implementation**. A glossary can be perfectly observed by a schema with
one `users` table, because prose does not create a boundary.

The pressure to collapse is real and comes from the simplest possible first story: *register a
customer*. One form, one row, one identifier. Every field belongs to something — a name to the
party, an email to the identity, a relationship start date to the customer — and one table holds
them all with no immediate consequence.

The consequence arrives later, in four places:

- **A person who is not a customer.** A beneficial owner in a KYB case (Phase 2) is a Party the
  platform must verify and has no relationship with. In a collapsed model they need a customer row
  and a login they will never use.
- **A customer who is not a person.** A business customer is a Party of a different kind, and its
  authorised users are different Parties again.
- **A person with several logins**, or a login that is retired and replaced. A credential is
  rotatable; a person is not.
- **Staff.** An operator acting on a customer's account is a User and an Identity, and never a
  Customer. In a collapsed model, either operators get customer rows or audit attribution has two
  code paths.

## Decision

**Party, Customer and Identity are three aggregates, in two modules, with three lifecycles.**

| Aggregate | Module | Answers | Lifecycle |
|---|---|---|---|
| **Party** | `party` | *who exists* — a natural or legal person | Created once; never deleted; may be merged (Phase 2+) |
| **Customer** | `party` | *who we have a commercial relationship with* | Opened, suspended, closed — and reopened as a new relationship, not a revived one |
| **Identity** | `identity` | *who can authenticate* | Created, suspended, closed; several may reference one Party over time |

**A Party is the root, and the only one of the three that is not a role.** Customer is a role a
Party plays toward the platform; Identity is a means by which a Party proves it is present. Both
reference a Party; neither owns one.

**Cardinality is stated rather than left to emerge:**

- one Party → zero or more Customers (a Party may be a beneficial owner and never a customer);
- one Party → zero or more Identities (zero for a party onboarded by an agent; more than one when
  a login is retired and replaced);
- one Customer → exactly one Party, for the life of the relationship. Reassigning a customer
  relationship to a different Party is not an update; it is closing one and opening another.

**Identity references Party by identifier only, across a module boundary.** `identity` never reads
party profile data and `party` never reads credentials. This is enforced by the existing
cross-module entity rule, not by convention.

**Credential data is isolated from profile data at the schema level** — separate tables, and
separate enough that a query mistake cannot join a password derivation onto a name.

## Alternatives Considered

### Option A — One `users` table
Pros: Simplest first story; one insert; no joins; every early screen is trivial.
Cons: Each of the four cases above becomes a nullable column, a type discriminator or a synthetic
row. Unpicking it later means migrating identity data out of a table that financial records already
reference by foreign key — and by then `INV-HIST-01` forbids rewriting the history that points at
it. This is the option `DELIVERY_PLAN.md` §17 names as the phase's top risk.

### Option B — Party and Identity, with Customer as a status field on Party
Pros: Two tables; the common case is one Party with one relationship.
Cons: The relationship has its own lifecycle — opened, suspended, closed — and its own dates,
terms and audit trail. A status field cannot hold a lifecycle, so the first suspension adds a date
column, then a reason, then a history table, arriving at Customer by accretion without a decision.
It also makes "one Party, two relationships" unrepresentable, which Phase 6 needs when a Party is
both a customer and a merchant's beneficial owner.

### Option C — Three aggregates, two modules (chosen)
Pros: Each of the four cases is representable with no nullable discriminator. Lifecycles are
separate because they *are* separate. Credential isolation is structural. Phase 2's KYC attaches to
Party, which is correct — verification is of a person, not of a login. Audit attribution has one
code path because an actor is an Identity regardless of whether it is a customer's or an operator's.
Cons: Three inserts and a join for the simplest registration, and a Phase 1 that looks
over-engineered against its own first screen. The cost is paid entirely in Phase 1 and the benefit
accrues in Phases 2, 6 and 10.

### Option D — Three aggregates in three modules
Pros: Maximum separation; `customer` could be extracted independently.
Cons: Party and Customer share a transaction boundary in the one operation that matters —
registering a new customer creates both, atomically. Splitting them puts a saga in front of the
platform's simplest write, which ADR-0001 exists to avoid. `MODULE_ARCHITECTURE.md` already maps
context 1 to `party` on this reasoning; this ADR records the same conclusion for the aggregates.

## Consequences

Positive:
- The distinction `CLAUDE.md` forbids collapsing is structural rather than documentary.
- Phase 2 attaches verification to a Party without touching identity.
- An operator and a customer are both Identities, so `INV-AUD-01` attribution has one path.
- A retired credential does not retire a person.

Negative:
- Registration writes to two modules in one transaction. Permitted — one deployable, one database
  (ADR-0001) — but it must be a *single* transaction, and the temptation to make it eventually
  consistent must be refused: a Party created without its Customer is an orphan nobody queries.
- Three identifiers where a naive model has one. `EntityId` makes substituting them a compile
  error (ADR-0013), which is why that mechanism exists.
- Reads that need name-plus-login cross a module boundary. Phase 1 has few such reads; if they
  multiply, the answer is a read model, not a merge.

## Invariants / Constraints

`INV-IDN-01`, `INV-IDN-04`, `INV-AUD-01`. Phase 1 exit criterion: *"Party, Customer and Identity
are separately persisted with distinct lifecycles."*

## Follow-up

- `P1-TSK-003` proves the separation by test before any authentication work begins.
- Phase 2 attaches KYC to Party and stores only a non-authoritative verification *status*
  projection on Party (`MODULE_ARCHITECTURE.md` §5).
- Party merge — two records found to be one person — is deliberately **not** in Phase 1. It is a
  Phase 2 concern that arrives with verification, and designing it now would be designing against
  no evidence.
