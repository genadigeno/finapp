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

# The pin lives in infra/scanner-pins.sh - one definition, alongside Trivy's, so
# infra/scripts/check-pinned-images.sh can check both and nothing has to be kept in step by
# hand (P0-TSK-040, ADR-0026). It was inline here until then, with the version in a comment
# that nothing could verify.
#
# Pinned by digest, never by tag: a tag can be repointed by whoever controls the source
# repository, a digest cannot. Same reasoning as the Gradle distribution and wrapper jar.
DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

GITLEAKS_IMAGE="${GITLEAKS_IMAGE:-${GITLEAKS_REPOSITORY}@${GITLEAKS_DIGEST}}"

REPO="${1:-$(pwd)}"

# No --config is passed, and that is not the same as no configuration: gitleaks loads
# <source>/.gitleaks.toml on its own, so the repository's own file applies here and in CI
# identically. Until 2026-09-04 there was no such file and this comment said there never
# would be. The first CI run of this repository's history - the first time the scan met our
# own commits rather than a throwaway clone - produced one finding, and it was a false
# positive. It is allowlisted as that one literal, narrowly, exactly as the sentence below
# always said it would be. A rule is never disabled. See .gitleaks.toml, which carries the
# reasoning and the demonstration that the allowlist is narrow: a real credential in the
# same file, on the same line, under the same rule still fails.
#
# The marked local-development default in compose.yaml does not trigger gitleaks - verified,
# not assumed - so nothing about it is suppressed.
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
