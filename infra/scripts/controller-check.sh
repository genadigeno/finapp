#!/bin/sh
# The controllers' own manifests, rendered and held to a rule (P15-TSK-032; OPERATIONS_RUNBOOK.md sections 11.32 and 11.36; THREAT_MODEL.md
# section 6 item 31).
#
# WHAT IT RENDERS. The first controllers of a cluster, which the pipeline role installs and no Application syncs, so nothing else reads
# what they are given:
#   argocd            Argo CD's install manifest at ARGOCD_VERSION, fetched from the release's tag and refused unless its bytes are
#                     ARGOCD_INSTALL_SHA256; every image it names rewritten to the digests of infra/scanner-pins.sh; this repository's
#                     argocd-cm keys merged into its argocd-cm (deploy/delivery/controller/argocd-cm.yaml); and the `default` project
#                     restricted to nothing (deploy/delivery/controller/default-project.yaml).
#   external-secrets  the External Secrets chart at ESO_VERSION, fetched and refused unless its archive is ESO_CHART_SHA256, rendered with
#                     deploy/operators/external-secrets/values.yaml.
#   metrics-server    for the kind delivery rehearsal alone: metrics-server's release manifest at METRICS_SERVER_VERSION, refused unless
#                     its bytes are METRICS_SERVER_MANIFEST_SHA256, its image by digest, its aggregated reader role deleted, and told not to
#                     verify the kubelets' serving certificates, which kind signs with nothing it can trust. A real environment brings its
#                     own metrics source (OPERATIONS_RUNBOOK.md section 11.36); kind has none, and without one the release's autoscaler is
#                     Degraded under Argo CD's own check and the root's last wave never comes.
# Each is held to infra/scripts/manifest-policy/controllers.rego: a ClusterRole only from the controller's named list, its
# ClusterRoleBinding only to that role and that controller's own ServiceAccount, a RoleBinding only to a role the stream holds, a binding
# to a role of the cluster's own only where the list names it, Secrets only in the namespaces listed for it, no aggregation, every image a
# pinned digest, and for Argo CD the committed configuration and the restricted default project.
#
# THEN EACH RULE AGAIN, OVER A PLANT IT MUST REFUSE. A policy that is wired but cannot fail reads as assurance it does not give. Each plant
# is a copy of a render (or the chart rendered with changed values) with one defect; the check must fail AND name the rule. A plant
# accepted, refused for another reason, or no longer planted (its mutation found nothing to change) fails this script.
# ControllerRenderContractTest requires the plants to cover every rule id in controllers.rego.
#
# `render <directory>` writes the three streams, each having passed its policy, as argocd.yaml, external-secrets.yaml and
# metrics-server.yaml, and runs no plant: the kind rehearsal's delivery scope installs exactly what this script checked.
#
# WHAT IT NEVER DOES. It talks to no cluster and holds no credential: no container is given a variable from the host, the repository is
# mounted read-only, and its working directory is a temporary one removed at exit. The network is used for the pinned images and for the
# three release files, each refused unless it is the pinned bytes. KUSTOMIZE_IMAGE, KUBECONFORM_IMAGE, CONFTEST_IMAGE and HELM_IMAGE
# override an image, for a machine whose TLS is intercepted (OPERATIONS_RUNBOOK.md section 11.13); CI never sets them.
#
# Usage:   infra/scripts/controller-check.sh [render <directory>]
# Exit:    0 every check green and every plant refused for its reason; 1 otherwise; 2 a step could not run (no Docker, a fetch that
#          failed), which is never a pass.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)
# shellcheck source=infra/scanner-pins.sh
. "$DIR/scanner-pins.sh"

KUSTOMIZE_IMAGE="${KUSTOMIZE_IMAGE:-${KUSTOMIZE_REPOSITORY}@${KUSTOMIZE_DIGEST}}"
KUBECONFORM_IMAGE="${KUBECONFORM_IMAGE:-${KUBECONFORM_REPOSITORY}@${KUBECONFORM_DIGEST}}"
CONFTEST_IMAGE="${CONFTEST_IMAGE:-${CONFTEST_REPOSITORY}@${CONFTEST_DIGEST}}"
HELM_IMAGE="${HELM_IMAGE:-${HELM_REPOSITORY}:${HELM_VERSION}@${HELM_DIGEST}}"

ARGOCD_INSTALL_URL="https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
ESO_CHART_VERSION=${ESO_VERSION#v}
ESO_CHART_URL="https://github.com/external-secrets/external-secrets/releases/download/helm-chart-${ESO_CHART_VERSION}/external-secrets-${ESO_CHART_VERSION}.tgz"
METRICS_SERVER_URL="https://github.com/kubernetes-sigs/metrics-server/releases/download/${METRICS_SERVER_VERSION}/components.yaml"

ARGOCD_IMAGES="${ARGOCD_REPOSITORY}@${ARGOCD_DIGEST} ${ARGOCD_DEX_REPOSITORY}@${ARGOCD_DEX_DIGEST} ${ARGOCD_REDIS_REPOSITORY}@${ARGOCD_REDIS_DIGEST}"
ESO_IMAGES="${ESO_REPOSITORY}:${ESO_VERSION}@${ESO_DIGEST}"
METRICS_SERVER_IMAGES="${METRICS_SERVER_REPOSITORY}@${METRICS_SERVER_DIGEST}"
STREAMS="argocd external-secrets metrics-server"

SCHEMAS="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/${KUBERNETES_SCHEMAS_COMMIT}/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
CRD_SCHEMAS="https://raw.githubusercontent.com/datreeio/CRDs-catalog/${ESO_CRD_SCHEMAS_COMMIT}/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

# Git Bash on Windows otherwise rewrites /work, /policy, ... into host paths. Unset and harmless on Linux, where CI runs.
export MSYS_NO_PATHCONV=1

MODE=check
OUT=""
case ${1:-} in
    "") ;;
    render)
        [ $# -eq 2 ] || { echo "usage: $0 [render <directory>]" >&2; exit 2; }
        MODE=render
        OUT=$2
        ;;
    *)
        echo "usage: $0 [render <directory>]" >&2
        exit 2
        ;;
esac

# A path as Docker on this host takes it.
dpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

cannot() {
    echo "?? $*" >&2
    exit 2
}

command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || cannot "Docker is not available: every check runs in a pinned image"
for tool in curl sha256sum sed awk; do
    command -v "$tool" >/dev/null 2>&1 || cannot "$tool is not on the PATH"
done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
trap 'exit 130' INT TERM
mkdir -p "$WORK/fetched" "$WORK/argocd" "$WORK/eso-values" "$WORK/metrics-server" "$WORK/rendered" "$WORK/plants"

# fetch <url> <file> <pinned sha256>: the release's bytes, or nothing: a mismatch is a moved tag or a substituted file, and is refused.
fetch() {
    curl -fsSL --retry 3 --max-time 120 -o "$(dpath "$2")" "$1" || cannot "could not fetch $1"
    actual=$(sha256sum "$2" | cut -c1-64)
    if [ "$actual" != "$3" ]; then
        echo "!!   $1 is not the pinned bytes: sha256 $actual, pinned $3" >&2
        exit 1
    fi
    echo "ok   fetched $1, sha256 $actual as pinned"
}

fetch "$ARGOCD_INSTALL_URL" "$WORK/fetched/install.yaml" "$ARGOCD_INSTALL_SHA256"
fetch "$ESO_CHART_URL" "$WORK/fetched/external-secrets.tgz" "$ESO_CHART_SHA256"
fetch "$METRICS_SERVER_URL" "$WORK/fetched/metrics-server.yaml" "$METRICS_SERVER_MANIFEST_SHA256"

# ---------------------------------------------------------------------------------------------------
# Rendering.
# ---------------------------------------------------------------------------------------------------

# render_argocd <output file>: the pinned install, its images by digest, the committed configuration and the restricted default project.
render_argocd() {
    cp "$WORK/fetched/install.yaml" "$WORK/argocd/install.yaml"
    cp "$ROOT/deploy/delivery/controller/argocd-cm.yaml" "$WORK/argocd/argocd-cm.yaml"
    cp "$ROOT/deploy/delivery/controller/default-project.yaml" "$WORK/argocd/default-project.yaml"
    cat > "$WORK/argocd/kustomization.yaml" <<YAML
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: argocd
resources:
  - install.yaml
  - default-project.yaml
patches:
  - path: argocd-cm.yaml
    target:
      kind: ConfigMap
      name: argocd-cm
images:
  - name: $ARGOCD_REPOSITORY
    digest: $ARGOCD_DIGEST
  - name: $ARGOCD_DEX_REPOSITORY
    digest: $ARGOCD_DEX_DIGEST
  - name: $ARGOCD_REDIS_REPOSITORY
    digest: $ARGOCD_REDIS_DIGEST
YAML
    docker run --rm -v "$(dpath "$WORK/argocd"):/work:ro" "$KUSTOMIZE_IMAGE" build /work > "$1" \
        || cannot "kustomize could not render Argo CD's install"
}

# render_eso <values directory> <output file>: the pinned chart with the values in that directory.
render_eso() {
    docker run --rm -v "$(dpath "$WORK/fetched"):/chart:ro" -v "$(dpath "$1"):/values:ro" "$HELM_IMAGE" template external-secrets \
        /chart/external-secrets.tgz --namespace external-secrets -f /values/values.yaml > "$2" \
        || cannot "helm could not render the External Secrets chart"
}

# render_metrics_server <output file>: the pinned release manifest, its image by digest, its aggregated reader deleted (nothing here needs
# `kubectl top`, and the label would merge it into view, edit and admin), and the kubelets' serving certificates not verified: kind's are
# signed by no authority metrics-server is given. Both changes are the kind rehearsal's; a real environment's metrics source is its own.
render_metrics_server() {
    cp "$WORK/fetched/metrics-server.yaml" "$WORK/metrics-server/components.yaml"
    cat > "$WORK/metrics-server/kustomization.yaml" <<YAML
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - components.yaml
patches:
  - patch: |-
      \$patch: delete
      apiVersion: rbac.authorization.k8s.io/v1
      kind: ClusterRole
      metadata:
        name: system:aggregated-metrics-reader
  - target:
      kind: Deployment
      name: metrics-server
    patch: |-
      - op: add
        path: /spec/template/spec/containers/0/args/-
        value: --kubelet-insecure-tls
images:
  - name: $METRICS_SERVER_REPOSITORY
    digest: $METRICS_SERVER_DIGEST
YAML
    docker run --rm -v "$(dpath "$WORK/metrics-server"):/work:ro" "$KUSTOMIZE_IMAGE" build /work > "$1" \
        || cannot "kustomize could not render metrics-server's manifest"
}

# data_file <controller> <namespace> <images...>: what the policy is told about the stream.
data_file() {
    printf 'controller:\n  name: %s\n  namespace: %s\n  images:\n' "$1" "$2"
    shift 2
    for image in "$@"; do
        printf '    - "%s"\n' "$image"
    done
}

render_argocd "$WORK/rendered/argocd.yaml"
cp "$ROOT/deploy/operators/external-secrets/values.yaml" "$WORK/eso-values/values.yaml"
render_eso "$WORK/eso-values" "$WORK/rendered/external-secrets.yaml"
render_metrics_server "$WORK/rendered/metrics-server.yaml"
# shellcheck disable=SC2086
data_file argocd argocd $ARGOCD_IMAGES > "$WORK/rendered/argocd.data.yaml"
# shellcheck disable=SC2086
data_file external-secrets external-secrets $ESO_IMAGES > "$WORK/rendered/external-secrets.data.yaml"
# shellcheck disable=SC2086
data_file metrics-server kube-system $METRICS_SERVER_IMAGES > "$WORK/rendered/metrics-server.data.yaml"
for stream in $STREAMS; do
    [ -s "$WORK/rendered/$stream.yaml" ] || cannot "the $stream render is empty"
done

# ---------------------------------------------------------------------------------------------------
# The checks.
# ---------------------------------------------------------------------------------------------------

# conftest_on <stream> <data file>
conftest_on() {
    docker run --rm -v "$(dpath "$DIR/scripts/manifest-policy"):/policy:ro" -v "$(dpath "$WORK"):/work:ro" "$CONFTEST_IMAGE" \
        test --no-color --combine --all-namespaces=false --namespace main --policy /policy/controllers.rego \
        --data "/work/${2#"$WORK"/}" "/work/${1#"$WORK"/}"
}

# Every object but the controllers' own CustomResourceDefinitions, for which the pinned schema catalogue holds no schema (seen 2026-11-12):
# the API server validates a definition when it is applied, and nothing in a definition grants a right.
kubeconform_on() {
    docker run --rm -v "$(dpath "$WORK"):/work:ro" "$KUBECONFORM_IMAGE" -strict -summary -skip CustomResourceDefinition \
        -kubernetes-version "$KUBERNETES_VERSION" -schema-location "$SCHEMAS" -schema-location "$CRD_SCHEMAS" "/work/${1#"$WORK"/}"
}

status=0

check() {
    label=$1
    shift
    echo "==   $label"
    if "$@"; then
        echo "ok   $label"
    else
        echo "!!   $label FAILED"
        status=1
    fi
}

for stream in $STREAMS; do
    check "the policy on $stream" conftest_on "$WORK/rendered/$stream.yaml" "$WORK/rendered/$stream.data.yaml"
done

if [ "$MODE" = render ]; then
    [ "$status" -eq 0 ] || exit 1
    mkdir -p "$OUT"
    cp "$WORK/rendered/argocd.yaml" "$WORK/rendered/external-secrets.yaml" "$WORK/rendered/metrics-server.yaml" "$OUT/"
    echo "ok   rendered into $OUT: argocd.yaml, external-secrets.yaml and metrics-server.yaml, each passing its policy"
    exit 0
fi

for stream in $STREAMS; do
    check "kubeconform $stream" kubeconform_on "$WORK/rendered/$stream.yaml"
done

# ---------------------------------------------------------------------------------------------------
# The plants.
# ---------------------------------------------------------------------------------------------------

# plant <name> <argocd|external-secrets|metrics-server>: a copy of that render, and its data file.
plant() {
    mkdir -p "$WORK/plants/$1"
    cp "$WORK/rendered/$2.yaml" "$WORK/plants/$1/stream.yaml"
    cp "$WORK/rendered/$2.data.yaml" "$WORK/plants/$1/data.yaml"
}

# mutate <name> <awk or sed program as a command>: refused as not planted if the stream did not change.
not_planted() {
    if cmp -s "$WORK/plants/$1/stream.yaml" "$WORK/plants/$1/before.yaml"; then
        echo "!!   plant $1 was not planted: its mutation no longer finds what it changes"
        status=1
    fi
}

# in_doc <name> <kind> <name in metadata> <sed program>: the sed program applied to that one document of the plant's stream.
in_doc() {
    cp "$WORK/plants/$1/stream.yaml" "$WORK/plants/$1/before.yaml"
    awk -v kind="$2" -v name="$3" -v out="$WORK/plants/$1" '
        BEGIN { n = 0 }
        /^---$/ { n++; print > (out "/doc-" n); next }
        { print > (out "/doc-" n) }
    ' "$WORK/plants/$1/stream.yaml"
    : > "$WORK/plants/$1/stream.yaml"
    for doc in $(ls "$WORK/plants/$1" | grep '^doc-' | sort -t- -k2 -n); do
        if grep -q "^kind: $2\$" "$WORK/plants/$1/$doc" && awk '/^metadata:/{m=1; next} m && /^  name:/{print $2; exit}' "$WORK/plants/$1/$doc" | grep -qx "$3"; then
            sed -i "$4" "$WORK/plants/$1/$doc"
        fi
        cat "$WORK/plants/$1/$doc" >> "$WORK/plants/$1/stream.yaml"
        rm -f "$WORK/plants/$1/$doc"
    done
    not_planted "$1"
}

# whole <name> <sed program>: the sed program over the plant's whole stream.
whole() {
    cp "$WORK/plants/$1/stream.yaml" "$WORK/plants/$1/before.yaml"
    sed -i "$2" "$WORK/plants/$1/stream.yaml"
    not_planted "$1"
}

# append <name>: stdin becomes one more document of the plant's stream.
append() {
    cp "$WORK/plants/$1/stream.yaml" "$WORK/plants/$1/before.yaml"
    { printf -- '---\n'; cat; } >> "$WORK/plants/$1/stream.yaml"
    not_planted "$1"
}

# eso_values <name> <sed program>: the chart rendered again with the committed values changed.
eso_values() {
    mkdir -p "$WORK/plants/$1/values"
    sed "$2" "$ROOT/deploy/operators/external-secrets/values.yaml" > "$WORK/plants/$1/values/values.yaml"
    if cmp -s "$ROOT/deploy/operators/external-secrets/values.yaml" "$WORK/plants/$1/values/values.yaml"; then
        echo "!!   plant $1 was not planted: the values no longer hold what it changes"
        status=1
    fi
    render_eso "$WORK/plants/$1/values" "$WORK/plants/$1/stream.yaml"
    cp "$WORK/rendered/external-secrets.data.yaml" "$WORK/plants/$1/data.yaml"
}

# refused <plant> <reason>: the plant through its policy must fail, and say the reason.
refused() {
    if output=$(conftest_on "$WORK/plants/$1/stream.yaml" "$WORK/plants/$1/data.yaml" 2>&1); then
        printf '%s\n' "$output"
        echo "!!   plant $1 was NOT refused"
        status=1
    elif printf '%s\n' "$output" | grep -qF -- "$2"; then
        echo "ok   plant $1 refused: $(printf '%s\n' "$output" | grep -F -m 1 -- "$2" | sed 's/^[[:space:]]*//' | cut -c1-170)"
    else
        printf '%s\n' "$output"
        echo "!!   plant $1 was refused, but not for its reason: the output never says '$2'"
        status=1
    fi
}

# The External Secrets Operator: the chart's own switches, then the rendered stream.
eso_values eso-cluster-wide 's/^scopedRBAC: true$/scopedRBAC: false/'
eso_values eso-other-namespace 's/^scopedNamespace: finapp$/scopedNamespace: finapp-edge/'
plant eso-aggregated external-secrets
in_doc eso-aggregated Role external-secrets-view 's/^  labels:$/  labels:\n    rbac.authorization.k8s.io\/aggregate-to-view: "true"/'
plant eso-image-tag external-secrets
whole eso-image-tag "s#image: $ESO_REPOSITORY:$ESO_VERSION@$ESO_DIGEST#image: $ESO_REPOSITORY:$ESO_VERSION#"
plant eso-empty external-secrets
whole eso-empty '/^kind: Deployment$/s/Deployment/NotADeployment/'

refused eso-cluster-wide "[controller-cluster-role]"
refused eso-cluster-wide "[controller-cluster-binding]"
refused eso-cluster-wide "[controller-secrets]"
refused eso-other-namespace "[controller-secrets]"
refused eso-aggregated "[controller-aggregate]"
refused eso-image-tag "[controller-images]"
refused eso-empty "[controller-stream]"

# Argo CD: its roles, its bindings, its Secrets, its images, its configuration and its default project.
plant argocd-cluster-role-unlisted argocd
in_doc argocd-cluster-role-unlisted ClusterRole argocd-server 's/^  name: argocd-server$/  name: argocd-server-wider/'
plant argocd-binding-to-a-group argocd
in_doc argocd-binding-to-a-group ClusterRoleBinding argocd-server 's/^subjects:$/subjects:\n- apiGroup: rbac.authorization.k8s.io\n  kind: Group\n  name: system:authenticated/'
plant argocd-binding-other-account argocd
in_doc argocd-binding-other-account ClusterRoleBinding argocd-server '/^subjects:$/,$s/^  name: argocd-server$/  name: argocd-dex-server/'
plant argocd-binding-other-role argocd
in_doc argocd-binding-other-role ClusterRoleBinding argocd-applicationset-controller '/^roleRef:$/,/^subjects:$/s/^  name: argocd-applicationset-controller$/  name: argocd-application-controller/'
plant argocd-binding-unlisted argocd
append argocd-binding-unlisted <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: argocd-notifications-controller
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: argocd-server
subjects:
- kind: ServiceAccount
  name: argocd-notifications-controller
  namespace: argocd
YAML
plant argocd-secrets-elsewhere argocd
in_doc argocd-secrets-elsewhere Role argocd-server 's/^  namespace: argocd$/  namespace: finapp/'
plant argocd-cluster-role-in-a-namespace argocd
append argocd-cluster-role-in-a-namespace <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: argocd-applicationset-controller
  namespace: finapp
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: argocd-applicationset-controller
subjects:
- kind: ServiceAccount
  name: argocd-applicationset-controller
  namespace: argocd
YAML
plant argocd-unmarked-secrets argocd
append argocd-unmarked-secrets <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: argocd-secret-reader
rules:
- apiGroups:
  - ""
  resources:
  - secrets
  verbs:
  - get
YAML
plant argocd-aggregated argocd
in_doc argocd-aggregated ClusterRole argocd-server 's/^  labels:$/  labels:\n    rbac.authorization.k8s.io\/aggregate-to-admin: "true"/'
plant argocd-image-tag argocd
whole argocd-image-tag "0,\\#image: $ARGOCD_REPOSITORY@$ARGOCD_DIGEST#s#image: $ARGOCD_REPOSITORY@$ARGOCD_DIGEST#image: $ARGOCD_REPOSITORY:$ARGOCD_VERSION#"
plant argocd-redis-other argocd
whole argocd-redis-other "s#image: $ARGOCD_REDIS_REPOSITORY@$ARGOCD_REDIS_DIGEST#image: $ARGOCD_REDIS_REPOSITORY:latest#"
plant argocd-config-absent argocd
in_doc argocd-config-absent ConfigMap argocd-cm '/^  resource.customizations.health.argoproj.io_Application: |$/s/argoproj.io_Application/argoproj.io_Applications/'
plant argocd-config-label argocd
in_doc argocd-config-label ConfigMap argocd-cm 's/^  application.resourceTrackingMethod: annotation$/  application.resourceTrackingMethod: label/'
plant argocd-default-absent argocd
in_doc argocd-default-absent AppProject default 's/^  name: default$/  name: not-default/'
plant argocd-default-destination argocd
in_doc argocd-default-destination AppProject default 's/^  destinations: \[\]$/  destinations:\n  - namespace: "*"\n    server: "*"/'
plant argocd-default-repositories argocd
in_doc argocd-default-repositories AppProject default "s/^  sourceRepos: \[\]$/  sourceRepos:\n  - '*'/"
plant argocd-default-cluster-kinds argocd
in_doc argocd-default-cluster-kinds AppProject default 's/^  clusterResourceWhitelist: \[\]$/  clusterResourceWhitelist:\n  - group: "*"\n    kind: "*"/'
plant argocd-default-blacklist-gone argocd
in_doc argocd-default-blacklist-gone AppProject default '/^  namespaceResourceBlacklist:$/,$d'
plant argocd-not-its-stream argocd
cp "$WORK/rendered/external-secrets.yaml" "$WORK/plants/argocd-not-its-stream/stream.yaml"
plant argocd-unknown-controller argocd
sed -i 's/^  name: argocd$/  name: another-controller/' "$WORK/plants/argocd-unknown-controller/data.yaml"

refused argocd-cluster-role-unlisted "[controller-cluster-role]"
refused argocd-binding-to-a-group "[controller-cluster-binding]"
refused argocd-binding-other-account "[controller-cluster-binding]"
refused argocd-binding-other-role "[controller-cluster-binding]"
refused argocd-binding-unlisted "[controller-cluster-binding]"
refused argocd-secrets-elsewhere "[controller-secrets] Role/argocd-server reaches Secrets in namespace finapp"
refused argocd-cluster-role-in-a-namespace "[controller-secrets] RoleBinding/argocd-applicationset-controller gives ClusterRole argocd-applicationset-controller"
refused argocd-unmarked-secrets "[controller-secrets] ClusterRole/argocd-secret-reader reaches Secrets in every namespace"
refused argocd-aggregated "[controller-aggregate]"
refused argocd-image-tag "[controller-images]"
refused argocd-redis-other "[controller-images]"
refused argocd-config-absent "[controller-config] argocd-cm does not carry resource.customizations.health.argoproj.io_Application"
refused argocd-config-label "[controller-config] argocd-cm tracks by label"
refused argocd-default-absent "[controller-default-project] the stream holds no"
refused argocd-default-destination "[controller-default-project] the default project states destinations"
refused argocd-default-repositories "[controller-default-project] the default project states sourceRepos"
refused argocd-default-cluster-kinds "[controller-default-project] the default project states clusterResourceWhitelist"
refused argocd-default-blacklist-gone "[controller-default-project] the default project does not refuse every namespaced kind"
refused argocd-not-its-stream "[controller-stream] no StatefulSet argocd-application-controller"
refused argocd-unknown-controller "[controller-stream] the data names controller"

# A RoleBinding to a role the stream does not hold: the operator given `admin` in finapp.
plant eso-admin-in-finapp external-secrets
append eso-admin-in-finapp <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: external-secrets-admin
  namespace: finapp
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: admin
subjects:
- kind: ServiceAccount
  name: external-secrets
  namespace: external-secrets
YAML
refused eso-admin-in-finapp "[controller-role-binding] RoleBinding/external-secrets-admin in finapp gives ClusterRole admin"

# metrics-server: its role, its bindings to its own role and to the cluster's, its image, and the aggregated reader its release ships.
plant metrics-cluster-role-unlisted metrics-server
in_doc metrics-cluster-role-unlisted ClusterRole system:metrics-server 's/^  name: system:metrics-server$/  name: system:metrics-server-wider/'
plant metrics-binding-to-a-group metrics-server
in_doc metrics-binding-to-a-group ClusterRoleBinding system:metrics-server 's/^subjects:$/subjects:\n- apiGroup: rbac.authorization.k8s.io\n  kind: Group\n  name: system:authenticated/'
plant metrics-delegator-wider metrics-server
in_doc metrics-delegator-wider ClusterRoleBinding metrics-server:system:auth-delegator '/^roleRef:$/,/^subjects:$/s/^  name: system:auth-delegator$/  name: cluster-admin/'
plant metrics-reader-elsewhere metrics-server
in_doc metrics-reader-elsewhere RoleBinding metrics-server-auth-reader '/^roleRef:$/,/^subjects:$/s/^  kind: Role$/  kind: ClusterRole/;/^roleRef:$/,/^subjects:$/s/^  name: extension-apiserver-authentication-reader$/  name: admin/'
plant metrics-reads-secrets metrics-server
in_doc metrics-reads-secrets ClusterRole system:metrics-server 's/^  - nodes\/metrics$/  - nodes\/metrics\n  - secrets/'
plant metrics-aggregated metrics-server
append metrics-aggregated <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  labels:
    k8s-app: metrics-server
    rbac.authorization.k8s.io/aggregate-to-admin: "true"
    rbac.authorization.k8s.io/aggregate-to-edit: "true"
    rbac.authorization.k8s.io/aggregate-to-view: "true"
  name: system:aggregated-metrics-reader
rules:
- apiGroups:
  - metrics.k8s.io
  resources:
  - pods
  - nodes
  verbs:
  - get
  - list
  - watch
YAML
plant metrics-image-tag metrics-server
whole metrics-image-tag "s#image: $METRICS_SERVER_REPOSITORY@$METRICS_SERVER_DIGEST#image: $METRICS_SERVER_REPOSITORY:$METRICS_SERVER_VERSION#"
plant metrics-not-its-stream metrics-server
cp "$WORK/rendered/external-secrets.yaml" "$WORK/plants/metrics-not-its-stream/stream.yaml"

refused metrics-cluster-role-unlisted "[controller-cluster-role]"
refused metrics-binding-to-a-group "[controller-cluster-binding] ClusterRoleBinding/system:metrics-server"
refused metrics-delegator-wider "[controller-cluster-binding] ClusterRoleBinding/metrics-server:system:auth-delegator"
refused metrics-reader-elsewhere "[controller-role-binding] RoleBinding/metrics-server-auth-reader in kube-system gives ClusterRole admin"
refused metrics-reads-secrets "[controller-secrets] ClusterRole/system:metrics-server reaches Secrets in every namespace"
refused metrics-aggregated "[controller-aggregate] ClusterRole/system:aggregated-metrics-reader"
refused metrics-image-tag "[controller-images]"
refused metrics-not-its-stream "[controller-stream] no Deployment metrics-server"

if [ "$status" -eq 0 ]; then
    echo "ok   every check green, every plant refused for its reason"
fi
exit $status
