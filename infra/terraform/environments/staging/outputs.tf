output "name_prefix" {
  description = "The start of every staging resource's name."
  value       = module.conventions.name_prefix
}

output "tags" {
  description = "The tags every staging resource carries."
  value       = module.conventions.tags
}
