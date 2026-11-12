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
 * What the platform rehearsals hold about themselves (`P15-TSK-032`; {@code OPERATIONS_RUNBOOK.md} section 11.38): the
 * {@code signals} and {@code incidents} scopes of the kind rehearsal, each procedure of the runbook against the condition it is
 * for, the alert read from the cluster's own Prometheus and Alertmanager, the audit trails read back.
 *
 * <p>The rehearsals' results are runs, recorded in the runbook, and nothing here claims them. What a hermetic test can hold is that
 * a later edit does not quietly remove what a run proves: an assertion dropped, a plant whose alert is no longer waited for, a
 * break-glass refusal no longer tried, an audit field no longer read.
 */
@Tag("architecture")
@DisplayName("the platform rehearsals hold what they say they hold (P15-TSK-032)")
class PlatformRehearsalContractTest {

    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";
    private static final String PLATFORM = "infra/scripts/kind-rehearsal/platform.sh";
    private static final String CLIENT = "infra/scripts/kind-rehearsal/Rehearsal.java";
    private static final String AUDIT_POLICY = "infra/scripts/kind-rehearsal/audit-policy.yaml";

    private static Path root;
    private static String harness;
    private static String platform;
    private static String client;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        harness = EvidenceCitations.read(root.resolve(HARNESS));
        platform = EvidenceCitations.read(root.resolve(PLATFORM));
        client = EvidenceCitations.read(root.resolve(CLIENT));
    }

    private static String read(String path) {
        return EvidenceCitations.read(root.resolve(path));
    }

    /** The body of one shell function of platform.sh, so an assertion cannot be satisfied by another step's text. */
    private static String function(String name) {
        Matcher start = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\(\\) \\{$").matcher(platform);
        assertThat(start.find()).as("platform.sh defines %s", name).isTrue();
        Matcher end = Pattern.compile("(?m)^}$").matcher(platform);
        assertThat(end.find(start.end())).as("%s ends", name).isTrue();
        return platform.substring(start.end(), end.start());
    }

    @Test
    @DisplayName("the two scopes deploy release N alone, source platform.sh at the scope branch, and run each of its steps in order")
    void thePlatformScopesRunReleaseNAloneAndSourceTheirSteps() {
        assertThat(harness).contains("case $SCOPE in release | rotations | signals | incidents)")
                .contains("case $SCOPE in rotations | signals | incidents) N_ONLY=1 ;; esac")
                .contains("if [ \"$SCOPE\" = signals ] || [ \"$SCOPE\" = incidents ]; then").contains(". \"$DIR/platform.sh\"");
        assertThat(platform).as("every step of each scope dispatched")
                .contains("signals_alerting;").contains("signals_plants;").contains("signals_staff;").contains("signals_surface;")
                .contains("signals_first_role;").contains("signals_access_review;").contains("signals_domain;")
                .contains("signals_correctness;").contains("signals_operators_half;").contains("signals_telemetry;")
                .contains("signals_close;").contains("incidents_broker;").contains("incidents_dead_letters;")
                .contains("incidents_instance_down;").contains("incidents_fleet_blind;").contains("incidents_retention_and_abuse;")
                .contains("incidents_poisoned_rows;").contains("incidents_break_glass;").contains("incidents_books;")
                .contains("incidents_database_restart;");
    }

    @Test
    @DisplayName("the development mode runs only named steps against a kept cluster, and is refused under CI and without one (Q11)")
    void theDevelopmentModeIsRefusedUnderCiAndWithoutAKeptCluster() {
        assertThat(harness).contains("STEPS=${REHEARSAL_STEPS:-}")
                .contains("[ -z \"${CI:-}\" ] || { echo \"?? REHEARSAL_STEPS is a development mode, refused under CI")
                .contains("[ \"$KEEP\" = 1 ] || { echo \"?? REHEARSAL_STEPS needs REHEARSAL_KEEP=1")
                .contains("wanted() {");
    }

    @Test
    @DisplayName("only the platform scopes write an API audit log, and its policy records metadata or nothing, never a body (Q12)")
    void onlyThePlatformScopesWriteAnAuditLogAndItHoldsNoBody() {
        assertThat(harness).contains("case $SCOPE in signals | incidents) AUDITED=1 ;; esac").contains("if [ -n \"$AUDITED\" ]; then")
                .contains("audit-policy-file: /etc/kubernetes/audit-policy/policy.yaml");
        List<String> levels = Pattern.compile("(?m)^\\s*-?\\s*level:\\s*(\\S+)\\s*$").matcher(read(AUDIT_POLICY)).results()
                .map(match -> match.group(1)).toList();
        assertThat(levels).as("every rule's level").isNotEmpty().allMatch(level -> level.equals("None") || level.equals("Metadata"));
        assertThat(levels.get(levels.size() - 1)).as("the catch-all").isEqualTo("Metadata");
    }

    @Test
    @DisplayName("an alert is waited for as the committed rule decides it, read from the cluster's Prometheus and its receiver from"
            + " Alertmanager, with every committed rule loaded")
    void everyAlertIsReadFromTheClustersPrometheusAndItsReceiver() {
        assertThat(function("alert_state")).contains("ALERTS{alertname=");
        assertThat(function("routed")).contains("/api/v2/alerts?filter=");
        assertThat(function("fires")).contains("await_alert \"$1\" firing").contains("routed \"$1\" \"$2\"");
        assertThat(function("alerting_ready")).contains("infra/prometheus/rules/*.yml").contains("'\"type\":\"alerting\"'")
                .contains("[ \"$loaded\" = \"$committed\" ]").contains("placeholder-page").contains("placeholder-ticket");
        assertThat(platform).as("no plant changes a rule: nothing writes the rules' ConfigMap").doesNotContain("finapp-prometheus-rules");
    }

    @Test
    @DisplayName("each domain section of the runbook is sampled by an activation under four eyes, its alert firing before and clearing after")
    void theDomainSectionsAreEachRehearsedByAnActivation() {
        String domain = function("signals_domain");
        assertThat(domain).contains("activation activate-recon-terms").contains("activation activate-pricing")
                .contains("activation activate-fx-source").contains("activation activate-corridor").contains("activation activate-credit")
                .contains("activation activate-lending").contains("activation activate-bnpl").contains("activation activate-risk")
                .contains("client fx-conversion before-source").contains("booked=yes");
        assertThat(domain).contains("fires ReconciliationRuleSetMissing").contains("clears ReconciliationRuleSetMissing")
                .contains("fires LendingTermsNoneActive").contains("clears LendingTermsNoneActive").contains("fires BnplTermsNoneActive")
                .contains("clears BnplTermsNoneActive").contains("fires RiskPolicyNoneActive").contains("clears RiskPolicyNoneActive")
                .contains("census domain");
        assertThat(function("activation")).as("the proposer's own approval refused, a second person's activating")
                .contains("self-approval status=40[39]").contains("approved status=20[01]");
        assertThat(function("signals_correctness")).as("section 10.17: the drift repaired by the rebuild door, two persons")
                .contains("fires LedgerProjectionDrift").contains("client rebuild-projection").contains("rebuild-projection self status=409")
                .contains("clears LedgerProjectionDrift");
    }

    @Test
    @DisplayName("break-glass: a credential naming the incident and ending by itself, the role's reach tried both ways, the disruption"
            + " budget holding, and every act of the holder in the API server's audit log with its outcome, time and id")
    void breakGlassReachAndItsAuditRecordAreAsserted() {
        String breakGlass = function("incidents_break_glass");
        assertThat(breakGlass).contains("holder=\"break-glass:$INCIDENT:holder\"").contains("/O=finapp-break-glass")
                .contains("expirationSeconds: 600").contains("signerName: kubernetes.io/kube-apiserver-client")
                .contains("kube certificate approve");
        assertThat(breakGlass).as("the runbook's checks, a subresource named with --subresource")
                .contains("create pods --subresource=exec: no").contains("get secrets: no").contains("patch deployments: no")
                .contains("delete pods: yes").contains("create pods --subresource=eviction: yes")
                .contains("patch deployments --subresource=scale: yes");
        assertThat(breakGlass).as("each refusal tried for real, each write made")
                .contains("bg -n finapp get secrets").contains("bg -n finapp exec").contains("bg -n finapp patch deployment")
                .contains("grep -qi 'disruption budget'").contains("bg -n finapp scale deployment/finapp-app --replicas=0")
                .contains("bg -n finapp scale deployment/finapp-app --replicas=3");
        assertThat(breakGlass).as("the credential refused once expired").contains("the certificate still served after its expiry")
                .contains("'unauthorized\\|logged in'");
        assertThat(breakGlass).as("the API server's own record of every act, joined to the incident record")
                .contains("api_audit \"$holder\"").contains("'create|r=pods,s=eviction'").contains("'list|r=secrets'")
                .contains("'patch|r=deployments,s=scale'").contains("an audit event lacks its code, time or id")
                .contains(">> \"$INCIDENT_FILE\"");
        assertThat(function("api_audit")).contains("\"auditID\"").contains("\"responseStatus\"").contains("\"stageTimestamp\"");
    }

    @Test
    @DisplayName("a compromised operator: the page on a proven password, the identity suspended with a reason naming the incident,"
            + " its sessions ended, reinstated with a reason, each record carrying actor, time, operation, target, correlation and outcome")
    void aCompromisedOperatorIsSuspendedWithItsRecord() {
        String surface = function("signals_surface");
        assertThat(surface).contains("client public-sign-in stranger").contains("fires OperatorPasswordProvenOnPublic placeholder-page")
                .contains("client compromise stranger \"$INCIDENT\"");
        assertThat(surface).as("section 11.12's order: suspend, the acts read, every role revoked, reinstated, the password replaced,"
                        + " the roles granted again")
                .contains("'suspend status=204'").contains("'old-session status=401'").contains("WHERE actor_id = '$STRANGER'")
                .contains("compromise revoke role=.* status=204").contains("'reinstate status=204'").contains("'password-change status=201'")
                .contains("'old-password status=401'").contains("compromise regrant role=.* status=204");
        assertThat(surface).as("each act's record, its reason where it takes one")
                .contains("audited \"the compromise: the suspension\" identity.IdentitySuspended \"\" reason")
                .contains("identity.SessionRevoked").contains("identity.RoleAssigned \"\" reason")
                .contains("identity.IdentityReinstated \"\" reason").contains("identity.CredentialChanged")
                .contains("grep -q \"$INCIDENT\" \"$ART/audit-identity.IdentitySuspended.txt\"").contains("lastRole=true");
        assertThat(client).as("recovery delivers no token, so the holder replaces the password at once").contains("/v1/me/credential");
        assertThat(function("audited")).as("every field the review reads")
                .contains("$1==\"\" || $2==\"\" || $4==\"\" || $6==\"\" || $8==\"\" || $9==\"\"");
        assertThat(function("app_audit")).contains("occurred_at").contains("actor_id").contains("operation").contains("target_id")
                .contains("reason").contains("outcome").contains("correlation_id");
    }

    @Test
    @DisplayName("question 5: a holding written around the door is named by the audited read and resolved by its holder's enrolment")
    void theAccessReviewResolvesAPlantedHolding() {
        assertThat(function("signals_access_review")).contains("access-review before status=200 holdings=[1-9][0-9]* stranger=yes")
                .contains("client access-review-enrol").contains("access-review after status=200 .*stranger=no")
                .contains("identity.FactorlessHoldingsRead");
        assertThat(client).as("the holding is written around the grant's door, as one predating the rule").contains("'MERCHANT_ADMINISTRATOR'");
    }

    @Test
    @DisplayName("question 6's subject: a first role ends the customer's session and is recorded firstRole=true; a second ends nothing")
    void aFirstRoleEndsTheCustomersSessionAndIsRecorded() {
        assertThat(function("signals_first_role")).contains("'old-session status=401'").contains("'staff-sign-in status=201'")
                .contains("'after-second status=200'").contains("change_summary LIKE '%firstRole=true%'");
    }

    @Test
    @DisplayName("a broker outage: every replica stays ready, nothing fails, the stalled outbox and the freshness burn page, and it"
            + " drains in order once back; a refused permission takes every replica out of readiness until restored")
    void theBrokerOutageKeepsTheFleetReadyAndPages() {
        String broker = function("incidents_broker");
        assertThat(broker).contains("scale statefulset/finapp-kafka --replicas=0").contains("fires OutboxPublicationStalled placeholder-page")
                .contains("fires EventFreshnessBurnFast placeholder-page").contains("readiness moved during the outage")
                .contains("requests failed during the outage").contains("census broker-outage");
        assertThat(broker).contains("fires InboxPollRefused placeholder-page").contains("replicas stayed ready while the broker refused")
                .contains("census refused-credential");
    }

    @Test
    @DisplayName("the telemetry stack's own alerts fire on the cluster: a back end gone and every collector gone, requests unaffected")
    void theTelemetryAlertsFireOnKind() {
        assertThat(function("signals_telemetry")).as("the negative control first: a rollout of the collectors under traffic raises nothing")
                .contains("rollout restart deployment/finapp-collector").contains("a rollout of the collectors raised TelemetryExportFailing");
        assertThat(function("signals_telemetry")).contains("scale deployment/finapp-tempo --replicas=0")
                .contains("fires TelemetryBackendDown placeholder-ticket").contains("fires TelemetryExportFailing placeholder-ticket")
                .contains("',exporter=\"otlp_grpc/tempo\"'")
                .contains("scale deployment/finapp-collector --replicas=0").contains("fires TelemetryCollectorsAbsent placeholder-ticket")
                .contains("readiness moved with the collectors gone").contains("smoke telemetry-collector").contains("census telemetry");
    }

    @Test
    @DisplayName("dead letters: a poison record parked, refused release and discarded by two persons; an abandoned event retried")
    void theDeadLettersAreResolvedThroughTheirDoors() {
        assertThat(function("incidents_dead_letters")).contains("fires InboxDeadLetterParked placeholder-page")
                .contains("code=deadletter.NotReleasable").contains("client discard INBOX_RECORD").contains("discard self status=403")
                .contains("fires OutboxDeadLetter placeholder-page").contains("client retry-abandoned").contains("census dead-letters");
    }

    @Test
    @DisplayName("a lost database takes readiness and never liveness; an unclean restart in place empties the unlogged counter, a"
            + " positive control counted before it")
    void aLostDatabaseDegradesReadinessNeverLiveness() {
        assertThat(function("incidents_fleet_blind")).contains("fires GaugesStaleAcrossTheFleet placeholder-page")
                .contains("liveness restarted a replica for a lost database").contains("census fleet-blind");
        assertThat(function("incidents_database_restart")).contains("pg_ctl stop -m immediate")
                .doesNotContain("delete pod").contains("the positive control").contains("the unlogged counter kept")
                .contains("a replica restarted for the database's restart");
    }

    @Test
    @DisplayName("the raw writes to the books are found by their own pages, triaged read-only, and removed as the plant's teardown,"
            + " never as a procedure; the census is whole after")
    void theRawWritesAreFoundByTheirPagesAndRemovedAsTeardown() {
        String books = function("incidents_books");
        assertThat(books).contains("fires JournalEntryUnsourced placeholder-page").contains("START TRANSACTION READ ONLY")
                .contains("fires LedgerTrialBalanceNonZero placeholder-page").contains("client trial-balance-report")
                .contains("the plant's removal, not a procedure").contains("clears LedgerTrialBalanceNonZero").contains("census books");
    }

    @Test
    @DisplayName("CI runs both platform scopes nightly only, each with a bound of its own (Q10)")
    void ciRunsThePlatformScopesNightly() {
        String workflow = read(".github/workflows/ci.yml");
        assertThat(workflow).contains("          - scope: signals\n            timeout: 330\n            nightly: only")
                .contains("          - scope: incidents\n            timeout: 300\n            nightly: only")
                .contains("if [ \"$NIGHTLY\" = only ] && [ \"$EVENT\" != schedule ]; then");
    }
}
