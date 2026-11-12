#!/bin/sh
# The series the telemetry rules read, as the pinned Prometheus stores them (P15-TSK-032; THREAT_MODEL.md section 6 item 37 (d) (i)).
#
# WHY THIS EXISTS. An alert on a name the scrape never stores never fires, and promtool cannot see that: its cases are series written by
# hand. What the scrape stores is not what a document says. The pinned collector answers the classic text format, so its counters are
# stored with no _total suffix; its failure counters do not exist before the first failure and are born above zero; its exporter series
# carry address and path labels a rule must sum away. So this runs the pinned images and writes what the pinned Prometheus stored to
# infra/prometheus/telemetry-series.txt. TelemetryAlertRulesTest holds every name, label and label value that
# infra/prometheus/rules/telemetry.yml reads to that file, and the file's image digests to infra/scanner-pins.sh: a pin moved without
# running this again is red in the build.
#
# THE PHASES, each line of the output labelled with the first one its series existed in:
#   fresh    every container started and scraped, no telemetry sent
#   healthy  spans and log records sent through the collector, the committed configuration unchanged, and received by the real Tempo and
#            Loki (deploy/observability/overlays/kind's tempo.yaml and loki.yaml)
#   failing  Tempo and Loki stopped and the collector's retries run out on the committed bound (sixty seconds); a second collector whose
#            sending queue holds one batch, overrun; a third whose memory limiter refuses everything. The second and third are the committed
#            configuration with that one bound changed, because a name does not depend on a bound and the committed bounds take a thousand
#            batches or real memory pressure to cross
#
# WHAT IT NEVER DOES. It talks to no cluster and holds no credential. The containers, the network and the scratch files are this run's and
# removed at exit; the repository is read, and only the output file is written.
#
# Usage:
#   infra/scripts/telemetry-series.sh [output file]     (default: infra/prometheus/telemetry-series.txt)
#
# Exit status: 0 written; 1 a phase was never reached (nothing written); 2 no Docker.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)
OUT=${1:-$ROOT/infra/prometheus/telemetry-series.txt}

# Git Bash on Windows otherwise rewrites /cfg and /api/... into host paths. Unset and harmless on Linux.
export MSYS_NO_PATHCONV=1
export LC_ALL=C

# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

COLLECTOR_IMAGE="${COLLECTOR_REPOSITORY}@${COLLECTOR_DIGEST}"
TEMPO_IMAGE="${TEMPO_REPOSITORY}@${TEMPO_DIGEST}"
LOKI_IMAGE="${LOKI_REPOSITORY}@${LOKI_DIGEST}"
PROMETHEUS_IMAGE="${PROMETHEUS_REPOSITORY}@${PROMETHEUS_DIGEST}"
CURL_IMAGE="${CURL_REPOSITORY}@${CURL_DIGEST}"

docker info >/dev/null 2>&1 || { echo "Docker is not available: every step runs a pinned image." >&2; exit 2; }

RUN="finapp-telemetry-series-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
CONFIGS=$(mktemp -d)

# A path as Docker on this host takes it: Git Bash's /tmp is not a path Docker Desktop can mount. Unchanged on Linux.
dpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

cleanup() {
    docker rm -f "$RUN-tempo" "$RUN-loki" "$RUN-prom" "$RUN-col" "$RUN-queue" "$RUN-refusing" >/dev/null 2>&1 || true
    docker network rm "$RUN" >/dev/null 2>&1 || true
    rm -rf "$CONFIGS"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# ------------------------------------------------------------------------------------------- the configurations

cp "$ROOT/deploy/observability/base/collector.yaml" "$CONFIGS/collector.yaml"
cp "$ROOT/deploy/observability/overlays/kind/tempo.yaml" "$CONFIGS/tempo.yaml"
cp "$ROOT/deploy/observability/overlays/kind/loki.yaml" "$CONFIGS/loki.yaml"
# One batch in each sending queue, one consumer: a few requests overrun it.
sed 's/^      queue_size: 1000$/      queue_size: 1\n      num_consumers: 1/' "$CONFIGS/collector.yaml" > "$CONFIGS/queue.yaml"
# A memory limiter whose bound every process is already above: every request refused at the receiver.
awk '/^  memory_limiter:$/ { print; print "    check_interval: 1s"; print "    limit_mib: 4"; print "    spike_limit_mib: 2"; skip = 3; next }
     skip > 0 { skip--; next } { print }' "$CONFIGS/collector.yaml" > "$CONFIGS/refusing.yaml"
for variant in queue refusing; do
    if cmp -s "$CONFIGS/collector.yaml" "$CONFIGS/$variant.yaml"; then
        echo "!! the $variant collector was not planted: collector.yaml no longer has the bound it changes" >&2
        exit 1
    fi
done

cat > "$CONFIGS/prometheus.yml" <<'YAML'
global:
  scrape_interval: 2s
scrape_configs:
  - job_name: finapp-collector
    static_configs:
      - targets: ["col:8888", "queue:8888", "refusing:8888"]
  - job_name: finapp-tempo
    static_configs:
      - targets: ["tempo:3200"]
  - job_name: finapp-loki
    static_configs:
      - targets: ["loki:3100"]
YAML

# ------------------------------------------------------------------------------------------- the helpers

curl_in() {
    docker run --rm --network "$RUN" "$CURL_IMAGE" -sS "$@"
}

query() {
    # query <promql>: the instant query's raw answer.
    curl_in -G "http://$RUN-prom:9090/api/v1/query" --data-urlencode "query=$1"
}

# Every series of the families a telemetry rule may read, one per line as name{label=value,...}: a value kept where a rule may select on
# it, * where it is this run's own (an address, a port, a pod, an identifier).
FAMILIES='{__name__=~"up|otelcol_.+|tempo_(distributor|discarded|receiver)_.+|loki_(distributor|discarded)_.+"}'
series() {
    curl_in -G "http://$RUN-prom:9090/api/v1/series" --data-urlencode "match[]=$FAMILIES" \
        | tr -d '\n' | sed 's/^.*"data":\[{//; s/}\]}.*$//; s/},{/\n/g' \
        | awk '
            BEGIN { split("job exporter receiver transport sampled decision data_type reason format processor otel.signal policy tenant is_internal_stream", k, " ")
                    for (i in k) keep[k[i]] = 1 }
            {
                line = $0; name = ""; n = 0
                while (match(line, /"[^"]*":"[^"]*"/)) {
                    pair = substr(line, RSTART + 1, RLENGTH - 2); line = substr(line, RSTART + RLENGTH)
                    split(pair, kv, "\":\""); key = kv[1]; value = kv[2]
                    if (key == "__name__") { name = value; continue }
                    labels[++n] = key "=" ((key in keep) ? value : "*")
                }
                for (i = 2; i <= n; i++) { v = labels[i]; j = i - 1; while (j > 0 && labels[j] > v) { labels[j + 1] = labels[j]; j-- } labels[j + 1] = v }
                out = name "{"; for (i = 1; i <= n; i++) out = out (i > 1 ? "," : "") labels[i]; print out "}"
                delete labels
            }' \
        | sort -u
}

await() {
    # await <seconds> <what> <promql>: until the query answers a sample, or exit 1 naming what never came.
    deadline=$(( $(date +%s) + $1 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if query "$3" 2>/dev/null | grep -q '"value"'; then
            return 0
        fi
        sleep 2
    done
    echo "!! $2 never came in $1 s; nothing written" >&2
    docker logs "$RUN-col" 2>&1 | grep -v deprecated | tail -5 >&2
    exit 1
}

post() {
    # post <collector> <count>: that many spans (a write, which the tail policy keeps) and log records.
    i=0
    while [ "$i" -lt "$2" ]; do
        now=$(date +%s)000000000
        trace=$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')
        curl_in -o /dev/null -H 'Content-Type: application/json' "http://$1:4318/v1/traces" --data-binary \
            "{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"finapp\"}}]},\"scopeSpans\":[{\"scope\":{\"name\":\"series\"},\"spans\":[{\"traceId\":\"$trace\",\"spanId\":\"0123456789abcdef\",\"name\":\"http post /v1/payments\",\"kind\":2,\"startTimeUnixNano\":\"$now\",\"endTimeUnixNano\":\"$now\",\"attributes\":[{\"key\":\"method\",\"value\":{\"stringValue\":\"POST\"}}]}]}]}]}" \
            || true
        curl_in -o /dev/null -H 'Content-Type: application/json' "http://$1:4318/v1/logs" --data-binary \
            "{\"resourceLogs\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"finapp\"}}]},\"scopeLogs\":[{\"scope\":{\"name\":\"finapp\"},\"logRecords\":[{\"timeUnixNano\":\"$now\",\"severityNumber\":9,\"body\":{\"stringValue\":\"series $i\"}}]}]}]}" \
            || true
        i=$((i + 1))
    done
}

collector() {
    # collector <alias> <config>: the pinned collector, as telemetry-check.sh runs it, exporting to this run's Tempo and Loki.
    docker run -d --name "$RUN-$1" --network "$RUN" --network-alias "$1" -v "$(dpath "$CONFIGS"):/cfg:ro" \
        -e POD_IP=0.0.0.0 -e COLLECTOR_PEERS="$1" -e TEMPO_ENDPOINT=tempo:4317 -e LOKI_ENDPOINT=http://loki:3100/otlp \
        -e FINAPP_ENVIRONMENT=series -e TAIL_DECISION_WAIT=2s "$COLLECTOR_IMAGE" --config "/cfg/$2" >/dev/null
}

# ------------------------------------------------------------------------------------------- the run

docker network create "$RUN" >/dev/null
docker run -d --name "$RUN-tempo" --network "$RUN" --network-alias tempo --tmpfs /var/tempo:rw,mode=1777 -v "$(dpath "$CONFIGS"):/cfg:ro" \
    "$TEMPO_IMAGE" -config.file=/cfg/tempo.yaml >/dev/null
docker run -d --name "$RUN-loki" --network "$RUN" --network-alias loki --tmpfs /loki:rw,mode=1777 -v "$(dpath "$CONFIGS"):/cfg:ro" \
    "$LOKI_IMAGE" -config.file=/cfg/loki.yaml >/dev/null
collector col collector.yaml
docker run -d --name "$RUN-prom" --network "$RUN" -v "$(dpath "$CONFIGS"):/cfg:ro" \
    "$PROMETHEUS_IMAGE" --config.file=/cfg/prometheus.yml --storage.tsdb.path=/prometheus >/dev/null

echo "== fresh: every container started and scraped, nothing sent"
await 120 "the collector, Tempo and Loki answering their scrapes" 'count(up{instance=~"col:8888|tempo:3200|loki:3100"} == 1) == 3'
series > "$CONFIGS/fresh.txt"

echo "== healthy: spans and log records through the committed collector, received by Tempo and Loki"
post col 3
await 120 "Tempo's and Loki's receipt of what the collector sent" \
    'count(tempo_distributor_spans_received_total > 0) + count(loki_distributor_lines_received_total > 0) + count(otelcol_exporter_sent_spans{exporter="otlp_grpc/tempo"} > 0)'
series > "$CONFIGS/healthy.txt"

echo "== failing: the back ends stopped, a queue overrun, a memory limiter refusing (the retries take about a minute)"
docker stop "$RUN-tempo" "$RUN-loki" >/dev/null
collector queue queue.yaml
collector refusing refusing.yaml
await 60 "the two planted collectors answering their scrapes" 'count(up{instance=~"queue:8888|refusing:8888"} == 1) == 2'
post col 3
post queue 8
post refusing 2
await 180 "every failure counter" \
    'count(otelcol_exporter_send_failed_spans) + count(otelcol_exporter_send_failed_log_records) + count(otelcol_exporter_enqueue_failed_spans) + count(otelcol_exporter_enqueue_failed_log_records) + count(otelcol_receiver_refused_spans > 0) + count(otelcol_receiver_refused_log_records > 0)'
series > "$CONFIGS/failing.txt"

{
    echo "# The series the telemetry rules read, as the pinned Prometheus stored them from the pinned collector, Tempo and Loki (P15-TSK-032)."
    echo "# Written by infra/scripts/telemetry-series.sh, never by hand: run it again whenever a pin below moves; TelemetryAlertRulesTest is red"
    echo "# until it is. Each line: the phase its series first existed in (fresh, healthy, failing), then its name and labels, a label's value"
    echo "# kept where a rule may select on it and * where it is the run's own."
    echo "# prometheus $PROMETHEUS_DIGEST"
    echo "# collector $COLLECTOR_DIGEST"
    echo "# tempo $TEMPO_DIGEST"
    echo "# loki $LOKI_DIGEST"
    sed 's/^/fresh /' "$CONFIGS/fresh.txt"
    comm -13 "$CONFIGS/fresh.txt" "$CONFIGS/healthy.txt" | sed 's/^/healthy /'
    sort -u "$CONFIGS/fresh.txt" "$CONFIGS/healthy.txt" > "$CONFIGS/before.txt"
    comm -13 "$CONFIGS/before.txt" "$CONFIGS/failing.txt" | sed 's/^/failing /'
} > "$OUT"

echo "written: $OUT ($(grep -c '^[a-z]' "$OUT") series: $(grep -c '^fresh ' "$OUT") fresh, $(grep -c '^healthy ' "$OUT") healthy, $(grep -c '^failing ' "$OUT") failing)"
