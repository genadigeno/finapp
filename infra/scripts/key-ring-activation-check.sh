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
#       property, any YAML file, check values included, and a check value it cannot parse. Silence would be the failure;
#   (4) a published check value changed or removed for a version still held (P15-TSK-020, THREAT_MODEL.md section 6 item 32 (a)):
#       the replica refuses a stored value that differs from its check value, and the one way round that is to publish the new
#       value's, which is exactly what makes every row under the old value unreadable. A version's value and its check value never
#       change once listed; new material goes to a new version.
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

# Anything that reads like a ring setting, however spelled, check values among them (P15-TSK-020's review: the legacy
# _RING_CHECK_VALUES, a dotted or camel-cased property, any separator). Matched without regard to case. The first character stops
# "spring" matching.
SHAPE='(^|[^a-z])ring[._-]*(active[._-]*version|retired[._-]*versions|versions|check[._-]*values)'
# The one form this script reads, and the form Spring binds to the four properties from a configMapGenerator literal: versions as
# numbers, a check value as version:sixteen lower-case hexadecimal digits. A ring setting in any other form, a check value it could
# not parse among them, is refused rather than read as nothing.
CANONICAL_RING='^[[:space:]]*-[[:space:]]*FINAPP_[A-Z0-9_]+_RING_(ACTIVEVERSION|VERSIONS|RETIREDVERSIONS)=[0-9, ]*[[:space:]]*$'
CANONICAL_CHECK='^[[:space:]]*-[[:space:]]*FINAPP_[A-Z0-9_]+_RING_CHECKVALUES=[[:space:]]*([0-9]+:[0-9a-f]{16}[[:space:]]*(,[[:space:]]*[0-9]+:[0-9a-f]{16}[[:space:]]*)*)?$'

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
        -e 's/^ *- *\(FINAPP_[A-Z0-9_]*\)_RING_RETIREDVERSIONS=.*/\1/p' \
        -e 's/^ *- *\(FINAPP_[A-Z0-9_]*\)_RING_CHECKVALUES=.*/\1/p' "$1"
}

# The check value FILE publishes for VERSION of STEM; nothing if none.
check_value() {
    [ -f "$3" ] || return 0
    sed -n "s/^ *- *$1_RING_CHECKVALUES=\([0-9a-f:, ]*\) *\$/\1/p" "$3" | tail -1 | tr -d ' ' | tr ',' '\n' | sed -n "s/^$2:\([0-9a-f]*\)\$/\1/p"
}

# The versions STEM's check values name in FILE, comma-separated.
checked_versions() {
    [ -f "$2" ] || return 0
    sed -n "s/^ *- *$1_RING_CHECKVALUES=\([0-9a-f:, ]*\) *\$/\1/p" "$2" | tail -1 | tr -d ' ' | tr ',' '\n' | sed -n 's/^\([0-9]*\):.*/\1/p' | paste -sd, -
}

# compare BASE HEAD: two directories, each holding a deploy tree. Sets status to 1 on any refusal.
compare() {
    base=$1
    head=$2
    status=0
    for file in $(yaml_files "$head"); do
        offending=$(grep -Ei "$SHAPE" "$head/$file" | grep -Ev '^[[:space:]]*#' | grep -Ev "$CANONICAL_RING" | grep -Ev "$CANONICAL_CHECK" || true)
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

            # (4) A published check value changed or removed for a version still held.
            for version in $(checked_versions "$stem" "$base/$file" | tr ',' ' '); do
                if in_list "$version" "${head_versions:-1}"; then
                    base_check=$(check_value "$stem" "$version" "$base/$file")
                    head_check=$(check_value "$stem" "$version" "$head/$file")
                    if [ "$head_check" != "$base_check" ]; then
                        echo "$file: ${stem} version $version's published check value is changed or removed while the version is held" >&2
                        echo "  a listed version's value never changes: put new material in a new version, and leave this check value as it was" >&2
                        status=1
                    fi
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
    c1=FINAPP_DOC_RING_CHECKVALUES=1:552d40876e6d9748,2:0ec936eb254d5bcf
    plant check-values-kept-through-activation 0 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" "FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2|$c1"
    plant check-value-of-a-held-version-changed 1 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_CHECKVALUES=1:552d40876e6d9748,2:5daa68abe4ec79b0'
    plant check-value-of-a-held-version-removed 1 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_CHECKVALUES=1:552d40876e6d9748'
    plant base-check-value-changed-with-one-version 1 "$f" 'FINAPP_DOC_RING_CHECKVALUES=1:552d40876e6d9748' 'FINAPP_DOC_RING_CHECKVALUES=1:5daa68abe4ec79b0'
    plant check-value-of-a-retired-version-dropped 0 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" 'FINAPP_DOC_RING_VERSIONS=2|FINAPP_DOC_RING_ACTIVEVERSION=2|FINAPP_DOC_RING_RETIREDVERSIONS=1|FINAPP_DOC_RING_CHECKVALUES=2:0ec936eb254d5bcf'
    # Every other spelling Spring binds to check-values, and a value this script could not parse (P15-TSK-020's review): refused,
    # because a check value read as nothing would let rule (4) pass a change it exists to stop.
    plant check-values-in-the-legacy-spelling 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECK_VALUES=1:552d40876e6d9748'
    plant check-values-as-a-dotted-property 1 "$f" 'NOT_A_RING=1' 'finapp.doc.ring.check-values=1:552d40876e6d9748'
    plant check-values-camel-cased 1 "$f" 'NOT_A_RING=1' 'finapp.doc.ring.checkValues=1:552d40876e6d9748'
    plant check-values-in-upper-case-hex 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECKVALUES=1:552D40876E6D9748,2:0EC936EB254D5BCF'
    plant check-value-with-no-version 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECKVALUES=552d40876e6d9748'
    plant check-value-too-short 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECKVALUES=1:552d4087'

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
