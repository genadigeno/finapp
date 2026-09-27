package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence for {@link DisputeEvidence} (`P7-TSK-014`, over `V022`) — and the ONLY way to reach
 * evidence content: the store owns the at-rest ceremony (encrypt and checksum on the way in,
 * decrypt and verify on the way out) under the dispute-evidence key, which no other store holds
 * ({@code INV-DSP-03}; one key per concern, ADR-0036's rule). The {@code kyc} store-owns-the-cipher
 * shape: a caller cannot retain or read evidence any other way.
 *
 * <p>The content reads never audit — that is the caller's act, committed in the read's own
 * transaction ({@code DocumentAccess}'s rule), because only the caller knows who is looking and
 * why. Every content read is therefore reachable only through {@link DisputeEvidenceAccess} and
 * the response dispatch, both of which write the record first-hand.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface DisputeEvidenceStore<T> {

    /** A document with its verified plaintext — held only for as long as the caller needs it. */
    record Content(DisputeEvidence evidence, DisputeEvidenceContent content) {

        /** Never the content ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Content[" + evidence + "]";
        }
    }

    /** What an upload did: stored a new document, or converged on the identical one. */
    record Stored(DisputeEvidence evidence, boolean created) {}

    /**
     * Stores {@code candidate} with its content — encrypted, checksummed — unless the dispute
     * already holds a document with the same checksum, in which case that document is answered
     * ({@code created == false}): CONTENT-ADDRESSED CONVERGENCE, the KYC upload's idempotency. A
     * retry after a lost response, a double-tap and a deliberate re-upload land on one row, and
     * the first declaration of kind and type stands.
     */
    Stored appendOrConverge(T unitOfWork, DisputeEvidence candidate, DisputeEvidenceContent content);

    /**
     * The dispute's document with exactly these bytes, if it holds one — the content address an
     * upload answers first, before any guard: a retried upload learns its document is stored.
     */
    Optional<DisputeEvidence> findByContent(
            T unitOfWork, DisputeId dispute, DisputeEvidenceContent content);

    /** How many documents the dispute holds — judged under the dispute's row lock. */
    int countFor(T unitOfWork, DisputeId dispute);

    /** The dispute's documents, oldest first — metadata only, no content. */
    List<DisputeEvidence> listFor(T unitOfWork, DisputeId dispute);

    /**
     * One document's content for a counterparty: found only when the disputed payment credited
     * one of {@code counterparties} — the tenant predicate IN THE STATEMENT ({@code INV-MER-01}),
     * so another tenant's document and an unknown one are the same absence.
     */
    Optional<Content> readContentForCounterparties(
            T unitOfWork,
            DisputeId dispute,
            DisputeEvidenceId id,
            Set<LedgerAccountId> counterparties);

    /** One document's content for an operator — across tenants by permission. */
    Optional<Content> readContent(T unitOfWork, DisputeId dispute, DisputeEvidenceId id);

    /**
     * The contents a response transmits, in the order {@code ids} names them — every one the
     * dispute's own; a missing one is corruption (the response froze them) and throws.
     */
    List<Content> contentsOf(T unitOfWork, DisputeId dispute, List<DisputeEvidenceId> ids);
}
