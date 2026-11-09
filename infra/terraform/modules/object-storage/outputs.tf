output "state_bucket_name" {
  description = "The bucket to name in this root's s3 backend at init."
  value       = aws_s3_bucket.this["terraform-state"].bucket
}

output "audit_archive_bucket_name" {
  description = "The write-once audit archive."
  value       = aws_s3_bucket.this["audit-archive"].bucket
}

output "log_archive_bucket_name" {
  description = "The log archive."
  value       = aws_s3_bucket.this["log-archive"].bucket
}

output "audit_archive_lock" {
  description = "The audit archive's default write-once rule, read from the plan."
  value = {
    mode = one(aws_s3_bucket_object_lock_configuration.audit_archive.rule).default_retention[0].mode
    days = one(aws_s3_bucket_object_lock_configuration.audit_archive.rule).default_retention[0].days
  }
}

output "encryption_key_arns" {
  description = "Each bucket's key, by purpose, read from the plan."
  value = {
    for purpose, encryption in aws_s3_bucket_server_side_encryption_configuration.this :
    purpose => one(flatten([for rule in encryption.rule : [for default in rule.apply_server_side_encryption_by_default : default.kms_master_key_id]]))
  }
}
