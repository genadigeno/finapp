/**
 * Exact monetary values and the representation primitives every module shares:
 * {@link com.finapp.sharedkernel.money.Money}, {@link com.finapp.sharedkernel.money.CurrencyCode},
 * {@link com.finapp.sharedkernel.money.RoundingPolicy},
 * {@link com.finapp.sharedkernel.money.ExchangeRate} and
 * {@link com.finapp.sharedkernel.money.CountryCode} (the last two since {@code P9-TSK-002},
 * ADR-0074 §§1, 10, which records ADR-0006's argument for them once).
 *
 * <p>The rules these types exist to make unbreakable, from
 * {@code docs/domain/FINANCIAL_INVARIANTS.md}:
 *
 * <ul>
 *   <li>{@code INV-MON-01} — no binary floating point anywhere on a monetary path
 *   <li>{@code INV-MON-02} — currency is always explicit; there is no default or ambient one
 *   <li>{@code INV-MON-04} — arithmetic across currencies is rejected, never coerced
 *   <li>{@code INV-MON-05} — the scale an amount was created with travels with it
 *   <li>{@code INV-MON-06} — overflow fails loudly rather than wrapping
 * </ul>
 *
 * <p>{@code INV-MON-03} (rounding is explicit and named): every operation that rounds takes a
 * {@link com.finapp.sharedkernel.money.RoundingPolicy} at the call site, and none has a default.
 * {@code ExchangeRate} ends each operation in at most one named rounding and offers no inversion
 * and no cross rate - an inverted rate is a rounding decision nobody named. *(Corrected
 * 2026-10-03 by {@code P9-TSK-002}: this paragraph said the package offered no operation that
 * rounds, and that rounding would arrive with {@code P0-TSK-010} - true when written, stale
 * since that task shipped {@code Money.of(…, RoundingPolicy)} and the allocations.)*
 *
 * <p>Nothing in this package performs I/O, depends on a framework, or knows about
 * persistence. That is what lets the financial kernel be tested without a container.
 */
package com.finapp.sharedkernel.money;
