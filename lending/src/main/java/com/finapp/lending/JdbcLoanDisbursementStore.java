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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanDisbursementStore} over plain JDBC (`P11-TSK-014`, ADR-0033): every time the database's, the customer's
 * schedule read owner-scoped in the statement itself (ADR-0031), every failure its operation and SQLSTATE only
 * ({@link LendingStorageException}).
 */
public final class JdbcLoanDisbursementStore implements LoanDisbursementStore {

    private static final String DISBURSEMENT_COLUMNS = "d.id, d.loan_id, d.status, d.payout_method_id, d.created_at,"
            + " d.deadline_at, d.journal_entry_id, d.value_date";

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
    public List<LoanId> claimDue(Connection unitOfWork, int limit, Duration permit) {
        Objects.requireNonNull(permit, "permit");
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one disbursement");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE lending.loan_disbursement SET next_attempt_at = statement_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM lending.loan_disbursement WHERE status = 'PENDING'"
                        + " AND next_attempt_at <= statement_timestamp()"
                        + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status = 'PENDING' RETURNING loan_id")) {
            claim.setLong(1, permit.toMillis());
            claim.setInt(2, limit);
            List<LoanId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(LoanId.of(rows.getObject(1, UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming due loan disbursements", failure);
        }
    }

    @Override
    public Optional<Disbursement> lock(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + DISBURSEMENT_COLUMNS + " FROM lending.loan_disbursement d WHERE d.loan_id = ? FOR UPDATE")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(disbursement(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("locking a loan disbursement", failure);
        }
    }

    @Override
    public boolean post(Connection unitOfWork, LoanId loan, JournalEntryId entry, LocalDate valueDate) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(valueDate, "valueDate");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_disbursement SET status = 'POSTED', journal_entry_id = ?, value_date = ?"
                        + " WHERE loan_id = ? AND status = 'PENDING'")) {
            update.setObject(1, entry.value());
            update.setObject(2, valueDate);
            update.setObject(3, loan.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("posting a loan disbursement", failure);
        }
    }

    @Override
    public boolean fail(Connection unitOfWork, LoanId loan, CancellationReason reason) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(reason, "reason");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_disbursement SET status = 'FAILED', failure_reason = ?"
                        + " WHERE loan_id = ? AND status = 'PENDING'")) {
            update.setString(1, reason.name());
            update.setObject(2, loan.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("failing a loan disbursement", failure);
        }
    }

    @Override
    public void insertAccrualStart(Connection unitOfWork, UUID id, AccrualStart start, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.accrual_start (id, loan_id, start_date, origin, journal_entry_id, created_at,"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, start.loan().value());
            insert.setObject(3, start.startDate());
            insert.setString(4, start.origin().name());
            insert.setObject(5, start.journalEntry().map(JournalEntryId::value).orElse(null), java.sql.Types.OTHER);
            insert.setString(6, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting an accrual start", failure);
        }
    }

    @Override
    public void insertSchedule(
            Connection unitOfWork, RepaymentSchedule schedule, List<LoanInstalment> rows, CorrelationId correlation) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(correlation, "correlation");
        Schedule projected = schedule.schedule();
        try (PreparedStatement header = unitOfWork.prepareStatement(
                "INSERT INTO lending.repayment_schedule (id, loan_id, schedule_version, agreement_id, agreement_version,"
                        + " schedule_engine_version, currency, scale, principal_minor, instalment_minor,"
                        + " final_instalment_minor, total_interest_minor, term_months, start_date, intended_day,"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " created_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " statement_timestamp(), ?)")) {
            int i = 0;
            header.setObject(++i, schedule.id());
            header.setObject(++i, schedule.loan().value());
            header.setInt(++i, schedule.version());
            header.setObject(++i, schedule.agreement().value());
            header.setInt(++i, schedule.agreementVersion());
            header.setInt(++i, projected.engine().number());
            header.setString(++i, projected.currency().code());
            header.setInt(++i, projected.principal().scale());
            header.setLong(++i, projected.principal().minorUnits());
            header.setLong(++i, projected.instalment().minorUnits());
            header.setLong(++i, projected.finalInstalment().minorUnits());
            header.setLong(++i, projected.totalInterest().minorUnits());
            header.setInt(++i, projected.termMonths());
            header.setObject(++i, projected.start());
            header.setInt(++i, projected.intendedDay());
            header.setString(++i, correlation.value());
            header.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a repayment schedule", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_instalment (id, schedule_id, loan_id, sequence, period_start, due_date, days,"
                        + " currency, scale, opening_principal_minor, interest_minor, principal_minor, amount_minor,"
                        + " closing_principal_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (LoanInstalment row : rows) {
                SchedulePeriod period = row.period();
                int i = 0;
                insert.setObject(++i, row.id());
                insert.setObject(++i, row.schedule());
                insert.setObject(++i, schedule.loan().value());
                insert.setInt(++i, period.sequence());
                insert.setObject(++i, period.periodStart());
                insert.setObject(++i, period.dueDate());
                insert.setInt(++i, Math.toIntExact(period.days()));
                insert.setString(++i, projected.currency().code());
                insert.setInt(++i, period.amount().scale());
                insert.setLong(++i, period.openingBalance().minorUnits());
                insert.setLong(++i, period.interest().minorUnits());
                insert.setLong(++i, period.principal().minorUnits());
                insert.setLong(++i, period.amount().minorUnits());
                insert.setLong(++i, period.closingBalance().minorUnits());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("inserting a schedule's instalments", failure);
        }
    }

    @Override
    public Optional<RepaymentSchedule> scheduleOwnedBy(
            Connection unitOfWork, LoanId loan, UUID party, Optional<Integer> version) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(version, "version");
        UUID id;
        int scheduleVersion;
        int agreementVersion;
        LoanAgreementId agreement;
        EngineVersion engine;
        CurrencyCode currency;
        int scale;
        long principal;
        long instalment;
        BigDecimal rate;
        LocalDate start;
        int intendedDay;
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT s.id, s.schedule_version, s.agreement_id, s.agreement_version, s.schedule_engine_version,"
                        + " s.currency, s.scale, s.principal_minor, s.instalment_minor, s.start_date, s.intended_day,"
                        + " a.nominal_annual_rate"
                        + " FROM lending.repayment_schedule s"
                        + " JOIN lending.loan l ON l.id = s.loan_id"
                        + " JOIN lending.loan_agreement a ON a.id = s.agreement_id"
                        + " WHERE s.loan_id = ? AND l.party_id = ? AND (CAST(? AS integer) IS NULL OR s.schedule_version = ?)"
                        + " ORDER BY s.schedule_version DESC LIMIT 1")) {
            select.setObject(1, loan.value());
            select.setObject(2, party);
            select.setObject(3, version.orElse(null), java.sql.Types.INTEGER);
            select.setObject(4, version.orElse(null), java.sql.Types.INTEGER);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                id = row.getObject("id", UUID.class);
                scheduleVersion = row.getInt("schedule_version");
                agreement = LoanAgreementId.of(row.getObject("agreement_id", UUID.class));
                agreementVersion = row.getInt("agreement_version");
                engine = EngineVersion.of(LendingEngine.SCHEDULE, row.getInt("schedule_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("a schedule names an engine version this build holds"));
                currency = CurrencyCode.of(row.getString("currency").strip());
                scale = row.getInt("scale");
                principal = row.getLong("principal_minor");
                instalment = row.getLong("instalment_minor");
                start = row.getObject("start_date", LocalDate.class);
                intendedDay = row.getInt("intended_day");
                rate = new BigDecimal(row.getString("nominal_annual_rate"));
            }
        } catch (SQLException failure) {
            throw failed("reading a repayment schedule", failure);
        }
        List<SchedulePeriod> periods = new ArrayList<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT sequence, period_start, due_date, days, opening_principal_minor, interest_minor, principal_minor,"
                        + " amount_minor, closing_principal_minor FROM lending.loan_instalment WHERE schedule_id = ?"
                        + " ORDER BY sequence")) {
            select.setObject(1, id);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    periods.add(new SchedulePeriod(
                            rows.getInt("sequence"),
                            rows.getObject("period_start", LocalDate.class),
                            rows.getObject("due_date", LocalDate.class),
                            rows.getInt("days"),
                            money(rows.getLong("opening_principal_minor"), currency, scale),
                            money(rows.getLong("interest_minor"), currency, scale),
                            money(rows.getLong("principal_minor"), currency, scale),
                            money(rows.getLong("amount_minor"), currency, scale),
                            money(rows.getLong("closing_principal_minor"), currency, scale)));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading a schedule's instalments", failure);
        }
        Schedule schedule = new Schedule(engine, currency, money(principal, currency, scale), rate, start, intendedDay,
                money(instalment, currency, scale), periods);
        return Optional.of(new RepaymentSchedule(id, loan, scheduleVersion, agreement, agreementVersion, schedule));
    }

    @Override
    public Optional<Duration> oldestPendingAge(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT floor(extract(epoch FROM statement_timestamp() - min(created_at)) * 1000)::bigint"
                        + " FROM lending.loan_disbursement WHERE status = 'PENDING'");
                ResultSet row = select.executeQuery()) {
            row.next();
            long millis = row.getLong(1);
            return row.wasNull() ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(0, millis)));
        } catch (SQLException failure) {
            throw failed("reading the oldest pending disbursement", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Disbursement disbursement(ResultSet row) throws SQLException {
        UUID entry = row.getObject("journal_entry_id", UUID.class);
        return new Disbursement(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                DisbursementStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getObject("payout_method_id", UUID.class)),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("deadline_at").toInstant(),
                Optional.ofNullable(entry).map(JournalEntryId::of),
                Optional.ofNullable(row.getObject("value_date", LocalDate.class)));
    }

    private static Money money(long minor, CurrencyCode currency, int scale) {
        return Money.ofPersisted(minor, currency, scale);
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
