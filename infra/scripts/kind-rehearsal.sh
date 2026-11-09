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
# A machine whose Docker cannot verify a registry from inside a container (a TLS-intercepting antivirus) names a bundle it trusts. CI never does.
HELM_CA_BUNDLE=${REHEARSAL_HELM_CA_BUNDLE:-}
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
: > "$ART/timings.tsv"
: > "$ART/assertions.tsv"

log() {
    printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$ART/rehearsal.log"
}

cannot() {
    log "?? $*"
    exit 2
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
CONTAINERS=""

finish() {
    set +e
    [ -z "$SAMPLER" ] || kill "$SAMPLER" 2>/dev/null
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
    if [ "$FAILED" -ne 0 ]; then
        log "RESULT: RED"
        exit 1
    fi
    log "RESULT: GREEN"
    exit 0
}
trap 'FAILED=${FAILED:-1}; finish' INT TERM

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
    case ${1%%/*} in
        *.*) printf '%s' "$1" ;;
        *) case $1 in
            */*) printf 'docker.io/%s' "$1" ;;
            *) printf 'docker.io/library/%s' "$1" ;;
        esac ;;
    esac
}

# load_tagged <repository> <tag> [digest]: pulled on the host and imported into the node, so the node never pulls and a digest-pinned
# manifest finds its image by the digest it names.
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
    dock save --platform linux/amd64 "$source_ref" -o "$WORK/image.tar" 2>/dev/null || dock save "$source_ref" -o "$WORK/image.tar" \
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
    revision=$(git -C "$tree" rev-parse HEAD)
    epoch=$(git -C "$tree" log -1 --format=%ct)
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
        last=$(git -C "$ROOT" log -1 --first-parent --format=%H -- '*/src/main/resources/db/migration/*')
        [ -n "$last" ] || cannot "no commit touched a migration"
        ref=$(git -C "$ROOT" rev-parse "$last^1")
    fi
    log "N - 1 is $ref, with the capture hint removed"
    printf '%s\n' "$ref" > "$ART/nminus1.ref"
    NMINUS1_TREE=$(dpath "$WORK/nminus1")
    git -C "$ROOT" worktree add --detach --force "$WORK/nminus1" "$ref" >> "$ART/build-nminus1.log" 2>&1 \
        || cannot "could not check out $ref"
    nminus1_patch "$WORK/nminus1"
    build_tree "$WORK/nminus1" nminus1
    git -C "$ROOT" worktree remove --force "$WORK/nminus1" >> "$ART/build-nminus1.log" 2>&1 || true
}

# ---------------------------------------------------------------------------------------------------
# The local CA and everything issued from it.
# ---------------------------------------------------------------------------------------------------
CA_DIR="$WORK/ca"

make_ca() {
    mkdir -p "$CA_DIR"
    openssl req -x509 -new -newkey rsa:2048 -nodes -days 2 -subj "/CN=finapp-rehearsal-local-ca" \
        -keyout "$CA_DIR/ca.key" -out "$CA_DIR/ca.crt" >/dev/null 2>&1 || cannot "the local CA could not be generated"
}

# issue <name> <SAN list, comma-separated DNS:...>: <name>.key and <name>.crt, signed by the local CA, one day.
issue() {
    openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$1" -keyout "$CA_DIR/$1.key" -out "$CA_DIR/$1.csr" >/dev/null 2>&1 \
        || cannot "a key for $1 could not be generated"
    printf 'subjectAltName=%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$2" > "$CA_DIR/$1.ext"
    openssl x509 -req -in "$CA_DIR/$1.csr" -CA "$CA_DIR/ca.crt" -CAkey "$CA_DIR/ca.key" -CAcreateserial -days 1 -extfile "$CA_DIR/$1.ext" \
        -out "$CA_DIR/$1.crt" >/dev/null 2>&1 || cannot "a certificate for $1 could not be issued"
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
    # wait_job <namespace> <name> <seconds> <artefact name>: complete, or the step fails with the Job's log kept.
    if ! kube -n "$1" wait --for=condition=complete "job/$2" --timeout="${3}s" >> "$ART/apply.log" 2>&1; then
        kube -n "$1" logs "job/$2" --all-containers > "$ART/$4.log" 2>&1 || true
        kube -n "$1" describe "job/$2" >> "$ART/$4.log" 2>&1 || true
        fail "the Job $2 did not complete: see $4.log"
    fi
    kube -n "$1" logs "job/$2" --all-containers > "$ART/$4.log" 2>&1 || true
}

# ---------------------------------------------------------------------------------------------------
# The sequence.
# ---------------------------------------------------------------------------------------------------
step build-images
if [ -z "${REHEARSAL_SKIP_BUILD:-}" ]; then
    build_tree "$ROOT" n
    build_nminus1
fi

step cluster
dock pull --quiet "$KIND_NODE_IMAGE" >/dev/null || cannot "could not pull the node image"
"$KIND_BIN" delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
"$KIND_BIN" create cluster --name "$CLUSTER" --image "$KIND_NODE_IMAGE" --config "$(dpath "$DIR/kind-cluster.yaml")" \
    --kubeconfig "$WORK/kubeconfig" --wait 180s >> "$ART/cluster.log" 2>&1 || cannot "the cluster did not come up: see cluster.log"
CLUSTER_UP=1
SERVER_VERSION=$(kube version -o json | sed -n 's/.*"gitVersion": *"\(v[^"]*\)".*/\1/p' | tail -1)
log "cluster up: Kubernetes $SERVER_VERSION, node image $KIND_NODE_VERSION, kind $("$KIND_BIN" version)"
printf '%s\n' "$SERVER_VERSION" > "$ART/server-version.txt"

step images-loaded
load_tagged "${JDK_IMAGE%%:*}" "$(printf '%s' "$JDK_IMAGE" | sed 's/^[^:]*:\([^@]*\)@.*/\1/')" "${JDK_IMAGE##*@}"
load_tagged postgres 18.6 "$(sed -n 's/.*postgres:18.6@\(sha256:[0-9a-f]*\).*/\1/p' "$ROOT/deploy/overlays/kind/postgres.yaml")"
load_tagged apache/kafka 4.3.1 "$(sed -n 's/.*apache\/kafka:4.3.1@\(sha256:[0-9a-f]*\).*/\1/p' "$ROOT/deploy/overlays/kind/kafka.yaml")"
load_tagged "$TRAEFIK_REPOSITORY" "$TRAEFIK_VERSION" "$TRAEFIK_DIGEST"
load_tagged "$CURL_REPOSITORY" "$CURL_VERSION" "$CURL_DIGEST"
IMAGE_APP_N=$(load_built finapp-app:rehearsal-n)
IMAGE_MIGRATOR_N=$(load_built finapp-migrator:rehearsal-n)
IMAGE_APP_NM1=$(load_built finapp-app:rehearsal-nminus1)
IMAGE_MIGRATOR_NM1=$(load_built finapp-migrator:rehearsal-nminus1)
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
SIM_STORE_PASSWORD=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
printf '%s' "$SIM_STORE_PASSWORD" > "$CA_DIR/simulator.password"
openssl pkcs12 -export -inkey "$CA_DIR/simulator.key" -in "$CA_DIR/simulator.crt" -certfile "$CA_DIR/ca.crt" \
    -passout "pass:$SIM_STORE_PASSWORD" -out "$CA_DIR/simulator.p12" >/dev/null 2>&1 || cannot "the simulator's keystore could not be made"
# Read without a password, so none is delivered: a trust store holds no secret.
TRUST_PASSWORD=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
jdk keytool -importcert -noprompt -alias local-ca -file ca/ca.crt -keystore ca/truststore.jks -storetype JKS -storepass "$TRUST_PASSWORD" \
    >> "$ART/tls.log" 2>&1 || cannot "the JVM trust store could not be made"
secret_of finapp finapp-kind-local-ca "ca.crt=$CA_DIR/ca.crt" "truststore.jks=$CA_DIR/truststore.jks"
secret_of finapp finapp-kind-postgres-tls "tls.crt=$CA_DIR/postgres.crt" "tls.key=$CA_DIR/postgres.key"
secret_of finapp finapp-kind-kafka-tls "keystore.pem=$CA_DIR/kafka-keystore.pem"
secret_of finapp-rehearsal finapp-simulator-tls "keystore.p12=$CA_DIR/simulator.p12" "password=$CA_DIR/simulator.password"

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
    kube -n finapp exec -i finapp-kafka-0 -c kafka -- "/opt/kafka/bin/$1" --bootstrap-server localhost:9094 "$@"
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
    kafka_admin kafka-acls.sh --add --allow-principal "User:$application_user" --operation Describe --operation Read --operation Write --topic "$topic" >> "$ART/kafka.log" 2>&1 \
        || cannot "could not grant topic $topic"
done
kafka_admin kafka-acls.sh --add --allow-principal "User:$application_user" --operation Describe --operation Read --group finapp. --resource-pattern-type prefixed >> "$ART/kafka.log" 2>&1 \
    || cannot "could not grant the consumer groups"
log "$(printf '%s\n' "$topics" | wc -l) topics created with $partitions partitions and the application's ACLs"

step bootstrap-roles
sed "s#MIGRATOR_IMAGE#$IMAGE_MIGRATOR_NM1#" "$DIR/bootstrap-job.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the bootstrap Job"
wait_job finapp finapp-role-bootstrap 300 role-bootstrap
log "bootstrap said: $(grep -c . "$ART/role-bootstrap.log") lines; $(tr '\n' ';' < "$ART/role-bootstrap.log" | cut -c1-400)"

step migrator-nminus1
apply_files "$A/Job.finapp-migrator.yaml"
wait_job finapp finapp-migrator 900 migrator-nminus1
log "migrator (N-1): $(tail -1 "$ART/migrator-nminus1.log" | cut -c1-200)"

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
step end
finish
