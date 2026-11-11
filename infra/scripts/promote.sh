#!/bin/sh
# Promote a release by digest (P15-TSK-017, ADR-0122 decision 4, C8; OPERATIONS_RUNBOOK.md section 11.36).
#
# A release is a pair of digests, the application's and the migrator's, built once from one commit. An environment runs the pair its
# overlay names, and under pull-based GitOps the merge of the change that writes that pair is the deploy: Argo CD syncs it. So every
# refusal is made here, before anything is written, and the same judgements run again on the pull request.
#
# REFUSED, EACH BY NAME (PromotionTest and CI's admission job hold every one, beside the same command accepting the good case):
#   - a tag, a name beside the digest, a malformed digest, or the placeholder: only `sha256:` and sixty-four hexadecimal characters;
#   - a digest without its signature, or without either attestation (SLSA provenance v1 and the CycloneDX SBOM), under the
#     environment's authority, with each attestation's subject that digest and the provenance's commit the revision named;
#   - for production, a pair staging never ran, or ran without a passing smoke record of that pair (deploy/evidence/staging);
#   - a rollback past a contract marker: a release older than a marker in a migration the environment's schema holds
#     (DATA_MIGRATIONS.md section 4.1). A rollback is a promotion of an older pair, the revert pull request included.
#
# WHAT IT NEVER DOES. It pushes nothing, signs nothing and holds no credential: it reads the registry and the signature repository with
# whatever read access the person or the pipeline role running it has, and writes two files of this repository. A copy of the digest
# and its signatures into the environment's own registry (C8: production and non-production are separate accounts) is the pipeline
# role's step and comes first, because a digest without its signatures there is refused. CI runs `verify` against a registry of its
# own and never promotes.
#
# Usage:
#   infra/scripts/promote.sh <staging|production> <app-digest> <migrator-digest> <revision>
#       check, verify both images in the environment's registry, write deploy/overlays/<environment>/{kustomization,release}.yaml
#   infra/scripts/promote.sh verify <repository>@<digest> <revision>
#       the signature and both attestations of one image, each naming its digest, the provenance naming the revision
# Env:
#   PROMOTE_REGISTRY    the environment's registry host; its repositories are finapp-<environment>-app and -migrator
#   PROMOTE_SIGNATURES  the environment's signature repository (cosign's COSIGN_REPOSITORY), finapp-<environment>-signatures
#   PROMOTE_KEY         a public key file: the authority, as deploy/admission's patched policy names it; or, keyless,
#   PROMOTE_IDENTITY and PROMOTE_ISSUER, exactly the identity and issuer the environment's policy names (THREAT_MODEL.md item 35)
#   PROMOTE_DOCKER_NETWORK  a Docker network cosign's container joins (CI's registry is on kind's)
#   PROMOTE_LOCAL_REGISTRY  set to 1 only for a registry at a private address (CI's): plain HTTP and no transparency log, which a
#                       registry with a name is refused, so the aid can never weaken a real environment's check
# Exit: 0 accepted (and written); 1 refused; 2 could not be judged, never a pass.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
# shellcheck source=infra/scanner-pins.sh
. "$ROOT/infra/scanner-pins.sh"
COSIGN_IMAGE="$COSIGN_REPOSITORY:$COSIGN_VERSION@$COSIGN_DIGEST"
TOOL="$ROOT/infra/scripts/Promotion.java"
export MSYS_NO_PATHCONV=1

refused() {
    echo "REFUSED: $*"
    exit 1
}

cannot() {
    echo "CANNOT JUDGE: $*"
    exit 2
}

dpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# The authority and the flags, from the environment, checked before anything runs.
authority() {
    SIGNATURES=${PROMOTE_SIGNATURES:-}
    [ -n "$SIGNATURES" ] || cannot "PROMOTE_SIGNATURES names no signature repository, so no signature can be found"
    KEYDIR=""
    if [ -n "${PROMOTE_KEY:-}" ]; then
        [ -z "${PROMOTE_IDENTITY:-}" ] || cannot "name one authority: PROMOTE_KEY or PROMOTE_IDENTITY, not both"
        [ -s "$PROMOTE_KEY" ] || cannot "PROMOTE_KEY names no readable key file"
        KEYDIR=$(CDPATH= cd -- "$(dirname -- "$PROMOTE_KEY")" && pwd)
        set -- --key "/keys/$(basename -- "$PROMOTE_KEY")"
    elif [ -n "${PROMOTE_IDENTITY:-}" ] && [ -n "${PROMOTE_ISSUER:-}" ]; then
        set -- --certificate-identity "$PROMOTE_IDENTITY" --certificate-oidc-issuer "$PROMOTE_ISSUER"
    else
        cannot "no authority: PROMOTE_KEY, or PROMOTE_IDENTITY with PROMOTE_ISSUER"
    fi
    AUTHORITY="$*"
}

# local_flags <reference>: the plain-HTTP aid, for a registry at a private address alone.
local_flags() {
    if [ "${PROMOTE_LOCAL_REGISTRY:-}" != 1 ]; then
        printf '%s' ""
        return
    fi
    case ${1%%/*} in
        10.*:* | 192.168.*:* | 172.1[6-9].*:* | 172.2[0-9].*:* | 172.3[01].*:*) ;;
        *) cannot "PROMOTE_LOCAL_REGISTRY is for a registry at a private address, and ${1%%/*} is not one" ;;
    esac
    [ -n "${PROMOTE_KEY:-}" ] || cannot "PROMOTE_LOCAL_REGISTRY ignores the transparency log, which only a key authority may"
    printf '%s' "--insecure-ignore-tlog=true --new-bundle-format=false --allow-http-registry"
}

run_cosign() {
    if [ -n "$KEYDIR" ]; then
        set -- -v "$(dpath "$KEYDIR"):/keys:ro" "$@"
    fi
    if [ -n "${PROMOTE_DOCKER_NETWORK:-}" ]; then
        set -- --network "$PROMOTE_DOCKER_NETWORK" "$@"
    fi
    docker run --rm -e HOME=/tmp -e COSIGN_REPOSITORY="$SIGNATURES" "$@"
}

# verify <repository>@<digest> <revision>
verify() {
    reference=$1 revision=$2
    case $reference in
        *@sha256:*) ;;
        *) refused "$reference is not named by a digest: a tag is never promoted" ;;
    esac
    digest=${reference##*@}
    repository=${reference%@*}
    printf '%s' "$digest" | grep -Eq '^sha256:[0-9a-f]{64}$' || refused "$reference: '$digest' is not a digest"
    case ${repository##*/} in
        *:*) refused "$reference carries a tag beside its digest: a tag is never promoted" ;;
    esac
    printf '%s' "$revision" | grep -Eq '^[0-9a-f]{40}$' || refused "the revision must be a full commit, not '$revision'"
    authority
    insecure=$(local_flags "$reference")
    command -v docker >/dev/null 2>&1 || cannot "docker is not on the PATH: cosign runs in its pinned image"

    work=$(mktemp -d)
    # shellcheck disable=SC2086
    if ! run_cosign "$COSIGN_IMAGE" verify $AUTHORITY $insecure "$reference" > "$work/verify.out" 2> "$work/verify.err"; then
        sed 's/^/     /' "$work/verify.err"
        rm -rf "$work"
        refused "$reference has no signature the environment's authority accepts"
    fi
    grep -q "\"docker-manifest-digest\":\"$digest\"" "$work/verify.out" || { rm -rf "$work"; refused "the signature verified for $reference names another digest"; }
    for type in slsaprovenance1 cyclonedx; do
        # shellcheck disable=SC2086
        if ! run_cosign "$COSIGN_IMAGE" verify-attestation $AUTHORITY $insecure --type "$type" "$reference" > "$work/$type.out" 2> "$work/$type.err"; then
            sed 's/^/     /' "$work/$type.err"
            rm -rf "$work"
            refused "$reference has no $type attestation the environment's authority accepts"
        fi
        # Every statement verified names this digest as its subject: an attestation of another image is not this one's.
        statements=$(sed -n 's/.*"payload":"\([^"]*\)".*/\1/p' "$work/$type.out")
        [ -n "$statements" ] || { rm -rf "$work"; refused "cosign accepted no $type statement for $reference"; }
        for payload in $statements; do
            statement=$(printf '%s' "$payload" | base64 -d 2>/dev/null || true)
            printf '%s' "$statement" | grep -q "\"sha256\":\"${digest#sha256:}\"" \
                || { rm -rf "$work"; refused "a $type attestation verified for $reference names another subject"; }
            if [ "$type" = slsaprovenance1 ] && printf '%s' "$statement" | grep -q "\"gitCommit\":\"$revision\""; then
                built_from=1
            fi
        done
    done
    rm -rf "$work"
    [ -n "${built_from:-}" ] || refused "no provenance verified for $reference says it was built from $revision"
    unset built_from
    echo "ok   $reference: signed, both attestations naming its digest, built from $revision"
}

case ${1:-} in
    verify)
        [ $# -eq 3 ] || cannot "usage: $0 verify <repository>@<digest> <revision>"
        verify "$2" "$3"
        exit 0
        ;;
    staging | production)
        [ $# -eq 4 ] || cannot "usage: $0 <staging|production> <app-digest> <migrator-digest> <revision>"
        ;;
    *)
        cannot "usage: $0 <staging|production> <app-digest> <migrator-digest> <revision> | verify <reference> <revision>"
        ;;
esac

ENVIRONMENT=$1 APP=$2 MIGRATOR=$3 REVISION=$4
# The repository's refusals first: they need no registry, and a tag never reaches cosign.
java --source 21 "$(dpath "$TOOL")" check "$(dpath "$ROOT")" "$ENVIRONMENT" "$APP" "$MIGRATOR" "$REVISION" || exit $?
REGISTRY=${PROMOTE_REGISTRY:-}
[ -n "$REGISTRY" ] || cannot "PROMOTE_REGISTRY names no registry, so neither image can be verified"
verify "$REGISTRY/finapp-$ENVIRONMENT-app@$APP" "$REVISION"
verify "$REGISTRY/finapp-$ENVIRONMENT-migrator@$MIGRATOR" "$REVISION"
java --source 21 "$(dpath "$TOOL")" write "$(dpath "$ROOT")" "$ENVIRONMENT" "$APP" "$MIGRATOR" "$REVISION"
echo "Commit deploy/overlays/$ENVIRONMENT/kustomization.yaml and release.yaml together, in a pull request of their own."
