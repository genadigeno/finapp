variable "name_prefix" {
  description = "The start of every name in this module: the conventions module's name_prefix. Only production keeps a recovery region (ADR-0129, ADR-0150)."
  type        = string

  validation {
    condition     = var.name_prefix == "finapp-production"
    error_message = "Only production keeps a recovery region: the name prefix is finapp-production."
  }
}

variable "region" {
  description = "The recovery region: where the standing site's keys and the database's region copies live, before any disaster."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "A region reads like eu-west-1."
  }
}

variable "primary_region" {
  description = "The region production runs in, whose loss this site exists to outlive."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.primary_region))
    error_message = "A region reads like eu-central-1."
  }

  # The probe of ADR-0150: a recovery site in the primary's own region is lost with it.
  validation {
    condition     = var.primary_region != var.region
    error_message = "The recovery region is production's own region, so the site would be lost with the region it is meant to outlive."
  }
}

variable "account_id" {
  description = "The account the standing site lives in: production's own (ADR-0150), named so that every key policy admits it and nobody else."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}
