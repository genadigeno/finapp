#!/bin/sh
# Refuses a base image that is not pinned by digest, and reports a pinned image - a scanner or a base - that has
# drifted or gone stale.
#
# WHY THIS EXISTS. Pinning by digest removes the risk of a tag being repointed by whoever
# controls the source repository - and it freezes the image. Without an update path the pin
# rots, and a fix in the scanner is never picked up: pinning without maintenance trades one
# supply-chain risk for a quieter one (P0-TSK-004 review, ADR-0026).
#
# Dependabot covers the SHA-pinned GitHub Actions and, since P15-TSK-003, the base images' FROM lines. It does not
# read a shell variable, so the scanner pins need this mechanism; the bases get it as well, because a weekly answer
# that does not depend on a bot's pull request being opened and read is the one this repository already trusts.
#
# THE BASES (P15-TSK-003). Every FROM line of every Dockerfile at the repository's root (Dockerfile, Dockerfile.*,
# *.Dockerfile - read by pattern, not by a list of names, so a second image is covered the day it arrives) must name
# an earlier stage or an image pinned as repository:tag@sha256:<64 hex>. The tag is the version the digest is claimed
# to be, which is what makes the pin checkable. Anything else - a tag alone, a digest alone, an ARG - is refused
# before any network call, and `--offline` stops there: image-build.sh runs it before every build, so an unpinned base
# never reaches one.
#
# TWO DIFFERENT QUESTIONS, both asked of every pin (not with --offline):
#
#   1. Does the pinned digest still match the version it claims?
#      A mismatch means the tag was MOVED. That is the attack digest-pinning defends against,
#      and it is the more serious of the two: the pin held, and somebody should find out why it
#      had to. A FLOATING tag (no version in it - distroless's `nonroot`) is the exception: it is
#      rebuilt and moved on purpose, so a new digest behind it is reported as a newer build, rot.
#
#   2. Is there a newer release?
#      That is rot. Not urgent, and invisible without asking.
#
# Exit status: 0 current, 1 unpinned, stale or drifted, 2 could not check. A network failure is NOT
# reported as "current" - a check that cannot run must not look like a check that passed, and a
# check that could not run never hides one that found something (1 is kept over 2).
#
# Assumes GNU `sort -V` for version ordering, which Git Bash and the Ubuntu runner both have.
# It is what makes v8.9.0 sort before v8.30.1 rather than after it - verified, because getting
# that backwards would silently report a newer release that does not exist, or miss one that does.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

OFFLINE=0
if [ "${1:-}" = "--offline" ]; then
    OFFLINE=1
fi

status=0

# A finding of 1 is never overwritten by a later 2.
raise() {
    if [ "$status" != 1 ]; then
        status=$1
    fi
}

# --- The bases: refused unless pinned (offline) ---------------------------------------------------------------

# One line per pin, "repository tag digest", for the network checks below.
BASE_PINS=""
dockerfiles=0
for dockerfile in "$ROOT"/Dockerfile "$ROOT"/Dockerfile.* "$ROOT"/*.Dockerfile; do
    [ -f "$dockerfile" ] || continue
    dockerfiles=$((dockerfiles + 1))
    name=${dockerfile#"$ROOT"/}
    stages=" "
    # FROM [--flag=value ...] reference [AS stage]; continuation lines are not used for FROM.
    froms=$(sed -n 's/^[[:space:]]*[Ff][Rr][Oo][Mm][[:space:]]\{1,\}//p' "$dockerfile" | tr -d '\r')
    [ -n "$froms" ] || { echo "!! $name has no FROM line"; raise 1; continue; }
    while IFS= read -r from; do
        # shellcheck disable=SC2086
        set -- $from
        while [ "$#" -gt 0 ] && [ "${1#--}" != "$1" ]; do
            shift
        done
        reference=${1:-}
        stage=""
        if [ "$#" -ge 3 ] && { [ "$2" = "AS" ] || [ "$2" = "as" ]; }; then
            stage=$3
        fi
        case "$stages" in
            *" $reference "*) ;;
            *)
                if printf '%s' "$reference" \
                    | grep -Eq '^[a-z0-9][a-z0-9._-]*(:[0-9]+)?(/[a-z0-9][a-z0-9._-]*)*:[A-Za-z0-9_][A-Za-z0-9_.-]*@sha256:[0-9a-f]{64}$'; then
                    image=${reference%@*}
                    digest=${reference#*@}
                    BASE_PINS="$BASE_PINS${image%:*} ${image##*:} $digest
"
                    echo "ok $name: FROM ${image} is pinned by digest"
                else
                    echo "!! $name: FROM $reference is not pinned as repository:tag@sha256:<digest>."
                    echo "   A tag can be moved by whoever controls the source repository; a digest cannot, and the tag"
                    echo "   beside it is the version the digest must keep matching."
                    raise 1
                fi
                ;;
        esac
        if [ -n "$stage" ]; then
            stages="$stages$stage "
        fi
    done <<EOF
$froms
EOF
done
if [ "$dockerfiles" -eq 0 ]; then
    echo "!! no Dockerfile at the repository's root: the base pins cannot be read"
    raise 1
fi

if [ "$OFFLINE" -eq 1 ]; then
    exit $status
fi

# --- Every pin: drifted, or stale? (network) --------------------------------------------------------------------

# The current digest of a tag, from the registry. `docker buildx imagetools inspect` speaks to
# the registry rather than the local cache, which is the point: a locally cached image would
# answer with what we already pulled and the check would always pass.
resolve_digest() {
    MSYS_NO_PATHCONV=1 docker buildx imagetools inspect "$1:$2" 2>/dev/null \
        | awk '/^Digest:/ { print $2; exit }'
}

# Tags, anonymously. Both registries allow it - verified - so this needs no credential, which
# matters: a check that needs a token is a check that stops running when the token expires.
#
# Every page (P15-TSK-007). ghcr answers a page at a time and names the next one in a Link header. The first version read
# the first page only: for cosign's repository, whose ~35 000 tags are mostly signatures, that page holds one release,
# v1.6.0, so a newer release could never be reported - a check reading less than it says. A page limit bounds a registry
# that never stops answering; reaching it is "could not check", never a pass.
list_tags_ghcr() {
    repository=${1#ghcr.io/}
    token=$(curl -fsS "https://ghcr.io/token?scope=repository:${repository}:pull" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
    [ -n "$token" ] || return 1
    headers=$(mktemp)
    path="/v2/${repository}/tags/list?n=1000"
    pages=0
    tags=""
    while [ -n "$path" ]; do
        pages=$((pages + 1))
        [ "$pages" -le 100 ] || { rm -f "$headers"; return 1; }
        # A page that fails mid-list fails the whole list, and nothing is printed: the caller reads empty output as "could
        # not list", while a partial list would be read as complete and could miss the newest release.
        body=$(curl -fsS -D "$headers" -H "Authorization: Bearer $token" "https://ghcr.io${path}") \
            || { rm -f "$headers"; return 1; }
        tags="$tags
$(printf '%s' "$body" | tr ',' '\n' | sed -n 's/.*"\(v\{0,1\}[0-9][0-9.]*\)".*/\1/p')"
        path=$(sed -n 's/^[Ll]ink: <\([^>]*\)>; *rel="next".*/\1/p' "$headers" | tr -d '\r')
    done
    rm -f "$headers"
    printf '%s\n' "$tags" | sed '/^$/d'
}

list_tags_dockerhub() {
    curl -fsS "https://hub.docker.com/v2/repositories/$1/tags?page_size=100&ordering=last_updated" \
        | tr ',' '\n' | sed -n 's/.*"name":"\(v\{0,1\}[0-9][0-9.]*\)".*/\1/p'
}

# registry.k8s.io serves its tag list anonymously, behind a redirect (P15-TSK-009, for Kustomize).
list_tags_registry_k8s() {
    curl -fsSL "https://registry.k8s.io/v2/${1#registry.k8s.io/}/tags/list" \
        | tr ',' '\n' | sed -n 's/.*"\(v\{0,1\}[0-9][0-9.]*\)".*/\1/p'
}

# A base's newer releases: Docker Hub tags of the same shape as the pinned one - its first number (the Java major)
# kept, every later number free, its suffix (-jdk-noble) kept. eclipse-temurin:21.0.13_9-jdk-noble is compared with
# 21.0.<n>_<n>-jdk-noble and never with 25.<...>: a new Java major is a decision, not rot.
list_tags_like_dockerhub() {
    repository=$1 tag=$2
    case "$repository" in
        */*) path=$repository ;;
        *) path=library/$repository ;;
    esac
    major=$(printf '%s' "$tag" | sed -n 's/^\(v\{0,1\}[0-9]\{1,\}\).*/\1/p')
    rest=$(printf '%s' "${tag#"$major"}" | sed -e 's/[.]/\\./g' -e 's/[0-9]\{1,\}/[0-9]+/g')
    curl -fsS "https://hub.docker.com/v2/repositories/${path}/tags?page_size=100&name=${major}.&ordering=last_updated" \
        | tr ',' '\n' | sed -n 's/.*"name":"\([^"]*\)".*/\1/p' | grep -E "^${major}${rest}\$"
}

check() {
    name=$1 repository=$2 version=$3 digest=$4 lister=$5

    actual=$(resolve_digest "$repository" "$version" || true)
    if [ -z "$actual" ]; then
        echo "?? $name: could not reach the registry to resolve $repository:$version"
        raise 2
        return
    fi

    if [ "$actual" != "$digest" ]; then
        if [ "$lister" = floating ]; then
            echo "-- $name: $repository:$version is a floating tag and has a newer build."
            echo "   pinned  $digest"
            echo "   now     $actual"
            raise 1
            return
        fi
        echo "!! $name: the tag MOVED."
        echo "   $repository:$version"
        echo "   pinned  $digest"
        echo "   now     $actual"
        echo "   The pin held. Find out why the tag changed before updating it."
        raise 1
        return
    fi

    if [ "$lister" = floating ]; then
        echo "ok $name: $version is a floating tag, digest matches its current build"
        return
    fi

    newest=$("$lister" "$repository" "$version" 2>/dev/null | sort -V | tail -1 || true)
    if [ -z "$newest" ]; then
        echo "?? $name: could not list the releases of $repository"
        raise 2
        return
    fi
    if [ "$newest" != "$version" ] \
        && [ "$(printf '%s\n%s\n' "$version" "$newest" | sort -V | tail -1)" = "$newest" ]; then
        echo "-- $name: $version is pinned and matches its digest, but $newest has been released."
        raise 1
        return
    fi

    echo "ok $name: $version, digest matches, no newer release"
}

check gitleaks  "$GITLEAKS_REPOSITORY"  "$GITLEAKS_VERSION"  "$GITLEAKS_DIGEST"  list_tags_ghcr
check trivy     "$TRIVY_REPOSITORY"     "$TRIVY_VERSION"     "$TRIVY_DIGEST"     list_tags_dockerhub
# The infrastructure-code tools (P15-TSK-013). A newer Terraform is also a change to every root's
# required_version, which TerraformIsPinnedAndCheckedTest holds equal to TERRAFORM_VERSION.
check terraform "$TERRAFORM_REPOSITORY" "$TERRAFORM_VERSION" "$TERRAFORM_DIGEST" list_tags_dockerhub
check tflint    "$TFLINT_REPOSITORY"    "$TFLINT_VERSION"    "$TFLINT_DIGEST"    list_tags_ghcr
# The Kubernetes manifest tools (P15-TSK-009). The schemas kubeconform reads are pinned by commit in
# scanner-pins.sh, a git hash being its own digest, and have no release to fall behind.
check kustomize   "$KUSTOMIZE_REPOSITORY"   "$KUSTOMIZE_VERSION"   "$KUSTOMIZE_DIGEST"   list_tags_registry_k8s
check kubeconform "$KUBECONFORM_REPOSITORY" "$KUBECONFORM_VERSION" "$KUBECONFORM_DIGEST" list_tags_ghcr
check conftest    "$CONFTEST_REPOSITORY"    "$CONFTEST_VERSION"    "$CONFTEST_DIGEST"    list_tags_dockerhub
# External Secrets Operator (P15-TSK-018): a controller in the cluster, watched here like the tools.
check external-secrets "$ESO_REPOSITORY" "$ESO_VERSION" "$ESO_DIGEST" list_tags_ghcr
# The alert-rule tools (P15-TSK-025): the versions compose.yaml runs.
check prometheus "$PROMETHEUS_REPOSITORY" "$PROMETHEUS_VERSION" "$PROMETHEUS_DIGEST" list_tags_dockerhub
check alertmanager "$ALERTMANAGER_REPOSITORY" "$ALERTMANAGER_VERSION" "$ALERTMANAGER_DIGEST" list_tags_dockerhub
# Static analysis (P15-TSK-006).
check semgrep   "$SEMGREP_REPOSITORY"   "$SEMGREP_VERSION"   "$SEMGREP_DIGEST"   list_tags_dockerhub
# The edge (P15-TSK-011): the controllers' Traefik, and the check's stand-in backend and client.
check traefik "$TRAEFIK_REPOSITORY" "$TRAEFIK_VERSION" "$TRAEFIK_DIGEST" list_tags_dockerhub
check whoami  "$WHOAMI_REPOSITORY"  "$WHOAMI_VERSION"  "$WHOAMI_DIGEST"  list_tags_dockerhub
check curl    "$CURL_REPOSITORY"    "$CURL_VERSION"    "$CURL_DIGEST"    list_tags_dockerhub
# The kind rehearsal (P15-TSK-012).
check kind-node   "$KIND_NODE_REPOSITORY"   "$KIND_NODE_VERSION"   "$KIND_NODE_DIGEST"   list_tags_dockerhub
check helm        "$HELM_REPOSITORY"        "$HELM_VERSION"        "$HELM_DIGEST"        list_tags_dockerhub
check kube-router "$KUBE_ROUTER_REPOSITORY" "$KUBE_ROUTER_VERSION" "$KUBE_ROUTER_DIGEST" list_tags_dockerhub
# The telemetry pipeline (P15-TSK-023): the collector everywhere, Tempo and Loki on kind. Compared within their major, as the bases are:
# Tempo 3 is a new architecture, a decision and not rot.
check collector "$COLLECTOR_REPOSITORY" "$COLLECTOR_VERSION" "$COLLECTOR_DIGEST" list_tags_like_dockerhub
check tempo     "$TEMPO_REPOSITORY"     "$TEMPO_VERSION"     "$TEMPO_DIGEST"     list_tags_like_dockerhub
check loki      "$LOKI_REPOSITORY"      "$LOKI_VERSION"      "$LOKI_DIGEST"      list_tags_like_dockerhub
# Signing and admission (P15-TSK-007): cosign, the policy controller and its chart (an OCI artefact, resolved by the same
# inspect), and the local registry the admission check signs in.
check cosign                  "$COSIGN_REPOSITORY"                  "$COSIGN_VERSION"                  "$COSIGN_DIGEST"                  list_tags_ghcr
check policy-controller       "$POLICY_CONTROLLER_REPOSITORY"       "$POLICY_CONTROLLER_VERSION"       "$POLICY_CONTROLLER_DIGEST"       list_tags_ghcr
check policy-controller-chart "$POLICY_CONTROLLER_CHART_REPOSITORY" "$POLICY_CONTROLLER_CHART_VERSION" "$POLICY_CONTROLLER_CHART_DIGEST" list_tags_ghcr
check registry                "$REGISTRY_REPOSITORY"                "$REGISTRY_VERSION"                "$REGISTRY_DIGEST"                list_tags_dockerhub

while read -r repository tag digest; do
    [ -n "$repository" ] || continue
    if ! printf '%s' "$tag" | grep -Eq '^v?[0-9]'; then
        lister=floating
    else
        case "$repository" in
            *.*/*) lister=unlisted ;;
            *) lister=list_tags_like_dockerhub ;;
        esac
    fi
    if [ "$lister" = unlisted ]; then
        echo "?? base $repository: a versioned tag on a registry this script cannot list releases of"
        raise 2
        continue
    fi
    check "base $repository" "$repository" "$tag" "$digest" "$lister"
done <<EOF
$BASE_PINS
EOF

exit $status
