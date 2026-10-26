package com.finapp.risk;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link RiskSignalStore} over {@code risk.risk_signal} ({@code risk V003}; `P13-TSK-005`): explicit SQL on the
 * caller's connection (ADR-0033). One insert, born-once by the unique beneath the inbox's dedupe; four reads, each one
 * range scan on {@code risk_signal_by_subject (subject_kind, subject_ref, kind, observed_at DESC)}, the merchant's
 * count on {@code risk V011}'s {@code risk_signal_by_merchant} (`P13-TSK-014`), and the standing row of a source event
 * on its unique (`P13-TSK-025`). Every attribute is
 * bound to its typed column by the kind's declared type, and read back into the canonical text the domain validates -
 * so what comes out is exactly what a {@link RiskSignal} admitted on the way in.
 */
public final class JdbcRiskSignalStore implements RiskSignalStore {

    static final String COLUMNS =
            "id, kind, subject_kind, subject_ref, source_event_id, source_event_type, source_event_version, producer,"
                    + " observed_at, correlation_id, causation_id, product, status, previous_status, bucket,"
                    + " previous_bucket, country, currency, business_date, days_past_due, defaulted, agreement_version,"
                    + " channel_ref, request_ref, merchant_subject_ref, intent_ref, list_version_ref, screening_ref";

    @Override
    public boolean insert(Connection unitOfWork, RiskSignal signal) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(signal, "signal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // Born once: the unique on source_event_id arbitrates beneath the inbox's dedupe, so a replay past the
                // dedupe's retention inserts nothing (INV-RSK-06). recorded_at is the trigger's: the database's clock.
                "INSERT INTO risk.risk_signal (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (source_event_id) DO NOTHING")) {
            int i = 0;
            insert.setObject(++i, signal.id().value());
            insert.setString(++i, signal.kind().name());
            insert.setString(++i, signal.subject().kind().name());
            insert.setString(++i, signal.subject().ref());
            insert.setObject(++i, signal.source().id().value());
            insert.setString(++i, signal.source().type());
            insert.setInt(++i, signal.source().version());
            insert.setString(++i, signal.source().producer());
            insert.setTimestamp(++i, Timestamp.from(signal.observedAt()));
            insert.setString(++i, signal.source().correlation().value());
            insert.setString(++i, signal.source().causation().value());
            for (String column : SignalKind.ATTRIBUTE_COLUMNS) {
                bind(insert, ++i, column, signal.attributes().get(column).orElse(null));
            }
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("recording a risk signal", failure);
        }
    }

    @Override
    public int countSince(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant since) {
        Objects.requireNonNull(since, "since");
        try (PreparedStatement count = unitOfWork.prepareStatement(
                "SELECT count(*) FROM risk.risk_signal WHERE subject_kind = ? AND subject_ref = ? AND kind = ?"
                        + " AND observed_at >= ?")) {
            bindSubject(count, subject, kind);
            count.setTimestamp(4, Timestamp.from(since));
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failed("counting risk signals", failure);
        }
    }

    @Override
    public int countByMerchantSince(Connection unitOfWork, UUID merchant, SignalKind kind, Instant since) {
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(since, "since");
        // One range scan on risk V011's risk_signal_by_merchant (merchant_subject_ref, kind, observed_at DESC).
        try (PreparedStatement count = unitOfWork.prepareStatement(
                "SELECT count(*) FROM risk.risk_signal WHERE merchant_subject_ref = ? AND kind = ? AND observed_at >= ?")) {
            count.setObject(1, merchant);
            count.setString(2, kind.name());
            count.setTimestamp(3, Timestamp.from(since));
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failed("counting a merchant's risk signals", failure);
        }
    }

    @Override
    public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
        try (PreparedStatement latest = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.risk_signal WHERE subject_kind = ? AND subject_ref = ? AND kind = ?"
                        + " ORDER BY observed_at DESC, recorded_at DESC LIMIT 1")) {
            bindSubject(latest, subject, kind);
            try (ResultSet row = latest.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading the latest risk signal", failure);
        }
    }

    @Override
    public Optional<Instant> firstObservedAt(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
        try (PreparedStatement first = unitOfWork.prepareStatement(
                "SELECT min(observed_at) FROM risk.risk_signal WHERE subject_kind = ? AND subject_ref = ? AND kind = ?")) {
            bindSubject(first, subject, kind);
            try (ResultSet row = first.executeQuery()) {
                row.next();
                Timestamp observed = row.getTimestamp(1);
                return observed == null ? Optional.empty() : Optional.of(observed.toInstant());
            }
        } catch (SQLException failure) {
            throw failed("reading the first risk signal", failure);
        }
    }

    @Override
    public boolean seen(Connection unitOfWork, SignalSubject subject) {
        Objects.requireNonNull(subject, "subject");
        try (PreparedStatement seen = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM risk.risk_signal WHERE subject_kind = ? AND subject_ref = ?)")) {
            seen.setString(1, subject.kind().name());
            seen.setString(2, subject.ref());
            try (ResultSet row = seen.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading whether a subject has a risk signal", failure);
        }
    }

    /** `P13-TSK-025`: the standing row of a source event - one probe of {@code UNIQUE (source_event_id)}. */
    @Override
    public Optional<RiskSignal> recordedFrom(Connection unitOfWork, EventId source) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(source, "source");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM risk.risk_signal WHERE source_event_id = ?")) {
            select.setObject(1, source.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a risk signal by its source event", failure);
        }
    }

    // -----------------------------------------------------------------

    private static void bindSubject(PreparedStatement statement, SignalSubject subject, SignalKind kind)
            throws SQLException {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        statement.setString(1, subject.kind().name());
        statement.setString(2, subject.ref());
        statement.setString(3, kind.name());
    }

    /** Binds an attribute's canonical text to its typed column, or NULL where the kind does not carry it. */
    private static void bind(PreparedStatement statement, int index, String column, String value) throws SQLException {
        switch (column) {
            case "business_date" -> {
                if (value == null) {
                    statement.setNull(index, Types.DATE);
                } else {
                    statement.setObject(index, LocalDate.parse(value), Types.DATE);
                }
            }
            case "days_past_due", "agreement_version" -> {
                if (value == null) {
                    statement.setNull(index, Types.INTEGER);
                } else {
                    statement.setInt(index, Integer.parseInt(value));
                }
            }
            case "defaulted" -> {
                if (value == null) {
                    statement.setNull(index, Types.BOOLEAN);
                } else {
                    statement.setBoolean(index, Boolean.parseBoolean(value));
                }
            }
            case "channel_ref", "request_ref", "merchant_subject_ref", "intent_ref", "list_version_ref", "screening_ref" -> {
                if (value == null) {
                    statement.setNull(index, Types.OTHER);
                } else {
                    statement.setObject(index, UUID.fromString(value));
                }
            }
            default -> {
                if (value == null) {
                    statement.setNull(index, Types.VARCHAR);
                } else {
                    statement.setString(index, value);
                }
            }
        }
    }

    static RiskSignal read(ResultSet row) throws SQLException {
        SignalKind kind = SignalKind.valueOf(row.getString("kind"));
        Map<String, String> attributes = new LinkedHashMap<>();
        for (SignalKind.Attribute attribute : kind.attributes()) {
            attributes.put(attribute.name(), text(row, attribute));
        }
        return new RiskSignal(
                RiskSignalId.of(row.getObject("id", UUID.class)),
                kind,
                new SignalSubject(SubjectKind.valueOf(row.getString("subject_kind")), row.getString("subject_ref")),
                new RiskSignal.SourceEvent(
                        EventId.of(row.getObject("source_event_id", UUID.class)),
                        row.getString("source_event_type"),
                        row.getInt("source_event_version"),
                        row.getString("producer"),
                        CorrelationId.of(row.getString("correlation_id")),
                        CausationId.of(row.getString("causation_id"))),
                row.getTimestamp("observed_at").toInstant(),
                SignalAttributes.of(kind, attributes));
    }

    /** An attribute's typed column, back as the canonical text the domain validates. */
    private static String text(ResultSet row, SignalKind.Attribute attribute) throws SQLException {
        return switch (attribute.type()) {
            case DATE -> row.getObject(attribute.name(), LocalDate.class).toString();
            case DAYS, VERSION -> Integer.toString(row.getInt(attribute.name()));
            case FLAG -> Boolean.toString(row.getBoolean(attribute.name()));
            case REF -> row.getObject(attribute.name(), UUID.class).toString();
            case CODE, COUNTRY, CURRENCY -> row.getString(attribute.name());
        };
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new RiskStorageException("risk storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
