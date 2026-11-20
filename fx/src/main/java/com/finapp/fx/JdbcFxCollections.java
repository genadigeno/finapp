package com.finapp.fx;

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
 * {@link FxCollections} over JDBC (`P17-TSK-037`, ADR-0033: explicit SQL, no ORM).
 *
 * <p>A subject's latest fact is its highest {@code seq} - the order the facts committed in, which `V004`'s
 * {@code *_availability_latest} indexes walk - never its {@code recorded_at}, so two facts of one instant are never a tie. The
 * trade search walks `V014`'s indexes, {@code (booked_at, id)} and the pair's {@code (source_currency, destination_currency,
 * booked_at, id)}, each a keyset range read backwards with its window the index condition. The enable requests read a table
 * of one row per controller's proposal.
 */
public final class JdbcFxCollections implements FxCollections {

    private static final String PAIR_FACTS =
            "SELECT DISTINCT ON (source_currency, destination_currency) source_currency, destination_currency, available,"
                    + " actor_id, reason, recorded_at FROM fx.pair_availability"
                    + " ORDER BY source_currency, destination_currency, seq DESC";

    private static final String PROVIDER_FACTS =
            "SELECT DISTINCT ON (provider_code) provider_code, available, actor_id, reason, recorded_at"
                    + " FROM fx.provider_availability ORDER BY provider_code, seq DESC";

    @Override
    public Map<AvailabilitySubject, AvailabilityFact> latestFacts(Connection unitOfWork, AvailabilitySubject.Kind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        boolean pairs = kind == AvailabilitySubject.Kind.PAIR;
        // One constant per statement: the SQL each kind reads, chosen apart from the statement that runs it.
        String sql;
        if (pairs) {
            sql = PAIR_FACTS;
        } else {
            sql = PROVIDER_FACTS;
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            Map<AvailabilitySubject, AvailabilityFact> facts = new LinkedHashMap<>();
            while (row.next()) {
                AvailabilitySubject subject = pairs
                        ? new AvailabilitySubject(kind, row.getString("source_currency") + "-"
                                + row.getString("destination_currency"))
                        : new AvailabilitySubject(kind, row.getString("provider_code"));
                facts.put(subject, new AvailabilityFact(subject, row.getBoolean("available"), row.getString("actor_id"),
                        row.getString("reason"), row.getTimestamp("recorded_at").toInstant()));
            }
            return Map.copyOf(facts);
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("reading the availability facts", failure));
        }
    }

    @Override
    public List<EnableRequestEntry> enableRequests(
            Connection unitOfWork,
            Optional<AvailabilityStore.EnableRequestStatus> status,
            Optional<EnableRequestCursor> after,
            int limit) {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder(
                "SELECT id, subject_kind, subject, status, proposed_by, proposed_at, proposal_reason, decided_by, decided_at,"
                        + " decision_reason FROM fx.availability_enable_request WHERE true");
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
            List<EnableRequestEntry> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    page.add(new EnableRequestEntry(
                            row.getObject("id", UUID.class),
                            new AvailabilitySubject(AvailabilitySubject.Kind.valueOf(row.getString("subject_kind")),
                                    row.getString("subject")),
                            AvailabilityStore.EnableRequestStatus.valueOf(row.getString("status")),
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
            throw new FxStorageException(DatabaseFailure.describe("listing the enable requests", failure));
        }
    }

    @Override
    public List<TradeHead> trades(Connection unitOfWork, TradeFilter filter, Optional<TradeCursor> after, int limit) {
        Objects.requireNonNull(filter, "filter must not be null");
        Objects.requireNonNull(after, "after must not be null");
        StringBuilder sql = new StringBuilder(
                "SELECT id, owner_party_id, purpose, fixed_side, status, source_currency, destination_currency, source_scale,"
                        + " destination_scale, customer_source_minor, customer_destination_minor, executed_rate, provider_code,"
                        + " booked_at, booked_on FROM fx.trade WHERE booked_at >= ? AND booked_at < ?");
        List<Object> arguments = new ArrayList<>(List.of(Timestamp.from(filter.from()), Timestamp.from(filter.to())));
        filter.pair().ifPresent(pair -> {
            sql.append(" AND source_currency = ? AND destination_currency = ?");
            arguments.add(pair.source().code());
            arguments.add(pair.destination().code());
        });
        filter.status().ifPresent(status -> {
            sql.append(" AND status = ?");
            arguments.add(status.name());
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (booked_at, id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.bookedAt()));
            arguments.add(cursor.id());
        });
        sql.append(" ORDER BY booked_at DESC, id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement select = unitOfWork.prepareStatement(sql.toString())) {
            bind(select, arguments);
            List<TradeHead> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
                    CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
                    page.add(new TradeHead(
                            FxTradeId.of(row.getObject("id", UUID.class)),
                            row.getObject("owner_party_id", UUID.class),
                            PricingPurpose.valueOf(row.getString("purpose")),
                            FixedSide.valueOf(row.getString("fixed_side")),
                            TradeStatus.valueOf(row.getString("status")),
                            // The STORED scales, never re-derived (INV-MON-05).
                            Money.ofPersisted(row.getLong("customer_source_minor"), source, row.getInt("source_scale")),
                            Money.ofPersisted(row.getLong("customer_destination_minor"), destination,
                                    row.getInt("destination_scale")),
                            row.getBigDecimal("executed_rate").stripTrailingZeros(),
                            row.getString("provider_code"),
                            row.getTimestamp("booked_at").toInstant(),
                            row.getDate("booked_on").toLocalDate()));
                }
            }
            return List.copyOf(page);
        } catch (SQLException failure) {
            throw new FxStorageException(DatabaseFailure.describe("searching the trades", failure));
        }
    }

    private static void bind(PreparedStatement statement, List<Object> arguments) throws SQLException {
        for (int i = 0; i < arguments.size(); i++) {
            statement.setObject(i + 1, arguments.get(i));
        }
    }
}
