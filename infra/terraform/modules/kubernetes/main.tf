# The managed cluster (ADR-0121, PHASE_15_PLAN.md section 7). Never applied:
# tests/kubernetes.tftest.hcl asserts every property below over a plan against a mock AWS provider.
#
# Deliberately not variables, so no environment can turn them off:
#   the API endpoint is private and has no public address;
#   Kubernetes Secrets are envelope-encrypted in etcd under the customer-managed key this module is given;
#   every control-plane log type is on, the audit log among them;
#   each zone has its own node group holding at least one node;
#   a pod cannot reach its node's credentials (IMDSv2, one network hop) and no node accepts SSH;
#   NetworkPolicy is enforced by the VPC CNI, which by default leaves it unenforced;
#   the cluster's access goes through access entries alone, and whoever creates it gains no standing
#   administrator access from having done so.
#
# Keys and workload roles are created elsewhere. This module exposes what they need: the OIDC provider
# a workload's role trusts.

locals {
  cluster_name = var.name_prefix

  # EKS writes to exactly this group. It is declared before the cluster so that it exists with its
  # retention, rather than EKS creating it with none.
  control_plane_log_group_name = "/aws/eks/${local.cluster_name}/cluster"

  control_plane_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  node_managed_policies = toset([
    "AmazonEC2ContainerRegistryPullOnly",
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
  ])
}

# ------------------------------------------------------------------------------------------------
# The control plane.
# ------------------------------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "control_plane" {
  name                        = local.control_plane_log_group_name
  retention_in_days           = var.control_plane_log_retention_days
  kms_key_id                  = var.control_plane_log_kms_key_arn
  deletion_protection_enabled = true
}

resource "aws_iam_role" "cluster" {
  name = "${var.name_prefix}-eks-cluster"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "eks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

# The control plane encrypts and decrypts with the etcd key, and with that key alone.
resource "aws_iam_role_policy" "cluster_etcd_key" {
  name = "use-the-etcd-key"
  role = aws_iam_role.cluster.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["kms:CreateGrant", "kms:Decrypt", "kms:DescribeKey", "kms:Encrypt", "kms:ListGrants"]
      Resource = var.etcd_kms_key_arn
    }]
  })
}

resource "aws_eks_cluster" "this" {
  name                      = local.cluster_name
  version                   = var.kubernetes_version
  role_arn                  = aws_iam_role.cluster.arn
  enabled_cluster_log_types = local.control_plane_log_types
  deletion_protection       = true

  vpc_config {
    subnet_ids              = values(var.private_subnet_ids_by_zone)
    endpoint_private_access = true
    endpoint_public_access  = false
  }

  encryption_config {
    resources = ["secrets"]

    provider {
      key_arn = var.etcd_kms_key_arn
    }
  }

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = false
  }

  depends_on = [
    aws_cloudwatch_log_group.control_plane,
    aws_iam_role_policy.cluster_etcd_key,
    aws_iam_role_policy_attachment.cluster,
  ]
}

# ------------------------------------------------------------------------------------------------
# Workload identity: a workload's role trusts this provider, one role per workload.
# ------------------------------------------------------------------------------------------------

resource "aws_iam_openid_connect_provider" "workload_identity" {
  url            = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list = ["sts.amazonaws.com"]

  tags = {
    Name = "${var.name_prefix}-workload-identity"
  }
}

# The network policy agent is off by default: without this, a NetworkPolicy is accepted and enforced by nothing.
resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = "vpc-cni"
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  configuration_values = jsonencode({
    enableNetworkPolicy = "true"
  })
}

# ------------------------------------------------------------------------------------------------
# The nodes: one managed node group per zone.
# ------------------------------------------------------------------------------------------------

resource "aws_iam_role" "node" {
  name = "${var.name_prefix}-eks-node"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = local.node_managed_policies

  role       = aws_iam_role.node.name
  policy_arn = "arn:aws:iam::aws:policy/${each.key}"
}

resource "aws_launch_template" "node" {
  name_prefix            = "${var.name_prefix}-node-"
  update_default_version = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      encrypted             = "true"
      volume_type           = "gp3"
      volume_size           = var.node_root_volume_gib
      delete_on_termination = "true"
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = merge(var.tags, { Name = "${var.name_prefix}-node" })
  }

  tag_specifications {
    resource_type = "volume"
    tags          = merge(var.tags, { Name = "${var.name_prefix}-node" })
  }
}

resource "aws_eks_node_group" "workers" {
  for_each = var.private_subnet_ids_by_zone

  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.name_prefix}-${each.key}"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = [each.value]
  version         = var.kubernetes_version
  ami_type        = "AL2023_x86_64_STANDARD"
  capacity_type   = "ON_DEMAND"
  instance_types  = var.node_instance_types

  scaling_config {
    min_size     = var.nodes_per_zone.min
    desired_size = var.nodes_per_zone.desired
    max_size     = var.nodes_per_zone.max
  }

  update_config {
    max_unavailable = 1
  }

  launch_template {
    id      = aws_launch_template.node.id
    version = aws_launch_template.node.latest_version
  }

  depends_on = [aws_eks_addon.vpc_cni, aws_iam_role_policy_attachment.node]
}
