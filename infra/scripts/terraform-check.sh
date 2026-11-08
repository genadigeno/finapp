#!/bin/sh
# Check the infrastructure code (P15-TSK-013, ADR-0121 decision 5).
#
# Five checks over every root and module under infra/terraform: `terraform fmt -check`, `tflint`,
# `trivy config` (HIGH and CRITICAL fail, the threshold of PHASE_GATES.md section 5 Phase 15 criterion
# 2 and of dependency-scan.sh), then per directory `terraform init` (no backend, the lock file
# read-only), `terraform validate` and `terraform test`, whose tests plan against mock providers.
#
# THEN EACH CHECK AGAIN, OVER A PLANT IT MUST REFUSE. A check that is wired but cannot fail reads as
# assurance it does not give. So every run copies the tree, plants one defect per copy, runs the same
# check over the copy as over the tree, and requires it to fail AND to name the defect's reason in its
# output. A plant accepted, refused for another reason, or no longer planted (its source changed under
# the mutation) fails this script:
#
#   unformatted        a badly formatted block             terraform fmt -check
#   unpinned-provider  the AWS provider's version dropped  tflint terraform_required_providers
#   camel-case         a module called namingConventions   tflint terraform_naming_convention
#   public-bucket      a bucket with public access open    trivy config (block public ACLs)
#   invalid            an output naming an undeclared      terraform validate
#                      local value
#   untagged           the ManagedBy tag dropped from the  terraform test (the conventions' tags)
#                      conventions module
#
# And one plant per named property of the network and cluster modules, each refused by the message
# of the terraform test assertion that holds it:
#
#   public-endpoint         the cluster's API endpoint made public      the kubernetes module's test
#   public-workload-subnet  a workload subnet given public addresses    the network module's test
#   cluster-on-edge-tier    staging's cluster given the edge subnets    staging's test
#   no-flow-logs            the flow log narrowed to rejected traffic   the network module's test
#   no-audit-log            the control plane's audit log turned off    the kubernetes module's test
#   no-etcd-encryption      the cluster's encryption block removed      the kubernetes module's test
#   two-zones               the three-zone rule relaxed to two          the network module's test
#
# WHAT IT NEVER DOES. It initialises no backend (every init is -backend=false) and holds no credential:
# no container is given a variable or a file from the host beyond the infra directory, mounted
# read-only, and a scratch volume of its own, removed at exit. terraform test keeps its state in
# memory, and every test file mocks every provider its root requires (TerraformIsPinnedAndCheckedTest).
# Nothing here can change the tree, the lock files or any cloud account.
#
# SAME BYTES HERE AND IN CI. Every tool is an image pinned by digest in infra/scanner-pins.sh; tflint
# uses only its bundled ruleset and trivy only the checks embedded in the pinned binary
# (--skip-check-update), so nothing is fetched at run time except the providers, which init verifies
# against the committed .terraform.lock.hcl. TERRAFORM_IMAGE, TFLINT_IMAGE and TRIVY_IMAGE override an
# image, as dependency-scan.sh allows, for a machine whose TLS is intercepted (OPERATIONS_RUNBOOK.md
# section 11.13); CI never sets them.
#
# Usage:
#   infra/scripts/terraform-check.sh
#
# Exit status: 0 every check green and every plant refused for its reason; 1 otherwise; 2 no Docker.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

# ---------------------------------------------------------------------------------------------------
# Inside the Terraform container: /repo/infra is the repository's infra directory, read-only;
# /scratch is this run's volume. Called by the host half below, never by hand.
# ---------------------------------------------------------------------------------------------------
TREE=/repo/infra/terraform

plant() {
    rm -rf "/scratch/plants/$1"
    mkdir -p /scratch/plants
    cp -R "$TREE" "/scratch/plants/$1"
}

planted() {
    if cmp -s "$TREE/$2" "/scratch/plants/$1/$2"; then
        echo "!!   plant $1 was not planted: $2 is unchanged, so its mutation no longer applies" >&2
        exit 1
    fi
}

plant_all() {
    plant unformatted
    printf 'locals {\n    plant   =     "unformatted"\n}\n' >> /scratch/plants/unformatted/environments/staging/main.tf
    planted unformatted environments/staging/main.tf

    plant unpinned-provider
    sed -i '/^ *version *= /d' /scratch/plants/unpinned-provider/environments/staging/versions.tf
    planted unpinned-provider environments/staging/versions.tf

    plant camel-case
    sed -i 's/^module "conventions"/module "namingConventions"/' /scratch/plants/camel-case/environments/staging/main.tf
    sed -i 's/module\.conventions\./module.namingConventions./g' \
        /scratch/plants/camel-case/environments/staging/main.tf \
        /scratch/plants/camel-case/environments/staging/providers.tf \
        /scratch/plants/camel-case/environments/staging/outputs.tf
    planted camel-case environments/staging/main.tf

    plant public-bucket
    cat > /scratch/plants/public-bucket/environments/staging/plant.tf <<'HCL'
resource "aws_s3_bucket" "plant" {
  bucket = "finapp-staging-plant"
}

resource "aws_s3_bucket_public_access_block" "plant" {
  bucket                  = aws_s3_bucket.plant.id
  block_public_acls       = false
  block_public_policy     = false
  ignore_public_acls      = false
  restrict_public_buckets = false
}
HCL
    planted public-bucket environments/staging/plant.tf

    plant invalid
    printf '\noutput "plant" {\n  description = "A reference to nothing."\n  value       = local.undeclared\n}\n' \
        >> /scratch/plants/invalid/environments/staging/outputs.tf
    planted invalid environments/staging/outputs.tf

    plant untagged
    sed -i '/ManagedBy/d' /scratch/plants/untagged/modules/conventions/main.tf
    planted untagged modules/conventions/main.tf

    plant public-endpoint
    sed -i 's/endpoint_public_access  = false/endpoint_public_access  = true/' \
        /scratch/plants/public-endpoint/modules/kubernetes/main.tf
    planted public-endpoint modules/kubernetes/main.tf

    plant public-workload-subnet
    sed -i '/^resource "aws_subnet" "private"/,/^}/ s/map_public_ip_on_launch = false/map_public_ip_on_launch = true/' \
        /scratch/plants/public-workload-subnet/modules/network/main.tf
    planted public-workload-subnet modules/network/main.tf

    plant cluster-on-edge-tier
    sed -i 's/module\.network\.private_subnet_ids_by_zone/module.network.public_subnet_ids_by_zone/' \
        /scratch/plants/cluster-on-edge-tier/environments/staging/main.tf
    planted cluster-on-edge-tier environments/staging/main.tf

    plant no-flow-logs
    sed -i 's/traffic_type             = "ALL"/traffic_type             = "REJECT"/' \
        /scratch/plants/no-flow-logs/modules/network/main.tf
    planted no-flow-logs modules/network/main.tf

    plant no-audit-log
    sed -i 's/\["api", "audit", /["api", /' /scratch/plants/no-audit-log/modules/kubernetes/main.tf
    planted no-audit-log modules/kubernetes/main.tf

    plant no-etcd-encryption
    sed -i '/^  encryption_config {/,/^  }/d' /scratch/plants/no-etcd-encryption/modules/kubernetes/main.tf
    planted no-etcd-encryption modules/kubernetes/main.tf

    plant two-zones
    sed -i 's/length(distinct(var\.availability_zones)) >= 3/length(distinct(var.availability_zones)) >= 2/' \
        /scratch/plants/two-zones/modules/network/variables.tf
    planted two-zones modules/network/variables.tf
}

# Every directory holding a .tf file, found rather than listed, so a module added later is checked
# without anyone remembering to add it here.
directories() {
    (cd "$1" && find . -name '*.tf' -exec dirname {} \; | sed 's|^\./||' | sort -u)
}

# init, validate and test one directory. TF_DATA_DIR keeps .terraform off the read-only tree.
terraform_directory() {
    data="/scratch/data/$(printf '%s' "$1/$2" | tr '/' '_')"
    echo "--   $1/$2"
    TF_DATA_DIR=$data terraform -chdir="$1/$2" init -backend=false -lockfile=readonly -input=false -no-color >/dev/null
    TF_DATA_DIR=$data terraform -chdir="$1/$2" validate -no-color
    TF_DATA_DIR=$data terraform -chdir="$1/$2" test -no-color
}

in_container() {
    export TF_IN_AUTOMATION=1 CHECKPOINT_DISABLE=1 TF_PLUGIN_CACHE_DIR=/scratch/plugins
    mkdir -p /scratch/plugins
    step=$1
    shift
    case $step in
        plants)
            plant_all
            ;;
        fmt)
            terraform fmt -check -recursive -no-color "$1"
            ;;
        terraform)
            tree=$1
            shift
            if [ $# -eq 0 ]; then
                set -- $(directories "$tree")
            fi
            [ $# -gt 0 ] || { echo "!!   no Terraform directory under $tree" >&2; exit 1; }
            for directory in "$@"; do
                terraform_directory "$tree" "$directory"
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

TERRAFORM_IMAGE="${TERRAFORM_IMAGE:-${TERRAFORM_REPOSITORY}@${TERRAFORM_DIGEST}}"
TFLINT_IMAGE="${TFLINT_IMAGE:-${TFLINT_REPOSITORY}@${TFLINT_DIGEST}}"
TRIVY_IMAGE="${TRIVY_IMAGE:-${TRIVY_REPOSITORY}@${TRIVY_DIGEST}}"

if ! docker info >/dev/null 2>&1; then
    echo "Docker is not available: every check runs in a pinned image." >&2
    exit 2
fi

# One volume per run, so two runs on one Docker host never share a plant, a data directory or a
# provider cache (Terraform's plugin cache is not safe for concurrent writers). The process id alone
# can repeat across containers sharing a host's Docker, so a random suffix goes with it.
VOLUME="finapp-terraform-check-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
docker volume create "$VOLUME" >/dev/null
trap 'docker volume rm -f "$VOLUME" >/dev/null 2>&1 || true' EXIT
trap 'exit 130' INT TERM

# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /repo and /scratch into host paths. Unset and
# harmless on Linux, where CI runs.
in_terraform() {
    MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh \
        -v "$DIR:/repo/infra:ro" -v "$VOLUME:/scratch" \
        "$TERRAFORM_IMAGE" /repo/infra/scripts/terraform-check.sh --in-container "$@"
}

fmt_on() {
    in_terraform fmt "$1"
}

terraform_on() {
    in_terraform terraform "$@"
}

tflint_on() {
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "$DIR:/repo/infra:ro" -v "$VOLUME:/scratch:ro" \
        "$TFLINT_IMAGE" --recursive --chdir="$1" --config="$1/.tflint.hcl" --no-color
}

trivy_on() {
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "$DIR:/repo/infra:ro" -v "$VOLUME:/scratch:ro" \
        "$TRIVY_IMAGE" config --skip-check-update --severity HIGH,CRITICAL --exit-code 1 --quiet "$1"
}

status=0

# check <label> <command...>: the command must succeed.
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
        echo "ok   plant $label refused: $(printf '%s\n' "$output" | grep -F -m 1 -- "$reason" | sed 's/^[[:space:]]*//')"
    else
        printf '%s\n' "$output"
        echo "!!   plant $label was refused, but not for its reason: the output never says '$reason'"
        status=1
    fi
}

PLANTS=/scratch/plants

check "the plants are planted" in_terraform plants

check "terraform fmt" fmt_on "$TREE"
check "tflint" tflint_on "$TREE"
check "trivy config" trivy_on "$TREE"
check "terraform validate and test" terraform_on "$TREE"

refuses unformatted "environments/staging/main.tf" fmt_on "$PLANTS/unformatted"
refuses unpinned-provider "terraform_required_providers" tflint_on "$PLANTS/unpinned-provider"
refuses camel-case "terraform_naming_convention" tflint_on "$PLANTS/camel-case"
refuses public-bucket "AWS-0086" trivy_on "$PLANTS/public-bucket"
refuses invalid "Reference to undeclared local value" terraform_on "$PLANTS/invalid" environments/staging
refuses untagged "convention's tags" terraform_on "$PLANTS/untagged" modules/conventions
refuses public-endpoint "The API endpoint has a public address" terraform_on "$PLANTS/public-endpoint" modules/kubernetes
refuses public-workload-subnet "A workload subnet assigns a public address" terraform_on "$PLANTS/public-workload-subnet" modules/network
refuses cluster-on-edge-tier "not on exactly the workload subnets" terraform_on "$PLANTS/cluster-on-edge-tier" environments/staging
refuses no-flow-logs "flow log does not cover every flow" terraform_on "$PLANTS/no-flow-logs" modules/network
refuses no-audit-log "audit log is off" terraform_on "$PLANTS/no-audit-log" modules/kubernetes
refuses no-etcd-encryption "Kubernetes Secrets are not encrypted in etcd" terraform_on "$PLANTS/no-etcd-encryption" modules/kubernetes
refuses two-zones "var.availability_zones, was expected to report" terraform_on "$PLANTS/two-zones" modules/network

if [ "$status" -eq 0 ]; then
    echo "ok   every check green, every plant refused for its reason"
fi
exit $status
