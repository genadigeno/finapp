#!/bin/sh
# Scan git history for secrets.
#
# This script is the ONE definition of that scan. CI calls it (.github/workflows/ci.yml,
# job `secret-scan`) and so does a developer, so there is no second copy of the image pin
# or the arguments to drift from this one. The CI workflow used to hold both and claimed in
# a comment that "the exact same command can be run locally" - which was true only for
# somebody willing to retype a sha256 digest by hand.
#
# Usage:
#   infra/scripts/secret-scan.sh [repository-path]
#
# Exit status is gitleaks' own: 0 clean, 1 leaks found. That is what fails the CI job.
#
#   The optional path exists so the scan can be pointed at a throwaway repository to prove
#   it still has teeth - see docs/architecture/SECRET_MANAGEMENT.md, "Proving the scan
#   fails". A dummy secret must NEVER be committed to this repository to test it: gitleaks
#   scans the whole of history, so the commit that proved the scan works would make the
#   scan red for ever, and removing it needs a history rewrite.
set -eu

# Pinned by digest, never by tag: a tag can be repointed by whoever controls the source
# repository, a digest cannot. This is the same reasoning that pins the Gradle distribution
# and the wrapper jar by SHA-256. Keeping the pin maintained is P0-TSK-040.
#
# gitleaks v8.30.1
GITLEAKS_IMAGE="${GITLEAKS_IMAGE:-ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f}"

REPO="${1:-$(pwd)}"

# No allowlist configuration is passed, deliberately. The marked local-development default
# in compose.yaml does not trigger gitleaks - verified, not assumed - so suppressing
# anything would weaken the scan for no benefit. A genuine false positive is allowlisted as
# that one finding, narrowly. A rule is never disabled.
#
# `git`, not `dir`: a secret that was committed and later removed is still disclosed,
# because it remains in the objects anyone can clone.
#
# --redact so a finding does not reprint the secret into a build log that is retained longer
# and read more widely than the commit that leaked it.
#
# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /repo into a host path. It is unset
# and harmless on Linux, where CI runs.
MSYS_NO_PATHCONV=1 exec docker run --rm \
  -v "${REPO}:/repo" \
  "${GITLEAKS_IMAGE}" \
  git /repo --no-banner --redact --verbose
