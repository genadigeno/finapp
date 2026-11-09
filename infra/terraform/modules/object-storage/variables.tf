variable "name_prefix" {
  description = "The start of every name in this module: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "account_id" {
  description = "The AWS account the buckets are built in. A bucket name is global, so each name carries the account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "state_kms_key_arn" {
  description = "The customer-managed KMS key of the Terraform state bucket. Created outside this module; a key's ARN, never an alias."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.state_kms_key_arn))
    error_message = "The state key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }
}

variable "archive_kms_key_arn" {
  description = "The customer-managed KMS key of the archive buckets. Created outside this module; a key's ARN, never an alias. Separate from the database's: each root refuses them equal."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.archive_kms_key_arn))
    error_message = "The archive key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }
}

variable "audit_archive_lock" {
  description = "The audit archive's default write-once rule: S3's retention mode and a period in days. Required, with no default: the period is OPEN until a real jurisdiction is chosen (DATA_RETENTION.md section 3) and nothing here invents one."
  type = object({
    mode = string
    days = number
  })

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.audit_archive_lock.mode)
    error_message = "The mode is one of the two S3 defines: GOVERNANCE or COMPLIANCE."
  }

  validation {
    condition     = var.audit_archive_lock.days == floor(var.audit_archive_lock.days) && var.audit_archive_lock.days >= 1
    error_message = "The period is a whole number of days, at least one."
  }
}

variable "archive_transition_days" {
  description = "How old an archived object is when it moves to colder storage, in days. A cost setting, a reference value; it is not a retention period and nothing is ever expired by it."
  type        = number

  validation {
    condition     = var.archive_transition_days >= 30
    error_message = "An object moves to colder storage after at least 30 days."
  }
}
