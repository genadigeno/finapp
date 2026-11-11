# The security module, asserted over a plan against a mock AWS provider. A mock provider holds no credential and calls no
# API, so this proves the configuration and nothing about what AWS accepts: README.md says which policies could not be
# checked without an account. The fixtures are small on purpose, and each root's own test holds the entries to the real
# inventory and manifests.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_kms_key.this["etcd"]
    values = { arn = "arn:aws:kms:eu-central-1:111111111111:key/aaaaaaaa-0000-0000-0000-000000000001" }
  }

  override_resource {
    target = aws_kms_key.this["secrets"]
    values = { arn = "arn:aws:kms:eu-central-1:111111111111:key/aaaaaaaa-0000-0000-0000-000000000002" }
  }

  override_resource {
    target = aws_kms_key.this["field_keys"]
    values = { arn = "arn:aws:kms:eu-central-1:111111111111:key/aaaaaaaa-0000-0000-0000-000000000003" }
  }

  override_resource {
    target = aws_iam_policy.human_boundary
    values = { arn = "arn:aws:iam::111111111111:policy/finapp-staging-human-boundary" }
  }

  override_resource {
    target = aws_iam_policy.bootstrap_boundary[0]
    values = { arn = "arn:aws:iam::111111111111:policy/finapp-staging-human-bootstrap-boundary" }
  }

  override_resource {
    target = aws_iam_role.human["break-glass"]
    values = { arn = "arn:aws:iam::111111111111:role/finapp-staging-human-break-glass" }
  }

  override_resource {
    target = aws_iam_role.human["read-only"]
    values = { arn = "arn:aws:iam::111111111111:role/finapp-staging-human-read-only" }
  }

  override_resource {
    target = aws_iam_user.human["alice"]
    values = { arn = "arn:aws:iam::111111111111:user/humans/alice" }
  }

  override_resource {
    target = aws_iam_user.human["bob"]
    values = { arn = "arn:aws:iam::111111111111:user/humans/bob" }
  }
}

variables {
  name_prefix           = "finapp-staging"
  region                = "eu-central-1"
  account_id            = "111111111111"
  secret_inventory_path = "tests/fixture-inventory.md"
  external_secret_manifest_paths = {
    app      = "tests/fixture-externalsecret-app.yaml"
    migrator = "tests/fixture-externalsecret-migrator.yaml"
  }
  secret_store_manifest_path     = "tests/fixture-secret-store.yaml"
  workload_namespace             = "finapp"
  cluster_name                   = "finapp-staging"
  cluster_role_arn               = "arn:aws:iam::111111111111:role/finapp-staging-eks-cluster"
  workload_identity_provider_arn = "arn:aws:iam::111111111111:oidc-provider/oidc.example.invalid/id/mock"
  workload_identity_issuer       = "https://oidc.example.invalid/id/mock"
  master_user_secret_arn         = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:rds!db-mock-AbCdEf"
  database_backup_copy_vault_arn = "arn:aws:backup:eu-central-1:999999999999:backup-vault:finapp-copies"
  human_users = {
    alice = { roles = ["read-only", "break-glass"] }
    bob   = { roles = ["read-only"] }
  }
}

# ------------------------------------------------------------------------------------------------
# Keys.
# ------------------------------------------------------------------------------------------------

run "there_is_one_key_per_purpose_with_rotation_and_a_deletion_window" {
  command = plan

  assert {
    condition     = toset(keys(aws_kms_key.this)) == toset(["etcd", "database", "database_backup", "secrets", "field_keys", "brokers", "state", "archive", "logs"])
    error_message = "The keys are not one per purpose."
  }

  assert {
    condition     = alltrue([for key in aws_kms_key.this : key.enable_key_rotation == true])
    error_message = "A key does not rotate."
  }

  assert {
    condition     = alltrue([for key in aws_kms_key.this : key.deletion_window_in_days == 30 && key.bypass_policy_lockout_safety_check == false && key.multi_region == false])
    error_message = "A key can be deleted in less than thirty days, bypasses the lock-out check or leaves its region."
  }

  assert {
    condition     = alltrue([for purpose, alias in aws_kms_alias.this : alias.name == "alias/finapp-staging-${replace(purpose, "_", "-")}"])
    error_message = "A key's alias is not named from the conventions' prefix and its purpose."
  }
}

run "no_key_policy_locks_everyone_out_or_lets_the_account_use_it" {
  command = plan

  assert {
    condition = alltrue([
      for key in aws_kms_key.this : anytrue([
        for statement in jsondecode(key.policy).Statement :
        statement.Principal == { AWS = "arn:aws:iam::111111111111:root" } && contains(statement.Action, "kms:PutKeyPolicy") && contains(statement.Action, "kms:ScheduleKeyDeletion")
      ])
    ])
    error_message = "A key's policy has no statement letting the account repair it: a wrong policy would lock the key for good."
  }

  assert {
    condition = alltrue([
      for key in aws_kms_key.this : alltrue([
        for statement in jsondecode(key.policy).Statement :
        try(statement.Principal.AWS, "") != "arn:aws:iam::111111111111:root" || can(statement.Condition.StringEquals["kms:ViaService"]) || length(setintersection(statement.Action, ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:ReEncryptFrom", "kms:ReEncryptTo"])) == 0
      ])
    ])
    error_message = "A key's administration statement lets the account use the key without naming a service."
  }

  # CreateCluster's caller must make a grant, and neither condition that could bound it applies to EKS.
  assert {
    condition = alltrue([
      for purpose, key in aws_kms_key.this : alltrue([
        for statement in jsondecode(key.policy).Statement :
        purpose == "etcd" || try(statement.Principal.AWS, "") != "arn:aws:iam::111111111111:root" || can(statement.Condition.StringEquals["kms:ViaService"]) || !contains(statement.Action, "kms:CreateGrant")
      ])
    ])
    error_message = "A key other than etcd's lets the account make a grant outside a service."
  }

  assert {
    condition = alltrue([
      for key in aws_kms_key.this : alltrue([
        for statement in jsondecode(key.policy).Statement :
        alltrue([for action in statement.Action : startswith(action, "kms:") && !strcontains(action, "*")]) && try(statement.Principal.AWS, "") != "*" && try(statement.Principal, "") != "*"
      ])
    ])
    error_message = "A key's policy holds a wildcard action or admits every principal."
  }
}

run "each_key_admits_the_services_its_purpose_names_and_no_other" {
  command = plan

  assert {
    condition = (
      toset(jsondecode(aws_kms_key.this["database"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["rds.eu-central-1.amazonaws.com"])
      && toset(jsondecode(aws_kms_key.this["database_backup"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["backup.eu-central-1.amazonaws.com"])
      && toset(jsondecode(aws_kms_key.this["secrets"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["secretsmanager.eu-central-1.amazonaws.com", "kafka.eu-central-1.amazonaws.com"])
      && toset(jsondecode(aws_kms_key.this["field_keys"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["secretsmanager.eu-central-1.amazonaws.com"])
      && toset(jsondecode(aws_kms_key.this["brokers"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["kafka.eu-central-1.amazonaws.com"])
      && toset(jsondecode(aws_kms_key.this["state"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["s3.eu-central-1.amazonaws.com"])
      && toset(jsondecode(aws_kms_key.this["archive"].policy).Statement[1].Condition.StringEquals["kms:ViaService"]) == toset(["s3.eu-central-1.amazonaws.com"])
    )
    error_message = "A key admits a service its purpose does not name, or does not admit the one it does."
  }

  assert {
    condition     = length(jsondecode(aws_kms_key.this["etcd"].policy).Statement) == 3 && jsondecode(aws_kms_key.this["etcd"].policy).Statement[2].Principal == { AWS = var.cluster_role_arn }
    error_message = "The etcd key's policy does not admit this environment's cluster role and nobody else."
  }

  assert {
    condition     = jsondecode(aws_kms_key.this["etcd"].policy).Statement[1].Principal == { AWS = "arn:aws:iam::111111111111:root" } && toset(jsondecode(aws_kms_key.this["etcd"].policy).Statement[1].Action) == toset(["kms:CreateGrant", "kms:DescribeKey"])
    error_message = "The etcd key does not let the caller of CreateCluster describe it and make the grant, or lets it do more."
  }

  assert {
    condition     = jsondecode(aws_kms_key.this["logs"].policy).Statement[2].Principal == { Service = "logs.eu-central-1.amazonaws.com" } && alltrue([for arn in jsondecode(aws_kms_key.this["logs"].policy).Statement[2].Condition.ArnLike["kms:EncryptionContext:aws:logs:arn"] : startswith(arn, "arn:aws:logs:eu-central-1:111111111111:log-group:")])
    error_message = "The logs key does not admit CloudWatch Logs bound to this account's log groups alone."
  }
}

run "the_database_key_is_shared_with_a_backup_copys_account_only_across_an_account" {
  command = plan

  assert {
    condition     = jsondecode(aws_kms_key.this["database"].policy).Statement[2].Principal == { AWS = "arn:aws:iam::999999999999:root" } && jsondecode(aws_kms_key.this["database"].policy).Statement[3].Principal == { AWS = "arn:aws:iam::999999999999:root" }
    error_message = "The database key does not admit the backup copy's destination account."
  }

  assert {
    condition     = !contains(jsondecode(aws_kms_key.this["database"].policy).Statement[2].Action, "kms:CreateGrant") && toset(try(jsondecode(aws_kms_key.this["database"].policy).Statement[2].Condition.StringEquals["kms:ViaService"], [])) == toset(["backup.eu-central-1.amazonaws.com", "rds.eu-central-1.amazonaws.com"])
    error_message = "The backup copy's account can use the database key outside the copy."
  }

  assert {
    condition     = jsondecode(aws_kms_key.this["database"].policy).Statement[3].Action == ["kms:CreateGrant"] && try(jsondecode(aws_kms_key.this["database"].policy).Statement[3].Condition.Bool["kms:GrantIsForAWSResource"], "") == "true"
    error_message = "The backup copy's account can make a grant that is not for an AWS service."
  }

  assert {
    condition     = alltrue([for purpose, key in aws_kms_key.this : purpose == "database" || !strcontains(key.policy, "999999999999")])
    error_message = "A key other than the database's admits the backup copy's destination account."
  }
}

run "a_copy_to_another_region_names_that_regions_services_too" {
  command = plan

  variables {
    database_backup_copy_vault_arn = "arn:aws:backup:eu-west-1:999999999999:backup-vault:finapp-copies"
  }

  assert {
    condition     = toset(try(jsondecode(aws_kms_key.this["database"].policy).Statement[2].Condition.StringEquals["kms:ViaService"], [])) == toset(["backup.eu-central-1.amazonaws.com", "rds.eu-central-1.amazonaws.com", "backup.eu-west-1.amazonaws.com", "rds.eu-west-1.amazonaws.com"])
    error_message = "The copy's services are not this region's and the vault's."
  }
}

run "a_backup_copy_inside_this_account_shares_the_key_with_nobody" {
  command = plan

  variables {
    database_backup_copy_vault_arn = "arn:aws:backup:eu-west-1:111111111111:backup-vault:finapp-copies"
  }

  assert {
    condition     = length(jsondecode(aws_kms_key.this["database"].policy).Statement) == 2
    error_message = "The database key is shared though the backup copy stays in this account."
  }
}

# ------------------------------------------------------------------------------------------------
# Secret entries.
# ------------------------------------------------------------------------------------------------

run "the_entries_are_what_the_inventory_delivers" {
  command = plan

  assert {
    condition = toset(concat([for entry in aws_secretsmanager_secret.entry : entry.name], [for entry in aws_secretsmanager_secret.field_key : entry.name])) == toset([
      "finapp/staging/app/FINAPP_DB_URL",
      "finapp/staging/app/FINAPP_MFA_KEY",
      "finapp/staging/app/FINAPP_PAYMENTS_WEBHOOK_KEY",
      "finapp/staging/migrator/FINAPP_DB_MIGRATOR_PASSWORD",
      "finapp/staging/migrator/FINAPP_DB_MIGRATOR_USER",
      "finapp/staging/migrator/FINAPP_DB_URL",
    ])
    error_message = "The entries are not one per delivered row under each workload's path."
  }

  assert {
    condition     = alltrue([for entry in concat(values(aws_secretsmanager_secret.entry), values(aws_secretsmanager_secret.field_key)) : !strcontains(entry.name, "MASTER") && !strcontains(entry.name, "AmazonMSK_")])
    error_message = "An entry was created for the master credential or for a SCRAM secret, which are RDS's and the kafka module's."
  }

  assert {
    condition     = alltrue([for entry in concat(values(aws_secretsmanager_secret.entry), values(aws_secretsmanager_secret.field_key)) : entry.recovery_window_in_days == 30])
    error_message = "An entry can be deleted with less than the thirty-day recovery window."
  }
}

run "each_entry_is_under_its_purposes_key" {
  command = plan

  assert {
    condition     = toset(keys(aws_secretsmanager_secret.field_key)) == toset(["app/FINAPP_MFA_KEY"]) && aws_secretsmanager_secret.field_key["app/FINAPP_MFA_KEY"].kms_key_id == aws_kms_key.this["field_keys"].arn
    error_message = "A field-level encryption key's entry is not under the field_keys key."
  }

  assert {
    condition     = alltrue([for entry in aws_secretsmanager_secret.entry : entry.kms_key_id == aws_kms_key.this["secrets"].arn])
    error_message = "An entry that is not a field-level key is not under the secrets key."
  }
}

run "an_inventory_row_with_no_entry_stops_the_plan" {
  command = plan

  variables {
    secret_inventory_path = "tests/fixture-inventory-row-without-entry.md"
  }

  expect_failures = [output.secret_entry_names]
}

run "an_entry_with_no_inventory_row_stops_the_plan" {
  command = plan

  variables {
    external_secret_manifest_paths = {
      app      = "tests/fixture-externalsecret-app-no-row.yaml"
      migrator = "tests/fixture-externalsecret-migrator.yaml"
    }
  }

  expect_failures = [output.secret_entry_names]
}

run "an_inventory_row_that_cannot_be_read_stops_the_plan" {
  command = plan

  variables {
    secret_inventory_path = "tests/fixture-inventory-unread-row.md"
  }

  expect_failures = [output.secret_entry_names]
}

run "two_unread_rows_cannot_hide_behind_a_section_with_no_table" {
  command = plan

  variables {
    secret_inventory_path = "tests/fixture-inventory-cancelling-tables.md"
  }

  expect_failures = [output.secret_entry_names]
}

run "an_entry_read_elsewhere_must_be_the_applications_scram_entry" {
  command = plan

  variables {
    external_secret_manifest_paths = {
      app      = "tests/fixture-externalsecret-app-other-scram.yaml"
      migrator = "tests/fixture-externalsecret-migrator.yaml"
    }
  }

  expect_failures = [output.secret_entry_names]
}

run "a_store_on_another_environments_path_stops_the_plan" {
  command = plan

  variables {
    secret_store_manifest_path = "tests/fixture-secret-store-other-environment.yaml"
  }

  expect_failures = [output.secret_entry_names]
}

# ------------------------------------------------------------------------------------------------
# The two readers.
# ------------------------------------------------------------------------------------------------

run "each_reader_is_trusted_by_its_own_service_account_alone" {
  command = plan

  assert {
    condition = (
      try(jsondecode(aws_iam_role.secret_reader["app"].assume_role_policy).Statement[0].Condition.StringEquals, {}) == {
        "oidc.example.invalid/id/mock:aud" = "sts.amazonaws.com"
        "oidc.example.invalid/id/mock:sub" = "system:serviceaccount:finapp:finapp-app-secret-reader"
      }
      && try(jsondecode(aws_iam_role.secret_reader["migrator"].assume_role_policy).Statement[0].Condition.StringEquals, {}) == {
        "oidc.example.invalid/id/mock:aud" = "sts.amazonaws.com"
        "oidc.example.invalid/id/mock:sub" = "system:serviceaccount:finapp:finapp-migrator-secret-reader"
      }
    )
    error_message = "A reader's trust does not name exactly its ServiceAccount and the STS audience with StringEquals."
  }

  assert {
    condition = alltrue([
      for role in aws_iam_role.secret_reader :
      keys(jsondecode(role.assume_role_policy).Statement[0].Condition) == ["StringEquals"] && length(jsondecode(role.assume_role_policy).Statement) == 1 && jsondecode(role.assume_role_policy).Statement[0].Action == "sts:AssumeRoleWithWebIdentity" && jsondecode(role.assume_role_policy).Statement[0].Principal == { Federated = var.workload_identity_provider_arn }
    ])
    error_message = "A reader's role admits something other than the cluster's identity provider under exact conditions."
  }

  assert {
    condition     = aws_iam_role.secret_reader["app"].name == "finapp-staging-app-secret-reader" && aws_iam_role.secret_reader["migrator"].name == "finapp-staging-migrator-secret-reader"
    error_message = "A reader's role is not named from the conventions' prefix and its workload."
  }
}

run "no_secret_or_key_policy_holds_a_wildcard_action" {
  command = plan

  assert {
    condition = alltrue(flatten([
      for policy in concat(
        [for role_policy in aws_iam_role_policy.secret_reader : role_policy.policy],
        [for role_policy in aws_iam_role_policy.human : role_policy.policy],
        [aws_iam_policy.human_boundary.policy, aws_iam_group_policy.humans.policy],
        ) : [
        for statement in jsondecode(policy).Statement :
        statement.Effect != "Allow" || alltrue([for action in try(statement.Action, []) : !strcontains(action, "*")])
      ]
    ]))
    error_message = "A policy this module plans grants a wildcard action."
  }
}

run "each_reader_reads_exactly_its_own_entries" {
  command = plan

  assert {
    condition = (
      jsondecode(aws_iam_role_policy.secret_reader["app"].policy).Statement[0].Action == ["secretsmanager:GetSecretValue"]
      && jsondecode(aws_iam_role_policy.secret_reader["migrator"].policy).Statement[0].Action == ["secretsmanager:GetSecretValue"]
    )
    error_message = "A reader may do more to a secret than read it: its action is not exactly secretsmanager:GetSecretValue."
  }

  assert {
    condition = toset(jsondecode(aws_iam_role_policy.secret_reader["app"].policy).Statement[0].Resource) == toset([
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:AmazonMSK_finapp-staging-application-??????",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:AmazonMSK_finapp-staging-application-alternate-??????",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:finapp/staging/app/FINAPP_DB_URL-??????",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:finapp/staging/app/FINAPP_MFA_KEY-??????",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:finapp/staging/app/FINAPP_PAYMENTS_WEBHOOK_KEY-??????",
    ])
    error_message = "The application's reader may not read exactly its own entries and the application's two SCRAM entries, the second its rotation's."
  }

  assert {
    condition = toset(jsondecode(aws_iam_role_policy.secret_reader["migrator"].policy).Statement[0].Resource) == toset([
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:finapp/staging/migrator/FINAPP_DB_MIGRATOR_PASSWORD-??????",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:finapp/staging/migrator/FINAPP_DB_MIGRATOR_USER-??????",
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:finapp/staging/migrator/FINAPP_DB_URL-??????",
    ])
    error_message = "The migrator's reader may not read exactly its own entries."
  }

  assert {
    condition     = alltrue([for resource in jsondecode(aws_iam_role_policy.secret_reader["app"].policy).Statement[0].Resource : !strcontains(resource, "/migrator/")])
    error_message = "The application's reader can read the migrator's entries."
  }

  assert {
    condition     = alltrue([for resource in jsondecode(aws_iam_role_policy.secret_reader["migrator"].policy).Statement[0].Resource : !strcontains(resource, "/app/") && !strcontains(resource, "AmazonMSK_")])
    error_message = "The migrator's reader can read the application's entries."
  }

  assert {
    condition = alltrue(flatten([
      for role_policy in aws_iam_role_policy.secret_reader : [
        for statement in jsondecode(role_policy.policy).Statement : [
          for resource in statement.Resource :
          !strcontains(resource, "*") && !strcontains(resource, "rds!") && resource != var.master_user_secret_arn
        ]
      ]
    ]))
    error_message = "A reader's policy has a wildcard resource, or reaches the database's master credential."
  }

  assert {
    condition = alltrue([
      for role_policy in aws_iam_role_policy.secret_reader :
      !strcontains(role_policy.policy, "s3:") && length(jsondecode(role_policy.policy).Statement) == 2
    ])
    error_message = "A reader holds an action on a bucket, or one beyond reading its entries and decrypting them."
  }
}

run "each_reader_decrypts_only_through_secrets_manager_under_its_own_keys" {
  command = plan

  assert {
    condition = alltrue([
      for role_policy in aws_iam_role_policy.secret_reader :
      jsondecode(role_policy.policy).Statement[1].Action == ["kms:Decrypt"] && jsondecode(role_policy.policy).Statement[1].Condition == { StringEquals = { "kms:ViaService" = "secretsmanager.eu-central-1.amazonaws.com" } }
    ])
    error_message = "A reader can use a key outside Secrets Manager, or do more than decrypt."
  }

  assert {
    condition     = toset(jsondecode(aws_iam_role_policy.secret_reader["app"].policy).Statement[1].Resource) == toset([aws_kms_key.this["secrets"].arn, aws_kms_key.this["field_keys"].arn])
    error_message = "The application's reader does not decrypt under exactly the secrets and field-level keys."
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.secret_reader["migrator"].policy).Statement[1].Resource == [aws_kms_key.this["secrets"].arn]
    error_message = "The migrator's reader decrypts under more than the secrets key."
  }
}

# ------------------------------------------------------------------------------------------------
# People.
# ------------------------------------------------------------------------------------------------

run "break_glass_needs_a_factor_and_lasts_an_hour" {
  command = plan

  assert {
    condition     = aws_iam_role.human["break-glass"].max_session_duration == 3600
    error_message = "Break-glass has no one-hour session limit, which is the only thing bounding it in time."
  }

  assert {
    condition     = try(jsondecode(aws_iam_role.human["break-glass"].assume_role_policy).Statement[0].Condition.Bool["aws:MultiFactorAuthPresent"], "") == "true" && try(jsondecode(aws_iam_role.human["break-glass"].assume_role_policy).Statement[0].Condition.NumericLessThan["aws:MultiFactorAuthAge"], "") == "3600"
    error_message = "A human role can be assumed without MFA: break-glass does not require a recent factor."
  }

  assert {
    condition     = jsondecode(aws_iam_role.human["break-glass"].assume_role_policy).Statement[0].Principal == { AWS = ["arn:aws:iam::111111111111:user/humans/alice"] }
    error_message = "Break-glass may be assumed by someone not given it."
  }
}

run "every_human_role_needs_a_factor_names_its_people_and_has_its_boundary" {
  command = plan

  assert {
    condition = alltrue([
      for role in aws_iam_role.human :
      try(jsondecode(role.assume_role_policy).Statement[0].Condition.Bool["aws:MultiFactorAuthPresent"], "") == "true"
    ])
    error_message = "A human role can be assumed without MFA."
  }

  assert {
    condition     = alltrue([for role in aws_iam_role.human : role.permissions_boundary == aws_iam_policy.human_boundary.arn && role.max_session_duration <= 14400])
    error_message = "A human role has no permission boundary, or a session longer than four hours."
  }

  assert {
    condition     = alltrue([for role in aws_iam_role.human : try(jsondecode(role.assume_role_policy).Statement[0].Condition.StringEquals["sts:RoleSessionName"], "") == "$${aws:username}"])
    error_message = "A human role's session need not carry the person's own name, so the audit logs would name only the role."
  }

  assert {
    condition     = toset(jsondecode(aws_iam_role.human["read-only"].assume_role_policy).Statement[0].Principal.AWS) == toset(["arn:aws:iam::111111111111:user/humans/alice", "arn:aws:iam::111111111111:user/humans/bob"])
    error_message = "The read-only role is not assumable by exactly the people given it."
  }
}

run "every_person_is_in_the_group_that_refuses_everything_without_a_factor" {
  command = plan

  assert {
    condition     = toset(aws_iam_group_membership.humans.users) == toset(keys(var.human_users))
    error_message = "A person is not in the humans group, so nothing requires a factor of them."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_group_policy.humans.policy).Statement :
      statement.Effect == "Deny" && try(statement.Condition.BoolIfExists["aws:MultiFactorAuthPresent"], "") == "false" && statement.Resource == "*" && length(try(statement.NotAction, [])) > 0
    ])
    error_message = "The humans group's policy does not refuse everything without a factor."
  }

  assert {
    condition = alltrue([
      for action in one([for statement in jsondecode(aws_iam_group_policy.humans.policy).Statement : statement.NotAction if statement.Sid == "RefuseEverythingWithoutAFactor"]) :
      contains(["iam:ChangePassword", "iam:CreateVirtualMFADevice", "iam:EnableMFADevice", "iam:GetUser", "iam:ListMFADevices", "iam:ListVirtualMFADevices", "iam:ResyncMFADevice", "sts:GetSessionToken"], action)
    ])
    error_message = "A person without a factor may do more than enrol one."
  }

  assert {
    condition = anytrue([
      for statement in jsondecode(aws_iam_group_policy.humans.policy).Statement :
      statement.Effect == "Deny" && contains(try(statement.Action, []), "iam:CreateAccessKey")
    ])
    error_message = "The humans group's policy does not refuse a long-lived access key."
  }
}

run "a_person_can_be_removed_whatever_they_enrolled" {
  command = plan

  assert {
    condition     = alltrue([for user in aws_iam_user.human : user.force_destroy == true])
    error_message = "A person who has a password or a device cannot be removed: the apply would fail and leave them in the trust."
  }
}

run "the_bootstrap_role_exists_only_when_asked_for" {
  command = plan

  variables {
    bootstrap_role_enabled = true
    human_users = {
      alice = { roles = ["read-only", "break-glass"] }
      bob   = { roles = ["read-only", "bootstrap"] }
    }
  }

  assert {
    condition = toset(jsondecode(aws_iam_role_policy.human["bootstrap"].policy).Statement[0].Resource) == toset([
      var.master_user_secret_arn,
      "arn:aws:secretsmanager:eu-central-1:111111111111:secret:finapp/staging/migrator/FINAPP_DB_MIGRATOR_PASSWORD-??????",
    ])
    error_message = "The bootstrap role does not read exactly the master credential and the passwords the inventory names the step as consuming."
  }

  assert {
    condition     = toset(keys(aws_eks_access_entry.human)) == toset(["break-glass", "read-only"])
    error_message = "The bootstrap role has a way into the cluster."
  }

  assert {
    condition     = aws_iam_role.human["bootstrap"].max_session_duration == 3600 && aws_iam_role.human["bootstrap"].permissions_boundary == aws_iam_policy.bootstrap_boundary[0].arn && try(jsondecode(aws_iam_role.human["bootstrap"].assume_role_policy).Statement[0].Condition.Bool["aws:MultiFactorAuthPresent"], "") == "true"
    error_message = "The bootstrap role lasts more than an hour, has no boundary of its own, or needs no factor."
  }

  assert {
    condition     = jsondecode(aws_iam_role.human["bootstrap"].assume_role_policy).Statement[0].Principal == { AWS = [aws_iam_user.human["bob"].arn] }
    error_message = "The bootstrap role may be assumed by someone not given it."
  }

  assert {
    condition     = !strcontains(aws_iam_role_policy.human["break-glass"].policy, "secretsmanager:") && !strcontains(aws_iam_policy.human_boundary.policy, "secretsmanager:")
    error_message = "Break-glass or its boundary can read a secret while the bootstrap role exists."
  }
}

run "the_bootstrap_role_needs_a_person_who_may_assume_it" {
  command = plan

  variables {
    bootstrap_role_enabled = true
  }

  expect_failures = [var.bootstrap_role_enabled]
}

run "no_standing_human_role_reads_a_secret" {
  command = plan

  assert {
    condition     = toset(keys(aws_iam_role.human)) == toset(["break-glass", "read-only"]) && length(aws_iam_policy.bootstrap_boundary) == 0
    error_message = "The bootstrap role exists though nobody asked for it."
  }

  assert {
    condition     = alltrue([for policy in aws_iam_role_policy.human : !strcontains(policy.policy, "secretsmanager:") && !strcontains(policy.policy, "kms:")])
    error_message = "A standing human role can read a secret or use a key."
  }

  assert {
    condition     = !strcontains(aws_iam_policy.human_boundary.policy, "secretsmanager:") && !strcontains(aws_iam_policy.human_boundary.policy, "kms:")
    error_message = "The boundary of the standing human roles would let one read a secret."
  }
}

# ------------------------------------------------------------------------------------------------
# The cluster's API.
# ------------------------------------------------------------------------------------------------

run "the_access_entries_carry_each_group_on_its_role_and_nothing_else" {
  command = plan

  assert {
    condition     = toset(keys(aws_eks_access_entry.human)) == toset(["break-glass", "read-only"])
    error_message = "The access entries are not exactly break-glass and read-only."
  }

  assert {
    condition     = toset(aws_eks_access_entry.human["break-glass"].kubernetes_groups) == toset(["finapp-break-glass"]) && toset(aws_eks_access_entry.human["read-only"].kubernetes_groups) == toset(["finapp-read-only"])
    error_message = "An access entry carries a group other than its own."
  }

  assert {
    condition     = aws_eks_access_entry.human["break-glass"].principal_arn == aws_iam_role.human["break-glass"].arn && aws_eks_access_entry.human["read-only"].principal_arn == aws_iam_role.human["read-only"].arn
    error_message = "An access entry names a principal other than the role that requires a factor."
  }

  assert {
    condition     = alltrue([for entry in aws_eks_access_entry.human : entry.type == "STANDARD" && entry.cluster_name == var.cluster_name])
    error_message = "An access entry is not a standard entry on this cluster."
  }
}

# ------------------------------------------------------------------------------------------------
# Refused inputs.
# ------------------------------------------------------------------------------------------------

run "a_person_with_an_unknown_role_is_refused" {
  command = plan

  variables {
    human_users = {
      alice = { roles = ["read-only", "break-glass", "admin"] }
    }
  }

  expect_failures = [var.human_users]
}

run "a_role_nobody_may_assume_is_refused" {
  command = plan

  variables {
    human_users = {
      alice = { roles = ["read-only"] }
    }
  }

  expect_failures = [var.human_users]
}

run "a_third_workload_is_refused" {
  command = plan

  variables {
    external_secret_manifest_paths = {
      app      = "tests/fixture-externalsecret-app.yaml"
      migrator = "tests/fixture-externalsecret-migrator.yaml"
      other    = "tests/fixture-externalsecret-app.yaml"
    }
  }

  expect_failures = [var.external_secret_manifest_paths]
}
