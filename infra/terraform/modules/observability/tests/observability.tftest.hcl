# The telemetry buckets, asserted over a plan against a mock AWS provider. A mock provider holds no credential and calls no
# API, so this proves the configuration and nothing about the service. The periods below are test inputs.

mock_provider "aws" {
  override_during = plan
}

variables {
  name_prefix          = "finapp-staging"
  account_id           = "111111111111"
  kms_key_arn          = "arn:aws:kms:eu-central-1:111111111111:key/11111111-1111-1111-1111-111111111111"
  log_retention_days   = 14
  trace_retention_days = 7
}

run "there_are_a_log_and_a_trace_bucket_named_for_this_account" {
  command = plan

  assert {
    condition     = output.bucket_names == { logs = "finapp-staging-111111111111-logs", traces = "finapp-staging-111111111111-traces" }
    error_message = "The buckets are not the log and trace buckets, named from the conventions' prefix and the account."
  }

  assert {
    condition     = alltrue([for bucket in aws_s3_bucket.this : bucket.force_destroy == false])
    error_message = "A destroy would take a bucket's objects."
  }
}

run "every_bucket_is_private_and_encrypted_under_the_given_key" {
  command = plan

  assert {
    condition = length(aws_s3_bucket_public_access_block.this) == 2 && alltrue([
      for block in aws_s3_bucket_public_access_block.this :
      block.block_public_acls == true && block.block_public_policy == true && block.ignore_public_acls == true && block.restrict_public_buckets == true
    ])
    error_message = "A bucket admits public access: one of the four public access blocks is off."
  }

  assert {
    condition = alltrue([
      for encryption in aws_s3_bucket_server_side_encryption_configuration.this :
      one(encryption.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "aws:kms" && one(encryption.rule).apply_server_side_encryption_by_default[0].kms_master_key_id == var.kms_key_arn
    ])
    error_message = "A bucket is not encrypted under the key this module is given."
  }

  assert {
    condition     = alltrue([for controls in aws_s3_bucket_ownership_controls.this : controls.rule[0].object_ownership == "BucketOwnerEnforced"])
    error_message = "A bucket still honours object ACLs."
  }

  assert {
    condition     = alltrue([for policy in aws_s3_bucket_policy.this : jsondecode(policy.policy).Statement[0].Effect == "Deny" && jsondecode(policy.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"])
    error_message = "A bucket accepts plain transport."
  }
}

run "each_signal_expires_on_its_own_period" {
  command = plan

  assert {
    condition     = one(aws_s3_bucket_lifecycle_configuration.this["logs"].rule).expiration[0].days == var.log_retention_days && one(aws_s3_bucket_lifecycle_configuration.this["traces"].rule).expiration[0].days == var.trace_retention_days
    error_message = "A bucket does not expire on the period given for its signal."
  }

  assert {
    condition     = alltrue([for lifecycle in aws_s3_bucket_lifecycle_configuration.this : one(lifecycle.rule).noncurrent_version_expiration[0].noncurrent_days == 7 && one(lifecycle.rule).status == "Enabled"])
    error_message = "An old version outlives its period by more than a week."
  }
}

run "a_period_under_a_week_is_refused" {
  command = plan

  variables {
    log_retention_days = 3
  }

  expect_failures = [var.log_retention_days]
}

run "an_alias_is_refused_for_the_key" {
  command = plan

  variables {
    kms_key_arn = "alias/logs"
  }

  expect_failures = [var.kms_key_arn]
}
