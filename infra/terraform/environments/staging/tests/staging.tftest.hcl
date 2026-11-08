# The staging root, planned against a mock AWS provider. A mock provider holds no credential and
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
}

variables {
  region             = "eu-central-1"
  account_id         = "111111111111"
  availability_zones = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]
  etcd_kms_key_arn   = "arn:aws:kms:eu-central-1:111111111111:key/11111111-2222-3333-4444-555555555555"
}

run "staging_names_and_tags_itself_staging" {
  command = plan

  assert {
    condition     = output.name_prefix == "finapp-staging"
    error_message = "The staging root names its resources finapp-staging."
  }

  assert {
    condition     = output.tags["Environment"] == "staging"
    error_message = "The staging root tags its resources Environment = staging."
  }

  assert {
    condition     = output.cluster_name == "finapp-staging"
    error_message = "The staging root's cluster is not named from the conventions' prefix."
  }
}

run "staging_places_the_cluster_on_the_workload_tier_alone" {
  command = plan

  assert {
    condition     = toset(module.kubernetes.cluster_subnet_ids) == toset(["subnet-private-a", "subnet-private-b", "subnet-private-c"])
    error_message = "The staging cluster is not on exactly the workload subnets."
  }

  assert {
    condition     = length(module.kubernetes.node_group_subnet_ids) == 3 && alltrue([for zone, subnets in module.kubernetes.node_group_subnet_ids : toset(subnets) == toset(["subnet-private-${substr(zone, -1, 1)}"])])
    error_message = "A staging node group is not on its own zone's workload subnet."
  }
}

run "staging_keeps_the_endpoint_private_and_encrypts_with_its_key_and_logs_the_control_plane" {
  command = plan

  assert {
    condition     = module.kubernetes.endpoint_public_access == false
    error_message = "The staging API endpoint has a public address."
  }

  assert {
    condition     = module.kubernetes.etcd_kms_key_arn == var.etcd_kms_key_arn
    error_message = "Staging's Secrets in etcd are not encrypted under staging's key."
  }

  assert {
    condition     = contains(module.kubernetes.control_plane_log_types, "audit")
    error_message = "Staging's control-plane audit log is off."
  }
}

run "an_account_id_that_is_not_one_is_refused" {
  command = plan

  variables {
    account_id = "staging"
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
    etcd_kms_key_arn = "arn:aws:kms:eu-central-1:222222222222:key/11111111-2222-3333-4444-555555555555"
  }

  expect_failures = [var.etcd_kms_key_arn]
}

run "a_key_of_another_region_is_refused" {
  command = plan

  variables {
    etcd_kms_key_arn = "arn:aws:kms:eu-west-1:111111111111:key/11111111-2222-3333-4444-555555555555"
  }

  expect_failures = [var.etcd_kms_key_arn]
}

run "an_alias_is_refused_for_the_key" {
  command = plan

  variables {
    etcd_kms_key_arn = "arn:aws:kms:eu-central-1:111111111111:alias/etcd"
  }

  expect_failures = [var.etcd_kms_key_arn]
}
