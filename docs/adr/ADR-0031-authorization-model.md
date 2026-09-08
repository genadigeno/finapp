# ADR-0031 — Roles grant permissions; ownership is checked separately

Status: Accepted

Date: 2026-09-03

## Context

`DELIVERY_PLAN.md` §5 requires a decision on "authorization model: RBAC with attribute
constraints; policy evaluation point location". `PHASE_GATES.md` §Phase 1 requires *"every
protected endpoint has a passing negative authorization test"*. `INV-AUD-03` requires every
privileged financial action to have an explicit authorization check with a passing negative test.

Two questions are usually answered as one and must not be:

1. *May this kind of actor perform this kind of operation?* — a **permission** question.
2. *May this actor perform it on **this** resource?* — an **ownership** question.

Collapsing them produces the most common authorization defect in financial software: a customer
who legitimately holds the `transfer:create` permission uses it against somebody else's account.
Every check passes. Nothing is logged as a denial.

The Phase 0 → Phase 1 transition also found an unrecorded merge: `CLAUDE.md` forbids collapsing
*Identity / Authentication / Authorization*, `BOUNDED_CONTEXTS.md` names context 2 as "Identity &
Authentication" with no Authorization, and `MODULE_ARCHITECTURE.md` gives `identity` ownership of
"Role assignment". ADR-0012 requires every merge to carry a justification and a split trigger. This
one had neither.

## Decision

### Two checks, always both, never one

| Check | Question | Where |
|---|---|---|
| **Permission** | May an actor of this kind do this at all? | Declarative, at the API boundary |
| **Ownership** | May *this* actor do it to *this* resource? | In the domain operation, against authoritative state |

**Ownership is never checked at the boundary**, because the boundary does not know who owns the
resource — it knows an identifier from the request, and trusting that is the defect. The owning
module knows, because it holds the state.

**Deny by default.** An operation with no declared permission is refused, not permitted. A rule's
absence is never a grant (`INV-IDN-04`).

### RBAC with ownership scoping, not ABAC

Permissions are grouped into **roles**; roles are assigned to Identities. There is no general
attribute-expression language and no policy engine.

**Why not a policy engine.** A rules engine is the right answer when policy changes faster than
code and non-engineers must author it. Neither is true here, and both cost the same thing:
authorization becomes data, which means *"why was this denied?"* is answered by evaluating a rule
set rather than by reading a method. Phase 13 introduces a versioned rules engine for **risk**
decisions, where policy genuinely does change faster than code — and that is a different question
with different auditability requirements (`INV-CRD-01`). Conflating them would put risk-shaped
machinery in front of every request.

### The policy decision point is the module that owns the state

Permission checks are declared at the API boundary and enforced before the handler. Ownership
checks live inside the domain operation. There is no central authorization service that other
modules call — that would require it to know every module's ownership model, which is the
authoritative-state duplication `MODULE_ARCHITECTURE.md` §5 forbids.

### Authorization stays in `identity` — a recorded merge

Role and permission **assignment** is owned by `identity`. Authorization is not given its own
module or context in Phase 1.

**Justification.** A separate module would own a table of role assignments keyed by Identity, read
by every module, and written by nobody but administrative flows — a module whose entire content is
one association to the aggregate next door. ADR-0012 prefers starting merged, because splitting
later is a package move and merging two modules that have both grown authoritative state is not.

**Split trigger, recorded as ADR-0012 requires.** Extract an `authorization` module when **either**
holds:

1. permissions must be granted on something other than an Identity — a service credential, an
   organisation, a delegated mandate; or
2. authorization policy acquires its own lifecycle — versioned policy sets, effective dates,
   approval workflow — at which point it has become the kind of thing Phase 13's engine handles
   and must not be answered by a role table.

`BOUNDED_CONTEXTS.md` is amended to name context 2 as **Identity, Authentication & Authorization**,
so the context list stops omitting a concept the instructions forbid collapsing.

## Alternatives Considered

### Option A — Permission checks only
Pros: One mechanism; every endpoint annotated; trivially testable.
Cons: The ownership defect above, in full. A customer with `transfer:create` moves money from any
account whose identifier they can guess or enumerate. Rejected outright.

### Option B — Ownership checks only, no roles
Pros: No role machinery; every operation checks the resource.
Cons: Cannot express operations with no resource — registration, listing one's own sessions — and
cannot express staff at all: an operator legitimately acts on resources they do not own, so
ownership-only degrades to a special case for every administrative action.

### Option C — A policy engine (ABAC/OPA-style), evaluated centrally
Pros: Expressive; policy authored as data; the answer for a platform with many tenants and
negotiated policy.
Cons: Authorization becomes data the code cannot be read to understand, and the decision point
needs facts it does not own. It is also premature by the roadmap's own structure: nothing before
Phase 13 has policy that changes faster than code. `SYSTEM_ARCHITECTURE.md`'s principle — prefer
fewer, stronger mechanisms — applies.

### Option D — RBAC at the boundary plus ownership in the domain (chosen)
Pros: Each question is answered where the knowledge is. The boundary check is declarative and
uniformly testable, which is what makes "a negative test for every protected endpoint" mechanically
checkable rather than a promise. Ownership is enforced by the module that would otherwise be the
one leaking.
Cons: Two mechanisms, and a reviewer must confirm both are present. Mitigated by making the
boundary check deny-by-default and by requiring a negative ownership test per resource-scoped
operation.

## Consequences

Positive:
- `INV-IDN-04` and `INV-AUD-03` are enforceable at the point each applies.
- Staff acting on customer resources is expressible without a special case.
- Phase 4's step-up requirement composes: permission, then ownership, then assurance level.

Negative:
- An operation missing its ownership check is not detectable by the boundary rule. The mitigation
  is a review question plus a required negative test.

  **Amended by `P1-TSK-021`, and narrowed rather than withdrawn.** The original wording was *"no
  build rule closes this"*. That is right about the **boundary** rule and was too broad about
  everything else: `OwnershipIsScopedTest` now fails the build when a persistence operation takes a
  resource identifier and nobody has classified how ownership is established. What it does **not**
  do is decide whether an operation is safe — it forces every operation that could be unsafe to be
  classified, which is the `MfaBypassPathsAreEnumeratedTest` shape applied to ownership, and for the
  same reason: *a list of tests is a snapshot*, and the operation added in Phase 4 will not be in it.

  The remaining limits are stated in the test itself and are real:
  - it cannot see that an owner-scoped statement binds the **right** owner — only the negative
    behavioural test does, which is why the register is held against a named test per operation;
  - it cannot verify an `AUTHORITATIVE_ID` claim, only that the provenance it names exists and is
    itself owner-constrained where that is checkable;
  - it is blind to an ownership decision taken somewhere that issues no SQL.

  So the class of limit ADR-0024 records still applies — it is narrower than it was.
- Role explosion is a real medium-term risk. Deliberately not pre-solved: Phase 1 has few roles,
  and inventing hierarchy now would be designing against no evidence.

## Invariants / Constraints

`INV-IDN-04`, `INV-AUD-03`, `INV-AUD-01`. ADR-0012 (context-to-module mapping, and this merge's
split trigger), ADR-0021 (the acting party), ADR-0030 (assurance level, a third and separate check).

## Follow-up

- Phase 4 consumes the assurance level for high-value transfers; the seam is ADR-0030's level.
- Phase 13 owns the risk rules engine. It is not an authorization engine and must not become one.
- `BOUNDED_CONTEXTS.md` context 2 renamed by this ADR.
