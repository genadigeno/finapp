package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * Plain-JDBC storage for KYC decisions (ADR-0033, `P2-TSK-013`).
 *
 * <p>Plain {@code INSERT}s, no {@code ON CONFLICT} and no savepoint, deliberately: the caller
 * won the conditional case move before inserting, so a unique violation here is not a lost race
 * to converge on — it is a decision existing on a case the mover just found undecided, an
 * invariant already broken, and the right answer is the loud storage failure this store throws
 * for any other refused write.
 */
public final class JdbcKycDecisionStore implements KycDecisionStore<Connection> {

    @Override
    public void record(Connection unitOfWork, KycDecision decision) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO kyc.kyc_decision"
                                + " (id, case_id, outcome, decision_basis, decided_by, reason,"
                                + " policy_version, decided_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, decision.id().value());
            insert.setObject(2, decision.caseId().value());
            insert.setString(3, decision.outcome().name());
            insert.setString(4, decision.basis().name());
            insert.setObject(5, decision.decidedBy().orElse(null));
            insert.setString(6, decision.reason());
            insert.setString(7, decision.policyVersion().value());
            insert.setTimestamp(8, Timestamp.from(decision.decidedAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            // The reason is free prose and must not ride an exception into a log (INV-AUD-02);
            // DatabaseFailure.describe carries no cause, and this message carries identifiers
            // only.
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "recording decision " + decision.id() + " on case "
                                    + decision.caseId(),
                            failure));
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO kyc.kyc_decision_check (decision_id, check_id)"
                                + " VALUES (?, ?)")) {
            for (CheckId checkId : decision.evidence()) {
                insert.setObject(1, decision.id().value());
                insert.setObject(2, checkId.value());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "recording the evidence references of decision " + decision.id(),
                            failure));
        }
    }
}
