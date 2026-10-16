package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link InstalmentBillingStore} over plain JDBC (`P11-TSK-017`, ADR-0033): every failure its operation and SQLSTATE
 * only ({@link LendingStorageException}) - never an amount or a date of a borrower's account. Sums are the database's
 * exact {@code bigint} arithmetic over integer minor units - never a floating point, never a division.
 */
public final class JdbcInstalmentBillingStore implements InstalmentBillingStore {

    /** The billed items with what each has been paid, per component - the one derivation every reader shares. */
    private static final String BILLED_ITEMS = "SELECT b.id, b.loan_id, b.kind, b.schedule_id, b.schedule_version,"
            + " b.instalment_id, b.sequence, b.period_start, b.due_date, b.currency, b.scale, b.interest_due_minor,"
            + " b.principal_due_minor, b.agreement_id, b.agreement_version, b.schedule_engine_version,"
            + " b.accrual_engine_version, b.journal_entry_id,"
            + " lending.billing_component_billed(b.id, 'FEES') AS fees_billed,"
            + " b.interest_due_minor - lending.billing_component_billed(b.id, 'INTEREST') AS interest_waived,"
            + " b.principal_due_minor - lending.billing_component_billed(b.id, 'PRINCIPAL') AS principal_rescheduled,"
            + " lending.billing_component_paid(b.id, 'FEES') AS fees_paid,"
            + " lending.billing_component_paid(b.id, 'INTEREST') AS interest_paid,"
            + " lending.billing_component_paid(b.id, 'PRINCIPAL') AS principal_paid"
            + " FROM lending.instalment_billing b";

    @Override
    public Optional<BillableSchedule> latestSchedule(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        UUID id;
        int version;
        EngineVersion engine;
        Money instalment;
        int intendedDay;
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, schedule_version, schedule_engine_version, currency, scale, instalment_minor, intended_day"
                        + " FROM lending.repayment_schedule WHERE loan_id = ? ORDER BY schedule_version DESC LIMIT 1")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                id = row.getObject("id", UUID.class);
                version = row.getInt("schedule_version");
                engine = EngineVersion.of(LendingEngine.SCHEDULE, row.getInt("schedule_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("a schedule names an engine version this build holds"));
                instalment = Money.ofPersisted(row.getLong("instalment_minor"),
                        CurrencyCode.of(row.getString("currency").strip()), row.getInt("scale"));
                intendedDay = row.getInt("intended_day");
            }
        } catch (SQLException failure) {
            throw failed("reading a loan's latest schedule", failure);
        }
        List<BillableSchedule.Instalment> instalments = new ArrayList<>();
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, sequence, period_start, due_date FROM lending.loan_instalment WHERE schedule_id = ?"
                        + " ORDER BY sequence")) {
            select.setObject(1, id);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    instalments.add(new BillableSchedule.Instalment(rows.getObject("id", UUID.class),
                            rows.getInt("sequence"), new AccrualPeriod(rows.getObject("period_start", LocalDate.class),
                                    rows.getObject("due_date", LocalDate.class))));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading a schedule's instalments", failure);
        }
        return Optional.of(new BillableSchedule(id, version, engine, instalment, intendedDay, instalments));
    }

    @Override
    public PeriodAccruals periodAccruals(Connection unitOfWork, LoanId loan, AccrualPeriod period, Money zero) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(zero, "zero");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT count(*), coalesce(sum(posted_minor), 0)::bigint FROM lending.interest_accrual"
                        + " WHERE loan_id = ? AND period_start = ? AND period_end = ?")) {
            select.setObject(1, loan.value());
            select.setObject(2, period.start());
            select.setObject(3, period.endExclusive());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return new PeriodAccruals(row.getLong(1),
                        Money.ofPersisted(row.getLong(2), zero.currency(), zero.scale()));
            }
        } catch (SQLException failure) {
            throw failed("reading a period's accruals", failure);
        }
    }

    @Override
    public List<BilledItem> billedItems(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                BILLED_ITEMS + " WHERE b.loan_id = ? ORDER BY b.due_date")) {
            select.setObject(1, loan.value());
            return items(select);
        } catch (SQLException failure) {
            throw failed("reading a loan's billed items", failure);
        }
    }

    @Override
    public List<DueState.DueItem> dueItems(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        // Both kinds of billed item in one statement - a loan holds only one kind, but the derivation asks no question of
        // the loan: what it has billed is what it owes on.
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // What each component billed is lending.billing_component_billed's - the row's figures with the item's
                // assessed fees, less its approved waivers (P11-TSK-025) - the one sum the database's rules read too.
                "SELECT i.id, i.due_date, i.currency, i.scale,"
                        + " lending.billing_component_billed(i.id, 'FEES') AS fees_billed,"
                        + " lending.billing_component_billed(i.id, 'INTEREST') AS interest_billed,"
                        + " lending.billing_component_billed(i.id, 'PRINCIPAL') AS principal_billed,"
                        + " lending.billing_component_paid(i.id, 'FEES') AS fees_paid,"
                        + " lending.billing_component_paid(i.id, 'INTEREST') AS interest_paid,"
                        + " lending.billing_component_paid(i.id, 'PRINCIPAL') AS principal_paid"
                        + " FROM (SELECT b.id, b.due_date, b.currency, b.scale FROM lending.instalment_billing b"
                        + " WHERE b.loan_id = ? UNION ALL SELECT s.id, s.due_date, s.currency, s.scale"
                        + " FROM lending.credit_line_statement s WHERE s.loan_id = ?) i"
                        + " ORDER BY i.due_date")) {
            select.setObject(1, loan.value());
            select.setObject(2, loan.value());
            List<DueState.DueItem> items = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                    int scale = row.getInt("scale");
                    items.add(new DueState.DueItem(
                            DueItemId.of(row.getObject("id", UUID.class)),
                            row.getObject("due_date", LocalDate.class),
                            Money.ofPersisted(row.getLong("fees_billed") - row.getLong("fees_paid"), currency, scale),
                            Money.ofPersisted(row.getLong("interest_billed") - row.getLong("interest_paid"), currency,
                                    scale),
                            Money.ofPersisted(row.getLong("principal_billed") - row.getLong("principal_paid"), currency,
                                    scale)));
                }
            }
            return items;
        } catch (SQLException failure) {
            throw failed("reading a loan's due items", failure);
        }
    }

    @Override
    public List<BilledItem> billedItemsOwnedBy(Connection unitOfWork, LoanId loan, UUID party) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(party, "party");
        try (PreparedStatement select = unitOfWork.prepareStatement(BILLED_ITEMS
                + " JOIN lending.loan l ON l.id = b.loan_id WHERE b.loan_id = ? AND l.party_id = ? ORDER BY b.due_date")) {
            select.setObject(1, loan.value());
            select.setObject(2, party);
            return items(select);
        } catch (SQLException failure) {
            throw failed("reading a party's loan's billed items", failure);
        }
    }

    private static List<BilledItem> items(PreparedStatement select) throws SQLException {
        List<BilledItem> items = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                items.add(item(row));
            }
        }
        return items;
    }

    private static BilledItem item(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        int scale = row.getInt("scale");
        UUID instalment = row.getObject("instalment_id", UUID.class);
        UUID entry = row.getObject("journal_entry_id", UUID.class);
        InstalmentBilling billing = new InstalmentBilling(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                BillingKind.valueOf(row.getString("kind")),
                row.getObject("schedule_id", UUID.class),
                row.getInt("schedule_version"),
                Optional.ofNullable(instalment),
                row.getInt("sequence"),
                new AccrualPeriod(row.getObject("period_start", LocalDate.class), row.getObject("due_date", LocalDate.class)),
                Money.ofPersisted(row.getLong("interest_due_minor"), currency, scale),
                Money.ofPersisted(row.getLong("principal_due_minor"), currency, scale),
                LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                row.getInt("agreement_version"),
                EngineVersion.of(LendingEngine.SCHEDULE, row.getInt("schedule_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("a billing names an engine version this build holds")),
                EngineVersion.of(LendingEngine.ACCRUAL, row.getInt("accrual_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("a billing names an engine version this build holds")),
                Optional.ofNullable(entry).map(JournalEntryId::of));
        return new BilledItem(billing,
                Money.ofPersisted(row.getLong("fees_billed"), currency, scale),
                Money.ofPersisted(row.getLong("interest_waived"), currency, scale),
                Money.ofPersisted(row.getLong("principal_rescheduled"), currency, scale),
                Money.ofPersisted(row.getLong("fees_paid"), currency, scale),
                Money.ofPersisted(row.getLong("interest_paid"), currency, scale),
                Money.ofPersisted(row.getLong("principal_paid"), currency, scale));
    }

    @Override
    public void insertBilling(Connection unitOfWork, InstalmentBilling billing, CorrelationId correlation) {
        Objects.requireNonNull(billing, "billing");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.instalment_billing (id, loan_id, kind, schedule_id, schedule_version, instalment_id,"
                        + " sequence, period_start, due_date, currency, scale, interest_due_minor, principal_due_minor,"
                        + " agreement_id, agreement_version, schedule_engine_version, accrual_engine_version,"
                        + " journal_entry_id, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, billing.id());
            insert.setObject(++i, billing.loan().value());
            insert.setString(++i, billing.kind().name());
            insert.setObject(++i, billing.schedule());
            insert.setInt(++i, billing.scheduleVersion());
            insert.setObject(++i, billing.instalment().orElse(null));
            insert.setInt(++i, billing.sequence());
            insert.setObject(++i, billing.period().start());
            insert.setObject(++i, billing.period().endExclusive());
            insert.setString(++i, billing.interestDue().currency().code());
            insert.setInt(++i, billing.interestDue().scale());
            insert.setLong(++i, billing.interestDue().minorUnits());
            insert.setLong(++i, billing.principalDue().minorUnits());
            insert.setObject(++i, billing.agreement().value());
            insert.setInt(++i, billing.agreementVersion());
            insert.setInt(++i, billing.scheduleEngine().number());
            insert.setInt(++i, billing.accrualEngine().number());
            insert.setObject(++i, billing.entry().map(JournalEntryId::value).orElse(null));
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting an instalment billing", failure);
        }
    }

    @Override
    public void insertApplication(Connection unitOfWork, CreditBalanceApplication application, CorrelationId correlation) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(correlation, "correlation");
        Money amount = application.amount();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.credit_balance_application (id, billing_id, catch_up_sequence, loan_id, currency,"
                        + " scale, amount_minor, agreement_id, agreement_version, allocation_engine_version, journal_entry_id,"
                        + " created_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, application.id());
            insert.setObject(++i, application.billing().orElse(null));
            insert.setObject(++i, application.catchUpSequence().orElse(null), java.sql.Types.INTEGER);
            insert.setObject(++i, application.loan().value());
            insert.setString(++i, amount.currency().code());
            insert.setInt(++i, amount.scale());
            insert.setLong(++i, amount.minorUnits());
            insert.setObject(++i, application.agreement().value());
            insert.setInt(++i, application.agreementVersion());
            insert.setInt(++i, application.allocationEngine().number());
            insert.setObject(++i, application.entry().value());
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a credit-balance application", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.credit_balance_allocation (id, application_id, ordinal, billing_id, component,"
                        + " currency, scale, amount_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            int ordinal = 0;
            for (CreditBalanceApplication.Line row : application.lines()) {
                AllocationLine line = row.line();
                int i = 0;
                insert.setObject(++i, row.id());
                insert.setObject(++i, application.id());
                insert.setInt(++i, ++ordinal);
                insert.setObject(++i, line.item().orElseThrow().value());
                insert.setString(++i, line.component().orElseThrow().name());
                insert.setString(++i, line.amount().currency().code());
                insert.setInt(++i, line.amount().scale());
                insert.setLong(++i, line.amount().minorUnits());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("inserting a credit-balance allocation", failure);
        }
    }

    @Override
    public int latestCatchUpSequence(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT coalesce(max(catch_up_sequence), 0) FROM lending.credit_balance_application WHERE loan_id = ?")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a loan's latest catch-up application", failure);
        }
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
