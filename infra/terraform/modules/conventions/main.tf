# The naming and tagging conventions, defined once (P15-TSK-013, ADR-0121).
#
# Every root module calls this module and hands its tags to the AWS provider's default_tags, so every
# taggable resource any module creates carries them without the module repeating them. Every cloud
# resource's name starts with name_prefix: "finapp-<environment>", lower case, hyphenated.
#
# Enforced: tests/conventions.tftest.hcl asserts the prefix and the exact tag set, and refuses an
# environment that is not staging or production; each root's own test asserts that the root names and
# tags itself as its environment; TerraformIsPinnedAndCheckedTest asserts that every root takes its
# default_tags from here. Each concern module's tests assert name_prefix on the resources they name;
# a new resource's use of it is a review point.

locals {
  name_prefix = "finapp-${var.environment}"

  tags = {
    Project     = "finapp"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}
