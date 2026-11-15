# Remote state for production's recovery region alone (ADR-0121 decision 3, ADR-0150): encrypted, locked, and under a
# key of its own beside production's, so that planning the standing site never takes production's lock. The bucket is
# given at init in a real environment and must be in the recovery region: production's own state lives in production's
# region and is lost with it, which is why this root's must not (THREAT_MODEL.md section 6 item 72).
#
# CI never initialises this backend: infra/scripts/terraform-check.sh runs every init with -backend=false, and
# terraform test keeps its state in memory. No credential belongs here either.
terraform {
  backend "s3" {
    key          = "finapp/production/recovery-region/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}
