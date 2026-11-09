output "cluster_name" {
  description = "The cluster's name."
  value       = aws_eks_cluster.this.name
}

output "endpoint_public_access" {
  description = "Whether the API endpoint has a public address, read from the planned cluster."
  value       = aws_eks_cluster.this.vpc_config[0].endpoint_public_access
}

output "cluster_subnet_ids" {
  description = "The subnets of the control plane's network interfaces, read from the planned cluster."
  value       = aws_eks_cluster.this.vpc_config[0].subnet_ids
}

output "node_group_subnet_ids" {
  description = "Each zone's node group's subnets, read from the planned node groups."
  value       = { for zone, group in aws_eks_node_group.workers : zone => group.subnet_ids }
}

output "etcd_kms_key_arn" {
  description = "The key encrypting Secrets in etcd, read from the planned cluster."
  value       = try(aws_eks_cluster.this.encryption_config[0].provider[0].key_arn, null)
}

output "control_plane_log_types" {
  description = "The control-plane log types enabled, read from the planned cluster."
  value       = aws_eks_cluster.this.enabled_cluster_log_types
}

output "workload_identity_provider_arn" {
  description = "The OIDC provider a workload's IAM role trusts."
  value       = aws_iam_openid_connect_provider.workload_identity.arn
}

output "workload_identity_issuer" {
  description = "The cluster's OIDC issuer, whose host and path a workload role's trust conditions name."
  value       = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

output "cluster_role_arn" {
  description = "The control plane's IAM role, the principal the etcd key's policy admits."
  value       = aws_iam_role.cluster.arn
}

output "node_role_arn" {
  description = "The nodes' IAM role."
  value       = aws_iam_role.node.arn
}

output "node_security_group_id" {
  description = "The security group EKS attaches to the managed nodes, the source the data tier admits. Every pod on a node shares it, so it does not name a workload: the namespace's NetworkPolicy does."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "log_group_kms_key_arn" {
  description = "The key of the control-plane log group, read from the plan."
  value       = aws_cloudwatch_log_group.control_plane.kms_key_id
}
