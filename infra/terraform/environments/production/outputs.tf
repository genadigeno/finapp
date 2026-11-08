output "name_prefix" {
  description = "The start of every production resource's name."
  value       = module.conventions.name_prefix
}

output "tags" {
  description = "The tags every production resource carries."
  value       = module.conventions.tags
}
