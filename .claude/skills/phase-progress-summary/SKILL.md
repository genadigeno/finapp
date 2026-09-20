---
name: phase-progress-summary
description: The required layout for a phase or project progress summary - header bar, milestones, epics, per-task tables for the current and every upcoming milestone, invariants, findings, open items. Use whenever the owner asks "where are we", "phase progress", or for a project status summary.
---

# Phase Progress Summary

Always use this layout. It is read to see, at a glance, the proportion done, which milestone is
active, and which invariants are actually enforced — a prose summary buries all four.

## Gather real numbers first — never recall them

```bash
grep -c "— \`COMPLETE\`" docs/project/BACKLOG.md          # and READY / TODO / BLOCKED
ls docs/adr/ADR-*.md | wc -l
find . -name "V*.sql" -path "*migration*" | wc -l
ls */build/test-results/*/*.xml 2>/dev/null               # counts, from a fresh run only
git status --short
```

## Emit, in this order

1. **Header** — `Phase N — <name>`, status line with the entry-gate date, a fenced ASCII bar
   `███░░░  done / total  (pct)`, then one line each: ✅ test counts + build/tree state ·
   📐 ADRs · 🗄️ migrations · 🔐 business capability. Add an italic note if the total changed.
2. **🎯 Milestones** — table: status emoji (✅ 🔨 🟡 ⬜), name, `█████░░░░░ n/m` mini-bar, short
   note. Footnote anything qualified.
3. **📦 Epics** — table: emoji, `n/m`, `← here` on the active one. One line beneath on how many
   are closed.
4. **🔨 Where the work is** — a per-task table for the active milestone **and one for every
   milestone still ahead of it**, each under its own `🔨 M<n> — <name>` heading, in order. Rows
   carry ✅/🚧/⬜, the task id, and a terse consequence. `← next` marks the next task overall.
   Closed milestones stay collapsed in the 🎯 table — only current and upcoming get task tables.
5. **🔒 Invariants mechanically enforced** — group, status, and what enforces it. State the
   count enforced against the catalogue total, and whether that is correct for this point.
6. **🧠 Findings** — blockquotes; the genuinely non-obvious ones from the phase.
7. **⚠️ Open** — recorded defects and debt. End with `Blockers: none` when true.

Emoji are row markers, not decoration.
