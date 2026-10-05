# ADR-0076 — Multi-currency accounting through `FX_POSITION`: the quote is a frozen posting plan

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: FX · Ledger · Accounts · App
Supersedes: nothing. Resolves `DELIVERY_PLAN.md` §Phase 9.14's anticipated "ADR on
multi-currency accounting and revaluation" (revaluation itself is deferred to Phase 14, D30).
Carries ADR-0002 (the ledger as the authoritative record) and ADR-0009 (balances as projections)
into two-currency entries, and pays ADR-0040's chart seam with five new operational purposes and
the one-wallet-per-currency rule. The arithmetic that produces every amount here is ADR-0074's;
the quote lifecycle is ADR-0075's; the provider leg that closes the position is ADR-0077's.

## Context

A conversion is one economic event in two currencies. The ledger already balances **per
currency** within an entry (ledger `V004`'s deferred trigger), so the question Phase 9 must
answer is not "can an entry hold two currencies" but **what stands between them**. Nothing may
convert inside a line (`INV-MON-04`), nothing may total across currencies, and every balance
must remain explainable from authoritative records (CLAUDE.md rule 11). The forces:

1. **The platform is principal** (D1, owner decision O1). The customer's conversion books when
   the quote is accepted, in one local transaction with no provider call in it; the
   back-to-back cover runs separately (ADR-0077). So between booking and cover execution the
   platform *holds a position*, and that position must be a ledger fact, not a plug.
2. **Execution must not re-price.** If the amounts posted at execution could differ from the
   amounts quoted, the gap would be invisible margin or invisible loss, and `INV-XB-03`'s
   "what was shown is what is posted" could not hold for cross-border.
3. **Value must be conserved at volume.** Ten thousand conversions must leave the trial
   balance zero in all five currencies, every position zero at rest, and every revenue and
   residual line equal to its trade's stored parts — provable, not assumed.
4. **A multi-currency customer needs an account per currency** — ADR-0040 keyed every ledger
   account by one currency, and that stands. The product question is how a wallet in a new
   currency comes to exist, race-free, on every path that needs it.
5. **Free adjustments are how books rot.** Phase 8 closed the reconciled positions to `MANUAL`
   lines; the FX books need the same protection, but joining `reconciledPositions()` itself
   would break completeness (every conversion line would read unattributed, since no settlement
   expectation explains a customer-side line).

## Decision

1. **The quote is a frozen posting plan** (D7). Every amount the trade will post — customer
   legs, position legs, margin with its attribution, residual — is computed once at quote time
   (ADR-0074) and frozen on `fx.quote`: a freeze trigger guards everything except the lifecycle
   columns, and a **per-currency plan-identity `CHECK`** holds for every writer
   (`fixed_side='SOURCE'` ⇒ `customer_source = position_source` ∧
   `position_destination = customer_destination + margin + residual`; the destination-fixed
   mirror likewise). With `UNIQUE (fx.trade.quote_id)`, a value-creating plan is unstorable and
   a plan is executable at most once. **Execution posts the plan and never re-prices**
   (`INV-FX-04`).
2. **The platform is principal; the customer's effect is final on posting** (D1, D12). A wallet
   conversion commits acceptance, trade and posting in one transaction — five distinct records
   (quote edge, trade, entry, cover row, later settlement allocations), never collapsed into
   one. No provider port is reachable from the conversion transaction (`INV-FX-09`, held by a
   static rule): a provider's `UNKNOWN` can never reach a customer balance. For cross-border,
   the one entry posts at the corridor provider's acceptance (ADR-0079); until then the
   customer waits under a hold, which is not a posting (ADR-0048).
3. **`FX_POSITION` is the account between the currencies, kept ASSET/DEBIT with its sign
   defined** — decided now, before its first line freezes the type (`INV-LED-06`). Per
   currency, DR − CR = the amount of that currency the open legs will *receive* from covers; a
   credit balance is an amount to be *delivered*. The conversion entry (posting key
   `fx-trade:<tradeId>`) credits `FX_POSITION` in the source currency with the plan's source
   position leg and debits it in the destination currency with the provider's stated counter
   (ADR-0074 point 3); the cover entry (`fx-cover:<coverId>`, ADR-0077) closes exactly those
   legs. **`FX_POSITION` is 0 at rest, per currency** — not revalued, not plugged, explained
   trade by trade.
4. **The revenue and result purposes are explicit and never netted.** `FX_SPREAD_REVENUE`
   (REVENUE/CREDIT) takes the one margin line, attribution stored (ADR-0074 point 4;
   `INV-FX-03`). `FX_REALISED_GAINS` (REVENUE/CREDIT) and `FX_REALISED_LOSSES` (EXPENSE/DEBIT)
   take only the difference when a cover or unwind executes off the plan, in that leg's
   currency, and are never netted with each other (the `RECONCILIATION_GAINS/LOSSES`
   precedent). `ROUNDING_RESIDUAL` (EXPENSE/DEBIT, existing) gains its **first production
   poster**: the conversion's bounded residual line, either sign (ADR-0074 point 5,
   `INV-BAL-03`). All are OPERATIONAL, one per currency, seeded below the UUIDv7 ceiling.
5. **`closedToFreeAdjustments()` is a separate set from `reconciledPositions()`.**
   `AccountPurpose.closedToFreeAdjustments()` = `reconciledPositions()` ∪ {`FX_POSITION`,
   `FX_SPREAD_REVENUE`, `FX_REALISED_GAINS`, `FX_REALISED_LOSSES`, `ROUNDING_RESIDUAL`}. The
   counterparty clearings join `reconciledPositions()` itself, because they open expectations
   (ADR-0078); **`FX_POSITION` and the P&L purposes must not** — completeness walks every line
   on a reconciled position and would report every conversion line unattributed
   (`PositionProof`). Each ledger migration that grows the set (`V020`, `V022`, `V023`,
   `V024`) restates `V015`'s binding trigger from the union. A `MANUAL` line on any of these is
   refused at the domain and by the trigger (`422 ledger.AdjustmentOnReconciledPosition`);
   `ReversalService` mirrors and reconciliation-origin resolutions remain admitted as today.
6. **One wallet account per currency, opened race-free in the caller's transaction** (D28). A
   wallet product holds n `CUSTOMER_WALLET` accounts, unique on `(owner_ref, purpose,
   currency)`. Every resolver is keyed by currency (`WalletsAreResolvedByCurrencyTest`: a
   static rule forbids `findFirst()` on wallets). A conversion, a return or the add-currency
   door opens a missing wallet inside the caller's transaction by
   `INSERT … ON CONFLICT (owner_ref, purpose, currency) DO NOTHING RETURNING id` followed by a
   re-read — a racing loser waits for the winner's commit, reads its row, and never aborts —
   and the act whose insert returned the row writes `accounts.WalletCurrencyAdded`, once,
   whichever path opened it.
   *As built (`P9-TSK-004`):* the one door is `accounts.WalletAccounts.openIfAbsent`, which first
   reads the agreement `FOR SHARE` with the ownership predicate in the statement — openers do not
   block each other, and an opener and a close (`FOR UPDATE`) serialise both ways, so no wallet is
   opened under an agreement being closed (a race the original wording did not name); the first
   currency at opening goes through the same door, so every wallet account is announced exactly
   once. A resolver asked for a currency the product does not hold answers its **first-opened**
   wallet (`created_at`, then id) with that wallet's own currency, so each flow's existing
   currency judgement refuses with the real account — a transfer's committed
   `FAILED(CURRENCY_MISMATCH)` row must carry real accounts — and `CURRENCY_MISMATCH` now means
   exactly "a side holds no wallet in this currency".
   *As built (`P9-TSK-009`):* the conversion uses the customer's ONE live WALLET agreement - the
   schema's `customer_account_one_live_per_customer_product` makes it unique - so the request is
   `{quoteId}` alone; the source wallet must exist in exactly the sold currency
   (`fx.SourceWalletMissing`), the destination opens through `WalletAccounts.openIfAbsent` inside
   T-a, and a refusal after it rolls the opened wallet back.
7. **The books are proven, report-only** (`PHASE_9_PLAN.md` §12.9.4). **The FX books proof** (`fx.FxBooksProof`,
   `INV-FX-06`), per currency `c`, in one `REPEATABLE READ` snapshot with `Money` folds:
   - `FX_POSITION(c)` DR−CR = Σ over every quote of its plan's position legs in `c` ×
     (booked trade − executed `COVER` + executed `UNWIND` − reversal) — at rest every term
     cancels to 0, and in flight the gap is explained as "executed covers whose quote is not
     yet `EXECUTED`";
   - `FX_SPREAD_REVENUE(c)` CR−DR = Σ margin of booked, unreversed trades;
   - `ROUNDING_RESIDUAL(c)` DR−CR = −Σ residual of booked, unreversed trades;
   - `FX_REALISED_GAINS(c)` / `LOSSES(c)` = Σ realised results of executions in `c`.

   **`FxPlanVerification`** recomputes every trade's plan from its stored columns alone through
   the pure function and compares it with the posted entry line by line, and the recomputed
   internal rate and disclosed margin with the stored ones (ADR-0068 §9's replay discipline
   applied to FX). Both are report-only and never repair; a divergence is CRITICAL, flips the
   verdict gauge and alerts. `FxBooksHaveOnePosterTest` admits only `fx`'s line composers (and
   its proofs as readers) to name the five purposes, on the `CashAtBankHasOnePosterTest` model.
8. **No revaluation, no functional currency, no unrealised P&L, nothing totalled across
   currencies** (D30). Covered positions are zero at rest; realised results come only from
   slipped covers and unwinds (ADR-0077). `BalanceDisplay` answers one balance per account, so
   per currency; the API never sums across currencies (an indicative total would use a
   non-executable rate as a fact); `TrialBalance` iterates every currency, now eager for five.
   Revaluation and a reporting currency are Phase 14's (DELIVERY_PLAN §18).
9. **Hot rows are accepted and ordered.** Every conversion updates the `FX_POSITION`,
   `FX_SPREAD_REVENUE` and `ROUNDING_RESIDUAL` projection rows of its two currencies — a
   per-currency serialisation point, correct by the projection's row lock (ADR-0009,
   ADR-0039). Postings stay last in every transaction; multi-entry transactions call
   `PostingService.lockBalancesInOrder`. The storm records the p99 lock wait, and the scale-out
   path — sub-accounts by owner, ADR-0041's mitigation using the counterparty mechanism's
   shape — is recorded for Phase 16, not built.

## Alternatives Considered

### Re-price at execution (compute the entry when the trade books)
Pros: no frozen plan to store; the freshest provider data.
Cons: what the customer accepted and what posts could differ, and the difference is invisible
margin; replay verification has no oracle; `INV-XB-03` becomes unprovable. The frozen plan makes
a value-creating entry *unstorable* rather than merely untested. Rejected — this is the
strongest idea the accounting candidate design contributed (D7).

### Cover first, book the customer after the provider executes
Pros: the platform never holds a position.
Cons: the customer's balance waits on a provider, and a provider `UNKNOWN` (ADR-0077) leaves
the customer in limbo — exactly what `INV-FX-09` forbids. ADR-0046's no-transaction-across-a-
provider-call rule would also force a held, half-booked intermediate state with no business
meaning (D1, D12).

### Revalue `FX_POSITION` at a current rate
Pros: the books show a mark-to-market picture.
Cons: Phase 9 positions are covered back-to-back and zero at rest, so there is nothing real to
revalue; an unrealised P&L line would be the first posting in the platform whose amount no
economic event fixed. Phase 14 owns revaluation with a functional currency (D30).

### `FX_POSITION` and the P&L purposes join `reconciledPositions()`
Pros: one set, one trigger.
Cons: completeness (`PositionProof`) walks every line on every reconciled position and knows
lines by expectations, suspense items and recorded entries — a conversion's customer-side lines
have none of those, so every conversion would read unattributed forever. The slices design made
exactly this mistake; the separate `closedToFreeAdjustments()` set gives the same write
protection without poisoning completeness.

### Refuse conversion into a missing wallet (`422 DestinationWalletMissing`), or open it in a separate transaction
Pros: simpler resolvers; no in-transaction DDL-adjacent write.
Cons: the refusal makes the commonest first conversion a two-step chore for every customer; the
separate transaction leaves an opened wallet with no conversion after a crash between the two,
and a plain `INSERT` under the unique aborts the racing loser's whole transaction. The
open-if-absent-then-re-read shape converges ten racers on one account with no abort (D28).

### A `balance = balance + amount` wallet with a currency column
Cons: listed only to refuse it by name — it is CLAUDE.md rule 5 and ADR-0002/ADR-0009
violated at once. Balances stay projections of journal lines; conversion is an entry.

## Consequences

Positive:
- A conversion's every amount is decided once, frozen, `CHECK`-guarded and executed at most
  once; the posted entry equals the accepted plan by construction and by replay.
- `FX_POSITION` turns "rate risk" into an auditable ledger fact with a defined sign, zero at
  rest, explained per trade — and the books proof flips loudly when any writer disagrees.
- The platform's revenue (spread, markup), rounding cost and execution slippage are four
  separately posted, separately explainable figures, never netted, never hidden in a rate.
- Multi-currency wallets arrive with deterministic resolution on every flow and a counted
  ten-racer convergence proof.

Negative:
- The quote row is wide: the full plan, both derived figures and three rounding names are
  frozen copies. That is the price of replay; storage is cheap, unexplainable books are not.
- Two more projection hot rows per currency per conversion (position and revenue) join the
  wallet rows — a real serialisation point per currency, measured by the storm, with the
  scale-out path recorded, not built.
- The one-wallet-per-currency rule touches every resolver in `transfers`, `payments` and
  `checkout`; the static rule and the re-run Phase 7 storm and dispute battery guard the
  migration of behaviour.

Operational impact: `finapp.fx.proof{purpose}` (currencies failing the books proof, must be 0),
`finapp.fx.plan.verdict` (1 clean / 0 diverged, CRITICAL log on divergence),
`finapp.fx.trade{pair, outcome}`, `finapp.fx.residual{currency, direction}` — counts and
verdicts only; position, spread, residual and P&L *amounts* are audited operator reports
(ADR-0072, D32). `finapp.ledger.trial.balance{currency}` is eager for five currencies.
Security impact: the five FX purposes are closed to free adjustment at the domain and by an
every-writer trigger; one static poster rule names who may touch them; the add-currency door is
an audited customer act under the existing `ACTIVE` gate (`INV-KYC-05` unchanged).
Financial impact: customer balances change at the acceptance commit and are final on posting;
margin and residual are recognised in the same entry; the platform carries the open position
knowingly, covered by ADR-0077, and the books proof prices that interim state at exactly its
stored legs — never at a market rate.

## Invariants / Constraints

`INV-FX-01` (amended: Enforce gains `STATIC` one-poster and the FX books proof), `INV-FX-04`
(new: the quote as a frozen, balanced-per-currency posting plan, executed at most once by its
owner, posting exactly its stored amounts), `INV-FX-06` (new: per currency `FX_POSITION` equals
the sum of its open legs and is zero at rest; spread, residual and realised results equal their
trades' stored parts; one poster, no free adjustment), `INV-FX-07` (the residual's own line,
ADR-0074), `INV-FX-09` (new: a customer's booked conversion never waits on, and is never changed
by, a provider outcome), `INV-BAL-03` (amended: the FX residual's destination), `INV-BAL-04`
(holds judged under the wallet lock), `INV-ACC-01` (zero per currency across five),
`INV-MON-04` (nothing converts inside a line), `INV-LED-01`, `INV-LED-04` (fx composes lines;
only the ledger writes journal rows), `INV-LED-06` (the position's type decided before first
use), ADR-0048 (a hold is not a posting).

## Follow-up

- `P9-TSK-004`: multi-currency wallets — the add-currency act, `openIfAbsent` under D28's
  shape, currency-keyed resolvers, `WalletOpenIfAbsentRaceDatabaseTest` (ten racers, one
  account, one event).
- `P9-TSK-009`: wallet conversion — `fx V006` (`trade` with copies, the equality and freeze),
  ledger `V020` (`FX_SPREAD_REVENUE`, the binding restated), the first production posters of
  `FX_POSITION` and `ROUNDING_RESIDUAL`, `FxBooksHaveOnePosterTest`,
  `NoProviderPortInConversionTest`, the entries `PHASE_9_PLAN.md` §12.4(a)/(d)/(e) posted exactly.
- `P9-TSK-012`: ledger `V023` (`FX_REALISED_GAINS`/`LOSSES`), the cover entry closing the
  plan's legs ± realised result (ADR-0077).
- `P9-TSK-013`: `FxBooksProof` and `FxPlanVerification`, each flipped by a plant; at rest
  `FX_POSITION` and `FX_PROVIDER_CLEARING` are 0. *(Built 2026-10-05: the books proof reads each FX book through ledger's `BalanceDerivation` against fx's own rows - trades' and executed covers' plan legs, margins, residuals, realised results - with the reversal and unwind terms zero until `-025`/`-021`; the replay recomputes each plan from the quote's frozen inputs through `ConversionPlan.compute` and compares the trade, the rates and the posted entry line by line; `FxSettledToCashDatabaseTest` proves the zero for a conversion carried to cash.)*
- `P9-TST-002`: ≥ 10,000 conversions over all 20 pairs and both fixed sides — trial balance
  zero in five currencies, the residual, margin and position identities exact, plan
  verification clean, golden replay of every quote.
- `P9-TSK-025` (owner decision O8: cut second, after M9.8): the operator FX trade reversal
  through `ReversalService`'s exact mirror, the one admitted corrector of these books.
- Until `P9-TSK-004` lands, nothing in this ADR is implemented: every statement is the decided
  design, to be corrected by the tasks that build it.
- The Phase 9 review reads this ADR against the code before accepting it (`P9-DOC-001`).
