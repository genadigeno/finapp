package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the experiments scope holds about security under scale and failure ({@code P16-TSK-028}, ADR-0147;
 * {@code PHASE_16_PLAN.md} section 18; {@code OPERATIONS_RUNBOOK.md} section 11.62).
 *
 * <p>A security control fails under load or failure in one of two ways a run can see: a request served without its check while
 * a part of the platform is away (a fail-open), or a credential or a four-eyes act the platform held before the fault no longer
 * held after it. The runs that would see either are owed under {@code POSTPONED_ISSUES.md} PI-025, and nothing here claims a
 * result. What a hermetic test can hold is that a later edit does not quietly take away what makes such a run able to see it:
 *
 * <ol>
 *   <li>every experiment carries the security probe, from just before its fault to the end of its recovery, through the one runner
 *       every experiment goes through, and none of an experiment's own steps;
 *   <li>the probe asks every application pod directly, with no credential and a forged one at a customer's, an operator's and a
 *       merchant's route, and with three held credentials, which travel on stdin;
 *   <li>its control, before the fault, shows each held credential served and each protected request refused on every ready replica,
 *       or the run cannot go on;
 *   <li>its judge fails the experiment on a protected request answered 2xx or 3xx, on a held credential refused, and on a fault no look
 *       was answered in;
 *   <li>the four-eyes plant is refused for its proposer and for the right reason, never a 2xx, each 403 recorded, and the plant left
 *       proposed; and the audit trail read before the fault is whole after it;
 *   <li>E05's sign-in counts across the failover are recorded as read, never a verdict;
 *   <li>E81 counts exactly the door's own limit across every replica, inside one window;
 *   <li>E82 replaces every replica and asks each new one with the held sessions;
 *   <li>the client prints no held credential.
 * </ol>
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("the experiments carry the security probe, the four-eyes plant and the audit comparison, and E81 and E82 count what their rows name (P16-TSK-028)")
class SecurityUnderFailureContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String EXPERIMENTS = "infra/scripts/kind-rehearsal/experiments.sh";
    private static final String CLIENT = "infra/scripts/kind-rehearsal/Rehearsal.java";

    private static String harness;
    private static String experiments;
    private static String client;

    @BeforeAll
    static void readTheHarness() {
        Path root = EvidenceCitations.repositoryRoot();
        harness = EvidenceCitations.read(root.resolve(HARNESS)).replace("\r\n", "\n");
        experiments = EvidenceCitations.read(root.resolve(EXPERIMENTS)).replace("\r\n", "\n");
        client = EvidenceCitations.read(root.resolve(CLIENT)).replace("\r\n", "\n");
    }

    /** The body of one shell function, so an assertion cannot be satisfied by another function's text. */
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(experiments);
        assertThat(start.find()).as("the script defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(experiments);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return experiments.substring(start.end(), end.start());
    }

    private static void inOrder(String text, List<String> parts) {
        int at = -1;
        for (String part : parts) {
            int next = text.indexOf(part, at + 1);
            assertThat(next).as("%s after the part before it", part).isGreaterThan(at);
            at = next;
        }
    }

    @Test
    @DisplayName("every experiment carries the probe through the one runner, from just before its fault to the end of its recovery")
    void everyExperimentCarriesTheProbe() {
        assertThat(function("experiments_scope")).as("every experiment goes through the runner").contains("experiment_run \"$id\"");
        inOrder(function("experiment_run"), List.of("\"${1}_steady\"", "security_steady \"$1\"", "step \"$1-fault\"", "security_fault \"$1\"",
                "\"${1}_fault\"", "security_during \"$1\"", "\"${1}_observe\"", "security_observed \"$1\"", "experiments_stop",
                "echo recover > \"$EXP_DIR/security-phase\"", "\"${1}_recover\"", "security_after \"$1\"", "census \"$1\""));
        String fault = function("security_fault");
        assertThat(fault).as("the audit trail read, then the probe started, before the fault")
                .contains("security_audit_snapshot before").contains("security_sampler \"$EXP_DIR\" &").contains("BACKGROUND=\"$BACKGROUND $SEC_SAMPLER\"");
        assertThat(fault.indexOf("security_audit_snapshot before")).isLessThan(fault.indexOf("security_sampler"));
        assertThat(function("security_sampler")).as("a look every 5 s in the phase named, until stopped")
                .contains("while [ ! -f \"$1/security-stop\" ]").contains("security_look \"$1\" \"$(cat \"$1/security-phase\"").contains("sleep 5");
    }

    @Test
    @DisplayName("the probe asks every application pod directly: no credential and a forged one at three doors, and three held credentials on stdin")
    void theProbeAsksEveryReplicaDirectly() {
        String look = function("security_look");
        assertThat(look).as("every application pod's address, from the fleet, straight from the edge's probe pod")
                .contains("experiments_fleet | awk -F'|' 'NF >= 5 && $5 != \"\" {print $5}'")
                .contains("kube -n finapp-edge exec -i np-edge -c probe").contains("u=\"http://$ip:8080/v1\"");
        assertThat(look).as("no credential and a forged one at a customer's, an operator's and a merchant's route")
                .contains("customer-none %{http_code}\\n\" \"$u/me\"")
                .contains("customer-forged %{http_code}\\n\" -H \"Authorization: Bearer $stranger\" \"$u/me\"")
                .contains("operator-forged %{http_code}\\n\" -H \"Authorization: Bearer $stranger\" \"$u/operator/events/dead-letters\"")
                .contains("merchant-forged %{http_code}\\n\" -H \"Authorization: Bearer $forged\" \"$u/merchant/me\"");
        assertThat(look).as("the held credentials read from stdin, never a command line")
                .contains("IFS= read -r customer").contains("IFS= read -r operator").contains("IFS= read -r merchant")
                .contains("printf '%s\\n%s\\n%s\\n' \"$SEC_CUSTOMER\" \"$SEC_OPERATOR\" \"$SEC_MERCHANT\" |")
                .contains("customer-held %{http_code}\\n\" -H \"Authorization: Bearer $customer\"")
                .contains("operator-held %{http_code}\\n\" -H \"Authorization: Bearer $operator\"")
                .contains("merchant-held %{http_code}\\n\" -H \"Authorization: Bearer $merchant\"");
        assertThat(look).as("a forged merchant key of the stored shape, so it reaches the store; a look not made is recorded as one")
                .contains("\"$(security_uuid).$(experiments_rand)\"").contains("printf '%s\\t%s\\t-\\texec\\t000\\n'");
        assertThat(function("security_uuid")).as("a version-7 identifier of the RFC variant: any other is refused as malformed before the store")
                .contains("\\1-\\2-7\\3-8\\4-\\5");
        assertThat(experiments).contains("SECURITY_REFUSED_CASES=\"customer-none customer-forged operator-forged merchant-forged\"")
                .contains("SECURITY_HELD_CASES=\"customer-held operator-held merchant-held\"");
    }

    @Test
    @DisplayName("before the fault, the probe's control: each held credential served 200 and each protected request refused 401 on every ready replica, or the run cannot go on")
    void theProbeHasItsControl() {
        String steady = function("security_steady");
        inOrder(steady, List.of("security_tokens \"$1\"", "security_look \"$EXP_DIR\" control", "off=$(security_expected control)",
                "[ -z \"$off\" ] || cannot", "security_plant", "SEC_SELF_BEFORE=$(security_self_records)"));
        String expected = function("security_expected");
        assertThat(expected).as("every ready replica, every case, the held 200 and the others 401")
                .contains("$2 == \"True\" && $6 == \"\"").contains("for se_case in $SECURITY_HELD_CASES $SECURITY_REFUSED_CASES")
                .contains("se_want=200").contains("se_want=401");
        assertThat(function("security_tokens")).as("fresh sessions from the client, read from its file and the file removed at once")
                .contains("client security-tokens \"$1\"").contains("rm -f \"$CLIENT/security-$1.tokens\"").contains("|| cannot");
    }

    @Test
    @DisplayName("the judge fails an experiment on a fail-open, on a held credential refused, and on a fault no look was answered in")
    void theJudgeFailsOnWhatItIsThereToSee() {
        String after = function("security_after");
        assertThat(after).as("the probe stopped before it is judged").contains("touch \"$EXP_DIR/security-stop\"").contains("wait \"$SEC_SAMPLER\"");
        assertThat(after).as("every phase but the control judged; a protected request answered 2xx or 3xx is a fail-open")
                .contains("NR > 1 && $2 != \"control\"").contains("if (!h && $5 ~ /^[23]/) open++")
                .contains("[ \"$sa_open\" = 0 ] || EXP_STOPPED=").contains("without their check (fail-open)");
        assertThat(after).as("a held credential refused is a session lost")
                .contains("if (h && ($5 == 401 || $5 == 403)) lost++").contains("[ \"$sa_lost\" = 0 ] || EXP_STOPPED=");
        assertThat(after).as("a fault with no answered look proves nothing")
                .contains("if (!h && $2 == \"fault\" && $4 != \"exec\" && $5 != \"000\") answered++")
                .contains("[ \"$sa_answered\" -ge 1 ] || EXP_STOPPED=");
        assertThat(after).as("every held credential served and every protected request refused again after the recovery")
                .contains("security_look \"$EXP_DIR\" after").contains("off=$(security_expected after)");
    }

    @Test
    @DisplayName("four eyes: the plant's proposer holds both roles, its own activation is never a 2xx, refused for its reason after, each 403 recorded, the plant still proposed")
    void fourEyesHoldsForItsProposer() {
        String plant = function("security_plant");
        assertThat(plant).as("once a run; the controller given the accountant's role on the run's own server, then its own proposal")
                .contains("[ -z \"$(experiments_state security.mapping)\" ] || return 0").contains("'ACCOUNTANT'")
                .contains("client four-eyes-plant").contains("'^four-eyes-plant proposed status=201 '");
        assertThat(function("security_during")).as("tried while the fault is on, in the background").contains("client four-eyes-self \"$1-during\"")
                .contains("&\n");
        String after = function("security_after");
        assertThat(after).as("never a 2xx while the fault was on; after it, refused for its reason")
                .contains("case ${sa_during%% *} in 2*) EXP_STOPPED=").contains("four eyes relaxed")
                .contains("[ \"$sa_after\" = \"403 accounting.SelfApprovalRefused\" ]");
        assertThat(after).as("every 403 recorded, and the plant never activated")
                .contains("[ \"$sa_recorded\" -ge \"$sa_forbidden\" ]").contains("a refusal without its record")
                .contains("[ \"$sa_plant\" = PROPOSED ] || EXP_STOPPED=");
        assertThat(function("security_self_records")).contains("operation = 'accounting.SelfApprovalRefused'");
    }

    @Test
    @DisplayName("the audit trail read before the fault, every record by a digest of its row, present and unchanged after the recovery")
    void theAuditTrailIsComparedByDigest() {
        assertThat(function("security_audit_snapshot")).contains("SELECT audit_id, md5(a::text) FROM platform.audit_record a ORDER BY audit_id")
                .contains("a comparison over nothing proves nothing");
        String after = function("security_after");
        assertThat(after).contains("security_audit_snapshot after").contains("if (!($1 in after)) m++; else if (after[$1] != $2) c++")
                .contains("[ \"$sa_missing\" = 0 ] && [ \"$sa_changed\" = 0 ]").contains("the audit trail before the fault is not whole after it");
    }

    @Test
    @DisplayName("E05's sign-in counts across the failover are read in one window and recorded, never a verdict")
    void theFreshBudgetsAreMeasuredNotJudged() {
        assertThat(function("security_fault")).as("this source counted, then the window's counts, before the fault")
                .contains("if [ \"$1\" = E05 ]; then").contains("security_sign_ins \"$sf_ip\" 3").contains("security_counts \"$SEC_WINDOW\" > \"$EXP_DIR/counts-before.tsv\"");
        String observed = function("security_observed");
        assertThat(observed).as("E05 only, the same window or not measured, recorded and never a stop")
                .contains("[ \"$1\" = E05 ] || return 0").contains("[ \"$(security_window_start)\" != \"$SEC_WINDOW\" ]").contains("not measured")
                .contains("experiments_record \"fresh budgets\"").doesNotContain("EXP_STOPPED");
        assertThat(function("security_counts")).contains("scope = 'door.sign-in'");
        assertThat(function("security_stride")).as("the window application.yaml's, refused if the overlay moves it")
                .contains("window: ${FINAPP_ABUSE_SIGN_IN_WINDOW:").contains("|| cannot \"the rendered overlay sets the sign-in door's window");
    }

    @Test
    @DisplayName("E81 sets the door's own limit, waits for a window it fits in, spreads one source's burst over every replica and counts exactly the limit")
    void e81CountsExactlyTheLimit() {
        assertThat(function("E81_steady")).as("the limit application.yaml's own, read").contains("limit: ${FINAPP_ABUSE_SIGN_IN_LIMIT:");
        String fault = function("E81_fault");
        assertThat(fault).as("the limit a registered setting, the fleet rolled to it and every ready replica in the burst")
                .contains("experiments_fault_on setting FINAPP_ABUSE_SIGN_IN_LIMIT \"$E81_LIMIT\"").contains("rollout status deployment/finapp-app")
                .contains("= \"$FLEET_REPLICAS\" ] || cannot");
        assertThat(fault).as("a fresh window when less than three minutes are left; the boundary read before the burst")
                .contains("[ \"$left\" -ge 180 ] || sleep $((left + 5))").contains("experiments_context_check || fail");
        assertThat(fault).as("three times the limit from one source, two to each replica at a time")
                .contains("for ip in \"$@\" \"$@\"; do").contains("probe $((3 * E81_LIMIT)) $E81_IPS");
        String observe = function("E81_observe");
        assertThat(observe).as("one window, every replica reached, exactly the limit admitted, more than one replica admitting, the row at the limit")
                .contains("|| cannot \"E81: the burst crossed the door's window end").contains("[ \"$reached\" = \"$FLEET_REPLICAS\" ] || cannot")
                .contains("[ \"$admitted\" = \"$E81_LIMIT\" ]").contains("[ \"$admitting\" -ge 2 ]")
                .contains("[ \"$at_limit\" -ge 1 ] && [ \"$above\" = 0 ]");
        assertThat(observe).as("a 429 is refused, a 5xx or no answer is no count, everything else admitted")
                .contains("if ($2 == 429) r++; else if ($2 == \"000\") u++; else if ($2 ~ /^5/) v++; else a++");
        assertThat(function("experiments_clear_faults")).as("the setting removed on every way out")
                .contains("kube -n finapp set env deployment/finapp-app FINAPP_ABUSE_SIGN_IN_LIMIT-");
        assertThat(harness).as("written for a count").contains("EXPERIMENTS_AT_A_COUNT=\"$EXPERIMENTS_AT_A_COUNT E81 E82\"");
    }

    @Test
    @DisplayName("E82 replaces every replica by a rolling restart and asks each new one with the sessions held from before it")
    void e82AsksEveryNewReplica() {
        assertThat(function("E82_steady")).contains("E82_BEFORE=$(experiments_fleet");
        assertThat(function("E82_fault")).contains("experiments_context_check || fail").contains("rollout restart deployment/finapp-app");
        String observe = function("E82_observe");
        inOrder(observe, List.of("rollout status deployment/finapp-app", "ready_fleet 400", "security_look \"$EXP_DIR\" rolled",
                "off=$(security_expected rolled)", "case \" $E82_BEFORE \" in"));
        assertThat(observe).as("every replica new").contains("[ -z \"$kept\" ] && [ \"$replaced\" = \"$FLEET_REPLICAS\" ]");
    }

    @Test
    @DisplayName("the client holds the sessions in a file and prints none; the four-eyes plant's proposer is the one who tries its activation")
    void theClientPrintsNoCredential() {
        Matcher tokens = Pattern.compile("private void securityTokens\\(String label\\) throws Exception \\{(.*?)\n    }\n", Pattern.DOTALL).matcher(client);
        assertThat(tokens.find()).isTrue();
        assertThat(tokens.group(1)).contains("Files.writeString(work.resolve(\"security-\" + label + \".tokens\")")
                .contains("System.out.println(\"security-tokens \" + label + \" customer=held operator=held\")")
                .doesNotContain("println(\"security-tokens \" + label + \" \" + customer").doesNotContain("+ operator)");
        Matcher plant = Pattern.compile("private void fourEyesPlant\\(\\) throws Exception \\{(.*?)\n    }\n", Pattern.DOTALL).matcher(client);
        assertThat(plant.find()).isTrue();
        assertThat(plant.group(1)).contains("fresh(\"controller\")").contains("/v1/operator/accounting/mapping-versions\"");
        Matcher self = Pattern.compile("private void fourEyesSelf\\(String label\\) throws Exception \\{(.*?)\n    }\n", Pattern.DOTALL).matcher(client);
        assertThat(self.find()).isTrue();
        assertThat(self.group(1)).contains("controller = fresh(\"controller\")").contains("\"/activation\"");
    }
}
