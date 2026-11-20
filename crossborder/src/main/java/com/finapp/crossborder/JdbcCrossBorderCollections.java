package com.finapp.crossborder;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CrossBorderCollections} over JDBC (`P17-TSK-037`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>A corridor's latest fact is its highest {@code seq} - the order the facts committed in, which `V002`'s
 * {@code corridor_availability_latest} walks - never its {@code recorded_at}. The payment search walks `V008`'s indexes,
 * {@code (created_at, id)} and the corridor's {@code (corridor, created_at, id)}, each a keyset range read backwards with its
 * window the index condition; each row's offer is joined on its primary key for the amounts. The enable requests read a table
 * of one row per controller's proposal.
 */
public final class JdbcCrossBorderCollections implements CrossBorderCollections {

    private static final String CORRIDOR_FACTS =
            "SELECT DISTINCT ON (corridor) corridor, available, actor_id, reason, recorded_at"
                    + " FROM crossborder.corridor_availability ORDER BY corridor, seq DESC";

    @Override
    public Map<CorridorKey, CorridorFact> latestCorridorFacts(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(CORRIDOR_FACTS);
                ResultSet row = select.executeQuery()) {
            Map<CorridorKey, CorridorFact> facts = new LinkedHashMap<>();
            while (row.next()) {
                CorridorKey corridor = CorridorKey.parse(row.getString("corridor"));
                facts.put(corridor, new CorridorFact(corridor, row.getBoolean("available"), row.getString("actor_id"),
                        row.getString("reason"), row.getTimestamp("recorded_at").toInstant()));
            }
            return Map.copyOf(facts);
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("reading the corridor availability facts", failure));
        }
    }

    @Override
    public List<CorridorRequestEntry> corridorEnableRequests(
            Connection unitOfWork,
            Optional<CorridorAvailabilityStore.EnableRequestStatus> status,
            Optional<CorridorRequestCursor> after,
            int limit) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder(
                "SELECT id, corridor, status, proposed_by, proposed_at, proposal_reason, decided_by, decided_at, decision_reason"
                        + " FROM crossborder.corridor_enable_request WHERE true");
        List<Object> arguments = new ArrayList<>();
        status.ifPresent(named -> {
            sql.append(" AND status = ?");
            arguments.add(named.name());
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (proposed_at, id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.proposedAt()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY proposed_at DESC, id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            bind(select, arguments);
            List<CorridorRequestEntry> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    page.add(new CorridorRequestEntry(
                            row.getObject("id", UUID.class),
                            CorridorKey.parse(row.getString("corridor")),
                            CorridorAvailabilityStore.EnableRequestStatus.valueOf(row.getString("status")),
                            row.getString("proposed_by"),
                            row.getTimestamp("proposed_at").toInstant(),
                            row.getString("proposal_reason"),
                            Optional.ofNullable(row.getString("decided_by")),
                            Optional.ofNullable(row.getTimestamp("decided_at")).map(Timestamp::toInstant),
                            Optional.ofNullable(row.getString("decision_reason"))));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("listing the corridor enable requests", failure));
        }
    }

    @Override
    public List<CrossBorderPaymentHead> payments(
            Connection unitOfWork, CrossBorderPaymentFilter filter, Optional<CrossBorderPaymentCursor> after, int limit) {
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.owner_party, p.beneficiary_id, p.corridor, p.status, p.failure_reason, p.created_at,"
                        + " o.source_currency, o.destination_currency, o.fee_scale, o.destination_scale, o.source_minor,"
                        + " o.fee_minor, o.total_debit_minor, o.destination_minor"
                        + " FROM crossborder.payment p JOIN crossborder.payment_offer o ON o.id = p.offer_id"
                        + " WHERE p.created_at >= ? AND p.created_at < ?");
        List<Object> arguments = new ArrayList<>(List.of(Timestamp.from(filter.from()), Timestamp.from(filter.to())));
        filter.corridor().ifPresent(corridor -> {
            sql.append(" AND p.corridor = ?");
            arguments.add(corridor.code());
        });
        filter.status().ifPresent(status -> {
            sql.append(" AND p.status = ?");
            arguments.add(status.name());
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (p.created_at, p.id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.createdAt()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            bind(select, arguments);
            List<CrossBorderPaymentHead> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
                    CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
                    // The offer's STORED scales, never re-derived (INV-MON-05): the source side at the fee's.
                    int sourceScale = row.getInt("fee_scale");
                    page.add(new CrossBorderPaymentHead(
                            row.getObject("id", UUID.class),
                            row.getObject("owner_party", UUID.class),
                            row.getObject("beneficiary_id", UUID.class),
                            CorridorKey.parse(row.getString("corridor")),
                            PaymentStore.Status.valueOf(row.getString("status")),
                            Optional.ofNullable(row.getString("failure_reason")),
                            Money.ofPersisted(row.getLong("source_minor"), source, sourceScale),
                            Money.ofPersisted(row.getLong("fee_minor"), source, sourceScale),
                            Money.ofPersisted(row.getLong("total_debit_minor"), source, sourceScale),
                            Money.ofPersisted(row.getLong("destination_minor"), destination, row.getInt("destination_scale")),
                            row.getTimestamp("created_at").toInstant()));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("searching the cross-border payments", failure));
        }
    }

    private static void bind(PreparedStatement statement, List<Object> arguments) throws SQLException {
        for (int i = 0; i < arguments.size(); i++) {
            statement.setObject(i + 1, arguments.get(i));
        }
    }
}
