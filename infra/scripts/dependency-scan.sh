#!/bin/sh
# Scan a CycloneDX SBOM for known vulnerabilities.
#
# The counterpart to secret-scan.sh, and it exists for the same two reasons: CI calls the script
# a developer runs, so the image and the arguments cannot drift apart; and the pin lives in
# infra/scanner-pins.sh where infra/scripts/check-pinned-images.sh can see it (P0-TSK-040, ADR-0026).
# Trivy's digest was an `env:` value in the workflow until then - a place no tool reads.
#
# Usage:
#   ./gradlew :app:cyclonedxBom
#   infra/scripts/dependency-scan.sh [sbom-path]
#
# Exit status is Trivy's own: 0 clean, 1 a HIGH or CRITICAL finding. That is what fails the job.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

TRIVY_IMAGE="${TRIVY_IMAGE:-${TRIVY_REPOSITORY}@${TRIVY_DIGEST}}"
SBOM="${1:-app/build/reports/cyclonedx/application.cdx.json}"

if [ ! -f "$ROOT/$SBOM" ]; then
    echo "No SBOM at $SBOM. Generate it first: ./gradlew :app:cyclonedxBom" >&2
    exit 2
fi

# HIGH and CRITICAL fail; MEDIUM and below are reported and do not block. A gate that fires
# constantly is a gate people learn to bypass, and the threshold should tighten deliberately
# rather than by accident.
#
# The SBOM is scanned rather than the build scripts: a scanner cannot resolve Gradle's
# dependency graph, and the SBOM is the resolved set that actually ships.
#
# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /scan into a host path. Unset and
# harmless on Linux, where CI runs.
MSYS_NO_PATHCONV=1 exec docker run --rm \
  -v "${ROOT}:/scan" \
  "${TRIVY_IMAGE}" \
  sbom --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --no-progress \
  "/scan/${SBOM}"
