# Execution Protocol

How this project is worked, phase by phase.

This protocol governs every working session. It exists because the failure mode of an
ambitious platform is not writing bad code — it is writing a lot of code across many
half-finished domains, none of which is correct or verifiable.

Related: [`DELIVERY_PLAN.md`](DELIVERY_PLAN.md) · [`PHASE_GATES.md`](PHASE_GATES.md) ·
[`BACKLOG.md`](BACKLOG.md) · [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md) ·
[`CURRENT_STATE.md`](CURRENT_STATE.md)

---

## The Ten Rules

### 1. Never skip a phase gate
A phase becomes `READY` only through the entry gate and `COMPLETE` only through the exit
gate, both defined in `PHASE_GATES.md`. There is no informal, partial or provisional pass.
If a gate criterion cannot be met, the correct action is to record it as a blocker or as
accepted architectural debt with a named owning phase — never to waive it silently.

### 2. Never silently move to the next phase
Transitioning between phases is an explicit, recorded decision written into
`CURRENT_STATE.md`, including the review outcome. Finishing the last task of a phase does
not complete the phase; passing the exit gate does.

### 3. Never implement future-phase functionality
Work stays inside the active phase. Where a later capability is structurally required
earlier, the earlier phase defines a **seam** — a named interface with documented default
behaviour — and nothing more. Defining a seam is not permission to implement the later
domain behind it. The seam register is in `ROADMAP.md` §Refinement 2.

If future work appears genuinely necessary to make current work correct, that is a signal
the phase boundary is wrong. Stop, and resolve it with an ADR — do not quietly widen scope.

### 4. Keep scope constrained to the active phase
Make the smallest coherent change that satisfies the task. Do not refactor unrelated
subsystems, do not "improve while you're in there", and do not redesign anything the task
did not require. Unrelated problems discovered during work are recorded as backlog items or
architectural debt, not fixed opportunistically.

### 5. Update project state after significant work
`CURRENT_STATE.md` is the canonical description of where the project is. It is updated
after any meaningful change: completed tasks, new blockers, discovered debt, resolved
questions, changed next task. A stale `CURRENT_STATE.md` blocks Definition of Done (§3
anti-patterns).

### 6. Update ADRs when architectural decisions change
Every architectural decision is recorded before or as it is taken, using
`docs/adr/ADR_TEMPLATE.md`. A decision that changes an accepted ADR does not edit it —
it supersedes it with a new ADR, and the old one is marked `Superseded` with a pointer.
Decisions discovered in code but absent from the ADR record are architectural debt.

### 7. Update domain documentation when the domain model changes
When implementation reveals that the domain model is wrong, incomplete or imprecise, the
documentation is corrected in the same unit of work. Documentation drift is the mechanism
by which a codebase stops being explainable — and an unexplainable ledger is an unauditable
one.

### 8. Maintain tests as part of implementation, not afterward
Tests are written with the behaviour, not scheduled after it. For financial behaviour, the
invariant test is written *first* wherever practical. A test is only credible if it has been
demonstrated to fail when the behaviour it protects is deliberately broken; for invariant
tests, that demonstration is mandatory.

### 9. Prefer vertical slices that produce working, testable capabilities
Within a phase, sequence work so that each slice delivers something exercisable end to end
rather than building horizontal layers that only integrate at the end. A thin slice through
API → domain → ledger → event → audit is more valuable and far less risky than a complete
persistence layer with nothing above it.

### 10. Conduct a formal architecture and correctness review at the end of each phase
The review defined in `PHASE_GATES.md` §4 is mandatory and is written down. It is not a
self-congratulatory summary: its purpose is to find the gap. A review that finds nothing in
a phase of meaningful size should itself be treated as suspect.

---

## Working Session Procedure

Every session follows this sequence.

### Before work
1. Read `CURRENT_STATE.md` — it, not conversation history, defines where the project is.
2. Confirm the active phase and its status.
3. Confirm the task is in the current phase's backlog and its dependencies are met.
4. Read the relevant architecture/domain docs and the applicable `.claude/rules/` files.
5. Identify: bounded context, aggregate, invariants (`INV-*`), lifecycle transitions,
   transaction boundary, consistency boundary, idempotency behaviour, external
   dependencies and their failure modes, security/audit/reconciliation implications.
6. Inspect existing code and tests before writing anything.

### During work
7. For non-trivial work, explain the domain and design **before** implementing it.
   Implementation before shared understanding is how architecture erodes.
8. Make the smallest coherent change.
9. Write tests alongside the behaviour.
10. Challenge any design that violates a financial, security, domain or
    distributed-systems principle — including a design already agreed, if implementation
    reveals it to be wrong.

### After work
11. Run the relevant tests, including integration tests where persistence or transaction
    boundaries are involved.
12. Verify the change against `DEFINITION_OF_DONE.md`.
13. Update documentation where behaviour or architecture changed.
14. Create or update ADRs for any decision taken.
15. Update `CURRENT_STATE.md`.
16. Record any newly discovered architectural debt or unresolved question.

---

## Ownership and Role

The project owner is implementing this system manually to develop fintech and system-design
expertise. The assisting role is **principal architect and mentor**, not autonomous code
generator.

In practice:

- Explain the domain and the design trade-offs first; teach the reasoning, not just the
  answer.
- Recommend a position rather than presenting an exhaustive neutral survey of options.
- Challenge designs that are wrong, including the owner's — deference that lets a
  financial-correctness defect through is a failure of the role.
- Do not generate large volumes of code the owner has not reasoned through.
- Prefer one well-understood correct slice over five plausible ones.

---

## Scope Discipline

Explicitly forbidden without an ADR and an explicit decision:

- Creating a microservice because a noun exists.
- Extracting a module into a service without measurement justifying it.
- Introducing a framework, library or pattern without a stated reason.
- Weakening a financial invariant to simplify distributed processing.
- Treating Kafka, Redis, a cache, a search index or a projection as financial truth.
- Adding a capability from a later phase because it is "quick".
- Redesigning a subsystem the current task did not require.

---

## Handling Blockers

When work cannot proceed:

1. Set the phase or task status to `BLOCKED`.
2. Record in `CURRENT_STATE.md`: what is blocked, why, what would unblock it, and who or
   what owns the resolution.
3. Do **not** work around a blocker by implementing a later phase, weakening an invariant,
   or leaving a half-finished path in the codebase.
4. If the blocker is an unresolved architectural question, resolve it with an ADR before
   resuming.

---

## Architectural Debt

Debt is acceptable when it is deliberate, recorded and owned. It is unacceptable when it is
accidental or hidden.

Every debt item records: what was deferred, why, what risk it carries, what would trigger
paying it down, and the phase that owns it. Debt items live in `CURRENT_STATE.md`
§Known Architectural Debt. Financial-correctness debt is not permitted — an invariant is
either protected or the work is not done.
