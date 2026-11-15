output "region" {
  description = "The recovery region, for production's recovery_region input."
  value       = var.region
}

output "database_region_copy_vault_arn" {
  description = "The vault the database's daily snapshot is copied to from production's region: production's recovery_region input, the postgres module's backup_region_copy_vault_arn."
  value       = aws_backup_vault.region_copies.arn
}

output "field_keys_kms_key_arn" {
  description = "The key the rings' replicas are wrapped under in this region: production's recovery_region input, the security module's."
  value       = aws_kms_key.this["field_keys"].arn
}

output "kms_key_arns" {
  description = "Each key of the standing site, by purpose."
  value       = { for purpose, key in aws_kms_key.this : purpose => key.arn }
}

output "vault_kms_key_arn" {
  description = "The region copies' vault key, read from the planned vault."
  value       = aws_backup_vault.region_copies.kms_key_arn
}
