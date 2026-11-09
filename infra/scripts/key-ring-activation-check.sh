#!/bin/sh
# Refuses a change that moves a key ring in a way the rollout order cannot survive (P15-TSK-019, ADR-0123 point 4).
#
# A ring's activation is two steps across N replicas. First a change lists the new version in the ring's versions and the fleet
# rolls; a replica that cannot hold every listed version refuses to start, so a finished rollout means every replica holds it. Then
# a later change moves the active version. This compares a change with its base commit, because order is what a rendered manifest
# cannot show, and refuses:
#
#   (1) an active version moved to a version the base did not already list: a replica would be asked to write what its rolling
#       neighbours have never heard of;
#   (2) a version listed at the base that is neither held nor retired at the head, a revert of a rotation for one: every row written
#       while it was active would be refused with nothing having said so, because a version dropped off the top leaves no hole. It
#       must leave through ring.retired-versions, so that KeyRingRetirementGuard reads the real tables first;
#   (3) anything that reads like a ring setting in a shape this script does not read: a spelling Spring still binds, a dotted
#       property, any YAML file. Silence would be the failure.
#
# WHAT IT CANNOT PROVE. It compares two commits, so it proves the change was two commits, not that the first rollout finished
# (OPERATIONS_RUNBOOK.md section 11.29 states that gate; delivery order is P15-TSK-017's once a GitOps controller applies these).
# A kubectl rollout undo never reaches it, which is why the runbook forbids that for a ring change.
#
# Usage:
#   infra/scripts/key-ring-activation-check.sh <base-ref>     compare the working tree with a base commit
#   infra/scripts/key-ring-activation-check.sh --self-test    every plant below must get the status it expects
#
# Exit status: 0 nothing moved out of order; 1 refused; 2 usage, or the base could not be read, so nothing is proved.
set -eu

# Anything that reads like a ring setting, however spelled. The first character stops "spring" matching.
SHAPE='(^|[^a-z])ring[._-]*(active[._-]*version|retired[._-]*versions|versions)'
# The one form this script reads, and the form Spring binds to the three properties from a configMapGenerator literal.
CANONICAL='^[[:space:]]*-[[:space:]]*FINAPP_[A-Z0-9_]+_RING_(ACTIVEVERSION|VERSIONS|RETIREDVERSIONS)=[0-9, ]*[[:space:]]*$'

yaml_files() {
    (cd "$1" && find deploy -type f \( -name '*.yaml' -o -name '*.yml' \) 2>/dev/null) | sort
}

# The value of the literal NAME in FILE, without spaces; nothing if the file or the literal is absent.
literal() {
    [ -f "$2" ] || return 0
    sed -n "s/^ *- *$1=\([0-9, ]*\) *\$/\1/p" "$2" | tail -1 | tr -d ' '
}

in_list() {
    case ",$2," in
        *",$1,"*) return 0 ;;
        *) return 1 ;;
    esac
}

# The ring stems a file names: FINAPP_DOC for FINAPP_DOC_RING_VERSIONS.
stems_of() {
    [ -f "$1" ] || return 0
    sed -n -e 's/^ *- *\(FINAPP_[A-Z0-9_]*\)_RING_ACTIVEVERSION=.*/\1/p' \
        -e 's/^ *- *\(FINAPP_[A-Z0-9_]*\)_RING_VERSIONS=.*/\1/p' \
        -e 's/^ *- *\(FINAPP_[A-Z0-9_]*\)_RING_RETIREDVERSIONS=.*/\1/p' "$1"
}

# compare BASE HEAD: two directories, each holding a deploy tree. Sets status to 1 on any refusal.
compare() {
    base=$1
    head=$2
    status=0
    for file in $(yaml_files "$head"); do
        offending=$(grep -Ei "$SHAPE" "$head/$file" | grep -Ev '^[[:space:]]*#' | grep -Ev "$CANONICAL" || true)
        if [ -n "$offending" ]; then
            echo "$file: a ring setting in a shape this check cannot read: $offending" >&2
            status=1
        fi
    done
    for file in $( (yaml_files "$base"; yaml_files "$head") | sort -u ); do
        for stem in $( (stems_of "$base/$file"; stems_of "$head/$file") | sort -u ); do
            base_active=$(literal "${stem}_RING_ACTIVEVERSION" "$base/$file")
            head_active=$(literal "${stem}_RING_ACTIVEVERSION" "$head/$file")
            base_versions=$(literal "${stem}_RING_VERSIONS" "$base/$file")
            head_versions=$(literal "${stem}_RING_VERSIONS" "$head/$file")
            base_retired=$(literal "${stem}_RING_RETIREDVERSIONS" "$base/$file")
            head_retired=$(literal "${stem}_RING_RETIREDVERSIONS" "$head/$file")

            # (1) An active version moved to one the base did not list.
            if [ -n "$head_active" ] && [ "$head_active" != "$base_active" ] && ! in_list "$head_active" "${base_versions:-1}"; then
                echo "$file: ${stem}_RING_ACTIVEVERSION moves to version $head_active, which the base does not list in ${stem}_RING_VERSIONS (${base_versions:-1})" >&2
                echo "  list the version in one change, let the rollout finish, then activate it in a later one" >&2
                status=1
            fi

            # (2) A version the base listed has left, neither held nor retired.
            for version in $(printf '%s,%s' "$base_versions" "$base_retired" | tr ',' ' '); do
                if ! in_list "$version" "${head_versions:-1}" && ! in_list "$version" "$head_retired"; then
                    echo "$file: ${stem} version $version is listed at the base and is neither held nor retired here: rows under it would be refused" >&2
                    echo "  a version leaves a ring only through ring.retired-versions, which reads the real tables first" >&2
                    status=1
                fi
            done
        done
    done
    return "$status"
}

# ---------------------------------------------------------------------------------------------------
# The plants: each states the base's literals, the head's, and the status the check must give.
# ---------------------------------------------------------------------------------------------------
self_test() {
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' EXIT
    failures=0

    plant() { # plant <name> <expected status> <file> <base literals, | separated> <head literals, | separated>
        rm -rf "$tmp/b" "$tmp/h"
        mkdir -p "$tmp/b/$(dirname "$3")" "$tmp/h/$(dirname "$3")"
        printf '%s\n' "$4" | tr '|' '\n' | sed 's/^/      - /' > "$tmp/b/$3"
        printf '%s\n' "$5" | tr '|' '\n' | sed 's/^/      - /' > "$tmp/h/$3"
        set +e
        compare "$tmp/b" "$tmp/h" > "$tmp/out" 2>&1
        got=$?
        set -e
        if [ "$got" -ne "$2" ]; then
            echo "!!   plant $1: expected status $2, got $got" >&2
            sed 's/^/     /' "$tmp/out" >&2
            failures=$((failures + 1))
        else
            echo "ok   plant $1: status $got"
        fi
    }

    f=deploy/overlays/production/kustomization.yaml
    plant nothing-changes 0 "$f" 'FINAPP_DOC_RING_VERSIONS=1,2' 'FINAPP_DOC_RING_VERSIONS=1,2'
    plant known-then-active 0 "$f" 'FINAPP_DOC_RING_VERSIONS=1,2' 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2'
    plant listed-and-activated-together 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2'
    plant top-version-dropped 1 "$f" 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2' 'NOT_A_RING=1'
    plant top-version-dropped-from-the-list 1 "$f" 'FINAPP_DOC_RING_VERSIONS=1,2' 'FINAPP_DOC_RING_VERSIONS=1'
    plant top-version-retired 0 "$f" 'FINAPP_DOC_RING_VERSIONS=1,2' 'FINAPP_DOC_RING_VERSIONS=1|FINAPP_DOC_RING_RETIREDVERSIONS=2'
    plant retired-version-forgotten 1 "$f" 'FINAPP_DOC_RING_VERSIONS=1|FINAPP_DOC_RING_RETIREDVERSIONS=2' 'FINAPP_DOC_RING_VERSIONS=1'
    plant the-legacy-spelling-spring-binds 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_ACTIVE_VERSION=2'
    plant a-dotted-property-spring-binds 1 "$f" 'NOT_A_RING=1' 'finapp.doc.ring.active-version=2'
    plant a-yml-file-is-read 1 deploy/overlays/production/ring.yml 'NOT_A_RING=1' 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2'
    plant spring-is-not-a-ring 0 "$f" 'NOT_A_RING=1' 'spring.versions=1'

    if [ "$failures" -ne 0 ]; then
        echo "!!   $failures plant(s) not refused for their reason" >&2
        exit 1
    fi
    echo "ok   every plant got the status it expects"
}

case ${1:-} in
    --self-test)
        self_test
        exit 0
        ;;
    "")
        echo "usage: $0 <base-ref> | --self-test" >&2
        exit 2
        ;;
esac

BASE=$1
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"
git rev-parse --verify --quiet "$BASE^{commit}" > /dev/null || { echo "cannot read base $BASE: no history to compare, so nothing proved" >&2; exit 2; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/base"
if git cat-file -e "$BASE:deploy" 2>/dev/null; then
    git archive --format=tar "$BASE" deploy | tar -x -C "$work/base"
fi

compare "$work/base" "$ROOT" || exit 1
exit 0
