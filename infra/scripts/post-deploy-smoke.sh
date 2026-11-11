#!/bin/sh
# The read-only post-deploy smoke (P15-TSK-017, ADR-0122 decisions 4 and 7; OPERATIONS_RUNBOOK.md section 11.36).
#
# WHAT IT ASKS, AND WITH WHOSE IDENTITY. The cluster, through `kubectl get` and nothing else, under the identity of whoever runs it: in
# production a person's read-only role (deploy/base/rbac-humans.yaml, which can read pods, ReplicaSets, Deployments, Jobs and
# ConfigMaps and never a Secret), never CI's, which holds no production credential. Prometheus, through one GET of its query API.
# It moves no money, writes nothing to the cluster and starts nothing there.
#
# WHAT IT JUDGES (infra/scripts/Promotion.java, `smoke`; PromotionTest holds each refusal):
#   health          the rollout complete, every replica ready, on one ReplicaSet and one configuration, none terminating (an old
#                   replica still consuming is a reason to run again, not a pass), and the release's migrator Job succeeded;
#   build identity  every replica's image and the image the kubelet resolved are the digest deploy/overlays/<environment> names, and
#                   the migrator's are its digest: a digest compared with a digest, so what runs is exactly what the promotion
#                   verified the signature and provenance of. /actuator/info's build.version is a Gradle version, not the commit,
#                   and is never read here;
#   trial balance   finapp_ledger_trial_balance, every currency's verdict zero and at least one currency answering (INV-ACC-01).
#
# --record writes what it saw, every verdict PASS, under deploy/evidence/<environment>/, to be committed as written: a staging record is
# the evidence a production promotion of that pair must name, and the newest record of an environment is what a key ring's
# activation there waits for (THREAT_MODEL.md section 6 item 32 (c)).
#
# Usage:   infra/scripts/post-deploy-smoke.sh <staging|production> [--record]
# Env:     SMOKE_KUBECTL     the kubectl command, its context included (default: kubectl)
#          SMOKE_PROMETHEUS  the base address of the Prometheus that scrapes the environment (required; never committed)
# Needs:   kubectl, curl, a JDK 21.
# Exit:    0 every verdict PASS; 1 a verdict FAIL, nothing recorded; 2 something could not be read, which is never a pass.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENVIRONMENT=${1:-}
case $ENVIRONMENT in
    staging | production) ;;
    *)
        echo "usage: $0 <staging|production> [--record]" >&2
        exit 2
        ;;
esac
RECORD=-
case ${2:-} in
    "") ;;
    --record) RECORD="$ROOT/deploy/evidence/$ENVIRONMENT" ;;
    *)
        echo "usage: $0 <staging|production> [--record]" >&2
        exit 2
        ;;
esac
KUBECTL=${SMOKE_KUBECTL:-kubectl}
PROMETHEUS=${SMOKE_PROMETHEUS:-}
[ -n "$PROMETHEUS" ] || { echo "SMOKE_PROMETHEUS names no Prometheus: the trial balance cannot be read, so nothing would pass" >&2; exit 2; }
NAMESPACE=finapp

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# get <what...>: kubectl's get, in the platform's namespace. The only verb this script uses.
get() {
    # shellcheck disable=SC2086
    $KUBECTL -n "$NAMESPACE" get "$@"
}

if ! get deployment finapp-app -o go-template='deployment|{{.metadata.generation}}|{{or .status.observedGeneration 0}}|{{or .spec.replicas 0}}|{{or .status.replicas 0}}|{{or .status.updatedReplicas 0}}|{{or .status.readyReplicas 0}}|{{or .status.availableReplicas 0}}{{"\n"}}' \
    > "$work/snapshot.txt"; then
    echo "the Deployment could not be read" >&2
    exit 2
fi
if ! get pods -l app.kubernetes.io/name=finapp-app -o go-template='{{range .items}}pod|{{.metadata.name}}|{{if .metadata.deletionTimestamp}}terminating{{else}}running{{end}}|{{range .metadata.ownerReferences}}{{.name}}{{end}}|{{range .status.conditions}}{{if eq .type "Ready"}}{{.status}}{{end}}{{end}}|{{range .spec.containers}}{{if eq .name "app"}}{{.image}}{{end}}{{end}}|{{range .status.containerStatuses}}{{if eq .name "app"}}{{.imageID}}{{end}}{{end}}|{{range .spec.containers}}{{if eq .name "app"}}{{range .envFrom}}{{if .configMapRef}}{{.configMapRef.name}}{{end}}{{end}}{{end}}{{end}}{{"\n"}}{{end}}' \
    >> "$work/snapshot.txt"; then
    echo "the replicas could not be read" >&2
    exit 2
fi
# The release's Job is kept for a day (ttlSecondsAfterFinished); gone, it reads as no Job, and the judgement fails rather than passes.
get job finapp-migrator -o go-template='job|{{.metadata.name}}|{{or .status.succeeded 0}}|{{range .spec.template.spec.containers}}{{if eq .name "migrator"}}{{.image}}{{end}}{{end}}{{"\n"}}' \
    >> "$work/snapshot.txt" 2> "$work/job.err" || true
get pods -l job-name=finapp-migrator -o go-template='{{range .items}}jobpod|{{.metadata.name}}|{{range .status.containerStatuses}}{{if eq .name "migrator"}}{{.imageID}}{{end}}{{end}}{{"\n"}}{{end}}' \
    >> "$work/snapshot.txt" 2>> "$work/job.err" || true
# The ring listings of the configuration the replicas read, and nothing else of it.
configuration=$(awk -F'|' '$1 == "pod" && $3 == "running" {print $8; exit}' "$work/snapshot.txt")
if [ -n "$configuration" ]; then
    get configmap "$configuration" -o go-template='{{range $key, $value := .data}}configmap|{{$key}}|{{$value}}{{"\n"}}{{end}}' > "$work/configuration.txt" \
        || { echo "the configuration $configuration could not be read" >&2; exit 2; }
    grep -E '^configmap\|FINAPP_[A-Z0-9_]+_RING_(VERSIONS|ACTIVEVERSION)\|[0-9, ]*$' "$work/configuration.txt" >> "$work/snapshot.txt" || true
fi

if ! curl -fsS --max-time 20 -G --data-urlencode 'query=max by (currency) (finapp_ledger_trial_balance)' "$PROMETHEUS/api/v1/query" \
    > "$work/trial-balance.json"; then
    echo "Prometheus could not be read" >&2
    exit 2
fi

# A path as a JDK on this host takes it: Git Bash's /c/... is not one on Windows.
jpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}
[ "$RECORD" = - ] || RECORD=$(jpath "$RECORD")
java --source 21 "$(jpath "$ROOT/infra/scripts/Promotion.java")" smoke "$(jpath "$ROOT")" "$ENVIRONMENT" "$(jpath "$work/snapshot.txt")" \
    "$(jpath "$work/trial-balance.json")" "$RECORD"
