# ADR-0074 — Conversion arithmetic: exact rates, one margin line, a proven residual

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: FX · Shared Kernel · Platform · Ledger
Supersedes: nothing. Pays ADR-0003's follow-up ("Phase 9: per-currency-pair rounding policy for
FX; residual account treatment") and resolves `DELIVERY_PLAN.md` §Phase 9.14's anticipated "ADR
on rounding policy". Records ADR-0006's argument for the two new kernel representation types
(`ExchangeRate`, `CountryCode`), once, the way ADR-0003 made it for `Money`. Where the amounts
this arithmetic produces are posted is ADR-0076's; where the rates come from and how long a price
lives is ADR-0075's.

## Context

Phase 9 converts money between currencies, and every later record — the quote's frozen plan
(ADR-0076), the trade, the cover (ADR-0077), the settlement expectations and the replay
verification — copies amounts this arithmetic produced. A defect here is not a bug; it is value
created or destroyed on every conversion, silently, at volume. The forces:

1. **Floating point is banned** (CLAUDE.md rule 1, `INV-MON-01`) and `Money` is `long` minor
   units with explicit currency and stored scale (ADR-0003). A rate is not a `Money`: it needs
   its own representation with its own precision semantics.
2. **Rounding is where conversion loses or invents value.** Each independently rounded amount
   (customer leg, margin) can disagree with the provider's stated amount by a fraction of a minor
   unit. Unbounded, those fractions are an unexplainable balance; folded into margin or the
   customer amount, they are hidden P&L (`INV-BAL-03`).
3. **Both directions are needed.** "Sell exactly 1,000.00 EUR" and "the beneficiary receives
   exactly 1,000.00 USD" are different fixed legs, and the second is the commonest cross-border
   need. The fixed-destination arithmetic divides, and a naive two-step division rounds twice.
4. **Currencies have 0, 2 and 3 minor units.** JPY (0) and BHD (3) join EUR, GBP and USD
   (`INV-ACC-01` amended). A JPY-source rate (JPY→BHD ≈ 0.0025) carries most of its information
   past the sixth decimal, so one rate scale cannot serve every pair.
5. **The arithmetic must be replayable.** `INV-FX-05` demands that every executed conversion's
   plan, internal rate and disclosed margin be reproduced exactly from stored inputs. That is
   only possible if every operation is deterministic and ends in a *named* rounding — never a
   database column's implicit truncation (`INV-MON-03`, `INV-HIST-04`).

## Decision

1. **`ExchangeRate` lives in `sharedkernel.money`** (D2). It is a record
   `(CurrencyCode source, CurrencyCode destination, BigDecimal value)` with `value > 0`,
   `source ≠ destination`, `precision ≤ MAX_PRECISION = 20` and `scale ≤ MAX_SCALE = 10`.
   Equality is by `compareTo`; the hash is taken over `stripTrailingZeros`. It has no Spring, no
   persistence and no policy, so `SharedKernelIsolationTest` is unchanged. ADR-0003 already
   decided that a representation primitive the whole platform shares belongs in the kernel, so
   ADR-0006's argument is made once, here, and not re-argued per module.
   - Operations, each ending in **one** named rounding: `exactProduct(Money s)` (an exact
     `BigDecimal`, refusing a currency other than `source`); `convert(Money s, RoundingPolicy p)`
     = `Money.of(exactProduct(s), destination, p)`; `sourceFor(Money d, RoundingPolicy p)` =
     one exactly-rounded division `d.toBigDecimal().divide(value, source.minorUnits(), p.mode())`,
     refusing a `d` not in `destination`; `marginAgainst(ExchangeRate other)`, the exact
     difference, same direction only.
   - **It has no inversion and no cross rate.** A direction is a different rate, obtained, not
     derived; an inverted rate is a rounding decision nobody named.
   - The column type `NUMERIC(20,10)` is generated from the two constants by a platform
     `RateColumns.ddl()` that migrations embed verbatim, asserted by a migration test (the
     `FeeRate` → `numeric(7,6)` precedent). An adapter refuses a provider rate with more than 10
     decimals; it never rounds one.
2. **Both fixed sides** (D3): `FIXED_SOURCE` (the customer sells exactly S) and
   `FIXED_DESTINATION` (exactly D is delivered), for conversions and cross-border alike (owner
   decision O3). Margin and residual always arise in the currency of the *computed* leg. The
   fixed-destination customer leg is **one exactly-rounded division** — never an inversion
   followed by a multiplication, which rounds twice.
3. **Position legs are the provider's coherent *stated* amounts** (D4). The provider's firm
   quote states rate `rp` and a counter amount; the plan's position legs copy the stated
   amounts, accepted only if coherent — strictly within 1 minor unit of the exact product
   (`|Dp − S·rp| < 1` minor unit of D), or, destination-fixed, of the quotient, judged by
   cross-multiplication (`|Sp·rp − D| < rp × 10^−m(S)`) so no division is needed. An incoherent
   quote is `PROVIDER_QUOTE_INCOHERENT` and the next provider is tried. A confirmed cover then
   closes `FX_POSITION` exactly (ADR-0077), and a provider's rounding never masquerades as
   market P&L.
4. **One margin line, with stored attribution** (D5). The customer rate is
   `rc = round(rp × (1 − (spread + markup)), rateScale, rateRounding)`, and the margin is
   computed **from the rounded `rc`** — so the rate rounding cannot hide margin — as one amount
   in the computed leg's currency (`M = round(S × (rp − rc), …)` source-fixed;
   `M = round(D × (rp − rc) ÷ (rc × rp), …)` destination-fixed, an exact numerator and
   denominator, then one division). It posts as one `FX_SPREAD_REVENUE` line (ADR-0076) and is
   attributed to spread and markup by `Money.allocateByWeights(spread×10⁶, markup×10⁶)` — the
   method's first production caller — with ties to the earlier part, and the attribution stored.
   - **The attribution is a stated convention.** It splits the whole posted `M`, which includes
     the rate-rounding gain `S × (rp × (1 − m) − rc)` — at most `S × 10^−rateScale`, under O7's
     scales at most 0.03 bps of the notional. The gain is derivable exactly from stored inputs,
     so Phase 14 can separate it with no new data.
   - **A pair needs `spread + markup > 0`** (the `pricing_pair` `CHECK`, and the function's own
     `MARGIN_UNATTRIBUTABLE` refusal): `allocateByWeights` refuses all-zero weights, and a
     zero-margin pair would hand it exactly that whenever truncation leaves `M > 0`. A negative
     margin is `MARGIN_NEGATIVE`, a defect, never a shipped price.
5. **The residual is proven bounded, `CHECK`-bounded, and posted** (D6). The residual is the
   integral remainder of independently rounded amounts: `r = Dp − Dc − M` (source-fixed),
   `r = Sc − Sp − M` (destination-fixed). From the exact identities
   `S·rp = S·rc + S·(rp − rc)` and `D/rc = D/rp + D(rp − rc)/(rc·rp)`, with coherence bounding
   the provider term strictly below 1 minor unit:
   - under **half policies** each rounding term is at most ½, so |r| < 2, and r being integral,
     **|r| ≤ 1**;
   - under **any named policy** each term is below 1, so |r| < 3, hence **|r| ≤ 2**.

   `fx.quote` and `fx.trade` therefore carry `CHECK (residual_amount_minor BETWEEN -2 AND 2)` —
   a universal bound no named policy can exceed, so no plan is unstorable — and the domain
   additionally asserts the active policy family's own bound (1 under half/half). The residual
   posts to `ROUNDING_RESIDUAL` in its own currency — CR when the platform kept the fraction, DR
   when it bears it, no line at zero — and is **never** folded into the margin, the customer
   amount or the position (`INV-BAL-03`, `INV-FX-07`). A residual beyond the policy's proven
   bound is `PLAN_INVARIANT_VIOLATED`, CRITICAL, and the quote is not issued.
6. **Rounding and rate scale are per pair, with no default** (`PHASE_9_PLAN.md` §12.2, O7). Each `fx.pricing_pair`
   names `rate_rounding`, `amount_rounding` and `margin_rounding` from `RoundingPolicy`
   (`INV-MON-03`: no default exists), stored as `policyName()` under text `CHECK` lists
   generated from the enum (the merchant `V004` pattern), and copied onto every quote and trade
   (`INV-HIST-04`). The per-pair `rate_scale` is at most `ExchangeRate.MAX_SCALE`; v1 chooses 10
   for the four JPY-source pairs and 6 for the other sixteen, `TOWARDS_ZERO` for the rate (a
   displayed rate never exceeds the priced one) and `HALF_EVEN` for amounts and margin —
   unbiased, so the residual random-walks around zero and drift is a defect signal.
7. **The two derived figures end in named roundings, never a column's.** Stored on the quote,
   neither ever posted:
   - **internal rate** = `round(rp × (1 − spread), 10, rate_rounding)` — the exact product can
     carry 16 decimals (rp at 10, spread at 6), so it is rounded to `MAX_SCALE` under the pair's
     own rate rounding, for Phase 14's split;
   - **disclosed margin over mid**, a fraction `NUMERIC(7,6)`: with the reference in the pair's
     direction, `round((ref − rc) ÷ ref, 6, HALF_EVEN)` — one exactly-rounded division; in the
     inverse direction, `round(1 − rc × ref, 6, HALF_EVEN)` — an exact product. No inversion is
     ever taken.

   Every rate column receives a value already at or below its scale, and the domain refuses one
   that is not.
8. **Notional bounds bind the fixed leg only**, in its currency, judged before any provider call
   (O7: EUR/GBP/USD 1.00–50,000.00; JPY 100–7,500,000; BHD 0.500–20,000.000). The computed leg
   must only be positive. Outside the bounds, or a computed leg ≤ 0, is
   `422 fx.AmountOutOfRange`.
9. **Minor units are pinned by a test and a startup guard** (D27). There is **no currency
   table**: minor units come from `CurrencyCode.minorUnits()`.
   `SupportedCurrencyMinorUnitsArePinnedTest` pins EUR 2, GBP 2, USD 2, JPY 0 and BHD 3, and
   `SupportedCurrencyMinorUnitsGuard` refuses startup if the running JDK disagrees — protecting
   `INV-MON-05` before `Money.plus` could throw `ScaleMismatchException` on history. What
   enables a currency is three distinct facts: `SupportedCurrencies` makes it *postable*, the
   active pricing policy's pairs make it *quotable* (ADR-0075), the active corridor policy's
   corridors make it *sendable* (ADR-0080).
10. **`Margin` and `CountryCode`.** `Margin` is `fx`'s: a fraction in `[0, 0.1)`, scale ≤ 6,
    `NUMERIC(7,6)`, whose attribution weight `value × 10⁶` is an exact `long`. `CountryCode` is
    a kernel representation primitive beside `CurrencyCode`: ISO 3166-1 alpha-2, validated
    against `Locale.getISOCountries()`, carrying no rule — the same kernel-type argument as
    point 1.
11. **The function is pure and total over its typed refusals.** `ConversionPlan.compute(fixedSide,
    fixedAmount, ProviderQuote, PricingPair)` returns a `Plan` or a typed refusal
    (`PROVIDER_QUOTE_INCOHERENT`, `MARGIN_NEGATIVE`, `MARGIN_UNATTRIBUTABLE`,
    `fx.AmountOutOfRange`, `PLAN_INVARIANT_VIOLATED`). Every input is stored on the quote, so
    replay reproduces the plan and both derived figures exactly (`INV-FX-05`) — the basis of
    `FxPlanVerification` (ADR-0076) and the golden replays.

**Implemented** (`P9-TSK-002`, 2026-10-03): points 1, 2-7, 9-11 as written - `ExchangeRate` and `CountryCode` in `sharedkernel.money`, `RateColumns.ddl()` in `platform`, `Margin`, `PricingPair`, `NotionalBounds`, `ProviderQuote` and `ConversionPlan` in `fx`, the pin `SupportedCurrencies.PINNED_MINOR_UNITS` with its test in `ledger` and `SupportedCurrencyMinorUnitsGuard` in `app`. The §12.2 figures, the internal rate 1.0812264160 and the disclosed margin 0.005162 are reproduced to the minor unit (`ConversionPlanTest`); a million seeded cases per policy family reach and never exceed the proven bound - 1 under half policies, 2 under directed ones (`ConversionPlanPropertiesTest`). Point 8's bounds are judged by the function too, so the pure plan refuses an out-of-range fixed leg on its own; Tx1's pre-check before the provider call is `P9-TSK-007`'s.

## Alternatives Considered

### `NUMERIC(24,12)`, or `ExchangeRate` owned by `fx`
Pros: more headroom; the type beside its only Phase 9 user.
Cons: JPY→BHD-scale rates (≈ 0.0025…) need no more than 10 decimals, and headroom nobody can
fill is precision the adapters would have to invent. In `fx`, every module that later carries a
rate (crossborder's offer, reporting) would need an edge to a sibling or a copy of the type —
exactly what ADR-0003 refused for `Money`. The kernel type with constants-generated DDL keeps one
representation and one column type everywhere.

### `SELL_FIXED` only
Pros: one arithmetic path, no division.
Cons: "the beneficiary receives exactly X" is the commonest cross-border need; without
`FIXED_DESTINATION` the client would iterate quotes to hit a destination amount, each iteration a
provider RFQ and a new price. Rejected as the distributed design's defect.

### Computing the fixed-destination leg via DECIMAL128 intermediates
Pros: library-default convenience.
Cons: a two-step divide-then-multiply rounds twice, and MathContext precision is not a stated
scale. One exactly-rounded division at the target's minor units is exact by construction.
Rejected as the accounting design's defect.

### Platform-computed cost leg (ignore the provider's stated counter)
Pros: no coherence check needed.
Cons: the cover would close the position legs only approximately, leaving `FX_POSITION` a
perpetual residue of sub-minor-unit noise that is nobody's P&L; a provider's rounding would read
as market slippage. Rejected as the slices design's defect.

### Two revenue lines (spread and markup posted separately)
Pros: the split is visible in the ledger itself.
Cons: a second independently rounded line adds a rounding term, loosening the residual bound of
point 5; the split is derivable and stored, so the ledger line count buys nothing. Rejected as
the distributed design's choice, D5.

### A residual `CHECK` of ±2 proven only for half policies
Pros: none over the decided form.
Cons: a later directed-rounding policy would produce a storable-in-theory, unstorable-in-practice
plan the moment the domain's bound and the `CHECK` disagree. The decided bound is proven
universal over every named policy. Rejected as the distributed design's defect.

### A currency table
Pros: currencies as data.
Cons: a second authority over minor units that can disagree with the JDK's and with history. The
pin test plus the startup guard protect the same fact with no second writer (D27).

## Consequences

Positive:
- Every conversion amount, margin, residual and attribution is deterministic and replayable from
  stored inputs; `FxPlanVerification` and the golden replays are possible at all.
- The residual is a proven, bounded, posted fact with both signs expected — drift is a defect
  signal, not noise.
- A provider cannot leak rounding into P&L (coherence), a client cannot supply precision the
  type refuses (`MAX_SCALE`), and a column can never round (values arrive at or below scale).
- The worked figures of `PHASE_9_PLAN.md` §12.2 give the build an exact, hand-checkable oracle: EUR→USD 1,000.00 at
  rp 1.085024 yields rc 1.079598, Dc 1,079.60, M 5.43 (3.80/1.63), r −0.01 — and the 0- and
  3-minor cases (USD→JPY, EUR→BHD, BHD→JPY) each land on the minor unit.

Negative:
- `sharedkernel` grows by two types, and the kernel's isolation test now guards them forever.
- Per-pair scales and roundings are four more columns of policy an operator can get wrong;
  mitigated by the enum-generated `CHECK` lists, v1's reviewed values (O7) and four-eyes
  activation (ADR-0075).
- The coherence refusal means a provider whose stated counters round differently than it quotes
  is unusable for the pair until fixed — deliberately: accepting it would move the error into
  the books.

Operational impact: refusal causes are counted on `finapp.fx.quote{outcome}`
(`refused_incoherent`, `refused_implausible`) and never shown to customers.
`finapp.fx.residual{currency, direction}` counts residual frequency; the amounts are the revenue
report's (ADR-0072, D32). A planted JDK minor-unit drift fails the build and refuses startup.
Security impact: no `double` on any path (`NoFloatingPointMoneyRulesTest` covers the new
modules); no request body anywhere carries a rate (`RatesAreNeverClientSuppliedTest`,
ADR-0075); over-precision is refused, never rounded, so an adapter cannot be steered into
inventing precision.
Financial impact: none posted by this ADR itself — it defines every amount the Phase 9 postings
copy. Residuals post at most 2 minor units per conversion either way, explained per trade.

## Invariants / Constraints

`INV-MON-01`…`-06` (the arithmetic is exact `BigDecimal` over `long` minor units, one named
rounding per figure, no default, explicit scale everywhere), `INV-FX-03` (amended: spread and
markup computed from the rates onto one attributed revenue line), `INV-FX-05` (new: replay
reproduces the plan and both derived figures exactly), `INV-FX-07` (new: the residual bounded,
`CHECK`-bounded and posted to `ROUNDING_RESIDUAL`, never folded), `INV-ACC-01` (amended: five
currencies at 0/2/3 minor units), `INV-BAL-03` (the residual's destination), `INV-HIST-04`
(rounding names as stored, versioned facts).

## Follow-up

- `P9-TSK-002`: `ExchangeRate`, `CountryCode`, `RateColumns.ddl()` with its test, `Margin`,
  `ConversionPlan.compute` with its refusals, coherence by cross-multiplication, the attribution,
  the per-pair rate scale, the two derived figures, the minor-unit pin test and startup guard,
  the stale `money/package-info.java` corrected — every `PHASE_9_PLAN.md` §12.2 figure reproduced exactly, property
  tests over 10⁶ cases per policy family.
- `P9-TSK-003`: JPY and BHD become postable; the thirteen operational purposes seeded per
  currency; Phase 6's deferred 0/3-minor fee batch paid.
- `P9-TSK-007`: the pricing policy stores the per-pair scales, roundings, bounds and
  `CHECK (spread + markup > 0)` this ADR requires (ADR-0075).
- `P9-TSK-008` and `P9-TSK-009`: the quote and trade rows carry the plan, the residual `CHECK`,
  the derived figures and the copied rounding names (ADR-0075, ADR-0076).
- `P9-TST-002`: the value-preservation and rounding battery — ≥ 10,000 conversions over all 20
  pairs and both fixed sides, every |r| ≤ 1 with both signs present, golden replay of every
  quote.
- Until `P9-TSK-002` lands, nothing in this ADR is implemented: every statement is the decided
  design, to be corrected by the tasks that build it.
- The Phase 9 review reads this ADR against the code before accepting it (`P9-DOC-001`).
