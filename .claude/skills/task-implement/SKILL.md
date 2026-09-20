---
name: task-implement
description: Command 2 of the backlog task loop - implement the approved design for the current task only. Use when the owner says "implement", "proceed", or approves a design. Stops after implementation and tests.
---

# Task Implement (Command 2 of 3)

**Only the current task.** No future-phase work. No next task. Stop after implementation and
testing.

## 1. Re-read the approved design

The design from Command 1, as agreed — including any corrections made during review.

## 2. Implement

Preserve architecture and bounded-context ownership. Enforce the invariants the design named.
Explicit transaction boundaries. Idempotent critical operations. Correct under 10 concurrent
instances. Handle retries, timeouts, duplicates and failures. Include the required
authentication/authorization, audit, reconciliation, observability and tests.

Make the smallest coherent change that satisfies the task. Do not refactor unrelated
subsystems or improve code the task did not require — record unrelated problems as backlog
items or architectural debt instead.

## 3. Run the relevant tests

Targeted tiers are sufficient during implementation. Read results from a **fresh** run — never
from stale `build/test-results` XML, and never report a count from a build that did not
execute. Confirm the build actually ran before reporting any verdict.

## 4. Inspect the complete diff

Check for: unrelated changes · architecture drift · single-instance assumptions · missing
tests · security regressions · documentation that now describes behaviour that does not exist.

## 5. Update the record

- `docs/project/BACKLOG.md` — the task's own entry
- `docs/project/CURRENT_STATE.md` — §Current Task, and any new blocker, debt or resolved
  question. Append historical narrative to `docs/project/history/`, not to `CURRENT_STATE.md`.
- ADRs and domain/architecture documentation where behaviour or architecture changed

**Stop after implementation and testing.** Do not run the completion gate unless asked.
