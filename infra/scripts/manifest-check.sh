#!/bin/sh
# Check the Kubernetes manifests (P15-TSK-009, ADR-0120).
#
# Three checks over every overlay under deploy/overlays: `kustomize build`, `kubeconform -strict` against schemas
# pinned to one commit, and the policies of infra/scripts/manifest-policy/finapp.rego under conftest. The policies read
# application.yaml itself for the pools and the timeout, so the budget is checked against the file the application
# reads, not a copy of its numbers.
#
# THEN EACH POLICY AGAIN, OVER A PLANT IT MUST REFUSE. A policy that is wired but cannot fail reads as assurance it does
# not give. So every run copies deploy/, plants one defect per copy, renders that copy with the same kustomize and runs
# the same check over it, and requires it to fail AND to name the rule. A plant accepted, refused for another reason, or
# no longer planted (its source changed under the mutation) fails this script. KubernetesManifestsTest requires the
# plants to cover every rule id in finapp.rego.
#
# WHAT IT NEVER DOES. It talks to no cluster and holds no credential: no container is given a variable from the host,
# the repository is mounted read-only, and a scratch volume of its own is removed at exit. Nothing here can change the
# tree. The network is used for the three pinned images and for the schemas, read at the commit below.
#
# SAME BYTES HERE AND IN CI. Every tool is an image pinned by digest in infra/scanner-pins.sh, and the schemas are read
# at KUBERNETES_SCHEMAS_COMMIT. KUSTOMIZE_IMAGE, KUBECONFORM_IMAGE and CONFTEST_IMAGE override an image, for a machine
# whose TLS is intercepted (OPERATIONS_RUNBOOK.md section 11.13); CI never sets them.
#
# Usage:
#   infra/scripts/manifest-check.sh
#
# Exit status: 0 every check green and every plant refused for its reason; 1 otherwise; 2 no Docker.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)

OVERLAYS="kind staging production"
ZEROS=0000000000000000000000000000000000000000000000000000000000000000

# ---------------------------------------------------------------------------------------------------
# Inside the Kustomize container: /repo/deploy is the repository's deploy directory, read-only; /scratch
# is this run's volume. Called by the host half below, never by hand.
# ---------------------------------------------------------------------------------------------------
PLANTS=/scratch/plants

plant() {
    # plant <name> [overlay]: a fresh copy of deploy/, rendered through <overlay> (production unless named).
    rm -rf "${PLANTS:?}/$1"
    mkdir -p "$PLANTS/$1"
    cp -R /repo/deploy "$PLANTS/$1/deploy"
    printf 'overlay: %s\n' "${2:-production}" > "$PLANTS/$1/overlay.yaml"
    printf '%s' "${2:-production}" > "$PLANTS/$1/overlay"
}

# mutate <plant> <file under deploy/> <sed program>: fails if the mutation no longer applies.
mutate() {
    sed -i "$3" "$PLANTS/$1/deploy/$2"
    if cmp -s "/repo/deploy/$2" "$PLANTS/$1/deploy/$2"; then
        echo "!!   plant $1 was not planted: $2 is unchanged, so its mutation no longer applies" >&2
        exit 1
    fi
}

# plant_on <name> <file> <sed program> [overlay]: the usual case, one plant and one mutation.
plant_on() {
    plant "$1" "${4:-}"
    mutate "$1" "$2" "$3"
}

# after_render <plant> <sed program>: a plant of the rendered stream itself, for a value Kustomize would normalise away.
after_render() {
    printf '%s\n' "$2" > "$PLANTS/$1/after-render.sed"
}

# add_resource <plant> <file name>: stdin becomes a new resource of the plant's overlay.
add_resource() {
    overlay=$(cat "$PLANTS/$1/overlay")
    cat > "$PLANTS/$1/deploy/overlays/$overlay/$2"
    mutate "$1" "overlays/$overlay/kustomization.yaml" "s|^  - \.\./\.\./base\$|  - ../../base\n  - $2|"
}

# Kubernetes' own field, named once here: this line's shape is not read as a credential by
# CommittedConfigurationHoldsNoSecretTest, and a sed program saying it literally would be.
SWITCH=automountServiceAccountToken
DEPLOYMENT=base/deployment.yaml
HPA=base/horizontalpodautoscaler.yaml
PRODUCTION=overlays/production/kustomization.yaml
KIND=overlays/kind/kustomization.yaml

plant_all() {
    # The stream was read.
    plant_on stream-empty "$DEPLOYMENT" 's/^  name: finapp-app$/  name: not-finapp-app/'
    plant_on stream-no-container "$DEPLOYMENT" 's/^        - name: app$/        - name: not-app/'
    plant stream-overlay
    printf 'nothing: here\n' > "$PLANTS/stream-overlay/overlay.yaml"

    # The restricted profile, on the application.
    plant_on non-root "$DEPLOYMENT" 's/runAsNonRoot: true/runAsNonRoot: false/'
    plant_on non-root-absent "$DEPLOYMENT" '/runAsNonRoot: true/d'
    plant_on non-root-uid "$DEPLOYMENT" 's/^        runAsUser: 65532$/        runAsUser: 0/'
    plant_on non-root-uid-absent "$DEPLOYMENT" '/^        runAsUser: 65532$/d'
    plant_on seccomp-unconfined "$DEPLOYMENT" 's/type: RuntimeDefault/type: Unconfined/'
    plant_on seccomp-absent "$DEPLOYMENT" '/seccompProfile:/d;/type: RuntimeDefault/d'
    plant_on escalation "$DEPLOYMENT" 's/allowPrivilegeEscalation: false/allowPrivilegeEscalation: true/'
    plant_on read-only "$DEPLOYMENT" 's/readOnlyRootFilesystem: true/readOnlyRootFilesystem: false/'
    plant_on capability-kept "$DEPLOYMENT" 's/^                - ALL$/                - NET_RAW/'
    plant_on capability-added "$DEPLOYMENT" 's/^              drop:$/              add:\n                - NET_ADMIN\n              drop:/'
    plant_on privileged "$DEPLOYMENT" 's/privileged: false/privileged: true/'
    plant_on host-network "$DEPLOYMENT" "s/^      $SWITCH: false\$/      $SWITCH: false\n      hostNetwork: true/"
    plant_on host-path "$DEPLOYMENT" 's/^          emptyDir:$/          hostPath:\n            path: \/var\/run\n        - name: extra\n          emptyDir:/'
    plant_on automount-on-pod "$DEPLOYMENT" "s/^      $SWITCH: false\$/      $SWITCH: true/"
    plant_on automount-on-account base/serviceaccount.yaml "1,/$SWITCH: false/s/$SWITCH: false/$SWITCH: true/"
    plant_on default-account "$DEPLOYMENT" '/serviceAccountName: finapp-app/d'

    # A key stated with no value reads as null, and a rule that walks into it passes with the hardening gone.
    plant_on null-security-context "$DEPLOYMENT" '/^          securityContext:$/,/^                - ALL$/{/^          securityContext:$/!d}'
    plant_on null-pod-security-context "$DEPLOYMENT" '/^      securityContext:$/,/^          type: RuntimeDefault$/{/^      securityContext:$/!d}'
    plant_on null-seccomp "$DEPLOYMENT" '/type: RuntimeDefault/d'
    plant_on null-resources "$DEPLOYMENT" '/^            requests:$/,/^              memory: 2Gi$/d;/^            limits:$/,/^              memory: 2Gi$/d'
    plant_on null-rolling-update "$DEPLOYMENT" '/^      maxUnavailable: 0$/d;/^      maxSurge: 1$/d'
    plant_on null-probe-test-only overlays/kind/postgres.yaml '/^          readinessProbe:$/,/^            timeoutSeconds: 3$/{/^          readinessProbe:$/!d}' kind
    # Kustomize renders a bare initContainers as an empty list, so the null is planted in the stream. Under it the main
    # container's root is writable: a list that stops the whole container walk would hide exactly that.
    plant_on null-init-containers "$DEPLOYMENT" 's/^      containers:$/      initContainers:\n      containers:/;s/readOnlyRootFilesystem: true/readOnlyRootFilesystem: false/'
    after_render null-init-containers 's/^      initContainers: \[\]$/      initContainers: null/'

    # The same, on what a rule written for the application's container would miss: an init container, the migrator
    # Job, the test-only copies.
    plant_on init-container "$DEPLOYMENT" 's/^      containers:$/      initContainers:\n        - name: init\n          image: busybox@sha256:'"$ZEROS"'\n          resources:\n            requests: {cpu: 10m, memory: 8Mi}\n            limits: {cpu: 10m, memory: 8Mi}\n      containers:/'
    plant_on job-read-only base/migrator-job.yaml 's/readOnlyRootFilesystem: true/readOnlyRootFilesystem: false/'
    plant_on test-only-capability overlays/kind/kafka.yaml 's/^                - ALL$/                - NET_RAW/' kind

    # Probes.
    plant_on probe-absent "$DEPLOYMENT" 's/^          livenessProbe:$/          livenessProbeDropped:/;/livenessProbeDropped:/,/failureThreshold: 3/d'
    plant_on probe-api-port "$DEPLOYMENT" '/readinessProbe:/,/failureThreshold/s/port: management/port: http/'
    plant_on probe-liveness-reads-readiness "$DEPLOYMENT" '/livenessProbe:/,/failureThreshold/s#/actuator/health/liveness#/actuator/health/readiness#'
    plant_on probe-readiness-short "$DEPLOYMENT" 's/timeoutSeconds: 7/timeoutSeconds: 5/'
    plant_on probe-readiness-overlaps "$DEPLOYMENT" '/readinessProbe:/,/failureThreshold/s/periodSeconds: 10/periodSeconds: 7/'
    plant_on probe-liveness-patient "$DEPLOYMENT" '/livenessProbe:/,/failureThreshold/s/timeoutSeconds: 3/timeoutSeconds: 5/'
    plant_on probe-test-only overlays/kind/postgres.yaml 's/^          readinessProbe:$/          readinessProbeDropped:/;/readinessProbeDropped:/,/timeoutSeconds/d' kind

    # Requests and limits.
    plant_on resources-absent "$DEPLOYMENT" '/^            limits:$/,/^              memory: 2Gi$/d'
    plant_on memory-overcommit "$DEPLOYMENT" '1,/memory: 2Gi/s/memory: 2Gi/memory: 1Gi/'
    plant_on job-resources base/migrator-job.yaml '/limits:/,/memory: 512Mi/d'

    # Digest-only images: a tag, no digest at all, and a short digest.
    plant_on image-tag "$PRODUCTION" '1,/digest: sha256:/s/digest: sha256:'"$ZEROS"'/newTag: "1.0"/'
    plant image-no-digest
    mutate image-no-digest "$PRODUCTION" '/digest: sha256:/d'
    plant_on image-absent "$DEPLOYMENT" '/^          image: finapp-app$/d'
    plant_on job-image-tag "$PRODUCTION" '/name: finapp-migrator/,/digest:/s/digest: sha256:'"$ZEROS"'/newTag: "1.0"/'

    # What must not exist.
    plant no-cronjob
    add_resource no-cronjob cronjob.yaml <<'YAML'
apiVersion: batch/v1
kind: CronJob
metadata:
  name: nightly
spec:
  schedule: "0 1 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: job
              image: busybox@sha256:0000000000000000000000000000000000000000000000000000000000000000
YAML
    plant no-statefulset
    cp "$PLANTS/no-statefulset/deploy/overlays/kind/postgres.yaml" "$PLANTS/no-statefulset/deploy/overlays/production/postgres.yaml"
    mutate no-statefulset "$PRODUCTION" 's|^  - \.\./\.\./base$|  - ../../base\n  - postgres.yaml|'
    plant_on no-statefulset-unlabelled overlays/kind/postgres.yaml '1,/^kind: StatefulSet/!{/finapp\/test-only/d}' kind
    plant no-test-only
    add_resource no-test-only labelled.yaml <<'YAML'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: labelled
  labels:
    finapp/test-only: "true"
automountServiceAccountToken: false
YAML
    plant no-secret-data
    add_resource no-secret-data secret.yaml <<'YAML'
apiVersion: v1
kind: Secret
metadata:
  name: committed
stringData:
  note: this-is-a-plant
YAML
    plant_on inline-env-value "$DEPLOYMENT" 's/^          env:$/          env:\n            - name: FINAPP_DB_APP_PASSWORD\n              value: this-is-a-plant/'
    plant_on inline-config-value "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_KAFKA_JAAS_CONFIG=this-is-a-plant/'
    plant_on no-endpoint-url "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_CALLBACK=https:\/\/provider.invalid\/hook/'
    plant_on no-endpoint-key "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_DB_URL=placeholder/'
    plant_on no-endpoint-broker "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_BROKERS=broker-1:9092/'

    # The rollout.
    plant_on rollout-unavailable "$DEPLOYMENT" 's/maxUnavailable: 0/maxUnavailable: 1/'
    plant_on rollout-surge-percent "$DEPLOYMENT" 's/maxSurge: 1/maxSurge: 25%/'
    plant_on rollout-recreate "$DEPLOYMENT" 's/type: RollingUpdate/type: Recreate/;/rollingUpdate:/,/maxSurge: 1/d'
    plant_on rollout-strategy-absent "$DEPLOYMENT" '/^  strategy:$/,/^      maxSurge: 1$/d'
    plant_on rollout-surge-absent "$DEPLOYMENT" '/^      maxSurge: 1$/d'
    plant_on rollout-min-ready "$DEPLOYMENT" 's/minReadySeconds: 115/minReadySeconds: 114/'
    plant_on rollout-grace-short "$DEPLOYMENT" 's/terminationGracePeriodSeconds: 115/terminationGracePeriodSeconds: 110/'
    plant_on rollout-grace-absent "$DEPLOYMENT" '/^      terminationGracePeriodSeconds: 115$/d'
    plant_on rollout-replicas "$DEPLOYMENT" 's/^spec:$/spec:\n  replicas: 3/'
    plant_on rollout-deadline "$DEPLOYMENT" 's/progressDeadlineSeconds: 600/progressDeadlineSeconds: 200/'
    plant_on rollout-prestop-exec "$DEPLOYMENT" 's/^              sleep:$/              exec:/;s/^                seconds: 10$/                command: ["sleep", "10"]/'
    plant_on topology-zone "$DEPLOYMENT" 's/whenUnsatisfiable: DoNotSchedule/whenUnsatisfiable: ScheduleAnyway/'
    plant_on topology-node "$DEPLOYMENT" 's#topologyKey: kubernetes.io/hostname#topologyKey: topology.kubernetes.io/region#'
    plant_on management-address "$DEPLOYMENT" 's/fieldPath: status.podIP/fieldPath: status.hostIP/'
    plant_on management-address-literal "$DEPLOYMENT" '/name: FINAPP_MANAGEMENT_ADDRESS/,/fieldPath: status.podIP/{/valueFrom:/,/fieldPath: status.podIP/d};s/^            - name: FINAPP_MANAGEMENT_ADDRESS$/            - name: FINAPP_MANAGEMENT_ADDRESS\n              value: 0.0.0.0/'

    # The connection budget.
    plant_on budget-max-replicas "$HPA" 's/maxReplicas: 7/maxReplicas: 8/'
    plant_on budget-surge "$DEPLOYMENT" 's/maxSurge: 1/maxSurge: 2/'
    plant_on budget-overlay-lowers-instances "$PRODUCTION" 's/FINAPP_DB_INSTANCES=10/FINAPP_DB_INSTANCES=9/'
    plant_on budget-kind-max "$KIND" 's/value: 3/value: 4/' kind
    plant_on budget-staging-instances overlays/staging/kustomization.yaml 's/FINAPP_DB_INSTANCES=7/FINAPP_DB_INSTANCES=6/' staging
    plant budget-undeclared
    mutate budget-undeclared base/kustomization.yaml '/FINAPP_DB_RESERVED_CONNECTIONS/d'
    mutate budget-undeclared "$PRODUCTION" '/FINAPP_DB_RESERVED_CONNECTIONS/d'
    plant no-autoscaler
    mutate no-autoscaler base/kustomization.yaml '/horizontalpodautoscaler.yaml/d'
    plant_on budget-fleet "$PRODUCTION" 's/FINAPP_DB_SERVER_MAX_CONNECTIONS=100/FINAPP_DB_SERVER_MAX_CONNECTIONS=90/'
    plant_on budget-one-source-env "$DEPLOYMENT" 's/^          env:$/          env:\n            - name: FINAPP_DB_INSTANCES\n              value: "99"/'
    plant_on budget-one-source-pool "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=20/'
    plant_on budget-one-source-canonical "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE=20/'
    plant_on budget-one-source-idle "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - SPRING_DATASOURCE_HIKARI_MINIMUMIDLE=2/'
    plant_on budget-one-source-relaxed "$DEPLOYMENT" 's/^          env:$/          env:\n            - name: FINAPP_DATABASE_INSTANCES\n              value: "99"/'
    plant_on budget-server-max overlays/kind/postgres.yaml 's/max_connections=60/max_connections=100/' kind

    # The autoscaler.
    plant_on hpa-min "$HPA" 's/minReplicas: 3/minReplicas: 2/'
    plant_on hpa-scale-down-period "$HPA" 's/periodSeconds: 120/periodSeconds: 114/'
    plant_on hpa-scale-down-two "$HPA" '1,/value: 1/s/value: 1/value: 2/'
    plant_on hpa-scale-down-percent "$HPA" '1,/type: Pods/s/type: Pods/type: Percent/'
    plant_on hpa-no-behaviour "$HPA" '/^  behavior:$/,$d'
    plant_on hpa-stabilisation "$HPA" 's/stabilizationWindowSeconds: 300/stabilizationWindowSeconds: 30/'

    # The rest of the shape.
    plant_on pdb-two base/poddisruptionbudget.yaml 's/maxUnavailable: 1/maxUnavailable: 2/'
    plant no-pdb
    mutate no-pdb base/kustomization.yaml '/poddisruptionbudget.yaml/d'
    plant_on service-management base/service.yaml 's/^      targetPort: http$/      targetPort: management/'
    plant_on service-extra-port base/service.yaml 's/^      protocol: TCP$/      protocol: TCP\n    - name: management\n      port: 8081\n      targetPort: 8081/'
    plant_on service-load-balancer base/service.yaml 's/type: ClusterIP/type: LoadBalancer/'
    plant no-service
    mutate no-service base/kustomization.yaml '/^  - service\.yaml$/d'
    plant no-service-accounts
    mutate no-service-accounts base/kustomization.yaml '/^  - serviceaccount\.yaml$/d'
    plant_on config-no-hash base/kustomization.yaml 's/^configMapGenerator:$/generatorOptions:\n  disableNameSuffixHash: true\nconfigMapGenerator:/'
    plant_on config-not-read "$DEPLOYMENT" '/configMapRef:/,/name: finapp-app-config/d;/- secretRef:/d'
    plant_on job-unbounded base/migrator-job.yaml '/activeDeadlineSeconds: 900/d'
    plant_on job-retries base/migrator-job.yaml 's/backoffLimit: 2/backoffLimit: 10/'
    plant_on job-failure-policy base/migrator-job.yaml '/^  podFailurePolicy:$/,/^            - 12$/d'
    plant_on job-entrypoint base/migrator-job.yaml 's/^          image: finapp-migrator$/          image: finapp-migrator\n          args: ["clean"]/'
    plant_on job-restarts base/migrator-job.yaml 's/restartPolicy: Never/restartPolicy: OnFailure/'
    plant no-migrator
    mutate no-migrator base/kustomization.yaml '/migrator-job.yaml/d'

    # kubeconform and kustomize: what no policy reads.
    plant_on schema-typo "$DEPLOYMENT" 's/^  revisionHistoryLimit: 10$/  revisionHistoryLimits: 10/'
    plant_on schema-type "$DEPLOYMENT" 's/^  revisionHistoryLimit: 10$/  revisionHistoryLimit: ten/'
    plant render-broken
    mutate render-broken "$PRODUCTION" 's|^  - \.\./\.\./base$|  - ../../base-that-is-not-there|'
}

in_container() {
    step=$1
    shift
    case $step in
        render)
            mkdir -p /scratch/rendered
            for overlay in $OVERLAYS; do
                kustomize build "/repo/deploy/overlays/$overlay" > "/scratch/rendered/$overlay.yaml"
                printf 'overlay: %s\n' "$overlay" > "/scratch/rendered/$overlay.data.yaml"
            done
            ;;
        plants)
            plant_all
            # A render that fails leaves an empty stream and its own error, which the host half reads.
            for directory in "$PLANTS"/*; do
                name=${directory##*/}
                overlay=$(cat "$directory/overlay")
                kustomize build "$directory/deploy/overlays/$overlay" > "$directory/rendered.yaml" 2> "$directory/render.err" \
                    || printf 'failed\n' > "$directory/render.failed"
                [ -s "$directory/rendered.yaml" ] || [ -f "$directory/render.failed" ] \
                    || { echo "!!   plant $name rendered nothing" >&2; exit 1; }
                if [ -f "$directory/after-render.sed" ]; then
                    cp "$directory/rendered.yaml" "$directory/rendered.before"
                    sed -i -f "$directory/after-render.sed" "$directory/rendered.yaml"
                    if cmp -s "$directory/rendered.before" "$directory/rendered.yaml"; then
                        echo "!!   plant $name was not planted: its rendered stream is unchanged" >&2
                        exit 1
                    fi
                fi
            done
            ;;
        *)
            echo "unknown step: $step" >&2
            exit 2
            ;;
    esac
}

if [ "${1:-}" = "--in-container" ]; then
    shift
    in_container "$@"
    exit 0
fi

# ---------------------------------------------------------------------------------------------------
# On the host (a laptop or CI's runner).
# ---------------------------------------------------------------------------------------------------
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

KUSTOMIZE_IMAGE="${KUSTOMIZE_IMAGE:-${KUSTOMIZE_REPOSITORY}@${KUSTOMIZE_DIGEST}}"
KUBECONFORM_IMAGE="${KUBECONFORM_IMAGE:-${KUBECONFORM_REPOSITORY}@${KUBECONFORM_DIGEST}}"
CONFTEST_IMAGE="${CONFTEST_IMAGE:-${CONFTEST_REPOSITORY}@${CONFTEST_DIGEST}}"

SCHEMAS="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/${KUBERNETES_SCHEMAS_COMMIT}/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"

if ! docker info >/dev/null 2>&1; then
    echo "Docker is not available: every check runs in a pinned image." >&2
    exit 2
fi

VOLUME="finapp-manifest-check-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
docker volume create "$VOLUME" >/dev/null
trap 'docker volume rm -f "$VOLUME" >/dev/null 2>&1 || true' EXIT
trap 'exit 130' INT TERM

# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /repo and /scratch into host paths. Unset and harmless on Linux,
# where CI runs.
in_kustomize() {
    MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh \
        -v "$ROOT/deploy:/repo/deploy:ro" -v "$DIR:/repo/infra:ro" -v "$VOLUME:/scratch" \
        "$KUSTOMIZE_IMAGE" /repo/infra/scripts/manifest-check.sh --in-container "$@"
}

kubeconform_on() {
    MSYS_NO_PATHCONV=1 docker run --rm -v "$VOLUME:/scratch" "$KUBECONFORM_IMAGE" \
        -strict -summary -cache /scratch \
        -kubernetes-version "$KUBERNETES_VERSION" -schema-location "$SCHEMAS" "$1"
}

# conftest_on <rendered stream> <file naming the overlay>
conftest_on() {
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "$DIR/scripts/manifest-policy:/policy:ro" \
        -v "$ROOT/app/src/main/resources/application.yaml:/application/application.yaml:ro" \
        -v "$VOLUME:/scratch:ro" \
        "$CONFTEST_IMAGE" test --no-color --combine --all-namespaces=false --namespace main --policy /policy/finapp.rego \
        --data /application/application.yaml --data /policy/shutdown-budget.yaml --data "$2" "$1"
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

# refuses <plant> <reason> <command...>: the command must fail, and its output must name the reason.
refuses() {
    label=$1 reason=$2
    shift 2
    if output=$("$@" 2>&1); then
        printf '%s\n' "$output"
        echo "!!   plant $label was NOT refused"
        status=1
    elif printf '%s\n' "$output" | grep -qF -- "$reason"; then
        echo "ok   plant $label refused: $(printf '%s\n' "$output" | grep -F -m 1 -- "$reason" | sed 's/^[[:space:]]*//' | cut -c1-150)"
    else
        printf '%s\n' "$output"
        echo "!!   plant $label was refused, but not for its reason: the output never says '$reason'"
        status=1
    fi
}

# refused <plant> <rule id>: the plant's rendered stream, through the policies.
refused() {
    refuses "$1" "[$2]" conftest_on "/scratch/plants/$1/rendered.yaml" "/scratch/plants/$1/overlay.yaml"
}

# render_refused <plant> <reason>: the plant fails to render at all.
render_refused() {
    refuses "$1" "$2" in_kustomize_read "$1"
}

in_kustomize_read() {
    MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh -v "$VOLUME:/scratch:ro" "$KUSTOMIZE_IMAGE" \
        -c "[ ! -f /scratch/plants/$1/render.failed ] || { cat /scratch/plants/$1/render.err; exit 1; }"
}

schema_refused() {
    refuses "$1" "$2" kubeconform_on "/scratch/plants/$1/rendered.yaml"
}

check "every overlay renders" in_kustomize render
check "the plants are planted and rendered" in_kustomize plants

for overlay in $OVERLAYS; do
    check "kubeconform $overlay" kubeconform_on "/scratch/rendered/$overlay.yaml"
    check "the policies on $overlay" conftest_on "/scratch/rendered/$overlay.yaml" "/scratch/rendered/$overlay.data.yaml"
done

refused stream-empty stream-empty
refused stream-no-container stream-empty
refused stream-overlay stream-overlay

refused null-security-context security-escalation
refused null-security-context security-read-only
refused null-security-context security-capabilities
refused null-pod-security-context security-non-root
refused null-pod-security-context security-seccomp
refused null-seccomp security-seccomp
refused null-resources resources-present
refused null-rolling-update rollout-strategy
refused null-probe-test-only probes-present
refused null-init-containers security-read-only

refused non-root security-non-root
refused non-root-absent security-non-root
refused non-root-uid security-non-root-uid
refused non-root-uid-absent security-non-root-uid
refused seccomp-unconfined security-seccomp
refused seccomp-absent security-seccomp
refused escalation security-escalation
refused read-only security-read-only
refused capability-kept security-capabilities
refused capability-added security-capabilities
refused privileged security-privileged
refused host-network security-host
refused host-path security-host
refused automount-on-pod security-token
refused automount-on-account security-token
refused default-account security-service-account
refused init-container security-read-only
refused job-read-only security-read-only
refused test-only-capability security-capabilities

refused probe-absent probes-present
refused probe-api-port probes-management
refused probe-liveness-reads-readiness probes-management
refused probe-readiness-short probes-readiness-timeout
refused probe-readiness-overlaps probes-readiness-timeout
refused probe-liveness-patient probes-liveness-timeout
refused probe-test-only probes-present

refused resources-absent resources-present
refused memory-overcommit resources-memory
refused job-resources resources-present

refused image-tag images-digest
refused image-no-digest images-digest
refused image-absent images-digest
refused job-image-tag images-digest

refused no-cronjob no-cronjob
refused no-statefulset no-statefulset
refused no-statefulset-unlabelled no-statefulset
refused no-test-only no-test-only
refused no-secret-data no-secret-data
refused inline-env-value no-secret-literal
refused inline-config-value no-secret-literal
refused no-endpoint-url no-endpoint
refused no-endpoint-key no-endpoint
refused no-endpoint-broker no-endpoint

refused rollout-unavailable rollout-strategy
refused rollout-surge-percent rollout-strategy
refused rollout-recreate rollout-strategy
refused rollout-strategy-absent rollout-strategy
refused rollout-surge-absent rollout-strategy
refused rollout-min-ready rollout-min-ready
refused rollout-grace-short rollout-grace
refused rollout-grace-absent rollout-grace
refused rollout-replicas rollout-replicas
refused rollout-deadline rollout-deadline
refused rollout-prestop-exec rollout-prestop
refused topology-zone topology-spread
refused topology-node topology-spread
refused management-address management-address
refused management-address-literal management-address

refused budget-max-replicas budget-instances
refused budget-surge budget-instances
refused budget-overlay-lowers-instances budget-instances
refused budget-kind-max budget-instances
refused budget-staging-instances budget-instances
refused budget-undeclared budget-declared
refused no-autoscaler budget-declared
refused budget-fleet budget-fleet
refused budget-one-source-env budget-one-source
refused budget-one-source-pool budget-one-source
refused budget-one-source-canonical budget-one-source
refused budget-one-source-idle budget-one-source
refused budget-one-source-relaxed budget-one-source
refused budget-server-max budget-server-max

refused hpa-min hpa-min
refused hpa-scale-down-period hpa-scale-down
refused hpa-scale-down-two hpa-scale-down
refused hpa-scale-down-percent hpa-scale-down
refused hpa-no-behaviour hpa-scale-down
refused hpa-stabilisation hpa-scale-down

refused pdb-two pdb
refused no-pdb pdb
refused service-management service-api-only
refused service-extra-port service-api-only
refused service-load-balancer service-api-only
refused no-service service-present
refused no-service-accounts security-service-account
refused config-no-hash config-hash
refused config-not-read config-hash
refused job-unbounded job-bounds
refused job-retries job-bounds
refused job-failure-policy job-failure-policy
refused job-entrypoint job-entrypoint
refused job-restarts job-bounds
refused no-migrator job-bounds

schema_refused schema-typo "revisionHistoryLimits"
schema_refused schema-type "revisionHistoryLimit"
render_refused render-broken "base-that-is-not-there"

if [ "$status" -eq 0 ]; then
    echo "ok   every check green, every plant refused for its reason"
fi
exit $status
