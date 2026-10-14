package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The JDBC reader of a loan's whole record (`P11-TSK-029`): one plain {@code SELECT} per lending table, every row of the
 * loan in a fixed order, then the pinned terms versions and the offer through their own stores, and the loan's ledger
 * accounts and every journal line on them through the ledger's API ({@link LedgerAccountStore#findAllOwned},
 * {@link PurposeBalances#datedLinesOn}) - never a ledger table read here, never the projection. Writes nothing.
 */
@RequiredArgsConstructor
public final class JdbcLoanRecordStore implements LoanRecordStore {

    /** Every journal line, whatever its value date: the latest date the database and the driver both carry. */
    static final LocalDate EVERY_VALUE_DATE = LocalDate.of(9999, 12, 31);

    @NonNull private final LoanOfferStore offers;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PurposeBalances<Connection> balances;

    @Override
    public List<LoanId> loanIds(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT id FROM lending.loan ORDER BY opened_at, id");
                ResultSet rows = select.executeQuery()) {
            List<LoanId> ids = new ArrayList<>();
            while (rows.next()) {
                ids.add(LoanId.of(rows.getObject("id", UUID.class)));
            }
            return List.copyOf(ids);
        } catch (SQLException failure) {
            throw failed("listing the loans", failure);
        }
    }

    @Override
    public Optional<LoanRecord> read(Connection c, LoanId loan) {
        Objects.requireNonNull(c, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        UUID id = loan.value();
        List<LoanRecord.Account> accounts = query(c, "SELECT id, party_id, product, status, currency, offer_id, decision_id,"
                + " opened_at, closed_at, closure_reason, cancellation_reason FROM lending.loan WHERE id = ?", id, row -> {
                    LoanProduct product = LoanProduct.valueOf(row.getString("product"));
                    return new LoanRecord.Account(LoanId.of(row.getObject("id", UUID.class)),
                            row.getObject("party_id", UUID.class), product, product.kind(),
                            LoanStatus.valueOf(row.getString("status")), currency(row),
                            LoanOfferId.of(row.getObject("offer_id", UUID.class)), row.getObject("decision_id", UUID.class),
                            instant(row, "opened_at"), optionalInstant(row, "closed_at"),
                            Optional.ofNullable(row.getString("closure_reason")),
                            Optional.ofNullable(row.getString("cancellation_reason")));
                });
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        LoanRecord.Account account = accounts.get(0);

        List<LoanRecord.Agreement> agreements = query(c, "SELECT * FROM lending.loan_agreement WHERE loan_id = ?"
                + " ORDER BY version", id, row -> new LoanRecord.Agreement(row.getObject("id", UUID.class),
                        row.getInt("version"), uuid(row, "offer_id"), uuid(row, "amendment_id"),
                        uuid(row, "prepayment_id"), row.getObject("decision_id", UUID.class),
                        row.getObject("terms_version_id", UUID.class), LoanProduct.valueOf(row.getString("product")),
                        row.getInt("scale"), row.getLong("amount_minor"), integer(row, "term_months"),
                        row.getString("nominal_annual_rate"), row.getInt("repayment_day"),
                        integer(row, "schedule_engine_version"), row.getInt("accrual_engine_version"),
                        row.getInt("allocation_engine_version"), integer(row, "statement_engine_version"),
                        row.getString("agreement_template_id"), row.getInt("agreement_template_version"),
                        row.getInt("canonical_format"), row.getString("canonical_terms"), row.getBytes("terms_sha256"),
                        instant(row, "effective_from"), instant(row, "created_at"), order(row, "recorded_order")));

        Optional<LoanRecord.Disbursement> disbursement = first(query(c, "SELECT id, status, payout_method_id, journal_entry_id,"
                + " value_date, decided_at, created_at FROM lending.loan_disbursement WHERE loan_id = ?", id,
                row -> new LoanRecord.Disbursement(row.getObject("id", UUID.class), row.getString("status"),
                        uuid(row, "payout_method_id"), entry(row, "journal_entry_id"), date(row, "value_date"),
                        optionalInstant(row, "decided_at"), instant(row, "created_at"))));
        Optional<LoanRecord.AccrualStartRow> accrualStart = first(query(c, "SELECT start_date, origin, journal_entry_id,"
                + " created_at FROM lending.accrual_start WHERE loan_id = ?", id,
                row -> new LoanRecord.AccrualStartRow(row.getObject("start_date", LocalDate.class), row.getString("origin"),
                        entry(row, "journal_entry_id"), instant(row, "created_at"))));
        Optional<LoanRecord.Payout> payout = first(query(c, "SELECT id, status, withdrawal_id, not_dispatched_reason, created_at,"
                + " dispatched_at, concluded_at FROM lending.loan_payout WHERE loan_id = ?", id,
                row -> new LoanRecord.Payout(row.getObject("id", UUID.class), row.getString("status"),
                        uuid(row, "withdrawal_id"), Optional.ofNullable(row.getString("not_dispatched_reason")),
                        instant(row, "created_at"), optionalInstant(row, "dispatched_at"),
                        optionalInstant(row, "concluded_at"))));

        Map<UUID, List<LoanRecord.Instalment>> instalments = new HashMap<>();
        for (Map.Entry<UUID, LoanRecord.Instalment> pair : query(c, "SELECT * FROM lending.loan_instalment"
                + " WHERE loan_id = ? ORDER BY schedule_id, sequence", id, row -> Map.entry(
                        row.getObject("schedule_id", UUID.class), new LoanRecord.Instalment(row.getObject("id", UUID.class),
                                row.getInt("sequence"), row.getObject("period_start", LocalDate.class),
                                row.getObject("due_date", LocalDate.class), row.getInt("days"),
                                money(row, "opening_principal_minor"), money(row, "interest_minor"),
                                money(row, "principal_minor"), money(row, "amount_minor"),
                                money(row, "closing_principal_minor"))))) {
            instalments.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue());
        }
        List<LoanRecord.ScheduleVersion> schedules = query(c, "SELECT * FROM lending.repayment_schedule WHERE loan_id = ?"
                + " ORDER BY schedule_version", id, row -> new LoanRecord.ScheduleVersion(row.getObject("id", UUID.class),
                        row.getInt("schedule_version"), row.getObject("agreement_id", UUID.class),
                        row.getInt("agreement_version"), row.getInt("schedule_engine_version"),
                        money(row, "principal_minor"), money(row, "instalment_minor"),
                        money(row, "final_instalment_minor"), money(row, "total_interest_minor"), row.getInt("term_months"),
                        row.getObject("start_date", LocalDate.class), row.getInt("intended_day"),
                        instant(row, "created_at"),
                        instalments.getOrDefault(row.getObject("id", UUID.class), List.of()), order(row, "recorded_order")));

        List<LoanRecord.Draw> draws = query(c, "SELECT * FROM lending.credit_line_draw WHERE loan_id = ?"
                + " ORDER BY created_at, id", id, row -> new LoanRecord.Draw(row.getObject("id", UUID.class),
                        money(row, "amount_minor"), row.getObject("agreement_id", UUID.class),
                        row.getInt("agreement_version"), JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                        row.getObject("value_date", LocalDate.class), instant(row, "created_at"),
                        order(row, "recorded_order")));
        List<LoanRecord.Accrual> accruals = query(c, "SELECT * FROM lending.interest_accrual WHERE loan_id = ?"
                + " ORDER BY accrual_date", id, row -> new LoanRecord.Accrual(row.getObject("id", UUID.class),
                        row.getObject("accrual_date", LocalDate.class), row.getObject("period_start", LocalDate.class),
                        row.getObject("period_end", LocalDate.class), money(row, "base_minor"),
                        row.getBigDecimal("cumulative_exact"), money(row, "posted_minor"),
                        row.getObject("agreement_id", UUID.class), row.getInt("agreement_version"),
                        row.getInt("accrual_engine_version"), entry(row, "journal_entry_id"), instant(row, "created_at"),
                        order(row, "recorded_order")));
        List<LoanRecord.Billing> billings = query(c, "SELECT * FROM lending.instalment_billing WHERE loan_id = ?"
                + " ORDER BY due_date", id, row -> new LoanRecord.Billing(row.getObject("id", UUID.class),
                        row.getString("kind"), row.getObject("schedule_id", UUID.class), row.getInt("schedule_version"),
                        uuid(row, "instalment_id"), row.getInt("sequence"), row.getObject("period_start", LocalDate.class),
                        row.getObject("due_date", LocalDate.class), money(row, "interest_due_minor"),
                        money(row, "principal_due_minor"), row.getObject("agreement_id", UUID.class),
                        row.getInt("agreement_version"), row.getInt("schedule_engine_version"),
                        row.getInt("accrual_engine_version"), entry(row, "journal_entry_id"), instant(row, "created_at"),
                        order(row, "recorded_order")));
        List<LoanRecord.StatementRow> statements = query(c, "SELECT * FROM lending.credit_line_statement WHERE loan_id = ?"
                + " ORDER BY cycle_end", id, row -> new LoanRecord.StatementRow(row.getObject("id", UUID.class),
                        row.getObject("cycle_start", LocalDate.class), row.getObject("cycle_end", LocalDate.class),
                        row.getObject("due_date", LocalDate.class), money(row, "interest_billed_minor"),
                        money(row, "fees_carried_minor"), money(row, "principal_billed_minor"),
                        money(row, "minimum_payment_minor"), row.getObject("agreement_id", UUID.class),
                        row.getInt("agreement_version"), row.getInt("statement_engine_version"),
                        row.getInt("accrual_engine_version"), entry(row, "journal_entry_id"), instant(row, "created_at"),
                        order(row, "recorded_order")));

        Map<UUID, List<LoanRecord.ApplicationLine>> applicationLines = new HashMap<>();
        for (Map.Entry<UUID, LoanRecord.ApplicationLine> pair : query(c, "SELECT x.* FROM lending.credit_balance_allocation x"
                + " JOIN lending.credit_balance_application a ON a.id = x.application_id WHERE a.loan_id = ?"
                + " ORDER BY x.application_id, x.ordinal", id, row -> Map.entry(row.getObject("application_id", UUID.class),
                        new LoanRecord.ApplicationLine(row.getInt("ordinal"), row.getObject("billing_id", UUID.class),
                                AllocationComponent.valueOf(row.getString("component")), money(row, "amount_minor"))))) {
            applicationLines.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue());
        }
        List<LoanRecord.Application> applications = query(c, "SELECT * FROM lending.credit_balance_application"
                + " WHERE loan_id = ? ORDER BY created_at, id", id, row -> new LoanRecord.Application(
                        row.getObject("id", UUID.class), row.getObject("billing_id", UUID.class),
                        money(row, "amount_minor"), row.getObject("agreement_id", UUID.class),
                        row.getInt("agreement_version"), row.getInt("allocation_engine_version"),
                        JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)), instant(row, "created_at"),
                        applicationLines.getOrDefault(row.getObject("id", UUID.class), List.of()),
                        order(row, "recorded_order")));

        Map<UUID, List<LoanRecord.PaymentLine>> paymentLines = new HashMap<>();
        for (Map.Entry<UUID, LoanRecord.PaymentLine> pair : query(c, "SELECT x.* FROM lending.repayment_allocation x"
                + " JOIN lending.repayment r ON r.id = x.repayment_id WHERE r.loan_id = ? ORDER BY x.repayment_id,"
                + " x.ordinal", id, row -> Map.entry(row.getObject("repayment_id", UUID.class),
                        new LoanRecord.PaymentLine(row.getObject("id", UUID.class), row.getInt("ordinal"),
                                AllocationLine.Target.valueOf(row.getString("target")), uuid(row, "billing_id"),
                                Optional.ofNullable(row.getString("component")).map(AllocationComponent::valueOf),
                                money(row, "amount_minor"))))) {
            paymentLines.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue());
        }
        List<LoanRecord.Payment> repayments = query(c, "SELECT * FROM lending.repayment WHERE loan_id = ?"
                + " ORDER BY created_at, id", id, row -> new LoanRecord.Payment(row.getObject("id", UUID.class),
                        RepaymentKind.valueOf(row.getString("kind")), money(row, "amount_minor"),
                        row.getObject("agreement_id", UUID.class), row.getInt("agreement_version"),
                        row.getInt("allocation_engine_version"),
                        JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                        row.getObject("value_date", LocalDate.class), instant(row, "created_at"),
                        paymentLines.getOrDefault(row.getObject("id", UUID.class), List.of()),
                        order(row, "recorded_order")));

        Map<UUID, List<LoanRecord.Negation>> negations = new HashMap<>();
        for (Map.Entry<UUID, LoanRecord.Negation> pair : query(c, "SELECT n.* FROM lending.repayment_allocation_reversal n"
                + " JOIN lending.repayment_reversal x ON x.id = n.reversal_id WHERE x.loan_id = ? ORDER BY n.reversal_id,"
                + " n.allocation_id", id, row -> Map.entry(row.getObject("reversal_id", UUID.class),
                        new LoanRecord.Negation(row.getObject("allocation_id", UUID.class), money(row, "amount_minor"))))) {
            negations.computeIfAbsent(pair.getKey(), key -> new ArrayList<>()).add(pair.getValue());
        }
        List<LoanRecord.Reversal> reversals = query(c, "SELECT id, repayment_id, agreement_version, status, proposed_at,"
                + " decided_at, journal_entry_id, decided_order FROM lending.repayment_reversal WHERE loan_id = ? ORDER BY proposed_at, id",
                id, row -> new LoanRecord.Reversal(row.getObject("id", UUID.class), row.getObject("repayment_id", UUID.class),
                        row.getInt("agreement_version"), row.getString("status"), instant(row, "proposed_at"),
                        optionalInstant(row, "decided_at"), entry(row, "journal_entry_id"),
                        negations.getOrDefault(row.getObject("id", UUID.class), List.of()), order(row, "decided_order")));

        List<LoanRecord.Fee> fees = query(c, "SELECT * FROM lending.fee_assessment WHERE loan_id = ?"
                + " ORDER BY created_at, id", id, row -> new LoanRecord.Fee(row.getObject("id", UUID.class),
                        row.getObject("due_item_id", UUID.class), row.getString("kind"),
                        row.getObject("business_date", LocalDate.class), money(row, "amount_minor"),
                        row.getObject("agreement_id", UUID.class), row.getInt("agreement_version"),
                        JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)), instant(row, "created_at"),
                        order(row, "recorded_order")));
        List<LoanRecord.WaiverRow> waivers = query(c, "SELECT w.id, w.kind, w.fee_assessment_id, w.due_item_id, w.currency,"
                + " w.scale, w.amount_minor, w.agreement_version, w.proposed_at, d.outcome, d.decided_at, d.journal_entry_id,"
                + " d.recorded_order"
                + " FROM lending.waiver w LEFT JOIN lending.waiver_decision d ON d.waiver_id = w.id WHERE w.loan_id = ?"
                + " ORDER BY w.proposed_at, w.id", id, row -> new LoanRecord.WaiverRow(row.getObject("id", UUID.class),
                        WaiverKind.valueOf(row.getString("kind")), uuid(row, "fee_assessment_id"),
                        row.getObject("due_item_id", UUID.class), money(row, "amount_minor"),
                        row.getInt("agreement_version"), instant(row, "proposed_at"),
                        Optional.ofNullable(row.getString("outcome")), optionalInstant(row, "decided_at"),
                        entry(row, "journal_entry_id"), order(row, "recorded_order")));
        List<LoanRecord.Amendment> amendments = query(c, "SELECT m.id, m.amends_agreement_id, m.amends_version,"
                + " m.nominal_annual_rate, m.remaining_instalments, m.reschedule_arrears, m.document_sha256, m.proposed_at,"
                + " d.outcome, d.decided_at, d.expires_at FROM lending.loan_amendment m LEFT JOIN"
                + " lending.loan_amendment_decision d ON d.amendment_id = m.id WHERE m.loan_id = ? ORDER BY m.proposed_at,"
                + " m.id", id, row -> new LoanRecord.Amendment(row.getObject("id", UUID.class),
                        row.getObject("amends_agreement_id", UUID.class), row.getInt("amends_version"),
                        row.getString("nominal_annual_rate"), row.getInt("remaining_instalments"),
                        row.getBoolean("reschedule_arrears"), row.getBytes("document_sha256"), instant(row, "proposed_at"),
                        Optional.ofNullable(row.getString("outcome")), optionalInstant(row, "decided_at"),
                        optionalInstant(row, "expires_at")));
        List<LoanRecord.Rescheduling> reschedulings = query(c, "SELECT * FROM lending.loan_amendment_rescheduling"
                + " WHERE loan_id = ? ORDER BY created_at, id", id, row -> new LoanRecord.Rescheduling(
                        row.getObject("id", UUID.class), row.getObject("amendment_id", UUID.class),
                        row.getObject("billing_id", UUID.class), money(row, "principal_minor"),
                        JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)), instant(row, "created_at"),
                        order(row, "recorded_order")));
        Optional<LoanRecord.Closure> closure = first(query(c, "SELECT * FROM lending.loan_closure WHERE loan_id = ?", id,
                row -> new LoanRecord.Closure(row.getObject("id", UUID.class), row.getString("reason"),
                        money(row, "refund_minor"), entry(row, "refund_journal_entry_id"), uuid(row, "repayment_id"),
                        instant(row, "created_at"), order(row, "recorded_order"))));
        List<LoanRecord.PayoffQuoteRow> quotes = query(c, "SELECT id, business_date, good_through, currency, scale,"
                + " outstanding_minor, agreement_version, created_at FROM lending.payoff_quote WHERE loan_id = ?"
                + " ORDER BY created_at, id", id, row -> new LoanRecord.PayoffQuoteRow(row.getObject("id", UUID.class),
                        row.getObject("business_date", LocalDate.class), row.getObject("good_through", LocalDate.class),
                        money(row, "outstanding_minor"), row.getInt("agreement_version"), instant(row, "created_at")));
        List<LoanRecord.PayoffExecutionRow> executions = query(c, "SELECT id, quote_id, business_date, executed_at"
                + " FROM lending.payoff_execution WHERE loan_id = ?", id, row -> new LoanRecord.PayoffExecutionRow(
                        row.getObject("id", UUID.class), row.getObject("quote_id", UUID.class),
                        row.getObject("business_date", LocalDate.class), instant(row, "executed_at")));
        Optional<LoanRecord.LineClosureRow> lineClosure = first(query(c, "SELECT id, outcome, requested_at"
                + " FROM lending.line_closure_request WHERE loan_id = ?", id, row -> new LoanRecord.LineClosureRow(
                        row.getObject("id", UUID.class), row.getString("outcome"), instant(row, "requested_at"))));
        List<LoanRecord.Condition> conditions = query(c, "SELECT * FROM lending.loan_condition_event WHERE loan_id = ?"
                + " ORDER BY created_at, id", id, row -> new LoanRecord.Condition(row.getObject("id", UUID.class),
                        row.getObject("business_date", LocalDate.class), row.getString("kind"), row.getInt("days_past_due"),
                        row.getString("bucket"), Optional.ofNullable(row.getString("previous_bucket")),
                        row.getBoolean("defaulted"), row.getInt("agreement_version"), instant(row, "created_at")));
        List<LoanRecord.Attempt> attempts = query(c, "SELECT * FROM lending.collection_attempt WHERE loan_id = ?"
                + " ORDER BY created_at, id", id, row -> new LoanRecord.Attempt(row.getObject("id", UUID.class),
                        row.getObject("due_item_id", UUID.class), row.getObject("attempt_date", LocalDate.class),
                        row.getString("outcome"), money(row, "due_minor"), money(row, "collected_minor"),
                        uuid(row, "repayment_id"), instant(row, "created_at")));

        // The pinned terms versions and the offer - through their own stores; a version or offer this build cannot read
        // (an engine version it does not hold) is recorded as unreadable, for the replay to report.
        Map<UUID, Optional<LoanTermsStore.LoanTermsVersion>> versions = new LinkedHashMap<>();
        for (LoanRecord.Agreement agreement : agreements) {
            versions.computeIfAbsent(agreement.termsVersionId(), version -> {
                try {
                    return terms.version(c, LoanTermsVersionId.of(version));
                } catch (IllegalStateException unreadable) {
                    return Optional.empty();
                }
            });
        }
        Optional<LoanOffer> offer;
        try {
            offer = offers.ownedBy(c, account.offer(), account.party());
        } catch (IllegalStateException unreadable) {
            offer = Optional.empty();
        }

        // The ledger: every account the ledger holds for the loan, and every journal line on them in one statement.
        List<LoanRecord.LedgerAccountRow> owned = new ArrayList<>();
        for (LedgerAccount ledger : ledgerAccounts.findAllOwned(c, id)) {
            owned.add(new LoanRecord.LedgerAccountRow(ledger.id(), ledger.purpose(), ledger.currency(),
                    ledger.status().name()));
        }
        Set<LedgerAccountId> ownedIds = new LinkedHashSet<>();
        owned.forEach(row -> ownedIds.add(row.id()));
        List<PurposeBalances.DatedLine> lines = ownedIds.isEmpty() ? List.of()
                : balances.datedLinesOn(c, ownedIds, EVERY_VALUE_DATE);

        return Optional.of(new LoanRecord(account, agreements, versions, offer, disbursement, accrualStart, payout,
                schedules, draws, accruals, billings, statements, applications, repayments, reversals, fees, waivers,
                amendments, reschedulings, closure, quotes, executions, lineClosure, conditions, attempts, owned, lines));
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Row<T> {
        T map(ResultSet row) throws SQLException;
    }

    private static <T> List<T> query(Connection unitOfWork, String sql, UUID loan, Row<T> mapper) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, loan);
            List<T> found = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    found.add(mapper.map(rows));
                }
            }
            return found;
        } catch (SQLException failure) {
            throw failed("reading a loan's record", failure);
        }
    }

    /** The one row a unique key admits, if any. */
    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** A fact's place in its loan's history, or {@link LoanRecord#UNORDERED} for one written before lending V025. */
    private static long order(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? LoanRecord.UNORDERED : value;
    }

    private static CurrencyCode currency(ResultSet row) throws SQLException {
        return CurrencyCode.of(row.getString("currency").strip());
    }

    private static Money money(ResultSet row, String column) throws SQLException {
        return Money.ofPersisted(row.getLong(column), currency(row), row.getInt("scale"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column).toInstant();
    }

    private static Optional<Instant> optionalInstant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }

    private static Optional<UUID> uuid(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, UUID.class));
    }

    private static Optional<JournalEntryId> entry(ResultSet row, String column) throws SQLException {
        return uuid(row, column).map(JournalEntryId::of);
    }

    private static Optional<LocalDate> date(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, LocalDate.class));
    }

    private static Optional<Integer> integer(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
