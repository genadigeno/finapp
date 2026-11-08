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
