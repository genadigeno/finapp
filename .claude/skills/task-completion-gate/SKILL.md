---
name: task-completion-gate
description: Command 3 of the backlog task loop - the formal task completion gate. Use when the owner says "complete <ID>", "task completion review", or "run the gate". Returns COMPLETE or NOT COMPLETE, and on COMPLETE marks exactly one next task READY.
---

# Task Completion Gate (Command 3 of 3)

A formal review, not a summary. Its purpose is to **find the gap** — a gate that finds nothing
in a task of meaningful size is itself suspect.

**Skip CI/CD checks** (standing instruction: `no-ci-watching`).

## 1. Re-read the governance basis — narrowly

The task's backlog entry (acceptance criteria and DoD profiles), the `INV-*` entries it claims
to protect, and the ADRs it rests on. Inspect the implementation, tests, migrations, APIs,
events and configuration as they now stand.

## 2. Review areas

- **Functional** — every acceptance criterion in the backlog entry, individually.
- **Domain** — ownership, aggregates, lifecycles, rules, coupling, bounded-context integrity.
- **Distributed-system** — the ≥10-instance list from `task-design`. Answer explicitly:
  *"Would this remain correct if 10 instances executed it concurrently?"* → `PASS` /
  `FAIL` / `UNKNOWN`. **`FAIL` or `UNKNOWN` is NOT COMPLETE.**
- **Atomicity and consistency** — no atomicity claimed across a boundary that lacks it.
- **Idempotency** — duplicates cannot cause a second effect; proven, not asserted.
- **Financial** — if applicable: invariants, reversal, no value created or destroyed.
- **Security** — authentication, authorization (with a passing negative test), privileges,
  input validation, sensitive data, secrets, logging, abuse cases.
- **Audit and reconciliation** — actor, time, operation, target, reason, correlation, outcome.
- **Testing** — happy path, invalid input, concurrency, duplicates, retries, failure,
  security, persistence. **Run them**, from a fresh execution.
- **Architecture** — against `CLAUDE.md`, the architecture docs and the ADRs. Fix violations;
  do not preserve an unsafe design because it already exists.

## 3. Invariant demonstrations

Where the task claims to protect an `INV-*`, the protecting test must have been demonstrated
to **fail** when the invariant is deliberately broken, and recorded in
`docs/project/MUTATION_TESTING.md`. A missing row is a gap; a row whose demonstration was
inferred rather than performed is worse than a missing one.

## 4. Return

```
CURRENT TASK: <ID>
TASK STATUS: COMPLETE | NOT COMPLETE
CRITICAL:  …
IMPORTANT: …
MINOR:     …
FIXES PERFORMED: …
```

**If NOT COMPLETE:** fix, re-run the tests, repeat the gate. Do not advance.

**If COMPLETE:**
1. Mark the task `COMPLETE` in `docs/project/BACKLOG.md`.
2. Update `docs/project/CURRENT_STATE.md` (§Current Task and anything else that changed) and
   the relevant docs/ADRs. Append the narrative record to
   `docs/project/history/TASK_HISTORY.md` and the dated row to
   `docs/project/history/CHANGE_LOG.md` — **not** to `CURRENT_STATE.md`, which stays current.
3. Identify **exactly ONE** next unfinished task in the current phase and mark it `READY`.
   Do not implement it.
4. Commit in the same turn (`commit-after-successful-review`), with no
   `Co-Authored-By: Claude` trailer (`no-claude-co-author-trailer`).
5. End with:

```
CURRENT TASK: <ID> / TASK STATUS: COMPLETE / NEXT TASK: <ID> / NEXT TASK STATUS: READY
```
