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
 * What the kind rehearsal's delivery scope asserts about the controller (`P15-TSK-032`; `OPERATIONS_RUNBOOK.md` section 11.36;
 * `THREAT_MODEL.md` T11 and T12): Argo CD, installed from the render {@code controller-check.sh} held to its rule, syncs staging's overlay
 * through a rehearsal root, and the run asserts the waves, the hook, the hold, self-heal, the failed hook and its way back, the
 * promotion's refusals, the smoke on the API server and the census. Since {@code P17-TSK-105} staging's root holds the console's
 * Application as its fifth and last wave, and the run holds it both ways: with a console image named, pushed, signed, patched and synced
 * after the edge; with none, removed from the run's own rendered root and said so beside the result, never passed silently.
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
        assertThat(harness).contains("case $SCOPE in release | rotations | signals | incidents) ;; delivery) ;;")
                .contains("if [ \"$SCOPE\" != delivery ]; then\nstep bootstrap-roles")
                .contains("if [ \"$SCOPE\" = delivery ]; then\n    # shellcheck source=infra/scripts/kind-rehearsal/delivery.sh\n    . \"$DIR/delivery.sh\"");
        assertThat(harness.indexOf(". \"$DIR/delivery.sh\"")).as("sourced after the census is defined")
                .isGreaterThan(harness.indexOf("census() {")).isGreaterThan(harness.indexOf("client() {"));
        assertThat(harness).as("the data tier only, of what the harness applies, in this scope")
                .contains("grep -q 'finapp/test-only: \"true\"' \"$file\" || continue");
        assertThat(delivery).as("its steps build on each other, so selecting some is refused").contains("[ -z \"${REHEARSAL_STEPS:-}\" ] || cannot");
    }

    @Test
    @DisplayName("the controllers installed are the render controller-check.sh held to its rule, every Argo CD image a pinned digest, metrics-server's API available before the root")
    void theControllerInstalledIsTheCheckedRender() {
        assertThat(delivery).contains("sh \"$ROOT/infra/scripts/controller-check.sh\" render \"$CONTROLLERS\"")
                .contains("ARGOCD_STREAM=\"$CONTROLLERS/argocd.yaml\"").contains("< \"$CONTROLLERS/external-secrets.yaml\"")
                .contains("kube apply --server-side --force-conflicts -f - < \"$ARGOCD_STREAM\"")
                .contains("fail \"Argo CD runs $image, which is not a pinned digest\"");
        assertThat(delivery).as("the release's autoscaler has a metrics source, from the checked render, before the root is applied")
                .contains("kube apply --server-side -f - < \"$CONTROLLERS/metrics-server.yaml\"")
                .contains("kube get apiservice v1beta1.metrics.k8s.io -o jsonpath='{.status.conditions[?(@.type==\"Available\")].status}'")
                .contains("crictl pull \"$METRICS_SERVER_REPOSITORY@$METRICS_SERVER_DIGEST\"");
        assertThat(delivery.indexOf("\"$CONTROLLERS/metrics-server.yaml\"")).isLessThan(delivery.indexOf("step delivery-root"));
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
        assertThat(delivery).contains("for app in finapp-admission finapp-namespaces finapp finapp-edge $CONSOLE_WAVE; do")
                .contains("'{.metadata.creationTimestamp}|{.status.operationState.startedAt}|{.status.operationState.finishedAt}'")
                .contains("if [ \"$(date -u -d \"$created\" +%s)\" -lt \"$(date -u -d \"$previous_finished\" +%s)\" ]; then")
                .contains("before the Application of the wave before finished its sync");
        assertThat(delivery).as("the probe that removes the Application health and must turn this red")
                .contains("[ \"$PROBE\" = no-application-health ]").contains("health.argoproj.io_Application: \\|$/ {skip = 1; next}");
    }

    @Test
    @DisplayName("a console image named (P17-TSK-105): pushed as staging's console, signed and attested under the run's key, its Application patched like the others, created after the release's and the edge's, and synced and healthy with them")
    void aConsoleNamedIsDeliveredInTheLastWave() {
        assertThat(harness).as("the harness's variables and up-front refusals, and the image it obtained at build-images")
                .contains("*) echo \"?? one console image at a time: $CONSOLE_FROM are all named\" >&2; exit 2 ;;")
                .contains("    console_obtain finapp-console:rehearsal\n");
        assertThat(harness.indexOf("    console_obtain finapp-console:rehearsal\n")).isLessThan(harness.indexOf(". \"$DIR/delivery.sh\""));
        assertThat(delivery.indexOf("cannot \"CONSOLE_SBOM goes with a console image, and none is named")).as("refused before anything of the delivery is made")
                .isPositive().isLessThan(delivery.indexOf("DELIVERY_RUN="));

        assertThat(delivery).as("pushed, signed and attested as the platform's images are; its SBOM its own when given; its provenance never this tree's commit; pulled by digest")
                .contains("CONSOLE_DIGEST=$(push_image finapp-console:rehearsal finapp-staging-console n)")
                .contains("sign_and_attest \"$REG/finapp-staging-console@$CONSOLE_DIGEST\" console console.cdx.json")
                .contains("--type cyclonedx --predicate \"/att/${3:-empty.cdx.json}\"")
                .contains("co_sbom=$CONSOLE_SBOM").contains("co_sbom=\"$(dirname \"$CONSOLE_ARCHIVE\")/sbom.cdx.json\"")
                .contains("\"externalParameters\": {\"source\": \"finapp-admin-panel\"").contains("> \"$ATT/console.provenance.json\"")
                .contains("CONSOLE_REFERENCE=\"finapp-staging-console@$CONSOLE_DIGEST\"")
                .contains("\"finapp-staging-migrator@$FAILING_DIGEST\" $CONSOLE_REFERENCE; do");
        assertThat(delivery.indexOf("sign_and_attest \"$REG/finapp-staging-console@$CONSOLE_DIGEST\""))
                .isGreaterThan(delivery.indexOf("step delivery-images")).isLessThan(delivery.indexOf("step delivery-controllers"));

        String named = consoleBranch(true);
        assertThat(named).as("patched like the others: the run's repository, and the run's layer naming the run's registry and digest over the committed overlay")
                .contains("CONSOLE_WAVE=finapp-console")
                .contains("cat > \"$R/root/app-console.yaml\" <<YAML\n")
                .contains("  name: finapp-console\n  namespace: argocd\nspec:\n  source:\n    repoURL: $REPO_URL\n    path: rehearsal/console/staging\nYAML\n")
                .contains("cat > \"$R/console/staging/kustomization.yaml\" <<YAML\n")
                .contains("resources:\n  - ../../../deploy/console/overlays/staging\nimages:\n  - name: finapp-console\n    newName: $REG/finapp-staging-console\n    digest: $CONSOLE_DIGEST\nYAML\n")
                .doesNotContain("$patch: delete").doesNotContain("skipped.txt");
        assertThat(delivery).contains("  - path: app-edge.yaml\n  - path: $CONSOLE_ROOT_PATCH\nYAML\n");
        assertThat(delivery).as("rendered before anything is committed: the fifth Application at the run's layer, which renders the image the run signed")
                .contains("admission ${CONSOLE_FROM:+console/staging}; do")
                .contains("[ \"$rendered_applications\" = 5 ] && grep -q 'path: rehearsal/console/staging$' \"$WORK/layer-root.yaml\"")
                .contains("grep -q \"image: $REG/finapp-staging-console@$CONSOLE_DIGEST\\$\" \"$WORK/layer-console-staging.yaml\"");

        assertThat(delivery).as("its wave after the release's and the edge's; every Application synced and healthy; its pods at the signed digest")
                .contains("finapp finapp-edge $CONSOLE_WAVE; do")
                .contains("[ -n \"$created\" ] || fail \"waves: the Application $app does not exist\"")
                .contains("console_state=$(argo application finapp-console '{.status.sync.status}|{.status.health.status}')")
                .contains("[ \"$edge_state\" = \"Synced|Healthy\" ] && [ \"$console_state\" = \"Synced|Healthy\" ]; then done_root=1; break; fi")
                .contains("[ \"$console_images\" = \"$REG/finapp-staging-console@$CONSOLE_DIGEST\" ]");
        assertThat(EvidenceCitations.read(root.resolve("deploy/delivery/staging/applications.yaml"))).as("the Application the run patches: the root's last wave")
                .contains("  name: finapp-console\n  namespace: argocd\n  annotations:\n    argocd.argoproj.io/sync-wave: \"4\"\n")
                .contains("    path: deploy/console/overlays/staging\n");
    }

    @Test
    @DisplayName("no console image named (P17-TSK-105): the console's Application removed from the run's rendered root alone, the waves the platform's four, and said so loudly beside the result - never a pass")
    void noConsoleNamedRemovesItsApplicationFromTheRunsRootLoudly() {
        String notNamed = consoleBranch(false);
        assertThat(notNamed).as("removed by the run's own root, in that branch alone")
                .contains("CONSOLE_WAVE=\"\"")
                .contains("cat > \"$R/root/console-removed.yaml\" <<'YAML'\n")
                .contains("kind: Application\nmetadata:\n  name: finapp-console\n  namespace: argocd\n$patch: delete\nYAML\n")
                .doesNotContain("rehearsal/console/staging").doesNotContain("$CONSOLE_DIGEST");
        assertThat(delivery.split("\\$patch: delete", -1)).as("one removal, the not-named branch's").hasSize(2);
        assertThat(delivery).contains("R=\"$CLONE/rehearsal\"");

        assertThat(notNamed).as("said at the step and in skipped.txt, so the result reads `SKIPPED: the console's Application, no console image named`")
                .contains("log \"CONSOLE APPLICATION SKIPPED: ")
                .contains("printf '%s\\n' \"the console's Application, no console image named (P17-TSK-105")
                .contains(">> \"$ART/skipped.txt\"").doesNotContain("not-exercised");
        assertThat(harness).contains("log \"SKIPPED: $skipped\"").contains(": > \"$ART/skipped.txt\"");

        assertThat(delivery).as("rendered, four Applications and none the console's; on the cluster none exists; nothing waits on it; the waves' pass says the skip")
                .contains("[ \"$rendered_applications\" = 4 ] && ! grep -Eq '^ *name: finapp-console$' \"$WORK/layer-root.yaml\"")
                .contains("|| cannot \"the console's Application was not removed from the run's rendered root")
                .contains("[ -z \"$(argo application finapp-console '{.metadata.name}')\" ] || fail \"the console's Application exists, although the run removed it from its rendered root\"")
                .contains("console_state=\"Synced|Healthy\"\n    [ -z \"$CONSOLE_FROM\" ] || console_state=")
                .contains("; the console's Application SKIPPED, no console image named\"");
    }

    @Test
    @DisplayName("no committed file under deploy/ is rewritten (P17-TSK-105): the console's layer and its removal live under the clone's rehearsal/, its deploy/ read clean before the root is committed, and the repository only ever read")
    void noCommittedFileUnderDeployIsRewritten() {
        assertThat(delivery).contains("cat > \"$R/console/staging/kustomization.yaml\"").contains("cat > \"$R/root/console-removed.yaml\"")
                .doesNotContain("\"$CLONE/deploy/console").doesNotContain("\"$CLONE/deploy/delivery")
                .doesNotContain("deploy/console/overlays/staging/release.yaml");
        int guard = delivery.indexOf("[ -z \"$(git_clone status --porcelain -- deploy)\" ] || cannot \"the rehearsal root wrote under deploy/ of the clone");
        assertThat(guard).as("the clone's deploy/ read clean once the root is written, before it is committed").isPositive()
                .isGreaterThan(delivery.indexOf("cat > \"$R/root/kustomization.yaml\""))
                .isLessThan(delivery.indexOf("commit_clone \"The rehearsal root: what staging's environment adds, on kind\""));
        assertThat(Pattern.compile(">>?\\s*\"\\$ROOT/").matcher(delivery).find()).as("nothing redirected into the repository").isFalse();
        assertThat(Pattern.compile("sed -i[^\\n]*\\$ROOT").matcher(delivery).find()).as("nothing of the repository edited in place").isFalse();
        Matcher mount = Pattern.compile("-v \"\\$\\(dpath \"\\$ROOT[^\"]*\"\\):([^\"\\s]*)\"").matcher(delivery);
        int mounts = 0;
        while (mount.find()) {
            assertThat(mount.group(1)).as("a mount of the repository is read-only: %s", mount.group()).endsWith(":ro");
            mounts++;
        }
        assertThat(mounts).as("the policy controller's values, read from the repository").isPositive();
    }

    /** The console's two branches of the rehearsal root (P17-TSK-105): the named one, or the one when none is named, up to its end. */
    private static String consoleBranch(boolean named) {
        int start = delivery.indexOf("if [ -n \"$CONSOLE_FROM\" ]; then\n    CONSOLE_ROOT_PATCH=app-console.yaml\n");
        int otherwise = delivery.indexOf("else\n    CONSOLE_ROOT_PATCH=console-removed.yaml\n", start);
        int end = delivery.indexOf("\nfi\n", otherwise);
        assertThat(start).as("the console's branch when one is named").isPositive();
        assertThat(otherwise).as("its branch when none is").isGreaterThan(start);
        assertThat(end).as("the branches' end").isGreaterThan(otherwise);
        return named ? delivery.substring(start, otherwise) : delivery.substring(otherwise, end);
    }

    @Test
    @DisplayName("an admission policy is healthy only on the controller's observed generation and Ready: the live v1beta1 status, the Application's health, and the installed check run by Argo CD's own code on each live policy and on it unobserved")
    void theAdmissionPolicyHealthIsObserved() {
        assertThat(delivery).contains("{.status.observedGeneration}|{.status.conditions[?(@.type==\"Ready\")].status}")
                .contains("[ -n \"$observed\" ] && [ \"$observed\" = \"$generation\" ] && [ \"$ready_status\" = True ]")
                .contains("[ \"$admission_state\" = \"Synced|Healthy\" ] || fail");
        assertThat(delivery).as("the check itself, as installed, judges: Healthy on the live policy, not Healthy one generation unobserved")
                .contains("kube -n argocd get configmap argocd-cm -o yaml > \"$WORK/admission-health/argocd-cm.yaml\"")
                .contains("argocd admin settings resource-overrides health \"/w/$1\" --argocd-cm-path /w/argocd-cm.yaml")
                .contains("s/^  observedGeneration: $observed\\$/  observedGeneration: $((observed - 1))/")
                .contains("[ \"$live\" = Healthy ] && [ -n \"$unobserved\" ] && [ \"$unobserved\" != Healthy ]");
        assertThat(delivery).as("Argo CD v3 keeps no health per resource in status.resources, so nothing reads it there")
                .doesNotContain("{.health.status}{\"\\n\"}");
    }

    @Test
    @DisplayName("an ExternalSecret whose store lacks a key holds its wave: the sync ends Failed there, no hook, no Deployment, not retried, for at least a minute; then one sync by hand")
    void anExternalSecretNotReadyHoldsItsWave() {
        assertThat(delivery).contains("delivery_store \"$HELD_KEY\"")
                .contains("{ [ \"$phase\" = Failed ] || [ \"$phase\" = Error ]; }")
                .contains("kube -n finapp get job finapp-migrator >/dev/null 2>&1 && fail \"the migrator hook ran while an ExternalSecret")
                .contains("kube -n finapp get deployment finapp-app >/dev/null 2>&1 && fail \"the Deployment was applied while")
                .contains("fail \"the release's failed sync was started again on its own\"")
                .contains("fail \"the release's sync was retried: retry count $retries\"").contains("-ge 60 ]")
                .contains("manual_sync finapp \"$PROMOTED_COMMIT\" first-release");
        assertThat(delivery).as("the root synced by hand only as section 11.36's recovery: failed on the release's Application, before the release was synced; any other failure stops the run")
                .contains("[ \"$root_failed_on\" = \"Application/finapp \" ] && [ \"$(date -u -d \"$root_finished\" +%s)\" -le \"$RELEASE_SYNCED\" ]")
                .contains("manual_sync finapp-staging \"$PROMOTED_COMMIT\" root-recovery")
                .contains("a defect, not the hold, and not synced past");
        assertThat(delivery.split("manual_sync finapp-staging ", -1)).as("one sync of the root by hand, no other").hasSize(2);
        assertThat(delivery).as("the store's entries reach the store object alone, never a file of the clone or an Application")
                .contains("} | kube apply -f - >> \"$ART/apply.log\" 2>&1 || cannot \"the store finapp-$store-store could not be written\"")
                .contains("value:\n          fake:\n            data: []").contains("RespectIgnoreDifferences=true")
                .doesNotContain("cp \"$STORE").doesNotContain("$ART/store");
    }

    @Test
    @DisplayName("a sync by hand carries the Application's own sync options, as Argo CD's API server fills them, and the stores are read after it: they keep the environment's entries")
    void aSyncByHandCarriesTheApplicationsOptions() {
        assertThat(delivery).contains("options=$(argo application \"$1\" '{.spec.syncPolicy.syncOptions}')")
                .contains("case $options in *RespectIgnoreDifferences=true*) ;;")
                .contains("\\\"sync\\\":{\\\"revision\\\":\\\"$2\\\",\\\"syncOptions\\\":$options}}}");
        assertThat(delivery).as("the stores counted after the release's sync by hand, before anything reads them again")
                .contains("kube -n finapp get secretstore \"finapp-$store-store\" -o jsonpath='{range .spec.provider.fake.data[*]}{.key}{\"\\n\"}{end}'")
                .contains("it wrote the committed provider over the environment's");
        int synced = delivery.indexOf("manual_sync finapp \"$PROMOTED_COMMIT\" first-release");
        assertThat(delivery.indexOf("it wrote the committed provider over the environment's")).isGreaterThan(synced)
                .isLessThan(delivery.indexOf("step delivery-first-release"));
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
