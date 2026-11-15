# Production's recovery-region root (P16-TSK-026, ADR-0150; ADR-0129). Nothing here has ever been applied.
#
# It holds the standing site alone: what must exist in a second region before production's region is lost, so that a
# regional recovery (ADR-0128 tier 4) finds a recovery point and every key ring version there. It is production's site, so
# it names and tags itself as production through the conventions, and its own resources say Site = recovery-region.
#
# Its outputs are production's recovery_region input: apply this root first, then production's. What a declared regional
# recovery builds here afterwards, and the fence before it, are OPERATIONS_RUNBOOK.md section 11.65; planning that rebuild in
# the same account is THREAT_MODEL.md section 6 item 72.

module "conventions" {
  source = "../../modules/conventions"

  environment = "production"
}

module "recovery_region" {
  source = "../../modules/recovery-region"

  name_prefix    = module.conventions.name_prefix
  region         = var.region
  primary_region = var.primary_region
  account_id     = var.account_id
}
