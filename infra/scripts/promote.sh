#!/bin/sh
# Promote a release by digest (P15-TSK-017, ADR-0122 decision 4, C8; OPERATIONS_RUNBOOK.md section 11.36).
#
# A release is a pair of digests, the application's and the migrator's, built once from one commit. An environment runs the pair its
# overlay names, and under pull-based GitOps the merge of the change that writes that pair is the deploy: Argo CD syncs it. So the
# refusals are made here, before anything is written, and what the repository alone can judge is judged again on the pull request.
#
# REFUSED, EACH BY NAME (PromotionTest and CI's admission job hold every one, beside the same command accepting the good case):
#   - a tag, a name beside the digest, a malformed digest, or the placeholder: only `sha256:` and sixty-four hexadecimal characters;
#   - a revision off this branch's first-parent history;
#   - a digest without its signature, or without either attestation (SLSA provenance v1 and the CycloneDX SBOM), under the
#     environment's authority; a verified statement whose subject is another digest, or whose provenance does not name the revision as
#     its git source's commit (each statement read as its structure, infra/scripts/Promotion.java `statement`);
#   - for production, a pair staging never ran, or ran without a passing smoke record of that pair, as committed (deploy/evidence/staging);
#   - a rollback past a contract marker: a release older than a marker in a migration the environment's schema holds
#     (DATA_MIGRATIONS.md section 4.1), and a rollback that does not keep the schema's own migrator.
#
# WHAT CI JUDGES AGAIN, AND WHAT IT CANNOT. `recheck` holds each overlay to its release record and the record to the repository's
# refusals, with staging's evidence read as it was merged before the change, so one change cannot bring its own evidence. It reads no
# registry: a release record naming a newer revision beside an older release's signed digest passes it, because nothing in the
# repository says which commit a digest was built from. That is the signatures' and the provenance's, read by `verify` here, and read
# again by the approver before approving (section 11.36). The manifest policy's `release-images` rule holds every image the overlay
# renders to the record, wherever it is named.
#
# THE CONSOLE (P17-TSK-105). The Operator Console's static server (deploy/console, X-TSK-020) is finapp-admin-panel's build, released on
# its own: one digest, never part of the platform's pair, its own Application (deploy/delivery/<environment>'s finapp-console, the root's
# last wave), its own repository (finapp-<environment>-console) and its own record (deploy/console/overlays/<environment>/release.yaml).
# `promote.sh console` promotes it as the platform's images are promoted - by digest alone, the placeholder and a tag refused, its
# signature and both attestations verified in the environment's registry under the environment's authority, every statement's subject
# its digest and the provenance's git source its revision - with three differences, each because it is another repository's build: its
# revision is finapp-admin-panel's commit, so its form is checked here and its history never is (the provenance names it, and `verify`
# reads the provenance); it holds no schema, so no contract marker bounds a rollback, which is the promotion of an older digest; and no
# console smoke exists yet, so production asks that staging was given the same digest and revision, merged before the change, and no
# smoke record of it. `recheck` judges the console's overlays against their records on every pull request, as the platform's.
#
# THE SERVING DIGEST. The record's `console` is the digest the environment serves once Argo CD has synced the merge. The next console
# build reads it through `promote.sh console-serving` (one line per environment, `none` before the first) and hands each digest named
# to finapp-admin-panel's scripts/collect-previous-assets.sh, so the new image also carries the hashed assets of the pods its rollout
# replaces (P17-TSK-104's rollout fix; the asset window DISTRIBUTED_EXECUTION.md section 3 names). Staging and production can serve
# different digests (staging ahead), and a build promoted to both must carry both. `promote.sh console` also says which digest its own
# rollout replaces; that the promoted image carries that digest's assets is the build's, and nothing here can read it.
#
# OWNER'S DECISION, OPEN (P17-TSK-105): which identity signs the console in a real environment. As committed, the console is verified
# under the environment's one authority (PROMOTE_KEY, or PROMOTE_IDENTITY with PROMOTE_ISSUER), the authority the admission policies
# hold every image of ours to, finapp-*-console included (deploy/admission/clusterimagepolicies.yaml). The PROPOSAL, for the owner to
# ratify or refuse: keyless signing from CI's identity, as THREAT_MODEL.md section 6 item 35 (a) decided for the platform's images - the
# platform's pipeline signing the console's artefact, never finapp-admin-panel's own identity. Nothing here names an identity. If the
# owner chooses another for the console, it is a second authority in both admission policies and a variable of its own here, decided
# and changed together.
#
# WHAT IT NEVER DOES. It pushes nothing, signs nothing and holds no credential of its own: it reads the registry with the docker
# configuration named (PROMOTE_DOCKER_CONFIG, mounted read-only) or none, and writes two files of this repository. A copy of the digest
# and its signatures into the environment's own registry (C8: production and non-production are separate accounts) is the pipeline
# role's step and comes first. CI runs `verify` against a registry of its own and `recheck`, and never promotes.
#
# Usage:
#   infra/scripts/promote.sh <staging|production> <app-digest> <migrator-digest> <revision>
#       check, verify both images in the environment's registry, write deploy/overlays/<environment>/{kustomization,release}.yaml
#   infra/scripts/promote.sh verify <repository>@<digest> <revision>
#       the signature and both attestations of one image, every statement's subject its digest, the provenance's source the revision
#   infra/scripts/promote.sh recheck <base-ref>
#       CI's judgement of a pull request, with no registry, against what was merged at its merge base with <base-ref>; the console's too
#   infra/scripts/promote.sh console <staging|production> <console-digest> <console-revision>
#       the Operator Console (P17-TSK-105): check, verify its image in the environment's registry against its finapp-admin-panel
#       revision, write deploy/console/overlays/<environment>/{kustomization,release}.yaml, and say what it serves and replaces
#   infra/scripts/promote.sh console-serving
#       each environment's serving console digest, as committed: the next console build's collect-previous-assets.sh arguments
#
# A ROLLBACK is a promotion of an older application with the migrator the schema already ran: the migrator stays the newest
# release's, whose run on its own schema is a no-op, and is verified against the commit it was built from.
#
# Env:
#   PROMOTE_REGISTRY    the environment's registry host; its repositories are finapp-<environment>-app, -migrator and -console
#   PROMOTE_SIGNATURES  the environment's signature repository (cosign's COSIGN_REPOSITORY), finapp-<environment>-signatures
#   PROMOTE_KEY         a public key file: the authority, as the environment's patched admission policy names it; or, keyless,
#   PROMOTE_IDENTITY and PROMOTE_ISSUER, exactly the identity and issuer the environment's policy names (THREAT_MODEL.md item 35)
#   PROMOTE_DOCKER_CONFIG  a directory holding the docker config.json with the registry's read credential, mounted read-only into
#                       cosign's container; never a write credential, and never one of CI's
#   PROMOTE_DOCKER_NETWORK  a Docker network cosign's container joins (CI's registry is on kind's)
#   PROMOTE_LOCAL_REGISTRY  set to 1 only for a registry at a private IP literal (CI's): plain HTTP and no transparency log, refused for
#                       any registry with a name, so the aid can never weaken a real environment's check
#
# THE FORMAT. With a key authority, signatures and attestations are read in cosign's tag-based form (--new-bundle-format=false), the
# form CI signs in (admission-check.sh) and the only one the policy controller's key authority reads (v0.15.1). A real environment's
# key signs through the transparency log, so the log is verified unless PROMOTE_LOCAL_REGISTRY. The keyless path is left at cosign
# v3.1.4's defaults and has run nowhere: no identity exists (item 35).
#
# Exit: 0 accepted (and written); 1 refused; 2 could not be judged (a registry that refused the read, an unreachable network, a server
# error, an answer this script does not recognise), never a pass and never reported as a missing signature.
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
        set -- --key "/keys/$(basename -- "$PROMOTE_KEY")" --new-bundle-format=false
    elif [ -n "${PROMOTE_IDENTITY:-}" ] && [ -n "${PROMOTE_ISSUER:-}" ]; then
        set -- --certificate-identity "$PROMOTE_IDENTITY" --certificate-oidc-issuer "$PROMOTE_ISSUER"
    else
        cannot "no authority: PROMOTE_KEY, or PROMOTE_IDENTITY with PROMOTE_ISSUER"
    fi
    AUTHORITY="$*"
    CONFIG=${PROMOTE_DOCKER_CONFIG:-}
    if [ -n "$CONFIG" ]; then
        [ -s "$CONFIG/config.json" ] || cannot "PROMOTE_DOCKER_CONFIG names no directory holding a config.json"
    fi
}

# local_flags <reference>: the plain-HTTP aid, for a registry at a private IP literal alone.
local_flags() {
    if [ "${PROMOTE_LOCAL_REGISTRY:-}" != 1 ]; then
        printf '%s' ""
        return
    fi
    printf '%s' "${1%%/*}" | grep -Eq '^(10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}|192\.168\.[0-9]{1,3}\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.[0-9]{1,3}\.[0-9]{1,3}):[0-9]+$' \
        || cannot "PROMOTE_LOCAL_REGISTRY is for a registry at a private IP literal, and ${1%%/*} is not one"
    [ -n "${PROMOTE_KEY:-}" ] || cannot "PROMOTE_LOCAL_REGISTRY ignores the transparency log, which only a key authority may"
    printf '%s' "--insecure-ignore-tlog=true --allow-http-registry"
}

run_cosign() {
    if [ -n "$KEYDIR" ]; then
        set -- -v "$(dpath "$KEYDIR"):/keys:ro" "$@"
    fi
    if [ -n "$CONFIG" ]; then
        set -- -v "$(dpath "$CONFIG"):/docker-config:ro" -e DOCKER_CONFIG=/docker-config "$@"
    fi
    if [ -n "${PROMOTE_DOCKER_NETWORK:-}" ]; then
        set -- --network "$PROMOTE_DOCKER_NETWORK" "$@"
    fi
    docker run --rm -e HOME=/tmp -e COSIGN_REPOSITORY="$SIGNATURES" "$@"
}

# judged <what> <stderr file>: cosign said no. A missing signature or attestation is a refusal; anything else (a refused credential, a
# network, a server error, an answer not recognised) could not be judged, and is never reported as a missing signature.
judged() {
    sed 's/^/     /' "$2"
    if grep -Eq 'no signatures found|no matching signatures|no matching attestations|none of the attestations matched' "$2"; then
        rm -rf "$work"
        refused "$1"
    fi
    rm -rf "$work"
    cannot "cosign could not read $reference: the registry, the network or the authority answered something other than a missing signature"
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
        judged "$reference has no signature the environment's authority accepts" "$work/verify.err"
    fi
    grep -q "\"docker-manifest-digest\":\"$digest\"" "$work/verify.out" || { rm -rf "$work"; refused "the signature verified for $reference names another digest"; }
    for type in slsaprovenance1 cyclonedx; do
        # shellcheck disable=SC2086
        if ! run_cosign "$COSIGN_IMAGE" verify-attestation $AUTHORITY $insecure --type "$type" "$reference" > "$work/$type.out" 2> "$work/$type.err"; then
            judged "$reference has no $type attestation the environment's authority accepts" "$work/$type.err"
        fi
        statements=$(sed -n 's/.*"payload":"\([^"]*\)".*/\1/p' "$work/$type.out")
        [ -n "$statements" ] || { rm -rf "$work"; refused "cosign accepted no $type statement for $reference"; }
        # Every statement verified, read as its structure: its subjects are this digest, and a provenance's git source is the revision.
        expected=$revision
        [ "$type" = slsaprovenance1 ] || expected=-
        for payload in $statements; do
            printf '%s' "$payload" | base64 -d > "$work/statement.json" 2>/dev/null || { rm -rf "$work"; cannot "a $type payload of $reference is not base64"; }
            said=$(java --source 21 "$(dpath "$TOOL")" statement "$type" "$digest" "$expected" < "$work/statement.json") && read_status=0 || read_status=$?
            if [ "$read_status" -eq 1 ]; then
                rm -rf "$work"
                refused "$reference: ${said#REFUSED: }"
            elif [ "$read_status" -ne 0 ]; then
                rm -rf "$work"
                cannot "$reference: ${said#CANNOT JUDGE: }"
            fi
        done
    done
    rm -rf "$work"
    echo "ok   $reference: signed, both attestations naming its digest, built from $revision"
}

case ${1:-} in
    verify)
        [ $# -eq 3 ] || cannot "usage: $0 verify <repository>@<digest> <revision>"
        verify "$2" "$3"
        exit 0
        ;;
    recheck)
        # CI, on every pull request: what the change writes into each GitOps overlay, judged with no registry against what was merged
        # at its merge base with the branch it merges into.
        [ $# -eq 2 ] || cannot "usage: $0 recheck <base-ref>"
        exec java --source 21 "$(dpath "$TOOL")" recheck "$(dpath "$ROOT")" "$2"
        ;;
    console-serving)
        # P17-TSK-105: what each environment's console serves, as committed, for the next console build. Reads this repository alone.
        [ $# -eq 1 ] || cannot "usage: $0 console-serving"
        exec java --source 21 "$(dpath "$TOOL")" console-serving "$(dpath "$ROOT")"
        ;;
    console)
        # P17-TSK-105: the Operator Console, by digest, as the platform's images are; the header's THE CONSOLE says how it differs.
        [ $# -eq 4 ] || cannot "usage: $0 console <staging|production> <console-digest> <console-revision>"
        ENVIRONMENT=$2 CONSOLE=$3 CONSOLE_REVISION=$4
        checked=$(java --source 21 "$(dpath "$TOOL")" console-check "$(dpath "$ROOT")" "$ENVIRONMENT" "$CONSOLE" "$CONSOLE_REVISION") && check_status=0 || check_status=$?
        printf '%s\n' "$checked"
        [ "$check_status" -eq 0 ] || exit "$check_status"
        REGISTRY=${PROMOTE_REGISTRY:-}
        [ -n "$REGISTRY" ] || cannot "PROMOTE_REGISTRY names no registry, so the console's image cannot be verified"
        # The environment's one authority, the admission policies' (the header's OWNER'S DECISION: which identity it is, for the console).
        verify "$REGISTRY/finapp-$ENVIRONMENT-console@$CONSOLE" "$CONSOLE_REVISION"
        java --source 21 "$(dpath "$TOOL")" console-write "$(dpath "$ROOT")" "$ENVIRONMENT" "$CONSOLE" "$CONSOLE_REVISION"
        echo "Commit deploy/console/overlays/$ENVIRONMENT/kustomization.yaml and release.yaml together, in a pull request of their own."
        exit 0
        ;;
    staging | production)
        [ $# -eq 4 ] || cannot "usage: $0 <staging|production> <app-digest> <migrator-digest> <revision>"
        ;;
    *)
        cannot "usage: $0 <staging|production> <app-digest> <migrator-digest> <revision> | console <staging|production> <console-digest> <console-revision> | console-serving | verify <reference> <revision> | recheck <base-ref>"
        ;;
esac

ENVIRONMENT=$1 APP=$2 MIGRATOR=$3 REVISION=$4
# The repository's refusals first: they need no registry, and a tag never reaches cosign. A rollback keeps the migrator the schema
# already ran, so the commit the migrator must have been built from is the check's to say.
checked=$(java --source 21 "$(dpath "$TOOL")" check "$(dpath "$ROOT")" "$ENVIRONMENT" "$APP" "$MIGRATOR" "$REVISION") && check_status=0 || check_status=$?
printf '%s\n' "$checked"
[ "$check_status" -eq 0 ] || exit "$check_status"
MIGRATOR_REVISION=$(printf '%s\n' "$checked" | sed -n 's/^MIGRATOR-REVISION: \([0-9a-f]\{40\}\)$/\1/p')
[ -n "$MIGRATOR_REVISION" ] || cannot "the check named no commit for the migrator"
REGISTRY=${PROMOTE_REGISTRY:-}
[ -n "$REGISTRY" ] || cannot "PROMOTE_REGISTRY names no registry, so neither image can be verified"
verify "$REGISTRY/finapp-$ENVIRONMENT-app@$APP" "$REVISION"
verify "$REGISTRY/finapp-$ENVIRONMENT-migrator@$MIGRATOR" "$MIGRATOR_REVISION"
java --source 21 "$(dpath "$TOOL")" write "$(dpath "$ROOT")" "$ENVIRONMENT" "$APP" "$MIGRATOR" "$REVISION"
echo "Commit deploy/overlays/$ENVIRONMENT/kustomization.yaml and release.yaml together, in a pull request of their own."
