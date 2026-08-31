/**
 * Exact monetary values: {@link com.finapp.sharedkernel.money.Money} and
 * {@link com.finapp.sharedkernel.money.CurrencyCode}.
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
 * <p>{@code INV-MON-03} (rounding is explicit and named) is upheld here by omission: this
 * package offers no operation that rounds. Rounding and allocation arrive with P0-TSK-010,
 * as operations that require the caller to name a rounding mode.
 *
 * <p>Nothing in this package performs I/O, depends on a framework, or knows about
 * persistence. That is what lets the financial kernel be tested without a container.
 */
package com.finapp.sharedkernel.money;
