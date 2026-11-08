# A child module states the oldest Terraform it supports; the root that calls it pins the exact
# version (environments/*/versions.tf). This module uses no provider.
terraform {
  required_version = ">= 1.17.1"
}
