# The cluster's properties, asserted over a plan against a mock AWS provider. A mock provider holds no
# credential and calls no API; every assertion reads what the plan would create.
#
# A mock leaves computed attributes unknown at plan, so the identifiers a wiring assertion compares are
# given fixed values here. Every other value is read as the configuration sets it.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_eks_cluster.this
    values = {
      identity = [{ oidc = [{ issuer = "https://oidc.example.invalid/id/mock" }] }]
    }
  }

  override_resource {
    target = aws_iam_role.cluster
    values = {
      arn = "arn:aws:iam::111111111111:role/cluster"
      id  = "cluster"
    }
  }

  override_resource {
    target = aws_iam_role.node
    values = { arn = "arn:aws:iam::111111111111:role/node" }
  }

  override_resource {
    target = aws_launch_template.node
    values = {
      id             = "lt-mock"
      latest_version = 7
    }
  }
}

variables {
  name_prefix        = "finapp-staging"
  tags               = { Project = "finapp", Environment = "staging", ManagedBy = "terraform" }
  kubernetes_version = "1.34"
  private_subnet_ids_by_zone = {
    "eu-central-1a" = "subnet-private-a"
    "eu-central-1b" = "subnet-private-b"
    "eu-central-1c" = "subnet-private-c"
  }
  etcd_kms_key_arn                 = "arn:aws:kms:eu-central-1:111111111111:key/11111111-2222-3333-4444-555555555555"
  node_instance_types              = ["m7i.large"]
  node_root_volume_gib             = 50
  nodes_per_zone                   = { min = 1, desired = 1, max = 2 }
  control_plane_log_retention_days = 0
}

run "the_api_endpoint_is_private" {
  command = plan

  assert {
    condition     = aws_eks_cluster.this.vpc_config[0].endpoint_public_access == false
    error_message = "The API endpoint has a public address."
  }

  assert {
    condition     = aws_eks_cluster.this.vpc_config[0].endpoint_private_access == true
    error_message = "The API endpoint is not reachable from inside the VPC."
  }

  assert {
    condition     = toset(aws_eks_cluster.this.vpc_config[0].subnet_ids) == toset(["subnet-private-a", "subnet-private-b", "subnet-private-c"])
    error_message = "The control plane's network interfaces are not in exactly the workload subnets."
  }
}

run "secrets_in_etcd_are_encrypted_under_the_given_key" {
  command = plan

  assert {
    condition     = toset(try(aws_eks_cluster.this.encryption_config[0].resources, [])) == toset(["secrets"])
    error_message = "Kubernetes Secrets are not encrypted in etcd."
  }

  assert {
    condition     = try(aws_eks_cluster.this.encryption_config[0].provider[0].key_arn, "") == var.etcd_kms_key_arn
    error_message = "Secrets in etcd are not encrypted under the key this module is given."
  }

  assert {
    condition     = output.etcd_kms_key_arn == var.etcd_kms_key_arn
    error_message = "The module does not report the key the planned cluster uses."
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.cluster_etcd_key.policy).Statement[0].Resource == var.etcd_kms_key_arn && alltrue([for action in jsondecode(aws_iam_role_policy.cluster_etcd_key.policy).Statement[0].Action : startswith(action, "kms:") && !strcontains(action, "*")])
    error_message = "The control plane's role can use more than the etcd key."
  }
}

run "the_control_plane_audit_log_is_on" {
  command = plan

  assert {
    condition     = contains(aws_eks_cluster.this.enabled_cluster_log_types, "audit")
    error_message = "The control plane's audit log is off."
  }

  assert {
    condition     = toset(aws_eks_cluster.this.enabled_cluster_log_types) == toset(["api", "audit", "authenticator", "controllerManager", "scheduler"])
    error_message = "A control-plane log type is off."
  }

  assert {
    condition     = aws_cloudwatch_log_group.control_plane.name == "/aws/eks/finapp-staging/cluster"
    error_message = "The log group is not the one EKS writes this cluster's logs to."
  }

  assert {
    condition     = aws_cloudwatch_log_group.control_plane.retention_in_days == 0 && aws_cloudwatch_log_group.control_plane.deletion_protection_enabled == true
    error_message = "The control plane's log group does not keep its logs as declared, or can be deleted."
  }
}

run "every_zone_has_its_own_node_group_with_a_node" {
  command = plan

  assert {
    condition     = toset(keys(aws_eks_node_group.workers)) == toset(["eu-central-1a", "eu-central-1b", "eu-central-1c"])
    error_message = "There is not one node group per zone."
  }

  assert {
    condition     = alltrue([for zone, group in aws_eks_node_group.workers : group.subnet_ids == toset(["subnet-private-${substr(zone, -1, 1)}"])])
    error_message = "A node group is not confined to its own zone's workload subnet."
  }

  assert {
    condition     = alltrue([for group in aws_eks_node_group.workers : group.scaling_config[0].min_size >= 1 && group.scaling_config[0].desired_size >= 1])
    error_message = "A zone's node group can hold no node, so a zone topology spread cannot hold."
  }

  assert {
    condition     = alltrue([for group in aws_eks_node_group.workers : group.update_config[0].max_unavailable == 1])
    error_message = "A node group's update takes more than one node of a zone at a time."
  }
}

run "a_pod_cannot_reach_its_nodes_credentials_and_no_node_accepts_ssh" {
  command = plan

  assert {
    condition     = aws_launch_template.node.metadata_options[0].http_tokens == "required" && aws_launch_template.node.metadata_options[0].http_put_response_hop_limit == 1
    error_message = "A pod can reach the instance metadata service and so the node's credentials."
  }

  assert {
    condition     = alltrue([for group in aws_eks_node_group.workers : group.launch_template[0].id == "lt-mock" && group.launch_template[0].version == "7"])
    error_message = "A node group does not launch from the hardened template's latest version."
  }

  assert {
    condition     = aws_launch_template.node.key_name == null && alltrue([for group in aws_eks_node_group.workers : length(group.remote_access) == 0])
    error_message = "A node accepts SSH."
  }

  assert {
    condition     = alltrue([for mapping in aws_launch_template.node.block_device_mappings : mapping.ebs[0].encrypted == "true"]) && length(aws_launch_template.node.block_device_mappings) == 1
    error_message = "A node's root volume is not encrypted."
  }

  assert {
    condition     = alltrue([for specification in aws_launch_template.node.tag_specifications : specification.tags["Environment"] == "staging" && specification.tags["ManagedBy"] == "terraform" && specification.tags["Project"] == "finapp"])
    error_message = "A node's instance or volume does not carry the conventions' tags."
  }
}

run "workload_identity_is_the_clusters_oidc_issuer" {
  command = plan

  assert {
    condition     = aws_iam_openid_connect_provider.workload_identity.url == "https://oidc.example.invalid/id/mock"
    error_message = "The workload identity provider is not the cluster's OIDC issuer."
  }

  assert {
    condition     = aws_iam_openid_connect_provider.workload_identity.client_id_list == toset(["sts.amazonaws.com"])
    error_message = "The workload identity provider's audience is not STS alone."
  }

  assert {
    condition     = output.workload_identity_issuer == "https://oidc.example.invalid/id/mock"
    error_message = "The module does not expose the issuer a workload role's trust names."
  }
}

run "network_policy_is_enforced" {
  command = plan

  assert {
    condition     = aws_eks_addon.vpc_cni.addon_name == "vpc-cni" && jsondecode(aws_eks_addon.vpc_cni.configuration_values).enableNetworkPolicy == "true"
    error_message = "NetworkPolicy would be accepted and enforced by nothing: the VPC CNI's agent is off."
  }
}

run "access_is_explicit_and_the_cluster_cannot_be_deleted_by_a_plan" {
  command = plan

  assert {
    condition     = aws_eks_cluster.this.access_config[0].authentication_mode == "API" && aws_eks_cluster.this.access_config[0].bootstrap_cluster_creator_admin_permissions == false
    error_message = "The cluster's creator gains standing administrator access, or the legacy aws-auth path is open."
  }

  assert {
    condition     = aws_eks_cluster.this.deletion_protection == true
    error_message = "The cluster is not protected from deletion."
  }

  assert {
    condition     = toset([for attachment in aws_iam_role_policy_attachment.node : attachment.policy_arn]) == toset(["arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPullOnly", "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy", "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"])
    error_message = "The node role holds more or other than the three policies a managed node needs."
  }
}

run "names_start_with_the_conventions_prefix" {
  command = plan

  assert {
    condition     = aws_eks_cluster.this.name == "finapp-staging" && aws_iam_role.cluster.name == "finapp-staging-eks-cluster" && aws_iam_role.node.name == "finapp-staging-eks-node" && startswith(aws_launch_template.node.name_prefix, "finapp-staging-")
    error_message = "A name does not start with the conventions' prefix."
  }

  assert {
    condition     = alltrue([for zone, group in aws_eks_node_group.workers : group.node_group_name == "finapp-staging-${zone}"])
    error_message = "A node group's name does not start with the conventions' prefix."
  }
}

run "fewer_than_three_zones_are_refused" {
  command = plan

  variables {
    private_subnet_ids_by_zone = {
      "eu-central-1a" = "subnet-private-a"
      "eu-central-1b" = "subnet-private-b"
    }
  }

  expect_failures = [var.private_subnet_ids_by_zone]
}

run "a_zone_with_no_node_is_refused" {
  command = plan

  variables {
    nodes_per_zone = { min = 0, desired = 1, max = 2 }
  }

  expect_failures = [var.nodes_per_zone]
}

run "an_alias_is_refused_for_the_etcd_key" {
  command = plan

  variables {
    etcd_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:alias/etcd"
  }

  expect_failures = [var.etcd_kms_key_arn]
}

run "text_after_a_key_id_is_refused" {
  command = plan

  variables {
    etcd_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:key/11111111-2222-3333-4444-555555555555/other"
  }

  expect_failures = [var.etcd_kms_key_arn]
}

run "an_empty_etcd_key_is_refused" {
  command = plan

  variables {
    etcd_kms_key_arn = ""
  }

  expect_failures = [var.etcd_kms_key_arn]
}
