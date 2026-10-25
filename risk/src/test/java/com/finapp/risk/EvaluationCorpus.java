package com.finapp.risk;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;

/**
 * A seeded corpus of evaluations over the policy v1 fixtures (`P13-TSK-003`): for every fixture operation, inputs
 * generated from one {@link Random} - whose sequence the JDK specifies, so the same seed gives the same corpus in
 * any JVM - each reading present or absent at random, the figures spread across every threshold the fixtures name.
 * The properties test, the coverage test and the two-JVM digest all draw from here; the battery at scale is
 * `P13-TST-002`'s.
 *
 * <p>Nothing here iterates a {@code Set.of} or a {@code Map.of}: their order is salted per process, and a corpus
 * that depended on it would differ between the JVMs it exists to compare.
 */
final class EvaluationCorpus {

    static final long SEED = 13_003L;

    /** The corridors the generator draws from: the fixtures' high-risk three in a fixed order, and two benign. */
    private static final List<String> CORRIDORS;

    static {
        List<String> corridors = new ArrayList<>(new TreeSet<>(RiskPolicyFixtures.HIGH_RISK_CORRIDORS));
        corridors.add("EUR-USD-US");
        corridors.add("EUR-GBP-GB");
        CORRIDORS = List.copyOf(corridors);
    }

    private EvaluationCorpus() {}

    /** One generated case: the inputs and the fixture they are judged under. */
    record Case(int index, EvaluationInputs inputs) {
        RiskPolicy policy() {
            return RiskPolicyFixtures.v1(inputs.operation());
        }
    }

    /** {@code perOperation} cases for each fixture operation, in generation order. */
    static List<Case> generate(long seed, int perOperation) {
        Random random = new Random(seed);
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < perOperation; i++) {
            for (RiskOperation operation : RiskPolicyFixtures.operations()) {
                cases.add(new Case(cases.size(), inputs(random, operation, false)));
            }
        }
        return List.copyOf(cases);
    }

    /** Inputs for {@code operation} with every reading present - no rule can come out {@code ABSENT}. */
    static EvaluationInputs filled(Random random, RiskOperation operation) {
        return inputs(random, operation, true);
    }

    /** Inputs for {@code operation}; with {@code everyReading} each reading is present, else present four times in five. */
    static EvaluationInputs inputs(Random random, RiskOperation operation, boolean everyReading) {
        EvaluationInputs.Builder builder = EvaluationInputs.builder(operation);
        builder.subject(SignalSubject.of(SubjectKind.PARTY, uuid(random)));
        builder.subject(SignalSubject.of(SubjectKind.ACCOUNT, uuid(random)));
        if (present(random, everyReading)) {
            builder.subject(new SignalSubject(SubjectKind.DEVICE, hash(random)));
        }
        if (operation.judgesAMovement() || present(random, everyReading)) {
            builder.amount(Money.ofMinorUnits(amount(random), RiskPolicyFixtures.EUR));
        }
        for (VelocityDimension dimension : VelocityDimension.values()) {
            for (WindowKind window : WindowKind.values()) {
                if (present(random, everyReading)) {
                    builder.velocity(dimension, window, count(random, 120), sum(random));
                }
            }
        }
        if (present(random, everyReading)) {
            builder.payeeFirstSeen(firstSeen(random));
        }
        if (present(random, everyReading)) {
            builder.deviceFirstSeen(firstSeen(random));
        }
        if (present(random, everyReading)) {
            builder.sessionAssurance(random.nextBoolean() ? RiskAssurance.MULTI_FACTOR : RiskAssurance.SINGLE_FACTOR);
        }
        for (WindowKind window : WindowKind.values()) {
            if (present(random, everyReading)) {
                builder.authFailures(window, count(random, 6));
            }
        }
        if (present(random, everyReading)) {
            builder.credentialChange(lastSeen(random));
        }
        if (present(random, everyReading)) {
            builder.recovery(lastSeen(random));
        }
        if (present(random, everyReading)) {
            builder.accountAgeDays(random.nextInt(400));
        }
        if (present(random, everyReading)) {
            builder.kycApproved(random.nextInt(5) != 0);
        }
        if (present(random, everyReading)) {
            builder.screeningHit(random.nextInt(8) == 0);
        }
        if (present(random, everyReading)) {
            builder.profileRating(RiskRating.values()[random.nextInt(RiskRating.values().length)]);
        }
        if (present(random, everyReading)) {
            builder.merchantSuspended(random.nextInt(6) == 0);
        }
        if (present(random, everyReading)) {
            builder.corridor(CORRIDORS.get(random.nextInt(CORRIDORS.size())));
        }
        if (operation == RiskOperation.MONITORING || operation == RiskOperation.CARD_PAYMENT
                || operation == RiskOperation.WALLET_PAYMENT) {
            // P13-TSK-014: a checkout payment seals its merchant's ratios too (the other monitoring readings never).
            builder.monitoring(monitoring(random, everyReading));
        }
        return builder.build();
    }

    private static MonitoringReadings monitoring(Random random, boolean everyReading) {
        Map<WindowKind, Flow> flows = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Amounts> amounts = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Ratio> refunds = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Ratio> chargebacks = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Long> bnpl = new EnumMap<>(WindowKind.class);
        for (WindowKind window : WindowKind.values()) {
            if (present(random, everyReading)) {
                flows.put(window, new Flow(sum(random), sum(random)));
            }
            if (present(random, everyReading)) {
                int n = random.nextInt(8);
                long[] minor = new long[n];
                for (int i = 0; i < n; i++) {
                    minor[i] = random.nextInt(3_000_00);
                }
                amounts.put(window, Amounts.of(minor));
            }
            if (present(random, everyReading)) {
                refunds.put(window, new Ratio(count(random, 30), count(random, 100)));
            }
            if (present(random, everyReading)) {
                chargebacks.put(window, new Ratio(count(random, 5), count(random, 100)));
            }
            if (present(random, everyReading)) {
                bnpl.put(window, count(random, 6));
            }
        }
        Reading<Integer> dormant = present(random, everyReading) ? Reading.of(random.nextInt(200)) : Reading.absent();
        return new MonitoringReadings(dormant, flows, amounts, refunds, chargebacks, bnpl);
    }

    private static boolean present(Random random, boolean everyReading) {
        return everyReading || random.nextInt(5) != 0;
    }

    /** Mostly small, sometimes past the EUR 10,000 deny band. */
    private static long amount(Random random) {
        return random.nextInt(10) == 0 ? random.nextInt(50_000_00) : random.nextInt(2_000_00);
    }

    private static long sum(Random random) {
        return random.nextInt(10) == 0 ? random.nextInt(60_000_00) : random.nextInt(6_000_00);
    }

    private static long count(Random random, int bound) {
        return random.nextInt(bound);
    }

    private static FirstSeen firstSeen(Random random) {
        return random.nextInt(3) == 0 ? FirstSeen.never() : FirstSeen.daysAgo(random.nextInt(120));
    }

    private static LastSeen lastSeen(Random random) {
        return random.nextInt(3) == 0 ? LastSeen.never() : LastSeen.hoursAgo(random.nextInt(200));
    }

    private static UUID uuid(Random random) {
        return new UUID(random.nextLong(), random.nextLong());
    }

    private static String hash(Random random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
