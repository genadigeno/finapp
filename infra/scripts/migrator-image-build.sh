#!/bin/sh
# Builds the migrator image, finapp-migrator, twice from one commit, and refuses it unless:
#
#   1. both base images are pinned by digest (check-pinned-images.sh --offline, before anything is built);
#   2. the two builds are the same bytes - the context tar, built FROM SOURCE both times, has the same sha256, and the two
#      images have the same image id and the same layer digests, compared one by one;
#   3. the image runs as UID 65532, declares no HEALTHCHECK and no environment of its own, carries its revision, source and
#      version labels, and its entrypoint launches the migrator and nothing else;
#   4. its filesystem is the pinned runtime base plus /migrator and nothing else: no shell and no package manager, java the
#      only program on the PATH, every migration of the repository byte for byte and no other;
#   5. it is within its size budget.
#
# P15-TSK-004; PHASE_15_PLAN.md section 5; THREAT_MODEL.md T12, T33. image-build.sh is the pattern, and the reasons there for
# building twice and from source hold here. It pushes nothing: scanning is P15-TSK-006's, signing and any push P15-TSK-007's.
#
# Usage:   infra/scripts/migrator-image-build.sh          (from anywhere in the repository)
# Needs:   git, Docker with buildx, tar, sha256sum, and a JDK for Gradle - the README's prerequisites.
# Leaves:  the image tagged finapp-migrator:<revision>, printed on the last line; infra/scripts/migrator-verify.sh runs it.
# Exit:    0 every assertion held; 1 an assertion failed; 2 a step could not run. A step that cannot run never looks like a
#          check that passed.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

REPOSITORY=finapp-migrator
CONTEXT_TAR=migrator/build/image/finapp-migrator.tar

# THE SIZE BUDGET, in MiB of the flattened filesystem (base, libraries and scripts). Measured 2026-11-08 at the figure
# README.md section 5g records; raised only by a reviewed change that says what grew.
SIZE_BUDGET_MIB=230

RUNTIME_USER=65532:65532

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

for tool in git docker tar sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || cannot "$tool is not on the PATH"
done
dock buildx version >/dev/null 2>&1 || cannot "docker buildx is not available"

WORK=$(mktemp -d)
CONTAINERS=""
cleanup() {
    for container in $CONTAINERS; do
        dock rm -f "$container" >/dev/null 2>&1 || true
    done
    rm -rf "$WORK"
}
trap cleanup EXIT

"$ROOT/infra/scripts/check-pinned-images.sh" --offline || fail "a base image is not pinned by digest (above)"

# The runtime base is the Dockerfile's last pinned FROM: the stage both images are built on.
RUNTIME_BASE=$(sed -n 's/^[[:space:]]*[Ff][Rr][Oo][Mm][[:space:]]\{1,\}\(--[^[:space:]]*[[:space:]]\{1,\}\)*\([^[:space:]]*\).*/\2/p' \
    Dockerfile | grep '@sha256:' | tail -1)
[ -n "$RUNTIME_BASE" ] || cannot "no pinned FROM line in Dockerfile"

REVISION=$(git rev-parse HEAD)
if [ -n "$(git status --porcelain)" ]; then
    REVISION="$REVISION-dirty"
fi
SOURCE_DATE_EPOCH=$(git log -1 --format=%ct)
SOURCE=$(git remote get-url origin 2>/dev/null || true)
SOURCE=$(printf '%s' "$SOURCE" | sed -e 's#^git@\([^:/]*\):#https://\1/#' -e 's#^ssh://[^@/]*@#https://#' \
    -e 's#^\(https\{0,1\}://\)[^@/]*@#\1#' -e 's#\.git$##')
if ! printf '%s' "$SOURCE" | grep -Eq '^https://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._~/-]+$'; then
    SOURCE=unknown
fi
VERSION=$(sed -n 's/^version = "\(.*\)"$/\1/p' build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts)
[ -n "$VERSION" ] || cannot "the build's version could not be read"

build_image() {
    dock buildx build --no-cache --provenance=false --sbom=false \
        --file Dockerfile --target migrator \
        --build-arg SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" \
        --build-arg REVISION="$REVISION" \
        --build-arg SOURCE="$SOURCE" \
        --build-arg VERSION="$VERSION" \
        --tag "$1" --load migrator || fail "the image build failed"
}

echo "== the context, first build, every task re-run"
./gradlew --console=plain -q --rerun-tasks :migrator:imageTar || fail "the context tar build failed"
FIRST_SUM=$(sha256sum < "$CONTEXT_TAR" | cut -d' ' -f1)
FIRST="$REPOSITORY:build-1"
echo "== the image, first build"
build_image "$FIRST"

echo "== the context, second build, every task re-run"
./gradlew --console=plain -q --rerun-tasks :migrator:imageTar || fail "the second context tar build failed"
SECOND_SUM=$(sha256sum < "$CONTEXT_TAR" | cut -d' ' -f1)
[ "$FIRST_SUM" = "$SECOND_SUM" ] || fail "the context tar is not reproducible: $FIRST_SUM, then $SECOND_SUM"
echo "the context tar, built twice, each from source: sha256 $FIRST_SUM, identical"
SECOND="$REPOSITORY:build-2"
echo "== the image, second build"
build_image "$SECOND"

FIRST_ID=$(dock image inspect --format '{{.Id}}' "$FIRST")
SECOND_ID=$(dock image inspect --format '{{.Id}}' "$SECOND")
dock image inspect --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$FIRST" | sed '/^$/d' > "$WORK/first.layers"
dock image inspect --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$SECOND" | sed '/^$/d' > "$WORK/second.layers"
if [ "$FIRST_ID" != "$SECOND_ID" ] || ! cmp -s "$WORK/first.layers" "$WORK/second.layers"; then
    echo "image id: $FIRST_ID, then $SECOND_ID" >&2
    paste -d'|' "$WORK/first.layers" "$WORK/second.layers" >&2
    fail "the two builds of $REVISION differ"
fi
echo "the image, built twice: id $FIRST_ID, $(wc -l < "$WORK/first.layers" | tr -d ' ') layers, identical"
IMAGE="$REPOSITORY:$REVISION"
dock tag "$FIRST" "$IMAGE"
dock image rm "$FIRST" "$SECOND" >/dev/null

config() {
    dock image inspect --format "$1" "$IMAGE"
}
user=$(config '{{.Config.User}}')
[ "$user" = "$RUNTIME_USER" ] || fail "the image runs as '$user' (empty is root), not $RUNTIME_USER"
healthcheck=$(config '{{json .Config.Healthcheck}}')
[ "$healthcheck" = "null" ] || fail "the image declares a HEALTHCHECK: $healthcheck"
for label in revision:"$REVISION" source:"$SOURCE" version:"$VERSION"; do
    key=${label%%:*}
    expected=${label#*:}
    actual=$(config "{{index .Config.Labels \"org.opencontainers.image.$key\"}}")
    [ "$actual" = "$expected" ] || fail "label org.opencontainers.image.$key is '$actual', not '$expected'"
done
entrypoint=$(config '{{json .Config.Entrypoint}}')
case "$entrypoint" in
    *'"-XX:+ExitOnOutOfMemoryError"'*'"com.finapp.migrator.MigratorMain"]') ;;
    *) fail "the entrypoint does not launch the migrator, last, after the JVM flags: $entrypoint" ;;
esac
[ "$(config '{{json .Config.Cmd}}')" = "null" ] || fail "the image declares a CMD: a Job must have nothing to override"
dock image inspect "$RUNTIME_BASE" >/dev/null 2>&1 || dock pull -q "$RUNTIME_BASE" >/dev/null \
    || cannot "could not pull the runtime base $RUNTIME_BASE"
[ "$(config '{{json .Config.Env}}')" = "$(dock image inspect --format '{{json .Config.Env}}' "$RUNTIME_BASE")" ] \
    || fail "the image sets environment of its own; configuration and credentials come from the Job, never the image"
dock image inspect --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$RUNTIME_BASE" | sed '/^$/d' > "$WORK/base.layers"
head -n "$(wc -l < "$WORK/base.layers")" "$WORK/first.layers" | cmp -s - "$WORK/base.layers" \
    || fail "the image is not built on the pinned runtime base $RUNTIME_BASE"
echo "configuration: user $user, no HEALTHCHECK, no CMD, the base's environment only, labels read back, on the pinned base"

container=$(dock create "$IMAGE")
CONTAINERS="$CONTAINERS $container"
dock export "$container" > "$WORK/image.tar"
tar -tf "$WORK/image.tar" | sort > "$WORK/image.entries"
container=$(dock create "$RUNTIME_BASE")
CONTAINERS="$CONTAINERS $container"
dock export "$container" | tar -tf - | sort > "$WORK/base.entries"

if grep -Eq '(^|/)(sh|bash|dash|ash|zsh|ksh|csh|tcsh|busybox|toybox|apt|apt-get|aptitude|dpkg|rpm|apk|yum|dnf|microdnf|zypper)$' \
    "$WORK/image.entries"; then
    grep -E '(^|/)(sh|bash|dash|ash|zsh|ksh|csh|tcsh|busybox|toybox|apt|apt-get|aptitude|dpkg|rpm|apk|yum|dnf|microdnf|zypper)$' \
        "$WORK/image.entries" >&2
    fail "the image holds a shell or a package manager (above)"
fi
programs=$(grep -E '^(usr/local/sbin|usr/local/bin|usr/sbin|usr/bin|sbin|bin)/[^/]+$' "$WORK/image.entries" | tr '\n' ' ')
[ "$programs" = "usr/bin/java " ] || fail "the PATH holds more than java: $programs"
grep -v '^migrator/' "$WORK/image.entries" | grep -v '^migrator$' > "$WORK/image.outside"
if ! cmp -s "$WORK/image.outside" "$WORK/base.entries"; then
    diff "$WORK/base.entries" "$WORK/image.outside" >&2 || true
    fail "outside /migrator the image differs from the pinned runtime base (> the image's, < the base's)"
fi

# Every migration of the repository, byte for byte, and no other. Read from the exported filesystem, not from the context.
mkdir "$WORK/exported"
tar -xf "$WORK/image.tar" -C "$WORK/exported" migrator
( cd "$WORK/exported/migrator/sql" && find . -type f | sort | xargs sha256sum ) > "$WORK/image.sums"
schemas=$(grep -v '^#' migrator/src/main/resources/com/finapp/migrator/schema-order.txt | sed '/^$/d' | tr -d '\r')
: > "$WORK/repository.sums"
scripts=0
for schema in $schemas; do
    for script in "$schema/src/main/resources/db/migration/$schema"/V*.sql; do
        [ -f "$script" ] || fail "no migrations for the schema $schema"
        sum=$(sha256sum < "$script" | cut -d' ' -f1)
        printf '%s  ./%s/%s\n' "$sum" "$schema" "$(basename "$script")" >> "$WORK/repository.sums"
        scripts=$((scripts + 1))
    done
done
sort -k2 "$WORK/repository.sums" > "$WORK/repository.sorted"
sort -k2 "$WORK/image.sums" > "$WORK/image.sorted"
if ! cmp -s "$WORK/repository.sorted" "$WORK/image.sorted"; then
    diff "$WORK/repository.sorted" "$WORK/image.sorted" >&2 || true
    fail "the image's migrations are not the repository's, byte for byte (> the image's, < the repository's)"
fi
others=$(grep '^migrator/' "$WORK/image.entries" | grep -v '/$' | grep -Ev '^migrator/(lib/[^/]+\.jar|sql/[a-z]+/V[0-9]+__[^/]+\.sql)$' || true)
[ -z "$others" ] || fail "/migrator holds something other than jars and migrations: $others"
echo "filesystem: no shell or package manager, java the only program on the PATH," \
    "$(wc -l < "$WORK/base.entries" | tr -d ' ') entries of the pinned base," \
    "$scripts migrations byte-identical to the repository's, and the libraries"

size=$(wc -c < "$WORK/image.tar" | tr -d ' ')
budget=$((SIZE_BUDGET_MIB * 1024 * 1024))
[ "$size" -le "$budget" ] || fail "the image is $size bytes, over its budget of $SIZE_BUDGET_MIB MiB ($budget bytes)"
echo "size: $size bytes ($((size / 1048576)) MiB) of a $SIZE_BUDGET_MIB MiB budget"

echo "$IMAGE"
