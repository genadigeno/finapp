package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.InterestAccrual;
import com.finapp.creditmath.AccrualPeriod;
import com.finapp.creditmath.ScheduleEngineV1;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The BNPL battery's population (`P12-TST-002`; {@code PHASE_12_PLAN.md} section 13.3): a seeded portfolio of pay-in-4 and
 * pay-in-N purchases, each obligation's whole history written as {@link JdbcBnplRecordStore} would return it - a
 * {@link BnplRecord} - by a simulated calendar, one business date at a time, in the order production writes: T-pur at the
 * purchase; then every business date T-acc (pay-in-N's accrual of every elapsed date), T-svc (bill, the application at each
 * billing, the catch-up, the collection, the closure, the late fee), and the day's acts after it - the merchant's refunds,
 * the customer's repayments, a dispute opened, resolved as a refund or upheld, a payoff quoted and executed.
 *
 * <p><strong>Every stored figure is the battery's own arithmetic</strong> ({@link BnplBatteryOracles}) - never a production
 * engine's - so a replay {@code IDENTICAL} is two independent derivations agreeing, and an engine edited under its stored
 * name disagrees with the rows on exactly the figures the edit changes. The canonical terms and the offer document alone
 * are rendered by production's {@link BnplOfferTerms} and {@link BnplOfferDocument} (code-owned formats, not arithmetic -
 * over the ORACLE's illustration; their proof is the forged byte). The one production figure read is the population's
 * parameter, never a stored figure: the highest pay-in-N rate the terms door admits, by the door's own rule.
 *
 * <p><strong>Deterministic in any JVM.</strong> One {@link Random} per purchase, seeded from {@code (SEED, index)} - so a
 * purchase regenerates alone and a parallel stream cannot reorder it; every id is derived in UUIDv7's shape (ADR-0013),
 * never random; every instant derived from a business date in UTC (the one servicing zone, A22); no default zone, locale
 * or charset is read; no hash collection is iterated into a figure; no floating point ({@code INV-MON-01}).
 */
final class BnplBatteryWorld {

    /** The population's seed - a failure named by it reproduces on the same population. */
    static final long SEED = 20_261_020L;

    static final CurrencyCode EUR = BnplTermsFixtures.EUR;

    /** Every purchase date falls in 2027 (non-leap) or 2028 (leap). */
    static final LocalDate FIRST_DAY = LocalDate.of(2027, 1, 1);

    static final int START_DAYS = 731;

    /** Credit's bounds (`BnplTermsFixtures.BOUNDS`): pay-in-4 EUR 50.00-1,000.00, pay-in-N EUR 200.00-5,000.00. */
    static final long PAY_IN_4_MINIMUM = 50_00;

    static final long PAY_IN_4_MAXIMUM = 1_000_00;

    static final long PAY_IN_N_MINIMUM = 200_00;

    static final long PAY_IN_N_MAXIMUM = 5_000_00;

    /** Pay-in-N's first-period floor (`BnplTermsFixtures.payInN`, A6). */
    static final int MIN_FIRST_PERIOD_DAYS = 15;

    /** The highest pay-in-N rate the terms door admits at credit's bounds, in units of {@code 10^-8}, by bisection. */
    static final long MAXIMUM_RATE_UNITS = maximumAdmittedRateUnits();

    /** The merchants' BNPL fee schedules: basis points and a fixed part - the first prices nothing (PN's optional fee). */
    static final int[] FEE_BPS = {0, 150, 299, 425, 600, 1_000};

    static final long[] FEE_FIXED = {0, 30, 0, 25, 0, 50};

    /** The late fee variants: none, EUR 5.00 capped at 12.00 (the third fee capped at 2.00), EUR 7.50 capped at 30.00. */
    static final long[] LATE_FEE = {0, 5_00, 7_50};

    static final long[] LATE_FEE_CAP = {15_00, 12_00, 30_00};

    /** Each purchase's history is simulated this far past its last due date at most. */
    static final int TAIL_DAYS = 130;

    /** How each stored figure was computed. */
    enum Arithmetic {
        /** The rules as the plan and the ADRs state them - what the pinned engines compute. */
        STANDARD,
        /** Pay-in-4's remainder on the last instalment - an engine swapped under BNPL_SCHEDULE_ENGINE_V1. */
        REMAINDER_LAST,
        /** Pay-in-N's accrual rounded through a scale-3 intermediate - an engine swapped under ACCRUAL_ENGINE_V1. */
        ROUNDED_TWICE
    }

    /** A purchase's behaviour on the simulated calendar. */
    enum Cohort {
        /** Pays every instalment on its due date. */
        ON_TIME,
        /** Pays one to four days after each due date - no late fee (the fee's day is 5). */
        IN_WINDOW,
        /** Pays five to forty days late - late fees, the cap reached on long plans. */
        LATE,
        /** Pays part on the due date and the rest later. */
        PARTIAL,
        /** Pays more than due - held as credit, applied at the next billing, refunded at the closure. */
        OVER,
        /** Pays the next instalment a few days before it falls due - held as credit, applied at its billing. */
        EARLY,
        /** Never pays directly: the servicing step collects from a wallet topped up fully, partly or late. */
        COLLECTED,
        /** Pays some instalments on time, then stops - late fees to the cap, the record ending ACTIVE. */
        DEFAULTER,
        /** A refund before the second instalment falls due, partial or full. */
        REFUND_BEFORE,
        /** A refund in the instalments' life - partial, the exact unpaid remainder, or the whole remaining. */
        REFUND_DURING,
        /** A refund after everything was paid and the obligation closed - all of it the customer's (OQ-15). */
        REFUND_AFTER,
        /** Two to four refunds at random points, half of them totalling the gross. */
        MULTI_REFUND,
        /** A dispute the merchant accepts - its outcome a refund (door DISPUTE). */
        DISPUTE_REFUNDED,
        /** A dispute the merchant answers and an operator upholds - its outcome nothing, the hold lifted. */
        DISPUTE_UPHELD,
        /** An early payoff - quoted, and executed on its good-through date when nothing moved meanwhile. */
        PAYOFF
    }

    /** What the writer counts as it writes - the census's coverage tags. */
    enum Tag {
        PAY_IN_4,
        PAY_IN_3,
        PAY_IN_6,
        PAY_IN_12,
        NON_DIVISIBLE_PAY_IN_4,
        AMOUNT_EDGE,
        LEAP_DAY_PURCHASES,
        MONTH_END_PURCHASES,
        OFFERED_DAY_BEFORE,
        RATE_ZERO,
        RATE_MAXIMUM,
        ZERO_MERCHANT_FEE,
        ACCRUALS,
        ZERO_POSTINGS,
        LEAP_DAY_ACCRUALS,
        BILLINGS,
        EARLY_BILLINGS,
        CUSTOMER_REPAYMENTS,
        MULTI_ITEM_REPAYMENTS,
        AUTO_COLLECTIONS,
        COLLECTED,
        PARTIALLY_COLLECTED,
        INSUFFICIENT_FUNDS,
        NOTHING_DUE,
        APPLICATIONS_AT_BILLING,
        CREDIT_HELD,
        LATE_FEES,
        LATE_FEES_CAPPED,
        LATE_FEE_CAP_REACHED,
        REFUNDS,
        REFUNDS_PARTIAL,
        REFUNDS_FULL,
        REFUNDS_BEFORE,
        REFUNDS_DURING,
        REFUNDS_AFTER_BILLED,
        REFUNDS_AFTER_CLOSURE,
        REFUNDS_WITH_EXCESS,
        REFUNDS_TO_CREDIT,
        REFUND_FEE_RETURNED,
        REFUND_SCHEDULE_VERSIONS,
        REFUND_REAMORTISED,
        REFUND_INTEREST_RELEASED,
        REFUND_LATE_FEE_RELEASED,
        FULLY_REFUNDED,
        DISPUTES_REFUNDED,
        DISPUTES_UPHELD,
        HELD_INSTALMENTS,
        FEES_AFTER_LIFT,
        PAYOFF_QUOTES,
        PAYOFF_PROJECTED,
        QUOTES_STALE,
        PAYOFFS,
        CLOSED_REPAID,
        CLOSED_PAID_OFF_EARLY,
        CLOSED_REFUNDED,
        CLOSED_REFUNDED_PARTIALLY_REPAID,
        CLOSURE_REFUNDS,
        STILL_ACTIVE
    }

    /**
     * The purchase's position at its first refund, for the every-order property: its gross and fee, and each instalment as
     * {@link RefundPosition.Instalment} reads it - {@code principal, billed (1/0), allocated, cancelled} - before any refund,
     * and every refund amount the purchase was refunded, in its own order.
     */
    record RefundCase(long gross, long fee, List<long[]> slots, List<Long> amounts) {

        RefundCase {
            slots = List.copyOf(slots);
            amounts = List.copyOf(amounts);
        }
    }

    /**
     * One generated purchase: its record, its coverage, and what a swapped arithmetic would change.
     *
     * @param predicted under {@code REMAINDER_LAST}, the gross is not divisible by 4; under {@code ROUNDED_TWICE}, some
     *     accrual's posting differs between single and double rounding of the same cumulative figures - the purchases a
     *     swapped engine must find {@code DIVERGED}
     */
    record Generated(int index, Arithmetic arithmetic, BnplRecord record, BnplProduct product, BnplPlan plan,
            Cohort cohort, long[] tags, long gross, long fee, LocalDate purchaseDate, boolean predicted,
            Optional<RefundCase> refundCase) {}

    private BnplBatteryWorld() {}

    /** The purchase {@code index}, its figures by {@code arithmetic}. */
    static Generated generate(int index, Arithmetic arithmetic) {
        return new Writer(index, arithmetic).write();
    }

    /** The product of purchase {@code index} - the generator's walk: even indices pay in 4, odd ones in N. */
    static BnplProduct productOf(int index) {
        return index % 2 == 0 ? BnplProduct.BNPL_PAY_IN_4 : BnplProduct.BNPL_PAY_IN_N;
    }

    // ------------------------------------------------------------------ the shared set

    /** The door's own rule - {@link ScheduleEngineV1#judgeAmortisation} at credit's smallest amount, the longest plan. */
    private static long maximumAdmittedRateUnits() {
        long admitted = 0;
        long refused = 100_000_001L; // the rate's own bound, 1, admitted when the door admits it
        while (refused - admitted > 1) {
            long middle = admitted + (refused - admitted) / 2;
            BnplTerms terms = BnplTermsFixtures.payInN(BigDecimal.valueOf(middle, 8).toPlainString()).build();
            if (ScheduleEngineV1.judgeAmortisation(terms.scheduleTerms().orElseThrow(),
                    BnplTermsAdministration.PURCHASE_DAYS, Money.ofMinorUnits(PAY_IN_N_MINIMUM, EUR), 12).isEmpty()) {
                admitted = middle;
            } else {
                refused = middle;
            }
        }
        return admitted;
    }

    /** A UUIDv7-shaped id shared by the whole population: the merchants, their payables, the operational rows. */
    static UUID shared(long n) {
        return new UUID((0x019A_2026_1020L << 16) | 0x7000L | 0x5AEL, 0x8000_0000_0000_0000L | n);
    }

    static final LedgerAccountId MERCHANT_FEE_INCOME = LedgerAccountId.of(shared(1));

    static final LedgerAccountId INTEREST_INCOME = LedgerAccountId.of(shared(2));

    static final LedgerAccountId LATE_FEE_INCOME = LedgerAccountId.of(shared(3));

    static UUID merchant(int m) {
        return shared(100 + m);
    }

    static LedgerAccountId payable(int m) {
        return LedgerAccountId.of(shared(200 + m));
    }

    static UUID feeSchedule(int m) {
        return shared(300 + m);
    }

    static Money money(long minor) {
        return Money.ofPersisted(minor, EUR, BnplBatteryOracles.SCALE);
    }

    // ------------------------------------------------------------------ the writer

    /** One billed instalment as the writer tracks it: billed, and settled by every payer and refund. */
    private static final class Item {
        final int sequence;
        final UUID instalment;
        final UUID billing;
        final LocalDate dueDate;
        final long principal;
        final long interest;
        long fees;
        long paidFees;
        long paidInterest;
        long paidPrincipal;
        boolean charged;

        Item(int sequence, UUID instalment, UUID billing, LocalDate dueDate, long principal, long interest) {
            this.sequence = sequence;
            this.instalment = instalment;
            this.billing = billing;
            this.dueDate = dueDate;
            this.principal = principal;
            this.interest = interest;
        }

        long due(AllocationComponent component) {
            return switch (component) {
                case FEES -> fees - paidFees;
                case INTEREST -> interest - paidInterest;
                case PRINCIPAL -> principal - paidPrincipal;
            };
        }

        long total() {
            return due(AllocationComponent.FEES) + due(AllocationComponent.INTEREST) + due(AllocationComponent.PRINCIPAL);
        }

        void pay(AllocationComponent component, long amount) {
            switch (component) {
                case FEES -> paidFees += amount;
                case INTEREST -> paidInterest += amount;
                case PRINCIPAL -> paidPrincipal += amount;
            }
        }
    }

    /** A merchant's refund to come: on a date, or so many days after the closure. */
    private record RefundPlan(Optional<LocalDate> on, int afterClosure, Amount amount, long value) {}

    private enum Amount {
        FULL_REMAINING,
        UNPAID_REMAINDER,
        PARTIAL,
        FIXED
    }

    /** A dispute being written. */
    private static final class Dispute {
        final BnplDisputeId id;
        final long amount;
        final long refundedBefore;
        final LocalDate openedOn;
        final Instant openedAt;
        final List<BnplDispute.Held> holds;
        final boolean toRefund;
        final LocalDate respondOn;
        final LocalDate resolveOn;
        BnplDisputeStatus status = BnplDisputeStatus.OPEN;
        Optional<Instant> respondedAt = Optional.empty();
        Optional<LocalDate> resolvedOn = Optional.empty();
        Optional<Instant> resolvedAt = Optional.empty();
        Optional<UUID> refund = Optional.empty();

        Dispute(BnplDisputeId id, long amount, long refundedBefore, LocalDate openedOn, Instant openedAt,
                List<BnplDispute.Held> holds, boolean toRefund, LocalDate respondOn, LocalDate resolveOn) {
            this.id = id;
            this.amount = amount;
            this.refundedBefore = refundedBefore;
            this.openedOn = openedOn;
            this.openedAt = openedAt;
            this.holds = holds;
            this.toRefund = toRefund;
            this.respondOn = respondOn;
            this.resolveOn = resolveOn;
        }

        boolean open() {
            return status == BnplDisputeStatus.OPEN || status == BnplDisputeStatus.MERCHANT_RESPONDED;
        }
    }

    private static final class Writer {

        private static final BigDecimal NOTHING = BigDecimal.ZERO.setScale(BnplBatteryOracles.EXACT_SCALE);

        final int index;
        final Arithmetic arithmetic;
        final Random random;
        final long idHigh;
        int ids;
        long tick;
        final long[] tags = new long[Tag.values().length];
        boolean predicted;

        // The purchase.
        BnplProduct product;
        BnplPlan plan;
        Cohort cohort;
        BnplTerms terms;
        BigDecimal rate;
        boolean payInN;
        long gross;
        long fee;
        int merchant;
        LocalDate purchaseDate;
        CorrelationId correlation;

        BnplObligationId obligationId;
        BnplPurchaseId purchaseId;
        BnplAgreementId agreementId;
        BnplOfferId offerId;
        BnplApplicationId applicationId;
        BnplTermsVersionId versionId;
        UUID party;
        UUID decision;
        UUID session;
        LedgerAccountId wallet;

        // The ledger, the obligation's six accounts.
        final Map<AccountPurpose, LedgerAccountId> accounts = new EnumMap<>(AccountPurpose.class);
        long principal;
        long principalDue;
        long accrued;
        long interestDue;
        long feesDue;
        long credit;
        final List<PurposeBalances.DatedLine> journal = new ArrayList<>();
        final List<BnplRecord.EntryLines> shared = new ArrayList<>();

        // The rows.
        BnplPurchaseStore.StoredSchedule first;
        BnplPurchaseStore.StoredSchedule latest;
        BnplAgreement governing;
        final List<BnplRecord.Agreement> agreements = new ArrayList<>();
        final List<BnplRecord.Schedule> schedules = new ArrayList<>();
        final List<BnplRecord.Billing> billings = new ArrayList<>();
        final List<BnplRecord.Stamped<BnplInterestAccrual>> accruals = new ArrayList<>();
        final List<BnplRecord.Stamped<BnplCreditBalanceApplication>> applications = new ArrayList<>();
        final List<BnplRecord.Stamped<BnplPurchaseStore.Repayment>> repayments = new ArrayList<>();
        final List<BnplRecord.Stamped<BnplCollectionAttempt>> attempts = new ArrayList<>();
        final List<BnplRecord.Stamped<BnplRefund>> refunds = new ArrayList<>();
        final List<BnplRecord.Stamped<BnplFeeAssessment>> fees = new ArrayList<>();
        final List<BnplRecord.Stamped<BnplPayoffQuote>> quotes = new ArrayList<>();
        Optional<BnplRecord.Stamped<BnplPayoffExecution>> execution = Optional.empty();
        final List<Dispute> disputes = new ArrayList<>();
        Optional<BnplRecord.Stamped<BnplClosure>> closure = Optional.empty();
        BnplOffer offer;
        BnplOfferStore.StoredTerms offerTerms;
        BnplPurchase purchaseRow;
        MerchantFinancing financing;
        Instant financedAt;

        // The state the writer reads, as production reads it under its locks.
        final TreeMap<Integer, Item> items = new TreeMap<>();
        final Map<Integer, Long> unbilledCancelled = new HashMap<>();
        final Map<Integer, Long> billedCancelled = new HashMap<>();
        BnplInterestAccrual lastAccrual;
        long feesAssessed;
        int catchUps;
        long refundedBefore;
        long feeReturnedBefore;
        boolean active = true;
        /** The customer's wallet cannot be credited on a refund's day (OQ-13): the excess held as credit instead. */
        boolean walletDown;
        Optional<LocalDate> downOn = Optional.empty();
        LocalDate closedOn;
        Optional<LocalDate> payoffDate = Optional.empty();
        final List<Long> refundAmounts = new ArrayList<>();
        Optional<List<long[]>> firstPosition = Optional.empty();

        // The calendar's plans.
        final int[] lag = new int[13];
        final int[] fraction = new int[13];
        final long[] extra = new long[13];
        final int[] early = new int[13];
        final int[] funding = new int[13];
        int stopAfter;
        Cohort style;
        int resumeLag;
        boolean capReached;
        final List<RefundPlan> refundPlans = new ArrayList<>();
        Optional<LocalDate> disputeOn = Optional.empty();
        long disputeAmountSpec;
        boolean disputePause;
        Optional<LocalDate> quoteOn = Optional.empty();
        int goodThroughDays;
        BnplPayoffQuote openQuote;
        boolean payoffStale;
        long walletBalance;
        LocalDate lastDue;

        Writer(int index, Arithmetic arithmetic) {
            this.index = index;
            this.arithmetic = arithmetic;
            this.random = new Random(SEED * 0x9E3779B97F4A7C15L + 0x424E504CL * 31 + index);
            // A UUIDv7's shape (ADR-0013: version 7, variant 2), derived: a fixed "timestamp", the battery in rand_a.
            this.idHigh = (0x019A_2026_1020L << 16) | 0x7000L | 0xB47L;
        }

        // -------------------------------------------------------------- plumbing

        /** A derived UUIDv7-shaped id - never random: the purchase's index and a per-purchase counter. */
        UUID id() {
            return new UUID(idHigh, 0x8000_0000_0000_0000L | ((long) index << 32) | ++ids);
        }

        /** An instant inside {@code date} (UTC, A22) at {@code hour}, strictly after every instant written before it. */
        Instant at(LocalDate date, int hour) {
            return date.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3_600L * hour).plusMillis(++tick);
        }

        long draw(long bound) {
            return Math.floorMod(random.nextLong(), bound);
        }

        int between(int low, int high) {
            return low + random.nextInt(high - low + 1);
        }

        void tag(Tag tag) {
            tags[tag.ordinal()]++;
        }

        JournalEntryId entry() {
            return JournalEntryId.of(id());
        }

        /** One leg on an obligation-owned account; the counter-legs are not the obligation's. */
        PurposeBalances.DatedLine leg(JournalEntryId entry, AccountPurpose purpose, Direction direction, long amount,
                LocalDate valueDate) {
            PurposeBalances.DatedLine line = new PurposeBalances.DatedLine(entry, accounts.get(purpose), valueDate,
                    direction, money(amount));
            journal.add(line);
            long signed = direction == Direction.DEBIT ? amount : -amount;
            switch (purpose) {
                case BNPL_PRINCIPAL -> principal += signed;
                case BNPL_PRINCIPAL_DUE -> principalDue += signed;
                case BNPL_INTEREST_ACCRUED -> accrued += signed;
                case BNPL_INTEREST_DUE -> interestDue += signed;
                case BNPL_FEES_DUE -> feesDue += signed;
                case BNPL_CREDIT_BALANCE -> credit -= signed;
                default -> throw new IllegalStateException("not an obligation-owned purpose: " + purpose);
            }
            return line;
        }

        static AccountPurpose dueAccount(AllocationComponent component) {
            return switch (component) {
                case FEES -> AccountPurpose.BNPL_FEES_DUE;
                case INTEREST -> AccountPurpose.BNPL_INTEREST_DUE;
                case PRINCIPAL -> AccountPurpose.BNPL_PRINCIPAL_DUE;
            };
        }

        /** {@code bnpl.principal_value_floor}: the purchase's date, the latest billing's value date, the day after the latest accrual. */
        LocalDate floor() {
            LocalDate floor = purchaseDate;
            for (BnplRecord.Billing billing : billings) {
                LocalDate counted = payoffDate.filter(date -> date.isBefore(billing.row().dueDate()))
                        .orElse(billing.row().dueDate());
                floor = counted.isAfter(floor) ? counted : floor;
            }
            if (lastAccrual != null && lastAccrual.accrual().date().plusDays(1).isAfter(floor)) {
                floor = lastAccrual.accrual().date().plusDays(1);
            }
            return floor;
        }

        LocalDate valueDate(LocalDate today) {
            LocalDate floor = floor();
            return floor.isAfter(today) ? floor : today;
        }

        boolean billed(LocalDate dueDate) {
            for (Item item : items.values()) {
                if (item.dueDate.equals(dueDate)) {
                    return true;
                }
            }
            return false;
        }

        List<BnplPurchaseStore.StoredInstalment> unbilled() {
            List<BnplPurchaseStore.StoredInstalment> rows = new ArrayList<>();
            for (BnplPurchaseStore.StoredInstalment row : latest.instalments()) {
                if (!billed(row.dueDate())) {
                    rows.add(row);
                }
            }
            return rows;
        }

        long dueTotal() {
            long total = 0;
            for (Item item : items.values()) {
                total += item.total();
            }
            return total;
        }

        // -------------------------------------------------------------- the dispute hold (ADR-0106 decision 3)

        /** Whether an instalment due {@code due} is held on {@code day} - as the servicing step reads it then. */
        boolean held(LocalDate due, LocalDate day) {
            for (Dispute dispute : disputes) {
                if (dispute.openedOn.isAfter(day)) {
                    continue;
                }
                boolean holding = dispute.open() || dispute.resolvedOn.map(lifted -> lifted.isAfter(day)).orElse(true);
                if (holding && dispute.holds.stream().anyMatch(each -> each.dueDate().equals(due))) {
                    return true;
                }
            }
            return false;
        }

        /** The later of the due date and the latest lift by {@code day} of a dispute that held it. */
        LocalDate clockFrom(LocalDate due, LocalDate day) {
            LocalDate from = due;
            for (Dispute dispute : disputes) {
                if (dispute.resolvedOn.isPresent() && !dispute.resolvedOn.get().isAfter(day)
                        && dispute.resolvedOn.get().isAfter(from)
                        && dispute.holds.stream().anyMatch(each -> each.dueDate().equals(due))) {
                    from = dispute.resolvedOn.get();
                }
            }
            return from;
        }

        // -------------------------------------------------------------- the purchase (T-pur)

        Generated write() {
            choose();
            purchase();
            LocalDate end = lastDue.plusDays(TAIL_DAYS);
            for (LocalDate day = purchaseDate; ; day = day.plusDays(1)) {
                if (day.isAfter(purchaseDate) && active) {
                    topUp(day);
                    if (payInN) {
                        accrue(day);
                    }
                    service(day);
                }
                act(day);
                boolean waiting = !refundPlans.isEmpty() || disputeOn.isPresent()
                        || disputes.stream().anyMatch(Dispute::open);
                if ((!active && !waiting) || !day.isBefore(end)) {
                    break;
                }
            }
            if (active) {
                tag(Tag.STILL_ACTIVE);
            }
            return new Generated(index, arithmetic, record(), product, plan, cohort, tags, gross, fee, purchaseDate,
                    predicted, firstPosition.map(slots -> new RefundCase(gross, fee, slots, refundAmounts)));
        }

        /** The purchase's parameters - the walk of the index for coverage, its own random for the rest. */
        void choose() {
            product = productOf(index);
            payInN = product == BnplProduct.BNPL_PAY_IN_N;
            cohort = Cohort.values()[(index / 2) % Cohort.values().length];
            plan = payInN ? List.of(BnplPlan.PAY_IN_3_MONTHS, BnplPlan.PAY_IN_6_MONTHS, BnplPlan.PAY_IN_12_MONTHS)
                    .get(random.nextInt(3)) : BnplPlan.PAY_IN_4;
            tag(switch (plan) {
                case PAY_IN_4 -> Tag.PAY_IN_4;
                case PAY_IN_3_MONTHS -> Tag.PAY_IN_3;
                case PAY_IN_6_MONTHS -> Tag.PAY_IN_6;
                case PAY_IN_12_MONTHS -> Tag.PAY_IN_12;
            });
            if (index % 7 == 3) {
                purchaseDate = YearMonth.from(FIRST_DAY).plusMonths((index / 7) % 24).atEndOfMonth();
                tag(Tag.MONTH_END_PURCHASES);
            } else {
                purchaseDate = FIRST_DAY.plusDays(Math.floorMod(index * 293L, START_DAYS));
                if (purchaseDate.equals(purchaseDate.withDayOfMonth(purchaseDate.lengthOfMonth()))) {
                    tag(Tag.MONTH_END_PURCHASES);
                }
            }
            if (purchaseDate.getMonth() == Month.FEBRUARY && purchaseDate.getDayOfMonth() == 29) {
                tag(Tag.LEAP_DAY_PURCHASES);
            }
            long minimum = payInN ? PAY_IN_N_MINIMUM : PAY_IN_4_MINIMUM;
            long maximum = payInN ? PAY_IN_N_MAXIMUM : PAY_IN_4_MAXIMUM;
            int edge = (index / 2) % 53;
            if (edge < 5) {
                gross = switch (edge) {
                    case 0 -> minimum;
                    case 1 -> maximum;
                    case 2 -> minimum + 1;
                    case 3 -> maximum - 1;
                    default -> minimum + 3;
                };
                tag(Tag.AMOUNT_EDGE);
            } else {
                gross = minimum + draw(maximum - minimum + 1);
            }
            if (!payInN && gross % 4 != 0) {
                tag(Tag.NON_DIVISIBLE_PAY_IN_4);
            }
            merchant = random.nextInt(FEE_BPS.length);
            fee = BnplBatteryOracles.merchantFee(gross, FEE_BPS[merchant], FEE_FIXED[merchant]);
            if (fee == 0) {
                tag(Tag.ZERO_MERCHANT_FEE);
            }
            int feeVariant = random.nextInt(LATE_FEE.length);
            boolean collects = cohort == Cohort.COLLECTED
                    || ((cohort == Cohort.DISPUTE_REFUNDED || cohort == Cohort.DISPUTE_UPHELD) && random.nextBoolean());
            BnplTerms.BnplTermsBuilder builder;
            if (payInN) {
                int rateCase = (index / 2) % 16;
                long units = switch (rateCase) {
                    case 1 -> 0;
                    case 3 -> MAXIMUM_RATE_UNITS;
                    case 5 -> 1;
                    default -> 1 + draw(MAXIMUM_RATE_UNITS);
                };
                if (units == 0) {
                    tag(Tag.RATE_ZERO);
                }
                if (units == MAXIMUM_RATE_UNITS) {
                    tag(Tag.RATE_MAXIMUM);
                }
                builder = BnplTermsFixtures.payInN(BigDecimal.valueOf(units, 8).toPlainString());
            } else {
                builder = BnplTermsFixtures.payIn4();
            }
            terms = builder.lateFee(money(LATE_FEE[feeVariant])).lateFeeCap(money(LATE_FEE_CAP[feeVariant]))
                    .autoCollection(collects).partialCollection(random.nextBoolean()).build();
            rate = terms.nominalAnnualRate();
            correlation = CorrelationId.of("bnpl-battery-" + index);

            // The refunding and disputing cohorts pay on time, a few days late, or late - so refunds meet late fees and
            // billed-unpaid instalments, and a dispute's hold meets the servicing step.
            style = switch (cohort) {
                case REFUND_BEFORE, REFUND_DURING, REFUND_AFTER, MULTI_REFUND, DISPUTE_REFUNDED, DISPUTE_UPHELD ->
                        List.of(Cohort.ON_TIME, Cohort.IN_WINDOW, Cohort.LATE).get(random.nextInt(3));
                default -> cohort;
            };
            // The calendar's plans, drawn whole before anything is written.
            for (int k = 0; k < 13; k++) {
                lag[k] = style == Cohort.LATE ? between(5, 40) : between(1, 4);
                fraction[k] = between(1, 99);
                extra[k] = 1 + draw(payInN ? 30_000 : 8_000);
                early[k] = between(1, 5);
                funding[k] = random.nextInt(4);
            }
            stopAfter = between(payInN ? 0 : 1, plan.instalments() - 1);
            resumeLag = between(1, 12);
            walletDown = random.nextInt(4) == 0;
        }

        /** T-pur: the offer, the agreement and schedule version 1, the financing F1 and - pay-in-4 - instalment 1 F2/F3. */
        void purchase() {
            obligationId = BnplObligationId.of(id());
            purchaseId = BnplPurchaseId.of(id());
            agreementId = BnplAgreementId.of(id());
            offerId = BnplOfferId.of(id());
            applicationId = BnplApplicationId.of(id());
            versionId = BnplTermsVersionId.of(id());
            party = id();
            decision = id();
            session = id();
            wallet = LedgerAccountId.of(id());
            for (AccountPurpose purpose : BnplSubledgerProof.PURPOSES) {
                accounts.put(purpose, LedgerAccountId.of(id()));
            }

            // An odd modulus coprime to the walk's 2 and 15 (the gate's lattice finding): both products, every cohort.
            boolean dayBefore = index % 49 == 17;
            LocalDate offeredOn = dayBefore ? purchaseDate.minusDays(1) : purchaseDate;
            if (dayBefore) {
                tag(Tag.OFFERED_DAY_BEFORE);
            }
            BnplIllustration shown = illustration(offeredOn);
            String canonical = BnplOfferTerms.render(new BnplOfferTerms.Content(offerId, applicationId, decision,
                    versionId, 1, terms, plan, shown));
            byte[] termsSha = BnplOfferTerms.sha256(canonical);
            byte[] documentSha = BnplOfferTerms.sha256(BnplOfferDocument.render(offerId, terms, plan, shown,
                    HexFormat.of().formatHex(termsSha)));
            offerTerms = BnplOfferStore.StoredTerms.of(plan, rate, shown, canonical, documentSha);
            // T-pur's hour: 10:00, or just past midnight for an offer made the evening before (A17's 30 minutes).
            int hour = dayBefore ? 0 : 10;
            Instant offeredAt = dayBefore ? purchaseDate.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(600)
                    : at(purchaseDate, hour);
            Instant authorisedAt = at(purchaseDate, hour);
            openedAt = at(purchaseDate, hour);
            Instant agreed = at(purchaseDate, hour);
            offer = new BnplOffer(offerId, applicationId, party, product, plan, BnplOfferStatus.ACCEPTED, decision,
                    versionId, 1, session, offeredAt, offeredAt.plusSeconds(1_800), Optional.of(agreed), correlation);

            governing = new BnplAgreement(agreementId, 1, obligationId, offerId, decision, versionId, product, plan,
                    offerTerms.scheduleEngine(), terms.payInN().map(own -> own.accrualEngine().name()),
                    terms.allocationEngine().name(), terms.refundAllocationEngine().name(), feeSchedule(merchant),
                    terms.agreementTemplate(), offerTerms.canonicalFormat(), canonical, termsSha, documentSha, agreed);
            agreements.add(new BnplRecord.Agreement(governing, Optional.empty(), 1));

            first = scheduleOne();
            latest = first;
            schedules.add(new BnplRecord.Schedule(first, Optional.empty(), at(purchaseDate, hour)));
            lastDue = first.instalments().get(first.instalments().size() - 1).dueDate();

            // F1: DR BNPL_PRINCIPAL G; DR MERCHANT_PAYABLE f / CR MERCHANT_PAYABLE G; CR BNPL_MERCHANT_FEE_INCOME f.
            JournalEntryId financingEntry = entry();
            leg(financingEntry, AccountPurpose.BNPL_PRINCIPAL, Direction.DEBIT, gross, purchaseDate);
            List<JournalLine> lines = new ArrayList<>();
            lines.add(new JournalLine(accounts.get(AccountPurpose.BNPL_PRINCIPAL), Direction.DEBIT, money(gross)));
            lines.add(new JournalLine(payable(merchant), Direction.CREDIT, money(gross)));
            if (fee > 0) {
                lines.add(new JournalLine(payable(merchant), Direction.DEBIT, money(fee)));
                lines.add(new JournalLine(MERCHANT_FEE_INCOME, Direction.CREDIT, money(fee)));
            }
            shared.add(new BnplRecord.EntryLines(financingEntry, lines));
            financing = new MerchantFinancing(purchaseId, merchant(merchant), new MerchantFeeSplit(money(gross), money(fee),
                    money(gross - fee), feeSchedule(merchant)), financingEntry);
            financedAt = at(purchaseDate, hour);

            if (!payInN) {
                // Instalment 1 billed (F2) and collected from the wallet (F3) inside the purchase (A5, A7, B6).
                BnplPurchaseStore.StoredInstalment one = first.instalments().get(0);
                bill(purchaseDate, one, purchaseDate, Optional.empty(), hour);
                Item item = items.get(1);
                receive(purchaseDate, BnplPurchaseStore.Repayment.AT_PURCHASE, purchaseId.value().toString(),
                        one.amount().minorUnits(), hour);
                if (item.total() != 0) {
                    throw new IllegalStateException("instalment 1's own amount settles exactly instalment 1");
                }
            }
            purchaseRow = new BnplPurchase(purchaseId, offerId, session, party, merchant(merchant), product, plan, EUR,
                    BnplPurchaseStatus.FINANCED, purchaseDate, authorisedAt, Optional.of(at(purchaseDate, hour)),
                    Optional.of(id()), correlation);
            plan();
        }

        /** The offer's illustration from {@code start} - the ORACLE's figures in production's shape. */
        BnplIllustration illustration(LocalDate start) {
            List<BnplIllustration.Line> lines = new ArrayList<>();
            if (!payInN) {
                long[] amounts = arithmetic == Arithmetic.REMAINDER_LAST ? BnplBatteryOracles.payIn4RemainderLast(gross)
                        : BnplBatteryOracles.payIn4(gross);
                List<LocalDate> dates = BnplBatteryOracles.payIn4Dates(start);
                for (int k = 0; k < amounts.length; k++) {
                    lines.add(new BnplIllustration.Line(k + 1, dates.get(k), money(amounts[k]), money(0),
                            money(amounts[k])));
                }
                if (arithmetic == Arithmetic.REMAINDER_LAST && gross % 4 != 0) {
                    predicted = true;
                }
                return new BnplIllustration(BnplEngine.BNPL_SCHEDULE_ENGINE_V1.name(), start, money(gross), lines);
            }
            BnplBatteryOracles.Plan schedule = BnplBatteryOracles.schedule(gross, rate, start,
                    BnplBatteryOracles.dueDates(start, MIN_FIRST_PERIOD_DAYS, start.getDayOfMonth(), plan.instalments()));
            for (BnplBatteryOracles.Period period : schedule.periods()) {
                lines.add(new BnplIllustration.Line(period.sequence(), period.due(), money(period.principal()),
                        money(period.interest()), money(period.amount())));
            }
            return new BnplIllustration(EngineVersion.SCHEDULE_ENGINE_V1.name(), start, money(gross), lines);
        }

        BnplPurchaseStore.StoredSchedule scheduleOne() {
            BnplIllustration projected = illustration(purchaseDate);
            List<BnplPurchaseStore.StoredInstalment> rows = new ArrayList<>();
            for (BnplIllustration.Line line : projected.lines()) {
                rows.add(new BnplPurchaseStore.StoredInstalment(id(), line.sequence(), line.dueDate(), line.principal(),
                        line.interest(), line.amount()));
            }
            return new BnplPurchaseStore.StoredSchedule(id(), obligationId, 1, projected.engine(), money(gross),
                    purchaseDate, rows);
        }

        /** The merchant's, the customer's and the dispute's plans on the calendar - drawn whole, before the first act. */
        void plan() {
            List<LocalDate> dues = first.instalments().stream().map(BnplPurchaseStore.StoredInstalment::dueDate).toList();
            LocalDate second = dues.size() > 1 ? dues.get(1) : dues.get(0);
            switch (cohort) {
                case REFUND_BEFORE -> refundPlans.add(new RefundPlan(Optional.of(purchaseDate.plusDays(
                        draw(Math.max(1, ChronoUnit.DAYS.between(purchaseDate, payInN ? dues.get(0) : second))))), 0,
                        random.nextBoolean() ? Amount.FULL_REMAINING : Amount.PARTIAL, 0));
                case REFUND_DURING -> refundPlans.add(new RefundPlan(Optional.of(second.plusDays(
                        draw(Math.max(1, ChronoUnit.DAYS.between(second, lastDue))))), 0,
                        List.of(Amount.PARTIAL, Amount.UNPAID_REMAINDER, Amount.FULL_REMAINING).get(random.nextInt(3)),
                        0));
                case REFUND_AFTER -> refundPlans.add(new RefundPlan(Optional.empty(), between(1, 30),
                        random.nextBoolean() ? Amount.FULL_REMAINING : Amount.PARTIAL, 0));
                case MULTI_REFUND -> {
                    int count = between(2, 4);
                    boolean whole = random.nextBoolean();
                    long left = gross;
                    for (int k = 0; k < count; k++) {
                        long value;
                        if (k == count - 1 && whole) {
                            value = left;
                        } else {
                            value = 1 + draw(Math.max(1, (whole ? left - (count - 1 - k) : left / 2)));
                        }
                        value = Math.max(1, Math.min(value, left - (whole ? count - 1 - k : 0)));
                        left -= value;
                        long span = ChronoUnit.DAYS.between(purchaseDate, lastDue) + 40;
                        refundPlans.add(new RefundPlan(Optional.of(purchaseDate.plusDays(draw(span))), 0, Amount.FIXED,
                                value));
                    }
                    refundPlans.sort(java.util.Comparator.comparing(plan -> plan.on().orElseThrow()));
                }
                case DISPUTE_REFUNDED, DISPUTE_UPHELD -> {
                    disputeOn = Optional.of(purchaseDate.plusDays(1 + draw(Math.max(1,
                            ChronoUnit.DAYS.between(purchaseDate, lastDue) - 1))));
                    disputeAmountSpec = random.nextInt(3) == 0 ? 0 : 1 + draw(gross - 1);
                    disputePause = random.nextBoolean();
                }
                case PAYOFF -> {
                    quoteOn = Optional.of(purchaseDate.plusDays(1 + draw(Math.max(1,
                            ChronoUnit.DAYS.between(purchaseDate, lastDue) - 1))));
                    goodThroughDays = between(0, payInN ? 10 : 3);
                }
                default -> {
                    // Repayments only.
                }
            }
        }

        // -------------------------------------------------------------- T-acc (pay-in-N, ADR-0094)

        /** Every elapsed date of the accrual span with principal outstanding, accrued once, at 08:00. */
        void accrue(LocalDate today) {
            if (latest.instalments().isEmpty()) {
                return;
            }
            List<AccrualPeriod> periods = BnplAccrualsView.periodsOf(latest);
            LocalDate spanEnd = periods.get(periods.size() - 1).endExclusive();
            LocalDate last = today.minusDays(1).isBefore(spanEnd) ? today.minusDays(1) : spanEnd.minusDays(1);
            LocalDate from = lastAccrual == null ? purchaseDate : lastAccrual.accrual().date().plusDays(1);
            if (from.isBefore(purchaseDate)) {
                from = purchaseDate;
            }
            for (LocalDate date = from; !date.isAfter(last); date = date.plusDays(1)) {
                long base = base(date);
                if (base <= 0) {
                    continue;
                }
                AccrualPeriod period = null;
                for (AccrualPeriod candidate : periods) {
                    if (candidate.contains(date)) {
                        period = candidate;
                    }
                }
                BigDecimal before = lastAccrual != null && lastAccrual.accrual().period().equals(period)
                        ? lastAccrual.accrual().cumulativeExact() : NOTHING;
                BigDecimal cumulative = before.add(BnplBatteryOracles.daily(base, rate));
                long once = BnplBatteryOracles.roundedOnce(cumulative) - BnplBatteryOracles.roundedOnce(before);
                long twice = BnplBatteryOracles.roundedTwice(cumulative) - BnplBatteryOracles.roundedTwice(before);
                long posted = arithmetic == Arithmetic.ROUNDED_TWICE ? twice : once;
                if (arithmetic == Arithmetic.ROUNDED_TWICE && once != twice) {
                    predicted = true;
                }
                Optional<JournalEntryId> entry = Optional.empty();
                if (posted > 0) {
                    entry = Optional.of(entry());
                    leg(entry.get(), AccountPurpose.BNPL_INTEREST_ACCRUED, Direction.DEBIT, posted, date);
                } else {
                    tag(Tag.ZERO_POSTINGS);
                }
                InterestAccrual accrual = new InterestAccrual(date, period, money(base), cumulative, money(posted),
                        EngineVersion.ACCRUAL_ENGINE_V1);
                lastAccrual = new BnplInterestAccrual(id(), obligationId, accrual, agreementId, governing.version(), entry,
                        correlation);
                accruals.add(new BnplRecord.Stamped<>(lastAccrual, at(today, 8)));
                tag(Tag.ACCRUALS);
                if (date.getMonth() == Month.FEBRUARY && date.getDayOfMonth() == 29) {
                    tag(Tag.LEAP_DAY_ACCRUALS);
                }
            }
        }

        /** Principal outstanding at the end of {@code date}: BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE over the lines valued by it. */
        long base(LocalDate date) {
            long base = 0;
            LedgerAccountId p = accounts.get(AccountPurpose.BNPL_PRINCIPAL);
            LedgerAccountId pd = accounts.get(AccountPurpose.BNPL_PRINCIPAL_DUE);
            for (PurposeBalances.DatedLine line : journal) {
                if ((line.account().equals(p) || line.account().equals(pd)) && !line.valueDate().isAfter(date)) {
                    base += line.direction() == Direction.DEBIT ? line.amount().minorUnits() : -line.amount().minorUnits();
                }
            }
            return base;
        }

        // -------------------------------------------------------------- T-svc (P12-TSK-015, -018, -019, -020)

        /** The servicing step at 09:00: bill, apply at each billing, catch up, collect, close, the late fee. */
        void service(LocalDate today) {
            for (BnplPurchaseStore.StoredInstalment row : unbilled()) {
                if (row.dueDate().isAfter(today)) {
                    break;
                }
                BnplBilling billing = bill(today, row, row.dueDate(), Optional.empty(), 9);
                apply(today, Optional.of(billing.id()), 9);
            }
            apply(today, Optional.empty(), 9);
            Optional<UUID> collected = terms.autoCollection() ? collect(today) : Optional.empty();
            if (closeIfSettled(today, collected, Optional.empty(), false, 9)) {
                return;
            }
            lateFees(today);
        }

        /**
         * F2: the instalment's interest the period's posted accruals, its principal {@code least(not yet due, amount -
         * interest)} and the last all of it - entry valued {@code entryValue}.
         */
        BnplBilling bill(LocalDate today, BnplPurchaseStore.StoredInstalment row, LocalDate entryValue,
                Optional<UUID> payoff, int hour) {
            LocalDate periodStart = latest.start();
            for (BnplPurchaseStore.StoredInstalment earlier : latest.instalments()) {
                if (earlier.sequence() == row.sequence() - 1) {
                    periodStart = earlier.dueDate();
                }
            }
            long interest = 0;
            if (payInN) {
                for (BnplRecord.Stamped<BnplInterestAccrual> accrual : accruals) {
                    LocalDate date = accrual.row().accrual().date();
                    if (!date.isBefore(periodStart) && date.isBefore(row.dueDate())) {
                        interest += accrual.row().accrual().posted().minorUnits();
                    }
                }
            }
            long remaining = Math.max(principal, 0);
            long billedPrincipal;
            if (row.sequence() == latest.instalments().size()) {
                billedPrincipal = remaining;
            } else {
                long portion = row.amount().minorUnits() - interest;
                billedPrincipal = portion <= 0 ? 0 : Math.min(portion, remaining);
            }
            Optional<JournalEntryId> entry = Optional.empty();
            if (billedPrincipal + interest > 0) {
                entry = Optional.of(entry());
                if (billedPrincipal > 0) {
                    leg(entry.get(), AccountPurpose.BNPL_PRINCIPAL_DUE, Direction.DEBIT, billedPrincipal, entryValue);
                    leg(entry.get(), AccountPurpose.BNPL_PRINCIPAL, Direction.CREDIT, billedPrincipal, entryValue);
                }
                if (interest > 0) {
                    leg(entry.get(), AccountPurpose.BNPL_INTEREST_DUE, Direction.DEBIT, interest, entryValue);
                    leg(entry.get(), AccountPurpose.BNPL_INTEREST_ACCRUED, Direction.CREDIT, interest, entryValue);
                }
            }
            BnplBilling billing = new BnplBilling(id(), row.id(), row.sequence(), obligationId, latest.id(),
                    latest.version(), latest.engine(), agreementId, governing.version(), governing.accrualEngine(),
                    periodStart, row.dueDate(), money(billedPrincipal), money(interest), entry, correlation);
            billings.add(new BnplRecord.Billing(billing, today, payoff, at(today, hour)));
            items.put(row.sequence(), new Item(row.sequence(), row.id(), billing.id(), row.dueDate(), billedPrincipal,
                    interest));
            tag(Tag.BILLINGS);
            if (payoff.isPresent()) {
                tag(Tag.EARLY_BILLINGS);
            }
            return billing;
        }

        List<BnplBatteryOracles.Due> due(boolean unheldOnly, LocalDate today) {
            List<BnplBatteryOracles.Due> due = new ArrayList<>();
            for (Item item : items.values()) {
                if (item.total() != 0 && (!unheldOnly || !held(item.dueDate, today))) {
                    due.add(new BnplBatteryOracles.Due(item.instalment, item.dueDate, item.due(AllocationComponent.FEES),
                            item.due(AllocationComponent.INTEREST), item.due(AllocationComponent.PRINCIPAL)));
                }
            }
            return due;
        }

        Item itemOf(UUID instalment) {
            for (Item item : items.values()) {
                if (item.instalment.equals(instalment)) {
                    return item;
                }
            }
            throw new IllegalStateException("a due item is a billed instalment");
        }

        /** F3a: the credit held over what is due - at a billing, or the step's catch-up (the Phase 11 gate's R2). */
        void apply(LocalDate today, Optional<UUID> billing, int hour) {
            if (credit <= 0) {
                return;
            }
            List<BnplBatteryOracles.Due> due = due(false, today);
            if (due.isEmpty()) {
                return;
            }
            List<BnplBatteryOracles.Line> lines = BnplBatteryOracles.allocate(credit, due);
            List<BnplCreditBalanceApplication.Line> rows = new ArrayList<>();
            Map<AllocationComponent, Long> perComponent = new EnumMap<>(AllocationComponent.class);
            long amount = 0;
            for (BnplBatteryOracles.Line line : lines) {
                if (line.credit()) {
                    continue;
                }
                rows.add(new BnplCreditBalanceApplication.Line(id(), itemOf(line.item()).billing, line.component(),
                        money(line.amount())));
                perComponent.merge(line.component(), line.amount(), Long::sum);
                amount += line.amount();
            }
            if (rows.isEmpty()) {
                return;
            }
            LocalDate valueDate = valueDate(today);
            JournalEntryId entry = entry();
            leg(entry, AccountPurpose.BNPL_CREDIT_BALANCE, Direction.DEBIT, amount, valueDate);
            perComponent.forEach((component, sum) -> leg(entry, dueAccount(component), Direction.CREDIT, sum, valueDate));
            for (BnplBatteryOracles.Line line : lines) {
                if (!line.credit()) {
                    itemOf(line.item()).pay(line.component(), line.amount());
                }
            }
            Optional<Integer> catchUp = billing.isPresent() ? Optional.empty() : Optional.of(++catchUps);
            BnplCreditBalanceApplication application = new BnplCreditBalanceApplication(id(), obligationId, billing,
                    catchUp, money(amount), agreementId, governing.version(), governing.allocationEngine(), valueDate, rows,
                    entry, correlation);
            applications.add(new BnplRecord.Stamped<>(application, at(today, hour)));
            if (billing.isPresent()) {
                tag(Tag.APPLICATIONS_AT_BILLING);
            }
        }

        /** P12-TSK-019: each billed instalment due, oldest first, collected from the wallet alone - retried daily. */
        Optional<UUID> collect(LocalDate today) {
            Map<UUID, LocalDate> lastAttempt = new HashMap<>();
            attempts.forEach(attempt -> lastAttempt.merge(attempt.row().instalment(), attempt.row().attemptDate(),
                    (a, b) -> a.isAfter(b) ? a : b));
            List<Item> billed = items.values().stream().filter(item -> !item.dueDate.isAfter(today))
                    .filter(item -> !held(item.dueDate, today))
                    .sorted(java.util.Comparator.comparing(item -> item.dueDate)).toList();
            Optional<UUID> lastRepayment = Optional.empty();
            boolean blocked = false;
            for (Item item : billed) {
                LocalDate last = lastAttempt.get(item.instalment);
                boolean candidate = (last == null || last.isBefore(today)) && (item.dueDate.equals(today)
                        || (item.total() > 0 && (last == null || !last.plusDays(terms.collectionRetryDays()).isAfter(today))));
                if (!candidate) {
                    blocked |= item.total() > 0;
                    continue;
                }
                long owed = item.total();
                if (owed == 0) {
                    attempt(today, item, CollectionOutcome.NOTHING_DUE, 0, 0, Optional.empty());
                    continue;
                }
                long spendable = blocked ? 0 : walletBalance;
                long amount = owed <= spendable ? owed : terms.partialCollection() && spendable > 0 ? spendable : 0;
                if (amount <= 0) {
                    blocked = true;
                    attempt(today, item, CollectionOutcome.INSUFFICIENT_FUNDS, owed, 0, Optional.empty());
                    continue;
                }
                List<BnplBatteryOracles.Line> lines = BnplBatteryOracles.allocate(amount, due(true, today));
                for (BnplBatteryOracles.Line line : lines) {
                    if (line.credit() || !line.item().equals(item.instalment)) {
                        throw new IllegalStateException("a collection pays only the instalment it was attempted on");
                    }
                }
                UUID repayment = receive(today, BnplPurchaseStore.Repayment.AUTO_COLLECTION,
                        BnplCollectionAttempt.requestKey(item.instalment, today), amount, lines, 9);
                walletBalance -= amount;
                CollectionOutcome outcome = amount == owed ? CollectionOutcome.COLLECTED : CollectionOutcome.PARTIAL;
                blocked |= outcome == CollectionOutcome.PARTIAL;
                attempt(today, item, outcome, owed, amount, Optional.of(repayment));
                lastRepayment = Optional.of(repayment);
            }
            return lastRepayment;
        }

        void attempt(LocalDate today, Item item, CollectionOutcome outcome, long due, long collected,
                Optional<UUID> repayment) {
            attempts.add(new BnplRecord.Stamped<>(new BnplCollectionAttempt(id(), obligationId, item.instalment, today,
                    outcome, money(due), money(collected), governing.version(), repayment, correlation), at(today, 9)));
            tag(switch (outcome) {
                case COLLECTED -> Tag.COLLECTED;
                case PARTIAL -> Tag.PARTIALLY_COLLECTED;
                case NOTHING_DUE -> Tag.NOTHING_DUE;
                default -> Tag.INSUFFICIENT_FUNDS;
            });
        }

        /** The collection cohort's wallet: topped up before the step - fully, partly, late - on each instalment's plan. */
        void topUp(LocalDate today) {
            if (!terms.autoCollection()) {
                return;
            }
            for (BnplPurchaseStore.StoredInstalment row : latest.instalments()) {
                int mode = funding[row.sequence()];
                long amount = row.amount().minorUnits() + 500;
                if (row.dueDate().equals(today)) {
                    walletBalance += switch (mode) {
                        case 0 -> amount;
                        case 1 -> amount / 2;
                        default -> 0;
                    };
                }
                if (row.dueDate().plusDays(lag[row.sequence()]).equals(today) && mode <= 2) {
                    walletBalance += dueTotal() + 1_500;
                }
            }
        }

        /** F3 for a repayment of {@code amount}: the customer's own, a collection's, a payoff's - allocated, valued, held. */
        UUID receive(LocalDate today, String kind, String key, long amount, int hour) {
            return receive(today, kind, key, amount, BnplBatteryOracles.allocate(amount, due(false, today)), hour);
        }

        UUID receive(LocalDate today, String kind, String key, long amount, List<BnplBatteryOracles.Line> lines,
                int hour) {
            LocalDate valueDate = BnplPurchaseStore.Repayment.AT_PURCHASE.equals(kind) ? purchaseDate : valueDate(today);
            JournalEntryId entry = entry();
            Map<AllocationComponent, Long> perComponent = new EnumMap<>(AllocationComponent.class);
            long held = 0;
            List<BnplPurchaseStore.Allocation> allocations = new ArrayList<>();
            for (BnplBatteryOracles.Line line : lines) {
                if (line.credit()) {
                    held += line.amount();
                    allocations.add(new BnplPurchaseStore.Allocation(id(), allocations.size() + 1, "CREDIT_BALANCE",
                            Optional.empty(), Optional.empty(), money(line.amount())));
                } else {
                    perComponent.merge(line.component(), line.amount(), Long::sum);
                    allocations.add(new BnplPurchaseStore.Allocation(id(), allocations.size() + 1, "DUE_ITEM",
                            Optional.of(line.item()), Optional.of(line.component().name()), money(line.amount())));
                    itemOf(line.item()).pay(line.component(), line.amount());
                }
            }
            perComponent.forEach((component, sum) -> leg(entry, dueAccount(component), Direction.CREDIT, sum, valueDate));
            if (held > 0) {
                leg(entry, AccountPurpose.BNPL_CREDIT_BALANCE, Direction.CREDIT, held, valueDate);
                tag(Tag.CREDIT_HELD);
            }
            UUID id = id();
            BnplPurchaseStore.Repayment repayment = new BnplPurchaseStore.Repayment(id, obligationId, kind, key,
                    money(amount), agreementId, governing.version(), governing.allocationEngine(), entry, valueDate,
                    allocations);
            repayments.add(new BnplRecord.Stamped<>(repayment, at(today, hour)));
            if (BnplPurchaseStore.Repayment.CUSTOMER.equals(kind)) {
                tag(Tag.CUSTOMER_REPAYMENTS);
            } else if (BnplPurchaseStore.Repayment.AUTO_COLLECTION.equals(kind)) {
                tag(Tag.AUTO_COLLECTIONS);
            }
            if (lines.stream().filter(line -> !line.credit()).map(BnplBatteryOracles.Line::item).distinct().count() > 1) {
                tag(Tag.MULTI_ITEM_REPAYMENTS);
            }
            return id;
        }

        /** The closure (P12-TSK-018): every receivable zero, every instalment billed - the credit refunded, CLOSED. */
        boolean closeIfSettled(LocalDate today, Optional<UUID> repayment, Optional<UUID> refund, boolean payoff,
                int hour) {
            if (!active || principal != 0 || principalDue != 0 || accrued != 0 || interestDue != 0 || feesDue != 0
                    || !unbilled().isEmpty()) {
                return false;
            }
            Optional<JournalEntryId> refundEntry = Optional.empty();
            long refunded = credit;
            if (refunded > 0) {
                refundEntry = Optional.of(entry());
                leg(refundEntry.get(), AccountPurpose.BNPL_CREDIT_BALANCE, Direction.DEBIT, refunded, today);
                tag(Tag.CLOSURE_REFUNDS);
            }
            BnplClosureReason reason = payoff ? BnplClosureReason.PAID_OFF_EARLY
                    : refundedBefore == gross ? BnplClosureReason.REFUNDED
                    : refund.isPresent() ? BnplClosureReason.REFUNDED_PARTIALLY_REPAID : BnplClosureReason.REPAID;
            tag(switch (reason) {
                case REPAID -> Tag.CLOSED_REPAID;
                case PAID_OFF_EARLY -> Tag.CLOSED_PAID_OFF_EARLY;
                case REFUNDED -> Tag.CLOSED_REFUNDED;
                case REFUNDED_PARTIALLY_REPAID -> Tag.CLOSED_REFUNDED_PARTIALLY_REPAID;
            });
            closure = Optional.of(new BnplRecord.Stamped<>(new BnplClosure(id(), obligationId, reason, money(refunded),
                    refundEntry, repayment, refund, correlation), at(today, hour)));
            active = false;
            closedOn = today;
            return true;
        }

        /** P12-TSK-020: the pinned late fee once per instalment whose own age reached the day, capped per agreement. */
        void lateFees(LocalDate today) {
            long fee = terms.lateFee().minorUnits();
            if (fee <= 0) {
                return;
            }
            long cap = terms.lateFeeCap().minorUnits();
            for (Item item : items.values().stream().sorted(java.util.Comparator.comparing(each -> each.dueDate))
                    .toList()) {
                if (held(item.dueDate, today)) {
                    continue;
                }
                LocalDate from = clockFrom(item.dueDate, today);
                if (ChronoUnit.DAYS.between(from, today) < terms.lateFeeDay()) {
                    continue;
                }
                if (item.charged || item.due(AllocationComponent.INTEREST) + item.due(AllocationComponent.PRINCIPAL) <= 0) {
                    continue;
                }
                long room = cap - feesAssessed;
                if (room <= 0) {
                    if (!capReached) {
                        capReached = true;
                        tag(Tag.LATE_FEE_CAP_REACHED);
                    }
                    break;
                }
                long amount = Math.min(fee, room);
                JournalEntryId entry = entry();
                leg(entry, AccountPurpose.BNPL_FEES_DUE, Direction.DEBIT, amount, today);
                fees.add(new BnplRecord.Stamped<>(new BnplFeeAssessment(id(), obligationId, item.instalment,
                        BnplFeeKind.LATE, today, money(amount), agreementId, governing.version(), entry, correlation),
                        at(today, 9)));
                feesAssessed += amount;
                item.fees += amount;
                item.charged = true;
                tag(Tag.LATE_FEES);
                if (amount < fee) {
                    tag(Tag.LATE_FEES_CAPPED);
                }
                if (!from.equals(item.dueDate)) {
                    tag(Tag.FEES_AFTER_LIFT);
                }
            }
        }

        // -------------------------------------------------------------- the day's acts

        void act(LocalDate today) {
            disputeActs(today);
            merchantActs(today);
            customer(today);
            payoffActs(today);
        }

        // -------------------------------------------------------------- the customer (P12-TSK-018)

        boolean paused(LocalDate today) {
            return disputePause && disputes.stream().anyMatch(dispute -> dispute.open() && !dispute.openedOn.isAfter(today));
        }

        void customer(LocalDate today) {
            if (!active || paused(today) || downOn.filter(today::equals).isPresent()) {
                return; // A paused customer waits for the dispute; a wallet down takes no repayment.
            }
            if (terms.autoCollection()) {
                // The step collects; a collecting customer on plan 3 pays the instalment by hand ahead of its date - held
                // as credit, applied at the billing, so the due date's attempt finds nothing due.
                List<BnplPurchaseStore.StoredInstalment> next = unbilled();
                if (!next.isEmpty() && funding[next.get(0).sequence()] == 3
                        && next.get(0).dueDate().minusDays(early[next.get(0).sequence()]).equals(today)) {
                    pay(today, next.get(0).amount().minorUnits());
                }
                return;
            }
            if (quoteOn.isPresent() && !payoffStale && (openQuote != null || today.isAfter(quoteOn.get()))) {
                return; // A payoff in hand: the customer waits for its date.
            }
            boolean resumed = disputePause && disputes.stream().anyMatch(dispute -> dispute.resolvedOn
                    .filter(lifted -> lifted.plusDays(resumeLag).equals(today)).isPresent());
            if (resumed && dueTotal() > 0) {
                pay(today, dueTotal());
                return;
            }
            if (payoffStale && quoteOn.isPresent() && dueTotal() > 0 && today.isAfter(quoteOn.get())
                    && items.values().stream().anyMatch(item -> item.total() > 0 && item.dueDate.isBefore(today))) {
                pay(today, dueTotal());
                return;
            }
            switch (style) {
                case IN_WINDOW, LATE -> {
                    for (Item item : items.values()) {
                        if (item.total() > 0 && item.dueDate.plusDays(lag[item.sequence]).equals(today)) {
                            pay(today, dueTotal());
                            return;
                        }
                    }
                }
                case PARTIAL -> {
                    for (Item item : items.values()) {
                        if (item.total() > 0 && item.dueDate.plusDays(lag[item.sequence] + 1L).equals(today)) {
                            pay(today, dueTotal());
                            return;
                        }
                    }
                    for (Item item : items.values()) {
                        if (item.total() > 0 && item.dueDate.equals(today)) {
                            pay(today, Math.max(1, dueTotal() * fraction[item.sequence] / 100));
                            return;
                        }
                    }
                }
                case OVER -> {
                    for (Item item : items.values()) {
                        if (item.total() > 0 && item.dueDate.equals(today)) {
                            pay(today, dueTotal() + extra[item.sequence]);
                            return;
                        }
                    }
                }
                case EARLY -> {
                    List<BnplPurchaseStore.StoredInstalment> next = unbilled();
                    if (!next.isEmpty() && next.get(0).dueDate().minusDays(early[next.get(0).sequence()]).equals(today)) {
                        pay(today, next.get(0).amount().minorUnits());
                        return;
                    }
                    onTime(today);
                }
                case DEFAULTER -> {
                    for (Item item : items.values()) {
                        if (item.total() > 0 && item.dueDate.equals(today) && item.sequence <= stopAfter) {
                            pay(today, dueTotal());
                            return;
                        }
                    }
                }
                default -> onTime(today);
            }
        }

        void onTime(LocalDate today) {
            for (Item item : items.values()) {
                if (item.total() > 0 && item.dueDate.equals(today)) {
                    pay(today, dueTotal());
                    return;
                }
            }
        }

        void pay(LocalDate today, long amount) {
            UUID repayment = receive(today, BnplPurchaseStore.Repayment.CUSTOMER, "battery-" + index + "-" + ids, amount,
                    14);
            closeIfSettled(today, Optional.of(repayment), Optional.empty(), false, 14);
        }

        // -------------------------------------------------------------- the merchant's refunds (P12-TSK-021)

        void merchantActs(LocalDate today) {
            for (int k = 0; k < refundPlans.size(); k++) {
                RefundPlan plan = refundPlans.get(k);
                LocalDate on = plan.on().orElse(closedOn == null ? null : closedOn.plusDays(plan.afterClosure()));
                if (on == null || !on.equals(today)) {
                    continue;
                }
                refundPlans.remove(k--);
                if (disputes.stream().anyMatch(Dispute::open)) {
                    continue; // 409 bnpl.DisputeOpen - the merchant settles a disputed purchase by accepting it.
                }
                long remaining = gross - refundedBefore;
                long amount = switch (plan.amount()) {
                    case FULL_REMAINING -> remaining;
                    case UNPAID_REMAINDER -> active ? Math.min(remaining, unpaidPrincipal()) : 0;
                    case PARTIAL -> remaining > 1 ? 1 + draw(remaining - 1) : remaining;
                    case FIXED -> Math.min(plan.value(), remaining);
                };
                if (amount > 0) {
                    refund(today, amount, BnplRefund.Door.MERCHANT, Optional.empty(), 12);
                }
            }
        }

        long unpaidPrincipal() {
            long unpaid = 0;
            for (long[] slot : position()) {
                unpaid += slot[1] - slot[3] - slot[4];
            }
            return unpaid;
        }

        /**
         * The position under B4 (ADR-0105 decision 2): version 1's instalments by sequence - a billed one its billed
         * principal (with what refunds cancelled of it unbilled), what payers settled and refunds cancelled; an unbilled one
         * still scheduled the principal the billing rule will bill, the last the remainder of the principal not yet due; one
         * no longer scheduled what refunds cancelled of it. Each {@code {sequence, principal, billed, allocated, cancelled}};
         * slots of no principal left out.
         */
        List<long[]> position() {
            Map<Integer, Long> toBill = new HashMap<>();
            List<BnplPurchaseStore.StoredInstalment> rows = unbilled();
            long remaining = principal;
            for (int k = 0; k < rows.size(); k++) {
                long portion = k == rows.size() - 1 ? remaining : Math.min(rows.get(k).principal().minorUnits(), remaining);
                toBill.put(rows.get(k).sequence(), portion);
                remaining -= portion;
            }
            if (remaining != 0 || (rows.isEmpty() && principal != 0)) {
                throw new IllegalStateException("the latest schedule explains the principal not yet due");
            }
            List<long[]> slots = new ArrayList<>();
            for (BnplPurchaseStore.StoredInstalment origin : first.instalments()) {
                int sequence = origin.sequence();
                long uc = unbilledCancelled.getOrDefault(sequence, 0L);
                long bc = billedCancelled.getOrDefault(sequence, 0L);
                Item item = items.get(sequence);
                long[] slot;
                if (item != null) {
                    slot = new long[] {sequence, item.principal + uc, 1, item.paidPrincipal - bc, uc + bc};
                } else if (toBill.containsKey(sequence)) {
                    slot = new long[] {sequence, toBill.get(sequence) + uc, 0, 0, uc};
                } else {
                    slot = new long[] {sequence, uc, 0, 0, uc};
                }
                if (slot[1] > 0) {
                    slots.add(slot);
                }
            }
            return slots;
        }

        /** T-ref: the refund split latest first, the releases, the versions, F5 + F5a, the closure. */
        UUID refund(LocalDate today, long amount, BnplRefund.Door door, Optional<BnplDisputeId> dispute, int hour) {
            List<long[]> slots = position();
            if (firstPosition.isEmpty()) {
                List<long[]> captured = new ArrayList<>();
                slots.forEach(slot -> captured.add(new long[] {slot[1], slot[2], slot[3], slot[4]}));
                firstPosition = Optional.of(captured);
            }
            refundAmounts.add(amount);
            String phase;
            if (!active) {
                phase = "AFTER_CLOSURE";
                tag(Tag.REFUNDS_AFTER_CLOSURE);
            } else if (items.size() <= (payInN ? 0 : 1)) {
                phase = "BEFORE";
                tag(Tag.REFUNDS_BEFORE);
            } else if (!unbilled().isEmpty()) {
                phase = "DURING";
                tag(Tag.REFUNDS_DURING);
            } else {
                phase = "AFTER_BILLED";
                tag(Tag.REFUNDS_AFTER_BILLED);
            }
            List<BnplBatteryOracles.Slot> unpaid = new ArrayList<>();
            for (long[] slot : slots) {
                unpaid.add(new BnplBatteryOracles.Slot((int) slot[0], slot[2] == 1, slot[1] - slot[3] - slot[4]));
            }
            BnplBatteryOracles.Split split = BnplBatteryOracles.split(unpaid, amount);
            if (!active && !split.cancellations().isEmpty()) {
                throw new IllegalStateException("a CLOSED obligation owes no principal a refund could cancel");
            }
            long cancelledUnbilled = split.cancelled(false);
            long cancelledBilled = split.cancelled(true);
            long feeReturned = BnplBatteryOracles.feeReturned(fee, refundedBefore, amount, gross);

            // Pay-in-N's releases (A11) and the late fees on cancelled instalments (OQ-14).
            long accruedReleased = 0;
            Map<Integer, Long> dueInterestReleased = new TreeMap<>();
            if (active && payInN) {
                if (split.leavesNothingUnpaid() || principal - cancelledUnbilled == 0) {
                    accruedReleased = accrued;
                }
                if (split.leavesNothingUnpaid()) {
                    for (long[] slot : slots) {
                        Item item = slot[2] == 1 ? items.get((int) slot[0]) : null;
                        if (item != null && item.due(AllocationComponent.INTEREST) > 0) {
                            dueInterestReleased.put(item.sequence, item.due(AllocationComponent.INTEREST));
                        }
                    }
                }
            }
            Map<Integer, BnplBatteryOracles.Cancelled> bySequence = new TreeMap<>();
            for (BnplBatteryOracles.Cancelled cancelled : split.cancellations()) {
                bySequence.put((int) slots.get(cancelled.index())[0], cancelled);
            }
            Map<Integer, Long> lateFeeReleased = new TreeMap<>();
            if (active) {
                for (long[] slot : slots) {
                    Item item = slot[2] == 1 ? items.get((int) slot[0]) : null;
                    BnplBatteryOracles.Cancelled cancelled = bySequence.get((int) slot[0]);
                    if (item != null && item.due(AllocationComponent.FEES) > 0 && ((cancelled != null && cancelled.billed()
                            && cancelled.whole()) || split.leavesNothingUnpaid())) {
                        lateFeeReleased.put(item.sequence, item.due(AllocationComponent.FEES));
                    }
                }
            }
            TreeSet<Integer> touched = new TreeSet<>(java.util.Comparator.reverseOrder());
            touched.addAll(bySequence.keySet());
            touched.addAll(dueInterestReleased.keySet());
            touched.addAll(lateFeeReleased.keySet());
            List<BnplRefund.Adjustment> adjustments = new ArrayList<>();
            for (int sequence : touched) {
                BnplBatteryOracles.Cancelled cancelled = bySequence.get(sequence);
                boolean billedKind = cancelled == null || cancelled.billed();
                UUID instalment = billedKind ? items.get(sequence).instalment
                        : latest.instalments().stream().filter(row -> row.sequence() == sequence).findFirst()
                                .orElseThrow().id();
                adjustments.add(new BnplRefund.Adjustment(id(), sequence, instalment,
                        billedKind ? RefundAllocation.Cancellation.Kind.BILLED_UNPAID
                                : RefundAllocation.Cancellation.Kind.UNBILLED,
                        money(cancelled == null ? 0 : cancelled.amount()), money(dueInterestReleased.getOrDefault(sequence,
                                0L)), cancelled == null || cancelled.whole(), money(lateFeeReleased.getOrDefault(sequence,
                                0L))));
            }
            long dueReleased = dueInterestReleased.values().stream().mapToLong(Long::longValue).sum();
            long feesReleased = lateFeeReleased.values().stream().mapToLong(Long::longValue).sum();

            // Schedule and agreement version n+1 when an unbilled instalment is cancelled or shortened (INV-HIST-04).
            UUID refundId = id();
            int agreementVersion = governing.version();
            int scheduleVersion = latest.version();
            BnplPurchaseStore.StoredSchedule next = null;
            if (cancelledUnbilled > 0) {
                next = nextSchedule(slots, bySequence, principal - cancelledUnbilled, cancelledUnbilled);
                schedules.add(new BnplRecord.Schedule(next, Optional.of(refundId), at(today, hour)));
                BnplAgreement copy = new BnplAgreement(governing.id(), governing.version() + 1, obligationId,
                        governing.offer(), governing.decision(), governing.termsVersionId(), product, plan,
                        governing.scheduleEngine(), governing.accrualEngine(), governing.allocationEngine(),
                        governing.refundAllocationEngine(), governing.feeScheduleVersion(), governing.template(),
                        governing.canonicalFormat(), governing.canonicalTerms(), governing.termsSha256(),
                        governing.documentSha256(), at(today, hour));
                agreements.add(new BnplRecord.Agreement(copy, Optional.of(refundId), next.version()));
                agreementVersion = copy.version();
                scheduleVersion = next.version();
                tag(payInN ? Tag.REFUND_REAMORTISED : Tag.REFUND_SCHEDULE_VERSIONS);
            }

            // F5 + F5a.
            LocalDate valueDate = active ? valueDate(today) : today;
            JournalEntryId entry = entry();
            List<JournalLine> lines = new ArrayList<>();
            lines.add(new JournalLine(payable(merchant), Direction.DEBIT, money(amount)));
            if (feeReturned > 0) {
                lines.add(new JournalLine(MERCHANT_FEE_INCOME, Direction.DEBIT, money(feeReturned)));
                tag(Tag.REFUND_FEE_RETURNED);
            }
            if (cancelledUnbilled > 0) {
                lines.add(new JournalLine(accounts.get(AccountPurpose.BNPL_PRINCIPAL), Direction.CREDIT,
                        money(cancelledUnbilled)));
                leg(entry, AccountPurpose.BNPL_PRINCIPAL, Direction.CREDIT, cancelledUnbilled, valueDate);
            }
            if (cancelledBilled > 0) {
                lines.add(new JournalLine(accounts.get(AccountPurpose.BNPL_PRINCIPAL_DUE), Direction.CREDIT,
                        money(cancelledBilled)));
                leg(entry, AccountPurpose.BNPL_PRINCIPAL_DUE, Direction.CREDIT, cancelledBilled, valueDate);
            }
            boolean toCredit = active && walletDown && split.excess() > 0;
            if (split.excess() > 0) {
                lines.add(new JournalLine(toCredit ? accounts.get(AccountPurpose.BNPL_CREDIT_BALANCE) : wallet,
                        Direction.CREDIT, money(split.excess())));
                if (toCredit) {
                    downOn = Optional.of(today);
                    leg(entry, AccountPurpose.BNPL_CREDIT_BALANCE, Direction.CREDIT, split.excess(), valueDate);
                    tag(Tag.REFUNDS_TO_CREDIT);
                }
                tag(Tag.REFUNDS_WITH_EXCESS);
            }
            if (feeReturned > 0) {
                lines.add(new JournalLine(payable(merchant), Direction.CREDIT, money(feeReturned)));
            }
            if (accruedReleased + dueReleased > 0) {
                lines.add(new JournalLine(INTEREST_INCOME, Direction.DEBIT, money(accruedReleased + dueReleased)));
                if (accruedReleased > 0) {
                    lines.add(new JournalLine(accounts.get(AccountPurpose.BNPL_INTEREST_ACCRUED), Direction.CREDIT,
                            money(accruedReleased)));
                    leg(entry, AccountPurpose.BNPL_INTEREST_ACCRUED, Direction.CREDIT, accruedReleased, valueDate);
                }
                if (dueReleased > 0) {
                    lines.add(new JournalLine(accounts.get(AccountPurpose.BNPL_INTEREST_DUE), Direction.CREDIT,
                            money(dueReleased)));
                    leg(entry, AccountPurpose.BNPL_INTEREST_DUE, Direction.CREDIT, dueReleased, valueDate);
                }
                tag(Tag.REFUND_INTEREST_RELEASED);
            }
            if (feesReleased > 0) {
                lines.add(new JournalLine(LATE_FEE_INCOME, Direction.DEBIT, money(feesReleased)));
                lines.add(new JournalLine(accounts.get(AccountPurpose.BNPL_FEES_DUE), Direction.CREDIT,
                        money(feesReleased)));
                leg(entry, AccountPurpose.BNPL_FEES_DUE, Direction.CREDIT, feesReleased, valueDate);
                tag(Tag.REFUND_LATE_FEE_RELEASED);
            }
            shared.add(new BnplRecord.EntryLines(entry, lines));

            // The state after it: the cancellations and releases settled on their instalments, the versions in force.
            for (BnplRefund.Adjustment adjustment : adjustments) {
                if (adjustment.kind() == RefundAllocation.Cancellation.Kind.BILLED_UNPAID) {
                    Item item = items.get(adjustment.sequence());
                    item.pay(AllocationComponent.PRINCIPAL, adjustment.principalCancelled().minorUnits());
                    item.pay(AllocationComponent.INTEREST, adjustment.interestReleased().minorUnits());
                    item.pay(AllocationComponent.FEES, adjustment.lateFeeReleased().minorUnits());
                    billedCancelled.merge(adjustment.sequence(), adjustment.principalCancelled().minorUnits(), Long::sum);
                } else {
                    unbilledCancelled.merge(adjustment.sequence(), adjustment.principalCancelled().minorUnits(),
                            Long::sum);
                }
            }
            if (next != null) {
                latest = next;
                governing = agreements.get(agreements.size() - 1).row();
            }
            String requestedBy = door == BnplRefund.Door.DISPUTE ? BnplRefund.DISPUTE_REQUESTER + dispute.orElseThrow()
                    .value() : "MERCHANT:" + merchant(merchant);
            String key = door == BnplRefund.Door.DISPUTE ? dispute.orElseThrow().value().toString()
                    : "battery-refund-" + index + "-" + refunds.size();
            Instant stamped = at(today, hour);
            BnplRefund row = new BnplRefund(refundId, purchaseId, obligationId, merchant(merchant), door, requestedBy, key,
                    money(amount), money(refundedBefore), money(feeReturned), money(amount - feeReturned),
                    money(cancelledUnbilled), money(cancelledBilled), money(split.excess()),
                    split.excess() == 0 ? BnplRefund.ExcessTo.NONE : toCredit ? BnplRefund.ExcessTo.CREDIT_BALANCE
                            : BnplRefund.ExcessTo.WALLET, money(accruedReleased),
                    money(dueReleased), BnplEngine.REFUND_ALLOCATION_ENGINE_V1.name(), agreementId, agreementVersion,
                    scheduleVersion, "battery refund " + phase, entry, valueDate, Optional.of(stamped), correlation,
                    adjustments);
            refunds.add(new BnplRecord.Stamped<>(row, stamped));
            refundedBefore += amount;
            feeReturnedBefore += feeReturned;
            tag(Tag.REFUNDS);
            tag(refundedBefore == gross ? Tag.REFUNDS_FULL : Tag.REFUNDS_PARTIAL);
            if (refundedBefore == gross) {
                tag(Tag.FULLY_REFUNDED);
            }
            if (active && !(walletDown && credit > 0)) {
                // A credit balance is refunded at the closure only to a wallet that can be credited: on its down day the
                // closure waits for the next servicing step.
                closeIfSettled(today, Optional.empty(), Optional.of(refundId), false, hour);
            }
            return refundId;
        }

        /**
         * Schedule version n+1 (ADR-0105 decision 2.4): every billed instalment kept as billed; pay-in-4's unbilled tail each
         * instalment's principal still to bill less what this refund cancelled of it, the wholly cancelled dropped;
         * pay-in-N's tail re-amortised at version 1's level instalment over what is left, the least term.
         */
        BnplPurchaseStore.StoredSchedule nextSchedule(List<long[]> slots,
                Map<Integer, BnplBatteryOracles.Cancelled> bySequence, long principalLeft, long cancelledUnbilled) {
            List<BnplPurchaseStore.StoredInstalment> rows = new ArrayList<>();
            List<BnplPurchaseStore.StoredInstalment> unbilledRows = new ArrayList<>();
            LocalDate lastBilled = purchaseDate;
            for (BnplPurchaseStore.StoredInstalment row : latest.instalments()) {
                Item item = items.get(row.sequence());
                if (item != null && item.dueDate.equals(row.dueDate())) {
                    rows.add(new BnplPurchaseStore.StoredInstalment(id(), row.sequence(), row.dueDate(),
                            money(item.principal), money(item.interest), money(item.principal + item.interest)));
                    lastBilled = row.dueDate();
                } else {
                    unbilledRows.add(row);
                }
            }
            if (!payInN) {
                for (BnplPurchaseStore.StoredInstalment row : unbilledRows) {
                    long toBill = 0;
                    for (long[] slot : slots) {
                        if (slot[0] == row.sequence()) {
                            toBill = slot[1] - slot[4];
                        }
                    }
                    BnplBatteryOracles.Cancelled cancelled = bySequence.get(row.sequence());
                    long left = toBill - (cancelled != null && !cancelled.billed() ? cancelled.amount() : 0);
                    if (left > 0) {
                        rows.add(new BnplPurchaseStore.StoredInstalment(id(), row.sequence(), row.dueDate(), money(left),
                                row.interest(), money(left + row.interest().minorUnits())));
                    }
                }
            } else if (principalLeft > 0) {
                long level = first.instalments().get(0).amount().minorUnits();
                BnplBatteryOracles.Plan plan = BnplBatteryOracles.atLevel(principalLeft, rate, level, unbilledRows.size(),
                        purchaseDate.getDayOfMonth(), lastBilled, unbilledRows.get(0).dueDate());
                for (BnplBatteryOracles.Period period : plan.periods()) {
                    BnplPurchaseStore.StoredInstalment kept = unbilledRows.get(period.sequence() - 1);
                    if (!kept.dueDate().equals(period.due())) {
                        throw new IllegalStateException("a recalculation keeps the agreement's due dates");
                    }
                    rows.add(new BnplPurchaseStore.StoredInstalment(id(), kept.sequence(), period.due(),
                            money(period.principal()), money(period.interest()), money(period.amount())));
                }
            }
            return new BnplPurchaseStore.StoredSchedule(id(), obligationId, latest.version() + 1, latest.engine(),
                    money(latest.principal().minorUnits() - cancelledUnbilled), latest.start(), rows);
        }

        // -------------------------------------------------------------- disputes (P12-TSK-023)

        void disputeActs(LocalDate today) {
            for (Dispute dispute : disputes) {
                if (!dispute.open()) {
                    continue;
                }
                if (!dispute.toRefund && dispute.status == BnplDisputeStatus.OPEN && dispute.respondOn.equals(today)) {
                    dispute.status = BnplDisputeStatus.MERCHANT_RESPONDED;
                    dispute.respondedAt = Optional.of(at(today, 13));
                }
                if (dispute.resolveOn.equals(today)) {
                    if (dispute.toRefund) {
                        dispute.status = BnplDisputeStatus.RESOLVED_REFUND;
                        dispute.resolvedOn = Optional.of(today);
                        long amount = Math.min(dispute.amount, gross - refundedBefore);
                        dispute.refund = Optional.of(refund(today, amount, BnplRefund.Door.DISPUTE,
                                Optional.of(dispute.id), 13));
                        dispute.resolvedAt = Optional.of(at(today, 13));
                        tag(Tag.DISPUTES_REFUNDED);
                    } else {
                        dispute.status = BnplDisputeStatus.RESOLVED_UPHELD;
                        dispute.resolvedOn = Optional.of(today);
                        dispute.resolvedAt = Optional.of(at(today, 13));
                        tag(Tag.DISPUTES_UPHELD);
                    }
                }
            }
            if (disputeOn.filter(today::equals).isPresent()) {
                disputeOn = Optional.empty();
                long remaining = gross - refundedBefore;
                if (remaining <= 0) {
                    return;
                }
                long amount = disputeAmountSpec == 0 ? remaining : Math.min(disputeAmountSpec, remaining);
                List<BnplDispute.Held> holds = new ArrayList<>();
                if (active) {
                    List<long[]> slots = position();
                    List<BnplBatteryOracles.Slot> unpaid = new ArrayList<>();
                    for (long[] slot : slots) {
                        unpaid.add(new BnplBatteryOracles.Slot((int) slot[0], slot[2] == 1, slot[1] - slot[3] - slot[4]));
                    }
                    for (BnplBatteryOracles.Cancelled cancelled : BnplBatteryOracles.split(unpaid, amount)
                            .cancellations()) {
                        int sequence = (int) slots.get(cancelled.index())[0];
                        UUID instalment = cancelled.billed() ? items.get(sequence).instalment
                                : latest.instalments().stream().filter(row -> row.sequence() == sequence).findFirst()
                                        .orElseThrow().id();
                        LocalDate due = first.instalments().get(sequence - 1).dueDate();
                        holds.add(new BnplDispute.Held(id(), sequence, instalment, due,
                                cancelled.billed() ? RefundAllocation.Cancellation.Kind.BILLED_UNPAID
                                        : RefundAllocation.Cancellation.Kind.UNBILLED, money(cancelled.amount())));
                        tag(Tag.HELD_INSTALMENTS);
                    }
                }
                boolean toRefund = cohort == Cohort.DISPUTE_REFUNDED;
                LocalDate respond = today.plusDays(between(1, 4));
                LocalDate resolve = toRefund ? today.plusDays(between(1, 10)) : respond.plusDays(between(1, 14));
                disputes.add(new Dispute(BnplDisputeId.of(id()), amount, refundedBefore, today, at(today, 16), holds,
                        toRefund, respond, resolve));
            }
        }

        BnplDispute disputeRow(Dispute dispute) {
            boolean terminal = !dispute.open();
            return new BnplDispute(dispute.id, purchaseId, obligationId, merchant(merchant), party, agreementId, versionId,
                    dispute.status, BnplDisputeReason.NOT_AS_DESCRIBED, "battery dispute", "battery-dispute-" + index,
                    money(dispute.amount), money(dispute.refundedBefore), Optional.of(dispute.openedAt),
                    Optional.of(dispute.openedOn), Optional.of(dispute.openedAt.plus(14, ChronoUnit.DAYS)),
                    dispute.respondedAt.map(at -> "the merchant's statement"), dispute.respondedAt,
                    terminal ? Optional.of(dispute.toRefund ? "MERCHANT:" + merchant(merchant) : "OPERATOR:battery")
                            : Optional.empty(), terminal ? Optional.of("battery resolution") : Optional.empty(),
                    dispute.resolvedAt, dispute.resolvedOn, dispute.refund, Optional.empty(), correlation,
                    dispute.holds);
        }

        // -------------------------------------------------------------- the payoff (P12-TSK-022)

        void payoffActs(LocalDate today) {
            if (!active || quoteOn.isEmpty()) {
                return;
            }
            if (openQuote == null && quoteOn.get().equals(today) && !payoffStale) {
                LocalDate goodThrough = today.plusDays(goodThroughDays);
                long base = principal + principalDue;
                long projected = 0;
                int days = 0;
                if (payInN && goodThrough.isAfter(today) && base > 0 && !latest.instalments().isEmpty()) {
                    List<AccrualPeriod> periods = BnplAccrualsView.periodsOf(latest);
                    LocalDate spanEnd = periods.get(periods.size() - 1).endExclusive();
                    LocalDate through = goodThrough.minusDays(1).isBefore(spanEnd) ? goodThrough.minusDays(1)
                            : spanEnd.minusDays(1);
                    if (!through.isBefore(today)) {
                        BnplInterestAccrual chained = lastAccrual != null
                                && periods.contains(lastAccrual.accrual().period()) ? lastAccrual : null;
                        AccrualPeriod previousPeriod = chained == null ? null : chained.accrual().period();
                        BigDecimal cumulative = chained == null ? NOTHING : chained.accrual().cumulativeExact();
                        for (LocalDate date = today; !date.isAfter(through); date = date.plusDays(1)) {
                            AccrualPeriod period = null;
                            for (AccrualPeriod candidate : periods) {
                                if (candidate.contains(date)) {
                                    period = candidate;
                                }
                            }
                            BigDecimal before = period.equals(previousPeriod) ? cumulative : NOTHING;
                            BigDecimal after = before.add(BnplBatteryOracles.daily(base, rate));
                            long once = BnplBatteryOracles.roundedOnce(after) - BnplBatteryOracles.roundedOnce(before);
                            long twice = BnplBatteryOracles.roundedTwice(after) - BnplBatteryOracles.roundedTwice(before);
                            if (arithmetic == Arithmetic.ROUNDED_TWICE && once != twice) {
                                predicted = true; // the quote's projection is the accrual engine's too
                            }
                            projected += arithmetic == Arithmetic.ROUNDED_TWICE ? twice : once;
                            cumulative = after;
                            previousPeriod = period;
                        }
                        days = (int) (ChronoUnit.DAYS.between(today, through) + 1);
                        tag(Tag.PAYOFF_PROJECTED);
                    }
                }
                long amount = principal + principalDue + accrued + interestDue + feesDue - credit + projected;
                Instant createdAt = at(today, 15);
                openQuote = new BnplPayoffQuote(id(), obligationId, agreementId, governing.version(),
                        "battery-quote-" + index, today, goodThrough, money(principal), money(principalDue),
                        money(accrued), money(interestDue), money(feesDue), money(credit), money(base), days,
                        money(projected), money(amount), governing.scheduleEngine(), governing.accrualEngine(),
                        governing.allocationEngine(), createdAt, correlation);
                quotes.add(new BnplRecord.Stamped<>(openQuote, createdAt));
                tag(Tag.PAYOFF_QUOTES);
            }
            if (openQuote != null && openQuote.goodThrough().equals(today)) {
                execute(today);
            }
        }

        /** T-pay on the quote's date: the execution, every unbilled instalment billed, applied, paid, closed. */
        void execute(LocalDate today) {
            BnplPayoffQuote quote = openQuote;
            openQuote = null;
            long now = principal + principalDue + accrued + interestDue + feesDue - credit;
            if (now != quote.amount().minorUnits() || governing.version() != quote.agreementVersion()) {
                payoffStale = true; // 409 bnpl.PayoffQuoteStale: nothing written; the customer pays on as before.
                tag(Tag.QUOTES_STALE);
                if (dueTotal() > 0) {
                    pay(today, dueTotal());
                }
                return;
            }
            LocalDate valueDate = valueDate(today);
            Instant executedAt = at(today, 15);
            BnplPayoffExecution row = new BnplPayoffExecution(id(), quote.id(), obligationId, "battery-payoff-" + index,
                    today, valueDate, executedAt, correlation);
            execution = Optional.of(new BnplRecord.Stamped<>(row, executedAt));
            payoffDate = Optional.of(valueDate);
            for (BnplPurchaseStore.StoredInstalment instalment : unbilled()) {
                boolean early = instalment.dueDate().isAfter(today);
                BnplBilling billing = bill(today, instalment, early ? valueDate : instalment.dueDate(),
                        early ? Optional.of(row.id()) : Optional.empty(), 15);
                apply(today, Optional.of(billing.id()), 15);
            }
            apply(today, Optional.empty(), 15);
            long receivable = principal + principalDue + accrued + interestDue + feesDue;
            if (principal != 0 || accrued != 0 || (receivable > 0 && credit > 0)) {
                throw new IllegalStateException("a payoff bills every instalment and applies held credit first");
            }
            Optional<UUID> repayment = Optional.empty();
            if (receivable > 0) {
                repayment = Optional.of(receive(today, BnplPurchaseStore.Repayment.PAYOFF, quote.id().toString(),
                        receivable, 15));
            }
            tag(Tag.PAYOFFS);
            if (!closeIfSettled(today, repayment, Optional.empty(), true, 15)) {
                throw new IllegalStateException("a payoff settles every receivable and closes");
            }
        }

        // -------------------------------------------------------------- the record

        Instant openedAt;

        BnplRecord record() {
            BnplObligation obligation = new BnplObligation(obligationId, purchaseId, agreementId, party, decision, product,
                    plan, EUR, active ? BnplObligationStatus.ACTIVE : BnplObligationStatus.CLOSED, purchaseDate,
                    openedAt,
                    closure.map(found -> found.row().reason()), correlation);
            BnplPurchase purchase = refundedBefore == gross ? new BnplPurchase(purchaseRow.id(), purchaseRow.offer(),
                    purchaseRow.checkoutSession(), purchaseRow.party(), purchaseRow.merchant(), product, plan, EUR,
                    BnplPurchaseStatus.FULLY_REFUNDED, purchaseDate, purchaseRow.authorisedAt(), purchaseRow.financedAt(),
                    purchaseRow.order(), correlation) : purchaseRow;
            BnplTermsStore.VersionRow version = new BnplTermsStore.VersionRow(versionId, product, 1,
                    BnplTermsVersionStatus.ACTIVE, "battery-proposer", purchaseDate.minusDays(60).atStartOfDay(ZoneOffset.UTC)
                    .toInstant(), Optional.of("battery-approver"), Optional.of(purchaseDate.minusDays(59)
                    .atStartOfDay(ZoneOffset.UTC).toInstant()), Optional.of(purchaseDate.minusDays(59)
                    .atStartOfDay(ZoneOffset.UTC).toInstant()), Optional.empty());
            List<BnplRecord.LedgerAccountRow> owned = new ArrayList<>();
            accounts.forEach((purpose, id) -> owned.add(new BnplRecord.LedgerAccountRow(id, purpose, EUR)));
            return new BnplRecord(obligation, closure.map(BnplRecord.Stamped::at), purchase, financing, financedAt,
                    Optional.of(offer), Optional.of(offerTerms), agreements,
                    Map.of(versionId, Optional.of(new BnplTermsVersion(version, terms))), schedules, billings, accruals,
                    applications, repayments, attempts, refunds, fees, List.of(), quotes, execution,
                    disputes.stream().map(this::disputeRow).toList(), List.of(), List.of(), closure, owned, journal, shared,
                    Optional.of(payable(merchant)), Optional.of(MERCHANT_FEE_INCOME));
        }
    }

    /** {@link BnplAccruals#periodsOf}'s rule restated - each instalment's period, from the schedule's start (A6). */
    static final class BnplAccrualsView {

        private BnplAccrualsView() {}

        static List<AccrualPeriod> periodsOf(BnplPurchaseStore.StoredSchedule schedule) {
            List<AccrualPeriod> periods = new ArrayList<>();
            LocalDate from = schedule.start();
            for (BnplPurchaseStore.StoredInstalment instalment : schedule.instalments()) {
                periods.add(new AccrualPeriod(from, instalment.dueDate()));
                from = instalment.dueDate();
            }
            return List.copyOf(periods);
        }
    }

    /** Every purchase index of a population of {@code size}. */
    static IntStream indices(int size) {
        return IntStream.range(0, size);
    }
}
