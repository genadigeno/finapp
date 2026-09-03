# The pinned scanner images. ONE definition each (P0-TSK-040).
#
# Named .sh rather than .env, and not by accident: .gitignore ignores `*.env` under its Secrets
# section, so the first version of this file was silently never committed - CI would have sourced
# a file that does not exist. Renaming is the honest fix; adding an exception to a
# security-motivated ignore rule for a file that holds no secret is not.
#
# Each pin is three facts, and all three are needed:
#
#   REPOSITORY  what to pull
#   VERSION     the release the digest is claimed to be - a comment cannot be checked, so it is
#               data. infra/scripts/check-pinned-images.sh resolves it and fails if the digest
#               no longer matches, which is how a repointed tag becomes visible
#   DIGEST      what is actually pulled. A tag can be moved by whoever controls the source
#               repository; a digest cannot
#
# Sourced by infra/scripts/secret-scan.sh and infra/scripts/dependency-scan.sh, which CI calls, so
# a developer and CI run the identical image - the single-definition property P0-TSK-031
# established and this file preserves rather than replaces.
#
# UPDATING: infra/scripts/check-pinned-images.sh reports both a moved tag and a newer release.
# Bump VERSION, re-resolve DIGEST with `docker buildx imagetools inspect <repo>:<version>`, and
# review the diff. See README.md section 7b.

GITLEAKS_REPOSITORY=ghcr.io/gitleaks/gitleaks
GITLEAKS_VERSION=v8.30.1
GITLEAKS_DIGEST=sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f

TRIVY_REPOSITORY=aquasec/trivy
TRIVY_VERSION=0.74.0
TRIVY_DIGEST=sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969
