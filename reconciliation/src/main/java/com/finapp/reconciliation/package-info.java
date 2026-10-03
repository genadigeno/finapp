/**
 * What the platform expected to happen, compared with what the evidence says — and never the
 * evidence itself.
 *
 * <p><strong>What belongs here.</strong> The SettlementExpectation — one per externally settling
 * completion, opened in the completing transaction and discharged only by its own declared
 * source's evidence ({@code INV-SET-02}, {@code INV-SET-05}, ADR-0067) — the ReconciliationBatch
 * and ExternalItem, the MatchDecision with its pinned rule set and decision snapshot
 * (deterministic and explainable: the same stored inputs always produce the same matches,
 * {@code INV-REC-04}, ADR-0068), the Break — a record with a lifecycle, classified and aged,
 * never deleted ({@code INV-REC-01}, ADR-0069) — the SuspenseItem, each owned by exactly one
 * break ({@code INV-REC-09}, ADR-0070), the Resolution — four-eyes for every person's
 * resolution but the zero-value acknowledgement of a timing detector's
 * {@code TIMING_DIFFERENCE} (reconciliation `V014`; {@code INV-REC-03}, ADR-0071) — and the
 * versioned RuleSet.
 *
 * <p><strong>What is structurally absent, and why the module exists in this shape.</strong> The
 * evidence — files, batches, lines, the counterparty's bytes — belongs to {@code settlement}
 * (ADR-0064), and there is <em>no build edge between the two modules in either direction</em>:
 * the module that decides what the platform expected must not compile against the module that
 * preserves what the counterparty said, or the matcher starts mutating evidence and acceptance
 * starts peeking at dispositions. Neither refusal has a Gradle cycle behind it;
 * {@code ReconciliationModuleIsolationTest} and {@code SettlementModuleIsolationTest} are the
 * only controls, and every hand-off — the intake's copy, the openers' calls — goes through a
 * port {@code app} composes.
 *
 * <p>Hence also this module's one permitted sibling edge, {@code reconciliation -> ledger}: an
 * approved resolution's compensating entry goes through the ledger's adjustment machinery, parks
 * and unparks through {@code PostingService}, a repudiated batch's recognition is reversed
 * through {@code ReversalService}, and the position proof reads balances through the ledger's
 * API — commanded and read, never written ({@code INV-LED-04}). With that edge in the build graph,
 * {@code ledger -> reconciliation} is a Gradle cycle that cannot be added at all.
 *
 * <p><strong>The floor.</strong> The boundary and the migrator-owned schema floor
 * ({@code P8-TSK-001}): {@code REVOKE ALL FROM PUBLIC}, {@code USAGE} alone to
 * {@code finapp_app}, no default privileges — so every table's grants are explicit, and
 * {@code finapp_app} holds no {@code DELETE} anywhere in this schema, which is what makes "no
 * code path deletes a break" a floor rather than a promise. Everything named above is built,
 * `V002`–`V015` ({@code P8-TSK-004} to {@code P8-DOC-001}); {@code MODULE_ARCHITECTURE.md}
 * §4 holds the build record. *(Corrected 2026-10-01, `P8-DOC-001`: this read "what exists so
 * far" and stopped at the schema floor.)*
 */
package com.finapp.reconciliation;
