package com.finapp.credit;

import java.sql.Connection;
import java.util.Optional;

/** The credit assessment's persistence (`P10-TSK-011`, {@code credit V007}) - on the caller's unit of work. */
public interface CreditAssessmentStore {

    /**
     * Inserts the assessment unless its snapshot already has one ({@code ON CONFLICT (snapshot_id) DO NOTHING}); true
     * when this call wrote it. {@code assessedAt} is the database's, whatever the argument holds.
     */
    boolean insert(Connection unitOfWork, CreditAssessment assessment);

    /** The snapshot's assessment, if it has one. */
    Optional<CreditAssessment> bySnapshot(Connection unitOfWork, DecisionSnapshotId snapshot);
}
