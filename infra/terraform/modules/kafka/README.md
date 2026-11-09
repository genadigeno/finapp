# `kafka`

The integration transport on the reference cloud (MSK, `PHASE_15_PLAN.md` C3 and C11). Called by each environment root.
**Never applied**: there is no account in Phase 15, so every property below is asserted by `tests/kafka.tftest.hcl` over a
plan against mock providers. That proves the configuration, not MSK's behaviour: no TLS handshake, SCRAM exchange or ACL
decision has been observed from this module. The Kafka version and instance types are unverified against the service.

Kafka carries hints and never truth (`INV-EVT-02`). The outbox in PostgreSQL is the durable source.

## What it builds

| Property | Held by the run |
|---|---|
| Three brokers on three of the given workload subnets, no public access; a fourth zone moves nothing | `the_brokers_span_three_zones_on_the_given_subnets`, `a_fourth_zone_leaves_the_brokers_on_the_first_three` |
| TLS from clients and between brokers; SCRAM the only mechanism; no unauthenticated access; its own security group, port 9096 from one given security group | `only_tls_and_scram_reach_the_brokers` |
| Volumes encrypted under the given customer-managed key | `the_volumes_are_encrypted_under_the_given_key` |
| The cluster runs this configuration's latest revision; broker logs on, into a group with deletion protection | `the_cluster_uses_this_configuration_and_keeps_its_logs` |
| `min.insync.replicas=2`, unclean election off, auto-create off, ACLs refused by default | `the_posture_the_clients_rely_on_is_the_clusters_configuration` |
| The configuration is exactly `infra/kafka/broker.properties`, less what the service sets itself | `every_broker_setting_is_sent_except_what_the_service_sets_itself` |
| Every catalogued topic and only those, each with its class's configuration, partitions and replication | `every_catalogued_topic_is_planned_with_its_class_configuration` |
| The application's ACLs per topic by literal name, on `finapp.`-prefixed groups, none on the cluster | `the_applications_acls_are_exactly_the_catalogues_by_literal_name` |
| The provisioner's ACLs: create and configure, cluster alter and describe, no delete | `the_provisioner_creates_and_configures_and_deletes_nothing` |
| One ACL per principal, resource and operation granted, nothing more | `every_acl_is_planned_nothing_more` |
| One SCRAM secret per principal, named `AmazonMSK_…`, under the given customer-managed key, readable by the brokers, associated with this cluster, with no value in state | `the_scram_secrets_are_what_msk_requires_and_hold_no_value` |

**Read, never restated.** Topics, ACLs and the posture come from `infra/kafka/topics.yaml` and `infra/kafka/broker.properties`,
which `TopicCatalogueTest` holds to the code and to the inbox's retention bound. `the_module_reads_the_files_it_is_given_and_holds_no_copy`
plans two fixture files whose contents match nothing real and requires exactly those; each root's test requires the real
catalogue; `TerraformIsPinnedAndCheckedTest#theCatalogueHasNoCopyUnderTerraform` refuses a copy that would compare equal.

**What MSK sets itself**, recorded rather than restated (`settings_the_service_sets_itself`): `sasl.enabled.mechanisms`
(SCRAM-SHA-512 is the only mechanism MSK offers, turned on by `client_authentication`) and `log.retention.check.interval.ms`
(not settable on MSK; Kafka's default is the 300000 the file states and `TopicCatalogueTest` counts). Any other key goes to the
brokers, and MSK refuses one it does not accept, so a new setting fails loudly instead of being dropped.

## The stages

`provisioning_stage` is required and has no default. A new cluster has to be brought up in three applies, because three
things cannot be done at once:

| Stage | The plan holds | Why not sooner |
|---|---|---|
| `brokers` | The cluster, the security group, the empty SCRAM secrets | The Kafka provider has nothing to reach, and associating a secret with no value fails |
| `principals` | That, plus the SCRAM association, topics and ACLs | The secrets must hold values first; `allow.everyone.if.no.acl.found` is `true` here |
| `enforced` | That, with the whole posture: `allow.everyone.if.no.acl.found=false` | MSK has no `super.users`, so with no ACL and this `false`, nobody can write the first ACL. AWS documents this as losing access to the cluster |

Only that one key differs before `enforced`, and `the_stage_that_writes_the_first_acls_admits_their_author` holds that. A
`check` block warns on every plan until the stage is `enforced`: the warning is the alarm for a cluster left half-way. A
plan at `principals` is open to any authenticated principal, of which there are only the two this module creates, so finish the
sequence in one sitting. If a cluster is ever locked out, the documented way back is to apply `principals` again.

**Between `brokers` and `principals`**: put a value in each secret, JSON with `username` and `password`, the username
being exactly the module's `scram_users` output for that principal. No Terraform writes that value (ADR-0121 decision 3).

## What applying needs

The `kafka` provider speaks to the brokers themselves, unlike every other provider here, so from `principals` on a
`plan` or `apply`:

- must run **from inside the network**, on a source the brokers' security group admits, which today means the cluster's nodes;
- needs the **administrator** principal's credential in `KAFKA_SASL_USERNAME` and `KAFKA_SASL_PASSWORD`, in the runner's
  environment only. The root's provider block holds TLS and the mechanism, and `TerraformIsPinnedAndCheckedTest` refuses a
  credential in it. Terraform does not write a provider's configuration to state.

No such runner exists in Phase 15 (`THREAT_MODEL.md` §6 item 7 holds applies). The provider is a community one
(`mongey/kafka`), pinned and locked like the others; the alternative was a list of topics and ACLs emitted for something else
to apply, which no plan could show.

A topic that leaves the catalogue is not deleted: `prevent_destroy` stops the plan, and the administrator holds no `Delete`
on any broker. Removing one is a break-glass act.

## Inputs and outputs

Inputs: `name_prefix`, `vpc_id`, `private_subnet_ids_by_zone`, `client_security_group_id`, `topic_catalogue_path`,
`broker_properties_path`, `broker_instance_type`, `broker_volume_gib`, `broker_log_retention_days`, `at_rest_kms_key_arn`,
`scram_kms_key_arn`, `provisioning_stage`. See `variables.tf` and `outputs.tf`.

## Operating it

- **A broker outage**: `OPERATIONS_RUNBOOK.md` §11.6 has both halves, the application's and the managed service's.
- **A posture change**: edit `infra/kafka/broker.properties`. A plan creates a configuration revision, and MSK rolls the
  brokers when it is applied, one at a time, waiting for each to catch up.
- **Rotating a SCRAM credential**: ADR-0123's order of add, roll, remove applies, and is `P15-TSK-020`'s to rehearse. An ACL names a
  SCRAM user, so a second user for rotation needs its grants too.
- **Before relying on this**: run the checks (`OPERATIONS_RUNBOOK.md` §11.13). Changing the Kafka provider's pin means
  regenerating this directory's lock file with both roots'.

## What it does not do

No KMS key, no value in any secret, no application credential delivery (`P15-TSK-018`'s ExternalSecrets), no tiered storage,
no schema registry (C11), no apply. Nothing here has met a real broker: `-012`'s `kind` rehearsal is the first
real TLS and SCRAM handshake, and it runs against a test-only broker.
