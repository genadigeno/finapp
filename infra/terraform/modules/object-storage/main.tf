# Buckets for infrastructure (PHASE_15_PLAN.md C3: object storage is infrastructure's, never the
# application's): the bucket each root's partial s3 backend names at init, and the two archives. Never
# applied: there is no account in Phase 15, so every property here is asserted by
# tests/object-storage.tftest.hcl over a plan against a mock provider, which proves this configuration and
# nothing about the service's behaviour.
#
# The security properties are literals, not variables, so that no environment can turn one off.

locals {
  buckets = {
    "terraform-state" = { kms_key_arn = var.state_kms_key_arn, archive = false }
    "audit-archive"   = { kms_key_arn = var.archive_kms_key_arn, archive = true }
    "log-archive"     = { kms_key_arn = var.archive_kms_key_arn, archive = true }
  }

  bucket_names = { for purpose, bucket in local.buckets : purpose => "${var.name_prefix}-${var.account_id}-${purpose}" }

  refuse_plain_transport = {
    Sid       = "RefusePlainTransport"
    Effect    = "Deny"
    Principal = "*"
    Action    = "s3:*"
    Condition = { Bool = { "aws:SecureTransport" = "false" } }
  }
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets

  bucket              = local.bucket_names[each.key]
  force_destroy       = false
  object_lock_enabled = each.key == "audit-archive"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = each.value.kms_key_arn
    }
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = local.buckets

  bucket                  = aws_s3_bucket.this[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [merge(local.refuse_plain_transport, { Resource = ["arn:aws:s3:::${local.bucket_names[each.key]}", "arn:aws:s3:::${local.bucket_names[each.key]}/*"] })],
      each.key == "audit-archive" ? [{
        Sid       = "RefuseDeletionByAnyone"
        Effect    = "Deny"
        Principal = "*"
        Action    = ["s3:DeleteObject", "s3:DeleteObjectVersion", "s3:BypassGovernanceRetention"]
        Resource  = "arn:aws:s3:::${local.bucket_names[each.key]}/*"
      }] : [],
    )
  })

  depends_on = [aws_s3_bucket_public_access_block.this]
}

# Only the audit archive has this. Object Lock is decided when the bucket is created, which is why
# object_lock_enabled sits on the bucket above and cannot be added later; the period is the audit class's,
# OPEN until a real jurisdiction is chosen, so it is an input with no default.
resource "aws_s3_bucket_object_lock_configuration" "audit_archive" {
  bucket = aws_s3_bucket.this["audit-archive"].id

  rule {
    default_retention {
      mode = var.audit_archive_lock.mode
      days = var.audit_archive_lock.days
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# Colder storage for what is old, and the cleanup of an upload that never finished. No expiration of any
# kind: the audit class is never deleted (DATA_RETENTION.md section 1), and a logs period, where one is
# wanted, is the observability stack's.
resource "aws_s3_bucket_lifecycle_configuration" "archives" {
  for_each = { for purpose, bucket in local.buckets : purpose => bucket if bucket.archive }

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    id     = "colder-storage-never-expiry"
    status = "Enabled"

    filter {}

    transition {
      days          = var.archive_transition_days
      storage_class = "GLACIER_IR"
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}
