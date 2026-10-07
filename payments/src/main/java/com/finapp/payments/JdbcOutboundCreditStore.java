package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plain-JDBC storage for outbound credits (ADR-0033, `P9-TSK-019`/`-020`); payments {@code V025} beneath. */
public final class JdbcOutboundCreditStore implements OutboundCreditStore {

    private static final String COLUMNS =
            "id, customer_party_id, subject_id, dispatch_key, rail, destination_reference, amount_minor, amount_currency,"
                    + " amount_scale, held_minor, held_currency, held_scale, hold_id, end_to_end_reference, status,"
                    + " provider_reference, created_at, last_dispatched_at, failure_reason, delivered_at,"
                    + " recall_requested_at, recall_outcome";

    @Override
    public void insert(Connection unitOfWork, Draft draft) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO payments.outbound_credit (id, customer_party_id, subject_id, dispatch_key, rail,"
                        + " destination_reference, amount_minor, amount_currency, amount_scale, held_minor, held_currency,"
                        + " held_scale, hold_id, end_to_end_reference, status, created_at, last_dispatched_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DISPATCHED', now(), now())")) {
            insert.setObject(1, draft.id().value());
            insert.setObject(2, draft.customerParty());
            insert.setObject(3, draft.subject());
            insert.setString(4, draft.dispatchKey());
            insert.setString(5, draft.rail().value());
            insert.setString(6, draft.destination().value());
            insert.setLong(7, draft.amount().minorUnits());
            insert.setString(8, draft.amount().currency().code());
            insert.setShort(9, (short) draft.amount().scale());
            insert.setLong(10, draft.held().minorUnits());
            insert.setString(11, draft.held().currency().code());
            insert.setShort(12, (short) draft.held().scale());
            insert.setObject(13, draft.holdId());
            insert.setString(14, draft.reference().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("recording an outbound credit", failure));
        }
    }

    @Override
    public Optional<Row> bySubject(Connection unitOfWork, UUID subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM payments.outbound_credit WHERE subject_id = ?", subject,
                "reading an outbound credit by its subject");
    }

    @Override
    public Optional<Row> byReference(Connection unitOfWork, EndToEndReference reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM payments.outbound_credit WHERE end_to_end_reference = ?",
                reference.value(), "reading an outbound credit by its end-to-end reference");
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, OutboundCreditId id) {
        Objects.requireNonNull(id, "id must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM payments.outbound_credit WHERE id = ? FOR UPDATE", id.value(),
                "locking outbound credit " + id);
    }

    @Override
    public boolean move(Connection unitOfWork, OutboundCreditId id, Status from, Status to) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        String doing = "moving outbound credit " + id;
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET status = ? WHERE id = ? AND status = ?")) {
            return run(update, doing, to.name(), id.value(), from.name()) == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public boolean complete(Connection unitOfWork, OutboundCreditId id, Status from, ProviderReference providerReference) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        String doing = "completing outbound credit " + id;
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE payments.outbound_credit SET status = 'COMPLETED',"
                + " provider_reference = COALESCE(provider_reference, ?) WHERE id = ? AND status = ?")) {
            return run(update, doing, providerReference.value(), id.value(), from.name()) == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public boolean fail(Connection unitOfWork, OutboundCreditId id, Status from, FailureReason reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        String doing = "failing outbound credit " + id;
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET status = 'FAILED', failure_reason = ? WHERE id = ? AND status = ?")) {
            return run(update, doing, reason.name(), id.value(), from.name()) == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public void recordProviderReference(Connection unitOfWork, OutboundCreditId id, ProviderReference providerReference) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        String doing = "recording outbound credit " + id + "'s provider reference";
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET provider_reference = ? WHERE id = ? AND provider_reference IS NULL")) {
            run(update, doing, providerReference.value(), id.value());
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public boolean markDelivered(Connection unitOfWork, OutboundCreditId id, Instant deliveredAt) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(deliveredAt, "deliveredAt must not be null");
        String doing = "recording outbound credit " + id + "'s delivery";
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE payments.outbound_credit SET delivered_at = ?"
                + " WHERE id = ? AND status = 'COMPLETED' AND delivered_at IS NULL")) {
            return run(update, doing, Timestamp.from(deliveredAt), id.value()) == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public boolean renewPermit(Connection unitOfWork, OutboundCreditId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        String doing = "renewing outbound credit " + id + "'s permit";
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET last_dispatched_at = statement_timestamp() WHERE id = ?"
                        + " AND recall_requested_at IS NULL")) {
            return run(update, doing, id.value()) == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public boolean requestRecall(Connection unitOfWork, OutboundCreditId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        String doing = "requesting outbound credit " + id + "'s recall";
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET recall_requested_at = statement_timestamp() WHERE id = ?"
                        + " AND recall_requested_at IS NULL AND status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED')")) {
            return run(update, doing, id.value()) == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public boolean recordRecallOutcome(Connection unitOfWork, OutboundCreditId id, RecallOutcome outcome) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        String doing = "recording outbound credit " + id + "'s recall outcome";
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET recall_outcome = ? WHERE id = ?"
                        + " AND recall_requested_at IS NOT NULL AND recall_outcome IS NULL")) {
            return run(update, doing, outcome.name(), id.value()) == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public List<Row> findDue(Connection unitOfWork, Duration dispatchedAge, Duration unknownAge, Duration receivedAge,
            Duration deliveryAge, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // THE ORDER IS FAIRNESS (P9-TST-001's storm found its absence): a credit awaiting its outcome holds the
                // customer's money, so every one comes before any delivery poll - oldest permit first; a COMPLETED
                // credit stays due until its delivery is known, so delivery polls rotate, least recently inquired first
                // (its latest inquiry evidence). Ordered by the permit alone, more undelivered credits than one page
                // re-read that same page on every sweep and starved every newer RECEIVED, UNKNOWN and DISPATCHED one.
                "SELECT " + COLUMNS + " FROM payments.outbound_credit c WHERE"
                        + " (status = 'DISPATCHED' AND last_dispatched_at <= statement_timestamp() - ? * interval '1 millisecond')"
                        + " OR (status = 'UNKNOWN' AND last_dispatched_at <= statement_timestamp() - ? * interval '1 millisecond')"
                        + " OR (status = 'RECEIVED' AND last_dispatched_at <= statement_timestamp() - ? * interval '1 millisecond')"
                        + " OR (status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED') AND recall_requested_at IS NOT NULL"
                        + "     AND recall_outcome IS NULL)"
                        + " OR (status = 'COMPLETED' AND delivered_at IS NULL"
                        + "     AND created_at <= statement_timestamp() - ? * interval '1 millisecond')"
                        + " ORDER BY (status = 'COMPLETED'),"
                        + " CASE WHEN status = 'COMPLETED' THEN (SELECT max(e.recorded_at) FROM payments.provider_evidence e"
                        + "     WHERE e.outbound_credit_id = c.id) END NULLS FIRST,"
                        + " last_dispatched_at, id LIMIT ?")) {
            select.setLong(1, dispatchedAge.toMillis());
            select.setLong(2, unknownAge.toMillis());
            select.setLong(3, receivedAge.toMillis());
            select.setLong(4, deliveryAge.toMillis());
            select.setInt(5, limit);
            List<Row> due = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    due.add(read(row));
                }
            }
            return due;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("reading the outbound credits due", failure));
        }
    }

    @Override
    public PaymentAttemptStore.UnknownReading unknownReading(Connection unitOfWork, Duration dispatchedAge) {
        // An UNKNOWN credit has waited since its latest permit, a DISPATCHED one too, counted only past the bound -
        // the server's clock deciding the age, a whole number of seconds floored at zero (the withdrawal's reading).
        return reading(unitOfWork, "SELECT count(*), GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM statement_timestamp()"
                + " - min(last_dispatched_at)))::bigint, 0)) FROM payments.outbound_credit WHERE status = 'UNKNOWN'"
                + " OR (status = 'DISPATCHED' AND last_dispatched_at <= statement_timestamp() - ? * interval '1 millisecond')",
                "reading the stuck-outbound-credit gauge", dispatchedAge.toMillis());
    }

    @Override
    public PaymentAttemptStore.UnknownReading receivedReading(Connection unitOfWork) {
        return reading(unitOfWork, "SELECT count(*), GREATEST(0, COALESCE(floor(EXTRACT(EPOCH FROM statement_timestamp()"
                + " - min(last_dispatched_at)))::bigint, 0)) FROM payments.outbound_credit WHERE status = 'RECEIVED'",
                "reading the received-outbound-credit gauge");
    }

    private static PaymentAttemptStore.UnknownReading reading(Connection unitOfWork, String sql, String doing,
            Object... parameters) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                read.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return new PaymentAttemptStore.UnknownReading(row.getLong(1), row.getLong(2));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    private static int run(PreparedStatement statement, String doing, Object... parameters) {
        try {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }


    private static Optional<Row> one(Connection unitOfWork, String sql, Object key, String doing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    private static Row read(ResultSet row) throws SQLException {
        String failure = row.getString("failure_reason");
        Timestamp delivered = row.getTimestamp("delivered_at");
        Timestamp recallRequested = row.getTimestamp("recall_requested_at");
        String recallOutcome = row.getString("recall_outcome");
        return new Row(
                OutboundCreditId.of(row.getObject("id", UUID.class)),
                row.getObject("customer_party_id", UUID.class),
                row.getObject("subject_id", UUID.class),
                row.getString("dispatch_key"),
                RailId.of(row.getString("rail")),
                new ProviderReference(row.getString("destination_reference")),
                Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("amount_currency")),
                        row.getShort("amount_scale")),
                Money.ofPersisted(row.getLong("held_minor"), CurrencyCode.of(row.getString("held_currency")),
                        row.getShort("held_scale")),
                row.getObject("hold_id", UUID.class),
                new EndToEndReference(row.getString("end_to_end_reference")),
                Status.valueOf(row.getString("status")),
                Optional.ofNullable(row.getString("provider_reference")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("last_dispatched_at").toInstant(),
                Optional.ofNullable(failure).map(FailureReason::valueOf),
                Optional.ofNullable(delivered).map(Timestamp::toInstant),
                Optional.ofNullable(recallRequested).map(Timestamp::toInstant),
                Optional.ofNullable(recallOutcome).map(RecallOutcome::valueOf));
    }
}
