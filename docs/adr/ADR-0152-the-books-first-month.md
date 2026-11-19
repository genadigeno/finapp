# ADR-0152 — The books' first month: how many months before today the first accounting period opens, read only while none exists

Status: Proposed
Date: 2026-11-19
Phase: 17
Context: Accounting (periods) · the Operator Console's end-to-end seed
Rests on: ADR-0113, ADR-0114 (periods, the lock and the close); `P14-TSK-005` (`V003`'s contiguous periods); the owner's decision of
2026-11-19 on `P17-TSK-112`; `BACKLOG.md` `P17-TSK-113`.

## Context

Accounting periods are born only by the platform: the schedule's open leg calls `AccountingPeriods.openNext`, which opens the month
after the latest period, **today's month when none exists**, through today's month (`P14-TSK-005`). No door creates one, `V003`'s
trigger refuses a month before the first or past the month after the latest, and a period is lockable only from its last day plus at
least two business days (`LockGrace`).

So a fresh database holds exactly one month, the current one, and it cannot be locked until the month after. Every finance act that
needs a past month - a lock, a valuation rate set, a close by four eyes, a prior-period adjustment, a regulatory return - is
unreachable on a fresh stack. The Operator Console's M5 end-to-end tests need exactly those, against a running platform
(`P17-TSK-112`), and no seed can make them: periods are not a seed's to insert, and a plant may not move a period's state.

A real platform meets the same question once: the first month it keeps books for. A platform taking over books kept elsewhere starts
them at the month it takes over, not at the month its software first ran.

## Decision

1. **A setting names how many months before today's the books open**: `finapp.accounting.period.books-open-months-back`
   (`FINAPP_ACCOUNTING_PERIOD_BOOKSOPENMONTHSBACK`), `0` by default - the behaviour so far - and `0` to `24` (`BooksOpening`; a value
   outside refuses to start). When no period exists, `openNext` opens the first that many months before today's month, then every
   month through today's, each `OPEN` at revision 1 and evented and audited as any opened month is.
2. **It is read only while no period exists.** Once any period exists the next one is the month after the latest, whatever the
   setting says, and `V003`'s trigger refuses a month before the first: the setting cannot open, reopen or reach a month behind
   books already kept. Changing it later changes nothing.
3. **Nothing else moves.** A past month opened this way is an ordinary `OPEN` month whose grace has passed: it locks, closes and is
   corrected by the same doors, under the same four eyes, in the same order. It holds nothing until something is posted in it, and
   its close needs what any month's does - reconciliation runs for every required source, an approved valuation rate set, the
   pinned GL mapping version.
4. **Test stacks set it; production does not need to.** A local stack started on a fresh database with `2` holds two past months
   for the console's end-to-end seed (`P17-TSK-112`). The kind rehearsal keeps `0`: its FX reference feed observes only the present,
   and the database keeps only an observation newer than the pair's latest, so a past month's valuation rate set cannot be captured
   there anyway.

## Consequences

- **Multi-instance.** Every instance reads the same configuration. On an empty table ten instances each start at the same first
  month and insert each month if absent; `V003`'s unique and contiguity rules decide, as they did for today's month. An instance
  configured differently that inserts today's month first makes its siblings' earlier inserts refused by the trigger: the books then
  start at today's month - the default's behaviour, never a gap - and the next tick opens nothing further.
- **What it does not do.** It creates no period by a door, backdates no posting (a posting date is still the poster's, judged by the
  ledger's cutoff) and adds no migration. A production deployment leaving it unset behaves exactly as before.
- **The bound.** Twenty-four months: each month opened must be locked and closed in order before the next, so a typo of years would
  leave years of months to close.

## Alternatives rejected

- **Planting past periods in the seed.** The trigger refuses a month before the first, and the seed's plants may not change a
  period's state (`E2eSeedStaysTestOnlyTest`).
- **A test-only clock for the running stack.** `ShadowClock` shifts the database's date for one test JVM through superuser DDL; a
  running stack's clock is the database's, and moving it would move every judgement on it.
- **Requiring a stack whose database first ran in an earlier month.** It never holds on a fresh stack, which is where the console's
  tests run.
