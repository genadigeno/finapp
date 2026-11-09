# The production root, planned against a mock AWS provider. A mock provider holds no credential and
# calls no API; TerraformIsPinnedAndCheckedTest refuses a test file that does not mock every provider
# its root requires, so no test here can reach a cloud.
#
# The concern modules' own tests assert each property inside the module. These runs assert what only
# the root decides: that it calls those modules, and with what. The subnets' identifiers are fixed so
# that the workload tier and the edge tier can be told apart.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = module.network.aws_subnet.private["eu-central-1a"]
    values = { id = "subnet-private-a" }
  }

  override_resource {
    target = module.network.aws_subnet.private["eu-central-1b"]
    values = { id = "subnet-private-b" }
  }

  override_resource {
    target = module.network.aws_subnet.private["eu-central-1c"]
    values = { id = "subnet-private-c" }
  }

  override_resource {
    target = module.network.aws_subnet.public["eu-central-1a"]
    values = { id = "subnet-public-a" }
  }

  override_resource {
    target = module.network.aws_subnet.public["eu-central-1b"]
    values = { id = "subnet-public-b" }
  }

  override_resource {
    target = module.network.aws_subnet.public["eu-central-1c"]
    values = { id = "subnet-public-c" }
  }

  override_resource {
    target = module.kubernetes.aws_eks_cluster.this
    values = {
      identity   = [{ oidc = [{ issuer = "https://oidc.example.invalid/id/mock" }] }]
      vpc_config = { cluster_security_group_id = "sg-nodes" }
    }
  }
}

mock_provider "kafka" {}

# The lock's mode and period below are test inputs and decide nothing: the audit class's period is OPEN
# until a real jurisdiction is chosen.
variables {
  region                         = "eu-central-1"
  account_id                     = "222222222222"
  availability_zones             = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]
  etcd_kms_key_arn               = "arn:aws:kms:eu-central-1:222222222222:key/11111111-2222-3333-4444-555555555555"
  database_backup_copy_vault_arn = "arn:aws:backup:eu-central-1:999999999999:backup-vault:finapp-copies"
  audit_archive_lock             = { mode = "GOVERNANCE", days = 42 }
  kafka_provisioning_stage       = "enforced"
  data_tier_kms_key_arns = {
    database        = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000001"
    database_backup = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000002"
    secrets         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000003"
    brokers         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000004"
    state           = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000005"
    archive         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000006"
  }
}

# Read from the overlay's own file, never restated: the two sides of the budget are the same bytes.
run "production_max_connections_is_the_overlays_declaration" {
  command = plan

  assert {
    condition = tonumber(module.postgres.max_connections) == one([
      for literal in flatten([for generator in yamldecode(file("../../../../deploy/overlays/production/kustomization.yaml")).configMapGenerator : generator.literals if generator.name == "finapp-app-config"]) :
      tonumber(split("=", literal)[1]) if startswith(literal, "FINAPP_DB_SERVER_MAX_CONNECTIONS=")
    ])
    error_message = "The database's max_connections is not what production's overlay declares to the application."
  }
}

run "production_keeps_the_data_tier_on_the_workload_tier_behind_the_nodes" {
  command = plan

  assert {
    condition     = module.postgres.subnet_group_subnet_ids == toset(["subnet-private-a", "subnet-private-b", "subnet-private-c"])
    error_message = "Production's database is not on exactly the workload subnets."
  }

  assert {
    condition     = toset(module.kafka.broker_subnet_ids) == toset(["subnet-private-a", "subnet-private-b", "subnet-private-c"])
    error_message = "Production's brokers are not on exactly the workload subnets."
  }

  assert {
    condition     = module.postgres.client_security_group_id == "sg-nodes"
    error_message = "Production's database does not admit the cluster's nodes, and only them."
  }

  assert {
    condition     = module.postgres.multi_az == true
    error_message = "Production's database has no synchronous standby."
  }
}

run "production_gives_each_store_its_own_key" {
  command = plan

  assert {
    condition     = module.postgres.storage_kms_key_arn == var.data_tier_kms_key_arns.database && module.postgres.backup_vault_kms_key_arn == var.data_tier_kms_key_arns.database_backup
    error_message = "Production's database or its backup vault is not under the key given for it."
  }

  assert {
    condition     = module.object_storage.encryption_key_arns["audit-archive"] == var.data_tier_kms_key_arns.archive && module.object_storage.encryption_key_arns["terraform-state"] == var.data_tier_kms_key_arns.state
    error_message = "Production's archive or state bucket is not under the key given for it."
  }
}

run "production_plans_the_catalogue_itself" {
  command = plan

  assert {
    condition     = toset(keys(module.kafka.topics)) == toset(keys(yamldecode(file("../../../kafka/topics.yaml")).topics))
    error_message = "Production's topics are not the catalogue's: the root passes the module something other than infra/kafka/topics.yaml."
  }

  assert {
    condition     = contains(split("\n", module.kafka.server_properties), "min.insync.replicas=2") && contains(split("\n", module.kafka.server_properties), "unclean.leader.election.enable=false")
    error_message = "Production's brokers do not carry the posture of infra/kafka/broker.properties."
  }

  assert {
    condition     = module.kafka.acls_enforced == true && contains(split("\n", module.kafka.server_properties), "allow.everyone.if.no.acl.found=false")
    error_message = "Production's finished stage leaves a resource with no ACL open to any principal."
  }
}

run "the_audit_archives_lock_reaches_the_bucket" {
  command = plan

  assert {
    condition     = endswith(module.object_storage.audit_archive_bucket_name, "-audit-archive") && startswith(module.object_storage.audit_archive_bucket_name, "finapp-production-222222222222-")
    error_message = "Production's audit archive is not named for this environment and account."
  }

  assert {
    condition     = module.object_storage.audit_archive_lock.mode == var.audit_archive_lock.mode && module.object_storage.audit_archive_lock.days == var.audit_archive_lock.days
    error_message = "Production's audit archive is not locked with the mode and period the root was given."
  }
}

run "a_data_tier_key_of_another_account_is_refused" {
  command = plan

  variables {
    data_tier_kms_key_arns = {
      database        = "arn:aws:kms:eu-central-1:111111111111:key/aaaaaaaa-0000-0000-0000-000000000001"
      database_backup = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000002"
      secrets         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000003"
      brokers         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000004"
      state           = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000005"
      archive         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000006"
    }
  }

  expect_failures = [var.data_tier_kms_key_arns]
}

run "the_databases_own_key_is_refused_for_the_archive" {
  command = plan

  variables {
    data_tier_kms_key_arns = {
      database        = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000001"
      database_backup = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000002"
      secrets         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000003"
      brokers         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000004"
      state           = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000005"
      archive         = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000001"
    }
  }

  expect_failures = [var.data_tier_kms_key_arns]
}

run "production_names_and_tags_itself_production" {
  command = plan

  assert {
    condition     = output.name_prefix == "finapp-production"
    error_message = "The production root names its resources finapp-production."
  }

  assert {
    condition     = output.tags["Environment"] == "production"
    error_message = "The production root tags its resources Environment = production."
  }

  assert {
    condition     = output.cluster_name == "finapp-production"
    error_message = "The production root's cluster is not named from the conventions' prefix."
  }
}

run "production_places_the_cluster_on_the_workload_tier_alone" {
  command = plan

  assert {
    condition     = toset(module.kubernetes.cluster_subnet_ids) == toset(["subnet-private-a", "subnet-private-b", "subnet-private-c"])
    error_message = "The production cluster is not on exactly the workload subnets."
  }

  assert {
    condition     = length(module.kubernetes.node_group_subnet_ids) == 3 && alltrue([for zone, subnets in module.kubernetes.node_group_subnet_ids : toset(subnets) == toset(["subnet-private-${substr(zone, -1, 1)}"])])
    error_message = "A production node group is not on its own zone's workload subnet."
  }
}

run "production_keeps_the_endpoint_private_and_encrypts_with_its_key_and_logs_the_control_plane" {
  command = plan

  assert {
    condition     = module.kubernetes.endpoint_public_access == false
    error_message = "The production API endpoint has a public address."
  }

  assert {
    condition     = module.kubernetes.etcd_kms_key_arn == var.etcd_kms_key_arn
    error_message = "Production's Secrets in etcd are not encrypted under production's key."
  }

  assert {
    condition     = contains(module.kubernetes.control_plane_log_types, "audit")
    error_message = "Production's control-plane audit log is off."
  }
}

run "an_account_id_that_is_not_one_is_refused" {
  command = plan

  variables {
    account_id = "production"
  }

  expect_failures = [var.account_id]
}

run "fewer_than_three_zones_are_refused" {
  command = plan

  variables {
    availability_zones = ["eu-central-1a", "eu-central-1b"]
  }

  expect_failures = [var.availability_zones]
}

run "a_zone_named_twice_counts_once" {
  command = plan

  variables {
    availability_zones = ["eu-central-1a", "eu-central-1b", "eu-central-1b"]
  }

  expect_failures = [var.availability_zones]
}

run "a_zone_of_another_region_is_refused" {
  command = plan

  variables {
    availability_zones = ["eu-central-1a", "eu-central-1b", "eu-west-1c"]
  }

  expect_failures = [var.availability_zones]
}

run "a_key_of_another_account_is_refused" {
  command = plan

  variables {
    etcd_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:key/11111111-2222-3333-4444-555555555555"
  }

  expect_failures = [var.etcd_kms_key_arn]
}

run "a_key_of_another_region_is_refused" {
  command = plan

  variables {
    etcd_kms_key_arn = "arn:aws:kms:eu-west-1:222222222222:key/11111111-2222-3333-4444-555555555555"
  }

  expect_failures = [var.etcd_kms_key_arn]
}

run "an_alias_is_refused_for_the_key" {
  command = plan

  variables {
    etcd_kms_key_arn = "arn:aws:kms:eu-central-1:222222222222:alias/etcd"
  }

  expect_failures = [var.etcd_kms_key_arn]
}
