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
# infra/scripts/image-scan.sh and infra/scripts/sast.sh and (since P15-TSK-009) infra/scripts/manifest-check.sh, which CI calls,
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
