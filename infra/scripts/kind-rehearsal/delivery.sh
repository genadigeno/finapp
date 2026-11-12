# The delivery through the controller (P15-TSK-032; OPERATIONS_RUNBOOK.md section 11.36; THREAT_MODEL.md T11, T12). Sourced by
# infra/scripts/kind-rehearsal.sh under REHEARSAL_SCOPE=delivery, once the test-only data tier, the simulator and the topics are up and the
# census and the client are defined: every function and variable of the harness is in scope here. Nothing of the release is applied by
# this script: Argo CD applies it, from a repository the run serves, through the committed delivery.
#
# WHAT A GREEN RUN SAYS. On a real API server, with the controllers installed as the pipeline role installs them (Argo CD and the External
# Secrets chart exactly as infra/scripts/controller-check.sh rendered and held them to its rule, the policy controller from its pinned chart):
#   - the `default` project reaches nothing: an Application placed in it is refused and creates nothing;
#   - infra/scripts/promote.sh refuses a tag and an unsigned digest by name and writes nothing; it writes the pair, and the merge (a push
#     to the run's repository) is the deploy;
#   - the root's four Applications sync in their waves, each created only once the one before had finished its sync, through the
#     committed Application health; each admission policy is healthy only once the policy controller has observed its generation and
#     says Ready (read from the pinned controller's v1beta1 status);
#   - inside the release, an ExternalSecret whose store lacks one key holds its wave: no migrator Job and no Deployment while it does;
#     the role bootstrap runs meanwhile as a real environment runs it, in finapp, the signed migrator image alone and no init container,
#     admitted; with the key in the store the ExternalSecret turns Ready, the migrator Sync hook runs and is awaited, and only then is the
#     Deployment applied;
#   - the read-only post-deploy smoke passes on that cluster, under the read-only role's own identity, and its record is committed only to
#     the run's repository;
#   - self-heal puts back a hand change; a rollback written by promote.sh, the migrator kept, syncs the same way;
#   - a migrator whose migration fails fails the sync at its hook: the Deployment unchanged, the old replicas serving, nothing retried,
#     self-heal suspended while it is so; section 11.36's way back (the cause read, a forward fix promoted) syncs green;
#   - each commit's time to sync, and the ExternalSecret's freshness after a change of its annotations alone, are recorded;
#   - the census after every sync: trial balance zero per currency, one effect per key at the platform and at the provider, the outbox
#     drained, no event lost, GL = ledger.
#
# WHAT EACH ENVIRONMENT ADDS (section 11.36), played here by the rehearsal root, generated into the run's own clone and never committed to
# the repository: the repository the controller reads (a git daemon on the kind network, from the pinned Argo CD image, which carries
# git's own daemon), the images' registry (the run's registry, a layer naming it over the overlay, so the digest stays the overlay's), the
# admission policies' authority (the run's key) and signature repository, the data tier's place (selectors on the test-only data tier, in
# place of the overlay's documentation ranges), the store (the External Secrets Operator's `fake` provider, its entries written by this
# script into the store object alone and never into a file, a commit or an Application: the root tells Argo CD to leave the store's
# provider to the environment, as a real store's contents are in no manifest), the edge's TLS Secrets and Services a node can reach, and
# kind-sized resources. A Prometheus that scrapes the replicas is the smoke's, test-only.
#
# THE OLDER RELEASE of the rollback is a stand-in, as the release scope's N - 1 is: N's own bytes under another digest (one label added),
# signed and attested by the run's key as built from the commit before N on the first-parent line. THE FAILING MIGRATOR is N's migrator
# with one migration added that a database holding a journal refuses (a CHECK no row passes), signed and attested as built from N.
#
# NOTHING GENERATED IS KEPT. The key pair, the store's entries and every password live in $WORK and in the cluster, removed at exit, with
# the registry and the repository's containers. The artefacts hold verdicts, timings, statuses and the public key.
#
# THE PROBE (REHEARSAL_PROBE): `no-application-health` installs the controller with the Application health check removed from argocd-cm,
# and must turn the wave assertion red (run it with REHEARSAL_STOP_AFTER=delivery-root).
#
# NO STEP IS RUN ALONE. Each step builds on the one before (the registry and the repository are this run's containers, removed at exit with
# its keys), so REHEARSAL_STEPS selects nothing here and is refused; REHEARSAL_STOP_AFTER ends a run early.

[ -z "${REHEARSAL_STEPS:-}" ] || cannot "REHEARSAL_STEPS selects nothing in the delivery scope: each of its steps builds on the one before; REHEARSAL_STOP_AFTER ends a run early"

DELIVERY_RUN="$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
REG_NAME="finapp-delivery-registry-$DELIVERY_RUN"
GIT_NAME="finapp-delivery-git-$DELIVERY_RUN"
CONTAINERS="$CONTAINERS $REG_NAME $GIT_NAME"
CLONE="$WORK/clone"
KEYS="$WORK/keys"
ATT="$WORK/att"
CONTROLLERS="$WORK/controllers"
mkdir -p "$KEYS" "$ATT" "$CONTROLLERS"
REGISTRY_IMAGE="$REGISTRY_REPOSITORY:$REGISTRY_VERSION@$REGISTRY_DIGEST"
COSIGN_IMAGE="$COSIGN_REPOSITORY:$COSIGN_VERSION@$COSIGN_DIGEST"
HELM_IMAGE="$HELM_REPOSITORY:$HELM_VERSION@$HELM_DIGEST"
ARGOCD_IMAGE="$ARGOCD_REPOSITORY@$ARGOCD_DIGEST"
PROMETHEUS_IMAGE="$PROMETHEUS_REPOSITORY@$PROMETHEUS_DIGEST"
REVISION=$(git -C "$(dpath "$ROOT")" rev-parse HEAD)
OLDER_REVISION=$(git -C "$(dpath "$ROOT")" rev-parse "HEAD^1")
# The one key the store lacks at the first sync: any key of the application's ExternalSecret holds its wave the same way.
HELD_KEY=FINAPP_ABUSE_BUCKET_KEY
# Names held in variables, so that no line of this script assigns a value to a credential's name (CommittedConfigurationHoldsNoSecretTest).
ESO_ENTRY_FIELD=secretKey
: > "$ART/latency.tsv"
: > "$ART/delivery-record.txt"

[ "$PROBE" = "" ] || [ "$PROBE" = no-application-health ] || cannot "REHEARSAL_PROBE=$PROBE is not a probe of the delivery scope"

record() {
    printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$ART/delivery-record.txt"
    log "record: $*"
}

# A JSONPath read of one Argo CD object in argocd.
argo() {
    kube -n argocd get "$1" "$2" -o "jsonpath=$3" 2>/dev/null || true
}

git_clone() {
    git -C "$(dpath "$CLONE")" "$@"
}

# in_git <git arguments>: git in the pinned Argo CD image on the kind network, the clone mounted. A push from the host's own git over a
# published port stalled in pack-objects on a development machine (Git for Windows, 2026-11-12), even for one small commit; from a
# container on the cluster's network it is the same protocol Argo CD's repository server reads.
in_git() {
    dock run --rm --network kind -v "$(dpath "$CLONE"):/clone" --entrypoint git "$ARGOCD_IMAGE" -c safe.directory='*' -C /clone "$@"
}

# push_clone <label>: the clone's branch to the run's repository as master. Prints the commit; the push's time is the latency's start.
push_clone() {
    in_git push --quiet --force "$REPO_URL" "HEAD:refs/heads/master" >> "$ART/git.log" 2>&1 \
        || cannot "the push of $1 to the run's repository failed: see git.log"
    pushed_head=$(git_clone rev-parse HEAD)
    [ "$(in_git ls-remote "$REPO_URL" refs/heads/master 2>> "$ART/git.log" | cut -c1-40)" = "$pushed_head" ] \
        || cannot "the run's repository does not serve master at $pushed_head after the push of $1"
    printf '%s' "$pushed_head"
}

# commit_clone <message>: every change of the clone in one commit, as one pull request merged.
commit_clone() {
    git_clone add -A >> "$ART/git.log" 2>&1 || cannot "git add failed in the clone"
    git_clone -c user.name=finapp-rehearsal -c user.email=rehearsal@finapp.test commit --quiet -m "$1" >> "$ART/git.log" 2>&1 \
        || cannot "nothing was committed for: $1"
}

# synced <application> <commit> <seconds> <label>: waits for the Application's operation on that commit to end, and records how long it
# took from the push. 0 when it succeeded, 1 when it failed or did not end.
synced() {
    app=$1 commit=$2 bound=$3 label=$4
    pushed=$(date +%s)
    deadline=$((pushed + bound))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        state=$(argo application "$app" '{.status.operationState.operation.sync.revision}|{.status.operationState.phase}|{.status.operationState.startedAt}|{.status.operationState.finishedAt}')
        case $state in
            "$commit|Succeeded|"*)
                printf '%s\t%s\t%s\t%s\n' "$label" "$app" "$commit" "$(( $(date +%s) - pushed ))" >> "$ART/latency.tsv"
                record "$label: $app synced $commit, $(( $(date +%s) - pushed )) s after the push (operation $(printf '%s' "$state" | cut -d'|' -f3-))"
                return 0
                ;;
            "$commit|Failed|"* | "$commit|Error|"*)
                printf '%s\t%s\t%s\tfailed after %s\n' "$label" "$app" "$commit" "$(( $(date +%s) - pushed ))" >> "$ART/latency.tsv"
                record "$label: $app's sync of $commit FAILED, $(( $(date +%s) - pushed )) s after the push"
                return 1
                ;;
        esac
        sleep 5
    done
    kube -n argocd get application "$app" -o yaml > "$ART/application-$app-$label.yaml" 2>&1 || true
    record "$label: $app did not end a sync of $commit within $bound s"
    return 1
}

# The run's Prometheus, the client's money and the read-only smoke.
delivery_money() {
    client smoke "$1" >> "$ART/client.log" 2>&1 || fail "the smoke's money ($1) failed through the edge: see client.log"
    SMOKE_TRANSFERS=$((SMOKE_TRANSFERS + 1))
    SMOKE_TOPUPS=$((SMOKE_TOPUPS + 4))
    SMOKE_CHECKOUTS=$((SMOKE_CHECKOUTS + 1))
    SMOKE_PAYOUTS=$((SMOKE_PAYOUTS + 1))
}

no_pod_terminating_in_finapp() {
    deadline=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ -z "$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.deletionTimestamp}{end}' 2>/dev/null)" ] && return 0
        sleep 3
    done
    return 1
}

# post_deploy_smoke <label>: infra/scripts/post-deploy-smoke.sh as section 11.36 runs it, from the clone, under the read-only role's
# identity (impersonated: the group deploy/base/rbac-humans.yaml binds), its one Prometheus read made from the node. --record writes into
# the clone, which is committed and pushed: the run's repository is the only place a record goes.
post_deploy_smoke() {
    no_pod_terminating_in_finapp 300 || fail "smoke $1: a replica was still terminating after five minutes"
    # The scrape every 15 s, and a fresh series of each replica after a rollout.
    sleep 45
    printf '#!/bin/sh\nMSYS_NO_PATHCONV=1 exec docker exec -i "%s" curl "$@"\n' "$NODE" > "$WORK/bin/curl"
    chmod +x "$WORK/bin/curl"
    if ! PATH="$WORK/bin:$PATH" SMOKE_KUBECTL="kubectl --as=finapp-rehearsal-reader --as-group=finapp-read-only" \
        SMOKE_PROMETHEUS="http://$PROMETHEUS_ADDRESS" sh "$CLONE/infra/scripts/post-deploy-smoke.sh" staging --record > "$ART/smoke-$1.txt" 2>&1; then
        rm -f "$WORK/bin/curl"
        fail "smoke $1: post-deploy-smoke.sh staging did not pass on the cluster: see smoke-$1.txt"
    fi
    rm -f "$WORK/bin/curl"
    recorded=$(git_clone status --porcelain -- deploy/evidence/staging | wc -l | tr -d ' ')
    [ "$recorded" -ge 1 ] || fail "smoke $1: the smoke passed and wrote no record under deploy/evidence/staging of the clone"
    [ -z "$(git -C "$(dpath "$ROOT")" status --porcelain -- deploy/evidence)" ] || fail "smoke $1: a record was written into the repository itself"
    commit_clone "The rehearsal: staging's smoke record ($1), as written"
    SMOKE_COMMIT=$(push_clone "the smoke record")
    pass "smoke $1: post-deploy-smoke.sh staging passed on a real API server under the read-only role, its record committed to the run's repository alone"
}

# ---------------------------------------------------------------------------------------------------
# The registry and the repository, on the kind network.
# ---------------------------------------------------------------------------------------------------
step delivery-services
dock pull --quiet "$REGISTRY_IMAGE" >/dev/null || cannot "could not pull the registry image"
port=$(( 20000 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 20000 ))
tries=0
until dock run -d --name "$REG_NAME" --network kind -p "127.0.0.1:$port:5000" "$REGISTRY_IMAGE" >> "$ART/delivery-services.log" 2>&1; do
    dock rm -f "$REG_NAME" >/dev/null 2>&1 || true
    tries=$((tries + 1))
    [ "$tries" -lt 10 ] || cannot "the registry did not start on any of ten ports"
    port=$((port + 1))
done
REG_IP=$(dock inspect -f '{{(index .NetworkSettings.Networks "kind").IPAddress}}' "$REG_NAME")
case $REG_IP in 10.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].*) ;; *) cannot "the registry's address '$REG_IP' is not RFC 1918" ;; esac
REG="$REG_IP:5000"
PUSH="127.0.0.1:$port"
SIGNATURES="$REG/finapp-staging-signatures"

dock pull --quiet "$ARGOCD_IMAGE" >/dev/null || cannot "could not pull Argo CD's image for the repository"
# No port published: only the cluster's network reaches it, the repository server and the run's own git container (in_git).
dock run -d --name "$GIT_NAME" --network kind --entrypoint sh "$ARGOCD_IMAGE" -c \
    'mkdir -p /tmp/git/finapp.git && git init --quiet --bare /tmp/git/finapp.git && exec git daemon --reuseaddr --base-path=/tmp/git --export-all --enable=receive-pack --informative-errors --listen=0.0.0.0 --port=9418 /tmp/git' \
    >> "$ART/delivery-services.log" 2>&1 || cannot "the run's repository did not start"
GIT_IP=$(dock inspect -f '{{(index .NetworkSettings.Networks "kind").IPAddress}}' "$GIT_NAME")
case $GIT_IP in 10.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].*) ;; *) cannot "the repository's address '$GIT_IP' is not on the kind network" ;; esac
REPO_URL="git://$GIT_IP/finapp.git"
log "the registry: $REG (pushed through $PUSH); the repository: $REPO_URL"

# The clone: this tree's commit and its history, so the promotion's first-parent rule reads what the repository's own would.
git -c core.longpaths=true clone --quiet --no-hardlinks "$(dpath "$ROOT")" "$(dpath "$CLONE")" >> "$ART/git.log" 2>&1 || cannot "the repository could not be cloned"
git_clone config core.longpaths true
git_clone checkout --quiet -B master "$REVISION" >> "$ART/git.log" 2>&1 || cannot "the clone could not be set at $REVISION"
# The history reaches the daemon once, as a copy of a bare clone (a first push packs every object of the history, which took longer than ten
# minutes on a development machine); every later change is a push of its own few objects.
git clone --quiet --bare "$(dpath "$CLONE")" "$(dpath "$WORK/seed.git")" >> "$ART/git.log" 2>&1 || cannot "the bare copy could not be made"
dock exec -u 0 "$GIT_NAME" rm -rf /tmp/git/finapp.git || cannot "the daemon's empty repository could not be replaced"
dock cp "$(dpath "$WORK/seed.git")" "$GIT_NAME:/tmp/git/finapp.git" >> "$ART/git.log" 2>&1 || cannot "the history could not be copied to the daemon"
dock exec -u 0 "$GIT_NAME" chown -R 999:999 /tmp/git/finapp.git || cannot "the daemon's repository could not be given to its user"
rm -rf "$WORK/seed.git"
[ "$(in_git ls-remote "$REPO_URL" refs/heads/master 2>> "$ART/git.log" | cut -c1-40)" = "$REVISION" ] || cannot "the daemon does not serve master at $REVISION"

# ---------------------------------------------------------------------------------------------------
# The images: N's two, pushed by digest, signed and attested with the run's key; the stand-ins beside them.
# ---------------------------------------------------------------------------------------------------
step delivery-images
digest_of() {
    dock run --rm --network kind "$CURL_IMAGE" -fsSI \
        -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json' \
        "http://$REG/v2/$1/manifests/$2" 2>/dev/null | tr -d '\r' | sed -n 's/^[Dd]ocker-[Cc]ontent-[Dd]igest: *\(sha256:[0-9a-f]\{64\}\)$/\1/p'
}
# push_image <local image> <repository> <tag>: the digest the registry answers.
push_image() {
    dock tag "$1" "$PUSH/$2:$3" || cannot "could not tag $1"
    tries=0
    until dock push --quiet "$PUSH/$2:$3" >> "$ART/push.log" 2>&1; do
        tries=$((tries + 1))
        [ "$tries" -lt 3 ] || cannot "could not push $2:$3: see push.log"
        sleep 3
    done
    dock image rm "$PUSH/$2:$3" >/dev/null 2>&1 || true
    pushed_digest=$(digest_of "$2" "$3")
    case $pushed_digest in sha256:*) printf '%s' "$pushed_digest" ;; *) cannot "the registry gave no digest for $2:$3" ;; esac
}
dock image inspect finapp-app:rehearsal-n >/dev/null 2>&1 || cannot "no image finapp-app:rehearsal-n: the build step did not run"
dock image inspect finapp-migrator:rehearsal-n >/dev/null 2>&1 || cannot "no image finapp-migrator:rehearsal-n: the build step did not run"
# The stand-ins: an older application (N's bytes, one label), an unsigned one, and a migrator whose added migration a journal refuses.
printf 'FROM finapp-app:rehearsal-n\nLABEL finapp.rehearsal.stand-in="older"\n' \
    | dock buildx build --quiet --provenance=false --sbom=false --load -t finapp-app:delivery-older - >> "$ART/build-delivery.log" 2>&1 \
    || cannot "the older stand-in could not be built"
printf 'FROM finapp-app:rehearsal-n\nLABEL finapp.rehearsal.stand-in="unsigned"\n' \
    | dock buildx build --quiet --provenance=false --sbom=false --load -t finapp-app:delivery-unsigned - >> "$ART/build-delivery.log" 2>&1 \
    || cannot "the unsigned stand-in could not be built"
mkdir -p "$WORK/failing"
printf -- '-- The kind rehearsal'"'"'s failing migration (P15-TSK-032): no journal entry passes this CHECK, so a database holding one refuses it.\nALTER TABLE ledger.journal_entry ADD CONSTRAINT delivery_rehearsal_refused CHECK (false);\n' \
    > "$WORK/failing/V9990__delivery_rehearsal_refused.sql"
printf 'FROM finapp-migrator:rehearsal-n\nCOPY V9990__delivery_rehearsal_refused.sql /migrator/sql/ledger/\n' > "$WORK/failing/Dockerfile"
dock buildx build --quiet --provenance=false --sbom=false --load -t finapp-migrator:delivery-failing "$(dpath "$WORK/failing")" >> "$ART/build-delivery.log" 2>&1 \
    || cannot "the failing migrator could not be built"
APP_DIGEST=$(push_image finapp-app:rehearsal-n finapp-staging-app n)
MIGRATOR_DIGEST=$(push_image finapp-migrator:rehearsal-n finapp-staging-migrator n)
OLDER_DIGEST=$(push_image finapp-app:delivery-older finapp-staging-app older)
UNSIGNED_DIGEST=$(push_image finapp-app:delivery-unsigned finapp-staging-app unsigned)
FAILING_DIGEST=$(push_image finapp-migrator:delivery-failing finapp-staging-migrator failing)
{
    printf 'app.n=%s\nmigrator.n=%s\napp.older=%s\napp.unsigned=%s\nmigrator.failing=%s\n' "$APP_DIGEST" "$MIGRATOR_DIGEST" "$OLDER_DIGEST" "$UNSIGNED_DIGEST" "$FAILING_DIGEST"
    printf 'revision=%s\nolder.revision=%s\nregistry=%s\n' "$REVISION" "$OLDER_REVISION" "$REG"
} > "$ART/delivery-images.txt"
[ "$APP_DIGEST" != "$OLDER_DIGEST" ] && [ "$APP_DIGEST" != "$UNSIGNED_DIGEST" ] && [ "$MIGRATOR_DIGEST" != "$FAILING_DIGEST" ] \
    || cannot "a stand-in has its original's digest, so its case would test nothing"

SIGNING_PHRASE=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
cosign() {
    COSIGN_PASSWORD=${SIGNING_PHRASE} docker run --rm --network kind -e HOME=/tmp -e COSIGN_PASSWORD -e COSIGN_REPOSITORY="$SIGNATURES" \
        -v "$(dpath "$KEYS"):/keys" -v "$(dpath "$ATT"):/att:ro" "$COSIGN_IMAGE" "$@"
}
cosign generate-key-pair --output-key-prefix /keys/delivery >> "$ART/cosign.log" 2>&1 || cannot "the run's key pair could not be generated"
[ -s "$KEYS/delivery.key" ] && [ -s "$KEYS/delivery.pub" ] || cannot "the run's key pair is missing a file"
cp "$KEYS/delivery.pub" "$ART/delivery.pub"
SIGNING="--tlog-upload=false --use-signing-config=false --new-bundle-format=false --allow-http-registry --yes"
SOURCE=https://github.com/genadigeno/finapp
# provenance <name> <revision>: SLSA provenance v1 naming the commit the image stands for, as its git source.
provenance() {
    printf '{"buildDefinition": {"buildType": "urn:finapp:build:image:v1", "externalParameters": {"source": "%s", "revision": "%s", "builtBy": "the kind delivery rehearsal"}, "resolvedDependencies": [{"uri": "git+%s@%s", "digest": {"gitCommit": "%s"}}]}, "runDetails": {"builder": {"id": "urn:finapp:builder:kind-delivery-rehearsal"}, "metadata": {"invocationId": "delivery-%s"}}}\n' \
        "$SOURCE" "$2" "$SOURCE" "$2" "$2" "$DELIVERY_RUN" > "$ATT/$1.provenance.json"
}
# The SBOM predicate: a CycloneDX document naming nothing. The policies and promote.sh read the attestation's type and subject; what an
# image holds is the image job's SBOM, not this rehearsal's.
printf '{"bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1, "components": []}\n' > "$ATT/empty.cdx.json"
# sign_and_attest <repository>@<digest> <provenance name>
sign_and_attest() {
    # shellcheck disable=SC2086
    cosign sign --key /keys/delivery.key $SIGNING "$1" >> "$ART/cosign.log" 2>&1 || cannot "could not sign $1: see cosign.log"
    # shellcheck disable=SC2086
    cosign attest --key /keys/delivery.key $SIGNING --type slsaprovenance1 --predicate "/att/$2.provenance.json" "$1" >> "$ART/cosign.log" 2>&1 \
        || cannot "could not attest the provenance of $1: see cosign.log"
    # shellcheck disable=SC2086
    cosign attest --key /keys/delivery.key $SIGNING --type cyclonedx --predicate /att/empty.cdx.json "$1" >> "$ART/cosign.log" 2>&1 \
        || cannot "could not attest the SBOM of $1: see cosign.log"
}
provenance n "$REVISION"
provenance older "$OLDER_REVISION"
sign_and_attest "$REG/finapp-staging-app@$APP_DIGEST" n
sign_and_attest "$REG/finapp-staging-migrator@$MIGRATOR_DIGEST" n
sign_and_attest "$REG/finapp-staging-app@$OLDER_DIGEST" older
sign_and_attest "$REG/finapp-staging-migrator@$FAILING_DIGEST" n
# The node takes each by its digest from the run's registry, over plain HTTP (kind's containerd reads no per-host configuration, and a real
# registry is HTTPS): pulled ahead by containerd's own client under the exact reference the pods name, so the kubelet finds it present.
# Admission still reads every signature and attestation from the registry itself.
for reference in "finapp-staging-app@$APP_DIGEST" "finapp-staging-app@$OLDER_DIGEST" "finapp-staging-app@$UNSIGNED_DIGEST" \
    "finapp-staging-migrator@$MIGRATOR_DIGEST" "finapp-staging-migrator@$FAILING_DIGEST"; do
    dock exec "$NODE" ctr -n k8s.io images pull --plain-http --platform linux/amd64 "$REG/$reference" >> "$ART/images.log" 2>&1 \
        || cannot "the node could not pull $REG/$reference: see images.log"
done
pass "the images: N's pair, the older stand-in and the failing migrator pushed by digest, each signed and attested with the run's key; one stand-in left unsigned"

# ---------------------------------------------------------------------------------------------------
# The controllers, as the pipeline role installs them.
# ---------------------------------------------------------------------------------------------------
step delivery-controllers
sh "$ROOT/infra/scripts/controller-check.sh" render "$CONTROLLERS" > "$ART/controller-render.log" 2>&1 \
    || cannot "controller-check.sh could not render the controllers: see controller-render.log"
grep -q '^ok   rendered into' "$ART/controller-render.log" || cannot "controller-check.sh rendered nothing"
ARGOCD_STREAM="$CONTROLLERS/argocd.yaml"
if [ "$PROBE" = no-application-health ]; then
    # The probe: the controller installed without the Application health check, so a wave no longer waits for the one before.
    awk '/^  resource.customizations.health.argoproj.io_Application: \|$/ {skip = 1; next} skip && /^  [^ ]/ {skip = 0} !skip {print}' \
        "$ARGOCD_STREAM" > "$WORK/argocd-probe.yaml"
    grep -q 'health.argoproj.io_Application' "$WORK/argocd-probe.yaml" && cannot "the probe did not remove the Application health check"
    ARGOCD_STREAM="$WORK/argocd-probe.yaml"
    log "PROBE no-application-health: argocd-cm installed without the Application health check"
fi
cp "$ARGOCD_STREAM" "$ART/argocd-installed.yaml"

# The policy controller, from its pinned chart and the committed values, as admission-check.sh installs it.
mkdir -p "$WORK/policy-chart"
if [ -n "$CA_BUNDLE" ]; then
    set -- -v "$(dpath "$CA_BUNDLE"):/etc/ssl/certs/ca-certificates.crt:ro"
else
    set --
fi
dock run --rm "$@" -v "$(dpath "$WORK/policy-chart"):/chart" "$HELM_IMAGE" pull "oci://$POLICY_CONTROLLER_CHART_REPOSITORY" \
    --version "$POLICY_CONTROLLER_CHART_VERSION" --destination /chart > "$ART/policy-helm.log" 2>&1 || cannot "the policy controller's chart could not be pulled: see policy-helm.log"
grep -Fq "Digest: $POLICY_CONTROLLER_CHART_DIGEST" "$ART/policy-helm.log" || fail "the policy controller's chart is not the pinned digest"
dock run --rm -v "$(dpath "$WORK/policy-chart"):/chart" -v "$(dpath "$ROOT/deploy/operators/policy-controller"):/values:ro" "$HELM_IMAGE" \
    template policy-controller "/chart/policy-controller-$POLICY_CONTROLLER_CHART_VERSION.tgz" --namespace cosign-system --no-hooks \
    -f /values/values.yaml --set webhook.extraArgs.disable-tuf=true > "$WORK/policy-controller.yaml" 2>> "$ART/policy-helm.log" \
    || cannot "the policy controller's chart could not be rendered"
kube create namespace cosign-system >> "$ART/apply.log" 2>&1 || cannot "could not create the policy controller's namespace"
dock exec "$NODE" crictl pull "$POLICY_CONTROLLER_REPOSITORY@$POLICY_CONTROLLER_DIGEST" >> "$ART/images.log" 2>&1 || cannot "the node could not pull the policy controller"
kube apply --server-side -f - < "$WORK/policy-controller.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply the policy controller"
kube -n cosign-system rollout status deployment/policy-controller-webhook --timeout=300s >> "$ART/apply.log" 2>&1 || fail "the policy controller did not become ready"
deadline=$(( $(date +%s) + 180 ))
until [ -n "$(kube get validatingwebhookconfiguration policy.sigstore.dev -o jsonpath='{.webhooks[0].rules[0].resources}' 2>/dev/null)" ]; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "the policy controller's webhook never registered its rules"
    sleep 2
done

# The External Secrets Operator and Argo CD, exactly as controller-check.sh rendered and held them.
kube create namespace external-secrets >> "$ART/apply.log" 2>&1 || cannot "could not create the operator's namespace"
dock exec "$NODE" crictl pull "$ESO_REPOSITORY@$ESO_DIGEST" >> "$ART/images.log" 2>&1 || cannot "the node could not pull the operator"
kube apply --server-side -f - < "$CONTROLLERS/external-secrets.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply the operator"
kube -n external-secrets rollout status deployment/external-secrets --timeout=240s >> "$ART/apply.log" 2>&1 || fail "the operator did not become ready"
kube create namespace argocd >> "$ART/apply.log" 2>&1 || cannot "could not create Argo CD's namespace"
for image in "$ARGOCD_REPOSITORY@$ARGOCD_DIGEST" "$ARGOCD_DEX_REPOSITORY@$ARGOCD_DEX_DIGEST" "$ARGOCD_REDIS_REPOSITORY@$ARGOCD_REDIS_DIGEST"; do
    dock exec "$NODE" crictl pull "$image" >> "$ART/images.log" 2>&1 || cannot "the node could not pull $image: see images.log"
done
# The install's order (deploy/delivery/controller/default-project.yaml says why): the definitions, then the restricted default project, then
# everything else. In one stream the project is refused, its kind not yet known, and the controller creates an open one when it starts.
awk 'BEGIN{n=0} /^---$/{n++; next} {doc[n] = doc[n] $0 "\n"} END{for (i = 0; i <= n; i++) if (doc[i] ~ /\nkind: CustomResourceDefinition\n/ || doc[i] ~ /^kind: CustomResourceDefinition\n/) printf "---\n%s", doc[i]}' \
    "$ARGOCD_STREAM" > "$WORK/argocd-definitions.yaml"
[ "$(grep -c '^kind: CustomResourceDefinition$' "$WORK/argocd-definitions.yaml")" -ge 3 ] || cannot "Argo CD's definitions were not found in its render"
kube apply --server-side -f - < "$WORK/argocd-definitions.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply Argo CD's definitions"
kube wait --for condition=established --timeout=120s crd/appprojects.argoproj.io crd/applications.argoproj.io >> "$ART/apply.log" 2>&1 \
    || cannot "Argo CD's definitions were not established"
kube apply --server-side -f - < "$ROOT/deploy/delivery/controller/default-project.yaml" >> "$ART/apply.log" 2>&1 || cannot "the restricted default project could not be applied"
kube apply --server-side --force-conflicts -f - < "$ARGOCD_STREAM" >> "$ART/apply.log" 2>&1 || cannot "could not apply Argo CD: see apply.log"
kube -n argocd rollout status statefulset/argocd-application-controller --timeout=300s >> "$ART/apply.log" 2>&1 || fail "Argo CD's application controller did not become ready"
for deployment in argocd-repo-server argocd-server argocd-redis argocd-applicationset-controller; do
    kube -n argocd rollout status "deployment/$deployment" --timeout=300s >> "$ART/apply.log" 2>&1 || fail "Argo CD's $deployment did not become ready"
done
running=$(kube -n argocd get pods -o jsonpath='{range .items[*]}{range .spec.containers[*]}{.image}{"\n"}{end}{end}' | sort -u)
printf '%s\n' "$running" > "$ART/argocd-images.txt"
for image in $running; do
    case $image in
        "$ARGOCD_REPOSITORY@$ARGOCD_DIGEST" | "$ARGOCD_DEX_REPOSITORY@$ARGOCD_DEX_DIGEST" | "$ARGOCD_REDIS_REPOSITORY@$ARGOCD_REDIS_DIGEST") ;;
        *) fail "Argo CD runs $image, which is not a pinned digest" ;;
    esac
done
pass "the controllers: Argo CD $ARGOCD_VERSION and the External Secrets Operator from the render controller-check.sh held to its rule, the policy controller from its pinned chart; every Argo CD image a pinned digest"

# ---------------------------------------------------------------------------------------------------
# The default project reaches nothing.
# ---------------------------------------------------------------------------------------------------
step delivery-default-project
spec=$(argo appproject default '{.spec}')
printf '%s\n' "$spec" > "$ART/default-project.json"
case $spec in
    *'"sourceRepos":['[!]]* | *'"destinations":['[!]]* | *'"clusterResourceWhitelist":['[!]]*) fail "the default project as installed reaches something: $spec" ;;
esac
printf '%s' "$spec" | grep -q '"namespaceResourceBlacklist":\[{"group":"\*","kind":"\*"}\]' || fail "the default project as installed does not refuse every namespaced kind: $spec"
# An Application placed there, as anyone able to write one in argocd could, pointing at a ConfigMap of its own.
mkdir -p "$CLONE/rehearsal/default-probe"
printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: delivery-default-probe\n  namespace: default\ndata:\n  reached: "yes"\n' > "$CLONE/rehearsal/default-probe/configmap.yaml"
commit_clone "The rehearsal: a ConfigMap only an Application in the default project would apply"
DEFAULT_COMMIT=$(push_clone "the default project's probe")
cat <<YAML | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the Application in the default project could not be written"
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: delivery-default-probe
  namespace: argocd
spec:
  project: default
  source:
    repoURL: $REPO_URL
    targetRevision: master
    path: rehearsal/default-probe
  destination:
    server: https://kubernetes.default.svc
    namespace: default
  syncPolicy:
    automated: {}
YAML
refused=""
deadline=$(( $(date +%s) + 180 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    conditions=$(argo application delivery-default-probe '{range .status.conditions[*]}{.type}: {.message}{"\n"}{end}')
    case $conditions in *InvalidSpecError*"not permitted"*) refused=1; break ;; esac
    sleep 5
done
printf '%s\n' "$conditions" > "$ART/default-probe-conditions.txt"
sleep 30
kube -n default get configmap delivery-default-probe >/dev/null 2>&1 && fail "an Application in the default project applied its ConfigMap"
[ -n "$refused" ] || fail "the Application in the default project was not refused as not permitted: $(printf '%s' "$conditions" | tr '\n' ';' | cut -c1-300)"
[ -z "$(argo application delivery-default-probe '{.status.operationState.phase}')" ] || fail "the Application in the default project started a sync"
kube -n argocd delete application delivery-default-probe --wait=true >> "$ART/apply.log" 2>&1 || true
pass "the default project: as installed it reaches nothing, and an Application placed in it was refused ($(printf '%s' "$conditions" | grep -m1 'not permitted' | cut -c1-140)) and applied nothing"

# ---------------------------------------------------------------------------------------------------
# What the environment adds: the store, the edge's TLS, the smoke's Prometheus, and the rehearsal root in the clone.
# ---------------------------------------------------------------------------------------------------
step delivery-environment
secret_value() { kube -n "$1" get "secret/$2" -o "jsonpath={.data.$3}" | base64 -d; }
STORE="$WORK/store"
mkdir -p "$STORE"
: > "$STORE/app.env"
: > "$STORE/migrator.env"
for key in $(sed -n 's/^ *- secret[K]ey: *\(FINAPP_[A-Z0-9_]*\) *$/\1/p' "$ROOT/deploy/components/secret-delivery/externalsecret-app.yaml") \
    FINAPP_PAYMENTS_PROVIDER_URL FINAPP_PAYMENTMETHODS_TOKENISATION_URL FINAPP_MERCHANT_PAYOUT_PROVIDER_URL; do
    case $key in FINAPP_KAFKA_SASL_*) continue ;; esac
    printf '%s=%s\n' "$key" "$(secret_value finapp finapp-app-secrets "$key")" >> "$STORE/app.env"
done
for key in $(sed -n 's/^ *- secret[K]ey: *\(FINAPP_[A-Z0-9_]*\) *$/\1/p' "$ROOT/deploy/components/secret-delivery/externalsecret-migrator.yaml"); do
    printf '%s=%s\n' "$key" "$(secret_value finapp finapp-migrator-secrets "$key")" >> "$STORE/migrator.env"
done
printf 'username=%s\npassword=%s\n' "$(secret_value finapp finapp-app-secrets FINAPP_KAFKA_SASL_USERNAME)" "$(secret_value finapp finapp-app-secrets FINAPP_KAFKA_SASL_PASSWORD)" > "$STORE/kafka.env"
grep -q "^$HELD_KEY=." "$STORE/app.env" || cannot "the store was not filled from the generated Secret"
# Handed over: the operator writes them from now on.
for workload in migrator app; do
    kube -n finapp delete "secret/finapp-$workload-secrets" --wait=true >> "$ART/apply.log" 2>&1 || cannot "the generated Secret finapp-$workload-secrets could not be handed over"
done

# delivery_store <withheld key or none>: the three stores, their entries through standard input alone.
delivery_store() {
    for store in app migrator; do
        {
            printf 'apiVersion: external-secrets.io/v1\nkind: SecretStore\nmetadata:\n  name: finapp-%s-store\n  namespace: finapp\nspec:\n  provider:\n    fake:\n      data:\n' "$store"
            while IFS= read -r line; do
                [ "${line%%=*}" != "$1" ] || continue
                printf "        - key: %s\n          value: '%s'\n" "${line%%=*}" "$(printf '%s' "${line#*=}" | sed "s/'/''/g")"
            done < "$STORE/$store.env"
        } | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the store finapp-$store-store could not be written"
    done
    {
        printf 'apiVersion: external-secrets.io/v1\nkind: SecretStore\nmetadata:\n  name: finapp-app-kafka-store\n  namespace: finapp\nspec:\n  provider:\n    fake:\n      data:\n'
        printf "        - key: application\n          value: '{\"username\":\"%s\",\"password\":\"%s\"}'\n" "$(sed -n 's/^username=//p' "$STORE/kafka.env")" "$(sed -n 's/^password=//p' "$STORE/kafka.env")"
    } | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the store finapp-app-kafka-store could not be written"
}
delivery_store "$HELD_KEY"
log "the stores written, the application's lacking $HELD_KEY"

# The edge's two certificates, as an environment supplies them, from the run's local CA; the client trusts that CA.
issue edge-public "DNS:public.finapp.test"
issue edge-operator "DNS:operator.finapp.test"
secret_of finapp-edge finapp-edge-public-tls "tls.crt=$CA_DIR/edge-public.crt" "tls.key=$CA_DIR/edge-public.key"
secret_of finapp-edge finapp-edge-operator-tls "tls.crt=$CA_DIR/edge-operator.crt" "tls.key=$CA_DIR/edge-operator.key"
cp "$CA_DIR/ca.crt" "$CLIENT/edge-ca.crt"

# The smoke's Prometheus, test-only: the replicas' management port, read through the scrape the release's policy admits.
dock exec "$NODE" crictl pull "$PROMETHEUS_IMAGE" >> "$ART/images.log" 2>&1 || cannot "the node could not pull Prometheus"
cat <<YAML | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the smoke's Prometheus could not be applied"
apiVersion: v1
kind: ServiceAccount
metadata:
  name: delivery-prometheus
  namespace: finapp-observability
  labels: {finapp/test-only: "true"}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: delivery-prometheus
  namespace: finapp
  labels: {finapp/test-only: "true"}
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [get, list, watch]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: delivery-prometheus
  namespace: finapp
  labels: {finapp/test-only: "true"}
roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: delivery-prometheus}
subjects:
  - {kind: ServiceAccount, name: delivery-prometheus, namespace: finapp-observability}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: delivery-prometheus
  namespace: finapp-observability
  labels: {finapp/test-only: "true"}
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
    scrape_configs:
      - job_name: finapp
        metrics_path: /actuator/prometheus
        kubernetes_sd_configs:
          - role: pod
            namespaces:
              names: [finapp]
        relabel_configs:
          - source_labels: [__meta_kubernetes_pod_label_app_kubernetes_io_name]
            regex: finapp-app
            action: keep
          - source_labels: [__meta_kubernetes_pod_container_port_number]
            regex: "8081"
            action: keep
          - source_labels: [__meta_kubernetes_pod_name]
            target_label: pod
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: delivery-prometheus
  namespace: finapp-observability
  labels: {finapp/test-only: "true"}
spec:
  replicas: 1
  selector:
    matchLabels: {app.kubernetes.io/name: delivery-prometheus}
  template:
    metadata:
      labels: {app.kubernetes.io/name: delivery-prometheus, finapp/role: metrics-scraper, finapp/test-only: "true"}
    spec:
      serviceAccountName: delivery-prometheus
      securityContext:
        runAsNonRoot: true
        runAsUser: 65534
        runAsGroup: 65534
        fsGroup: 65534
        seccompProfile: {type: RuntimeDefault}
      containers:
        - name: prometheus
          image: $PROMETHEUS_IMAGE
          imagePullPolicy: IfNotPresent
          args: ["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus", "--web.listen-address=:9090"]
          ports: [{containerPort: 9090}]
          resources:
            requests: {cpu: 100m, memory: 256Mi}
            limits: {cpu: 500m, memory: 256Mi}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: ["ALL"]}
          volumeMounts:
            - {name: config, mountPath: /etc/prometheus}
            - {name: data, mountPath: /prometheus}
      volumes:
        - name: config
          configMap: {name: delivery-prometheus}
        - name: data
          emptyDir: {sizeLimit: 256Mi}
---
apiVersion: v1
kind: Service
metadata:
  name: delivery-prometheus
  namespace: finapp-observability
  labels: {finapp/test-only: "true"}
spec:
  selector: {app.kubernetes.io/name: delivery-prometheus}
  ports: [{port: 9090, targetPort: 9090}]
YAML
kube -n finapp-observability rollout status deployment/delivery-prometheus --timeout=180s >> "$ART/apply.log" 2>&1 || fail "the smoke's Prometheus did not become ready"
PROMETHEUS_ADDRESS="$(kube -n finapp-observability get service delivery-prometheus -o jsonpath='{.spec.clusterIP}'):9090"

# The rehearsal root, in the clone: a layer over each Application's path, and over the delivery itself.
R="$CLONE/rehearsal"
mkdir -p "$R/root" "$R/overlays/staging" "$R/edge/staging" "$R/admission"
cat > "$R/root/kustomization.yaml" <<YAML
# The kind rehearsal's root (P15-TSK-032): what staging's environment adds, generated by infra/scripts/kind-rehearsal/delivery.sh into the
# run's own repository and never committed to the platform's. Every Application reads the run's repository; three read a layer over their
# committed path that adds what the environment would.
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../deploy/delivery/staging
patches:
  - path: projects.yaml
  - path: project-cluster.yaml
  - path: app-admission.yaml
  - path: app-namespaces.yaml
  - path: app-finapp.yaml
  - path: app-edge.yaml
YAML
cat > "$R/root/projects.yaml" <<YAML
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: finapp
  namespace: argocd
spec:
  sourceRepos:
    - $REPO_URL
YAML
cat > "$R/root/project-cluster.yaml" <<YAML
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: finapp-cluster
  namespace: argocd
spec:
  sourceRepos:
    - $REPO_URL
YAML
cat > "$R/root/app-admission.yaml" <<YAML
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: finapp-admission
  namespace: argocd
spec:
  source:
    repoURL: $REPO_URL
    path: rehearsal/admission
YAML
cat > "$R/root/app-namespaces.yaml" <<YAML
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: finapp-namespaces
  namespace: argocd
spec:
  source:
    repoURL: $REPO_URL
YAML
cat > "$R/root/app-finapp.yaml" <<YAML
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: finapp
  namespace: argocd
spec:
  source:
    repoURL: $REPO_URL
    path: rehearsal/overlays/staging
  # The store's contents are the environment's, outside the repository: Argo CD creates the store and leaves its provider alone.
  ignoreDifferences:
    - group: external-secrets.io
      kind: SecretStore
      jsonPointers:
        - /spec/provider
  syncPolicy:
    syncOptions:
      - PruneLast=true
      - FailOnSharedResource=true
      - RespectIgnoreDifferences=true
YAML
cat > "$R/root/app-edge.yaml" <<YAML
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: finapp-edge
  namespace: argocd
spec:
  source:
    repoURL: $REPO_URL
    path: rehearsal/edge/staging
YAML
{
    printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - ../../deploy/admission\npatches:\n'
    for policy in finapp-images-signed finapp-images-attested; do
        printf '  - target:\n      kind: ClusterImagePolicy\n      name: %s\n    path: authority.yaml\n' "$policy"
    done
} > "$R/admission/kustomization.yaml"
{
    printf -- '- op: replace\n  path: /spec/authorities/0/key/data\n  value: |\n'
    sed 's/^/    /' "$KEYS/delivery.pub"
    printf -- '- op: replace\n  path: /spec/authorities/0/source/0/oci\n  value: %s\n' "$SIGNATURES"
} > "$R/admission/authority.yaml"
# The release's layer: the registry by name (the digest stays the overlay's), the store, the data tier's place, kind's resources and trust.
sed -n '/^patches:$/,$p' "$ROOT/deploy/overlays/kind/kustomization.yaml" > "$WORK/kind-patches.yaml"
cat > "$R/overlays/staging/kustomization.yaml" <<YAML
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../deploy/overlays/staging
images:
  - name: finapp-app
    newName: $REG/finapp-staging-app
  - name: finapp-migrator
    newName: $REG/finapp-staging-migrator
YAML
# kind's own patches, as the kind overlay states them: the Deployment's trust and memory, the Job's trust, the LimitRange, the quota and
# the autoscaler's maximum. Then the environment's: the store, the data tier, the configuration a node and its client need.
cat "$WORK/kind-patches.yaml" >> "$R/overlays/staging/kustomization.yaml"
cat >> "$R/overlays/staging/kustomization.yaml" <<YAML
  - target:
      kind: SecretStore
    patch: |-
      - op: replace
        path: /spec/provider
        value:
          fake:
            data: []
  - target:
      kind: NetworkPolicy
      name: finapp-app-data-tier
    path: app-data-tier.yaml
  - target:
      kind: NetworkPolicy
      name: finapp-migrator-data-tier
    path: migrator-data-tier.yaml
  - target:
      kind: ConfigMap
      name: finapp-app-config
    path: configuration.yaml
  - target:
      kind: ExternalSecret
      name: finapp-app-secrets
    path: provider-entries.yaml
YAML
grep -q '^      name: finapp-app$' "$R/overlays/staging/kustomization.yaml" && grep -q 'name: finapp-migrator$' "$R/overlays/staging/kustomization.yaml" \
    || cannot "the kind overlay's patches were not carried into the rehearsal's layer"
# The data tier's two policies, as the kind overlay states them (networkpolicy-test-only.yaml), in place of staging's documentation ranges.
render_overlay kindpolicies overlays/kind
for workload in app migrator; do
    {
        printf -- '- op: replace\n  path: /spec/egress\n  value:\n'
        sed -n '/^  egress:$/,$p' "$WORK/render/kindpolicies/NetworkPolicy.finapp-$workload-data-tier.yaml" | sed '1d' | sed 's/^/  /'
    } > "$R/overlays/staging/$workload-data-tier.yaml"
    grep -q 'finapp-postgres' "$R/overlays/staging/$workload-data-tier.yaml" || cannot "the $workload data-tier policy was not read from the kind overlay"
done
{
    for literal in $(sed -n 's/^      - \(FINAPP_[A-Z0-9_]*=[^ ]*\)$/\1/p' "$ROOT/deploy/overlays/kind/kustomization.yaml"); do
        case ${literal%%=*} in FINAPP_DB_INSTANCES | FINAPP_DB_SERVER_MAX_CONNECTIONS | FINAPP_DB_RESERVED_CONNECTIONS) continue ;; esac
        printf -- '- op: add\n  path: /data/%s\n  value: "%s"\n' "${literal%%=*}" "${literal#*=}"
    done
} > "$R/overlays/staging/configuration.yaml"
grep -q 'FINAPP_EDGE_PROXIES' "$R/overlays/staging/configuration.yaml" || cannot "the kind configuration was not read"
{
    for key in FINAPP_PAYMENTS_PROVIDER_URL FINAPP_PAYMENTMETHODS_TOKENISATION_URL FINAPP_MERCHANT_PAYOUT_PROVIDER_URL; do
        printf -- '- op: add\n  path: /spec/data/-\n  value:\n    %s: %s\n    remoteRef:\n      key: %s\n' "$ESO_ENTRY_FIELD" "$key" "$key"
    done
} > "$R/overlays/staging/provider-entries.yaml"
sed -n '/^patches:$/,$p' "$ROOT/deploy/edge/overlays/kind/kustomization.yaml" > "$WORK/edge-patches.yaml"
{
    printf 'apiVersion: kustomize.config.k8s.io/v1beta1\nkind: Kustomization\nresources:\n  - ../../../deploy/edge/overlays/staging\n'
    cat "$WORK/edge-patches.yaml"
} > "$R/edge/staging/kustomization.yaml"
grep -q 'nodePort' "$R/edge/staging/kustomization.yaml" || cannot "the kind edge's Services were not carried into the rehearsal's layer"
# The rendered layers, read before anything is committed: what Argo CD will render is checked here first.
for layer in root overlays/staging edge/staging admission; do
    dock run --rm -v "$(dpath "$CLONE"):/clone:ro" "$KUSTOMIZE_IMAGE" build "/clone/rehearsal/$layer" > "$WORK/layer.yaml" 2> "$ART/layer-$(printf '%s' "$layer" | tr '/' '-').err" \
        || cannot "the rehearsal's $layer layer does not render: see layer-*.err"
done
pass "the environment: the stores written outside every manifest (the application's lacking $HELD_KEY), the edge's TLS, the smoke's Prometheus, and the rehearsal root rendering"

# ---------------------------------------------------------------------------------------------------
# The promotion: refusals first, then the pair, committed and pushed.
# ---------------------------------------------------------------------------------------------------
step delivery-promote
commit_clone "The rehearsal root: what staging's environment adds, on kind"
promote() {
    PROMOTE_REGISTRY="$REG" PROMOTE_SIGNATURES="$SIGNATURES" PROMOTE_KEY="$KEYS/delivery.pub" PROMOTE_DOCKER_NETWORK=kind PROMOTE_LOCAL_REGISTRY=1 \
        sh "$CLONE/infra/scripts/promote.sh" staging "$@"
}
promote_status=0
promote latest "$MIGRATOR_DIGEST" "$REVISION" > "$ART/promote-tag.txt" 2>&1 || promote_status=$?
[ "$promote_status" -eq 1 ] && grep -q "is named by 'latest', which is not a digest" "$ART/promote-tag.txt" \
    || fail "promote.sh did not refuse a tag by name (exit $promote_status): see promote-tag.txt"
promote_status=0
promote "$UNSIGNED_DIGEST" "$MIGRATOR_DIGEST" "$REVISION" > "$ART/promote-unsigned.txt" 2>&1 || promote_status=$?
[ "$promote_status" -eq 1 ] && grep -q "has no signature the environment's authority accepts" "$ART/promote-unsigned.txt" \
    || fail "promote.sh did not refuse an unsigned digest by name (exit $promote_status): see promote-unsigned.txt"
[ -z "$(git_clone status --porcelain)" ] || fail "a refused promotion wrote into the clone: $(git_clone status --porcelain | tr '\n' ' ')"
pass "promote.sh refused a tag and an unsigned digest, each by name, and wrote nothing"
promote "$APP_DIGEST" "$MIGRATOR_DIGEST" "$REVISION" > "$ART/promote-n.txt" 2>&1 || fail "promote.sh refused N's signed pair: see promote-n.txt"
grep -q "digest: $APP_DIGEST" "$CLONE/deploy/overlays/staging/kustomization.yaml" && grep -q "digest: $MIGRATOR_DIGEST" "$CLONE/deploy/overlays/staging/kustomization.yaml" \
    || fail "promote.sh did not write the pair into the staging overlay"
grep -q "$REVISION" "$CLONE/deploy/overlays/staging/release.yaml" || fail "promote.sh did not write the revision into staging's release record"
[ "$(git_clone status --porcelain | wc -l | tr -d ' ')" = 2 ] || fail "promote.sh wrote other than the overlay and its release record: $(git_clone status --porcelain | tr '\n' ' ')"
commit_clone "Promote N to staging: $APP_DIGEST and $MIGRATOR_DIGEST, built from $REVISION"
PROMOTED_COMMIT=$(push_clone "N's promotion")
pass "promote.sh staging wrote the pair and the release record, two files, committed and pushed as one change"

# ---------------------------------------------------------------------------------------------------
# The root: the waves, the hold on a store lacking one key, the bootstrap meanwhile, the hook awaited.
# ---------------------------------------------------------------------------------------------------
step delivery-root
sed -e "s#^    - https://github.com/genadigeno/finapp.git\$#    - $REPO_URL#" -e "s#^    repoURL: https://github.com/genadigeno/finapp.git\$#    repoURL: $REPO_URL#" \
    -e "s#^    path: deploy/delivery/staging\$#    path: rehearsal/root#" "$ROOT/deploy/delivery/roots/staging.yaml" > "$WORK/root.yaml"
grep -q "path: rehearsal/root" "$WORK/root.yaml" && [ "$(grep -c "$REPO_URL" "$WORK/root.yaml")" = 2 ] || cannot "the committed root could not be pointed at the run's repository"
cp "$WORK/root.yaml" "$ART/root-applied.yaml"
ROOT_APPLIED=$(date +%s)
kube apply -f - < "$WORK/root.yaml" >> "$ART/apply.log" 2>&1 || cannot "the root could not be applied"
record "the root applied at $(date -u +%Y-%m-%dT%H:%M:%SZ), the repository at $PROMOTED_COMMIT"

# The hold: the release reaches wave -2 and goes no further while the application's ExternalSecret lacks its key. Argo CD's own check judges
# an ExternalSecret whose Ready is False Degraded, and a Degraded resource fails the sync at its wave (seen on kind, 2026-11-12): so the
# sync ends Failed there, nothing of waves -1 and 0 applied, and - its retry stated as none - not run again.
held=""
deadline=$(( $(date +%s) + 900 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    ready=$(kube -n finapp get externalsecret finapp-app-secrets -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
    migrator_ready=$(kube -n finapp get externalsecret finapp-migrator-secrets -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)
    phase=$(argo application finapp '{.status.operationState.phase}')
    if [ "$ready" = False ] && [ "$migrator_ready" = True ] && { [ "$phase" = Failed ] || [ "$phase" = Error ]; }; then held=1; break; fi
    sleep 5
done
kube -n argocd get application finapp -o yaml > "$ART/application-finapp-at-hold.yaml" 2>&1 || true
[ -n "$held" ] || fail "the release's sync did not stop at its ExternalSecrets with the application's not Ready (phase '$phase'): see application-finapp-at-hold.yaml"
HOLD_FROM=$(date +%s)
HELD_STARTED=$(argo application finapp '{.status.operationState.startedAt}')
record "the hold: the release's sync ended $phase at its ExternalSecrets ($(argo application finapp '{.status.operationState.message}'), retries '$(argo application finapp '{.status.operationState.retryCount}')')"

# Meanwhile, the role bootstrap as a real environment runs it: in finapp, the signed migrator alone, no init container, through admission.
awk '/^      initContainers:$/ {skip = 1; next} skip && /^      containers:$/ {skip = 0} !skip {print}' "$DIR/bootstrap-job.yaml" \
    | sed "s#MIGRATOR_IMAGE#$REG/finapp-staging-migrator@$MIGRATOR_DIGEST#" > "$WORK/bootstrap-real.yaml"
grep -q 'initContainers\|PROBE_IMAGE' "$WORK/bootstrap-real.yaml" && cannot "the real bootstrap Job still carries an init container"
cp "$WORK/bootstrap-real.yaml" "$ART/bootstrap-real.yaml"
kube apply -f - < "$WORK/bootstrap-real.yaml" >> "$ART/apply.log" 2>&1 || fail "the role bootstrap Job was refused: see apply.log"
wait_job finapp finapp-role-bootstrap 420 role-bootstrap-real
bootstrap_pod=$(kube -n finapp get pods -l job-name=finapp-role-bootstrap -o jsonpath='{range .items[*]}{.spec.containers[0].image}|{.spec.initContainers}{"\n"}{end}' | head -1)
[ "$bootstrap_pod" = "$REG/finapp-staging-migrator@$MIGRATOR_DIGEST|" ] || fail "the bootstrap's pod ran '$bootstrap_pod', not the signed migrator alone"
pass "the role bootstrap ran as a real environment runs it: in finapp, the signed migrator image alone, no init container, admitted and complete"

# Still held a minute on: nothing of waves -1 and 0 exists, and no sync of that revision started again.
[ "$(( $(date +%s) - HOLD_FROM ))" -ge 60 ] || sleep $(( 60 - ($(date +%s) - HOLD_FROM) ))
kube -n finapp get job finapp-migrator >/dev/null 2>&1 && fail "the migrator hook ran while an ExternalSecret of the wave before was not Ready"
kube -n finapp get deployment finapp-app >/dev/null 2>&1 && fail "the Deployment was applied while an ExternalSecret of wave -2 was not Ready"
[ "$(argo application finapp '{.status.operationState.startedAt}')" = "$HELD_STARTED" ] || fail "the release's failed sync was started again on its own"
retries=$(argo application finapp '{.status.operationState.retryCount}')
[ -z "$retries" ] || [ "$retries" = 0 ] || fail "the release's sync was retried: retry count $retries"
pass "an ExternalSecret not Ready stopped its wave: the sync Failed there, for $(( $(date +%s) - HOLD_FROM )) s no migrator Job, no Deployment, not retried"

# manual_sync <application> <revision> <label>: section 11.36's one sync of the same revision by hand, by a person holding Argo CD's sync on
# that Application (here written as Argo CD's own client writes it: the Application's operation). 0 when it succeeded.
manual_sync() {
    kube -n argocd patch application "$1" --type merge \
        -p "{\"operation\":{\"initiatedBy\":{\"username\":\"finapp-rehearsal-person\"},\"sync\":{\"revision\":\"$2\"}}}" >> "$ART/apply.log" 2>&1 \
        || cannot "a sync of $1 by hand could not be started"
    asked=$(date +%s)
    deadline=$((asked + 1500))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        state=$(argo application "$1" '{.status.operationState.operation.initiatedBy.username}|{.status.operationState.phase}')
        case $state in
            "finapp-rehearsal-person|Succeeded") record "$3: $1 synced by hand at $2, $(( $(date +%s) - asked )) s"; return 0 ;;
            "finapp-rehearsal-person|Failed" | "finapp-rehearsal-person|Error") record "$3: $1's sync by hand FAILED"; return 1 ;;
        esac
        sleep 5
    done
    record "$3: $1's sync by hand did not end within $((deadline - asked)) s"
    return 1
}

# The key reaches the store, as the environment's store would gain it; the operator reads it on its next try; then one sync by hand.
delivery_store none
KEY_ADDED=$(date +%s)
record "the store gained $HELD_KEY"
deadline=$(( $(date +%s) + 1500 ))
until [ "$(kube -n finapp get externalsecret finapp-app-secrets -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" = True ]; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "the application's ExternalSecret did not turn Ready within 25 minutes of the store gaining its key"
    sleep 5
done
ready_at=$(kube -n finapp get externalsecret finapp-app-secrets -o jsonpath='{.status.conditions[?(@.type=="Ready")].lastTransitionTime}')
record "the ExternalSecret turned Ready at $ready_at, $(( $(date -u -d "$ready_at" +%s) - KEY_ADDED )) s after the store gained the key"
manual_sync finapp "$PROMOTED_COMMIT" first-release || fail "the release's sync by hand, once the store held its key, did not succeed"
# The root's own sync waited on the release and failed with it; the same, by hand, applies the edge's wave.
if [ "$(argo application finapp-edge '{.status.health.status}')" != Healthy ]; then
    root_phase=$(argo application finapp-staging '{.status.operationState.phase}')
    record "the root's sync is $root_phase while the release is healthy: the edge's wave waits for it"
    if [ "$root_phase" != Running ]; then
        manual_sync finapp-staging "$PROMOTED_COMMIT" root || fail "the root's sync by hand did not succeed"
    fi
fi
deadline=$(( $(date +%s) + 900 ))
done_root=""
while [ "$(date +%s)" -lt "$deadline" ]; do
    root_state=$(argo application finapp-staging '{.status.sync.status}|{.status.health.status}')
    edge_state=$(argo application finapp-edge '{.status.sync.status}|{.status.health.status}')
    if [ "$root_state" = "Synced|Healthy" ] && [ "$edge_state" = "Synced|Healthy" ]; then done_root=1; break; fi
    sleep 10
done
kube -n argocd get applications -o yaml > "$ART/applications-first-sync.yaml" 2>&1 || true
[ -n "$done_root" ] || fail "the root did not reach every Application synced and healthy: see applications-first-sync.yaml"
record "the root synced and healthy $(( $(date +%s) - ROOT_APPLIED )) s after it was applied"

# The waves: each Application created only once the one before had finished its sync.
: > "$ART/waves.tsv"
previous=""
for app in finapp-admission finapp-namespaces finapp finapp-edge; do
    line=$(argo application "$app" '{.metadata.creationTimestamp}|{.status.operationState.startedAt}|{.status.operationState.finishedAt}')
    printf '%s\t%s\n' "$app" "$line" >> "$ART/waves.tsv"
    created=${line%%|*}
    if [ -n "$previous" ]; then
        previous_finished=${previous##*|}
        [ -n "$previous_finished" ] || fail "waves: the Application before $app has no finished sync"
        if [ "$(date -u -d "$created" +%s)" -lt "$(date -u -d "$previous_finished" +%s)" ]; then
            fail "waves: $app was created at $created, before the Application of the wave before finished its sync at $previous_finished"
        fi
    fi
    previous=$line
done
pass "waves: admission, namespaces, the release and the edge, each Application created only after the one before finished its sync ($(tr '\t\n' ' ;' < "$ART/waves.tsv" | cut -c1-500))"

# The admission policies: healthy in Argo CD only because the controller observed their generation and says Ready.
kube get clusterimagepolicy -o jsonpath='{range .items[*]}{.metadata.name}|{.metadata.generation}|{.status.observedGeneration}|{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' > "$ART/policies-status.txt"
[ "$(grep -c . "$ART/policies-status.txt")" = 3 ] || fail "not three admission policies: see policies-status.txt"
while IFS='|' read -r policy generation observed ready_status; do
    [ -n "$observed" ] && [ "$observed" = "$generation" ] && [ "$ready_status" = True ] \
        || fail "admission: $policy is at generation $generation, observed '$observed', Ready '$ready_status'"
done < "$ART/policies-status.txt"
argo application finapp-admission '{range .status.resources[*]}{.kind}|{.name}|{.health.status}{"\n"}{end}' > "$ART/admission-health.txt"
[ "$(grep -c '^ClusterImagePolicy|.*|Healthy$' "$ART/admission-health.txt")" = 3 ] || fail "admission: Argo CD does not judge the three policies Healthy: see admission-health.txt"
pass "admission: each v1beta1 policy carries observedGeneration equal to its generation and Ready True, and Argo CD's check judged the three Healthy on that"

# Inside the release: the hook after the ExternalSecrets, the Deployment after the hook.
job_created=$(kube -n finapp get job finapp-migrator -o jsonpath='{.metadata.creationTimestamp}')
job_done=$(kube -n finapp get job finapp-migrator -o jsonpath='{.status.completionTime}')
deployment_created=$(kube -n finapp get deployment finapp-app -o jsonpath='{.metadata.creationTimestamp}')
record "the release: the ExternalSecret Ready at $ready_at, the migrator hook created $job_created and complete $job_done, the Deployment created $deployment_created"
[ -n "$job_done" ] || fail "the migrator hook has no completion time"
[ "$(date -u -d "$job_created" +%s)" -ge "$(date -u -d "$ready_at" +%s)" ] || fail "the migrator hook started before the application's ExternalSecret was Ready"
[ "$(date -u -d "$deployment_created" +%s)" -ge "$(date -u -d "$job_done" +%s)" ] || fail "the Deployment was applied at $deployment_created, before the migrator hook completed at $job_done"
kube -n finapp logs job/finapp-migrator > "$ART/migrator-first.log" 2>&1 || true
grep -q 'result=APPLIED' "$ART/migrator-first.log" || fail "the migrator hook did not report its migrations applied: see migrator-first.log"
pass "the migrator Sync hook was awaited: created after the ExternalSecrets were Ready, the Deployment applied only after it completed"

# ---------------------------------------------------------------------------------------------------
# The first release: money through it, the smoke on the API server, the census.
# ---------------------------------------------------------------------------------------------------
step delivery-first-release
client prepare >> "$ART/client.log" 2>&1 || fail "the smoke's participants could not be registered: see client.log"
psql_query -f - < "$CLIENT/seed-1.sql" >> "$ART/seed.log" 2>&1 || fail "the seed failed: see seed.log"
client onboard >> "$ART/client.log" 2>&1 || fail "the onboarding failed: see client.log"
psql_query -f - < "$CLIENT/seed-2.sql" >> "$ART/seed.log" 2>&1 || fail "the destination seed failed: see seed.log"
client map-gl >> "$ART/client.log" 2>&1 || fail "the GL mapping could not be activated: see client.log"
delivery_money delivery-promoted
post_deploy_smoke promoted
census delivery-promoted

# ---------------------------------------------------------------------------------------------------
# Self-heal: a hand change to a field the release states, put back.
# ---------------------------------------------------------------------------------------------------
step delivery-self-heal
# revisionHistoryLimit: stated by deploy/base, and no pod rolls for it.
kube -n finapp patch deployment finapp-app --type=merge -p '{"spec":{"revisionHistoryLimit":3}}' >> "$ART/apply.log" 2>&1 || cannot "the hand change could not be made"
changed=$(date +%s)
healed=""
deadline=$((changed + 300))
while [ "$(date +%s)" -lt "$deadline" ]; do
    [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.revisionHistoryLimit}')" = 10 ] && { healed=1; break; }
    sleep 3
done
[ -n "$healed" ] || fail "self-heal did not put back a hand change of revisionHistoryLimit within five minutes"
record "self-heal put back the hand change in $(( $(date +%s) - changed )) s"
pass "self-heal put back a hand change to a field the release states, in $(( $(date +%s) - changed )) s"

# An observation, not a gate (the runbook forbids the act whatever the controller does): `kubectl rollout restart` as the cluster's admin,
# a field the release does not state. Is the Application OutOfSync, does self-heal take the annotation back, and how many times does the
# fleet roll?
step delivery-rollout-restart
revision_before=$(kube -n finapp get deployment finapp-app -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')
kube -n finapp rollout restart deployment/finapp-app >> "$ART/apply.log" 2>&1 || cannot "the rollout restart could not be made"
restarted=$(date +%s)
out_of_sync=""
deadline=$((restarted + 300))
while [ "$(date +%s)" -lt "$deadline" ]; do
    [ "$(argo application finapp '{.status.sync.status}')" = OutOfSync ] && { out_of_sync=$(( $(date +%s) - restarted )); break; }
    sleep 3
done
kube -n finapp rollout status deployment/finapp-app --timeout=900s >> "$ART/apply.log" 2>&1 || fail "the restarted rollout did not complete"
sleep 60
annotation=$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.template.metadata.annotations.kubectl\.kubernetes\.io/restartedAt}')
revision_after=$(kube -n finapp get deployment finapp-app -o jsonpath='{.metadata.annotations.deployment\.kubernetes\.io/revision}')
sync_now=$(argo application finapp '{.status.sync.status}|{.status.operationState.startedAt}')
record "rollout restart: the Application $( [ -n "$out_of_sync" ] && printf 'went OutOfSync after %s s' "$out_of_sync" || printf 'stayed Synced for five minutes'); after the roll the restartedAt annotation is '$annotation' ($( [ -n "$annotation" ] && printf 'kept: self-heal did not take it back' || printf 'gone: taken back')); the Deployment's revision $revision_before before, $revision_after after (one roll is one step); the Application now $sync_now"
no_pod_terminating_in_finapp 600 || fail "a replica was still terminating ten minutes after the rollout restart"
pass "rollout restart observed and recorded (see delivery-record.txt): the fleet rolled and came back ready"

# ---------------------------------------------------------------------------------------------------
# Freshness: a change of the ExternalSecret's annotations alone, and what the wave knew of the store when it passed.
# ---------------------------------------------------------------------------------------------------
step delivery-freshness
before=$(kube -n finapp get externalsecret finapp-app-secrets -o jsonpath='{.metadata.generation}|{.status.syncedResourceVersion}|{.status.refreshTime}')
sed -i 's/^    finapp\/rotation-generation: "0"$/    finapp\/rotation-generation: "1"/' "$CLONE/deploy/components/secret-delivery/externalsecret-app.yaml"
[ -n "$(git_clone status --porcelain)" ] || cannot "the ExternalSecret's generation annotation could not be moved"
commit_clone "The rehearsal: the application's ExternalSecret's generation annotation moved, nothing else"
FRESH_COMMIT=$(push_clone "the annotation's move")
synced finapp "$FRESH_COMMIT" 900 freshness || fail "the annotation's change did not sync: see latency.tsv"
after=$(kube -n finapp get externalsecret finapp-app-secrets -o jsonpath='{.metadata.generation}|{.status.syncedResourceVersion}|{.status.refreshTime}')
job_created=$(kube -n finapp get job finapp-migrator -o jsonpath='{.metadata.creationTimestamp}')
record "freshness: the ExternalSecret before the change $before (generation|syncedResourceVersion|refreshTime), after the sync $after; this sync's migrator hook created $job_created"
[ "${after#*|}" != "${before#*|}" ] || fail "freshness: the operator did not read the store again after the annotation's change"
refreshed=${after##*|}
if [ "$(date -u -d "$refreshed" +%s)" -le "$(date -u -d "$job_created" +%s)" ]; then
    record "freshness: the store was read again at $refreshed, before the hook of that sync started ($job_created)"
else
    record "freshness: the store was read again at $refreshed, AFTER the hook of that sync started ($job_created): the wave passed on the Ready of the read before, as section 11.36 says it can"
fi
pass "freshness: status.syncedResourceVersion and refreshTime moved after a change of the annotations alone (see delivery-record.txt for the order against the hook)"

# ---------------------------------------------------------------------------------------------------
# A rollback by digest, the migrator kept.
# ---------------------------------------------------------------------------------------------------
step delivery-rollback
promote_status=0
promote "$OLDER_DIGEST" "$FAILING_DIGEST" "$OLDER_REVISION" > "$ART/promote-rollback-other-migrator.txt" 2>&1 || promote_status=$?
[ "$promote_status" -eq 1 ] && grep -q "a rollback moves the application alone" "$ART/promote-rollback-other-migrator.txt" \
    || fail "promote.sh did not refuse a rollback that changes the migrator (exit $promote_status): see promote-rollback-other-migrator.txt"
promote "$OLDER_DIGEST" "$MIGRATOR_DIGEST" "$OLDER_REVISION" > "$ART/promote-rollback.txt" 2>&1 || fail "promote.sh refused the rollback that keeps the migrator: see promote-rollback.txt"
grep -q "digest: $OLDER_DIGEST" "$CLONE/deploy/overlays/staging/kustomization.yaml" && grep -q "digest: $MIGRATOR_DIGEST" "$CLONE/deploy/overlays/staging/kustomization.yaml" \
    || fail "the rollback did not write the older application beside the kept migrator"
commit_clone "Roll staging back to the older application, built from $OLDER_REVISION; the migrator kept"
ROLLBACK_COMMIT=$(push_clone "the rollback")
synced finapp "$ROLLBACK_COMMIT" 1200 rollback || fail "the rollback did not sync: see latency.tsv"
kube -n finapp rollout status deployment/finapp-app --timeout=900s >> "$ART/apply.log" 2>&1 || fail "the rollback's rollout did not complete"
running=$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.template.spec.containers[0].image}')
[ "$running" = "$REG/finapp-staging-app@$OLDER_DIGEST" ] || fail "after the rollback the Deployment runs $running"
pass "rollback: promote.sh refused a changed migrator, wrote the older application beside the kept one, and Argo CD synced it by digest"
delivery_money delivery-rolled-back
post_deploy_smoke rolled-back
census delivery-rolled-back

# ---------------------------------------------------------------------------------------------------
# A migrator whose migration fails: the sync stops at its hook, nothing is retried, self-heal waits; then section 11.36's way back.
# ---------------------------------------------------------------------------------------------------
step delivery-failed-hook
generation_before=$(kube -n finapp get deployment finapp-app -o jsonpath='{.metadata.generation}')
promote "$APP_DIGEST" "$FAILING_DIGEST" "$REVISION" > "$ART/promote-failing.txt" 2>&1 || fail "promote.sh refused the failing migrator, which is signed: see promote-failing.txt"
commit_clone "Promote N with a migrator whose added migration the database refuses"
FAILING_COMMIT=$(push_clone "the failing migrator")
if synced finapp "$FAILING_COMMIT" 1200 failed-hook; then
    fail "the sync with a failing migrator succeeded"
fi
[ "$(argo application finapp '{.status.operationState.phase}')" = Failed ] || fail "the sync with a failing migrator did not end Failed"
failed_started=$(argo application finapp '{.status.operationState.startedAt}')
exit_codes=$(kube -n finapp get pods -l job-name=finapp-migrator -o jsonpath='{range .items[*]}{.status.containerStatuses[0].state.terminated.exitCode}{"\n"}{end}')
[ "$(printf '%s\n' "$exit_codes" | grep -c .)" = 1 ] && [ "$exit_codes" = 10 ] || fail "the failing hook's pods exited '$(printf '%s' "$exit_codes" | tr '\n' ' ')', not one exit 10"
kube -n finapp logs job/finapp-migrator > "$ART/migrator-failed.log" 2>&1 || true
[ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.metadata.generation}')" = "$generation_before" ] || fail "the Deployment changed under a failed hook"
[ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.template.spec.containers[0].image}')" = "$REG/finapp-staging-app@$OLDER_DIGEST" ] \
    || fail "the Deployment no longer runs the release before the failed hook"
[ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')" -ge 3 ] || fail "the old replicas are not serving after the failed hook"
history=$(psql_query -c "SELECT count(*) FROM ledger.flyway_schema_history WHERE script LIKE '%delivery_rehearsal_refused%'" | tr -d '[:space:]')
[ "$history" = 0 ] || fail "the failed migration left $history history rows"
pass "a failed migrator hook (exit 10, once) failed the sync: the Deployment's generation unchanged, the old replicas serving, no history row"
# While failed: a hand change stays, nothing is retried.
kube -n finapp patch deployment finapp-app --type=merge -p '{"spec":{"revisionHistoryLimit":4}}' >> "$ART/apply.log" 2>&1 || cannot "the hand change could not be made"
sleep 300
[ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.revisionHistoryLimit}')" = 4 ] || fail "self-heal acted while the last sync was failed"
[ "$(argo application finapp '{.status.operationState.startedAt}')" = "$failed_started" ] && [ "$(argo application finapp '{.status.operationState.phase}')" = Failed ] \
    || fail "Argo CD started another sync of the failed revision"
[ "$(kube -n finapp get pods -l job-name=finapp-migrator -o name | grep -c .)" = 1 ] || fail "the failed migrator ran again"
pass "for five minutes after the failure: no sync started again, the hook not run again, and a hand change left in place (self-heal suspended)"
# Section 11.36's way back: read the cause, never sync the same revision again, fix forward in a new promotion.
grep -q 'failure=DEFINITE\|result=FAILED\|exit 10\|check constraint' "$ART/migrator-failed.log" || record "the failed hook's log names its cause as: $(tail -3 "$ART/migrator-failed.log" | tr '\n' ' ' | cut -c1-300)"
promote "$APP_DIGEST" "$MIGRATOR_DIGEST" "$REVISION" > "$ART/promote-recovery.txt" 2>&1 || fail "promote.sh refused the forward fix: see promote-recovery.txt"
commit_clone "Fix forward: N with the migrator that carries no failing migration"
RECOVERY_COMMIT=$(push_clone "the forward fix")
synced finapp "$RECOVERY_COMMIT" 1500 recovery || fail "the forward fix did not sync: see latency.tsv"
kube -n finapp rollout status deployment/finapp-app --timeout=900s >> "$ART/apply.log" 2>&1 || fail "the forward fix's rollout did not complete"
[ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.template.spec.containers[0].image}')" = "$REG/finapp-staging-app@$APP_DIGEST" ] \
    || fail "after the forward fix the Deployment does not run N"
[ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.revisionHistoryLimit}')" = 10 ] || fail "the successful sync did not put back the hand change"
pass "the way back (section 11.36): the cause read from the hook's log, a forward fix promoted and synced, the hand change put back with it"
delivery_money delivery-recovered
post_deploy_smoke recovered
census delivery-recovered

# ---------------------------------------------------------------------------------------------------
# The record.
# ---------------------------------------------------------------------------------------------------
step delivery-record
kube -n argocd get applications -o wide > "$ART/applications-final.txt" 2>&1 || true
git_clone log --oneline "$REVISION..HEAD" > "$ART/clone-commits.txt" 2>&1 || true
record "commit-to-sync, seconds: $(awk -F'\t' '{printf "%s %s; ", $1, $4}' "$ART/latency.tsv")"
census delivery-final
