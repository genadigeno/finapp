package com.finapp.consent;

import java.util.Optional;
import java.util.UUID;

/**
 * The append-only consent history and its derivations (`P2-TSK-017`, ADR-0037).
 *
 * <p>Generic over the unit of work — a JDBC {@code Connection} (ADR-0033) — so the caller
 * decides the transaction: `P2-TSK-018`'s endpoints will commit the record and its audit entry
 * together, and the gate (`P2-TSK-019`) reads authoritative state per decision on whatever
 * connection the gated operation already holds ({@code INV-CNS-03}: no process-local consent
 * cache, anywhere, ever).
 *
 * @param <T> the unit of work
 */
public interface ConsentStore<T> {

    /**
     * Appends one fact. Never updates, never converges, never refuses a duplicate: a repeated
     * grant and a withdrawal with no grant before it are both honest history — the derivation
     * absorbs them, which is what makes retries and races safe with no key and no lock.
     */
    void append(T unitOfWork, ConsentRecord record);

    /**
     * The latest fact for (party, purpose) — the raw history's head, for views that show a
     * person their own posture (`P2-TSK-018`). Latest by {@code seq}, the server-assigned
     * total order, never by {@code recorded_at}: a timestamp written by N instances' clocks
     * cannot order concurrent facts, and every reader must agree which fact is last.
     */
    Optional<ConsentRecord> latestFor(T unitOfWork, UUID partyId, ConsentPurpose purpose);

    /**
     * The derivation ({@code INV-CNS-01}/{@code 04}): is there a current basis for this
     * processing?
     *
     * <p>True exactly when the latest fact is a {@code GRANT} <strong>and</strong> no text
     * version newer than the one it pinned requires re-consent — one statement, one snapshot,
     * so the answer can never pair a fact with text-version state another transaction has
     * already replaced. <strong>Absence is refusal</strong>: no history and a latest
     * withdrawal are the same {@code false}, indistinguishable to every caller, because a
     * default-permit consent check is not a consent check.
     */
    boolean hasCurrentBasis(T unitOfWork, UUID partyId, ConsentPurpose purpose);

    /**
     * The current (highest) text version for a purpose — what `P2-TSK-018` presents and grants
     * against. Always present: every purpose's v1 is seeded by the migration that created the
     * table, so an empty answer is a deployment defect and throws rather than returning empty.
     */
    ConsentText currentTextFor(T unitOfWork, ConsentPurpose purpose);
}
