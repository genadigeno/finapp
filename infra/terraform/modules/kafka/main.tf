# MSK: the integration transport (PHASE_15_PLAN.md C3 and C11, ADR-0121). Never applied: there is no account
# in Phase 15, so every property here is asserted by tests/kafka.tftest.hcl over a plan against mock
# providers, which proves this configuration and nothing about the service's behaviour. Kafka carries hints
# and never truth (INV-EVT-02): the outbox in PostgreSQL is the durable source.
#
# Security properties are literals, not variables, so that no environment can turn one off. Everything that
# describes topics, permissions or the brokers' posture is READ from infra/kafka/, never restated here:
# TopicCatalogueTest holds those two files to the code, so a copy here would be a second truth nothing holds.

locals {
  # MSK's version string for this Kafka line on KRaft. Provisioned brokers are offered up to 4.1, while the
  # local and kind brokers run 4.3. Unverified until an account exists.
  kafka_version = "4.1.x.kraft"

  catalogue = yamldecode(file(var.topic_catalogue_path))

  topics = {
    for name, class in local.catalogue.topics : name => local.catalogue.classes[class]
  }

  # Every line of broker.properties that is not blank or a comment. A line that is not key=value fails the
  # regex and stops the plan, and a key given twice is a duplicate-key error, so an unreadable or
  # ambiguous posture stops the plan rather than quietly losing a setting.
  broker_lines = [
    for line in split("\n", file(var.broker_properties_path)) : trimspace(line)
    if trimspace(line) != "" && !startswith(trimspace(line), "#")
  ]
  broker_posture = {
    for line in local.broker_lines : regex("^([^=]+)=", line)[0] => regex("^[^=]+=(.*)$", line)[0]
  }

  # What MSK sets itself and refuses in a custom configuration, recorded rather than restated: the mechanism
  # is client_authentication's SCRAM below, the only one MSK offers, and the retention check is left at
  # Kafka's own default, the value broker.properties states and TopicCatalogueTest counts against the
  # inbox's dedupe window. Anything else in the file goes to the brokers, and MSK refuses a key it does not
  # accept, so a new setting cannot be dropped here without someone naming it in this set.
  set_by_the_service = toset(["sasl.enabled.mechanisms", "log.retention.check.interval.ms"])

  # MSK has no super.users setting. With no ACL anywhere and allow.everyone.if.no.acl.found=false nobody,
  # the provisioner included, can create the first ACL, and AWS documents that as losing access to the
  # cluster. So until the stage is enforced the brokers admit an authenticated principal to a resource no
  # ACL names. Only the stages before enforced do this, and the check below warns on every plan until then.
  acl_enforced = var.provisioning_stage == "enforced"

  server_settings = merge(
    { for key, value in local.broker_posture : key => value if !contains(local.set_by_the_service, key) },
    local.acl_enforced ? {} : { "allow.everyone.if.no.acl.found" = "true" },
  )

  server_properties = join("\n", [for key in sort(keys(local.server_settings)) : "${key}=${local.server_settings[key]}"])

  # MSK takes three zones at most, and the broker count must be a multiple of the zones it is given. The
  # network may have more zones, so the first three by name are taken: stable when a zone is added.
  broker_zones   = slice(sort(keys(var.private_subnet_ids_by_zone)), 0, 3)
  client_subnets = [for zone in local.broker_zones : var.private_subnet_ids_by_zone[zone]]

  # A role in the catalogue is a SCRAM user here. The name is derived, so ACLs can name it without reading
  # the secret; the secret's "username" must be exactly this (README.md).
  #
  # A principal's users (P15-TSK-020, SECRET_MANAGEMENT.md section 5.1's add-roll-remove): its primary user, and during a rotation
  # its alternate too, each with its own entry, because an ACL names a user and a fleet half rolled is authenticating as both. A user
  # is staged as the principals are: "entry" plans its entry alone, which a person then fills, because this module associates no
  # entry before it holds a value (the stage brokers below; AWS's and the provider's pages are silent on what an empty one does,
  # and their own example fills it first); "associated" associates it with the cluster and gives it exactly
  # the principal's ACLs, before the fleet is pointed at it. Then the fleet rolls, and the old user leaves (README.md). A principal
  # absent from scram_user_slots has { primary = "associated" }, which is every principal's state outside a rotation.
  scram_slots = {
    for principal in keys(local.catalogue.principals) : principal => lookup(var.scram_user_slots, principal, { primary = "associated" })
  }
  scram_user_entries = flatten([
    for principal, slots in local.scram_slots : [
      for slot, stage in slots : {
        key        = slot == "primary" ? principal : "${principal}-alternate"
        principal  = principal
        username   = slot == "primary" ? "${var.name_prefix}-${principal}" : "${var.name_prefix}-${principal}-alternate"
        associated = stage == "associated"
      }
    ]
  ])
  # Every user with an entry; the users associated with the cluster; and theirs alone, the ACLs.
  scram_users            = { for user in local.scram_user_entries : user.key => user.username }
  scram_associated_users = { for user in local.scram_user_entries : user.key => user.username if user.associated }
  scram_principal_of     = { for user in local.scram_user_entries : user.key => user.principal if user.associated }

  principals_exist = var.provisioning_stage != "brokers"

  topic_grants = flatten([
    for user, principal in local.scram_principal_of : [
      for topic in keys(local.catalogue.topics) : [
        for operation in local.catalogue.principals[principal].topics : {
          user      = user
          principal = principal
          type      = "Topic"
          name      = topic
          pattern   = "Literal"
          operation = operation
        }
      ]
    ]
  ])

  group_grants = flatten([
    for user, principal in local.scram_principal_of : [
      for operation in try(local.catalogue.principals[principal].groups.operations, []) : {
        user      = user
        principal = principal
        type      = "Group"
        name      = local.catalogue.principals[principal].groups.prefix
        pattern   = "Prefixed"
        operation = operation
      }
    ]
  ])

  cluster_grants = flatten([
    for user, principal in local.scram_principal_of : [
      for operation in try(local.catalogue.principals[principal].cluster, []) : {
        user      = user
        principal = principal
        type      = "Cluster"
        name      = "kafka-cluster"
        pattern   = "Literal"
        operation = operation
      }
    ]
  ])

  acl_grants = {
    for grant in concat(local.topic_grants, local.group_grants, local.cluster_grants) :
    "${grant.user}/${grant.type}/${grant.name}/${grant.operation}" => grant
  }
}

check "the_catalogues_acls_are_enforced" {
  assert {
    condition     = local.acl_enforced
    error_message = "This cluster is at stage ${var.provisioning_stage}: allow.everyone.if.no.acl.found is true, so any authenticated principal reaches a resource no ACL names. Finish the sequence in modules/kafka/README.md."
  }
}

# Every data-tier service brings its own security group: the network module's interface endpoints admit
# the VPC on 443, and nothing else in the VPC may reach this port merely by being in it.
resource "aws_security_group" "brokers" {
  name_prefix = "${var.name_prefix}-brokers-"
  description = "The brokers: SASL_SSL from the cluster's nodes only."
  vpc_id      = var.vpc_id

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "from_clients" {
  security_group_id            = aws_security_group.brokers.id
  referenced_security_group_id = var.client_security_group_id
  description                  = "Kafka over SASL_SSL from the cluster's nodes."
  ip_protocol                  = "tcp"
  from_port                    = 9096
  to_port                      = 9096
}

resource "aws_cloudwatch_log_group" "brokers" {
  name                        = "/finapp/${var.name_prefix}/msk-brokers"
  retention_in_days           = var.broker_log_retention_days
  kms_key_id                  = var.broker_log_kms_key_arn
  deletion_protection_enabled = true
}

resource "aws_msk_configuration" "this" {
  name              = "${var.name_prefix}-brokers"
  kafka_versions    = [local.kafka_version]
  server_properties = local.server_properties
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name_prefix}-brokers"
  kafka_version          = local.kafka_version
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = local.client_subnets
    security_groups = [aws_security_group.brokers.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_gib
      }
    }

    connectivity_info {
      public_access {
        type = "DISABLED"
      }
    }
  }

  client_authentication {
    unauthenticated = false

    sasl {
      scram = true
      iam   = false
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.at_rest_kms_key_arn

    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.brokers.name
      }
    }
  }
}

# Entries without values: the name MSK requires, the customer-managed key it requires, and the policy that
# lets it read them. No aws_secretsmanager_secret_version here or anywhere: no secret value enters state
# (ADR-0121 decision 3). What fills them is a real environment's secret store, before stage principals.
#
# Deleted at once when its user leaves (P15-TSK-020): a revoked credential's deletion is the point of removing it, and a rotation
# re-creates the same name the next time round (primary, alternate, primary), which an entry still in its recovery window would hold
# for up to 30 days, the provider's default. 0 is the provider's documented value for deletion without recovery.
resource "aws_secretsmanager_secret" "scram" {
  for_each = local.scram_users

  name                    = "AmazonMSK_${each.value}"
  description             = "SCRAM-SHA-512 credential of the ${each.key} principal on ${var.name_prefix}'s brokers. JSON with username and password; the username is exactly ${each.value}."
  kms_key_id              = var.scram_kms_key_arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_policy" "scram" {
  for_each = local.scram_users

  secret_arn = aws_secretsmanager_secret.scram[each.key].arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AWSKafkaResourcePolicy"
      Effect    = "Allow"
      Principal = { Service = "kafka.amazonaws.com" }
      Action    = "secretsmanager:getSecretValue"
      Resource  = aws_secretsmanager_secret.scram[each.key].arn
    }]
  })
}

# Absent at stage brokers: associating a secret that holds no value fails. For the same reason a rotating principal's new user
# joins the list only once it is staged "associated", after its entry is filled. This resource owns the cluster's whole list.
resource "aws_msk_scram_secret_association" "this" {
  count = local.principals_exist ? 1 : 0

  cluster_arn     = aws_msk_cluster.this.arn
  secret_arn_list = [for user in sort(keys(local.scram_associated_users)) : aws_secretsmanager_secret.scram[user].arn]

  depends_on = [aws_secretsmanager_secret_policy.scram]
}

# Topics and ACLs are written through the brokers themselves, by the catalogue's administrator principal,
# which is why they wait for the principals to exist. A topic that leaves the catalogue stays until someone
# breaks glass: prevent_destroy here, and the administrator holds no Delete on any broker.
resource "kafka_topic" "catalogue" {
  for_each = local.principals_exist ? local.topics : {}

  name               = each.key
  partitions         = each.value.partitions
  replication_factor = each.value["replication-factor"]
  config             = each.value.config

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [aws_msk_scram_secret_association.this]
}

resource "kafka_acl" "catalogue" {
  for_each = local.principals_exist ? local.acl_grants : {}

  resource_name                = each.value.name
  resource_type                = each.value.type
  resource_pattern_type_filter = each.value.pattern
  acl_principal                = "User:${local.scram_users[each.value.user]}"
  acl_host                     = "*"
  acl_operation                = each.value.operation
  acl_permission_type          = "Allow"

  depends_on = [aws_msk_scram_secret_association.this]
}
