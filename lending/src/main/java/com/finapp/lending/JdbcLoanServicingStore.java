package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanServicingStore} over plain JDBC (`P11-TSK-016`, ADR-0033): every time the database's, every failure its
 * operation and SQLSTATE only ({@link LendingStorageException}) - never an amount or a date of a borrower's account.
 */
public final class JdbcLoanServicingStore implements LoanServicingStore {

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT statement_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public List<LoanId> claimDue(Connection unitOfWork, int limit, Duration retryCadence) {
        Objects.requireNonNull(retryCadence, "retryCadence");
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one account");
        }
        if (retryCadence.isNegative() || retryCadence.isZero()) {
            throw new IllegalArgumentException("the retry cadence is positive");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE lending.loan SET next_servicing_at = statement_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM lending.loan WHERE status IN ('ACTIVE', 'CLOSING')"
                        + " AND next_servicing_at <= statement_timestamp()"
                        + " ORDER BY next_servicing_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status IN ('ACTIVE', 'CLOSING') RETURNING id")) {
            claim.setLong(1, retryCadence.toMillis());
            claim.setInt(2, limit);
            List<LoanId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(LoanId.of(rows.getObject(1, UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming accounts due servicing", failure);
        }
    }

    @Override
    public boolean restamp(Connection unitOfWork, LoanId loan, Instant permit) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(permit, "permit");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan SET next_servicing_at = ? WHERE id = ? AND status IN ('ACTIVE', 'CLOSING')")) {
            update.setTimestamp(1, Timestamp.from(permit));
            update.setObject(2, loan.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("re-stamping a servicing permit", failure);
        }
    }

    @Override
    public Optional<LocalDate> accrualStartOf(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT start_date FROM lending.accrual_start WHERE loan_id = ?")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getObject(1, LocalDate.class)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading an accrual start", failure);
        }
    }

    @Override
    public Optional<InstalmentCalendar> calendarOf(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT s.intended_day, i.period_start, i.due_date"
                        + " FROM lending.repayment_schedule s JOIN lending.loan_instalment i ON i.schedule_id = s.id"
                        + " WHERE s.loan_id = ? AND s.schedule_version ="
                        + " (SELECT max(x.schedule_version) FROM lending.repayment_schedule x WHERE x.loan_id = ?)"
                        + " ORDER BY i.sequence")) {
            select.setObject(1, loan.value());
            select.setObject(2, loan.value());
            List<AccrualPeriod> periods = new ArrayList<>();
            int intendedDay = 0;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    intendedDay = rows.getInt("intended_day");
                    periods.add(new AccrualPeriod(rows.getObject("period_start", LocalDate.class),
                            rows.getObject("due_date", LocalDate.class)));
                }
            }
            return periods.isEmpty() ? Optional.empty() : Optional.of(new InstalmentCalendar(periods, intendedDay));
        } catch (SQLException failure) {
            throw failed("reading a schedule's periods", failure);
        }
    }

    @Override
    public Optional<RecordedAccrual> latestAccrual(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, accrual_date, period_start, period_end, currency, scale, base_minor, cumulative_exact,"
                        + " posted_minor, agreement_id, agreement_version, accrual_engine_version, journal_entry_id"
                        + " FROM lending.interest_accrual WHERE loan_id = ? ORDER BY accrual_date DESC LIMIT 1")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                int scale = row.getInt("scale");
                EngineVersion engine = EngineVersion.of(LendingEngine.ACCRUAL, row.getInt("accrual_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("an accrual names an engine version this build holds"));
                BigDecimal cumulative = row.getBigDecimal("cumulative_exact").setScale(Act365Fixed.EXACT_SCALE);
                InterestAccrual accrual = new InterestAccrual(
                        row.getObject("accrual_date", LocalDate.class),
                        new AccrualPeriod(row.getObject("period_start", LocalDate.class),
                                row.getObject("period_end", LocalDate.class)),
                        Money.ofPersisted(row.getLong("base_minor"), currency, scale),
                        cumulative,
                        Money.ofPersisted(row.getLong("posted_minor"), currency, scale),
                        engine);
                UUID entry = row.getObject("journal_entry_id", UUID.class);
                return Optional.of(new RecordedAccrual(
                        row.getObject("id", UUID.class),
                        loan,
                        accrual,
                        LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                        row.getInt("agreement_version"),
                        Optional.ofNullable(entry).map(JournalEntryId::of)));
            }
        } catch (SQLException failure) {
            throw failed("reading an account's latest accrual", failure);
        }
    }

    @Override
    public void insertAccrual(Connection unitOfWork, RecordedAccrual accrual, CorrelationId correlation) {
        Objects.requireNonNull(accrual, "accrual");
        Objects.requireNonNull(correlation, "correlation");
        InterestAccrual figures = accrual.accrual();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.interest_accrual (id, loan_id, accrual_date, period_start, period_end, currency,"
                        + " scale, base_minor, cumulative_exact, posted_minor, agreement_id, agreement_version,"
                        + " accrual_engine_version, journal_entry_id, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, accrual.id());
            insert.setObject(++i, accrual.loan().value());
            insert.setObject(++i, figures.date());
            insert.setObject(++i, figures.period().start());
            insert.setObject(++i, figures.period().endExclusive());
            insert.setString(++i, figures.base().currency().code());
            insert.setInt(++i, figures.base().scale());
            insert.setLong(++i, figures.base().minorUnits());
            insert.setBigDecimal(++i, figures.cumulativeExact());
            insert.setLong(++i, figures.posted().minorUnits());
            insert.setObject(++i, accrual.agreement().value());
            insert.setInt(++i, accrual.agreementVersion());
            insert.setInt(++i, figures.engine().number());
            insert.setObject(++i, accrual.entry().map(JournalEntryId::value).orElse(null));
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting an interest accrual", failure);
        }
    }

    @Override
    public Optional<Duration> oldestUnaccruedLag(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT floor(extract(epoch FROM max(statement_timestamp()"
                        + " - ((x.next_date + 1)::timestamp - x.zone_offset) AT TIME ZONE 'UTC')) * 1000)::bigint"
                        + " FROM (SELECT COALESCE((SELECT max(a.accrual_date) + 1 FROM lending.interest_accrual a"
                        + " WHERE a.loan_id = l.id), s.start_date) AS next_date, t.servicing_zone AS zone,"
                        + " CASE WHEN t.servicing_zone = 'UTC' THEN interval '0'"
                        + " ELSE CAST(t.servicing_zone AS interval) END AS zone_offset"
                        + " FROM lending.loan l JOIN lending.accrual_start s ON s.loan_id = l.id"
                        + " JOIN lending.loan_offer o ON o.id = l.offer_id"
                        + " JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id"
                        + " WHERE l.status IN ('ACTIVE', 'CLOSING')) x"
                        + " WHERE x.next_date < lending.business_date(statement_timestamp(), x.zone)");
                ResultSet row = select.executeQuery()) {
            row.next();
            long millis = row.getLong(1);
            return row.wasNull() ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(0, millis)));
        } catch (SQLException failure) {
            throw failed("reading the oldest unaccrued date", failure);
        }
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
