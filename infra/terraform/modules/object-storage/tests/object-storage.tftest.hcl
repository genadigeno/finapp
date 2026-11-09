# The buckets' properties, asserted over a plan against a mock AWS provider. A mock provider holds no
# credential and calls no API; every assertion reads what the plan would create.
#
# The lock's mode and period below are test inputs and decide nothing: the audit class's period is OPEN
# until a real jurisdiction is chosen, and neither value appears in any root.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_s3_bucket.this["audit-archive"]
    values = { id = "audit-archive-mock" }
  }
}

variables {
  name_prefix             = "finapp-staging"
  account_id              = "111111111111"
  state_kms_key_arn       = "arn:aws:kms:eu-central-1:111111111111:key/11111111-1111-1111-1111-111111111111"
  archive_kms_key_arn     = "arn:aws:kms:eu-central-1:111111111111:key/22222222-2222-2222-2222-222222222222"
  audit_archive_lock      = { mode = "GOVERNANCE", days = 42 }
  archive_transition_days = 90
}

run "there_are_exactly_the_state_and_the_two_archive_buckets" {
  command = plan

  assert {
    condition     = toset(keys(aws_s3_bucket.this)) == toset(["terraform-state", "audit-archive", "log-archive"])
    error_message = "The buckets are not the state bucket and the two archives."
  }

  assert {
    condition     = alltrue([for bucket in aws_s3_bucket.this : startswith(bucket.bucket, "finapp-staging-111111111111-") && bucket.force_destroy == false])
    error_message = "A bucket's name does not start with the conventions' prefix and the account, or a destroy would take its objects."
  }
}

run "every_bucket_refuses_public_access" {
  command = plan

  assert {
    condition = length(aws_s3_bucket_public_access_block.this) == 3 && alltrue([
      for block in aws_s3_bucket_public_access_block.this :
      block.block_public_acls == true && block.block_public_policy == true && block.ignore_public_acls == true && block.restrict_public_buckets == true
    ])
    error_message = "A bucket admits public access: one of the four public access blocks is off."
  }

  assert {
    condition     = alltrue([for controls in aws_s3_bucket_ownership_controls.this : controls.rule[0].object_ownership == "BucketOwnerEnforced"])
    error_message = "A bucket still honours object ACLs."
  }
}

run "every_bucket_keeps_versions" {
  command = plan

  assert {
    condition     = length(aws_s3_bucket_versioning.this) == 3 && alltrue([for versioning in aws_s3_bucket_versioning.this : versioning.versioning_configuration[0].status == "Enabled"])
    error_message = "A bucket does not keep every version of an object."
  }
}

run "every_bucket_is_encrypted_under_the_key_given_for_it" {
  command = plan

  assert {
    condition     = alltrue([for encryption in aws_s3_bucket_server_side_encryption_configuration.this : one(encryption.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "aws:kms" && one(encryption.rule).bucket_key_enabled == true])
    error_message = "A bucket is not encrypted with KMS."
  }

  assert {
    condition     = output.encryption_key_arns["terraform-state"] == var.state_kms_key_arn && output.encryption_key_arns["audit-archive"] == var.archive_kms_key_arn && output.encryption_key_arns["log-archive"] == var.archive_kms_key_arn
    error_message = "A bucket is not under the key this module is given for it."
  }
}

run "every_bucket_refuses_plain_transport" {
  command = plan

  assert {
    condition = length(aws_s3_bucket_policy.this) == 3 && alltrue([
      for purpose, policy in aws_s3_bucket_policy.this :
      jsondecode(policy.policy).Statement[0].Effect == "Deny" && jsondecode(policy.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false" && contains(jsondecode(policy.policy).Statement[0].Resource, "arn:aws:s3:::finapp-staging-111111111111-${purpose}/*")
    ])
    error_message = "A bucket accepts a request without TLS."
  }
}

run "the_audit_archive_is_write_once_for_the_period_given" {
  command = plan

  assert {
    condition     = aws_s3_bucket.this["audit-archive"].object_lock_enabled == true
    error_message = "Object Lock is not on at the audit archive's creation, the only moment it can be chosen by this resource."
  }

  assert {
    condition     = one(aws_s3_bucket_object_lock_configuration.audit_archive.rule).default_retention[0].mode == "GOVERNANCE" && one(aws_s3_bucket_object_lock_configuration.audit_archive.rule).default_retention[0].days == 42
    error_message = "The audit archive's default lock is not the mode and period given."
  }

  assert {
    condition     = aws_s3_bucket_object_lock_configuration.audit_archive.bucket == "audit-archive-mock"
    error_message = "The lock configuration is not on the audit archive."
  }

  assert {
    condition     = aws_s3_bucket.this["terraform-state"].object_lock_enabled == false && aws_s3_bucket.this["log-archive"].object_lock_enabled == false
    error_message = "Object Lock is on a bucket whose objects must stay replaceable."
  }
}

run "nobody_may_delete_from_the_audit_archive" {
  command = plan

  assert {
    condition     = jsondecode(aws_s3_bucket_policy.this["audit-archive"].policy).Statement[1].Effect == "Deny" && jsondecode(aws_s3_bucket_policy.this["audit-archive"].policy).Statement[1].Principal == "*"
    error_message = "The audit archive's policy does not refuse deletion by every principal."
  }

  assert {
    condition     = toset(jsondecode(aws_s3_bucket_policy.this["audit-archive"].policy).Statement[1].Action) == toset(["s3:DeleteObject", "s3:DeleteObjectVersion", "s3:BypassGovernanceRetention"])
    error_message = "The audit archive's policy leaves a way to delete an object or to bypass its lock."
  }
}

run "nothing_in_an_archive_ever_expires" {
  command = plan

  assert {
    condition     = toset(keys(aws_s3_bucket_lifecycle_configuration.archives)) == toset(["audit-archive", "log-archive"])
    error_message = "An archive has no lifecycle rule, or the state bucket has one."
  }

  assert {
    condition     = alltrue([for lifecycle in aws_s3_bucket_lifecycle_configuration.archives : length(one(lifecycle.rule).expiration) == 0 && length(one(lifecycle.rule).noncurrent_version_expiration) == 0])
    error_message = "A lifecycle rule expires an archived object."
  }

  assert {
    condition     = alltrue([for lifecycle in aws_s3_bucket_lifecycle_configuration.archives : anytrue([for transition in one(lifecycle.rule).transition : transition.days == 90 && transition.storage_class == "GLACIER_IR"])])
    error_message = "An archive does not move to colder storage at the age given."
  }
}

run "an_invented_lock_mode_is_refused" {
  command = plan

  variables {
    audit_archive_lock = { mode = "NONE", days = 42 }
  }

  expect_failures = [var.audit_archive_lock]
}

run "a_lock_of_no_days_is_refused" {
  command = plan

  variables {
    audit_archive_lock = { mode = "GOVERNANCE", days = 0 }
  }

  expect_failures = [var.audit_archive_lock]
}

run "an_alias_is_refused_for_the_archive_key" {
  command = plan

  variables {
    archive_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:alias/archive"
  }

  expect_failures = [var.archive_kms_key_arn]
}
