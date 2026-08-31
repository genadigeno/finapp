/**
 * How a monetary value is stored, and read back unchanged.
 *
 * <p>{@link com.finapp.platform.money.MoneyColumns} is the single definition of the column
 * shape ADR-0003 fixed: integer minor units, an explicit currency, and the scale the amount
 * was written with. Every table holding money uses it, so the shape cannot drift from one
 * schema to the next.
 *
 * <p>This package sits in {@code platform} rather than {@code sharedkernel} because
 * persistence is mechanism, and {@code sharedkernel} is framework-free — a rule the ArchUnit
 * boundary tests enforce. {@code Money} itself knows nothing about being stored.
 *
 * <p>Invariants this package exists to hold:
 *
 * <ul>
 *   <li>{@code INV-MON-01} — integer minor units, never a floating-point column
 *   <li>{@code INV-MON-02} — the currency column is {@code NOT NULL}; there is no implied currency
 *   <li>{@code INV-MON-05} — a persisted amount round-trips to exactly the value written,
 *       including its scale, for every supported currency
 * </ul>
 */
package com.finapp.platform.money;
