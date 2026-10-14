package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * The battery's population (`P11-TST-002`): a seeded portfolio of instalment loans and credit lines, each account's whole
 * history written as {@link JdbcLoanRecordStore} would return it - a {@link LoanRecord} - by a simulated servicing
 * calendar, one business date at a time, in the order production's servicing step writes ({@link LoanServicing}: accrue,
 * bill or state, the credit-balance application, the closure, the late fee) with the customer's acts after it.
 *
 * <p><strong>Every stored figure is the battery's own arithmetic</strong> ({@link AmortisationOracles}) - never a
 * production engine's - so a replay {@code IDENTICAL} is two independent derivations agreeing, and an engine edited under
 * its stored number disagrees with the rows on exactly the figures the edit changes. The canonical terms alone are
 * rendered by production's {@link CanonicalOfferTerms} (a code-owned format, not arithmetic; its proof is the forged
 * byte).
 *
 * <p><strong>Deterministic in any JVM.</strong> One {@link Random} per account, seeded from {@code (SEED, kind, index)} - so
 * an account regenerates alone and a parallel stream cannot reorder it; every id is derived ({@code new UUID}), never
 * random; every instant derived from a business date in the account's pinned zone; no default zone, locale or charset is
 * read; no hash collection is iterated into a figure; no floating point ({@code INV-MON-01}).
 */
final class AmortisationWorld {

    /** The population's seed - a failure named by it reproduces on the same population. */
    static final long SEED = 20_261_014L;

    static final CurrencyCode EUR = LoanTermsFixtures.EUR;

    /** The servicing zones generated - UTC, west of it, and east of it (format 2's {@code +}). */
    static final List<String> ZONES = List.of("UTC", "-12:00", "-03:00", "+05:30", "+14:00");

    /** Every start (and opening) date falls in 2027 (non-leap) or 2028 (leap). */
    static final LocalDate FIRST_DAY = LocalDate.of(2027, 1, 1);

    static final int START_DAYS = 731;

    /** The plan's shortest first period (`LoanTermsFixtures`). */
    static final int MIN_FIRST_PERIOD_DAYS = 15;

    /** Every repayment and statement day reachable. */
    static final List<Integer> EVERY_DAY = IntStream.rangeClosed(1, LoanTerms.LAST_ALLOWED_DAY).boxed().toList();

    /** A line's limit ladder, in minor units. */
    static final long[] LIMITS = {500_00, 1_000_00, 2_500_00, 5_000_00, 10_000_00, 25_000_00};

    /** A line's minimum-payment variants: floor (minor), ratio (bps), payment-due days. */
    static final long[] FLOORS = {25_00, 10_00, 100_00};

    static final int[] RATIOS = {300, 100, 1_000};

    static final int[] DUE_DAYS = {25, 10};

    /** The largest loan rate the amortisation door admits at the product's bounds, in units of {@code 10^-8}. */
    static final long MAXIMUM_LOAN_RATE_UNITS = maximumAdmittedRateUnits();

    /** A line's rates: up to 30 %, eight places. */
    static final long MAXIMUM_LINE_RATE_UNITS = 30_000_000L;

    /**
     * The late fee caps: the plan's 60.00 (four whole fees) and 50.00 (the fourth fee capped at 5.00), alternating by
     * zone.
     */
    static final long[] LATE_FEE_CAPS = {60_00, 50_00};

    /** The personal loan's base term set per zone - the rate is each account's own. */
    private static final List<LoanTerms> LOAN_TERMS = IntStream.range(0, ZONES.size())
            .mapToObj(zone -> LoanTermsFixtures.personalLoan("0.05").servicingZone(ZONES.get(zone))
                    .allowedDays(EVERY_DAY).lateFeeCap(Money.ofMinorUnits(LATE_FEE_CAPS[zone % 2], EUR)).build())
            .toList();

    /** The credit line's base term sets, per zone and minimum-payment variant. */
    private static final List<LoanTerms> LINE_TERMS = lineTerms();

    /** How each stored figure was computed. */
    enum Arithmetic {
        /** The rules as the plan and the ADRs state them - what the pinned engines compute. */
        STANDARD,
        /** The accrual rounded through a scale-3 intermediate - an engine swapped under ACCRUAL_ENGINE_V1. */
        ROUNDED_TWICE,
        /** The minimum payment without its cap - an engine swapped under STATEMENT_ENGINE_V1. */
        UNCAPPED
    }

    enum Kind {
        LOAN,
        LINE
    }

    /** An instalment loan's repayment behaviour. */
    enum LoanCohort {
        ON_TIME_ON_DUE_DATE,
        /** Pays one to four days after the due date - the base changes mid-period, no late fee. */
        ON_TIME_IN_WINDOW,
        /** Pays the next instalment a few days before it falls due - held as credit, applied at the billing. */
        EARLY,
        /** Pays five to forty days late - late fees, the cap reached on long terms. */
        LATE,
        PARTIAL,
        /** Pays more than due - held as credit, applied by the next billing. */
        OVER,
        SKIP_THEN_CATCH_UP,
        /** Stops paying before maturity - post-maturity billings of continuing interest. */
        UNPAID_PAST_MATURITY,
        /** The record ends at a random as-of date, the loan still ACTIVE. */
        SNAPSHOT_MID_LIFE
    }

    /** A credit line's behaviour. */
    enum LineProfile {
        MINIMUM_PAYER(30),
        FULL_PAYER(40),
        FIXED_PAYER(25),
        LATE_PAYER(25),
        SLEEPER(20),
        OVERPAYER(15),
        /** Pays down to a few euros - the minimum payment's cap binds. */
        PAY_TO_NEAR_ZERO(5),
        /** Long spells at zero drawn - no accrual rows, empty statements. */
        IDLE_SPELLS(4);

        /** Draws a day, per mille. */
        final int drawPerMille;

        LineProfile(int drawPerMille) {
            this.drawPerMille = drawPerMille;
        }
    }

    /** What the writer counts as it writes - the census's coverage tags. */
    enum Tag {
        ACCRUALS,
        ZERO_POSTINGS,
        LEAP_DAY_ACCRUALS,
        IDLE_DAYS,
        BILLINGS,
        POST_MATURITY_BILLINGS,
        STATEMENTS,
        EMPTY_STATEMENTS,
        FLOOR_DOMINATES,
        RATIO_DOMINATES,
        CAP_DOMINATES,
        PAYMENTS,
        MULTI_ITEM_PAYMENTS,
        APPLICATIONS,
        LATE_FEES,
        LATE_FEES_CAPPED,
        LATE_FEE_CAP_REACHED,
        CLOSURES,
        CLOSURES_BY_PAYMENT,
        CLOSURES_BY_STEP,
        REFUNDS,
        PAYDOWNS,
        CREDIT_HELD,
        DRAWS,
        RAISED_SCHEDULES,
        ZERO_RATE,
        MONTH_END_STARTS,
        FORMAT_1,
        FORMAT_2
    }

    /** What a loan's schedule engine is asked - kept for the per-projection cost measurement (ADR-0093). */
    record ScheduleInput(LoanTerms terms, Money principal, int termMonths, int day, LocalDate start) {}

    /**
     * One generated account: its record, its coverage, and what each swapped arithmetic would change.
     *
     * @param roundingSensitive some accrual's posting differs between single and double rounding of the same cumulative
     *     figures - the account the double-rounding probe (and its in-suite twin) must find {@code DIVERGED}
     * @param capBinds some statement's principal part differs with and without the cap on the same operands
     */
    record Generated(Kind kind, int index, Arithmetic arithmetic, LoanRecord record, String behaviour, long[] tags,
            int zone, int format, int term, int day, LocalDate start, boolean roundingSensitive, boolean capBinds,
            Optional<ScheduleInput> scheduleInput) {}

    private AmortisationWorld() {}

    /** The account {@code index} of {@code kind}, its figures by {@code arithmetic}. */
    static Generated generate(Kind kind, int index, Arithmetic arithmetic) {
        Writer writer = new Writer(kind, index, arithmetic);
        return kind == Kind.LOAN ? writer.loan() : writer.line();
    }

    // ------------------------------------------------------------------ the shared set

    /** The door's own rule: the largest rate {@link ScheduleEngineV1#judgeAmortisation} admits, by bisection. */
    private static long maximumAdmittedRateUnits() {
        LoanTerms base = LoanTermsFixtures.personalLoan("0.05").allowedDays(EVERY_DAY).build();
        LoanProductBounds.Bounds bounds = LoanTermsFixtures.BOUNDS.amortisationBounds(LoanProduct.PERSONAL_LOAN)
                .orElseThrow();
        long admitted = 0;
        long refused = 100_000_000L;
        while (refused - admitted > 1) {
            long middle = admitted + (refused - admitted) / 2;
            LoanTerms terms = base.toBuilder().nominalAnnualRate(BigDecimal.valueOf(middle, 8)).build();
            if (ScheduleEngineV1.judgeAmortisation(terms, bounds).isEmpty()) {
                admitted = middle;
            } else {
                refused = middle;
            }
        }
        return admitted;
    }

    private static List<LoanTerms> lineTerms() {
        List<LoanTerms> terms = new ArrayList<>();
        for (String zone : ZONES) {
            for (int variant = 0; variant < variants(); variant++) {
                terms.add(LoanTermsFixtures.creditLine("0.05").servicingZone(zone).allowedDays(EVERY_DAY)
                        .lateFeeCap(Money.ofMinorUnits(LATE_FEE_CAPS[(terms.size() / variants()) % 2], EUR))
                        .revolving(Optional.of(new LoanTerms.RevolvingTerms(
                                Money.ofMinorUnits(FLOORS[variant % FLOORS.length], EUR), RATIOS[variant % RATIOS.length],
                                DUE_DAYS[variant % DUE_DAYS.length], EngineVersion.STATEMENT_ENGINE_V1)))
                        .build());
            }
        }
        return List.copyOf(terms);
    }

    /** Floors x ratios x due days, walked together (3 and 2 coprime: six variants, every floor meets every due day). */
    static int variants() {
        return FLOORS.length * DUE_DAYS.length;
    }

    // ------------------------------------------------------------------ the writer

    /** One billed item as the writer tracks it: what each component still has due. */
    private static final class Item {
        final UUID id;
        final LocalDate dueDate;
        long fees;
        long interest;
        long principal;
        boolean charged;

        Item(UUID id, LocalDate dueDate, long interest, long principal) {
            this.id = id;
            this.dueDate = dueDate;
            this.interest = interest;
            this.principal = principal;
        }

        long total() {
            return fees + interest + principal;
        }
    }

    /** A planned customer payment. */
    private enum Pay {
        ALL_DUE,
        FRACTION,
        ALL_DUE_PLUS,
        AMOUNT,
        EVERYTHING,
        EVERYTHING_PLUS,
        EVERYTHING_LESS,
        STATEMENT_DUE
    }

    private record Plan(Pay mode, long value, UUID item) {}

    private static final class Writer {

        private static final BigDecimal NO_INTEREST = BigDecimal.ZERO.setScale(AmortisationOracles.EXACT_SCALE);

        final Kind kind;
        final int index;
        final Arithmetic arithmetic;
        final Random random;
        final long idHigh;
        int ids;
        long order;

        LoanTerms terms;
        ZoneId zone;
        BigDecimal rate;
        int zoneIndex;
        int format;
        UUID agreementId;

        final Map<AccountPurpose, LedgerAccountId> accounts = new EnumMap<>(AccountPurpose.class);
        final List<LoanRecord.LedgerAccountRow> ledgerAccounts = new ArrayList<>();
        long principal;
        long principalDue;
        long accrued;
        long interestDue;
        long feesDue;
        long credit;
        final List<PurposeBalances.DatedLine> journal = new ArrayList<>();

        final List<LoanRecord.Draw> draws = new ArrayList<>();
        final List<LoanRecord.Accrual> accruals = new ArrayList<>();
        final List<LoanRecord.Billing> billings = new ArrayList<>();
        final List<LoanRecord.StatementRow> statements = new ArrayList<>();
        final List<LoanRecord.Application> applications = new ArrayList<>();
        final List<LoanRecord.Payment> payments = new ArrayList<>();
        final List<LoanRecord.Fee> fees = new ArrayList<>();
        Optional<LoanRecord.Closure> closure = Optional.empty();
        LocalDate closedOn;

        final List<Item> items = new ArrayList<>();
        long feesAssessed;
        long cycleFees;
        final long[] tags = new long[Tag.values().length];
        boolean roundingSensitive;
        boolean capBinds;

        LoanRecord.Accrual last;
        BigDecimal lastCumulative;
        long cachedBase = -1;
        BigDecimal cachedDaily;
        final Map<LocalDate, long[]> periodPosted = new HashMap<>();
        final TreeMap<LocalDate, List<Plan>> plans = new TreeMap<>();

        Writer(Kind kind, int index, Arithmetic arithmetic) {
            this.kind = kind;
            this.index = index;
            this.arithmetic = arithmetic;
            long kindTag = kind == Kind.LOAN ? 0x4C4F414E00000000L : 0x4C494E4500000000L;
            this.random = new Random(SEED * 0x9E3779B97F4A7C15L + kindTag + index);
            // A UUIDv7's shape (ADR-0013: version 7, variant 2), derived: a fixed "timestamp", the kind in rand_a.
            this.idHigh = (0x019A_2026_1014L << 16) | 0x7000L | (kind == Kind.LOAN ? 0x0A1L : 0x0B2L);
        }

        // -------------------------------------------------------------- plumbing

        /** A derived UUIDv7-shaped id - never random: the account's index and a per-account counter. */
        UUID id() {
            return new UUID(idHigh, 0x8000_0000_0000_0000L | ((long) index << 32) | ++ids);
        }

        long nextOrder() {
            return ++order;
        }

        /** An instant inside {@code businessDate} in the pinned zone - never the default zone. */
        Instant at(LocalDate businessDate) {
            return businessDate.atStartOfDay(zone).toInstant().plusSeconds(60 + order % 80_000);
        }

        static Money money(long minor) {
            return Money.ofPersisted(minor, EUR, AmortisationOracles.SCALE);
        }

        long draw(long bound) {
            return Math.floorMod(random.nextLong(), bound);
        }

        void tag(Tag tag) {
            tags[tag.ordinal()]++;
        }

        void openLedger() {
            for (AccountPurpose purpose : LoanSubledgerProof.PURPOSES) {
                LedgerAccountId account = LedgerAccountId.of(id());
                accounts.put(purpose, account);
                ledgerAccounts.add(new LoanRecord.LedgerAccountRow(account, purpose, EUR, "ACTIVE"));
            }
        }

        JournalEntryId entry() {
            return JournalEntryId.of(id());
        }

        /** One leg on a loan-owned account (the counter-legs - wallet, income - are not the loan's). */
        void leg(JournalEntryId entry, AccountPurpose purpose, Direction direction, long amount, LocalDate valueDate) {
            journal.add(new PurposeBalances.DatedLine(entry, accounts.get(purpose), valueDate, direction, money(amount)));
            long signed = direction == Direction.DEBIT ? amount : -amount;
            switch (purpose) {
                case LOAN_PRINCIPAL -> principal += signed;
                case LOAN_PRINCIPAL_DUE -> principalDue += signed;
                case LOAN_INTEREST_ACCRUED -> accrued += signed;
                case LOAN_INTEREST_DUE -> interestDue += signed;
                case LOAN_FEES_DUE -> feesDue += signed;
                case LOAN_CREDIT_BALANCE -> credit -= signed;
                default -> throw new IllegalStateException("not a loan-owned purpose: " + purpose);
            }
        }

        LoanTermsStore.LoanTermsVersion version(UUID id, LocalDate proposed) {
            Instant at = proposed.minusDays(30).atStartOfDay(zone).toInstant();
            return new LoanTermsStore.LoanTermsVersion(new LoanTermsStore.VersionRow(LoanTermsVersionId.of(id),
                    terms.product(), 1, LoanTermsVersionStatus.ACTIVE, "battery-proposer", at,
                    Optional.of("battery-approver"), Optional.of(at), Optional.of(at), Optional.empty()), terms);
        }

        // -------------------------------------------------------------- the instalment loan

        Generated loan() {
            int term = 6 + index % 55;
            int day = 1 + index % 28;
            LocalDate start = start(index, 7, 3);
            LoanCohort cohort = LoanCohort.values()[index % LoanCohort.values().length];
            zoneIndex = random.nextInt(ZONES.size());
            long amount = 500_00 + draw(24_500_01L);
            BigDecimal drawn = index % 16 == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(1 + draw(MAXIMUM_LOAN_RATE_UNITS), 8);
            terms = LOAN_TERMS.get(zoneIndex).toBuilder().nominalAnnualRate(drawn).build();
            rate = terms.nominalAnnualRate();
            zone = ZoneId.of(terms.servicingZone());
            format = terms.servicingZone().contains("+") || random.nextInt(3) > 0 ? 2 : 1;
            LocalDate offeredOn = start.minusDays(random.nextInt(10));
            if (rate.signum() == 0) {
                tag(Tag.ZERO_RATE);
            }

            AmortisationOracles.Plan plan = AmortisationOracles.schedule(amount, rate, start, day,
                    AmortisationOracles.dueDates(start, MIN_FIRST_PERIOD_DAYS, day, term));
            AmortisationOracles.Plan shown = AmortisationOracles.schedule(amount, rate, offeredOn, day,
                    AmortisationOracles.dueDates(offeredOn, MIN_FIRST_PERIOD_DAYS, day, term));
            if (plan.raised()) {
                tag(Tag.RAISED_SCHEDULES);
            }

            UUID loanId = id();
            UUID party = id();
            UUID offerId = id();
            UUID applicationId = id();
            UUID decision = id();
            UUID versionId = id();
            agreementId = id();
            openLedger();
            LoanTermsStore.LoanTermsVersion version = version(versionId, offeredOn);
            LoanOffer.Illustration illustration = new LoanOffer.Illustration(offeredOn, shown.periods().get(0).due(),
                    money(shown.instalment()), money(shown.finalInstalment()), money(shown.totalInterest()),
                    money(amount + shown.totalInterest()));
            String canonical = render(offerId, applicationId, decision, versionId, amount, Optional.of(term), day,
                    Optional.of(illustration));
            byte[] sha = CanonicalOfferTerms.sha256(canonical);
            Instant offeredAt = at(offeredOn);
            LoanOffer offer = new LoanOffer(LoanOfferId.of(offerId), LoanApplicationId.of(applicationId), party,
                    LoanProduct.PERSONAL_LOAN, LoanOfferStatus.ACCEPTED, decision, LoanTermsVersionId.of(versionId), 1,
                    offeredAt, offeredAt.plus(14, ChronoUnit.DAYS), Optional.of(at(start)),
                    CorrelationId.of("battery-loan-" + index),
                    new LoanOffer.Terms(money(amount), Optional.of(term), rate, day, Optional.of(illustration),
                            Optional.empty(), Optional.of(EngineVersion.SCHEDULE_ENGINE_V1),
                            EngineVersion.ACCRUAL_ENGINE_V1, EngineVersion.ALLOCATION_ENGINE_V1, Optional.empty(),
                            AgreementTemplate.PERSONAL_LOAN_AGREEMENT_V1, canonical, sha, CanonicalOfferTerms.sha256(
                                    "offer document " + offerId)));
            Instant acceptedAt = at(start);
            LoanRecord.Agreement agreement = new LoanRecord.Agreement(agreementId, 1, Optional.of(offerId),
                    Optional.empty(), Optional.empty(), decision, versionId, LoanProduct.PERSONAL_LOAN,
                    AmortisationOracles.SCALE, amount, Optional.of(term), rate.toPlainString(), day, Optional.of(1), 1, 1,
                    Optional.empty(), AgreementTemplate.PERSONAL_LOAN_AGREEMENT_V1.id(),
                    AgreementTemplate.PERSONAL_LOAN_AGREEMENT_V1.version(), format, canonical, sha, acceptedAt,
                    acceptedAt, nextOrder());
            tag(format == 1 ? Tag.FORMAT_1 : Tag.FORMAT_2);

            // Rule 1 - the disbursement (path W), before every ordered fact; the accrual start its value date.
            JournalEntryId disbursed = entry();
            leg(disbursed, AccountPurpose.LOAN_PRINCIPAL, Direction.DEBIT, amount, start);
            LoanRecord.Disbursement disbursement = new LoanRecord.Disbursement(id(), "POSTED", Optional.empty(),
                    Optional.of(disbursed), Optional.of(start), Optional.of(acceptedAt), acceptedAt);
            LoanRecord.AccrualStartRow accrualStart = new LoanRecord.AccrualStartRow(start,
                    AccrualStart.Origin.DISBURSEMENT.name(), Optional.of(disbursed), acceptedAt);

            List<LoanRecord.Instalment> instalments = new ArrayList<>();
            for (AmortisationOracles.Period period : plan.periods()) {
                instalments.add(new LoanRecord.Instalment(id(), period.sequence(), period.start(), period.due(),
                        (int) period.days(), money(period.opening()), money(period.interest()),
                        money(period.principal()), money(period.amount()), money(period.closing())));
            }
            UUID scheduleId = id();
            LoanRecord.ScheduleVersion schedule = new LoanRecord.ScheduleVersion(scheduleId, 1, agreementId, 1, 1,
                    money(amount), money(plan.instalment()), money(plan.finalInstalment()), money(plan.totalInterest()),
                    term, start, day, acceptedAt, instalments, nextOrder());

            new LoanLife(this, cohort, plan, instalments, scheduleId).live(start);

            LoanRecord.Account account = new LoanRecord.Account(LoanId.of(loanId), party, LoanProduct.PERSONAL_LOAN,
                    LoanKind.INSTALMENT, closure.isPresent() ? LoanStatus.CLOSED : LoanStatus.ACTIVE, EUR,
                    LoanOfferId.of(offerId), decision, acceptedAt,
                    closure.map(found -> at(closedOn)), closure.map(found -> ClosureReason.REPAID.name()),
                    Optional.empty());
            LoanRecord record = record(account, agreement, versionId, version, offer, Optional.of(disbursement),
                    Optional.of(accrualStart), List.of(schedule));
            return new Generated(Kind.LOAN, index, arithmetic, record, cohort.name(), tags, zoneIndex, format, term,
                    day, start, roundingSensitive, capBinds,
                    Optional.of(new ScheduleInput(terms, money(amount), term, day, start)));
        }

        String render(UUID offerId, UUID applicationId, UUID decision, UUID versionId, long amount,
                Optional<Integer> term, int day, Optional<LoanOffer.Illustration> illustration) {
            CanonicalOfferTerms.Content content = new CanonicalOfferTerms.Content(LoanOfferId.of(offerId),
                    LoanApplicationId.of(applicationId), decision, LoanTermsVersionId.of(versionId), 1, terms,
                    money(amount), term, day, illustration);
            return CanonicalOfferTerms.render(content, format);
        }

        LoanRecord record(LoanRecord.Account account, LoanRecord.Agreement agreement, UUID versionId,
                LoanTermsStore.LoanTermsVersion version, LoanOffer offer, Optional<LoanRecord.Disbursement> disbursement,
                Optional<LoanRecord.AccrualStartRow> accrualStart, List<LoanRecord.ScheduleVersion> schedules) {
            return new LoanRecord(account, List.of(agreement), Map.of(versionId, Optional.of(version)), Optional.of(offer),
                    disbursement, accrualStart, Optional.empty(), schedules, draws, accruals, billings, statements,
                    applications, payments, List.of(), fees, List.of(), List.of(), List.of(), closure, List.of(),
                    List.of(), Optional.empty(), List.of(), List.of(), ledgerAccounts, journal);
        }

        // -------------------------------------------------------------- the credit line

        Generated line() {
            int day = 1 + index % 28;
            LocalDate openedOn = start(index, 5, 2);
            LineProfile profile = LineProfile.values()[(index + index / 28) % LineProfile.values().length];
            zoneIndex = random.nextInt(ZONES.size());
            int variant = random.nextInt(variants());
            long limit = LIMITS[(index / 28 + index / 3) % LIMITS.length];
            BigDecimal drawn = index % 20 == 7 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(1 + draw(MAXIMUM_LINE_RATE_UNITS), 8);
            terms = LINE_TERMS.get(zoneIndex * variants() + variant).toBuilder().nominalAnnualRate(drawn).build();
            rate = terms.nominalAnnualRate();
            zone = ZoneId.of(terms.servicingZone());
            format = terms.servicingZone().contains("+") || random.nextInt(3) > 0 ? 2 : 1;
            int months = 6 + random.nextInt(31);
            if (rate.signum() == 0) {
                tag(Tag.ZERO_RATE);
            }

            UUID loanId = id();
            UUID party = id();
            UUID offerId = id();
            UUID applicationId = id();
            UUID decision = id();
            UUID versionId = id();
            agreementId = id();
            openLedger();
            LocalDate offeredOn = openedOn.minusDays(random.nextInt(10));
            LoanTermsStore.LoanTermsVersion version = version(versionId, offeredOn);
            String canonical = render(offerId, applicationId, decision, versionId, limit, Optional.empty(), day,
                    Optional.empty());
            byte[] sha = CanonicalOfferTerms.sha256(canonical);
            LoanTerms.RevolvingTerms revolving = terms.revolving().orElseThrow();
            Instant offeredAt = at(offeredOn);
            Instant openedAt = at(openedOn);
            LoanOffer offer = new LoanOffer(LoanOfferId.of(offerId), LoanApplicationId.of(applicationId), party,
                    LoanProduct.CREDIT_LINE, LoanOfferStatus.ACCEPTED, decision, LoanTermsVersionId.of(versionId), 1,
                    offeredAt, offeredAt.plus(14, ChronoUnit.DAYS), Optional.of(openedAt),
                    CorrelationId.of("battery-line-" + index),
                    new LoanOffer.Terms(money(limit), Optional.empty(), rate, day, Optional.empty(),
                            Optional.of(new LoanOffer.MinimumPaymentRule(revolving.minimumPaymentFloor(),
                                    revolving.minimumPaymentRatioBps(), revolving.paymentDueDays())),
                            Optional.empty(), EngineVersion.ACCRUAL_ENGINE_V1, EngineVersion.ALLOCATION_ENGINE_V1,
                            Optional.of(EngineVersion.STATEMENT_ENGINE_V1), AgreementTemplate.CREDIT_LINE_AGREEMENT_V1,
                            canonical, sha, CanonicalOfferTerms.sha256("offer document " + offerId)));
            LoanRecord.Agreement agreement = new LoanRecord.Agreement(agreementId, 1, Optional.of(offerId),
                    Optional.empty(), Optional.empty(), decision, versionId, LoanProduct.CREDIT_LINE,
                    AmortisationOracles.SCALE, limit, Optional.empty(), rate.toPlainString(), day, Optional.empty(), 1, 1,
                    Optional.of(1), AgreementTemplate.CREDIT_LINE_AGREEMENT_V1.id(),
                    AgreementTemplate.CREDIT_LINE_AGREEMENT_V1.version(), format, canonical, sha, openedAt, openedAt,
                    nextOrder());
            tag(format == 1 ? Tag.FORMAT_1 : Tag.FORMAT_2);

            new LineLife(this, profile, limit, day, revolving).live(openedOn, openedOn.plusMonths(months));

            LoanRecord.Account account = new LoanRecord.Account(LoanId.of(loanId), party, LoanProduct.CREDIT_LINE,
                    LoanKind.REVOLVING, LoanStatus.ACTIVE, EUR, LoanOfferId.of(offerId), decision, openedAt,
                    Optional.empty(), Optional.empty(), Optional.empty());
            LoanRecord record = record(account, agreement, versionId, version, offer, Optional.empty(), Optional.empty(),
                    List.of());
            return new Generated(Kind.LINE, index, arithmetic, record, profile.name(), tags, zoneIndex, format, 0, day,
                    openedOn, roundingSensitive, capBinds, Optional.empty());
        }

        /**
         * A start or opening date: every day of 2027 and 2028 in turn, with month ends over-sampled (one account in
         * {@code every}) - 31 January, 28 February 2027, 29 February 2028, 30 April and the rest.
         */
        LocalDate start(int index, int every, int residue) {
            if (index % every == residue) {
                tag(Tag.MONTH_END_STARTS);
                return YearMonth.of(2027, Month.JANUARY).plusMonths((index / every) % 24L).atEndOfMonth();
            }
            return FIRST_DAY.plusDays(index % START_DAYS);
        }

        // -------------------------------------------------------------- the servicing step's parts

        /** Rule 2 for {@code date}: the period's cumulative exact figure and the change in its once-rounded value. */
        void accrue(LocalDate date, LocalDate periodStart, LocalDate periodEnd, LocalDate businessDate) {
            long base = principal + principalDue;
            if (base <= 0) {
                tag(Tag.IDLE_DAYS); // no principal outstanding at the date's end: no row (a line at zero drawn)
                return;
            }
            boolean continues = last != null && last.periodStart().equals(periodStart)
                    && last.periodEnd().equals(periodEnd);
            BigDecimal before = continues ? lastCumulative : NO_INTEREST;
            if (base != cachedBase) {
                cachedBase = base;
                cachedDaily = AmortisationOracles.daily(base, rate);
            }
            BigDecimal cumulative = before.add(cachedDaily);
            long standard = AmortisationOracles.posted(before, cumulative);
            long twice = AmortisationOracles.postedTwice(before, cumulative);
            roundingSensitive |= standard != twice;
            long posted = arithmetic == Arithmetic.ROUNDED_TWICE ? twice : standard;
            Optional<JournalEntryId> entry = posted > 0 ? Optional.of(entry()) : Optional.empty();
            long recorded = nextOrder();
            LoanRecord.Accrual row = new LoanRecord.Accrual(id(), date, periodStart, periodEnd, money(base), cumulative,
                    money(posted), agreementId, 1, 1, entry, at(businessDate), recorded);
            entry.ifPresent(found -> leg(found, AccountPurpose.LOAN_INTEREST_ACCRUED, Direction.DEBIT, posted, date));
            accruals.add(row);
            last = row;
            lastCumulative = cumulative;
            long[] sum = periodPosted.computeIfAbsent(periodStart, key -> new long[2]);
            sum[0] += posted;
            sum[1]++;
            tag(Tag.ACCRUALS);
            if (posted == 0) {
                tag(Tag.ZERO_POSTINGS);
            }
            if (date.getMonth() == Month.FEBRUARY && date.getDayOfMonth() == 29) {
                tag(Tag.LEAP_DAY_ACCRUALS);
            }
        }

        /** Rule 3's legs, dated the business date: interest accrued to due, principal not yet due to due. */
        Optional<JournalEntryId> reclassify(long interest, long principalBilled, LocalDate today) {
            if (interest + principalBilled <= 0) {
                return Optional.empty();
            }
            JournalEntryId entry = entry();
            if (interest > 0) {
                leg(entry, AccountPurpose.LOAN_INTEREST_DUE, Direction.DEBIT, interest, today);
                leg(entry, AccountPurpose.LOAN_INTEREST_ACCRUED, Direction.CREDIT, interest, today);
            }
            if (principalBilled > 0) {
                leg(entry, AccountPurpose.LOAN_PRINCIPAL_DUE, Direction.DEBIT, principalBilled, today);
                leg(entry, AccountPurpose.LOAN_PRINCIPAL, Direction.CREDIT, principalBilled, today);
            }
            return Optional.of(entry);
        }

        List<AmortisationOracles.Due> dues() {
            List<AmortisationOracles.Due> dues = new ArrayList<>();
            for (Item item : items) {
                if (item.total() > 0) {
                    dues.add(new AmortisationOracles.Due(item.id, item.dueDate, item.fees, item.interest,
                            item.principal));
                }
            }
            return dues;
        }

        long dueTotal() {
            long total = 0;
            for (Item item : items) {
                total += item.total();
            }
            return total;
        }

        Item item(UUID id) {
            for (Item item : items) {
                if (item.id.equals(id)) {
                    return item;
                }
            }
            throw new IllegalStateException("an allocation names a billed item of the account");
        }

        void settle(AmortisationOracles.Line line) {
            Item item = item(line.item());
            switch (line.component()) {
                case FEES -> item.fees -= line.amount();
                case INTEREST -> item.interest -= line.amount();
                case PRINCIPAL -> item.principal -= line.amount();
            }
        }

        static AccountPurpose dueAccount(AllocationComponent component) {
            return switch (component) {
                case FEES -> AccountPurpose.LOAN_FEES_DUE;
                case INTEREST -> AccountPurpose.LOAN_INTEREST_DUE;
                case PRINCIPAL -> AccountPurpose.LOAN_PRINCIPAL_DUE;
            };
        }

        /** Rule 6 after a billed item is born: the credit balance over what is due, the remainder held. */
        void apply(UUID billed, LocalDate today) {
            if (credit <= 0 || dueTotal() <= 0) {
                return;
            }
            List<LoanRecord.ApplicationLine> lines = new ArrayList<>();
            Map<AllocationComponent, Long> perComponent = new EnumMap<>(AllocationComponent.class);
            long amount = 0;
            for (AmortisationOracles.Line line : AmortisationOracles.allocate(credit, dues(), 0, false)) {
                if (line.target() == AllocationLine.Target.DUE_ITEM) {
                    lines.add(new LoanRecord.ApplicationLine(lines.size() + 1, line.item(), line.component(),
                            money(line.amount())));
                    perComponent.merge(line.component(), line.amount(), Long::sum);
                    amount += line.amount();
                    settle(line);
                }
            }
            if (lines.isEmpty()) {
                return;
            }
            JournalEntryId entry = entry();
            leg(entry, AccountPurpose.LOAN_CREDIT_BALANCE, Direction.DEBIT, amount, today);
            perComponent.forEach((component, paid) -> leg(entry, dueAccount(component), Direction.CREDIT, paid, today));
            long recorded = nextOrder();
            applications.add(new LoanRecord.Application(id(), billed, money(amount), agreementId, 1, 1, entry,
                    at(today), lines, recorded));
            tag(Tag.APPLICATIONS);
        }

        /** Rule 5: the customer's payment, allocated by the pinned order and treatment; the closure if it settles. */
        void pay(long amount, LocalDate today) {
            if (amount <= 0) {
                return;
            }
            boolean payDown = kind == Kind.LINE;
            List<AmortisationOracles.Line> allocated = AmortisationOracles.allocate(amount, dues(), principal, payDown);
            JournalEntryId entry = entry();
            Map<AllocationComponent, Long> perComponent = new EnumMap<>(AllocationComponent.class);
            long paidDown = 0;
            long held = 0;
            int dueLines = 0;
            List<LoanRecord.PaymentLine> lines = new ArrayList<>();
            for (AmortisationOracles.Line line : allocated) {
                lines.add(new LoanRecord.PaymentLine(id(), lines.size() + 1, line.target(),
                        Optional.ofNullable(line.item()), Optional.ofNullable(line.component()), money(line.amount())));
                switch (line.target()) {
                    case DUE_ITEM -> {
                        perComponent.merge(line.component(), line.amount(), Long::sum);
                        settle(line);
                        dueLines++;
                    }
                    case NOT_DUE_PRINCIPAL -> paidDown += line.amount();
                    case CREDIT_BALANCE -> held += line.amount();
                }
            }
            for (Map.Entry<AllocationComponent, Long> component : perComponent.entrySet()) {
                leg(entry, dueAccount(component.getKey()), Direction.CREDIT, component.getValue(), today);
            }
            if (paidDown > 0) {
                leg(entry, AccountPurpose.LOAN_PRINCIPAL, Direction.CREDIT, paidDown, today);
                tag(Tag.PAYDOWNS);
            }
            if (held > 0) {
                leg(entry, AccountPurpose.LOAN_CREDIT_BALANCE, Direction.CREDIT, held, today);
                tag(Tag.CREDIT_HELD);
            }
            UUID paymentId = id();
            long recorded = nextOrder();
            payments.add(new LoanRecord.Payment(paymentId, RepaymentKind.CUSTOMER, money(amount), agreementId, 1, 1,
                    entry, today, at(today), lines, recorded));
            tag(Tag.PAYMENTS);
            if (dueLines >= 2) {
                tag(Tag.MULTI_ITEM_PAYMENTS);
            }
            if (kind == Kind.LOAN && closeIfSettled(today, Optional.of(paymentId))) {
                tag(Tag.CLOSURES_BY_PAYMENT);
            }
        }

        /** An ACTIVE instalment loan whose every receivable is zero closes REPAID, its credit balance refunded. */
        boolean closeIfSettled(LocalDate today, Optional<UUID> repayment) {
            if (principal != 0 || principalDue != 0 || accrued != 0 || interestDue != 0 || feesDue != 0) {
                return false;
            }
            long refund = credit;
            Optional<JournalEntryId> entry = refund > 0 ? Optional.of(entry()) : Optional.empty();
            entry.ifPresent(found -> leg(found, AccountPurpose.LOAN_CREDIT_BALANCE, Direction.DEBIT, refund, today));
            long recorded = nextOrder();
            closure = Optional.of(new LoanRecord.Closure(id(), ClosureReason.REPAID.name(), money(refund), entry,
                    repayment, at(today), recorded));
            closedOn = today;
            tag(Tag.CLOSURES);
            if (refund > 0) {
                tag(Tag.REFUNDS);
            }
            return true;
        }

        /** Rule 4: once per billed item whose age reached the pinned day while it still owes, capped per account. */
        void lateFees(LocalDate today) {
            long fee = terms.lateFee().minorUnits();
            long cap = terms.lateFeeCap().minorUnits();
            for (Item item : items) {
                if (ChronoUnit.DAYS.between(item.dueDate, today) < terms.lateFeeDay()) {
                    break;
                }
                if (item.charged || item.interest + item.principal <= 0) {
                    continue;
                }
                long room = cap - feesAssessed;
                if (room <= 0) {
                    break;
                }
                long amount = Math.min(fee, room);
                JournalEntryId entry = entry();
                leg(entry, AccountPurpose.LOAN_FEES_DUE, Direction.DEBIT, amount, today);
                long recorded = nextOrder();
                fees.add(new LoanRecord.Fee(id(), item.id, FeeKind.LATE.name(), today, money(amount), agreementId, 1,
                        entry, at(today), recorded));
                feesAssessed += amount;
                cycleFees += amount;
                item.fees += amount;
                item.charged = true;
                tag(Tag.LATE_FEES);
                if (amount < fee) {
                    tag(Tag.LATE_FEES_CAPPED);
                }
                if (feesAssessed == cap) {
                    tag(Tag.LATE_FEE_CAP_REACHED);
                }
            }
        }

        void plan(LocalDate date, Pay mode, long value) {
            plan(date, mode, value, null);
        }

        void plan(LocalDate date, Pay mode, long value, UUID item) {
            plans.computeIfAbsent(date, key -> new ArrayList<>()).add(new Plan(mode, value, item));
        }

        /** The customer's planned payments of {@code today}, after the day's servicing step. */
        void payPlanned(LocalDate today) {
            List<Plan> due = plans.remove(today);
            if (due == null) {
                return;
            }
            for (Plan plan : due) {
                if (closure.isPresent()) {
                    return;
                }
                long everything = dueTotal() + Math.max(principal, 0);
                long amount = switch (plan.mode()) {
                    case ALL_DUE -> dueTotal();
                    case FRACTION -> dueTotal() * plan.value() / 100;
                    case ALL_DUE_PLUS -> dueTotal() + plan.value();
                    case AMOUNT -> plan.value();
                    case EVERYTHING -> everything;
                    case EVERYTHING_PLUS -> everything + plan.value();
                    case EVERYTHING_LESS -> everything - plan.value();
                    case STATEMENT_DUE -> item(plan.item()).total();
                };
                pay(amount, today);
            }
        }
    }

    // ------------------------------------------------------------------ an instalment loan's life

    /** An instalment loan's simulated calendar: the servicing step each business date, then the customer's acts. */
    private static final class LoanLife {

        final Writer writer;
        final LoanCohort cohort;
        final AmortisationOracles.Plan plan;
        final List<LoanRecord.Instalment> instalments;
        final UUID scheduleId;
        final int n;
        final LocalDate firstDue;
        int billed;
        int maturityPeriods;
        int skipped;
        int unpaidFrom;
        int unpaidPayAt;
        LocalDate periodStart;
        LocalDate periodEnd;
        int periodSequence;

        LoanLife(Writer writer, LoanCohort cohort, AmortisationOracles.Plan plan,
                List<LoanRecord.Instalment> instalments, UUID scheduleId) {
            this.writer = writer;
            this.cohort = cohort;
            this.plan = plan;
            this.instalments = instalments;
            this.scheduleId = scheduleId;
            this.n = plan.periods().size();
            this.firstDue = plan.periods().get(0).due();
            this.unpaidFrom = n - writer.random.nextInt(3);
            this.unpaidPayAt = writer.random.nextInt(4);
        }

        void live(LocalDate start) {
            LocalDate maturity = plan.periods().get(n - 1).due();
            LocalDate end = cohort == LoanCohort.SNAPSHOT_MID_LIFE
                    ? start.plusDays(45 + writer.random.nextInt((int) ChronoUnit.DAYS.between(start, maturity) - 60))
                    : maturity.plusDays(190);
            periodStart = start;
            periodEnd = firstDue;
            periodSequence = 1;
            if (cohort == LoanCohort.EARLY) {
                writer.plan(firstDue.minusDays(1 + writer.random.nextInt(5)), Pay.AMOUNT, plan.periods().get(0).amount());
            }
            for (LocalDate today = start.plusDays(1); !today.isAfter(end); today = today.plusDays(1)) {
                step(today);
                if (writer.closure.isPresent()) {
                    writer.tag(Tag.CLOSURES_BY_STEP);
                    return;
                }
                writer.payPlanned(today);
                if (writer.closure.isPresent()) {
                    return;
                }
            }
        }

        /** The servicing step of {@code today}: accrue yesterday, bill what fell due, close, assess late fees. */
        void step(LocalDate today) {
            LocalDate date = today.minusDays(1);
            while (!date.isBefore(periodEnd)) {
                periodSequence++;
                periodStart = periodEnd;
                periodEnd = AmortisationOracles.continuedDue(firstDue, plan.day(), periodSequence);
            }
            writer.accrue(date, periodStart, periodEnd, today);
            while (billed < n && !plan.periods().get(billed).due().isAfter(today)) {
                AmortisationOracles.Period period = plan.periods().get(billed);
                billed++;
                bill(BillingKind.INSTALMENT, Optional.of(instalments.get(billed - 1).id()), billed, period.start(),
                        period.due(), today);
            }
            while (billed == n) {
                LocalDate start = AmortisationOracles.continuedDue(firstDue, plan.day(), n + maturityPeriods);
                LocalDate end = AmortisationOracles.continuedDue(firstDue, plan.day(), n + maturityPeriods + 1);
                if (end.isAfter(today)) {
                    break;
                }
                maturityPeriods++;
                bill(BillingKind.POST_MATURITY, Optional.empty(), n + maturityPeriods, start, end, today);
            }
            if (writer.closeIfSettled(today, Optional.empty())) {
                return;
            }
            writer.lateFees(today);
        }

        /** Rule 3 for one billed item, then rule 6. */
        void bill(BillingKind kind, Optional<UUID> instalment, int sequence, LocalDate start, LocalDate due,
                LocalDate today) {
            long[] accrued = writer.periodPosted.getOrDefault(start, new long[2]);
            if (kind == BillingKind.POST_MATURITY && accrued[1] == 0) {
                return; // nothing accrued in the period: nothing falls due
            }
            long interest = accrued[0];
            long principalBilled;
            if (kind == BillingKind.POST_MATURITY) {
                principalBilled = 0;
            } else if (sequence == n) {
                principalBilled = Math.max(writer.principal, 0);
            } else {
                long portion = plan.instalment() - interest;
                principalBilled = portion <= 0 ? 0 : portion <= writer.principal ? portion : Math.max(writer.principal, 0);
            }
            Optional<JournalEntryId> entry = writer.reclassify(interest, principalBilled, today);
            UUID billingId = writer.id();
            long recorded = writer.nextOrder();
            writer.billings.add(new LoanRecord.Billing(billingId, kind.name(), scheduleId, 1, instalment, sequence, start,
                    due, Writer.money(interest), Writer.money(principalBilled), writer.agreementId, 1, 1, 1, entry,
                    writer.at(today), recorded));
            writer.items.add(new Item(billingId, due, interest, principalBilled));
            writer.tag(Tag.BILLINGS);
            if (kind == BillingKind.POST_MATURITY) {
                writer.tag(Tag.POST_MATURITY_BILLINGS);
            }
            writer.apply(billingId, today);
            plans(sequence, kind == BillingKind.POST_MATURITY, today);
        }

        /** The cohort's payment for the item just billed. */
        void plans(int sequence, boolean postMaturity, LocalDate today) {
            Random random = writer.random;
            if (postMaturity || sequence > n) {
                if (cohort != LoanCohort.UNPAID_PAST_MATURITY) {
                    writer.plan(today, Pay.ALL_DUE, 0);
                } else if (unpaidPayAt > 0 && maturityPeriods >= unpaidPayAt) {
                    writer.plan(today, Pay.ALL_DUE, 0);
                }
                return;
            }
            switch (cohort) {
                case ON_TIME_ON_DUE_DATE, SNAPSHOT_MID_LIFE -> writer.plan(today, Pay.ALL_DUE, 0);
                case ON_TIME_IN_WINDOW -> writer.plan(today.plusDays(1 + random.nextInt(4)), Pay.ALL_DUE, 0);
                case EARLY -> {
                    writer.plan(today, Pay.ALL_DUE, 0);
                    if (sequence < n) {
                        AmortisationOracles.Period next = plan.periods().get(sequence);
                        writer.plan(next.due().minusDays(1 + random.nextInt(5)), Pay.AMOUNT, next.amount());
                    }
                }
                case LATE -> writer.plan(today.plusDays(5 + random.nextInt(36)), Pay.ALL_DUE, 0);
                case PARTIAL -> {
                    if (sequence % 4 == 0 || sequence == n) {
                        writer.plan(today, Pay.ALL_DUE, 0);
                    } else {
                        writer.plan(today, Pay.FRACTION, 30 + random.nextInt(66));
                    }
                }
                case OVER -> writer.plan(today, Pay.ALL_DUE_PLUS, 1_00 + random.nextInt(200_00));
                case SKIP_THEN_CATCH_UP -> {
                    if (skipped < 3 && sequence < n && random.nextInt(10) < 4) {
                        skipped++;
                    } else {
                        skipped = 0;
                        writer.plan(today, Pay.ALL_DUE, 0);
                    }
                }
                case UNPAID_PAST_MATURITY -> {
                    if (sequence < unpaidFrom) {
                        writer.plan(today, Pay.ALL_DUE, 0);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ a credit line's life

    /** A credit line's simulated calendar: draws, statements and payments, the step each business date first. */
    private static final class LineLife {

        final Writer writer;
        final LineProfile profile;
        final long limit;
        final int statementDay;
        final long floor;
        final int ratioBps;
        final int dueDays;
        final long fixed;
        LocalDate cycleStart;
        LocalDate statementDate;

        LineLife(Writer writer, LineProfile profile, long limit, int statementDay, LoanTerms.RevolvingTerms revolving) {
            this.writer = writer;
            this.profile = profile;
            this.limit = limit;
            this.statementDay = statementDay;
            this.floor = revolving.minimumPaymentFloor().minorUnits();
            this.ratioBps = revolving.minimumPaymentRatioBps();
            this.dueDays = revolving.paymentDueDays();
            this.fixed = 20_00 + writer.draw(480_01L);
        }

        void live(LocalDate openedOn, LocalDate end) {
            cycleStart = openedOn;
            statementDate = AmortisationOracles.firstStatement(openedOn, statementDay);
            // The first draw on the opening day - every line is drawn at least once.
            drawUpTo(openedOn, limit * (20 + writer.random.nextInt(81)) / 100);
            for (LocalDate today = openedOn.plusDays(1); !today.isAfter(end); today = today.plusDays(1)) {
                step(today);
                acts(today);
            }
        }

        /** The servicing step of {@code today}: accrue yesterday, state the cycle that ended, assess late fees. */
        void step(LocalDate today) {
            writer.accrue(today.minusDays(1), cycleStart, statementDate, today);
            if (today.equals(statementDate)) {
                state(today);
            }
            writer.lateFees(today);
        }

        /** Rule 3 for the cycle ending today - A24's minimum payment - then rule 6. */
        void state(LocalDate today) {
            long interest = writer.periodPosted.getOrDefault(cycleStart, new long[2])[0];
            long carried = writer.cycleFees;
            long notDue = writer.principal;
            long drawn = writer.principal + writer.principalDue;
            long capped = AmortisationOracles.cappedPrincipal(notDue, drawn, interest, carried, floor, ratioBps);
            long uncapped = AmortisationOracles.uncappedPrincipal(drawn, interest, carried, floor, ratioBps);
            long ratio = AmortisationOracles.ratioPart(drawn, ratioBps);
            long floorPart = floor - interest - carried;
            if (capped != uncapped) {
                writer.capBinds = true;
                writer.tag(Tag.CAP_DOMINATES);
            } else if (uncapped > 0 && ratio >= floorPart) {
                writer.tag(Tag.RATIO_DOMINATES);
            } else if (uncapped > 0) {
                writer.tag(Tag.FLOOR_DOMINATES);
            }
            long principalBilled = writer.arithmetic == Arithmetic.UNCAPPED ? uncapped : capped;
            long minimum = interest + carried + principalBilled;
            LocalDate due = today.plusDays(dueDays);
            Optional<JournalEntryId> entry = writer.reclassify(interest, principalBilled, today);
            UUID statementId = writer.id();
            long recorded = writer.nextOrder();
            writer.statements.add(new LoanRecord.StatementRow(statementId, cycleStart, today, due,
                    Writer.money(interest), Writer.money(carried), Writer.money(principalBilled), Writer.money(minimum),
                    writer.agreementId, 1, 1, 1, entry, writer.at(today), recorded));
            writer.items.add(new Item(statementId, due, interest, principalBilled));
            writer.tag(Tag.STATEMENTS);
            if (minimum == 0) {
                writer.tag(Tag.EMPTY_STATEMENTS);
            }
            writer.apply(statementId, today);
            cycleStart = today;
            statementDate = today.plusMonths(1);
            writer.cycleFees = 0;
            plans(statementId, due);
        }

        /** The profile's payment of the statement just issued. */
        void plans(UUID statement, LocalDate due) {
            Random random = writer.random;
            switch (profile) {
                case MINIMUM_PAYER -> writer.plan(due.minusDays(random.nextInt(6)), Pay.STATEMENT_DUE, 0, statement);
                case FULL_PAYER, IDLE_SPELLS -> writer.plan(due.minusDays(random.nextInt(11)), Pay.EVERYTHING, 0);
                case FIXED_PAYER -> writer.plan(due.minusDays(2), Pay.AMOUNT, fixed);
                case LATE_PAYER -> writer.plan(due.plusDays(5 + random.nextInt(16)), Pay.ALL_DUE, 0);
                case SLEEPER -> {
                    if (random.nextInt(2) == 0) {
                        writer.plan(due, Pay.ALL_DUE, 0);
                    }
                }
                case OVERPAYER -> writer.plan(due.minusDays(random.nextInt(6)), Pay.EVERYTHING_PLUS,
                        1_00 + random.nextInt(100_00));
                case PAY_TO_NEAR_ZERO -> writer.plan(due.minusDays(1), Pay.EVERYTHING_LESS, 1_00 + random.nextInt(2_000));
            }
        }

        /** The customer's acts of {@code today}: a draw by the profile's odds, then the planned payments. */
        void acts(LocalDate today) {
            boolean idle = profile == LineProfile.IDLE_SPELLS && (today.getMonthValue() + today.getYear()) % 3 != 0;
            if (!idle && writer.random.nextInt(1_000) < profile.drawPerMille) {
                drawUpTo(today, Math.max(limit / 3, 100_00));
            }
            writer.payPlanned(today);
        }

        /** Rule 1 for a draw: within the available limit, {@code DR LOAN_PRINCIPAL}. */
        void drawUpTo(LocalDate today, long most) {
            long available = limit - (writer.principal + writer.principalDue);
            long ceiling = Math.min(available, most);
            if (ceiling < 1_00) {
                return;
            }
            long amount = 1_00 + writer.draw(ceiling - 1_00 + 1);
            JournalEntryId entry = writer.entry();
            writer.leg(entry, AccountPurpose.LOAN_PRINCIPAL, Direction.DEBIT, amount, today);
            long recorded = writer.nextOrder();
            writer.draws.add(new LoanRecord.Draw(writer.id(), Writer.money(amount), writer.agreementId, 1, entry, today,
                    writer.at(today), recorded));
            writer.tag(Tag.DRAWS);
        }
    }
}
