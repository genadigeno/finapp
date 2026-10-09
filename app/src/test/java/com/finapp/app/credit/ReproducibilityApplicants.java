package com.finapp.app.credit;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditProduct;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * The battery's seeded applicant generator (`P10-TST-002`): one seed, one list of applicants - every value drawn from a
 * {@link SplittableRandom} in a fixed order on one thread before anything runs, so the same seed always yields the same
 * applicants whatever the workers' interleaving later does with them.
 *
 * <p>The distributions are chosen to reach every path the engine has, not to resemble a population: boundary values on
 * every ordered attribute (an age of exactly 18, delinquencies of exactly 2, a request at exactly the auto-approval
 * ceiling, a bureau balance putting the exposure headroom at exactly zero), partial answers, money in a currency no
 * product holds, unavailable sources, absent party facts and every risk signal.
 */
final class ReproducibilityApplicants {

    /** How a source answers an applicant. */
    enum Answer {
        /** Every attribute present. */
        RECEIVED,
        /** One or two attributes absent. */
        PARTIAL,
        /** Money in US dollars - absent in the snapshot, with the CURRENCY_NOT_SUPPORTED marker, never converted. */
        FOREIGN,
        /** No answer by the collection window: the source's attributes absent, SOURCE_UNAVAILABLE naming it. */
        UNAVAILABLE
    }

    /** What the bureau reports for one applicant. */
    record Bureau(
            Answer answer,
            long externalScore,
            long activeAccounts,
            long delinquencies,
            long defaults,
            boolean insolvent,
            long monthlyObligationsMinor,
            long totalBalanceMinor,
            Set<CreditAttributeCode> absent) {}

    /** What the financial-data provider reports for one applicant. */
    record FinancialData(Answer answer, long incomeMinor, long committedMinor, Set<CreditAttributeCode> absent) {}

    /**
     * One applicant's request and everything the sources and ports will say about them.
     *
     * @param epoch the policy epoch the request is pinned in (1, 2 or 3)
     * @param personApproves whether the person deciding a referral approves (when the bounds allow) or declines
     * @param personReason the index of the adverse reason code a person cites
     * @param companion the same party's request for the other product, evaluated beside this one before either is
     *     decided - so the second decided meets the first's reservation and decides on a successor snapshot
     */
    record Applicant(
            int index,
            UUID party,
            int epoch,
            CreditProduct product,
            Money requested,
            Optional<Integer> termMonths,
            Optional<Money> declaredIncome,
            Optional<Money> declaredExpenditure,
            Optional<Integer> ageYears,
            Optional<String> residence,
            String riskSignal,
            long platformOutstandingMinor,
            Bureau bureau,
            FinancialData financialData,
            boolean personApproves,
            int personReason,
            Optional<Money> companion) {}

    /** The other product. */
    static CreditProduct other(CreditProduct product) {
        return product == CreditProduct.PERSONAL_LOAN ? CreditProduct.CREDIT_LINE : CreditProduct.PERSONAL_LOAN;
    }

    /** The residences the battery's own policies accept, and three they refuse. */
    static final List<String> ACCEPTED_RESIDENCES = List.of("AT", "BE", "DE", "ES", "FR", "IE", "IT", "NL", "PT");

    static final List<String> REFUSED_RESIDENCES = List.of("CH", "GB", "US");

    /** Every code the risk seam double answers - each banded by the battery's scorecards. */
    static final List<String> RISK_SIGNALS = List.of("HIGH", "LOW", "NOT_ASSESSED", "SEVERE", "WATCH");

    private ReproducibilityApplicants() {}

    /**
     * {@code count} applicants from {@code seed}, spread over three epochs; {@code maximumExposure} is each epoch's
     * product limit in minor units (index {@code [epoch - 1][product ordinal]}), so a cohort can sit at exactly zero
     * headroom.
     */
    static List<Applicant> generate(long seed, int count, long[][] maximumExposure) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Applicant> applicants = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int epoch = 1 + index * 3 / count;
            applicants.add(applicant(random, index, epoch, maximumExposure));
        }
        return List.copyOf(applicants);
    }

    private static Applicant applicant(SplittableRandom random, int index, int epoch, long[][] maximumExposure) {
        UUID party = new UUID(random.nextLong(), random.nextLong());
        CreditProduct product = random.nextInt(100) < 55 ? CreditProduct.PERSONAL_LOAN : CreditProduct.CREDIT_LINE;
        long requested = requested(random, product);
        Optional<Integer> term = product.revolving() ? Optional.empty() : Optional.of(term(random));
        Optional<Money> declaredIncome = random.nextInt(100) < 70
                ? Optional.of(CreditWorld.eur(150_000 + random.nextLong(850_000))) : Optional.empty();
        Optional<Money> declaredExpenditure = random.nextInt(100) < 60
                ? Optional.of(CreditWorld.eur(20_000 + random.nextLong(230_000))) : Optional.empty();
        if (random.nextInt(100) < 2) {
            declaredIncome = Optional.of(CreditWorld.eur(1_000_000 + random.nextLong(500_000)));
        }
        Optional<Integer> age = age(random);
        Optional<String> residence = residence(random);
        String risk = risk(random);
        long outstanding = random.nextInt(100) < 85 ? 0 : random.nextLong(500_000);
        Bureau bureau = bureau(random, product, epoch, requested, outstanding, maximumExposure);
        FinancialData financialData = financialData(random);
        boolean approves = random.nextBoolean();
        int reason = random.nextInt(Integer.MAX_VALUE);
        Optional<Money> companion = random.nextInt(100) < 5
                ? Optional.of(CreditWorld.eur(requested(random, other(product)))) : Optional.empty();
        return new Applicant(index, party, epoch, product, CreditWorld.eur(requested), term, declaredIncome,
                declaredExpenditure, age, residence, risk, outstanding, bureau, financialData, approves, reason, companion);
    }

    private static long requested(SplittableRandom random, CreditProduct product) {
        long minimum = product.minimumAmount().minorUnits();
        long maximum = product == CreditProduct.PERSONAL_LOAN ? 2_500_000 : 500_000;
        long ceiling = product == CreditProduct.PERSONAL_LOAN ? 1_000_000 : 250_000;
        return switch (random.nextInt(20)) {
            case 0 -> minimum;
            case 1 -> ceiling;
            case 2 -> ceiling + 1;
            case 3 -> maximum;
            default -> minimum + random.nextLong(maximum - minimum);
        };
    }

    private static int term(SplittableRandom random) {
        return switch (random.nextInt(10)) {
            case 0 -> 6;
            case 1 -> 60;
            default -> 6 + random.nextInt(55);
        };
    }

    private static Optional<Integer> age(SplittableRandom random) {
        int roll = random.nextInt(100);
        if (roll < 3) {
            return Optional.empty();
        }
        if (roll < 6) {
            return Optional.of(17);
        }
        if (roll < 9) {
            return Optional.of(18);
        }
        if (roll < 11) {
            return Optional.of(75);
        }
        return Optional.of(19 + random.nextInt(65));
    }

    private static Optional<String> residence(SplittableRandom random) {
        int roll = random.nextInt(100);
        if (roll < 3) {
            return Optional.empty();
        }
        if (roll < 10) {
            return Optional.of(REFUSED_RESIDENCES.get(random.nextInt(REFUSED_RESIDENCES.size())));
        }
        return Optional.of(ACCEPTED_RESIDENCES.get(random.nextInt(ACCEPTED_RESIDENCES.size())));
    }

    private static String risk(SplittableRandom random) {
        int roll = random.nextInt(100);
        if (roll < 70) {
            return "NOT_ASSESSED";
        }
        if (roll < 80) {
            return "LOW";
        }
        if (roll < 87) {
            return "WATCH";
        }
        return roll < 94 ? "HIGH" : "SEVERE";
    }

    private static Bureau bureau(SplittableRandom random, CreditProduct product, int epoch, long requested, long outstanding,
            long[][] maximumExposure) {
        Answer answer = answer(random, 7, 4, 2);
        long score = switch (random.nextInt(12)) {
            case 0 -> 550;
            case 1 -> 650;
            case 2 -> 750;
            default -> 300 + random.nextInt(551);
        };
        long accounts = random.nextInt(10) == 0 ? 0 : random.nextInt(10);
        long delinquencies = weighted(random, 80, 4);
        long defaults = weighted(random, 90, 3);
        boolean insolvent = random.nextInt(100) < 2;
        long obligations = random.nextLong(80_000);
        long balance = balance(random);
        long limit = maximumExposure[epoch - 1][product.ordinal()];
        if (random.nextInt(25) == 0 && limit - requested - outstanding >= 0) {
            balance = limit - requested - outstanding; // the exposure headroom at exactly zero
        }
        Set<CreditAttributeCode> absent = EnumSet.noneOf(CreditAttributeCode.class);
        if (answer == Answer.PARTIAL) {
            List<CreditAttributeCode> codes = new ArrayList<>(List.of(CreditAttributeCode.BUREAU_EXTERNAL_SCORE,
                    CreditAttributeCode.BUREAU_ACTIVE_ACCOUNTS, CreditAttributeCode.BUREAU_DELINQUENCIES_24M,
                    CreditAttributeCode.BUREAU_DEFAULTS_72M, CreditAttributeCode.BUREAU_INSOLVENCY_FLAG,
                    CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS, CreditAttributeCode.BUREAU_TOTAL_BALANCE));
            int missing = 1 + random.nextInt(2);
            for (int i = 0; i < missing; i++) {
                absent.add(codes.remove(random.nextInt(codes.size())));
            }
        }
        return new Bureau(answer, score, accounts, delinquencies, defaults, insolvent, obligations, balance, absent);
    }

    /** Mostly a modest bureau balance, a quarter larger, a few past most limits. */
    private static long balance(SplittableRandom random) {
        int roll = random.nextInt(100);
        if (roll < 60) {
            return random.nextLong(300_000);
        }
        return roll < 85 ? 300_000 + random.nextLong(1_200_000) : 1_500_000 + random.nextLong(2_500_000);
    }

    private static FinancialData financialData(SplittableRandom random) {
        Answer answer = answer(random, 5, 3, 2);
        long income = 150_000 + random.nextLong(1_050_000);
        long committed = 20_000 + random.nextLong(280_000);
        Set<CreditAttributeCode> absent = EnumSet.noneOf(CreditAttributeCode.class);
        if (answer == Answer.PARTIAL) {
            absent.add(random.nextBoolean() ? CreditAttributeCode.FINDATA_MONTHLY_INCOME
                    : CreditAttributeCode.FINDATA_MONTHLY_COMMITTED_EXPENDITURE);
        }
        return new FinancialData(answer, income, committed, absent);
    }

    /** {@code partial}, {@code foreign} and {@code unavailable} percent; the rest received. */
    private static Answer answer(SplittableRandom random, int partial, int foreign, int unavailable) {
        int roll = random.nextInt(100);
        if (roll < partial) {
            return Answer.PARTIAL;
        }
        if (roll < partial + foreign) {
            return Answer.FOREIGN;
        }
        return roll < partial + foreign + unavailable ? Answer.UNAVAILABLE : Answer.RECEIVED;
    }

    /** {@code zeroPercent} zero, else 1 to {@code maximum}. */
    private static long weighted(SplittableRandom random, int zeroPercent, int maximum) {
        return random.nextInt(100) < zeroPercent ? 0 : 1 + random.nextInt(maximum);
    }
}
