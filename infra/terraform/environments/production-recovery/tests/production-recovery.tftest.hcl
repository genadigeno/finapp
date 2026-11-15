# Production's recovery-region root, planned against a mock AWS provider. A mock provider holds no credential and calls no
# API; TerraformIsPinnedAndCheckedTest refuses a test file that does not mock every provider its root requires. The module's
# own test holds each key's and the vault's properties; these runs hold what the root decides: that it is production's site,
# in another region inside the residency rule, and that its outputs are what production's recovery_region input takes.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = module.recovery_region.aws_kms_key.this["region_copies"]
    values = { arn = "arn:aws:kms:eu-west-1:222222222222:key/bbbbbbbb-0000-0000-0000-000000000001" }
  }

  override_resource {
    target = module.recovery_region.aws_kms_key.this["field_keys"]
    values = { arn = "arn:aws:kms:eu-west-1:222222222222:key/bbbbbbbb-0000-0000-0000-000000000002" }
  }

  override_resource {
    target = module.recovery_region.aws_backup_vault.region_copies
    values = { arn = "arn:aws:backup:eu-west-1:222222222222:backup-vault:finapp-production-database-region-copies" }
  }
}

# The residency set below is a test input and decides nothing: no jurisdiction is chosen.
variables {
  region            = "eu-west-1"
  primary_region    = "eu-central-1"
  account_id        = "222222222222"
  residency_regions = ["eu-central-1", "eu-west-1"]
}

run "the_site_is_production_s_in_the_recovery_region" {
  command = plan

  assert {
    condition     = module.conventions.name_prefix == "finapp-production" && module.conventions.tags.Environment == "production"
    error_message = "The recovery site does not name and tag itself as production's."
  }

  assert {
    condition     = alltrue([for key in module.recovery_region.kms_key_arns : split(":", key)[3] == "eu-west-1"])
    error_message = "A key of the standing site is not in the recovery region."
  }
}

run "the_outputs_are_production_s_recovery_region_input_and_point_outside_its_region" {
  command = plan

  assert {
    condition = output.recovery_region == {
      region                  = "eu-west-1"
      database_copy_vault_arn = "arn:aws:backup:eu-west-1:222222222222:backup-vault:finapp-production-database-region-copies"
      field_keys_kms_key_arn  = "arn:aws:kms:eu-west-1:222222222222:key/bbbbbbbb-0000-0000-0000-000000000002"
    }
    error_message = "The root's output is not the recovery region, its vault and its rings' key."
  }

  assert {
    condition     = split(":", output.recovery_region.database_copy_vault_arn)[3] != var.primary_region && split(":", output.recovery_region.field_keys_kms_key_arn)[3] != var.primary_region
    error_message = "The copy's destination, or the rings' replica key, is in production's own region."
  }
}

run "a_recovery_region_outside_the_residency_rule_is_refused" {
  command = plan

  variables {
    region = "us-east-1"
  }

  expect_failures = [var.residency_regions]
}

# The site in production's own region is refused by the module's own validation, held by the module's test
# (a_recovery_site_in_the_primary_region_is_refused) and by terraform-check.sh's recovery-site-in-primary-region plant.
