#!/bin/sh
# Reports a pinned scanner image that has drifted or gone stale.
#
# WHY THIS EXISTS. Pinning by digest removes the risk of a tag being repointed by whoever
# controls the source repository - and it freezes the image. Without an update path the pin
# rots, and a fix in the scanner is never picked up: pinning without maintenance trades one
# supply-chain risk for a quieter one (P0-TSK-004 review, ADR-0026).
#
# Dependabot covers the SHA-pinned GitHub Actions. It does not read a shell variable, so the
# scanner pins need their own mechanism. This is it.
#
# TWO DIFFERENT QUESTIONS, both asked:
#
#   1. Does the pinned digest still match the version it claims?
#      A mismatch means the tag was MOVED. That is the attack digest-pinning defends against,
#      and it is the more serious of the two: the pin held, and somebody should find out why it
#      had to.
#
#   2. Is there a newer release?
#      That is rot. Not urgent, and invisible without asking.
#
# Exit status: 0 current, 1 stale or drifted, 2 could not check. A network failure is NOT
# reported as "current" - a check that cannot run must not look like a check that passed.
#
# Assumes GNU `sort -V` for version ordering, which Git Bash and the Ubuntu runner both have.
# It is what makes v8.9.0 sort before v8.30.1 rather than after it - verified, because getting
# that backwards would silently report a newer release that does not exist, or miss one that does.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

status=0

# The current digest of a tag, from the registry. `docker buildx imagetools inspect` speaks to
# the registry rather than the local cache, which is the point: a locally cached image would
# answer with what we already pulled and the check would always pass.
resolve_digest() {
    MSYS_NO_PATHCONV=1 docker buildx imagetools inspect "$1:$2" 2>/dev/null \
        | awk '/^Digest:/ { print $2; exit }'
}

# Tags, anonymously. Both registries allow it - verified - so this needs no credential, which
# matters: a check that needs a token is a check that stops running when the token expires.
list_tags_ghcr() {
    repository=${1#ghcr.io/}
    token=$(curl -fsS "https://ghcr.io/token?scope=repository:${repository}:pull" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
    [ -n "$token" ] || return 1
    curl -fsS -H "Authorization: Bearer $token" "https://ghcr.io/v2/${repository}/tags/list" \
        | tr ',' '\n' | sed -n 's/.*"\(v\{0,1\}[0-9][0-9.]*\)".*/\1/p'
}

list_tags_dockerhub() {
    curl -fsS "https://hub.docker.com/v2/repositories/$1/tags?page_size=100&ordering=last_updated" \
        | tr ',' '\n' | sed -n 's/.*"name":"\(v\{0,1\}[0-9][0-9.]*\)".*/\1/p'
}

check() {
    name=$1 repository=$2 version=$3 digest=$4 lister=$5

    actual=$(resolve_digest "$repository" "$version" || true)
    if [ -z "$actual" ]; then
        echo "?? $name: could not reach the registry to resolve $repository:$version"
        status=2
        return
    fi

    if [ "$actual" != "$digest" ]; then
        echo "!! $name: the tag MOVED."
        echo "   $repository:$version"
        echo "   pinned  $digest"
        echo "   now     $actual"
        echo "   The pin held. Find out why the tag changed before updating it."
        status=1
        return
    fi

    newest=$("$lister" "$repository" 2>/dev/null | sort -V | tail -1 || true)
    if [ -n "$newest" ] && [ "$newest" != "$version" ] \
        && [ "$(printf '%s\n%s\n' "$version" "$newest" | sort -V | tail -1)" = "$newest" ]; then
        echo "-- $name: $version is pinned and matches its digest, but $newest has been released."
        status=1
        return
    fi

    echo "ok $name: $version, digest matches, no newer release"
}

check gitleaks "$GITLEAKS_REPOSITORY" "$GITLEAKS_VERSION" "$GITLEAKS_DIGEST" list_tags_ghcr
check trivy    "$TRIVY_REPOSITORY"    "$TRIVY_VERSION"    "$TRIVY_DIGEST"    list_tags_dockerhub

exit $status
