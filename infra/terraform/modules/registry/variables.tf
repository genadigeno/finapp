variable "name_prefix" {
  description = "The start of every repository's name: the conventions module's name_prefix."
  type        = string

  validation {
    condition     = can(regex("^finapp-(staging|production)$", var.name_prefix))
    error_message = "The name prefix is the conventions module's: finapp-staging or finapp-production."
  }
}

variable "images" {
  description = "The images this environment runs, read from its overlay: one repository each."
  type        = set(string)

  validation {
    condition     = length(var.images) > 0
    error_message = "A registry with no repository holds nothing to deploy."
  }

  validation {
    condition     = alltrue([for image in var.images : can(regex("^finapp-[a-z]+$", image))])
    error_message = "An image is named finapp-<name>."
  }
}
