output "repository_urls" {
  description = "Each image's repository: where a promotion copies a digest to."
  value       = { for image, repository in aws_ecr_repository.this : image => repository.repository_url }
}

output "repository_names" {
  description = "Each image's repository name, read from the plan."
  value       = { for image, repository in aws_ecr_repository.this : image => repository.name }
}

output "signature_repository_url" {
  description = "Where each digest's signature and attestations live: cosign's COSIGN_REPOSITORY, the admission policy's source.oci and promote.sh's PROMOTE_SIGNATURES."
  value       = aws_ecr_repository.signatures.repository_url
}

output "signature_repository_name" {
  description = "The signature repository's name, read from the plan."
  value       = aws_ecr_repository.signatures.name
}
