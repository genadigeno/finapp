#!/bin/sh
# Refuses a change that activates a key version not already listed as held at its base (P15-TSK-019, ADR-0123 point 4).
#
# A ring's activation is two steps across N replicas. First a change lists the new version in the ring's versions and the fleet
# rolls; a replica that cannot hold every listed version refuses to start, so a finished rollout means every replica holds it. Then
# a later change moves the active version. A single change doing both would have replicas writing under a version that a replica
# still rolling has never heard of. This compares a change with its base and refuses exactly that: an active version that moved to
# a version the base did not already list. Order is what a rendered manifest cannot show, so this needs the base commit.
#
# WHAT IT CANNOT PROVE. It compares two commits, so it proves the change was two commits, not that the first rollout finished
# (OPERATIONS_RUNBOOK.md section 11.29 states that gate; delivery order is P15-TSK-017's once a GitOps controller applies these).
#
# It reads every file git tracks under deploy/, the literal lines `- FINAPP_..._RING_ACTIVEVERSION=<n>` and
# `- FINAPP_..._RING_VERSIONS=<list>` that the kustomizations carry. KeyRingDeployConfigurationTest holds every ring literal under
# deploy/ to that one form, so none can be written in a shape this cannot read.
#
# Usage:
#   infra/scripts/key-ring-activation-check.sh <base-ref>
#
# Exit status: 0 no active version moved ahead of what its base listed; 1 refused; 2 usage, or the base could not be read.
set -eu

BASE=${1:-}
[ -n "$BASE" ] || { echo "usage: $0 <base-ref>" >&2; exit 2; }
git rev-parse --verify --quiet "$BASE^{commit}" > /dev/null || { echo "cannot read base $BASE: no history to compare, so nothing proved" >&2; exit 2; }

# The value of the literal NAME in the text on stdin, without spaces; nothing if absent.
literal() {
    sed -n "s/^ *- *$1=\([0-9, ]*\) *\$/\1/p" | tail -1 | tr -d ' '
}

status=0
for file in $(git ls-files 'deploy/*.yaml'); do
    for name in $(sed -n 's/^ *- *\(FINAPP_[A-Z0-9_]*_RING_ACTIVEVERSION\)=.*/\1/p' "$file"); do
        head=$(literal "$name" < "$file")
        base=$(git show "$BASE:$file" 2>/dev/null | literal "$name")
        [ "$head" != "$base" ] || continue
        versions=${name%ACTIVEVERSION}VERSIONS
        listed=$(git show "$BASE:$file" 2>/dev/null | literal "$versions")
        # No list at the base means the ring was its base key alone (ConfinedKeyRing's default), version 1 unless key-version says otherwise.
        listed=${listed:-1}
        case ",$listed," in
            *",$head,"*) ;;
            *)
                echo "$file: $name moves to version $head, which $BASE does not list in $versions ($listed)" >&2
                echo "  list the version in one change, let the rollout finish, then activate it in a later one" >&2
                status=1
                ;;
        esac
    done
done
exit "$status"
