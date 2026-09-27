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

    /** The mutable money fragments, in binding order: the chargeback, its two shares, the fee. */
    private static final String MONEY_COLUMNS =
            " chargeback_amount_minor, chargeback_currency, chargeback_scale,"
                    + " counterparty_share_amount_minor, counterparty_share_currency,"
                    + " counterparty_share_scale,"
                    + " parked_share_amount_minor, parked_share_currency, parked_share_scale,"
                    + " dispute_fee_amount_minor, dispute_fee_currency, dispute_fee_scale";

    private static final String COLUMNS =
            "id, provider, provider_dispute_reference, attempt_id, reason, stage,"
                    + MONEY_COLUMNS + ", opened_at";

    /** The read surfaces' shape: the dispute's columns, and the payment its attempt serves. */
    private static final String FOUND =
            "SELECT d.id, d.provider, d.provider_dispute_reference, d.attempt_id, d.reason,"
                    + " d.stage, d.chargeback_amount_minor, d.chargeback_currency,"
                    + " d.chargeback_scale, d.counterparty_share_amount_minor,"
                    + " d.counterparty_share_currency, d.counterparty_share_scale,"
                    + " d.parked_share_amount_minor, d.parked_share_currency,"
                    + " d.parked_share_scale, d.dispute_fee_amount_minor,"
                    + " d.dispute_fee_currency, d.dispute_fee_scale, d.opened_at, a.intent_id"
                    + " FROM " + TABLE + " d"
                    + " JOIN payments.payment_attempt a ON a.id = d.attempt_id";

    /** The chargeback stands — generated from the machine, one definition (`V021` shares it). */
    private static final String STANDING =
            "stage IN (" + DisputeStage.standingSqlValueList() + ")";

    @Override
    public boolean insert(Connection unitOfWork, Dispute opened) {
        Objects.requireNonNull(opened, "opened must not be null");
        // ON CONFLICT names the ONE arbiter it may absorb - a primary-key collision or any
        // other refusal still fails loudly rather than converging into somebody else's row.
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES"
                                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " ON CONFLICT ON CONSTRAINT dispute_one_per_provider_reference"
                                + " DO NOTHING")) {
            insert.setObject(1, opened.id().value());
            insert.setString(2, opened.provider());
            insert.setString(3, opened.providerReference().value());
            insert.setObject(4, opened.attemptId().value());
            insert.setString(5, opened.reason().name());
            insert.setString(6, opened.stage().name());
            bindMoney(insert, 7, opened);
            insert.setTimestamp(19, Timestamp.from(opened.openedAt()));
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
        // The money fragments ride the statement too: V020/V021 let the chargeback and its
        // attribution arrive only NULL -> value on the edge entering CHARGED_BACK and the fee
        // only NULL -> value, so re-binding recorded ones is a no-op for every writer and
        // changing them is refused.
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE + " SET stage = ?, chargeback_amount_minor = ?,"
                                + " chargeback_currency = ?, chargeback_scale = ?,"
                                + " counterparty_share_amount_minor = ?,"
                                + " counterparty_share_currency = ?,"
                                + " counterparty_share_scale = ?,"
                                + " parked_share_amount_minor = ?, parked_share_currency = ?,"
                                + " parked_share_scale = ?,"
                                + " dispute_fee_amount_minor = ?, dispute_fee_currency = ?,"
                                + " dispute_fee_scale = ?"
                                + " WHERE id = ? AND stage = ?")) {
            update.setString(1, after.stage().name());
            bindMoney(update, 2, after);
            update.setObject(14, after.id().value());
            update.setString(15, before.stage().name());
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
    public boolean reattribute(Connection unitOfWork, Dispute before, Dispute after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        ChargebackSplit was = before.split().orElseThrow();
        ChargebackSplit now = after.split().orElseThrow();
        // Conditional on the stage AND the shares this caller read: a lost race is a false,
        // never a second re-attribution on top of somebody else's (the expected-value rule).
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE + " SET counterparty_share_amount_minor = ?,"
                                + " parked_share_amount_minor = ?"
                                + " WHERE id = ? AND stage = ?"
                                + " AND counterparty_share_amount_minor = ?"
                                + " AND parked_share_amount_minor = ?")) {
            update.setLong(1, MoneyColumns.amountMinorOf(now.counterpartyShare()));
            update.setLong(2, MoneyColumns.amountMinorOf(now.parkedShare()));
            update.setObject(3, after.id().value());
            update.setString(4, before.stage().name());
            update.setLong(5, MoneyColumns.amountMinorOf(was.counterpartyShare()));
            update.setLong(6, MoneyColumns.amountMinorOf(was.parkedShare()));
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("re-attributing a chargeback", failure));
        }
    }

    @Override
    public boolean recordFee(Connection unitOfWork, Dispute before, Dispute after) {
        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Money fee = after.fee().orElseThrow();
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE + " SET dispute_fee_amount_minor = ?,"
                                + " dispute_fee_currency = ?, dispute_fee_scale = ?"
                                + " WHERE id = ? AND dispute_fee_amount_minor IS NULL")) {
            update.setLong(1, MoneyColumns.amountMinorOf(fee));
            update.setString(2, MoneyColumns.currencyOf(fee));
            update.setShort(3, MoneyColumns.scaleOf(fee));
            update.setObject(4, after.id().value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("recording a dispute fee", failure));
        }
    }

    @Override
    public Money attributedStanding(
            Connection unitOfWork, PaymentAttemptId attempt, CurrencyCode currency) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        // Folded through Money, never a SQL SUM (the BalanceDerivation doctrine): a share in
        // another currency or scale refuses loudly instead of adding into the bound's term.
        Money attributed = Money.zero(currency);
        for (Dispute standing : standing(unitOfWork, attempt, false)) {
            attributed = attributed.plus(standing.split().orElseThrow().attributed());
        }
        return attributed;
    }

    @Override
    public List<Dispute> standingOn(Connection unitOfWork, PaymentAttemptId attempt) {
        Objects.requireNonNull(attempt, "attempt must not be null");
        return standing(unitOfWork, attempt, true);
    }

    @Override
    public boolean anyRestorableTo(Connection unitOfWork, LedgerAccountId account) {
        Objects.requireNonNull(account, "account must not be null");
        // A POSTED share only: a parked one never reached the account, so a win returns it
        // from the recoverable and nothing is on its way to the account.
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM " + TABLE + " d"
                                + " JOIN payments.payment_attempt a ON a.id = d.attempt_id"
                                + " JOIN payments.payment_intent i ON i.id = a.intent_id"
                                + " WHERE i.credit_account_id = ?"
                                + " AND d.stage IN (" + DisputeStage.restorableSqlValueList()
                                + ") AND d.counterparty_share_amount_minor > 0)")) {
            select.setObject(1, account.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "asking whether a chargeback may still credit " + account, failure));
        }
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

    /** The standing chargebacks on one attempt, oldest first — locked when {@code lock}. */
    private List<Dispute> standing(
            Connection unitOfWork, PaymentAttemptId attempt, boolean lock) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT " + COLUMNS + " FROM " + TABLE
                                + " WHERE attempt_id = ? AND " + STANDING
                                + " ORDER BY opened_at, id"
                                + (lock ? " FOR UPDATE" : ""))) {
            select.setObject(1, attempt.value());
            List<Dispute> found = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    found.add(map(row));
                }
            }
            return List.copyOf(found);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "reading the chargebacks standing on attempt " + attempt, failure));
        }
    }

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

    /**
     * The four money fragments from {@code first}, twelve parameters: the chargeback, its
     * counterparty share, its parked share, the fee — each all present or all NULL.
     */
    private static void bindMoney(PreparedStatement statement, int first, Dispute dispute)
            throws SQLException {
        Optional<ChargebackSplit> split = dispute.split();
        bindFragment(statement, first, split.map(ChargebackSplit::amount));
        bindFragment(statement, first + 3, split.map(ChargebackSplit::counterpartyShare));
        bindFragment(statement, first + 6, split.map(ChargebackSplit::parkedShare));
        bindFragment(statement, first + 9, dispute.fee());
    }

    private static void bindFragment(PreparedStatement statement, int first, Optional<Money> money)
            throws SQLException {
        if (money.isPresent()) {
            statement.setLong(first, MoneyColumns.amountMinorOf(money.get()));
            statement.setString(first + 1, MoneyColumns.currencyOf(money.get()));
            statement.setShort(first + 2, MoneyColumns.scaleOf(money.get()));
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
        Optional<Money> amount = fragment(row, "chargeback");
        Optional<ChargebackSplit> split =
                amount.map(
                        taken -> {
                            try {
                                return new ChargebackSplit(
                                        taken,
                                        fragment(row, "counterparty_share").orElseThrow(),
                                        fragment(row, "parked_share").orElseThrow());
                            } catch (SQLException unreadable) {
                                throw new PaymentsStorageException(
                                        DatabaseFailure.describe(
                                                "reading a chargeback's attribution",
                                                unreadable));
                            }
                        });
        return Dispute.rehydrate(
                DisputeId.of(row.getObject("id", UUID.class)),
                row.getString("provider"),
                new ProviderReference(row.getString("provider_dispute_reference")),
                PaymentAttemptId.of(row.getObject("attempt_id", UUID.class)),
                DisputeReason.valueOf(row.getString("reason")),
                DisputeStage.valueOf(row.getString("stage")),
                split,
                fragment(row, "dispute_fee"),
                row.getTimestamp("opened_at").toInstant());
    }

    /** One nullable money fragment by its field name (MoneyColumns' naming), strictly read. */
    private static Optional<Money> fragment(ResultSet row, String field) throws SQLException {
        String currency = row.getString(field + MoneyColumns.CURRENCY_SUFFIX);
        if (currency == null) {
            return Optional.empty();
        }
        return Optional.of(
                Money.ofPersisted(
                        row.getLong(field + MoneyColumns.AMOUNT_MINOR_SUFFIX),
                        CurrencyCode.of(currency.stripTrailing()),
                        row.getShort(field + MoneyColumns.SCALE_SUFFIX)));
    }
}
