variable "environment" {
  description = "The environment a root module builds. Staging and production only: local and ci run no Terraform (ADR-0121, C8)."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "The environment is staging or production."
  }
}
