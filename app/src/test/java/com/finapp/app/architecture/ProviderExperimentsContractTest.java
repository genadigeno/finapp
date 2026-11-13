package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the provider experiments hold about themselves ({@code P16-TSK-018}; {@code RESILIENCE_EXPERIMENTS.md} E17 to E21;
 * {@code OPERATIONS_RUNBOOK.md} section 11.53; {@code docs/architecture/TIMEOUTS_AND_RETRIES.md} section 5): the simulator's degradation
 * controls and the experiments scope's E17, E18 and E21, and the reasons E19 and E20 refuse to run.
 *
 * <p>None of them has run: their runs are owed under {@code POSTPONED_ISSUES.md} PI-025, and nothing here claims a result. What a hermetic
 * test can hold is that the harness measures what each register row names, and that a later edit cannot quietly take that away. A provider
 * experiment fails uselessly in three ways - a degraded provider that still took the effect, so a payment the platform concluded failed was
 * in fact made; a check that reads nothing and passes; a fault left on - and each member refuses one way of doing that:
 *
 * <ol>
 *   <li>the simulator degrades one rail at a time, before any effect is taken or recorded, and records every request it received;
 *   <li>every degradation flag is a fault the registry puts on and takes off, and the exit trap clears every one of them;
 *   <li>E17 holds the card rail unavailable and then silent, and judges both sides: no payment concluded without an answer the provider
 *       took, no blind resend, every unknown resolved once within the sweep's bound read from the code, the failure rate per rail recorded;
 *   <li>E18 takes the FX provider and then the reference away, and counts no conversion on a stale rate over conversions that exist;
 *   <li>E21 slows every provider to two and ten times its steady latency and holds the routes that call none within their objectives;
 *   <li>a stop condition is read in the harness's own shell and fails closed; E19 and E20 refuse to run, each naming its reason;
 *   <li>the owed runs are listed under PI-025.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the provider experiments measure what their register rows name (P16-TSK-018)")
class ProviderExperimentsContractTest {

    private static final String SIMULATOR = "infra/scripts/kind-rehearsal/Simulator.java";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String POSTPONED = "docs/project/POSTPONED_ISSUES.md";

    private static Path root;
    private static String simulator;
    private static String experiments;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        simulator = read(SIMULATOR);
        experiments = read(EXPERIMENTS);
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(experiments);
        assertThat(start.find()).as("the script defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(experiments);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return experiments.substring(start.end(), end.start());
    }

    /** The body of one Java method of the simulator, from its signature to the next method's. */
    private static String method(String signature) {
        int start = simulator.indexOf(signature);
        assertThat(start).as("the simulator declares %s", signature).isNotNegative();
        int end = simulator.indexOf("\n    private static ", start + signature.length());
        return simulator.substring(start, end < 0 ? simulator.length() : end);
    }

    /** {@code first} appears in {@code text}, and before {@code then}. */
    private static void before(String text, String first, String then, String what) {
        assertThat(text).as(what).contains(first).contains(then);
        assertThat(text.indexOf(first)).as(what).isLessThan(text.indexOf(then));
    }

    /** The simulator's rails, as its RAILS map declares them. */
    private static List<String> rails() {
        Matcher rails = Pattern.compile("RAILS = Map\\.of\\(([^;]*)\\);").matcher(simulator);
        assertThat(rails.find()).as("the simulator declares its rails").isTrue();
        Matcher rail = Pattern.compile("\"([a-z]+)\", List\\.of\\(").matcher(rails.group(1));
        List<String> names = new ArrayList<>();
        while (rail.find()) {
            names.add(rail.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("the simulator degrades one rail at a time before any effect is taken or recorded, and records every request it received")
    void theSimulatorDegradesBeforeAnyEffect() {
        assertThat(rails()).as("the rails the experiments degrade").contains("card", "fx", "reference", "payout", "tokenisation");
        assertThat(simulator).as("each rail's card paths, its inquiry among them")
                .contains("\"card\", List.of(\"/authorizations\", \"/captures\", \"/refunds\", \"/voids\", \"/operations/\")");
        String degraded = method("private static boolean degraded(");
        assertThat(degraded).as("unavailable: 503 at once; silent: held while the flag stands, then 503 to nobody")
                .contains("Path.of(DEGRADE + rail + \"-unavailable\")").contains("Path.of(DEGRADE + rail + \"-silent\")")
                .contains("waitWhile(silent, 1200);").contains("send(exchange, 503,");
        assertThat(degraded).as("a degraded request takes no effect and records no answer").doesNotContain("ANSWERED").doesNotContain("KEYS");
        before(method("private static void answer("), "degraded(exchange, path, asked)", "ANSWERED.computeIfAbsent(path",
                "the card, payout and tokenisation rails are degraded before the effect");
        before(method("private static void fxQuote("), "degraded(exchange, FX_QUOTES, key)", "ANSWERED.computeIfAbsent(FX_QUOTES",
                "an FX quote is degraded before it is quoted");
        before(method("private static void fxExecution("), "degraded(exchange, FX_EXECUTIONS, asked)", "ANSWERED.computeIfAbsent(FX_EXECUTIONS",
                "an FX execution is degraded before it is executed");
        before(method("private static void referenceRates("), "degraded(exchange, FX_REFERENCE, \"-\")", "send(exchange, 200,",
                "the reference is degraded before it answers");
        before(method("private static void operationInquiry("), "degraded(exchange, OPERATIONS, reference)", "send(exchange, 200,",
                "the card inquiry is degraded with its rail");
        assertThat(method("private static void writeCounts(")).as("every request's line written beside the counts")
                .contains("REQUEST_LOG.poll()").contains("Path.of(\"/tmp/requests.tsv\")");
        assertThat(method("private static void logged(")).as("when, how, where, which key, what became of it")
                .contains("System.currentTimeMillis() + \"\\t\" + exchange.getRequestMethod() + \"\\t\" + path + \"\\t\"");
    }

    @Test
    @DisplayName("every degradation flag is a fault the registry puts on and takes off, and every one is cleared on every way out")
    void everyDegradationIsAFaultTheRegistryClears() {
        Matcher listed = Pattern.compile("(?m)^EXPERIMENTS_DEGRADATIONS=\"([^\"]*)\"$").matcher(experiments);
        assertThat(listed.find()).as("the scope lists the simulator's degradation flags").isTrue();
        TreeSet<String> wanted = new TreeSet<>();
        for (String rail : rails()) {
            wanted.add("degrade-" + rail + "-unavailable");
            wanted.add("degrade-" + rail + "-silent");
        }
        assertThat(new TreeSet<>(List.of(listed.group(1).split(" ")))).as("every rail in both modes, and nothing else")
                .isEqualTo(wanted);
        assertThat(function("experiments_clear_faults")).as("the exit trap removes every degradation flag, whatever was registered")
                .contains("for flag in $EXPERIMENTS_DEGRADATIONS; do\n        experiments_simulator rm -f \"/tmp/$flag\"");
        assertThat(function("experiments_faults_left")).as("a flag still standing is read as a fault left on")
                .contains("grep -x 'degrade-[a-z]*-unavailable\\|degrade-[a-z]*-silent'");
        Matcher used = Pattern.compile("experiments_fault_on hold (\\S+)").matcher(experiments);
        int count = 0;
        while (used.find()) {
            if (used.group(1).startsWith("degrade-")) {
                count++;
                assertThat(wanted).as("a flag the experiments put on is one the simulator reads and the trap clears").contains(used.group(1));
            }
        }
        assertThat(count).as("the experiments put degradations on through the registry").isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("E17 degrades the card rail, unavailable then silent, and judges both sides: no conclusion without the provider's answer, no blind resend, every unknown resolved once")
    void e17MeasuresWhatItsRowNames() {
        String fault = function("E17_fault");
        before(fault, "experiments_fault_on hold degrade-card-unavailable", "experiments_fault_on hold degrade-card-silent",
                "the card rail unavailable first, then silent past the timeout");
        assertThat(fault).contains("experiments_fault_off hold degrade-card-unavailable");
        assertThat(function("provider_resolve_seconds")).as("the resolution bound read from the code's default, never copied")
                .contains("finapp\\.payments\\.sweeper\\.dispatched-age:PT\\([0-9]*\\)M}").contains("PaymentSweeperSchedule.java")
                .contains("PROVIDER_RESOLVE_SECONDS=$((2 * age * 60 + 300))");
        assertThat(function("E17_steady")).as("the client keeps asking under its keys past the outage and the resolution")
                .contains("WORKLOAD_RETRY_SECONDS=$((2 * PROVIDER_PHASE_SECONDS + PROVIDER_RESOLVE_SECONDS))").contains("start_workload e17 retry");
        assertThat(function("provider_blind_resends")).as("a second send is blind unless an answered inquiry of its key came between")
                .contains("if ((key in sent) && !asked[$4]) print").contains("else if ($5 == \"inquiry\") asked[$4] = 1");
        String unanswered = function("provider_card_unanswered");
        assertThat(unanswered).as("authorized and captured attempts each need the effect the provider took")
                .contains("a.status IN ('AUTHORIZED', 'CAPTURED')").contains("$5 == \"effect\"")
                .contains("captured with no capture the provider took").contains("authorized with no authorization the provider took")
                .doesNotContain("cannot").doesNotContain("fail ");
        String recover = function("E17_recover");
        for (String check : List.of("provider_card_waiting", "unanswered=$(provider_card_unanswered)", "twice=$(provider_card_resolved_twice)",
                "blind=$(provider_blind_resends \"$PROVIDER_START_MILLIS\")", "provider_failure_rates steady unavailable",
                "provider_failure_rates silent returned")) {
            assertThat(recover).as("E17's recovery reads %s", check).contains(check);
        }
        assertThat(recover).as("the control: something was degraded, or nothing was shown").contains("[ \"$degraded\" -ge 1 ] || fail");
        assertThat(function("provider_card_resolved_twice")).contains("from_status LIKE '%\\_UNKNOWN'").contains("HAVING count(*) > 1");
        assertThat(function("provider_failure_rates")).as("failed and unknown judgements per rail, recorded and never judged")
                .contains("$2 == \"FAILED\" || $2 == \"UNKNOWN\"").contains("experiments_record \"failure rate per rail");
    }

    @Test
    @DisplayName("E18 takes the FX provider and then the reference away, and counts no conversion on a stale rate over conversions that exist")
    void e18MeasuresWhatItsRowNames() {
        assertThat(read(HARNESS)).as("the experiments scope names the FX provider and the reference")
                .contains("[ \"$SCOPE\" != experiments ] || EXTRA_PROVIDER_NAMES=\"$EXTRA_PROVIDER_NAMES FINAPP_FX_REFERENCE_URL FINAPP_FX_PROVIDER_URL\"");
        String steady = function("E18_steady");
        assertThat(steady).as("the pricing policy by two persons, its maximum age read, a conversion booked before the fault")
                .contains("client activate-pricing").contains("reference_max_age_seconds").contains("e18_conversion e18-steady");
        String fault = function("E18_fault");
        before(fault, "experiments_fault_on hold degrade-fx-unavailable", "experiments_fault_on hold degrade-reference-unavailable",
                "the provider out first, then the reference");
        assertThat(fault).as("every conversion refused while no firm quote can be had").contains("[ \"$refused\" = 3 ] || fail");
        String observe = function("E18_observe");
        assertThat(observe).as("past the maximum age, refused for the stale reference itself")
                .contains("provider_watch $((PROVIDER_MAX_AGE + 60))").contains("provider_quote_outcome refused_reference_stale")
                .contains("[ \"$stale_refused\" = 3 ] || fail").contains("[ $((after - before)) -ge 3 ] || fail");
        assertThat(function("provider_fx_stale")).as("a stale rate: a reference older than its pair's maximum age, or an expired quote")
                .contains("q.issued_at - s.received_at > p.reference_max_age_seconds * interval '1 second'").contains("t.booked_at > q.expires_at");
        assertThat(function("E18_recover")).as("the count read over conversions that exist").contains("[ \"$stale\" = 0 ] || fail")
                .contains("[ \"${booked:-0}\" -ge 2 ] || fail");
    }

    @Test
    @DisplayName("E21 slows every provider to two and then ten times its steady latency, and holds the routes that call none within their objectives")
    void e21MeasuresWhatItsRowNames() {
        assertThat(function("E21_steady")).as("the steady latency read, not assumed").contains("E21_NORMAL=$(provider_latency_ms 2m)");
        String fault = function("E21_fault");
        before(fault, "experiments_fault_on toxic simulator e21-twice latency \"latency=$E21_NORMAL\"",
                "experiments_fault_on toxic simulator e21-tenfold latency \"latency=$((9 * E21_NORMAL))\"",
                "two times (one more of its latency), then ten times (nine more)");
        assertThat(function("provider_unaffected_ratios")).as("the routes that call no provider, against their budgets (SLOS.md section 2)")
                .contains("transfer_latency:0.01").contains("authentication_latency:0.05").contains("finapp:slo_${name}:error_ratio_rate5m");
        assertThat(function("provider_unaffected_breach")).as("past an objective for five minutes: twenty passes of 15 s")
                .contains("[ \"${E21_OVER:-0}\" -lt 20 ] || PROVIDER_STOP=");
        assertThat(function("provider_bulkhead_refusals")).as("the bulkhead's refusals, counted from every replica's lines")
                .contains("Too many hinted inquiries are running on this instance");
        assertThat(function("E21_recover")).contains("[ -z \"$unread\" ] || fail");
    }

    @Test
    @DisplayName("a stop condition is read in the harness's own shell and fails closed; E19 and E20 refuse to run, each naming its reason")
    void stopsAreReadHereAndTheRefusalsNameTheirReasons() {
        String watch = function("provider_watch");
        assertThat(watch).as("read in this shell: a count that survives passes, a refusal that ends the run")
                .contains("\n        provider_stop_condition\n").doesNotContain("$(provider_stop_condition)")
                .contains("experiments_pages_firing");
        String stop = function("provider_stop_condition");
        assertThat(stop).as("a count nobody could read is a stop, never a zero").contains("[ \"$stale\" = 0 ] || PROVIDER_STOP=")
                .doesNotContain("${stale:-0}");
        assertThat(function("E19_steady")).contains("cannot \"E19 cannot be driven: no production path runs a KYC case's checks")
                .contains("THREAT_MODEL.md section 6 item 60");
        assertThat(function("E20_steady")).contains("cannot \"E20 rests on the recorded fail-safe")
                .contains("the owner decided on 2026-11-13").contains("unresolved question 13");
    }

    @Test
    @DisplayName("every run these experiments owe is listed under PI-025, with the runbook section that will carry its record")
    void theOwedRunsAreListed() {
        String postponed = read(POSTPONED);
        int at = postponed.indexOf("  - **`P16-TSK-018`**");
        assertThat(at).as("PI-025 lists P16-TSK-018's owed runs").isPositive();
        int end = postponed.indexOf("\n  - ", at + 1);
        String bullet = postponed.substring(at, end < 0 ? postponed.length() : end);
        assertThat(bullet).contains("E17").contains("E18").contains("E21").contains("section 11.53").contains("three replicas");
    }
}
