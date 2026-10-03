/**
 * The customer's instruction to pay a beneficiary abroad, and the corridor rules governing it -
 * and never the price or the execution.
 *
 * <p><strong>What belongs here.</strong> The versioned, four-eyes Corridor Policy with its
 * availability; the Cross-Border Beneficiary, known by an opaque provider reference, its
 * country, currency and entity type, never a name ({@code INV-RAIL-03}); the Corridor
 * Selection; the Cross-Border Payment with its history - never reversed ({@code INV-REV-03}),
 * and what the customer was shown is exactly what is held, posted and instructed
 * ({@code INV-XB-03}); and the Cancellation Request, born once, never updated (ADR-0079).
 *
 * <p><strong>What never belongs here.</strong> The quote, the trade and the cover are
 * {@code fx}'s; the outbound credit is {@code payments}'; the beneficiary's screening decision
 * is {@code kyc}'s, mirrored here, never this module's own check (ADR-0081). There is no build
 * edge to any of them: crossborder decides, fx prices and books, payments executes, and every
 * hand-off goes through a port this module declares and {@code app} composes.
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema floor
 * ({@code P9-TSK-001}): {@code REVOKE ALL FROM PUBLIC}, {@code USAGE} alone to
 * {@code finapp_app}, no tables, no default privileges - so every later table's grants are
 * explicit. Corridors are {@code P9-TSK-015}'s.
 */
package com.finapp.crossborder;
