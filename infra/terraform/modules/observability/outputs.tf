output "encryption_key_arn" {
  description = "The one key both buckets are under, read from the plan."
  value = one(distinct(flatten([
    for encryption in aws_s3_bucket_server_side_encryption_configuration.this :
    [for rule in encryption.rule : [for default in rule.apply_server_side_encryption_by_default : default.kms_master_key_id]]
  ])))
}

output "bucket_names" {
  description = "Each telemetry bucket, by signal."
  value       = { for signal, bucket in aws_s3_bucket.this : signal => bucket.bucket }
}
