# `kubernetes`

The managed cluster on the reference cloud (EKS, `PHASE_15_PLAN.md` C1 and C2). Called by each environment root.
**Never applied**: there is no account in Phase 15, so every property below is asserted by
`tests/kubernetes.tftest.hcl` over a plan against a mock provider. That proves the configuration, not AWS's behaviour.

The security properties are literals in `main.tf`, not variables, so no environment can turn one off.

## What it builds

| Property | Held by the run |
|---|---|
| The API endpoint is private: no public address, reachable from inside the VPC, on the workload subnets | `the_api_endpoint_is_private` |
| Kubernetes Secrets are envelope-encrypted in etcd under the key given as `etcd_kms_key_arn`; the control plane's role can use that key and no other | `secrets_in_etcd_are_encrypted_under_the_given_key` |
| All five control-plane logs are on, `audit` among them, into the log group EKS writes to, created first with its retention and deletion protection | `the_control_plane_audit_log_is_on` |
| One managed node group per zone, at least three zones, each confined to its own zone's workload subnet and holding at least one node; an update takes one node at a time | `every_zone_has_its_own_node_group_with_a_node` |
| A pod cannot reach its node's credentials (IMDSv2, one network hop); no node accepts SSH; root volumes encrypted; instances and volumes carry the conventions' tags | `the_node_template_keeps_pods_off_instance_metadata_and_sets_no_ssh_key` |
| Workload identity: an OIDC provider on the cluster's issuer, audience STS alone | `workload_identity_is_the_clusters_oidc_issuer` |
| NetworkPolicy is enforced: the VPC CNI's agent is on, which it is not by default | `the_network_policy_agent_is_turned_on` |
| Cluster access through access entries alone; the creator gains no standing administrator access; the cluster cannot be deleted without first turning deletion protection off; the node role holds three AWS-managed policies and nothing else | `access_is_explicit_and_the_cluster_cannot_be_deleted_by_a_plan` |
| Malformed input refused: fewer than three zones, a zone with no node, an alias or a malformed key | the `..._refused` runs |

Each root's own tests assert the wiring: the cluster and every node group are on the workload subnets and never the
edge tier, and the key is a key of that root's account and region.

**Why one node group per zone.** The application's Deployment spreads replicas across zones with `DoNotSchedule`. A
single group over three subnets can hold fewer nodes than zones, and nothing then guarantees a node in each. A group per
zone with a minimum of one makes that a count.

## Inputs and outputs

Inputs: `name_prefix`, `tags`, `kubernetes_version`, `private_subnet_ids_by_zone`, `etcd_kms_key_arn`,
`node_instance_types`, `node_root_volume_gib`, `nodes_per_zone`, `control_plane_log_retention_days`.
Outputs: `cluster_name`, `workload_identity_provider_arn`, `workload_identity_issuer`, `node_role_arn`,
`node_security_group_id` (the source the data tier admits), and four that read the planned cluster back for the roots' tests. See `variables.tf` and `outputs.tf`.

## Operating it

- **The key**: `etcd_kms_key_arn` is a root variable until the security module supplies it. Its key policy must let this
  account's cluster role use it; the role's own policy here names that key and no other.
- **Reaching the API**: the endpoint is private and the mode is `API`, so nobody reaches the cluster until access entries
  exist. Humans and the GitOps controller are given theirs by the security module; this module creates none.
- **Pods that read instance metadata** get no answer, by design. A workload that needs the region is given it in its
  environment, and gets credentials through workload identity.
- **Upgrade**: raise `kubernetes_version` in staging's root first, then production's. The minor and the node sizes in the
  roots are reference values until an account exists.
- **Control-plane log retention** is `0` (no expiry) in both roots: the audit class's period is a jurisdiction's and none
  is invented (`PHASE_15_PLAN.md` C12).
- **Before relying on this**: run the checks (`OPERATIONS_RUNBOOK.md` §11.13). A change to this module's provider
  constraint or a root's pin needs this directory's `.terraform.lock.hcl` regenerated with the same pinned version.

## What it does not do

No KMS key, no workload role, no access entry, no namespace policy (those live with their own tasks' modules and
manifests), no customer-managed key on the control-plane log group (CloudWatch's default encryption applies), and no
apply.
