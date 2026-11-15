# Pinned and locked (ADR-0121 decision 2), as every root: required_version equals TERRAFORM_VERSION in
# infra/scanner-pins.sh, and the one provider this root needs is the exact version production pins, which
# .terraform.lock.hcl holds with its checksums (the same entry as production's). TerraformIsPinnedAndCheckedTest
# holds both, and that this root requires no provider production does not pin at production's version.
terraform {
  required_version = "1.17.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "6.72.0"
    }
  }
}
