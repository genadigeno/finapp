package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the objectives' judgement holds about itself ({@code P16-TSK-029}; ADR-0148; {@code SLOS.md} section 6;
 * {@code OPERATIONS_RUNBOOK.md} section 11.63; {@code RESILIENCE_EXPERIMENTS.md} E84, E85 and E86).
 *
 * <p>Every run is a cluster's, owed under {@code POSTPONED_ISSUES.md} PI-025, and nothing here claims an objective met. A judgement of
 * objectives fails by passing on a number it never read, on a threshold someone copied, on a silence nobody could have heard, or on a
 * decline counted as nothing because nothing was declined; each member refuses one of those:
 *
 * <ol>
 *   <li>the objectives, their budgets, their pairs and their SLIs are read from the committed rules at run time, never copied, and an
 *       objective is MET only at a burn rate of at most 1 with neither pair fired;
 *   <li>the load's judgement is recorded on every run and judged only when asked; judged, it refuses an objective it could not read and a
 *       window whose firing alerts do not show the alert planted before the load, and fails an objective not met, naming the only way to
 *       recalibrate one;
 *   <li>the decline run's control is on before the first arrival and off after the load, on a stop and on every way out; a refused card
 *       payment there counts only as a decline the provider gave, and the payment objective's errors are held to what the client saw;
 *   <li>the simulator declines by the key, so a retried key gets the answer it got;
 *   <li>the real burn (E85) is judged from Prometheus's own history: a pair that held for its wait and never fired fails, a fault that
 *       burned nothing could not measure, the page must reach its receiver, the sheds must be the fleet's 503s, and the fast pair must
 *       stop once the burn does;
 *   <li>a provider's outage is recorded against the payment objective and never judged: it spends no budget by design.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the objectives' judgement holds what it says it holds (P16-TSK-029)")
class ObjectivesRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String LOAD = "infra/scripts/kind-rehearsal/load.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String SIMULATOR = "infra/scripts/kind-rehearsal/Simulator.java";
    private static final String DIR = "infra/scripts/kind-rehearsal/load/";
    private static final String REGISTER = "docs/architecture/RESILIENCE_EXPERIMENTS.md";

    private static Path root;
    private static String load;
    private static String experiments;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        load = read(LOAD);
        experiments = read(EXPERIMENTS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String text, String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(text);
        assertThat(start.find()).as("the harness defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(text);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return text.substring(start.end(), end.start());
    }

    @Test
    @DisplayName("the objectives, budgets, pairs and SLIs are read from the committed rules, never copied; MET is a burn rate of at most 1"
            + " with neither pair fired")
    void theObjectivesAreReadFromTheRulesAndJudgedOnBoth() {
        String rules = function(load, "objectives_rules");
        assertThat(rules).as("both rule files, as committed")
                .contains("\"$ROOT/infra/prometheus/rules/slo-recording.yml\" \"$ROOT/infra/prometheus/rules/slo.yml\"")
                .contains("/^      - record: finapp:slo_[a-z_]+:error_ratio_rate5m$/").contains("/^      - alert: [A-Za-z]+Burn(Fast|Slow)$/")
                .as("the budget is the fast pair's own").contains("sub(/.*\\(14\\.4 \\* /, \"\", b)");
        String table = function(load, "objectives_table");
        assertThat(table).as("a line missing a field is refused, never judged on a blank")
                .contains("NF != 9 || $2 == \"\" || $3 == \"\" || $4 == \"\" || $6 == \"\" || $7 == \"\" || $9 == \"\" { bad = 1 }")
                .as("the SLI over the run's own window, its rates as increases")
                .contains("sed \"s/rate(/increase(/g; s/\\[5m\\]/[${span}s]/g\"")
                .as("the alerts that fired, read through the judgement, unreadable refused")
                .contains("objectives_fired $(( $4 - $2 )) \"$4\"").contains("! grep -qx UNREADABLE \"$1.fired\" || return 1")
                .as("MET needs both: the run's burn rate and silence")
                .contains("if (burn == \"-\") print \"NOT READ\"; else if (burn + 0 <= 1 && f == \"no\" && s == \"no\") print \"MET\"; else print \"NOT MET\"");
        assertThat(function(load, "objectives_fired")).as("every burn alert, at any evaluation of the window")
                .contains("max by (alertname) (max_over_time(ALERTS{alertstate=\\\"firing\\\",slo!=\\\"\\\"}[$1s]))");
        assertThat(table + rules).as("no budget or threshold written here").doesNotContain("0.001").doesNotContain("0.0005");
    }

    @Test
    @DisplayName("the load's judgement is recorded on every run and judged only when asked: an unread objective or a window with no"
            + " planted alert refused, an objective not met failed, recalibration left to a reviewed change")
    void theLoadsJudgementIsOptInAndNeverBlind() {
        assertThat(load).contains("LOAD_OBJECTIVES=${REHEARSAL_LOAD_OBJECTIVES:-0}")
                .contains("case $LOAD_OBJECTIVES in 0 | 1) ;; *) cannot \"REHEARSAL_LOAD_OBJECTIVES is 0 or 1");
        String judge = function(load, "load_objectives");
        assertThat(judge).contains("objectives_table \"$LOAD_ART/slo-objectives.tsv\" \"$LOAD_STARTED\" \"$LOAD_ENDED\" \"$now\"")
                .contains("[ \"$LOAD_OBJECTIVES\" = 1 ] || return 0")
                .as("the control: the migrator Job planted before the load is a firing alert of the window")
                .contains("alertname=\\\"MigratorJobFailed\\\"").contains("[ -n \"$control\" ] || cannot")
                .contains("$7 == \"NOT READ\"").contains("[ -z \"$unread\" ] || cannot")
                .contains("$7 == \"NOT MET\"").contains("[ -z \"$missed\" ] || fail")
                .contains("recalibrated only by a reviewed change to slo.yml and SLOS.md with its reason");
        assertThat(judge.indexOf("[ \"$LOAD_OBJECTIVES\" = 1 ] || return 0")).as("recorded before the opt-in, judged after it")
                .isGreaterThan(judge.indexOf("log \"recorded the objectives over the run"))
                .isLessThan(judge.indexOf("[ -n \"$control\" ] || cannot"));
        String scope = function(load, "load_scope");
        assertThat(scope.indexOf("load_objectives")).as("after the census, before the budget and while the planted Job still pages")
                .isGreaterThan(scope.indexOf("load_census")).isLessThan(scope.indexOf("load_budget"))
                .isLessThan(scope.indexOf("    platform_signals\n"));
        assertThat(scope.indexOf("platform_signals_plant")).isLessThan(scope.indexOf("load_run"));
    }

    @Test
    @DisplayName("the decline run: its control on before the first arrival and off after the load, on a stop and on every way out; a refusal"
            + " counted only as a decline the provider gave; the payment objective held to what the client saw")
    void theDeclineRunCountsEveryRefusalAsADecline() {
        assertThat(function(load, "load_preflight")).contains("share=$(load_profile_value declineShare)")
                .contains("cannot \"the profile's declineShare is a fraction between 0 and 1");
        String run = function(load, "load_run");
        assertThat(run.indexOf("[ -z \"$LOAD_DECLINE_PERCENT\" ] || load_declines on")).as("on before the generators start").isPositive()
                .isLessThan(run.indexOf("dock run -d --name \"$CLUSTER-load-k6-$i\""));
        assertThat(run.indexOf("    load_declines off\n")).as("off once every generator has stopped")
                .isGreaterThan(run.indexOf("LOAD_ENDED=$(date +%s)"));
        assertThat(function(load, "load_stopped")).contains("load_declines off");
        String finish = function(read(HARNESS), "finish");
        assertThat(finish.indexOf("    load_declines off\n")).as("on every way out, before the cluster may go").isPositive()
                .isLessThan(finish.indexOf("\"$KIND_BIN\" delete cluster --name \"$CLUSTER\""));
        assertThat(function(load, "load_declines")).contains("/tmp/decline-card-share").contains("[ -n \"$LOAD_DECLINES_ON\" ] || return 0")
                .contains("|| cannot \"the simulator's decline control does not read $LOAD_DECLINE_PERCENT\"");
        String judge = function(load, "load_judge");
        assertThat(judge).as("only a card payment's refusal, only in the decline run; refused otherwise still fails")
                .contains("if [ -n \"$LOAD_DECLINE_PERCENT\" ] && { [ \"$op\" = checkout ] || [ \"$op\" = topup ]; }; then")
                .contains("LOAD_DECLINED_REFUSED=$((LOAD_DECLINED_REFUSED + refused))")
                .contains("[ \"$refused\" = 0 ] || fail \"$op: $refused movements refused: see load/k6/report.tsv\"");
        String declines = function(load, "load_declines_judge");
        assertThat(declines).contains("WHERE status = 'FAILED' AND failure_reason = 'DECLINED'").contains("rail = 'card'")
                .contains("[ \"$declined\" -ge 1 ] || fail").contains("[ \"$declined\" = \"$LOAD_DECLINED_REFUSED\" ]")
                .contains("(s >= p - 5 && s <= p + 5)")
                .as("the payment objective's errors, P16-TSK-023's reading, within what the client saw answered 5xx or not at all")
                .contains("$1 == \"payment_checkout\" {print $4}").contains("\"$LOAD_ART/slo-availability.tsv\"")
                .contains("($2 ~ /^5/ || $2 == \"0\")").contains("(e <= c + 5 + c * 0.01)");
        String objectives = function(load, "load_objectives");
        assertThat(objectives.indexOf("[ -z \"$LOAD_DECLINE_PERCENT\" ] || load_declines_judge"))
                .as("the excused refusals judged on every decline run, whether or not the objectives are")
                .isPositive().isLessThan(objectives.indexOf("[ \"$LOAD_OBJECTIVES\" = 1 ] || return 0"));
        String scenarios = read(DIR + "scenarios.js");
        assertThat(scenarios).as("a declined checkout's session waits for its expiry, so the payer's own payment is read beside it, and"
                        + " FAILED there is a refusal - only where the profile declines")
                .contains("const intent = profile.declineShare && status === 'PAYMENT_PENDING' ? json(response, 'paymentIntentId') : undefined;")
                .contains("if (payment.status === 200 && json(payment, 'status') === 'FAILED') {\n                return 'FAILED';")
                .contains("status = pollCheckout(op, checkoutId, who.token);")
                .contains("conclude(op, status === 'COMPLETED' ? null : status === 'FAILED' ? REFUSED : UNRESOLVED, started);")
                .contains("conclude(op, status === 'SUCCEEDED' ? null : status === 'FAILED' ? REFUSED : UNRESOLVED, started);");
        String profile = read(DIR + "declines.json");
        assertThat(profile).contains("  \"declineShare\": 0.3,\n").contains("  \"stages\": [[1200, 1.0]],\n").doesNotContain("\"stress\"");
        String peak = read(DIR + "peak.json");
        assertThat(profile.substring(profile.indexOf("  \"retryShare\""))).as("the target's mix at its peak, as the peak profile drives it")
                .isEqualTo(peak.substring(peak.indexOf("  \"retryShare\"")));
    }

    @Test
    @DisplayName("the simulator declines its share by the key, so every retry of a key gets the answer it got")
    void theSimulatorDeclinesByTheKey() {
        String simulator = read(SIMULATOR);
        assertThat(simulator).contains("Path.of(\"/tmp/decline-card-share\")")
                .contains("return share > 0 && Math.floorMod(key.hashCode(), 100) < share;")
                .contains("key -> declined(key) ? \"{\\\"status\\\":\\\"declined\\\",\\\"reason\\\":\\\"do_not_honor\\\"}\"")
                .as("the first answer to a key is kept and replayed").contains("ANSWERED.computeIfAbsent(path + \"|\" + key");
    }

    @Test
    @DisplayName("the real burn is judged from Prometheus's history: a pair that held and never fired fails, a burn that never happened"
            + " could not measure, the page routed, the sheds the fleet's 503s, the fast pair stopped once the burn did")
    void aRealBurnIsJudgedFromPrometheusHistory() {
        String held = function(load, "objectives_held");
        assertThat(held).as("held unbroken for the pair's for and a minute, every 15 s, and fired as Prometheus wrote ALERTS")
                .contains("points=$(( (wait + 60) / 15 ))")
                .contains("max(max_over_time((count_over_time(($expr)[$((wait + 60))s:15s]) >= $points)[${span}s:15s]))")
                .contains("ALERTS{alertstate=\\\"firing\\\",alertname=\\\"$alert\\\"}");
        assertThat(function(experiments, "E85_steady")).contains("OBJECTIVES_GET=experiments_prometheus")
                .contains("[ -z \"$burning\" ] || cannot").contains("experiments_start_workload e85 1800");
        assertThat(function(experiments, "E85_fault")).as("E51's toxic, through the registry and its guard")
                .contains("experiments_fault_on toxic postgres e85-latency latency \"latency=$E85_LATENCY_MS\"");
        String observe = function(experiments, "E85_observe");
        assertThat(observe).contains("deadline=$((E85_FAULT_AT + E85_HOLD_SECONDS))").contains("firing=$(experiments_pages_firing)")
                .contains("EXP_STOPPED=\"a correctness page while the database was slow").contains("grep 'BurnFast$'");
        String recover = function(experiments, "E85_recover");
        assertThat(recover).contains("objectives_held \"$EXP_DIR/held.tsv\" \"$E85_FAULT_AT\" \"$now\"")
                .contains("[ \"$held\" -ge 1 ] || cannot").contains("$3 == \"held\" && $4 != \"fired\"").contains("[ -z \"$unfired\" ] || fail")
                .contains("signals_routed \"$E85_PAGED\" || fail").contains("[ -n \"$cleared\" ] || fail")
                .contains("status=\\\"503\\\"").contains("(a + 0 >= s * 0.95)")
                .contains("[ \"$(workload_unresolved e85)\" = 0 ] || fail").contains("assert_flows_ran e85");
        assertThat(experiments).as("E85's bounds").contains("E85_HOLD_SECONDS=900").contains("E85_CLEAR_SECONDS=600");
    }

    @Test
    @DisplayName("a provider's outage is recorded against the payment objective in E17 and never judged: it spends no budget by design")
    void aProviderOutageIsRecordedNeverJudged() {
        String observe = function(experiments, "E17_observe");
        assertThat(observe).contains("experiments_record \"payment objective during the fault\" \"PaymentCheckoutBurnFast $(provider_page PaymentCheckoutBurnFast)")
                .doesNotContain("fail ");
    }

    @Test
    @DisplayName("each of E84, E85 and E86 is registered with the run that makes it")
    void theRunsAreRegistered() {
        String register = read(REGISTER);
        assertThat(row(register, "E84")).contains("REHEARSAL_LOAD_PROFILE=sustained").contains("REHEARSAL_LOAD_OBJECTIVES=1")
                .contains("`P16-TSK-029`");
        assertThat(row(register, "E85")).contains("REHEARSAL_EXPERIMENTS").contains("E85").contains("`P16-TSK-029`");
        assertThat(row(register, "E86")).contains("REHEARSAL_LOAD_PROFILE=declines").contains("REHEARSAL_LOAD_OBJECTIVES=1")
                .contains("`P16-TSK-029`");
    }

    private static String row(String register, String id) {
        Matcher row = Pattern.compile("(?m)^\\| " + id + " \\|.*$").matcher(register);
        assertThat(row.find()).as("the register has %s", id).isTrue();
        return row.group();
    }
}
