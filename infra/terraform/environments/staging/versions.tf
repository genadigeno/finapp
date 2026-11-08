# Pinned and locked (ADR-0121 decision 2, mirroring ADR-0025 for Gradle).
#
# required_version equals TERRAFORM_VERSION in infra/scanner-pins.sh, the image the checks run, and
# every provider is an exact version that .terraform.lock.hcl holds with its checksums.
# TerraformIsPinnedAndCheckedTest refuses a range, a missing pin or a lock file that disagrees.
# Dependabot's terraform ecosystem proposes provider updates (.github/dependabot.yml).
terraform {
  required_version = "1.17.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.72.0"
    }
  }
}
