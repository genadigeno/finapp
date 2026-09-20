---
name: task-design
description: Command 1 of the backlog task loop - produce the full design for the current READY task before any code is written. Use when the owner says "design <ID>", "next task", or starts work on a backlog task. Produces the 23-point design plus the mandatory multi-instance and financial analysis, then stops.
---

# Task Design (Command 1 of 3)

**No code. No application changes. No other task.** Stop after the design.

## 1. Establish the task — read narrowly

The current task is the first `READY` task in `docs/project/BACKLOG.md`.

Read only what the task needs. Do **not** read whole large documents, and do not re-read
anything already in context this session:

```bash
grep -n "READY" docs/project/BACKLOG.md | head            # find the task
sed -n '<start>,<end>p' docs/project/BACKLOG.md           # its Scope/Deps/Accept/Risk/DoD block
sed -n '/^## Current Phase/,/^## Current Milestone/p' docs/project/CURRENT_STATE.md
sed -n '/^## Current Task/,/^### Previously/p' docs/project/CURRENT_STATE.md
```

`docs/project/BACKLOG.md` and `docs/project/history/` are large. Grep them; never cat them.

Then read, **only if the task touches them**: the owning phase plan
(`docs/project/PHASE_<n>_PLAN.md`), the specific ADRs the backlog entry names, the relevant
sections of `docs/architecture/` and `docs/domain/`, and the `INV-*` entries in
`docs/domain/FINANCIAL_INVARIANTS.md` the task must protect. Inspect the existing code and
tests in the owning module before designing anything.

## 2. The design — all 23 points

1 Objective · 2 Business/domain meaning · 3 Bounded context · 4 Aggregates/entities/VOs ·
5 Lifecycle and transitions · 6 Business invariants · 7 Data model · 8 DB constraints and
indexes · 9 Transaction boundaries · 10 Consistency model · 11 API contracts · 12
Events/messaging · 13 Idempotency · 14 Concurrency · 15 Multi-instance behaviour · 16 Failure
scenarios · 17 Security/authorization · 18 Audit · 19 Reconciliation (or `N/A`) · 20
Observability · 21 Testing strategy · 22 Acceptance criteria · 23 Explicitly out of scope.

## 3. Mandatory distributed-system analysis

Assume **at least 10 concurrent instances**. Address explicitly: concurrent requests · races ·
lost updates · duplicate commands, events and webhooks · retries · timeout-then-retry ·
restart · database isolation level · stale reads · distributed scheduling · partial failure.

Never rely on JVM-local locks, `synchronized`, in-memory or static state, process-local
idempotency, or any single-instance assumption. If existing code or documentation is
incompatible with this, identify it **now**, before implementing — do not preserve an unsafe
design because it already exists.

## 4. Mandatory financial analysis

Walk the chain, or state `N/A` with the reason:
economic event → domain operation → financial transaction → journal entry → debit/credit lines
→ resulting balances → invariants protected → reversal path → settlement → reconciliation.

## 5. Output

Proposed design · trade-offs · risks · files and modules expected to change · tests required.

Explain the domain and the design reasoning first — the role is principal architect and
mentor, not autonomous code generator.

**Stop after the design.**
