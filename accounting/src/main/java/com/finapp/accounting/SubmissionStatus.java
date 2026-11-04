package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Set;

/**
 * A regulatory submission's lifecycle (`P14-TSK-001` declared it; `P14-TSK-018` widened it before {@code accounting V010}
 * applied; {@code PHASE_14_PLAN.md} sections 6, 13.7; ADR-0118 As built; {@code INV-ACC-13}) - drafted only from
 * {@code FINAL} runs of {@code CLOSED} periods, validated, approved under four eyes, submitted through
 * {@code RegulatoryReportingProvider} with {@code SUBMITTED} committed before the provider is called, then acknowledged or
 * rejected on the provider's word.
 *
 * <pre>
 * DRAFT --validate--> VALIDATED --approve--> APPROVED --submit--> SUBMITTED --the provider--> ACKNOWLEDGED | REJECTED
 * DRAFT | VALIDATED | APPROVED --a preparer withdraws--> WITHDRAWN
 * VALIDATED | APPROVED --a controller refuses--> REFUSED
 * SUBMITTED --a person, on a definite "not received" asked at that act, the resends spent, the horizon past or the month moved--> UNDELIVERED
 * </pre>
 *
 * <p><strong>Every state has a door out.</strong> As first declared the machine had only forward edges, so a stale
 * {@code DRAFT}, a {@code VALIDATED} a controller would not sign and an {@code APPROVED} overtaken by a reopen would each have
 * stood forever. {@link #REFUSED} is a <em>controller's</em> refusal and nothing left the building; {@link #REJECTED} is a
 * <em>provider's</em> answer to what was sent. Their names, events, audit records and what a reader sees are kept apart.
 * A rejection or a correction is a <strong>new</strong> submission referencing the original - never an edit; an unknown
 * provider answer is an inquiry on the {@code SUBMITTED} row, never a re-submit. Only a definite "not received" permits the
 * driver to send again, under the same submission id.
 */
public enum SubmissionStatus {

    DRAFT,
    VALIDATED,
    APPROVED,
    SUBMITTED,
    ACKNOWLEDGED,
    /** The provider refused what was sent - its answer, never a person's. */
    REJECTED,
    /** A controller would not attest it, or took their attestation back - nothing was sent. */
    REFUSED,
    /** A preparer took it back before it was sent. */
    WITHDRAWN,
    /** Committed to be sent, and the provider definitely holds nothing under its id, asked at the act, the resends spent, the horizon past or the month moved - a person's conclusion. */
    UNDELIVERED;

    /** The statuses this one may move to, by exactly one edge. */
    public Set<SubmissionStatus> next() {
        return switch (this) {
            case DRAFT -> EnumSet.of(VALIDATED, WITHDRAWN);
            case VALIDATED -> EnumSet.of(APPROVED, REFUSED, WITHDRAWN);
            case APPROVED -> EnumSet.of(SUBMITTED, REFUSED, WITHDRAWN);
            case SUBMITTED -> EnumSet.of(ACKNOWLEDGED, REJECTED, UNDELIVERED);
            case ACKNOWLEDGED, REJECTED, REFUSED, WITHDRAWN, UNDELIVERED -> EnumSet.noneOf(SubmissionStatus.class);
        };
    }

    /** True when {@code target} is one edge away. */
    public boolean canMoveTo(SubmissionStatus target) {
        return next().contains(target);
    }

    /** True from {@code SUBMITTED} on: what was sent is never edited, only answered or superseded by a new submission. */
    public boolean immutable() {
        return this == SUBMITTED || this == ACKNOWLEDGED || this == REJECTED || this == UNDELIVERED;
    }

    /** True when no edge leaves it. */
    public boolean terminal() {
        return next().isEmpty();
    }

    /** True of the only submissions a correction may reference: sent, and concluded. */
    public boolean correctable() {
        return this == ACKNOWLEDGED || this == REJECTED || this == UNDELIVERED;
    }

    /**
     * One plain sentence a reader of the status is told, so a {@link #REFUSED} is never read as the provider's word and a
     * {@link #REJECTED} never as a colleague's.
     */
    public String meaning() {
        return switch (this) {
            case DRAFT -> "Drawn from issued figures; not yet validated.";
            case VALIDATED -> "Passed its validation rules; awaiting a second person's attestation.";
            case APPROVED -> "Attested by a second person; not sent.";
            case SUBMITTED -> "Committed to be sent, or sent; the provider's answer is awaited.";
            case ACKNOWLEDGED -> "The provider acknowledged what was sent.";
            case REJECTED -> "The provider rejected what was sent; a correction references it.";
            case REFUSED -> "A controller would not attest it. Nothing was sent.";
            case WITHDRAWN -> "A preparer took it back. Nothing was sent.";
            case UNDELIVERED -> "A person concluded the provider holds nothing under its id; a correction references it.";
        };
    }
}
