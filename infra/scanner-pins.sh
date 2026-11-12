# The pinned scanner and tool images. ONE definition each (P0-TSK-040).
#
# Named .sh rather than .env, and not by accident: .gitignore ignores `*.env` under its Secrets
# section, so the first version of this file was silently never committed - CI would have sourced
# a file that does not exist. Renaming is the honest fix; adding an exception to a
# security-motivated ignore rule for a file that holds no secret is not.
#
# Each pin is three facts, and all three are needed:
#
#   REPOSITORY  what to pull
#   VERSION     the release the digest is claimed to be - a comment cannot be checked, so it is
#               data. infra/scripts/check-pinned-images.sh resolves it and fails if the digest
#               no longer matches, which is how a repointed tag becomes visible
#   DIGEST      what is actually pulled. A tag can be moved by whoever controls the source
#               repository; a digest cannot
#
# Sourced by infra/scripts/secret-scan.sh, infra/scripts/dependency-scan.sh, (since P15-TSK-013)
# infra/scripts/terraform-check.sh, (since P15-TSK-025) infra/scripts/alert-rules-check.sh, (since P15-TSK-006)
# infra/scripts/image-scan.sh and infra/scripts/sast.sh, (since P15-TSK-009) infra/scripts/manifest-check.sh and (since P15-TSK-007)
# infra/scripts/admission-check.sh, which CI calls,
# so a developer and CI run the identical image - the single-definition property P0-TSK-031 established and this file preserves
# rather than replaces.
#
# UPDATING: infra/scripts/check-pinned-images.sh reports both a moved tag and a newer release.
# Bump VERSION, re-resolve DIGEST with `docker buildx imagetools inspect <repo>:<version>`, and
# review the diff. See README.md section 7b.

GITLEAKS_REPOSITORY=ghcr.io/gitleaks/gitleaks
GITLEAKS_VERSION=v8.30.1
GITLEAKS_DIGEST=sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f

TRIVY_REPOSITORY=aquasec/trivy
TRIVY_VERSION=0.76.2
TRIVY_DIGEST=sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb

# The infrastructure-code tools (P15-TSK-013, ADR-0121): sourced by infra/scripts/terraform-check.sh.
# TERRAFORM_VERSION is also every root's required_version (infra/terraform/environments/*/versions.tf),
# which TerraformIsPinnedAndCheckedTest holds equal, so the binary that checks the code is the one the
# code declares. Trivy above also runs `trivy config` over the Terraform.
TERRAFORM_REPOSITORY=hashicorp/terraform
TERRAFORM_VERSION=1.17.1
TERRAFORM_DIGEST=sha256:a5d5a5a892c98f60fcbfa81eac2a40fc19e7b3fa7719bccae10ff6111d22524a

TFLINT_REPOSITORY=ghcr.io/terraform-linters/tflint
TFLINT_VERSION=v0.65.0
TFLINT_DIGEST=sha256:2788f4d4fc663c0e953b011b01bf443fe5bfce7587ee916e588e6057b85d6359

# The Kubernetes manifest tools (P15-TSK-009, ADR-0120): sourced by infra/scripts/manifest-check.sh.
KUSTOMIZE_REPOSITORY=registry.k8s.io/kustomize/kustomize
KUSTOMIZE_VERSION=v5.8.3
KUSTOMIZE_DIGEST=sha256:429eda4a1c76a7de458d0c4ca3ded9b5d764899ff6bdaeccc1d98b1b6ee873e4

KUBECONFORM_REPOSITORY=ghcr.io/yannh/kubeconform
KUBECONFORM_VERSION=v0.8.0
KUBECONFORM_DIGEST=sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e

CONFTEST_REPOSITORY=openpolicyagent/conftest
CONFTEST_VERSION=v0.71.1
CONFTEST_DIGEST=sha256:0e77cdf7c1fcde035f3c579e4aaa6664ee2a8be5777eaa9af9f520cdd02280c0

# The schemas kubeconform reads, at one commit of yannh/kubernetes-json-schema, for one Kubernetes version. Not an
# image, so not a _REPOSITORY/_DIGEST pin: a git commit is its own digest, and the raw URL is built from it. The
# preStop sleep action needs Kubernetes 1.30 or later. Its minor is the clusters': KubernetesManifestsTest holds it
# equal to kubernetes_version in infra/terraform/environments/*/main.tf.
KUBERNETES_VERSION=1.34.12
KUBERNETES_SCHEMAS_COMMIT=c416d7b0da0751bd8ebe5be4fa4c8e8274e3bbe2

# External Secrets Operator (P15-TSK-018, ADR-0123, C6): a controller in the cluster and not a tool, pinned here because this
# is the list infra/scripts/check-pinned-images.sh holds to its tags. deploy/operators/external-secrets/values.yaml names the same
# digest, which SecretInventoryIsCompleteTest holds equal, and the chart's version is this one without its leading v. The schemas
# kubeconform reads for its two custom kinds are read at one commit of datreeio/CRDs-catalog: a git hash being its own digest,
# as KUBERNETES_SCHEMAS_COMMIT above. ESO, because gitleaks reads a 40-character hash under a name saying SECRETS as a leak.
ESO_REPOSITORY=ghcr.io/external-secrets/external-secrets
ESO_VERSION=v2.14.0
ESO_DIGEST=sha256:10608eb53e636e744903d5f3657a4d9c44e28d9d9c181b73e07bc7dd2e6884dc
ESO_CRD_SCHEMAS_COMMIT=49729e49e8f062933e3716a26540d41b0cd0856c

# The alert-rule tools (P15-TSK-025, ADR-0124): promtool and amtool, sourced by infra/scripts/alert-rules-check.sh. The Prometheus
# version is the one compose.yaml runs, and so is Alertmanager's, so the tool that checks the rules is the server that loads them.
PROMETHEUS_REPOSITORY=prom/prometheus
PROMETHEUS_VERSION=v3.14.0
PROMETHEUS_DIGEST=sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0

ALERTMANAGER_REPOSITORY=prom/alertmanager
ALERTMANAGER_VERSION=v0.34.2
ALERTMANAGER_DIGEST=sha256:39d8bb3966c694a124428a3a88fbcffd40bd069129ecd47c059f8a3f17c55f3b

# Static analysis (P15-TSK-006, ADR-0122 decision 2): sourced by infra/scripts/sast.sh. Semgrep OSS, run with
# no network at all, over the rules vendored under infra/semgrep/rules.
SEMGREP_REPOSITORY=semgrep/semgrep
SEMGREP_VERSION=1.184.0
SEMGREP_DIGEST=sha256:cc1b68266f267878b39739373ea9985b9a78e5d64497dd875c8de100b22695ed

# The edge (P15-TSK-011, ADR-0120 decision 6): sourced by infra/scripts/edge-check.sh. Traefik is what the controllers run, its digest named again in
# deploy/edge/base/controllers.yaml, and EdgeManifestsTest holds the two equal. ingress-nginx was retired upstream in March 2026, so it was not an
# option. whoami and curl are the check's stand-in backend and client, and run nowhere else.
TRAEFIK_REPOSITORY=library/traefik
TRAEFIK_VERSION=v3.7.17
TRAEFIK_DIGEST=sha256:f4bb143d1135a579b630ed491e17ec50f6f7c9c6a30585ff3d385471d2a86f5a

WHOAMI_REPOSITORY=traefik/whoami
WHOAMI_VERSION=v1.11.0
WHOAMI_DIGEST=sha256:200689790a0a0ea48ca45992e0450bc26ccab5307375b41c84dfc4f2475937ab

CURL_REPOSITORY=curlimages/curl
CURL_VERSION=8.17.0
CURL_DIGEST=sha256:935d9100e9ba842cdb060de42472c7ca90cfe9a7c96e4dacb55e79e560b3ff40

# The schemas kubeconform reads for cert-manager's kinds (the kind overlay's local CA), at one commit of datreeio/CRDs-catalog.
CRD_SCHEMAS_COMMIT=49729e49e8f062933e3716a26540d41b0cd0856c

# cert-manager, for the kind rehearsal alone: infra/scripts/kind-rehearsal.sh installs this chart. A chart, so check-pinned-images.sh has no release list to compare it to.
CERT_MANAGER_REPOSITORY=quay.io/jetstack/charts/cert-manager
CERT_MANAGER_VERSION=v1.21.3
CERT_MANAGER_DIGEST=sha256:e482675aec32862d323a9218293ff7de81192337998615e4378c56498473f240

# The kind rehearsal (P15-TSK-012): sourced by infra/scripts/kind-rehearsal.sh. KIND_NODE_VERSION's minor is KUBERNETES_VERSION's, which
# KindRehearsalContractTest holds equal, so the rehearsal runs the API the clusters and the schemas declare. KIND_VERSION is the kind
# release CI installs, which needs to be at least v0.24.0: kindnetd enforces NetworkPolicy from there. Helm renders cert-manager's chart.
KIND_VERSION=v0.33.0
KIND_NODE_REPOSITORY=kindest/node
KIND_NODE_VERSION=v1.34.11
KIND_NODE_DIGEST=sha256:44e222ee2132dab25ff87301682f89eb82c7880ea3a1bf543bfe9708fd08d67d

HELM_REPOSITORY=alpine/helm
HELM_VERSION=3.19.0
HELM_DIGEST=sha256:aef9b56f64e866207d9591d0abd8f6d767b36aadd12edf68f8a719716d9d29c9

# Signing and admission (P15-TSK-007, ADR-0122 decision 3, C7): sourced by infra/scripts/admission-check.sh. cosign signs, attests and
# verifies; the policy controller is the admission webhook, installed from its chart (an OCI artefact, pinned like cert-manager's, and its
# default image, v0.13.1, overridden by this pin: deploy/operators/policy-controller/values.yaml names the same digest, which
# ImageAdmissionTest holds equal); the registry is the local one the check pushes to and signs in, since no real registry exists.
COSIGN_REPOSITORY=ghcr.io/sigstore/cosign/cosign
COSIGN_VERSION=v3.1.4
COSIGN_DIGEST=sha256:0f06154836d2de34f887a5c273506d52ca43edaa1d9e53e6510fa78a48f69c2d

POLICY_CONTROLLER_REPOSITORY=ghcr.io/sigstore/policy-controller/policy-controller
POLICY_CONTROLLER_VERSION=v0.15.1
POLICY_CONTROLLER_DIGEST=sha256:0492bb264fb1d9bdc8e3f343ef542cc85b7dd7c7fd8d9524b453c2bd31a1d128

POLICY_CONTROLLER_CHART_REPOSITORY=ghcr.io/sigstore/helm-charts/policy-controller
POLICY_CONTROLLER_CHART_VERSION=0.10.8
POLICY_CONTROLLER_CHART_DIGEST=sha256:9ff3ca6ae4a0155de4dd667f8b760e56e1bd5c8233a0321ad870a654ce022f2f

REGISTRY_REPOSITORY=library/registry
REGISTRY_VERSION=3.1.2
REGISTRY_DIGEST=sha256:38907510915c3f1604c7e83d6ab602ab3020d092f73bdaed8696773693ff768c

# Only where kindnetd cannot enforce NetworkPolicy, because the kernel has no nft_queue (Docker Desktop's WSL2 kernel): REHEARSAL_POLICY_ENGINE=kube-router.
# The rehearsal says in its record which engine enforced the policies it tested, and CI never sets it.
KUBE_ROUTER_REPOSITORY=cloudnativelabs/kube-router
KUBE_ROUTER_VERSION=v2.12.0
KUBE_ROUTER_DIGEST=sha256:e1da38f71e2f1521e7a74c5cdaa511fdee9d2ee76972b25c9b5785b030b96fe4

# The telemetry pipeline (P15-TSK-023, ADR-0124 decision 1): sourced by infra/scripts/telemetry-check.sh and the kind rehearsal. The collector is the
# contrib distribution, which holds the transform, tail-sampling and load-balancing components the configuration names; its digest is named
# again in deploy/observability/base/collector-workload.yaml, Tempo's and Loki's in kind's overlay, and TelemetryPipelineManifestsTest holds each
# pair equal. Prometheus on kind is PROMETHEUS_* above, the version compose runs.
COLLECTOR_REPOSITORY=otel/opentelemetry-collector-contrib
COLLECTOR_VERSION=0.165.0
COLLECTOR_DIGEST=sha256:e40d61f10c9a617cca1ffd1be4d7e2b3a4b0f0001798ff07e58cabe82e618cea

TEMPO_REPOSITORY=grafana/tempo
TEMPO_VERSION=2.10.10
TEMPO_DIGEST=sha256:12b0820f13ffa10ce956b6e9acc3b03f3cffded99109be6cc09d106fe56e5d38

LOKI_REPOSITORY=grafana/loki
LOKI_VERSION=3.7.9
LOKI_DIGEST=sha256:0db216b630ae1f348a1584c67f3cead8b4c1fc839169a39501db664744158e28

# Argo CD and the External Secrets chart, rendered under a rule (P15-TSK-032; OPERATIONS_RUNBOOK.md section 11.36): sourced by
# infra/scripts/controller-check.sh, which renders both and holds them to infra/scripts/manifest-policy/controllers.rego, and by the kind
# rehearsal's delivery scope, which installs exactly what that check rendered. Argo CD is installed from its release's own install manifest,
# a raw file at the release's tag, held by the file's sha256 (a moved tag changes the bytes, and the check refuses them); every image it
# names is rewritten to these digests, and the policy refuses any other. Dex and Redis are the images that manifest names beside its own,
# at the versions it names: a new Argo CD release moves all three and the sha256 together.
ARGOCD_REPOSITORY=quay.io/argoproj/argocd
ARGOCD_VERSION=v3.6.1
ARGOCD_DIGEST=sha256:296e3d74f449b2ce45265613fcb68c18ed7366488360c8ea32f5f2a93d2e5c54
ARGOCD_INSTALL_SHA256=7be23ca7d9ca3d3074dee8332f1ca92a933b990cee7c9cf22cf3843ee8c2913e

ARGOCD_DEX_REPOSITORY=ghcr.io/dexidp/dex
ARGOCD_DEX_VERSION=v2.46.0
ARGOCD_DEX_DIGEST=sha256:933fcd3f523338c847b88ef84a7145fb2fcb7b9917f08418612afeaaa2001777

ARGOCD_REDIS_REPOSITORY=public.ecr.aws/docker/library/redis
ARGOCD_REDIS_VERSION=8.10.1-alpine
ARGOCD_REDIS_DIGEST=sha256:ef216d85ec44f4fdf7c0e6df2d1757a4734ed32fbb278cabd3181b5a27353b79

# The External Secrets chart's archive at ESO_VERSION without its leading v: a classic Helm repository prints no digest, so the archive's
# own sha256 is the pin. The same value kind-rehearsal/rotations.sh pulls by, which ControllerRenderContractTest holds equal.
ESO_CHART_SHA256=0a6d9e91b1ff50adfdbf24b50b582babfa7c5b7df42b21974ca4b3fb88ee5e34

# metrics-server, for the kind delivery rehearsal alone (P15-TSK-032): kind has no metrics source, so the release's HorizontalPodAutoscaler
# is Degraded under Argo CD's own check and the root never applies the edge's wave. Installed from its release's manifest, held by the
# file's sha256, its image by this digest. A real environment brings its own metrics source (OPERATIONS_RUNBOOK.md section 11.36).
METRICS_SERVER_REPOSITORY=registry.k8s.io/metrics-server/metrics-server
METRICS_SERVER_VERSION=v0.9.1
METRICS_SERVER_DIGEST=sha256:9b0ba1926e1d3089673f45461c3c1416109dea33b3ba3434048defd380df329b
METRICS_SERVER_MANIFEST_SHA256=3c7f57cdbee0f57fb0259a150e8f6d6cb4f29d72d1aa5dce8803151dd6d4c2a1

# The component rolls of the kind rehearsal (P16-TSK-005, REHEARSAL_SCOPE=rolls; THREAT_MODEL.md section 6 item 43 (f)): for each component the
# platform runs beside its own release, the other image of a pair, which the step rolls to under the workload and back from to the deployed pin.
# The deployed pins are TRAEFIK_* above (deploy/edge/base/controllers.yaml) and the kind overlay's postgres.yaml and kafka.yaml (compose.yaml's
# versions). Each target is the same repository and minor as its deployed pin and another patch, which KindRehearsalContractTest holds, so a
# bump of a deployed pin onto its target turns the test red until a new target is chosen. Deployed nowhere but a rehearsal's own cluster and
# pulled by digest; check-pinned-images.sh checks that each tag has not moved, and asks no newer-release question of a target.
# PostgreSQL's deployed 18.6 is the newest patch published on 2026-11-12 (18.5 never was), so its target is the patch before it, 18.4, and its
# step rolls back first: the same data directory under two releases of one major, in both directions.
TRAEFIK_ROLL_REPOSITORY=library/traefik
TRAEFIK_ROLL_VERSION=v3.7.18
TRAEFIK_ROLL_DIGEST=sha256:9c437ac4f09ce6f4980a8544902d3475fdf70a1a7c578729581d2535dfa7d635

POSTGRES_ROLL_REPOSITORY=library/postgres
POSTGRES_ROLL_VERSION=18.4
POSTGRES_ROLL_DIGEST=sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636

KAFKA_ROLL_REPOSITORY=apache/kafka
KAFKA_ROLL_VERSION=4.3.2
KAFKA_ROLL_DIGEST=sha256:852a0d49fc4e656b022e7f23785411802c9a088c78a7c5e39f33969de9c227a7
