# ADR-0003 — Monetary values are integer minor units with explicit currency and scale

Status: Accepted

Date: 2026-08-31

## Context

`CLAUDE.md` rule 1 forbids floating point for money and rule 2 requires explicit currency
and precision semantics. That constrains the choice but does not make it.

The remaining questions are real:
- Which exact-arithmetic representation — `BigDecimal` or integer minor units?
- How is it persisted?
- How are currencies with 0 minor units (JPY), 2 (USD, EUR) and 3 (BHD, KWD) all handled?
- How are values needing more precision than a currency's minor unit — interest rates, FX
  rates, per-unit prices — represented without contaminating the money type?

This must be settled before any table exists. Changing it after financial history exists
means migrating immutable records, which is the one thing the platform is designed to avoid.

## Decision

**Two distinct types.**

**1. `Money`** — exact, currency-scoped, used for every posting, balance, amount and fee.
- Backed by integer minor units (`long`).
- Carries an explicit `CurrencyCode` (ISO 4217); no default, no ambient currency.
- Carries the `scale` (minor-unit exponent) used at construction.
- Arithmetic across different currencies throws; it never converts or coerces.
- Overflow is rejected, never wrapped.
- Immutable, with no public mutator.

**2. `Rate` / high-precision decimal** — `BigDecimal` with a defined precision, used for
interest rates, FX rates and intermediate calculations.
- Converting a `Rate` result into `Money` is an **explicit, named rounding step** with a
  caller-specified rounding mode. It never happens implicitly.

**Persistence:**
```sql
amount_minor  BIGINT     NOT NULL
currency      CHAR(3)    NOT NULL
scale         SMALLINT   NOT NULL
```
`scale` is denormalised onto every monetary row rather than looked up from currency
configuration.

## Alternatives Considered

### Option A — `BigDecimal` everywhere, `NUMERIC(38,9)` in the database
Pros: One type; arbitrary precision; familiar in Java.
Cons: Scale is a property of the *value*, so `1.5` and `1.50` are not `equals` — a subtle
and recurring bug source. Comparison and hashing surprise developers. Nothing forces a
scale, so an unrounded intermediate can be persisted as if it were money. Slower and larger
than integer arithmetic at ledger volume.

### Option B — Integer minor units with currency, scale looked up from configuration
Pros: Exact, fast, compact; equality is value equality.
Cons: If a currency's minor-unit definition changes, or configuration is wrong, historical
amounts become misinterpretable. That breaks `INV-MON-05` and, worse, breaks it silently.

### Option C — Integer minor units with currency and stored scale (chosen)
Pros: All of Option B, plus a historical amount is self-describing and remains correctly
interpretable regardless of later configuration changes. `long` range (±9.2×10¹⁸) is far
beyond any plausible requirement, and overflow is rejected rather than wrapped.
Cons: Three columns per monetary value. `scale` is redundant with currency in the normal
case. Requires a separate type for high-precision rates — which is arguably a benefit,
since it forces rounding to be explicit.

### Option D — Store as strings
Pros: No precision loss.
Cons: No arithmetic or comparison in the database; no constraints; poor performance.
Rejected.

## Consequences

Positive:
- Money equality is value equality; `1.50 USD` equals `1.50 USD` unconditionally.
- Rounding cannot happen by accident — converting a rate result to money is a visible call.
- Integer arithmetic is fast and exact at ledger scale.
- Historical amounts remain interpretable independently of current configuration.

Negative:
- Three columns per monetary value; slightly more verbose schemas.
- Developers must consciously choose between `Money` and `Rate`. This friction is
  intentional.
- Currencies whose minor units change require care in reporting across the change.

Operational impact: A static architecture rule fails the build on any `float`/`double` in a
monetary path (`INV-MON-01`).

Security impact: None directly.

Financial impact: Foundational. Precision defects introduced here would be present in every
posting the platform ever writes.

## Invariants / Constraints

`INV-MON-01` through `INV-MON-06`; `INV-BAL-03` (allocation with zero residual).

## Follow-up

- Phase 0: allocation helper guaranteeing zero residual when splitting an amount.
- Phase 9: per-currency-pair rounding policy for FX; residual account treatment.
- Phase 11: day-count and interest rounding conventions.
- Revisit only if a requirement emerges for amounts exceeding `long` minor units.
