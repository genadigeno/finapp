# The recovery region, in production's account (ADR-0150): allowed_account_ids refuses any other, and every resource
# carries production's conventions' tags through default_tags. The region is the recovery region's, never production's
# (refused by the recovery-region module).
provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = module.conventions.tags
  }
}
