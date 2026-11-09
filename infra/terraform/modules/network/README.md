# `network`

The platform's private network on the reference cloud (AWS, `PHASE_15_PLAN.md` C1). Called by each environment root.
**Never applied**: there is no account in Phase 15, so every property below is asserted by `tests/network.tftest.hcl`
over a plan against a mock provider. That proves the configuration, not AWS's behaviour.

## What it builds

| Part | Property | Held by the run |
|---|---|---|
| VPC | a private `/16`, DNS on | `the_addresses_are_the_plan_and_do_not_overlap` |
| Workload subnets (`private`) | one `/20` per zone; no public address on launch; no route to the internet gateway; default route through the NAT gateway of its own zone | `no_workload_subnet_is_public` |
| Edge subnets (`public`) | one `/24` per zone; the only route table with an internet route; no public address on launch | `the_public_tier_gives_nothing_a_public_address_and_holds_the_only_internet_route` |
| NAT egress | one gateway per zone, in that zone's edge subnet, in every environment | `egress_is_a_nat_gateway_per_zone` |
| VPC endpoints | `ec2`, `ecr.api`, `ecr.dkr`, `kms`, `logs`, `secretsmanager`, `sts` (interface, in every workload subnet, HTTPS from the VPC only) and `s3` (gateway, on every workload route table) | `the_cloud_apis_are_reached_through_vpc_endpoints` |
| Flow logs | every flow in the VPC, accepted and rejected, into a log group with deletion protection, through a role trusted by this account's flow logs and able to write only there | `the_vpc_flow_log_covers_all_traffic` |
| Zones | at least three, each named once, all in the region | `fewer_than_three_zones_are_refused`, `a_zone_named_twice_counts_once`, `a_zone_of_another_region_is_refused` |

The edge tier exists because a NAT gateway and an internet-facing load balancer each need a public subnet. Nothing in
this module places a workload there, and each root hands the cluster the workload tier alone (asserted in the roots'
tests).

## Inputs and outputs

Inputs: `name_prefix`, `region`, `account_id`, `availability_zones`, `cidr_block`, `flow_log_retention_days`, `flow_log_kms_key_arn`.
Outputs: `vpc_id`, `private_subnet_ids_by_zone`, `public_subnet_ids_by_zone`. See `variables.tf` and `outputs.tf`; each is
described there.

## Operating it

- **Add a zone**: append it to the root's `availability_zones`. Addressing follows a zone's position in the list, so a
  zone added at the end moves nothing, and a re-ordered list would replace subnets. Read the plan.
- **Flow-log retention** is operational data; the period is a reference value in each root. The audit class's period is a
  jurisdiction's and is not set here (`PHASE_15_PLAN.md` C12).
- **Before relying on this**: run the checks (`OPERATIONS_RUNBOOK.md` §11.13). A change to this module's provider
  constraint or a root's pin needs this directory's `.terraform.lock.hcl` regenerated with the same pinned version.

## What it does not do

No database or broker networking rules (`-015`'s modules add their own), no load balancer (the edge is a Kubernetes
concern), and no apply.
