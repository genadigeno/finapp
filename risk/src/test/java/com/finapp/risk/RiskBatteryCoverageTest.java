package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.RiskBatteryOracles.Band;
import com.finapp.risk.RiskBatteryOracles.Edge;
import com.finapp.risk.RiskBatteryOracles.Line;
import com.finapp.risk.RiskBatteryOracles.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The policy battery's coverage floors (`P13-TST-002`; {@code PHASE_13_PLAN.md} section 13.3): what the population must
 * have reached for its verdicts to mean anything - every rule row of every v1 version fired and not fired, every rule kind
 * fired, every decision and rating each version can reach by a hard rule and by a band, every band edge, the clamp, the
 * threshold, lookback and window edges the generator plans, every corridor, the account-takeover hold, and the fail-safe at
 * every band, at its edge, under every cause and for an amount no band can place. Every count is the oracle's reading of
 * the sealed inputs - never the engine's - and each family's floor is about half the smallest figure of that family in the
 * full population (the figures in BACKLOG.md's as-built; the BNPL battery's precedent, by family rather than by figure), so
 * a generator change that thins a family out fails here and not only in the fingerprint a reviewer may re-pin. A shrunk run
 * fails them by design.
 *
 * <p><strong>The one row no request can reach</strong>: {@code CARD_PAYMENT v1} rule 4 ({@code VELOCITY_COUNT} per
 * {@code SOURCE_ACCOUNT}) - a card payment carries no source account ({@code PaymentRiskJudgement}: present exactly for the
 * wallet), so the row is {@code ABSENT} on every card payment, in production as here. The floor holds it at exactly zero
 * fires: the dead row is a finding of this battery (the C8 fixture's), recorded, not hidden.
 */
@DisplayName("P13-TST-002 - the coverage floors: every row, decision, band edge, threshold edge and fail-safe band reached")
class RiskBatteryCoverageTest {

    // The floors - each about half the smallest figure of its family in the full population (2026-10-26, seed 13302,
    // 10,000 subjects; BACKLOG.md's as-built records the figures): the fewest fires of a row 343 (MONITORING v1 rule 7),
    // the fewest quiet judgements 7,811 (WITHDRAWAL v1 rule 6), the fewest fires of a kind 808 (STRUCTURING), the rarest
    // decision 294 (a WITHDRAWAL challenge refused), the rarest band edge 71 (WITHDRAWAL v1 at 700), the rarest planned edge
    // 72 (MONITORING v1 rule 8 at 100), the rarest fail-safe case 357 (CROSS_BORDER with its pin unread).

    /** Each rule row's fires and quiet judgements (a row's reading present). */
    static final long ROW_FIRED = 170;
    static final long ROW_NOT_FIRED = 3_900;

    /** Each rule kind's fires over the whole population. */
    static final long KIND_FIRED = 400;

    /** Each decision, rating and step a version can reach; a challenge satisfied and refused. */
    static final long DECIDED = 145;

    /** Each side of each band edge, and the clamp. */
    static final long BAND_EDGE = 35;

    /** Each planned threshold, lookback and window edge. */
    static final long THRESHOLD_EDGE = 35;

    /** Each fail-safe band, edge, cause, compiled answer and unplaceable amount. */
    static final long FAIL_SAFE = 175;

    /** The rows no request can make fire: their fires are held at zero. */
    static final Set<String> DEAD_ROWS = Set.of("CARD_PAYMENT v1|4");

    private static RiskBattery.Run run;

    @BeforeAll
    static void battery() throws Exception {
        run = RiskBattery.run();
    }

    @Test
    @DisplayName("every rule row of every v1 version fired and judged quiet - but the one no request can reach, held at"
            + " zero fires - and every rule kind fired")
    void everyRuleRowFiresAndStaysQuiet() {
        List<String> short_ = new ArrayList<>();
        Map<String, String> figures = new TreeMap<>();
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            Statement statement = RiskBatteryOracles.v1(operation);
            for (Line line : statement.lines()) {
                String row = statement.name() + "|" + line.ordinal();
                long fired = count("fired|" + row);
                long quiet = count("not_fired|" + row);
                figures.put(row + " " + line.kind(), fired + "/" + quiet + "/" + count("absent|" + row));
                if (DEAD_ROWS.contains(row)) {
                    if (fired != 0) {
                        short_.add(row + " fired " + fired + " - a row no request can reach");
                    }
                    continue;
                }
                floor(short_, "fired|" + row + " " + line.kind(), fired, ROW_FIRED);
                floor(short_, "not_fired|" + row + " " + line.kind(), quiet, ROW_NOT_FIRED);
            }
        }
        for (RuleKind kind : RuleKind.values()) {
            floor(short_, "kindFired|" + kind, count("kindFired|" + kind), KIND_FIRED);
        }
        System.out.println("P13-TST-002 ROWS fired/quiet/absent: " + figures);
        assertThat(short_).as("rows and kinds below their floor").isEmpty();
    }

    @Test
    @DisplayName("every decision each judged version can reach - by a hard rule and by a band - every rating, and a"
            + " challenge both satisfied and refused")
    void everyDecisionAndRatingIsReached() {
        List<String> short_ = new ArrayList<>();
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            Statement statement = RiskBatteryOracles.v1(operation);
            String version = statement.name();
            if (statement.monitoring()) {
                for (Band<RiskRating> band : statement.ratingBands()) {
                    floor(short_, "rating|" + version + "|" + band.answer(), count("rating|" + version + "|"
                            + band.answer()), DECIDED);
                }
                continue;
            }
            Set<RiskDecision> reachable = new TreeSet<>();
            statement.scoreBands().forEach(band -> reachable.add(band.answer()));
            statement.lines().forEach(line -> line.hard().ifPresent(reachable::add));
            for (RiskDecision decision : reachable) {
                floor(short_, "decision|" + version + "|" + decision, count("decision|" + version + "|" + decision),
                        DECIDED);
            }
            for (RiskBatteryOracles.Step step : List.of(RiskBatteryOracles.Step.HARD_RULE,
                    RiskBatteryOracles.Step.SCORE_BAND)) {
                floor(short_, "step|" + version + "|" + step, count("step|" + version + "|" + step), DECIDED);
            }
            for (boolean satisfied : new boolean[] {true, false}) {
                floor(short_, "challenge|" + version + "|" + satisfied, count("challenge|" + version + "|" + satisfied),
                        DECIDED);
            }
        }
        Map<String, Long> figures = new TreeMap<>();
        run.coverage().forEach((key, value) -> {
            if (key.startsWith("decision|") || key.startsWith("rating|") || key.startsWith("step|")
                    || key.startsWith("challenge|") || key.startsWith("kindFired|")) {
                figures.put(key, value);
            }
        });
        System.out.println("P13-TST-002 DECISIONS: " + figures);
        assertThat(short_).as("decisions, ratings, steps and challenges below their floor").isEmpty();
    }

    @Test
    @DisplayName("every band edge - the nearest reachable score on each side of each boundary, from the version's own"
            + " weights - and the clamp, wherever the weights can pass the ceiling")
    void everyBandEdgeAndTheClampAreReached() {
        List<String> short_ = new ArrayList<>();
        Map<String, Long> figures = new LinkedHashMap<>();
        for (RiskOperation operation : RiskBatteryOracles.OPERATIONS) {
            Statement statement = RiskBatteryOracles.v1(operation);
            String version = statement.name();
            TreeSet<Integer> reachable = reachable(statement);
            List<Integer> boundaries = new ArrayList<>();
            if (statement.monitoring()) {
                statement.ratingBands().stream().skip(1).forEach(band -> boundaries.add(band.from()));
            } else {
                statement.scoreBands().stream().skip(1).forEach(band -> boundaries.add(band.from()));
            }
            for (int boundary : boundaries) {
                Integer below = reachable.lower(boundary);
                Integer at = reachable.ceiling(boundary);
                for (Integer score : new Integer[] {below, at}) {
                    if (score != null) {
                        long reached = count("score|" + version + "|" + score);
                        figures.put(version + " edge " + boundary + " score " + score, reached);
                        floor(short_, version + " score " + score + " beside the edge at " + boundary, reached, BAND_EDGE);
                    }
                }
            }
            long weights = statement.lines().stream().mapToLong(line -> line.weight().orElse(0)).sum();
            if (weights > RiskBatteryOracles.CEILING) {
                figures.put(version + " clamped", count("clamped|" + version));
                floor(short_, version + " clamped", count("clamped|" + version), BAND_EDGE);
            }
        }
        System.out.println("P13-TST-002 BAND EDGES: " + figures);
        assertThat(short_).as("band edges below their floor").isEmpty();
    }

    @Test
    @DisplayName("the planned threshold, lookback and window edges: every amount band at t-1, t, t+1; the counts, sums,"
            + " lookbacks, ages and dormancy at their threshold and one short; structuring's sum and below edges; the"
            + " rapid in-out share; every window kind's boundary; every corridor; the account-takeover hold")
    void everyPlannedEdgeIsReached() {
        List<String> short_ = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (RiskOperation operation : RiskBatteryWorld.JUDGED) {
            Statement statement = RiskBatteryOracles.v1(operation);
            for (Line line : statement.lines()) {
                if (line.kind() == RuleKind.AMOUNT_BAND) {
                    for (int delta = -1; delta <= 1; delta++) {
                        keys.add("edge|" + statement.name() + "|" + line.ordinal() + "|" + delta);
                    }
                }
            }
        }
        for (String row : List.of("TRANSFER v1|3", "TRANSFER v1|4", "TRANSFER v1|5", "TRANSFER v1|12",
                "WITHDRAWAL v1|2", "CARD_PAYMENT v1|6", "CARD_PAYMENT v1|7", "CARD_PAYMENT v1|10", "WALLET_PAYMENT v1|10",
                "CROSS_BORDER v1|6", "MONITORING v1|3", "MONITORING v1|8", "MONITORING v1|11")) {
            keys.add("edge|" + row + "|-1");
            keys.add("edge|" + row + "|0");
        }
        keys.add("edge|MONITORING v1|7|0");
        keys.add("edge|MONITORING v1|1|sum=0");
        keys.add("edge|MONITORING v1|1|sum=1");
        keys.add("edge|MONITORING v1|1|below=0");
        keys.add("edge|MONITORING v1|2|share=0");
        keys.add("edge|MONITORING v1|2|share=-1");
        for (WindowKind window : WindowKind.values()) {
            keys.add("boundary:" + window);
        }
        for (String corridor : RiskBatteryWorld.CORRIDORS) {
            keys.add("corridor|CROSS_BORDER v1|" + corridor);
            keys.add("corridor|MONITORING v1|" + corridor);
        }
        keys.add("ato|hold");
        Map<String, Long> figures = new LinkedHashMap<>();
        for (String key : keys) {
            figures.put(key, count(key));
            floor(short_, key, count(key), THRESHOLD_EDGE);
        }
        System.out.println("P13-TST-002 EDGES: " + figures);
        assertThat(short_).as("planned edges below their floor").isEmpty();
    }

    @Test
    @DisplayName("the fail-safe at every band of every judged version, at each band's edge, under every cause, the compiled"
            + " answers, and an amount in another currency - refused by the engine, answered by the open band")
    void theFailSafeIsReachedAtEveryBand() {
        List<String> short_ = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (RiskOperation operation : RiskBatteryWorld.JUDGED) {
            Statement statement = RiskBatteryOracles.v1(operation);
            for (int position = 1; position <= statement.failSafe().size(); position++) {
                keys.add("failSafeBand|" + operation + "|" + position);
            }
            for (Edge edge : statement.failSafe()) {
                if (edge.fromMinor() > 0) {
                    keys.add("failSafeEdge|" + operation + "|-1");
                    keys.add("failSafeEdge|" + operation + "|0");
                }
            }
            for (UnavailableCause cause : UnavailableCause.values()) {
                keys.add("failSafe|" + operation + "|" + cause);
                if (cause.unpinned()) {
                    keys.add("compiled|" + operation + "|" + cause);
                }
            }
            keys.add("unplaced|" + operation);
            keys.add("refused|" + operation + "|CURRENCY");
        }
        Map<String, Long> figures = new LinkedHashMap<>();
        for (String key : keys) {
            figures.put(key, count(key));
            floor(short_, key, count(key), FAIL_SAFE);
        }
        System.out.println("P13-TST-002 FAIL-SAFE: " + figures);
        assertThat(short_).as("fail-safe coverage below its floor").isEmpty();
    }

    // ------------------------------------------------------------------ plumbing

    /** Every score {@code statement}'s weights can sum to, clamped - the sums of every subset of its weighted rules. */
    static TreeSet<Integer> reachable(Statement statement) {
        TreeSet<Integer> sums = new TreeSet<>(List.of(0));
        for (Line line : statement.lines()) {
            if (line.weight().isPresent()) {
                TreeSet<Integer> more = new TreeSet<>(sums);
                for (int sum : sums) {
                    more.add(Math.min(RiskBatteryOracles.CEILING, sum + line.weight().getAsInt()));
                }
                sums = more;
            }
        }
        return sums;
    }

    private static long count(String key) {
        return run.coverage().getOrDefault(key, 0L);
    }

    private static void floor(List<String> short_, String what, long count, long floor) {
        if (count < floor) {
            short_.add(what + ": " + count + " < " + floor);
        }
    }
}
