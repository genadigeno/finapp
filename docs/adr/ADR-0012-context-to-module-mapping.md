# ADR-0012 — Context-to-module mapping: deliberate merges with recorded split triggers

Status: Accepted

Date: 2026-08-31

## Context

ADR-0001 chose a modular monolith and ADR-0006 decided how boundaries are *enforced*. Neither
decided **where the boundaries are** — which bounded context becomes which module.

That gap is not academic. `BOUNDED_CONTEXTS.md` lists 28 contexts. Mapping each to its own
module produces 28 modules before a single line of business code exists, most of them empty
for years. Mapping them too coarsely produces modules that own several unrelated pieces of
authoritative state, which is the coupling a modular monolith exists to prevent.

`CLAUDE.md` is explicit that these are "domain boundaries, not an automatic list of
microservices", and that a microservice must not be created merely because a noun exists. The
same restraint applies to modules: a module per noun is the same mistake at a smaller scale.

There is also an asymmetry that should drive the decision. **Splitting a module later is
cheap** — a package move, some interface extraction, no data migration if the state was
already cohesive. **Merging two modules later is expensive** — both have grown authoritative
state, schemas, events and callers, and merging means deciding whose state survives. The
reversible direction is therefore to start merged.

## Decision

**Each bounded context maps to exactly one module. A module may serve several contexts. Every
merge records its rationale and the specific evidence that would trigger a split.**

- 28 contexts map to 24 modules.
- A context is never split across modules — that would give its state two owners, which
  `CLAUDE.md` forbids.
- Six merges are recorded, each with a named split trigger (`MODULE_ARCHITECTURE.md` §3,
  M1–M10): Wallet into `accounts`; Fraud, AML and Case Management into `risk`; Audit into
  `platform`; Reporting into `accounting`; API/Integration across `platform` and `app`.
- Two separations are recorded as deliberate *against* the merge default:
  `paymentmethods` from `payments` (PCI scope is the one isolation boundary far more
  expensive to introduce late), and `reconciliation` from `settlement` (settlement is not
  reconciliation).
- Two merges are marked **provisional** with a resolution deadline — Wallet (Phase 3) and
  Checkout (Phase 6) — rather than silently settled.
- Every authoritative state is listed against exactly one owning module
  (`MODULE_ARCHITECTURE.md` §5), and anything derived names what it derives from.

## Alternatives Considered

### Option A — One module per bounded context
Pros: Maximum boundary clarity; the mapping needs no judgement; no merge ever has to be
justified.
Cons: 28 modules, most empty for years. Cross-module calls proliferate for concepts that are
genuinely one thing — a wallet and an account would maintain duplicate lifecycles. Boundary
count is not boundary quality: many boundaries drawn before the domain is understood are
drawn in the wrong place, and each one is then defended because it exists.

### Option B — A few coarse modules (customer, money, commerce, risk, ops)
Pros: Very few boundaries to maintain; fast early progress.
Cons: Each coarse module owns several unrelated pieces of authoritative state, so "one writer
per table" degrades into "one module writes forty tables". `ledger` would sit inside a
general "money" module alongside transfers and payments, destroying the single-writer
property that `INV-LED-04` depends on.

### Option C — Context-to-module mapping with recorded merges and split triggers (chosen)
Pros: Follows the reversible direction — merged now, split on evidence. Each merge is a
decision with a rationale someone can disagree with, rather than an accident. Split triggers
convert "we should revisit this" into a specific observable condition. Provisional mappings
carry deadlines, so an unmade decision cannot quietly become load-bearing.
Cons: Requires judgement, and the judgement can be wrong. A merge whose split trigger is
never observed may persist past the point it should have split. The map must be maintained as
phases land, or it becomes documentation that describes an architecture nobody has.

## Consequences

Positive:
- Every context has exactly one owning module, and every authoritative state exactly one
  owner — checked explicitly rather than assumed (`MODULE_ARCHITECTURE.md` §5).
- Merges are reviewable. "Why is AML not its own module?" has a written answer.
- Doing the mapping surfaced two bounded contexts absent from the context list (`Transfers`,
  `Case Management`) and one state at risk of two owners (`Case`).

Negative:
- 24 modules is more than a small team would choose and fewer than a purist would. It will be
  wrong somewhere; the split triggers are the mechanism for finding out where.
- The map is a design contract for modules that do not exist yet. Phases 1–14 must either
  satisfy it or amend it deliberately.
- Provisional mappings carry a decision debt with a deadline, which must actually be honoured.

Operational impact: none directly — this is a design decision. It constrains where later
phases put code.

Security impact: `paymentmethods` is separated specifically so the PCI boundary is a module
boundary rather than a convention. `identity` is separated from `party` so credential material
is isolated from profile data.

Financial impact: `ledger` remains a single module and the sole writer of postings
(`INV-LED-04`), and `accounting` is separated from it so a reporting change cannot alter
financial truth.

## Invariants / Constraints

`INV-LED-04` (ledger is the sole posting writer), `INV-BAL-01` (no independent balance
authority), `INV-HIST-03` (audit append-only, owned by `platform`), `INV-EVT-02` (events are
not the accounting source of truth).

The map itself is **not** mechanically enforced. Gradle enforces dependency direction only;
cross-module internals, entity references and second owners rest on review until
`P0-TSK-007` (ArchUnit) lands.

## Follow-up

- `P0-TSK-007`: ArchUnit rules converting §6 boundary rules from reviewed into enforced.
- Phase 3: resolve the Wallet merge (M1).
- Phase 6: resolve the Checkout separation (M2).
- Phase 13: confirm that no shared case store is required (§5).
- Each phase entry gate: confirm the module it is about to build still matches its register
  entry, and amend the map by ADR if not.
