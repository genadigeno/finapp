#!/bin/sh
# Static analysis of the production source (P15-TSK-006; PHASE_15_PLAN.md section 8; ADR-0122 decision 2;
# THREAT_MODEL.md T5, T43, T45).
#
# Semgrep OSS, pinned by digest, over the rules under infra/semgrep/rules, and with no network: the container runs
# --network none, so no rule can be fetched and no result can leave.
#
# THREE STAGES, each of which can fail the job:
#
#   1. The rules' own tests. `semgrep --test` over the annotated files beside each rule: every `ruleid:` line must be
#      found and no `ok:` line may be.
#   2. The tree. Every module's src/main, found by pattern rather than listed, so a new module is scanned without anyone
#      adding it. Every finding must have its row in infra/semgrep/baseline.tsv (a reason and an expiry each), every row
#      its finding, and the scanner must have read every Java file it was given (infra/semgrep/baseline.py).
#   3. The plants. A check that is wired but cannot fail reads as assurance it does not give, so the plants go through
#      the same scan and the same comparison as the tree, and each must be refused and named:
#
#        sql-concatenation  a statement built from a value          finapp-sql-text-not-constant (T45)
#        sql-helper         a private helper given a built text     finapp-sql-helper-given-non-constant (T45)
#        sql-builder-name   a builder's text read through a name    finapp-sql-text-not-constant (T45)
#        unbounded-body     a provider's answer read whole          finapp-provider-body-unbounded (T43)
#        weak-digest        MD5                                     finapp-java-weak-digest
#        cors-wildcard      every origin allowed                    finapp-spring-cors-wildcard
#        secret-in-source   a generated private-key header          finapp-secret-private-key-block
#
# WHAT THE TREE EXCLUDES: test sources, which are not shipped and concatenate SQL freely. Nothing else: the scan runs on a
# copy of the module trees with an empty .semgrepignore, because Semgrep's defaults skip any path containing test/ or
# build/, a package of that name included, and a skipped file reads as a clean one.
#
# SEMGREP_IMAGE overrides the image, as the other scripts allow; CI never sets it.
#
# Usage:   infra/scripts/sast.sh                the three stages, what CI runs
#          infra/scripts/sast.sh --plants-only  stages 1 and 3 only, for someone changing a rule (CI never passes it)
# Exit:    0 every stage held; 1 a finding, a stale or expired row, a rule test failing or a plant not refused;
#          2 a step could not run. A scan that cannot run never looks like a scan that passed.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)

# ---------------------------------------------------------------------------------------------------
# Inside the Semgrep container: /repo is the repository, read-only; /scratch is this run's volume.
# ---------------------------------------------------------------------------------------------------
RULES=/repo/infra/semgrep/rules
BASELINE=/repo/infra/semgrep/baseline.tsv
COMPARE="python3 -I /repo/infra/semgrep/baseline.py"

# The one definition of a scan: --timeout=0 and --max-target-bytes=0 because Semgrep otherwise skips a rule that takes
# too long on a file and a file past a megabyte, either of which reads as a clean file.
semgrep_scan() {
    (cd "$1" && semgrep scan --config "$RULES" --metrics=off --disable-version-check --no-git-ignore \
        --timeout=0 --timeout-threshold=0 --max-target-bytes=0 --quiet --json --output "$2" .)
}

in_container() {
    export SEMGREP_SEND_METRICS=off SEMGREP_ENABLE_VERSION_CHECK=0
    step=$1
    case $step in
        rule-tests)
            semgrep --test --metrics=off --disable-version-check --config "$RULES" "$RULES"
            ;;
        tree)
            rm -rf /scratch/tree
            mkdir -p /scratch/tree
            modules=0
            for main in /repo/*/src/main; do
                [ -d "$main" ] || continue
                module=$(basename "$(dirname "$(dirname "$main")")")
                mkdir -p "/scratch/tree/$module/src"
                cp -R "$main" "/scratch/tree/$module/src/main"
                modules=$((modules + 1))
            done
            [ "$modules" -gt 0 ] || { echo "?? no module's src/main was found"; exit 2; }
            : > /scratch/tree/.semgrepignore
            expected=$(find /scratch/tree -name '*.java' | wc -l)
            echo "--   $modules modules, $expected Java files"
            semgrep_scan /scratch/tree /scratch/tree.json
            $COMPARE /scratch/tree.json "$BASELINE" /scratch/tree "$expected"
            ;;
        plants)
            rm -rf /scratch/plants
            mkdir -p /scratch/plants/secret-in-source
            # One small file per planted shape, so each plant's name means what it says: a rule that fired only on
            # another plant's shape would not pass this one.
            for plant in /repo/infra/semgrep/plants/*/; do
                name=$(basename "$plant")
                mkdir -p "/scratch/plants/$name"
                cp "$plant"Plant.java "/scratch/plants/$name/Plant.java"
            done
            # Written here, in pieces, so that no key header is ever committed (P0-TSK-031's rule).
            printf 'package plant;\nclass Plant { String pem = "-----%s RSA %s-----"; }\n' "BEGIN" "PRIVATE KEY" \
                > /scratch/plants/secret-in-source/Plant.java
            : > /scratch/plants/.semgrepignore
            expected=$(find /scratch/plants -name '*.java' | wc -l)
            semgrep_scan /scratch/plants /scratch/plants.json
            $COMPARE /scratch/plants.json "$BASELINE" /scratch/plants "$expected"
            ;;
        *)
            echo "unknown step: $step" >&2
            exit 2
            ;;
    esac
}

if [ "${1:-}" = "--in-container" ]; then
    shift
    in_container "$@"
    exit $?
fi

# ---------------------------------------------------------------------------------------------------
# On the host (a laptop or CI's runner).
# ---------------------------------------------------------------------------------------------------
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

SEMGREP_IMAGE="${SEMGREP_IMAGE:-${SEMGREP_REPOSITORY}@${SEMGREP_DIGEST}}"

if ! docker info >/dev/null 2>&1; then
    echo "Docker is not available: the analysis runs in a pinned image." >&2
    exit 2
fi

VOLUME="finapp-sast-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
docker volume create "$VOLUME" >/dev/null
trap 'docker volume rm -f "$VOLUME" >/dev/null 2>&1 || true' EXIT
trap 'exit 130' INT TERM

# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /repo and /scratch into host paths.
in_semgrep() {
    MSYS_NO_PATHCONV=1 docker run --rm --network none --entrypoint /bin/sh \
        -v "$ROOT:/repo:ro" -v "$VOLUME:/scratch" \
        "$SEMGREP_IMAGE" /repo/infra/scripts/sast.sh --in-container "$@"
}

status=0

# check <label> <command...>: the command must succeed; exit 2 (could not run) stays 2.
check() {
    label=$1
    shift
    echo "==   $label"
    code=0
    "$@" || code=$?
    case $code in
        0) echo "ok   $label" ;;
        2) echo "??   $label could not run"; exit 2 ;;
        *) echo "!!   $label FAILED"; status=1 ;;
    esac
}

check "the rules' own tests" in_semgrep rule-tests
if [ "${1:-}" != "--plants-only" ]; then
    check "the tree: every module's src/main, every finding accepted with a reason" in_semgrep tree
fi

# The plants go through one scan, then each must have been reported by name, for its own file.
echo "==   the plants: each must be refused, by name"
output=""
code=0
output=$(in_semgrep plants 2>&1) || code=$?
if [ "$code" -eq 2 ]; then
    printf '%s\n' "$output"
    echo "??   the plants could not be scanned"
    exit 2
fi
if [ "$code" -eq 0 ]; then
    printf '%s\n' "$output"
    echo "!!   the plants were NOT refused: the comparison accepted them all"
    status=1
fi

# refuses <plant> <rule>: the report must name that rule for that plant's file.
refuses() {
    if printf '%s\n' "$output" | grep -E "^!! $2 $1/" >/dev/null 2>&1; then
        echo "ok   plant $1 refused: $2"
    else
        printf '%s\n' "$output" | grep -E "$1/" | head -5 || true
        echo "!!   plant $1 was NOT refused by $2"
        status=1
    fi
}

refuses sql-concatenation finapp-sql-text-not-constant
refuses sql-helper finapp-sql-helper-given-non-constant
refuses sql-builder-name finapp-sql-text-not-constant
refuses unbounded-body finapp-provider-body-unbounded
refuses weak-digest finapp-java-weak-digest
refuses cors-wildcard finapp-spring-cors-wildcard
refuses secret-in-source finapp-secret-private-key-block

if [ "$status" -eq 0 ]; then
    echo "ok   the rules' tests passed, the tree holds nothing new, every plant refused by name"
fi
exit $status
