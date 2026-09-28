/**
 * External settlement evidence and its recognition — what the counterparties say happened, and
 * never what the platform expected.
 *
 * <p><strong>What belongs here.</strong> The SettlementFile — bytes verbatim, screened at the
 * door, encrypted under its own key with the associated data bound, its stored checksum equal to
 * the received bytes' ({@code INV-HIST-02}, ADR-0066) — the SettlementBatch with its control
 * totals, accepted whole or rejected whole and recognised exactly once from its own stored
 * evidence ({@code INV-SET-04}, ADR-0065), the canonical settlement lines and their references,
 * the refused deliveries (metadata only — {@code INV-PAY-02} takes precedence over evidence
 * retention for a PAN-bearing file), and the pull permits ({@code P8-TSK-021}, strictly
 * advancing on every renewal).
 *
 * <p><strong>What is structurally absent, and why the module exists in this shape.</strong>
 * Expectations, matching, breaks, suspense and resolutions — the platform's own derived state —
 * belong to {@code reconciliation} (ADR-0064), and there is <em>no build edge between the two
 * modules in either direction</em>: the module that preserves what the counterparty said must
 * not compile against the module that decides what the platform expected, or acceptance starts
 * peeking at dispositions and the matcher starts mutating evidence. Neither refusal has a
 * Gradle cycle behind it; {@code SettlementModuleIsolationTest} and
 * {@code ReconciliationModuleIsolationTest} are the only controls, and every hand-off goes
 * through a port {@code app} composes.
 *
 * <p>Hence also this module's one permitted sibling edge, {@code settlement -> ledger}:
 * recognition — the counterparty's fees on acceptance, cash on the bank's own statement
 * ({@code INV-SET-06}) — IS a ledger posting, commanded through {@code PostingService} and never
 * written here ({@code INV-LED-04}). With that edge in the build graph,
 * {@code ledger -> settlement} is a Gradle cycle that cannot be added at all.
 *
 * <p><strong>What exists so far.</strong> The boundary and the migrator-owned schema floor
 * ({@code P8-TSK-001}): {@code REVOKE ALL FROM PUBLIC}, {@code USAGE} alone to
 * {@code finapp_app}, no tables, no default privileges — so every later evidence table's grants
 * are explicit, and {@code finapp_app} never holds a {@code DELETE} anywhere in this schema.
 * The source register and file store are {@code P8-TSK-002}'s.
 */
package com.finapp.settlement;
