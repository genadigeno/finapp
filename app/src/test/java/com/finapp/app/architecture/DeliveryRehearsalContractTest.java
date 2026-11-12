package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the kind rehearsal's delivery scope asserts about the controller (`P15-TSK-032`; `OPERATIONS_RUNBOOK.md` section 11.36;
 * `THREAT_MODEL.md` T11 and T12): Argo CD, installed from the render {@code controller-check.sh} held to its rule, syncs staging's overlay
 * through a rehearsal root, and the run asserts the waves, the hook, the hold, self-heal, the failed hook and its way back, the
 * promotion's refusals, the smoke on the API server and the census.
 *
 * <p>The run's result is a cluster's behaviour, recorded in section 11.36, and nothing here claims it. What a hermetic test holds is that a
 * later edit cannot quietly remove an assertion the run makes, or make one pass without the condition it reads.
 */
@Tag("architecture")
@DisplayName("the delivery rehearsal asserts what the controller must do (P15-TSK-032)")
class DeliveryRehearsalContractTest {

    private static final String DELIVERY = "infra/scripts/kind-rehearsal/delivery.sh";
    private static final String HARNESS = "infra/scripts/kind-rehearsal.sh";

    private static Path root;
    private static String delivery;
    private static String harness;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
        delivery = EvidenceCitations.read(root.resolve(DELIVERY));
        harness = EvidenceCitations.read(root.resolve(HARNESS));
    }

    @Test
    @DisplayName("a scope of the harness, sourced after the census and the client are defined, which deploys none of the harness's own release")
    void theScopeIsTheHarnesss() {
        assertThat(harness).contains("case $SCOPE in release | rotations) ;; delivery) ;;")
                .contains("if [ \"$SCOPE\" != delivery ]; then\nstep bootstrap-roles")
                .contains("if [ \"$SCOPE\" = delivery ]; then\n    # shellcheck source=infra/scripts/kind-rehearsal/delivery.sh\n    . \"$DIR/delivery.sh\"");
        assertThat(harness.indexOf(". \"$DIR/delivery.sh\"")).as("sourced after the census is defined")
                .isGreaterThan(harness.indexOf("census() {")).isGreaterThan(harness.indexOf("client() {"));
        assertThat(harness).as("the data tier only, of what the harness applies, in this scope")
                .contains("grep -q 'finapp/test-only: \"true\"' \"$file\" || continue");
        assertThat(delivery).as("its steps build on each other, so selecting some is refused").contains("[ -z \"${REHEARSAL_STEPS:-}\" ] || cannot");
    }

    @Test
    @DisplayName("the controller installed is the render controller-check.sh held to its rule, every image a pinned digest")
    void theControllerInstalledIsTheCheckedRender() {
        assertThat(delivery).contains("sh \"$ROOT/infra/scripts/controller-check.sh\" render \"$CONTROLLERS\"")
                .contains("ARGOCD_STREAM=\"$CONTROLLERS/argocd.yaml\"").contains("< \"$CONTROLLERS/external-secrets.yaml\"")
                .contains("kube apply --server-side --force-conflicts -f - < \"$ARGOCD_STREAM\"")
                .contains("fail \"Argo CD runs $image, which is not a pinned digest\"");
    }

    @Test
    @DisplayName("the default project as installed reaches nothing, and an Application placed in it is refused and applies nothing")
    void theDefaultProjectRefusesAnApplication() {
        assertThat(delivery).contains("argo appproject default '{.spec}'")
                .contains("  project: default\n")
                .contains("*InvalidSpecError*\"not permitted\"*")
                .contains("kube -n default get configmap delivery-default-probe >/dev/null 2>&1 && fail")
                .contains("fail \"the Application in the default project started a sync\"");
    }

    @Test
    @DisplayName("promote.sh refuses a tag and an unsigned digest by name and writes nothing, then writes the pair alone, committed as one change")
    void promotionIsByDigestAndRefusesByName() {
        assertThat(delivery).contains("promote latest \"$MIGRATOR_DIGEST\" \"$REVISION\"").contains("is named by 'latest', which is not a digest")
                .contains("promote \"$UNSIGNED_DIGEST\" \"$MIGRATOR_DIGEST\" \"$REVISION\"").contains("has no signature the environment's authority accepts")
                .contains("fail \"a refused promotion wrote into the clone")
                .contains("fail \"promote.sh wrote other than the overlay and its release record");
        assertThat(delivery).as("the run's own repository, and the promotion run from the clone so it writes there")
                .contains("sh \"$CLONE/infra/scripts/promote.sh\" staging \"$@\"").contains("PROMOTE_LOCAL_REGISTRY=1");
    }

    @Test
    @DisplayName("each wave's Application is created only after the one before finished its sync")
    void eachWaveWaitsForTheOneBefore() {
        assertThat(delivery).contains("for app in finapp-admission finapp-namespaces finapp finapp-edge; do")
                .contains("'{.metadata.creationTimestamp}|{.status.operationState.startedAt}|{.status.operationState.finishedAt}'")
                .contains("if [ \"$(date -u -d \"$created\" +%s)\" -lt \"$(date -u -d \"$previous_finished\" +%s)\" ]; then")
                .contains("before the Application of the wave before finished its sync");
        assertThat(delivery).as("the probe that removes the Application health and must turn this red")
                .contains("[ \"$PROBE\" = no-application-health ]").contains("health.argoproj.io_Application: \\|$/ {skip = 1; next}");
    }

    @Test
    @DisplayName("an admission policy is healthy only on the controller's observed generation and Ready, read from the live v1beta1 status")
    void theAdmissionPolicyHealthIsObserved() {
        assertThat(delivery).contains("{.status.observedGeneration}|{.status.conditions[?(@.type==\"Ready\")].status}")
                .contains("[ -n \"$observed\" ] && [ \"$observed\" = \"$generation\" ] && [ \"$ready_status\" = True ]")
                .contains("grep -c '^ClusterImagePolicy|.*|Healthy$'");
    }

    @Test
    @DisplayName("an ExternalSecret whose store lacks a key holds its wave: no hook, no Deployment, the sync Running, for at least a minute")
    void anExternalSecretNotReadyHoldsItsWave() {
        assertThat(delivery).contains("delivery_store \"$HELD_KEY\"")
                .contains("kube -n finapp get job finapp-migrator >/dev/null 2>&1 && fail \"the migrator hook ran while an ExternalSecret")
                .contains("kube -n finapp get deployment finapp-app >/dev/null 2>&1 && fail \"the Deployment was applied while")
                .contains("[ \"$phase\" = Running ] || fail").contains("-ge 60 ]");
        assertThat(delivery).as("the store's entries reach the store object alone, never a file of the clone or an Application")
                .contains("} | kube apply -f - >> \"$ART/apply.log\" 2>&1 || cannot \"the store finapp-$store-store could not be written\"")
                .contains("value:\n          fake:\n            data: []").contains("RespectIgnoreDifferences=true")
                .doesNotContain("cp \"$STORE").doesNotContain("$ART/store");
    }

    @Test
    @DisplayName("the migrator Sync hook is awaited: created after the ExternalSecrets are Ready, the Deployment applied only after it completed")
    void theMigratorHookIsAwaited() {
        assertThat(delivery).contains("the migrator hook started before the application's ExternalSecret was Ready")
                .contains("before the migrator hook completed at $job_done").contains("grep -q 'result=APPLIED' \"$ART/migrator-first.log\"");
    }

    @Test
    @DisplayName("the role bootstrap runs in finapp with the signed migrator image alone and no init container, admitted")
    void theBootstrapJobIsAdmittedAsARealEnvironmentRunsIt() {
        assertThat(delivery).contains("/^      initContainers:$/ {skip = 1; next}")
                .contains("s#MIGRATOR_IMAGE#$REG/finapp-staging-migrator@$MIGRATOR_DIGEST#")
                .contains("wait_job finapp finapp-role-bootstrap 420 role-bootstrap-real")
                .contains("[ \"$bootstrap_pod\" = \"$REG/finapp-staging-migrator@$MIGRATOR_DIGEST|\" ]");
    }

    @Test
    @DisplayName("self-heal puts back a hand change to a field the release states, and a rollout restart is observed and recorded")
    void selfHealPutsBackAHandChange() {
        assertThat(delivery).contains("'{\"spec\":{\"revisionHistoryLimit\":3}}'")
                .contains("fail \"self-heal did not put back a hand change of revisionHistoryLimit within five minutes\"");
        assertThat(delivery).contains("kube -n finapp rollout restart deployment/finapp-app").contains("record \"rollout restart: the Application");
    }

    @Test
    @DisplayName("a failed migrator hook fails the sync: the Deployment unchanged, the old replicas serving, nothing retried, self-heal suspended; the way back is a forward fix")
    void aFailedHookStopsTheSyncBeforeTheRollout() {
        assertThat(delivery).contains("ALTER TABLE ledger.journal_entry ADD CONSTRAINT delivery_rehearsal_refused CHECK (false);")
                .contains("fail \"the sync with a failing migrator succeeded\"")
                .contains("[ \"$exit_codes\" = 10 ]")
                .contains("fail \"the Deployment changed under a failed hook\"")
                .contains("fail \"the old replicas are not serving after the failed hook\"")
                .contains("fail \"self-heal acted while the last sync was failed\"")
                .contains("fail \"Argo CD started another sync of the failed revision\"")
                .contains("fail \"the failed migrator ran again\"")
                .contains("fail \"the successful sync did not put back the hand change\"");
    }

    @Test
    @DisplayName("a rollback by digest keeps the migrator: a changed migrator refused, the kept one synced")
    void aRollbackKeepsTheMigrator() {
        assertThat(delivery).contains("promote \"$OLDER_DIGEST\" \"$FAILING_DIGEST\" \"$OLDER_REVISION\"").contains("a rollback moves the application alone")
                .contains("promote \"$OLDER_DIGEST\" \"$MIGRATOR_DIGEST\" \"$OLDER_REVISION\"")
                .contains("synced finapp \"$ROLLBACK_COMMIT\" 1200 rollback");
    }

    @Test
    @DisplayName("the smoke runs on the API server under the read-only role, and its record goes to the run's repository alone")
    void theSmokeRunsOnTheApiServerAndRecordsOnlyInTheClone() {
        assertThat(delivery).contains("SMOKE_KUBECTL=\"kubectl --as=finapp-rehearsal-reader --as-group=finapp-read-only\"")
                .contains("sh \"$CLONE/infra/scripts/post-deploy-smoke.sh\" staging --record")
                .contains("git -C \"$(dpath \"$ROOT\")\" status --porcelain -- deploy/evidence")
                .contains("post_deploy_smoke promoted").contains("post_deploy_smoke rolled-back").contains("post_deploy_smoke recovered");
    }

    @Test
    @DisplayName("the census follows every release the controller synced, and the time from each commit to its sync is recorded")
    void theCensusFollowsEverySync() {
        assertThat(delivery).contains("census delivery-promoted").contains("census delivery-rolled-back").contains("census delivery-recovered")
                .contains("census delivery-final");
        assertThat(delivery).contains(">> \"$ART/latency.tsv\"").contains("freshness: the store was read again at");
    }

    @Test
    @DisplayName("nothing generated is kept: the keys, the store and the clone live in the run's temporary directory, and the services are removed at exit")
    void generatedMaterialStaysOutOfTheRepository() {
        assertThat(delivery).contains("CLONE=\"$WORK/clone\"").contains("KEYS=\"$WORK/keys\"").contains("STORE=\"$WORK/store\"")
                .contains("CONTAINERS=\"$CONTAINERS $REG_NAME $GIT_NAME\"").doesNotContain("cp \"$KEYS/delivery.key");
        assertThat(harness).contains("for container in $CONTAINERS; do").contains("rm -rf \"$WORK\"");
    }

    @Test
    @DisplayName("CI runs the delivery scope nightly only, on its own runner and bound")
    void ciRunsItNightlyOnly() {
        String workflow = EvidenceCitations.read(root.resolve(".github/workflows/ci.yml"));
        assertThat(workflow).contains("          - scope: delivery\n            timeout: 180\n            nightly: true")
                .contains("NIGHTLY: ${{ matrix.nightly }}")
                .contains("if [ \"$NIGHTLY\" = true ] && [ \"$EVENT\" != schedule ] && [ \"$EVENT\" != workflow_dispatch ]; then");
    }
}
