output "identifier" {
  description = "The database instance's identifier."
  value       = aws_db_instance.this.identifier
}

output "max_connections" {
  description = "The server's max_connections, read from the planned parameter group."
  value       = one([for parameter in aws_db_parameter_group.this.parameter : parameter.value if parameter.name == "max_connections"])
}

output "multi_az" {
  description = "Whether a synchronous standby is planned, read from the planned instance."
  value       = aws_db_instance.this.multi_az
}

output "subnet_group_subnet_ids" {
  description = "The subnets of the database's subnet group, read from the plan."
  value       = aws_db_subnet_group.this.subnet_ids
}

output "client_security_group_id" {
  description = "The one security group admitted to the database's port, read from the planned rule."
  value       = aws_vpc_security_group_ingress_rule.from_clients.referenced_security_group_id
}

output "storage_kms_key_arn" {
  description = "The key of the volume, read from the planned instance."
  value       = aws_db_instance.this.kms_key_id
}

output "backup_vault_kms_key_arn" {
  description = "The backup vault's key, read from the planned vault."
  value       = aws_backup_vault.database.kms_key_arn
}

output "master_user_secret_arn" {
  description = "The master credential's entry, which RDS names and keeps: only the security module's bootstrap role reads it, while that role exists."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "endpoint" {
  description = "The database's address, for the store's URL entries at first fill and the bootstrap step (OPERATIONS_RUNBOOK.md section 11.7)."
  value       = aws_db_instance.this.endpoint
}
