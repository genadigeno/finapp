output "name_prefix" {
  description = "The start of every staging resource's name."
  value       = module.conventions.name_prefix
}

output "tags" {
  description = "The tags every staging resource carries."
  value       = module.conventions.tags
}

output "vpc_id" {
  description = "Staging's VPC."
  value       = module.network.vpc_id
}

output "cluster_name" {
  description = "Staging's cluster."
  value       = module.kubernetes.cluster_name
}

output "state_bucket_name" {
  description = "The bucket to name in this root's s3 backend at init, once it exists."
  value       = module.object_storage.state_bucket_name
}

output "kafka_bootstrap_brokers" {
  description = "Staging's brokers, over SASL_SSL: for the application's secret, never for a file in this repository."
  value       = module.kafka.bootstrap_brokers_sasl_scram
}

output "secret_reader_role_arns" {
  description = "The role of each reader ServiceAccount: what the environment's delivery puts on its annotation, never a file in this repository."
  value       = module.security.secret_reader_role_arns
}

output "secret_entry_names" {
  description = "Every entry to fill once, by name (OPERATIONS_RUNBOOK.md section 11.7)."
  value       = module.security.secret_entry_names
}

output "repository_urls" {
  description = "Where staging's images are copied to, by image."
  value       = module.registry.repository_urls
}

output "signature_repository_url" {
  description = "Where staging's signatures and attestations are copied to with their digests: PROMOTE_SIGNATURES, and the admission policy's source.oci."
  value       = module.registry.signature_repository_url
}

output "database_endpoint" {
  description = "Staging's database address, for the store's URL entries at first fill and the bootstrap step: never for a file in this repository."
  value       = module.postgres.endpoint
}
