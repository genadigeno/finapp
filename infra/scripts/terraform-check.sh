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
# And one per named property of the data tier. A plant can mutate infra/kafka and read deploy too,
# because each copy keeps the repository's shape, and those are the files the data tier reads:
#
#   multi-az-off              the database's standby turned off         the postgres module's test
#   force-ssl-off             rds.force_ssl set to 0                    the postgres module's test
#   database-public           the database given a public address       the postgres module's test
#   backup-key-shared         the separate-key rule removed             the postgres module's test
#   max-connections-restated  a number in place of the overlay's        production's test
#   unclean-election-on       the posture's unclean election turned on  the kafka module's test
#   auto-create-on            the posture's auto-create turned on       the kafka module's test
#   min-insync-one            the posture's min.insync.replicas at 1    the kafka module's test
#   brokers-plaintext         clients admitted without TLS              the kafka module's test
#   brokers-unauthenticated   unauthenticated access turned on          the kafka module's test
#   scram-unprefixed          a SCRAM secret without MSK's prefix       the kafka module's test
#   topics-from-a-copy        production's topics read from another     production's test
#                             file
#   public-archive            one of a bucket's public blocks off       the object-storage test
#   no-object-lock            the audit archive created without a lock  the object-storage test
#
# And one per named property of the security modules:
#
#   reader-wildcard            the application's reader given every secret action  the security module's test
#   reader-reads-the-other     each reader allowed the other's entries             the security module's test
#   reader-reads-the-master    the application's reader given the master entry     the security module's test
#   reader-any-account         a reader's trust made a pattern                     the security module's test
#   break-glass-no-mfa         the factor dropped from a human role's trust        the security module's test
#   break-glass-long-session   break-glass's session raised past an hour           the security module's test
#   human-role-no-boundary     a human role's permission boundary dropped          the security module's test
#   human-no-mfa-policy        the group's refusal without a factor removed        the security module's test
#   person-holds-a-key         the plan's refusal of a person's access key removed the security module's test
#   key-no-rotation            a key's rotation turned off                         the security module's test
#   key-lock-out               the account's PutKeyPolicy removed                  the security module's test
#   key-account-uses-it        the account's administration given Decrypt          the security module's test
#   backup-copy-unconditioned  the copy's account free to use the database key     the security module's test
#   inventory-row-no-entry     an inventory row no ExternalSecret reads            production's test
#   registry-mutable           a repository's tags made mutable                    the registry module's test
#   registry-no-scan           scan on push turned off                             the registry module's test
#
# WHAT IT NEVER DOES. It initialises no backend (every init is -backend=false) and holds no credential:
# no container is given a variable or a file from the host beyond the infra, deploy and docs directories,
# mounted read-only (deploy holds the overlays, whose max_connections the database reads; docs holds the
# secret inventory, whose rows the security module's entries are), and a scratch volume of its own, removed at exit. terraform test keeps its state in memory, and every test
# file mocks every provider its root requires (TerraformIsPinnedAndCheckedTest).
# Nothing here can change the tree, the lock files or any cloud account.
#
# SAME BYTES HERE AND IN CI. Every tool is an image pinned by digest in infra/scanner-pins.sh; tflint
# uses only its bundled ruleset and trivy only the checks embedded in the pinned binary
# (--skip-check-update), so nothing is fetched at run time except the providers, once per run into a
# mirror that every init reads and verifies against the committed .terraform.lock.hcl. TERRAFORM_IMAGE, TFLINT_IMAGE and TRIVY_IMAGE override an
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
# Inside the Terraform container: /repo/infra and /repo/deploy are the repository's directories,
# read-only; /scratch is this run's volume. Called by the host half below, never by hand.
# ---------------------------------------------------------------------------------------------------
TREE=/repo/infra/terraform
INVENTORY=docs/architecture/SECRET_MANAGEMENT.md

# A copy keeps the repository's shape, so a root's ../../../kafka and ../../../../deploy resolve in it.
plant() {
    rm -rf "/scratch/plants/$1"
    mkdir -p "/scratch/plants/$1/infra"
    cp -R "$TREE" "/scratch/plants/$1/infra/terraform"
    cp -R /repo/infra/kafka "/scratch/plants/$1/infra/kafka"
    cp -R /repo/deploy "/scratch/plants/$1/deploy"
    mkdir -p "/scratch/plants/$1/docs/architecture"
    cp "/repo/$INVENTORY" "/scratch/plants/$1/$INVENTORY"
}

planted() {
    if cmp -s "$TREE/$2" "/scratch/plants/$1/infra/terraform/$2"; then
        echo "!!   plant $1 was not planted: $2 is unchanged, so its mutation no longer applies" >&2
        exit 1
    fi
}

planted_posture() {
    if cmp -s /repo/infra/kafka/broker.properties "/scratch/plants/$1/infra/kafka/broker.properties"; then
        echo "!!   plant $1 was not planted: broker.properties is unchanged, so its mutation no longer applies" >&2
        exit 1
    fi
}

plant_all() {
    plant unformatted
    printf 'locals {\n    plant   =     "unformatted"\n}\n' >> /scratch/plants/unformatted/infra/terraform/environments/staging/main.tf
    planted unformatted environments/staging/main.tf

    plant unpinned-provider
    sed -i '/^ *version *= /d' /scratch/plants/unpinned-provider/infra/terraform/environments/staging/versions.tf
    planted unpinned-provider environments/staging/versions.tf

    plant camel-case
    sed -i 's/^module "conventions"/module "namingConventions"/' /scratch/plants/camel-case/infra/terraform/environments/staging/main.tf
    sed -i 's/module\.conventions\./module.namingConventions./g' \
        /scratch/plants/camel-case/infra/terraform/environments/staging/main.tf \
        /scratch/plants/camel-case/infra/terraform/environments/staging/providers.tf \
        /scratch/plants/camel-case/infra/terraform/environments/staging/outputs.tf
    planted camel-case environments/staging/main.tf

    plant public-bucket
    cat > /scratch/plants/public-bucket/infra/terraform/environments/staging/plant.tf <<'HCL'
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
        >> /scratch/plants/invalid/infra/terraform/environments/staging/outputs.tf
    planted invalid environments/staging/outputs.tf

    plant untagged
    sed -i '/ManagedBy/d' /scratch/plants/untagged/infra/terraform/modules/conventions/main.tf
    planted untagged modules/conventions/main.tf

    plant public-endpoint
    sed -i 's/endpoint_public_access  = false/endpoint_public_access  = true/' \
        /scratch/plants/public-endpoint/infra/terraform/modules/kubernetes/main.tf
    planted public-endpoint modules/kubernetes/main.tf

    plant public-workload-subnet
    sed -i '/^resource "aws_subnet" "private"/,/^}/ s/map_public_ip_on_launch = false/map_public_ip_on_launch = true/' \
        /scratch/plants/public-workload-subnet/infra/terraform/modules/network/main.tf
    planted public-workload-subnet modules/network/main.tf

    plant cluster-on-edge-tier
    sed -i '/^module "kubernetes" {/,/^}/ s/module\.network\.private_subnet_ids_by_zone/module.network.public_subnet_ids_by_zone/' \
        /scratch/plants/cluster-on-edge-tier/infra/terraform/environments/staging/main.tf
    planted cluster-on-edge-tier environments/staging/main.tf

    plant no-flow-logs
    sed -i 's/traffic_type             = "ALL"/traffic_type             = "REJECT"/' \
        /scratch/plants/no-flow-logs/infra/terraform/modules/network/main.tf
    planted no-flow-logs modules/network/main.tf

    plant no-audit-log
    sed -i 's/\["api", "audit", /["api", /' /scratch/plants/no-audit-log/infra/terraform/modules/kubernetes/main.tf
    planted no-audit-log modules/kubernetes/main.tf

    plant no-etcd-encryption
    sed -i '/^  encryption_config {/,/^  }/d' /scratch/plants/no-etcd-encryption/infra/terraform/modules/kubernetes/main.tf
    planted no-etcd-encryption modules/kubernetes/main.tf

    plant two-zones
    sed -i 's/length(distinct(var\.availability_zones)) >= 3/length(distinct(var.availability_zones)) >= 2/' \
        /scratch/plants/two-zones/infra/terraform/modules/network/variables.tf
    planted two-zones modules/network/variables.tf

    plant multi-az-off
    sed -i 's/multi_az               = true/multi_az               = false/' \
        /scratch/plants/multi-az-off/infra/terraform/modules/postgres/main.tf
    planted multi-az-off modules/postgres/main.tf

    plant force-ssl-off
    sed -i '/name         = "rds.force_ssl"/,/apply_method/ s/value        = "1"/value        = "0"/' \
        /scratch/plants/force-ssl-off/infra/terraform/modules/postgres/main.tf
    planted force-ssl-off modules/postgres/main.tf

    plant database-public
    sed -i 's/publicly_accessible    = false/publicly_accessible    = true/' \
        /scratch/plants/database-public/infra/terraform/modules/postgres/main.tf
    planted database-public modules/postgres/main.tf

    plant backup-key-shared
    sed -i 's/condition     = var\.backup_kms_key_arn != var\.storage_kms_key_arn/condition     = var.backup_kms_key_arn == var.backup_kms_key_arn/' \
        /scratch/plants/backup-key-shared/infra/terraform/modules/postgres/variables.tf
    planted backup-key-shared modules/postgres/variables.tf

    plant max-connections-restated
    sed -i 's/max_connections           = local\.server_max_connections/max_connections           = 90/' \
        /scratch/plants/max-connections-restated/infra/terraform/environments/production/main.tf
    planted max-connections-restated environments/production/main.tf

    plant unclean-election-on
    sed -i 's/^unclean\.leader\.election\.enable=false/unclean.leader.election.enable=true/' \
        /scratch/plants/unclean-election-on/infra/kafka/broker.properties
    planted_posture unclean-election-on

    plant auto-create-on
    sed -i 's/^auto\.create\.topics\.enable=false/auto.create.topics.enable=true/' \
        /scratch/plants/auto-create-on/infra/kafka/broker.properties
    planted_posture auto-create-on

    plant min-insync-one
    sed -i 's/^min\.insync\.replicas=2/min.insync.replicas=1/' \
        /scratch/plants/min-insync-one/infra/kafka/broker.properties
    planted_posture min-insync-one

    plant brokers-plaintext
    sed -i 's/client_broker = "TLS"/client_broker = "TLS_PLAINTEXT"/' \
        /scratch/plants/brokers-plaintext/infra/terraform/modules/kafka/main.tf
    planted brokers-plaintext modules/kafka/main.tf

    plant brokers-unauthenticated
    sed -i 's/unauthenticated = false/unauthenticated = true/' \
        /scratch/plants/brokers-unauthenticated/infra/terraform/modules/kafka/main.tf
    planted brokers-unauthenticated modules/kafka/main.tf

    plant scram-unprefixed
    sed -i 's/name        = "AmazonMSK_\${each\.value}"/name        = each.value/' \
        /scratch/plants/scram-unprefixed/infra/terraform/modules/kafka/main.tf
    planted scram-unprefixed modules/kafka/main.tf

    plant topics-from-a-copy
    sed -i 's#\${path\.module}/\.\./\.\./\.\./kafka/topics\.yaml#${path.module}/../../modules/kafka/tests/fixture-topics.yaml#' \
        /scratch/plants/topics-from-a-copy/infra/terraform/environments/production/main.tf
    planted topics-from-a-copy environments/production/main.tf

    plant public-archive
    sed -i 's/block_public_policy     = true/block_public_policy     = false/' \
        /scratch/plants/public-archive/infra/terraform/modules/object-storage/main.tf
    planted public-archive modules/object-storage/main.tf

    plant no-object-lock
    sed -i 's/object_lock_enabled = each\.key == "audit-archive"/object_lock_enabled = false/' \
        /scratch/plants/no-object-lock/infra/terraform/modules/object-storage/main.tf
    planted no-object-lock modules/object-storage/main.tf

    plant reader-wildcard
    sed -i 's/Action   = \["secretsmanager:GetSecretValue"\]/Action   = ["secretsmanager:*"]/' \
        /scratch/plants/reader-wildcard/infra/terraform/modules/security/main.tf
    planted reader-wildcard modules/security/main.tf

    plant reader-reads-the-other
    sed -i 's/workload => toset(\[for item in local\.delivered_entries : item\.name if item\.workload == workload\])/workload => toset([for item in local.delivered_entries : item.name])/' \
        /scratch/plants/reader-reads-the-other/infra/terraform/modules/security/main.tf
    planted reader-reads-the-other modules/security/main.tf

    plant reader-reads-the-master
    sed -i 's/Resource = \[for name in sort(tolist(local\.reads\[each\.key\])) : format(local\.entry_arn, name)\]/Resource = concat([for name in sort(tolist(local.reads[each.key])) : format(local.entry_arn, name)], [var.master_user_secret_arn])/' \
        /scratch/plants/reader-reads-the-master/infra/terraform/modules/security/main.tf
    planted reader-reads-the-master modules/security/main.tf

    plant reader-any-account
    sed -i '/^resource "aws_iam_role" "secret_reader"/,/^}/ s/StringEquals = {/StringLike = {/' \
        /scratch/plants/reader-any-account/infra/terraform/modules/security/main.tf
    planted reader-any-account modules/security/main.tf

    plant break-glass-no-mfa
    sed -i '/Bool            = { "aws:MultiFactorAuthPresent" = "true" }/d' \
        /scratch/plants/break-glass-no-mfa/infra/terraform/modules/security/main.tf
    planted break-glass-no-mfa modules/security/main.tf

    plant break-glass-long-session
    sed -i 's/kubernetes_group = "finapp-break-glass", session_seconds = 3600/kubernetes_group = "finapp-break-glass", session_seconds = 43200/' \
        /scratch/plants/break-glass-long-session/infra/terraform/modules/security/main.tf
    planted break-glass-long-session modules/security/main.tf

    plant human-role-no-boundary
    sed -i '/permissions_boundary = each\.key == "bootstrap"/d' \
        /scratch/plants/human-role-no-boundary/infra/terraform/modules/security/main.tf
    planted human-role-no-boundary modules/security/main.tf

    plant human-no-mfa-policy
    sed -i 's/BoolIfExists = { "aws:MultiFactorAuthPresent" = "false" }/BoolIfExists = { "aws:SecureTransport" = "false" }/' \
        /scratch/plants/human-no-mfa-policy/infra/terraform/modules/security/main.tf
    planted human-no-mfa-policy modules/security/main.tf

    plant person-holds-a-key
    sed -i 's/condition     = length(self\.access_keys) == 0/condition     = length(self.access_keys) >= 0/' \
        /scratch/plants/person-holds-a-key/infra/terraform/modules/security/main.tf
    planted person-holds-a-key modules/security/main.tf

    plant key-no-rotation
    sed -i 's/enable_key_rotation                = true/enable_key_rotation                = false/' \
        /scratch/plants/key-no-rotation/infra/terraform/modules/security/main.tf
    planted key-no-rotation modules/security/main.tf

    plant key-lock-out
    sed -i '/^    "kms:PutKeyPolicy",$/d' /scratch/plants/key-lock-out/infra/terraform/modules/security/main.tf
    planted key-lock-out modules/security/main.tf

    plant key-account-uses-it
    sed -i 's/^    "kms:PutKeyPolicy",$/    "kms:Decrypt",\n    "kms:PutKeyPolicy",/' \
        /scratch/plants/key-account-uses-it/infra/terraform/modules/security/main.tf
    planted key-account-uses-it modules/security/main.tf

    plant backup-copy-unconditioned
    sed -i '/Condition = { StringEquals = { "kms:ViaService" = local\.backup_copy_services } }/d' \
        /scratch/plants/backup-copy-unconditioned/infra/terraform/modules/security/main.tf
    planted backup-copy-unconditioned modules/security/main.tf

    plant inventory-row-no-entry
    sed -i '/^| `FINAPP_MFA_KEY` /a | `FINAPP_PLANTED_KEY` | none | webhook-hmac | `payments` | two-valid-window | `finapp-app` | `finapp-app-secrets` |' \
        "/scratch/plants/inventory-row-no-entry/$INVENTORY"
    if cmp -s "/repo/$INVENTORY" "/scratch/plants/inventory-row-no-entry/$INVENTORY"; then
        echo "!!   plant inventory-row-no-entry was not planted: the inventory is unchanged" >&2
        exit 1
    fi

    plant registry-mutable
    sed -i 's/image_tag_mutability = "IMMUTABLE"/image_tag_mutability = "MUTABLE"/' \
        /scratch/plants/registry-mutable/infra/terraform/modules/registry/main.tf
    planted registry-mutable modules/registry/main.tf

    plant registry-no-scan
    sed -i 's/scan_on_push = true/scan_on_push = false/' \
        /scratch/plants/registry-no-scan/infra/terraform/modules/registry/main.tf
    planted registry-no-scan modules/registry/main.tf
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

# Every provider the tree requires, fetched once per run and verified against the committed lock file.
# Each root pins every provider a module uses (TerraformIsPinnedAndCheckedTest), so one root's list is the
# whole list. Every init after this reads the mirror and needs no network, so a transient failure cannot
# read as a plant refused for the wrong reason: the Kafka provider's checksums come from GitHub, and init
# asks for them on every run, plugin cache or not. This one fetch is retried; a real failure still stops.
mirror_providers() {
    unset TF_CLI_CONFIG_FILE
    rm -rf /scratch/mirror-config
    mkdir -p /scratch/mirror-config /scratch/mirror
    cp "$TREE/environments/production/versions.tf" "$TREE/environments/production/.terraform.lock.hcl" /scratch/mirror-config/
    attempt=1
    until terraform -chdir=/scratch/mirror-config providers mirror /scratch/mirror >/dev/null; do
        [ "$attempt" -lt 3 ] || return 1
        attempt=$((attempt + 1))
        sleep 20
    done
    printf 'provider_installation {\n  filesystem_mirror {\n    path = "/scratch/mirror"\n  }\n}\n' > /scratch/terraformrc
}

in_container() {
    export TF_IN_AUTOMATION=1 CHECKPOINT_DISABLE=1 TF_PLUGIN_CACHE_DIR=/scratch/plugins TF_CLI_CONFIG_FILE=/scratch/terraformrc
    mkdir -p /scratch/plugins
    step=$1
    shift
    case $step in
        mirror)
            mirror_providers
            ;;
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
REPO=$(CDPATH= cd -- "$DIR/.." && pwd)

in_terraform() {
    MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh \
        -v "$DIR:/repo/infra:ro" -v "$REPO/deploy:/repo/deploy:ro" -v "$REPO/docs:/repo/docs:ro" -v "$VOLUME:/scratch" \
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
        -v "$DIR:/repo/infra:ro" -v "$REPO/deploy:/repo/deploy:ro" -v "$REPO/docs:/repo/docs:ro" -v "$VOLUME:/scratch:ro" \
        "$TFLINT_IMAGE" --recursive --chdir="$1" --config="$1/.tflint.hcl" --no-color
}

trivy_on() {
    MSYS_NO_PATHCONV=1 docker run --rm \
        -v "$DIR:/repo/infra:ro" -v "$REPO/deploy:/repo/deploy:ro" -v "$REPO/docs:/repo/docs:ro" -v "$VOLUME:/scratch:ro" \
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

check "the providers are mirrored from the lock file" in_terraform mirror
check "the plants are planted" in_terraform plants

check "terraform fmt" fmt_on "$TREE"
check "tflint" tflint_on "$TREE"
check "trivy config" trivy_on "$TREE"
check "terraform validate and test" terraform_on "$TREE"

refuses unformatted "environments/staging/main.tf" fmt_on "$PLANTS/unformatted/infra/terraform"
refuses unpinned-provider "terraform_required_providers" tflint_on "$PLANTS/unpinned-provider/infra/terraform"
refuses camel-case "terraform_naming_convention" tflint_on "$PLANTS/camel-case/infra/terraform"
refuses public-bucket "AWS-0086" trivy_on "$PLANTS/public-bucket/infra/terraform"
refuses invalid "Reference to undeclared local value" terraform_on "$PLANTS/invalid/infra/terraform" environments/staging
refuses untagged "convention's tags" terraform_on "$PLANTS/untagged/infra/terraform" modules/conventions
refuses public-endpoint "The API endpoint has a public address" terraform_on "$PLANTS/public-endpoint/infra/terraform" modules/kubernetes
refuses public-workload-subnet "A workload subnet assigns a public address" terraform_on "$PLANTS/public-workload-subnet/infra/terraform" modules/network
refuses cluster-on-edge-tier "not on exactly the workload subnets" terraform_on "$PLANTS/cluster-on-edge-tier/infra/terraform" environments/staging
refuses no-flow-logs "flow log does not cover every flow" terraform_on "$PLANTS/no-flow-logs/infra/terraform" modules/network
refuses no-audit-log "audit log is off" terraform_on "$PLANTS/no-audit-log/infra/terraform" modules/kubernetes
refuses no-etcd-encryption "Kubernetes Secrets are not encrypted in etcd" terraform_on "$PLANTS/no-etcd-encryption/infra/terraform" modules/kubernetes
refuses two-zones "var.availability_zones, was expected to report" terraform_on "$PLANTS/two-zones/infra/terraform" modules/network
refuses multi-az-off "The database has no synchronous standby" terraform_on "$PLANTS/multi-az-off/infra/terraform" modules/postgres
refuses force-ssl-off "The server accepts a connection without TLS" terraform_on "$PLANTS/force-ssl-off/infra/terraform" modules/postgres
refuses database-public "The database has a public address" terraform_on "$PLANTS/database-public/infra/terraform" modules/postgres
refuses backup-key-shared "var.backup_kms_key_arn, was expected to report" terraform_on "$PLANTS/backup-key-shared/infra/terraform" modules/postgres
refuses max-connections-restated "not what production's overlay declares" terraform_on "$PLANTS/max-connections-restated/infra/terraform" environments/production
refuses unclean-election-on "Unclean leader election is on" terraform_on "$PLANTS/unclean-election-on/infra/terraform" modules/kafka
refuses auto-create-on "A broker would create a topic" terraform_on "$PLANTS/auto-create-on/infra/terraform" modules/kafka
refuses min-insync-one "min.insync.replicas is not 2" terraform_on "$PLANTS/min-insync-one/infra/terraform" modules/kafka
refuses brokers-plaintext "A client or a broker can talk to a broker without TLS" terraform_on "$PLANTS/brokers-plaintext/infra/terraform" modules/kafka
refuses brokers-unauthenticated "A client can reach the brokers unauthenticated" terraform_on "$PLANTS/brokers-unauthenticated/infra/terraform" modules/kafka
refuses scram-unprefixed "does not start with AmazonMSK_" terraform_on "$PLANTS/scram-unprefixed/infra/terraform" modules/kafka
refuses topics-from-a-copy "topics are not the catalogue's" terraform_on "$PLANTS/topics-from-a-copy/infra/terraform" environments/production
refuses public-archive "A bucket admits public access" terraform_on "$PLANTS/public-archive/infra/terraform" modules/object-storage
refuses no-object-lock "Object Lock is not on at the audit archive" terraform_on "$PLANTS/no-object-lock/infra/terraform" modules/object-storage
refuses reader-wildcard "A reader may do more to a secret than read it" terraform_on "$PLANTS/reader-wildcard/infra/terraform" modules/security
refuses reader-reads-the-other "The application's reader can read the migrator's entries" terraform_on "$PLANTS/reader-reads-the-other/infra/terraform" modules/security
refuses reader-reads-the-master "has a wildcard resource, or reaches" terraform_on "$PLANTS/reader-reads-the-master/infra/terraform" modules/security
refuses reader-any-account "does not name exactly its ServiceAccount" terraform_on "$PLANTS/reader-any-account/infra/terraform" modules/security
refuses break-glass-no-mfa "A human role can be assumed without MFA" terraform_on "$PLANTS/break-glass-no-mfa/infra/terraform" modules/security
refuses break-glass-long-session "no one-hour session limit" terraform_on "$PLANTS/break-glass-long-session/infra/terraform" modules/security
refuses human-role-no-boundary "A human role has no permission boundary" terraform_on "$PLANTS/human-role-no-boundary/infra/terraform" modules/security
refuses human-no-mfa-policy "does not refuse everything without a factor" terraform_on "$PLANTS/human-no-mfa-policy/infra/terraform" modules/security
refuses person-holds-a-key "data.aws_iam_access_keys.human" terraform_on "$PLANTS/person-holds-a-key/infra/terraform" modules/security
refuses key-no-rotation "A key does not rotate" terraform_on "$PLANTS/key-no-rotation/infra/terraform" modules/security
refuses key-lock-out "has no statement letting the account repair it" terraform_on "$PLANTS/key-lock-out/infra/terraform" modules/security
refuses key-account-uses-it "lets the account use the key" terraform_on "$PLANTS/key-account-uses-it/infra/terraform" modules/security
refuses backup-copy-unconditioned "can use the database key outside the copy" terraform_on "$PLANTS/backup-copy-unconditioned/infra/terraform" modules/security
refuses inventory-row-no-entry "An inventory row has no entry" terraform_on "$PLANTS/inventory-row-no-entry/infra/terraform" environments/production
refuses registry-mutable "tags are not immutable" terraform_on "$PLANTS/registry-mutable/infra/terraform" modules/registry
refuses registry-no-scan "does not scan on push" terraform_on "$PLANTS/registry-no-scan/infra/terraform" modules/registry

if [ "$status" -eq 0 ]; then
    echo "ok   every check green, every plant refused for its reason"
fi
exit $status
