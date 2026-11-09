variable "name_prefix" {
  description = "The start of every name in this module: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "vpc_id" {
  description = "The VPC the brokers' security group belongs to: the network module's vpc_id."
  type        = string
}

variable "private_subnet_ids_by_zone" {
  description = "The workload subnets, by zone: the network module's private_subnet_ids_by_zone. Brokers go in three of them, the first three zones by name."
  type        = map(string)

  validation {
    condition     = length(keys(var.private_subnet_ids_by_zone)) >= 3
    error_message = "The brokers span three zones."
  }
}

variable "client_security_group_id" {
  description = "The one security group admitted to the brokers' SASL_SSL port: the cluster's nodes."
  type        = string
}

variable "topic_catalogue_path" {
  description = "The path of infra/kafka/topics.yaml, read at plan. Topics, their configuration and the ACLs come from that file and from nowhere else."
  type        = string
}

variable "broker_properties_path" {
  description = "The path of infra/kafka/broker.properties, read at plan. The brokers' posture comes from that file."
  type        = string
}

variable "broker_instance_type" {
  description = "The broker instance type. A reference value, recalibrated by Phase 16's load model."
  type        = string

  validation {
    condition     = startswith(var.broker_instance_type, "kafka.")
    error_message = "An MSK broker instance type starts with kafka."
  }
}

variable "broker_volume_gib" {
  description = "Each broker's volume, in GiB. A reference value."
  type        = number

  validation {
    condition     = var.broker_volume_gib >= 100
    error_message = "A broker's volume is at least 100 GiB: seven days of every topic at three copies."
  }
}

variable "broker_log_retention_days" {
  description = "How long the brokers' own logs are kept: operational data, a reference value, one of the periods CloudWatch Logs accepts."
  type        = number

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.broker_log_retention_days)
    error_message = "The brokers' logs expire after one of the periods CloudWatch Logs accepts."
  }
}

variable "at_rest_kms_key_arn" {
  description = "The customer-managed KMS key encrypting the brokers' volumes. Created outside this module; a key's ARN, never an alias."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.at_rest_kms_key_arn))
    error_message = "The brokers' key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }
}

variable "scram_kms_key_arn" {
  description = "The customer-managed KMS key of the SCRAM secrets. MSK refuses a secret under Secrets Manager's default key. Created outside this module; a key's ARN, never an alias."
  type        = string

  validation {
    condition     = can(regex("^arn:aws:kms:[a-z]{2}(-[a-z]+)+-[0-9]:[0-9]{12}:key/([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}|mrk-[0-9a-f]{32})$", var.scram_kms_key_arn))
    error_message = "The SCRAM secrets' key is a KMS key's ARN: arn:aws:kms:<region>:<account>:key/<id>. An alias can be repointed, so it is refused."
  }
}

variable "provisioning_stage" {
  description = "How far this cluster has been brought up, one step per apply: brokers, then principals, then enforced. Only enforced has the catalogue's whole posture. No default, because every other value is an unfinished cluster and must be chosen on purpose: README.md."
  type        = string

  validation {
    condition     = contains(["brokers", "principals", "enforced"], var.provisioning_stage)
    error_message = "The stage is brokers, principals or enforced."
  }
}
