#!/bin/sh
# Builds the application image, finapp-app, twice from one commit, and refuses it unless:
#
#   1. both base images are pinned by digest (check-pinned-images.sh --offline, before anything is built);
#   2. the two builds are the same bytes - the boot jar, built FROM SOURCE both times, has the same sha256, and the
#      two images have the same image id and the same layer digests (diff ids), compared one by one;
#   3. the image runs as UID 65532, declares no HEALTHCHECK and no environment of its own, carries its revision,
#      source and version labels, and its entrypoint holds the two JVM flags;
#   4. its filesystem is the pinned runtime base plus the boot jar's entries and nothing else: no shell and no package
#      manager anywhere, java the only program on the PATH;
#   5. it is within its size budget.
#
# P15-TSK-003; PHASE_15_PLAN.md section 5; THREAT_MODEL.md T4, T5, T27. It pushes nothing: scanning is P15-TSK-006's,
# signing and any push P15-TSK-007's.
#
# WHY TWICE, AND WHY FROM SOURCE. "Reproducible" is a claim about bytes, so it is measured on bytes that would
# differ if it were false: the image id and the diff ids, not a size or a tag. Each jar build re-runs every task
# (--rerun-tasks), not only the archive: a re-archive hides the defect this script was written against - the
# CycloneDX SBOM Spring Boot copied into the jar carried a random serial and its own instant, so two builds of one
# commit gave two jars while a re-zip of the first gave the first again (measured 2026-11-08; app/build.gradle.kts
# now keeps the SBOM out of the jar). And BOTH builds are from source, not an incremental one and a full one: an
# up-to-date task keeps whatever its last run left in build/ (Gradle does not see a change to a copy spec's eachFile
# action, so a resource excluded since that run stays), and a comparison of a stale build with a clean one fails for
# the wrong reason - or, two stale ones, passes for one (found by this task's own probes, 2026-11-08). --rerun-tasks
# removes a task's previous outputs before it runs.
#
# WHY THE FLATTENED FILESYSTEM FOR THE SIZE. `docker image inspect`'s Size is the compressed content under the
# containerd image store (Docker Desktop) and the unpacked size under the classic store (CI's runner): two
# quantities under one name. `docker export` is the same bytes on both.
#
# Usage:   infra/scripts/image-build.sh          (from anywhere in the repository)
# Needs:   git, Docker with buildx, unzip, tar, sha256sum, and a JDK for Gradle - the README's prerequisites.
# Leaves:  the image tagged finapp-app:<revision>, printed on the last line; infra/scripts/image-readiness.sh runs it.
# Exit:    0 every assertion held; 1 an assertion failed; 2 a step could not run. A step that cannot run never looks
#          like a check that passed.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

REPOSITORY=finapp-app

# THE SIZE BUDGET, in MiB of the flattened filesystem (base and jar together). Measured 2026-11-08 at 272,924,672
# bytes, 260.3 MiB: the distroless Java 21 base 193,669,120 bytes (184.7 MiB), the boot jar's entries the remaining
# 75.6 MiB. Raised only by a reviewed change that says what grew.
SIZE_BUDGET_MIB=300

# The user the image must run as: distroless's nonroot, numeric so Kubernetes' runAsNonRoot can verify it.
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

for tool in git docker unzip tar sha256sum; do
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

# 1. No unpinned base reaches a build.
"$ROOT/infra/scripts/check-pinned-images.sh" --offline || fail "a base image is not pinned by digest (above)"

# The runtime base is the Dockerfile's last PINNED FROM: the application's stage and the migrator's both name it through
# the stage that holds the pin (P15-TSK-004), and the image must be built on exactly that.
RUNTIME_BASE=$(sed -n 's/^[[:space:]]*[Ff][Rr][Oo][Mm][[:space:]]\{1,\}\(--[^[:space:]]*[[:space:]]\{1,\}\)*\([^[:space:]]*\).*/\2/p' \
    Dockerfile | grep '@sha256:' | tail -1)
[ -n "$RUNTIME_BASE" ] || cannot "no FROM line in Dockerfile"

# What the labels say: the commit (marked -dirty when the tree differs from it), where it came from, the version the
# jar declares. The source is the origin remote with any user or token a local remote URL may carry stripped, so a
# credential can never become a label; anything that does not then read as a plain https URL is labelled unknown.
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

boot_jar() {
    found=""
    for candidate in app/build/libs/app-*.jar; do
        case "$candidate" in
            *-plain.jar) ;;
            *) [ -f "$candidate" ] && found="$found $candidate" ;;
        esac
    done
    # shellcheck disable=SC2086
    set -- $found
    [ "$#" -eq 1 ] || fail "expected one boot jar in app/build/libs, found $#:$found (remove the stale ones)"
    printf '%s' "$1"
}

build_image() {
    # --no-cache: the second build must not reuse the first's layers, or the comparison compares a thing with itself.
    # --provenance=false --sbom=false: attestations are P15-TSK-007's, and buildx's default provenance writes the
    # build's own timestamps into the image index.
    dock buildx build --no-cache --provenance=false --sbom=false \
        --build-arg SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" \
        --build-arg JAR="$JAR" \
        --build-arg REVISION="$REVISION" \
        --build-arg SOURCE="$SOURCE" \
        --build-arg VERSION="$VERSION" \
        --tag "$1" --load . || fail "the image build failed"
}

# 2a. The first build, from source.
echo "== the boot jar, first build, every task re-run"
./gradlew --console=plain -q --rerun-tasks :app:bootJar || fail "the boot jar build failed"
JAR=$(boot_jar)
VERSION=$(unzip -p "$JAR" META-INF/build-info.properties | tr -d '\r' | sed -n 's/^build\.version=//p')
[ -n "$VERSION" ] || fail "$JAR carries no build.version in META-INF/build-info.properties"
cp "$JAR" "$WORK/first.jar"
FIRST_JAR_SUM=$(sha256sum < "$JAR" | cut -d' ' -f1)
FIRST="$REPOSITORY:build-1"
echo "== the image, first build"
build_image "$FIRST"

# 2b. The second build, from source.
echo "== the boot jar, second build, every task re-run"
./gradlew --console=plain -q --rerun-tasks :app:bootJar || fail "the second boot jar build failed"
[ "$(boot_jar)" = "$JAR" ] || fail "the second build produced a different jar file name"
SECOND_JAR_SUM=$(sha256sum < "$JAR" | cut -d' ' -f1)
if [ "$FIRST_JAR_SUM" != "$SECOND_JAR_SUM" ]; then
    unzip -v "$WORK/first.jar" | sed -n 's/^.* \([0-9a-f]\{8\}\)  \(.*\)$/\1 \2/p' | sort -k2 > "$WORK/first.crc"
    unzip -v "$JAR" | sed -n 's/^.* \([0-9a-f]\{8\}\)  \(.*\)$/\1 \2/p' | sort -k2 > "$WORK/second.crc"
    echo "entries that differ (crc, name; < first, > second):" >&2
    diff "$WORK/first.crc" "$WORK/second.crc" >&2 || true
    fail "the boot jar is not reproducible: $FIRST_JAR_SUM, then $SECOND_JAR_SUM"
fi
echo "the boot jar, built twice, each from source: sha256 $FIRST_JAR_SUM, identical"
SECOND="$REPOSITORY:build-2"
echo "== the image, second build"
build_image "$SECOND"

# 2c. The comparison: the image id (a digest over the configuration, which names every layer) and every layer.
FIRST_ID=$(dock image inspect --format '{{.Id}}' "$FIRST")
SECOND_ID=$(dock image inspect --format '{{.Id}}' "$SECOND")
dock image inspect --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$FIRST" | sed '/^$/d' > "$WORK/first.layers"
dock image inspect --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$SECOND" | sed '/^$/d' > "$WORK/second.layers"
if [ "$FIRST_ID" != "$SECOND_ID" ] || ! cmp -s "$WORK/first.layers" "$WORK/second.layers"; then
    echo "image id: $FIRST_ID, then $SECOND_ID" >&2
    echo "layer digests (first | second):" >&2
    paste -d'|' "$WORK/first.layers" "$WORK/second.layers" \
        | sed -e 's/^\(.*\)|\1$/   \1/' -e 's/^\([^ ].*\)|\(.*\)$/!! \1 | \2/' >&2
    fail "the two builds of $REVISION differ"
fi
echo "the image, built twice: id $FIRST_ID, $(wc -l < "$WORK/first.layers" | tr -d ' ') layers, identical:"
sed 's/^/   /' "$WORK/first.layers"
IMAGE="$REPOSITORY:$REVISION"
dock tag "$FIRST" "$IMAGE"
dock image rm "$FIRST" "$SECOND" >/dev/null

# 3. The configuration, read from the built image - not from the Dockerfile's text.
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
    *'"-XX:+ExitOnOutOfMemoryError"'*'"org.springframework.boot.loader.launch.JarLauncher"'*) ;;
    *) fail "the entrypoint does not exit on an out-of-memory error before launching the jar: $entrypoint" ;;
esac
case "$entrypoint" in
    *'"-XX:MaxRAMPercentage='*) ;;
    *) fail "the entrypoint does not size the heap from the container's limit: $entrypoint" ;;
esac
dock image inspect "$RUNTIME_BASE" >/dev/null 2>&1 || dock pull -q "$RUNTIME_BASE" >/dev/null \
    || cannot "could not pull the runtime base $RUNTIME_BASE"
[ "$(config '{{json .Config.Env}}')" = "$(dock image inspect --format '{{json .Config.Env}}' "$RUNTIME_BASE")" ] \
    || fail "the image sets environment of its own; configuration comes from the deployment, never the image"
dock image inspect --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$RUNTIME_BASE" | sed '/^$/d' > "$WORK/base.layers"
head -n "$(wc -l < "$WORK/base.layers")" "$WORK/first.layers" | cmp -s - "$WORK/base.layers" \
    || fail "the image is not built on the pinned runtime base $RUNTIME_BASE"
echo "configuration: user $user, no HEALTHCHECK, the base's environment only, labels revision=$REVISION" \
    "source=$SOURCE version=$VERSION, on the pinned runtime base"

# 4. The filesystem: the image's, the base's and the jar's, compared as entry lists.
container=$(dock create "$IMAGE")
CONTAINERS="$CONTAINERS $container"
dock export "$container" > "$WORK/image.tar"
tar -tf "$WORK/image.tar" | sort > "$WORK/image.entries"
container=$(dock create "$RUNTIME_BASE")
CONTAINERS="$CONTAINERS $container"
dock export "$container" | tar -tf - | sort > "$WORK/base.entries"
unzip -Z1 "$JAR" | grep -v '/$' | sort > "$WORK/jar.files"

if grep -Eq '(^|/)(sh|bash|dash|ash|zsh|ksh|csh|tcsh|busybox|toybox|apt|apt-get|aptitude|dpkg|rpm|apk|yum|dnf|microdnf|zypper)$' \
    "$WORK/image.entries"; then
    grep -E '(^|/)(sh|bash|dash|ash|zsh|ksh|csh|tcsh|busybox|toybox|apt|apt-get|aptitude|dpkg|rpm|apk|yum|dnf|microdnf|zypper)$' \
        "$WORK/image.entries" >&2
    fail "the image holds a shell or a package manager (above)"
fi
programs=$(grep -E '^(usr/local/sbin|usr/local/bin|usr/sbin|usr/bin|sbin|bin)/[^/]+$' "$WORK/image.entries" | tr '\n' ' ')
[ "$programs" = "usr/bin/java " ] || fail "the PATH holds more than java: $programs"
grep -v '^app/' "$WORK/image.entries" > "$WORK/image.outside-app"
if ! cmp -s "$WORK/image.outside-app" "$WORK/base.entries"; then
    diff "$WORK/base.entries" "$WORK/image.outside-app" >&2 || true
    fail "outside /app the image differs from the pinned runtime base (> the image's, < the base's)"
fi
sed -n 's#^app/##p' "$WORK/image.entries" | grep -v '/$' | sed '/^$/d' > "$WORK/image.app-files"
if ! cmp -s "$WORK/image.app-files" "$WORK/jar.files"; then
    diff "$WORK/jar.files" "$WORK/image.app-files" >&2 || true
    fail "/app is not exactly the boot jar's entries (> the image's, < the jar's)"
fi
echo "filesystem: no shell or package manager, java the only program on the PATH," \
    "$(wc -l < "$WORK/base.entries" | tr -d ' ') entries of the pinned base and" \
    "$(wc -l < "$WORK/jar.files" | tr -d ' ') files of the boot jar, nothing else"

# 5. The size budget.
size=$(wc -c < "$WORK/image.tar" | tr -d ' ')
budget=$((SIZE_BUDGET_MIB * 1024 * 1024))
[ "$size" -le "$budget" ] || fail "the image is $size bytes, over its budget of $SIZE_BUDGET_MIB MiB ($budget bytes)"
echo "size: $size bytes ($((size / 1048576)) MiB) of a $SIZE_BUDGET_MIB MiB budget"

echo "$IMAGE"
