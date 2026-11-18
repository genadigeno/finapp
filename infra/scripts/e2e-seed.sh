#!/bin/sh
# TEST-ONLY. The Operator Console's end-to-end seed (P17-TSK-106; finapp-admin-panel docs/FINAPP_BACKEND_WORK.md B2.1).
#
#   infra/scripts/e2e-seed.sh <scenario>...
#
# Puts in place, on a TEST stack, what the console's M2 end-to-end tests act on, and prints what it created as KEY=value lines on
# stdout - one line per identifier, nothing else there - for the console's harness to read. Everything else goes to stderr.
#
#   abandoned-event   an outbox event its relay gave up on: the console retries it, or two persons discard it
#                     -> ABANDONED_EVENT_ID
#   parked-record     a parked inbox record whose event is published: the console releases it, or two persons discard it
#                     -> PARKED_RECORD_ID (the door's park id), PARKED_EVENT_ID
#   parsed-file       a simulated-PSP report uploaded by the seed's own operator and PARSED, awaiting a second person's
#                     attestation: the console's operator attests or declines it (never the submitter: INV-SET-07)
#                     -> PARSED_FILE_ID, PARSED_FILE_SUBMITTER_ID
#   fee-break         an OPEN FEE_MISMATCH of 1.75 EUR: a report uploaded by one of the seed's operators, attested by another,
#                     accepted and matched by the platform's own sweeps. ACKNOWLEDGE admits it, four-eyes, and moves no money; an
#                     approval echoing an operand the proposal does not name is refused 409 ResolutionStale, and the plain
#                     re-confirmation approves -> BREAK_ID, BREAK_FILE_ID
#   all               each of the above once
#
# Every run creates fresh rows, identities and references, so it reruns without collision and two runs never share a row. The two
# settlement scenarios wait for the platform's sweeps (fifteen seconds each by default): up to a few minutes on a slow stack.
#
# WHERE IT RUNS (E2E_SEED_TARGET), and nowhere else - an unknown target is refused, never defaulted:
#   compose (default)  this checkout's compose stack: PostgreSQL through `docker compose exec`, as finapp_migrator, the role that
#                      owns the platform schema; the application at FINAPP_BACKEND_URL, which must be this machine's
#                      (http://localhost or http://127.0.0.1, any port). The HTTP half runs on the local JDK (21 or later).
#   kind               a kind cluster of the kind rehearsal: E2E_SEED_NODE names its control-plane node container, which must carry
#                      the label kind gives its nodes (io.x-k8s.kind.cluster); PostgreSQL through that node's kubectl, in pod
#                      E2E_SEED_PG_POD (default finapp-postgres-0), as the test-only server's superuser, as the rehearsal's census
#                      does; the HTTP half in the application's own JDK image on the kind network, through the real edge's public
#                      and operator hosts (PUBLIC_PORT 30443, OPERATOR_PORT 30444 unless set), trusting the edge's local CA read
#                      from the cluster. Run by the console's battery on kind (P17-TST-001), which owns that cluster.
#
# HOW: a state no door reaches - an abandoned relay, a parked record - is planted by its owning module's rules, one SQL statement
# per plant (infra/scripts/e2e-seed/*.sql, each proven against PostgreSQL and the dead-letter doors by platform's
# E2eSeedPlantsDatabaseTest). Every other effect goes through the platform's own doors (infra/scripts/e2e-seed/Seed.java, which has
# no route to the database): registration, a second factor, step-up, the role grant, the settlement upload and attestation. The
# two things only the database can answer are asked here, as the console's own harness asks them: a test identity's id (no API
# exposes it before it holds a role - the console's gap G-10) and the run's first administrator, by the one documented break-glass
# step (README section 5e).
#
# WHAT IT NEVER IS: in an image, on a route, or in a deployed environment. Nothing under any module's src/main, deploy/ or
# infra/terraform names it (app's E2eSeedStaysTestOnlyTest), and it refuses a stack that is not a test one before it touches anything.
# The planted rows page the stack's own alerting (OutboxDeadLetter, InboxDeadLetterParked) as real ones would: on a test stack, by
# design. Do not run it while a kind rehearsal's experiments count dead letters.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SEED="$ROOT/infra/scripts/e2e-seed"
TARGET=${E2E_SEED_TARGET:-compose}
SCENARIOS="abandoned-event, parked-record, parsed-file, fee-break or all"

say() { printf '%s\n' "e2e-seed: $*" >&2; }
refuse() { printf '%s\n' "e2e-seed: refused: $*" >&2; exit 2; }

# Git Bash on Windows otherwise rewrites the paths handed to docker. Unset and harmless elsewhere.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV
dpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

[ "$#" -ge 1 ] || refuse "name a scenario: $SCENARIOS"
HTTP=no
for scenario in "$@"; do
    case "$scenario" in
        abandoned-event|parked-record) ;;
        parsed-file|fee-break|all) HTTP=yes ;;
        *) refuse "unknown scenario '$scenario': $SCENARIOS" ;;
    esac
done

# ---------------------------------------------------------------------------------------------------
# The target, judged before anything is touched.
case "$TARGET" in
    compose)
        FINAPP_BACKEND_URL=${FINAPP_BACKEND_URL:-http://localhost:8080}
        # Anchored, host and port and nothing after: a glob's '*' would admit http://localhost:8.example.org.
        printf '%s' "$FINAPP_BACKEND_URL" | grep -Eq '^http://(localhost|127\.0\.0\.1)(:[0-9]{1,5})?/?$' \
            || refuse "FINAPP_BACKEND_URL is '$FINAPP_BACKEND_URL': the compose target seeds this machine's stack only (http://localhost or http://127.0.0.1)"
        FINAPP_BACKEND_URL=${FINAPP_BACKEND_URL%/}
        [ -f "$ROOT/compose.yaml" ] || refuse "no compose.yaml at $ROOT"
        command -v docker >/dev/null 2>&1 || refuse "docker is not on the PATH"
        [ "$HTTP" = no ] || command -v java >/dev/null 2>&1 || refuse "java (21 or later) is not on the PATH, and the settlement scenarios need it"
        ;;
    kind)
        [ -n "${E2E_SEED_NODE:-}" ] || refuse "E2E_SEED_NODE is unset: name the kind cluster's control-plane node container"
        cluster=$(docker inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$E2E_SEED_NODE" 2>/dev/null || true)
        case "$cluster" in
            ''|'<no value>') refuse "E2E_SEED_NODE '$E2E_SEED_NODE' is not a kind node (no io.x-k8s.kind.cluster label)" ;;
        esac
        E2E_SEED_PG_POD=${E2E_SEED_PG_POD:-finapp-postgres-0}
        say "kind cluster '$cluster', node $E2E_SEED_NODE, database pod $E2E_SEED_PG_POD"
        ;;
    *)
        refuse "E2E_SEED_TARGET is '$TARGET': compose or kind"
        ;;
esac

# psql_seed [psql arguments]: the target's database, one statement or file at a time, stopping on the first error.
psql_seed() {
    case "$TARGET" in
        compose)
            docker compose -f "$(dpath "$ROOT/compose.yaml")" exec -T postgres \
                psql -X -q -A -t -v ON_ERROR_STOP=1 -U finapp_migrator -d finapp "$@"
            ;;
        kind)
            docker exec -i "$E2E_SEED_NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf -n finapp exec -i "$E2E_SEED_PG_POD" \
                -c postgres -- psql -X -q -A -t -v ON_ERROR_STOP=1 -U finapp -d finapp "$@"
            ;;
    esac
}

# plant <file>: runs one plant, its one statement, and prints the row it returned (fields separated by '|').
plant() {
    psql_seed -f - < "$SEED/$1"
}

uuid_like() {
    printf '%s' "$1" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
}

# ---------------------------------------------------------------------------------------------------
# The dead letters: planted.
seed_abandoned_event() {
    event=$(plant abandoned-event.sql | tr -d '[:space:]')
    uuid_like "$event" || { say "the abandoned event's plant printed '$event'"; exit 1; }
    say "planted an abandoned event ($event): retry it, or propose and approve its discard"
    printf 'ABANDONED_EVENT_ID=%s\n' "$event"
}

seed_parked_record() {
    row=$(plant parked-record.sql | tr -d '[:space:]')
    park=${row%%|*}
    event=${row#*|}
    uuid_like "$park" && uuid_like "$event" || { say "the parked record's plant printed '$row'"; exit 1; }
    say "planted a parked record ($park) naming the published event $event: release it, or propose and approve its discard"
    printf 'PARKED_RECORD_ID=%s\n' "$park"
    printf 'PARKED_EVENT_ID=%s\n' "$event"
}

# ---------------------------------------------------------------------------------------------------
# The settlement scenarios: through the doors, as the seed's own test operators.
STATE=
cleanup() { [ -z "$STATE" ] || rm -rf "$STATE"; }
trap cleanup EXIT
trap 'exit 130' INT TERM

# seed_http <command> [arguments]: Seed.java against the target's application. Its stdout is KEY=value lines for this script only.
seed_http() {
    case "$TARGET" in
        compose)
            java --source 21 "$(dpath "$SEED/Seed.java")" "$FINAPP_BACKEND_URL" "$FINAPP_BACKEND_URL" "$(dpath "$STATE")" "$@"
            ;;
        kind)
            docker run --rm --network kind --add-host "public.finapp.test:$PUBLIC_IP" --add-host "operator.finapp.test:$OPERATOR_IP" \
                -v "$(dpath "$STATE"):/work" -v "$(dpath "$SEED"):/src:ro" "$JDK_IMAGE" java --source 21 /src/Seed.java \
                "https://public.finapp.test:${PUBLIC_PORT:-30443}" "https://operator.finapp.test:${OPERATOR_PORT:-30444}" /work "$@"
            ;;
    esac
}

# value <KEY> <output>: the one KEY=value line Seed.java printed for KEY.
value() {
    printf '%s\n' "$2" | sed -n "s/^$1=//p" | head -1
}

# operator <who>: a fresh test identity with a factor, registered through the door. Prints its identity id.
operator() {
    login=$(value LOGIN "$(seed_http register "$1")")
    # Checked before it reaches SQL: the shape Seed.java makes and nothing else.
    printf '%s' "$login" | grep -Eq '^e2e-seed\.[a-z]+\.[0-9a-f]{12}$' || { say "registration of $1 printed '$login'"; exit 1; }
    id=$(psql_seed -c "SELECT id FROM identity.identity WHERE login_identifier = '$login'" | tr -d '[:space:]')
    uuid_like "$id" || { say "no identity found for $login"; exit 1; }
    printf '%s %s\n' "$id" "$login"
}

SETTLEMENT_READY=no
settlement_operators() {
    [ "$SETTLEMENT_READY" = no ] || return 0
    STATE=$(mktemp -d)
    if [ "$TARGET" = kind ]; then
        JDK_IMAGE=$(sed -n 's/^FROM \(eclipse-temurin:[^ ]*\) AS builder$/\1/p' "$ROOT/Dockerfile")
        [ -n "$JDK_IMAGE" ] || { say "no JDK image found in the Dockerfile"; exit 1; }
        node_ip=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$E2E_SEED_NODE")
        PUBLIC_IP=${E2E_SEED_PUBLIC_IP:-$node_ip}
        OPERATOR_IP=${E2E_SEED_OPERATOR_IP:-$node_ip}
        docker exec "$E2E_SEED_NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf -n finapp-edge get secret finapp-edge-local-ca \
            -o jsonpath='{.data.ca\.crt}' | base64 -d > "$STATE/edge-ca.crt"
        [ -s "$STATE/edge-ca.crt" ] || { say "the edge's local CA could not be read"; exit 1; }
    fi
    set -- $(operator admin)
    admin_login=$2
    # The run's first administrator: README section 5e's break-glass step, the one effect here with no door by design.
    psql_seed -c "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i WHERE i.login_identifier = '$admin_login'" >/dev/null
    set -- $(operator submitter)
    SUBMITTER_ID=$1
    set -- $(operator attester)
    ATTESTER_ID=$1
    seed_http grant admin RECONCILIATION_OPERATOR "$SUBMITTER_ID" "$ATTESTER_ID" >/dev/null
    say "the run's operators: submitter $SUBMITTER_ID and attester $ATTESTER_ID, each RECONCILIATION_OPERATOR with a factor"
    SETTLEMENT_READY=yes
}

seed_parsed_file() {
    settlement_operators
    file=$(value FILE_ID "$(seed_http upload submitter parsed)")
    uuid_like "$file" || { say "the upload printed '$file'"; exit 1; }
    seed_http await-file submitter "$file" PARSED
    say "file $file PARSED, unattested: attest or decline it as anyone but its submitter $SUBMITTER_ID"
    printf 'PARSED_FILE_ID=%s\n' "$file"
    printf 'PARSED_FILE_SUBMITTER_ID=%s\n' "$SUBMITTER_ID"
}

seed_fee_break() {
    settlement_operators
    file=$(value FILE_ID "$(seed_http upload submitter fee)")
    uuid_like "$file" || { say "the upload printed '$file'"; exit 1; }
    seed_http await-file submitter "$file" PARSED
    seed_http attest attester "$file"
    seed_http await-file submitter "$file" ACCEPTED
    found=$(value BREAK_ID "$(seed_http await-break submitter "$file")")
    uuid_like "$found" || { say "the break search printed '$found'"; exit 1; }
    say "break $found: an OPEN FEE_MISMATCH of 1.75 EUR from file $file; propose ACKNOWLEDGE (FEE_ACCEPTED_AS_CHARGED) and approve as another person"
    printf 'BREAK_ID=%s\n' "$found"
    printf 'BREAK_FILE_ID=%s\n' "$file"
}

for scenario in "$@"; do
    case "$scenario" in
        abandoned-event) seed_abandoned_event ;;
        parked-record) seed_parked_record ;;
        parsed-file) seed_parsed_file ;;
        fee-break) seed_fee_break ;;
        all) seed_abandoned_event; seed_parked_record; seed_parsed_file; seed_fee_break ;;
    esac
done
