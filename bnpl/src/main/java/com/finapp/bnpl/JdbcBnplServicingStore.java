package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplServicingStore} over plain JDBC (`P12-TSK-015`, ADR-0033; lending's {@code JdbcLoanServicingStore}): every
 * instant the database's, every failure its operation and SQLSTATE only ({@link BnplStorageException}) - never an amount or
 * a date of a customer's obligation.
 */
public final class JdbcBnplServicingStore implements BnplServicingStore {

    @Override
    public List<BnplObligationId> claimDue(Connection unitOfWork, int limit, Duration retryCadence) {
        Objects.requireNonNull(retryCadence, "retryCadence");
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one obligation");
        }
        if (retryCadence.isNegative() || retryCadence.isZero()) {
            throw new IllegalArgumentException("the retry cadence is positive");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                // The page locked SKIP LOCKED, then each permit re-stamped after the locks on the database clock.
                "UPDATE bnpl.bnpl_obligation SET next_servicing_at = clock_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM bnpl.bnpl_obligation WHERE status = 'ACTIVE'"
                        + " AND next_servicing_at <= statement_timestamp()"
                        + " ORDER BY next_servicing_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status = 'ACTIVE' RETURNING id")) {
            claim.setLong(1, retryCadence.toMillis());
            claim.setInt(2, limit);
            List<BnplObligationId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(BnplObligationId.of(rows.getObject(1, UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming BNPL obligations due servicing", failure);
        }
    }

    @Override
    public Optional<Locked> lock(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT o.id, o.purchase_id, o.agreement_id, o.party_id, o.decision_id, o.product, o.plan, o.currency,"
                        + " o.status, o.servicing_zone, o.purchase_date, o.opened_at, o.closure_reason, o.correlation_id,"
                        + " a.version, a.terms_version_id, a.schedule_engine, a.accrual_engine, a.allocation_engine"
                        + " FROM bnpl.bnpl_obligation o"
                        + " JOIN LATERAL (SELECT * FROM bnpl.bnpl_agreement x WHERE x.obligation_id = o.id"
                        + " ORDER BY x.version DESC LIMIT 1) a ON true"
                        + " WHERE o.id = ? FOR UPDATE OF o")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String closure = row.getString("closure_reason");
                BnplObligation locked = new BnplObligation(obligation,
                        BnplPurchaseId.of(row.getObject("purchase_id", UUID.class)),
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getObject("party_id", UUID.class),
                        row.getObject("decision_id", UUID.class), BnplProduct.valueOf(row.getString("product")),
                        BnplPlan.valueOf(row.getString("plan")), CurrencyCode.of(row.getString("currency").strip()),
                        BnplObligationStatus.valueOf(row.getString("status")),
                        row.getDate("purchase_date").toLocalDate(), row.getTimestamp("opened_at").toInstant(),
                        Optional.ofNullable(closure).map(BnplClosureReason::valueOf),
                        CorrelationId.of(row.getString("correlation_id")));
                return Optional.of(new Locked(locked, locked.agreement(), row.getInt("version"),
                        BnplTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                        row.getString("schedule_engine"), Optional.ofNullable(row.getString("accrual_engine")),
                        row.getString("allocation_engine"), row.getString("servicing_zone")));
            }
        } catch (SQLException failure) {
            throw failed("locking a BNPL obligation", failure);
        }
    }

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT clock_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public LocalDate principalValueFloor(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT bnpl.principal_value_floor(?)")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getDate(1).toLocalDate();
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's principal value floor", failure);
        }
    }

    @Override
    public Optional<LocalDate> latestAccrualDate(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT max(accrual_date) FROM bnpl.bnpl_interest_accrual WHERE obligation_id = ?")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                Date latest = row.getDate(1);
                return Optional.ofNullable(latest).map(Date::toLocalDate);
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's latest accrual", failure);
        }
    }

    @Override
    public Money periodInterest(Connection unitOfWork, BnplObligationId obligation, LocalDate from,
            LocalDate toExclusive, Money zero) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(zero, "zero");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT coalesce(sum(posted_minor), 0) FROM bnpl.bnpl_interest_accrual"
                        + " WHERE obligation_id = ? AND accrual_date >= ? AND accrual_date < ?")) {
            select.setObject(1, obligation.value());
            select.setDate(2, Date.valueOf(from));
            select.setDate(3, Date.valueOf(toExclusive));
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return Money.ofPersisted(row.getLong(1), zero.currency(), zero.scale());
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL period's accrued interest", failure);
        }
    }

    @Override
    public Optional<BnplPurchaseStore.StoredSchedule> latestSchedule(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT s.id, s.schedule_version, s.engine, s.currency, s.scale, s.principal_minor, s.start_date,"
                        + " i.id AS instalment_id, i.sequence, i.due_date, i.principal_minor AS instalment_principal,"
                        + " i.interest_minor, i.amount_minor"
                        + " FROM bnpl.bnpl_schedule s LEFT JOIN bnpl.bnpl_instalment i ON i.schedule_id = s.id"
                        + " WHERE s.obligation_id = ? AND s.schedule_version ="
                        + " (SELECT max(x.schedule_version) FROM bnpl.bnpl_schedule x WHERE x.obligation_id = ?)"
                        + " ORDER BY i.sequence")) {
            select.setObject(1, obligation.value());
            select.setObject(2, obligation.value());
            UUID id = null;
            int version = 0;
            String engine = null;
            Money principal = null;
            LocalDate start = null;
            List<BnplPurchaseStore.StoredInstalment> instalments = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    CurrencyCode currency = CurrencyCode.of(rows.getString("currency").strip());
                    int scale = rows.getInt("scale");
                    if (id == null) {
                        id = rows.getObject("id", UUID.class);
                        version = rows.getInt("schedule_version");
                        engine = rows.getString("engine");
                        principal = Money.ofPersisted(rows.getLong("principal_minor"), currency, scale);
                        start = rows.getDate("start_date").toLocalDate();
                    }
                    if (rows.getObject("instalment_id") == null) {
                        continue; // An empty refund version (P12-TSK-021): the schedule, no instalment.
                    }
                    instalments.add(new BnplPurchaseStore.StoredInstalment(rows.getObject("instalment_id", UUID.class),
                            rows.getInt("sequence"), rows.getDate("due_date").toLocalDate(),
                            Money.ofPersisted(rows.getLong("instalment_principal"), currency, scale),
                            Money.ofPersisted(rows.getLong("interest_minor"), currency, scale),
                            Money.ofPersisted(rows.getLong("amount_minor"), currency, scale)));
                }
            }
            return id == null ? Optional.empty()
                    : Optional.of(new BnplPurchaseStore.StoredSchedule(id, obligation, version, engine, principal, start,
                            instalments));
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's latest schedule", failure);
        }
    }

    @Override
    public int latestCatchUpSequence(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT coalesce(max(catch_up_sequence), 0) FROM bnpl.bnpl_credit_balance_application"
                        + " WHERE obligation_id = ?")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's latest catch-up application", failure);
        }
    }

    @Override
    public void insertApplication(Connection unitOfWork, BnplCreditBalanceApplication application) {
        Objects.requireNonNull(application, "application");
        Money amount = application.amount();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // created_at is the trigger's.
                "INSERT INTO bnpl.bnpl_credit_balance_application (id, obligation_id, billing_id, catch_up_sequence,"
                        + " currency, scale, amount_minor, agreement_id, agreement_version, allocation_engine, value_date,"
                        + " journal_entry_id, created_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, application.id());
            insert.setObject(2, application.obligation().value());
            if (application.billing().isPresent()) {
                insert.setObject(3, application.billing().get());
            } else {
                insert.setNull(3, Types.OTHER);
            }
            if (application.catchUp().isPresent()) {
                insert.setInt(4, application.catchUp().get());
            } else {
                insert.setNull(4, Types.INTEGER);
            }
            insert.setString(5, amount.currency().code());
            insert.setInt(6, amount.scale());
            insert.setLong(7, amount.minorUnits());
            insert.setObject(8, application.agreement().value());
            insert.setInt(9, application.agreementVersion());
            insert.setString(10, application.allocationEngine());
            insert.setDate(11, Date.valueOf(application.valueDate()));
            insert.setObject(12, application.entry().value());
            insert.setString(13, application.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL credit-balance application", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_credit_balance_allocation (id, application_id, ordinal, billing_id, component,"
                        + " currency, scale, amount_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int ordinal = 0;
            for (BnplCreditBalanceApplication.Line line : application.lines()) {
                insert.setObject(1, line.id());
                insert.setObject(2, application.id());
                insert.setInt(3, ++ordinal);
                insert.setObject(4, line.billing());
                insert.setString(5, line.component().name());
                insert.setString(6, line.amount().currency().code());
                insert.setInt(7, line.amount().scale());
                insert.setLong(8, line.amount().minorUnits());
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL credit-balance application's allocation", failure);
        }
    }

    @Override
    public boolean restamp(Connection unitOfWork, BnplObligationId obligation, LocalDate openDate) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(openDate, "openDate");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // The start of the date in the obligation's own pinned zone, computed by the database.
                "UPDATE bnpl.bnpl_obligation SET next_servicing_at = (CAST(? AS date)::timestamp AT TIME ZONE servicing_zone)"
                        + " WHERE id = ? AND status = 'ACTIVE'")) {
            update.setDate(1, Date.valueOf(openDate));
            update.setObject(2, obligation.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("re-stamping a BNPL servicing permit", failure);
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
