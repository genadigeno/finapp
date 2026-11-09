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

  override_resource {
    target = module.kubernetes.aws_iam_openid_connect_provider.workload_identity
    values = { arn = "arn:aws:iam::222222222222:oidc-provider/oidc.example.invalid/id/mock" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["etcd"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000001" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["database"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000002" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["database_backup"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000003" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["secrets"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000004" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["field_keys"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000005" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["brokers"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000006" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["state"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000007" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["archive"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000008" }
  }

  override_resource {
    target = module.security.aws_kms_key.this["logs"]
    values = { arn = "arn:aws:kms:eu-central-1:222222222222:key/aaaaaaaa-0000-0000-0000-000000000009" }
  }

  override_resource {
    target = module.postgres.aws_db_instance.this
    values = {
      arn                = "arn:aws:rds:eu-central-1:222222222222:db:finapp-production-postgres"
      master_user_secret = [{ kms_key_id = "mock", secret_arn = "arn:aws:secretsmanager:eu-central-1:222222222222:secret:rds!db-mock-AbCdEf", secret_status = "active" }]
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
  database_backup_copy_vault_arn = "arn:aws:backup:eu-central-1:999999999999:backup-vault:finapp-copies"
  audit_archive_lock             = { mode = "GOVERNANCE", days = 42 }
  kafka_provisioning_stage       = "enforced"
  human_users = {
    alice = { roles = ["read-only", "break-glass"] }
    bob   = { roles = ["read-only"] }
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
    condition     = module.kafka.client_security_group_id == "sg-nodes"
    error_message = "Production's brokers do not admit the cluster's nodes, and only them."
  }

  assert {
    condition     = module.postgres.multi_az == true
    error_message = "Production's database has no synchronous standby."
  }
}

run "production_gives_each_store_the_key_of_its_purpose" {
  command = plan

  assert {
    condition     = module.postgres.storage_kms_key_arn == module.security.kms_key_arns["database"] && module.postgres.backup_vault_kms_key_arn == module.security.kms_key_arns["database_backup"]
    error_message = "Production's database or its backup vault is not under the key of its purpose."
  }

  assert {
    condition     = module.object_storage.encryption_key_arns["audit-archive"] == module.security.kms_key_arns["archive"] && module.object_storage.encryption_key_arns["terraform-state"] == module.security.kms_key_arns["state"]
    error_message = "Production's archive or state bucket is not under the key of its purpose."
  }

  assert {
    condition     = module.kubernetes.etcd_kms_key_arn == module.security.kms_key_arns["etcd"]
    error_message = "Production's Secrets in etcd are not under its etcd key."
  }
}

# The entries, against the document itself rather than through what the module read from it.
run "production_has_one_entry_per_row_the_inventory_delivers" {
  command = plan

  assert {
    condition = toset(values(module.security.secret_entry_names)) == toset(concat(
      [
        for cells in regexall("(?m)^\\| `([A-Z0-9_]+)` \\|.*`finapp-app-secrets`[^|]*\\|$", split("### 3.4 ", split("### 3.2 ", file("../../../../docs/architecture/SECRET_MANAGEMENT.md"))[1])[0]) :
        "finapp/production/app/${cells[0]}" if !startswith(cells[0], "FINAPP_KAFKA_SASL_")
      ],
      [
        for cells in regexall("(?m)^\\| `([A-Z0-9_]+)` \\|.*`finapp-migrator-secrets`[^|]*\\|$", split("### 3.4 ", split("### 3.2 ", file("../../../../docs/architecture/SECRET_MANAGEMENT.md"))[1])[0]) :
        "finapp/production/migrator/${cells[0]}"
      ],
    ))
    error_message = "Production's entries are not one per row the inventory delivers."
  }

  assert {
    condition = toset([for key, name in module.security.secret_entry_names : name if startswith(key, "app/")]) == toset([
      for item in yamldecode(file("../../../../deploy/components/secret-delivery/externalsecret-app.yaml")).spec.data : "finapp/production/app/${item.remoteRef.key}" if !can(item.sourceRef)
    ])
    error_message = "Production's application entries are not what its ExternalSecret reads."
  }

  assert {
    condition     = alltrue([for name in values(module.security.secret_entry_names) : !strcontains(name, "MASTER")])
    error_message = "Production has an entry for the master credential, which is RDS's own."
  }
}

run "production_leaves_the_brokers_entries_to_the_kafka_module_and_reads_only_the_applications" {
  command = plan

  assert {
    condition     = length(setintersection(toset(values(module.security.secret_entry_names)), toset(values(module.kafka.scram_secret_names)))) == 0
    error_message = "Production creates a SCRAM entry twice."
  }

  assert {
    condition     = contains(module.security.reader_entry_arns["app"], "arn:aws:secretsmanager:eu-central-1:222222222222:secret:${lookup(module.kafka.scram_secret_names, "application", "absent")}-??????")
    error_message = "Production's application reader cannot read the application's SCRAM entry."
  }

  assert {
    condition = alltrue([
      for arn in concat(module.security.reader_entry_arns["app"], module.security.reader_entry_arns["migrator"]) :
      !strcontains(arn, lookup(module.kafka.scram_secret_names, "administrator", "absent"))
    ])
    error_message = "A reader can read production's administrator SCRAM entry."
  }

  assert {
    condition     = alltrue([for arn in module.security.reader_entry_arns["migrator"] : !strcontains(arn, "AmazonMSK_")])
    error_message = "Production's migrator reader can read a SCRAM entry."
  }
}

run "production_trusts_the_readers_its_overlay_names" {
  command = plan

  assert {
    condition = alltrue([
      for workload in ["app", "migrator"] :
      module.security.reader_trusted_subjects[workload]["oidc.example.invalid/id/mock:sub"] == format(
        "system:serviceaccount:%s:%s",
        yamldecode(file("../../../../deploy/overlays/production/kustomization.yaml")).namespace,
        one([
          for document in split("\n---\n", file("../../../../deploy/overlays/production/secret-store.yaml")) :
          yamldecode(document).spec.provider.aws.auth.jwt.serviceAccountRef.name
          if yamldecode(document).kind == "SecretStore" && yamldecode(document).metadata.name == "finapp-${workload}-store"
        ]),
      )
    ])
    error_message = "Production's reader roles do not trust the ServiceAccounts its overlay names."
  }
}

run "production_gives_people_exactly_the_groups_the_manifests_bind" {
  command = plan

  assert {
    condition = toset(flatten(values(module.security.access_entry_groups))) == toset([
      for document in split("\n---\n", file("../../../../deploy/base/rbac-humans.yaml")) : yamldecode(document).subjects[0].name if yamldecode(document).kind == "RoleBinding"
    ])
    error_message = "Production's access entries are not the groups deploy/base/rbac-humans.yaml binds."
  }
}

run "production_keeps_every_log_group_and_telemetry_bucket_under_its_logs_key" {
  command = plan

  assert {
    condition     = toset([module.network.log_group_kms_key_arn, module.kubernetes.log_group_kms_key_arn, module.kafka.log_group_kms_key_arn]) == toset([module.security.kms_key_arns["logs"]])
    error_message = "One of Production's log groups is not under its logs key."
  }

  assert {
    condition     = length(module.observability.bucket_names) == 2
    error_message = "Production's telemetry buckets are not planned."
  }

  assert {
    condition     = module.observability.encryption_key_arn == module.security.kms_key_arns["logs"]
    error_message = "Production's telemetry buckets are not under its logs key."
  }
}

run "production_has_a_repository_per_image_its_overlay_runs" {
  command = plan

  assert {
    condition     = toset(keys(module.registry.repository_names)) == toset([for image in yamldecode(file("../../../../deploy/overlays/production/kustomization.yaml")).images : image.name])
    error_message = "Production's repositories are not the images its overlay names."
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
    condition     = module.kubernetes.etcd_kms_key_arn == module.security.kms_key_arns["etcd"]
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
