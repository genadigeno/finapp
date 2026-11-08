variable "name_prefix" {
  description = "The cluster's name and the start of every other name in this module: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "tags" {
  description = "The conventions module's tags. The provider's default_tags do not reach the instances and volumes a launch template creates, so the template carries them."
  type        = map(string)
}

variable "kubernetes_version" {
  description = "The control plane's Kubernetes minor version, for example 1.34."
  type        = string

  validation {
    condition     = can(regex("^1\\.[0-9]{2}$", var.kubernetes_version))
    error_message = "A Kubernetes minor version reads like 1.34."
  }
}

variable "private_subnet_ids_by_zone" {
  description = "The workload subnets, by zone: the network module's private_subnet_ids_by_zone. At least three zones, so that a zone topology spread can hold."
  type        = map(string)

  validation {
    condition     = length(keys(var.private_subnet_ids_by_zone)) >= 3
    error_message = "The cluster spans at least three zones."
  }
}

variable "etcd_kms_key_arn" {
  description = "The customer-managed KMS key that envelope-encrypts Kubernetes Secrets in etcd. Created outside this module; a key's ARN, never an alias."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.etcd_kms_key_arn))
    error_message = "The etcd key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }
}

variable "node_instance_types" {
  description = "The node instance types. A reference value, recalibrated by Phase 16's load model."
  type        = list(string)

  validation {
    condition     = length(var.node_instance_types) >= 1
    error_message = "A node group needs at least one instance type."
  }
}

variable "node_root_volume_gib" {
  description = "Each node's encrypted root volume, in GiB."
  type        = number

  validation {
    condition     = var.node_root_volume_gib >= 20
    error_message = "A node's root volume is at least 20 GiB."
  }
}

variable "nodes_per_zone" {
  description = "The size of each zone's node group. Every zone keeps at least one node, so a zone topology spread always has capacity in every zone."
  type = object({
    min     = number
    desired = number
    max     = number
  })

  validation {
    condition     = var.nodes_per_zone.min >= 1 && var.nodes_per_zone.min <= var.nodes_per_zone.desired && var.nodes_per_zone.desired <= var.nodes_per_zone.max
    error_message = "Each zone keeps at least one node, and min <= desired <= max."
  }
}

variable "control_plane_log_retention_days" {
  description = "How long the control plane's logs, its audit log among them, are kept. 0 keeps them with no expiry until a jurisdiction sets a period (PHASE_15_PLAN.md C12); otherwise at least a year."
  type        = number

  validation {
    condition     = contains([0, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.control_plane_log_retention_days)
    error_message = "The control plane's logs are kept for at least a year, or without expiry (0), in one of the periods CloudWatch Logs accepts."
  }
}
