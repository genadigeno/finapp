# The network's properties, asserted over a plan against a mock AWS provider. A mock provider holds no
# credential and calls no API; every assertion reads what the plan would create.
#
# A mock leaves computed attributes unknown at plan, so the identifiers a wiring assertion compares are
# given fixed values here, one per zone where the assertion is about a zone. Every other value is read
# as the configuration sets it.

mock_provider "aws" {
  override_during = plan

  override_resource {
    target = aws_vpc.this
    values = { id = "vpc-mock" }
  }

  override_resource {
    target = aws_internet_gateway.this
    values = { id = "igw-mock" }
  }

  override_resource {
    target = aws_subnet.private["eu-central-1a"]
    values = { id = "subnet-private-a" }
  }

  override_resource {
    target = aws_subnet.private["eu-central-1b"]
    values = { id = "subnet-private-b" }
  }

  override_resource {
    target = aws_subnet.private["eu-central-1c"]
    values = { id = "subnet-private-c" }
  }

  override_resource {
    target = aws_subnet.public["eu-central-1a"]
    values = { id = "subnet-public-a" }
  }

  override_resource {
    target = aws_subnet.public["eu-central-1b"]
    values = { id = "subnet-public-b" }
  }

  override_resource {
    target = aws_subnet.public["eu-central-1c"]
    values = { id = "subnet-public-c" }
  }

  override_resource {
    target = aws_route_table.private["eu-central-1a"]
    values = { id = "rtb-private-a" }
  }

  override_resource {
    target = aws_route_table.private["eu-central-1b"]
    values = { id = "rtb-private-b" }
  }

  override_resource {
    target = aws_route_table.private["eu-central-1c"]
    values = { id = "rtb-private-c" }
  }

  override_resource {
    target = aws_route_table.public
    values = { id = "rtb-public" }
  }

  override_resource {
    target = aws_nat_gateway.this["eu-central-1a"]
    values = { id = "nat-a" }
  }

  override_resource {
    target = aws_nat_gateway.this["eu-central-1b"]
    values = { id = "nat-b" }
  }

  override_resource {
    target = aws_nat_gateway.this["eu-central-1c"]
    values = { id = "nat-c" }
  }

  override_resource {
    target = aws_security_group.endpoints
    values = { id = "sg-endpoints" }
  }

  override_resource {
    target = aws_cloudwatch_log_group.flow_logs
    values = { arn = "arn:aws:logs:eu-central-1:111111111111:log-group:mock" }
  }

  override_resource {
    target = aws_iam_role.flow_logs
    values = {
      arn = "arn:aws:iam::111111111111:role/mock"
      id  = "mock"
    }
  }
}

variables {
  name_prefix             = "finapp-staging"
  region                  = "eu-central-1"
  account_id              = "111111111111"
  availability_zones      = ["eu-central-1a", "eu-central-1b", "eu-central-1c"]
  cidr_block              = "10.20.0.0/16"
  flow_log_retention_days = 90
}

run "no_workload_subnet_is_public" {
  command = plan

  assert {
    condition     = length(aws_subnet.private) == 3 && alltrue([for subnet in aws_subnet.private : subnet.map_public_ip_on_launch == false])
    error_message = "A workload subnet assigns a public address on launch."
  }

  assert {
    condition     = length(aws_route.private_egress) == 3 && alltrue([for route in aws_route.private_egress : route.gateway_id == null && route.destination_cidr_block == "0.0.0.0/0"])
    error_message = "A workload subnet's default route reaches the internet gateway."
  }

  assert {
    condition     = alltrue([for zone, route in aws_route.private_egress : route.route_table_id == "rtb-private-${substr(zone, -1, 1)}" && route.nat_gateway_id == "nat-${substr(zone, -1, 1)}"])
    error_message = "A workload subnet's default route does not leave through the NAT gateway of its own zone."
  }

  assert {
    condition     = length(aws_route_table_association.private) == 3 && alltrue([for zone, association in aws_route_table_association.private : association.subnet_id == "subnet-private-${substr(zone, -1, 1)}" && association.route_table_id == "rtb-private-${substr(zone, -1, 1)}"])
    error_message = "A workload subnet is associated with a route table other than its own private one."
  }

  assert {
    condition     = tomap(output.private_subnet_ids_by_zone) == tomap({ "eu-central-1a" = "subnet-private-a", "eu-central-1b" = "subnet-private-b", "eu-central-1c" = "subnet-private-c" })
    error_message = "The subnets this module hands to the cluster are not exactly the private tier."
  }
}

run "the_public_tier_gives_nothing_a_public_address_and_holds_the_only_internet_route" {
  command = plan

  assert {
    condition     = length(aws_subnet.public) == 3 && alltrue([for subnet in aws_subnet.public : subnet.map_public_ip_on_launch == false])
    error_message = "The edge tier assigns a public address on launch."
  }

  assert {
    condition     = aws_route.public_internet.gateway_id == "igw-mock" && aws_route.public_internet.route_table_id == "rtb-public"
    error_message = "The internet gateway's route is not on the edge tier's route table."
  }

  assert {
    condition     = length(aws_route_table_association.public) == 3 && alltrue([for zone, association in aws_route_table_association.public : association.subnet_id == "subnet-public-${substr(zone, -1, 1)}" && association.route_table_id == "rtb-public"])
    error_message = "An edge-tier subnet is not on the edge tier's route table."
  }
}

run "the_addresses_are_the_plan_and_do_not_overlap" {
  command = plan

  assert {
    condition     = aws_vpc.this.cidr_block == "10.20.0.0/16" && aws_vpc.this.enable_dns_support == true && aws_vpc.this.enable_dns_hostnames == true
    error_message = "The VPC's range or its DNS settings are not the plan's."
  }

  assert {
    condition     = toset([for subnet in aws_subnet.private : subnet.cidr_block]) == toset(["10.20.0.0/20", "10.20.16.0/20", "10.20.32.0/20"])
    error_message = "The workload subnets are not a /20 per zone from the start of the range."
  }

  assert {
    condition     = toset([for subnet in aws_subnet.public : subnet.cidr_block]) == toset(["10.20.160.0/24", "10.20.161.0/24", "10.20.162.0/24"])
    error_message = "The edge tier is not a /24 per zone, clear of the workload subnets."
  }

  assert {
    condition     = aws_subnet.private["eu-central-1b"].availability_zone == "eu-central-1b" && aws_subnet.public["eu-central-1c"].availability_zone == "eu-central-1c"
    error_message = "A subnet is not in the zone it is named for."
  }
}

run "egress_is_a_nat_gateway_per_zone" {
  command = plan

  assert {
    condition     = length(aws_nat_gateway.this) == 3 && length(aws_eip.nat) == 3 && alltrue([for zone, nat in aws_nat_gateway.this : nat.subnet_id == "subnet-public-${substr(zone, -1, 1)}"])
    error_message = "NAT egress is not one gateway per zone, each in that zone's edge subnet."
  }
}

run "the_cloud_apis_are_reached_through_vpc_endpoints" {
  command = plan

  assert {
    condition     = toset(keys(aws_vpc_endpoint.interface)) == toset(["ec2", "ecr.api", "ecr.dkr", "kms", "logs", "secretsmanager", "sts"])
    error_message = "The interface endpoints are not the cloud APIs the platform calls."
  }

  assert {
    condition     = alltrue([for service, endpoint in aws_vpc_endpoint.interface : endpoint.service_name == "com.amazonaws.eu-central-1.${service}" && endpoint.vpc_endpoint_type == "Interface" && endpoint.private_dns_enabled == true])
    error_message = "An interface endpoint names the wrong service, or does not take over the service's DNS name."
  }

  assert {
    condition     = alltrue([for endpoint in aws_vpc_endpoint.interface : endpoint.vpc_id == "vpc-mock" && endpoint.subnet_ids == toset(["subnet-private-a", "subnet-private-b", "subnet-private-c"]) && endpoint.security_group_ids == toset(["sg-endpoints"])])
    error_message = "An interface endpoint is not in every workload subnet, or not behind the endpoints' security group."
  }

  assert {
    condition     = aws_vpc_security_group_ingress_rule.endpoints_https.cidr_ipv4 == "10.20.0.0/16" && aws_vpc_security_group_ingress_rule.endpoints_https.from_port == 443 && aws_vpc_security_group_ingress_rule.endpoints_https.to_port == 443
    error_message = "The endpoints accept something other than HTTPS from inside the VPC."
  }

  assert {
    condition     = aws_vpc_endpoint.s3.service_name == "com.amazonaws.eu-central-1.s3" && aws_vpc_endpoint.s3.vpc_endpoint_type == "Gateway" && aws_vpc_endpoint.s3.route_table_ids == toset(["rtb-private-a", "rtb-private-b", "rtb-private-c"])
    error_message = "The S3 gateway endpoint is not on every workload route table."
  }
}

run "every_flow_in_the_vpc_is_logged" {
  command = plan

  assert {
    condition     = aws_flow_log.vpc.vpc_id == "vpc-mock" && aws_flow_log.vpc.traffic_type == "ALL"
    error_message = "The VPC's flow log does not cover every flow, accepted and rejected."
  }

  assert {
    condition     = aws_flow_log.vpc.log_destination_type == "cloud-watch-logs" && aws_flow_log.vpc.log_destination == "arn:aws:logs:eu-central-1:111111111111:log-group:mock" && aws_flow_log.vpc.iam_role_arn == "arn:aws:iam::111111111111:role/mock"
    error_message = "The flow log does not write to its log group through its role."
  }

  assert {
    condition     = aws_cloudwatch_log_group.flow_logs.name == "/finapp/finapp-staging/vpc-flow-logs" && aws_cloudwatch_log_group.flow_logs.retention_in_days == 90 && aws_cloudwatch_log_group.flow_logs.deletion_protection_enabled == true
    error_message = "The flow log group's name, retention or deletion protection is not as declared."
  }

  assert {
    condition     = jsondecode(aws_iam_role.flow_logs.assume_role_policy).Statement[0].Principal.Service == "vpc-flow-logs.amazonaws.com" && jsondecode(aws_iam_role.flow_logs.assume_role_policy).Statement[0].Condition.StringEquals["aws:SourceAccount"] == "111111111111"
    error_message = "The flow-log role is assumable by something other than this account's flow logs."
  }

  assert {
    condition     = alltrue([for statement in jsondecode(aws_iam_role_policy.flow_logs.policy).Statement : startswith(statement.Resource, "arn:aws:logs:eu-central-1:111111111111:log-group:/finapp/finapp-staging/vpc-flow-logs") && alltrue([for action in statement.Action : startswith(action, "logs:") && !strcontains(action, "*")])])
    error_message = "The flow-log role can do more than write to its own log group."
  }
}

run "names_start_with_the_conventions_prefix" {
  command = plan

  assert {
    condition     = aws_vpc.this.tags["Name"] == "finapp-staging-vpc" && aws_iam_role.flow_logs.name == "finapp-staging-vpc-flow-logs" && startswith(aws_security_group.endpoints.name_prefix, "finapp-staging-")
    error_message = "A name does not start with the conventions' prefix."
  }

  assert {
    condition     = alltrue([for subnet in concat(values(aws_subnet.private), values(aws_subnet.public)) : startswith(subnet.tags["Name"], "finapp-staging-")])
    error_message = "A subnet's name does not start with the conventions' prefix."
  }
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

run "a_public_address_range_is_refused" {
  command = plan

  variables {
    cidr_block = "54.20.0.0/16"
  }

  expect_failures = [var.cidr_block]
}
