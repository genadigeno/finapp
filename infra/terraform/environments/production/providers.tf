# Production applies only to production's account: production and non-production live in separate accounts
# (ADR-0121 decision 4), and allowed_account_ids refuses any other. Every resource carries the
# conventions' tags through default_tags.
provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = module.conventions.tags
  }
}
