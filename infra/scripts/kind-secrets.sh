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
# THE KEY RINGS FROM AN ESCROW (P16-TSK-025, ADR-0149). With KIND_SECRETS_RINGS=stdin, every version of every field-level key ring is
# read from stdin as NAME=value lines, the disaster-recovery exercise's escrow decrypted into a pipe, and never generated: a ring's
# version made afresh in a recovery would leave every row written under it unreadable. A ring's version is a name ending in _KEY
# that the inventory holds a second version beside (its stem's _V2_KEY), or a numbered version itself, read from the ExternalSecret
# like every other name here, never from a list. Every such name must arrive on stdin, and the run refuses before any object is
# written when one does not. A line naming anything else - a password, a provider's or a callback's key, a name the inventory does
# not hold - is refused: a recovery carries the rings and nothing else, and every credential is generated again.
#
# Usage:
#   FINAPP_DB_URL=... [every other non-secret name] infra/scripts/kind-secrets.sh apply <namespace>
#   FINAPP_DB_URL=... [every other non-secret name] infra/scripts/kind-secrets.sh render <empty directory outside the repository>
#   <the escrow's NAME=value lines> | KIND_SECRETS_RINGS=stdin FINAPP_DB_URL=... infra/scripts/kind-secrets.sh apply|render ...
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

RINGS=${KIND_SECRETS_RINGS:-}
case $RINGS in
    '' | stdin) ;;
    *) echo "KIND_SECRETS_RINGS is stdin or unset, not $RINGS" >&2; exit 2 ;;
esac

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
        *_KEY)
            # A ring's version from the escrow, when one was given: read whole from stdin before anything was written (below).
            if [ "$RINGS" = stdin ] && is_ring "$1"; then
                eval "fresh=\${ring_$1}"
            else
                fresh=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
            fi
            ;;
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

# is_ring <name>: a version of a field-level key ring - a numbered version, or a base key the inventory holds a second version beside.
is_ring() {
    case $1 in
        *_V[0-9]_KEY) return 0 ;;
    esac
    printf '%s\n' "$app_keys" | grep -qx "${1%_KEY}_V2_KEY"
}

# The escrow's lines, read whole before any object is written: every one a ring's version the inventory holds, and every ring's version
# the inventory holds among them. Nothing is half-applied by a recovery that lacks a version.
if [ "$RINGS" = stdin ]; then
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        name=${line%%=*}
        case $name in
            '' | *[!A-Z0-9_]*) echo "a line on stdin names no key: only a key ring's versions are read there" >&2; exit 1 ;;
        esac
        printf '%s\n' "$app_keys" | grep -qx "$name" || { echo "$name, on stdin, is not in the inventory" >&2; exit 1; }
        is_ring "$name" || { echo "$name, on stdin, is not a key ring's version: only a ring is recovered, every other secret is generated" >&2; exit 1; }
        [ -n "${line#*=}" ] && [ "$line" != "$name" ] || { echo "$name, on stdin, has no value" >&2; exit 1; }
        eval "ring_$name=\${line#*=}"
    done
    for name in $app_keys; do
        is_ring "$name" || continue
        eval "held=\${ring_$name:-}"
        [ -n "$held" ] || { echo "no value for $name on stdin: a key ring's version is never generated in its place" >&2; exit 1; }
    done
fi
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
