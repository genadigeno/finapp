package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAuditAction;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplThreeWayProof;
import com.finapp.ledger.SupportedCurrencies;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * BNPL's operations reports (`P12-TSK-026`; {@code PHASE_12_PLAN.md} sections 9 and 15; ADR-0072; lending's
 * {@code LendingReports}): the portfolio, the merchant financing, the refunds and the delinquency, each AS OF one business
 * date - the amounts BNPL never publishes as a series (outstanding, merchant financing exposure, refund impact on
 * outstanding, delinquency sums). Each is read by a holder of {@code BNPL_INVESTIGATE} in ONE read-only
 * {@code REPEATABLE READ} snapshot - so a report's rows and its ties agree whatever commits beside it, and two instances
 * reading one instant serve the same report - and recorded {@code bnpl.ReportRead} in a transaction of its own that commits
 * before the report is answered (the replay's precedent: the snapshot is read-only, so the proofs it carries can never
 * write), so no report is served unrecorded.
 *
 * <h2>The date</h2>
 *
 * <p>{@code date} is a business date, {@code YYYY-MM-DD}; anything else is {@code 422 api.ValidationFailed} before any read,
 * and nothing is recorded. An obligation is judged on its OWN business date - the database clock in its pinned servicing
 * zone ({@code bnpl.business_date}): an amount is the ledger's lines whose entry's value date is on or before {@code date},
 * a status the obligation's closure if its business date is on or before it, a condition its latest change recorded on or
 * before it. A date before any business day the platform has had - or after the latest business date any zone has reached
 * - is not reportable: the report answers EMPTY ({@code known=false}), never a figure and never an error; it is still
 * recorded.
 *
 * <h2>The ties - never a stored balance where journal lines are the truth</h2>
 *
 * <ul>
 *   <li><strong>Portfolio</strong>: the BNPL book read from every {@code BNPL_OBLIGATION}-owned ledger account equals the
 *       sum of the product rows (no obligation account outside bnpl's book); the trial balance through the date is zero per
 *       currency ({@code INV-ACC-01}); and the three proofs read on the same snapshot - the subledger proof's and the
 *       replay's counts with the obligations that fail or diverge (their agreements, bounded), and each currency's
 *       three-way verdict with its failing arms.
 *   <li><strong>Merchant financing</strong>: Σ (G - f) of the financings less Σ the refunds' payable debit - the exposure -
 *       equals CR - DR of those entries' lines on the merchant payables (the three-way proof's merchant leg, in sum), and
 *       the three-way merchant legs failing in the currency are counted beside it.
 *   <li><strong>Refunds</strong>: each refund row's cancelled principal and released interest equal what its own entry
 *       credited the obligation's four receivables; the impact on outstanding is the ledger's, the late fees released with
 *       it included.
 *   <li><strong>Delinquency</strong>: the due sums are the ledger's lines on the due accounts, never a row's figure.
 * </ul>
 *
 * <p>Minor units at their persisted scale, narrowed with {@link BigDecimal#longValueExact()} (which refuses rather than
 * wraps); one currency holding two scales refuses loudly. No party, merchant, purchase or rate is read into any report; the
 * portfolio names agreements only where a proof fails for them - the ones an investigator must explain (the explanation
 * door judges self-dealing).
 */
public final class BnplReports {

    /** The longest list of agreements a report names: {@code truncated} says when there were more. */
    static final int BOUND = 100;

    /** The first business date the platform could have had. */
    static final LocalDate EARLIEST = LocalDate.of(2026, 1, 1);

    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    static final List<String> RECEIVABLES = List.of("BNPL_PRINCIPAL", "BNPL_PRINCIPAL_DUE", "BNPL_INTEREST_ACCRUED",
            "BNPL_INTEREST_DUE", "BNPL_FEES_DUE");

    /** The two units of work a report needs - a read-only snapshot, and the audit's own transaction. */
    public interface Units {
        <R> R inSnapshot(Function<Connection, R> work);

        <R> R inTransaction(Function<Connection, R> work);
    }

    private final Units units;
    private final BnplReportReads reads;
    private final Function<Connection, BnplProofs.Reading> proofs;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public BnplReports(Units units, BnplReportReads reads, Function<Connection, BnplProofs.Reading> proofs,
            AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.units = Objects.requireNonNull(units, "units");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.proofs = Objects.requireNonNull(proofs, "proofs");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** {@link BnplTransactions} as the two units. */
    public static Units over(BnplTransactions transactions) {
        Objects.requireNonNull(transactions, "transactions");
        return new Units() {
            @Override
            public <R> R inSnapshot(Function<Connection, R> work) {
                return transactions.inSnapshot(work);
            }

            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return transactions.inTransaction(work);
            }
        };
    }

    // ------------------------------------------------------------------ the portfolio

    /**
     * One product's book in one currency on the date: its obligations by status, and each balance - principal not yet
     * due, principal due, interest accrued and due, fees due (receivables, debit-normal) and the credit balance owed back
     * (a liability, credit-normal) - with the outstanding total of the five receivables.
     */
    public record BnplPortfolioRow(
            String product, String currency, int scale, long active, long closed, long principalMinor,
            long principalDueMinor, long interestAccruedMinor, long interestDueMinor, long feesDueMinor,
            long creditBalanceMinor, long outstandingMinor) {}

    /**
     * One currency's book from the ledger's side - every {@code BNPL_OBLIGATION}-owned account - and whether it ties: equal
     * to the product rows, the trial balance through the date zero, and the three-way proof at the snapshot's instant.
     */
    public record BnplBookTie(
            String currency, int scale, long outstandingMinor, long creditBalanceMinor, boolean tiesToProducts,
            boolean trialBalanceZero, Boolean threeWayHolds, List<String> threeWayFailures) {}

    /**
     * The subledger proof and the replay over every obligation, read on the report's snapshot (the instant it was taken,
     * not the date): counts per verdict, and the agreements that fail or diverge - identifiers only, bounded.
     */
    public record BnplProofCounts(
            long holding, long failing, List<String> failingAgreements, long identical, long diverged,
            List<String> divergedAgreements, boolean truncated) {}

    public record BnplPortfolioReport(
            String date, boolean known, List<BnplPortfolioRow> rows, List<BnplBookTie> currencies,
            BnplProofCounts proofs) {}

    /** The BNPL book as of the end of {@code date}, tied to the ledger and carrying the proofs. */
    public BnplPortfolioReport portfolio(String date) {
        LocalDate day = date(date);
        return read("portfolio", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new BnplPortfolioReport(day.toString(), false, List.of(), List.of(), null), 0);
            }
            Map<String, long[]> states = new TreeMap<>();
            for (BnplReportReads.ObligationCount count : reads.obligations(uow, day)) {
                long[] counts = states.computeIfAbsent(key(count.product(), count.currency()), ignored -> new long[2]);
                counts[statusIndex(count.status())] = Math.addExact(counts[statusIndex(count.status())], count.count());
            }
            Map<String, Balances> byProduct = new TreeMap<>();
            for (BnplReportReads.Balance balance : reads.obligationBalances(uow, day)) {
                byProduct.computeIfAbsent(key(balance.group(), balance.currency()), ignored -> new Balances())
                        .add(balance.scale(), balance.purpose(), balance.signed());
            }
            List<BnplPortfolioRow> rows = new ArrayList<>();
            Map<String, Balances> productTotals = new TreeMap<>();
            Set<String> keys = new TreeSet<>(states.keySet());
            keys.addAll(byProduct.keySet());
            for (String key : keys) {
                String[] parts = key.split("\\|");
                long[] counts = states.getOrDefault(key, new long[2]);
                Balances balances = byProduct.getOrDefault(key, new Balances());
                rows.add(new BnplPortfolioRow(tag(parts[0]), parts[1], balances.scale(parts[1]), counts[0], counts[1],
                        balances.debit("BNPL_PRINCIPAL"), balances.debit("BNPL_PRINCIPAL_DUE"),
                        balances.debit("BNPL_INTEREST_ACCRUED"), balances.debit("BNPL_INTEREST_DUE"),
                        balances.debit("BNPL_FEES_DUE"), balances.credit("BNPL_CREDIT_BALANCE"), balances.outstanding()));
                productTotals.computeIfAbsent(parts[1], ignored -> new Balances()).addAll(balances);
            }
            Map<String, Balances> book = new TreeMap<>();
            for (BnplReportReads.Balance balance : reads.bookBalances(uow, day)) {
                book.computeIfAbsent(balance.currency().strip(), ignored -> new Balances())
                        .add(balance.scale(), balance.purpose(), balance.signed());
            }
            Map<String, Boolean> trial = reads.trialBalance(uow, day);
            BnplProofs.Reading reading = proofs.apply(uow);
            Map<String, BnplThreeWayProof.CurrencyVerdict> threeWay = new TreeMap<>();
            reading.currencies().forEach(verdict -> threeWay.put(verdict.currency().code(), verdict));
            List<BnplBookTie> currencies = new ArrayList<>();
            Set<String> bookCurrencies = new TreeSet<>(book.keySet());
            bookCurrencies.addAll(productTotals.keySet());
            for (String currency : bookCurrencies) {
                Balances ledger = book.getOrDefault(currency, new Balances());
                Balances products = productTotals.getOrDefault(currency, new Balances());
                Optional<BnplThreeWayProof.CurrencyVerdict> verdict = Optional.ofNullable(threeWay.get(currency));
                currencies.add(new BnplBookTie(currency, ledger.scale(currency), ledger.outstanding(),
                        ledger.credit("BNPL_CREDIT_BALANCE"), ledger.equals(products), trial.getOrDefault(currency, true),
                        verdict.map(BnplThreeWayProof.CurrencyVerdict::holds).orElse(null),
                        verdict.map(found -> List.copyOf(found.failures())).orElse(List.of())));
            }
            BnplProofCounts counted = proofCounts(uow, reading);
            return new Served<>(new BnplPortfolioReport(day.toString(), true, List.copyOf(rows),
                    List.copyOf(currencies), counted), rows.size());
        });
    }

    // ------------------------------------------------------------------ the merchant financing

    /**
     * One currency's merchant financing as of the date: how many purchases were financed, the gross, the
     * merchant discount and the net financed; the refunds valued by then - their amounts, the fee returned and the payable
     * debited; the exposure (net financed less the payable debited) and the same read from the merchant payables' lines
     * of those entries, whether the two tie, and how many merchants' three-way legs fail in the currency at the snapshot.
     */
    public record BnplMerchantFinancingRow(
            String currency, int scale, long purchases, long grossMinor, long feeMinor,
            long netFinancedMinor, long refunds, long refundedMinor, long feeReturnedMinor, long payableDebitMinor,
            long exposureMinor, long ledgerPayableMinor, boolean tiesToLedger, long merchantLegsFailing) {}

    public record BnplMerchantFinancingReport(String date, boolean known, List<BnplMerchantFinancingRow> rows) {}

    /** The merchant financing exposure as of the end of {@code date}, tied to the merchant payables' lines. */
    public BnplMerchantFinancingReport merchantFinancing(String date) {
        LocalDate day = date(date);
        return read("merchant-financing", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new BnplMerchantFinancingReport(day.toString(), false, List.of()), 0);
            }
            Map<String, Balances> payables = new TreeMap<>();
            for (BnplReportReads.Balance balance : reads.payableLines(uow, day)) {
                payables.computeIfAbsent(balance.currency().strip(), ignored -> new Balances())
                        .add(balance.scale(), balance.purpose(), balance.signed());
            }
            Map<String, Long> failingLegs = new TreeMap<>();
            for (BnplThreeWayProof.CurrencyVerdict verdict : proofs.apply(uow).currencies()) {
                failingLegs.put(verdict.currency().code(),
                        verdict.merchantLegs().stream().filter(leg -> !leg.holds()).count());
            }
            List<BnplMerchantFinancingRow> rows = new ArrayList<>();
            for (BnplReportReads.Financing financing : reads.financing(uow, day)) {
                String currency = financing.currency().strip();
                long net = exact(financing.net());
                long payableDebit = exact(financing.payableDebit());
                long exposure = Math.subtractExact(net, payableDebit);
                Balances payable = payables.getOrDefault(currency, new Balances());
                if (payable.scale != null && payable.scale != financing.scale()) {
                    throw new IllegalStateException("one book holds two scales in one currency - refused, never rescaled");
                }
                long ledger = payable.debit("MERCHANT_PAYABLE");
                rows.add(new BnplMerchantFinancingRow(currency, financing.scale(), financing.purchases(), exact(financing.gross()), exact(financing.fee()), net, financing.refunds(),
                        exact(financing.refunded()), exact(financing.feeReturned()), payableDebit, exposure, ledger,
                        ledger == exposure, failingLegs.getOrDefault(currency, 0L)));
            }
            return new Served<>(new BnplMerchantFinancingReport(day.toString(), true, List.copyOf(rows)), rows.size());
        });
    }

    // ------------------------------------------------------------------ the refunds

    /**
     * One group of the refunds valued on the date - by product, currency, kind ({@code full} or {@code partial}) and door
     * ({@code merchant}, {@code operator}, {@code dispute}): how many, their amounts, the principal they cancelled and the
     * interest they released (the rows'), the excess returned to the wallet or held as a credit balance, the merchant fee
     * returned and the payable debited; the impact on outstanding as their own entries left it on the obligations'
     * receivables (the ledger's - the late fees released included), and whether the rows tie to it.
     */
    public record BnplRefundRow(
            String product, String currency, int scale, String kind, String door, long refunds, long amountMinor,
            long principalCancelledMinor, long interestReleasedMinor, long excessToWalletMinor,
            long excessToCreditBalanceMinor, long feeReturnedMinor, long payableDebitMinor, long lateFeesReleasedMinor,
            long outstandingReducedMinor, boolean tiesToLedger) {}

    public record BnplRefundReport(String date, boolean known, List<BnplRefundRow> rows) {}

    /** The refunds valued on {@code date} and their impact on outstanding, tied to each refund's own entry. */
    public BnplRefundReport refunds(String date) {
        LocalDate day = date(date);
        return read("refunds", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new BnplRefundReport(day.toString(), false, List.of()), 0);
            }
            List<BnplRefundRow> rows = new ArrayList<>();
            for (BnplReportReads.RefundSum sum : reads.refunds(uow, day)) {
                long principal = exact(sum.principalCancelled());
                long interest = exact(sum.interestReleased());
                long receivables = exact(sum.ledgerReceivables());
                long fees = exact(sum.ledgerFeesReleased());
                rows.add(new BnplRefundRow(tag(sum.product()), sum.currency().strip(), sum.scale(), tag(sum.kind()),
                        tag(sum.door()), sum.refunds(), exact(sum.amount()), principal, interest,
                        exact(sum.excessToWallet()), exact(sum.excessToCreditBalance()), exact(sum.feeReturned()),
                        exact(sum.payableDebit()), fees, Math.addExact(receivables, fees),
                        Math.addExact(principal, interest) == receivables));
            }
            return new Served<>(new BnplRefundReport(day.toString(), true, List.copyOf(rows)), rows.size());
        });
    }

    // ------------------------------------------------------------------ the delinquency

    /**
     * One product's {@code ACTIVE} obligations on the date in one days-past-due bucket - the latest change recorded on or
     * before it, {@code current} when none - how many of them stood defaulted, and their due balances from the ledger:
     * principal, interest and fees billed or assessed and not yet paid.
     */
    public record BnplDelinquencyRow(
            String product, String currency, int scale, String bucket, long obligations, long defaulted,
            long principalDueMinor, long interestDueMinor, long feesDueMinor, long dueMinor) {}

    public record BnplDelinquencyReport(String date, boolean known, List<BnplDelinquencyRow> rows) {}

    /** The active book by days-past-due bucket as of the end of {@code date}, with its due sums from the ledger. */
    public BnplDelinquencyReport delinquency(String date) {
        LocalDate day = date(date);
        return read("delinquency", day, (uow, known) -> {
            if (!known) {
                return new Served<>(new BnplDelinquencyReport(day.toString(), false, List.of()), 0);
            }
            Map<String, Balances> dues = new TreeMap<>();
            for (BnplReportReads.Balance balance : reads.dues(uow, day)) {
                dues.computeIfAbsent(balance.group() + "|" + balance.currency().strip(), ignored -> new Balances())
                        .add(balance.scale(), balance.purpose(), balance.signed());
            }
            List<BnplDelinquencyRow> rows = new ArrayList<>();
            for (BnplReportReads.DelinquencyCount count : reads.delinquency(uow, day)) {
                String currency = count.currency().strip();
                Balances due = dues.getOrDefault(count.product() + "|" + count.bucket() + "|" + currency, new Balances());
                long principal = due.debit("BNPL_PRINCIPAL_DUE");
                long interest = due.debit("BNPL_INTEREST_DUE");
                long fees = due.debit("BNPL_FEES_DUE");
                rows.add(new BnplDelinquencyRow(tag(count.product()), currency, due.scale(currency),
                        tag(count.bucket()), count.obligations(), count.defaulted(), principal, interest, fees,
                        Math.addExact(Math.addExact(principal, interest), fees)));
            }
            return new Served<>(new BnplDelinquencyReport(day.toString(), true, List.copyOf(rows)), rows.size());
        });
    }

    // ------------------------------------------------------------------ plumbing

    /** A report and how many rows it serves - the audit's detail. */
    private record Served<R>(R report, int rows) {}

    @FunctionalInterface
    private interface Read<R> {
        Served<R> in(Connection snapshot, boolean known);
    }

    /**
     * One read-only {@code REPEATABLE READ} snapshot; then {@code bnpl.ReportRead} in a transaction of its own, committed
     * before the report is returned - a report whose read failed is answered by the error and recorded by nothing.
     */
    private <R> R read(String report, LocalDate day, Read<R> work) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a report runs inside a correlation scope"))
                .correlationId();
        boolean[] known = new boolean[1];
        Served<R> served = units.inSnapshot(snapshot -> {
            known[0] = reportable(snapshot, day);
            return work.in(snapshot, known[0]);
        });
        units.inTransaction(unitOfWork -> {
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    BnplAuditAction.REPORT_READ, "bnpl_report", report, Optional.empty(), AuditOutcome.SUCCEEDED,
                    correlation, Optional.of("report=" + report + ", date=" + day + ", known=" + known[0] + ", rows="
                            + served.rows())));
            return null;
        });
        return served.report();
    }

    private boolean reportable(Connection snapshot, LocalDate day) {
        return !day.isBefore(EARLIEST) && reads.reportable(snapshot, day);
    }

    /** The subledger and replay counts of {@code reading}, naming the failing and diverged agreements, bounded. */
    private BnplProofCounts proofCounts(Connection snapshot, BnplProofs.Reading reading) {
        List<UUID> failing = reading.failures().stream().map(BnplSubledgerProof.Verdict::obligation)
                .map(obligation -> obligation.value()).toList();
        List<UUID> diverged = reading.divergences().stream().map(BnplReplayProof.Replay::obligation)
                .map(obligation -> obligation.value()).toList();
        Set<UUID> named = new java.util.LinkedHashSet<>(bounded(failing));
        named.addAll(bounded(diverged));
        Map<UUID, UUID> agreements = named.isEmpty() ? Map.of() : reads.agreementsOf(snapshot, named);
        Function<List<UUID>, List<String>> agreementsOf = obligations -> bounded(obligations).stream()
                .map(obligation -> Optional.ofNullable(agreements.get(obligation)).orElse(obligation).toString())
                .toList();
        return new BnplProofCounts(reading.holding(), reading.failing(), agreementsOf.apply(failing),
                reading.replaying(BnplReplayProof.Verdict.IDENTICAL), reading.replaying(BnplReplayProof.Verdict.DIVERGED),
                agreementsOf.apply(diverged), failing.size() > BOUND || diverged.size() > BOUND);
    }

    private static <T> List<T> bounded(List<T> items) {
        return List.copyOf(items.size() > BOUND ? items.subList(0, BOUND) : items);
    }

    private static int statusIndex(String status) {
        return switch (status) {
            case "ACTIVE" -> 0;
            case "CLOSED" -> 1;
            default -> throw new IllegalStateException("an obligation status this build does not know");
        };
    }

    private static String key(String product, String currency) {
        return product + "|" + currency.strip();
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static long exact(BigDecimal value) {
        return value == null ? 0 : value.longValueExact();
    }

    /** {@code YYYY-MM-DD} or {@code 422} before any read. */
    static LocalDate date(String date) {
        if (date != null && DATE.matcher(date).matches()) {
            try {
                return LocalDate.parse(date);
            } catch (DateTimeParseException unparseable) {
                // Falls through: 2026-02-30 has the shape and no meaning.
            }
        }
        throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
                "A BNPL report was asked for a date that is not a calendar date",
                "date must be YYYY-MM-DD, a business date.");
    }

    /**
     * Signed sums per purpose at one scale: DR - CR, so a debit-normal receivable reads positive and a credit-normal
     * liability reads through {@link #credit}.
     */
    static final class Balances {
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
            BigDecimal total = BigDecimal.ZERO;
            for (String purpose : RECEIVABLES) {
                total = total.add(byPurpose.getOrDefault(purpose, BigDecimal.ZERO));
            }
            return exact(total);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Balances that)) {
                return false;
            }
            return nonZero().equals(that.nonZero());
        }

        @Override
        public int hashCode() {
            return nonZero().hashCode();
        }

        private Map<String, BigDecimal> nonZero() {
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            byPurpose.forEach((purpose, value) -> {
                if (value.signum() != 0) {
                    values.put(purpose, value.stripTrailingZeros());
                }
            });
            return values;
        }
    }
}
