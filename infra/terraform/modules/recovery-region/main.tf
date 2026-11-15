# The standing recovery site in a second region (P16-TSK-026, ADR-0150; ADR-0129 decision 5: "backups and key material
# are made available in the recovery region before they are needed").
#
# What it holds, and nothing more:
#   - the vault the database's daily snapshot is copied to from production's region (the postgres module's
#     backup_region_copy_vault_arn), under a key of this region, so a region's loss leaves a recovery point (ADR-0128 tier 4);
#   - the key the field-level key rings' replicas are wrapped under here (the security module's recovery_region), so every
#     ring version stays readable when production's region is gone.
#
# No key is multi-region: a multi-region key shares one key's material across regions, so a compromise or a mistake in one
# reaches the other. Each store that crosses re-wraps under this region's own key instead: AWS Backup's cross-region copy takes
# the destination vault's key, and Secrets Manager's replica takes the key named for its region.
#
# Nothing here takes a write from the application and nothing here can promote anything. The data tier, the cluster and the
# fleet in this region are built only in a declared regional recovery, after production's region is fenced
# (OPERATIONS_RUNBOOK.md section 11.65); how that rebuild is planned in the same account is THREAT_MODEL.md section 6 item 72.

locals {
  # The same administration the security module gives the account on production's keys: the account administers each key,
  # keeps PutKeyPolicy so a wrong policy can be repaired, and uses no key except through a service's own statement.
  key_administration = [
    "kms:CancelKeyDeletion",
    "kms:CreateAlias",
    "kms:DeleteAlias",
    "kms:DescribeKey",
    "kms:DisableKey",
    "kms:DisableKeyRotation",
    "kms:EnableKey",
    "kms:EnableKeyRotation",
    "kms:GetKeyPolicy",
    "kms:GetKeyRotationStatus",
    "kms:ListAliases",
    "kms:ListGrants",
    "kms:ListKeyPolicies",
    "kms:ListKeyRotations",
    "kms:ListResourceTags",
    "kms:PutKeyPolicy",
    "kms:RevokeGrant",
    "kms:RotateKeyOnDemand",
    "kms:ScheduleKeyDeletion",
    "kms:TagResource",
    "kms:UntagResource",
    "kms:UpdateAlias",
    "kms:UpdateKeyDescription",
  ]

  key_use_through_a_service = [
    "kms:CreateGrant",
    "kms:Decrypt",
    "kms:DescribeKey",
    "kms:Encrypt",
    "kms:GenerateDataKey",
    "kms:GenerateDataKeyWithoutPlaintext",
    "kms:ListGrants",
    "kms:ReEncryptFrom",
    "kms:ReEncryptTo",
  ]

  # Each purpose's services, in this region alone. The region copies are written by AWS Backup and restored by RDS; the rings'
  # replicas are written and read through Secrets Manager.
  key_purposes = {
    region_copies = { description = "the database's daily snapshots copied from production's region", services = ["backup", "rds"] }
    field_keys    = { description = "the replicas of the field-level encryption keys' entries", services = ["secretsmanager"] }
  }

  account_root = "arn:aws:iam::${var.account_id}:root"

  key_policies = {
    for purpose, key in local.key_purposes : purpose => {
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "TheAccountAdministersTheKeyAndNeverUsesIt"
          Effect    = "Allow"
          Principal = { AWS = local.account_root }
          Action    = local.key_administration
          Resource  = "*"
        },
        {
          Sid       = "UseThroughItsServiceInTheRecoveryRegionAlone"
          Effect    = "Allow"
          Principal = { AWS = local.account_root }
          Action    = local.key_use_through_a_service
          Resource  = "*"
          Condition = { StringEquals = { "kms:ViaService" = [for service in key.services : "${service}.${var.region}.amazonaws.com"] } }
        },
      ]
    }
  }

  # Each resource says it is the recovery site and which region it stands in for.
  site_tags = { Site = "recovery-region", RecoversRegion = var.primary_region }
}

resource "aws_kms_key" "this" {
  for_each = local.key_purposes

  description                        = "finapp production, recovery region: ${each.value.description}."
  enable_key_rotation                = true
  deletion_window_in_days            = 30
  multi_region                       = false
  bypass_policy_lockout_safety_check = false
  policy                             = jsonencode(local.key_policies[each.key])
  tags                               = local.site_tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_kms_alias" "this" {
  for_each = local.key_purposes

  name          = "alias/${var.name_prefix}-recovery-${replace(each.key, "_", "-")}"
  target_key_id = aws_kms_key.this[each.key].key_id
}

# The region copies' vault. A copy crossing a region takes this vault's key, so the copy is under a key apart from the
# database's storage key and from production's own backup vault key.
resource "aws_backup_vault" "region_copies" {
  name        = "${var.name_prefix}-database-region-copies"
  kms_key_arn = aws_kms_key.this["region_copies"].arn
  tags        = local.site_tags

  lifecycle {
    prevent_destroy = true
  }
}

# The vault admits copies from this account alone: no other account, whatever its own policies say, can write a recovery
# point here that a regional recovery might restore. It grants nothing, so it opens nothing.
resource "aws_backup_vault_policy" "region_copies" {
  backup_vault_name = aws_backup_vault.region_copies.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "CopiesFromThisAccountAlone"
      Effect    = "Deny"
      Principal = { AWS = "*" }
      Action    = ["backup:CopyIntoBackupVault"]
      Resource  = "*"
      Condition = { StringNotEquals = { "aws:PrincipalAccount" = var.account_id } }
    }]
  })
}
