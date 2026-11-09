# The brokers' properties, asserted over a plan against mock AWS and Kafka providers. A mock provider holds
# no credential and calls no API; every assertion reads what the plan would create.
#
# These runs read the real infra/kafka/topics.yaml and infra/kafka/broker.properties, because those are
# what the module must carry; the expected values are read from the same files, never restated, except
# where the assertion is the posture itself. One run gives the module two fixture files instead, which is
# what proves it reads the files it is given.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_security_group.brokers
    values = { id = "sg-brokers" }
  }

  override_resource {
    target = aws_msk_configuration.this
    values = {
      arn             = "arn:aws:kafka:eu-central-1:111111111111:configuration/mock"
      latest_revision = 7
    }
  }

  override_resource {
    target = aws_msk_cluster.this
    values = { arn = "arn:aws:kafka:eu-central-1:111111111111:cluster/mock" }
  }

  override_resource {
    target = aws_cloudwatch_log_group.brokers
    values = { arn = "arn:aws:logs:eu-central-1:111111111111:log-group:mock" }
  }

  override_resource {
    target = aws_secretsmanager_secret.scram["application"]
    values = { arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:application-mock" }
  }

  override_resource {
    target = aws_secretsmanager_secret.scram["administrator"]
    values = { arn = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:administrator-mock" }
  }
}

mock_provider "kafka" {}

variables {
  name_prefix                = "finapp-staging"
  vpc_id                     = "vpc-mock"
  private_subnet_ids_by_zone = { "eu-central-1a" = "subnet-a", "eu-central-1b" = "subnet-b", "eu-central-1c" = "subnet-c" }
  client_security_group_id   = "sg-nodes"
  topic_catalogue_path       = "../../../kafka/topics.yaml"
  broker_properties_path     = "../../../kafka/broker.properties"
  broker_instance_type       = "kafka.m7g.large"
  broker_volume_gib          = 500
  broker_log_retention_days  = 90
  at_rest_kms_key_arn        = "arn:aws:kms:eu-central-1:111111111111:key/11111111-1111-1111-1111-111111111111"
  scram_kms_key_arn          = "arn:aws:kms:eu-central-1:111111111111:key/22222222-2222-2222-2222-222222222222"
  provisioning_stage         = "enforced"
}

run "the_brokers_span_three_zones_on_the_given_subnets" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.number_of_broker_nodes == 3
    error_message = "There are not three brokers."
  }

  assert {
    condition     = toset(aws_msk_cluster.this.broker_node_group_info[0].client_subnets) == toset(["subnet-a", "subnet-b", "subnet-c"])
    error_message = "The brokers are not on exactly the three workload subnets they were given."
  }

  assert {
    condition     = aws_msk_cluster.this.broker_node_group_info[0].connectivity_info[0].public_access[0].type == "DISABLED"
    error_message = "The brokers can be given public addresses."
  }
}

run "a_fourth_zone_leaves_the_brokers_on_the_first_three" {
  command = plan

  variables {
    private_subnet_ids_by_zone = { "eu-central-1a" = "subnet-a", "eu-central-1b" = "subnet-b", "eu-central-1c" = "subnet-c", "eu-central-1d" = "subnet-d" }
  }

  assert {
    condition     = toset(output.broker_subnet_ids) == toset(["subnet-a", "subnet-b", "subnet-c"])
    error_message = "A fourth zone moved the brokers, or gave them a zone count MSK refuses."
  }
}

run "fewer_than_three_zones_are_refused" {
  command = plan

  variables {
    private_subnet_ids_by_zone = { "eu-central-1a" = "subnet-a", "eu-central-1b" = "subnet-b" }
  }

  expect_failures = [var.private_subnet_ids_by_zone]
}

run "only_tls_and_scram_reach_the_brokers" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.encryption_info[0].encryption_in_transit[0].client_broker == "TLS" && aws_msk_cluster.this.encryption_info[0].encryption_in_transit[0].in_cluster == true
    error_message = "A client or a broker can talk to a broker without TLS."
  }

  assert {
    condition     = aws_msk_cluster.this.client_authentication[0].unauthenticated == false
    error_message = "A client can reach the brokers unauthenticated."
  }

  assert {
    condition     = aws_msk_cluster.this.client_authentication[0].sasl[0].scram == true && aws_msk_cluster.this.client_authentication[0].sasl[0].iam == false
    error_message = "SCRAM is not the brokers' one authentication mechanism."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.from_clients.referenced_security_group_id == "sg-nodes" && aws_vpc_security_group_ingress_rule.from_clients.from_port == 9096 && aws_vpc_security_group_ingress_rule.from_clients.to_port == 9096 && aws_vpc_security_group_ingress_rule.from_clients.cidr_ipv4 == null
    error_message = "Something other than the cluster's nodes on the SASL_SSL port is admitted to the brokers."
  }

  assert {
    condition     = aws_msk_cluster.this.broker_node_group_info[0].security_groups == toset(["sg-brokers"]) && aws_security_group.brokers.vpc_id == "vpc-mock"
    error_message = "The brokers are not behind their own security group, in the given VPC."
  }
}

run "the_volumes_are_encrypted_under_the_given_key" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.encryption_info[0].encryption_at_rest_kms_key_arn == var.at_rest_kms_key_arn
    error_message = "The brokers' volumes are not encrypted under the key this module is given."
  }
}

run "the_cluster_uses_this_configuration_and_keeps_its_logs" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.configuration_info[0].arn == "arn:aws:kafka:eu-central-1:111111111111:configuration/mock" && aws_msk_cluster.this.configuration_info[0].revision == 7
    error_message = "The cluster does not run the latest revision of this configuration, so none of its settings apply."
  }

  assert {
    condition     = aws_msk_cluster.this.logging_info[0].broker_logs[0].cloudwatch_logs[0].enabled == true && aws_cloudwatch_log_group.brokers.deletion_protection_enabled == true
    error_message = "The brokers' own logs are off, or their group can be deleted."
  }
}

# Each line is compared whole, so min.insync.replicas=20 is not read as 2.
run "the_posture_the_clients_rely_on_is_the_clusters_configuration" {
  command = plan

  assert {
    condition     = contains(split("\n", output.server_properties), "min.insync.replicas=2")
    error_message = "min.insync.replicas is not 2: an acknowledged record could live on one broker."
  }

  assert {
    condition     = contains(split("\n", output.server_properties), "unclean.leader.election.enable=false")
    error_message = "Unclean leader election is on, which silently drops records a producer was told were written."
  }

  assert {
    condition     = contains(split("\n", output.server_properties), "auto.create.topics.enable=false")
    error_message = "A broker would create a topic because a client named it."
  }

  assert {
    condition     = contains(split("\n", output.server_properties), "allow.everyone.if.no.acl.found=false")
    error_message = "A principal reaches any resource no ACL names."
  }
}

run "every_broker_setting_is_sent_except_what_the_service_sets_itself" {
  command = plan

  assert {
    condition = toset(split("\n", output.server_properties)) == toset([
      for line in split("\n", file("../../../kafka/broker.properties")) : trimspace(line)
      if trimspace(line) != "" && !startswith(trimspace(line), "#") && !contains(output.settings_the_service_sets_itself, split("=", trimspace(line))[0])
    ])
    error_message = "The brokers' configuration is not exactly infra/kafka/broker.properties, less what the service sets itself."
  }

  assert {
    condition     = output.settings_the_service_sets_itself == toset(["sasl.enabled.mechanisms", "log.retention.check.interval.ms"])
    error_message = "The settings left to the service changed: each is a setting the file states and the brokers are not sent."
  }

  assert {
    condition     = alltrue([for key in output.settings_the_service_sets_itself : length(regexall("(?m)^${replace(key, ".", "\\.")}=", file("../../../kafka/broker.properties"))) == 1])
    error_message = "A setting recorded as the service's is not stated once in infra/kafka/broker.properties."
  }
}

run "every_catalogued_topic_is_planned_with_its_class_configuration" {
  command = plan

  assert {
    condition     = toset(keys(kafka_topic.catalogue)) == toset(keys(yamldecode(file("../../../kafka/topics.yaml")).topics))
    error_message = "The planned topics are not exactly the catalogue's."
  }

  assert {
    condition     = alltrue([for name, class in yamldecode(file("../../../kafka/topics.yaml")).topics : output.topics[name] == tomap(yamldecode(file("../../../kafka/topics.yaml")).classes[class].config)])
    error_message = "A topic's configuration, its retention among it, is not its class's in the catalogue."
  }

  assert {
    condition     = alltrue([for name, class in yamldecode(file("../../../kafka/topics.yaml")).topics : kafka_topic.catalogue[name].partitions == yamldecode(file("../../../kafka/topics.yaml")).classes[class].partitions && kafka_topic.catalogue[name].replication_factor == yamldecode(file("../../../kafka/topics.yaml")).classes[class]["replication-factor"]])
    error_message = "A topic's partitions or replication factor are not its class's in the catalogue."
  }

  assert {
    condition     = alltrue([for topic in kafka_topic.catalogue : topic.replication_factor == 3 && tonumber(topic.config["min.insync.replicas"]) == 2])
    error_message = "A topic does not keep three copies with two in sync before an acknowledgement."
  }
}

# Proof that the module reads the file it is given and has no list of its own: a catalogue in which nothing
# matches the real one must be what is planned.
run "the_module_reads_the_files_it_is_given_and_holds_no_copy" {
  command = plan

  variables {
    topic_catalogue_path   = "tests/fixture-topics.yaml"
    broker_properties_path = "tests/fixture-broker.properties"
  }

  assert {
    condition     = toset(keys(kafka_topic.catalogue)) == toset(["fixture.one"])
    error_message = "The module does not plan the catalogue it is given: it holds a copy."
  }

  assert {
    condition     = kafka_topic.catalogue["fixture.one"].config["retention.ms"] == "1234567" && kafka_topic.catalogue["fixture.one"].partitions == 2
    error_message = "A topic's retention and partitions do not come from the catalogue the module is given."
  }

  assert {
    condition     = toset(split("\n", output.server_properties)) == toset(["fixture.setting=left=right", "allow.everyone.if.no.acl.found=false"])
    error_message = "The brokers' configuration does not come from the file the module is given, or a value with an equals sign was cut."
  }

  assert {
    condition     = output.acl_keys == toset(["application/Topic/fixture.one/Read", "application/Group/fixture./Read"])
    error_message = "The ACLs do not come from the catalogue the module is given, or a principal with no cluster grant gained one."
  }
}

run "the_applications_acls_are_exactly_the_catalogues_by_literal_name" {
  command = plan

  assert {
    condition = toset([for key, acl in kafka_acl.catalogue : "${acl.resource_name}/${acl.acl_operation}" if startswith(key, "application/Topic/")]) == toset(flatten([
      for topic in keys(yamldecode(file("../../../kafka/topics.yaml")).topics) : [for operation in yamldecode(file("../../../kafka/topics.yaml")).principals.application.topics : "${topic}/${operation}"]
    ]))
    error_message = "The application's topic permissions are not exactly the catalogue's, per topic."
  }

  assert {
    condition     = alltrue([for key, acl in kafka_acl.catalogue : acl.resource_pattern_type_filter == "Literal" && acl.resource_name != "*" if startswith(key, "application/Topic/")])
    error_message = "The application holds a topic permission by pattern or wildcard, not by literal name."
  }

  assert {
    condition     = toset([for key, acl in kafka_acl.catalogue : "${acl.resource_name}/${acl.resource_pattern_type_filter}/${acl.acl_operation}" if startswith(key, "application/Group/")]) == toset(["finapp./Prefixed/Describe", "finapp./Prefixed/Read"])
    error_message = "The application's group permissions are not Describe and Read on the groups prefixed finapp."
  }

  assert {
    condition     = length([for key in keys(kafka_acl.catalogue) : key if startswith(key, "application/Cluster/")]) == 0
    error_message = "The application holds a permission on the cluster."
  }

  assert {
    condition     = alltrue([for key, acl in kafka_acl.catalogue : acl.acl_principal == "User:finapp-staging-application" if startswith(key, "application/")])
    error_message = "An application ACL names a principal other than the application's SCRAM user."
  }
}

run "the_provisioner_creates_and_configures_and_deletes_nothing" {
  command = plan

  assert {
    condition     = toset([for key, acl in kafka_acl.catalogue : acl.acl_operation if startswith(key, "administrator/Cluster/")]) == toset(["Alter", "Describe"])
    error_message = "The provisioner's cluster permissions are not Alter and Describe."
  }

  assert {
    condition     = toset([for key, acl in kafka_acl.catalogue : acl.acl_operation if startswith(key, "administrator/Topic/")]) == toset(yamldecode(file("../../../kafka/topics.yaml")).principals.administrator.topics)
    error_message = "The provisioner's topic permissions are not the catalogue's."
  }

  assert {
    condition     = length([for key in keys(kafka_acl.catalogue) : key if startswith(key, "administrator/Group/")]) == 0
    error_message = "The provisioner holds a permission on a consumer group."
  }

  assert {
    condition     = alltrue([for acl in kafka_acl.catalogue : !contains(["Delete", "All", "Any"], acl.acl_operation) && acl.acl_permission_type == "Allow"])
    error_message = "A principal may delete, or holds a blanket operation, or the ACL is not a plain grant."
  }
}

run "every_acl_is_planned_nothing_more" {
  command = plan

  assert {
    condition = length(kafka_acl.catalogue) == sum([
      for grants in yamldecode(file("../../../kafka/topics.yaml")).principals :
      length(yamldecode(file("../../../kafka/topics.yaml")).topics) * length(grants.topics) + length(try(grants.groups.operations, [])) + length(try(grants.cluster, []))
    ])
    error_message = "The ACLs planned are not one per principal, resource and operation the catalogue grants."
  }
}

run "the_scram_secrets_are_what_msk_requires_and_hold_no_value" {
  command = plan

  assert {
    condition     = alltrue([for secret in aws_secretsmanager_secret.scram : startswith(secret.name, "AmazonMSK_")])
    error_message = "A SCRAM secret's name does not start with AmazonMSK_, which MSK requires."
  }

  assert {
    condition     = alltrue([for secret in aws_secretsmanager_secret.scram : secret.kms_key_id == var.scram_kms_key_arn])
    error_message = "A SCRAM secret is not under the customer-managed key MSK requires."
  }

  assert {
    condition     = toset(keys(aws_secretsmanager_secret.scram)) == toset(keys(yamldecode(file("../../../kafka/topics.yaml")).principals))
    error_message = "The SCRAM secrets are not one per principal of the catalogue."
  }

  assert {
    condition     = alltrue([for policy in aws_secretsmanager_secret_policy.scram : jsondecode(policy.policy).Statement[0].Principal.Service == "kafka.amazonaws.com" && jsondecode(policy.policy).Statement[0].Action == "secretsmanager:getSecretValue"])
    error_message = "A SCRAM secret's policy does not let the brokers read it, or lets something else."
  }

  assert {
    condition     = toset(one(aws_msk_scram_secret_association.this).secret_arn_list) == toset(["arn:aws:secretsmanager:eu-central-1:111111111111:secret:application-mock", "arn:aws:secretsmanager:eu-central-1:111111111111:secret:administrator-mock"]) && one(aws_msk_scram_secret_association.this).cluster_arn == "arn:aws:kafka:eu-central-1:111111111111:cluster/mock"
    error_message = "The SCRAM users are not associated with this cluster."
  }
}

run "the_first_apply_reaches_for_no_broker_and_no_empty_secret" {
  command = plan

  variables {
    provisioning_stage = "brokers"
  }

  expect_failures = [check.the_catalogues_acls_are_enforced]

  assert {
    condition     = length(aws_msk_scram_secret_association.this) == 0
    error_message = "A secret that holds no value yet would be associated with the cluster, which fails."
  }

  assert {
    condition     = length(kafka_topic.catalogue) == 0 && length(kafka_acl.catalogue) == 0
    error_message = "The plan reaches for brokers that do not exist yet."
  }

  assert {
    condition     = length(aws_secretsmanager_secret.scram) == 2 && output.acls_enforced == false
    error_message = "The first apply does not create the entries the values go into."
  }
}

# At no stage before enforced may the brokers refuse the provisioner's first ACL, and only that key moves.
run "the_stage_that_writes_the_first_acls_admits_their_author" {
  command = plan

  variables {
    provisioning_stage = "principals"
  }

  expect_failures = [check.the_catalogues_acls_are_enforced]

  assert {
    condition     = contains(split("\n", output.server_properties), "allow.everyone.if.no.acl.found=true")
    error_message = "With no ACL yet and no super user on MSK, the provisioner is locked out of writing the first one."
  }

  assert {
    condition     = contains(split("\n", output.server_properties), "unclean.leader.election.enable=false") && contains(split("\n", output.server_properties), "auto.create.topics.enable=false") && contains(split("\n", output.server_properties), "min.insync.replicas=2")
    error_message = "A stage before enforced moved a setting other than the ACL default."
  }

  assert {
    condition     = length(aws_msk_scram_secret_association.this) == 1 && length(kafka_topic.catalogue) > 0 && length(kafka_acl.catalogue) > 0
    error_message = "The principals stage does not associate the users and write the topics and ACLs."
  }
}

run "an_alias_is_refused_for_the_brokers_key" {
  command = plan

  variables {
    at_rest_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:alias/brokers"
  }

  expect_failures = [var.at_rest_kms_key_arn]
}

run "an_alias_is_refused_for_the_scram_key" {
  command = plan

  variables {
    scram_kms_key_arn = "alias/aws/secretsmanager"
  }

  expect_failures = [var.scram_kms_key_arn]
}

run "an_unknown_stage_is_refused" {
  command = plan

  variables {
    provisioning_stage = "enforcing"
  }

  expect_failures = [var.provisioning_stage]
}

run "names_start_with_the_conventions_prefix" {
  command = plan

  assert {
    condition     = aws_msk_cluster.this.cluster_name == "finapp-staging-brokers" && aws_msk_configuration.this.name == "finapp-staging-brokers" && startswith(aws_security_group.brokers.name_prefix, "finapp-staging-") && aws_cloudwatch_log_group.brokers.name == "/finapp/finapp-staging/msk-brokers"
    error_message = "A name does not start with the conventions' prefix."
  }
}
