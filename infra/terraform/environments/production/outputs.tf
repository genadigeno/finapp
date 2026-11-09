output "name_prefix" {
  description = "The start of every production resource's name."
  value       = module.conventions.name_prefix
}

output "tags" {
  description = "The tags every production resource carries."
  value       = module.conventions.tags
}

output "vpc_id" {
  description = "Production's VPC."
  value       = module.network.vpc_id
}

output "cluster_name" {
  description = "Production's cluster."
  value       = module.kubernetes.cluster_name
}

output "state_bucket_name" {
  description = "The bucket to name in this root's s3 backend at init, once it exists."
  value       = module.object_storage.state_bucket_name
}

output "kafka_bootstrap_brokers" {
  description = "Production's brokers, over SASL_SSL: for the application's secret, never for a file in this repository."
  value       = module.kafka.bootstrap_brokers_sasl_scram
}
