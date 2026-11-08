variable "region" {
  description = "The AWS region production runs in, chosen with its account."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "A region reads like eu-central-1."
  }
}

variable "account_id" {
  description = "Production's own AWS account, the only one this root may apply to."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "availability_zones" {
  description = "The zones production spans, chosen with its account: at least three distinct zones of the region."
  type        = list(string)

  validation {
    condition     = length(distinct(var.availability_zones)) >= 3 && length(distinct(var.availability_zones)) == length(var.availability_zones)
    error_message = "An environment spans at least three zones, each named once."
  }

  validation {
    condition     = alltrue([for zone in var.availability_zones : can(regex("^${var.region}[a-z]$", zone))])
    error_message = "Every zone is a zone of the region."
  }
}

variable "etcd_kms_key_arn" {
  description = "The customer-managed KMS key that encrypts Kubernetes Secrets in etcd: a key of this account and region."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:${var.region}:${var.account_id}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.etcd_kms_key_arn))
    error_message = "The etcd key is a KMS key's ARN in this account and region, never an alias or another account's key."
  }
}
