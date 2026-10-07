package com.finapp.credit;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decision snapshots and the collection rows a freeze reads (`P10-TSK-008`, credit {@code V005}).
 *
 * <p>Every judgement of time - a deadline passed, a record still fresh - is made in SQL on the freezing transaction's
 * database instant ({@code transaction_timestamp()}): one instant for the whole freeze, and never an instance's clock
 * ({@code INV-CRD-08}).
 */
public interface DecisionSnapshotStore {

    /** A data request as the freeze sees it, locked. */
    record DataRequestState(
            CreditDataRequestId id,
            CreditSourceKind kind,
            CreditDataRequestStatus status,
            Instant requestedAt,
            boolean pastDeadline) {}

    /** One stored attribute, as read back. */
    record StoredAttribute(CreditAttributeCode code, AttributeValue value) {}

    /** A data request's record, judged against a maximum age. */
    record StoredRecord(
            CreditRecordId id,
            CreditSourceKind kind,
            String providerCode,
            int normaliserVersion,
            boolean fresh,
            List<StoredAttribute> attributes) {}

    /** A stored snapshot's columns. */
    record StoredSnapshot(
            DecisionSnapshotId id, int sequence, int format, String canonical, byte[] sha256, Instant frozenAt) {}

    /** Every data request of the decision request, locked {@code FOR UPDATE} by id - lock-order element (4). */
    List<DataRequestState> lockDataRequestsOf(Connection unitOfWork, UUID decisionRequestId);

    /** The data request's record with its freshness: {@code retrieved_at >= transaction_timestamp() - maxAge}. */
    Optional<StoredRecord> recordOf(Connection unitOfWork, CreditDataRequestId dataRequest, Duration maxAge);

    /** Births the snapshot; {@code false} when one already exists for its decision request and sequence. */
    boolean insertSnapshot(
            Connection unitOfWork,
            DecisionSnapshotId id,
            UUID decisionRequestId,
            int sequence,
            int format,
            String canonical,
            byte[] sha256,
            PinnedVersions versions);

    Optional<StoredSnapshot> snapshotOf(Connection unitOfWork, UUID decisionRequestId, int sequence);
}
