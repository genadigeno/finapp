# The standing recovery site, asserted over a plan against a mock AWS provider. A mock provider holds no credential and calls
# no API, so this proves the configuration and nothing about what AWS accepts (README.md, "What could not be checked").

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_kms_key.this["region_copies"]
    values = { arn = "arn:aws:kms:eu-west-1:222222222222:key/bbbbbbbb-0000-0000-0000-000000000001" }
  }

  override_resource {
    target = aws_kms_key.this["field_keys"]
    values = { arn = "arn:aws:kms:eu-west-1:222222222222:key/bbbbbbbb-0000-0000-0000-000000000002" }
  }

  override_resource {
    target = aws_backup_vault.region_copies
    values = { arn = "arn:aws:backup:eu-west-1:222222222222:backup-vault:finapp-production-database-region-copies" }
  }
}

variables {
  name_prefix    = "finapp-production"
  region         = "eu-west-1"
  primary_region = "eu-central-1"
  account_id     = "222222222222"
}

run "each_key_rotates_keeps_a_deletion_window_and_never_leaves_its_region" {
  command = plan

  assert {
    condition     = toset(keys(aws_kms_key.this)) == toset(["region_copies", "field_keys"])
    error_message = "The standing site does not hold exactly its two keys: the region copies' and the rings' replicas'."
  }

  assert {
    condition     = alltrue([for key in aws_kms_key.this : key.enable_key_rotation == true && key.deletion_window_in_days == 30])
    error_message = "A key of the recovery region does not rotate, or can be deleted without the thirty days' window."
  }

  assert {
    condition     = alltrue([for key in aws_kms_key.this : key.multi_region == false])
    error_message = "A key of the recovery region is multi-region, sharing one key's material with another region."
  }

  assert {
    condition     = alltrue([for key in aws_kms_key.this : key.bypass_policy_lockout_safety_check == false])
    error_message = "A key's policy may be written past KMS's lockout check."
  }

  assert {
    condition     = alltrue([for tags in concat([for key in aws_kms_key.this : key.tags], [aws_backup_vault.region_copies.tags]) : tags == tomap({ Site = "recovery-region", RecoversRegion = "eu-central-1" })])
    error_message = "A resource of the standing site does not say it is the recovery site for production's region."
  }
}

run "no_key_policy_admits_anyone_outside_the_account_or_lets_the_account_use_it_directly" {
  command = plan

  assert {
    condition = alltrue([
      for key in aws_kms_key.this : alltrue([
        for statement in jsondecode(key.policy).Statement : statement.Principal == { AWS = "arn:aws:iam::222222222222:root" }
      ])
    ])
    error_message = "A key policy of the recovery region admits a principal outside the account."
  }

  assert {
    condition = alltrue([
      for key in aws_kms_key.this :
      contains(jsondecode(key.policy).Statement[0].Action, "kms:PutKeyPolicy") && !contains(jsondecode(key.policy).Statement[0].Action, "kms:Decrypt")
    ])
    error_message = "A key policy locks the account out of repairing it, or lets the account use the key without a service."
  }

  assert {
    condition     = alltrue([for key in aws_kms_key.this : length(jsondecode(key.policy).Statement) == 2])
    error_message = "A key policy holds a statement beyond administration and use through its services."
  }
}

run "each_key_is_used_through_its_services_in_the_recovery_region_alone" {
  command = plan

  assert {
    condition     = toset(jsondecode(aws_kms_key.this["region_copies"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["backup.eu-west-1.amazonaws.com", "rds.eu-west-1.amazonaws.com"])
    error_message = "The region copies' key is not used through AWS Backup and RDS in the recovery region alone."
  }

  assert {
    condition     = toset(jsondecode(aws_kms_key.this["field_keys"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["secretsmanager.eu-west-1.amazonaws.com"])
    error_message = "The rings' replica key is not used through Secrets Manager in the recovery region alone."
  }

  assert {
    condition     = alltrue([for key in aws_kms_key.this : !strcontains(key.policy, "eu-central-1")])
    error_message = "A key of the recovery region is usable from production's region."
  }
}

run "the_region_copies_land_in_a_vault_of_the_recovery_region_under_its_own_key" {
  command = plan

  assert {
    condition     = aws_backup_vault.region_copies.kms_key_arn == "arn:aws:kms:eu-west-1:222222222222:key/bbbbbbbb-0000-0000-0000-000000000001"
    error_message = "The region copies' vault is not encrypted under the recovery region's region-copies key."
  }

  assert {
    condition     = aws_backup_vault.region_copies.kms_key_arn != aws_kms_key.this["field_keys"].arn
    error_message = "The region copies and the rings' replicas share one key."
  }

  assert {
    condition     = split(":", output.database_region_copy_vault_arn)[3] == "eu-west-1" && split(":", output.database_region_copy_vault_arn)[3] != var.primary_region
    error_message = "The copy's destination is not outside production's region."
  }

  assert {
    condition     = aws_backup_vault.region_copies.name == "finapp-production-database-region-copies"
    error_message = "The vault's name does not start with the conventions' prefix."
  }
}

run "the_vault_grants_nothing_and_refuses_a_copy_from_another_account" {
  command = plan

  assert {
    condition     = alltrue([for statement in jsondecode(aws_backup_vault_policy.region_copies.policy).Statement : statement.Effect == "Deny"])
    error_message = "The region copies' vault policy allows something: it may only refuse."
  }

  assert {
    condition = (
      one(jsondecode(aws_backup_vault_policy.region_copies.policy).Statement).Action == ["backup:CopyIntoBackupVault"]
      && one(jsondecode(aws_backup_vault_policy.region_copies.policy).Statement).Condition.StringNotEquals["aws:PrincipalAccount"] == "222222222222"
    )
    error_message = "The region copies' vault admits a copy written from another account."
  }
}

run "the_aliases_name_the_recovery_site" {
  command = plan

  assert {
    condition     = toset([for alias in aws_kms_alias.this : alias.name]) == toset(["alias/finapp-production-recovery-region-copies", "alias/finapp-production-recovery-field-keys"])
    error_message = "A recovery key's alias does not name the recovery site under the conventions' prefix."
  }
}

# The probe: the site in production's own region.
run "a_recovery_site_in_the_primary_region_is_refused" {
  command = plan

  variables {
    region = "eu-central-1"
  }

  expect_failures = [var.primary_region]
}

run "staging_keeps_no_recovery_region" {
  command = plan

  variables {
    name_prefix = "finapp-staging"
  }

  expect_failures = [var.name_prefix]
}
