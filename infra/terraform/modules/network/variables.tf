variable "name_prefix" {
  description = "The start of every name in this module: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "region" {
  description = "The AWS region the network is built in. The VPC endpoints' service names are derived from it."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "A region reads like eu-central-1."
  }
}

variable "account_id" {
  description = "The AWS account the network is built in. The flow-log role's trust is bound to it."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "availability_zones" {
  description = "The zones the network spans: at least three distinct zones of the region, so that a zone topology spread can hold."
  type        = list(string)

  validation {
    condition     = length(distinct(var.availability_zones)) >= 3 && length(distinct(var.availability_zones)) == length(var.availability_zones)
    error_message = "The network spans at least three zones, each named once."
  }

  validation {
    condition     = length(var.availability_zones) <= 6
    error_message = "The network's addressing plan has room for at most six zones."
  }

  validation {
    condition     = alltrue([for zone in var.availability_zones : can(regex("^${var.region}[a-z]$", zone))])
    error_message = "Every zone is a zone of the region: the region's name followed by one letter."
  }
}

variable "cidr_block" {
  description = "The VPC's address range: a private /16, carved into a /20 per zone for workloads and a /24 per zone for the edge tier."
  type        = string

  validation {
    condition     = can(cidrhost(var.cidr_block, 0)) && endswith(var.cidr_block, "/16") && can(regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)", var.cidr_block))
    error_message = "The VPC's range is a private (RFC 1918) /16."
  }
}

variable "flow_log_retention_days" {
  description = "How long the VPC flow logs are kept: operational data, a reference value, one of the periods CloudWatch Logs accepts."
  type        = number

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.flow_log_retention_days)
    error_message = "The flow logs expire after one of the periods CloudWatch Logs accepts."
  }
}
