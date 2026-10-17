package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the BNPL terms versions (`P12-TSK-004`, {@code bnpl V002}): the machine row
 * ({@code bnpl_terms_version}, its decision columns the only ones that move), the term set born with it
 * ({@code bnpl_terms}, INSERT-only) and the history ({@code bnpl_terms_version_event}, append-only).
 *
 * <p>The administration's lock order (A0 to A2, {@code BNPL_LIFECYCLES.md} section 3): the product's advisory lock
 * (namespace {@code 12}), then the version row {@code FOR UPDATE}, then the product's {@code ACTIVE} row
 * {@code FOR UPDATE}. A pinning reader - the application and the offer - takes the {@code ACTIVE} row {@code FOR SHARE}
 * and nothing of the administration's.
 */
public interface BnplTermsStore {

    /**
     * A0: the product's administration lock - advisory namespace {@code 12} on {@code hashtext(product)}, blocking,
     * transaction-scoped - taken FIRST by every proposal and decision.
     */
    void lockProduct(Connection unitOfWork, BnplProduct product);

    /** Whether a version of {@code product} awaits a decision - read under A0. */
    boolean proposalPending(Connection unitOfWork, BnplProduct product);

    /** The product's highest version number, 0 for none - read under A0. */
    int maxVersion(Connection unitOfWork, BnplProduct product);

    /** Births the version {@code PROPOSED}, its term set and its history row; the database stamps the time. */
    void insertProposal(Connection unitOfWork, NewProposal proposal);

    /** The version's machine row, unlocked - to learn its (frozen) product before A0. */
    Optional<VersionRow> find(Connection unitOfWork, BnplTermsVersionId id);

    /** A1: the version's machine row {@code FOR UPDATE}. */
    Optional<VersionRow> lock(Connection unitOfWork, BnplTermsVersionId id);

    /** A2: the product's {@code ACTIVE} row {@code FOR UPDATE} - waits for every reader pinning it {@code FOR SHARE}. */
    Optional<VersionRow> lockActive(Connection unitOfWork, BnplProduct product);

    /** Retires an {@code ACTIVE} version; {@code false} when it was not {@code ACTIVE}. */
    boolean retire(Connection unitOfWork, BnplTermsVersionId id);

    /**
     * Decides a {@code PROPOSED} version - {@code ACTIVE} or {@code REJECTED} - conditional on {@code PROPOSED}. Returns
     * the database's stamps, or empty when the version was no longer {@code PROPOSED}: nothing written.
     */
    Optional<DecisionStamps> decide(
            Connection unitOfWork, BnplTermsVersionId id, BnplTermsVersionStatus status, Actor actor, String reason);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /** The version with its whole term set, any status. */
    Optional<BnplTermsVersion> version(Connection unitOfWork, BnplTermsVersionId id);

    /** The version of {@code product} in force at {@code instant} - from the effective periods alone. */
    Optional<BnplTermsVersionId> activeAt(Connection unitOfWork, BnplProduct product, Instant instant);

    /** The version of {@code product} {@code ACTIVE} now, by status. */
    Optional<BnplTermsVersionId> active(Connection unitOfWork, BnplProduct product);

    /**
     * The {@code ACTIVE} version of {@code product}, its row held {@code FOR SHARE} to the caller's commit - the read the
     * application and the offer pin, so an activation waits for them and they never pin a version retiring under them.
     */
    Optional<BnplTermsVersionId> shareActive(Connection unitOfWork, BnplProduct product);

    /** Every product's {@code ACTIVE} version number - for the gauge. */
    Map<BnplProduct, Integer> activeVersions(Connection unitOfWork);

    // ------------------------------------------------------------------ rows

    /** A proposal at birth. */
    record NewProposal(
            BnplTermsVersionId id,
            int version,
            BnplTerms terms,
            Actor proposer,
            String reason,
            CorrelationId correlation,
            UUID eventId) {

        public NewProposal {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            Objects.requireNonNull(eventId, "eventId must not be null");
            if (version < 1) {
                throw new IllegalArgumentException("a terms version is numbered from 1");
            }
        }

        @Override
        public String toString() {
            return "NewProposal[" + id.value() + ", " + terms.product() + " v" + version + "]";
        }
    }

    /** One edge of the history. */
    record HistoryRow(
            UUID eventId,
            BnplTermsVersionId version,
            BnplProduct product,
            Optional<BnplTermsVersionStatus> from,
            BnplTermsVersionStatus to,
            Actor actor,
            String reason,
            CorrelationId correlation) {

        public HistoryRow {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "HistoryRow[" + version.value() + ", " + from.map(Enum::name).orElse("-") + " -> " + to + "]";
        }
    }

    /** The database's stamps on a decision: when, and - for an activation - the effective start. */
    record DecisionStamps(Instant decidedAt, Optional<Instant> effectiveFrom) {

        public DecisionStamps {
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        }
    }

    /** A version's machine row: who, which state, and its effective period. No term, no money. */
    record VersionRow(
            BnplTermsVersionId id,
            BnplProduct product,
            int version,
            BnplTermsVersionStatus status,
            String proposedBy,
            Instant proposedAt,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<Instant> effectiveFrom,
            Optional<Instant> effectiveTo) {

        public VersionRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            Objects.requireNonNull(decidedBy, "decidedBy must not be null");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
            Objects.requireNonNull(effectiveTo, "effectiveTo must not be null");
        }

        @Override
        public String toString() {
            return "VersionRow[" + id.value() + ", " + product + " v" + version + ", " + status + "]";
        }
    }
}
