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
# AND A SECOND NET THE PLATFORM DID NOT WRITE (P16-TSK-004; THREAT_MODEL.md section 6 item 44 (b), ADR-0122 decision 2). The
# policies hold what the platform decided; `trivy config` reads what a maintained catalogue of Kubernetes misconfigurations
# reports, at HIGH and CRITICAL, the bar of PHASE_GATES.md section 5 Phase 15 criterion 2. It reads every kustomize root under
# deploy/, found by search rather than listed (a directory holding a kustomization.yaml that is not a Component, bases included,
# since each renders alone), and the delivery's committed roots. Every stream must come back read as Kubernetes, or a stream
# Trivy could not read would pass as a clean one. Every finding as read is printed, the carried ones included, and then the
# verdict: a finding fails the run unless infra/trivy/accepted-misconfigurations.rego carries that one finding, by its check
# and its whole message, with a reason and an expiry that THREAT_MODEL.md section 6 item 48 lists. Then three plants Trivy
# itself must refuse, by the check's id, over every root of the planted tree: a privileged edge controller, a telemetry back
# end mounting the node's root, and a carried finding widened by one more key. A hostPath of an ordinary directory is
# KSV-0023, MEDIUM, below the bar: the policies' [security-host] is what refuses every hostPath.
#
# WHAT IT NEVER DOES. It talks to no cluster and holds no credential: no container is given a variable from the host,
# the repository is mounted read-only, and a scratch volume of its own is removed at exit. Nothing here can change the
# tree. The network is used for the four pinned images and for the schemas, each catalogue read at its own pinned commit;
# Trivy runs with no network at all, on the checks embedded in its binary (--network none, and --skip-check-update,
# --disable-telemetry and --skip-version-check besides).
#
# SAME BYTES HERE AND IN CI. Every tool is an image pinned by digest in infra/scanner-pins.sh, and the schemas are read
# at KUBERNETES_SCHEMAS_COMMIT. KUSTOMIZE_IMAGE, KUBECONFORM_IMAGE, CONFTEST_IMAGE and TRIVY_IMAGE override an image, for a
# machine whose TLS is intercepted (OPERATIONS_RUNBOOK.md section 11.13); CI never sets them.
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
    plant_on inline-config-named-timeout "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_DB_PASSWORD_TOKENISATION_TIMEOUT=${plant}/'
    plant_on inline-config-timeout-not-a-duration "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_PAYMENTMETHODS_TOKENISATION_TIMEOUT=not-a-duration/'
    plant_on inline-config-tokenisation "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_PAYMENTMETHODS_TOKENISATION_TOKEN=${plant}/'
    plant_on no-endpoint-url "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_CALLBACK=https:\/\/provider.invalid\/hook/'
    plant_on no-endpoint-key "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_DB_URL=placeholder/'
    plant_on no-endpoint-broker "$PRODUCTION" 's/- FINAPP_DB_INSTANCES=10/- FINAPP_DB_INSTANCES=10\n      - FINAPP_BROKERS=broker-1:9092/'

    # The rollout.
    plant_on rollout-unavailable "$DEPLOYMENT" 's/maxUnavailable: 0/maxUnavailable: 1/'
    plant_on rollout-surge-percent "$DEPLOYMENT" 's/maxSurge: 1/maxSurge: 25%/'
    plant_on rollout-recreate "$DEPLOYMENT" 's/type: RollingUpdate/type: Recreate/;/rollingUpdate:/,/maxSurge: 1/d'
    plant_on rollout-strategy-absent "$DEPLOYMENT" '/^  strategy:$/,/^      maxSurge: 1$/d'
    plant_on rollout-surge-absent "$DEPLOYMENT" '/^      maxSurge: 1$/d'
    plant_on rollout-min-ready "$DEPLOYMENT" 's/minReadySeconds: 117/minReadySeconds: 116/'
    plant_on rollout-grace-short "$DEPLOYMENT" 's/terminationGracePeriodSeconds: 117/terminationGracePeriodSeconds: 110/'
    plant_on rollout-grace-absent "$DEPLOYMENT" '/^      terminationGracePeriodSeconds: 117$/d'
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
    # The release record (P15-TSK-017): every image the overlay renders is the recorded pair, wherever it is named. A finapp image under
    # another name kustomize's images: does not rewrite, as an init container of the Deployment and by a patch of the Job; and a record
    # that names another pair than the overlay's.
    OTHER=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
    plant_on release-elsewhere "$DEPLOYMENT" 's/^      containers:$/      initContainers:\n        - name: elsewhere\n          image: registry.invalid\/finapp-app@'"$OTHER"'\n          resources:\n            requests: {cpu: 10m, memory: 8Mi}\n            limits: {cpu: 10m, memory: 8Mi}\n      containers:/'
    plant_on release-patched "$PRODUCTION" 's|^images:$|patches:\n  - target:\n      kind: Job\n      name: finapp-migrator\n    patch: \|-\n      - op: replace\n        path: /spec/template/spec/containers/0/image\n        value: registry.invalid/finapp-migrator@'"$OTHER"'\nimages:|'
    plant_on release-record-differs overlays/production/release.yaml 's/^app: .*$/app: "'"$OTHER"'"/'
    plant_on release-staging-record-differs overlays/staging/release.yaml 's/^migrator: .*$/migrator: "'"$OTHER"'"/' staging

    # The delivery's Applications against Argo CD's schema (P15-TSK-017): a sync policy misspelled is refused, not read as none.
    plant delivery-schema-typo ../delivery/production
    mutate delivery-schema-typo delivery/production/applications.yaml 's/^    automated:$/    automatic:/'

    plant_namespace_security

    # The graded scan (P16-TSK-004): Trivy, not the policies, must refuse each over every root of the planted tree, by its check's id.
    # A privileged edge controller (KSV-0017). A telemetry back end mounting the node's root (KSV-0121, HIGH; an ordinary directory would
    # be KSV-0023, MEDIUM, below the bar). And one more key Trivy reads as a secret's name in the ConfigMap whose one such key is carried,
    # so the carried message no longer matches and KSV-0109 fails the run: a carry is one finding, never an object.
    plant_on trivy-privileged edge/base/controllers.yaml '1,/privileged: false/s/privileged: false/privileged: true/'
    plant_on trivy-host-root observability/overlays/kind/backends.yaml '1,/^      volumes:$/s/^      volumes:$/      volumes:\n        - name: planted\n          hostPath:\n            path: \//'
    plant_on trivy-carry-widened "$KIND" 's/^      - FINAPP_PAYMENTMETHODS_TOKENISATION_TIMEOUT=PT15S$/&\n      - FINAPP_PLANTED_TOKENISATION_TIMEOUT=PT15S/'
}

# render_roots <deploy dir> <output dir>: every kustomize root under the directory, found by search, one stream each, named for its path.
# A Component renders only inside another root, so it is skipped; a base renders alone, so it is a root.
render_roots() {
    mkdir -p "$2"
    for file in $(find "$1" -name kustomization.yaml | sort); do
        grep -q '^kind: Component$' "$file" && continue
        root=${file%/kustomization.yaml}
        kustomize build "$root" > "$2/$(printf '%s' "${root#"$1"/}" | tr / -).yaml"
    done
    # Never vacuous: the release's own overlay is among them, or the search read nothing.
    [ -s "$2/overlays-production.yaml" ] || { echo "!!   no kustomize root was rendered under $1" >&2; exit 1; }
}

# release_data <deploy dir> <overlay>: the pair an environment GitOps delivers is recorded to run (deploy/overlays/<environment>/release.yaml,
# P15-TSK-017), as the policies' data: release-images holds every image the overlay renders to it. An overlay with no record states none.
release_data() {
    case $2 in staging | production) ;; *) return 0 ;; esac
    for key in app migrator; do
        printf 'release_%s: "%s"\n' "$key" "$(sed -n "s/^$key: \"\{0,1\}\(sha256:[0-9a-f]*\)\"\{0,1\} *\$/\1/p" "$1/overlays/$2/release.yaml" 2>/dev/null)"
    done
}

# The namespaces are a root of their own, because an overlay's `namespace:` renames every Namespace it renders (see
# deploy/namespaces/kustomization.yaml). Appended to each overlay's stream so the policies read them together.
append_namespaces() {
    printf -- '---\n' >> "$2"
    kustomize build "$1/namespaces" >> "$2"
}

# One plant per way each namespace-security rule can be broken (P15-TSK-010), wildcards and null-valued keys included.
plant_namespace_security() {
    NS=namespaces/namespaces.yaml
    DENY=base/networkpolicy-default-deny.yaml
    APP=base/networkpolicy-app.yaml
    MIGRATOR=base/networkpolicy-migrator.yaml
    TIER=overlays/production/networkpolicy-data-tier.yaml
    RBAC=base/rbac-humans.yaml
    QUOTA=base/resourcequota.yaml
    LIMITS=base/limitrange.yaml
    # Kubernetes' own field, named once for the reason SWITCH is above.
    MINTED_SOURCE=serviceAccountToken

    # Namespaces and Pod Security.
    plant_on namespace-edge-absent "$NS" 's/^  name: finapp-edge$/  name: not-the-edge/'
    plant_on namespace-dropped "$PRODUCTION" '/^namespace: finapp$/d'
    plant_on psa-enforce-baseline "$NS" '1,/enforce: restricted/s/enforce: restricted/enforce: baseline/'
    plant_on psa-enforce-absent "$NS" '/pod-security.kubernetes.io\/enforce: restricted/d'
    plant_on psa-audit-absent "$NS" '/pod-security.kubernetes.io\/audit: restricted/d'
    plant_on psa-warn-absent "$NS" '/pod-security.kubernetes.io\/warn: restricted/d'
    plant_on psa-version-latest "$NS" '1,/enforce-version:/s/enforce-version: .*/enforce-version: latest/'
    plant_on psa-version-absent "$NS" '/pod-security.kubernetes.io\/enforce-version:/d'

    # Default-deny, and what would quietly stop it being one.
    plant no-default-deny
    mutate no-default-deny base/kustomization.yaml '/networkpolicy-default-deny.yaml/d'
    plant_on default-deny-ingress-only "$DENY" '/^    - Egress$/d'
    plant_on default-deny-opened "$DENY" 's/^    - Egress$/    - Egress\n  egress:\n    - {}/'
    plant_on default-deny-narrowed "$DENY" 's/^  podSelector: {}$/  podSelector:\n    matchLabels:\n      app.kubernetes.io\/name: finapp-app/'

    # The form of a rule: no peers is everyone, no ports is every port, two list items are OR.
    # kind's simulated providers are one rule of its own overlay: the same rule anywhere else names no listed flow.
    plant netpol-simulator-outside-kind
    add_resource netpol-simulator-outside-kind simulator.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: simulator
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-app
  policyTypes: [Egress]
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: finapp-rehearsal
          podSelector:
            matchLabels:
              finapp/role: provider-simulator
      ports:
        - protocol: TCP
          port: 8443
YAML
    plant netpol-open-egress
    add_resource netpol-open-egress wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-app
  policyTypes: [Egress]
  egress:
    - ports:
        - protocol: TCP
          port: 443
YAML
    plant netpol-open-ingress
    add_resource netpol-open-ingress wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-app
  policyTypes: [Ingress]
  ingress:
    - ports:
        - protocol: TCP
          port: 8081
YAML
    plant netpol-empty-rule
    add_resource netpol-empty-rule wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-app
  policyTypes: [Egress]
  egress:
    - {}
YAML
    plant netpol-no-ports
    add_resource netpol-no-ports wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-app
  policyTypes: [Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 203.0.113.0/24
YAML
    plant_on netpol-port-range "$APP" 's/^          port: 8080$/          port: 8080\n          endPort: 8090/'
    plant_on netpol-named-port "$APP" 's/^          port: 8081$/          port: management/'
    plant netpol-selector-expression
    add_resource netpol-selector-expression wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchExpressions:
      - key: app.kubernetes.io/name
        operator: Exists
  policyTypes: [Egress]
YAML

    # Who reaches the application: the management port above all.
    plant_on netpol-peers-split "$APP" '1,/^          podSelector:$/s/^          podSelector:$/        - podSelector:/'
    plant_on netpol-null-namespace "$APP" '/kubernetes.io\/metadata.name: finapp-edge/d'
    plant netpol-any-namespace
    add_resource netpol-any-namespace wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-app
  policyTypes: [Ingress]
  ingress:
    - from:
        - namespaceSelector: {}
      ports:
        - protocol: TCP
          port: 8081
YAML
    plant_on netpol-edge-reaches-management "$APP" '1,/^          port: 8080$/s/^          port: 8080$/          port: 8081/'
    plant_on netpol-management-any-pod-there "$APP" '/finapp\/role: metrics-scraper/d'
    plant_on netpol-management-other-namespace "$APP" '1,/finapp-observability/s/finapp-observability/finapp-edge/'
    plant_on netpol-no-edge "$APP" '1,/^          port: 8080$/{/^    - from:$/,/^          port: 8080$/d}'
    plant_on netpol-no-scrape "$APP" '/^    # The scrape/,/^          port: 8081$/d'

    # What the application and the migrator reach.
    plant netpol-unlisted-egress
    add_resource netpol-unlisted-egress wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-app
  policyTypes: [Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 203.0.113.0/24
      ports:
        - protocol: TCP
          port: 25
YAML
    plant netpol-migrator-kafka
    add_resource netpol-migrator-kafka wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-migrator
  policyTypes: [Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 198.51.100.0/24
      ports:
        - protocol: TCP
          port: 9096
YAML
    plant netpol-migrator-providers
    add_resource netpol-migrator-providers wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-migrator
  policyTypes: [Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 203.0.113.0/24
      ports:
        - protocol: TCP
          port: 443
YAML
    plant netpol-every-pod-egress
    add_resource netpol-every-pod-egress wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to:
        - ipBlock:
            cidr: 203.0.113.0/24
      ports:
        - protocol: TCP
          port: 443
YAML
    plant_on netpol-dns-udp-only "$APP" '/^        - protocol: TCP$/{N;/port: 53$/d}'
    plant_on netpol-dns-tcp-only "$APP" '/^        - protocol: UDP$/{N;/port: 53$/d}'
    plant_on netpol-migrator-dns-udp-only "$MIGRATOR" '/^        - protocol: TCP$/{N;/port: 53$/d}'
    plant no-data-tier
    mutate no-data-tier "$PRODUCTION" '/networkpolicy-data-tier.yaml/d'
    plant_on netpol-egress-not-typed "$APP" 's/^    - Egress$/    - Ingress/'
    plant_on netpol-real-address "$TIER" 's#cidr: 192.0.2.0/24#cidr: 10.20.0.0/16#'
    plant_on netpol-world "$TIER" 's#cidr: 203.0.113.0/24#cidr: 0.0.0.0/0#'

    # What a person may hold: a plant for each way of reading a Secret or getting inside a pod.
    human_rule rbac-get-secrets '""' secrets get
    human_rule rbac-list-secrets '""' secrets list
    human_rule rbac-watch-secrets '""' secrets watch
    human_rule rbac-wildcard-verb '""' pods '"*"'
    human_rule rbac-wildcard-resource '""' '"*"' get
    human_rule rbac-wildcard-group '"*"' pods get
    human_rule rbac-wildcard-subresource '""' '"*/exec"' create
    human_rule rbac-pods-exec '""' pods/exec get
    human_rule rbac-pods-attach '""' pods/attach get
    human_rule rbac-pods-portforward '""' pods/portforward get
    human_rule rbac-pods-ephemeral '""' pods/ephemeralcontainers get
    human_rule rbac-pods-proxy '""' pods/proxy get
    human_rule rbac-mint-for-an-account '""' serviceaccounts/token create
    human_rule rbac-impersonate '""' users impersonate
    human_rule rbac-escalate rbac.authorization.k8s.io roles escalate
    human_rule rbac-bind rbac.authorization.k8s.io roles bind
    human_rule rbac-read-role-writes '""' configmaps patch
    human_rule rbac-externalsecrets-write external-secrets.io externalsecrets patch
    break_glass_rule rbac-break-glass-patches-template apps deployments patch
    break_glass_rule rbac-break-glass-creates-pods '""' pods create
    break_glass_rule rbac-break-glass-secrets '""' secrets get
    break_glass_rule rbac-break-glass-exec '""' pods/exec create
    break_glass_rule rbac-break-glass-wildcard '""' pods '"*"'
    break_glass_rule rbac-break-glass-bind rbac.authorization.k8s.io roles bind
    plant no-human-roles
    mutate no-human-roles base/kustomization.yaml '/rbac-humans.yaml/d'
    plant_on rbac-named-user "$RBAC" '1,/kind: Group/s/kind: Group/kind: User/'
    plant_on rbac-system-masters "$RBAC" '1,/    name: finapp-read-only$/s/    name: finapp-read-only$/    name: system:masters/'
    plant rbac-built-in-role
    add_resource rbac-built-in-role binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: too-much
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
  - apiGroup: rbac.authorization.k8s.io
    kind: Group
    name: finapp-read-only
YAML
    plant rbac-cluster-wide
    add_resource rbac-cluster-wide binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: everywhere
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: everywhere
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: everywhere
subjects:
  - apiGroup: rbac.authorization.k8s.io
    kind: Group
    name: finapp-read-only
YAML
    plant rbac-unbound-role
    add_resource rbac-unbound-role role.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: nobody-yet
rules:
  - apiGroups: [""]
    resources: [secrets]
    verbs: [get]
YAML
    plant rbac-aggregated
    add_resource rbac-aggregated role.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: gathered
aggregationRule:
  clusterRoleSelectors:
    - matchLabels:
        finapp/aggregate: "true"
rules: []
YAML
    plant rbac-workload-bound
    add_resource rbac-workload-bound binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: app-reads
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: finapp-read-only
subjects:
  - kind: ServiceAccount
    name: finapp-app
    namespace: finapp
YAML
    plant rbac-migrator-bound
    add_resource rbac-migrator-bound binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: migrator-reads
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: finapp-read-only
subjects:
  - kind: ServiceAccount
    name: finapp-migrator
    namespace: finapp
YAML

    # A controller may write Secrets; that role in a person's hands may not. The first of these must pass, the second must not.
    # Against the kind overlay, the one place the test-only controller account is listed.
    plant controller-writes-secrets kind
    add_resource controller-writes-secrets controller.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret-delivery
rules:
  - apiGroups: [""]
    resources: [secrets]
    verbs: [get, list, watch, create, update, patch, delete]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: secret-delivery
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: secret-delivery
subjects:
  - kind: ServiceAccount
    name: finapp-test-only-controller
    namespace: finapp
YAML
    plant controller-role-held-by-a-person kind
    add_resource controller-role-held-by-a-person controller.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret-delivery
rules:
  - apiGroups: [""]
    resources: [secrets]
    verbs: [get, list, watch, create, update, patch, delete]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: secret-delivery
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: secret-delivery
subjects:
  - kind: ServiceAccount
    name: finapp-test-only-controller
    namespace: finapp
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: secret-delivery-too
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: secret-delivery
subjects:
  - apiGroup: rbac.authorization.k8s.io
    kind: Group
    name: finapp-read-only
YAML

    # A ServiceAccount off the list is refused whatever it is granted: the namespace's default holding cluster-admin, the
    # internet-facing controller's account reading this namespace's Secrets, an account with no namespace stated.
    plant rbac-default-account-cluster-admin
    add_resource rbac-default-account-cluster-admin binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: everything
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
  - kind: ServiceAccount
    name: default
    namespace: finapp
YAML
    plant rbac-edge-account-reads-secrets
    add_resource rbac-edge-account-reads-secrets binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: reads
rules:
  - apiGroups: [""]
    resources: [secrets]
    verbs: [get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: reads
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: reads
subjects:
  - kind: ServiceAccount
    name: ingress-controller
    namespace: finapp-edge
YAML
    plant rbac-account-with-no-namespace
    add_resource rbac-account-with-no-namespace binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: someone
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: finapp-read-only
subjects:
  - kind: ServiceAccount
    name: finapp-test-only-controller
YAML
    plant rbac-test-only-account-in-production
    add_resource rbac-test-only-account-in-production binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: someone
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: finapp-read-only
subjects:
  - kind: ServiceAccount
    name: finapp-test-only-controller
    namespace: finapp
YAML
    # On the list, and still refused a role this stream cannot read: the rule is no longer only about people.
    plant rbac-listed-account-built-in-role kind
    add_resource rbac-listed-account-built-in-role binding.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: built-in
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: edit
subjects:
  - kind: ServiceAccount
    name: finapp-test-only-controller
    namespace: finapp
YAML
    plant rbac-aggregated-into-view
    add_resource rbac-aggregated-into-view role.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: merged-in
  labels:
    rbac.authorization.k8s.io/aggregate-to-view: "true"
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [get]
YAML
    # A second namespace, which an overlay's transformer would rewrite, so through the namespaces root. A same-named Role
    # elsewhere must not borrow a controller's standing, and a person's binding is this namespace's alone.
    plant rbac-same-name-elsewhere kind
    add_resource rbac-same-name-elsewhere controller.yaml <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: shared-name
rules:
  - apiGroups: [""]
    resources: [secrets]
    verbs: [get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: shared-name
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: shared-name
subjects:
  - kind: ServiceAccount
    name: finapp-test-only-controller
    namespace: finapp
YAML
    add_to_namespaces rbac-same-name-elsewhere <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: shared-name
  namespace: finapp-edge
rules:
  - apiGroups: [""]
    resources: [secrets]
    verbs: [get]
YAML
    plant rbac-person-bound-elsewhere
    add_to_namespaces rbac-person-bound-elsewhere <<'YAML'
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: finapp-break-glass
  namespace: kube-system
rules:
  - apiGroups: [""]
    resources: [pods]
    verbs: [delete]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: finapp-break-glass
  namespace: kube-system
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: finapp-break-glass
subjects:
  - apiGroup: rbac.authorization.k8s.io
    kind: Group
    name: finapp-break-glass
YAML

    # The quota: every key the refusal depends on, and its arithmetic read from the manifests.
    plant no-quota
    mutate no-quota base/kustomization.yaml '/resourcequota.yaml/d'
    plant_on quota-no-requests-cpu "$QUOTA" '/^    requests.cpu:/d'
    plant_on quota-no-requests-memory "$QUOTA" '/^    requests.memory:/d'
    plant_on quota-no-limits-cpu "$QUOTA" '/^    limits.cpu:/d'
    plant_on quota-no-limits-memory "$QUOTA" '/^    limits.memory:/d'
    plant_on quota-no-pods "$QUOTA" '/^    pods:/d'
    plant_on quota-scoped "$QUOTA" 's/^  hard:$/  scopes:\n    - NotBestEffort\n  hard:/'
    plant_on quota-pods-short "$QUOTA" 's/pods: "11"/pods: "10"/'
    plant_on quota-requests-cpu-short "$QUOTA" 's/requests.cpu: 5250m/requests.cpu: 5249m/'
    plant_on quota-requests-memory-short "$QUOTA" 's/requests.memory: 20992Mi/requests.memory: 20991Mi/'
    plant_on quota-limits-cpu-short "$QUOTA" 's/limits.cpu: "21"/limits.cpu: "20"/'
    plant_on quota-limits-memory-short "$QUOTA" 's/limits.memory: 20992Mi/limits.memory: 20991Mi/'
    plant_on quota-follows-the-autoscaler "$HPA" 's/maxReplicas: 7/maxReplicas: 8/'
    plant_on quota-follows-the-surge "$DEPLOYMENT" 's/maxSurge: 1/maxSurge: 2/'
    plant_on quota-follows-the-request "$DEPLOYMENT" 's/cpu: 500m/cpu: 600m/'
    plant_on quota-kind-short "$KIND" 's/value: "9"/value: "8"/' kind
    plant_on quota-staging-short overlays/staging/kustomization.yaml 's/value: "8"/value: "7"/' staging
    plant_on quota-pods-widened "$QUOTA" 's/pods: "11"/pods: "1000"/'
    plant_on quota-limits-memory-widened "$QUOTA" 's/limits.memory: 20992Mi/limits.memory: 40000Mi/'

    # The LimitRange: an injected default makes a pod without requests admitted, and a bound below a pod refuses the pod.
    plant no-limitrange
    mutate no-limitrange base/kustomization.yaml '/limitrange.yaml/d'
    plant_on limitrange-default "$LIMITS" 's/^      max:$/      default:\n        cpu: "1"\n      max:/'
    plant_on limitrange-default-request "$LIMITS" 's/^      max:$/      defaultRequest:\n        cpu: 100m\n      max:/'
    plant_on limitrange-container-item "$LIMITS" 's/type: Pod/type: Container/'
    plant_on limitrange-max-below-a-pod "$LIMITS" 's/memory: 2Gi/memory: 1Gi/'
    plant_on limitrange-min-above-a-pod "$LIMITS" 's/cpu: 100m/cpu: 300m/'
    plant_on limitrange-max-widened "$LIMITS" 's/memory: 2Gi/memory: 64Gi/'
    plant_on limitrange-no-max "$LIMITS" '/^      max:$/,$d'
    plant_on limitrange-null-max "$LIMITS" '/^        cpu: "2"$/d;/^        memory: 2Gi$/d'
    plant_on limitrange-no-memory-bound "$LIMITS" '/^        memory: 128Mi$/d'

    # Every kind branch of the flows reads the test-only copies by name, so a third workload's egress to the database is refused.
    plant netpol-kafka-reaches-the-database kind
    add_resource netpol-kafka-reaches-the-database wide.yaml <<'YAML'
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: wide
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: finapp-kafka
  policyTypes: [Egress]
  egress:
    - to:
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: finapp-postgres
      ports:
        - protocol: TCP
          port: 5432
YAML

    # A token can be mounted without the automount switch.
    plant_on minted-volume "$DEPLOYMENT" "s/^      volumes:\$/      volumes:\n        - name: minted\n          projected:\n            sources:\n              - $MINTED_SOURCE:\n                  path: credential/"
}

# add_to_namespaces <plant>: stdin becomes more documents of the namespaces root, where no transformer rewrites a namespace.
add_to_namespaces() {
    { printf -- '---\n'; cat; } >> "$PLANTS/$1/deploy/namespaces/namespaces.yaml"
}

# human_rule <plant> <apiGroup> <resource> <verb>: one more rule on the standing read-only role.
human_rule() {
    plant_on "$1" "$RBAC" "1,/^rules:\$/s#^rules:\$#rules:\n  - apiGroups: [$2]\n    resources: [$3]\n    verbs: [$4]#"
}

# break_glass_rule <plant> <apiGroup> <resource> <verb>: the same, on the break-glass role.
break_glass_rule() {
    plant_on "$1" "$RBAC" "/^  name: finapp-break-glass\$/,/^rules:\$/s#^rules:\$#rules:\n  - apiGroups: [$2]\n    resources: [$3]\n    verbs: [$4]#"
}

in_container() {
    step=$1
    shift
    case $step in
        render)
            mkdir -p /scratch/rendered
            for overlay in $OVERLAYS; do
                kustomize build "/repo/deploy/overlays/$overlay" > "/scratch/rendered/$overlay.yaml"
                append_namespaces /repo/deploy "/scratch/rendered/$overlay.yaml"
                printf 'overlay: %s\n' "$overlay" > "/scratch/rendered/$overlay.data.yaml"
                release_data /repo/deploy "$overlay" >> "/scratch/rendered/$overlay.data.yaml"
            done
            # The delivery (P15-TSK-017): each environment's project and Applications, and, read as committed, the roots and the
            # controller's configuration, which no Kustomize root holds. Schema-checked like everything else that reaches a cluster.
            for environment in staging production; do
                kustomize build "/repo/deploy/delivery/$environment" > "/scratch/rendered/delivery-$environment.yaml"
            done
            : > /scratch/rendered/delivery-committed.yaml
            for file in /repo/deploy/delivery/roots/*.yaml /repo/deploy/delivery/controller/*.yaml; do
                { printf -- '---\n'; cat "$file"; } >> /scratch/rendered/delivery-committed.yaml
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
                [ -f "$directory/render.failed" ] || append_namespaces "$directory/deploy" "$directory/rendered.yaml"
                release_data "$directory/deploy" "$overlay" >> "$directory/overlay.yaml"
                if [ -f "$directory/after-render.sed" ]; then
                    cp "$directory/rendered.yaml" "$directory/rendered.before"
                    sed -i -f "$directory/after-render.sed" "$directory/rendered.yaml"
                    if cmp -s "$directory/rendered.before" "$directory/rendered.yaml"; then
                        echo "!!   plant $name was not planted: its rendered stream is unchanged" >&2
                        exit 1
                    fi
                fi
                # The graded scan's plants are read as the tree is: every root of the planted copy.
                case $name in trivy-*) render_roots "$directory/deploy" "$directory/roots" ;; esac
            done
            ;;
        trivy-roots)
            # Every kustomize root, and what the delivery applies with no root (its roots and the controller's configuration), as rendered.
            render_roots /repo/deploy /scratch/trivy
            cp /scratch/rendered/delivery-committed.yaml /scratch/trivy/delivery-committed.yaml
            ls /scratch/trivy > /scratch/trivy-roots.txt
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
TRIVY_IMAGE="${TRIVY_IMAGE:-${TRIVY_REPOSITORY}@${TRIVY_DIGEST}}"

SCHEMAS="https://raw.githubusercontent.com/yannh/kubernetes-json-schema/${KUBERNETES_SCHEMAS_COMMIT}/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json"
# ExternalSecret and SecretStore are custom kinds, read from their own catalogue at one commit, and so are the delivery's Argo CD
# Application and AppProject (P15-TSK-017), from the same commit. A kind with no schema stays an error here: an object no schema reads
# is an object nothing checks.
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

# The graded scan. One line per stream Trivy read and the type it read it as, then one per HIGH or CRITICAL finding.
TRIVY_TEMPLATE='{{- range . }}read {{ .Target }} {{ .Type }}
{{ $target := .Target }}{{ range .Misconfigurations }}{{ if eq .Status "FAIL" }}{{ .Severity }} {{ .ID }} {{ $target }}: {{ .Message }}
{{ end }}{{ end }}{{ end }}'

# trivy_config <directory in this run's volume> <flag...>: the embedded checks only, HIGH and CRITICAL, and no network at all, so nothing
# is fetched or sent whatever a later release's defaults become.
trivy_config() {
    target=$1
    shift
    MSYS_NO_PATHCONV=1 docker run --rm --network none -v "$VOLUME:/scratch:ro" -v "$DIR/trivy:/accepted:ro" "$TRIVY_IMAGE" \
        config --skip-check-update --disable-telemetry --skip-version-check --quiet --severity HIGH,CRITICAL \
        --format template --template "$TRIVY_TEMPLATE" "$@" "$target"
}

# Every finding as read, the carried ones included, so a carry is never invisible; and every stream read as Kubernetes.
trivy_reads_every_root() {
    output=$(trivy_config /scratch/trivy --exit-code 0) || { printf '%s\n' "$output"; return 1; }
    printf '%s\n' "$output"
    roots=$(MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh -v "$VOLUME:/scratch:ro" "$KUSTOMIZE_IMAGE" -c 'cat /scratch/trivy-roots.txt') || return 1
    unread=0
    for root in $roots; do
        printf '%s\n' "$output" | grep -qxF -- "read $root kubernetes" || { echo "!!   Trivy did not read $root as Kubernetes"; unread=1; }
    done
    echo "     $(printf '%s\n' "$roots" | wc -l | tr -d ' ') streams read as Kubernetes"
    return $unread
}

# The verdict: a finding fails unless infra/trivy/accepted-misconfigurations.rego carries that one finding, unexpired.
trivy_clean() {
    trivy_config "$1" --exit-code 1 --ignore-policy /accepted/accepted-misconfigurations.rego
}

# trivy_refused <plant> <check id>: every root of the planted tree, through the verdict, must fail and name the check.
trivy_refused() {
    refuses "$1" "$2" trivy_clean "/scratch/plants/$1/roots"
}

check "every overlay renders" in_kustomize render
check "the plants are planted and rendered" in_kustomize plants
check "the kind rehearsal's generated Secrets" in_kustomize kind-secrets

for overlay in $OVERLAYS; do
    check "kubeconform $overlay" kubeconform_on "/scratch/rendered/$overlay.yaml"
    check "the policies on $overlay" conftest_on "/scratch/rendered/$overlay.yaml" "/scratch/rendered/$overlay.data.yaml"
done
for delivery in delivery-staging delivery-production delivery-committed; do
    check "kubeconform $delivery" kubeconform_on "/scratch/rendered/$delivery.yaml"
done
check "every kustomize root rendered for the graded scan" in_kustomize trivy-roots
check "trivy config reads every root as Kubernetes, and every HIGH or CRITICAL finding as read, carried ones included" trivy_reads_every_root
check "trivy config: no HIGH or CRITICAL finding that is not carried" trivy_clean /scratch/trivy

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
refused release-elsewhere release-images
refused release-patched release-images
refused release-record-differs release-images
refused release-staging-record-differs release-images
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
refused inline-config-tokenisation no-secret-literal
refused inline-config-named-timeout no-secret-literal
refused inline-config-timeout-not-a-duration no-secret-literal
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

# accepted <plant>: a case the policies must let through, so a rule cannot pass its plants by refusing everything.
accepted() {
    if output=$(conftest_on "/scratch/plants/$1/rendered.yaml" "/scratch/plants/$1/overlay.yaml" 2>&1); then
        echo "ok   plant $1 accepted"
    else
        printf '%s\n' "$output"
        echo "!!   plant $1 was refused, and must not be"
        status=1
    fi
}

refused namespace-edge-absent namespace-present
refused namespace-dropped namespace-workload
refused psa-enforce-baseline psa-restricted
refused psa-enforce-absent psa-restricted
refused psa-audit-absent psa-restricted
refused psa-warn-absent psa-restricted
refused psa-version-latest psa-version
refused psa-version-absent psa-version

refused no-default-deny netpol-default-deny
refused default-deny-ingress-only netpol-default-deny
refused default-deny-opened netpol-default-deny
refused default-deny-narrowed netpol-default-deny
refused netpol-simulator-outside-kind netpol-egress-closed
refused netpol-open-egress netpol-open-peer
refused netpol-open-ingress netpol-open-peer
refused netpol-empty-rule netpol-open-peer
refused netpol-no-ports netpol-ports
refused netpol-port-range netpol-ports
refused netpol-named-port netpol-ports
refused netpol-selector-expression netpol-selector
refused netpol-peers-split netpol-ingress-closed
refused netpol-null-namespace netpol-ingress-closed
refused netpol-any-namespace netpol-ingress-closed
refused netpol-edge-reaches-management netpol-ingress-closed
refused netpol-management-any-pod-there netpol-ingress-closed
refused netpol-management-other-namespace netpol-ingress-closed
refused netpol-no-edge netpol-flow-missing
refused netpol-no-scrape netpol-flow-missing
refused netpol-unlisted-egress netpol-egress-closed
refused netpol-migrator-kafka netpol-egress-closed
refused netpol-migrator-providers netpol-egress-closed
refused netpol-every-pod-egress netpol-egress-closed
refused netpol-dns-udp-only netpol-dns
refused netpol-dns-tcp-only netpol-dns
refused netpol-migrator-dns-udp-only netpol-dns
refused no-data-tier netpol-flow-missing
refused netpol-egress-not-typed netpol-flow-missing
refused netpol-real-address netpol-placeholder-cidr
refused netpol-world netpol-placeholder-cidr

refused rbac-get-secrets rbac-human-secrets
refused rbac-list-secrets rbac-human-secrets
refused rbac-watch-secrets rbac-human-secrets
refused rbac-wildcard-verb rbac-human-wildcard
refused rbac-wildcard-resource rbac-human-wildcard
refused rbac-wildcard-group rbac-human-wildcard
refused rbac-wildcard-subresource rbac-human-wildcard
refused rbac-pods-exec rbac-human-subresource
refused rbac-pods-attach rbac-human-subresource
refused rbac-pods-portforward rbac-human-subresource
refused rbac-pods-ephemeral rbac-human-subresource
refused rbac-pods-proxy rbac-human-subresource
refused rbac-mint-for-an-account rbac-human-subresource
refused rbac-impersonate rbac-human-privilege
refused rbac-escalate rbac-human-privilege
refused rbac-bind rbac-human-privilege
refused rbac-read-role-writes rbac-human-verbs
refused rbac-externalsecrets-write rbac-human-verbs
refused rbac-break-glass-patches-template rbac-break-glass-writes
refused rbac-break-glass-creates-pods rbac-break-glass-writes
refused rbac-break-glass-secrets rbac-human-secrets
refused rbac-break-glass-exec rbac-human-subresource
refused rbac-break-glass-wildcard rbac-human-wildcard
refused rbac-break-glass-bind rbac-human-privilege
refused no-human-roles rbac-present
refused rbac-named-user rbac-human-subject
refused rbac-system-masters rbac-human-subject
refused rbac-built-in-role rbac-binding-rendered
refused rbac-cluster-wide rbac-human-cluster-wide
refused rbac-unbound-role rbac-human-secrets
refused rbac-aggregated rbac-human-aggregated
refused rbac-workload-bound rbac-workload-none
refused rbac-migrator-bound rbac-workload-none
accepted controller-writes-secrets
refused controller-role-held-by-a-person rbac-human-secrets
refused rbac-default-account-cluster-admin rbac-account-unlisted
refused rbac-edge-account-reads-secrets rbac-account-unlisted
refused rbac-account-with-no-namespace rbac-account-unlisted
refused rbac-test-only-account-in-production rbac-account-unlisted
refused rbac-listed-account-built-in-role rbac-binding-rendered
refused rbac-aggregated-into-view rbac-aggregate-label
refused rbac-same-name-elsewhere rbac-human-secrets
refused rbac-person-bound-elsewhere rbac-human-namespace

refused no-quota quota-present
refused quota-no-requests-cpu quota-requires
refused quota-no-requests-memory quota-requires
refused quota-no-limits-cpu quota-requires
refused quota-no-limits-memory quota-requires
refused quota-no-pods quota-requires
refused quota-scoped quota-scoped
refused quota-pods-short quota-pods
refused quota-requests-cpu-short quota-compute
refused quota-requests-memory-short quota-compute
refused quota-limits-cpu-short quota-compute
refused quota-limits-memory-short quota-compute
refused quota-follows-the-autoscaler quota-pods
refused quota-follows-the-surge quota-pods
refused quota-follows-the-request quota-compute
refused quota-kind-short quota-pods
refused quota-staging-short quota-pods
refused quota-pods-widened quota-pods
refused quota-limits-memory-widened quota-compute

refused no-limitrange limitrange-present
refused limitrange-default limitrange-injects
refused limitrange-default-request limitrange-injects
refused limitrange-container-item limitrange-injects
refused limitrange-max-below-a-pod limitrange-admits
refused limitrange-min-above-a-pod limitrange-admits
refused limitrange-max-widened limitrange-admits
refused limitrange-no-max limitrange-present
refused limitrange-null-max limitrange-present
refused limitrange-no-memory-bound limitrange-present

refused netpol-kafka-reaches-the-database netpol-egress-closed
refused minted-volume security-token

schema_refused schema-typo "revisionHistoryLimits"
schema_refused schema-type "revisionHistoryLimit"
render_refused render-broken "base-that-is-not-there"
schema_refused delivery-schema-typo "/spec/syncPolicy"

trivy_refused trivy-privileged KSV-0017
trivy_refused trivy-host-root KSV-0121
trivy_refused trivy-carry-widened KSV-0109

if [ "$status" -eq 0 ]; then
    echo "ok   every check green, every plant refused for its reason"
fi
exit $status
