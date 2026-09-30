package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** {@link ExternalItems} over JDBC (ADR-0033: explicit SQL, no mapper). */
public final class JdbcExternalItems implements ExternalItems {

    @Override
    public void birthAll(Connection unitOfWork, Actor actor, List<NewItem> items) {
        try (PreparedStatement insert =
                        unitOfWork.prepareStatement(
                                "INSERT INTO reconciliation.external_item (id, run_id,"
                                        + " source_id, settlement_line_id, line_no,"
                                        + " line_type, direction, amount_minor, currency,"
                                        + " scale, position_purpose, business_date,"
                                        + " settlement_date, value_date,"
                                        + " canonical_fingerprint, status, created_at,"
                                        + " status_changed_at, correlation_id,"
                                        + " attributed_source_id)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                        + " ?, 'PENDING', ?, ?, ?, ?)");
                PreparedStatement key =
                        unitOfWork.prepareStatement(
                                "INSERT INTO reconciliation.external_item_key (item_id,"
                                        + " source_id, key_kind, key_value)"
                                        + " VALUES (?, ?, ?, ?)");
                PreparedStatement event =
                        unitOfWork.prepareStatement(
                                "INSERT INTO reconciliation.external_item_event (item_id,"
                                        + " from_status, to_status, actor, actor_type,"
                                        + " occurred_at, correlation_id)"
                                        + " VALUES (?, NULL, 'PENDING', ?, ?, ?, ?)")) {
            for (NewItem item : items) {
                insert.setObject(1, item.id());
                insert.setObject(2, item.runId());
                insert.setObject(3, item.sourceId());
                insert.setObject(4, item.settlementLineId());
                insert.setInt(5, item.lineNo());
                insert.setString(6, item.lineType().name());
                insert.setString(7, item.direction().name());
                insert.setLong(8, item.amount().minorUnits());
                insert.setString(9, item.amount().currency().code());
                insert.setShort(10, (short) item.amount().scale());
                insert.setString(11, item.positionPurpose().map(Enum::name).orElse(null));
                insert.setObject(12, item.businessDate());
                insert.setObject(13, item.settlementDate().orElse(null));
                insert.setObject(14, item.valueDate().orElse(null));
                insert.setBytes(15, item.canonicalFingerprint());
                insert.setTimestamp(16, Timestamp.from(item.at()));
                insert.setTimestamp(17, Timestamp.from(item.at()));
                insert.setString(18, item.correlation().value());
                insert.setObject(19, item.attributedSourceId().orElse(null));
                insert.addBatch();
                for (Map.Entry<ItemKeyKind, String> each : item.keys().entrySet()) {
                    key.setObject(1, item.id());
                    key.setObject(2, item.sourceId());
                    key.setString(3, each.getKey().name());
                    key.setString(4, each.getValue());
                    key.addBatch();
                }
                event.setObject(1, item.id());
                event.setString(2, actor.id());
                event.setString(3, actor.type().name());
                event.setTimestamp(4, Timestamp.from(item.at()));
                event.setString(5, item.correlation().value());
                event.addBatch();
            }
            insert.executeBatch();
            key.executeBatch();
            event.executeBatch();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not birth an accepted batch's items", failure);
        }
    }
}
