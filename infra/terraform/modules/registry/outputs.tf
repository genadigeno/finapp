output "repository_urls" {
  description = "Each image's repository: where a promotion copies a digest to."
  value       = { for image, repository in aws_ecr_repository.this : image => repository.repository_url }
}

output "repository_names" {
  description = "Each image's repository name, read from the plan."
  value       = { for image, repository in aws_ecr_repository.this : image => repository.name }
}
