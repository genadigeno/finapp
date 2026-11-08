#!/bin/sh
# Runs the application image against compose's PostgreSQL and Kafka on loopback and waits for readiness UP, then
# checks the running process is UID 65532 (P15-TSK-003: the image starts ready against local dependencies, non-root).
#
# THE CALLER STARTS THE DEPENDENCIES: `docker compose up -d postgres kafka --wait`, then `./gradlew flywayMigrate`
# (the application never migrates, ADR-0011). This script never starts or stops compose, so it cannot tear down a
# developer's own stack.
#
# --network host: the container shares the host's network namespace, so 127.0.0.1 reaches compose's ports, which
# compose publishes on loopback only - and the application's loopback-only defaults hold as they were written: the
# marked local database credential and plaintext Kafka are refused off loopback (DatabaseCredentialGuard,
# KafkaTransportGuard). On Docker Desktop "host" is its VM's namespace, where the published ports are bound (verified
# 2026-11-08). The run is restricted as a pod will be: the root filesystem read-only, /tmp a tmpfs, every capability
# dropped, no privilege escalation.
#
# The readiness URL is the actuator's on the API port, 8080, today. P15-TSK-008 moves the actuator to a management
# port (8081); READINESS_URL below follows it.
#
# Usage:   infra/scripts/image-readiness.sh [image]   (default: finapp-app:<revision>, image-build.sh's tag)
# Passes through, when set: FINAPP_DB_URL (a compose database published on another loopback port).
# Exit:    0 ready and non-root; 1 not ready, or not as the image's user; 2 could not run.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

READINESS_URL=http://127.0.0.1:8080/actuator/health/readiness
RUNTIME_UID=65532

fail() {
    echo "!! $*" >&2
    exit 1
}

cannot() {
    echo "?? $*" >&2
    exit 2
}

dock() {
    MSYS_NO_PATHCONV=1 docker "$@"
}

if [ "$#" -ge 1 ]; then
    IMAGE=$1
else
    REVISION=$(git rev-parse HEAD)
    if [ -n "$(git status --porcelain)" ]; then
        REVISION="$REVISION-dirty"
    fi
    IMAGE="finapp-app:$REVISION"
fi
dock image inspect "$IMAGE" >/dev/null 2>&1 || cannot "no image $IMAGE: build it with infra/scripts/image-build.sh"

# The poller: the builder base, already pinned by digest in the Dockerfile and carrying curl, on the host network
# beside the application. No new image to pin.
PROBE=$(sed -n 's/^[[:space:]]*[Ff][Rr][Oo][Mm][[:space:]]\{1,\}\(--[^[:space:]]*[[:space:]]\{1,\}\)*\([^[:space:]]*\).*/\2/p' \
    Dockerfile | head -1)
[ -n "$PROBE" ] || cannot "no FROM line in Dockerfile"

curl_on_host() {
    dock run --rm --network host --entrypoint curl "$PROBE" "$@"
}

# Something already answering would be read as this image's readiness.
if curl_on_host --silent --max-time 2 --output /dev/null "$READINESS_URL" 2>/dev/null; then
    cannot "something already answers on $READINESS_URL; stop it first"
fi

NAME="finapp-image-readiness-$$"
cleanup() {
    dock rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [ -n "${FINAPP_DB_URL:-}" ]; then
    set -- --env FINAPP_DB_URL
else
    set --
fi
dock run --detach --name "$NAME" --network host \
    --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
    "$@" "$IMAGE" >/dev/null || cannot "the container did not start"

logs() {
    echo "the container's last log lines:" >&2
    dock logs --tail 40 "$NAME" >&2 2>&1 || true
}

echo "== waiting for $READINESS_URL (up to about four minutes)"
if ! body=$(curl_on_host --fail --silent --show-error --max-time 2 \
    --retry 47 --retry-delay 3 --retry-all-errors --retry-connrefused "$READINESS_URL"); then
    logs
    fail "$IMAGE never reported ready"
fi
[ "$body" = '{"status":"UP"}' ] || { logs; fail "readiness answered '$body', not UP"; }
[ "$(dock inspect --format '{{.State.Running}}' "$NAME")" = "true" ] || { logs; fail "the container is no longer running"; }

# The process as it runs, not the image's declaration: docker top lists the container's processes by host uid.
uids=$(dock top "$NAME" -o pid,uid | sed 1d | sed 's/^[[:space:]]*[0-9]\{1,\}[[:space:]]\{1,\}//' | sort -u | tr '\n' ' ')
[ "$uids" = "$RUNTIME_UID " ] || fail "the container's processes run as uid(s) $uids, not $RUNTIME_UID"

echo "ready: $READINESS_URL answered $body; every process runs as uid $RUNTIME_UID; the root filesystem read-only," \
    "every capability dropped"
