# The production root module (ADR-0121). It composes the concern modules, and nothing here has ever been
# applied. The sizes and the Kubernetes minor below are reference values, confirmed against EKS and
# recalibrated by Phase 16's load model when an account exists.

module "conventions" {
  source = "../../modules/conventions"

  environment = "production"
}

module "network" {
  source = "../../modules/network"

  name_prefix             = module.conventions.name_prefix
  region                  = var.region
  account_id              = var.account_id
  availability_zones      = var.availability_zones
  cidr_block              = "10.10.0.0/16"
  flow_log_retention_days = 365
}

module "kubernetes" {
  source = "../../modules/kubernetes"

  name_prefix                = module.conventions.name_prefix
  tags                       = module.conventions.tags
  kubernetes_version         = "1.34"
  private_subnet_ids_by_zone = module.network.private_subnet_ids_by_zone
  etcd_kms_key_arn           = var.etcd_kms_key_arn
  node_instance_types        = ["m7i.xlarge"]
  node_root_volume_gib       = 100
  nodes_per_zone             = { min = 2, desired = 2, max = 4 }

  # Kept with no expiry: the audit class's period is a jurisdiction's (PHASE_15_PLAN.md C12), and none is invented here.
  control_plane_log_retention_days = 0
}

# THE ONE DECLARATION of the server's max_connections is this environment's overlay, the same literal the
# application reads (FINAPP_DB_SERVER_MAX_CONNECTIONS) and the manifest policy's budget counts. Read, not
# restated: the number appears nowhere under infra/terraform. A literal missing, duplicated or not a number
# stops the plan. It is static on the engine, so a change needs a reboot and an order: the postgres
# module's README.
locals {
  application_literals = flatten([
    for generator in yamldecode(file("${path.module}/../../../../deploy/overlays/production/kustomization.yaml")).configMapGenerator :
    generator.literals if generator.name == "finapp-app-config"
  ])

  server_max_connections = one([
    for literal in local.application_literals : tonumber(split("=", literal)[1])
    if startswith(literal, "FINAPP_DB_SERVER_MAX_CONNECTIONS=")
  ])
}

module "postgres" {
  source = "../../modules/postgres"

  name_prefix                = module.conventions.name_prefix
  region                     = var.region
  account_id                 = var.account_id
  vpc_id                     = module.network.vpc_id
  private_subnet_ids_by_zone = module.network.private_subnet_ids_by_zone
  client_security_group_id   = module.kubernetes.node_security_group_id

  instance_class            = "db.m7g.2xlarge"
  allocated_storage_gib     = 500
  max_allocated_storage_gib = 4000
  max_connections           = local.server_max_connections

  storage_kms_key_arn   = var.data_tier_kms_key_arns.database
  backup_kms_key_arn    = var.data_tier_kms_key_arns.database_backup
  secrets_kms_key_arn   = var.data_tier_kms_key_arns.secrets
  backup_copy_vault_arn = var.database_backup_copy_vault_arn

  backup_retention_days   = 35
  snapshot_retention_days = 35
}

module "kafka" {
  source = "../../modules/kafka"

  name_prefix                = module.conventions.name_prefix
  vpc_id                     = module.network.vpc_id
  private_subnet_ids_by_zone = module.network.private_subnet_ids_by_zone
  client_security_group_id   = module.kubernetes.node_security_group_id

  topic_catalogue_path   = "${path.module}/../../../kafka/topics.yaml"
  broker_properties_path = "${path.module}/../../../kafka/broker.properties"

  broker_instance_type      = "kafka.m7g.xlarge"
  broker_volume_gib         = 1000
  broker_log_retention_days = 365

  at_rest_kms_key_arn = var.data_tier_kms_key_arns.brokers
  scram_kms_key_arn   = var.data_tier_kms_key_arns.secrets
  provisioning_stage  = var.kafka_provisioning_stage
}

module "object_storage" {
  source = "../../modules/object-storage"

  name_prefix             = module.conventions.name_prefix
  account_id              = var.account_id
  state_kms_key_arn       = var.data_tier_kms_key_arns.state
  archive_kms_key_arn     = var.data_tier_kms_key_arns.archive
  audit_archive_lock      = var.audit_archive_lock
  archive_transition_days = 90
}
