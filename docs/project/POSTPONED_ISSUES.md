# Postponed Issues

Issues the owner has deliberately postponed until **after the project's phases are finished**.
Each was found during a phase's work, judged not blocking, and parked here rather than fixed in the
task that found it. None is a financial-correctness defect that can be accepted silently; anything of
that kind belongs in the owning phase's gate, not here.

This file is the work list for the post-phase clean-up. When an issue is fixed, mark it `FIXED` with the
commit and the date; do not delete the entry.

| ID | Status | Found by | Area |
|---|---|---|---|
| PI-001 | `POSTPONED` | P12-TSK-003 (2026-10-17) | lending — loan account opener |
| PI-002 | `POSTPONED` | Phase 12 → 13 transition gate, R2 (2026-10-22) | merchant — card-payment fee version in force on the JVM clock |
| PI-003 | `POSTPONED` | Phase 12 → 13 transition gate, R2 (2026-10-22) | merchant — Phase 6 reason columns unscreened for card and account numbers |

---

## PI-001 — Lending's loan account opener has no up-front judgement of existing accounts

- **Status:** `POSTPONED` (owner, 2026-10-17: "create a postponed issues md file and we will fix them after
  project phases finished").
- **Found by:** P12-TSK-003's completion gate, comparing `BnplAccounts` with its lending precedent.
- **Where:** `lending/src/main/java/com/finapp/lending/LoanAccounts.java` — `openFor(Connection, LoanId,
  CurrencyCode)` (~line 104).
- **What:** `LoanAccounts.openFor` converges the loan's six ledger accounts one by one through
  `createOrConverge` and judges each account only after converging it. It never reads the loan's existing
  accounts first. `BnplAccounts.openFor` (`bnpl/src/main/java/com/finapp/bnpl/BnplAccounts.java` ~line 107)
  reads the owner's existing accounts once (`LedgerAccountStore#findAllOwned`, no lock) and refuses before
  its first insert if any is mis-shaped, then judges each converged account again.
- **Consequence today:** a wrong-typed account is met only after earlier inserts in the same transaction (the
  whole transaction still rolls back — nothing is half-written); an account of the loan in another currency,
  or under a purpose outside the six, is never noticed by the opener, because the convergence key includes
  both the purpose and the currency. No production path creates such an account today, so this is a
  defensive gap, not a live defect.
- **Fix:** give `LoanAccounts.openFor` the same lock-free pre-read and refusal as `BnplAccounts.openFor`
  (keep the post-convergence judgement); add the matching hermetic cases to `LoanAccountsTest` and a
  database case to `LoanChartDatabaseTest`; probe it (pre-read disabled → the new case red).
- **Owner of the fix:** the post-phase clean-up.

## PI-002 — The card-payment fee version in force is chosen on the JVM clock

- **Status:** `POSTPONED` — recorded by the Phase 12 → 13 transition gate (2026-10-22) as out of its scope, at the
  coordinator's direction; **the owner confirms it as post-phase or schedules it** (the gate's owner list, N17). The
  debt row "Time decided across instances outside the session store" (`X-TSK-008`, `CURRENT_STATE.md` §Known
  Architectural Debt) already names this site and says "Phase 15 at the latest" and "the fee schedule is due before the
  next change to it"; whichever the owner chooses governs, and this entry is struck when either is paid.
- **Found by:** the gate's multi-instance audit (A3-M1) on merchant's BNPL pricing; repair R2 moved BNPL to the database
  clock and named the card side as left unchanged.
- **Where:** `app/src/main/java/com/finapp/app/checkout/CheckoutSessions.java` — `open` (~line 177) resolves
  `FeeSchedules.effectiveVersionFor(unitOfWork, merchant, Instant.now(clock))`, the instance's clock;
  `merchant/src/main/java/com/finapp/merchant/JdbcFeeScheduleStore.java` — `findEffectiveVersionFor` (~line 295) judges
  `v.effective_from <= ?` against that instant. The version is then pinned per payment (`PaymentFeePin`, merchant `V005`).
- **What:** two instances whose clocks disagree by Δ can, within Δ of a version's `effective_from`, price one session by
  the outgoing version and another by the incoming one, at the same database time. BNPL's pin now resolves at
  `clock_timestamp()` after its share locks and merchant `V012` binds `pinned_at` to `[transaction_timestamp(),
  clock_timestamp()]` (`BnplPricing`, `JdbcFeeScheduleStore.findEffectiveVersion`, R2); the card resolution does not.
- **Consequence today:** inside the skew window a card payment can be priced by a version not yet (or no longer) in force
  on the authoritative clock. The fee still conserves (`fee + net = gross`) and is explainable from its pinned version
  (`INV-MER-03`); it is the version choice, not the arithmetic, that can be off. No production merchant is priced yet.
- **Fix:** resolve the card version on the database clock after the session's locks, as R2 did for BNPL (and the same
  immediately-effective rule: a version effective at its creation is in force from its commit); add a trigger rank on the
  pin's instant like merchant `V012`'s; a regression case with the JVM clock skewed past a version boundary; probe it.
- **Owner of the fix:** the post-phase clean-up, unless the owner schedules it with `X-TSK-008`'s debt row (Phase 15 at
  the latest).

## PI-003 — Phase 6 merchant reason columns are not screened for card or account numbers

- **Status:** `POSTPONED` — recorded by the Phase 12 → 13 transition gate (2026-10-22) as out of its scope, at the
  coordinator's direction; **the owner confirms it as post-phase or schedules it** (the gate's owner list, N17).
- **Found by:** the gate's security audit (A5-F5) on merchant's BNPL reasons; repair R2 screened those and named the
  Phase 6 reasons as left unchanged.
- **Where:** four person-written reasons in `merchant`, each checked at most for presence and length:
  - API key revocation — `MerchantApiKeys` (~line 183), column `merchant.merchant_api_key_event.reason` (merchant `V003`);
  - card fee schedule assignment — `FeeSchedules` (~line 190), column `merchant.merchant_fee_schedule_event.reason`
    (merchant `V004`);
  - payout destination proposal — `PayoutDestination` (~line 378), column `merchant.payout_destination.proposal_reason`
    (merchant `V006`);
  - an operator's payout request — `MerchantPayouts` (~line 107), column `merchant.merchant_payout.reason` (merchant `V007`).
- **What:** every other reason door on the platform — settlement, reconciliation, credit, lending, bnpl and, since R2,
  merchant's BNPL enablement and BNPL fee schedule — passes `sharedkernel`'s `InstrumentShapes` and a PL/pgSQL twin as a
  `CHECK` (merchant `V012` added `merchant.holds_instrument_shape` and its helpers). These four do not.
- **Consequence today:** an operator could paste a card or account number into one of these reasons and it would be stored
  verbatim (and copied into `audit_record.reason`, handled at the `RESTRICTED-PII` ceiling). No written rule is broken —
  the audit record's reason is already at its ceiling — but the cross-cutting screening rule has four holes.
- **Fix:** call `InstrumentShapes` in each door (mapping the refusal to `422 api.ValidationFailed`, as `MerchantBnplDesk`
  does); a merchant migration adding `CHECK (NOT merchant.holds_instrument_shape(...))` on the four columns, `NOT VALID`
  if existing rows must be kept; the corpus-parity and raw-writer cases of
  `MerchantBnplEnablementDatabaseTest#everyBnplReasonColumnRefusesAnInstrumentShape` extended to them; probe each rank.
- **Owner of the fix:** the post-phase clean-up, or Phase 15's data-handling review if the owner schedules it there.
