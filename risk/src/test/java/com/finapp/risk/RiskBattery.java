package com.finapp.risk;

import com.finapp.risk.RiskBatteryOracles.Band;
import com.finapp.risk.RiskBatteryOracles.Edge;
import com.finapp.risk.RiskBatteryOracles.Expected;
import com.finapp.risk.RiskBatteryOracles.Judged;
import com.finapp.risk.RiskBatteryOracles.Line;
import com.finapp.risk.RiskBatteryOracles.Statement;
import com.finapp.risk.RiskBatteryWorld.Cohort;
import com.finapp.risk.RiskBatteryWorld.Evaluation;
import com.finapp.risk.RiskBatteryWorld.Generated;
import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.IntStream;

/**
 * The policy battery's run (`P13-TST-002`; {@code PHASE_13_PLAN.md} section 13.3; ADR-0109 decisions 3-4;
 * {@code INV-RSK-01}, {@code INV-RSK-04}, {@code INV-RSK-05}, {@code INV-MON-01}) - computed once per JVM and read by
 * {@code RiskPolicyBatteryTest}, {@code RiskBatteryCoverageTest} and {@code RiskBatteryFlipTest}.
 *
 * <p>For every subject {@link RiskBatteryWorld} generates, every evaluation goes through what production does with it:
 * {@code RiskEngineV1} under the pinned v1 fixture (or the fail-safe {@code RiskEvaluation} records for its cause), the
 * seal exactly as T-eval writes it ({@link RiskAssessment}: the canonical inputs text, both digests) or as the profile
 * records a sweep's ({@link CustomerRiskProfileVersion.Evaluation}), the replay ({@link AssessmentReplay}, `-004`'s
 * {@code IDENTICAL}) and the explanation ({@link Explanation}) - and every verdict is compared, rule by rule, with
 * {@link RiskBatteryOracles}' independent one. Then one perturbation per subject ({@link #perturb}), the version-swap
 * slice, the control (every seal replayed under the battery's own statement), the coverage counts and the digests: one
 * line per subject, which a second JVM must write identically ({@link RiskBatteryInAnotherJvm}).
 *
 * <p>Hermetic (ADR-0028 tiers by requirement): nothing but the JVM. The size is {@code FINAPP_RISK_BATTERY_SUBJECTS} for a
 * probe's quick run only - a shrunk run fails the census by design, so it can never pass for the gate's.
 */
final class RiskBattery {

    /** How many subjects: 10,000 unless a probe shrinks it (and fails the census). */
    static final int SUBJECTS = size("FINAPP_RISK_BATTERY_SUBJECTS", 10_000);

    /** One subject in ten (by the mix) is also replayed whole under its operations' successor versions. */
    static final int SLICE = 10;

    /** The population's fingerprint - every sealed inputs digest in order - at {@link RiskBatteryWorld#SEED}, full size. */
    static final String POPULATION = "2c22367d07f8a3aef6d3b29ec7dc21e9c2d9d5d07bd6dce38dc560b5c2eef60d";

    /** Each v1 version's fingerprint: its content as this battery renders it, then every verdict digest under it. */
    static final Map<RiskOperation, String> VERSIONS = versions(
            "186361e3691c08cf35d8d11df289265282412b414cfc2372e12001706c0e8ed4", // TRANSFER v1
            "27c4d4ea2ec084428fa6fffeaee8057561c1d52dc02ddfe1d8f1c52621196708", // WITHDRAWAL v1
            "9a47cb4cc8eca4b112bf212374177b1fcf3e408f7c604dc6e0653e0f2d493f92", // CARD_PAYMENT v1
            "4d3d9beb46fbdf83d810a62c28f625e6645d25a8af3a06a02efa1393b178afac", // WALLET_PAYMENT v1
            "7957f2f88fccfb72edeefe4b0a983a000b6c0f2162ffbdc5a14696f86d3e6a80", // CROSS_BORDER v1
            "88abda474d4e1a5a262e75926bcc8384de973849ce594b37927a075d8a574102"); // MONITORING v1

    /** The battery's fingerprint over the population's and the six versions' - what the activation gate records. */
    static final String FINGERPRINT = "d470990578800637e77550116ba5147c77704b02c587e84f99d74a27566683c8";

    /** The perturbations, one per subject. */
    enum Perturbation {
        READING,
        PARAMETER,
        BAND,
        VERSION
    }

    /**
     * A perturbed seal's replay. {@code flipped}: the replay answered {@code DIVERGED} (or the sweep's digest moved);
     * {@code namesItsRow}: it diverged on exactly the row the perturbation changed - the rule's ordinal, or the decision
     * alone with the explanation naming the band moved - not merely on something (the Phase 11 gate's finding);
     * {@code agrees}: the engine's verdict under the perturbation equals the oracle's.
     */
    record Flip(Perturbation kind, RiskOperation operation, String version, Optional<RuleKind> rule, String what,
            boolean flipped, boolean namesItsRow, boolean agrees, String detail) {

        boolean holds() {
            return flipped && namesItsRow && agrees;
        }

        @Override
        public String toString() {
            return kind + " " + what + " -> flipped=" + flipped + " namesItsRow=" + namesItsRow + " agrees=" + agrees
                    + " (" + detail + ")";
        }
    }

    /** One subject's version-swap slice: per operation, rewritten / predicted / diverged, and anything not as predicted. */
    record Swap(Map<RiskOperation, long[]> counts, List<String> wrong) {}

    /** What one subject's evaluations came to - the line both JVMs write, and the digests the fingerprints are built of. */
    record Summary(String line, String inputsDigest, List<String> verdictDigests, int judged, int monitored, int failSafes,
            int identical, int agreed) {}

    /** What one subject came to in this JVM. */
    record Outcome(int index, Cohort cohort, Summary summary, List<String> oracle, List<String> replays,
            List<String> explanations, List<String> controls, List<String> windowStarts, Optional<Flip> flip,
            Optional<Swap> swap, int evaluations, long nanos) {}

    /** The run, once per JVM. */
    record Run(int subjects, List<Outcome> outcomes, List<String> ours, List<String> theirs, String population,
            Map<RiskOperation, String> versions, String fingerprint, Map<String, Long> coverage, long wallMillis,
            long parentMillis, long childMillis) {}

    private static Run run;

    private RiskBattery() {}

    /** The run - computed on the first call in this JVM, the second JVM beside it. */
    static synchronized Run run() throws Exception {
        if (run == null) {
            run = compute();
        }
        return run;
    }

    // ------------------------------------------------------------------ the policies

    /** The pinned v1 fixtures - the data under test. */
    static final Map<RiskOperation, RiskPolicy> FIXTURES = new EnumMap<>(RiskOperation.class);

    /** The successor versions, translated from the battery's statements. */
    static final Map<RiskOperation, RiskPolicy> SUCCESSORS = new EnumMap<>(RiskOperation.class);

    static {
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            FIXTURES.put(operation, RiskPolicyFixtures.v1(operation));
            SUCCESSORS.put(operation, policy(RiskBatteryOracles.v2(operation)));
        }
    }

    /** The battery's own statements of the v1 versions, translated - empty where one is no valid policy at all. */
    private static final Map<RiskOperation, Optional<RiskPolicy>> STATED = new EnumMap<>(RiskOperation.class);

    static {
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            Optional<RiskPolicy> stated;
            try {
                stated = Optional.of(policy(RiskBatteryOracles.v1(operation)));
            } catch (RiskPolicy.PolicyInvalid | IllegalArgumentException invalid) {
                stated = Optional.empty();
            }
            STATED.put(operation, stated);
        }
    }

    /** The battery's statement of {@code operation}'s v1 translated - empty when it is not a valid policy at all. */
    static Optional<RiskPolicy> stated(RiskOperation operation) {
        return STATED.get(operation);
    }

    /** The pinned version's id - one per operation, as a stored version's would be. */
    static RiskPolicyVersionId versionId(RiskOperation operation) {
        return RiskPolicyVersionId.of(uuid7(0x1330_2000_0000_0000L, operation.ordinal()));
    }

    /** A UUIDv7-shaped identifier from two numbers (ADR-0013: every entity id is a v7) - deterministic, never random. */
    static UUID uuid7(long high, long low) {
        return new UUID((high & ~0xF000L) | 0x7000L, (low & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L);
    }

    /**
     * A statement translated into the production policy model - its constructors judge it whole, so a statement that is no
     * valid version is refused here exactly as the policy door would refuse it.
     */
    static RiskPolicy policy(Statement statement) {
        List<RiskRule> rules = new ArrayList<>();
        for (Line line : statement.lines()) {
            Map<String, ParameterValue> values = new TreeMap<>();
            Set<String> declared = new TreeSet<>();
            for (RuleKind.Parameter parameter : line.kind().parameters()) {
                declared.add(parameter.name());
                Object value = line.parameters().get(parameter.name());
                if (value == null) {
                    throw new RiskPolicy.PolicyInvalid(line.kind() + " states no '" + parameter.name() + "'");
                }
                values.put(parameter.name(), value(parameter.type(), value));
            }
            for (String name : line.parameters().keySet()) {
                if (!declared.contains(name)) {
                    throw new RiskPolicy.PolicyInvalid(line.kind() + " declares no '" + name + "'");
                }
            }
            rules.add(new RiskRule(line.ordinal(), line.kind(), RuleParameters.of(line.kind(), values), line.weight(),
                    line.hard()));
        }
        List<ScoreBand> scoreBands = new ArrayList<>();
        for (Band<RiskDecision> band : statement.scoreBands()) {
            scoreBands.add(new ScoreBand(band.from(), band.to(), band.answer()));
        }
        List<RatingBand> ratingBands = new ArrayList<>();
        for (Band<RiskRating> band : statement.ratingBands()) {
            ratingBands.add(new RatingBand(band.from(), band.to(), band.answer()));
        }
        List<FailSafeBand> failSafe = new ArrayList<>();
        for (Edge edge : statement.failSafe()) {
            failSafe.add(new FailSafeBand(edge.fromMinor(), edge.toMinor(), edge.decision()));
        }
        Optional<Money> threshold = statement.overrideThresholdMinor().isPresent()
                ? Optional.of(Money.ofMinorUnits(statement.overrideThresholdMinor().getAsLong(), RiskBatteryWorld.EUR))
                : Optional.empty();
        Optional<Duration> hold = statement.holdHours().isPresent()
                ? Optional.of(Duration.ofHours(statement.holdHours().getAsInt())) : Optional.empty();
        return new RiskPolicy(statement.operation(), RiskEngine.RISK_ENGINE_V1, RiskBatteryWorld.EUR, rules, scoreBands,
                ratingBands, failSafe, statement.challenge(), threshold, hold);
    }

    private static ParameterValue value(RuleKind.ParameterType type, Object value) {
        return switch (type) {
            case COUNT -> new ParameterValue.Count(((Number) value).longValue());
            case MINOR_AMOUNT -> new ParameterValue.MinorAmount(((Number) value).longValue());
            case BASIS_POINTS -> new ParameterValue.BasisPoints(((Number) value).intValue());
            case DAYS -> new ParameterValue.Days(((Number) value).intValue());
            case HOURS -> new ParameterValue.Hours(((Number) value).intValue());
            case WINDOW -> new ParameterValue.Window((WindowKind) value);
            case VELOCITY_DIMENSION -> new ParameterValue.Dimension((VelocityDimension) value);
            case CORRIDOR_SET -> {
                Set<String> codes = new TreeSet<>();
                for (Object code : (Set<?>) value) {
                    codes.add((String) code);
                }
                yield new ParameterValue.Corridors(codes);
            }
            case ASSURANCE -> new ParameterValue.Assurance((RiskAssurance) value);
            case RATING -> new ParameterValue.Rating((RiskRating) value);
        };
    }

    /**
     * A policy version's content as one canonical text - the battery's own rendering, every rule, parameter, weight, hard
     * decision, band, fail-safe band and knob in a fixed order - what a version fingerprint binds (owner item 69: the
     * version row carries no content digest of its own).
     */
    static String content(RiskPolicy policy) {
        StringBuilder out = new StringBuilder("policy|").append(policy.operation()).append('|').append(policy.engine())
                .append('|').append(policy.currency().code());
        for (RiskRule rule : policy.rules()) {
            out.append("|rule:").append(rule.ordinal()).append(':').append(rule.kind());
            for (String name : new TreeSet<>(rule.parameters().values().keySet())) {
                out.append(':').append(name).append('=').append(text(rule.parameters().value(name)));
            }
            out.append(":w=").append(rule.weight().isPresent() ? Integer.toString(rule.weight().getAsInt()) : "-")
                    .append(":h=").append(rule.hardDecision().map(Enum::name).orElse("-"));
        }
        for (ScoreBand band : policy.scoreBands()) {
            out.append("|band:").append(band.from()).append('-').append(band.to()).append(':').append(band.decision());
        }
        for (RatingBand band : policy.ratingBands()) {
            out.append("|rating:").append(band.from()).append('-').append(band.to()).append(':').append(band.rating());
        }
        for (FailSafeBand band : policy.failSafeBands()) {
            out.append("|failsafe:").append(band.fromMinor()).append('-')
                    .append(band.toMinor().isPresent() ? Long.toString(band.toMinor().getAsLong()) : "*").append(':')
                    .append(band.decision());
        }
        out.append("|challenge:").append(policy.challengeAssurance().map(Enum::name).orElse("-"))
                .append("|override:").append(policy.overrideThreshold()
                        .map(money -> money.minorUnits() + ":" + money.currency().code()).orElse("-"))
                .append("|hold:").append(policy.holdValidity().map(hold -> Long.toString(hold.toHours())).orElse("-"));
        return out.toString();
    }

    private static String text(ParameterValue value) {
        return switch (value) {
            case ParameterValue.Count count -> Long.toString(count.value());
            case ParameterValue.MinorAmount amount -> Long.toString(amount.value());
            case ParameterValue.BasisPoints points -> Integer.toString(points.value());
            case ParameterValue.Days days -> Integer.toString(days.value());
            case ParameterValue.Hours hours -> Integer.toString(hours.value());
            case ParameterValue.Window window -> window.value().name();
            case ParameterValue.Dimension dimension -> dimension.value().name();
            case ParameterValue.Corridors corridors -> String.join(",", corridors.sorted());
            case ParameterValue.Assurance assurance -> assurance.value().name();
            case ParameterValue.Rating rating -> rating.value().name();
        };
    }

    // ------------------------------------------------------------------ the run

    private static Run compute() throws Exception {
        long begun = System.nanoTime();
        SecondJvm.Started child = SecondJvm.start(RiskBatteryInAnotherJvm.class, "1g", Integer.toString(SUBJECTS));
        List<Outcome> outcomes;
        Coverage coverage = new Coverage();
        long parentNanos;
        try {
            outcomes = IntStream.range(0, SUBJECTS).parallel().mapToObj(index -> outcome(index, coverage)).toList();
            parentNanos = System.nanoTime() - begun;
        } catch (RuntimeException | Error failed) {
            child.abandon(); // this JVM's pass failed: no orphaned child, no temporary files
            throw failed;
        }
        List<String> theirs = child.join(Duration.ofMinutes(30));
        long wallNanos = System.nanoTime() - begun;
        List<Summary> summaries = outcomes.stream().map(Outcome::summary).toList();
        List<String> ours = lines(summaries);
        Fingerprints fingerprints = fingerprints(summaries);
        Map<String, Long> counted = coverage.snapshot();
        Run computed = new Run(SUBJECTS, outcomes, ours, theirs, fingerprints.population(), fingerprints.versions(),
                fingerprints.fingerprint(), counted, millis(wallNanos), millis(parentNanos),
                millis(child.elapsedNanos()));
        System.out.println("P13-TST-002 TIMING: wall=" + computed.wallMillis() + "ms parentPass=" + computed.parentMillis()
                + "ms secondJvm=" + computed.childMillis() + "ms threadSummed="
                + millis(outcomes.stream().mapToLong(Outcome::nanos).sum()) + "ms processors="
                + Runtime.getRuntime().availableProcessors());
        System.out.println("P13-TST-002 FINGERPRINT: seed=" + RiskBatteryWorld.SEED + " subjects=" + SUBJECTS
                + " population=" + fingerprints.population() + " fingerprint=" + fingerprints.fingerprint());
        fingerprints.versions().forEach((operation, value) -> System.out.println("P13-TST-002 VERSION: "
                + RiskBatteryOracles.v1(operation).name() + " content=" + CanonicalEvaluation.sha256(content(
                        FIXTURES.get(operation))) + " fingerprint=" + value));
        System.out.println("P13-TST-002 CENSUS: " + census(outcomes));
        return computed;
    }

    /** The lines both JVMs write: one per subject, then the totals with every fingerprint. */
    static List<String> lines(List<Summary> summaries) {
        List<String> lines = new ArrayList<>(summaries.size() + 1);
        long judged = 0;
        long monitored = 0;
        long failSafes = 0;
        long identical = 0;
        long agreed = 0;
        for (Summary summary : summaries) {
            lines.add(summary.line());
            judged += summary.judged();
            monitored += summary.monitored();
            failSafes += summary.failSafes();
            identical += summary.identical();
            agreed += summary.agreed();
        }
        Fingerprints fingerprints = fingerprints(summaries);
        StringBuilder totals = new StringBuilder("subjects=").append(summaries.size()).append(" judged=").append(judged)
                .append(" monitoring=").append(monitored).append(" failSafes=").append(failSafes).append(" identical=")
                .append(identical).append(" agreed=").append(agreed).append(" population=")
                .append(fingerprints.population());
        fingerprints.versions().forEach((operation, value) -> totals.append(' ').append(operation).append('=')
                .append(value));
        totals.append(" fingerprint=").append(fingerprints.fingerprint());
        lines.add(totals.toString());
        return lines;
    }

    record Fingerprints(String population, Map<RiskOperation, String> versions, String fingerprint) {}

    static Fingerprints fingerprints(List<Summary> summaries) {
        MessageDigest population = sha256();
        for (Summary summary : summaries) {
            population.update((summary.inputsDigest() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String populationHex = HexFormat.of().formatHex(population.digest());
        Map<RiskOperation, String> versions = new EnumMap<>(RiskOperation.class);
        MessageDigest all = sha256();
        all.update((populationHex + "\n").getBytes(StandardCharsets.UTF_8));
        for (int op = 0; op < RiskBatteryOracles.OPERATIONS.size(); op++) {
            RiskOperation operation = RiskBatteryOracles.OPERATIONS.get(op);
            MessageDigest version = sha256();
            version.update((content(FIXTURES.get(operation)) + "\n").getBytes(StandardCharsets.UTF_8));
            for (Summary summary : summaries) {
                version.update((summary.verdictDigests().get(op) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            String hex = HexFormat.of().formatHex(version.digest());
            versions.put(operation, hex);
            all.update((operation + "=" + hex + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return new Fingerprints(populationHex, Collections.unmodifiableMap(versions),
                HexFormat.of().formatHex(all.digest()));
    }

    // ------------------------------------------------------------------ one subject

    /** One evaluation sealed and replayed: what the perturbations start from. */
    record Sealed(Evaluation evaluation, Verdict verdict, String inputsText, String verdictSha,
            Optional<RiskAssessment> assessment) {}

    /** The second JVM's share: the subject generated, every verdict sealed and replayed, its line - nothing else. */
    static Summary summary(int index) {
        return pass(RiskBatteryWorld.generate(index), null, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), null);
    }

    private static Outcome outcome(int index, Coverage coverage) {
        long begun = System.nanoTime();
        Generated generated = RiskBatteryWorld.generate(index);
        List<String> oracle = new ArrayList<>();
        List<String> replays = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        List<String> controls = new ArrayList<>();
        List<String> windowStarts = new ArrayList<>();
        List<Sealed> sealed = new ArrayList<>();
        Summary summary = pass(generated, coverage, oracle, replays, explanations, controls, windowStarts, sealed);
        Optional<Flip> flip = perturb(index, sealed);
        flip.ifPresent(found -> {
            coverage.add("perturbation|" + found.kind());
            coverage.add("perturbed|" + found.version());
            coverage.add("perturbedCohort|" + generated.cohort());
        });
        Optional<Swap> swap = pick(index, 24, SLICE) == 0 ? Optional.of(swap(sealed)) : Optional.empty();
        return new Outcome(index, generated.cohort(), summary, List.copyOf(oracle), List.copyOf(replays),
                List.copyOf(explanations), List.copyOf(controls), List.copyOf(windowStarts), flip, swap,
                generated.evaluations().size(), System.nanoTime() - begun);
    }

    /**
     * Every evaluation of {@code generated} through the engine, the seal, the replay and the explanation, compared with
     * the oracle - {@code coverage} and the control only in this JVM's full pass ({@code coverage} non-null).
     */
    private static Summary pass(Generated generated, Coverage coverage, List<String> oracle, List<String> replays,
            List<String> explanations, List<String> controls, List<String> windowStarts, List<Sealed> sealedOut) {
        int index = generated.index();
        boolean full = coverage != null;
        MessageDigest inputs = sha256();
        List<MessageDigest> verdicts = new ArrayList<>();
        for (int i = 0; i < RiskBatteryOracles.OPERATIONS.size(); i++) {
            verdicts.add(sha256());
        }
        int judged = 0;
        int monitored = 0;
        int failSafes = 0;
        int identical = 0;
        int agreed = 0;
        for (Evaluation evaluation : generated.evaluations()) {
            String name = "subject " + index + " #" + evaluation.seq() + " "
                    + RiskBatteryOracles.v1(evaluation.operation()).name();
            if (full) {
                for (WindowKind window : WindowKind.values()) {
                    if (!WindowStarts.start(window, evaluation.at()).equals(RiskBatteryWorld.start(window,
                            evaluation.at()))) {
                        windowStarts.add(name + " " + window);
                    }
                }
            }
            RiskPolicy fixture = FIXTURES.get(evaluation.operation());
            Verdict verdict;
            try {
                verdict = verdict(evaluation, fixture);
            } catch (RuntimeException refused) {
                oracle.add(name + ": the engine refused where the oracle judged - " + refused.getClass().getSimpleName()
                        + " " + refused.getMessage());
                continue;
            }
            List<String> differences = differences(verdict, evaluation.expected());
            if (evaluation.mustRefuse().isPresent()) {
                String refusal = refusal(fixture, evaluation.evaluated());
                if (!refusal.equals("CURRENCY_MISMATCH")) {
                    differences.add("the engine must refuse the currency, answered " + refusal);
                }
            }
            if (differences.isEmpty()) {
                agreed++;
            } else {
                differences.forEach(difference -> oracle.add(name + ": " + difference));
            }
            String inputsText = CanonicalEvaluation.render(evaluation.sealed());
            String verdictText = CanonicalEvaluation.render(verdict);
            String verdictSha = CanonicalEvaluation.sha256(verdictText);
            inputs.update((inputsText + "\n").getBytes(StandardCharsets.UTF_8));
            verdicts.get(RiskBatteryOracles.OPERATIONS.indexOf(evaluation.operation()))
                    .update((verdictText + "\n").getBytes(StandardCharsets.UTF_8));
            Optional<RiskAssessment> assessment = Optional.empty();
            if (evaluation.monitoring()) {
                monitored++;
                if (replayMonitoring(fixture, inputsText, verdictSha, verdict)) {
                    identical++;
                } else {
                    replays.add(name + ": the sweep's seal does not re-evaluate to its digest");
                }
                if (full) {
                    stated(RiskOperation.MONITORING).ifPresentOrElse(stated -> {
                        if (!replayMonitoring(stated, inputsText, verdictSha, verdict)) {
                            controls.add(name + ": the battery's own statement re-evaluates to another digest");
                        }
                    }, () -> controls.add(name + ": the battery's statement is no valid version"));
                }
            } else {
                judged++;
                failSafes += evaluation.failSafe() ? 1 : 0;
                try {
                    RiskAssessment seal = seal(index, evaluation, verdict, inputsText, verdictSha);
                    assessment = Optional.of(seal);
                    Optional<RiskPolicy> pinned = seal.pin().isPresent() ? Optional.of(fixture) : Optional.empty();
                    AssessmentReplay.Replay replay = AssessmentReplay.replay(seal, pinned);
                    if (replay.verdict() == AssessmentReplay.Outcome.IDENTICAL) {
                        identical++;
                    } else {
                        replays.add(name + ": " + replay.diverged() + " first rule " + replay.firstDivergedRule());
                    }
                    if (full) {
                        explain(seal, pinned, evaluation.expected()).ifPresent(wrong -> explanations.add(name + ": "
                                + wrong));
                        if (pinned.isPresent()) {
                            Optional<RiskPolicy> stated = stated(evaluation.operation());
                            AssessmentReplay.Replay control = stated.isPresent()
                                    ? AssessmentReplay.replay(seal, stated) : null;
                            if (control == null || control.verdict() != AssessmentReplay.Outcome.IDENTICAL) {
                                controls.add(name + ": under the battery's own statement "
                                        + (control == null ? "no valid version" : control.diverged() + " first rule "
                                                + control.firstDivergedRule()));
                            }
                        }
                    }
                } catch (IllegalArgumentException unsealable) {
                    replays.add(name + ": the seal is refused - " + unsealable.getMessage());
                }
            }
            if (full) {
                sealedOut.add(new Sealed(evaluation, verdict, inputsText, verdictSha, assessment));
                count(coverage, evaluation);
            }
        }
        List<String> verdictDigests = new ArrayList<>();
        MessageDigest all = sha256();
        for (MessageDigest digest : verdicts) {
            String hex = HexFormat.of().formatHex(digest.digest());
            verdictDigests.add(hex);
            all.update(hex.getBytes(StandardCharsets.UTF_8));
        }
        String inputsDigest = HexFormat.of().formatHex(inputs.digest());
        String line = index + " " + generated.cohort() + " judged=" + judged + " monitoring=" + monitored + " failSafe="
                + failSafes + " identical=" + identical + " agreed=" + agreed + " inputs=" + inputsDigest + " verdicts="
                + HexFormat.of().formatHex(all.digest());
        return new Summary(line, inputsDigest, List.copyOf(verdictDigests), judged, monitored, failSafes, identical,
                agreed);
    }

    /** The verdict production records for {@code evaluation}: the engine's, or the fail-safe its cause names. */
    static Verdict verdict(Evaluation evaluation, RiskPolicy fixture) {
        if (evaluation.cause().isEmpty()) {
            return RiskEngineV1.evaluate(fixture, evaluation.evaluated());
        }
        return switch (evaluation.cause().get()) {
            case NO_POLICY -> RiskEngineV1.failSafe(evaluation.operation());
            case PIN_UNREADABLE -> RiskEngineV1.failSafeUnpinned(evaluation.operation());
            case READ_FAILED, TIMEOUT, ENGINE_REFUSED -> RiskEvaluation.band(fixture, evaluation.amount());
        };
    }

    /** How the engine refuses {@code inputs} - its cause's name, or {@code NONE} when it does not. */
    static String refusal(RiskPolicy policy, EvaluationInputs inputs) {
        try {
            RiskEngineV1.evaluate(policy, inputs);
            return "NONE";
        } catch (EvaluationFailed failed) {
            return failed.failure().name();
        }
    }

    /** Where {@code verdict} differs from {@code expected} - rule by rule, then the score, decision, rating, reasons. */
    static List<String> differences(Verdict verdict, Expected expected) {
        List<String> differences = new ArrayList<>();
        if (expected.isRefused()) {
            differences.add("the oracle refuses (" + expected.refusal().get() + "), the engine answered");
            return differences;
        }
        if (verdict.failSafeApplied() != (expected.step() == RiskBatteryOracles.Step.FAIL_SAFE)) {
            differences.add("fail-safe engine=" + verdict.failSafeApplied());
        }
        if (verdict.hits().size() != expected.rules().size()) {
            differences.add("hits engine=" + verdict.hits().size() + " oracle=" + expected.rules().size());
        } else {
            for (int i = 0; i < verdict.hits().size(); i++) {
                RuleHit hit = verdict.hits().get(i);
                Judged rule = expected.rules().get(i);
                if (hit.ordinal() != rule.ordinal() || hit.kind() != rule.kind() || hit.outcome() != rule.outcome()
                        || hit.contribution() != rule.contribution() || !hit.hardDecision().equals(rule.hard())) {
                    differences.add("rule " + rule.ordinal() + " " + rule.kind() + " engine=" + hit.outcome() + "/"
                            + hit.contribution() + "/" + hit.hardDecision().map(Enum::name).orElse("-") + " oracle="
                            + rule.outcome() + "/" + rule.contribution() + "/" + rule.hard().map(Enum::name).orElse("-"));
                }
                if ((hit.outcome() == RuleOutcome.ABSENT) != hit.judged().equals("ABSENT")) {
                    differences.add("rule " + rule.ordinal() + " judged token does not say what was read");
                }
                if (!rule.evidence().isEmpty() && !rule.evidence().equals(evidence(hit.judged()))) {
                    differences.add("rule " + rule.ordinal() + " " + rule.kind() + " token's figures engine="
                            + evidence(hit.judged()) + " oracle=" + rule.evidence());
                }
            }
        }
        if (verdict.score().value() != expected.score()) {
            differences.add("score engine=" + verdict.score().value() + " oracle=" + expected.score());
        }
        if (!verdict.decision().equals(expected.decision())) {
            differences.add("decision engine=" + verdict.decision() + " oracle=" + expected.decision());
        }
        if (!verdict.rating().equals(expected.rating())) {
            differences.add("rating engine=" + verdict.rating() + " oracle=" + expected.rating());
        }
        List<String> reasons = new ArrayList<>();
        verdict.reasons().forEach(reason -> reasons.add(reason.name()));
        List<String> wanted = new ArrayList<>();
        if (expected.failSafeReason().isPresent()) {
            wanted.add(expected.failSafeReason().get());
        } else {
            expected.reasons().forEach(kind -> wanted.add(kind.name()));
        }
        if (!reasons.equals(wanted)) {
            differences.add("reasons engine=" + reasons + " oracle=" + wanted);
        }
        return differences;
    }

    /** {@code STRUCTURING}'s token's count and sum below the threshold ({@code ...,below=N,belowSum=S}), or empty. */
    static List<Long> evidence(String token) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(",below=(\\d+),belowSum=(\\d+)$")
                .matcher(token);
        return matcher.find() ? List.of(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2))) : List.of();
    }

    /**
     * {@code evaluation} sealed as T-eval seals it: every field, both digests - the ENGINE's decision, with the challenge
     * answered and the windows' outcome decided by the battery's own rule from that decision (never production's
     * {@code RiskEvaluation.challenge}, which the replay then re-derives and must agree with). A seal always records what
     * the engine said, so a verdict the oracle disputes is still sealed, replayed and controlled - and named twice.
     */
    static RiskAssessment seal(int index, Evaluation evaluation, Verdict verdict, String inputsText, String verdictSha) {
        RiskDecision decision = verdict.decision().orElseThrow();
        Optional<Boolean> challenge = RiskBatteryOracles.challengeAnswer(RiskBatteryOracles.v1(evaluation.operation()),
                decision, evaluation.sealed());
        boolean permits = decision == RiskDecision.ALLOW || challenge.orElse(false);
        boolean pinned = evaluation.cause().map(cause -> !cause.unpinned()).orElse(true);
        return new RiskAssessment(
                RiskAssessmentId.of(uuid7(0x1330_2000_0000_0000L | ((long) index << 16), evaluation.seq())),
                evaluation.operation(),
                CommandRef.of("battery-" + index + "-" + evaluation.seq()),
                evaluation.subjects(),
                evaluation.amount(),
                pinned ? Optional.of(new RiskAssessment.Pin(versionId(evaluation.operation()), 1)) : Optional.empty(),
                verdict.engine(),
                verdict.score(),
                decision,
                evaluation.cause(),
                challenge,
                permits ? VelocityOutcome.COUNTED : VelocityOutcome.NOT_PERMITTED,
                permits ? Optional.of(evaluation.at()) : Optional.empty(),
                inputsText,
                CanonicalEvaluation.sha256(inputsText),
                verdictSha,
                verdict.hits(),
                verdict.reasons(),
                0L,
                "battery-" + index,
                "battery-" + index + "-" + evaluation.seq(),
                Optional.of(evaluation.at()));
    }

    /** The sweep's seal re-evaluated from its text alone, as the profile's recorded evaluation is: the same digest. */
    static boolean replayMonitoring(RiskPolicy policy, String inputsText, String verdictSha, Verdict sealed) {
        CustomerRiskProfileVersion.Evaluation recorded = new CustomerRiskProfileVersion.Evaluation(
                versionId(RiskOperation.MONITORING), 1, RiskEngine.RISK_ENGINE_V1, sealed.score().value(), inputsText,
                CanonicalEvaluation.sha256(inputsText));
        Verdict again = RiskEngineV1.evaluate(policy, CanonicalEvaluation.parseInputs(recorded.inputsCanonical()));
        return CanonicalEvaluation.sha256(CanonicalEvaluation.render(again)).equals(verdictSha)
                && again.score().value() == recorded.signalScore() && again.rating().equals(sealed.rating());
    }

    /** What the explanation must name: the oracle's step, deciding rules and band - empty when it does. */
    static Optional<String> explain(RiskAssessment seal, Optional<RiskPolicy> pinned, Expected expected) {
        Explanation explanation = Explanation.of(seal, pinned);
        String step = explanation.step().name();
        if (!step.equals(expected.step().name())) {
            return Optional.of("step explanation=" + step + " oracle=" + expected.step());
        }
        if (!explanation.decidingRules().equals(expected.deciding())) {
            return Optional.of("deciding explanation=" + explanation.decidingRules() + " oracle=" + expected.deciding());
        }
        if (expected.step() == RiskBatteryOracles.Step.FAIL_SAFE) {
            int position = explanation.failSafe().flatMap(Explanation.FailSafe::band).map(Explanation.Band::position)
                    .orElse(0);
            if (position != expected.bandPosition()) {
                return Optional.of("fail-safe band explanation=" + position + " oracle=" + expected.bandPosition());
            }
            return Optional.empty();
        }
        int position = explanation.band().map(Explanation.Band::position).orElse(0);
        return position == expected.bandPosition() ? Optional.empty()
                : Optional.of("band explanation=" + position + " oracle=" + expected.bandPosition());
    }

    // ------------------------------------------------------------------ coverage

    /** Order-free counts - every key a fact of the population, summed over every subject. */
    static final class Coverage {
        private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

        void add(String key) {
            counts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
        }

        Map<String, Long> snapshot() {
            Map<String, Long> ordered = new TreeMap<>();
            counts.forEach((key, adder) -> ordered.put(key, adder.sum()));
            return Collections.unmodifiableMap(ordered);
        }
    }

    /** What {@code evaluation} covers - by its oracle's verdict and its sealed readings, never the engine's. */
    private static void count(Coverage coverage, Evaluation evaluation) {
        Statement statement = RiskBatteryOracles.v1(evaluation.operation());
        String version = statement.name();
        Expected expected = evaluation.expected();
        evaluation.tags().forEach(tag -> coverage.add(tag));
        coverage.add("evaluated|" + version);
        if (evaluation.failSafe()) {
            UnavailableCause cause = evaluation.cause().get();
            coverage.add("failSafe|" + evaluation.operation() + "|" + cause);
            if (cause.unpinned()) {
                coverage.add("compiled|" + evaluation.operation() + "|" + cause);
            } else if (!evaluation.amount().currency().code().equals(RiskBatteryOracles.CURRENCY)) {
                coverage.add("unplaced|" + evaluation.operation());
            } else {
                coverage.add("failSafeBand|" + evaluation.operation() + "|" + expected.bandPosition());
                for (Edge edge : statement.failSafe()) {
                    long delta = evaluation.amount().minorUnits() - edge.fromMinor();
                    if (edge.fromMinor() > 0 && (delta == -1 || delta == 0)) {
                        coverage.add("failSafeEdge|" + evaluation.operation() + "|" + delta);
                    }
                }
            }
            if (evaluation.mustRefuse().isPresent()) {
                coverage.add("refused|" + evaluation.operation() + "|" + evaluation.mustRefuse().get());
            }
            // Kept apart from the evaluated decisions: a fail-safe's decision is its band's, never a rule's or a score's.
            coverage.add("failSafeDecision|" + evaluation.operation() + "|" + expected.decision().orElseThrow());
            return;
        }
        long sum = 0;
        for (Judged rule : expected.rules()) {
            coverage.add(rule.outcome().name().toLowerCase(java.util.Locale.ROOT) + "|" + version + "|" + rule.ordinal());
            if (rule.outcome() == RuleOutcome.FIRED) {
                coverage.add("kindFired|" + rule.kind());
            }
            Line line = statement.line(rule.ordinal());
            if (line.weight().isPresent() && rule.outcome() == RuleOutcome.FIRED) {
                sum += line.weight().getAsInt();
            }
            edges(coverage, version, line, evaluation.evaluated());
        }
        coverage.add("score|" + version + "|" + expected.score());
        if (sum > RiskBatteryOracles.CEILING) {
            coverage.add("clamped|" + version);
        }
        if (expected.decision().isPresent()) {
            coverage.add("decision|" + version + "|" + expected.decision().get());
            coverage.add("step|" + version + "|" + expected.step());
            coverage.add("decided|" + version + "|" + expected.decision().get() + "|" + expected.step());
            expected.challengeSatisfied().ifPresent(satisfied -> coverage.add("challenge|" + version + "|" + satisfied));
        }
        expected.rating().ifPresent(rating -> coverage.add("rating|" + version + "|" + rating));
        evaluation.evaluated().corridor().value().ifPresent(corridor -> coverage.add("corridor|" + version + "|"
                + corridor));
        if (evaluation.operation() == RiskOperation.WITHDRAWAL && expected.rules().get(1).outcome() == RuleOutcome.FIRED
                && expected.decision().equals(Optional.of(RiskDecision.HOLD))) {
            coverage.add("ato|hold");
        }
    }

    /** The edges {@code line}'s reading sits at: its threshold minus one and the threshold itself (or one above). */
    private static void edges(Coverage coverage, String version, Line line, EvaluationInputs in) {
        String key = "edge|" + version + "|" + line.ordinal() + "|";
        switch (line.kind()) {
            case AMOUNT_BAND -> in.amount().value().ifPresent(money -> delta(coverage, key,
                    money.minorUnits() - line.number("threshold_minor"), true));
            case VELOCITY_COUNT, VELOCITY_SUM -> {
                VelocityReading reading = in.velocity().get(new VelocityKey(line.value("dimension",
                        VelocityDimension.class), line.value("window", WindowKind.class)));
                if (reading != null) {
                    delta(coverage, key, line.kind() == RuleKind.VELOCITY_COUNT
                            ? reading.count() - line.number("threshold_count")
                            : reading.sumMinor() - line.number("threshold_minor"), false);
                }
            }
            case NEW_PAYEE -> in.payeeFirstSeen().value().ifPresent(seen -> {
                if (seen.daysAgo().isPresent()) {
                    delta(coverage, key, seen.daysAgo().getAsInt() - line.number("lookback_days"), false);
                }
            });
            case NEW_DEVICE -> in.deviceFirstSeen().value().ifPresent(seen -> {
                if (seen.daysAgo().isPresent()) {
                    delta(coverage, key, seen.daysAgo().getAsInt() - line.number("lookback_days"), false);
                }
            });
            case RECENT_CREDENTIAL_CHANGE -> in.credentialChange().value().ifPresent(last -> {
                if (last.hoursAgo().isPresent()) {
                    delta(coverage, key, last.hoursAgo().getAsLong() - line.number("lookback_hours"), false);
                }
            });
            case RECENT_RECOVERY -> in.recovery().value().ifPresent(last -> {
                if (last.hoursAgo().isPresent()) {
                    delta(coverage, key, last.hoursAgo().getAsLong() - line.number("lookback_hours"), false);
                }
            });
            case ACCOUNT_AGE_BELOW -> in.accountAgeDays().value().ifPresent(age -> delta(coverage, key,
                    age - line.number("days"), false));
            case RECENT_AUTH_FAILURES -> {
                Long failures = in.authFailures().get(line.value("window", WindowKind.class));
                if (failures != null) {
                    delta(coverage, key, failures - line.number("threshold_count"), false);
                }
            }
            case DORMANT_THEN_ACTIVE -> in.monitoring().dormantDays().value().ifPresent(days -> delta(coverage, key,
                    days - line.number("dormant_days"), false));
            case STRUCTURING -> {
                Amounts amounts = in.monitoring().amounts().get(line.value("window", WindowKind.class));
                if (amounts != null) {
                    long below = line.number("below_minor");
                    long sum = 0;
                    for (long minor : amounts.minor()) {
                        sum += minor < below ? minor : 0;
                        if (minor == below) {
                            coverage.add(key + "below=0");
                        }
                    }
                    long over = sum - line.number("sum_minor");
                    if (over == 0 || over == 1) {
                        coverage.add(key + "sum=" + over);
                    }
                }
            }
            case BNPL_MULTI_APPLICATION -> {
                Long count = in.monitoring().bnplApplications().get(line.value("window", WindowKind.class));
                if (count != null) {
                    delta(coverage, key, count - line.number("threshold_count"), false);
                }
            }
            case RAPID_IN_OUT -> {
                Flow flow = in.monitoring().flows().get(line.value("window", WindowKind.class));
                if (flow != null && flow.inMinor() > 0) {
                    long margin = flow.outMinor() * 10_000L - line.number("ratio_bps") * flow.inMinor();
                    if (margin == 0) {
                        coverage.add(key + "share=0");
                    } else if (margin < 0 && margin >= -10_000L) {
                        coverage.add(key + "share=-1"); // one minor unit out short of the share
                    }
                }
            }
            case REFUND_RATIO, CHARGEBACK_RATIO -> {
                Ratio ratio = (line.kind() == RuleKind.REFUND_RATIO ? in.monitoring().refunds()
                        : in.monitoring().chargebacks()).get(line.value("window", WindowKind.class));
                if (ratio != null && ratio.denominator() > 0) {
                    long floor = line.number("threshold_count");
                    long margin = ratio.numerator() * 10_000L - line.number("ratio_bps") * ratio.denominator();
                    boolean shareMet = margin >= 0;
                    if (ratio.numerator() >= floor && margin == 0) {
                        coverage.add(key + "share=0"); // exactly the share, the count met
                    } else if (ratio.numerator() >= floor && margin < 0 && margin + 10_000L >= 0) {
                        coverage.add(key + "share=-1"); // one more refund or chargeback would meet the share
                    }
                    if (shareMet && ratio.numerator() == floor) {
                        coverage.add(key + "count=0"); // exactly the count floor, the share met
                    } else if (shareMet && ratio.numerator() == floor - 1) {
                        coverage.add(key + "count=-1"); // one short of the count floor, the share met
                    }
                }
            }
            case ROUND_AMOUNT -> in.amount().value().ifPresent(money -> {
                long amount = money.minorUnits();
                long modulus = line.number("modulus_minor");
                if (amount > 0 && amount % modulus == 0) {
                    coverage.add(key + "round=0");
                } else if ((amount + 1) % modulus == 0) {
                    coverage.add(key + "round=-1");
                } else if (amount > modulus && amount % modulus == 1) {
                    coverage.add(key + "round=1");
                }
            });
            default -> {
                // The boolean and set kinds have no edge; their fired and not-fired counts are the coverage.
            }
        }
    }

    private static void delta(Coverage coverage, String key, long delta, boolean above) {
        if (delta == -1 || delta == 0 || (above && delta == 1)) {
            coverage.add(key + delta);
        }
    }

    // ------------------------------------------------------------------ the perturbations

    /** The subject's one perturbation: the kind rotated by the mix, the target drawn by the mix among those that apply. */
    static Optional<Flip> perturb(int index, List<Sealed> sealed) {
        Perturbation[] kinds = Perturbation.values();
        int first = pick(index, 0, kinds.length);
        for (int k = 0; k < kinds.length; k++) {
            Perturbation kind = kinds[(first + k) % kinds.length];
            List<Candidate> candidates = candidates(kind, sealed);
            if (candidates.isEmpty()) {
                continue;
            }
            int start = pick(index, 8, candidates.size());
            for (int c = 0; c < candidates.size(); c++) {
                Optional<Flip> flip = apply(candidates.get((start + c) % candidates.size()));
                if (flip.isPresent()) {
                    return flip;
                }
            }
        }
        return Optional.empty();
    }

    /** A perturbation that may apply: the seal and the rule (or band option) - verified only when drawn. */
    private record Candidate(Perturbation kind, Sealed target, int ordinal, int option) {}

    private static List<Candidate> candidates(Perturbation kind, List<Sealed> sealed) {
        List<Candidate> candidates = new ArrayList<>();
        for (Sealed target : sealed) {
            Evaluation evaluation = target.evaluation();
            boolean judgedBySeal = target.assessment().isPresent();
            if (!evaluation.monitoring() && !judgedBySeal) {
                continue; // a judged evaluation whose seal was refused is already a failure
            }
            Expected expected = evaluation.expected();
            Statement statement = RiskBatteryOracles.v1(evaluation.operation());
            switch (kind) {
                case READING, PARAMETER -> {
                    if (evaluation.failSafe()) {
                        continue;
                    }
                    for (RiskBatteryOracles.Judged rule : expected.rules()) {
                        if (rule.outcome() == RuleOutcome.ABSENT) {
                            continue;
                        }
                        if (kind == Perturbation.READING && !firstReader(statement, rule.ordinal())) {
                            continue;
                        }
                        candidates.add(new Candidate(kind, target, rule.ordinal(), 0));
                    }
                }
                case BAND -> {
                    if (evaluation.failSafe()) {
                        if (!evaluation.cause().get().unpinned()) {
                            candidates.add(new Candidate(kind, target, 0, 0));
                        }
                    } else {
                        candidates.add(new Candidate(kind, target, 0, -1));
                        candidates.add(new Candidate(kind, target, 0, 1));
                    }
                }
                case VERSION -> {
                    if (!evaluation.failSafe() || !evaluation.cause().get().unpinned()) {
                        candidates.add(new Candidate(kind, target, 0, 0));
                    }
                }
            }
        }
        return candidates;
    }

    /** The perturbation applied and replayed - empty when it does not apply (no flipping value, an invalid version). */
    private static Optional<Flip> apply(Candidate candidate) {
        Sealed target = candidate.target();
        Evaluation evaluation = target.evaluation();
        Statement statement = RiskBatteryOracles.v1(evaluation.operation());
        String version = statement.name();
        Expected expected = evaluation.expected();
        switch (candidate.kind()) {
            case READING -> {
                Line line = statement.line(candidate.ordinal());
                Optional<EvaluationInputs> moved = Perturbations.reading(line, evaluation.evaluated());
                if (moved.isEmpty()) {
                    return Optional.empty();
                }
                Expected predicted = RiskBatteryOracles.judge(statement, moved.get());
                if (predicted.isRefused() || predicted.rules().get(line.ordinal() - 1).outcome()
                        == expected.rules().get(line.ordinal() - 1).outcome()) {
                    return Optional.empty();
                }
                String what = Perturbations.key(line) + " of " + version + " rule " + line.ordinal() + " " + line.kind();
                RiskPolicy fixture = FIXTURES.get(evaluation.operation());
                boolean agrees = differences(RiskEngineV1.evaluate(fixture, moved.get()), predicted).isEmpty();
                String text = CanonicalEvaluation.render(moved.get());
                if (evaluation.monitoring()) {
                    Verdict again = RiskEngineV1.evaluate(fixture, CanonicalEvaluation.parseInputs(text));
                    return Optional.of(monitoringFlip(Perturbation.READING, evaluation, version, what, target, again,
                            line.ordinal(), agrees));
                }
                RiskAssessment forged = withInputs(target.assessment().orElseThrow(), text);
                AssessmentReplay.Replay replay = AssessmentReplay.replay(forged, Optional.of(fixture));
                return Optional.of(judgedFlip(Perturbation.READING, evaluation, version, what, replay, line.ordinal(),
                        agrees));
            }
            case PARAMETER -> {
                Line line = statement.line(candidate.ordinal());
                Optional<Line> changed = Perturbations.parameter(line, evaluation.evaluated());
                if (changed.isEmpty()) {
                    return Optional.empty();
                }
                Statement perturbed = statement.withLine(changed.get());
                RiskPolicy policy;
                try {
                    policy = policy(perturbed);
                } catch (RiskPolicy.PolicyInvalid | IllegalArgumentException invalid) {
                    return Optional.empty();
                }
                Expected predicted = RiskBatteryOracles.judge(perturbed, evaluation.evaluated());
                if (predicted.isRefused() || predicted.rules().get(line.ordinal() - 1).outcome()
                        == expected.rules().get(line.ordinal() - 1).outcome()) {
                    return Optional.empty();
                }
                String what = Perturbations.describe(line, changed.get()) + " of " + version + " rule " + line.ordinal()
                        + " " + line.kind();
                Verdict engine = RiskEngineV1.evaluate(policy, evaluation.evaluated());
                boolean agrees = differences(engine, predicted).isEmpty();
                if (evaluation.monitoring()) {
                    Verdict again = RiskEngineV1.evaluate(policy, CanonicalEvaluation.parseInputs(target.inputsText()));
                    return Optional.of(monitoringFlip(Perturbation.PARAMETER, evaluation, version, what, target, again,
                            line.ordinal(), agrees));
                }
                AssessmentReplay.Replay replay = AssessmentReplay.replay(target.assessment().orElseThrow(),
                        Optional.of(policy));
                return Optional.of(judgedFlip(Perturbation.PARAMETER, evaluation, version, what, replay, line.ordinal(),
                        agrees));
            }
            case BAND -> {
                return band(candidate, target, statement);
            }
            case VERSION -> {
                Statement successor = RiskBatteryOracles.v2(evaluation.operation());
                Expected predicted = evaluation.failSafe()
                        ? RiskBatteryOracles.failSafe(Optional.of(successor), evaluation.operation(),
                                evaluation.cause().get(), evaluation.amount())
                        : RiskBatteryOracles.judge(successor, evaluation.evaluated());
                if (predicted.isRefused() || predicted.sameVerdict(expected)) {
                    return Optional.empty();
                }
                RiskPolicy policy = SUCCESSORS.get(evaluation.operation());
                int first = expected.firstDifference(predicted);
                String what = version + " swapped for " + successor.name()
                        + (first > 0 ? " - rule " + first + " judged otherwise" : " - the decision otherwise");
                Verdict engine = evaluation.failSafe() ? RiskEvaluation.band(policy, evaluation.amount())
                        : RiskEngineV1.evaluate(policy, evaluation.evaluated());
                boolean agrees = differences(engine, predicted).isEmpty();
                if (evaluation.monitoring()) {
                    Verdict again = RiskEngineV1.evaluate(policy, CanonicalEvaluation.parseInputs(target.inputsText()));
                    return Optional.of(monitoringFlip(Perturbation.VERSION, evaluation, version, what, target, again,
                            first, agrees));
                }
                AssessmentReplay.Replay replay = AssessmentReplay.replay(target.assessment().orElseThrow(),
                        Optional.of(policy));
                if (first > 0) {
                    return Optional.of(judgedFlip(Perturbation.VERSION, evaluation, version, what, replay, first, agrees));
                }
                return Optional.of(decisionFlip(Perturbation.VERSION, evaluation, version, what, replay, target,
                        policy, predicted, agrees));
            }
        }
        return Optional.empty();
    }

    /** A band moved under the score (or a fail-safe band's decision flipped) so the decision or rating changes. */
    private static Optional<Flip> band(Candidate candidate, Sealed target, Statement statement) {
        Evaluation evaluation = target.evaluation();
        Expected expected = evaluation.expected();
        String version = statement.name();
        Statement perturbed;
        String what;
        if (evaluation.failSafe()) {
            int position = expected.bandPosition();
            List<Edge> edges = new ArrayList<>(statement.failSafe());
            Edge edge = edges.get(position - 1);
            RiskDecision flipped = edge.decision() == RiskDecision.ALLOW ? RiskDecision.DENY : RiskDecision.ALLOW;
            edges.set(position - 1, new Edge(edge.fromMinor(), edge.toMinor(), flipped));
            perturbed = statement.withFailSafe(edges);
            what = "fail-safe band " + position + " of " + version + " " + edge.decision() + "->" + flipped;
        } else if (evaluation.monitoring()) {
            Optional<List<Band<RiskRating>>> moved = Perturbations.moved(statement.ratingBands(), expected.score(),
                    candidate.option());
            if (moved.isEmpty()) {
                return Optional.empty();
            }
            perturbed = statement.withRatingBands(moved.get());
            what = "rating band " + expected.bandPosition() + (candidate.option() < 0 ? " lowered" : " raised")
                    + " past the score of " + version;
        } else {
            Optional<List<Band<RiskDecision>>> moved = Perturbations.moved(statement.scoreBands(), expected.score(),
                    candidate.option());
            if (moved.isEmpty()) {
                return Optional.empty();
            }
            perturbed = statement.withScoreBands(moved.get());
            what = "score band " + expected.bandPosition() + (candidate.option() < 0 ? " lowered" : " raised")
                    + " past the score of " + version;
        }
        RiskPolicy policy;
        try {
            policy = policy(perturbed);
        } catch (RiskPolicy.PolicyInvalid | IllegalArgumentException invalid) {
            return Optional.empty();
        }
        Expected predicted = evaluation.failSafe()
                ? RiskBatteryOracles.failSafe(Optional.of(perturbed), evaluation.operation(), evaluation.cause().get(),
                        evaluation.amount())
                : RiskBatteryOracles.judge(perturbed, evaluation.evaluated());
        if (predicted.isRefused() || predicted.decision().equals(expected.decision())
                && predicted.rating().equals(expected.rating())) {
            return Optional.empty();
        }
        Verdict engine = evaluation.failSafe() ? RiskEvaluation.band(policy, evaluation.amount())
                : RiskEngineV1.evaluate(policy, evaluation.evaluated());
        boolean agrees = differences(engine, predicted).isEmpty();
        if (evaluation.monitoring()) {
            Verdict again = RiskEngineV1.evaluate(policy, CanonicalEvaluation.parseInputs(target.inputsText()));
            boolean flipped = !CanonicalEvaluation.sha256(CanonicalEvaluation.render(again)).equals(target.verdictSha());
            boolean names = again.hits().equals(target.verdict().hits()) && again.score().equals(target.verdict().score())
                    && !again.rating().equals(target.verdict().rating())
                    && RiskBatteryOracles.position(perturbed.ratingBands(), again.score().value())
                            == predicted.bandPosition();
            return Optional.of(new Flip(Perturbation.BAND, evaluation.operation(), version, Optional.empty(), what,
                    flipped, names, agrees,
                    "rating " + target.verdict().rating().orElseThrow() + "->" + again.rating().orElseThrow()));
        }
        AssessmentReplay.Replay replay = AssessmentReplay.replay(target.assessment().orElseThrow(), Optional.of(policy));
        return Optional.of(decisionFlip(Perturbation.BAND, evaluation, version, what, replay, target, policy, predicted,
                agrees));
    }

    /** A judged replay that must diverge on the hits, its first diverged rule {@code ordinal}. */
    private static Flip judgedFlip(Perturbation kind, Evaluation evaluation, String version, String what,
            AssessmentReplay.Replay replay, int ordinal, boolean agrees) {
        boolean flipped = replay.verdict() == AssessmentReplay.Outcome.DIVERGED;
        boolean names = replay.diverged().contains(AssessmentReplay.Divergence.HITS)
                && replay.firstDivergedRule().equals(OptionalInt.of(ordinal));
        return new Flip(kind, evaluation.operation(), version, Optional.of(ruleKind(evaluation, ordinal)), what, flipped,
                names, agrees, replay.verdict() + " " + replay.diverged() + " first rule " + replay.firstDivergedRule());
    }

    /**
     * A judged replay that must diverge on the decision alone (and the challenge's answer with it) - the hits and score
     * equal - with the explanation under the perturbed version naming the band the battery moved the score into.
     */
    private static Flip decisionFlip(Perturbation kind, Evaluation evaluation, String version, String what,
            AssessmentReplay.Replay replay, Sealed target, RiskPolicy policy, Expected predicted, boolean agrees) {
        boolean flipped = replay.verdict() == AssessmentReplay.Outcome.DIVERGED;
        Set<AssessmentReplay.Divergence> allowed = Set.of(AssessmentReplay.Divergence.DECISION,
                AssessmentReplay.Divergence.CHALLENGE);
        Explanation explanation = Explanation.of(target.assessment().orElseThrow(), Optional.of(policy));
        int position = evaluation.failSafe()
                ? explanation.failSafe().flatMap(Explanation.FailSafe::band).map(Explanation.Band::position).orElse(0)
                : explanation.band().map(Explanation.Band::position).orElse(0);
        Optional<RiskDecision> named = evaluation.failSafe()
                ? explanation.failSafe().flatMap(Explanation.FailSafe::band).map(Explanation.Band::decision)
                : explanation.band().map(Explanation.Band::decision);
        boolean names = replay.diverged().contains(AssessmentReplay.Divergence.DECISION)
                && allowed.containsAll(replay.diverged()) && position == predicted.bandPosition()
                && (evaluation.failSafe() ? named.equals(predicted.decision()) : named.isPresent());
        return new Flip(kind, evaluation.operation(), version, Optional.empty(), what, flipped, names, agrees,
                replay.verdict() + " " + replay.diverged() + " band " + position + " oracle band "
                        + predicted.bandPosition());
    }

    /** A sweep's seal re-evaluated under the perturbation: its digest moved, its first differing hit {@code ordinal}. */
    private static Flip monitoringFlip(Perturbation kind, Evaluation evaluation, String version, String what,
            Sealed target, Verdict again, int ordinal, boolean agrees) {
        boolean flipped = !CanonicalEvaluation.sha256(CanonicalEvaluation.render(again)).equals(target.verdictSha());
        int first = 0;
        List<RuleHit> sealed = target.verdict().hits();
        for (int i = 0; i < Math.min(sealed.size(), again.hits().size()); i++) {
            if (!sealed.get(i).equals(again.hits().get(i))) {
                first = i + 1;
                break;
            }
        }
        boolean names = ordinal > 0 ? first == ordinal : first == 0 && !again.rating().equals(target.verdict().rating());
        return new Flip(kind, evaluation.operation(), version,
                ordinal > 0 ? Optional.of(ruleKind(evaluation, ordinal)) : Optional.empty(), what, flipped, names, agrees,
                "first differing hit " + first + " rating " + target.verdict().rating().orElseThrow() + "->"
                        + again.rating().orElseThrow());
    }

    private static RuleKind ruleKind(Evaluation evaluation, int ordinal) {
        return RiskBatteryOracles.v1(evaluation.operation()).line(ordinal).kind();
    }

    /** {@code sealed} with another inputs text, re-sealed with that text's own digest - a forger's edit. */
    static RiskAssessment withInputs(RiskAssessment sealed, String text) {
        return new RiskAssessment(sealed.id(), sealed.operation(), sealed.commandRef(), sealed.subjects(),
                sealed.amount(), sealed.pin(), sealed.engine(), sealed.score(), sealed.decision(), sealed.unavailable(),
                sealed.challengeSatisfied(), sealed.velocity(), sealed.cutAt(), text, CanonicalEvaluation.sha256(text),
                sealed.verdictSha256(), sealed.hits(), sealed.reasons(), sealed.latencyMicros(), sealed.correlationId(),
                sealed.causationId(), sealed.assessedAt());
    }

    /** True when {@code ordinal} is the first rule of {@code statement} reading its reading. */
    private static boolean firstReader(Statement statement, int ordinal) {
        String key = Perturbations.key(statement.line(ordinal));
        for (Line line : statement.lines()) {
            if (line.ordinal() >= ordinal) {
                return true;
            }
            if (Perturbations.reads(line).contains(key)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ the version-swap slice

    /** Every seal of a subject replayed under its operation's successor: diverged exactly where the oracle predicts. */
    private static Swap swap(List<Sealed> sealed) {
        Map<RiskOperation, long[]> counts = new EnumMap<>(RiskOperation.class);
        List<String> wrong = new ArrayList<>();
        for (Sealed target : sealed) {
            Evaluation evaluation = target.evaluation();
            if (evaluation.failSafe() && evaluation.cause().get().unpinned()) {
                continue;
            }
            Statement successor = RiskBatteryOracles.v2(evaluation.operation());
            Expected predicted = evaluation.failSafe()
                    ? RiskBatteryOracles.failSafe(Optional.of(successor), evaluation.operation(), evaluation.cause().get(),
                            evaluation.amount())
                    : RiskBatteryOracles.judge(successor, evaluation.evaluated());
            boolean diverges = !predicted.sameVerdict(evaluation.expected());
            int first = evaluation.expected().firstDifference(predicted);
            RiskPolicy policy = SUCCESSORS.get(evaluation.operation());
            boolean diverged;
            boolean named;
            if (evaluation.monitoring()) {
                Verdict again = RiskEngineV1.evaluate(policy, CanonicalEvaluation.parseInputs(target.inputsText()));
                diverged = !CanonicalEvaluation.sha256(CanonicalEvaluation.render(again)).equals(target.verdictSha());
                int differing = 0;
                for (int i = 0; i < again.hits().size(); i++) {
                    if (!again.hits().get(i).equals(target.verdict().hits().get(i))) {
                        differing = i + 1;
                        break;
                    }
                }
                named = differing == first;
            } else {
                if (target.assessment().isEmpty()) {
                    continue;
                }
                AssessmentReplay.Replay replay = AssessmentReplay.replay(target.assessment().get(), Optional.of(policy));
                diverged = replay.verdict() == AssessmentReplay.Outcome.DIVERGED;
                named = first > 0 ? replay.firstDivergedRule().equals(OptionalInt.of(first))
                        : !replay.diverged().contains(AssessmentReplay.Divergence.HITS);
            }
            long[] count = counts.computeIfAbsent(evaluation.operation(), ignored -> new long[3]);
            count[0]++;
            count[1] += diverges ? 1 : 0;
            count[2] += diverged ? 1 : 0;
            if (diverged != diverges || (diverged && !named)) {
                wrong.add(RiskBatteryOracles.v1(evaluation.operation()).name() + " #" + evaluation.seq() + " predicted="
                        + diverges + " diverged=" + diverged + " first=" + first);
            }
        }
        return new Swap(counts, wrong);
    }

    // ------------------------------------------------------------------ the selections (the mix, never the walk)

    /**
     * A stable 64-bit mix of a subject's index (splitmix64's finaliser). Every selection - the perturbation's kind and
     * target, the swapped slice - is taken from it and never from the index itself: the generator walks the index (the
     * cohort on {@code index mod 12}, the variants on {@code index / 12}), so an index-modulo selection would cover a
     * lattice of the population (the Phase 11 gate's finding) where the mix covers all of it.
     */
    static long mix(int index) {
        long z = 0x5249534B42415454L * 0x9E3779B97F4A7C15L + index;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** {@code mix}'s bits from {@code shift}, reduced modulo {@code modulus} - never negative. */
    static int pick(int index, int shift, int modulus) {
        return (int) Math.floorMod(mix(index) >>> shift, (long) modulus);
    }

    // ------------------------------------------------------------------ plumbing

    static String census(List<Outcome> outcomes) {
        Map<String, Long> cohorts = new TreeMap<>();
        long evaluations = 0;
        for (Outcome outcome : outcomes) {
            cohorts.merge(outcome.cohort().name(), 1L, Long::sum);
            evaluations += outcome.evaluations();
        }
        Map<String, Long> flips = new TreeMap<>();
        outcomes.forEach(outcome -> outcome.flip().ifPresent(flip -> flips.merge(flip.kind().name(), 1L, Long::sum)));
        return "subjects=" + outcomes.size() + " evaluations=" + evaluations + " cohorts=" + cohorts + " perturbations="
                + flips + " swapped=" + outcomes.stream().filter(outcome -> outcome.swap().isPresent()).count();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java platform provides SHA-256", absent);
        }
    }

    private static Map<RiskOperation, String> versions(String... values) {
        Map<RiskOperation, String> versions = new EnumMap<>(RiskOperation.class);
        for (int i = 0; i < values.length; i++) {
            versions.put(RiskBatteryOracles.OPERATIONS.get(i), values[i]);
        }
        return Collections.unmodifiableMap(versions);
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }

    private static int size(String variable, int fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
    }

    // ------------------------------------------------------------------ the perturbations' arithmetic

    /** What each kind reads, and the value one step across its threshold - the battery's own, from the statement. */
    static final class Perturbations {

        private Perturbations() {}

        /** The reading {@code line} judges, as a key. */
        static String key(Line line) {
            return switch (line.kind()) {
                case AMOUNT_BAND, ROUND_AMOUNT -> "amount";
                case VELOCITY_COUNT -> "velocity:" + line.value("dimension", VelocityDimension.class) + ":"
                        + line.value("window", WindowKind.class) + ":count";
                case VELOCITY_SUM -> "velocity:" + line.value("dimension", VelocityDimension.class) + ":"
                        + line.value("window", WindowKind.class) + ":sum";
                case NEW_PAYEE -> "payee";
                case NEW_DEVICE -> "device";
                case SESSION_ASSURANCE_BELOW -> "session";
                case RECENT_AUTH_FAILURES -> "authFailures:" + line.value("window", WindowKind.class);
                case RECENT_CREDENTIAL_CHANGE -> "credentialChange";
                case RECENT_RECOVERY -> "recovery";
                case ACCOUNT_AGE_BELOW -> "accountAge";
                case KYC_NOT_APPROVED -> "kyc";
                case SCREENING_HIT -> "screening";
                case PROFILE_RATING -> "rating";
                case MERCHANT_SUSPENDED -> "merchantSuspended";
                case CORRIDOR_HIGH_RISK -> "corridor";
                case DORMANT_THEN_ACTIVE -> "dormant";
                case RAPID_IN_OUT -> "flow:" + line.value("window", WindowKind.class);
                case STRUCTURING -> "amounts:" + line.value("window", WindowKind.class);
                case REFUND_RATIO -> "refunds:" + line.value("window", WindowKind.class);
                case CHARGEBACK_RATIO -> "chargebacks:" + line.value("window", WindowKind.class);
                case BNPL_MULTI_APPLICATION -> "bnpl:" + line.value("window", WindowKind.class);
            };
        }

        /** Every reading {@code line} reads - {@code DORMANT_THEN_ACTIVE} also reads the party's sum in its window. */
        static Set<String> reads(Line line) {
            if (line.kind() == RuleKind.DORMANT_THEN_ACTIVE) {
                return Set.of("dormant", "velocity:PARTY:" + line.value("window", WindowKind.class) + ":sum");
            }
            return Set.of(key(line));
        }

        /** {@code in} with {@code line}'s reading moved one step across its threshold - empty when no value does it. */
        static Optional<EvaluationInputs> reading(Line line, EvaluationInputs in) {
            Inputs edit = new Inputs(in);
            switch (line.kind()) {
                case AMOUNT_BAND -> {
                    long amount = in.amount().value().orElseThrow().minorUnits();
                    long threshold = line.number("threshold_minor");
                    long moved = amount >= threshold ? threshold - 1 : threshold;
                    if (moved < 0) {
                        return Optional.empty();
                    }
                    edit.amount = Reading.of(Money.ofMinorUnits(moved, RiskBatteryWorld.EUR));
                }
                case ROUND_AMOUNT -> {
                    long amount = in.amount().value().orElseThrow().minorUnits();
                    long modulus = line.number("modulus_minor");
                    long moved = amount > 0 && amount % modulus == 0 ? amount + 1 : (amount / modulus + 1) * modulus;
                    edit.amount = Reading.of(Money.ofMinorUnits(moved, RiskBatteryWorld.EUR));
                }
                case VELOCITY_COUNT, VELOCITY_SUM -> {
                    VelocityKey key = new VelocityKey(line.value("dimension", VelocityDimension.class),
                            line.value("window", WindowKind.class));
                    VelocityReading reading = in.velocity().get(key);
                    boolean count = line.kind() == RuleKind.VELOCITY_COUNT;
                    long threshold = line.number(count ? "threshold_count" : "threshold_minor");
                    long figure = count ? reading.count() : reading.sumMinor();
                    long moved = figure >= threshold ? threshold - 1 : threshold;
                    if (moved < 0) {
                        return Optional.empty();
                    }
                    edit.velocity.put(key, count ? new VelocityReading(moved, reading.sumMinor())
                            : new VelocityReading(reading.count(), moved));
                }
                case NEW_PAYEE, NEW_DEVICE -> {
                    boolean payee = line.kind() == RuleKind.NEW_PAYEE;
                    FirstSeen seen = (payee ? in.payeeFirstSeen() : in.deviceFirstSeen()).value().orElseThrow();
                    long lookback = line.number("lookback_days");
                    boolean isNew = seen.daysAgo().isEmpty() || seen.daysAgo().getAsInt() < lookback;
                    if (!isNew && lookback < 1) {
                        return Optional.empty();
                    }
                    Reading<FirstSeen> moved = Reading.of(FirstSeen.daysAgo((int) (isNew ? lookback : lookback - 1)));
                    if (payee) {
                        edit.payeeFirstSeen = moved;
                    } else {
                        edit.deviceFirstSeen = moved;
                    }
                }
                case SESSION_ASSURANCE_BELOW -> edit.sessionAssurance = Reading.of(
                        in.sessionAssurance().value().orElseThrow() == RiskAssurance.MULTI_FACTOR
                                ? RiskAssurance.SINGLE_FACTOR : RiskAssurance.MULTI_FACTOR);
                case RECENT_AUTH_FAILURES -> {
                    WindowKind window = line.value("window", WindowKind.class);
                    long failures = in.authFailures().get(window);
                    long threshold = line.number("threshold_count");
                    long moved = failures >= threshold ? threshold - 1 : threshold;
                    if (moved < 0) {
                        return Optional.empty();
                    }
                    edit.authFailures.put(window, moved);
                }
                case RECENT_CREDENTIAL_CHANGE, RECENT_RECOVERY -> {
                    boolean credential = line.kind() == RuleKind.RECENT_CREDENTIAL_CHANGE;
                    LastSeen last = (credential ? in.credentialChange() : in.recovery()).value().orElseThrow();
                    long lookback = line.number("lookback_hours");
                    boolean recent = last.hoursAgo().isPresent() && last.hoursAgo().getAsLong() < lookback;
                    Reading<LastSeen> moved = Reading.of(LastSeen.hoursAgo(recent ? lookback : lookback - 1));
                    if (credential) {
                        edit.credentialChange = moved;
                    } else {
                        edit.recovery = moved;
                    }
                }
                case ACCOUNT_AGE_BELOW -> {
                    int age = in.accountAgeDays().value().orElseThrow();
                    long days = line.number("days");
                    long moved = age < days ? days : days - 1;
                    if (moved < 0) {
                        return Optional.empty();
                    }
                    edit.accountAgeDays = Reading.of((int) moved);
                }
                case KYC_NOT_APPROVED -> edit.kycApproved = Reading.of(!in.kycApproved().value().orElseThrow());
                case SCREENING_HIT -> edit.screeningHit = Reading.of(!in.screeningHit().value().orElseThrow());
                case MERCHANT_SUSPENDED -> edit.merchantSuspended = Reading.of(!in.merchantSuspended().value()
                        .orElseThrow());
                case PROFILE_RATING -> {
                    RiskRating floor = line.value("rating", RiskRating.class);
                    int position = RiskBatteryOracles.SEVERITY.indexOf(in.profileRating().value().orElseThrow());
                    int at = RiskBatteryOracles.SEVERITY.indexOf(floor);
                    if (position >= at && at == 0) {
                        return Optional.empty();
                    }
                    edit.profileRating = Reading.of(RiskBatteryOracles.SEVERITY.get(position >= at ? at - 1 : at));
                }
                case CORRIDOR_HIGH_RISK -> {
                    SortedSet<String> listed = new TreeSet<>();
                    for (Object code : (Set<?>) line.parameters().get("corridors")) {
                        listed.add((String) code);
                    }
                    String corridor = in.corridor().value().orElseThrow();
                    String moved = null;
                    if (listed.contains(corridor)) {
                        for (String code : RiskBatteryWorld.CORRIDORS) {
                            if (!listed.contains(code)) {
                                moved = code;
                                break;
                            }
                        }
                    } else {
                        moved = listed.first();
                    }
                    if (moved == null) {
                        return Optional.empty();
                    }
                    edit.corridor = Reading.of(moved);
                }
                case DORMANT_THEN_ACTIVE -> {
                    int dormant = in.monitoring().dormantDays().value().orElseThrow();
                    long days = line.number("dormant_days");
                    long moved = dormant >= days ? days - 1 : days;
                    if (moved < 0) {
                        return Optional.empty();
                    }
                    edit.dormantDays = Reading.of((int) moved);
                }
                case RAPID_IN_OUT -> {
                    WindowKind window = line.value("window", WindowKind.class);
                    Flow flow = in.monitoring().flows().get(window);
                    long bps = line.number("ratio_bps");
                    if (flow.inMinor() == 0 || bps * flow.inMinor() == 0) {
                        return Optional.empty();
                    }
                    boolean fired = flow.outMinor() * 10_000L >= bps * flow.inMinor();
                    long moved = fired ? (bps * flow.inMinor() - 1) / 10_000L : (bps * flow.inMinor() + 9_999L) / 10_000L;
                    edit.flows.put(window, new Flow(flow.inMinor(), moved));
                }
                case STRUCTURING -> {
                    WindowKind window = line.value("window", WindowKind.class);
                    long below = line.number("below_minor");
                    List<Long> moved = new ArrayList<>();
                    boolean fired = RiskBatteryOracles.outcome(line, in) == RuleOutcome.FIRED;
                    for (long minor : in.monitoring().amounts().get(window).minor()) {
                        if (!fired || minor >= below) {
                            moved.add(minor);
                        }
                    }
                    if (!fired) {
                        if (below < 1) {
                            return Optional.empty();
                        }
                        for (long n = 0; n < line.number("threshold_count"); n++) {
                            moved.add(below - 1);
                        }
                    }
                    edit.amounts.put(window, new Amounts(moved));
                }
                case REFUND_RATIO, CHARGEBACK_RATIO -> {
                    WindowKind window = line.value("window", WindowKind.class);
                    Map<WindowKind, Ratio> shares = line.kind() == RuleKind.REFUND_RATIO ? edit.refunds : edit.chargebacks;
                    Ratio ratio = shares.get(window);
                    long floor = line.number("threshold_count");
                    long bps = line.number("ratio_bps");
                    boolean fired = RiskBatteryOracles.outcome(line, in) == RuleOutcome.FIRED;
                    if (ratio.denominator() == 0 || (fired && floor < 1)) {
                        return Optional.empty();
                    }
                    long moved = fired ? floor - 1 : Math.max(floor, (bps * ratio.denominator() + 9_999L) / 10_000L);
                    shares.put(window, new Ratio(moved, ratio.denominator()));
                }
                case BNPL_MULTI_APPLICATION -> {
                    WindowKind window = line.value("window", WindowKind.class);
                    long count = in.monitoring().bnplApplications().get(window);
                    long threshold = line.number("threshold_count");
                    long moved = count >= threshold ? threshold - 1 : threshold;
                    if (moved < 0) {
                        return Optional.empty();
                    }
                    edit.bnpl.put(window, moved);
                }
            }
            try {
                return Optional.of(edit.build());
            } catch (IllegalArgumentException outOfBounds) {
                return Optional.empty();
            }
        }

        /** {@code line} with one parameter moved across the reading {@code in} holds - empty when none does it. */
        static Optional<Line> parameter(Line line, EvaluationInputs in) {
            boolean fired = RiskBatteryOracles.outcome(line, in) == RuleOutcome.FIRED;
            return switch (line.kind()) {
                case AMOUNT_BAND -> {
                    long amount = in.amount().value().orElseThrow().minorUnits();
                    yield Optional.of(line.with("threshold_minor", fired ? amount + 1 : amount));
                }
                case VELOCITY_COUNT, VELOCITY_SUM -> {
                    VelocityReading reading = in.velocity().get(new VelocityKey(line.value("dimension",
                            VelocityDimension.class), line.value("window", WindowKind.class)));
                    boolean count = line.kind() == RuleKind.VELOCITY_COUNT;
                    long figure = count ? reading.count() : reading.sumMinor();
                    yield Optional.of(line.with(count ? "threshold_count" : "threshold_minor", fired ? figure + 1 : figure));
                }
                case NEW_PAYEE, NEW_DEVICE -> {
                    FirstSeen seen = (line.kind() == RuleKind.NEW_PAYEE ? in.payeeFirstSeen() : in.deviceFirstSeen())
                            .value().orElseThrow();
                    if (seen.daysAgo().isEmpty()) {
                        yield Optional.empty(); // never seen is new under every lookback
                    }
                    int days = seen.daysAgo().getAsInt();
                    yield fired ? (days >= 1 ? Optional.of(line.with("lookback_days", days)) : Optional.empty())
                            : Optional.of(line.with("lookback_days", days + 1));
                }
                case SESSION_ASSURANCE_BELOW -> fired
                        ? Optional.of(line.with("assurance", RiskAssurance.SINGLE_FACTOR)) : Optional.empty();
                case RECENT_AUTH_FAILURES -> {
                    long failures = in.authFailures().get(line.value("window", WindowKind.class));
                    yield Optional.of(line.with("threshold_count", fired ? failures + 1 : failures));
                }
                case RECENT_CREDENTIAL_CHANGE, RECENT_RECOVERY -> {
                    LastSeen last = (line.kind() == RuleKind.RECENT_CREDENTIAL_CHANGE ? in.credentialChange()
                            : in.recovery()).value().orElseThrow();
                    if (last.hoursAgo().isEmpty()) {
                        yield Optional.empty(); // never happened is never recent
                    }
                    long hours = last.hoursAgo().getAsLong();
                    yield fired ? (hours >= 1 ? Optional.of(line.with("lookback_hours", (int) hours)) : Optional.empty())
                            : Optional.of(line.with("lookback_hours", (int) (hours + 1)));
                }
                case ACCOUNT_AGE_BELOW -> {
                    int age = in.accountAgeDays().value().orElseThrow();
                    yield fired ? (age >= 1 ? Optional.of(line.with("days", age)) : Optional.empty())
                            : Optional.of(line.with("days", age + 1));
                }
                case PROFILE_RATING -> {
                    int position = RiskBatteryOracles.SEVERITY.indexOf(in.profileRating().value().orElseThrow());
                    if (fired && position == RiskBatteryOracles.SEVERITY.size() - 1) {
                        yield Optional.empty();
                    }
                    yield Optional.of(line.with("rating", RiskBatteryOracles.SEVERITY.get(fired ? position + 1 : position)));
                }
                case CORRIDOR_HIGH_RISK -> {
                    SortedSet<String> listed = new TreeSet<>();
                    for (Object code : (Set<?>) line.parameters().get("corridors")) {
                        listed.add((String) code);
                    }
                    String corridor = in.corridor().value().orElseThrow();
                    if (fired) {
                        listed.remove(corridor);
                    } else {
                        listed.add(corridor);
                    }
                    yield listed.isEmpty() ? Optional.empty()
                            : Optional.of(line.with("corridors", Collections.unmodifiableSortedSet(listed)));
                }
                case DORMANT_THEN_ACTIVE -> {
                    int dormant = in.monitoring().dormantDays().value().orElseThrow();
                    VelocityReading party = in.velocity().get(new VelocityKey(VelocityDimension.PARTY,
                            line.value("window", WindowKind.class)));
                    if (fired) {
                        yield Optional.of(line.with("dormant_days", dormant + 1));
                    }
                    if (party.sumMinor() >= line.number("threshold_minor") && dormant >= 1) {
                        yield Optional.of(line.with("dormant_days", dormant));
                    }
                    if (dormant >= line.number("dormant_days")) {
                        yield Optional.of(line.with("threshold_minor", party.sumMinor()));
                    }
                    yield Optional.empty();
                }
                case RAPID_IN_OUT -> {
                    Flow flow = in.monitoring().flows().get(line.value("window", WindowKind.class));
                    if (flow.inMinor() == 0) {
                        yield Optional.empty();
                    }
                    long share = flow.outMinor() * 10_000L / flow.inMinor();
                    long moved = fired ? share + 1 : share;
                    yield moved > 10_000L ? Optional.empty() : Optional.of(line.with("ratio_bps", (int) moved));
                }
                case STRUCTURING -> {
                    long below = line.number("below_minor");
                    long count = 0;
                    long sum = 0;
                    for (long minor : in.monitoring().amounts().get(line.value("window", WindowKind.class)).minor()) {
                        if (minor < below) {
                            count++;
                            sum += minor;
                        }
                    }
                    if (fired) {
                        yield Optional.of(line.with("threshold_count", count + 1));
                    }
                    if (count >= line.number("threshold_count") && sum >= 1) {
                        yield Optional.of(line.with("sum_minor", sum - 1));
                    }
                    if (sum > line.number("sum_minor")) {
                        yield Optional.of(line.with("threshold_count", count));
                    }
                    yield Optional.empty();
                }
                case ROUND_AMOUNT -> {
                    long amount = in.amount().value().orElseThrow().minorUnits();
                    yield fired ? Optional.of(line.with("modulus_minor", amount + 1))
                            : amount > 0 ? Optional.of(line.with("modulus_minor", amount)) : Optional.empty();
                }
                case REFUND_RATIO, CHARGEBACK_RATIO -> {
                    Ratio ratio = (line.kind() == RuleKind.REFUND_RATIO ? in.monitoring().refunds()
                            : in.monitoring().chargebacks()).get(line.value("window", WindowKind.class));
                    if (ratio.denominator() == 0) {
                        yield Optional.empty();
                    }
                    if (fired) {
                        yield Optional.of(line.with("threshold_count", ratio.numerator() + 1));
                    }
                    if (ratio.numerator() >= line.number("threshold_count")) {
                        long share = ratio.numerator() * 10_000L / ratio.denominator();
                        yield share > 10_000L ? Optional.empty() : Optional.of(line.with("ratio_bps", (int) share));
                    }
                    yield ratio.numerator() >= 1 ? Optional.of(line.with("threshold_count", ratio.numerator()))
                            : Optional.empty();
                }
                case BNPL_MULTI_APPLICATION -> {
                    long count = in.monitoring().bnplApplications().get(line.value("window", WindowKind.class));
                    yield fired ? Optional.of(line.with("threshold_count", count + 1))
                            : count >= 1 ? Optional.of(line.with("threshold_count", count)) : Optional.empty();
                }
                case KYC_NOT_APPROVED, SCREENING_HIT, MERCHANT_SUSPENDED -> Optional.empty();
            };
        }

        /** What a parameter perturbation changed, by name - never the value (a threshold is the policy's content). */
        static String describe(Line before, Line after) {
            for (String name : before.parameters().keySet()) {
                if (!before.parameters().get(name).equals(after.parameters().get(name))) {
                    return "parameter " + name;
                }
            }
            return "parameter";
        }

        /**
         * {@code bands} with the band holding {@code score} moved so the score falls in its lower ({@code -1}) or upper
         * ({@code +1}) neighbour - empty when that neighbour does not exist or the band would be left empty.
         */
        static <T> Optional<List<Band<T>>> moved(List<Band<T>> bands, int score, int direction) {
            int position = RiskBatteryOracles.position(bands, score);
            int at = position - 1;
            Band<T> band = bands.get(at);
            List<Band<T>> moved = new ArrayList<>(bands);
            if (direction < 0) {
                if (at == 0 || score >= band.to()) {
                    return Optional.empty();
                }
                Band<T> lower = bands.get(at - 1);
                moved.set(at - 1, new Band<>(lower.from(), score, lower.answer()));
                moved.set(at, new Band<>(score + 1, band.to(), band.answer()));
            } else {
                if (at == bands.size() - 1 || score <= band.from()) {
                    return Optional.empty();
                }
                Band<T> upper = bands.get(at + 1);
                moved.set(at, new Band<>(band.from(), score - 1, band.answer()));
                moved.set(at + 1, new Band<>(score, upper.to(), upper.answer()));
            }
            return Optional.of(moved);
        }
    }

    /** A mutable copy of an {@link EvaluationInputs}, edited one reading at a time and judged whole again on build. */
    private static final class Inputs {
        private final EvaluationInputs base;
        Reading<Money> amount;
        final SortedMap<VelocityKey, VelocityReading> velocity;
        Reading<FirstSeen> payeeFirstSeen;
        Reading<FirstSeen> deviceFirstSeen;
        Reading<RiskAssurance> sessionAssurance;
        final Map<WindowKind, Long> authFailures;
        Reading<LastSeen> credentialChange;
        Reading<LastSeen> recovery;
        Reading<Integer> accountAgeDays;
        Reading<Boolean> kycApproved;
        Reading<Boolean> screeningHit;
        Reading<RiskRating> profileRating;
        Reading<Boolean> merchantSuspended;
        Reading<String> corridor;
        Reading<Integer> dormantDays;
        final Map<WindowKind, Flow> flows;
        final Map<WindowKind, Amounts> amounts;
        final Map<WindowKind, Ratio> refunds;
        final Map<WindowKind, Ratio> chargebacks;
        final Map<WindowKind, Long> bnpl;

        Inputs(EvaluationInputs in) {
            base = in;
            amount = in.amount();
            velocity = new TreeMap<>(in.velocity());
            payeeFirstSeen = in.payeeFirstSeen();
            deviceFirstSeen = in.deviceFirstSeen();
            sessionAssurance = in.sessionAssurance();
            authFailures = new EnumMap<>(WindowKind.class);
            authFailures.putAll(in.authFailures());
            credentialChange = in.credentialChange();
            recovery = in.recovery();
            accountAgeDays = in.accountAgeDays();
            kycApproved = in.kycApproved();
            screeningHit = in.screeningHit();
            profileRating = in.profileRating();
            merchantSuspended = in.merchantSuspended();
            corridor = in.corridor();
            dormantDays = in.monitoring().dormantDays();
            flows = copy(in.monitoring().flows());
            amounts = copy(in.monitoring().amounts());
            refunds = copy(in.monitoring().refunds());
            chargebacks = copy(in.monitoring().chargebacks());
            bnpl = copy(in.monitoring().bnplApplications());
        }

        private static <V> Map<WindowKind, V> copy(Map<WindowKind, V> map) {
            Map<WindowKind, V> copy = new EnumMap<>(WindowKind.class);
            copy.putAll(map);
            return copy;
        }

        EvaluationInputs build() {
            return new EvaluationInputs(base.operation(), base.subjects(), amount, velocity, payeeFirstSeen,
                    deviceFirstSeen, sessionAssurance, authFailures, credentialChange, recovery, accountAgeDays,
                    kycApproved, screeningHit, profileRating, merchantSuspended, corridor,
                    new MonitoringReadings(dormantDays, flows, amounts, refunds, chargebacks, bnpl));
        }
    }
}
