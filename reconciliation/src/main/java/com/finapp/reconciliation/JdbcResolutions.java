package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** {@link Resolutions} over JDBC (ADR-0033: explicit SQL, no mapper). */
@RequiredArgsConstructor
public final class JdbcResolutions implements Resolutions {

    static final String TARGET_TYPE = "reconciliation_resolution";

    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;

    @Override
    public boolean evidence(Connection unitOfWork, Evidence evidence) {
        // The break first (the section-3 lock order), conditionally: a break already
        // RESOLVED converges - this writer records nothing (the raise-loser precedent).
        try (PreparedStatement resolve =
                unitOfWork.prepareStatement(
                        "UPDATE reconciliation.break SET status = 'RESOLVED',"
                                + " resolved_at = ?, status_changed_at = ?"
                                + " WHERE id = ? AND status <> 'RESOLVED'")) {
            resolve.setTimestamp(1, Timestamp.from(evidence.at()));
            resolve.setTimestamp(2, Timestamp.from(evidence.at()));
            resolve.setObject(3, evidence.breakId());
            if (resolve.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not resolve the break", failure);
        }
        try (PreparedStatement history =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.break_event (break_id, event_type,"
                                + " actor, actor_type, reason, detail, occurred_at,"
                                + " correlation_id) VALUES (?, 'RESOLVED', ?, ?, NULL, ?,"
                                + " ?, ?)")) {
            history.setObject(1, evidence.breakId());
            history.setString(2, evidence.actor().id());
            history.setString(3, evidence.actor().type().name());
            history.setString(4, "resolution=" + evidence.resolutionId()
                    + ", kind=EVIDENCED");
            history.setTimestamp(5, Timestamp.from(evidence.at()));
            history.setString(6, evidence.correlation().value());
            history.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the break's history", failure);
        }

        String narrative =
                "decision=" + evidence.decisionId()
                        + evidence.parkId().map(park -> ", park=" + park).orElse("");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.resolution (id, break_id, kind,"
                                + " status, reason_code, narrative, four_eyes,"
                                + " proposed_amount_minor, currency, scale,"
                                + " residual_version, offset_item_id, decision_id,"
                                + " park_id, rule_set_id, journal_entry_id, proposed_by,"
                                + " proposed_by_type, proposed_at, decided_by,"
                                + " decided_by_type, decided_at, created_at,"
                                + " status_changed_at, correlation_id)"
                                + " VALUES (?, ?, 'EVIDENCED', 'APPROVED',"
                                + " 'EVIDENCE_RECEIVED', ?, false, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, evidence.resolutionId());
            insert.setObject(2, evidence.breakId());
            insert.setString(3, narrative);
            insert.setLong(4, evidence.explained().minorUnits());
            insert.setString(5, evidence.explained().currency().code());
            insert.setInt(6, evidence.explained().scale());
            insert.setLong(7, evidence.residualVersion());
            insert.setObject(8, evidence.offsetItemId().orElse(null));
            insert.setObject(9, evidence.decisionId());
            insert.setObject(10, evidence.parkId().orElse(null));
            insert.setObject(11, evidence.ruleSetId());
            insert.setObject(12, evidence.journalEntryId().orElse(null));
            insert.setString(13, evidence.actor().id());
            insert.setString(14, evidence.actor().type().name());
            insert.setTimestamp(15, Timestamp.from(evidence.at()));
            insert.setString(16, evidence.actor().id());
            insert.setString(17, evidence.actor().type().name());
            insert.setTimestamp(18, Timestamp.from(evidence.at()));
            insert.setTimestamp(19, Timestamp.from(evidence.at()));
            insert.setTimestamp(20, Timestamp.from(evidence.at()));
            insert.setString(21, evidence.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not store the resolution", failure);
        }
        try (PreparedStatement history =
                unitOfWork.prepareStatement(
                        "INSERT INTO reconciliation.resolution_event (resolution_id,"
                                + " from_status, to_status, actor, actor_type, reason,"
                                + " occurred_at, correlation_id)"
                                + " VALUES (?, NULL, 'APPROVED', ?, ?, NULL, ?, ?)")) {
            history.setObject(1, evidence.resolutionId());
            history.setString(2, evidence.actor().id());
            history.setString(3, evidence.actor().type().name());
            history.setTimestamp(4, Timestamp.from(evidence.at()));
            history.setString(5, evidence.correlation().value());
            history.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not append the resolution's history", failure);
        }

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        evidence.actor(),
                        evidence.at(),
                        ReconciliationAuditAction.BREAK_RESOLVED_BY_EVIDENCE,
                        TARGET_TYPE,
                        evidence.resolutionId().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        evidence.correlation(),
                        // Identifiers and enumerated names only - never an amount
                        // (INV-AUD-02).
                        Optional.of(
                                "break=" + evidence.breakId()
                                        + ", kind=EVIDENCED, reason=EVIDENCE_RECEIVED, "
                                        + narrative)));
        ReconciliationEvents.breakResolved(
                outbox,
                unitOfWork,
                ids,
                evidence.breakId(),
                evidence.resolutionId(),
                ResolutionKind.EVIDENCED,
                ResolutionReasonCode.EVIDENCE_RECEIVED,
                evidence.journalEntryId(),
                evidence.at(),
                evidence.correlation());
        return true;
    }
}
