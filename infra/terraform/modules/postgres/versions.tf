# A child module states the oldest Terraform and provider it supports; the root that calls it pins the
# exact versions (environments/*/versions.tf). This directory's .terraform.lock.hcl exists because
# terraform-check.sh runs every init with -lockfile=readonly, and TerraformIsPinnedAndCheckedTest holds
# it at the roots' version, so this module's tests run against the provider the roots use.
terraform {
  required_version = ">= 1.17.1"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.72.0"
    }
  }
}
