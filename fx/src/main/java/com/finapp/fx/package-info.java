/**
 * Currency conversion at a server-authoritative, time-bounded, single-use price, and the
 * platform's own FX exposure - and never the instruction to pay abroad.
 *
 * <p><strong>What belongs here.</strong> The versioned, four-eyes Pricing Policy with its pair
 * and provider availability; the Exchange Rate snapshot - the independent reference, used for
 * plausibility and disclosure only, never executable, failing closed when stale; the FX Quote,
 * a frozen posting plan carrying its whole rate provenance ({@code INV-FX-01},
 * {@code INV-FX-02}, ADR-0075); the FX Trade, which posts exactly that plan in the acceptance's
 * own transaction (ADR-0076); and the FX Cover, sent back-to-back to the provider exactly once
 * however it answers (ADR-0077). {@code FX_POSITION} is the ledger's account per currency,
 * explained by this module's proof - never a table here.
 *
 * <p><strong>What never belongs here.</strong> Corridors, beneficiaries and the payment abroad
 * are {@code crossborder}'s (ADR-0079): crossborder decides, fx prices and books, payments
 * executes. There is no build edge between the two modules in either direction; every
 * hand-off goes through a port {@code app} composes. Postings are commanded through the
 * ledger's API and never written here ({@code INV-LED-04}).
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema floor
 * ({@code P9-TSK-001}): {@code REVOKE ALL FROM PUBLIC}, {@code USAGE} alone to
 * {@code finapp_app}, no tables, no default privileges - so every later table's grants are
 * explicit. The conversion arithmetic is {@code P9-TSK-002}'s.
 */
package com.finapp.fx;
