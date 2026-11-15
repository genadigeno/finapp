output "recovery_region" {
  description = "Production's recovery_region input, whole: the region, the vault the daily snapshot is copied to, and the key the rings' replicas are wrapped under."
  value = {
    region                  = module.recovery_region.region
    database_copy_vault_arn = module.recovery_region.database_region_copy_vault_arn
    field_keys_kms_key_arn  = module.recovery_region.field_keys_kms_key_arn
  }
}

output "tags" {
  description = "The tags every resource here carries: production's."
  value       = module.conventions.tags
}

output "residency_regions" {
  description = "The residency rule this site was planned under: production's root must be given the same set (OPERATIONS_RUNBOOK.md section 11.65)."
  value       = var.residency_regions
}
