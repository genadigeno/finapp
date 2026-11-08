# The staging root module (ADR-0121). It composes the concern modules, and nothing here has ever been
# applied. The sizes and the Kubernetes minor below are reference values, confirmed against EKS and
# recalibrated by Phase 16's load model when an account exists.

module "conventions" {
  source = "../../modules/conventions"

  environment = "staging"
}

module "network" {
  source = "../../modules/network"

  name_prefix             = module.conventions.name_prefix
  region                  = var.region
  account_id              = var.account_id
  availability_zones      = var.availability_zones
  cidr_block              = "10.20.0.0/16"
  flow_log_retention_days = 90
}

module "kubernetes" {
  source = "../../modules/kubernetes"

  name_prefix                = module.conventions.name_prefix
  tags                       = module.conventions.tags
  kubernetes_version         = "1.34"
  private_subnet_ids_by_zone = module.network.private_subnet_ids_by_zone
  etcd_kms_key_arn           = var.etcd_kms_key_arn
  node_instance_types        = ["m7i.large"]
  node_root_volume_gib       = 50
  nodes_per_zone             = { min = 1, desired = 1, max = 2 }

  # Kept with no expiry: the audit class's period is a jurisdiction's (PHASE_15_PLAN.md C12), and none is invented here.
  control_plane_log_retention_days = 0
}
