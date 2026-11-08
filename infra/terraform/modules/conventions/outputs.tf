output "name_prefix" {
  description = "The start of every cloud resource's name in this environment: finapp-<environment>."
  value       = local.name_prefix
}

output "tags" {
  description = "The tags every resource carries, through the AWS provider's default_tags."
  value       = local.tags
}
