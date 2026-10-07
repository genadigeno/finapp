package com.finapp.credit;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit data requests, their attempts, records and evidence (`P10-TSK-006`, credit {@code V004}).
 *
 * <p>Every operation runs on the caller's unit of work. Every transition is conditional on the status the caller
 * expects, and every window is the database's: a renewal port takes no clock ({@code SendPermitsAreTheDatabasesTest}).
 */
public interface CreditDataRequestStore {

    /** A data request to be born - the timing is the source kind's configuration, frozen on the row. */
    record NewRequest(
            CreditDataRequestId id,
            UUID decisionRequestId,
            UUID partyId,
            CreditProduct product,
            CreditSourceKind kind,
            String providerCode,
            String reference,
            Duration retryCadence,
            Duration collectionWindow) {
        public NewRequest {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(decisionRequestId, "decisionRequestId");
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(providerCode, "providerCode");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(retryCadence, "retryCadence");
            Objects.requireNonNull(collectionWindow, "collectionWindow");
        }
    }

    /** A data request as it stands. */
    record Row(
            CreditDataRequestId id,
            UUID decisionRequestId,
            UUID partyId,
            CreditProduct product,
            CreditSourceKind kind,
            String providerCode,
            String reference,
            CreditDataRequestStatus status,
            int attempts,
            Instant nextAttemptAt,
            Instant requestedAt,
            Instant deadlineAt,
            boolean unavailableReported) {}

    /** An evidence row to be born; the payload is absent exactly when consent was found withdrawn. */
    record NewEvidence(
            CreditEvidenceId id,
            CreditDataRequestId dataRequest,
            int attempt,
            boolean duplicate,
            Optional<CreditEvidenceCipher.Encrypted> sealed,
            Optional<byte[]> checksum,
            int contentLength,
            int retentionMonths) {
        public NewEvidence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(dataRequest, "dataRequest");
            Objects.requireNonNull(sealed, "sealed");
            Objects.requireNonNull(checksum, "checksum");
            if (sealed.isPresent() != checksum.isPresent()) {
                throw new IllegalArgumentException("evidence is sealed whole, or not at all");
            }
        }

        public boolean consentWithdrawn() {
            return sealed.isEmpty();
        }
    }

    /** An unavailable request past its deadline, claimed for its one report. */
    record Overdue(CreditDataRequestId id, CreditSourceKind kind, String providerCode, int attempts) {}

    /** Births a data request {@code REQUESTED} - its reference unique, its windows stamped by the database. */
    void insertRequested(Connection unitOfWork, NewRequest request);

    Optional<Row> find(Connection unitOfWork, CreditDataRequestId id);

    /** The row {@code FOR UPDATE} - lock-order element (4). */
    Optional<Row> lock(Connection unitOfWork, CreditDataRequestId id);

    /** {@code REQUESTED -> RECEIVED}. */
    boolean receive(Connection unitOfWork, CreditDataRequestId id, int attempts);

    /** {@code REQUESTED -> UNAVAILABLE}, the permit re-stamped from the database's clock at the stamped cadence. */
    boolean markUnavailable(Connection unitOfWork, CreditDataRequestId id, int attempts);

    /** {@code from -> CONSENT_WITHDRAWN}, {@code from} being {@code REQUESTED} or {@code UNAVAILABLE}. */
    boolean withdraw(Connection unitOfWork, CreditDataRequestId id, CreditDataRequestStatus from, int attempts);

    void insertAttempt(Connection unitOfWork, CreditDataRequestId id, int attempt, String outcome);

    /** Births the record and its attributes - once per data request, by {@code UNIQUE (data_request_id)}. */
    void insertRecord(
            Connection unitOfWork,
            CreditRecordId id,
            Row request,
            String providerCode,
            int normaliserVersion,
            boolean complete,
            Instant retrievedAt,
            List<CreditAttribute> attributes);

    void insertEvidence(Connection unitOfWork, NewEvidence evidence);

    /**
     * Claims at most {@code limit} due requests - {@code REQUESTED} past its permit, or {@code UNAVAILABLE} past its
     * permit and before its deadline - oldest permit first, in ONE statement that moves each to {@code REQUESTED} and
     * re-stamps its permit; rows another claimer holds are skipped.
     */
    List<CreditDataRequestId> claimDue(Connection unitOfWork, int limit);

    /** Claims at most {@code limit} unavailable requests past their deadline and not yet reported, marking them reported. */
    List<Overdue> claimOverdue(Connection unitOfWork, int limit);
}
