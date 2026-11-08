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
