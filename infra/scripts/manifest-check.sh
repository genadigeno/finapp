#!/bin/sh
# Check the Kubernetes manifests (P15-TSK-009, ADR-0120).
#
# Three checks over every overlay under deploy/overlays: `kustomize build`, `kubeconform -strict` against schemas
# pinned to one commit, and the policies of infra/scripts/manifest-policy/finapp.rego under conftest. The policies read
# application.yaml itself for the pools and the timeout, so the budget is checked against the file the application
# reads, not a copy of its numbers.
#
# Then the kind rehearsal's secret generator, in its render mode, into this run's own volume (P15-TSK-018): its values are random,
# removed at the end of the step, and never leave the container.
#
# THEN EACH POLICY AGAIN, OVER A PLANT IT MUST REFUSE. A policy that is wired but cannot fail reads as assurance it does
# not give. So every run copies deploy/, plants one defect per copy, renders that copy with the same kustomize and runs
# the same check over it, and requires it to fail AND to name the rule. A plant accepted, refused for another reason, or
# no longer planted (its source changed under the mutation) fails this script. KubernetesManifestsTest requires the
# plants to cover every rule id in finapp.rego.
#
# WHAT IT NEVER DOES. It talks to no cluster and holds no credential: no container is given a variable from the host,
# the repository is mounted read-only, and a scratch volume of its own is removed at exit. Nothing here can change the
# tree. The network is used for the three pinned images and for the schemas, each catalogue read at its own pinned commit.
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
# The operator's own field, named once here for the reason SWITCH is.
ENTRY=secretKey
APP_DELIVERY=components/secret-delivery/externalsecret-app.yaml
MIGRATOR_DELIVERY=components/secret-delivery/externalsecret-migrator.yaml
PRODUCTION_STORE=overlays/production/secret-store.yaml

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
    plant no-secret-data-field
    add_resource no-secret-data-field secret.yaml <<'YAML'
apiVersion: v1
kind: Secret
metadata:
  name: committed
data:
  note: dGhpcy1pcy1hLXBsYW50
YAML
    plant generated-object
    mutate generated-object "$PRODUCTION" 's/^images:$/secretGenerator:\n  - name: generated\n    literals:\n      - note=this-is-a-plant\n\nimages:/'
    plant no-secret-empty
    add_resource no-secret-empty secret.yaml <<'YAML'
apiVersion: v1
kind: Secret
metadata:
  name: finapp-app-secrets
YAML
    plant no-secret-in-list
    add_resource no-secret-in-list list.yaml <<'YAML'
apiVersion: v1
kind: List
items:
  - apiVersion: v1
    kind: Secret
    metadata:
      name: listed
    stringData:
      note: this-is-a-plant
YAML

    # Delivery from the store.
    plant delivery-absent
    mutate delivery-absent "$PRODUCTION" '/^components:$/,/secret-delivery$/d'
    plant_on delivery-on-kind "$KIND" 's/^resources:$/components:\n  - ..\/..\/components\/secret-delivery\n\nresources:/' kind
    plant_on delivery-unknown "$APP_DELIVERY" 's/^    name: finapp-app-secrets$/    name: another-secret/'
    plant_on delivery-optional-app "$DEPLOYMENT" 's/^                name: finapp-app-secrets$/                name: finapp-app-secrets\n                optional: true/'
    plant_on delivery-optional-migrator base/migrator-job.yaml 's/^                name: finapp-migrator-secrets$/                name: finapp-migrator-secrets\n                optional: true/'
    plant_on delivery-undelivered base/migrator-job.yaml 's/name: finapp-migrator-secrets/name: nothing-delivers-this/'
    plant_on separation-reader base/migrator-job.yaml 's/name: finapp-migrator-secrets/name: finapp-app-secrets/'
    plant_on separation-second-ref base/migrator-job.yaml 's/^                name: finapp-migrator-secrets$/                name: finapp-migrator-secrets\n            - secretRef:\n                name: finapp-app-secrets/'
    plant_on separation-app-holds "$APP_DELIVERY" 's/^  data:$/  data:\n    - secretKey: FINAPP_DB_MIGRATOR_PASSWORD\n      remoteRef:\n        key: FINAPP_DB_MIGRATOR_PASSWORD/'
    plant_on separation-migrator-holds "$MIGRATOR_DELIVERY" 's/^  data:$/  data:\n    - secretKey: FINAPP_MFA_KEY\n      remoteRef:\n        key: FINAPP_MFA_KEY/'
    plant_on separation-migrator-short "$MIGRATOR_DELIVERY" '/FINAPP_DB_URL$/,/FINAPP_DB_URL$/d'
    plant_on sync-periodic "$APP_DELIVERY" 's/refreshPolicy: OnChange/refreshPolicy: Periodic/'
    plant_on sync-interval "$APP_DELIVERY" 's/refreshInterval: 0s/refreshInterval: 1h/'
    plant_on sync-orphan "$APP_DELIVERY" 's/creationPolicy: Owner/creationPolicy: Orphan/'
    plant_on sync-delete "$MIGRATOR_DELIVERY" 's/deletionPolicy: Retain/deletionPolicy: Delete/'
    plant_on sync-null "$APP_DELIVERY" 's/^    deletionPolicy: Retain$/    deletionPolicy: null/'
    plant_on sync-template "$APP_DELIVERY" 's/^    creationPolicy: Owner$/    creationPolicy: Owner\n    template:\n      type: Opaque/'
    plant_on entries-find "$APP_DELIVERY" 's/^  data:$/  dataFrom:\n    - find:\n        name:\n          regexp: .*\n  data:/'
    plant_on entries-other-key "$APP_DELIVERY" '1,/        key: FINAPP_CORRIDOR_PROVIDER_B_KEY/s/        key: FINAPP_CORRIDOR_PROVIDER_B_KEY/        key: FINAPP_MFA_KEY/'
    plant_on entries-property "$APP_DELIVERY" '1,/        key: FINAPP_CORRIDOR_PROVIDER_B_KEY/s/        key: FINAPP_CORRIDOR_PROVIDER_B_KEY/        key: FINAPP_CORRIDOR_PROVIDER_B_KEY\n        property: inner/'
    plant_on entries-plain-source "$APP_DELIVERY" '1,/^        key: FINAPP_CORRIDOR_PROVIDER_B_KEY$/s/^      remoteRef:$/      sourceRef:\n        storeRef:\n          kind: SecretStore\n          name: finapp-app-kafka-store\n      remoteRef:/'
    plant_on entries-name-shape "$APP_DELIVERY" "s/^    - $ENTRY: FINAPP_CORRIDOR_PROVIDER_B_KEY\$/    - $ENTRY: finapp_corridor_provider_b_key/;s/^        key: FINAPP_CORRIDOR_PROVIDER_B_KEY\$/        key: finapp_corridor_provider_b_key/"
    plant entries-empty
    mutate entries-empty "$MIGRATOR_DELIVERY" '/^  data:$/,$d'
    printf '  data: []\n' >> "$PLANTS/entries-empty/deploy/$MIGRATOR_DELIVERY"
    plant_on entries-brokers-plain "$APP_DELIVERY" "/^    - $ENTRY: FINAPP_KAFKA_SASL_PASSWORD\$/{n;N;N;N;N;N;N;s/.*/      remoteRef:\n        key: FINAPP_KAFKA_SASL_PASSWORD/}"
    plant_on entries-brokers-swapped "$APP_DELIVERY" 's/^        property: password$/        property: username/'
    plant_on entries-brokers-other-store "$APP_DELIVERY" 's/^          name: finapp-app-kafka-store$/          name: finapp-app-store/'
    plant_on store-brokers-other-path "$PRODUCTION_STORE" 's/prefix: AmazonMSK_finapp-production-/prefix: AmazonMSK_finapp-staging-/'
    plant no-brokers-store
    mutate no-brokers-store "$PRODUCTION_STORE" '/^# The application.s SCRAM entry/,$d'
    plant_on store-role "$PRODUCTION_STORE" '1,/^      service: SecretsManager$/s/^      service: SecretsManager$/      service: SecretsManager\n      role: arn:aws:iam::000000000000:role\/placeholder-assumed/'
    plant_on store-more-roles "$PRODUCTION_STORE" '1,/^      service: SecretsManager$/s/^      service: SecretsManager$/      service: SecretsManager\n      additionalRoles:\n        - arn:aws:iam::000000000000:role\/placeholder-assumed/'
    plant placeholder-no-reader
    mutate placeholder-no-reader "$PRODUCTION_STORE" '/^  name: finapp-migrator-secret-reader$/,/^---$/{/^  annotations:$/d;/eks.amazonaws.com/d}'
    plant_on identity-workload-reader "$DEPLOYMENT" 's/serviceAccountName: finapp-app$/serviceAccountName: finapp-app-secret-reader/'
    plant_on identity-role-on-workload base/serviceaccount.yaml '1,/^  name: finapp-app$/s/^  name: finapp-app$/  name: finapp-app\n  annotations:\n    eks.amazonaws.com\/role-arn: arn:aws:iam::000000000000:role\/placeholder-workload/'
    plant kind-cluster-external
    add_resource kind-cluster-external forbidden.yaml <<'YAML'
apiVersion: external-secrets.io/v1
kind: ClusterExternalSecret
metadata:
  name: planted
YAML
    plant kind-push
    add_resource kind-push forbidden.yaml <<'YAML'
apiVersion: external-secrets.io/v1alpha1
kind: PushSecret
metadata:
  name: planted
YAML
    plant kind-cluster-push
    add_resource kind-cluster-push forbidden.yaml <<'YAML'
apiVersion: external-secrets.io/v1alpha1
kind: ClusterPushSecret
metadata:
  name: planted
YAML
    plant_on store-cluster "$PRODUCTION_STORE" '1,/^kind: SecretStore$/s/^kind: SecretStore$/kind: ClusterSecretStore/'
    plant_on store-other-path "$PRODUCTION_STORE" 's/prefix: finapp\/production\/app\//prefix: finapp\/staging\/app\//'
    plant_on store-shared "$APP_DELIVERY" 's/name: finapp-app-store/name: finapp-migrator-store/'
    plant_on store-reader "$PRODUCTION_STORE" 's/^            name: finapp-app-secret-reader$/            name: finapp-app/'
    plant_on store-static "$PRODUCTION_STORE" '1,/^        jwt:$/s/^        jwt:$/        secretRef:\n          accessKeyIDSecretRef:\n            name: planted\n            key: planted\n        jwt:/'
    plant_on placeholder-account "$PRODUCTION_STORE" '1,/role-arn: arn:aws:iam::000000000000/s/role-arn: arn:aws:iam::000000000000/role-arn: arn:aws:iam::123456789012/'
    plant_on placeholder-region "$PRODUCTION_STORE" '1,/      region: placeholder-region-0/s/      region: placeholder-region-0/      region: eu-central-1/'
    plant_on placeholder-any-field "$DEPLOYMENT" 's/^        finapp\/rotation-generation: "0"$/        finapp\/rotation-generation: "0"\n        planted: arn:aws:iam::123456789012:role\/planted/'
    plant_on generation-absent "$DEPLOYMENT" '/finapp\/rotation-generation: "0"/d'
    plant_on generation-ahead "$DEPLOYMENT" 's/finapp\/rotation-generation: "0"/finapp\/rotation-generation: "1"/'
    plant_on generation-delivery-absent "$APP_DELIVERY" '/finapp\/rotation-generation: "0"/d'

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
        kind-secrets)
            check_kind_secrets
            ;;
        *)
            echo "unknown step: $step" >&2
            exit 2
            ;;
    esac
}

# The generator the kind rehearsal runs (infra/scripts/kind-secrets.sh), in its render mode, twice, into this run's own volume.
check_kind_secrets() {
    fail() {
        echo "!!   kind-secrets: $1" >&2
        exit 1
    }
    generate() {
        mkdir -p "$1"
        FINAPP_DB_URL=not-a-host FINAPP_DB_APP_USER=not-a-user FINAPP_DB_REPORTING_USER=not-a-user \
            FINAPP_DB_MIGRATOR_USER=not-a-user FINAPP_DB_MASTER_USER=not-a-user \
            FINAPP_KAFKA_BOOTSTRAPSERVERS=not-a-host FINAPP_KAFKA_SASL_USERNAME=not-a-user \
            /bin/sh /repo/infra/scripts/kind-secrets.sh render "$1"
    }
    value() {
        sed -n "s/^$2=//p" "$1"
    }

    # A name that is not a secret and that the caller did not supply is refused, never guessed.
    mkdir -p /scratch/kind-secrets-refused
    if /bin/sh /repo/infra/scripts/kind-secrets.sh render /scratch/kind-secrets-refused 2> /scratch/kind-secrets-refused/err; then
        fail "ran with no value for the endpoints and users"
    fi
    grep -q 'is not a secret, so it is not generated' /scratch/kind-secrets-refused/err || fail "refused for another reason"

    # An entry this parser cannot read, written in another order, is refused rather than dropped from the Secret.
    mkdir -p /scratch/kind-secrets-reordered/copy/infra/scripts /scratch/kind-secrets-reordered/out
    cp -R /repo/deploy /scratch/kind-secrets-reordered/copy/deploy
    cp /repo/infra/scripts/kind-secrets.sh /scratch/kind-secrets-reordered/copy/infra/scripts/kind-secrets.sh
    sed -i "s/^    - $ENTRY: FINAPP_DB_URL\$/    - remoteRef:\n        key: FINAPP_DB_URL\n      $ENTRY: FINAPP_DB_URL/;/^      remoteRef:\$/{N;/key: FINAPP_DB_URL\$/d}" \
        /scratch/kind-secrets-reordered/copy/deploy/components/secret-delivery/externalsecret-migrator.yaml
    if FINAPP_DB_URL=not-a-host FINAPP_DB_APP_USER=not-a-user FINAPP_DB_REPORTING_USER=not-a-user \
        FINAPP_DB_MIGRATOR_USER=not-a-user FINAPP_DB_MASTER_USER=not-a-user \
        FINAPP_KAFKA_BOOTSTRAPSERVERS=not-a-host FINAPP_KAFKA_SASL_USERNAME=not-a-user \
        /bin/sh /scratch/kind-secrets-reordered/copy/infra/scripts/kind-secrets.sh render /scratch/kind-secrets-reordered/out \
        2> /scratch/kind-secrets-reordered/err; then
        fail "ran over an entry it could not read"
    fi
    grep -q 'could be read' /scratch/kind-secrets-reordered/err || fail "an unreadable entry was refused for another reason"
    rm -rf /scratch/kind-secrets-reordered

    first=/scratch/kind-secrets-first
    second=/scratch/kind-secrets-second
    generate "$first"
    generate "$second"
    for object in finapp-app-secrets finapp-migrator-secrets finapp-kind-bootstrap finapp-kind-postgres; do
        [ -s "$first/$object.env" ] || fail "$object was not generated"
    done

    # Every key of each ExternalSecret and nothing else.
    for pair in app:finapp-app-secrets migrator:finapp-migrator-secrets; do
        wanted=$(sed -n 's/^ *- secretKey: *\(FINAPP_[A-Z0-9_]*\) *$/\1/p' "/repo/deploy/components/secret-delivery/externalsecret-${pair%%:*}.yaml" | sort)
        [ -n "$wanted" ] || fail "no secretKey read for ${pair%%:*}"
        got=$(cut -d= -f1 "$first/${pair##*:}.env" | sort)
        [ "$wanted" = "$got" ] || fail "${pair##*:} does not hold exactly its ExternalSecret's keys"
    done

    # The shape the application's guards decode.
    for file in "$first"/*.env; do
        while IFS= read -r line; do
            name=${line%%=*}
            content=${line#*=}
            case $name in
                *_KEY)
                    [ "$(printf '%s' "$content" | base64 -d | wc -c)" -eq 32 ] || fail "$name does not decode to 32 bytes"
                    ;;
                *_PASSWORD | password)
                    printf '%s' "$content" | grep -Eq '^[0-9a-f]{64}$' || fail "$name is not 64 hex characters"
                    ;;
            esac
        done < "$file"
    done

    # The same value wherever one name is carried, and a different one on every run.
    for pair in finapp-app-secrets:FINAPP_DB_APP_PASSWORD finapp-app-secrets:FINAPP_DB_REPORTING_PASSWORD finapp-migrator-secrets:FINAPP_DB_MIGRATOR_PASSWORD; do
        name=${pair##*:}
        [ "$(value "$first/${pair%%:*}.env" "$name")" = "$(value "$first/finapp-kind-bootstrap.env" "$name")" ] \
            || fail "$name differs between ${pair%%:*} and the bootstrap's"
    done
    [ "$(value "$first/finapp-kind-postgres.env" password)" = "$(value "$first/finapp-kind-bootstrap.env" FINAPP_DB_MASTER_PASSWORD)" ] \
        || fail "the test-only server's password is not the bootstrap's master credential"
    [ "$(value "$first/finapp-app-secrets.env" FINAPP_MFA_KEY)" != "$(value "$second/finapp-app-secrets.env" FINAPP_MFA_KEY)" ] \
        || fail "two runs generated the same key"
    [ "$(value "$first/finapp-app-secrets.env" FINAPP_DB_APP_PASSWORD)" != "$(value "$second/finapp-app-secrets.env" FINAPP_DB_APP_PASSWORD)" ] \
        || fail "two runs generated the same password"
    [ "$(value "$first/finapp-app-secrets.env" FINAPP_MFA_KEY)" != "$(value "$first/finapp-app-secrets.env" FINAPP_DOC_KEY)" ] \
        || fail "two keys of one run are the same"
    rm -rf /scratch/kind-secrets-first /scratch/kind-secrets-second /scratch/kind-secrets-refused
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
# ExternalSecret and SecretStore are custom kinds, read from their own catalogue at one commit. A kind with no schema stays an
# error here: an object no schema reads is an object nothing checks.
CRD_SCHEMAS="https://raw.githubusercontent.com/datreeio/CRDs-catalog/${ESO_CRD_SCHEMAS_COMMIT}/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

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
        -kubernetes-version "$KUBERNETES_VERSION" -schema-location "$SCHEMAS" -schema-location "$CRD_SCHEMAS" "$1"
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

# refused_saying <plant> "<message>": the same, by the message, where a sibling deny carries the same id and the id alone
# would accept the wrong refusal. KubernetesManifestsTest reads the id from the start of the message.
refused_saying() {
    refuses "$1" "$2" conftest_on "/scratch/plants/$1/rendered.yaml" "/scratch/plants/$1/overlay.yaml"
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
check "the kind rehearsal's generated Secrets" in_kustomize kind-secrets

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
refused no-secret-data no-secret-manifest
refused no-secret-data-field no-secret-data
refused generated-object no-secret-data
refused generated-object no-secret-manifest
refused no-secret-empty no-secret-manifest
refused no-secret-in-list no-secret-manifest
refused delivery-absent secret-delivery
refused delivery-on-kind secret-delivery
refused delivery-unknown secret-delivery
refused_saying delivery-undelivered "[secret-delivery] Job/finapp-migrator reads Secret nothing-delivers-this through envFrom, which nothing delivers"
refused_saying delivery-optional-app "[secret-delivery] Deployment/finapp-app must read finapp-app-secrets through envFrom, and not optionally"
refused_saying delivery-optional-migrator "[secret-delivery] Job/finapp-migrator must read finapp-migrator-secrets through envFrom, and not optionally"
refused_saying separation-reader "[secret-separation] Job/finapp-migrator reads finapp-app-secrets, which only finapp-app may read"
refused_saying separation-second-ref "[secret-separation] Job/finapp-migrator reads finapp-app-secrets, which only finapp-app may read"
refused separation-app-holds secret-separation
refused separation-migrator-holds secret-separation
refused separation-migrator-short secret-separation
refused sync-periodic secret-sync
refused sync-interval secret-sync
refused sync-orphan secret-sync
refused sync-template secret-sync
refused sync-delete secret-sync
refused sync-null secret-sync
refused entries-find secret-entries
refused_saying entries-empty "[secret-entries] ExternalSecret/finapp-migrator-secrets has no entries"
refused entries-plain-source secret-entries
refused entries-name-shape secret-entries
refused entries-brokers-plain secret-entries
refused entries-brokers-swapped secret-entries
refused entries-brokers-other-store secret-entries
refused entries-other-key secret-entries
refused entries-property secret-entries
refused_saying store-cluster "[secret-store] ClusterSecretStore/"
refused_saying kind-cluster-external "[secret-store] ClusterExternalSecret/"
refused_saying kind-push "[secret-store] PushSecret/"
refused_saying kind-cluster-push "[secret-store] ClusterPushSecret/"
refused_saying store-role "[secret-store] SecretStore/finapp-app-store must be the store"
refused_saying store-more-roles "[secret-store] SecretStore/finapp-app-store must be the store"
refused_saying store-brokers-other-path "[secret-store] SecretStore/finapp-app-kafka-store must be the store"
refused_saying no-brokers-store "[secret-store] SecretStore finapp-app-kafka-store is not rendered"
refused store-other-path secret-store
refused store-shared secret-store
refused store-reader secret-store
refused store-static secret-store
refused_saying placeholder-account "[secret-placeholder] ServiceAccount finapp-app-secret-reader must exist and carry the placeholder role"
refused_saying placeholder-no-reader "[secret-placeholder] ServiceAccount finapp-migrator-secret-reader must exist"
refused_saying identity-workload-reader "[secret-identity] Deployment/finapp-app runs as a store reader"
refused_saying identity-role-on-workload "[secret-identity] ServiceAccount/finapp-app carries a cloud role"
refused placeholder-region secret-placeholder
refused_saying placeholder-any-field "[secret-placeholder] Deployment/finapp-app names an account"
refused generation-absent secret-generation
refused generation-ahead secret-generation
refused generation-delivery-absent secret-generation
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
