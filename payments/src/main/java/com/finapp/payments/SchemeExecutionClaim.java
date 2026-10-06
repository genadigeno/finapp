package com.finapp.payments;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What explains one scheme execution on one rail (the Phase 7 -&gt; 8 transition, over
 * {@code V023}): <strong>one scheme execution, one money fact</strong>. Every producer that
 * records a scheme execution claims its {@code (rail, scheme reference)} inside its own
 * transaction before any money moves — the pay-in's credit, the withdrawal's completion, the
 * return's completion and the suspense parking — and the table's primary key decides between
 * them race-free, whichever instance runs them.
 *
 * <p>The gate found the reference guarded by two per-table {@code UNIQUE}s and two unlocked
 * reads: a credit and a parking of the same execution could both commit, and a withdrawal's
 * own confirmation echoed to the pay-in door parked as inbound money for value that went out.
 *
 * @param subjectId the claiming row's identifier — an attempt, a withdrawal, a refund or a
 *     parking, as {@code subject} says
 */
public record SchemeExecutionClaim(
        RailId rail,
        ProviderReference schemeReference,
        Subject subject,
        UUID subjectId,
        Instant claimedAt) {

    /** What an execution can be explained by. */
    public enum Subject {
        /** A pay-in attempt credited by it. */
        PAY_IN,
        /** A withdrawal completed by it — value that went OUT. */
        WITHDRAWAL,
        /** A return payment (a refund row) completed by it — value that went OUT. */
        RETURN,
        /** A suspense parking: value that arrived with no commercial home ({@code INV-REC-05}). */
        UNMATCHED,
        /** A cross-border outbound credit completed by it - value that went OUT (`P9-TSK-020`, payments V026). */
        OUTBOUND_CREDIT
    }

    public SchemeExecutionClaim {
        Objects.requireNonNull(rail, "rail must not be null");
        Objects.requireNonNull(schemeReference, "schemeReference must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(subjectId, "subjectId must not be null");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");
    }

    /** Whether this claim is the one {@code subject}/{@code subjectId} would have made. */
    public boolean heldBy(Subject kind, UUID id) {
        return subject == kind && subjectId.equals(id);
    }
}
