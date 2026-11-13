#!/bin/sh
# The kind rehearsal's Secrets, generated per run and never written to the repository (P15-TSK-018, ADR-0123).
#
# A deployed environment fills finapp-app-secrets and finapp-migrator-secrets from its secret store, through the two
# ExternalSecrets of deploy/components/secret-delivery. kind has no store and no operator, so this fills the same two names, and
# it reads their keys from those same two files: there is no list here to fall behind the inventory.
#
# WHAT IS GENERATED, AND WHAT IS NOT. A name ending in _KEY gets 32 random bytes as base64, which both of ConfinedCredential's
# length rules accept (exactly 32 for an AES key, at least 32 for an HMAC or API key); one ending in _PASSWORD gets 64 random hex
# characters. Any other name is a user or an endpoint: not a secret, and not something this script can know, so it must be in the
# caller's environment under its own name, and the script refuses to run without it rather than guess a host.
# SecretInventoryIsCompleteTest holds that suffix rule against the inventory. infra/scripts/manifest-check.sh runs the render mode
# on every change and checks what comes out.
#
# Three more objects the rehearsal needs, and which nothing delivers in a deployed environment: finapp-kind-bootstrap (what the
# one-time role bootstrap reads, the same generated passwords), finapp-kind-postgres (the test-only server's own password, the
# bootstrap step's master credential) and finapp-kind-kafka-scram (the application's SCRAM user, for the test-only broker to create).
#
# WHERE THE VALUES GO. Into this process's variables and then to `kubectl create` on stdin: never onto a command line, into a file
# in the repository, or to the output. Generated per run, so a second run against the same cluster fails on the first object that
# already exists, rather than quietly changing values the rehearsal's roles were created with.
#
# THE LEDGER (P16-TSK-019). With KIND_SECRETS_LEDGER naming a file outside the repository, the sha256 of every value this run puts in an
# object - generated or taken from the caller - is appended to it, and nothing else: no name, no value. The kind rehearsal's experiments
# scope reads it to refuse any Secret value its run did not make (ADR-0127 decision 5).
#
# Usage:
#   FINAPP_DB_URL=... [every other non-secret name] infra/scripts/kind-secrets.sh apply <namespace>
#   FINAPP_DB_URL=... [every other non-secret name] infra/scripts/kind-secrets.sh render <empty directory outside the repository>
#
# Exit status: 0 done; 1 refused; 2 usage.
set -eu
umask 077

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
DELIVERY="$ROOT/deploy/components/secret-delivery"

MODE=${1:-}
TARGET=${2:-}
case $MODE in
    apply | render) [ -n "$TARGET" ] || { echo "usage: $0 apply <namespace> | render <directory>" >&2; exit 2; } ;;
    *) echo "usage: $0 apply <namespace> | render <directory>" >&2; exit 2 ;;
esac

if [ "$MODE" = render ]; then
    [ -d "$TARGET" ] || { echo "$TARGET is not a directory" >&2; exit 1; }
    place=$(CDPATH= cd -- "$TARGET" && pwd)
    case "$place/" in
        "$ROOT"/*) echo "refusing to write generated values inside the repository: $place" >&2; exit 1 ;;
    esac
fi

LEDGER=${KIND_SECRETS_LEDGER:-}
if [ -n "$LEDGER" ]; then
    ledger_dir=$(CDPATH= cd -- "$(dirname -- "$LEDGER")" && pwd) || { echo "the ledger's directory does not exist: $LEDGER" >&2; exit 1; }
    case "$ledger_dir/" in
        "$ROOT"/*) echo "refusing to write the ledger inside the repository: $ledger_dir" >&2; exit 1 ;;
    esac
fi

# The keys one ExternalSecret writes. Its file is the list, and an empty answer is refused: a parser that finds nothing has failed.
# So is one that finds fewer than the file holds: an entry written in another order, or quoted, is valid YAML this sed cannot read.
keys_of() {
    found=$(sed -n 's/^ *- secretKey: *\(FINAPP_[A-Z0-9_]*\) *$/\1/p' "$DELIVERY/$1")
    [ -n "$found" ] || { echo "no secretKey could be read from $1" >&2; exit 1; }
    held=$(grep -v '^ *#' "$DELIVERY/$1" | grep -c 'secretKey' || true)
    read_here=$(printf '%s\n' "$found" | wc -l)
    [ "$held" -eq "$read_here" ] || { echo "$1 has $held entries and only $read_here could be read: write each as '- secretKey: NAME'" >&2; exit 1; }
    printf '%s\n' "$found"
}

# One value per name for the whole run, so a password reads the same in every object that carries it.
remember() {
    eval "known=\${value_$1:-}"
    [ -z "$known" ] || return 0
    case $1 in
        *_KEY) fresh=$(head -c 32 /dev/urandom | base64 | tr -d '\n') ;;
        *_PASSWORD) fresh=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n') ;;
        *)
            eval "fresh=\${$1:-}"
            [ -n "$fresh" ] || { echo "$1 is not a secret, so it is not generated: set it in the environment" >&2; exit 1; }
            ;;
    esac
    eval "value_$1=\$fresh"
    [ -z "$LEDGER" ] || printf '%s' "$fresh" | sha256sum | cut -c1-64 >> "$LEDGER"
}

# write <object name>, with "KEY=value" lines on stdin.
write() {
    if [ "$MODE" = render ]; then
        cat > "$TARGET/$1.env"
        return
    fi
    {
        printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: %s\n  namespace: %s\ntype: Opaque\nstringData:\n' "$1" "$TARGET"
        while IFS= read -r line; do
            printf "  %s: '%s'\n" "${line%%=*}" "$(printf '%s' "${line#*=}" | sed "s/'/''/g")"
        done
    } | kubectl create -f - > /dev/null
}

# named <object name> <name>...: the object's entries, each under its own name.
named() {
    object=$1
    shift
    entries=""
    for name in "$@"; do
        remember "$name"
        eval "entries=\"\${entries}\${name}=\${value_$name}
\""
    done
    printf '%s' "$entries" | write "$object"
}

# Assigned first: a failure inside a command substitution given as an argument is ignored even under set -e.
app_keys=$(keys_of externalsecret-app.yaml)
migrator_keys=$(keys_of externalsecret-migrator.yaml)
# The simulators' URLs (P15-TSK-012). No ExternalSecret delivers a provider URL and the policy refuses one in a ConfigMap, so the
# rehearsal names them here; each follows the suffix rule above, a URL being a name the caller must supply. Unset, nothing is added.
# shellcheck disable=SC2086
named finapp-app-secrets $app_keys ${KIND_SECRETS_EXTRA_APP_NAMES:-}
# shellcheck disable=SC2086
named finapp-migrator-secrets $migrator_keys
named finapp-kind-bootstrap FINAPP_DB_URL FINAPP_DB_MASTER_USER FINAPP_DB_MASTER_PASSWORD \
    FINAPP_DB_MIGRATOR_PASSWORD FINAPP_DB_APP_PASSWORD FINAPP_DB_REPORTING_PASSWORD
# What the test-only broker's SCRAM step reads (deploy/overlays/kind/kafka.yaml): the application's own user and password.
named finapp-kind-kafka-scram FINAPP_KAFKA_SASL_USERNAME FINAPP_KAFKA_SASL_PASSWORD

# deploy/overlays/kind/postgres.yaml reads one key of this object.
field=password
remember FINAPP_DB_MASTER_PASSWORD
printf '%s=%s\n' "$field" "$value_FINAPP_DB_MASTER_PASSWORD" | write finapp-kind-postgres
