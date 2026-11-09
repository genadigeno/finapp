output "vpc_id" {
  description = "The VPC every other module's resources join."
  value       = aws_vpc.this.id
}

output "private_subnet_ids_by_zone" {
  description = "The workload subnets, by zone: the only subnets the cluster and its node groups are given."
  value       = { for zone, subnet in aws_subnet.private : zone => subnet.id }
}

output "public_subnet_ids_by_zone" {
  description = "The edge tier's subnets, by zone: NAT gateways and the public ingress's load balancer, never a workload."
  value       = { for zone, subnet in aws_subnet.public : zone => subnet.id }
}

output "log_group_kms_key_arn" {
  description = "The key of the flow log group, read from the plan."
  value       = aws_cloudwatch_log_group.flow_logs.kms_key_id
}
