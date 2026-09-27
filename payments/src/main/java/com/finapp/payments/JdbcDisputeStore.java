package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.money.MoneyColumns;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DisputeStore} over explicit SQL (ADR-0033), on the caller's connection — the
 * {@code JdbcWithdrawalStore} protocols: the conditional transition with its trail row, and a
 * lock-first read for the resolver; plus the insert whose conflict IS the dedupe arbiter.
 */
public final class JdbcDisputeStore implements DisputeStore<Connection> {

    private static final String TABLE = "payments.dispute";

    private static final String COLUMNS =
            "id, provider, provider_dispute_reference, attempt_id, reason, stage,"
                    + " chargeback_amount_minor, chargeback_currency, chargeback_scale,"
                    + " opened_at";

    /** The read surfaces' shape: the dispute's columns, and the payment its attempt serves. */
    private static final String FOUND =
            "SELECT d.id, d.provider, d.provider_dispute_reference, d.attempt_id, d.reason,"
                    + " d.stage, d.chargeback_amount_minor, d.chargeback_currency,"
                    + " d.chargeback_scale, d.opened_at, a.intent_id"
                    + " FROM " + TABLE + " d"
                    + " JOIN payments.payment_attempt a ON a.id = d.attempt_id";

    @Override
    public boolean insert(Connection unitOfWork, Dispute opened) {
        Objects.requireNonNull(opened, "opened must not be null");
        // ON CONFLICT names the ONE arbiter it may absorb - a primary-key collision or any
        // other refusal still fails loudly rather than converging into somebody else's row.
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES"
                                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT ON CONSTRAINT dispute_one_per_provider_reference"
                                + " DO NOTHING")) {
            insert.setObject(1, opened.id().value());
            insert.setString(2, opened.provider());
            insert.setString(3, opened.providerReference().value());
            insert.setObject(4, opened.attemptId().value());
            insert.setString(5, opened.reason().name());
            insert.setString(6, opened.stage().name());
            bindChargeback(insert, 7, opened);
            insert.setTimestamp(10, Timestamp.from(opened.openedAt()));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("opening a dispute", failure));
        }
    }

    @Override
    public Optional<Dispute> findForUpdate(
            Connection unitOfWork, String provider, ProviderReference reference) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE provider = ? AND provider_dispute_reference = ?"
                                + " FOR UPDATE")) {
            select.setString(1, provider);
            select.setString(2, reference.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("locking a dispute", failure));
        }
    }

    @Override
    public boolean transition(Connection unitOfWork, Dispute before, Dispute after, Instant at) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(at, "at must not be null");
        // The chargeback's amount rides the statement too: V020 lets it move only NULL -> value
        // (the edge entering CHARGED_BACK), so re-binding a recorded one is a no-op for every
        // writer and changing it is refused.
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE + " SET stage = ?, chargeback_amount_minor = ?,"
                                + " chargeback_currency = ?, chargeback_scale = ?"
                                + " WHERE id = ? AND stage = ?")) {
            update.setString(1, after.stage().name());
            bindChargeback(update, 2, after);
            update.setObject(5, after.id().value());
            update.setString(6, before.stage().name());
            if (update.executeUpdate() != 1) {
                return false;
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "moving a dispute " + before.stage() + " -> " + after.stage(),
                            failure));
        }
        appendHistory(unitOfWork, after.id(), before.stage(), after.stage(), at);
        return true;
    }

    @Override
    public Optional<Found> findById(Connection unitOfWork, DisputeId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(FOUND + " WHERE d.id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(found(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a dispute", failure));
        }
    }

    @Override
    public List<Found> listForIntent(Connection unitOfWork, PaymentIntentId intent) {
        Objects.requireNonNull(intent, "intent must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        FOUND + " WHERE a.intent_id = ? ORDER BY d.opened_at, d.id")) {
            select.setObject(1, intent.value());
            return all(select);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a payment's disputes", failure));
        }
    }

    @Override
    public Optional<Found> findForCounterparties(
            Connection unitOfWork, DisputeId id, Set<LedgerAccountId> counterparties) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(counterparties, "counterparties must not be null");
        // THE TENANT PREDICATE IS IN THE STATEMENT (INV-MER-01): the disputed payment must
        // have credited one of the caller's own accounts - never a load-then-compare.
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        FOUND
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE d.id = ? AND i.credit_account_id = ANY (?)")) {
            select.setObject(1, id.value());
            select.setArray(2, accounts(unitOfWork, counterparties));
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(found(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a counterparty's dispute", failure));
        }
    }

    @Override
    public List<Found> listForCounterparties(
            Connection unitOfWork, Set<LedgerAccountId> counterparties, int limit) {
        Objects.requireNonNull(counterparties, "counterparties must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a listing's limit must be positive");
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        FOUND
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE i.credit_account_id = ANY (?)"
                                + " ORDER BY d.opened_at DESC, d.id DESC"
                                + " LIMIT ?")) {
            select.setArray(1, accounts(unitOfWork, counterparties));
            select.setInt(2, limit);
            return all(select);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("listing a counterparty's disputes", failure));
        }
    }

    @Override
    public List<StageChange> historyOf(Connection unitOfWork, DisputeId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT from_stage, to_stage, occurred_at FROM payments.dispute_event"
                                + " WHERE dispute_id = ? ORDER BY id")) {
            select.setObject(1, id.value());
            List<StageChange> trail = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    trail.add(
                            new StageChange(
                                    DisputeStage.valueOf(row.getString("from_stage")),
                                    DisputeStage.valueOf(row.getString("to_stage")),
                                    row.getTimestamp("occurred_at").toInstant()));
                }
            }
            return List.copyOf(trail);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a dispute's trail", failure));
        }
    }

    // -----------------------------------------------------------------

    private void appendHistory(
            Connection unitOfWork, DisputeId id, DisputeStage from, DisputeStage to, Instant at) {
        Actor actor = SecurityContext.require();
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.dispute_event"
                                + " (dispute_id, from_stage, to_stage, actor_id, actor_type,"
                                + " occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, id.value());
            insert.setString(2, from.name());
            insert.setString(3, to.name());
            insert.setString(4, actor.id());
            insert.setString(5, actor.type().name());
            insert.setTimestamp(6, Timestamp.from(at));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("appending a dispute trail row", failure));
        }
    }

    /** The chargeback's three columns from {@code first}: all present or all NULL. */
    private static void bindChargeback(PreparedStatement statement, int first, Dispute dispute)
            throws SQLException {
        if (dispute.chargeback().isPresent()) {
            Money amount = dispute.chargeback().get();
            statement.setLong(first, MoneyColumns.amountMinorOf(amount));
            statement.setString(first + 1, MoneyColumns.currencyOf(amount));
            statement.setShort(first + 2, MoneyColumns.scaleOf(amount));
        } else {
            statement.setNull(first, java.sql.Types.BIGINT);
            statement.setNull(first + 1, java.sql.Types.CHAR);
            statement.setNull(first + 2, java.sql.Types.SMALLINT);
        }
    }

    private static Array accounts(Connection unitOfWork, Set<LedgerAccountId> counterparties)
            throws SQLException {
        return unitOfWork.createArrayOf(
                "uuid", counterparties.stream().map(LedgerAccountId::value).toArray());
    }

    private static List<Found> all(PreparedStatement select) throws SQLException {
        List<Found> found = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                found.add(found(row));
            }
        }
        return List.copyOf(found);
    }

    private static Found found(ResultSet row) throws SQLException {
        return new Found(map(row), PaymentIntentId.of(row.getObject("intent_id", UUID.class)));
    }

    private static Dispute map(ResultSet row) throws SQLException {
        String currency = row.getString("chargeback_currency");
        Optional<Money> chargeback =
                currency == null
                        ? Optional.empty()
                        : Optional.of(
                                Money.ofPersisted(
                                        row.getLong("chargeback_amount_minor"),
                                        CurrencyCode.of(currency),
                                        row.getShort("chargeback_scale")));
        return Dispute.rehydrate(
                DisputeId.of(row.getObject("id", UUID.class)),
                row.getString("provider"),
                new ProviderReference(row.getString("provider_dispute_reference")),
                PaymentAttemptId.of(row.getObject("attempt_id", UUID.class)),
                DisputeReason.valueOf(row.getString("reason")),
                DisputeStage.valueOf(row.getString("stage")),
                chargeback,
                row.getTimestamp("opened_at").toInstant());
    }
}
