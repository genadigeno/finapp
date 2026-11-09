# Telemetry's retention buckets (PHASE_15_PLAN.md section 7): where a log and a trace backend keep what they hold.
# Never applied: there is no account in Phase 15, so every property here is asserted by
# tests/observability.tftest.hcl over a plan against a mock provider, which proves this configuration and nothing about
# the service's behaviour.
#
# These are not the audit archive and no audit record belongs in them. modules/object-storage keeps what is never
# deleted; what is here expires, on each environment's period (DATA_RETENTION.md: operational, per environment).
#
# NO WORKLOAD ROLE READS OR WRITES THESE. The application has no use for a bucket, and a telemetry backend gets its own
# identity with its own workload when one is deployed.
#
# The alerting endpoints stay where they are: Alertmanager's placeholder receivers (infra/alertmanager/alertmanager.yml).
# A topic with nobody subscribed would read as paging.

locals {
  buckets = {
    logs   = { retention_days = var.log_retention_days }
    traces = { retention_days = var.trace_retention_days }
  }

  bucket_names = { for purpose, bucket in local.buckets : purpose => "${var.name_prefix}-${var.account_id}-${purpose}" }
}

resource "aws_s3_bucket" "this" {
  for_each = local.buckets

  bucket        = local.bucket_names[each.key]
  force_destroy = false
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
      kms_master_key_id = var.kms_key_arn
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
    Statement = [{
      Sid       = "RefusePlainTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = ["arn:aws:s3:::${local.bucket_names[each.key]}", "arn:aws:s3:::${local.bucket_names[each.key]}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.this]
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each = local.buckets

  bucket = aws_s3_bucket.this[each.key].id

  rule {
    id     = "expire-on-this-environments-period"
    status = "Enabled"

    filter {}

    expiration {
      days = each.value.retention_days
    }

    # A version is kept long enough to recover a mistaken delete, and no longer: the period above is the period.
    noncurrent_version_expiration {
      noncurrent_days = 7
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}
