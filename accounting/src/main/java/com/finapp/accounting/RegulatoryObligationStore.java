package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence for regulatory obligation versions (`P14-TSK-018`, {@code accounting V010}): born once per form and version, every
 * later move a conditional {@code UPDATE} the machine trigger judges again for every writer.
 */
public interface RegulatoryObligationStore {

    /** Births the version {@code PROPOSED} unless its form and version are held; {@code true} when born. */
    boolean insertIfAbsent(Connection unitOfWork, NewObligation obligation);

    /** The version, unlocked. */
    Optional<Row> find(Connection unitOfWork, RegulatoryObligationId id);

    /** The version {@code FOR SHARE}: a submission's act holds it so no activation retires it underneath. */
    Optional<Row> lockShared(Connection unitOfWork, RegulatoryObligationId id);

    /** Every version of the form {@code FOR UPDATE}, in id order: two activations of one form take the same rows in the same order. */
    List<Row> lockForm(Connection unitOfWork, String jurisdiction, String form);

    /** The latest {@code limit} versions, newest first. */
    List<Row> recent(Connection unitOfWork, int limit);

    /** Those {@code PROPOSED} by anyone but {@code reader}, oldest first: the filter is the statement's, before its limit. */
    List<Row> awaitingActivation(Connection unitOfWork, String reader, int limit);

    /** {@code PROPOSED -> ACTIVE}; {@code false} when it was not {@code PROPOSED}. */
    boolean markActive(Connection unitOfWork, RegulatoryObligationId id, Actor actor, String reason);

    /** {@code PROPOSED -> REJECTED}; {@code false} when it was not {@code PROPOSED}. */
    boolean markRejected(Connection unitOfWork, RegulatoryObligationId id, Actor actor, String reason);

    /** {@code ACTIVE -> RETIRED}; {@code false} when it was not {@code ACTIVE}. */
    boolean markRetired(Connection unitOfWork, RegulatoryObligationId id);

    /** A version to birth. */
    record NewObligation(RegulatoryObligationId id, String jurisdiction, String form, int formVersion, String adapter, String configuration, String configurationSha256, Actor proposedBy,
            String reason, CorrelationId correlation) {

        public NewObligation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(configuration, "configuration");
        }

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "NewObligation[" + id.value() + ", " + form + "/" + formVersion + "]";
        }
    }

    /** A version's row. */
    record Row(RegulatoryObligationId id, String jurisdiction, String form, int formVersion, String periodType, String adapter, String standing, String configuration, String configurationSha256,
            RegulatoryObligationStatus status, String proposedBy, Instant proposedAt, String proposalReason, Optional<String> decidedBy, Optional<Instant> decidedAt, Optional<Instant> retiredAt,
            CorrelationId correlation) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "Row[" + id.value() + ", " + form + "/" + formVersion + ", " + status + "]";
        }
    }
}
