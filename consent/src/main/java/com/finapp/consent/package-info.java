/**
 * Consent: the lawful basis for processing, and nothing else.
 *
 * <p><strong>What belongs here.</strong> Versioned consent texts and the append-only history of
 * grants and withdrawals. A capability declared consent-gated proceeds only when a current grant
 * for that specific purpose exists, and the absence of a record is a refusal
 * ({@code INV-CNS-01}).
 *
 * <p><strong>Consent is not authentication and is not authorization</strong> — the distinction
 * {@code CLAUDE.md} §Domain Distinctions and {@code INV-IDN-04} both insist on, and the reason
 * this is a module rather than a corner of {@code identity}: a session proves who is present,
 * a permission says what an actor of a kind may do, and neither is ever a lawful basis for
 * processing. Merging them is how data ends up processed with no basis at all while every
 * check passes.
 *
 * <p><strong>History, never state</strong> (ADR-0037). Grants and withdrawals are immutable
 * facts; withdrawal is a new record, not an edit; the current basis is <em>derived</em> from the
 * history per decision, never cached in process memory ({@code INV-CNS-02}, {@code INV-CNS-03}).
 * "Was there a basis on the day it happened?" is answerable only from history, and an updated
 * row would have destroyed the evidence the question needs. Every grant is bound to the version
 * of the text it was given against ({@code INV-CNS-04}).
 *
 * <p><strong>Nothing is implemented yet.</strong> This module is the skeleton created by
 * {@code P2-TSK-003}: the boundary, the schema and the auditable-action registry exist so the
 * record lands inside an enforced boundary. The text and record tables are {@code P2-TSK-017}.
 */
package com.finapp.consent;
