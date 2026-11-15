variable "region" {
  description = "The recovery region, chosen with production's account: never production's own."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.region))
    error_message = "A region reads like eu-west-1."
  }
}

variable "primary_region" {
  description = "The region production runs in: production's region input, the same value."
  type        = string
}

variable "account_id" {
  description = "Production's own AWS account, where the standing site lives (ADR-0150), and the only one this root may apply to."
  type        = string

  validation {
    condition     = can(regex("^[0-9]{12}$", var.account_id))
    error_message = "An AWS account id is twelve digits."
  }
}

variable "residency_regions" {
  description = "The regions the residency rule admits for production's data, the same value production is given. No default: no jurisdiction is chosen (ROADMAP.md, Non-Goals), and a recovery region must satisfy the rule the primary does (ADR-0129, ADR-0150)."
  type        = set(string)

  validation {
    condition     = contains(var.residency_regions, var.primary_region) && contains(var.residency_regions, var.region)
    error_message = "The recovery region, or production's own, is outside the regions the residency rule admits: a recovery site must satisfy the rule the primary does."
  }
}
