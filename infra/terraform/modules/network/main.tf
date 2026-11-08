# The platform's private network (ADR-0121, PHASE_15_PLAN.md section 7). Never applied:
# tests/network.tftest.hcl asserts every property below over a plan against a mock AWS provider.
#
# Two tiers per zone, at least three zones:
#   private  every workload subnet. No route to the internet gateway and no public address on launch;
#            the default route leaves through the NAT gateway of its own zone.
#   public   the edge tier: the NAT gateways and the load balancer of the public ingress, nothing else.
#            The kubernetes module is given the private tier only.
#
# One NAT gateway per zone, in staging as in production: a zone's egress does not depend on another
# zone, and staging rehearses production. The cloud APIs the cluster and the workloads call are reached
# through VPC endpoints, inside the VPC. Every flow is logged.

locals {
  # Addressing follows a zone's position in the list, so a zone added at the end moves no existing subnet.
  zone_index = { for index, zone in var.availability_zones : zone => index }

  # The cloud APIs reached over interface endpoints: pulling an image (ecr.api, ecr.dkr), workload
  # identity (sts), logs (logs), a node's network interfaces (ec2), secrets and keys (secretsmanager, kms).
  interface_endpoints = toset(["ec2", "ecr.api", "ecr.dkr", "kms", "logs", "secretsmanager", "sts"])

  flow_log_group_name = "/finapp/${var.name_prefix}/vpc-flow-logs"
  flow_log_group_arn  = "arn:aws:logs:${var.region}:${var.account_id}:log-group:${local.flow_log_group_name}"
}

resource "aws_vpc" "this" {
  cidr_block           = var.cidr_block
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.name_prefix}-vpc"
  }
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-igw"
  }
}

# ------------------------------------------------------------------------------------------------
# The private tier: every workload subnet.
# ------------------------------------------------------------------------------------------------

resource "aws_subnet" "private" {
  for_each = local.zone_index

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.cidr_block, 4, each.value)
  map_public_ip_on_launch = false

  tags = {
    Name                              = "${var.name_prefix}-private-${each.key}"
    "kubernetes.io/role/internal-elb" = "1"
  }
}

resource "aws_route_table" "private" {
  for_each = local.zone_index

  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-private-${each.key}"
  }
}

resource "aws_route" "private_egress" {
  for_each = local.zone_index

  route_table_id         = aws_route_table.private[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[each.key].id
}

resource "aws_route_table_association" "private" {
  for_each = local.zone_index

  subnet_id      = aws_subnet.private[each.key].id
  route_table_id = aws_route_table.private[each.key].id
}

# ------------------------------------------------------------------------------------------------
# The public tier: the NAT gateways and the public ingress's load balancer, and no workload.
# ------------------------------------------------------------------------------------------------

resource "aws_subnet" "public" {
  for_each = local.zone_index

  vpc_id                  = aws_vpc.this.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.cidr_block, 8, 160 + each.value)
  map_public_ip_on_launch = false

  tags = {
    Name                     = "${var.name_prefix}-public-${each.key}"
    "kubernetes.io/role/elb" = "1"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-public"
  }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  for_each = local.zone_index

  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  for_each = local.zone_index

  domain = "vpc"

  tags = {
    Name = "${var.name_prefix}-nat-${each.key}"
  }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  for_each = local.zone_index

  subnet_id     = aws_subnet.public[each.key].id
  allocation_id = aws_eip.nat[each.key].id

  tags = {
    Name = "${var.name_prefix}-nat-${each.key}"
  }

  depends_on = [aws_internet_gateway.this]
}

# ------------------------------------------------------------------------------------------------
# VPC endpoints: the cloud APIs, reached inside the VPC.
# ------------------------------------------------------------------------------------------------

resource "aws_security_group" "endpoints" {
  name_prefix = "${var.name_prefix}-endpoints-"
  description = "The interface VPC endpoints: HTTPS from inside the VPC only."
  vpc_id      = aws_vpc.this.id

  tags = {
    Name = "${var.name_prefix}-endpoints"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "endpoints_https" {
  security_group_id = aws_security_group.endpoints.id
  description       = "HTTPS to the VPC endpoints from inside the VPC."
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = var.cidr_block
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.interface_endpoints

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = [for subnet in aws_subnet.private : subnet.id]
  security_group_ids  = [aws_security_group.endpoints.id]

  tags = {
    Name = "${var.name_prefix}-${replace(each.key, ".", "-")}"
  }
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [for table in aws_route_table.private : table.id]

  tags = {
    Name = "${var.name_prefix}-s3"
  }
}

# ------------------------------------------------------------------------------------------------
# Flow logs: every flow in the VPC, accepted and rejected.
# ------------------------------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "flow_logs" {
  name                        = local.flow_log_group_name
  retention_in_days           = var.flow_log_retention_days
  deletion_protection_enabled = true
}

resource "aws_iam_role" "flow_logs" {
  name = "${var.name_prefix}-vpc-flow-logs"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Condition = { StringEquals = { "aws:SourceAccount" = var.account_id } }
    }]
  })
}

resource "aws_iam_role_policy" "flow_logs" {
  name = "write-the-flow-log-group"
  role = aws_iam_role.flow_logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["logs:DescribeLogGroups", "logs:DescribeLogStreams"]
        Resource = local.flow_log_group_arn
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${local.flow_log_group_arn}:log-stream:*"
      },
    ]
  })
}

resource "aws_flow_log" "vpc" {
  vpc_id                   = aws_vpc.this.id
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs.arn
  iam_role_arn             = aws_iam_role.flow_logs.arn
  max_aggregation_interval = 60

  tags = {
    Name = "${var.name_prefix}-vpc-flow-log"
  }
}
