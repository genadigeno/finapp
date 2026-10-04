# Operations Runbook

Procedures an operator performs on a running platform, each with **when**, **why**, the exact
steps, how to **verify**, and what happens if it is skipped. A procedure belongs here when the
platform cannot do it for itself by design — usually because it is a four-eyes decision, which a
migration or a scheduler must never take on a person's behalf.

Every procedure below runs through the platform's own audited doors. None is a raw `UPDATE`.

---

## 1. Activating a currency's reconciliation terms (JPY and BHD) — before its first traffic

*(Added by `P9-TSK-003`, 2026-10-04; owner decision O6 in `PHASE_9_PLAN.md` §2; ADR-0074 §9;
decision D26.)*

**When.** Once, after a deployment carrying ledger `V019` (JPY and BHD postable), and **before**
the first JPY or BHD payment, report or statement reaches any of the four settlement sources.

**Why.** A currency being postable (`SupportedCurrencies`) gives it the ledger chart; it does not
give reconciliation its per-currency terms. Those live in each source's **rule set**, which is
versioned, pinned on every decision (`INV-HIST-04`) and never seeded by a migration (D26): a
version is a four-eyes decision. Until each source's successor carrying JPY and BHD rows is
**active**:

- a JPY or BHD break grades at **base** severity — no `high_value_minor` row exists for it;
- a JPY or BHD fee line meets **no fee schedule**, which `FeeCheck` prices at **zero** — so every
  genuine fee raises a `FEE_MISMATCH` break for its whole amount;
- the PSP's fee tolerances read **zero**.

None of this loses value — every line is still owned and explained — but it floods the desk with
false breaks. Hence: activate first, then admit traffic.

**What each successor carries.** The source's **whole** active version restated (a proposal is a
full version, never a delta: every rule, lag, tolerance, fee schedule and threshold of its
predecessor), plus, for JPY (scale 0) and BHD (scale 3):

| Row | Sources | JPY | BHD |
|---|---|---|---|
| Severity threshold (`high_value_minor`) | all four | `150000` | `400000` (= 400.000) |
| `PROCESSING_FEE` schedule (rate as EUR's, `HALF_UP`) | PSP | fixed `40` | fixed `100` |
| `SCHEME_FEE` schedule | scheme | fixed `15` | fixed `40` |
| `PAYOUT_FEE` schedule | payout | fixed `40` | fixed `100` |
| `BANK_FEE` schedule | bank | fixed `75` | fixed `200` |
| `PROCESSING_FEE_PER_LINE` tolerance | PSP | `3` | `10` |
| `PROCESSING_FEE_PER_BATCH` tolerance | PSP | `75` | `200` |

Each fee schedule's **scale is its currency's minor units** — 0 for JPY, 3 for BHD. The door
refuses any other (`RuleSetInvalid`, since `P9-TSK-003`): `FeeCheck` prices in raw minor units, so
a JPY schedule at scale 2 would read its fixed part a hundred times too large.

**Steps** — for each source (`simulated-psp.settlement`, `simulated-scheme.cycle-report`,
`simulated-payout.settlement`, `simulated-bank.statement`), two different people holding
`RECONCILIATION_ADMINISTER`:

1. **Read** the active version: `GET /v1/operator/reconciliation/rule-sets?source=<sourceId>`.
2. **Propose** (person A): `POST /v1/operator/reconciliation/rule-sets` with an
   `Idempotency-Key`, the full version from step 1 plus the rows above, and a reason naming this
   procedure. The answer is the proposal's id and version (`PROPOSED`).
3. **Approve** (person B — the proposer is refused, `RuleSetActivationBySameActor`, at the domain
   and by the database's `CHECK`): `POST /v1/operator/reconciliation/rule-sets/{id}/approval`.
   In one transaction the predecessor moves `ACTIVE → RETIRED` and the successor
   `PROPOSED → ACTIVE`; both acts are audited (`reconciliation.RuleSetProposed`,
   `reconciliation.RuleSetActivated`).

**Verify.** Step 1 again shows the successor `ACTIVE` with the JPY and BHD rows. Decisions made
before activation keep their pinned version and replay `IDENTICAL`; decisions after it pin the
successor.

**If a proposal is wrong.** Reject it (`POST …/rule-sets/{id}/rejection`, reasoned) and propose
again. Once active, a version is never edited — a further successor corrects it.
