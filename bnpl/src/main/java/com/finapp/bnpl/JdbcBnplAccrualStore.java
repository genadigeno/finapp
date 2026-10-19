package com.finapp.bnpl;

import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.Act365Fixed;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.ledger.JournalEntryId;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplAccrualStore} over plain JDBC (`P12-TSK-017`, ADR-0033; lending's {@code JdbcLoanServicingStore} accrual half):
 * every instant the database's, every failure its operation and SQLSTATE only ({@link BnplStorageException}) - never an
 * amount or a date of a customer's obligation.
 */
public final class JdbcBnplAccrualStore implements BnplAccrualStore {

    @Override
    public Optional<BnplInterestAccrual> latest(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, accrual_date, period_start, period_end, currency, scale, base_minor, cumulative_exact,"
                        + " posted_minor, agreement_id, agreement_version, accrual_engine, journal_entry_id, correlation_id"
                        + " FROM bnpl.bnpl_interest_accrual WHERE obligation_id = ? ORDER BY accrual_date DESC LIMIT 1")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                int scale = row.getInt("scale");
                InterestAccrual accrual = new InterestAccrual(row.getDate("accrual_date").toLocalDate(),
                        new AccrualPeriod(row.getDate("period_start").toLocalDate(),
                                row.getDate("period_end").toLocalDate()),
                        Money.ofPersisted(row.getLong("base_minor"), currency, scale),
                        row.getBigDecimal("cumulative_exact").setScale(Act365Fixed.EXACT_SCALE),
                        Money.ofPersisted(row.getLong("posted_minor"), currency, scale),
                        EngineVersion.valueOf(row.getString("accrual_engine")));
                UUID entry = row.getObject("journal_entry_id", UUID.class);
                return Optional.of(new BnplInterestAccrual(row.getObject("id", UUID.class), obligation, accrual,
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                        Optional.ofNullable(entry).map(JournalEntryId::of),
                        CorrelationId.of(row.getString("correlation_id"))));
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's latest accrual", failure);
        }
    }

    @Override
    public void insert(Connection unitOfWork, BnplInterestAccrual row) {
        Objects.requireNonNull(row, "row");
        InterestAccrual accrual = row.accrual();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // created_at is the trigger's: clock_timestamp() after its B4.
                "INSERT INTO bnpl.bnpl_interest_accrual (id, obligation_id, accrual_date, period_start, period_end,"
                        + " currency, scale, base_minor, cumulative_exact, posted_minor, agreement_id, agreement_version,"
                        + " accrual_engine, journal_entry_id, created_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, row.id());
            insert.setObject(2, row.obligation().value());
            insert.setDate(3, Date.valueOf(accrual.date()));
            insert.setDate(4, Date.valueOf(accrual.period().start()));
            insert.setDate(5, Date.valueOf(accrual.period().endExclusive()));
            insert.setString(6, accrual.base().currency().code());
            insert.setInt(7, accrual.base().scale());
            insert.setLong(8, accrual.base().minorUnits());
            insert.setBigDecimal(9, accrual.cumulativeExact());
            insert.setLong(10, accrual.posted().minorUnits());
            insert.setObject(11, row.agreement().value());
            insert.setInt(12, row.agreementVersion());
            insert.setString(13, accrual.engine().name());
            if (row.entry().isPresent()) {
                insert.setObject(14, row.entry().get().value());
            } else {
                insert.setNull(14, Types.OTHER);
            }
            insert.setString(15, row.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL interest accrual", failure);
        }
    }

    @Override
    public Optional<Duration> oldestLag(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // Per ACTIVE pay-in-N obligation: its next date to accrue (the day after its latest accrual, else its
                // purchase's business date) and its span's end (the latest schedule version's final due date). Lagging
                // when that next date has ended on the database clock in the pinned zone and lies inside the span.
                "SELECT floor(extract(epoch FROM max(statement_timestamp()"
                        + " - ((x.next_date + 1)::timestamp AT TIME ZONE x.zone))) * 1000)::bigint"
                        + " FROM (SELECT o.servicing_zone AS zone,"
                        + " COALESCE((SELECT max(a.accrual_date) + 1 FROM bnpl.bnpl_interest_accrual a"
                        + " WHERE a.obligation_id = o.id), o.purchase_date) AS next_date,"
                        + " (SELECT max(i.due_date) FROM bnpl.bnpl_instalment i JOIN bnpl.bnpl_schedule s"
                        + " ON s.id = i.schedule_id WHERE s.obligation_id = o.id AND s.schedule_version ="
                        + " (SELECT max(v.schedule_version) FROM bnpl.bnpl_schedule v WHERE v.obligation_id = o.id))"
                        + " AS span_end"
                        + " FROM bnpl.bnpl_obligation o WHERE o.status = 'ACTIVE' AND o.product = 'BNPL_PAY_IN_N') x"
                        + " WHERE x.next_date < LEAST(bnpl.business_date(statement_timestamp(), x.zone), x.span_end)");
                ResultSet row = select.executeQuery()) {
            row.next();
            long millis = row.getLong(1);
            return row.wasNull() ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(0, millis)));
        } catch (SQLException failure) {
            throw failed("reading the oldest unaccrued BNPL date", failure);
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
