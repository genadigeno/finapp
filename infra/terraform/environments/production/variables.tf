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

variable "data_tier_kms_key_arns" {
  description = "The customer-managed KMS keys of the data tier, by purpose: keys of this account and region, supplied until the security module creates them."
  type = object({
    database        = string
    database_backup = string
    secrets         = string
    brokers         = string
    state           = string
    archive         = string
  })

  validation {
    condition     = alltrue([for arn in values(var.data_tier_kms_key_arns) : can(regex("^arn:aws:kms:${var.region}:${var.account_id}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", arn))])
    error_message = "Each data-tier key is a KMS key's ARN in this account and region, never an alias or another account's key."
  }

  validation {
    condition     = var.data_tier_kms_key_arns.database_backup != var.data_tier_kms_key_arns.database && var.data_tier_kms_key_arns.archive != var.data_tier_kms_key_arns.database
    error_message = "The backup vault's key and the archive's key are separate from the database's."
  }
}

variable "database_backup_copy_vault_arn" {
  description = "The backup vault each daily database snapshot is copied to, under that vault's key: in another account or region. Chosen with the account (THREAT_MODEL.md section 6 item 23)."
  type        = string
}

variable "audit_archive_lock" {
  description = "The audit archive's write-once mode and period. No default: the period is OPEN until a real jurisdiction is chosen (DATA_RETENTION.md section 3)."
  type = object({
    mode = string
    days = number
  })
}

variable "kafka_provisioning_stage" {
  description = "How far the brokers have been brought up: brokers, principals or enforced, one step per apply (modules/kafka/README.md). Only enforced has the catalogue's whole posture."
  type        = string
}
