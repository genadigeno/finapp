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
#   (5) an activation rendered into an environment GitOps delivers (an overlay's own literal for that environment; a literal in
#       deploy/base or deploy/components for both) before that environment was seen holding the version (P15-TSK-017; THREAT_MODEL.md
#       section 6 item 32 (c)): the environment's newest record under deploy/evidence/<environment> AS MERGED AT THE BASE, written by
#       infra/scripts/post-deploy-smoke.sh from what every replica runs (the rollout complete, none terminating, every pod on one
#       configuration), must list the version. A record the change itself adds does not count: it would be the change vouching for
#       itself. Comparing two commits proves the change was two commits, not that the first
#       had rolled out, and Argo CD alone cannot hold the order: it syncs the branch's newest commit, so two commits merged inside one
#       polling interval reach a cluster as one sync. So the order is held here, at the merge, which is the deploy.
#
# WHAT IT CANNOT PROVE. That the record is still true when the activation merges: nothing but a reviewed change, which rule (2)
# holds, can take a listed version away. A kubectl rollout undo never reaches it, which is why the runbook forbids that for a ring
# change. The kind overlay is the rehearsal's, applied by its script, and is held to rules (1) to (4) alone.
#
# Usage:
#   infra/scripts/key-ring-activation-check.sh <base-ref>     compare the working tree with a base commit
#   infra/scripts/key-ring-activation-check.sh --self-test    every plant below must get the status it expects
#
# Exit status: 0 nothing moved out of order; 1 refused; 2 usage, or the base could not be read, so nothing is proved.
set -eu

# The one reader of a smoke record (P15-TSK-017), and a path as a JDK on this host takes it.
PROMOTION="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/Promotion.java"
jpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

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

            # (5) An activation in an environment GitOps delivers, before that environment's newest smoke record, as merged at the base,
            #     lists the version. The base and the components are rendered into both environments, so a literal there waits for both.
            environments=""
            case $file in
                deploy/overlays/staging/*) environments=staging ;;
                deploy/overlays/production/*) environments=production ;;
                deploy/base/* | deploy/components/*) environments="staging production" ;;
            esac
            if [ -n "$environments" ] && [ -n "$head_active" ] && [ "$head_active" != "${base_active:-1}" ]; then
                for environment in $environments; do
                    said=$(java --source 21 "$(jpath "$PROMOTION")" held "$(jpath "$base")" "$environment" "$stem" "$head_active" 2>&1) && read_status=0 || read_status=$?
                    if [ "$read_status" -eq 1 ]; then
                        echo "$file: ${stem}_RING_ACTIVEVERSION moves to $head_active before $environment was seen holding it" >&2
                        echo "  $said" >&2
                        status=1
                    elif [ "$read_status" -ne 0 ]; then
                        # A record that could not be read, or no JDK, proves nothing: refused, and said so.
                        echo "$file: ${stem}_RING_ACTIVEVERSION moves to $head_active, and $environment's records could not be judged ($read_status)" >&2
                        echo "  $said" >&2
                        status=1
                    fi
                done
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

    plant() { # plant <name> <expected status> <file> <base literals, | separated> <head literals, | separated> [<environment> <listing>]...
        rm -rf "$tmp/b" "$tmp/h"
        mkdir -p "$tmp/b/$(dirname "$3")" "$tmp/h/$(dirname "$3")"
        printf '%s\n' "$4" | tr '|' '\n' | sed 's/^/      - /' > "$tmp/b/$3"
        printf '%s\n' "$5" | tr '|' '\n' | sed 's/^/      - /' > "$tmp/h/$3"
        # The smoke's records, in the form infra/scripts/Promotion.java writes, listing FINAPP_DOC as given: "<environment> <listing>"
        # pairs, written into the base (merged before the change) unless the pair's environment is prefixed head: (the change's own).
        name=$1 expected=$2
        shift 5
        while [ $# -ge 2 ]; do
            side=b
            environment=$1
            case $environment in head:*) side=h; environment=${environment#head:} ;; esac
            mkdir -p "$tmp/$side/deploy/evidence/$environment"
            printf 'environment: %s\nchecked: "2026-11-12T10:00:00Z"\nhealth: PASS\nbuild-identity: PASS\ntrial-balance: PASS\nheld:\n  - "FINAPP_DOC listed %s active 1"\n' \
                "$environment" "$2" > "$tmp/$side/deploy/evidence/$environment/20261112T100000Z-000000000000.yaml"
            shift 2
        done
        set +e
        compare "$tmp/b" "$tmp/h" > "$tmp/out" 2>&1
        got=$?
        set -e
        if [ "$got" -ne "$expected" ]; then
            echo "!!   plant $name: expected status $expected, got $got" >&2
            sed 's/^/     /' "$tmp/out" >&2
            failures=$((failures + 1))
        else
            echo "ok   plant $name: status $got"
        fi
    }

    f=deploy/overlays/production/kustomization.yaml
    plant nothing-changes 0 "$f" 'FINAPP_DOC_RING_VERSIONS=1,2' 'FINAPP_DOC_RING_VERSIONS=1,2'
    plant known-then-active 0 "$f" 'FINAPP_DOC_RING_VERSIONS=1,2' 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2' production 1,2
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
    plant check-values-kept-through-activation 0 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" "FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2|$c1" production 1,2
    plant check-value-of-a-held-version-changed 1 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_CHECKVALUES=1:552d40876e6d9748,2:5daa68abe4ec79b0'
    plant check-value-of-a-held-version-removed 1 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" 'FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_CHECKVALUES=1:552d40876e6d9748'
    plant base-check-value-changed-with-one-version 1 "$f" 'FINAPP_DOC_RING_CHECKVALUES=1:552d40876e6d9748' 'FINAPP_DOC_RING_CHECKVALUES=1:5daa68abe4ec79b0'
    plant check-value-of-a-retired-version-dropped 0 "$f" "FINAPP_DOC_RING_VERSIONS=1,2|$c1" 'FINAPP_DOC_RING_VERSIONS=2|FINAPP_DOC_RING_ACTIVEVERSION=2|FINAPP_DOC_RING_RETIREDVERSIONS=1|FINAPP_DOC_RING_CHECKVALUES=2:0ec936eb254d5bcf' production 1,2
    # Every other spelling Spring binds to check-values, and a value this script could not parse (P15-TSK-020's review): refused,
    # because a check value read as nothing would let rule (4) pass a change it exists to stop.
    plant check-values-in-the-legacy-spelling 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECK_VALUES=1:552d40876e6d9748'
    plant check-values-as-a-dotted-property 1 "$f" 'NOT_A_RING=1' 'finapp.doc.ring.check-values=1:552d40876e6d9748'
    plant check-values-camel-cased 1 "$f" 'NOT_A_RING=1' 'finapp.doc.ring.checkValues=1:552d40876e6d9748'
    plant check-values-in-upper-case-hex 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECKVALUES=1:552D40876E6D9748,2:0EC936EB254D5BCF'
    plant check-value-with-no-version 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECKVALUES=552d40876e6d9748'
    plant check-value-too-short 1 "$f" 'NOT_A_RING=1' 'FINAPP_DOC_RING_CHECKVALUES=1:552d4087'
    # Rule (5) (P15-TSK-017): an activation where GitOps delivers waits for that environment's smoke record to list the version. The three
    # production activations above carry the record their environment would have; without it each is refused, as below.
    k12='FINAPP_DOC_RING_VERSIONS=1,2'
    a2='FINAPP_DOC_RING_VERSIONS=1,2|FINAPP_DOC_RING_ACTIVEVERSION=2'
    plant activation-with-no-record 1 "$f" "$k12" "$a2"
    plant activation-before-the-listing-was-seen 1 "$f" "$k12" "$a2" production 1
    plant activation-on-another-environments-record 1 "$f" "$k12" "$a2" staging 1,2
    plant activation-after-the-listing-was-seen 0 "$f" "$k12" "$a2" production 1,2
    plant staging-activation-with-no-record 1 deploy/overlays/staging/kustomization.yaml "$k12" "$a2"
    plant staging-activation-after-the-listing-was-seen 0 deploy/overlays/staging/kustomization.yaml "$k12" "$a2" staging 1,2
    plant kind-activation-needs-no-record 0 deploy/overlays/kind/kustomization.yaml "$k12" "$a2"
    plant pointer-moved-back-after-the-listing-was-seen 0 "$f" "$a2" "$k12|FINAPP_DOC_RING_ACTIVEVERSION=1" production 1,2
    # A record the change itself adds is no record (the review of P15-TSK-017): it would be the change vouching for itself.
    plant activation-with-its-own-record 1 "$f" "$k12" "$a2" head:production 1,2
    # A literal in the base or a component is rendered into both GitOps environments, so it waits for both.
    b=deploy/base/kustomization.yaml
    c=deploy/components/secret-delivery/kustomization.yaml
    plant base-activation-with-no-record 1 "$b" "$k12" "$a2"
    plant base-activation-with-one-environment-seen 1 "$b" "$k12" "$a2" staging 1,2
    plant base-activation-with-both-seen 0 "$b" "$k12" "$a2" staging 1,2 production 1,2
    plant component-activation-with-no-record 1 "$c" "$k12" "$a2"
    plant component-activation-with-both-seen 0 "$c" "$k12" "$a2" staging 1,2 production 1,2

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
