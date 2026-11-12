#!/bin/sh
# The telemetry pipeline's check (P15-TSK-023, ADR-0124 decision 1, THREAT_MODEL.md T4, T15, T46).
#
# TWO HALVES. First the manifests, as manifest-check.sh and edge-check.sh do: the application's kind overlay, the namespaces and the pipeline's
# kind overlay rendered as one stream, kubeconform -strict, the policies of manifest-policy/observability.rego (which re-read finapp.rego's
# hardening rules for the collector and the backends), and then every policy again over a plant it must refuse, for the reason it must give.
# TelemetryPipelineManifestsTest requires the plants to cover every rule id in observability.rego.
#
# THEN THE SECOND NET, THROUGH THE REAL COLLECTOR. The allow-list and the scrub are statements the collector evaluates, so this runs the pinned
# image with the committed configuration unchanged, posts spans and log records planted with needles - a card number with and without
# separators, an amount in Money's string form, a party's identifier, a URL with a query, an exception's message, a host - and reads what the
# collector exported, through a second pinned collector standing in for Tempo and Loki and printing everything it receives. No needle may come
# out, and every allowed identifier must (a pipeline that dropped everything would pass the first half alone). Then THE PROBE, in every run: the
# same configuration with the scrubbing processor removed from its pipelines, the same plants, and the needles must come out - a net that cannot
# be seen to catch anything is no evidence that it does.
#
# WHAT IT NEVER DOES. It talks to no cluster and holds no credential. Nothing here can change the tree: the repository is mounted read-only, and
# the scratch volume, the network and the containers are this run's and removed at exit. The network is used for the pinned images and the schemas.
#
# KUSTOMIZE_IMAGE, KUBECONFORM_IMAGE and CONFTEST_IMAGE override an image for a machine whose TLS is intercepted (OPERATIONS_RUNBOOK.md section
# 11.13); CI never sets them.
#
# Exit status: 0 every check green, every plant refused and the probe red; 1 otherwise; 2 no Docker.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)

# Git Bash on Windows otherwise rewrites /repo, /scratch and /v1/... into host paths. Unset and harmless on Linux, where CI runs.
export MSYS_NO_PATHCONV=1

# ---------------------------------------------------------------------------------------------------
# Inside the Kustomize container: /repo/deploy read-only, /scratch this run's volume.
# ---------------------------------------------------------------------------------------------------
PLANTS=/scratch/obs-plants

render_stream() {
    # render_stream <deploy dir> <output file>: the application's kind overlay, the namespaces and the pipeline's kind overlay, as one stream.
    kustomize build "$1/overlays/kind" > "$2"
    printf '%s\n' '---' >> "$2"
    kustomize build "$1/namespaces" >> "$2"
    printf '%s\n' '---' >> "$2"
    kustomize build "$1/observability/overlays/kind" >> "$2"
}

obs_plant() {
    # obs_plant <name>: a fresh copy of deploy/.
    rm -rf "${PLANTS:?}/$1"
    mkdir -p "$PLANTS/$1"
    cp -R /repo/deploy "$PLANTS/$1/deploy"
}

obs_mutate() {
    # obs_mutate <plant> <file under deploy/> <sed program>: fails if the mutation no longer applies.
    sed -i "$3" "$PLANTS/$1/deploy/$2"
    if cmp -s "/repo/deploy/$2" "$PLANTS/$1/deploy/$2"; then
        echo "!!   plant $1 was not planted: $2 is unchanged, so its mutation no longer applies" >&2
        exit 1
    fi
}

obs_plant_on() {
    obs_plant "$1"
    obs_mutate "$1" "$2" "$3"
}

# Kubernetes' own field, named once: this line's shape is not read as a credential by CommittedConfigurationHoldsNoSecretTest.
SWITCH=automountServiceAccountToken
POLICY=observability/base/networkpolicy.yaml
WORKLOAD=observability/base/collector-workload.yaml
CONFIG=observability/base/collector.yaml
APP=base/deployment.yaml
BACKENDS=observability/overlays/kind/networkpolicy-backends.yaml
PEOPLE=observability/base/rbac-humans.yaml

plant_all() {
    # The general hardening, reused from finapp.rego, on the collector.
    obs_plant_on obs-mounted-account "$WORKLOAD" "1,/$SWITCH: false/s/$SWITCH: false/$SWITCH: true/"
    obs_plant_on obs-image-tag "$WORKLOAD" 's|opentelemetry-collector-contrib@sha256:[0-9a-f]*|opentelemetry-collector-contrib:latest|'
    obs_plant_on obs-root "$WORKLOAD" 's/runAsNonRoot: true/runAsNonRoot: false/'
    obs_plant_on obs-no-probe "$WORKLOAD" '/^          livenessProbe:$/,/^            failureThreshold: 3$/d'

    # The namespace's default-deny.
    obs_plant_on obs-no-default-deny "$POLICY" '1,/^  podSelector: {}$/s/^  podSelector: {}$/  podSelector:\n    matchLabels:\n      finapp\/role: nobody/'

    # The collector as P15-TSK-010 named it.
    obs_plant_on obs-one-collector "$WORKLOAD" 's/^  replicas: 2$/  replicas: 1/'
    obs_plant_on obs-no-role "$WORKLOAD" '1,/^        finapp\/role: telemetry-collector$/{/^        finapp\/role: telemetry-collector$/d}'
    obs_plant_on obs-otlp-from-edge "$POLICY" '1,/kubernetes.io\/metadata.name: finapp$/s/kubernetes.io\/metadata.name: finapp$/kubernetes.io\/metadata.name: finapp-edge/'
    obs_plant_on obs-ingress-open "$POLICY" 's/^  ingress:$/  ingress:\n    - ports:\n        - protocol: TCP\n          port: 13133/'

    # Nothing in the namespace reaches the data tier or out.
    obs_plant_on obs-egress-database "$POLICY" '1,/^  egress:$/s/^  egress:$/  egress:\n    - to:\n        - podSelector:\n            matchLabels:\n              app.kubernetes.io\/name: finapp-postgres\n      ports:\n        - protocol: TCP\n          port: 5432/'
    obs_plant_on obs-egress-anywhere "$POLICY" '1,/^  egress:$/s/^  egress:$/  egress:\n    - ports:\n        - protocol: TCP\n          port: 443/'
    obs_plant_on obs-egress-block "$POLICY" '1,/^  egress:$/s/^  egress:$/  egress:\n    - to:\n        - ipBlock:\n            cidr: 0.0.0.0\/0\n      ports:\n        - protocol: TCP\n          port: 443/'

    # The scraper's flow.
    obs_plant_on obs-no-scraper "$POLICY" 's/^      finapp\/role: metrics-scraper$/      finapp\/role: nobody/'

    # The alerts' flows (P15-TSK-032), each named in observability.rego: the scraper to cert-manager's webhook port, and to every pod of
    # cert-manager's namespace; Loki's ingest open to every namespace; Alertmanager admitting the collectors.
    obs_plant_on obs-scraper-to-webhook "$BACKENDS" 's/port: 9402/port: 10250/'
    obs_plant_on obs-scraper-whole-cert-manager "$BACKENDS" '/app.kubernetes.io\/component: controller/d'
    obs_plant_on obs-logs-from-anywhere "$BACKENDS" '/name: finapp-log-store/,/name: finapp-scraper-to-backends/s/^  ingress:$/  ingress:\n    - from:\n        - namespaceSelector: {}\n      ports:\n        - protocol: TCP\n          port: 3100/'
    obs_plant_on obs-alerts-from-collector "$BACKENDS" '/name: finapp-alert-router/,$s/finapp\/role: metrics-scraper/finapp\/role: telemetry-collector/'
    # The scraper reachable at all (the review's MINOR 7): on kind its lifecycle endpoints answer anyone who reaches its port.
    obs_plant_on obs-scraper-reachable "$POLICY" '/name: finapp-metrics-scraper/,/^---$/s/^  egress:$/  ingress:\n    - from:\n        - podSelector:\n            matchLabels:\n              finapp\/role: telemetry-collector\n      ports:\n        - protocol: TCP\n          port: 9090\n  egress:/'

    # A person's roles here (P15-TSK-032, item 42 (k)), held by finapp.rego's rules through this stream and by observability.rego: a person's
    # binding in a third namespace (through the namespaces root, which no transformer rewrites), the read-only role reading Secrets, and
    # break-glass deleting a pod or reaching inside one.
    obs_plant obs-person-third-namespace
    { printf -- '---\n'; cat; } >> "$PLANTS/obs-person-third-namespace/deploy/namespaces/namespaces.yaml" <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: finapp-read-only
  namespace: finapp-edge
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: finapp-read-only
  namespace: finapp-edge
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: finapp-read-only
subjects:
  - apiGroup: rbac.authorization.k8s.io
    kind: Group
    name: finapp-read-only
YAML
    obs_plant_on obs-read-only-secrets "$PEOPLE" 's/resources: \[pods, pods\/log, events\]/resources: [pods, pods\/log, events, secrets]/'
    obs_plant_on obs-break-glass-delete "$PEOPLE" '/^  name: finapp-break-glass$/,/^rules:$/s/^rules:$/rules:\n  - apiGroups: [""]\n    resources: [pods]\n    verbs: [delete]/'
    obs_plant_on obs-break-glass-exec "$PEOPLE" '/^  name: finapp-break-glass$/,/^rules:$/s/^rules:$/rules:\n  - apiGroups: [""]\n    resources: [pods\/exec]\n    verbs: [create]/'
    # Exactly those reads (the review of item 42 (k)): an overlay's patch giving read-only a ConfigMap, or break-glass a list of pods, passes
    # every rule that refuses what no person may hold, and must be refused here.
    obs_plant obs-read-only-configmaps
    cat >> "$PLANTS/obs-read-only-configmaps/deploy/observability/overlays/kind/kustomization.yaml" <<'YAML'
patches:
  - target:
      kind: Role
      name: finapp-read-only
    patch: |-
      - op: add
        path: /rules/-
        value:
          apiGroups: [""]
          resources: [configmaps]
          verbs: [get, list, watch]
YAML
    obs_plant obs-break-glass-reads
    cat >> "$PLANTS/obs-break-glass-reads/deploy/observability/overlays/kind/kustomization.yaml" <<'YAML'
patches:
  - target:
      kind: Role
      name: finapp-break-glass
    patch: |-
      - op: add
        path: /rules/-
        value:
          apiGroups: [""]
          resources: [pods]
          verbs: [list]
YAML
    # Break-glass reached through a ClusterRole of its name, bound here to its group: no Role of this namespace carries the delete, so only
    # the group-keyed reading of [obs-break-glass-evictions] sees it.
    obs_plant obs-break-glass-cluster-role
    { printf -- '---\n'; cat; } >> "$PLANTS/obs-break-glass-cluster-role/deploy/$PEOPLE" <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: finapp-break-glass
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [delete]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: finapp-break-glass-cluster
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: finapp-break-glass
subjects:
  - apiGroup: rbac.authorization.k8s.io
    kind: Group
    name: finapp-break-glass
YAML

    # The application's endpoint: anything but the cluster's own collector is refused, in the base and in an overlay of its own.
    obs_plant_on obs-endpoint-external "$APP" 's|http://finapp-collector.finapp-observability.svc.cluster.local:4318/v1/traces|https://otlp.example.invalid/v1/traces|'
    obs_plant_on obs-endpoint-staging overlays/staging/kustomization.yaml 's/^patches:$/patches:\n  - target:\n      kind: Deployment\n      name: finapp-app\n    patch: |-\n      apiVersion: apps\/v1\n      kind: Deployment\n      metadata:\n        name: finapp-app\n      spec:\n        template:\n          spec:\n            containers:\n              - name: app\n                env:\n                  - name: FINAPP_TELEMETRY_TRACESENDPOINT\n                    value: https:\/\/otlp.example.invalid\/v1\/traces/'
    # The peers' tier scrubs nothing: its 4317 from any namespace is refused, and so is an ingress rule naming no port.
    obs_plant_on obs-peers-from-anywhere "$POLICY" '1,/^  ingress:$/s/^  ingress:$/  ingress:\n    - from:\n        - namespaceSelector: {}\n      ports:\n        - protocol: TCP\n          port: 4317/'
    obs_plant_on obs-ingress-portless "$POLICY" '1,/^  ingress:$/s/^  ingress:$/  ingress:\n    - from:\n        - namespaceSelector: {}/'
    # An egress rule naming no port is every port, the database's among them.
    obs_plant_on obs-egress-portless "$POLICY" '1,/^  egress:$/s/^  egress:$/  egress:\n    - to:\n        - namespaceSelector:\n            matchLabels:\n              kubernetes.io\/metadata.name: finapp\n          podSelector:\n            matchLabels:\n              app.kubernetes.io\/name: finapp-postgres/'

    # The second net taken out of a pipeline.
    obs_plant_on obs-scrub-removed "$CONFIG" '1,/processors: \[memory_limiter, transform\/scrub, resource\/environment, batch\]/s/processors: \[memory_limiter, transform\/scrub, resource\/environment, batch\]/processors: [memory_limiter, resource\/environment, batch]/'

    # The platform's own lines (P16-TSK-003): their receiver's senders, their own net, and the cluster watcher's reach and token.
    obs_plant_on obs-platform-from-app "$POLICY" '/^    # The platform.s own components on their own receiver/,/^          port: 4319$/s/^              finapp\/role: ingress-controller$/              finapp\/role: ingress-controller\n        - namespaceSelector:\n            matchLabels:\n              kubernetes.io\/metadata.name: finapp\n          podSelector:\n            matchLabels:\n              app.kubernetes.io\/name: finapp-app/'
    obs_plant_on obs-platform-any-edge-pod "$POLICY" '/^    # The platform.s own components on their own receiver/,/^          port: 4319$/{/^          podSelector:$/,/^              finapp\/role: ingress-controller$/d}'
    obs_plant_on obs-platform-scrub-removed "$CONFIG" 's/processors: \[memory_limiter, transform\/scrub-platform, resource\/environment, batch\]/processors: [memory_limiter, resource\/environment, batch]/'
    obs_plant_on obs-watch-egress-to-app "$POLICY" '/^  name: finapp-cluster-watch$/,$s/^  egress:$/  egress:\n    - to:\n        - namespaceSelector:\n            matchLabels:\n              kubernetes.io\/metadata.name: finapp\n          podSelector:\n            matchLabels:\n              app.kubernetes.io\/name: finapp-app\n      ports:\n        - protocol: TCP\n          port: 8080/'
    obs_plant_on obs-watch-reachable "$POLICY" '/^  name: finapp-cluster-watch$/,$s/^              finapp\/role: metrics-scraper$/              finapp\/role: telemetry-collector/'
    obs_plant_on obs-token-for-another-account observability/base/cluster-watch-workload.yaml 's/serviceAccountName: finapp-cluster-watch/serviceAccountName: finapp-collector/'
}

in_container() {
    case $1 in
        render)
            mkdir -p /scratch/rendered
            render_stream /repo/deploy /scratch/rendered/kind.yaml
            printf 'overlay: kind\n' > /scratch/rendered/kind.data.yaml
            # The pipeline's rules hold where it is rendered: the kind stream must render it, or they would hold nothing.
            grep -q '^  namespace: finapp-observability$' /scratch/rendered/kind.yaml || { echo "the kind stream renders no finapp-observability" >&2; exit 1; }
            # The alerts on kind (P15-TSK-032): every rule file in the scraper's ConfigMap and Alertmanager beside it, or a rehearsal reads no alert.
            grep -q '^  name: finapp-prometheus-rules-' /scratch/rendered/kind.yaml || { echo "the kind stream renders no rules ConfigMap" >&2; exit 1; }
            grep -q '^  name: finapp-alertmanager$' /scratch/rendered/kind.yaml || { echo "the kind stream renders no Alertmanager" >&2; exit 1; }
            # Staging's and production's application overlays, alone: held to the endpoint rule, the pipeline having no overlay there.
            for overlay in staging production; do
                kustomize build "/repo/deploy/overlays/$overlay" > "/scratch/rendered/$overlay.yaml"
                printf 'overlay: %s\n' "$overlay" > "/scratch/rendered/$overlay.data.yaml"
            done
            ;;
        plants)
            plant_all
            for directory in "$PLANTS"/*; do
                case $directory in
                    */obs-endpoint-staging)
                        kustomize build "$directory/deploy/overlays/staging" > "$directory/rendered.yaml"
                        printf 'overlay: staging\n' > "$directory/overlay.yaml"
                        ;;
                    *)
                        render_stream "$directory/deploy" "$directory/rendered.yaml"
                        printf 'overlay: kind\n' > "$directory/overlay.yaml"
                        ;;
                esac
            done
            ;;
        *)
            echo "unknown step: $1" >&2
            exit 2
            ;;
    esac
}

if [ "${1:-}" = "--in-container" ]; then
    in_container "$2"
    exit 0
fi

# ---------------------------------------------------------------------------------------------------
# On the host (a laptop or CI's runner).
# ---------------------------------------------------------------------------------------------------
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

KUSTOMIZE_IMAGE="${KUSTOMIZE_IMAGE:-${KUSTOMIZE_REPOSITORY}@${KUSTOMIZE_DIGEST}}"
KUBECONFORM_IMAGE="${KUBECONFORM_IMAGE:-${KUBECONFORM_REPOSITORY}@${KUBECONFORM_DIGEST}}"
CONFTEST_IMAGE="${CONFTEST_IMAGE:-${CONFTEST_REPOSITORY}@${CONFTEST_DIGEST}}"
COLLECTOR_IMAGE="${COLLECTOR_REPOSITORY}@${COLLECTOR_DIGEST}"
CURL_IMAGE="${CURL_REPOSITORY}@${CURL_DIGEST}"

SCHEMAS="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/${KUBERNETES_SCHEMAS_COMMIT}/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
CRD_SCHEMAS="https://raw.githubusercontent.com/datreeio/CRDs-catalog/${CRD_SCHEMAS_COMMIT}/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

docker info >/dev/null 2>&1 || { echo "Docker is not available: every check runs in a pinned image." >&2; exit 2; }

RUN="finapp-telemetry-check-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
VOLUME="$RUN"
NETWORK="$RUN"
CONFIGS=$(mktemp -d)

# A path as Docker on this host takes it: Git Bash's /tmp is not a path Docker Desktop can mount. Unchanged on Linux, where CI runs.
dpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

status=0

cleanup() {
    docker rm -f "$RUN-sink" "$RUN-collector" "$RUN-probe" >/dev/null 2>&1 || true
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
    docker volume rm -f "$VOLUME" >/dev/null 2>&1 || true
    rm -rf "$CONFIGS"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

docker volume create "$VOLUME" >/dev/null

check() {
    label=$1
    shift
    echo "==   $label"
    if "$@"; then
        echo "ok   $label"
    else
        echo "!!   $label FAILED"
        status=1
    fi
}

in_kustomize() {
    docker run --rm --entrypoint /bin/sh \
        -v "$ROOT/deploy:/repo/deploy:ro" -v "$DIR:/repo/infra:ro" -v "$VOLUME:/scratch" \
        "$KUSTOMIZE_IMAGE" /repo/infra/scripts/telemetry-check.sh --in-container "$1"
}

kubeconform_on() {
    docker run --rm -v "$VOLUME:/scratch" "$KUBECONFORM_IMAGE" \
        -strict -summary -cache /scratch \
        -kubernetes-version "$KUBERNETES_VERSION" -schema-location "$SCHEMAS" -schema-location "$CRD_SCHEMAS" "$1"
}

conftest_on() {
    docker run --rm \
        -v "$DIR/scripts/manifest-policy:/policy:ro" \
        -v "$ROOT/app/src/main/resources/application.yaml:/application/application.yaml:ro" \
        -v "$VOLUME:/scratch:ro" \
        "$CONFTEST_IMAGE" test --no-color --combine --all-namespaces=false --namespace observability \
        --policy /policy/finapp.rego --policy /policy/observability.rego \
        --data /application/application.yaml --data /policy/shutdown-budget.yaml --data "$2" "$1"
}

obs_refused() {
    # obs_refused <plant> <rule id>: the plant's rendered stream must fail the policies and name the rule.
    if output=$(conftest_on "/scratch/obs-plants/$1/rendered.yaml" "/scratch/obs-plants/$1/overlay.yaml" 2>&1); then
        printf '%s\n' "$output"
        echo "!!   plant $1 was NOT refused"
        status=1
    elif printf '%s\n' "$output" | grep -qF -- "[$2]"; then
        echo "ok   plant $1 refused: $(printf '%s\n' "$output" | grep -F -m 1 -- "[$2]" | sed 's/^[[:space:]]*//' | cut -c1-150)"
    else
        printf '%s\n' "$output"
        echo "!!   plant $1 was refused, but not for its reason: the output never says [$2]"
        status=1
    fi
}

check "the application's, the namespaces' and the pipeline's kind overlays render as one stream" in_kustomize render
check "the plants are planted and rendered" in_kustomize plants
check "kubeconform kind" kubeconform_on /scratch/rendered/kind.yaml
check "the pipeline's policies on kind" conftest_on /scratch/rendered/kind.yaml /scratch/rendered/kind.data.yaml
check "the application's one telemetry endpoint on staging" conftest_on /scratch/rendered/staging.yaml /scratch/rendered/staging.data.yaml
check "the application's one telemetry endpoint on production" conftest_on /scratch/rendered/production.yaml /scratch/rendered/production.data.yaml

obs_refused obs-mounted-account security-token
obs_refused obs-image-tag images-digest
obs_refused obs-root security-non-root
obs_refused obs-no-probe probes-present
obs_refused obs-no-default-deny obs-default-deny
obs_refused obs-one-collector obs-collector
obs_refused obs-no-role obs-collector
obs_refused obs-otlp-from-edge obs-collector-ingress
obs_refused obs-ingress-open obs-collector-ingress
obs_refused obs-egress-database obs-egress
obs_refused obs-egress-anywhere obs-egress
obs_refused obs-egress-block obs-egress
obs_refused obs-no-scraper obs-scraper
obs_refused obs-scraper-to-webhook obs-scraper-egress
obs_refused obs-scraper-whole-cert-manager obs-scraper-egress
obs_refused obs-logs-from-anywhere obs-store-ingress
obs_refused obs-alerts-from-collector obs-store-ingress
obs_refused obs-scraper-reachable obs-store-ingress
obs_refused obs-person-third-namespace rbac-human-namespace
obs_refused obs-read-only-secrets rbac-human-secrets
obs_refused obs-break-glass-delete obs-break-glass-evictions
obs_refused obs-break-glass-exec rbac-human-subresource
obs_refused obs-read-only-configmaps obs-person-reads
obs_refused obs-break-glass-reads obs-person-reads
obs_refused obs-break-glass-cluster-role obs-break-glass-evictions
obs_refused obs-endpoint-external obs-endpoint
obs_refused obs-endpoint-staging obs-endpoint
obs_refused obs-peers-from-anywhere obs-collector-peers
obs_refused obs-ingress-portless obs-collector-ingress
obs_refused obs-egress-portless obs-egress
obs_refused obs-scrub-removed obs-scrub
obs_refused obs-platform-from-app obs-platform-ingress
obs_refused obs-platform-any-edge-pod obs-platform-ingress
obs_refused obs-platform-scrub-removed obs-scrub
obs_refused obs-watch-egress-to-app obs-cluster-watch-egress
obs_refused obs-watch-reachable obs-store-ingress
obs_refused obs-token-for-another-account security-token

# ---------------------------------------------------------------------------------------------------
# The second net, through the real collector.
# ---------------------------------------------------------------------------------------------------
# The needles. A card number with and without its separators, an amount as Money prints one, a party's identifier, and three that only an
# allow-list drops: a query string, a host, and the stack of an exception.
PAN=4111111111111111
PAN_SPACED="4111 1111 1111 1111"
AMOUNT="7319.47 EUR"
# The scrub's edges: an amount right after a JSON escape (the ECS line escapes a message's newline as a backslash and a letter), a
# dot-separated card number after another, and Diners' 4-6-4. A run of twenty digits is no card number and must come through unchanged.
AMOUNT_ESCAPED="4821.37 EUR"
PAN_DOTTED="4111.1111.1111.1111"
DINERS="3056 930902 5904"
RUN20=98765432109876543210
PARTY=0b6f3c1e-8d2a-4e57-9a31-5c7d2e8f4a19
HOST=needle-host.invalid
QUERY="pan=$PAN"
# What must come through: the flow's ids, a payment's identifier, a route template.
PAYMENT=7a9e2c40-31d5-4f0b-8e66-2b1c9d0e5f73

# The stand-in for Tempo and Loki: OTLP in on both ports, everything printed, nothing kept.
cat > "$CONFIGS/sink.yaml" <<'YAML'
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
        logs_url_path: /otlp/v1/logs
exporters:
  debug:
    verbosity: detailed
# The debug exporter prints at the collector's info level: left at its default so the sink shows what it received.
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [debug]
    logs:
      receivers: [otlp]
      exporters: [debug]
YAML

cp "$ROOT/deploy/observability/base/collector.yaml" "$CONFIGS/collector.yaml"
# The probe's configuration: the committed one with both scrubbing processors taken out of every pipeline, the application's and the
# platform's own (P16-TSK-003), nothing else changed.
sed -e 's/transform\/scrub, //' -e 's/transform\/scrub-platform, //' "$ROOT/deploy/observability/base/collector.yaml" > "$CONFIGS/probe.yaml"
if cmp -s "$CONFIGS/collector.yaml" "$CONFIGS/probe.yaml"; then
    echo "!!   the probe was not planted: no pipeline names transform/scrub"
    status=1
fi
if grep -q 'transform/scrub-platform, ' "$CONFIGS/probe.yaml" || ! grep -q 'transform/scrub-platform, ' "$CONFIGS/collector.yaml"; then
    echo "!!   the probe was not planted: the platform's pipeline names no transform/scrub-platform, or still does in the probe"
    status=1
fi

span_body() {
    # span_body <correlation> <trace id>
    now=$(date +%s)000000000
    cat <<JSON
{"resourceSpans":[{"resource":{"attributes":[
  {"key":"service.name","value":{"stringValue":"finapp"}},
  {"key":"host.name","value":{"stringValue":"$HOST"}}]},
 "scopeSpans":[{"scope":{"name":"planted"},"spans":[{
  "traceId":"$2","spanId":"0123456789abcdef","name":"http post /v1/payments","kind":2,
  "startTimeUnixNano":"$now","endTimeUnixNano":"$now",
  "attributes":[
   {"key":"finapp.correlation_id","value":{"stringValue":"$1"}},
   {"key":"method","value":{"stringValue":"POST"}},
   {"key":"uri","value":{"stringValue":"/v1/payments/{id}"}},
   {"key":"payment.id","value":{"stringValue":"$PAYMENT"}},
   {"key":"outcome","value":{"stringValue":"declined $PAN_SPACED for $AMOUNT"}},
   {"key":"card.number","value":{"stringValue":"$PAN"}},
   {"key":"amount","value":{"stringValue":"$AMOUNT"}},
   {"key":"party.id","value":{"stringValue":"$PARTY"}},
   {"key":"http.url","value":{"stringValue":"https://public.example.invalid/v1/payments?$QUERY"}}],
  "events":[{"timeUnixNano":"$now","name":"exception","attributes":[
   {"key":"exception.type","value":{"stringValue":"java.lang.IllegalStateException"}},
   {"key":"exception.message","value":{"stringValue":"party $PARTY card $PAN"}},
   {"key":"exception.stacktrace","value":{"stringValue":"at Planted.capture($AMOUNT)"}}]}],
  "status":{"code":2,"message":"declined $AMOUNT for $PARTY"}}]}]}]}
JSON
}

log_body() {
    # log_body <correlation>
    now=$(date +%s)000000000
    cat <<JSON
{"resourceLogs":[{"resource":{"attributes":[
  {"key":"service.name","value":{"stringValue":"finapp"}},
  {"key":"host.name","value":{"stringValue":"$HOST"}}]},
 "scopeLogs":[{"scope":{"name":"finapp"},"logRecords":[{
  "timeUnixNano":"$now","severityNumber":9,"severityText":"INFO",
  "body":{"stringValue":"{\\"message\\":\\"captured card $PAN_SPACED and $PAN for $AMOUNT\\\\n$AMOUNT_ESCAPED\\\\t$PAN_DOTTED or $DINERS, reference $RUN20\\",\\"correlationId\\":\\"$1\\"}"},
  "attributes":[
   {"key":"finapp.correlation_id","value":{"stringValue":"$1"}},
   {"key":"party.id","value":{"stringValue":"$PARTY"}},
   {"key":"amount","value":{"stringValue":"$AMOUNT"}}]}]}]}]}
JSON
}

start_collector() {
    # start_collector <container> <config file>: the pinned collector, the committed shape, on this run's network.
    docker run -d --name "$1" --network "$NETWORK" --network-alias "$1" \
        -v "$(dpath "$CONFIGS"):/cfg:ro" \
        -e POD_IP=0.0.0.0 -e COLLECTOR_PEERS="$1" -e TEMPO_ENDPOINT="$RUN-sink:4317" \
        -e LOKI_ENDPOINT="http://$RUN-sink:4318/otlp" -e FINAPP_ENVIRONMENT=check -e TAIL_DECISION_WAIT=2s \
        "$COLLECTOR_IMAGE" --config "/cfg/$2" >/dev/null
}

post() {
    # post <container> <path> <body>
    docker run --rm --network "$NETWORK" "$CURL_IMAGE" -sS -o /dev/null -w '%{http_code}' \
        -H 'Content-Type: application/json' --data-binary "$3" "http://$1:4318$2"
}

exported() {
    # Everything the sink printed: what reached the backends' stand-in.
    docker logs "$RUN-sink" 2>&1
}

sweep() {
    # sweep <collector> <correlation> <trace id>: plant through the collector, then read the sink.
    traces=$(post "$1" /v1/traces "$(span_body "$2" "$3")" || true)
    logs=$(post "$1" /v1/logs "$(log_body "$2")" || true)
    if [ "$traces" != 200 ] || [ "$logs" != 200 ]; then
        echo "!!   $1 did not take the plants (traces $traces, logs $logs)"
        return 1
    fi
    # The tail policy's decision wait, the load-balancing hop and the batch: well inside fifteen seconds.
    deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        output=$(exported "$2")
        if printf '%s' "$output" | grep -qF -- "$3" && printf '%s' "$output" | grep -qF -- "captured card"; then
            return 0
        fi
        sleep 1
    done
    echo "!!   nothing of $2 reached the sink in 30 s; the collector and the sink said:"
    docker logs "$1" 2>&1 | grep -v deprecated | tail -15
    exported | tail -15
    return 1
}

needles_in() {
    # needles_in <text>: prints each needle found.
    for needle in "$PAN" "$PAN_SPACED" "7319.47" "4821.37" "$PAN_DOTTED" "$DINERS" "$PARTY" "$HOST" "$QUERY"; do
        if printf '%s' "$1" | grep -qF -- "$needle"; then
            printf '%s\n' "$needle"
        fi
    done
}

docker network create "$NETWORK" >/dev/null
docker run -d --name "$RUN-sink" --network "$NETWORK" --network-alias "$RUN-sink" -v "$(dpath "$CONFIGS"):/cfg:ro" \
    "$COLLECTOR_IMAGE" --config /cfg/sink.yaml >/dev/null
start_collector "$RUN-collector" collector.yaml
start_collector "$RUN-probe" probe.yaml
sleep 5

TRACE=5e0f3c2a9b8d7e6f1a2b3c4d5e6f7a8b
echo "==   the committed collector: the planted needles go in, none comes out, every allowed identifier does"
if sweep "$RUN-collector" "check-committed" "$TRACE"; then
    output=$(exported)
    found=$(needles_in "$output")
    if [ -n "$found" ]; then
        printf '%s\n' "$output" | grep -nF -e "$PAN" -e "7319.47" -e "$PARTY" -e "$HOST" -e "pan=" | head -20
        echo "!!   needles exported by the committed collector: $(printf '%s' "$found" | tr '\n' ' ')"
        status=1
    else
        missing=""
        for control in check-committed "$PAYMENT" "/v1/payments/{id}" "[scrubbed]" "exception.type" "captured card" "deployment.environment.name" "$RUN20"; do
            printf '%s' "$output" | grep -qF -- "$control" || missing="$missing $control"
        done
        if [ -n "$missing" ]; then
            echo "!!   the committed collector dropped what it must keep:$missing"
            status=1
        else
            echo "ok   no needle exported; the correlation, the payment's identifier, the route template and the scrub marks are"
        fi
    fi
else
    status=1
fi

PROBE_TRACE=6a1b2c3d4e5f60718293a4b5c6d7e8f9
echo "==   THE PROBE: the scrubbing processor removed, the same plants, and the needles must come out"
if sweep "$RUN-probe" "check-probe" "$PROBE_TRACE"; then
    # The sink held no needle after the committed collector's run, so whatever needle it holds now came through the probe.
    found=$(needles_in "$(exported)")
    if printf '%s' "$found" | grep -qF -- "$PAN" && printf '%s' "$found" | grep -qF -- "7319.47" && printf '%s' "$found" | grep -qF -- "$PARTY"; then
        echo "ok   the probe is red: without the scrub the collector exported $(printf '%s' "$found" | tr '\n' ' ')"
    else
        echo "!!   the probe stayed green: with the scrub removed the needles still did not come out, so the check cannot see a leak"
        status=1
    fi
else
    status=1
fi

# ---------------------------------------------------------------------------------------------------
# The platform's own lines (P16-TSK-003): the same collectors, the platform receiver on 4319.
# ---------------------------------------------------------------------------------------------------
# An edge's access line as the pinned proxy sends it (the whole JSON line as the body, every field an attribute, its host and process in the
# resource), routed and unrouted, and a migrator Job's record as the cluster watcher sends it. The needles are what the platform's net drops
# by name - the client's address, the host the client named, the backend's address, a user name, an unrouted path (the caller's own text), a
# query string, the process's arguments, a node - and what it scrubs by shape, a card number and an amount in a migrator's line.
CLIENT=198.51.100.77
EDGE_HOST=needle-edge-host.invalid
BACKEND=needle-backend.invalid
EDGE_USER=needle-edge-user
UNROUTED=needle-unrouted-path
QUERY_NEEDLE=needle-in-a-query
ARGS=needle-process-argument
NODE=needle-node-name
MIGRATOR_AMOUNT="6234.18 EUR"
ROUTER=public-machine@file
EDGE_POD=finapp-edge-public-7c9d8b6f5-x2k4q

access_line() {
    # access_line <path> <router or empty> <status>: the JSON line the proxy writes, escaped for an OTLP string value.
    router_field=""
    [ -n "$2" ] && router_field=",\\\"RouterName\\\":\\\"$2\\\",\\\"ServiceName\\\":\\\"finapp-app@file\\\""
    printf '{\\"ClientAddr\\":\\"%s:51152\\",\\"ClientHost\\":\\"%s\\",\\"ClientUsername\\":\\"%s\\",\\"DownstreamStatus\\":%s,\\"Duration\\":3430534,\\"RequestHost\\":\\"%s\\",\\"RequestMethod\\":\\"GET\\",\\"RequestPath\\":\\"%s\\"%s,\\"ServiceURL\\":\\"http://%s:8080\\",\\"entryPointName\\":\\"websecure\\"}' \
        "$CLIENT" "$CLIENT" "$EDGE_USER" "$3" "$EDGE_HOST" "$1" "$router_field" "$BACKEND"
}

access_record() {
    # access_record <path> <router or empty> <status>
    now=$(date +%s)000000000
    router_attributes=""
    [ -n "$2" ] && router_attributes=",{\"key\":\"RouterName\",\"value\":{\"stringValue\":\"$2\"}},{\"key\":\"ServiceName\",\"value\":{\"stringValue\":\"finapp-app@file\"}},{\"key\":\"ServiceURL\",\"value\":{\"stringValue\":\"http://$BACKEND:8080\"}}"
    cat <<JSON
{"timeUnixNano":"$now","severityNumber":9,"severityText":"info","body":{"stringValue":"$(access_line "$1" "$2" "$3")"},
 "attributes":[
  {"key":"ClientHost","value":{"stringValue":"$CLIENT"}},
  {"key":"ClientAddr","value":{"stringValue":"$CLIENT:51152"}},
  {"key":"ClientUsername","value":{"stringValue":"$EDGE_USER"}},
  {"key":"RequestHost","value":{"stringValue":"$EDGE_HOST"}},
  {"key":"RequestMethod","value":{"stringValue":"GET"}},
  {"key":"RequestPath","value":{"stringValue":"$1"}},
  {"key":"DownstreamStatus","value":{"intValue":"$3"}},
  {"key":"Duration","value":{"intValue":"3430534"}},
  {"key":"entryPointName","value":{"stringValue":"websecure"}}$router_attributes]}
JSON
}

platform_body() {
    now=$(date +%s)000000000
    cat <<JSON
{"resourceLogs":[
 {"resource":{"attributes":[
   {"key":"service.name","value":{"stringValue":"finapp-edge"}},
   {"key":"host.name","value":{"stringValue":"$EDGE_POD"}},
   {"key":"process.command_args","value":{"stringValue":"traefik --configFile=$ARGS"}}]},
  "scopeLogs":[{"scope":{"name":"traefik"},"logRecords":[
   $(access_record "/v1/merchant/payouts/$PAYMENT?ref=$QUERY_NEEDLE" "$ROUTER" 502),
   $(access_record "/$UNROUTED/$PAN" "" 404)]}]},
 {"resource":{"attributes":[
   {"key":"service.name","value":{"stringValue":"finapp-migrator"}},
   {"key":"k8s.node.name","value":{"stringValue":"$NODE"}}]},
  "scopeLogs":[{"scope":{"name":"k8s_objects"},"logRecords":[{
   "timeUnixNano":"$now","severityNumber":9,
   "body":{"stringValue":"finapp-migrator schema=ledger attempts=1 failure=MIGRATION card $PAN for $MIGRATOR_AMOUNT\\nfinapp-migrator result=MIGRATION_FAILED schema=ledger"},
   "attributes":[
    {"key":"k8s.job.name","value":{"stringValue":"finapp-migrator"}},
    {"key":"k8s.container.exit_code","value":{"intValue":"10"}},
    {"key":"k8s.node.name","value":{"stringValue":"$NODE"}}]}]}]}]}
JSON
}

platform_needles_in() {
    for needle in "$CLIENT" "$EDGE_HOST" "$BACKEND" "$EDGE_USER" "$UNROUTED" "$QUERY_NEEDLE" "$ARGS" "$NODE" "$PAN" "6234.18"; do
        if printf '%s' "$1" | grep -qF -- "$needle"; then
            printf '%s\n' "$needle"
        fi
    done
}

platform_sweep() {
    # platform_sweep <collector>: post the platform's records to 4319, then wait for the sink to print the routed line's router.
    answer=$(docker run --rm --network "$NETWORK" "$CURL_IMAGE" -sS -o /dev/null -w '%{http_code}' \
        -H 'Content-Type: application/json' --data-binary "$(platform_body)" "http://$1:4319/v1/logs" || true)
    if [ "$answer" != 200 ]; then
        echo "!!   $1 did not take the platform's records on 4319 (answered $answer)"
        return 1
    fi
    deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if exported | grep -qF -- "MIGRATION_FAILED" && exported | grep -qF -- "$ROUTER"; then
            return 0
        fi
        sleep 1
    done
    echo "!!   the platform's records did not reach the sink in 30 s; the collector and the sink said:"
    docker logs "$1" 2>&1 | grep -v deprecated | tail -15
    exported | tail -15
    return 1
}

# The sink is restarted empty between the two, so what each sweep finds is its own collector's.
restart_sink() {
    docker rm -f "$RUN-sink" >/dev/null 2>&1
    docker run -d --name "$RUN-sink" --network "$NETWORK" --network-alias "$RUN-sink" -v "$(dpath "$CONFIGS"):/cfg:ro" \
        "$COLLECTOR_IMAGE" --config /cfg/sink.yaml >/dev/null
    sleep 3
}

echo "==   the committed collector, the platform's own lines: the needles go in on 4319, none comes out, every kept field does"
restart_sink
if platform_sweep "$RUN-collector"; then
    output=$(exported)
    found=$(platform_needles_in "$output")
    if [ -n "$found" ]; then
        echo "!!   needles exported from the platform's own lines: $(printf '%s' "$found" | tr '\n' ' ')"
        status=1
    else
        missing=""
        for control in "/v1/merchant/payouts/$PAYMENT" "$ROUTER" "GET 502 $ROUTER" "DownstreamStatus" "finapp-migrator result=MIGRATION_FAILED" "[scrubbed]" "k8s.job.name" "$EDGE_POD"; do
            printf '%s' "$output" | grep -qF -- "$control" || missing="$missing $control"
        done
        if [ -n "$missing" ]; then
            echo "!!   the platform's net dropped what it must keep:$missing"
            status=1
        else
            echo "ok   no needle exported from the edge's or the migrator's records; the routed path, its router and status, the migrator's result and the scrub marks are"
        fi
    fi
else
    status=1
fi

echo "==   THE PROBE, the platform's own lines: transform/scrub-platform removed, and the needles must come out"
restart_sink
if platform_sweep "$RUN-probe"; then
    found=$(platform_needles_in "$(exported)")
    if printf '%s' "$found" | grep -qF -- "$CLIENT" && printf '%s' "$found" | grep -qF -- "$PAN" && printf '%s' "$found" | grep -qF -- "$UNROUTED"; then
        echo "ok   the probe is red: without the platform's net the collector exported $(printf '%s' "$found" | tr '\n' ' ')"
    else
        echo "!!   the platform probe stayed green: with its net removed the needles still did not come out, so the check cannot see a leak"
        status=1
    fi
else
    status=1
fi

if [ "$status" -eq 0 ]; then
    echo "telemetry check: every overlay check green, every plant refused for its reason, no needle exported, and the probe red"
else
    echo "telemetry check: FAILED"
fi
exit "$status"
