# PostgreSQL on RDS: the financial record's database (PHASE_15_PLAN.md C3, ADR-0121). Never applied: there
# is no account in Phase 15, so every property here is asserted by tests/postgres.tftest.hcl over a plan
# against a mock provider, which proves this configuration and nothing about the service's behaviour.
#
# The security properties are literals, not variables, so that no environment can turn one off.

locals {
  # The major the local databases run. RDS picks the minor and auto_minor_version_upgrade keeps it
  # patched, which is why the version below carries no minor.
  engine_major = "18"
}

resource "aws_db_subnet_group" "this" {
  name        = "${var.name_prefix}-database"
  description = "The workload tier, which has no public address and no route from the internet."
  subnet_ids  = values(var.private_subnet_ids_by_zone)
}

# Every data-tier service brings its own security group: the network module's interface endpoints admit
# the VPC on 443, and nothing else in the VPC may reach this port merely by being in it.
resource "aws_security_group" "database" {
  name_prefix = "${var.name_prefix}-database-"
  description = "The database: PostgreSQL from the cluster's nodes only."
  vpc_id      = var.vpc_id

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_clients" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = var.client_security_group_id
  description                  = "PostgreSQL from the cluster's nodes."
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

resource "aws_db_parameter_group" "this" {
  name_prefix = "${var.name_prefix}-postgres${local.engine_major}-"
  family      = "postgres${local.engine_major}"
  description = "TLS required, and the max_connections the application declares."

  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }

  # Static on the engine: the server reads it at start, so RDS refuses "immediate", and a change sits at
  # pending-reboot until the instance restarts. A plan reports nothing while it waits: README.md.
  parameter {
    name         = "max_connections"
    value        = tostring(var.max_connections)
    apply_method = "pending-reboot"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name_prefix}-postgres"
  engine         = "postgres"
  engine_version = local.engine_major
  instance_class = var.instance_class

  db_name  = "finapp"
  username = "finapp_master"
  # RDS generates this password and keeps it in Secrets Manager, so no value reaches this configuration or
  # its state. Only the one-time bootstrap step reads it (PHASE_15_PLAN.md section 7).
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.secrets_kms_key_arn

  allocated_storage     = var.allocated_storage_gib
  max_allocated_storage = var.max_allocated_storage_gib
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.storage_kms_key_arn

  multi_az               = true
  publicly_accessible    = false
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.database.id]
  parameter_group_name   = aws_db_parameter_group.this.name

  backup_retention_period  = var.backup_retention_days
  backup_window            = "02:00-03:00"
  maintenance_window       = "sun:04:00-sun:05:00"
  copy_tags_to_snapshot    = true
  delete_automated_backups = false

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name_prefix}-postgres-final"

  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false
  apply_immediately           = false

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = var.storage_kms_key_arn
  performance_insights_retention_period = 7
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]

  lifecycle {
    prevent_destroy = true
  }
}

# THE SEPARATE KEY, AND WHAT IT DOES NOT COVER. RDS has no setting for the key of its automated backups:
# they and every snapshot are under the instance's own key, and AWS Backup's vault key does not change
# that within one account and region. A copy crossing an account or a region is what takes the destination
# vault's key, so the plan below copies each daily snapshot across that boundary. Point-in-time recovery
# stays with RDS, under the storage key: no copy is point-in-time. README.md has the detail, and
# THREAT_MODEL.md section 6 holds what is still the owner's to choose.
resource "aws_backup_vault" "database" {
  name        = "${var.name_prefix}-database"
  kms_key_arn = var.backup_kms_key_arn

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role" "backup" {
  name = "${var.name_prefix}-database-backup"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "backup.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = var.account_id } }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_backup_plan" "database" {
  name = "${var.name_prefix}-database"

  rule {
    rule_name         = "daily-copied-across-the-boundary"
    target_vault_name = aws_backup_vault.database.name
    schedule          = "cron(0 3 * * ? *)"
    # Left off: continuous backup would hand the instance's retention and window to AWS Backup, leaving
    # backup_retention_period above saying one thing while the service did another.
    enable_continuous_backup = false

    lifecycle {
      delete_after = var.snapshot_retention_days
    }

    copy_action {
      destination_vault_arn = var.backup_copy_vault_arn

      lifecycle {
        delete_after = var.snapshot_retention_days
      }
    }
  }
}

resource "aws_backup_selection" "database" {
  name         = "${var.name_prefix}-database"
  plan_id      = aws_backup_plan.database.id
  iam_role_arn = aws_iam_role.backup.arn
  resources    = [aws_db_instance.this.arn]
}
