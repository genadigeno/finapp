variable "name_prefix" {
  description = "The start of every name in this module: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "region" {
  description = "The environment's AWS region, named in every service condition and every entry's ARN."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "A region reads like eu-central-1."
  }
}

variable "account_id" {
  description = "The environment's own AWS account."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "secret_inventory_path" {
  description = "The path of docs/architecture/SECRET_MANAGEMENT.md, read at plan. Which entries exist and which key each is under come from its tables and nowhere else."
  type        = string
}

variable "external_secret_manifest_paths" {
  description = "Each workload's ExternalSecret, by the name in its entries' path (app, migrator), read at plan: what the operator will ask the store for."
  type        = map(string)

  validation {
    condition     = toset(keys(var.external_secret_manifest_paths)) == toset(["app", "migrator"])
    error_message = "The workloads are app and migrator: one reader each."
  }
}

variable "secret_store_manifest_path" {
  description = "The environment overlay's secret-store.yaml, read at plan: the path each store reads under and the ServiceAccount that reads it."
  type        = string
}

variable "workload_namespace" {
  description = "The Kubernetes namespace the readers live in: the overlay's namespace."
  type        = string
}

variable "cluster_name" {
  description = "The cluster the access entries and the human roles name: the kubernetes module's cluster_name."
  type        = string
}

variable "cluster_role_arn" {
  description = "The control plane's role, the only principal the etcd key's policy names: the kubernetes module's cluster_role_arn."
  type        = string
}

variable "workload_identity_provider_arn" {
  description = "The OIDC provider each reader's role trusts: the kubernetes module's workload_identity_provider_arn."
  type        = string
}

variable "workload_identity_issuer" {
  description = "The cluster's OIDC issuer, whose host and path the trust conditions name: the kubernetes module's workload_identity_issuer."
  type        = string

  validation {
    condition     = startswith(var.workload_identity_issuer, "https://")
    error_message = "An OIDC issuer is an https URL."
  }
}

variable "master_user_secret_arn" {
  description = "The database's own master credential, which RDS names and keeps: the postgres module's master_user_secret_arn. Only the bootstrap role reads it, while that role exists, and no workload."
  type        = string
}

variable "database_backup_copy_vault_arn" {
  description = "The backup vault each daily snapshot is copied to, the same value the postgres module is given. Its account decides whether the database key is shared across accounts. That vault is not a resource of these roots (THREAT_MODEL.md section 6 item 23)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:backup:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:backup-vault:.+$", var.database_backup_copy_vault_arn))
    error_message = "The destination is a backup vault's ARN."
  }
}

variable "human_users" {
  description = "The people with access to this environment, by IAM user name, and the roles each may assume (read-only, break-glass, bootstrap). No default: an empty list locks everyone out, and who holds break-glass is a decision."
  type = map(object({
    roles = set(string)
  }))

  validation {
    condition     = alltrue([for name in keys(var.human_users) : can(regex("^[a-z][a-z0-9.-]{1,62}$", name))])
    error_message = "A person's user name is lower case letters, digits, dots and hyphens."
  }

  validation {
    condition     = alltrue([for user in values(var.human_users) : length(setsubtract(user.roles, ["read-only", "break-glass", "bootstrap"])) == 0])
    error_message = "A person's roles are read-only, break-glass and bootstrap, and nothing else exists."
  }

  # IAM refuses a trust policy naming no principal.
  validation {
    condition     = alltrue([for role in ["read-only", "break-glass"] : anytrue([for user in values(var.human_users) : contains(user.roles, role)])])
    error_message = "Each of read-only and break-glass needs at least one person who may assume it."
  }
}

variable "bootstrap_role_enabled" {
  description = "Whether the role that reads what the one-time bootstrap step reads exists. It has no access entry and is off unless a change turns it on: for that step, with a person given the bootstrap role in human_users, and off again after it."
  type        = bool
  default     = false

  # IAM refuses a trust policy naming no principal.
  validation {
    condition     = !var.bootstrap_role_enabled || anytrue([for user in values(var.human_users) : contains(user.roles, "bootstrap")])
    error_message = "The bootstrap role needs at least one person who may assume it."
  }
}
