# The database's properties, asserted over a plan against a mock AWS provider. A mock provider holds no
# credential and calls no API; every assertion reads what the plan would create. Identifiers an assertion
# compares are fixed here, because a mock leaves computed attributes unknown at plan.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_db_subnet_group.this
    values = { name = "finapp-staging-database" }
  }

  override_resource {
    target = aws_security_group.database
    values = { id = "sg-database" }
  }

  override_resource {
    target = aws_db_parameter_group.this
    values = { name = "parameter-group-mock" }
  }

  override_resource {
    target = aws_db_instance.this
    values = {
      arn                = "arn:aws:rds:eu-central-1:111111111111:db:finapp-staging-postgres"
      master_user_secret = [{ kms_key_id = "mock", secret_arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:rds!db-mock-AbCdEf", secret_status = "active" }]
    }
  }

  override_resource {
    target = aws_backup_plan.database
    values = { id = "plan-mock" }
  }

  override_resource {
    target = aws_iam_role.backup
    values = {
      arn  = "arn:aws:iam::111111111111:role/backup-mock"
      name = "backup-mock"
    }
  }
}

variables {
  name_prefix                = "finapp-staging"
  region                     = "eu-central-1"
  account_id                 = "111111111111"
  vpc_id                     = "vpc-mock"
  private_subnet_ids_by_zone = { "eu-central-1a" = "subnet-a", "eu-central-1b" = "subnet-b", "eu-central-1c" = "subnet-c" }
  client_security_group_id   = "sg-nodes"
  instance_class             = "db.m7g.large"
  allocated_storage_gib      = 100
  max_allocated_storage_gib  = 500
  max_connections            = 137
  storage_kms_key_arn        = "arn:aws:kms:eu-central-1:111111111111:key/11111111-1111-1111-1111-111111111111"
  backup_kms_key_arn         = "arn:aws:kms:eu-central-1:111111111111:key/22222222-2222-2222-2222-222222222222"
  secrets_kms_key_arn        = "arn:aws:kms:eu-central-1:111111111111:key/33333333-3333-3333-3333-333333333333"
  backup_retention_days      = 14
  backup_copy_vault_arn      = "arn:aws:backup:eu-central-1:999999999999:backup-vault:finapp-copies"
  snapshot_retention_days    = 30
}

run "the_database_has_a_synchronous_standby" {
  command = plan

  assert {
    condition     = aws_db_instance.this.multi_az == true
    error_message = "The database has no synchronous standby in another zone."
  }

  assert {
    condition     = aws_db_instance.this.engine == "postgres" && aws_db_instance.this.engine_version == "18"
    error_message = "The database is not PostgreSQL at the pinned major."
  }

  assert {
    condition     = aws_db_parameter_group.this.family == "postgres18"
    error_message = "The parameter group's family is not the engine's major."
  }
}

run "the_database_accepts_tls_alone" {
  command = plan

  assert {
    condition     = one([for parameter in aws_db_parameter_group.this.parameter : parameter.value if parameter.name == "rds.force_ssl"]) == "1"
    error_message = "The server accepts a connection without TLS: rds.force_ssl is not 1."
  }

  assert {
    condition     = aws_db_instance.this.parameter_group_name == "parameter-group-mock"
    error_message = "The instance does not use this parameter group, so none of its parameters apply."
  }
}

run "max_connections_is_the_value_given_and_waits_for_a_reboot" {
  command = plan

  assert {
    condition     = output.max_connections == "137"
    error_message = "max_connections on the server is not the value the module was given."
  }

  assert {
    condition     = one([for parameter in aws_db_parameter_group.this.parameter : parameter.apply_method if parameter.name == "max_connections"]) == "pending-reboot"
    error_message = "max_connections is static on the engine, and RDS refuses to apply a static parameter immediately."
  }

  assert {
    condition     = length([for parameter in aws_db_parameter_group.this.parameter : parameter if parameter.name == "max_connections"]) == 1
    error_message = "max_connections is set more than once."
  }
}

run "the_volume_is_encrypted_under_the_given_key" {
  command = plan

  assert {
    condition     = aws_db_instance.this.storage_encrypted == true && aws_db_instance.this.kms_key_id == var.storage_kms_key_arn
    error_message = "The database's volume is not encrypted under the key this module is given."
  }

  assert {
    condition     = aws_db_instance.this.performance_insights_enabled == true && aws_db_instance.this.performance_insights_kms_key_id == var.storage_kms_key_arn
    error_message = "Performance Insights is off, or holds statement text under another key than the database's."
  }
}

run "the_database_has_no_public_address_and_lives_on_the_given_subnets" {
  command = plan

  assert {
    condition     = aws_db_instance.this.publicly_accessible == false
    error_message = "The database has a public address."
  }

  assert {
    condition     = aws_db_subnet_group.this.subnet_ids == toset(["subnet-a", "subnet-b", "subnet-c"]) && aws_db_instance.this.db_subnet_group_name == "finapp-staging-database"
    error_message = "The database is not on exactly the subnets it was given."
  }
}

run "only_the_given_security_group_reaches_the_port" {
  command = plan

  assert {
    condition     = aws_vpc_security_group_ingress_rule.from_clients.referenced_security_group_id == "sg-nodes" && aws_vpc_security_group_ingress_rule.from_clients.cidr_ipv4 == null && aws_vpc_security_group_ingress_rule.from_clients.cidr_ipv6 == null
    error_message = "Something other than the given security group is admitted to the database."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.from_clients.ip_protocol == "tcp" && aws_vpc_security_group_ingress_rule.from_clients.from_port == 5432 && aws_vpc_security_group_ingress_rule.from_clients.to_port == 5432
    error_message = "The rule admits something other than PostgreSQL's port."
  }

  assert {
    condition     = aws_db_instance.this.vpc_security_group_ids == toset(["sg-database"]) && aws_security_group.database.vpc_id == "vpc-mock"
    error_message = "The database is not behind its own security group, in the given VPC."
  }
}

run "the_master_password_is_generated_and_kept_out_of_state" {
  command = plan

  assert {
    condition     = aws_db_instance.this.manage_master_user_password == true && aws_db_instance.this.password == null
    error_message = "The master password is given, so state would hold it."
  }

  assert {
    condition     = aws_db_instance.this.master_user_secret_kms_key_id == var.secrets_kms_key_arn
    error_message = "The generated master password is not under the key this module is given."
  }
}

run "point_in_time_recovery_is_on_and_outlives_the_instance" {
  command = plan

  assert {
    condition     = aws_db_instance.this.backup_retention_period == 14
    error_message = "Point-in-time recovery does not reach back as far as the root asked."
  }

  assert {
    condition     = aws_db_instance.this.delete_automated_backups == false
    error_message = "Deleting the instance would delete its automated backups."
  }

  assert {
    condition     = aws_db_instance.this.skip_final_snapshot == false && aws_db_instance.this.final_snapshot_identifier == "finapp-staging-postgres-final"
    error_message = "Deleting the instance would take no final snapshot."
  }
}

run "the_database_cannot_be_deleted_by_a_plan" {
  command = plan

  assert {
    condition     = aws_db_instance.this.deletion_protection == true
    error_message = "The database has no deletion protection."
  }

  assert {
    condition     = aws_db_instance.this.allow_major_version_upgrade == false && aws_db_instance.this.apply_immediately == false
    error_message = "A change could reach the database outside its maintenance window, or move its major version."
  }
}

run "the_vault_is_under_its_own_key_and_every_snapshot_is_copied_across_the_boundary" {
  command = plan

  assert {
    condition     = aws_backup_vault.database.kms_key_arn == var.backup_kms_key_arn && aws_backup_vault.database.kms_key_arn != aws_db_instance.this.kms_key_id
    error_message = "The backup vault is not under its own key, separate from the database's."
  }

  assert {
    condition     = one(aws_backup_plan.database.rule).target_vault_name == "finapp-staging-database" && one(one(aws_backup_plan.database.rule).copy_action).destination_vault_arn == var.backup_copy_vault_arn
    error_message = "The daily snapshot does not go to this vault and on to the destination across the boundary."
  }

  assert {
    condition     = one(aws_backup_plan.database.rule).enable_continuous_backup == false
    error_message = "AWS Backup would take over the instance's point-in-time retention."
  }

  assert {
    condition     = aws_backup_selection.database.resources == toset(["arn:aws:rds:eu-central-1:111111111111:db:finapp-staging-postgres"]) && aws_backup_selection.database.plan_id == "plan-mock"
    error_message = "The backup plan selects something other than this database."
  }

  assert {
    condition     = jsondecode(aws_iam_role.backup.assume_role_policy).Statement[0].Principal.Service == "backup.amazonaws.com" && jsondecode(aws_iam_role.backup.assume_role_policy).Statement[0].Condition.StringEquals["aws:SourceAccount"] == "111111111111"
    error_message = "The backup role is assumable by something other than this account's AWS Backup."
  }
}

run "names_start_with_the_conventions_prefix" {
  command = plan

  assert {
    condition     = aws_db_instance.this.identifier == "finapp-staging-postgres" && startswith(aws_security_group.database.name_prefix, "finapp-staging-") && startswith(aws_db_parameter_group.this.name_prefix, "finapp-staging-")
    error_message = "A name does not start with the conventions' prefix."
  }
}

run "fewer_than_three_zones_are_refused" {
  command = plan

  variables {
    private_subnet_ids_by_zone = { "eu-central-1a" = "subnet-a", "eu-central-1b" = "subnet-b" }
  }

  expect_failures = [var.private_subnet_ids_by_zone]
}

run "the_databases_own_key_is_refused_for_the_vault" {
  command = plan

  variables {
    backup_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:key/11111111-1111-1111-1111-111111111111"
  }

  expect_failures = [var.backup_kms_key_arn]
}

run "an_alias_is_refused_for_the_storage_key" {
  command = plan

  variables {
    storage_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:alias/database"
  }

  expect_failures = [var.storage_kms_key_arn]
}

run "a_copy_vault_inside_this_account_and_region_is_refused" {
  command = plan

  variables {
    backup_copy_vault_arn = "arn:aws:backup:eu-central-1:111111111111:backup-vault:same-boundary"
  }

  expect_failures = [var.backup_copy_vault_arn]
}

run "a_copy_vault_in_this_account_and_another_region_is_admitted" {
  command = plan

  variables {
    backup_copy_vault_arn = "arn:aws:backup:eu-west-1:111111111111:backup-vault:other-region"
  }

  assert {
    condition     = one(one(aws_backup_plan.database.rule).copy_action).destination_vault_arn == "arn:aws:backup:eu-west-1:111111111111:backup-vault:other-region"
    error_message = "A copy to another region is the boundary too, and must be admitted."
  }
}

run "point_in_time_below_a_week_is_refused" {
  command = plan

  variables {
    backup_retention_days = 3
  }

  expect_failures = [var.backup_retention_days]
}

run "a_fractional_max_connections_is_refused" {
  command = plan

  variables {
    max_connections = 100.5
  }

  expect_failures = [var.max_connections]
}
