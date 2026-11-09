variable "region" {
  description = "The AWS region staging runs in, chosen with its account."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "A region reads like eu-central-1."
  }
}

variable "account_id" {
  description = "Staging's own AWS account, the only one this root may apply to."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "availability_zones" {
  description = "The zones staging spans, chosen with its account: at least three distinct zones of the region."
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

variable "database_backup_copy_vault_arn" {
  description = "The backup vault each daily database snapshot is copied to, under that vault's key: in another account or region. Chosen with the account (THREAT_MODEL.md section 6 item 23)."
  type        = string

  # The copy is shared the database's key, so it must not be another environment's to read.
  validation {
    condition     = !contains(var.other_environment_account_ids, try(split(":", var.database_backup_copy_vault_arn)[4], ""))
    error_message = "The backup copy is in another environment's account, which would be given use of this environment's database key."
  }
}

variable "other_environment_account_ids" {
  description = "The accounts of the platform's other environments, so that a backup copy can be refused one of them. No default: leaving one out would turn the refusal off."
  type        = set(string)

  validation {
    condition     = length(var.other_environment_account_ids) > 0 && alltrue([for id in var.other_environment_account_ids : can(regex("^[0-9]{12}$", id))])
    error_message = "Name at least the other environment's account: an AWS account id is twelve digits."
  }
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

variable "human_users" {
  description = "The people with access to staging, by IAM user name, and the roles each may assume (read-only, break-glass, bootstrap). No default: who holds break-glass is a decision, and an empty list locks everyone out."
  type = map(object({
    roles = set(string)
  }))
}

variable "bootstrap_role_enabled" {
  description = "Whether the role that reads what the one-time bootstrap step reads exists in staging. Off unless a reviewed change turns it on for that step, and off again after it."
  type        = bool
  default     = false
}
