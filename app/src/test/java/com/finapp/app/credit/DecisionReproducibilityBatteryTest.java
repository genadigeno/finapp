package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CanonicalSnapshot;
import com.finapp.credit.CreditAssessment;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditReplayProof;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.DecisionReplayer.Divergence;
import com.finapp.credit.DecisionReplayer.Verdict;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.EvaluationResult;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyEvaluator;
import com.finapp.credit.PolicyEvaluatorV1;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.SnapshotContent;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The decision reproducibility battery (`P10-TST-002`; PHASE_10_PLAN.md section 13; {@code INV-CRD-01},
 * {@code INV-CRD-02}, {@code INV-CRD-05}, {@code INV-CRD-07}, {@code INV-CRD-12}): ten thousand seeded applicants
 * through production's pipeline across both products, three policy epochs and three scorecards
 * ({@link ReproducibilityWorld}) - every reason code, operator and effect exercised, every decision replayed
 * {@code IDENTICAL} - and the proof shown not to be vacuous: each perturbation flips exactly the verdicts the stored
 * records say it must, through the one property it tests and no other.
 *
 * <ul>
 *   <li><strong>One byte of every stored snapshot</strong> - a digit of the party's identifier, a text the strict parser
 *       still reads, so only the seal can see it - {@code DIVERGED} by {@code HASH} alone, under the stored digest and
 *       re-digested.
 *   <li><strong>The exposure rule's operand forced past its trigger</strong>, both ways, in every pinned version - the
 *       decisions that diverge are exactly those whose stored rule result says the rule did not (or did) trigger.
 *   <li><strong>The engine swapped</strong> under version 1 for one ordering its reasons the other way - exactly the
 *       decisions with two or more reasons diverge; and the engine NUMBER swapped in every decision's pinned versions,
 *       engine 2 held - every decision {@code HASH}, the seal covering the versions.
 * </ul>
 *
 * <p><strong>The seed reproduces the population</strong>: every decision's seed-fixed facts are fingerprinted
 * ({@link #FINGERPRINT}), so a failure named by its seed runs again on the same ten thousand decisions.
 *
 * <p>Then: every evaluation's reasons are its triggered rules' codes in rule order, first kept, recomputed from the stored
 * rows alone; two readings agree verdict for verdict; and another locale and time zone in this JVM, and a second JVM with
 * another default charset, locale, time zone and identity hash codes, reach the same verdicts.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the battery brings its own policies into force, and
 * its proofs are over every decision the database holds. Tampers run in the owner's own transaction, rolled back.
 */
@Tag("database")
@Tag("own-container")
@DisplayName("the decision reproducibility battery (P10-TST-002)")
class DecisionReproducibilityBatteryTest {

    /** The generator's seed - recorded, so a failure names the population it failed on. */
    static final long SEED = 20_261_009L;

    static final int APPLICANTS = 10_000;

    /**
     * What {@link #SEED} produces, every decision fingerprinted by what the seed fixes - its party, product, pinned version
     * numbers, snapshot sequence, decider kind, outcome, amount and ordered reasons - sorted and hashed: the same seed
     * reproduces the same ten thousand decisions, so a failure named by its seed can be run again. A change to the
     * battery's world, the pipeline or the engine moves it, deliberately, in the same reviewed change.
     */
    static final String FINGERPRINT = "2529d62cd41ab6d9cd5bc83633949e8f419a73dc7d00752ea30d5dcdb5db5786";

    private static final long FORCED = 1_000_000_000_000L;

    private static ReproducibilityWorld.Built built;
    private static CreditReplayProof.Report reference;
    private static Map<UUID, Decided> decided;

    @BeforeAll
    static void build() throws Exception {
        built = ReproducibilityWorld.build(SEED, APPLICANTS);
        decided = decisions();
        reference = read(proof(EngineVersions.STANDARD));
    }

    @AfterAll
    static void close() {
        ReproducibilityWorld.TRANSACTIONS.close();
    }

    // ------------------------------------------------------------------ the population

    @Test
    @DisplayName("at least 10,000 decisions, one per request, across both products, three policy versions and three"
            + " scorecards each - the platform's and persons', four-eyes, successor snapshots and decisions pinned before an"
            + " activation and made after it")
    void theCensus() {
        String seed = "seed " + built.seed();
        System.out.printf("P10-TST-002 CENSUS: seed %d, %d applicants, %d requests, %d decisions (%d by a person, %d"
                + " four-eyes, %d on a successor snapshot); outcomes %s; per product and policy version %s%n", built.seed(),
                built.applicants().size(), built.requests(), decided.size(), built.personDecided(), built.fourEyes(),
                decided.values().stream().filter(d -> d.sequence() > 1).count(),
                new TreeMap<>(decided.values().stream().collect(Collectors.groupingBy(
                        d -> (d.byPerson() ? "person " : "platform ") + d.outcome(), Collectors.counting()))),
                new TreeMap<>(decided.values().stream().collect(Collectors.groupingBy(
                        d -> d.product() + " " + d.policy(), Collectors.counting()))));
        String fingerprint = fingerprint();
        System.out.println("P10-TST-002 FINGERPRINT: " + fingerprint);
        assertThat(fingerprint).as(seed + ": the population the seed recorded").isEqualTo(FINGERPRINT);
        assertThat(decided).as(seed).hasSizeGreaterThanOrEqualTo(APPLICANTS);
        assertThat(decided).as(seed + ": every request decided, once").hasSize(built.requests());
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE status <> 'DECIDED'")).as(seed).isZero();
        for (String product : List.of("PERSONAL_LOAN", "CREDIT_LINE")) {
            List<Decided> ofProduct = decided.values().stream().filter(d -> d.product().equals(product)).toList();
            assertThat(ofProduct.stream().map(Decided::policy).distinct()).as(seed + " " + product).hasSize(3);
            assertThat(ofProduct.stream().map(Decided::model).distinct()).as(seed + " " + product).hasSize(3);
            Map<UUID, Long> perPolicy = ofProduct.stream()
                    .collect(Collectors.groupingBy(Decided::policy, Collectors.counting()));
            assertThat(perPolicy.values()).as(seed + " " + product + " decisions per policy version")
                    .allSatisfy(n -> assertThat(n).isGreaterThanOrEqualTo(300));
        }
        assertThat(built.personDecided()).as(seed).isEqualTo(
                decided.values().stream().filter(Decided::byPerson).count());
        assertThat(built.personDecided()).as(seed).isGreaterThanOrEqualTo(100);
        for (boolean byPerson : List.of(false, true)) {
            assertThat(decided.values().stream().filter(d -> d.byPerson() == byPerson && d.outcome().equals("APPROVED"))
                    .count()).as(seed + ": approvals, by person " + byPerson + " - the amount compared on replay")
                    .isGreaterThanOrEqualTo(byPerson ? 100 : 1_000);
        }
        assertThat(built.fourEyes()).as(seed).isPositive();
        assertThat(decided.values().stream().filter(d -> d.sequence() > 1).count()).as(seed + ": successor snapshots")
                .isPositive();
        assertThat(count("SELECT count(*) FROM credit.credit_decision d JOIN credit.credit_policy_version p"
                + " ON p.id = d.policy_version_id WHERE p.effective_to IS NOT NULL AND d.decided_at > p.effective_to"))
                .as(seed + ": decided under a version retired before the decision")
                .isGreaterThanOrEqualTo(ReproducibilityWorld.CARRIED);
    }

    @Test
    @DisplayName("every reason code in the catalogue is cited by a platform decision - a code left unexercised is named")
    void everyReasonCodeIsExercised() {
        Set<String> cited = new TreeSet<>();
        decided.values().stream().filter(d -> !d.byPerson()).forEach(d -> cited.addAll(d.reasons()));
        Set<String> unexercised = new TreeSet<>();
        for (ReasonCode code : ReasonCode.values()) {
            if (!cited.contains(code.code())) {
                unexercised.add(code.code());
            }
        }
        assertThat(unexercised).as("seed %d: reason codes no platform decision cites", SEED).isEmpty();
    }

    @Test
    @DisplayName("every operator triggers and stays untriggered, every effect triggers, every outcome and the fallback both"
            + " ways are reached, and every provenance kind and marker is frozen")
    void everyOperatorEffectAndPathIsExercised() throws SQLException {
        Map<String, long[]> operators = new TreeMap<>();
        Map<String, Long> effects = new TreeMap<>();
        for (Decided d : decided.values()) {
            for (RuleRow rule : d.evaluation().rules()) {
                long[] seen = operators.computeIfAbsent(rule.operator(), key -> new long[2]);
                if (rule.triggered()) {
                    seen[0]++;
                    effects.merge(rule.effect(), 1L, Long::sum);
                } else if (rule.assessed()) {
                    seen[1]++;
                }
            }
        }
        for (PolicyOperator operator : PolicyOperator.values()) {
            long[] seen = operators.getOrDefault(operator.name(), new long[2]);
            assertThat(seen[0]).as("seed %d: %s triggered", SEED, operator).isPositive();
            assertThat(seen[1]).as("seed %d: %s assessed and not triggered", SEED, operator).isPositive();
        }
        for (PolicyEffect effect : PolicyEffect.values()) {
            assertThat(effects.getOrDefault(effect.name(), 0L)).as("seed %d: %s triggered", SEED, effect).isPositive();
        }
        assertThat(decided.values().stream().map(d -> d.evaluation().outcome()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("APPROVE", "REFER", "DECLINE", "HARD_DECLINE");
        assertThat(decided.values().stream().filter(d -> d.evaluation().fallback()).map(d -> d.evaluation().outcome())
                .collect(Collectors.toSet())).as("the unavailable fallback, declining and referring")
                .containsExactlyInAnyOrder("DECLINE", "REFER");
        for (boolean byPerson : List.of(false, true)) {
            assertThat(decided.values().stream().filter(d -> d.byPerson() == byPerson).map(Decided::outcome)
                    .collect(Collectors.toSet())).as("by person: " + byPerson)
                    .containsExactlyInAnyOrder("APPROVED", "DECLINED");
        }
        List<Decided> capped = decided.values().stream()
                .filter(d -> !d.byPerson() && d.approved().isPresent() && d.approved().get() < d.requested()).toList();
        System.out.printf("P10-TST-002 CAPPED: %d by the ceiling, %d by a rule%n", capped.stream()
                .filter(d -> d.reasons().equals(List.of(ReasonCode.AUTO_APPROVAL_CEILING.code()))).count(), capped.stream()
                .filter(d -> !d.reasons().equals(List.of(ReasonCode.AUTO_APPROVAL_CEILING.code()))).count());
        assertThat(capped).as("approvals capped by the ceiling")
                .anyMatch(d -> d.reasons().equals(List.of(ReasonCode.AUTO_APPROVAL_CEILING.code())));
        assertThat(capped).as("approvals capped by a rule")
                .anyMatch(d -> !d.reasons().equals(List.of(ReasonCode.AUTO_APPROVAL_CEILING.code())));
        assertThat(decided.values()).as("full approvals")
                .anyMatch(d -> !d.byPerson() && d.approved().isPresent() && d.approved().get() == d.requested());
        for (String frozen : List.of("\"kind\":\"record\"", "\"kind\":\"unavailable\"", "\"kind\":\"notRead\"",
                "\"kind\":\"declared\"", "\"kind\":\"port\"",
                "{\"code\":\"CURRENCY_NOT_SUPPORTED\",\"value\":{\"code\"",
                "{\"code\":\"SOURCE_UNAVAILABLE\",\"value\":{\"code\"",
                "{\"code\":\"PARTY_AGE_YEARS\",\"value\":{\"absent\"")) {
            assertThat(count("SELECT count(*) FROM credit.decision_snapshot s JOIN credit.credit_decision d"
                    + " ON d.snapshot_id = s.id WHERE position(? IN s.canonical) > 0", frozen))
                    .as("decided snapshots holding " + frozen).isPositive();
        }
    }

    @Test
    @DisplayName("missing data never approves (INV-CRD-10): no platform approval and no approving evaluation on a source"
            + " unavailable or an attribute ABSENT that the pinned policy reads - recomputed from the snapshots, the pinned"
            + " rules and the assessments, never from the evaluator's own record; replay cannot see it, this census can")
    void missingDataNeverApproves() throws SQLException {
        // Replay re-runs the same evaluator, so an evaluator approving on absent data replays IDENTICAL; the reason
        // oracle starts from the stored outcome. Only a census over the inputs themselves catches it.
        try (Connection app = DatabaseRoles.application()) {
            List<String> subjects = MissingDataCensus.subjects(app);
            long unavailable = one(app, MissingDataCensus.subjectsWhere(MissingDataCensus.SOURCE_UNAVAILABLE_READ));
            long absent = one(app, MissingDataCensus.subjectsWhere(MissingDataCensus.ABSENT_COMPARED));
            System.out.printf("P10-TST-002 MISSING DATA: evaluations on missing data by outcome %s - %d on a source the"
                    + " policy reads unavailable, %d comparing an absent attribute or figure%n", subjects, unavailable, absent);
            assertThat(MissingDataCensus.systemApprovals(app)).as("seed %d: platform approvals on missing data", SEED)
                    .isEmpty();
            assertThat(MissingDataCensus.approvingEvaluations(app)).as("seed %d: evaluations approving on missing data",
                    SEED).isEmpty();
            assertThat(unavailable).as("seed %d: the census's subjects - a source the pinned policy reads unavailable",
                    SEED).isGreaterThanOrEqualTo(50);
            assertThat(absent).as("seed %d: the census's subjects - an absent attribute or figure compared", SEED)
                    .isGreaterThanOrEqualTo(50);
            assertThat(subjects).as("seed %d: the subjects referred and declined, never approved", SEED)
                    .anyMatch(outcome -> outcome.startsWith("REFER "))
                    .anyMatch(outcome -> outcome.startsWith("DECLINE "))
                    .noneMatch(outcome -> outcome.startsWith("APPROVE "));
        }
    }

    private static long one(Connection connection, String sql) throws SQLException {
        try (Statement read = connection.createStatement(); ResultSet row = read.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    // ------------------------------------------------------------------ IDENTICAL

    @Test
    @DisplayName("every decision replays IDENTICAL from its sealed snapshot and pinned versions - the platform's and persons',"
            + " under every version, with the third epoch's versions in force")
    void everyDecisionReplaysIdentical() {
        assertThat(reference.diverged()).as("seed %d", SEED).isEmpty();
        assertThat(reference.count(Verdict.IDENTICAL)).isEqualTo(decided.size());
        assertThat(reference.replays().stream().filter(DecisionReplayer.Replay::byPerson).count())
                .isEqualTo(decided.values().stream().filter(Decided::byPerson).count());
        assertThat(reference.replays().stream().map(replay -> replay.decision().value()).collect(Collectors.toSet()))
                .isEqualTo(decided.keySet());
    }

    @Test
    @DisplayName("replaying twice is identical - a second reading reaches every verdict the first did, in the same order")
    void replayingTwiceIsIdentical() {
        assertThat(lines(read(proof(EngineVersions.STANDARD)))).isEqualTo(lines(reference));
    }

    @Test
    @DisplayName("every evaluation's reasons are its triggered rules' codes in rule order, first kept - recomputed from the"
            + " stored rule results and the pinned rules alone - and every platform decision's are its evaluation's")
    void reasonsFollowRuleOrder() {
        List<String> wrong = new ArrayList<>();
        for (Decided d : decided.values()) {
            Evaluation evaluation = d.evaluation();
            List<String> expected = expectedReasons(evaluation);
            if (!evaluation.reasons().equals(expected)) {
                wrong.add(d.id() + " " + evaluation.reasons() + " expected " + expected);
            }
            if (!d.byPerson() && !d.reasons().equals(evaluation.reasons())) {
                wrong.add(d.id() + " decision " + d.reasons() + " evaluation " + evaluation.reasons());
            }
        }
        assertThat(wrong).as("seed %d", SEED).isEmpty();
        assertThat(decided.values().stream().map(d -> d.evaluation().reasons()).filter(r -> r.size() >= 2).distinct()
                .count()).as("distinct orders of two or more reasons - what a hash order would scramble")
                .isGreaterThanOrEqualTo(20);
    }

    // ------------------------------------------------------------------ the perturbations

    @Test
    @DisplayName("one byte of every stored snapshot - a text the strict parser still reads - DIVERGES every decision by HASH"
            + " alone, under its stored digest and re-digested")
    void oneByteOfEverySnapshotDivergesByHash() throws SQLException {
        String decidedSnapshots = "id IN (SELECT snapshot_id FROM credit.credit_decision)";
        String reDigested = "get_byte(content_sha256, 0) % 2 = 1";
        String digit = "position('\"party\":\"' IN canonical) + 44";
        Map<UUID, DecisionReplayer.Replay> replays = tampered(ReproducibilityWorld.replayer(EngineVersions.STANDARD), owner -> {
            List<String> before = strings(owner, "SELECT canonical FROM credit.decision_snapshot WHERE " + decidedSnapshots
                    + " ORDER BY id");
            execute(owner, "ALTER TABLE credit.decision_snapshot DISABLE TRIGGER USER");
            execute(owner, "ALTER TABLE credit.decision_snapshot DROP CONSTRAINT decision_snapshot_hash_is_the_contents");
            int edited = update(owner, "UPDATE credit.decision_snapshot SET canonical = overlay(canonical PLACING"
                    + " (CASE substr(canonical, " + digit + ", 1) WHEN '0' THEN '1' ELSE '0' END) FROM " + digit
                    + " FOR 1) WHERE " + decidedSnapshots);
            assertThat(edited).isEqualTo(decided.size());
            int reSealed = update(owner, "UPDATE credit.decision_snapshot SET content_sha256 ="
                    + " sha256(convert_to(canonical, 'UTF8')) WHERE " + decidedSnapshots + " AND " + reDigested);
            assertThat(reSealed).as("some re-digested, so only the decision's digest disagrees").isPositive()
                    .isLessThan(edited);
            List<String> after = strings(owner, "SELECT canonical FROM credit.decision_snapshot WHERE " + decidedSnapshots
                    + " ORDER BY id");
            for (int i = 0; i < before.size(); i++) {
                assertThat(differingCharacters(before.get(i), after.get(i))).isEqualTo(1);
                // The strict parser reads the edited text: the seal, and nothing before it, must see the change.
                CanonicalSnapshot.parse(after.get(i));
            }
        });
        assertThat(replays).hasSize(decided.size());
        assertThat(replays.values()).allSatisfy(replay -> {
            assertThat(replay.verdict()).isEqualTo(Verdict.DIVERGED);
            assertThat(replay.divergences()).containsExactly(Divergence.HASH);
        });
    }

    @Test
    @DisplayName("the exposure rule's operand forced past its trigger in every pinned version DIVERGES exactly the decisions"
            + " its stored result says it did not trigger for - and, forced the other way, exactly those it did")
    void aRuleForcedPastItsTriggerDivergesExactlyThePredictedDecisions() throws SQLException {
        String sentinel = "subject_kind = 'FIGURE' AND subject = 'EXPOSURE_HEADROOM' AND operator = 'LT'";
        assertThat(count("SELECT count(*) FROM (SELECT policy_version_id FROM credit.credit_policy_rule WHERE " + sentinel
                + " AND reason_code = 'CRD-EXPOSURE-LIMIT' GROUP BY policy_version_id HAVING count(*) = 1) one"
                + " WHERE policy_version_id IN (SELECT policy_version_id FROM credit.credit_decision)"))
                .as("every pinned version holds the sentinel once").isEqualTo(6);
        assertThat(count("SELECT count(*) FROM credit.credit_policy_rule WHERE reason_code = 'CRD-EXPOSURE-LIMIT'"
                + " AND NOT (" + sentinel + ") AND policy_version_id IN (SELECT policy_version_id FROM"
                + " credit.credit_decision)")).as("the sentinel alone cites CRD-EXPOSURE-LIMIT").isZero();
        for (boolean on : List.of(true, false)) {
            Set<UUID> predicted = decided.values().stream()
                    .filter(d -> d.evaluation().sentinel().map(rule -> rule.assessed() && rule.triggered() != on)
                            .orElseThrow())
                    .map(Decided::id)
                    .collect(Collectors.toSet());
            assertThat(predicted).as("forced " + (on ? "on" : "off") + ": decisions it must flip")
                    .hasSizeGreaterThanOrEqualTo(100);
            Map<UUID, DecisionReplayer.Replay> replays =
                    tampered(ReproducibilityWorld.replayer(EngineVersions.STANDARD), owner -> {
                        execute(owner, "ALTER TABLE credit.credit_policy_rule DISABLE TRIGGER USER");
                        assertThat(update(owner, "UPDATE credit.credit_policy_rule SET operand_money_minor = "
                                + (on ? FORCED : -FORCED) + " WHERE " + sentinel)).isGreaterThanOrEqualTo(6);
                    });
            assertDivergedExactly(replays, predicted, "forced " + (on ? "on" : "off"));
        }
    }

    @Test
    @DisplayName("an engine swapped under version 1 for one ordering its reasons the other way DIVERGES exactly the decisions"
            + " with two or more reasons, by their reasons")
    void aSwappedEngineDivergesExactlyThePredictedDecisions() {
        PolicyEvaluator reversing = new PolicyEvaluator() {
            @Override
            public int engineVersion() {
                return PolicyEvaluatorV1.VERSION;
            }

            @Override
            public EvaluationResult evaluate(SnapshotContent snapshot, CreditAssessment assessment, CreditPolicy policy) {
                EvaluationResult v1 = new PolicyEvaluatorV1().evaluate(snapshot, assessment, policy);
                List<ReasonCode> reversed = new ArrayList<>(v1.reasons());
                java.util.Collections.reverse(reversed);
                return new EvaluationResult(v1.engineVersion(), v1.outcome(), v1.requested(), v1.approved(), reversed,
                        v1.fallbackApplied(), v1.rules());
            }
        };
        Set<UUID> predicted = decided.values().stream().filter(d -> d.evaluation().reasons().size() >= 2).map(Decided::id)
                .collect(Collectors.toSet());
        assertThat(predicted).hasSizeGreaterThanOrEqualTo(100);
        CreditReplayProof.Report swapped = read(proof(EngineVersions.of(List.of(reversing))));
        Map<UUID, DecisionReplayer.Replay> replays = new HashMap<>();
        swapped.replays().forEach(replay -> replays.put(replay.decision().value(), replay));
        assertDivergedExactly(replays, predicted, "engine swapped");
        assertThat(swapped.replays()).filteredOn(replay -> replay.verdict() == Verdict.DIVERGED)
                .allSatisfy(replay -> assertThat(replay.divergences()).containsExactly(Divergence.REASONS));
    }

    @Test
    @DisplayName("the engine version swapped in every decision's pinned versions DIVERGES every decision by HASH - the seal"
            + " covers the versions, and engine 2 is held, so nothing but the seal can refuse it")
    void aSwappedEngineVersionDivergesEveryDecisionByHash() throws SQLException {
        PolicyEvaluator two = new PolicyEvaluator() {
            @Override
            public int engineVersion() {
                return 2;
            }

            @Override
            public EvaluationResult evaluate(SnapshotContent snapshot, CreditAssessment assessment, CreditPolicy policy) {
                EvaluationResult v1 = new PolicyEvaluatorV1().evaluate(snapshot, assessment, policy);
                return new EvaluationResult(2, v1.outcome(), v1.requested(), v1.approved(), v1.reasons(),
                        v1.fallbackApplied(), v1.rules());
            }
        };
        Map<UUID, DecisionReplayer.Replay> replays = tampered(
                ReproducibilityWorld.replayer(EngineVersions.of(List.of(new PolicyEvaluatorV1(), two))), owner -> {
                    execute(owner, "ALTER TABLE credit.credit_decision DISABLE TRIGGER USER");
                    assertThat(update(owner, "UPDATE credit.credit_decision SET engine_version = 2"))
                            .isEqualTo(decided.size());
                });
        assertThat(replays.values()).hasSize(decided.size()).allSatisfy(replay -> {
            assertThat(replay.verdict()).isEqualTo(Verdict.DIVERGED);
            assertThat(replay.divergences()).containsExactly(Divergence.HASH);
        });
    }

    // ------------------------------------------------------------------ determinism

    @Test
    @DisplayName("another default locale and time zone in this JVM reach every verdict the reference reading did")
    void anotherLocaleAndTimeZoneReplayIdentically() {
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
            assertThat(lines(read(proof(EngineVersions.STANDARD)))).isEqualTo(lines(reference));
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(zone);
        }
    }

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other identity hash codes - reaches every"
            + " verdict this one did")
    void aSecondJvmReplaysIdentically() throws Exception {
        Path out = Files.createTempFile("battery-replay", ".txt");
        Path log = Files.createTempFile("battery-replay", ".log");
        Path coordinates = Files.createTempFile("battery-replay", ".args");
        try {
            // The database's coordinates reach the second JVM through an argument file, read at its start and deleted
            // after - never on a command line another process on the machine could list.
            List<String> properties = new ArrayList<>();
            for (String property : List.of("finapp.db.url", "finapp.db.app.user", "finapp.db.app.password")) {
                properties.add(quoted("-D" + property + "=" + DatabaseRoles.required(property)));
            }
            Files.write(coordinates, properties, StandardCharsets.UTF_8);
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> command = new ArrayList<>(List.of(java, "-Xmx1g", "@" + coordinates,
                    "-Dfile.encoding=UTF-16", "-Duser.language=ar", "-Duser.country=EG", "-Duser.timezone=America/Adak"));
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), ReplayInAnotherJvm.class.getName(),
                    out.toString()));
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertThat(child.waitFor(15, TimeUnit.MINUTES)).as("the second JVM finished").isTrue();
            assertThat(child.exitValue()).as(Files.readString(log, StandardCharsets.UTF_8)).isZero();
            List<String> lines = Files.readAllLines(out, StandardCharsets.UTF_8);
            assertThat(lines.get(0)).as("the defaults it ran under")
                    .isEqualTo("charset=UTF-16 locale=ar-EG zone=America/Adak");
            assertThat(ReplayInAnotherJvm.defaults()).as("and this JVM's differ").isNotEqualTo(lines.get(0));
            assertThat(lines.subList(1, lines.size())).isEqualTo(lines(reference));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(log);
            Files.deleteIfExists(coordinates);
        }
    }

    /** One argument-file argument: quoted, its backslashes and quotes escaped. */
    private static String quoted(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------ the oracles

    /**
     * Engine version 1's reasons, recomputed from the stored rule results and the pinned rules' codes alone: an adverse
     * outcome's triggered codes in rule order, first kept (the fallback adding {@code CRD-SOURCE-UNAVAILABLE} when absent);
     * a capped approval's binding cap, else the ceiling's code; a full approval none.
     */
    private static List<String> expectedReasons(Evaluation evaluation) {
        List<RuleRow> triggered = evaluation.rules().stream().filter(RuleRow::triggered).toList();
        if (evaluation.outcome().equals("APPROVE")) {
            if (evaluation.approved().orElseThrow() >= evaluation.requested()) {
                return List.of();
            }
            long approved = evaluation.approved().get();
            return List.of(triggered.stream()
                    .filter(rule -> rule.cap().isPresent() && rule.cap().get() == approved)
                    .map(RuleRow::reason)
                    .findFirst()
                    .orElse(ReasonCode.AUTO_APPROVAL_CEILING.code()));
        }
        Set<String> firstOfEach = new LinkedHashSet<>();
        triggered.forEach(rule -> firstOfEach.add(rule.reason()));
        if (evaluation.fallback()) {
            firstOfEach.add(ReasonCode.SOURCE_UNAVAILABLE.code());
        }
        return List.copyOf(firstOfEach);
    }

    private static void assertDivergedExactly(Map<UUID, DecisionReplayer.Replay> replays, Set<UUID> predicted, String what) {
        assertThat(replays.keySet()).as(what).isEqualTo(decided.keySet());
        Set<UUID> diverged = replays.values().stream().filter(replay -> replay.verdict() == Verdict.DIVERGED)
                .map(replay -> replay.decision().value()).collect(Collectors.toSet());
        System.out.printf("P10-TST-002 %s: %d of %d decisions predicted to diverge, %d diverged%n", what, predicted.size(),
                replays.size(), diverged.size());
        Set<UUID> missed = new TreeSet<>(predicted);
        missed.removeAll(diverged);
        Set<UUID> unexpected = new TreeSet<>(diverged);
        unexpected.removeAll(predicted);
        assertThat(missed).as("seed %d, %s: predicted to diverge, replayed IDENTICAL", SEED, what).isEmpty();
        assertThat(unexpected).as("seed %d, %s: diverged unpredicted", SEED, what).isEmpty();
        assertThat(replays.values()).filteredOn(replay -> replay.verdict() == Verdict.DIVERGED).allSatisfy(replay ->
                assertThat(replay.divergences()).as(what + ": by the re-run, never the seal or an unreplayable")
                        .isNotEmpty().doesNotContain(Divergence.HASH, Divergence.UNREPLAYABLE));
    }

    // ------------------------------------------------------------------ plumbing

    /** One decision as stored, with the evaluation it was compared against on replay. */
    record Decided(UUID id, String product, String outcome, long requested, Optional<Long> approved, boolean byPerson,
            UUID policy, UUID model, int sequence, List<String> reasons, Evaluation evaluation) {}

    /** The evaluation of the decision's own snapshot: what replay re-derives and compares. */
    record Evaluation(String outcome, boolean fallback, long requested, Optional<Long> approved, List<String> reasons,
            List<RuleRow> rules) {

        Optional<RuleRow> sentinel() {
            List<RuleRow> found = rules.stream().filter(rule -> rule.subject().equals("EXPOSURE_HEADROOM")
                    && rule.operator().equals("LT")).toList();
            return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
        }
    }

    /** One rule's stored result beside the pinned rule's definition. */
    record RuleRow(int ordinal, String subject, String operator, String effect, String reason, Optional<Long> cap,
            boolean triggered, boolean assessed) {}

    private static Map<UUID, Decided> decisions() throws SQLException {
        Map<UUID, List<RuleRow>> rules = new HashMap<>();
        Map<UUID, Decided> found = new LinkedHashMap<>();
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            try (ResultSet row = statement.executeQuery("SELECT er.evaluation_id, er.ordinal, r.subject, r.operator,"
                    + " er.effect, r.reason_code, r.cap_amount_minor, er.triggered, er.assessed"
                    + " FROM credit.policy_evaluation_rule er JOIN credit.policy_evaluation e ON e.id = er.evaluation_id"
                    + " JOIN credit.credit_policy_rule r ON r.policy_version_id = e.policy_version_id"
                    + " AND r.ordinal = er.ordinal ORDER BY er.evaluation_id, er.ordinal")) {
                while (row.next()) {
                    rules.computeIfAbsent(row.getObject(1, UUID.class), key -> new ArrayList<>()).add(new RuleRow(
                            row.getInt(2), row.getString(3), row.getString(4), row.getString(5), row.getString(6),
                            Optional.ofNullable((Long) row.getObject(7)), row.getBoolean(8), row.getBoolean(9)));
                }
            }
            try (ResultSet row = statement.executeQuery("SELECT d.id, d.product, d.outcome, d.requested_minor,"
                    + " d.approved_minor, d.decided_by_type, d.policy_version_id, d.model_version_id, s.sequence,"
                    + " ARRAY(SELECT reason_code FROM credit.credit_decision_reason WHERE decision_id = d.id ORDER BY"
                    + " ordinal), e.id, e.outcome, e.fallback_applied, e.requested_minor, e.approved_minor, e.reason_codes"
                    + " FROM credit.credit_decision d JOIN credit.decision_snapshot s ON s.id = d.snapshot_id"
                    + " JOIN credit.credit_assessment a ON a.snapshot_id = d.snapshot_id"
                    + " JOIN credit.policy_evaluation e ON e.assessment_id = a.id ORDER BY d.id")) {
                while (row.next()) {
                    Evaluation evaluation = new Evaluation(row.getString(12), row.getBoolean(13), row.getLong(14),
                            Optional.ofNullable((Long) row.getObject(15)), texts(row.getArray(16)),
                            List.copyOf(rules.getOrDefault(row.getObject(11, UUID.class), List.of())));
                    UUID id = row.getObject(1, UUID.class);
                    found.put(id, new Decided(id, row.getString(2), row.getString(3), row.getLong(4),
                            Optional.ofNullable((Long) row.getObject(5)), row.getString(6).equals("EMPLOYEE"),
                            row.getObject(7, UUID.class), row.getObject(8, UUID.class), row.getInt(9),
                            texts(row.getArray(10)), evaluation));
                }
            }
        }
        return found;
    }

    /** Every decision, as the seed fixes it, one line each - sorted, then SHA-256. */
    private static String fingerprint() {
        List<String> lines = new ArrayList<>();
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement();
                ResultSet row = statement.executeQuery("SELECT d.party_id::text || ' ' || d.product || ' policy ' || p.version"
                        + " || ' model ' || m.version || ' engine ' || d.engine_version || ' sequence ' || s.sequence || ' '"
                        + " || d.decided_by_type || ' ' || d.outcome || ' ' || coalesce(d.approved_minor::text, '-') || ' '"
                        + " || coalesce(d.term_months::text, '-') || ' ' || array_to_string(ARRAY(SELECT reason_code FROM"
                        + " credit.credit_decision_reason WHERE decision_id = d.id ORDER BY ordinal), ',')"
                        + " FROM credit.credit_decision d JOIN credit.credit_policy_version p ON p.id = d.policy_version_id"
                        + " JOIN credit.scorecard_model_version m ON m.id = d.model_version_id"
                        + " JOIN credit.decision_snapshot s ON s.id = d.snapshot_id")) {
            while (row.next()) {
                lines.add(row.getString(1));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        java.util.Collections.sort(lines);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", lines).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<String> texts(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }

    private static Function<Connection, CreditReplayProof.Report> proof(EngineVersions engines) {
        return new CreditReplayProof(ReproducibilityWorld.replayer(engines))::prove;
    }

    private static <R> R read(Function<Connection, R> work) {
        return new CreditReadingSnapshot(DatabaseRoles::application).read(work);
    }

    private static List<String> lines(CreditReplayProof.Report report) {
        return report.replays().stream().map(ReplayInAnotherJvm::line).toList();
    }

    /** A tamper's statements as the owner, then every decision replayed in the same transaction - rolled back. */
    private static Map<UUID, DecisionReplayer.Replay> tampered(DecisionReplayer replayer, SqlWork tamper)
            throws SQLException {
        Map<UUID, DecisionReplayer.Replay> replays = new HashMap<>();
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try {
                tamper.run(owner);
                for (UUID id : decided.keySet()) {
                    replays.put(id, replayer.replay(owner, CreditDecisionId.of(id)).orElseThrow());
                }
            } finally {
                owner.rollback();
            }
        }
        return replays;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection owner) throws SQLException;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int update(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            while (row.next()) {
                found.add(row.getString(1));
            }
        }
        return found;
    }

    private static int differingCharacters(String a, String b) {
        if (a.length() != b.length()) {
            return -1;
        }
        int differing = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                differing++;
            }
        }
        return differing;
    }

    private static long count(String sql, Object... parameters) {
        return CreditWorld.count(sql, parameters);
    }
}
