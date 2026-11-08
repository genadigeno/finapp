# Remote state for staging alone (ADR-0121 decision 3): encrypted at rest, locked against a second
# concurrent writer by the backend's lock file, and keyed per environment. The bucket (versioned, in
# staging's own account) and the region are given at init in a real environment, as a partial
# configuration; no account exists in Phase 15, so none is invented here.
#
# CI never initialises this backend: infra/scripts/terraform-check.sh runs every init with
# -backend=false, and terraform test keeps its state in memory. No credential belongs here either.
terraform {
  backend "s3" {
    key          = "finapp/staging/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}
