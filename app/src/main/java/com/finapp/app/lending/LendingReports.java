package com.finapp.app.lending;

import com.finapp.ledger.SupportedCurrencies;
import com.finapp.lending.LendingAuditAction;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanSubledgerProof;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Lending's operations reports (`P11-TSK-030`; PHASE_11_PLAN.md sections 9 and 15; ADR-0072): the portfolio, the
 * delinquency, the accrual and the capital, each AS OF one business date - the amounts this platform never publishes as a
 * series. Each is read by a holder of {@code LENDING_INVESTIGATE} in ONE read-only {@code REPEATABLE READ} snapshot - so a
 * report's rows and its ties agree whatever commits beside it, and two instances reading one instant serve the same report
 * - and recorded {@code lending.ReportRead} in a transaction of its own that commits before the report is answered (the
 * replay's precedent, `P11-TSK-029`: the snapshot is read-only, so the proofs it carries can never write), so no report is
 * served unrecorded.
 *
 * <h2>The date</h2>
 *
 * <p>{@code date} is a business date, {@code YYYY-MM-DD}; anything else is {@code 422} before any read, and nothing is
 * recorded. Every account is judged on its OWN business date - the database clock in its agreement's pinned zone (A2,
 * {@code lending.business_date}) - exactly as servicing judges it: an amount is the ledger's lines whose entry's value date
 * is on or before {@code date} (every lending posting is dated its account's business date), a state is the account's
 * latest edge whose business date is on or before it, a condition its latest change recorded on or before it, an accrual
 * the one born for it. A date before any business day the platform has had - or after the latest business date any zone
 * has reached ({@code UTC+14}) - is not a reportable date: the report answers EMPTY ({@code known=false}), never a figure
 * and never an error; it is still recorded.
 *
 * <h2>Amounts, at last - and only here</h2>
 *
 * <p>Minor units at their persisted scale; summed in SQL over {@code bigint} into {@code numeric} - arbitrary precision,
 * read back as {@link BigDecimal} exactly, the scale a GROUPING key so no sum crosses a scale (the {@code TrialBalance}
 * argument) - and narrowed with {@link BigDecimal#longValueExact()}, which refuses rather than wraps. A currency holding
 * two scales in one product refuses loudly. No party, person or rate is read into any report; the portfolio report names
 * loans only where a proof fails for them - the accounts an operator must explain (the explanation door judges self-dealing).
 *
 * <h2>The ties</h2>
 *
 * <p>The portfolio report ties itself, in its own snapshot, to the ledger: the loan book read from every {@code LOAN}-owned
 * ledger account equals the sum of its product rows (no loan account outside lending's book), and the trial balance
 * through the date is zero per currency ({@code INV-ACC-01}); and it carries the subledger proof and the replay read on
 * the same snapshot - the accounts whose six balances their rows explain, and the ones they do not.
 */
public final class LendingReports {

    /** The longest list of loans a report names: {@code truncated} says when there were more. */
    static final int BOUND = 100;

    /** The first business date the platform could have had. */
    static final LocalDate EARLIEST = LocalDate.of(2026, 1, 1);

    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    /** Each account's pinned zone - the agreement's terms version through its offer, as servicing reads it. */
    private static final String ZONED_LOANS = "SELECT l.id, l.product, l.currency, t.servicing_zone AS zone"
            + " FROM lending.loan l JOIN lending.loan_offer o ON o.id = l.offer_id"
            + " JOIN lending.loan_terms t ON t.terms_version_id = o.terms_version_id";

    /** Each account's state at the end of its business date {@code ?} - its latest edge on or before it; NULL unborn. */
    private static final String STATED_LOANS = "SELECT z.id, z.product, z.currency, z.zone, (SELECT e.to_status"
            + " FROM lending.loan_event e WHERE e.loan_id = z.id AND lending.business_date(e.occurred_at, z.zone) <= ?"
            + " ORDER BY e.occurred_at DESC, e.id DESC LIMIT 1) AS status FROM (" + ZONED_LOANS + ") z";

    /** Every line on a {@code LOAN}-owned account whose entry's value date is on or before {@code ?}. */
    private static final String LOAN_LINES = " FROM ledger.ledger_account a"
            + " JOIN ledger.journal_line ln ON ln.ledger_account_id = a.id"
            + " JOIN ledger.journal_entry e ON e.id = ln.entry_id"
            + " WHERE a.owner_kind = 'LOAN' AND e.value_date <= ?";

    private final LendingTransactions transactions;
    private final LendingProofs proofs;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public LendingReports(LendingTransactions transactions, LendingProofs proofs, AuditWriter<Connection> audit,
            IdGenerator ids, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ the portfolio

    /**
     * One product's book in one currency on the date: its accounts by state, and each balance - principal not yet due, due
     * principal, accrued and due interest, due fees (receivables, debit-normal) and the credit balance owed back (a
     * liability, credit-normal) - with the outstanding total of the five receivables.
     */
    public record LendingPortfolioRow(
            String product, String currency, int scale, long pendingDisbursement, long active, long closing, long closed,
            long cancelled, long principalMinor, long principalDueMinor, long interestAccruedMinor, long interestDueMinor,
            long feesDueMinor, long creditBalanceMinor, long outstandingMinor) {}

    /**
     * One currency's book from the ledger's side - every {@code LOAN}-owned account - and whether it ties: equal to the
     * product rows, and the trial balance through the date zero in the currency.
     */
    public record LendingBookTie(
            String currency, int scale, long outstandingMinor, long creditBalanceMinor, boolean tiesToProducts,
            boolean trialBalanceZero) {}

    /**
     * The subledger proof and the replay over every account, read on the report's snapshot (the instant it was taken, not
     * the date): counts per verdict, and the accounts that fail or diverge - identifiers only, bounded.
     */
    public record LendingProofCounts(
            long holding, long failing, List<String> failingLoans, long identical, long diverged, List<String> divergedLoans,
            boolean truncated) {}

    public record LendingPortfolioReport(
            String date, boolean known, List<LendingPortfolioRow> rows, List<LendingBookTie> currencies,
            LendingProofCounts proofs) {}

    /** The loan book as of the end of {@code date}, tied to the ledger and carrying the proofs. */
    public LendingPortfolioReport portfolio(String date) {
        LocalDate day = date(date);
        return read("portfolio", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new LendingPortfolioReport(day.toString(), false, List.of(), List.of(), null), 0);
            }
            // The accounts by product, currency and state.
            Map<String, long[]> states = new TreeMap<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT s.product, s.currency, s.status, count(*) FROM ("
                    + STATED_LOANS + ") s WHERE s.status IS NOT NULL GROUP BY 1, 2, 3")) {
                select.setObject(1, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        long[] counts = states.computeIfAbsent(key(found.getString(1), found.getString(2)),
                                ignored -> new long[5]);
                        counts[stateIndex(found.getString(3))] = found.getLong(4);
                    }
                }
            }
            // The balances by product, currency and purpose - through lending's loans.
            Map<String, Balances> byProduct = new TreeMap<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT l.product, a.currency, ln.scale, a.purpose,"
                    + " sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor ELSE -ln.amount_minor END)"
                    + LOAN_LINES.replace(" WHERE ", " JOIN lending.loan l ON l.id = a.owner_ref WHERE ")
                    + " GROUP BY 1, 2, 3, 4")) {
                select.setObject(1, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        byProduct.computeIfAbsent(key(found.getString(1), found.getString(2)),
                                ignored -> new Balances()).add(found.getInt(3), found.getString(4), found.getBigDecimal(5));
                    }
                }
            }
            List<LendingPortfolioRow> rows = new ArrayList<>();
            Map<String, Balances> productTotals = new TreeMap<>();
            java.util.Set<String> keys = new java.util.TreeSet<>(states.keySet());
            keys.addAll(byProduct.keySet());
            for (String key : keys) {
                String[] parts = key.split("\\|");
                long[] counts = states.getOrDefault(key, new long[5]);
                Balances balances = byProduct.getOrDefault(key, new Balances());
                int scale = balances.scale(parts[1]);
                rows.add(new LendingPortfolioRow(tag(parts[0]), parts[1].strip(), scale, counts[0], counts[1], counts[2],
                        counts[3], counts[4], balances.debit("LOAN_PRINCIPAL"), balances.debit("LOAN_PRINCIPAL_DUE"),
                        balances.debit("LOAN_INTEREST_ACCRUED"), balances.debit("LOAN_INTEREST_DUE"),
                        balances.debit("LOAN_FEES_DUE"), balances.credit("LOAN_CREDIT_BALANCE"), balances.outstanding()));
                productTotals.computeIfAbsent(parts[1].strip(), ignored -> new Balances()).addAll(balances);
            }
            // The ledger's side: every LOAN-owned account, whoever names it.
            Map<String, Balances> book = new TreeMap<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT a.currency, ln.scale, a.purpose,"
                    + " sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor ELSE -ln.amount_minor END)" + LOAN_LINES
                    + " GROUP BY 1, 2, 3")) {
                select.setObject(1, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        book.computeIfAbsent(found.getString(1).strip(), ignored -> new Balances())
                                .add(found.getInt(2), found.getString(3), found.getBigDecimal(4));
                    }
                }
            }
            Map<String, Boolean> trial = trialBalance(uow, day);
            List<LendingBookTie> currencies = new ArrayList<>();
            java.util.Set<String> bookCurrencies = new java.util.TreeSet<>(book.keySet());
            bookCurrencies.addAll(productTotals.keySet());
            for (String currency : bookCurrencies) {
                Balances ledger = book.getOrDefault(currency, new Balances());
                Balances products = productTotals.getOrDefault(currency, new Balances());
                currencies.add(new LendingBookTie(currency, ledger.scale(currency), ledger.outstanding(),
                        ledger.credit("LOAN_CREDIT_BALANCE"), ledger.equals(products), trial.getOrDefault(currency, true)));
            }
            LendingProofCounts read = proofs(uow);
            return new Served<>(new LendingPortfolioReport(day.toString(), true, List.copyOf(rows), List.copyOf(currencies), read),
                    rows.size());
        });
    }

    // ------------------------------------------------------------------ the delinquency

    /**
     * One product's accounts in servicing on the date ({@code ACTIVE} or {@code CLOSING}) in one days-past-due bucket - the
     * latest change recorded on or before it, {@code current} when none - how many of them stood defaulted, and their due
     * balances: principal, interest and fees billed or assessed and not yet paid.
     */
    public record LendingDelinquencyRow(
            String product, String currency, int scale, String bucket, long accounts, long defaulted,
            long principalDueMinor, long interestDueMinor, long feesDueMinor, long dueMinor) {}

    public record LendingDelinquencyReport(String date, boolean known, List<LendingDelinquencyRow> rows) {}

    /** The book in servicing by days-past-due bucket as of the end of {@code date}, with its due sums from the ledger. */
    public LendingDelinquencyReport delinquency(String date) {
        LocalDate day = date(date);
        return read("delinquency", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new LendingDelinquencyReport(day.toString(), false, List.of()), 0);
            }
            String conditioned = "SELECT s.id, s.product, s.currency,"
                    + " COALESCE((SELECT c.bucket FROM lending.loan_condition_event c WHERE c.loan_id = s.id"
                    + " AND c.kind = 'DELINQUENCY' AND c.business_date <= ? ORDER BY c.business_date DESC LIMIT 1),"
                    + " 'CURRENT') AS bucket,"
                    + " COALESCE((SELECT c.defaulted FROM lending.loan_condition_event c WHERE c.loan_id = s.id"
                    + " AND c.kind = 'DEFAULT' AND c.business_date <= ? ORDER BY c.business_date DESC LIMIT 1),"
                    + " false) AS defaulted"
                    + " FROM (" + STATED_LOANS + ") s WHERE s.status IN ('ACTIVE', 'CLOSING')";
            Map<String, long[]> accounts = new TreeMap<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT d.product, d.currency, d.bucket, count(*),"
                    + " count(*) FILTER (WHERE d.defaulted) FROM (" + conditioned + ") d GROUP BY 1, 2, 3")) {
                select.setObject(1, day);
                select.setObject(2, day);
                select.setObject(3, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        accounts.put(key(found.getString(1), found.getString(2)) + "|" + found.getString(3),
                                new long[] {found.getLong(4), found.getLong(5)});
                    }
                }
            }
            Map<String, Balances> dues = new TreeMap<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT d.product, d.currency, d.bucket, ln.scale,"
                    + " a.purpose, sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor ELSE -ln.amount_minor END)"
                    + " FROM (" + conditioned + ") d JOIN ledger.ledger_account a ON a.owner_kind = 'LOAN'"
                    + " AND a.owner_ref = d.id AND a.purpose IN ('LOAN_PRINCIPAL_DUE', 'LOAN_INTEREST_DUE', 'LOAN_FEES_DUE')"
                    + " JOIN ledger.journal_line ln ON ln.ledger_account_id = a.id"
                    + " JOIN ledger.journal_entry e ON e.id = ln.entry_id WHERE e.value_date <= ? GROUP BY 1, 2, 3, 4, 5")) {
                select.setObject(1, day);
                select.setObject(2, day);
                select.setObject(3, day);
                select.setObject(4, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        dues.computeIfAbsent(key(found.getString(1), found.getString(2)) + "|" + found.getString(3),
                                ignored -> new Balances()).add(found.getInt(4), found.getString(5), found.getBigDecimal(6));
                    }
                }
            }
            List<LendingDelinquencyRow> rows = new ArrayList<>();
            for (Map.Entry<String, long[]> entry : accounts.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                Balances due = dues.getOrDefault(entry.getKey(), new Balances());
                long principal = due.debit("LOAN_PRINCIPAL_DUE");
                long interest = due.debit("LOAN_INTEREST_DUE");
                long fees = due.debit("LOAN_FEES_DUE");
                rows.add(new LendingDelinquencyRow(tag(parts[0]), parts[1].strip(), due.scale(parts[1]), tag(parts[2]),
                        entry.getValue()[0], entry.getValue()[1], principal, interest, fees,
                        Math.addExact(Math.addExact(principal, interest), fees)));
            }
            return new Served<>(new LendingDelinquencyReport(day.toString(), true, List.copyOf(rows)), rows.size());
        });
    }

    // ------------------------------------------------------------------ the accrual

    /**
     * One product's accruals born for the date in one currency: how many accounts accrued it, how many posted a non-zero
     * amount, the posted interest the accrual rows record, the same from the ledger's lines on {@code LOAN_INTEREST_ACCRUED}
     * of their entries, and whether the two tie.
     */
    public record LendingAccrualRow(
            String product, String currency, int scale, long accounts, long posted, long accruedMinor, long ledgerMinor,
            boolean ties) {}

    public record LendingAccrualReport(String date, boolean known, List<LendingAccrualRow> rows) {}

    /** The interest accrued for {@code date}, by product - the rows' totals tied to the ledger's. */
    public LendingAccrualReport accrual(String date) {
        LocalDate day = date(date);
        return read("accrual", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new LendingAccrualReport(day.toString(), false, List.of()), 0);
            }
            List<LendingAccrualRow> rows = new ArrayList<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT l.product, x.currency, x.scale, count(*),"
                    + " count(*) FILTER (WHERE x.journal_entry_id IS NOT NULL), sum(x.posted_minor),"
                    + " COALESCE(sum((SELECT sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor"
                    + " ELSE -ln.amount_minor END) FROM ledger.journal_line ln JOIN ledger.ledger_account a"
                    + " ON a.id = ln.ledger_account_id AND a.purpose = 'LOAN_INTEREST_ACCRUED' AND a.owner_ref = x.loan_id"
                    + " WHERE ln.entry_id = x.journal_entry_id)), 0)"
                    + " FROM lending.interest_accrual x JOIN lending.loan l ON l.id = x.loan_id"
                    + " WHERE x.accrual_date = ? GROUP BY 1, 2, 3 ORDER BY 1, 2, 3")) {
                select.setObject(1, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        long accrued = exact(found.getBigDecimal(6));
                        long ledger = exact(found.getBigDecimal(7));
                        rows.add(new LendingAccrualRow(tag(found.getString(1)), found.getString(2).strip(), found.getInt(3),
                                found.getLong(4), found.getLong(5), accrued, ledger, accrued == ledger));
                    }
                }
            }
            return new Served<>(new LendingAccrualReport(day.toString(), true, List.copyOf(rows)), rows.size());
        });
    }

    // ------------------------------------------------------------------ the capital

    /**
     * One supported currency's lending capital on the date (ADR-0096 section 5): the capital recognised from bank
     * evidence, the principal committed to loans awaiting disbursement, the principal outstanding, the headroom left, and
     * the safeguarding term - wallet money lending created and still funds.
     */
    public record LendingCapitalRow(
            String currency, int scale, long recognisedMinor, long committedMinor, long outstandingMinor,
            long headroomMinor, long lendingFundedMinor) {}

    public record LendingCapitalReport(String date, boolean known, List<LendingCapitalRow> rows) {}

    /** The lending capital's headroom per supported currency as of the end of {@code date}. */
    public LendingCapitalReport capital(String date) {
        LocalDate day = date(date);
        return read("capital", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new LendingCapitalReport(day.toString(), false, List.of()), 0);
            }
            Map<String, Balances> ledger = new TreeMap<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT a.currency, ln.scale, a.purpose,"
                    + " sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor ELSE -ln.amount_minor END)"
                    + " FROM ledger.ledger_account a JOIN ledger.journal_line ln ON ln.ledger_account_id = a.id"
                    + " JOIN ledger.journal_entry e ON e.id = ln.entry_id WHERE e.value_date <= ?"
                    + " AND a.purpose IN ('LENDING_CAPITAL', 'LOAN_PRINCIPAL', 'LOAN_PRINCIPAL_DUE') GROUP BY 1, 2, 3")) {
                select.setObject(1, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        ledger.computeIfAbsent(found.getString(1).strip(), ignored -> new Balances())
                                .add(found.getInt(2), found.getString(3), found.getBigDecimal(4));
                    }
                }
            }
            // Committed: the agreement principal of the loans awaiting disbursement at the end of their business date.
            Map<String, Balances> committed = new TreeMap<>();
            try (PreparedStatement select = uow.prepareStatement("SELECT s.currency, g.scale, sum(g.amount_minor) FROM ("
                    + STATED_LOANS + ") s JOIN lending.loan_agreement g ON g.loan_id = s.id AND g.version = 1"
                    + " WHERE s.status = 'PENDING_DISBURSEMENT' GROUP BY 1, 2")) {
                select.setObject(1, day);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        committed.computeIfAbsent(found.getString(1).strip(), ignored -> new Balances())
                                .add(found.getInt(2), "COMMITTED", found.getBigDecimal(3));
                    }
                }
            }
            List<LendingCapitalRow> rows = new ArrayList<>();
            for (CurrencyCode currency : SupportedCurrencies.ALL) {
                Balances terms = ledger.getOrDefault(currency.code(), new Balances());
                terms.addAll(committed.getOrDefault(currency.code(), new Balances()));
                int scale = terms.scale(currency.code());
                long recognised = terms.credit("LENDING_CAPITAL");
                long commitment = terms.debit("COMMITTED");
                long outstanding = Math.addExact(terms.debit("LOAN_PRINCIPAL"), terms.debit("LOAN_PRINCIPAL_DUE"));
                rows.add(new LendingCapitalRow(currency.code(), scale, recognised, commitment, outstanding,
                        Math.subtractExact(Math.subtractExact(recognised, commitment), outstanding),
                        Math.addExact(outstanding, commitment)));
            }
            return new Served<>(new LendingCapitalReport(day.toString(), true, List.copyOf(rows)), rows.size());
        });
    }

    // ------------------------------------------------------------------ plumbing

    /** A report and how many rows it serves - the audit's detail. */
    private record Served<R>(R report, int rows) {}

    @FunctionalInterface
    private interface Read<R> {
        Served<R> in(Connection snapshot, boolean known) throws SQLException;
    }

    /**
     * One read-only {@code REPEATABLE READ} snapshot; then {@code lending.ReportRead} in a transaction of its own, committed
     * before the report is returned.
     */
    private <R> R read(String report, LocalDate day, Read<R> work) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a report runs inside a correlation scope"))
                .correlationId();
        boolean[] known = new boolean[1];
        Served<R> served = transactions.inSnapshot(snapshot -> {
            try {
                known[0] = reportable(snapshot, day);
                return work.in(snapshot, known[0]);
            } catch (SQLException failure) {
                throw new IllegalStateException("a lending report could not be read: " + failure.getSQLState());
            }
        });
        transactions.inTransaction(unitOfWork -> {
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    LendingAuditAction.REPORT_READ, "lending_report", report, Optional.empty(), AuditOutcome.SUCCEEDED,
                    correlation, Optional.of("report=" + report + ", date=" + day + ", known=" + known[0] + ", rows="
                            + served.rows())));
            return null;
        });
        return served.report();
    }

    /** Whether {@code day} is a business date the platform has had: on or after the earliest, and begun in some zone. */
    private static boolean reportable(Connection snapshot, LocalDate day) throws SQLException {
        if (day.isBefore(EARLIEST)) {
            return false;
        }
        try (PreparedStatement select = snapshot.prepareStatement(
                "SELECT ? <= ((statement_timestamp() AT TIME ZONE 'UTC') + interval '14 hours')::date")) {
            select.setObject(1, day);
            try (ResultSet found = select.executeQuery()) {
                found.next();
                return found.getBoolean(1);
            }
        }
    }

    /** The trial balance through the date, per currency: whether DR - CR of every line is zero, the scales combined exactly. */
    private static Map<String, Boolean> trialBalance(Connection snapshot, LocalDate day) throws SQLException {
        Map<String, BigDecimal> difference = new TreeMap<>();
        try (PreparedStatement select = snapshot.prepareStatement("SELECT ln.currency, ln.scale,"
                + " sum(CASE WHEN ln.direction = 'DEBIT' THEN ln.amount_minor ELSE -ln.amount_minor END)"
                + " FROM ledger.journal_line ln JOIN ledger.journal_entry e ON e.id = ln.entry_id WHERE e.value_date <= ?"
                + " GROUP BY 1, 2")) {
            select.setObject(1, day);
            try (ResultSet found = select.executeQuery()) {
                while (found.next()) {
                    difference.merge(found.getString(1).strip(), found.getBigDecimal(3).movePointLeft(found.getInt(2)),
                            BigDecimal::add);
                }
            }
        }
        Map<String, Boolean> zero = new TreeMap<>();
        difference.forEach((currency, value) -> zero.put(currency, value.signum() == 0));
        return zero;
    }

    /** The subledger proof and the replay over every account, on the snapshot - counts, and the loans that do not hold. */
    private LendingProofCounts proofs(Connection snapshot) {
        LendingProofs.Reading reading = proofs.prove(snapshot);
        List<String> failing = reading.failures().stream().map(LoanSubledgerProof.Verdict::loan)
                .map(loan -> loan.value().toString()).toList();
        List<String> diverged = reading.divergences().stream().map(replay -> replay.loan().value().toString()).toList();
        boolean truncated = failing.size() > BOUND || diverged.size() > BOUND;
        return new LendingProofCounts(reading.holding(), reading.failing(), bounded(failing),
                reading.replaying(com.finapp.lending.LoanReplayProof.Verdict.IDENTICAL),
                reading.replaying(com.finapp.lending.LoanReplayProof.Verdict.DIVERGED), bounded(diverged), truncated);
    }

    private static List<String> bounded(List<String> loans) {
        return List.copyOf(loans.size() > BOUND ? loans.subList(0, BOUND) : loans);
    }

    private static int stateIndex(String status) {
        return switch (status) {
            case "PENDING_DISBURSEMENT" -> 0;
            case "ACTIVE" -> 1;
            case "CLOSING" -> 2;
            case "CLOSED" -> 3;
            case "CANCELLED" -> 4;
            default -> throw new IllegalStateException("a loan state this build does not know");
        };
    }

    private static String key(String product, String currency) {
        return product + "|" + currency;
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static long exact(BigDecimal value) {
        return value == null ? 0 : value.longValueExact();
    }

    /** {@code YYYY-MM-DD} or {@code 422} before any read. */
    private static LocalDate date(String date) {
        if (date != null && DATE.matcher(date).matches()) {
            try {
                return LocalDate.parse(date);
            } catch (DateTimeParseException unparseable) {
                // Falls through: 2026-02-30 has the shape and no meaning.
            }
        }
        throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
                "A lending report was asked for a date that is not a calendar date",
                "date must be YYYY-MM-DD, a business date.");
    }

    /**
     * Signed sums per purpose at one scale: DR - CR, so a debit-normal receivable reads positive and a credit-normal
     * liability or equity reads through {@link #credit}.
     */
    private static final class Balances {
        private final Map<String, BigDecimal> byPurpose = new TreeMap<>();
        private Integer scale;

        void add(int lineScale, String purpose, BigDecimal signed) {
            if (scale != null && scale != lineScale) {
                throw new IllegalStateException("one book holds two scales in one currency - refused, never rescaled");
            }
            scale = lineScale;
            byPurpose.merge(purpose, signed == null ? BigDecimal.ZERO : signed, BigDecimal::add);
        }

        void addAll(Balances other) {
            other.byPurpose.forEach((purpose, value) -> add(other.scale, purpose, value));
        }

        int scale(String currency) {
            if (scale != null) {
                return scale;
            }
            Integer pinned = SupportedCurrencies.PINNED_MINOR_UNITS.get(CurrencyCode.of(currency.strip()));
            return pinned == null ? 2 : pinned;
        }

        long debit(String purpose) {
            return exact(byPurpose.getOrDefault(purpose, BigDecimal.ZERO));
        }

        long credit(String purpose) {
            return exact(byPurpose.getOrDefault(purpose, BigDecimal.ZERO).negate());
        }

        long outstanding() {
            return exact(sum("LOAN_PRINCIPAL", "LOAN_PRINCIPAL_DUE", "LOAN_INTEREST_ACCRUED", "LOAN_INTEREST_DUE",
                    "LOAN_FEES_DUE"));
        }

        private BigDecimal sum(String... purposes) {
            BigDecimal total = BigDecimal.ZERO;
            for (String purpose : purposes) {
                total = total.add(byPurpose.getOrDefault(purpose, BigDecimal.ZERO));
            }
            return total;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Balances that)) {
                return false;
            }
            Map<String, BigDecimal> mine = new LinkedHashMap<>();
            Map<String, BigDecimal> theirs = new LinkedHashMap<>();
            byPurpose.forEach((purpose, value) -> mine.put(purpose, value.stripTrailingZeros()));
            that.byPurpose.forEach((purpose, value) -> theirs.put(purpose, value.stripTrailingZeros()));
            mine.values().removeIf(value -> value.signum() == 0);
            theirs.values().removeIf(value -> value.signum() == 0);
            return mine.equals(theirs);
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "Balances[" + byPurpose.keySet() + "]";
        }
    }
}
