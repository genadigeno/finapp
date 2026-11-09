variable "name_prefix" {
  description = "The start of every bucket's name: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "account_id" {
  description = "The environment's account, in every bucket's name: a bucket name is global."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "kms_key_arn" {
  description = "The customer-managed key both buckets are encrypted under: the security module's logs key."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.kms_key_arn))
    error_message = "The key is a KMS key's ARN, never an alias."
  }
}

variable "log_retention_days" {
  description = "How long the log backend's objects are kept. A reference value, an operational period set per environment and confirmed against the backend."
  type        = number

  validation {
    condition     = var.log_retention_days >= 7
    error_message = "Logs are kept for at least a week: an incident's evidence must outlive its review."
  }
}

variable "trace_retention_days" {
  description = "How long the trace backend's objects are kept. A reference value, an operational period set per environment and confirmed against the backend."
  type        = number

  validation {
    condition     = var.trace_retention_days >= 7
    error_message = "Traces are kept for at least a week: an incident's evidence must outlive its review."
  }
}
