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

# NOTHING EXPIRES HERE YET. An untagged image is not safely a stray: a promotion that copies a digest with no tag would
# leave a running image, and the rollback target it becomes, untagged, and an expiry rule would remove it. An expiry
# rule comes back only once a promotion lands under an immutable tag and signature storage is decided.

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

# WHERE THE SIGNATURES LIVE (P15-TSK-007 decided, P15-TSK-017 built; THREAT_MODEL.md section 6 items 35 and 40): a repository of their
# own, written through cosign's COSIGN_REPOSITORY and read by the admission policy's source.oci and by infra/scripts/promote.sh. cosign
# adds a second signature or attestation to a digest by rewriting that digest's tag, sha256-<digest>.sig or .att, so those tags must
# be mutable, and only those: every other tag here is immutable, the exclusion being exactly cosign's form. No lifecycle rule: a
# signature that expired would refuse every pod of its digest, the rollback target's included.
#
# The pinned Trivy refuses any mutability but IMMUTABLE (AWS-0031, HIGH): its rule does not read the exclusion. Accepted on this one
# resource until the date below (item 40); past it the finding fails CI's iac job again. Who may push here can delete or replace a
# signature: not a forgery (each is verified against its digest), but every pod of that digest, running or kept for rollback, would
# then be refused. The pipeline's signing role alone writes it (item 35 (d)); that grant is a repository policy, and needs an account.
#trivy:ignore:AVD-AWS-0031:exp:2027-06-30
resource "aws_ecr_repository" "signatures" {
  name                 = "${var.name_prefix}-signatures"
  image_tag_mutability = "IMMUTABLE_WITH_EXCLUSION"
  force_delete         = false

  image_tag_mutability_exclusion_filter {
    filter      = "sha256-*"
    filter_type = "WILDCARD"
  }

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}
