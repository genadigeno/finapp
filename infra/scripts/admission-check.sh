#!/bin/sh
# Signing, provenance and admission, proven end to end (P15-TSK-007, ADR-0122 decision 3, C7; THREAT_MODEL.md T5, T27, T30).
#
# WHAT A GREEN RUN SAYS. Both images of this tree, the application and the migrator, were pushed to a registry by digest, signed, and
# given two attestations (SLSA provenance v1 and the CycloneDX SBOM), all under a key generated for this run; `cosign verify` and
# `cosign verify-attestation` accepted them with that key and refused, by name, an unsigned image, an image signed by another key and
# a signed image with no attestations - and only then did anything deploy them. Then, on a kind cluster of this run's own, the Sigstore
# policy controller from its pinned chart, configured as deploy/operators/policy-controller/values.yaml and enforcing deploy/admission
# in the namespaces deploy/namespaces opts in, answered every case below as stated, and each refusal sat beside its control: the
# identical object admitted in a namespace the policy does not cover, so a cluster that refuses everything cannot pass.
#
#   app signed            the kind overlay's own Deployment, at the signed digest     admitted, and its pod created at that digest
#   migrator signed       the kind overlay's own migrator Job, likewise                admitted, and its pod created at that digest
#   environment name      the app under finapp-rehearsal-app, as an environment's      admitted signed; refused unsigned (control:
#                         registry names it (finapp-<environment>-app)                 admitted outside)
#   app/migrator unsigned the same object at an image nobody signed                    refused (control: admitted outside)
#   app/migrator other    the same object at an image signed and attested by another key   refused (control: admitted outside)
#   app unattested        signed by the trusted key, no attestation                    refused (control: admitted outside)
#   init unsigned         the migrator Job with an init container nobody signed        refused (control: admitted outside),
#                         and with a signed init container instead                     admitted
#   unknown image         a Job running an image no policy names                       refused (control: admitted outside)
#   edge                  the edge's own controller, Traefik at its pinned digest      admitted in finapp-edge
#   edge other image      the same controller running another image                    refused (control: admitted outside)
#   controller down       the webhook scaled to zero, the signed app                   refused; admitted again once it is back
#
# THE PROBES (ADMISSION_PROBE), each a deliberate break that must turn the run red: `warn` (every policy's mode warn), `second-key`
# (the other key trusted beside this run's), `migrator-out` (the migrator's glob removed from both platform policies),
# `no-match-allow` (the controller admitting an image no policy matches). MUTATION_TESTING.md records what each was seen to do.
#
# WHAT IT NEVER DOES. No key, password or token outlives the run or enters the repository: both key pairs and the password are generated
# into a temporary directory outside it and removed at exit; the public key is kept as an artefact. Nothing reaches the public
# transparency log, Fulcio or TUF (a key authority needs none, and the controller runs with -disable-tuf here). Nothing is pushed
# anywhere but this run's registry, which runs on the kind network and is removed at exit, with the cluster.
#
# HOW THE CONTROLLER REACHES THE REGISTRY. By the registry's address on the kind network, plain HTTP. The controller's registry client
# (go-containerregistry) falls back to HTTP for an RFC 1918 address and for nothing a real registry would be named, which is why the
# references here are an address and never a name. A real registry is HTTPS.
#
# Usage:   infra/scripts/admission-check.sh
# Needs:   Docker with buildx, kind (KIND, default `kind`), git, a JDK for Gradle, sha256sum, base64, od.
# Env:     ADMISSION_IMAGES="<app image> <migrator image>" uses two local images instead of building this tree, and
#          ADMISSION_SBOM_DIR a directory holding finapp-app.cdx.json and finapp-migrator.cdx.json instead of writing them (a
#          developer's aids; CI sets neither, which ImageAdmissionTest holds, and the provenance of a run that used them says so);
#          ADMISSION_CA_BUNDLE names a CA bundle for a machine whose TLS is intercepted (the chart pull, the node's pull of the
#          controller); TRIVY_IMAGE likewise (OPERATIONS_RUNBOOK.md section 11.9); ADMISSION_KEEP keeps the cluster.
# Names:   the cluster, the registry, the Trivy volume and the artefacts (build/admission/<run>) are this run's own, so two runs never
#          meet; a cluster of the chosen name that already exists is refused, never deleted.
# Exit:    0 every assertion held; 1 an assertion failed; 2 a step could not run. A step that cannot run never looks like a pass.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
# shellcheck source=infra/scanner-pins.sh
. "$ROOT/infra/scanner-pins.sh"

# One run's own names: two runs on one Docker host share no cluster, registry, volume or artefact directory, and a run deletes only
# what it created.
RUN="$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
CLUSTER=${ADMISSION_CLUSTER:-finapp-admission-$RUN}
NODE="$CLUSTER-control-plane"
KIND_BIN=${KIND:-kind}
ART=${ADMISSION_ARTEFACTS:-$ROOT/build/admission/$RUN}
PROBE=${ADMISSION_PROBE:-}
KEEP=${ADMISSION_KEEP:-}
CA_BUNDLE=${ADMISSION_CA_BUNDLE:-}
IMAGES_GIVEN=${ADMISSION_IMAGES:-}
SBOMS_GIVEN=${ADMISSION_SBOM_DIR:-}

case $PROBE in
    "" | warn | second-key | migrator-out | no-match-allow) ;;
    *) echo "?? ADMISSION_PROBE=$PROBE is not a probe this script knows" >&2; exit 2 ;;
esac

KIND_NODE_IMAGE="$KIND_NODE_REPOSITORY:$KIND_NODE_VERSION@$KIND_NODE_DIGEST"
KUSTOMIZE_IMAGE="$KUSTOMIZE_REPOSITORY:$KUSTOMIZE_VERSION@$KUSTOMIZE_DIGEST"
HELM_IMAGE="$HELM_REPOSITORY:$HELM_VERSION@$HELM_DIGEST"
COSIGN_IMAGE="$COSIGN_REPOSITORY:$COSIGN_VERSION@$COSIGN_DIGEST"
REGISTRY_IMAGE="$REGISTRY_REPOSITORY:$REGISTRY_VERSION@$REGISTRY_DIGEST"
CURL_IMAGE="$CURL_REPOSITORY:$CURL_VERSION@$CURL_DIGEST"
TRIVY_IMAGE=${TRIVY_IMAGE:-$TRIVY_REPOSITORY@$TRIVY_DIGEST}
CONTROLLER_REF="$POLICY_CONTROLLER_REPOSITORY@$POLICY_CONTROLLER_DIGEST"

# Git Bash on Windows otherwise rewrites /work, /keys, ... into host paths. Unset and harmless on Linux, where CI runs.
export MSYS_NO_PATHCONV=1

dock() {
    docker "$@"
}

# A path as Docker and kind on this host take it.
dpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

mkdir -p "$ART"
: > "$ART/admission.log"
: > "$ART/assertions.tsv"

# To standard error, so that a line logged inside a command substitution reaches the log and the terminal, not the value.
log() {
    printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$ART/admission.log" >&2
}

FAILED=0
FINISHED=""
CLUSTER_UP=""
REGISTRY_NAME="finapp-admission-registry-$RUN"
REGISTRY_UP=""
PUSH="no-registry-yet"
TRIVY_VOLUME="finapp-admission-trivy-$RUN"
# Outside the repository: the private keys and their password live here, and only here.
WORK=$(mktemp -d)
mkdir -p "$WORK/keys" "$WORK/render" "$WORK/cases" "$WORK/att"

finish() {
    [ -z "$FINISHED" ] || return 0
    FINISHED=1
    set +e
    if [ -n "$CLUSTER_UP" ]; then
        kube get pods -A -o wide > "$ART/pods-final.txt" 2>&1
        kube -n cosign-system logs -l control-plane=policy-controller-webhook --tail=400 --prefix > "$ART/controller.log" 2>&1
    fi
    if [ -z "$KEEP" ] && [ -n "$CLUSTER_UP" ]; then
        "$KIND_BIN" delete cluster --name "$CLUSTER" >/dev/null 2>&1
    fi
    [ -z "$REGISTRY_UP" ] || dock rm -f "$REGISTRY_NAME" >/dev/null 2>&1
    dock volume rm -f "$TRIVY_VOLUME" >/dev/null 2>&1
    # What this run built and tagged; images it was given are the caller's.
    dock image ls --format '{{.Repository}}:{{.Tag}}' \
        | grep -E "^(finapp-admission-variant-$RUN-|finapp-(app|migrator):admission-$RUN\$|$PUSH/finapp-)" \
        | while read -r image; do dock image rm -f "$image" >/dev/null 2>&1; done
    rm -rf "$WORK"
    if [ "$FAILED" -eq 2 ]; then
        log "RESULT: COULD NOT RUN"
        exit 2
    fi
    if [ "$FAILED" -ne 0 ]; then
        log "RESULT: RED"
        exit 1
    fi
    log "RESULT: GREEN"
    exit 0
}
trap 'FAILED=2; finish' HUP INT PIPE TERM
trap 'status=$?; if [ -z "$FINISHED" ]; then [ "$status" = 0 ] || FAILED=2; finish; fi' EXIT

cannot() {
    log "?? $*"
    FAILED=2
    finish
}

fail() {
    log "!! $*"
    printf 'FAIL\t%s\n' "$*" >> "$ART/assertions.tsv"
    FAILED=1
    finish
}

pass() {
    log "ok $*"
    printf 'PASS\t%s\n' "$*" >> "$ART/assertions.tsv"
}

step() {
    log "== $*"
}

# kubectl inside the node: the client that matches the server, and no route through the host.
kube() {
    dock exec -i "$NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf "$@"
}

for tool in docker git sha256sum base64 od sed awk; do
    command -v "$tool" >/dev/null 2>&1 || cannot "$tool is not on the PATH"
done
command -v "$KIND_BIN" >/dev/null 2>&1 || cannot "kind ($KIND_BIN) is not on the PATH"
[ -z "$PROBE" ] || log "PROBE: $PROBE - this run must end red"

REVISION=$(git -C "$(dpath "$ROOT")" rev-parse HEAD)
if [ -n "$(git -C "$(dpath "$ROOT")" status --porcelain)" ]; then
    DIRTY=true
else
    DIRTY=false
fi
SOURCE_DATE_EPOCH=$(git -C "$(dpath "$ROOT")" log -1 --format=%ct)
SOURCE=$(git -C "$(dpath "$ROOT")" remote get-url origin 2>/dev/null || true)
SOURCE=$(printf '%s' "$SOURCE" | sed -e 's#^git@\([^:/]*\):#https://\1/#' -e 's#^ssh://[^@/]*@#https://#' -e 's#\.git$##')
printf '%s' "$SOURCE" | grep -Eq '^https://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._~/-]+$' || SOURCE=unknown
VERSION=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$ROOT/build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts")
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)

# ---------------------------------------------------------------------------------------------------
# The images: this tree's two, built once (the image jobs prove a second build is the same bytes).
# ---------------------------------------------------------------------------------------------------
step "the images"
if [ -n "$IMAGES_GIVEN" ]; then
    # shellcheck disable=SC2086
    set -- $IMAGES_GIVEN
    [ "$#" -eq 2 ] || cannot "ADMISSION_IMAGES names two images, the application's and the migrator's"
    APP_LOCAL=$1
    MIGRATOR_LOCAL=$2
    log "the images given: $APP_LOCAL and $MIGRATOR_LOCAL (not built from this tree)"
else
    (
        cd "$ROOT"
        if [ -x ./gradlew.bat ] && command -v cmd.exe >/dev/null 2>&1; then
            ./gradlew.bat --console=plain -q :app:bootJar :migrator:imageTar
        else
            ./gradlew --console=plain -q :app:bootJar :migrator:imageTar
        fi
    ) >> "$ART/build.log" 2>&1 || cannot "the build failed: see build.log"
    jar=$(cd "$ROOT" && ls app/build/libs/app-*.jar | grep -v plain | head -1)
    APP_LOCAL="finapp-app:admission-$RUN"
    MIGRATOR_LOCAL="finapp-migrator:admission-$RUN"
    (
        cd "$ROOT"
        dock buildx build --provenance=false --sbom=false \
            --build-arg SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" --build-arg JAR="$jar" --build-arg REVISION="$REVISION" \
            --build-arg SOURCE="$SOURCE" --build-arg VERSION="$VERSION" --tag "$APP_LOCAL" --load . \
            && dock buildx build --provenance=false --sbom=false --file Dockerfile --target migrator \
                --build-arg SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" --build-arg REVISION="$REVISION" \
                --build-arg SOURCE="$SOURCE" --build-arg VERSION="$VERSION" --tag "$MIGRATOR_LOCAL" --load migrator
    ) >> "$ART/build.log" 2>&1 || cannot "the image build failed: see build.log"
fi
dock image inspect "$APP_LOCAL" >/dev/null 2>&1 || cannot "no image $APP_LOCAL"
dock image inspect "$MIGRATOR_LOCAL" >/dev/null 2>&1 || cannot "no image $MIGRATOR_LOCAL"

# The variants a policy must refuse: each image again, one label added, so a different digest that nobody signed (or another key did).
variant() {
    tag="finapp-admission-variant-$RUN-$1:$2"
    printf 'FROM %s\nLABEL finapp.admission.variant="%s"\n' "$3" "$2" \
        | dock buildx build --quiet --provenance=false --sbom=false --load -t "$tag" - >> "$ART/build.log" 2>&1 \
        || cannot "could not build the $2 variant of $1"
    printf '%s' "$tag"
}

# Each image's SBOM, CycloneDX, written by the pinned Trivy from the saved image (it needs Trivy's Java database, downloaded into a volume
# this run owns). What the SBOM must list is image-scan.sh's assertion; here it is the predicate the attestation carries.
step "the SBOMs"
trivy() {
    dock run --rm -v "$TRIVY_VOLUME:/cache" -v "$(dpath "$WORK/att"):/work" "$TRIVY_IMAGE" "$@"
}
if [ -z "$SBOMS_GIVEN" ]; then
    dock volume create "$TRIVY_VOLUME" >/dev/null || cannot "could not create the Trivy volume"
    tries=0
    until trivy image --cache-dir /cache --download-java-db-only --no-progress >> "$ART/sbom.log" 2>&1; do
        tries=$((tries + 1))
        [ "$tries" -lt 3 ] || cannot "Trivy's Java database could not be downloaded: see sbom.log"
        sleep 20
    done
fi
sbom() {
    if [ -n "$SBOMS_GIVEN" ]; then
        cp "$SBOMS_GIVEN/$1.cdx.json" "$WORK/att/$1.cdx.json" || cannot "no $1.cdx.json in $SBOMS_GIVEN"
        cp "$WORK/att/$1.cdx.json" "$ART/sbom-$1.cdx.json"
        log "the SBOM of $2: given, $(grep -c '"purl"' "$WORK/att/$1.cdx.json") components (not written by this run)"
        return 0
    fi
    dock save "$2" -o "$(dpath "$WORK/att/$1.tar")" || cannot "could not save $2"
    trivy image --cache-dir /cache --skip-db-update --skip-java-db-update --offline-scan --no-progress --timeout 30m \
        --format cyclonedx --input "/work/$1.tar" --output "/work/$1.cdx.json" >> "$ART/sbom.log" 2>&1 \
        || cannot "the SBOM of $2 could not be written: see sbom.log"
    rm -f "$WORK/att/$1.tar"
    grep -q '"bomFormat": *"CycloneDX"' "$WORK/att/$1.cdx.json" || cannot "the SBOM of $2 is not CycloneDX"
    grep -q '"pkg:maven/' "$WORK/att/$1.cdx.json" || fail "the SBOM of $2 lists no Java library: the scanner did not read the image"
    cp "$WORK/att/$1.cdx.json" "$ART/sbom-$1.cdx.json"
    log "the SBOM of $2: $(grep -c '"purl"' "$WORK/att/$1.cdx.json") components"
}
sbom finapp-app "$APP_LOCAL"
sbom finapp-migrator "$MIGRATOR_LOCAL"

# ---------------------------------------------------------------------------------------------------
# The cluster and the registry on its network.
# ---------------------------------------------------------------------------------------------------
step "the cluster"
dock pull --quiet "$KIND_NODE_IMAGE" >/dev/null || cannot "could not pull the node image"
# A cluster of this name that this run did not create is someone else's: never deleted, never reused.
if "$KIND_BIN" get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
    cannot "a cluster named $CLUSTER already exists and is not this run's"
fi
"$KIND_BIN" create cluster --name "$CLUSTER" --image "$KIND_NODE_IMAGE" --kubeconfig "$(dpath "$WORK/kubeconfig")" --wait 180s \
    >> "$ART/cluster.log" 2>&1 || cannot "the cluster did not come up: see cluster.log"
CLUSTER_UP=1
if [ -n "$CA_BUNDLE" ]; then
    dock cp "$(dpath "$CA_BUNDLE")" "$NODE:/etc/ssl/certs/ca-certificates.crt" || cannot "the CA bundle could not be copied into the node"
    dock exec "$NODE" systemctl restart containerd || cannot "the node's containerd could not be restarted"
    sleep 5
fi
log "cluster up: $(kube version -o json 2>/dev/null | sed -n 's/.*"gitVersion": *"\(v[^"]*\)".*/\1/p' | tail -1)"

step "the registry"
# Published on a loopback port named here, not one Docker picks: Docker Desktop's daemon cannot push to a port it allocated itself
# (measured: "connection refused"), and a named one it can. A port already taken refuses the start, and the next is tried.
dock pull --quiet "$REGISTRY_IMAGE" >/dev/null || cannot "could not pull the registry image"
tries=0
until dock run -d --name "$REGISTRY_NAME" --network kind -p "127.0.0.1:$(( 20000 + $(od -An -N2 -tu2 /dev/urandom | tr -d ' ') % 20000 )):5000" \
    "$REGISTRY_IMAGE" >> "$ART/cluster.log" 2>&1; do
    dock rm -f "$REGISTRY_NAME" >/dev/null 2>&1 || true
    tries=$((tries + 1))
    [ "$tries" -lt 10 ] || cannot "the registry did not start on any of ten ports: see cluster.log"
done
REGISTRY_UP=1
REGISTRY_IP=$(dock inspect -f '{{(index .NetworkSettings.Networks "kind").IPAddress}}' "$REGISTRY_NAME")
case $REGISTRY_IP in
    10.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].*) ;;
    *) cannot "the registry's address on the kind network, '$REGISTRY_IP', is not RFC 1918: the controller would only try HTTPS" ;;
esac
REG="$REGISTRY_IP:5000"
PUSH=$(dock port "$REGISTRY_NAME" 5000/tcp | head -1)
[ -n "$PUSH" ] || cannot "the registry publishes no port to push to"
SIGNATURES="$REG/finapp-signatures"
log "the registry: $REG on the kind network, pushed to through $PUSH; signatures in $SIGNATURES"

# The registry's own answer for a tag: the digest a signature names and a pod must run.
digest_of() {
    dock run --rm --network kind "$CURL_IMAGE" -fsSI \
        -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json, application/vnd.docker.distribution.manifest.list.v2+json' \
        "http://$REG/v2/$1/manifests/$2" 2>/dev/null | tr -d '\r' | sed -n 's/^[Dd]ocker-[Cc]ontent-[Dd]igest: *\(sha256:[0-9a-f]\{64\}\)$/\1/p'
}

# push <local image> <repository> <tag>: the reference by digest, as the registry answers it.
push() {
    dock tag "$1" "$PUSH/$2:$3" || cannot "could not tag $1"
    tries=0
    until dock push --quiet "$PUSH/$2:$3" >> "$ART/push.log" 2>&1; do
        tries=$((tries + 1))
        [ "$tries" -lt 3 ] || cannot "could not push $2:$3: see push.log"
        sleep 3
    done
    digest=$(digest_of "$2" "$3")
    case $digest in sha256:*) ;; *) cannot "the registry gave no digest for $2:$3" ;; esac
    printf '%s/%s@%s' "$REG" "$2" "$digest"
}

step "push"
APP=$(push "$APP_LOCAL" finapp-app signed)
MIGRATOR=$(push "$MIGRATOR_LOCAL" finapp-migrator signed)
APP_UNSIGNED_LOCAL=$(variant app unsigned "$APP_LOCAL")
APP_UNSIGNED=$(push "$APP_UNSIGNED_LOCAL" finapp-app unsigned)
MIGRATOR_UNSIGNED=$(push "$(variant migrator unsigned "$MIGRATOR_LOCAL")" finapp-migrator unsigned)
APP_OTHER=$(push "$(variant app other-key "$APP_LOCAL")" finapp-app other-key)
MIGRATOR_OTHER=$(push "$(variant migrator other-key "$MIGRATOR_LOCAL")" finapp-migrator other-key)
APP_UNATTESTED=$(push "$(variant app unattested "$APP_LOCAL")" finapp-app unattested)
# The name an environment's registry gives the image (infra/terraform/modules/registry: finapp-<environment>-app), so that the policy's
# environment glob is proven on a cluster: the same image signed under it, and the unsigned one beside it.
APP_ENV=$(push "$APP_LOCAL" finapp-rehearsal-app signed)
APP_ENV_UNSIGNED=$(push "$APP_UNSIGNED_LOCAL" finapp-rehearsal-app unsigned)
{
    printf 'app.signed=%s\nmigrator.signed=%s\napp.unsigned=%s\nmigrator.unsigned=%s\n' "$APP" "$MIGRATOR" "$APP_UNSIGNED" "$MIGRATOR_UNSIGNED"
    printf 'app.other-key=%s\nmigrator.other-key=%s\napp.unattested=%s\n' "$APP_OTHER" "$MIGRATOR_OTHER" "$APP_UNATTESTED"
    printf 'app.environment-name.signed=%s\napp.environment-name.unsigned=%s\n' "$APP_ENV" "$APP_ENV_UNSIGNED"
} > "$ART/digests.txt"
for ref in "$APP" "$MIGRATOR" "$APP_UNSIGNED" "$MIGRATOR_UNSIGNED" "$APP_OTHER" "$MIGRATOR_OTHER" "$APP_UNATTESTED" "$APP_ENV" "$APP_ENV_UNSIGNED"; do
    log "pushed $ref"
done
[ "$APP" != "$APP_UNSIGNED" ] && [ "$APP" != "$APP_OTHER" ] && [ "$APP" != "$APP_UNATTESTED" ] \
    || cannot "a variant has the signed image's digest, so its case would test nothing"

# ---------------------------------------------------------------------------------------------------
# Keys, signatures, attestations: this run's, in its temporary directory.
# ---------------------------------------------------------------------------------------------------
step "keys"
# Random per run, and in the environment of the one docker client that starts each cosign container, never exported to the script's
# other children and never on a command line. While a cosign container runs, its configuration holds it, readable by whoever can
# inspect this host's containers, as the key files are by whoever can read $WORK: both die with the run.
SIGNING_PHRASE=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
cosign() {
    COSIGN_PASSWORD=${SIGNING_PHRASE} docker run --rm --network kind --user "$(id -u):$(id -g)" -e HOME=/tmp -e COSIGN_PASSWORD \
        -e COSIGN_REPOSITORY="$SIGNATURES" -v "$(dpath "$WORK/keys"):/keys" -v "$(dpath "$WORK/att"):/att:ro" \
        "$COSIGN_IMAGE" "$@"
}
cosign generate-key-pair --output-key-prefix /keys/ci >> "$ART/cosign.log" 2>&1 || cannot "the run's key pair could not be generated"
cosign generate-key-pair --output-key-prefix /keys/other >> "$ART/cosign.log" 2>&1 || cannot "the other key pair could not be generated"
[ -s "$WORK/keys/ci.key" ] && [ -s "$WORK/keys/ci.pub" ] && [ -s "$WORK/keys/other.pub" ] || cannot "a key file is missing"
if cmp -s "$WORK/keys/ci.pub" "$WORK/keys/other.pub"; then cannot "the two keys are one key"; fi
cp "$WORK/keys/ci.pub" "$ART/ci.pub"
log "this run's public key: sha256 $(sha256sum < "$WORK/keys/ci.pub" | cut -c1-16)..., kept as ci.pub; the private keys never leave $WORK"

# The legacy, tag-based format the controller's key authority reads (the new bundle format is keyless-only there); no transparency log,
# no signing configuration fetched from TUF.
SIGNING="--tlog-upload=false --use-signing-config=false --new-bundle-format=false --allow-http-registry --yes"
VERIFYING="--insecure-ignore-tlog=true --new-bundle-format=false --allow-http-registry"

# SLSA provenance v1 for one image: what it was built from, by what, when. Every value read here, none typed.
provenance() {
    image=$1 target=$2
    bases=$(sed -n 's/^FROM \([^ ]*@sha256:[0-9a-f]\{64\}\).*/\1/p' "$ROOT/Dockerfile")
    [ -n "$bases" ] || cannot "no pinned base in the Dockerfile"
    builder=${GITHUB_SERVER_URL:-}
    if [ -n "$builder" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
        builder="$builder/$GITHUB_REPOSITORY/.github/workflows/ci.yml@${GITHUB_REF:-unknown}"
        invocation="${GITHUB_RUN_ID:-unknown}-${GITHUB_RUN_ATTEMPT:-1}"
    else
        builder="urn:finapp:builder:developer-machine"
        invocation="local-$RUN"
    fi
    # Images given by ADMISSION_IMAGES were not built by this run, from this revision or by this builder: the statement says so, and
    # names no builder, rather than claiming a build it did not see.
    supplied=false
    if [ -n "$IMAGES_GIVEN" ]; then
        supplied=true
        builder="urn:finapp:builder:none-images-supplied"
    fi
    {
        printf '{\n  "buildDefinition": {\n'
        printf '    "buildType": "urn:finapp:build:image:v1",\n'
        printf '    "externalParameters": {"source": "%s", "revision": "%s", "treeDirty": %s, "dockerfile": "Dockerfile", "target": "%s", "image": "%s"},\n' \
            "$SOURCE" "$REVISION" "$DIRTY" "$target" "$image"
        printf '    "internalParameters": {"sourceDateEpoch": "%s", "version": "%s", "imagesSuppliedNotBuilt": %s, "sbomSuppliedNotWritten": %s},\n' \
            "$SOURCE_DATE_EPOCH" "$VERSION" "$supplied" "$( [ -n "$SBOMS_GIVEN" ] && echo true || echo false )"
        printf '    "resolvedDependencies": [\n      {"uri": "git+%s@%s", "digest": {"gitCommit": "%s"}}' "$SOURCE" "$REVISION" "$REVISION"
        for base in $bases; do
            # A package URL: pkg:docker/<name>@<tag>, a registry other than Docker Hub as its repository_url qualifier.
            reference=${base%@*}
            name=${reference%:*}
            case ${name%%/*} in
                *.*) purl="pkg:docker/${name#*/}@${reference##*:}?repository_url=${name%%/*}" ;;
                *) purl="pkg:docker/$name@${reference##*:}" ;;
            esac
            printf ',\n      {"uri": "%s", "digest": {"sha256": "%s"}}' "$purl" "${base##*@sha256:}"
        done
        printf '\n    ]\n  },\n  "runDetails": {\n'
        printf '    "builder": {"id": "%s"},\n' "$builder"
        printf '    "metadata": {"invocationId": "%s", "startedOn": "%s", "finishedOn": "%s"}\n  }\n}\n' "$invocation" "$STARTED" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } > "$WORK/att/$image.provenance.json"
    cp "$WORK/att/$image.provenance.json" "$ART/provenance-$image.json"
}
provenance finapp-app runtime
provenance finapp-migrator migrator

# sign_and_attest <key> <reference> <name>: one signature, then the two attestations.
sign_and_attest() {
    # shellcheck disable=SC2086
    cosign sign --key "/keys/$1.key" $SIGNING "$2" >> "$ART/cosign.log" 2>&1 || cannot "could not sign $2 with the $1 key: see cosign.log"
    # shellcheck disable=SC2086
    cosign attest --key "/keys/$1.key" $SIGNING --type slsaprovenance1 --predicate "/att/$3.provenance.json" "$2" >> "$ART/cosign.log" 2>&1 \
        || cannot "could not attest the provenance of $2: see cosign.log"
    # shellcheck disable=SC2086
    cosign attest --key "/keys/$1.key" $SIGNING --type cyclonedx --predicate "/att/$3.cdx.json" "$2" >> "$ART/cosign.log" 2>&1 \
        || cannot "could not attest the SBOM of $2: see cosign.log"
}

step "sign and attest"
sign_and_attest ci "$APP" finapp-app
sign_and_attest ci "$MIGRATOR" finapp-migrator
sign_and_attest ci "$APP_ENV" finapp-app
sign_and_attest other "$APP_OTHER" finapp-app
sign_and_attest other "$MIGRATOR_OTHER" finapp-migrator
# shellcheck disable=SC2086
cosign sign --key /keys/ci.key $SIGNING "$APP_UNATTESTED" >> "$ART/cosign.log" 2>&1 || cannot "could not sign $APP_UNATTESTED"

# ---------------------------------------------------------------------------------------------------
# cosign verify, before this run deploys anything: the two images accepted, the three plants refused by name.
# ---------------------------------------------------------------------------------------------------
step "verify before deploy"
verified() {
    # shellcheck disable=SC2086
    cosign verify --key /keys/ci.pub $VERIFYING "$1" > "$WORK/verify.out" 2>> "$ART/cosign.log" || fail "cosign verify refused $1"
    grep -q "\"docker-manifest-digest\":\"${1##*@}\"" "$WORK/verify.out" || fail "the signature verified for $1 names another digest"
    for type in slsaprovenance1 cyclonedx; do
        # shellcheck disable=SC2086
        cosign verify-attestation --key /keys/ci.pub $VERIFYING --type "$type" "$1" > "$WORK/attestation.out" 2>> "$ART/cosign.log" \
            || fail "cosign verify-attestation refused the $type attestation of $1"
        statement=$(sed -n 's/.*"payload":"\([^"]*\)".*/\1/p' "$WORK/attestation.out" | head -1 | base64 -d 2>/dev/null || true)
        printf '%s' "$statement" | grep -q "\"sha256\":\"${1##*@sha256:}\"" \
            || fail "the $type attestation verified for $1 names another subject"
    done
    pass "verified before deploy: $2 - its signature and both attestations, each naming its digest, under this run's key"
}
# refused_by_verify <plant> <reason> <command...>: cosign must fail AND say why.
refused_by_verify() {
    label=$1 reason=$2
    shift 2
    if output=$("$@" 2>&1); then
        printf '%s\n' "$output" >> "$ART/cosign.log"
        fail "plant $label was NOT refused by cosign"
    fi
    printf '%s\n' "$output" >> "$ART/cosign.log"
    printf '%s\n' "$output" | grep -qF -- "$reason" || fail "plant $label was refused, but not for its reason: cosign never says '$reason'"
    pass "plant $label refused by cosign: $(printf '%s\n' "$output" | grep -F -m 1 -- "$reason" | cut -c1-140)"
}
verified "$APP" "the application image"
verified "$MIGRATOR" "the migrator image"
verified "$APP_ENV" "the application image under an environment's name"
# shellcheck disable=SC2086
refused_by_verify unsigned "no signatures found" cosign verify --key /keys/ci.pub $VERIFYING "$APP_UNSIGNED"
# shellcheck disable=SC2086
refused_by_verify other-key "no matching signatures" cosign verify --key /keys/ci.pub $VERIFYING "$APP_OTHER"
# shellcheck disable=SC2086
refused_by_verify unattested "no matching attestations" cosign verify-attestation --key /keys/ci.pub $VERIFYING --type slsaprovenance1 "$APP_UNATTESTED"

# ---------------------------------------------------------------------------------------------------
# The promotion's own check (P15-TSK-017, infra/scripts/promote.sh verify), against this run's registry and signature repository: the
# signed pair accepted, built from this revision; an unsigned image, one signed by another key, one with no attestation, a tag and the
# right image named with another commit, each refused by name. The public key alone is handed over, in a directory of its own.
# ---------------------------------------------------------------------------------------------------
mkdir -p "$WORK/trust"
cp "$WORK/keys/ci.pub" "$WORK/trust/ci.pub"
promote_verify() {
    PROMOTE_KEY="$WORK/trust/ci.pub" PROMOTE_SIGNATURES="$SIGNATURES" PROMOTE_DOCKER_NETWORK=kind PROMOTE_LOCAL_REGISTRY=1 \
        "$ROOT/infra/scripts/promote.sh" verify "$@"
}
promoted() {
    if output=$(promote_verify "$1" "$REVISION" 2>&1); then
        printf '%s\n' "$output" >> "$ART/promotion.log"
        pass "the promotion accepts $2: signed, both attestations naming its digest, built from $REVISION"
    else
        printf '%s\n' "$output" >> "$ART/promotion.log"
        fail "the promotion refused $2, which is signed and attested: see promotion.log"
    fi
}
# promotion_refused <plant> <reason> <reference> [revision]: promote.sh must refuse, with status 1, AND say why.
promotion_refused() {
    label=$1 reason=$2
    set +e
    output=$(promote_verify "$3" "${4:-$REVISION}" 2>&1)
    status=$?
    set -e
    printf '%s\n' "$output" >> "$ART/promotion.log"
    [ "$status" -eq 1 ] || fail "promotion plant $label: status $status, where a refusal is 1: see promotion.log"
    printf '%s\n' "$output" | grep -qF -- "$reason" || fail "promotion plant $label was refused, but not for its reason: it never says '$reason'"
    pass "promotion plant $label refused: $(printf '%s\n' "$output" | grep -F -m 1 -- "$reason" | cut -c1-140)"
}
promoted "$APP" "the application image"
promoted "$MIGRATOR" "the migrator image"
promotion_refused unsigned "has no signature" "$APP_UNSIGNED"
promotion_refused other-key "has no signature" "$APP_OTHER"
promotion_refused unattested "has no slsaprovenance1 attestation" "$APP_UNATTESTED"
promotion_refused tag "a tag is never promoted" "$REG/finapp-app:signed"
promotion_refused another-commit "says it was built from" "$APP" ffffffffffffffffffffffffffffffffffffffff

# ---------------------------------------------------------------------------------------------------
# The controller, from its pinned chart and the committed values.
# ---------------------------------------------------------------------------------------------------
step "the policy controller"
mkdir -p "$WORK/chart"
if [ -n "$CA_BUNDLE" ]; then
    set -- -v "$(dpath "$CA_BUNDLE"):/etc/ssl/certs/ca-certificates.crt:ro"
else
    set --
fi
dock run --rm "$@" -v "$(dpath "$WORK/chart"):/chart" "$HELM_IMAGE" pull "oci://$POLICY_CONTROLLER_CHART_REPOSITORY" \
    --version "$POLICY_CONTROLLER_CHART_VERSION" --destination /chart > "$ART/helm-pull.log" 2>&1 \
    || cannot "the policy controller's chart could not be pulled: see helm-pull.log"
grep -Fq "Digest: $POLICY_CONTROLLER_CHART_DIGEST" "$ART/helm-pull.log" || fail "the policy controller's chart is not the pinned digest: see helm-pull.log"
set -- --set webhook.extraArgs.disable-tuf=true
[ "$PROBE" != no-match-allow ] || set -- "$@" --set webhook.configData.no-match-policy=allow
dock run --rm -v "$(dpath "$WORK/chart"):/chart" -v "$(dpath "$ROOT/deploy/operators/policy-controller"):/values:ro" "$HELM_IMAGE" \
    template policy-controller "/chart/policy-controller-$POLICY_CONTROLLER_CHART_VERSION.tgz" --namespace cosign-system --no-hooks \
    -f /values/values.yaml "$@" > "$WORK/controller.yaml" 2>> "$ART/helm-pull.log" \
    || cannot "the policy controller's chart could not be rendered: see helm-pull.log"
images=$(sed -n 's/^ *image: *"\{0,1\}\([^" ]*\)"\{0,1\} *$/\1/p' "$WORK/controller.yaml" | sort -u)
[ "$images" = "$CONTROLLER_REF" ] || fail "the rendered controller runs '$images', not exactly the pinned $CONTROLLER_REF"
grep -q 'no-match-policy: *"\{0,1\}deny' "$WORK/controller.yaml" || [ "$PROBE" = no-match-allow ] \
    || fail "the rendered controller does not refuse an image no policy matches"
# Every webhook the chart renders fails closed, the image webhooks' from the committed values: one Ignore anywhere is a door.
grep -q 'failurePolicy: Fail' "$WORK/controller.yaml" || fail "the rendered controller's webhooks state no failure policy"
if grep -q 'failurePolicy: Ignore' "$WORK/controller.yaml"; then fail "a rendered webhook of the controller fails open (failurePolicy: Ignore)"; fi
cp "$WORK/controller.yaml" "$ART/controller-rendered.yaml"
kube create namespace cosign-system >> "$ART/apply.log" 2>&1 || cannot "could not create the controller's namespace"
# The node pulls the controller itself, by digest, as it would from any registry.
dock exec "$NODE" crictl pull "$CONTROLLER_REF" >> "$ART/images.log" 2>&1 || cannot "the node could not pull the controller: see images.log"
kube apply --server-side -f - < "$WORK/controller.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply the controller: see apply.log"
kube -n cosign-system rollout status deployment/policy-controller-webhook --timeout=300s >> "$ART/apply.log" 2>&1 \
    || fail "the policy controller did not become ready"
# Its webhook is created with no rules and fills them in itself: until it has, nothing is checked, so nothing below starts before.
deadline=$(( $(date +%s) + 180 ))
until [ -n "$(kube get validatingwebhookconfiguration policy.sigstore.dev -o jsonpath='{.webhooks[0].rules[0].resources}' 2>/dev/null)" ]; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "the controller's webhook never registered its rules"
    sleep 2
done
kube get validatingwebhookconfiguration policy.sigstore.dev -o yaml > "$ART/webhook-registered.yaml" 2>&1 || true
grep -q 'policy.sigstore.dev/include' "$ART/webhook-registered.yaml" || fail "the registered webhook does not select the opted-in namespaces"
if grep -q 'webhooks.knative.dev/exclude' "$ART/webhook-registered.yaml"; then
    log "the registered selector also excludes a namespace labelled webhooks.knative.dev/exclude (the controller's own addition): ImageAdmissionTest refuses that label"
fi
pass "the controller is up from its pinned chart: two replicas of the pinned digest, its webhook's rules registered, failing closed"

# ---------------------------------------------------------------------------------------------------
# Namespaces and policies.
# ---------------------------------------------------------------------------------------------------
render() {
    dock run --rm -v "$(dpath "$ROOT/deploy"):/deploy:ro" "$KUSTOMIZE_IMAGE" build "/deploy/$1" > "$2" || cannot "kustomize could not render $1"
}
# One file per document, named <kind>.<name>.yaml.
split() {
    out=$1
    awk -v out="$out" 'BEGIN{n=0; f=out "/doc-0"} /^---$/{close(f); n++; f=out "/doc-" n; next} {print > f}' "$out/all.yaml"
    for doc in "$out"/doc-*; do
        kind=$(sed -n 's/^kind: \(.*\)$/\1/p' "$doc" | head -1)
        name=$(awk '/^metadata:/{m=1; next} m && /^  name:/{print $2; exit}' "$doc")
        mv "$doc" "$out/$kind.$name.yaml"
    done
}

step "namespaces"
mkdir -p "$WORK/render/namespaces"
render namespaces "$WORK/render/namespaces/all.yaml"
kube apply -f - < "$WORK/render/namespaces/all.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply the namespaces"
for namespace in finapp finapp-edge; do
    [ "$(kube get namespace "$namespace" -o jsonpath='{.metadata.labels.policy\.sigstore\.dev/include}')" = true ] \
        || fail "the namespace $namespace is not opted in to admission"
done
# The control: restricted like the others, not opted in. What it admits says the object is valid; what finapp refuses, the policy refused.
CONTROL=finapp-admission-control
kube create namespace "$CONTROL" >> "$ART/apply.log" 2>&1 || cannot "could not create the control namespace"
kube label namespace "$CONTROL" pod-security.kubernetes.io/enforce=restricted >> "$ART/apply.log" 2>&1 || cannot "could not label the control namespace"

step "policies"
mkdir -p "$WORK/render/admission"
# The root as Kustomize renders it is what is applied, with this run's authority in the placeholders' place: whatever the root holds
# reaches the cluster, a fourth policy or a patch included, and this run answers for it.
render admission "$WORK/render/admission/all.yaml"
grep -q 'ADMISSION-AUTHORITY-PUBLIC-KEY' "$WORK/render/admission/all.yaml" || fail "the committed policies hold no placeholder for the authority: something real is committed"
# The second-key probe: every authority named ci repeated whole as one named other, under the other key (any one authority suffices).
# Kustomize renders an authority as a sequence item at two spaces under spec, its keys at four.
awk -v probe="$PROBE" '
    function flush(    i, line, ours) {
        ours = 0
        for (i = 1; i <= n; i++) {
            print buffer[i]
            if (buffer[i] ~ /^    name: ci$/) ours = 1
        }
        if (probe == "second-key" && ours) {
            for (i = 1; i <= n; i++) {
                line = buffer[i]
                sub(/^    name: ci$/, "    name: other", line)
                sub(/ADMISSION-AUTHORITY-PUBLIC-KEY/, "ADMISSION-OTHER-PUBLIC-KEY", line)
                print line
            }
        }
        n = 0
        inside = 0
    }
    inside && !/^    / && !/^  - / { flush() }
    /^  authorities:$/ { print; listing = 1; next }
    listing && /^  - / { if (inside) flush(); inside = 1 }
    listing && !/^  - / && !/^    / { listing = 0 }
    inside { buffer[++n] = $0; next }
    { print }
    END { if (inside) flush() }
' "$WORK/render/admission/all.yaml" > "$WORK/render/admission/authorities.yaml"
# The run's public key, indented under key.data as a literal block; the other probes' mutations.
awk -v keyfile="$WORK/keys/ci.pub" -v otherfile="$WORK/keys/other.pub" -v signatures="$SIGNATURES" -v probe="$PROBE" '
    function block(file, indent,    line, text) {
        text = "|"
        while ((getline line < file) > 0) { text = text "\n" indent line }
        close(file)
        return text
    }
    /data: ADMISSION-(AUTHORITY|OTHER)-PUBLIC-KEY$/ {
        indent = $0
        sub(/data:.*/, "", indent)
        print indent "data: " block(($0 ~ /OTHER/) ? otherfile : keyfile, indent "  ")
        next
    }
    /- oci: ADMISSION-SIGNATURE-REPOSITORY$/ { sub(/ADMISSION-SIGNATURE-REPOSITORY/, signatures) }
    probe == "warn" && /^  mode: enforce$/ { print "  mode: warn"; next }
    probe == "migrator-out" && /^  - glob: .*finapp-(\*-)?migrator@sha256/ { next }
    { print }
' "$WORK/render/admission/authorities.yaml" > "$WORK/render/admission/applied.yaml"
case $PROBE in
    second-key) [ "$(grep -c '^    name: other$' "$WORK/render/admission/applied.yaml")" -eq 2 ] || cannot "the second-key probe did not apply" ;;
    warn) if grep -q '^  mode: enforce$' "$WORK/render/admission/applied.yaml"; then cannot "the warn probe did not apply"; fi ;;
    migrator-out) if grep -q 'glob: .*migrator' "$WORK/render/admission/applied.yaml"; then cannot "the migrator-out probe did not apply"; fi ;;
esac
if grep -q 'ADMISSION-' "$WORK/render/admission/applied.yaml"; then cannot "a placeholder was left in the policies"; fi
[ "$(grep -c '^kind: ClusterImagePolicy$' "$WORK/render/admission/applied.yaml")" -eq 3 ] \
    || fail "the admission root renders $(grep -c '^kind: ClusterImagePolicy$' "$WORK/render/admission/applied.yaml") policies, not the three: see policies-applied.yaml"
cp "$WORK/render/admission/applied.yaml" "$ART/policies-applied.yaml"
tries=0
until kube apply -f - < "$WORK/render/admission/applied.yaml" >> "$ART/apply.log" 2>&1; do
    # The policy's own validating webhook can answer before its endpoint is ready.
    tries=$((tries + 1))
    [ "$tries" -lt 30 ] || cannot "the policies could not be applied: see apply.log"
    sleep 2
done
for policy in finapp-images-signed finapp-images-attested finapp-edge-traefik; do
    kube wait --for=condition=Ready "clusterimagepolicy/$policy" --timeout=120s >> "$ART/apply.log" 2>&1 \
        || fail "the policy $policy did not become Ready: see apply.log"
done
pass "the three policies are Ready, under this run's key and signature repository$( [ -z "$PROBE" ] || printf ' (probe %s)' "$PROBE")"

# ---------------------------------------------------------------------------------------------------
# The workloads: the kind overlay's and the edge's own objects, at the pushed digests.
# ---------------------------------------------------------------------------------------------------
mkdir -p "$WORK/render/kind" "$WORK/render/edge"
render overlays/kind "$WORK/render/kind/all.yaml"
split "$WORK/render/kind"
render edge/overlays/kind "$WORK/render/edge/all.yaml"
split "$WORK/render/edge"
for file in kind/ServiceAccount.finapp-app kind/ServiceAccount.finapp-migrator kind/Deployment.finapp-app kind/Job.finapp-migrator \
    edge/ServiceAccount.finapp-edge edge/Deployment.finapp-edge-public; do
    [ -s "$WORK/render/$file.yaml" ] || cannot "the rendered overlays hold no $file"
done
for namespace in finapp "$CONTROL"; do
    for file in kind/ServiceAccount.finapp-app kind/ServiceAccount.finapp-migrator; do
        sed "s/^  namespace: finapp$/  namespace: $namespace/" "$WORK/render/$file.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 \
            || cannot "could not apply $file to $namespace"
    done
done
for namespace in finapp-edge "$CONTROL"; do
    sed "s/^  namespace: finapp-edge$/  namespace: $namespace/" "$WORK/render/edge/ServiceAccount.finapp-edge.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 \
        || cannot "could not apply the edge's ServiceAccount to $namespace"
done
grep -q '^  namespace: finapp$' "$WORK/render/kind/Deployment.finapp-app.yaml" || cannot "the rendered Deployment is not in finapp"
grep -q 'image: finapp-app@sha256:' "$WORK/render/kind/Deployment.finapp-app.yaml" || cannot "the rendered Deployment does not name finapp-app by digest"
grep -q 'image: finapp-migrator@sha256:' "$WORK/render/kind/Job.finapp-migrator.yaml" || cannot "the rendered Job does not name finapp-migrator by digest"
grep -q 'image: docker.io/library/traefik@sha256:' "$WORK/render/edge/Deployment.finapp-edge-public.yaml" || cannot "the rendered edge does not name Traefik by digest"

# deployment <name> <namespace> <image>: the overlay's Deployment, renamed, at <image>.
deployment() {
    sed -e "s/^  name: finapp-app$/  name: $1/" -e "s/^  namespace: finapp$/  namespace: $2/" \
        -e "s#image: finapp-app@sha256:[0-9a-f]*#image: $3#" "$WORK/render/kind/Deployment.finapp-app.yaml"
}
# job <name> <namespace> <image> [init image]: the overlay's migrator Job, renamed, at <image>, with an init container if asked.
job() {
    sed -e "s/^  name: finapp-migrator$/  name: $1/" -e "s/^  namespace: finapp$/  namespace: $2/" \
        -e "s#image: finapp-migrator@sha256:[0-9a-f]*#image: $3#" "$WORK/render/kind/Job.finapp-migrator.yaml" \
        | awk -v init="${4:-}" '
            init != "" && /^      containers:$/ {
                print "      initContainers:"
                print "        - name: init"
                print "          image: " init
                print "          securityContext:"
                print "            allowPrivilegeEscalation: false"
                print "            capabilities:"
                print "              drop:"
                print "                - ALL"
            }
            { print }'
}
# edge <name> <namespace> <image>: the edge's public controller, renamed, at <image>.
edge() {
    sed -e "s/^  name: finapp-edge-public$/  name: $1/" -e "s/^  namespace: finapp-edge$/  namespace: $2/" \
        -e "s#image: docker.io/library/traefik@sha256:[0-9a-f]*#image: $3#" "$WORK/render/edge/Deployment.finapp-edge-public.yaml"
}

# attempt <case> <file>: 0 if the API server took the object, 1 if it refused it; its answer kept for the case.
attempt() {
    if kube apply -f - < "$2" > "$WORK/cases/$1.out" 2>&1; then
        cat "$WORK/cases/$1.out" >> "$ART/cases.log"
        return 0
    fi
    cat "$WORK/cases/$1.out" >> "$ART/cases.log"
    return 1
}

# pod_at <namespace> <selector> <image>: a pod the controller created whose first container runs exactly <image>, within a minute.
# Matched on the image and not on the first pod listed, which may be an earlier case's, still going.
pod_at() {
    deadline=$(( $(date +%s) + 60 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if kube -n "$1" get pods -l "$2" -o jsonpath='{range .items[*]}{.spec.containers[0].image}{"\n"}{end}' 2>/dev/null | grep -qxF -- "$3"; then
            return 0
        fi
        sleep 2
    done
    return 1
}

# admitted <case> <file> <namespace> <selector> <image>: taken, and a pod created at that image.
admitted() {
    attempt "$1" "$2" || fail "$1: refused, and it must be admitted: $(tr '\n' ' ' < "$WORK/cases/$1.out" | cut -c1-300)"
    pod_at "$3" "$4" "$5" || {
        kube -n "$3" get events --sort-by=.lastTimestamp > "$ART/events-$1.txt" 2>&1 || true
        kube -n "$3" get pods -l "$4" -o wide > "$ART/pods-$1.txt" 2>&1 || true
        fail "$1: the object was taken but no pod at $5 was created within a minute (its pods refused?): see events-$1.txt"
    }
    pass "$1: admitted, its pod created at $5"
    kube delete -f - --wait=false < "$2" >> "$ART/apply.log" 2>&1 || true
}

# refused <case> <file> <reason> <control file>: refused by the policy, saying so, and the identical object taken outside it.
refused() {
    if attempt "$1" "$2"; then
        kube delete -f - --wait=false < "$2" >> "$ART/apply.log" 2>&1 || true
        fail "$1: ADMITTED, and it must be refused$(grep -i warning "$WORK/cases/$1.out" | head -1 | cut -c1-200 | sed 's/^/ - /')"
    fi
    grep -qF 'admission webhook "policy.sigstore.dev" denied the request' "$WORK/cases/$1.out" \
        || fail "$1: refused, but not by the policy controller: $(tr '\n' ' ' < "$WORK/cases/$1.out" | cut -c1-300)"
    grep -qF -- "$3" "$WORK/cases/$1.out" || fail "$1: refused, but not for its reason: the answer never says '$3': $(tr '\n' ' ' < "$WORK/cases/$1.out" | cut -c1-300)"
    attempt "$1-control" "$4" || fail "$1: its control was refused too, so the refusal proves nothing: $(tr '\n' ' ' < "$WORK/cases/$1-control.out" | cut -c1-300)"
    kube delete -f - --wait=false < "$4" >> "$ART/apply.log" 2>&1 || true
    pass "$1: refused ($(grep -oF -- "$3" "$WORK/cases/$1.out" | head -1)), its control admitted outside the policy"
}

C="$WORK/cases"
# What the controller says, read from its answers: the authority's own failure, after the policy's name.
UNSIGNED_REASON='no signatures found'
OTHER_KEY_REASON='no matching signatures: invalid signature'
UNATTESTED='failed policy: finapp-images-attested'

step "the cases"
deployment case-app-signed finapp "$APP" > "$C/app-signed.yaml"
admitted "app signed" "$C/app-signed.yaml" finapp app.kubernetes.io/name=finapp-app "$APP"
job case-migrator-signed finapp "$MIGRATOR" > "$C/migrator-signed.yaml"
admitted "migrator signed" "$C/migrator-signed.yaml" finapp job-name=case-migrator-signed "$MIGRATOR"
deployment case-app-environment-name finapp "$APP_ENV" > "$C/app-environment.yaml"
admitted "app under an environment's name, signed" "$C/app-environment.yaml" finapp app.kubernetes.io/name=finapp-app "$APP_ENV"
deployment case-app-environment-unsigned finapp "$APP_ENV_UNSIGNED" > "$C/app-environment-unsigned.yaml"
deployment case-app-environment-unsigned "$CONTROL" "$APP_ENV_UNSIGNED" > "$C/app-environment-unsigned-control.yaml"
refused "app under an environment's name, unsigned" "$C/app-environment-unsigned.yaml" "$UNSIGNED_REASON" "$C/app-environment-unsigned-control.yaml"

deployment case-app-unsigned finapp "$APP_UNSIGNED" > "$C/app-unsigned.yaml"
deployment case-app-unsigned "$CONTROL" "$APP_UNSIGNED" > "$C/app-unsigned-control.yaml"
refused "app unsigned" "$C/app-unsigned.yaml" "$UNSIGNED_REASON" "$C/app-unsigned-control.yaml"
job case-migrator-unsigned finapp "$MIGRATOR_UNSIGNED" > "$C/migrator-unsigned.yaml"
job case-migrator-unsigned "$CONTROL" "$MIGRATOR_UNSIGNED" > "$C/migrator-unsigned-control.yaml"
refused "migrator unsigned" "$C/migrator-unsigned.yaml" "$UNSIGNED_REASON" "$C/migrator-unsigned-control.yaml"

deployment case-app-other-key finapp "$APP_OTHER" > "$C/app-other.yaml"
deployment case-app-other-key "$CONTROL" "$APP_OTHER" > "$C/app-other-control.yaml"
refused "app signed by another key" "$C/app-other.yaml" "$OTHER_KEY_REASON" "$C/app-other-control.yaml"
job case-migrator-other-key finapp "$MIGRATOR_OTHER" > "$C/migrator-other.yaml"
job case-migrator-other-key "$CONTROL" "$MIGRATOR_OTHER" > "$C/migrator-other-control.yaml"
refused "migrator signed by another key" "$C/migrator-other.yaml" "$OTHER_KEY_REASON" "$C/migrator-other-control.yaml"

deployment case-app-unattested finapp "$APP_UNATTESTED" > "$C/app-unattested.yaml"
deployment case-app-unattested "$CONTROL" "$APP_UNATTESTED" > "$C/app-unattested-control.yaml"
refused "app signed, not attested" "$C/app-unattested.yaml" "$UNATTESTED" "$C/app-unattested-control.yaml"

job case-init-unsigned finapp "$MIGRATOR" "$APP_UNSIGNED" > "$C/init-unsigned.yaml"
job case-init-unsigned "$CONTROL" "$MIGRATOR" "$APP_UNSIGNED" > "$C/init-unsigned-control.yaml"
refused "init container unsigned" "$C/init-unsigned.yaml" "spec.template.spec.initContainers[0].image" "$C/init-unsigned-control.yaml"
job case-init-signed finapp "$MIGRATOR" "$APP" > "$C/init-signed.yaml"
admitted "init container signed" "$C/init-signed.yaml" finapp job-name=case-init-signed "$MIGRATOR"

job case-unknown-image finapp "$CURL_REPOSITORY@$CURL_DIGEST" > "$C/unknown.yaml"
job case-unknown-image "$CONTROL" "$CURL_REPOSITORY@$CURL_DIGEST" > "$C/unknown-control.yaml"
refused "an image no policy names" "$C/unknown.yaml" "no matching policies" "$C/unknown-control.yaml"

TRAEFIK_REF="docker.io/library/traefik@$TRAEFIK_DIGEST"
edge case-edge-traefik finapp-edge "$TRAEFIK_REF" > "$C/edge.yaml"
admitted "edge at Traefik's pinned digest" "$C/edge.yaml" finapp-edge app.kubernetes.io/name=finapp-edge-public "$TRAEFIK_REF"
edge case-edge-other finapp-edge "$WHOAMI_REPOSITORY@$WHOAMI_DIGEST" > "$C/edge-other.yaml"
edge case-edge-other "$CONTROL" "$WHOAMI_REPOSITORY@$WHOAMI_DIGEST" > "$C/edge-other-control.yaml"
refused "edge running another image" "$C/edge-other.yaml" "no matching policies" "$C/edge-other-control.yaml"

step "the controller down"
kube -n cosign-system scale deployment/policy-controller-webhook --replicas=0 >> "$ART/apply.log" 2>&1 || cannot "could not scale the controller down"
deadline=$(( $(date +%s) + 120 ))
until [ "$(kube -n cosign-system get pods -l control-plane=policy-controller-webhook -o name 2>/dev/null | wc -l)" -eq 0 ]; do
    [ "$(date +%s)" -lt "$deadline" ] || cannot "the controller's pods did not go"
    sleep 2
done
deployment case-app-controller-down finapp "$APP" > "$C/down.yaml"
if attempt "controller down" "$C/down.yaml"; then
    kube delete -f - --wait=false < "$C/down.yaml" >> "$ART/apply.log" 2>&1 || true
    fail "controller down: ADMITTED while no webhook could answer, so admission fails open"
fi
grep -q 'failed calling webhook' "$WORK/cases/controller down.out" \
    || fail "controller down: refused, but not because the webhook could not answer: $(tr '\n' ' ' < "$WORK/cases/controller down.out" | cut -c1-300)"
kube -n cosign-system scale deployment/policy-controller-webhook --replicas=2 >> "$ART/apply.log" 2>&1 || cannot "could not scale the controller up"
kube -n cosign-system rollout status deployment/policy-controller-webhook --timeout=300s >> "$ART/apply.log" 2>&1 || cannot "the controller did not come back"
tries=0
until attempt "controller back" "$C/down.yaml"; do
    tries=$((tries + 1))
    [ "$tries" -lt 30 ] || fail "controller back: the signed app is still refused once the controller is back: $(tr '\n' ' ' < "$WORK/cases/controller back.out" | cut -c1-300)"
    sleep 2
done
kube delete -f - --wait=false < "$C/down.yaml" >> "$ART/apply.log" 2>&1 || true
pass "controller down: the signed app refused while no webhook answered (failed calling webhook), admitted once it was back"

[ -z "$PROBE" ] || log "the probe $PROBE reached the end of the cases with every assertion holding"
step "end"
