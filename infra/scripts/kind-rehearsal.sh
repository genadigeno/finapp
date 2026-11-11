#!/bin/sh
# The kind rehearsal (P15-TSK-012, ADR-0120, PHASE_15_PLAN.md section 6): the platform deployed on a real Kubernetes API as three
# replicas, migrated by its Job, smoked through its real edge, rolled from N - 1 to N under a workload, a pod killed in the middle,
# rolled back by digest and rolled forward again, with the financial census read from the database after every step.
#
# WHAT A GREEN RUN SAYS. Each step asserts counts, not the absence of an error: zero failed requests across the rollout, one effect
# per idempotency key, the outbox drained, the peak pg_stat_activity within instances x pool, and an operator admitted at that peak.
# The artefacts under REHEARSAL_ARTEFACTS (build/rehearsal by default) are the evidence: every census, the peak, the timings, the logs.
#
# WHAT IT NEVER DOES. It holds no credential beyond this run: every key, password, SCRAM user, CA and keystore is generated into a
# temporary directory outside the repository, handed to the cluster as Secrets and removed at exit. It pushes nothing and talks to
# no cloud. It runs one throwaway cluster of its own name and deletes it at exit.
#
# N - 1 AND N. Until a release is cut (docs/migrations/released-schema-floor.yaml says `none`) there is no release to roll from, so N is
# this tree and N - 1 is the newest first-parent commit before the last migration landed: older code on a genuinely older schema.
# REHEARSAL_NMINUS1_REF names another. N - 1 is further patched to not have one handler (see nminus1_tree), so that "a handler added in
# release N" is a real case: this is a rehearsal's stand-in, and the first real release moves both definitions.
#
# THE MODES (REHEARSAL_PROBE), each a deliberate break that must turn the run red: `max-unavailable`, `min-ready`, `skip-replay`,
# `no-default-deny`. A probe stops as soon as its assertion has gone red or the run has completed green, and the script's exit status
# says which. MUTATION_TESTING.md records what each was seen to do.
#
# Usage:   infra/scripts/kind-rehearsal.sh
# Needs:   Docker with buildx, kind (KIND, default `kind`), git, openssl, a JDK for Gradle, sha256sum, tar, base64.
# Exit:    0 every assertion held; 1 an assertion failed; 2 a step could not run. A step that cannot run never looks like a pass.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
DIR="$ROOT/infra/scripts/kind-rehearsal"
# shellcheck source=infra/scanner-pins.sh
. "$ROOT/infra/scanner-pins.sh"

CLUSTER=${REHEARSAL_CLUSTER:-finapp-rehearsal}
NODE="$CLUSTER-control-plane"
KIND_BIN=${KIND:-kind}
ART=${REHEARSAL_ARTEFACTS:-$ROOT/build/rehearsal}
PROBE=${REHEARSAL_PROBE:-}
STOP_AFTER=${REHEARSAL_STOP_AFTER:-}
KEEP=${REHEARSAL_KEEP:-}
POLICY_ENGINE=${REHEARSAL_POLICY_ENGINE:-kindnet}
# A machine whose Docker cannot verify a registry from inside a container (a TLS-intercepting antivirus) names a bundle it trusts: Helm's pull and the node's
# own pull of cert-manager both use it. CI never does.
CA_BUNDLE=${REHEARSAL_CA_BUNDLE:-}
ROLLOUT_TIMEOUT=${REHEARSAL_ROLLOUT_TIMEOUT:-1500}

KIND_NODE_IMAGE="$KIND_NODE_REPOSITORY:$KIND_NODE_VERSION@$KIND_NODE_DIGEST"
KUSTOMIZE_IMAGE="$KUSTOMIZE_REPOSITORY:$KUSTOMIZE_VERSION@$KUSTOMIZE_DIGEST"
JDK_IMAGE=$(sed -n 's/^FROM \(eclipse-temurin:[^ ]*\) AS builder$/\1/p' "$ROOT/Dockerfile")
[ -n "$JDK_IMAGE" ] || { echo "?? no JDK image found in the Dockerfile" >&2; exit 2; }
CURL_IMAGE="$CURL_REPOSITORY:$CURL_VERSION@$CURL_DIGEST"

# Git Bash on Windows otherwise rewrites /work, /etc/... into host paths. Unset and harmless on Linux, where CI runs.
export MSYS_NO_PATHCONV=1

dock() {
    docker "$@"
}

# A path as Docker and kind on this host take it.
dpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

mkdir -p "$ART"
: > "$ART/rehearsal.log"
: > "$ART/not-exercised.txt"
: > "$ART/timings.tsv"
: > "$ART/assertions.tsv"

log() {
    printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$ART/rehearsal.log"
}

# A step that could not run is not a green step and not a red assertion, but it still tears down what it built: generated keys live in $WORK.
cannot() {
    log "?? $*"
    FAILED=2
    finish
}

FAILED=0
fail() {
    log "!! $*"
    printf 'FAIL\t%s\n' "$*" >> "$ART/assertions.tsv"
    FAILED=1
    finish
}

pass() {
    log "ok $*"
    printf 'PASS\t%s\n' "$*" >> "$ART/assertions.tsv"
}

STEP_NAME=""
STEP_START=0
step() {
    now=$(date +%s)
    if [ -n "$STEP_NAME" ]; then
        printf '%s\t%s\n' "$STEP_NAME" "$((now - STEP_START))" >> "$ART/timings.tsv"
    fi
    if [ "$STEP_NAME" = "$STOP_AFTER" ] && [ -n "$STOP_AFTER" ]; then
        log "stopping after $STEP_NAME, as asked"
        finish
    fi
    STEP_NAME=$1
    STEP_START=$now
    log "== $1"
}

WORK=$(mktemp -d)
CLIENT="$WORK/client"
mkdir -p "$CLIENT" "$WORK/bin" "$WORK/render"
SAMPLER=""
REPORTER=""
CONTAINERS=""
FINISHED=""

finish() {
    [ -z "$FINISHED" ] || return 0
    FINISHED=1
    set +e
    [ -z "$SAMPLER" ] || kill "$SAMPLER" 2>/dev/null
    [ -z "$REPORTER" ] || kill "$REPORTER" 2>/dev/null
    for container in $CONTAINERS; do
        dock rm -f "$container" >/dev/null 2>&1
    done
    if [ -n "${CLUSTER_UP:-}" ]; then
        kube -n finapp get pods -o wide > "$ART/pods-final.txt" 2>&1
        kube -n finapp get events --sort-by=.lastTimestamp > "$ART/events-final.txt" 2>&1
    fi
    if [ -z "$KEEP" ] && [ -n "${CLUSTER_UP:-}" ]; then
        "$KIND_BIN" delete cluster --name "$CLUSTER" >/dev/null 2>&1
    fi
    rm -rf "$WORK"
    if [ "$FAILED" -eq 2 ]; then
        log "RESULT: COULD NOT RUN"
        exit 2
    fi
    if [ "$FAILED" -ne 0 ]; then
        log "RESULT: RED"
        exit 1
    fi
    log "RESULT: GREEN"
    exit 0
}
trap 'FAILED=2; finish' INT TERM
# An unguarded failure under set -e leaves through here: the keys in $WORK and the cluster go with it, and the run says it did not complete.
trap 'status=$?; if [ -z "$FINISHED" ]; then [ "$status" = 0 ] || FAILED=2; finish; fi' EXIT

# kubectl inside the node: the client that matches the server, and no route through the host. On a developer's machine an antivirus's
# TLS interception breaks a host-side client that verifies the API server against the cluster's CA.
kube() {
    dock exec -i "$NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf "$@"
}

for tool in docker git openssl sha256sum tar base64; do
    command -v "$tool" >/dev/null 2>&1 || cannot "$tool is not on the PATH"
done
command -v "$KIND_BIN" >/dev/null 2>&1 || cannot "kind ($KIND_BIN) is not on the PATH"

# ---------------------------------------------------------------------------------------------------
# The images.
# ---------------------------------------------------------------------------------------------------

# The name containerd stores: a bare repository is under docker.io/library, an org/name under docker.io.
normalized() {
    case $1 in
        */*) ;;
        *) printf 'docker.io/library/%s' "$1"; return ;;
    esac
    case ${1%%/*} in
        *.* | *:*) printf '%s' "$1" ;;
        *) printf 'docker.io/%s' "$1" ;;
    esac
}

# load_tagged <repository> <tag> [digest]: pulled on the host and imported into the node, so that a digest-pinned manifest finds its
# image by the digest it names without the node reaching a registry.
load_tagged() {
    source_ref="$1:$2"
    if ! dock image inspect "$source_ref" >/dev/null 2>&1; then
        if [ -n "${3:-}" ]; then
            dock pull --platform linux/amd64 "$1@$3" >/dev/null || cannot "could not pull $1@$3"
            dock tag "$1@$3" "$source_ref"
        else
            dock pull --platform linux/amd64 "$source_ref" >/dev/null || cannot "could not pull $source_ref"
        fi
    fi
    dock save --platform linux/amd64 "$source_ref" -o "$(dpath "$WORK/image.tar")" 2>/dev/null || dock save "$source_ref" -o "$(dpath "$WORK/image.tar")" \
        || cannot "could not save $source_ref"
    dock exec -i "$NODE" ctr -n k8s.io images import --platform linux/amd64 - < "$WORK/image.tar" >> "$ART/images.log" 2>&1 \
        || cannot "could not import $source_ref into the node"
    rm -f "$WORK/image.tar"
    if [ -n "${3:-}" ]; then
        dock exec "$NODE" ctr -n k8s.io images tag --force "$(normalized "$source_ref")" "$(normalized "$1")@$3" >> "$ART/images.log" 2>&1 \
            || cannot "could not tag $source_ref by digest in the node"
    fi
}

# The digest containerd holds for an image the rehearsal built: the same sha256 a registry would answer. What the Deployment is pinned to.
imported_digest() {
    dock exec "$NODE" ctr -n k8s.io images ls "name==$(normalized "$1")" 2>/dev/null | awk 'NR==2 {print $3}'
}

# load_built <local tag>: the digest reference the manifests use.
load_built() {
    load_tagged "${1%:*}" "${1##*:}"
    digest=$(imported_digest "$1")
    case $digest in sha256:*) ;; *) cannot "no digest for $1 in the node" ;; esac
    ref="${1%:*}@$digest"
    dock exec "$NODE" ctr -n k8s.io images tag --force "$(normalized "$1")" "$(normalized "${1%:*}")@$digest" >> "$ART/images.log" 2>&1 \
        || cannot "could not tag $1 by digest in the node"
    printf '%s' "$ref"
}

build_tree() {
    # build_tree <tree> <tag suffix>: finapp-app and finapp-migrator of one tree. Built once: reproducibility is image-build.sh's claim, not this.
    tree=$1
    suffix=$2
    (
        cd "$tree"
        if [ -x ./gradlew.bat ] && command -v cmd.exe >/dev/null 2>&1; then
            ./gradlew.bat --console=plain -q :app:bootJar :migrator:imageTar
        else
            ./gradlew --console=plain -q :app:bootJar :migrator:imageTar
        fi
    ) >> "$ART/build-$suffix.log" 2>&1 || cannot "the $suffix build failed: see build-$suffix.log"
    revision=$(git -C "$(dpath "$tree")" rev-parse HEAD)
    epoch=$(git -C "$(dpath "$tree")" log -1 --format=%ct)
    jar=$(cd "$tree" && ls app/build/libs/app-*.jar | grep -v plain | head -1)
    version=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$tree/build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts")
    (
        cd "$tree"
        dock buildx build --provenance=false --sbom=false \
            --build-arg SOURCE_DATE_EPOCH="$epoch" --build-arg JAR="$jar" --build-arg REVISION="$revision" \
            --build-arg SOURCE=rehearsal --build-arg VERSION="$version" --tag "finapp-app:rehearsal-$suffix" --load . \
            && dock buildx build --provenance=false --sbom=false --file Dockerfile --target migrator \
                --build-arg SOURCE_DATE_EPOCH="$epoch" --build-arg REVISION="$revision" \
                --build-arg SOURCE=rehearsal --build-arg VERSION="$version" --tag "finapp-migrator:rehearsal-$suffix" --load migrator
    ) >> "$ART/build-$suffix.log" 2>&1 || cannot "the $suffix image build failed: see build-$suffix.log"
}

# The handler release N has and N - 1 does not. The one change made to the older tree: the capture hint's bean, so that a payments.PaymentCaptured
# record reaches an N - 1 replica and is committed unseen (UNHANDLED) while the topic stays subscribed through the module's other handlers.
nminus1_patch() {
    beans="$1/app/src/main/java/com/finapp/app/risk/RiskBeans.java"
    cp "$beans" "$WORK/RiskBeans.before"
    sed -i -e '/^    @Bean$/{N;/riskHintPaymentCaptured/{N;N;d}}' "$beans"
    if cmp -s "$WORK/RiskBeans.before" "$beans"; then
        cannot "the N - 1 patch no longer applies: RiskBeans.riskHintPaymentCaptured was not found"
    fi
}

NMINUS1_TREE=""
build_nminus1() {
    ref=${REHEARSAL_NMINUS1_REF:-}
    if [ -z "$ref" ]; then
        last=$(git -C "$(dpath "$ROOT")" log -1 --first-parent --format=%H -- '*/src/main/resources/db/migration/*')
        [ -n "$last" ] || cannot "no commit touched a migration"
        ref=$(git -C "$(dpath "$ROOT")" rev-parse "$last^1")
    fi
    log "N - 1 is $ref, with the capture hint removed"
    printf '%s\n' "$ref" > "$ART/nminus1.ref"
    NMINUS1_TREE=$(dpath "$WORK/nminus1")
    git -C "$(dpath "$ROOT")" worktree add --detach --force "$(dpath "$WORK/nminus1")" "$ref" >> "$ART/build-nminus1.log" 2>&1 \
        || cannot "could not check out $ref"
    nminus1_patch "$WORK/nminus1"
    build_tree "$WORK/nminus1" nminus1
    git -C "$(dpath "$ROOT")" worktree remove --force "$(dpath "$WORK/nminus1")" >> "$ART/build-nminus1.log" 2>&1 || true
}

# ---------------------------------------------------------------------------------------------------
# The local CA and everything issued from it.
# ---------------------------------------------------------------------------------------------------
CA_DIR="$WORK/ca"

make_ca() {
    mkdir -p "$CA_DIR"
    (cd "$CA_DIR" && openssl req -x509 -new -newkey rsa:2048 -nodes -days 2 -subj "/CN=finapp-rehearsal-local-ca" \
        -keyout ca.key -out ca.crt) >/dev/null 2>&1 || cannot "the local CA could not be generated"
}

# issue <name> <SAN list, comma-separated DNS:...>: <name>.key and <name>.crt, signed by the local CA, one day.
issue() {
    (cd "$CA_DIR" && openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$1" -keyout "$1.key" -out "$1.csr") >/dev/null 2>&1 \
        || cannot "a key for $1 could not be generated"
    printf 'subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$2" > "$CA_DIR/$1.ext"
    (cd "$CA_DIR" && openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days 1 -extfile "$1.ext" \
        -out "$1.crt") >/dev/null 2>&1 || cannot "a certificate for $1 could not be issued"
}

# secret_of <namespace> <name> <key=file>...: applied through stdin, base64 from the host, never on a command line.
secret_of() {
    namespace=$1
    name=$2
    shift 2
    {
        printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\ntype: Opaque\ndata:\n' "$name" "$namespace"
        for entry in "$@"; do
            printf '  %s: %s\n' "${entry%%=*}" "$(base64 -w0 < "${entry#*=}")"
        done
    } | kube apply -f - > /dev/null || cannot "the Secret $name could not be applied"
}

jdk() {
    dock run --rm -v "$(dpath "$WORK"):/work" -w /work "$JDK_IMAGE" "$@"
}

# ---------------------------------------------------------------------------------------------------
# Rendering and applying.
# ---------------------------------------------------------------------------------------------------

render_overlay() {
    out="$WORK/render/$1"
    rm -rf "$out"
    mkdir -p "$out"
    dock run --rm -v "$(dpath "$ROOT/deploy"):/deploy:ro" "$KUSTOMIZE_IMAGE" build "/deploy/$2" > "$out/all.yaml" \
        || cannot "kustomize could not render $2"
    # One file per document, named <kind>.<name>, so the rehearsal can apply them in the order a release needs.
    awk -v out="$out" 'BEGIN{n=0; f=out "/doc-0"} /^---$/{close(f); n++; f=out "/doc-" n; next} {print > f}' "$out/all.yaml"
    for doc in "$out"/doc-*; do
        kind=$(sed -n 's/^kind: \(.*\)$/\1/p' "$doc" | head -1)
        name=$(awk '/^metadata:/{m=1; next} m && /^  name:/{print $2; exit}' "$doc")
        mv "$doc" "$out/$kind.$name.yaml"
    done
}

apply_files() {
    for file in "$@"; do
        kube apply -f - < "$file" >> "$ART/apply.log" 2>&1 || cannot "could not apply $file"
    done
}

wait_job() {
    # wait_job <namespace> <name> <seconds> <artefact name>: complete, or the step fails with the Job's log kept. A Job that has failed fails the step at once.
    deadline=$(( $(date +%s) + $3 ))
    state=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        state=$(kube -n "$1" get "job/$2" -o jsonpath='{.status.conditions[?(@.status=="True")].type}' 2>/dev/null || true)
        case $state in
            *Complete*) break ;;
            *Failed*) break ;;
        esac
        sleep 2
    done
    kube -n "$1" logs -l "job-name=$2" --all-containers --prefix --tail=-1 > "$ART/$4.log" 2>&1 || true
    case $state in
        *Complete*) return 0 ;;
    esac
    kube -n "$1" describe "job/$2" >> "$ART/$4.log" 2>&1 || true
    fail "the Job $2 did not complete: see $4.log"
}

# ---------------------------------------------------------------------------------------------------
# The sequence.
# ---------------------------------------------------------------------------------------------------
step build-images
if [ -z "${REHEARSAL_SKIP_BUILD:-}" ]; then
    build_tree "$ROOT" n
    [ -n "${REHEARSAL_REUSE_NMINUS1:-}" ] || build_nminus1
elif [ -n "${REHEARSAL_NMINUS1_REF:-}" ]; then
    # A reused build still has to say which tree N - 1 was: the hold step reads it, and without it reports itself not exercised.
    printf '%s\n' "$REHEARSAL_NMINUS1_REF" > "$ART/nminus1.ref"
fi

step cluster
REUSING=""
if [ -n "${REHEARSAL_REUSE_CLUSTER:-}" ] && "$KIND_BIN" get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    REUSING=1
fi
if [ -n "$REUSING" ]; then
    # A development aid, for a run that is being debugged: the images stay loaded and only what the run deploys is thrown away.
    CLUSTER_UP=1
    log "reusing the cluster $CLUSTER: its namespaces are removed first"
    kube delete namespace finapp finapp-edge finapp-observability finapp-rehearsal cert-manager --ignore-not-found --wait=true --timeout=300s >> "$ART/cluster.log" 2>&1 \
        || cannot "the cluster's namespaces could not be removed"
else
dock pull --quiet "$KIND_NODE_IMAGE" >/dev/null || cannot "could not pull the node image"
"$KIND_BIN" delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
"$KIND_BIN" create cluster --name "$CLUSTER" --image "$KIND_NODE_IMAGE" --config "$(dpath "$DIR/kind-cluster.yaml")" \
    --kubeconfig "$(dpath "$WORK/kubeconfig")" --wait 180s >> "$ART/cluster.log" 2>&1 || cannot "the cluster did not come up: see cluster.log"
CLUSTER_UP=1
fi
SERVER_VERSION=$(kube version -o json | sed -n 's/.*"gitVersion": *"\(v[^"]*\)".*/\1/p' | tail -1)
log "cluster up: Kubernetes $SERVER_VERSION, node image $KIND_NODE_VERSION, kind $("$KIND_BIN" version)"
printf '%s\n' "$SERVER_VERSION" > "$ART/server-version.txt"

# kindnetd enforces NetworkPolicy from kind v0.24.0, through nft's queue statement. A kernel without it (Docker Desktop's WSL2 kernel has no
# CONFIG_NFT_QUEUE) leaves kindnetd's policy engine failing and the policies unenforced: the machine says so, and names the engine it brings instead.
if [ "$POLICY_ENGINE" = kube-router ]; then
    log "the policy engine here is kube-router, because this machine's kernel has no nft queue; CI uses kindnetd"
    printf 'kube-router\n' > "$ART/policy-engine.txt"
else
    log "the policy engine here is kindnetd"
    printf 'kindnetd\n' > "$ART/policy-engine.txt"
fi

ref_of_built() {
    digest=$(imported_digest "$1")
    case $digest in sha256:*) printf '%s@%s' "${1%:*}" "$digest" ;; *) cannot "no digest for $1 in the node" ;; esac
}

if [ -z "$REUSING" ] && [ -n "$CA_BUNDLE" ]; then
    dock cp "$(dpath "$CA_BUNDLE")" "$NODE:/etc/ssl/certs/ca-certificates.crt" || cannot "the CA bundle could not be copied into the node"
    dock exec "$NODE" systemctl restart containerd || cannot "the node's containerd could not be restarted"
fi

step images-loaded
if [ -n "$REUSING" ]; then
    IMAGE_APP_N=$(ref_of_built finapp-app:rehearsal-n)
    IMAGE_MIGRATOR_N=$(ref_of_built finapp-migrator:rehearsal-n)
    IMAGE_APP_NM1=$(ref_of_built finapp-app:rehearsal-nminus1)
    IMAGE_MIGRATOR_NM1=$(ref_of_built finapp-migrator:rehearsal-nminus1)
else
if [ "$POLICY_ENGINE" = kube-router ]; then
    load_tagged "$KUBE_ROUTER_REPOSITORY" "$KUBE_ROUTER_VERSION" "$KUBE_ROUTER_DIGEST"
    sed "s#KUBE_ROUTER_IMAGE#$KUBE_ROUTER_REPOSITORY@$KUBE_ROUTER_DIGEST#" "$DIR/kube-router.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "kube-router could not be applied"
fi
load_tagged "${JDK_IMAGE%%:*}" "$(printf '%s' "$JDK_IMAGE" | sed 's/^[^:]*:\([^@]*\)@.*/\1/')" "${JDK_IMAGE##*@}"
load_tagged postgres 18.6 "$(sed -n 's/.*postgres:18.6@\(sha256:[0-9a-f]*\).*/\1/p' "$ROOT/deploy/overlays/kind/postgres.yaml")"
load_tagged apache/kafka 4.3.1 "$(sed -n 's/.*apache\/kafka:4.3.1@\(sha256:[0-9a-f]*\).*/\1/p' "$ROOT/deploy/overlays/kind/kafka.yaml")"
load_tagged "$TRAEFIK_REPOSITORY" "$TRAEFIK_VERSION" "$TRAEFIK_DIGEST"
load_tagged "$CURL_REPOSITORY" "$CURL_VERSION" "$CURL_DIGEST"
IMAGE_APP_N=$(load_built finapp-app:rehearsal-n)
IMAGE_MIGRATOR_N=$(load_built finapp-migrator:rehearsal-n)
IMAGE_APP_NM1=$(load_built finapp-app:rehearsal-nminus1)
IMAGE_MIGRATOR_NM1=$(load_built finapp-migrator:rehearsal-nminus1)
fi
log "N-1 $IMAGE_APP_NM1 / $IMAGE_MIGRATOR_NM1"
log "N   $IMAGE_APP_N / $IMAGE_MIGRATOR_N"
{
    printf 'app.n=%s\nmigrator.n=%s\napp.nminus1=%s\nmigrator.nminus1=%s\n' "$IMAGE_APP_N" "$IMAGE_MIGRATOR_N" "$IMAGE_APP_NM1" "$IMAGE_MIGRATOR_NM1"
} > "$ART/images.txt"

step namespaces
render_overlay namespaces namespaces
apply_files "$WORK/render/namespaces/all.yaml"
kube create namespace finapp-rehearsal >> "$ART/apply.log" 2>&1 || cannot "could not create the rehearsal's namespace"

step tls-and-secrets
make_ca
PG_HOST=finapp-postgres.finapp.svc.cluster.local
KAFKA_HOST=finapp-kafka.finapp.svc.cluster.local
SIM_HOST=finapp-simulator.finapp-rehearsal.svc.cluster.local
issue postgres "DNS:$PG_HOST,DNS:finapp-postgres,DNS:finapp-postgres.finapp,DNS:finapp-postgres.finapp.svc"
issue kafka "DNS:$KAFKA_HOST,DNS:finapp-kafka,DNS:finapp-kafka.finapp,DNS:finapp-kafka.finapp.svc"
issue simulator "DNS:$SIM_HOST,DNS:finapp-simulator,DNS:finapp-simulator.finapp-rehearsal"
cat "$CA_DIR/kafka.key" "$CA_DIR/kafka.crt" "$CA_DIR/ca.crt" > "$CA_DIR/kafka-keystore.pem"
SIM_STORE_PHRASE=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
printf '%s' "$SIM_STORE_PHRASE" > "$CA_DIR/simulator.password"
(cd "$CA_DIR" && openssl pkcs12 -export -inkey simulator.key -in simulator.crt -certfile ca.crt \
    -passout "pass:$SIM_STORE_PHRASE" -out simulator.p12) >/dev/null 2>&1 || cannot "the simulator's keystore could not be made"
# Read without a password, so none is delivered: a trust store holds no secret.
TRUST_PHRASE=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
jdk keytool -importcert -noprompt -alias local-ca -file ca/ca.crt -keystore ca/truststore.jks -storetype JKS -storepass "$TRUST_PHRASE" \
    >> "$ART/tls.log" 2>&1 || cannot "the JVM trust store could not be made"
secret_of finapp finapp-kind-local-ca "ca.crt=$CA_DIR/ca.crt" "truststore.jks=$CA_DIR/truststore.jks"
secret_of finapp finapp-kind-postgres-tls "tls.crt=$CA_DIR/postgres.crt" "tls.key=$CA_DIR/postgres.key"
secret_of finapp finapp-kind-kafka-tls "keystore.pem=$CA_DIR/kafka-keystore.pem"
secret_of finapp-rehearsal finapp-simulator-tls "keystore.p12=$CA_DIR/simulator.p12" "store-phrase=$CA_DIR/simulator.password"

printf '#!/bin/sh\nMSYS_NO_PATHCONV=1 exec docker exec -i "%s" kubectl --kubeconfig=/etc/kubernetes/admin.conf "$@"\n' "$NODE" > "$WORK/bin/kubectl"
chmod +x "$WORK/bin/kubectl"
SIM_URL="https://$SIM_HOST:8443"
PATH="$WORK/bin:$PATH" \
    FINAPP_DB_URL="jdbc:postgresql://$PG_HOST:5432/finapp?sslmode=verify-full&sslrootcert=/etc/finapp-local-ca/ca.crt" \
    FINAPP_DB_APP_USER=finapp_app FINAPP_DB_REPORTING_USER=finapp_reporting FINAPP_DB_MIGRATOR_USER=finapp_migrator FINAPP_DB_MASTER_USER=finapp \
    FINAPP_KAFKA_BOOTSTRAPSERVERS="$KAFKA_HOST:9092" FINAPP_KAFKA_SASL_USERNAME=finapp_app \
    FINAPP_PAYMENTS_PROVIDER_URL="$SIM_URL" FINAPP_PAYMENTMETHODS_TOKENISATION_URL="$SIM_URL" FINAPP_MERCHANT_PAYOUT_PROVIDER_URL="$SIM_URL" \
    KIND_SECRETS_EXTRA_APP_NAMES="FINAPP_PAYMENTS_PROVIDER_URL FINAPP_PAYMENTMETHODS_TOKENISATION_URL FINAPP_MERCHANT_PAYOUT_PROVIDER_URL" \
    sh "$ROOT/infra/scripts/kind-secrets.sh" apply finapp >> "$ART/apply.log" 2>&1 || cannot "kind-secrets.sh apply failed: see apply.log"

step simulator
{
    printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: finapp-simulator-source\n  namespace: finapp-rehearsal\ndata:\n  Simulator.java: |\n'
    sed 's/^/    /' "$DIR/Simulator.java"
} | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the simulator's source"
sed "s#JDK_IMAGE#$JDK_IMAGE#" "$DIR/simulator.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the simulator"
kube -n finapp-rehearsal rollout status deployment/finapp-simulator --timeout=180s >> "$ART/apply.log" 2>&1 || fail "the simulator did not become ready"

step data-tier
render_overlay app overlays/kind
A="$WORK/render/app"
sed -i "s#finapp-app@sha256:0\{64\}#$IMAGE_APP_NM1#; s#finapp-migrator@sha256:0\{64\}#$IMAGE_MIGRATOR_NM1#" "$A/all.yaml" "$A/Deployment.finapp-app.yaml" "$A/Job.finapp-migrator.yaml"
# Only under kube-router, which programs a new pod asynchronously (see kube-router.yaml): a Job pod's first connection, a JVM's start after it is created, can be
# refused before its policy exists, and the migrator is built to fail fast on that and leave retries to its Job. A wait in front of it keeps that failure out of this
# run's record; the Job itself is the base's, unchanged, and kindnetd needs none of this.
if [ "$POLICY_ENGINE" = kube-router ]; then
    cat > "$WORK/job-wait.yaml" <<YAML
      initContainers:
      - name: wait-for-postgres
        image: $CURL_IMAGE
        imagePullPolicy: IfNotPresent
        command: ["/bin/sh", "-c", "until nc -z -w 3 finapp-postgres.finapp.svc.cluster.local 5432; do sleep 1; done"]
        resources:
          limits: {cpu: 100m, memory: 128Mi}
          requests: {cpu: 100m, memory: 128Mi}
        securityContext:
          allowPrivilegeEscalation: false
          capabilities: {drop: ["ALL"]}
          privileged: false
          readOnlyRootFilesystem: true
YAML
    awk -v wait="$WORK/job-wait.yaml" '/^      containers:$/ {while ((getline line < wait) > 0) print line} {print}' "$A/Job.finapp-migrator.yaml" > "$WORK/job.tmp"
    mv "$WORK/job.tmp" "$A/Job.finapp-migrator.yaml"
fi
# Everything but what a release orders: the Deployment, its autoscaler and the Job.
for file in "$A"/*.yaml; do
    case $(basename "$file") in
        all.yaml | Deployment.finapp-app.yaml | HorizontalPodAutoscaler.finapp-app.yaml | Job.finapp-migrator.yaml) ;;
        *) apply_files "$file" ;;
    esac
done
{
    printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: finapp-kind-kafka-config\n  namespace: finapp\n  labels:\n    finapp/test-only: "true"\ndata:\n  server.properties: |\n'
    {
        # The posture, from the catalogued file, overriding only replication: one node.
        sed -e '/^#/d' -e '/^$/d' \
            -e 's/^default.replication.factor=.*/default.replication.factor=1/' \
            -e 's/^min.insync.replicas=.*/min.insync.replicas=1/' \
            -e 's/^offsets.topic.replication.factor=.*/offsets.topic.replication.factor=1/' \
            "$ROOT/infra/kafka/broker.properties"
        cat <<PROPERTIES
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@localhost:9093
controller.listener.names=CONTROLLER
listeners=SASL_SSL://:9092,CONTROLLER://localhost:9093,LOCAL://localhost:9094
advertised.listeners=SASL_SSL://$KAFKA_HOST:9092,LOCAL://localhost:9094
inter.broker.listener.name=LOCAL
listener.security.protocol.map=CONTROLLER:PLAINTEXT,LOCAL:PLAINTEXT,SASL_SSL:SASL_SSL
listener.name.sasl_ssl.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required;
ssl.keystore.type=PEM
ssl.keystore.location=/etc/finapp-kind-kafka-tls/keystore.pem
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
super.users=User:ANONYMOUS
log.dirs=/var/lib/kafka/data
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
PROPERTIES
    } | sed 's/^/    /'
} | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the broker's configuration"
kube -n finapp rollout status statefulset/finapp-postgres --timeout=240s >> "$ART/apply.log" 2>&1 || fail "the test-only PostgreSQL did not become ready"
kube -n finapp rollout status statefulset/finapp-kafka --timeout=300s >> "$ART/apply.log" 2>&1 || fail "the test-only broker did not become ready"

psql_query() {
    # The superuser over the pod's own socket: the census and the seed read and write what no application role may.
    kube -n finapp exec -i finapp-postgres-0 -c postgres -- psql -q -X -A -t -U finapp -d finapp "$@"
}

kafka_admin() {
    tool=$1
    shift
    kube -n finapp exec -i finapp-kafka-0 -c kafka -- "/opt/kafka/bin/$tool" --bootstrap-server localhost:9094 "$@"
}

step topics-and-acls
catalogue="$ROOT/infra/kafka/topics.yaml"
partitions=$(sed -n '/^  domain-events:$/,/^topics:/s/^ *partitions: *\([0-9]*\) *$/\1/p' "$catalogue")
[ -n "$partitions" ] || cannot "no partition count read from topics.yaml"
topic_config=""
for entry in min.insync.replicas=1 \
    "$(sed -n 's/^ *cleanup.policy: *\([a-z]*\).*/cleanup.policy=\1/p' "$catalogue")" \
    "$(sed -n 's/^ *retention.ms: *"\([0-9]*\)".*/retention.ms=\1/p' "$catalogue")" \
    "$(sed -n 's/^ *segment.ms: *"\([0-9]*\)".*/segment.ms=\1/p' "$catalogue")" \
    "$(sed -n 's/^ *message.timestamp.type: *\([A-Za-z]*\).*/message.timestamp.type=\1/p' "$catalogue")"; do
    [ -n "${entry#*=}" ] || cannot "a topic setting could not be read from topics.yaml"
    topic_config="$topic_config --config $entry"
done
topics=$(sed -n 's/^  \(finapp\.[a-z]*\): domain-events$/\1/p' "$catalogue")
[ -n "$topics" ] || cannot "no topic read from topics.yaml"
application_user=finapp_app
for topic in $topics; do
    # shellcheck disable=SC2086
    kafka_admin kafka-topics.sh --create --if-not-exists --topic "$topic" --partitions "$partitions" --replication-factor 1 $topic_config >> "$ART/kafka.log" 2>&1 \
        || cannot "could not create topic $topic: see kafka.log"
done
set --
for topic in $topics; do
    set -- "$@" --topic "$topic"
done
kafka_admin kafka-acls.sh --add --allow-principal "User:$application_user" --operation Describe --operation Read --operation Write "$@" >> "$ART/kafka.log" 2>&1 \
    || cannot "could not grant the topics"
kafka_admin kafka-acls.sh --add --allow-principal "User:$application_user" --operation Describe --operation Read --group finapp. --resource-pattern-type prefixed >> "$ART/kafka.log" 2>&1 \
    || cannot "could not grant the consumer groups"
log "$(printf '%s\n' "$topics" | wc -l) topics created with $partitions partitions and the application's ACLs"

step bootstrap-roles
sed "s#MIGRATOR_IMAGE#$IMAGE_MIGRATOR_NM1#; s#PROBE_IMAGE#$CURL_IMAGE#" "$DIR/bootstrap-job.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the bootstrap Job"
wait_job finapp finapp-role-bootstrap 300 role-bootstrap
log "bootstrap said: $(grep -c . "$ART/role-bootstrap.log") lines; $(tr '\n' ';' < "$ART/role-bootstrap.log" | cut -c1-400)"

step migrator-nminus1
apply_files "$A/Job.finapp-migrator.yaml"
wait_job finapp finapp-migrator 900 migrator-nminus1
log "migrator (N-1): $(grep -h 'result=APPLIED' "$ART/migrator-nminus1.log" | tail -1 | cut -c1-200)"

step app-nminus1
apply_files "$A/Deployment.finapp-app.yaml" "$A/HorizontalPodAutoscaler.finapp-app.yaml"
ready_three() {
    deadline=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
        [ "${ready:-0}" -ge 3 ] && return 0
        sleep 5
    done
    return 1
}
if ! ready_three 900; then
    kube -n finapp get pods -o wide > "$ART/pods-at-failure.txt" 2>&1 || true
    fail "three replicas did not become ready: see pods-at-failure.txt"
fi
log "three replicas ready: $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name | tr '\n' ' ')"
kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o wide > "$ART/pods-nminus1.txt" 2>&1 || true

# ---------------------------------------------------------------------------------------------------
# The edge: cert-manager from its pinned chart, then the two controllers, with the local CA's certificates.
# ---------------------------------------------------------------------------------------------------
step cert-manager
HELM_IMAGE="$HELM_REPOSITORY:$HELM_VERSION@$HELM_DIGEST"
chart="$WORK/chart"
mkdir -p "$chart"
if [ -n "$CA_BUNDLE" ]; then
    set -- -v "$(dpath "$CA_BUNDLE"):/etc/ssl/certs/ca-certificates.crt:ro"
else
    set --
fi
dock run --rm "$@" -v "$(dpath "$chart"):/chart" "$HELM_IMAGE" pull "oci://$CERT_MANAGER_REPOSITORY" --version "$CERT_MANAGER_VERSION" --destination /chart > "$ART/helm-pull.log" 2>&1 \
    || cannot "the cert-manager chart could not be pulled: see helm-pull.log"
grep -Fq "Digest: $CERT_MANAGER_DIGEST" "$ART/helm-pull.log" || fail "the cert-manager chart is not the pinned digest: see helm-pull.log"
dock run --rm -v "$(dpath "$chart"):/chart" "$HELM_IMAGE" template cert-manager "/chart/cert-manager-$CERT_MANAGER_VERSION.tgz" \
    --namespace cert-manager --set crds.enabled=true --set startupapicheck.enabled=false > "$WORK/cert-manager.yaml" 2>> "$ART/helm-pull.log" \
    || cannot "the cert-manager chart could not be rendered: see helm-pull.log"
kube create namespace cert-manager >> "$ART/apply.log" 2>&1 || cannot "could not create cert-manager's namespace"
# The node pulls these three itself, as it would from any registry: `docker save` of a Docker-schema image from a containerd store here writes only its manifest.
for component in cert-manager-controller cert-manager-webhook cert-manager-cainjector; do
    dock exec "$NODE" crictl pull "quay.io/jetstack/$component:$CERT_MANAGER_VERSION" >> "$ART/images.log" 2>&1 \
        || cannot "the node could not pull $component: see images.log"
done
kube apply --server-side -f - < "$WORK/cert-manager.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply cert-manager: see apply.log"
for deployment in cert-manager cert-manager-webhook cert-manager-cainjector; do
    kube -n cert-manager rollout status "deployment/$deployment" --timeout=240s >> "$ART/apply.log" 2>&1 || fail "cert-manager's $deployment did not become ready"
done

step edge
render_overlay edge edge/overlays/kind
tries=0
until kube apply -f - < "$WORK/render/edge/all.yaml" >> "$ART/apply.log" 2>&1; do
    # The first Issuer can reach the webhook before cainjector has given it a CA.
    tries=$((tries + 1))
    [ "$tries" -lt 24 ] || cannot "the edge overlay could not be applied: see apply.log"
    sleep 5
done
kube -n finapp-edge wait --for=condition=Ready certificate --all --timeout=240s >> "$ART/apply.log" 2>&1 || fail "the edge's certificates were not issued"
for deployment in finapp-edge-public finapp-edge-operator; do
    kube -n finapp-edge rollout status "deployment/$deployment" --timeout=240s >> "$ART/apply.log" 2>&1 || fail "$deployment did not become ready"
done
kube -n finapp-edge get secret finapp-edge-local-ca -o jsonpath='{.data.ca\.crt}' | base64 -d > "$CLIENT/edge-ca.crt"
[ -s "$CLIENT/edge-ca.crt" ] || fail "the edge's local CA could not be read"
pass "the edge is up: two controllers per surface, reading their certificates at mode 0440 through fsGroup"

NODE_IP=$(dock inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$NODE")
[ -n "$NODE_IP" ] || cannot "the node has no address on the kind network"

client() {
    dock run --rm --network kind --add-host "public.finapp.test:$NODE_IP" --add-host "operator.finapp.test:$NODE_IP" \
        -v "$(dpath "$CLIENT"):/work" -v "$(dpath "$DIR"):/src:ro" "$JDK_IMAGE" java --source 21 /src/Rehearsal.java /work "$@"
}

# ---------------------------------------------------------------------------------------------------
# The census: what the database says, after the outbox has drained.
# ---------------------------------------------------------------------------------------------------
pending_events() {
    psql_query -c "SELECT count(*) FROM platform.outbox_event WHERE published_at IS NULL AND discarded_at IS NULL" | tr -d '[:space:]'
}

drained() {
    deadline=$(( $(date +%s) + ${1:-180} ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ "$(pending_events)" = 0 ] && return 0
        sleep 3
    done
    return 1
}

transfer_ok_count() {
    cat "$CLIENT"/workload-*.tsv 2>/dev/null | awk -F'\t' '$1=="transfer" && $NF=="ok" {print $3}' | sort -u | wc -l | tr -d ' '
}

flow_ok_count() {
    cat "$CLIENT"/workload-*.tsv 2>/dev/null | awk -F'\t' -v flow="$1" '$1==flow && $NF=="ok"' | wc -l | tr -d ' '
}

HANDLER_EVENT=payments.PaymentCaptured
HANDLER_CONSUMER=risk.signals

# The handler catalogue, as (consumer, event type) rows for the lost-event check. The handler N adds is left out here: its window has its own, stricter, assertions.
HANDLERS=$(sed -n "s/.*eventType: \"\([^\"]*\)\", consumerName: \"\([^\"]*\)\".*/('\2','\1')/p" "$ROOT/docs/events/handler-catalogue.yaml" | paste -sd, -)
[ -n "$HANDLERS" ] || cannot "the handler catalogue could not be read"

lost_events() {
    psql_query -v "handlers=$HANDLERS" -v "added_event=$HANDLER_EVENT" -v "added_consumer=$HANDLER_CONSUMER" -f - < "$DIR/lost-events.sql"
}

# A partition with no live owner, or with records behind its owner; a LAG of "-" with records on the partition is a group that never committed there, which is not the same as none behind.
lag_settled() {
    deadline=$(( $(date +%s) + 240 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        kafka_admin kafka-consumer-groups.sh --describe --all-groups > "$ART/consumer-groups-$1.txt" 2>/dev/null || true
        unowned=$(awk '$1 ~ /^finapp\./ && $2 ~ /^finapp\./ && ($7 == "-" || $6 + 0 > 0 || ($6 == "-" && $5 + 0 > 0))' "$ART/consumer-groups-$1.txt" | wc -l)
        partitions=$(awk '$1 ~ /^finapp\./ && $2 ~ /^finapp\./' "$ART/consumer-groups-$1.txt" | wc -l)
        [ "$partitions" -gt 0 ] && [ "$unowned" = 0 ] && return 0
        sleep 5
    done
    return 1
}

SMOKE_TRANSFERS=0
SMOKE_TOPUPS=0
SMOKE_CHECKOUTS=0
SMOKE_PAYOUTS=0

census() {
    # census <label>: read it, keep it, and any FAIL line fails the step.
    label=$1
    drained 240 || fail "the outbox did not drain before census $label: $(pending_events) rows pending"
    expected_transfers=$(( $(transfer_ok_count) + SMOKE_TRANSFERS ))
    expected_topups=$(( $(flow_ok_count top-up) + SMOKE_TOPUPS ))
    expected_checkouts=$(( $(flow_ok_count checkout) + SMOKE_CHECKOUTS ))
    expected_payouts=$(( $(flow_ok_count payout) + SMOKE_PAYOUTS ))
    # Every top-up credits one wallet: 5.00 in a smoke, 0.05 in the workload. Transfers move money between the same wallets and a checkout is paid by card.
    expected_wallets=$(( SMOKE_TOPUPS * 500 + $(flow_ok_count top-up) * 5 ))
    lag_settled "$label" || fail "census $label: a partition is unowned or lagging: see consumer-groups-$label.txt"
    # A consumer's inbox row can trail the outbox by a poll: the check waits for it, bounded, and what it last read is the verdict.
    lost_deadline=$(( $(date +%s) + 180 ))
    while :; do
        lost=$(lost_events 2>&1 | tail -1)
        case $lost in PASS*) break ;; esac
        [ "$(date +%s)" -lt "$lost_deadline" ] || break
        sleep 3
    done
    # The simulator rewrites its counts every two seconds.
    sleep 3
    sim=$(kube -n finapp-rehearsal exec deploy/finapp-simulator -- cat /tmp/counts.txt 2>/dev/null || true)
    psql_query -v "expected_transfers=$expected_transfers" -v "expected_topups=$expected_topups" -v "expected_checkouts=$expected_checkouts" -v "expected_payouts=$expected_payouts" -v "expected_wallets=$expected_wallets" -f - < "$DIR/census.sql" > "$ART/census-$label.txt" 2>&1 \
        || fail "census $label could not run: see census-$label.txt"
    # A census that printed nothing exited 0 too.
    census_lines=$(grep -c '^SELECT CASE' "$DIR/census.sql")
    [ "$(grep -c '^\(PASS\|FAIL\)|' "$ART/census-$label.txt")" = "$census_lines" ] || fail "census $label printed $(grep -c '^\(PASS\|FAIL\)|' "$ART/census-$label.txt") verdicts where census.sql has $census_lines: see census-$label.txt"
    provider_keys() { printf '%s\n' "$sim" | sed -n "s#^$1: [0-9]* requests, \([0-9]*\) keys.*#\1#p"; }
    captures=$(provider_keys /captures)
    payouts=$(provider_keys /payouts)
    {
        if [ "${captures:-0}" = "$((expected_topups + expected_checkouts))" ]; then verdict=PASS; else verdict=FAIL; fi
        printf '%s|one capture key at the provider per card payment|%s keys, client was told %s\n' "$verdict" "${captures:-0}" "$((expected_topups + expected_checkouts))"
        if [ "${payouts:-0}" = "$expected_payouts" ]; then verdict=PASS; else verdict=FAIL; fi
        printf '%s|one payout key at the provider per payout|%s keys, client was told %s\n' "$verdict" "${payouts:-0}" "$expected_payouts"
        printf '%s\n' "$lost"
        printf 'INFO|simulator\n%s\n' "$sim"
        printf 'INFO|connections|%s\n' "$(psql_query -c "SELECT count(*), count(*) FILTER (WHERE s.ssl) FROM pg_stat_activity a JOIN pg_stat_ssl s USING (pid) WHERE a.application_name = 'finapp'" | tr '|' ' ')"
    } >> "$ART/census-$label.txt"
    # GL = ledger, the application's own judgement of the month: readable only because a mapping version is active.
    case $lost in PASS*) ;; *) fail "census $label: $(printf '%s' "$lost" | cut -c1-400)" ;; esac
    gl=$(client gl-check 2>&1 | tail -1) || true
    printf '%s\n' "$gl" >> "$ART/census-$label.txt"
    case $gl in PASS*) ;; *) fail "census $label: GL = ledger: $(printf '%s' "$gl" | cut -c1-400)" ;; esac
    if grep -q '^FAIL' "$ART/census-$label.txt"; then
        fail "census $label: $(grep '^FAIL' "$ART/census-$label.txt" | tr '\n' ';' | cut -c1-600)"
    fi
    pass "census $label green"
}

# ---------------------------------------------------------------------------------------------------
# Smoke, then the hold, then N - 1 on N's schema.
# ---------------------------------------------------------------------------------------------------
step smoke-nminus1
client prepare >> "$ART/client.log" 2>&1 || fail "the smoke's participants could not be registered: see client.log"
psql_query -f - < "$CLIENT/seed-1.sql" >> "$ART/seed.log" 2>&1 || fail "the seed failed: see seed.log"
client onboard >> "$ART/client.log" 2>&1 || fail "the onboarding failed: see client.log"
psql_query -f - < "$CLIENT/seed-2.sql" >> "$ART/seed.log" 2>&1 || fail "the destination seed failed: see seed.log"
# The first GL mapping of a deployment, two persons (runbook 10.12): without one the General Ledger cannot be read.
client map-gl >> "$ART/client.log" 2>&1 || fail "the GL mapping could not be activated: see client.log"
smoke() {
    client smoke "$1" >> "$ART/client.log" 2>&1 || fail "the smoke ($1) failed: see client.log"
    SMOKE_TRANSFERS=$((SMOKE_TRANSFERS + 1))
    # Four: smoke funds every customer of the workload.
    SMOKE_TOPUPS=$((SMOKE_TOPUPS + 4))
    SMOKE_CHECKOUTS=$((SMOKE_CHECKOUTS + 1))
    SMOKE_PAYOUTS=$((SMOKE_PAYOUTS + 1))
}
smoke nminus1
grep -F 'smoke nminus1' "$ART/client.log" | tee -a "$ART/rehearsal.log" > /dev/null
census nminus1

# ---------------------------------------------------------------------------------------------------
# The two cluster acceptance suites that need only a ready fleet: NetworkPolicy and admission (P15-TSK-010), and the edge (P15-TSK-011).
# ---------------------------------------------------------------------------------------------------

# probe_pod <file> <namespace> <name> <labels, YAML flow mapping> <extra pod-spec YAML lines, indented by 2 spaces, may be empty>
probe_pod() {
    cat > "$1" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $3
  namespace: $2
  labels: $4
spec:
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 0
  securityContext:
    runAsNonRoot: true
    runAsUser: 100
    runAsGroup: 101
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: probe
      image: $CURL_IMAGE
      imagePullPolicy: IfNotPresent
      command: ["/bin/sh", "-c", "while true; do nc -l -p 8080 </dev/null >/dev/null 2>&1 || sleep 1; done"]
      resources:
        requests: {cpu: 100m, memory: 128Mi}
        limits: {cpu: 100m, memory: 128Mi}
      securityContext:
        allowPrivilegeEscalation: false
        privileged: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
$5
YAML
}

# reach <namespace> <pod> <container> <address> <port>: 0 when a TCP connection opened within the timeout, 1 when it did not. An answer from neither branch
# is the test not having run at all, which must not read as "refused": a denial is only a denial if the probe ran.
reach() {
    answer=$(kube -n "$1" exec "$2" -c "$3" -- /bin/sh -c "if nc -z -w 4 '$4' '$5'; then echo REACHED; else echo REFUSED; fi" 2>&1)
    case $answer in
        *REACHED*) return 0 ;;
        *REFUSED*) return 1 ;;
    esac
    cannot "the connection test did not run in $2/$3: $answer"
}

must_reach() {
    if reach "$2" "$3" "$4" "$5" "$6"; then
        pass "$1: $3 in $2 reaches $5:$6"
    else
        fail "$1: $3 in $2 did not reach $5:$6, and it must"
    fi
}

must_not_reach() {
    if reach "$2" "$3" "$4" "$5" "$6"; then
        fail "$1: $3 in $2 reached $5:$6, and it must not"
    else
        pass "$1: $3 in $2 is refused $5:$6"
    fi
}

# A TCP DNS question for a name, sent over a raw connection from inside the pod being tested: finapp-postgres.finapp.svc.cluster.local, type A, id 0x1234.
# A TCP answer is its two-byte length and then the response, which starts with the same id. Run from a script on stdin, so its quoting is the pod shell's alone.
dns_over_tcp() {
    kube -n "$1" exec -i "$2" -c "$3" -- /bin/sh -s "$4" <<'SCRIPT'
name=finapp-postgres.finapp.svc.cluster.local
b() { printf "\\$(printf %03o "$1")"; }
question() {
    for label in $(printf '%s' "$name" | tr . ' '); do
        b "${#label}"
        printf '%s' "$label"
    done
    b 0; b 0; b 1; b 0; b 1
}
header() { b 18; b 52; b 1; b 0; b 0; b 1; b 0; b 0; b 0; b 0; b 0; b 0; }
total=$(( 12 + $(question | wc -c) ))
{ b 0; b "$total"; header; question; sleep 2; } | nc -w 3 "$1" 53 | od -An -tx1 | tr -d ' \n'
SCRIPT
}

step network-policy
TARGET_POD=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{.items[0].metadata.name}')
APP_IP=$(kube -n finapp get pod "$TARGET_POD" -o jsonpath='{.status.podIP}')
[ -n "$APP_IP" ] || fail "no application pod address"
NEVER_READY='      readinessProbe:
        exec:
          command: ["/bin/false"]'
probe_pod "$WORK/pod-outsider.yaml" finapp-rehearsal np-outsider '{finapp/role: rehearsal-outsider}' ""
probe_pod "$WORK/pod-scraper.yaml" finapp-observability np-scraper '{finapp/role: metrics-scraper}' ""
# In finapp-edge, whose default-deny lets only the controllers out. Labelled as one, and never Ready, so it joins no Service.
probe_pod "$WORK/pod-edge.yaml" finapp-edge np-edge '{finapp/role: ingress-controller, app.kubernetes.io/name: finapp-edge-public}' "$NEVER_READY"
probe_pod "$WORK/pod-migrator.yaml" finapp np-migrator '{app.kubernetes.io/name: finapp-migrator}' ""
# No policy selects this one but the namespace's default-deny, which is what holds it.
probe_pod "$WORK/pod-plain.yaml" finapp np-plain '{finapp/role: rehearsal-plain}' ""
apply_files "$WORK/pod-outsider.yaml" "$WORK/pod-scraper.yaml" "$WORK/pod-edge.yaml" "$WORK/pod-migrator.yaml" "$WORK/pod-plain.yaml"
kube -n finapp-rehearsal wait --for=condition=Ready pod/np-outsider --timeout=120s >> "$ART/apply.log" 2>&1 || fail "np-outsider did not start"
kube -n finapp-observability wait --for=condition=Ready pod/np-scraper --timeout=120s >> "$ART/apply.log" 2>&1 || fail "np-scraper did not start"
kube -n finapp wait --for=condition=Ready pod/np-migrator --timeout=120s >> "$ART/apply.log" 2>&1 || fail "np-migrator did not start"
kube -n finapp wait --for=condition=Ready pod/np-plain --timeout=120s >> "$ART/apply.log" 2>&1 || fail "np-plain did not start"
kube -n finapp-edge wait --for=jsonpath='{.status.containerStatuses[0].state.running}' pod/np-edge --timeout=120s >> "$ART/apply.log" 2>&1 || fail "np-edge did not start"

if [ "$PROBE" = no-default-deny ]; then
    # The probe: the namespace's default-deny removed, as the entry says. The application's and the migrator's own policies still select them, so what this
    # takes away is the refusal of a pod no other policy selects: np-plain below.
    kube -n finapp delete networkpolicy default-deny >> "$ART/apply.log" 2>&1 || cannot "the probe could not remove default-deny"
    log "PROBE no-default-deny: the namespace's default-deny removed"
    sleep 8
fi

# (1) A pod in another namespace reaches neither port; the scraper and the edge reach exactly their own.
must_not_reach "1 ingress" finapp-rehearsal np-outsider probe "$APP_IP" 8081
must_not_reach "1 ingress" finapp-rehearsal np-outsider probe "$APP_IP" 8080
must_reach "1 control" finapp-observability np-scraper probe "$APP_IP" 8081
must_not_reach "1 control" finapp-observability np-scraper probe "$APP_IP" 8080
must_reach "1 control" finapp-edge np-edge probe "$APP_IP" 8080
must_not_reach "1 control" finapp-edge np-edge probe "$APP_IP" 8081

# (2) Egress, from inside a running application pod's own network namespace.
if ! kube -n finapp debug "$TARGET_POD" --profile=restricted --image="$CURL_IMAGE" --container=np-debug --target=app -- sleep 3600 >> "$ART/apply.log" 2>&1; then
    fail "the ephemeral container could not be added to $TARGET_POD: see apply.log"
fi
kube -n finapp wait --for=jsonpath='{.status.ephemeralContainerStatuses[0].state.running}' "pod/$TARGET_POD" --timeout=120s >> "$ART/apply.log" 2>&1 || fail "the ephemeral container did not start"
OUTSIDER_IP=$(kube -n finapp-rehearsal get pod np-outsider -o jsonpath='{.status.podIP}')
PG_IP=$(kube -n finapp get pod finapp-postgres-0 -o jsonpath='{.status.podIP}')
KAFKA_IP=$(kube -n finapp get pod finapp-kafka-0 -o jsonpath='{.status.podIP}')
DNS_IP=$(kube -n kube-system get pods -l k8s-app=kube-dns -o jsonpath='{.items[0].status.podIP}')
must_reach "2 control" finapp "$TARGET_POD" np-debug "$PG_IP" 5432
must_not_reach "2 egress" finapp "$TARGET_POD" np-debug "$OUTSIDER_IP" 8080
must_not_reach "2 egress" finapp "$TARGET_POD" np-debug 1.1.1.1 443
must_not_reach "default-deny" finapp np-plain probe "$OUTSIDER_IP" 8080
must_not_reach "default-deny" finapp np-plain probe 1.1.1.1 443
# A control on the targets themselves: they are reachable where no policy stands, so the refusals above are the policy's.
must_reach "2 control" finapp-observability np-scraper probe "$OUTSIDER_IP" 8080
must_reach "2 control" finapp-rehearsal np-outsider probe 1.1.1.1 443
if kube -n finapp exec "$TARGET_POD" -c np-debug -- nslookup "finapp-postgres.finapp.svc.cluster.local" >> "$ART/network-policy.log" 2>&1; then
    pass "2 control: the application's network namespace resolves a name over UDP"
else
    fail "2 control: the application's network namespace could not resolve a name over UDP"
fi
answer=$(dns_over_tcp finapp "$TARGET_POD" np-debug "$DNS_IP" || true)
case $answer in
    *1234*) pass "2 control: the application's network namespace resolves a name over TCP" ;;
    *) fail "2 control: no TCP answer from the resolver (got: $answer)" ;;
esac

# The migrator's namespace: PostgreSQL and the resolver, and nothing else, Kafka above all.
must_reach "2 control" finapp np-migrator probe "$PG_IP" 5432
must_not_reach "2 egress" finapp np-migrator probe "$KAFKA_IP" 9092
must_not_reach "2 egress" finapp np-migrator probe 1.1.1.1 443

# (3) A pod without requests is refused, each refused pod meeting `restricted` in every other respect.
admission_pod() {
    # admission_pod <file> <name> <security YAML of the first container, may be empty> <containers YAML>
    cat > "$1" <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: $2
  namespace: finapp
spec:
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 65532
    runAsGroup: 65532
    seccompProfile:
      type: RuntimeDefault
  containers:
$3
YAML
}
RESTRICTED='      securityContext:
        allowPrivilegeEscalation: false
        privileged: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]'
FULL_RESOURCES='      resources:
        requests: {cpu: 100m, memory: 128Mi}
        limits: {cpu: 100m, memory: 128Mi}'
C_ONE="    - name: a
      image: $CURL_IMAGE
      command: [\"/bin/sh\", \"-c\", \"sleep 3600\"]
$RESTRICTED"
C_TWO="    - name: b
      image: $CURL_IMAGE
      command: [\"/bin/sh\", \"-c\", \"sleep 3600\"]
$RESTRICTED"
admission() {
    # admission <name> <file>: the server's answer to a dry run, the message kept.
    kube create --dry-run=server -f - < "$2" > "$ART/admission-$1.out" 2>&1
}
# The refused pods are refused by the first check they meet, so the message is the test.
admission_pod "$WORK/adm-3a.yaml" adm-3a "$C_ONE"
if admission 3a "$WORK/adm-3a.yaml"; then
    fail "3a: a pod stating no requests was admitted"
elif grep -q 'No request is specified' "$ART/admission-3a.out"; then
    pass "3a: a pod stating no resources is refused by the LimitRange: $(tr '\n' ' ' < "$ART/admission-3a.out" | cut -c1-200)"
else
    fail "3a: refused, but not by the message P15-TSK-010 read from source: $(tr '\n' ' ' < "$ART/admission-3a.out" | cut -c1-300)"
fi
admission_pod "$WORK/adm-3b.yaml" adm-3b "$C_ONE
$FULL_RESOURCES
$C_TWO"
if admission 3b "$WORK/adm-3b.yaml"; then
    fail "3b: a pod with one container stating nothing was admitted"
elif grep -q 'must specify' "$ART/admission-3b.out"; then
    pass "3b: a container stating nothing beside one that does is refused by the quota: $(tr '\n' ' ' < "$ART/admission-3b.out" | cut -c1-200)"
else
    fail "3b: refused, but not by the message P15-TSK-010 read from source: $(tr '\n' ' ' < "$ART/admission-3b.out" | cut -c1-300)"
fi
admission_pod "$WORK/adm-control.yaml" adm-control "$C_ONE
$FULL_RESOURCES
$C_TWO
$FULL_RESOURCES"
if admission control "$WORK/adm-control.yaml"; then
    pass "3 control: the same pod with every container's requests and limits is admitted"
else
    fail "3 control: a complete pod was refused: $(tr '\n' ' ' < "$ART/admission-control.out" | cut -c1-300)"
fi
sed 's/allowPrivilegeEscalation: false/allowPrivilegeEscalation: true/' "$WORK/adm-control.yaml" > "$WORK/adm-psa.yaml"
if admission psa "$WORK/adm-psa.yaml"; then
    fail "3 control: a pod breaking only restricted was admitted"
elif grep -qi 'violates PodSecurity' "$ART/admission-psa.out"; then
    pass "3 control: a pod breaking only restricted is refused by Pod Security"
else
    fail "3 control: refused, but not by Pod Security: $(tr '\n' ' ' < "$ART/admission-psa.out" | cut -c1-300)"
fi

if [ "$PROBE" = no-default-deny ]; then
    fail "PROBE no-default-deny: every denial still held with the policies removed, so the test cannot fail"
fi

# The namespace's quota equals the fleet's worst moment with nothing to spare, so the probes leave before anything rolls.
kube -n finapp delete pod np-migrator np-plain --grace-period=0 --force >> "$ART/apply.log" 2>&1 || cannot "the probe pods could not be removed"

step edge-acceptance
client edge > "$ART/edge.txt" 2>&1 || fail "the edge check could not run: see edge.txt"
grep -q 'edge public-route status=401 hsts=max-age=31536000' "$ART/edge.txt" \
    && pass "a customer route answers over TLS with HSTS, on the certificate the local CA issued" \
    || fail "the public route: $(grep 'public-route' "$ART/edge.txt")"
grep -q 'edge operator-route-on-public status=404' "$ART/edge.txt" \
    && pass "a /v1/operator route answers 404 on the public host" \
    || fail "the operator route on the public host: $(grep 'operator-route-on-public' "$ART/edge.txt")"
grep -Eq 'edge operator-route-on-operator status=401 ' "$ART/edge.txt" \
    && pass "a /v1/operator route reaches the application on the operator host" \
    || fail "the operator route on the operator host: $(grep 'operator-route-on-operator' "$ART/edge.txt")"
grep -q 'edge actuator-on-public status=404' "$ART/edge.txt" \
    && pass "the actuator is not on the public host" \
    || fail "the actuator on the public host: $(grep 'actuator-on-public' "$ART/edge.txt")"
# The surface marker's own acceptance (P15-TSK-042) is step surface-on-n, after the rollout: this step runs on N - 1, which can predate it.

CM_POD_IP=$(kube -n cert-manager get pods -l app.kubernetes.io/component=controller -o jsonpath='{.items[0].status.podIP}')
kube -n finapp-observability exec np-scraper -c probe -- curl -s --max-time 10 "http://$CM_POD_IP:9402/metrics" > "$ART/cert-manager-metrics.txt" 2>&1 \
    || fail "cert-manager could not be scraped"
series=$(grep -c '^certmanager_certificate_expiration_timestamp_seconds' "$ART/cert-manager-metrics.txt" || true)
[ "$series" -ge 2 ] && pass "cert-manager is scraped: $series certificate-expiry series" || fail "no certificate-expiry series in cert-manager's metrics"

# ---------------------------------------------------------------------------------------------------
# The connection budget and the rollout.
# ---------------------------------------------------------------------------------------------------
FLEET_INSTANCES=$(sed -n 's/^  FINAPP_DB_INSTANCES: "\([0-9]*\)".*/\1/p' "$A/all.yaml" | head -1)
WRITER_POOL=$(sed -n 's/^ *maximum-pool-size: *\([0-9]*\).*/\1/p' "$ROOT/app/src/main/resources/application.yaml" | head -1)
REPORTING_POOL=$(sed -n 's/^ *pool-size: *\${FINAPP_DB_REPORTING_POOL_SIZE:\([0-9]*\)}.*/\1/p' "$ROOT/app/src/main/resources/application.yaml" | head -1)
BUDGET=$((FLEET_INSTANCES * (WRITER_POOL + REPORTING_POOL)))
log "the budget's arithmetic: $FLEET_INSTANCES instances x ($WRITER_POOL + $REPORTING_POOL) = $BUDGET"
[ "$BUDGET" = 48 ] || fail "the overlay's budget is $BUDGET, and this rehearsal measures 6 x (7 + 1) = 48"

REPORTING_LOGIN=$(kube -n finapp get secret finapp-app-secrets -o jsonpath='{.data.FINAPP_DB_REPORTING_PASSWORD}' | base64 -d)
operator_connect() {
    # An operator's session, as finapp_reporting, over TLS: not a superuser, so the reserved slots are not what admit it.
    printf '%s\n' "$REPORTING_LOGIN" | kube -n finapp exec -i finapp-postgres-0 -c postgres -- /bin/sh -c \
        'IFS= read -r PGPASSWORD; export PGPASSWORD; exec psql "host=127.0.0.1 dbname=finapp user=finapp_reporting sslmode=require" -X -q -A -t -c "select 1"' 2>/dev/null
}

sample_fleet() {
    kube -n finapp get pods -l app.kubernetes.io/name=finapp-app \
        -o jsonpath='{range .items[*]}{.metadata.name}|{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null
}

start_sampler() {
    label=$1
    file="$ART/samples-$label.tsv"
    printf 'time\tready\tterminating\tpods\tfinapp_connections\ttotal_connections\toperator\n' > "$file"
    (
        peak=0
        while [ ! -f "$WORK/sampler-stop-$label" ]; do
            fleet=$(sample_fleet || true)
            counts=$(printf '%s\n' "$fleet" | awk -F'|' 'NF>=3 && $2=="" && $3=="True" {r++} NF>=3 && $2!="" {t++} NF>=3 {p++} END {print r+0, t+0, p+0}')
            connections=$(psql_query -c "SELECT count(*) FILTER (WHERE application_name = 'finapp'), count(*) FROM pg_stat_activity" 2>/dev/null | tr '|' ' ')
            set -- $counts $connections
            operator=-
            if [ -n "${4:-}" ] && [ "$4" -gt 0 ] && [ "$4" -ge "$peak" ]; then
                peak=$4
                if [ "$(operator_connect || true)" = 1 ]; then operator=ok; else operator=REFUSED; fi
            fi
            printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$1" "$2" "$3" "${4:-}" "${5:-}" "$operator" >> "$file"
            sleep 2
        done
    ) &
    SAMPLER=$!
}

stop_sampler() {
    touch "$WORK/sampler-stop-$1"
    wait "$SAMPLER" 2>/dev/null || true
    SAMPLER=""
}

peak_of() { awk -F'\t' 'NR>1 && $5+0 > m {m = $5+0} END {print m+0}' "$ART/samples-$1.tsv"; }
floor_of() { awk -F'\t' 'NR>1 && (m == "" || $2+0 < m) {m = $2+0} END {print m+0}' "$ART/samples-$1.tsv"; }
operator_at_peak() { awk -F'\t' -v p="$(peak_of "$1")" 'NR>1 && $5+0 == p && $7 == "ok" {n++} END {print n+0}' "$ART/samples-$1.tsv"; }
operator_refused() { awk -F'\t' 'NR>1 && $7 == "REFUSED" {n++} END {print n+0}' "$ART/samples-$1.tsv"; }
fleet_connections() { psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'finapp'" | tr -d '[:space:]'; }

# The peak says nothing about a pod that holds too many if the rollout had pods to spare, so each sample is held to its own pods as well.
assert_within_pods() {
    over=$(awk -F'\t' -v pool="$((WRITER_POOL + REPORTING_POOL))" 'NR>1 && $5+0 > $4*pool {n++} END {print n+0}' "$ART/samples-$1.tsv")
    [ "$over" = 0 ] || fail "$1: $over samples held more than $((WRITER_POOL + REPORTING_POOL)) connections for each pod: see samples-$1.tsv"
    pass "$1: no sample held more than $((WRITER_POOL + REPORTING_POOL)) connections for each pod it had"
}

assert_flows_ran() {
    for flow in transfer top-up register authenticate checkout payout; do
        done_count=$(sed -n "s/.*[{ ]$flow\.ok=\([0-9]*\).*/\1/p" "$CLIENT/workload-$1.summary" | head -1)
        [ "${done_count:-0}" -gt 0 ] || fail "$1: the $flow flow completed nothing, and a dead thread reads like a quiet one: see workload-$1.tsv"
    done
    pass "$1: every flow of the workload completed work"
}

# The reporting pool opens on a pod's first report, so a fleet that has run none holds seven connections a pod and the eighth of the budget is never measured. One report per ready pod, sent to
# that pod from the pod the policy admits to the API port: a report is generated by the replica that takes the request. The kind overlay shortens a provisional report's freshness, or the first
# report would answer all the others.
report_on() {
    # report_on <pod address> <bearer>: the HTTP status of one report generated there. The bearer travels on stdin, never in an argument.
    printf '%s\n' "$2" | kube -n finapp-edge exec -i np-edge -c probe -- /bin/sh -c '
        IFS= read -r bearer
        curl -s -o /dev/null -w "%{http_code}" --max-time 60 -X POST -H "Authorization: Bearer $bearer" -H "Content-Type: application/json" \
            -H "Idempotency-Key: report-$(printf %s "$2" | tr . -)-$(date +%s)" -d "$1" "http://$2:8080/v1/operator/accounting/report-runs"' probe "$REPORT_BODY" "$1" 2>/dev/null
}

start_reporter() {
    label=$1
    REPORT_PERIOD=$(psql_query -c "SELECT id FROM accounting.accounting_period WHERE period_start = date_trunc('month', now() AT TIME ZONE 'UTC')::date" | tr -d '[:space:]')
    [ -n "$REPORT_PERIOD" ] || fail "$label: this month has no accounting period to report on"
    REPORT_BODY="{\"definition\":\"TRIAL_BALANCE\",\"periodId\":\"$REPORT_PERIOD\"}"
    report_login=$(client report-token 2>/dev/null | sed -n 's/^token //p')
    [ -n "$report_login" ] || fail "$label: no operator session for the reports"
    : > "$WORK/reported-$label"
    : > "$ART/reports-$label.tsv"
    (
        while [ ! -f "$WORK/reporter-stop-$label" ]; do
            for ip in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}|{.status.podIP}{"\n"}{end}' 2>/dev/null | awk -F'|' '$1=="" && $2=="True" {print $3}'); do
                grep -qx "$ip" "$WORK/reported-$label" && continue
                code=$(report_on "$ip" "$report_login" || true)
                printf '%s\t%s\t%s\n' "$(date -u +%H:%M:%S)" "$ip" "$code" >> "$ART/reports-$label.tsv"
                case $code in 200|201) printf '%s\n' "$ip" >> "$WORK/reported-$label" ;; esac
                sleep 2
            done
            sleep 3
        done
    ) &
    REPORTER=$!
}

# Every replica now ready has generated its report: a replacement can come up after the rollout's own pods.
wait_for_reports() {
    settle_deadline=$(( $(date +%s) + 90 ))
    while [ "$(date +%s)" -lt "$settle_deadline" ]; do
        pending=0
        for ip in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.deletionTimestamp}|{.status.podIP}{"\n"}{end}' 2>/dev/null | awk -F'|' '$1=="" {print $2}'); do
            grep -qx "$ip" "$WORK/reported-$1" || pending=$((pending + 1))
        done
        [ "$pending" = 0 ] && return 0
        sleep 2
    done
    return 1
}

stop_reporter() {
    touch "$WORK/reporter-stop-$1"
    wait "$REPORTER" 2>/dev/null || true
    REPORTER=""
}

# Three replicas, each having run a report, hold the whole pool: the fleet's own figure, once it has settled.
assert_reporting_open() {
    reported=$(awk -F'\t' '$3 ~ /^20[01]$/ {n++} END {print n+0}' "$ART/reports-$1.tsv")
    [ "$reported" -ge 3 ] || fail "$1: only $reported pods generated a report, so the eighth connection was not measured: see reports-$1.tsv"
    want=$((3 * (WRITER_POOL + REPORTING_POOL)))
    settle_deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$settle_deadline" ]; do
        [ "$(fleet_connections)" = "$want" ] && break
        sleep 3
    done
    held=$(fleet_connections)
    [ "$held" = "$want" ] || fail "$1: the settled fleet holds $held connections where three replicas with their reporting pools open hold $want"
    pass "$1: $reported pods generated a report, and the settled fleet holds $want connections, every pool open"
}

# `kubectl rollout status` returns once the last old pod is marked for deletion. That pod still runs its preStop and its drain with its consumers polling, so a window that closes there leaves a
# tail acknowledged unseen and never replayed. The window closes when no pod is terminating.
no_pod_terminating() {
    settle_deadline=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$settle_deadline" ]; do
        [ "$(sample_fleet | awk -F'|' 'NF>=3 && $2!="" {t++} END {print t+0}')" = 0 ] && return 0
        sleep 2
    done
    return 1
}

start_workload() {
    name="$CLUSTER-workload-$1"
    # shellcheck disable=SC2086
    dock run -d --name "$name" --network kind --add-host "public.finapp.test:$NODE_IP" --add-host "operator.finapp.test:$NODE_IP" \
        -e "WORKLOAD_RETRY=${2:-}" -v "$(dpath "$CLIENT"):/work" -v "$(dpath "$DIR"):/src:ro" "$JDK_IMAGE" java --source 21 /src/Rehearsal.java /work workload "$1" >/dev/null \
        || cannot "the workload could not start"
    CONTAINERS="$CONTAINERS $name"
}

stop_workload() {
    name="$CLUSTER-workload-$1"
    touch "$CLIENT/stop-$1"
    dock wait "$name" >/dev/null 2>&1 || true
    dock logs "$name" >> "$ART/client.log" 2>&1 || true
    dock rm -f "$name" >/dev/null 2>&1 || true
    cp "$CLIENT/workload-$1.tsv" "$ART/" 2>/dev/null || true
    [ -f "$CLIENT/workload-$1.summary" ] || fail "the workload $1 wrote no summary"
    log "$(cat "$CLIENT/workload-$1.summary")"
}

workload_failed() { sed -n 's/.* failed=\([0-9]*\) .*/\1/p' "$CLIENT/workload-$1.summary" | head -1; }
workload_unresolved() { sed -n 's/.* unresolved=\([0-9]*\) .*/\1/p' "$CLIENT/workload-$1.summary" | head -1; }

db_epoch() { psql_query -c "SELECT extract(epoch FROM now())::bigint" | tr -d '[:space:]'; }
iso() { date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }

# The Eviction API, the way a node drain removes a pod: the PodDisruptionBudget still decides. Used mid-rollout for the budget's "+ 1" term.
evict_during_rollout() {
    # evict_during_rollout <image of the pod to evict> <label> [<terminating pods to wait for> <running pods to wait for>]
    old=$1
    label=$2
    want_terminating=${3:-1}
    want_running=${4:-4}
    deadline=$(( $(date +%s) + ROLLOUT_TIMEOUT ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        fleet=$(sample_fleet || true)
        read -r t n <<COUNTS
$(printf '%s\n' "$fleet" | awk -F'|' 'NF>=3 && $2!="" {t++} NF>=3 && $2=="" {n++} END {print t+0, n+0}')
COUNTS
        if [ "$t" -ge "$want_terminating" ] && [ "$n" -ge "$want_running" ]; then
            victim=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.name}|{.metadata.deletionTimestamp}|{.spec.containers[0].image}{"\n"}{end}' \
                | awk -F'|' -v old="$old" '$2=="" && $3==old {print $1; exit}')
            if [ -n "$victim" ]; then
                if printf '{"apiVersion":"policy/v1","kind":"Eviction","metadata":{"name":"%s","namespace":"finapp"}}' "$victim" \
                    | kube create --raw "/api/v1/namespaces/finapp/pods/$victim/eviction" -f - >> "$ART/eviction-$label.log" 2>&1; then
                    log "evicted $victim mid-rollout, through the Eviction API"
                    printf 'evicted %s\n' "$victim" >> "$ART/eviction-$label.log"
                    return 0
                fi
            fi
        fi
        sleep 3
    done
    log "no pod was evicted during $label"
}

# rollout <label> <new image> <old image> <evict: yes|no>: the workload, the sampling, the assertions a rollout owes.
rollout() {
    label=$1
    start_workload "$label"
    sleep 8
    start_sampler "$label"
    start_reporter "$label"
    ROLLOUT_FROM=$(db_epoch)
    started=$(date +%s)
    kube -n finapp set image deployment/finapp-app "app=$2" >> "$ART/apply.log" 2>&1 || fail "the image could not be set"
    if [ "$4" = yes ]; then
        if [ "$PROBE" = min-ready ]; then
            # Three old pods held by the long preStop and three new ones running are six, the quota's own figure, which the probe has raised. Evicting a new pod then makes a seventh.
            evict_during_rollout "$2" "$label" 3 3 &
        else
            evict_during_rollout "$3" "$label" &
        fi
        EVICTOR=$!
    else
        EVICTOR=""
    fi
    if ! kube -n finapp rollout status deployment/finapp-app --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1; then
        kube -n finapp get pods -o wide > "$ART/pods-at-failure-$label.txt" 2>&1 || true
        stop_reporter "$label"
        stop_sampler "$label"
        stop_workload "$label"
        fail "the rollout $label did not complete within ${ROLLOUT_TIMEOUT}s: see pods-at-failure-$label.txt"
    fi
    [ -z "$EVICTOR" ] || wait "$EVICTOR" 2>/dev/null || true
    no_pod_terminating 300 || fail "rollout $label: a pod was still terminating 300 s after the rollout"
    ended=$(date +%s)
    ROLLOUT_TO=$(db_epoch)
    sleep 8
    wait_for_reports "$label" || fail "rollout $label: a replica never generated its report: see reports-$label.tsv"
    stop_reporter "$label"
    stop_sampler "$label"
    stop_workload "$label"
    printf 'rollout %s took %s s\n' "$label" "$((ended - started))" | tee -a "$ART/timings.txt" >> "$ART/rehearsal.log"
    [ "$(workload_failed "$label")" = 0 ] || fail "rollout $label: $(workload_failed "$label") failed requests, see workload-$label.tsv"
    pass "rollout $label: zero failed requests, $(sed -n 's/.* ok=\([0-9]*\) .*/\1/p' "$CLIENT/workload-$label.summary") succeeded"
    assert_flows_ran "$label"
    peak=$(peak_of "$label")
    log "peak pg_stat_activity for finapp during $label: $peak (budget $BUDGET)"
    [ "$peak" -le "$BUDGET" ] || fail "rollout $label: the peak of $peak connections exceeds instances x pool = $BUDGET"
    pass "rollout $label: peak $peak connections within instances x pool = $BUDGET"
    assert_within_pods "$label"
    [ "$(operator_at_peak "$label")" -ge 1 ] || fail "rollout $label: no operator connection was tried at the peak"
    [ "$(operator_refused "$label")" = 0 ] || fail "rollout $label: an operator was refused during the rollout"
    pass "rollout $label: an operator was admitted at the peak"
    assert_reporting_open "$label"
}

assert_floor() {
    floor=$(floor_of "$1")
    [ "$floor" -ge 3 ] || fail "rollout $1: ready replicas fell to $floor, and maxUnavailable 0 keeps three"
    pass "rollout $1: ready replicas never below three"
}

# ---------------------------------------------------------------------------------------------------
# The handler window (a handler added in release N): what an N - 1 replica committed unseen, and the audited replay that heals it.
# ---------------------------------------------------------------------------------------------------
window_counts() {
    # window_counts <from epoch> <to epoch>: "unseen|total" of the handler's event type first published in the window.
    psql_query -c "SELECT count(*) FILTER (WHERE i.dedupe_key IS NULL), count(*) FROM platform.outbox_event e LEFT JOIN platform.inbox_message i ON i.consumer = '$HANDLER_CONSUMER' AND i.dedupe_key = e.event_id::text WHERE e.event_type = '$HANDLER_EVENT' AND COALESCE(e.first_published_at, e.published_at) >= to_timestamp($1) AND COALESCE(e.first_published_at, e.published_at) < to_timestamp($2)" | tr -d '[:space:]'
}

replay_window() {
    # replay_window <from> <to> <reason>: in slices, because one command refuses more than 1000 events.
    t=$1
    while [ "$t" -lt "$2" ]; do
        end=$((t + 300))
        [ "$end" -le "$2" ] || end=$2
        client replay "$HANDLER_EVENT" "$(iso "$t")" "$(iso "$end")" "$3" >> "$ART/client.log" 2>&1 || fail "the replay of [$(iso "$t"), $(iso "$end")) failed: see client.log"
        t=$end
    done
}

# Unseen after the replay must be zero, and every event in the window must have exactly one dedupe row; the inbox's key is what makes that exactly one.
assert_handler_window() {
    # assert_handler_window <label> <from> <to> <unseen before the replay>
    label=$1
    deadline=$(( $(date +%s) + 240 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        counts=$(window_counts "$2" "$3")
        unseen=${counts%%|*}
        total=${counts##*|}
        [ "$unseen" = 0 ] && break
        sleep 3
    done
    [ "$unseen" = 0 ] || fail "handler window $label: $unseen of $total events still unseen after the replay"
    pass "handler window $label: every one of $total events applied exactly once ($4 unseen before the replay)"
}

# ---------------------------------------------------------------------------------------------------
# The hold: a replica of N ahead of its Job.
# ---------------------------------------------------------------------------------------------------
migrations_between() {
    git -C "$(dpath "$ROOT")" diff --name-only "$1" HEAD -- '*/src/main/resources/db/migration/*' | wc -l | tr -d ' '
}

NM1_REF=$(cat "$ART/nminus1.ref" 2>/dev/null || true)
step hold
if [ -n "$NM1_REF" ] && [ "$(migrations_between "$NM1_REF")" -gt 0 ]; then
    kube -n finapp set image deployment/finapp-app "app=$IMAGE_APP_N" >> "$ART/apply.log" 2>&1 || fail "the image could not be set"
    surge=""
    deadline=$(( $(date +%s) + 240 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        surge=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.name}|{.status.containerStatuses[0].started}|{.spec.containers[0].image}{"\n"}{end}' \
            | awk -F'|' -v n="$IMAGE_APP_N" '$2=="true" && $3==n {print $1; exit}')
        [ -n "$surge" ] && break
        sleep 3
    done
    [ -n "$surge" ] || fail "no surge pod of N started ahead of its Job"
    sleep 45
    ready=$(kube -n finapp get pod "$surge" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')
    [ "$ready" = False ] || fail "a surge pod of N on an older schema went ready"
    kube -n finapp logs "$surge" > "$ART/hold-surge.log" 2>&1 || true
    if grep -q 'out of service until the migration has run' "$ART/hold-surge.log"; then
        pass "hold: a replica of N on an older schema is not ready and says why: $(grep -m1 'out of service' "$ART/hold-surge.log" | sed 's/.*"message":"//' | cut -c1-160)"
    else
        fail "hold: the guard's log line is missing: see hold-surge.log"
    fi
    surge_ip=$(kube -n finapp get pod "$surge" -o jsonpath='{.status.podIP}')
    kafka_admin kafka-consumer-groups.sh --describe --all-groups --members > "$ART/hold-members.txt" 2>&1 || true
    # A listing that shows no member at all would pass the test below: the ready replicas of N - 1 must be in it for the absence to mean anything.
    old_ip=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app --field-selector=status.phase=Running -o jsonpath='{range .items[*]}{.status.podIP}{" "}{.spec.containers[0].image}{"\n"}{end}' \
        | awk -v n="$IMAGE_APP_N" '$2 != n {print $1; exit}')
    grep -q "/$old_ip" "$ART/hold-members.txt" || fail "hold: the group listing shows no member of the ready fleet: see hold-members.txt"
    if grep -q "/$surge_ip" "$ART/hold-members.txt"; then
        fail "hold: the surge pod holds consumer-group membership"
    else
        pass "hold: the surge pod joined no consumer group (no member at $surge_ip; the ready fleet's are listed)"
    fi
    # While it waits the ready fleet keeps working: a smoke through it writes outbox rows, and the fleet publishes them. The surge pod's own relay, the first of the
    # schedules it must not tick, publishes nothing; its scrape is read for the counter, and a pod with nothing to report has no counter at all.
    smoke during-hold
    kube -n finapp-observability exec np-scraper -c probe -- curl -s --max-time 15 "http://$surge_ip:8081/actuator/prometheus" > "$ART/hold-metrics.txt" 2>&1 \
        || fail "hold: the held pod's metrics could not be read"
    grep -q '^finapp_' "$ART/hold-metrics.txt" || fail "hold: the held pod's scrape holds no finapp meter: see hold-metrics.txt"
    published=$(awk '/^finapp_outbox_publication_total[{]outcome="published"[}]/ {print $2 + 0; exit}' "$ART/hold-metrics.txt")
    [ "${published:-0}" = 0 ] || fail "hold: the surge pod's relay published $published events while it was held"
    pass "hold: the surge pod's relay published nothing while it waited, and the fleet kept working"
    parked=$(psql_query -c "SELECT count(*) FROM platform.inbox_parked_record" | tr -d '[:space:]')
    [ "$parked" = 0 ] || fail "hold: $parked records parked while the surge pod waited"
    pass "hold: nothing parked while a pod waited ahead of its Job"
    kube -n finapp rollout undo deployment/finapp-app >> "$ART/apply.log" 2>&1 || fail "the rollout could not be undone"
    kube -n finapp rollout status deployment/finapp-app --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1 || fail "the fleet did not return after the hold"
    ready_three 240 || fail "the fleet did not return to three after the hold"
else
    log "HOLD NOT EXERCISED: N - 1 and N have the same migrations"
    printf 'hold not exercised: N - 1 and N have the same migrations\n' >> "$ART/not-exercised.txt"
fi

step migrator-n
sed -i "s#$IMAGE_MIGRATOR_NM1#$IMAGE_MIGRATOR_N#" "$A/Job.finapp-migrator.yaml"
kube -n finapp delete job finapp-migrator --wait=true >> "$ART/apply.log" 2>&1 || cannot "the N - 1 Job could not be removed"
apply_files "$A/Job.finapp-migrator.yaml"
wait_job finapp finapp-migrator 900 migrator-n
log "migrator (N): $(grep -h 'result=APPLIED' "$ART/migrator-n.log" | tail -1 | cut -c1-200)"

step nminus1-on-n-schema
sleep 20
ready_three 120 || fail "a replica of N - 1 did not stay ready on N's schema"
pass "three replicas of N - 1 are ready on N's schema"
smoke on-n-schema
census nminus1-on-n-schema

# A probe's break is applied to the Deployment before the rollout it must be seen breaking.
apply_probe() {
    case $PROBE in
        max-unavailable)
            kube -n finapp patch deployment finapp-app --type=json -p '[{"op":"replace","path":"/spec/strategy/rollingUpdate/maxUnavailable","value":1},{"op":"add","path":"/spec/template/spec/containers/0/readinessProbe/initialDelaySeconds","value":45}]' >> "$ART/apply.log" 2>&1 \
                || cannot "the probe could not patch the Deployment"
            kube -n finapp rollout status deployment/finapp-app --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1 || cannot "the probe's patch did not settle"
            ;;
        min-ready)
            # The kind quota is the formula's six application pods, so it, and not the rollout, is what stops a seventh: the probe raises it by one pod, as an overlay's numbers drifting from the formula would.
            kube -n finapp patch resourcequota finapp --type=json -p '[{"op":"replace","path":"/spec/hard/pods","value":"11"},{"op":"replace","path":"/spec/hard/requests.cpu","value":"4750m"},{"op":"replace","path":"/spec/hard/limits.cpu","value":"17"},{"op":"replace","path":"/spec/hard/requests.memory","value":"9216Mi"},{"op":"replace","path":"/spec/hard/limits.memory","value":"9216Mi"}]' >> "$ART/apply.log" 2>&1 \
                || cannot "the probe could not raise the quota"
            kube -n finapp patch deployment finapp-app --type=json -p '[{"op":"replace","path":"/spec/minReadySeconds","value":0},{"op":"replace","path":"/spec/template/spec/containers/0/lifecycle/preStop/sleep/seconds","value":180},{"op":"replace","path":"/spec/template/spec/terminationGracePeriodSeconds","value":230}]' >> "$ART/apply.log" 2>&1 \
                || cannot "the probe could not patch the Deployment"
            kube -n finapp rollout status deployment/finapp-app --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1 || cannot "the probe's patch did not settle"
            ;;
    esac
}

step rollout-to-n
apply_probe
if [ "$PROBE" = min-ready ]; then
    rollout rollout-to-n "$IMAGE_APP_N" "$IMAGE_APP_NM1" yes
    fail "PROBE min-ready: the peak stayed within the budget, so the assertion cannot fail"
fi
rollout rollout-to-n "$IMAGE_APP_N" "$IMAGE_APP_NM1" no
assert_floor rollout-to-n
[ "$PROBE" != max-unavailable ] || fail "PROBE max-unavailable: the floor held, so the assertion cannot fail"
FROM_N=$ROLLOUT_FROM
TO_N=$ROLLOUT_TO
census rollout-to-n

step handler-window-n
counts=$(window_counts "$FROM_N" "$TO_N")
UNSEEN=${counts%%|*}
TOTAL=${counts##*|}
log "handler window: $UNSEEN of $TOTAL $HANDLER_EVENT records were committed unseen by N - 1"
[ "${UNSEEN:-0}" -gt 0 ] || fail "handler window: no record reached an N - 1 replica, so this rehearsal cannot show the case"
if [ "$PROBE" = skip-replay ]; then
    log "PROBE skip-replay: the replay is skipped, and the same assertion is made over the same window"
else
    replay_window "$FROM_N" "$TO_N" "the rehearsal: handler added in release N, over the rollout window"
fi
assert_handler_window rollout "$FROM_N" "$TO_N" "$UNSEEN"
[ "$PROBE" != skip-replay ] || fail "PROBE skip-replay: the window was clean with no replay, so the assertion cannot fail"
census handler-window-n

step surface-on-n
# P15-TSK-042: the edge's surface marker through the real controllers, on release N - an operator's own password refused on the public host,
# served on the operator host. Here and not at edge-acceptance, which runs on N - 1: an N - 1 that predates the refusal serves it there. The client
# prints each status alone, never a body, so a sign-in that was served leaves no session token in the artefacts.
client surface > "$ART/surface.txt" 2>&1 || fail "the surface check could not run: see surface.txt"
grep -qx 'surface operator-sign-in-on-public status=401' "$ART/surface.txt" \
    && pass "an operator's own password is refused on the public host" \
    || fail "the operator's sign-in on the public host: $(grep 'operator-sign-in-on-public' "$ART/surface.txt")"
grep -qx 'surface operator-sign-in-on-operator status=201' "$ART/surface.txt" \
    && pass "an operator signs in on the operator host" \
    || fail "the operator's sign-in on the operator host: $(grep 'operator-sign-in-on-operator' "$ART/surface.txt")"

step forged-address
# (3) of the edge's acceptance. What the application keys on is request.getRemoteAddr() after the RemoteIpValve, and N's per-source limits are built on it, a bucket of the address
# in platform.window_counter and never the address. The client calls this door nowhere else, so every row of its scope is this step's. Forged addresses that became the key would each
# make a row; a key taken from the connection's peer, the controller, would put a second real client in the first one's row. A stopped client's address is reused, so the second
# client runs while a holder keeps the first one's.
forged_counts() { psql_query -c "SELECT count(DISTINCT counter_key) || '|' || coalesce(sum(admitted), 0) FROM platform.window_counter WHERE scope = 'door.recovery'" | tr -d '[:space:]'; }
client forged first 5 > "$ART/edge-forged.txt" 2>&1 || fail "the forged-header requests did not run: see edge-forged.txt"
holder="$CLUSTER-address-holder"
dock run -d --rm --name "$holder" --network kind "$JDK_IMAGE" sleep 120 >> "$ART/apply.log" 2>&1 || cannot "the second client's address could not be held apart"
! grep -Eq 'status=(429|5[0-9][0-9])' "$ART/edge-forged.txt" || fail "the forged-header requests were refused: see edge-forged.txt"
observed=$(forged_counts)
[ "$observed" = '1|5' ] || fail "five forged addresses gave '$observed' (buckets|counted) where one bucket of five was due: a forged X-Forwarded-For moved the key, or the door did not count"
pass "five forged addresses landed in one bucket: a forged X-Forwarded-For does not become the address the application keys on"
client forged second 1 >> "$ART/edge-forged.txt" 2>&1 || fail "the second client's request did not run: see edge-forged.txt"
dock rm -f "$holder" >> "$ART/apply.log" 2>&1 || true
first=$(sed -n 's/^forged first status=[0-9]* client=//p' "$ART/edge-forged.txt" | sort -u)
second=$(sed -n 's/^forged second status=[0-9]* client=//p' "$ART/edge-forged.txt" | sort -u)
[ -n "$first" ] && [ -n "$second" ] && [ "$first" != "$second" ] || cannot "the two clients did not have two addresses: '$first' and '$second'"
observed=$(forged_counts)
[ "$observed" = '2|6' ] || fail "a second client at $second gave '$observed' (buckets|counted) where two buckets of six in all were due: the key is not the address the controller saw"
pass "a second real client lands in a bucket of its own: the key is the connection's address as the controller saw it"

step budget-ceiling
# The peaks above are what this rollout reached. Whether an operator is admitted with the fleet at its budget is a question about the ceiling, so the connections are held there: sessions
# named as the fleet's, up to the budget exactly, and one operator tried while they stand.
ceiling_have=$(fleet_connections)
ceiling_need=$((BUDGET - ceiling_have))
[ "$ceiling_need" -gt 0 ] || cannot "the fleet already holds $ceiling_have connections, and the ceiling is $BUDGET"
kube -n finapp exec -i finapp-postgres-0 -c postgres -- /bin/sh -c "i=0; while [ \$i -lt $ceiling_need ]; do PGAPPNAME=finapp psql -q -X -U finapp -d finapp -c 'SELECT pg_sleep(30)' >/dev/null 2>&1 & i=\$((i + 1)); done; wait" >> "$ART/apply.log" 2>&1 &
ceiling_holder=$!
ceiling_deadline=$(( $(date +%s) + 25 ))
while [ "$(date +%s)" -lt "$ceiling_deadline" ] && [ "$(fleet_connections)" -lt "$BUDGET" ]; do
    sleep 1
done
ceiling_at=$(fleet_connections)
[ "$ceiling_at" = "$BUDGET" ] || cannot "the fleet could not be held at its budget: $ceiling_at of $BUDGET"
ceiling_total=$(psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'" | tr -d '[:space:]')
[ "$(operator_connect || true)" = 1 ] || fail "an operator was refused with the fleet at its budget of $BUDGET connections, $ceiling_total client connections open"
pass "an operator is admitted with the fleet held at its budget: $ceiling_at of $BUDGET, $ceiling_total client connections of the server's $(psql_query -c 'SHOW max_connections' | tr -d '[:space:]')"
wait "$ceiling_holder" 2>/dev/null || true

step kill
start_workload kill retry
sleep 15
start_sampler kill
# A payout held at the provider, its effect already taken: a request is in flight on a replica we can name, with its claim IN_PROGRESS, whenever the kill comes. A kill that happens to find
# nothing in flight proves nothing about a dead replica's claims.
kube -n finapp-rehearsal exec deploy/finapp-simulator -- touch /tmp/hold-payouts >> "$ART/apply.log" 2>&1 || cannot "the provider could not be told to hold a payout"
held=""
held_deadline=$(( $(date +%s) + 90 ))
while [ "$(date +%s)" -lt "$held_deadline" ]; do
    held=$(kube -n finapp-rehearsal exec deploy/finapp-simulator -- cat /tmp/held.txt 2>/dev/null || true)
    [ -n "$held" ] && break
    sleep 1
done
[ -n "$held" ] || cannot "no payout reached the provider to be held"
HELD_ADDRESS=${held%% *}
VICTIM=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.name}|{.status.podIP}{"\n"}{end}' | awk -F'|' -v a="$HELD_ADDRESS" '$2==a {print $1}')
[ -n "$VICTIM" ] || cannot "no replica has the address $HELD_ADDRESS the held payout came from"
KILLED_CLAIM=$(psql_query -c "SELECT idempotency_key FROM platform.idempotency_record WHERE state = 'IN_PROGRESS' AND idempotency_key LIKE 'wl-kill-p%'" | tr -d '[:space:]')
[ -n "$KILLED_CLAIM" ] || cannot "the payout held at the provider has no IN_PROGRESS claim to orphan"
log "killing $VICTIM mid-workload, force and no grace: a payout it sent is held at the provider, its claim IN_PROGRESS"
kube -n finapp delete pod "$VICTIM" --grace-period=0 --force >> "$ART/apply.log" 2>&1 || fail "could not kill $VICTIM"
# The API forgets a force-deleted pod at once and the kubelet kills its container a moment later, so an answer released now would reach a process still alive, and nothing would have died in flight.
sleep 10
kube -n finapp-rehearsal exec deploy/finapp-simulator -- rm -f /tmp/hold-payouts >> "$ART/apply.log" 2>&1 || true
sleep 20
ready_three 400 || fail "the fleet did not return to three after the kill"
sleep 30
# The retry has to outlast the dead replica's lease, so the workload runs until the held payout's claim has been taken and finished by another replica.
claim_state() { psql_query -c "SELECT state FROM platform.idempotency_record WHERE idempotency_key = '$KILLED_CLAIM'" | tr -d '[:space:]'; }
claim_deadline=$(( $(date +%s) + 240 ))
while [ "$(date +%s)" -lt "$claim_deadline" ]; do
    [ "$(claim_state)" = COMPLETED ] && break
    sleep 3
done
stop_sampler kill
stop_workload kill
[ "$(workload_unresolved kill)" = 0 ] || fail "the kill: $(workload_unresolved kill) requests stayed unresolved, see workload-kill.tsv"
if [ "$(workload_failed kill)" -gt 0 ]; then
    pass "the kill: $(workload_failed kill) attempts failed around the kill and were retried with the same key; none unresolved"
else
    log "THE KILL NOT EXERCISED: no attempt failed around it"
    printf 'the kill not exercised: no attempt failed around it\n' >> "$ART/not-exercised.txt"
fi
[ "$(claim_state)" = COMPLETED ] || fail "the kill: the claim the dead replica held is $(claim_state), and another replica should have taken it and finished"
pass "the kill: the claim the dead replica held was taken over and completed by another replica"
assert_flows_ran kill
peak=$(peak_of kill)
[ "$peak" -le "$BUDGET" ] || fail "the kill: the peak of $peak connections exceeds $BUDGET"
pass "the kill: peak $peak connections within $BUDGET"
assert_within_pods kill
[ "$(operator_at_peak kill)" -ge 1 ] || fail "the kill: no operator connection was tried at the peak"
[ "$(operator_refused kill)" = 0 ] || fail "the kill: an operator was refused"
pass "the kill: an operator was admitted at the peak"
drained 240 || fail "the outbox did not drain after the kill"
# Partitions: another member owns everything the dead one held.
lag_settled kill || fail "after the kill, a partition is unowned or lagging: see consumer-groups-kill.txt"
pass "after the kill, every partition has a live owner and no lag"
census kill

step rollback
ROLLBACK_REF_FROM=$(db_epoch)
rollout rollback "$IMAGE_APP_NM1" "$IMAGE_APP_N" yes
[ -f "$ART/eviction-rollback.log" ] && grep -q '^evicted ' "$ART/eviction-rollback.log" || fail "the rollback's eviction did not happen, so the budget's + 1 was not measured"
census rollback

step reroll
rollout reroll "$IMAGE_APP_N" "$IMAGE_APP_NM1" no
assert_floor reroll
REROLL_TO=$ROLLOUT_TO
census reroll
SCRAPE_POD=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{.items[0].status.podIP}')
scrape_status=$(kube -n finapp-observability exec np-scraper -c probe -- curl -s -o /dev/null -w '%{http_code}' --max-time 15 "http://$SCRAPE_POD:8081/actuator/prometheus" 2>/dev/null)
[ "$scrape_status" = 200 ] || fail "a replica's scrape answered $scrape_status after traffic, every other assertion here depends on metrics being readable"
pass "a replica of N answers the scrape 200 after real traffic"

step handler-window-rollback
counts=$(window_counts "$ROLLBACK_REF_FROM" "$REROLL_TO")
UNSEEN=${counts%%|*}
TOTAL=${counts##*|}
log "handler window, rollback and re-roll: $UNSEEN of $TOTAL records unseen"
[ "${UNSEEN:-0}" -gt 0 ] || fail "handler window: nothing reached an N - 1 replica over the rollback, so this rehearsal cannot show the case"
replay_window "$ROLLBACK_REF_FROM" "$REROLL_TO" "the rehearsal: handler added in release N, over the rollback and re-roll windows"
assert_handler_window rollback "$ROLLBACK_REF_FROM" "$REROLL_TO" "$UNSEEN"
# The windows are the operator's arithmetic. This is the check of it: nothing of the type published since N's first rollout began is unseen, inside the two windows or outside them.
unseen_since() { psql_query -c "SELECT count(*) FROM platform.outbox_event e LEFT JOIN platform.inbox_message i ON i.consumer = '$HANDLER_CONSUMER' AND i.dedupe_key = e.event_id::text WHERE e.event_type = '$HANDLER_EVENT' AND COALESCE(e.first_published_at, e.published_at) >= to_timestamp($FROM_N) AND i.dedupe_key IS NULL" | tr -d '[:space:]'; }
whole_deadline=$(( $(date +%s) + 240 ))
while [ "$(date +%s)" -lt "$whole_deadline" ]; do
    [ "$(unseen_since)" = 0 ] && break
    sleep 3
done
[ "$(unseen_since)" = 0 ] || fail "handler window, the whole run: $(unseen_since) events of the type published since N's first rollout began were never applied, inside the replayed windows or outside them"
pass "handler window, the whole run: nothing of the type published since N's first rollout began is unseen"
census final

# A step that could not be shown is not a pass: a run in which N - 1 and N have the same schema proved nothing about the hold.
if [ -s "$ART/not-exercised.txt" ] && [ -z "${REHEARSAL_ALLOW_NOT_EXERCISED:-}" ]; then
    fail "not exercised: $(tr '\n' ';' < "$ART/not-exercised.txt")"
fi

step end
finish
