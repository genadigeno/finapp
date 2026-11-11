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

    # The application's endpoint: anything but the cluster's own collector is refused.
    obs_plant_on obs-endpoint-external "$APP" 's|http://finapp-collector.finapp-observability.svc.cluster.local:4318/v1/traces|https://otlp.example.invalid/v1/traces|'

    # The second net taken out of a pipeline.
    obs_plant_on obs-scrub-removed "$CONFIG" '1,/processors: \[memory_limiter, transform\/scrub, resource\/environment, batch\]/s/processors: \[memory_limiter, transform\/scrub, resource\/environment, batch\]/processors: [memory_limiter, resource\/environment, batch]/'
}

in_container() {
    case $1 in
        render)
            mkdir -p /scratch/rendered
            render_stream /repo/deploy /scratch/rendered/kind.yaml
            printf 'overlay: kind\n' > /scratch/rendered/kind.data.yaml
            ;;
        plants)
            plant_all
            for directory in "$PLANTS"/*; do
                render_stream "$directory/deploy" "$directory/rendered.yaml"
                printf 'overlay: kind\n' > "$directory/overlay.yaml"
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
obs_refused obs-endpoint-external obs-endpoint
obs_refused obs-scrub-removed obs-scrub

# ---------------------------------------------------------------------------------------------------
# The second net, through the real collector.
# ---------------------------------------------------------------------------------------------------
# The needles. A card number with and without its separators, an amount as Money prints one, a party's identifier, and three that only an
# allow-list drops: a query string, a host, and the stack of an exception.
PAN=4111111111111111
PAN_SPACED="4111 1111 1111 1111"
AMOUNT="7319.47 EUR"
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
# The probe's configuration: the committed one with the scrubbing processor taken out of every pipeline, nothing else changed.
sed 's/transform\/scrub, //' "$ROOT/deploy/observability/base/collector.yaml" > "$CONFIGS/probe.yaml"
if cmp -s "$CONFIGS/collector.yaml" "$CONFIGS/probe.yaml"; then
    echo "!!   the probe was not planted: no pipeline names transform/scrub"
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
  "body":{"stringValue":"{\\"message\\":\\"captured card $PAN_SPACED and $PAN for $AMOUNT\\",\\"correlationId\\":\\"$1\\"}"},
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
    for needle in "$PAN" "$PAN_SPACED" "7319.47" "$PARTY" "$HOST" "$QUERY"; do
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
        for control in check-committed "$PAYMENT" "/v1/payments/{id}" "[scrubbed]" "exception.type" "captured card" "deployment.environment.name"; do
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

if [ "$status" -eq 0 ]; then
    echo "telemetry check: every overlay check green, every plant refused for its reason, no needle exported, and the probe red"
else
    echo "telemetry check: FAILED"
fi
exit "$status"
