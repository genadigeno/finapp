variable "name_prefix" {
  description = "The start of every name in this module: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "region" {
  description = "The AWS region the database runs in. A backup copy must leave this region or this account for its key to apply."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "A region reads like eu-central-1."
  }
}

variable "account_id" {
  description = "The AWS account the database runs in."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "vpc_id" {
  description = "The VPC the database's security group belongs to: the network module's vpc_id."
  type        = string
}

variable "private_subnet_ids_by_zone" {
  description = "The workload subnets, by zone: the network module's private_subnet_ids_by_zone, the tier with no public address and no internet route."
  type        = map(string)

  validation {
    condition     = length(keys(var.private_subnet_ids_by_zone)) >= 3
    error_message = "The database's subnet group spans at least three zones, so that a standby always has a zone to fail over to."
  }
}

variable "client_security_group_id" {
  description = "The one security group admitted to the database's port: the cluster's nodes. The namespace's NetworkPolicy narrows that to the application's pods."
  type        = string
}

variable "instance_class" {
  description = "The instance class. A reference value, recalibrated by Phase 16's load model."
  type        = string

  validation {
    condition     = startswith(var.instance_class, "db.")
    error_message = "An RDS instance class starts with db."
  }
}

variable "allocated_storage_gib" {
  description = "The volume's initial size, in GiB. A reference value."
  type        = number

  validation {
    condition     = var.allocated_storage_gib >= 20
    error_message = "A gp3 volume starts at 20 GiB."
  }
}

variable "max_allocated_storage_gib" {
  description = "The ceiling of storage autoscaling, in GiB. A reference value."
  type        = number

  validation {
    condition     = var.max_allocated_storage_gib >= var.allocated_storage_gib
    error_message = "The autoscaling ceiling is at least the initial size."
  }
}

variable "max_connections" {
  description = "The server's max_connections: the number the application's overlay declares as FINAPP_DB_SERVER_MAX_CONNECTIONS, read from that file by the root. Static on the engine, so a change waits for a reboot."
  type        = number

  validation {
    condition     = var.max_connections == floor(var.max_connections) && var.max_connections >= 20 && var.max_connections <= 5000
    error_message = "max_connections is a whole number between 20, below which the server's own reserved slots leave no room, and 5000."
  }
}

variable "storage_kms_key_arn" {
  description = "The customer-managed KMS key encrypting the volume, its automated backups and Performance Insights. Created outside this module; a key's ARN, never an alias."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.storage_kms_key_arn))
    error_message = "The storage key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }
}

variable "backup_kms_key_arn" {
  description = "The customer-managed KMS key of the backup vault. Never the storage key. RDS's own backups stay under the storage key whatever this is: README.md, section 'The separate key'."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.backup_kms_key_arn))
    error_message = "The backup key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }

  validation {
    condition     = var.backup_kms_key_arn != var.storage_kms_key_arn
    error_message = "The backup key is the database's own key, so losing or leaking that one key would take the backups with it."
  }
}

variable "secrets_kms_key_arn" {
  description = "The customer-managed KMS key of the secret RDS generates for the master user. Created outside this module; a key's ARN, never an alias."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.secrets_kms_key_arn))
    error_message = "The secrets key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }
}

variable "backup_retention_days" {
  description = "How far back point-in-time recovery reaches, in days: 7 at the least, and 35 is the service's limit."
  type        = number

  validation {
    condition     = var.backup_retention_days == floor(var.backup_retention_days) && var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "Point-in-time recovery reaches back between 7 and 35 days."
  }
}

variable "backup_copy_vault_arn" {
  description = "The vault each daily snapshot is copied to, encrypted under that vault's key. It must be in another account or another region: within one account and region RDS's snapshots keep the database's key."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:backup:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:backup-vault:[A-Za-z0-9_-]+$", var.backup_copy_vault_arn))
    error_message = "The copy's destination is a backup vault's ARN: arn:aws:backup:<region>:<account>:backup-vault:<name>."
  }

  validation {
    condition     = !startswith(var.backup_copy_vault_arn, "arn:aws:backup:${var.region}:${var.account_id}:")
    error_message = "The copy's destination is in this account and this region, where RDS's snapshots keep the database's key: it would not be under a separate key."
  }
}

variable "snapshot_retention_days" {
  description = "How long the daily snapshot and its copy are kept, in days. Operational, a reference value; a duty's period is a jurisdiction's and is not set here."
  type        = number

  validation {
    condition     = var.snapshot_retention_days >= 7
    error_message = "A snapshot is kept at least a week, so the copy always has one to work from."
  }
}
