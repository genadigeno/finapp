# The image registry (PHASE_15_PLAN.md C7, section 7). Never applied: there is no account in Phase 15, so every
# property here is asserted by tests/registry.tftest.hcl over a plan against a mock provider, which proves this
# configuration and nothing about the service's behaviour.
#
# A deployment names an image by digest. Immutable tags are what stop a tag from meaning two images, so a digest's
# provenance can be read back from the tag it was pushed under. These are literals, not variables, so that no
# environment can turn them off. Each environment's account has its own registry: production and non-production live
# in separate accounts (C8), and a promotion copies a digest between them.
#
# Who may push is not here. Nothing has a push credential until the delivery path exists (no CI credential reaches
# production), and the nodes pull through their own role.

locals {
  # Only untagged images ever expire. A release is pushed under a tag, so a release, and the rollback target it
  # becomes, cannot be expired by a count or an age here.
  expire_untagged_after_days = 30
}

resource "aws_ecr_repository" "this" {
  for_each = var.images

  name                 = "${var.name_prefix}-${trimprefix(each.key, "finapp-")}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = var.images

  repository = aws_ecr_repository.this[each.key].name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "An untagged image is a push that was never a release."
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = local.expire_untagged_after_days
      }
      action = { type = "expire" }
    }]
  })
}
