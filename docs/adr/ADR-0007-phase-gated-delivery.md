# ADR-0007 — Phase-gated delivery with a formal status model

Status: Accepted

Date: 2026-08-31

## Context

The platform spans 26 bounded contexts and seventeen phases. The dominant risk is not
technical difficulty in any one area — it is breadth: many domains started, none finished
to a defensible standard, with correctness claimed on the basis that code compiles and a
demo worked.

`CLAUDE.md` §Definition of Done already states that a feature is not complete merely because
it compiles. That principle needs a mechanism, or it is a sentiment.

There is also a project-specific constraint: the owner is building this manually to develop
expertise. Depth matters more than coverage, and a phase left half-correct teaches the wrong
lesson.

## Decision

**Delivery is phase-gated, with formal entry and exit gates and an explicit status model.**

- Status model: `PLANNED` → `READY` → `IN_PROGRESS` → `IN_REVIEW` → `COMPLETE`, with
  `BLOCKED` reachable from any active state. Defined in `PHASE_GATES.md`.
- Exactly one phase may be `IN_PROGRESS` or `IN_REVIEW` at a time.
- A phase becomes `READY` only through a twelve-point entry gate.
- A phase becomes `COMPLETE` only through a twelve-point exit gate, plus an eight-point
  financial supplement for any phase that touches money.
- `IN_REVIEW` → `COMPLETE` is an explicit recorded decision, never implicit.
- A formal architecture and correctness review is mandatory before `COMPLETE`.
- Future-phase functionality is not implemented. Where a later capability is structurally
  needed earlier, the earlier phase defines a **seam** — an interface with documented default
  behaviour — and nothing more.
- `CURRENT_STATE.md` is the canonical statement of project state. Conversation history is not.

## Alternatives Considered

### Option A — Continuous flow, no phases
Pros: Maximum flexibility; work on whatever is most valuable.
Cons: With 26 contexts and no gate, breadth wins over depth by default. Financial invariants
get deferred because they are never the immediate blocker. No point at which correctness is
formally established.

### Option B — Phases as loose themes
Pros: Lightweight; provides direction without bureaucracy.
Cons: "Mostly done" becomes the terminal state of every phase. Without measurable exit
criteria there is no honest way to answer whether the ledger is correct.

### Option C — Formal gates with measurable criteria (chosen)
Pros: Forces the question "is this actually correct?" at a defined moment. Makes deferral
explicit and owned rather than silent. Matches how regulated financial software is genuinely
built. Directly supports the learning objective: each phase produces a complete, defensible
capability.
Cons: Overhead per phase. Risk of gate theatre if criteria are checked as a formality. Slower
apparent progress — real progress is the same or better, but it looks slower.

## Consequences

Positive:
- Every completed phase is genuinely complete: tested, secured, observable, documented.
- Deferred work is recorded as architectural debt with a named owning phase.
- The project has one authoritative state document, resilient to context loss between
  sessions.

Negative:
- Overhead per phase transition.
- Gates can degrade into checklist-ticking; the phase review exists specifically to counter
  this, and a review that finds nothing in a substantial phase should be treated as suspect.
- Discipline is required to refuse work that belongs to a later phase.

Operational impact: `CURRENT_STATE.md` is updated after every meaningful unit of work.

Security impact: Security requirements are gate criteria, not follow-up items. A phase cannot
complete with unimplemented security controls.

Financial impact: The financial supplement (F1–F8) means no money-touching phase completes
without a verified trial balance, tested idempotency, tested reversals and tested
concurrency.

## Invariants / Constraints

- No phase advances without its exit gate.
- Financial-correctness debt is never accepted; an invariant is either protected or the work
  is not done.
- Only one phase active at a time.

## Follow-up

Review the gate model after Phase 3 — the first heavily financial phase — and tighten or
simplify based on what the gate actually caught versus what it merely cost.
