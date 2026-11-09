output "bootstrap_brokers_sasl_scram" {
  description = "The brokers' SASL_SSL addresses, for the Kafka provider's configuration in the root and the application's secret."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
}

output "broker_subnet_ids" {
  description = "The subnets the brokers are planned on, read from the plan."
  value       = aws_msk_cluster.this.broker_node_group_info[0].client_subnets
}

output "client_security_group_id" {
  description = "The one security group admitted to the brokers' port, read from the planned rule."
  value       = aws_vpc_security_group_ingress_rule.from_clients.referenced_security_group_id
}

output "server_properties" {
  description = "The brokers' configuration, as planned."
  value       = aws_msk_configuration.this.server_properties
}

output "settings_the_service_sets_itself" {
  description = "The keys of broker.properties this module does not send, because MSK sets them itself."
  value       = local.set_by_the_service
}

output "scram_users" {
  description = "Each catalogue principal's SCRAM username: the username its secret must hold."
  value       = local.scram_users
}

output "scram_secret_names" {
  description = "Each catalogue principal's Secrets Manager entry, by name."
  value       = { for principal, secret in aws_secretsmanager_secret.scram : principal => secret.name }
}

output "topics" {
  description = "Every topic planned, with its planned configuration."
  value       = { for name, topic in kafka_topic.catalogue : name => topic.config }
}

output "acl_keys" {
  description = "Every ACL planned, as principal/type/name/operation."
  value       = toset(keys(kafka_acl.catalogue))
}

output "acls_enforced" {
  description = "Whether the brokers refuse a principal on a resource no ACL names: true only at stage enforced."
  value       = local.acl_enforced
}

output "log_group_kms_key_arn" {
  description = "The key of the broker log group, read from the plan."
  value       = aws_cloudwatch_log_group.brokers.kms_key_id
}
