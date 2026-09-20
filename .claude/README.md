# Claude Code configuration

## What loads, and when

| Layer | Loads | Contains |
|---|---|---|
| `CLAUDE.md` | **always** | The permanent constitution: mission, the non-negotiable financial rules, the mandatory multi-instance rule, domain distinctions, architecture/API/event/ledger/security principles, failure engineering, and a **pointer map** to everything else. |
| `.claude/rules/*.md` without frontmatter | **always** | Small global rules: financial invariants, security, testing. |
| `.claude/rules/*.md` with `paths:` frontmatter | when a matching file is touched | Domain rules: API design, Java/Spring, ledger, payments, lending/credit, reconciliation. |
| `.claude/skills/*/SKILL.md` | on demand | The procedures for working a backlog task: design, implement, completion gate, progress summary. |
| `docs/**` | on demand | Project knowledge. Read the *section* you need. |

**`CLAUDE.md` deliberately contains no `@imports`.** An `@import` is loaded into every session
whether or not the task needs it, and the documents worth importing are exactly the ones that
grow — so the import list silently becomes the context budget. Before 2026-09-20 the eight
imports totalled **1.38 MB (~346k tokens) per session**, of which `CURRENT_STATE.md` alone was
91%. It is now a pointer map.

## Where state lives

| File | Role |
|---|---|
| `docs/project/CURRENT_STATE.md` | Where the project **is**: current phase, milestone, task, next task, blockers, debt, open questions. Kept small. |
| `docs/project/BACKLOG.md` | The authoritative task backlog. Large — **grep it, never cat it**. |
| `docs/project/history/` | Where the project **has been**: task records, milestone records, completed capabilities, change log. Not loaded by default. |
| `docs/adr/` | Architectural decisions. |

Historical narrative is appended to `docs/project/history/`, never to `CURRENT_STATE.md` — that
is what keeps "current state" current.

## Two files are machine-parsed — do not reformat them casually

| File | Section | Parsed by |
|---|---|---|
| `CLAUDE.md` | `## Domain Distinctions` — 8 bullets verbatim | `DomainGlossaryTest` |
| `CLAUDE.md` | `## Failure Engineering` — 12 bullets verbatim | `ProviderFailureCoverageTest` |
| `docs/project/CURRENT_STATE.md` | `## Current Phase` — `**Phase N — …**` headings and `Status: …` `COMPLETE` lines | `MutationDemonstrationTest`, `PlannedMetersExistTest` |

Both files are declared `:app:test` inputs, so editing them re-runs the guards.

## Inspecting context

`/memory` shows loaded memory; `/context` shows context usage.

Keep global instructions concise. Put domain-specific requirements in path-scoped rules,
procedures in skills, and everything else in `docs/` behind a pointer.
