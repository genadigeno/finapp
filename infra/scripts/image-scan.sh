#!/bin/sh
# Scans every image built from this commit - vulnerabilities, secrets, misconfiguration - and writes one CycloneDX
# SBOM per image (P15-TSK-006; PHASE_15_PLAN.md section 8; ADR-0122 decision 2; THREAT_MODEL.md T5, T6).
#
# HIGH AND CRITICAL FAIL, in every run. A finding is carried only if infra/trivy/accepted-findings.yaml names it, with a
# reason and an expiry, and THREAT_MODEL.md section 6 records it (held there by SecurityScansAreWiredTest). An expired
# entry stops applying by itself: Trivy reads expired_at, and the finding fails the job again.
#
# EVERY IMAGE BUILT. The default is every local image tagged finapp-*:<this commit>, what image-build.sh and its
# siblings leave, so an image added later is scanned without anyone listing it; the application image is required, so a
# build that produced nothing cannot pass as nothing to scan. Arguments name images instead.
#
# THEN THE PLANTS. A scan that is wired but cannot fail reads as assurance it does not give. Every run builds five
# images a scan must refuse, and requires it to fail AND to name the planted finding in its output. A plant accepted, or
# refused for another reason, fails this script:
#
#   vulnerable-base   a real old base image, pinned by digest        CVE (a vulnerable base fails the job)
#   high-only         a library whose only finding is HIGH           CVE-2021-23337: lowering the bar to CRITICAL passes
#                                                                    this plant, and so fails the script
#   secret-in-layer   a generated token in a file under /app         Trivy's github-pat rule
#   secret-in-env     a generated key in the image's environment     Trivy's aws-access-key-id rule
#   root-user         an image that runs as root                     Trivy's DS-0002 check
#
# A SCAN THAT READ NOTHING MUST NOT PASS. Each image's SBOM must list its operating-system packages, every jar in the
# image by its path, and no test-scope library: an unreadable image, a path filter or a broken analyzer shows up here
# rather than as a clean report. An image whose filesystem Trivy cannot open never produces an SBOM.
#
# HOW THE IMAGE REACHES THE SCANNER. `docker save` to a tar, read with --input: the scanner is never given the Docker
# socket, so nothing it does can reach the host's containers.
#
# THE DATABASES are downloaded once per run into a volume this run owns and removes, never cached between runs: the
# weekly scheduled run is worth having only because it reads a new database. TRIVY_IMAGE overrides the image for a
# machine whose TLS is intercepted (OPERATIONS_RUNBOOK.md section 11.9), exactly as in dependency-scan.sh. CI never sets it.
#
# Usage:   infra/scripts/image-scan.sh [image ...]
# Leaves:  build/image-sboms/<name>.cdx.json, one per image scanned.
# Exit:    0 every image clean and every plant refused for its reason; 1 a finding, a failed assertion or a plant not
#          refused; 2 a step could not run. A scan that cannot run never looks like a scan that passed.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"
# shellcheck disable=SC1091
. "$ROOT/infra/scanner-pins.sh"

TRIVY_IMAGE="${TRIVY_IMAGE:-${TRIVY_REPOSITORY}@${TRIVY_DIGEST}}"

# The bar. Held by SecurityScansAreWiredTest and by the high-only plant below.
SEVERITY=HIGH,CRITICAL

# A real old base, its version pinned to a digest so the plant cannot change under the script. Nothing runs it.
VULNERABLE_BASE=alpine:3.10.0@sha256:ca1c944a4f8486a153024d9965aafbe24f5723c1d5c02f4964c045a16d19dc54

# The exit code Trivy gives for findings, so a finding (1 here) is told from a scan that could not run (2 here).
FOUND=42

ACCEPTED=infra/trivy/accepted-findings.yaml
SBOMS=build/image-sboms

fail() {
    echo "!! $*" >&2
    exit 1
}

cannot() {
    echo "?? $*" >&2
    exit 2
}

# MSYS_NO_PATHCONV: Git Bash on Windows otherwise rewrites arguments that look like absolute paths.
dock() {
    MSYS_NO_PATHCONV=1 docker "$@"
}

for tool in git docker tar grep sed; do
    command -v "$tool" >/dev/null 2>&1 || cannot "$tool is not on the PATH"
done
dock info >/dev/null 2>&1 || cannot "Docker is not available: the scanner runs in a pinned image"
[ -f "$ROOT/$ACCEPTED" ] || cannot "$ACCEPTED is missing"

mkdir -p "$ROOT/build" "$ROOT/$SBOMS"
# Under the repository, not /tmp: Docker can bind-mount this on Docker Desktop as well as on CI's runner.
WORK=$(mktemp -d "$ROOT/build/image-scan.XXXXXX")
# One volume per run, so two runs on one Docker host never share a database. The process id alone can repeat across
# containers sharing a host's Docker, so a random suffix goes with it.
VOLUME="finapp-image-scan-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
PLANTS=""
cleanup() {
    for image in $PLANTS; do
        dock image rm -f "$image" >/dev/null 2>&1 || true
    done
    dock volume rm -f "$VOLUME" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
dock volume create "$VOLUME" >/dev/null

trivy() {
    dock run --rm -v "$VOLUME:/cache" -v "$WORK:/work" -v "$ROOT/infra/trivy:/accepted:ro" \
        -v "$ROOT/$SBOMS:/sboms" "$TRIVY_IMAGE" "$@"
}

# The one definition of what a scan is. The real images and every plant go through this, flags and acceptances
# included, so a plant cannot pass or fail for a reason the real scan does not share.
scan() {
    trivy image --cache-dir /cache --skip-db-update --skip-java-db-update --skip-check-update --no-progress \
        --scanners vuln,secret,misconfig --image-config-scanners misconfig,secret \
        --severity "$SEVERITY" --exit-code "$FOUND" \
        --ignorefile /accepted/accepted-findings.yaml \
        --input "/work/$1"
}

# What this script scans: the arguments, or every image tagged with this commit.
REVISION=$(git rev-parse HEAD)
if [ -n "$(git status --porcelain)" ]; then
    REVISION="$REVISION-dirty"
fi
if [ "$#" -gt 0 ]; then
    IMAGES=$*
else
    IMAGES=$(dock image ls --format '{{.Repository}}:{{.Tag}}' --filter "reference=finapp-*:$REVISION" | sort -u | tr '\n' ' ')
    case " $IMAGES " in
        *" finapp-app:$REVISION "*) ;;
        *) cannot "no finapp-app:$REVISION to scan: run infra/scripts/image-build.sh first (or name the images)" ;;
    esac
fi
echo "== the images: $IMAGES"

echo "== the vulnerability databases, this run's own"
trivy image --cache-dir /cache --download-db-only --no-progress || cannot "the vulnerability database could not be downloaded"
trivy image --cache-dir /cache --download-java-db-only --no-progress || cannot "the Java database could not be downloaded"

status=0

# The SBOM and the assertions that it describes what the image holds.
sbom_of() {
    image=$1 tar=$2
    name=$(printf '%s' "${image%:*}" | tr '/' '_')
    out="$SBOMS/$name.cdx.json"
    trivy image --cache-dir /cache --skip-db-update --skip-java-db-update --no-progress \
        --format cyclonedx --output "/sboms/$name.cdx.json" --input "/work/$tar" \
        || cannot "the SBOM of $image could not be written"
    [ -s "$out" ] || fail "the SBOM of $image is empty: $out"

    container=$(dock create "$image" /nothing-runs)
    dock export "$container" > "$WORK/fs.tar" || { dock rm -f "$container" >/dev/null 2>&1; cannot "could not export $image"; }
    dock rm -f "$container" >/dev/null
    tar -tf "$WORK/fs.tar" | sort > "$WORK/fs.entries"
    rm -f "$WORK/fs.tar"

    grep -q '"purl": *"pkg:deb/' "$out" || grep -q '"pkg:deb/' "$out" \
        || fail "the SBOM of $image lists no operating-system package: the scanner did not read the image's base"

    jars=$(grep -E '^(app|flyway)/.*\.jar$' "$WORK/fs.entries" || true)
    count=0
    missing=""
    for jar in $jars; do
        count=$((count + 1))
        grep -qF "$jar" "$out" || missing="$missing $jar"
    done
    [ -z "$missing" ] || fail "the SBOM of $image omits jars the image holds:$missing"
    grep -Eq '"pkg:maven/(org\.junit|org\.mockito|org\.assertj|org\.testcontainers|com\.tngtech\.archunit)' "$out" \
        && fail "the SBOM of $image lists a test-scope library, so it overstates what ships"
    echo "ok   the SBOM of $image: $out, $count jars by path, operating-system packages listed, no test-scope library"
}

# The real images.
index=0
for image in $IMAGES; do
    index=$((index + 1))
    tar="image-$index.tar"
    dock image inspect "$image" >/dev/null 2>&1 || cannot "no such local image: $image"
    echo "== $image"
    dock save -o "$WORK/$tar" "$image" || cannot "could not save $image"
    sbom_of "$image" "$tar"
    code=0
    scan "$tar" || code=$?
    case $code in
        0) echo "ok   $image: no finding at $SEVERITY beyond those accepted" ;;
        "$FOUND") echo "!!   $image carries a finding at $SEVERITY (above)"; status=1 ;;
        *) cannot "the scan of $image could not run (exit $code)" ;;
    esac
    rm -f "$WORK/$tar"
done

# ---------------------------------------------------------------------------------------------------
# The plants.
# ---------------------------------------------------------------------------------------------------

# refuses <plant> <reason> <image>: the scan must find something, and its output must name the reason.
refuses() {
    label=$1 reason=$2 image=$3
    tar="plant-$label.tar"
    dock save -o "$WORK/$tar" "$image" >/dev/null || cannot "could not save the plant $label"
    code=0
    output=$(scan "$tar" 2>&1) || code=$?
    rm -f "$WORK/$tar"
    if [ "$code" -eq 0 ]; then
        printf '%s\n' "$output"
        echo "!!   plant $label was NOT refused"
        status=1
    elif [ "$code" -ne "$FOUND" ]; then
        printf '%s\n' "$output"
        cannot "the scan of the plant $label could not run (exit $code)"
    elif printf '%s\n' "$output" | grep -qF -- "$reason"; then
        echo "ok   plant $label refused: $(printf '%s\n' "$output" | grep -F -m 1 -- "$reason" | tr -s ' ' | cut -c1-140)"
    else
        printf '%s\n' "$output"
        echo "!!   plant $label was refused, but not for its reason: the output never says '$reason'"
        status=1
    fi
}

# plant <label>: builds $WORK/plants/<label> (a Dockerfile and its context), echoes the image.
plant() {
    tag="finapp-scan-plant-$1:$$"
    PLANTS="$PLANTS $tag"
    dock buildx build --quiet --provenance=false --sbom=false --load -t "$tag" "$WORK/plants/$1" >/dev/null \
        || cannot "could not build the plant $1"
    printf '%s' "$tag"
}

# A token in a shape only its issuer gives, generated here so that the repository never holds one (gitleaks scans this
# file's history: P0-TSK-031's rule) and written only into a throwaway image.
token() {
    od -An -N"$2" -tx1 /dev/urandom | tr -d ' \n' | tr 'a-f' "$1" | cut -c1-"$3"
}

mkdir -p "$WORK/plants"

mkdir -p "$WORK/plants/high-only/node_modules/lodash"
printf '{"name":"lodash","version":"4.17.20"}\n' > "$WORK/plants/high-only/node_modules/lodash/package.json"
printf 'FROM scratch\nCOPY node_modules /app/node_modules\nUSER 65532:65532\n' > "$WORK/plants/high-only/Dockerfile"

mkdir -p "$WORK/plants/secret-in-layer"
printf 'provider.token=ghp_%s\n' "$(token ABCDEF 36 36)" > "$WORK/plants/secret-in-layer/application-plant.properties"
printf 'FROM scratch\nCOPY application-plant.properties /app/config/application-plant.properties\nUSER 65532:65532\n' \
    > "$WORK/plants/secret-in-layer/Dockerfile"

mkdir -p "$WORK/plants/secret-in-env"
printf 'FROM scratch\nCOPY Dockerfile /app/Dockerfile\nENV AWS_ACCESS_KEY_ID=AKIA%s\nUSER 65532:65532\n' \
    "$(token ABCDEF 8 16 | tr '0-9' 'GHIJKLMNOP')" > "$WORK/plants/secret-in-env/Dockerfile"

mkdir -p "$WORK/plants/root-user"
printf 'FROM scratch\nCOPY Dockerfile /app/Dockerfile\nUSER root\n' > "$WORK/plants/root-user/Dockerfile"

echo "== the plants: each must be refused, by name"
dock pull -q "$VULNERABLE_BASE" >/dev/null || cannot "could not pull the plant base $VULNERABLE_BASE"
refuses vulnerable-base "CVE-" "$VULNERABLE_BASE"
refuses high-only "CVE-2021-23337" "$(plant high-only)"
refuses secret-in-layer "github-pat" "$(plant secret-in-layer)"
refuses secret-in-env "aws-access-key-id" "$(plant secret-in-env)"
refuses root-user "DS-0002" "$(plant root-user)"

if [ "$status" -eq 0 ]; then
    echo "ok   every image clean at $SEVERITY, every plant refused for its reason"
fi
exit $status
