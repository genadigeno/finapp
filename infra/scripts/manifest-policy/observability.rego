# The telemetry pipeline's policies (P15-TSK-023, ADR-0124 decision 1; THREAT_MODEL.md section 6 item 21, decided 2026-11-09).
#
# Run by infra/scripts/telemetry-check.sh, conftest with --combine over an overlay's application stream and the pipeline's stream
# together, under this file's package. Every message begins with [rule-id]; telemetry-check.sh plants a violation of every rule id
# here and requires the id back, and TelemetryPipelineManifestsTest requires that list to be this file's.
#
# THE GENERAL HARDENING IS REUSED, NOT COPIED: finapp.rego's rules read every workload in the stream, so the collector and the
# backends meet the restricted profile, the digest pin, the probes and the resources by the same rule that holds the application.
#
# NEVER `not` OVER AN EXPRESSION WHOSE PARTS CAN BE UNDEFINED: finapp.rego's header says why. Each rule asks a helper.
package observability

import rego.v1

import data.main

reused_prefixes := {"[security-", "[resources-", "[images-digest]", "[probes-present]", "[no-secret-", "[no-test-only]", "[no-cronjob]"}

deny contains msg if {
	some msg in main.deny
	some prefix in reused_prefixes
	startswith(msg, prefix)
}

namespace := "finapp-observability"

in_namespace(doc) if {
	doc.metadata.namespace == namespace
}

# ------------------------------------------------------------------------------------------- the namespace's default-deny

denies_everything(policy) if {
	in_namespace(policy)
	count(main.section(policy.spec, "podSelector")) == 0
	{type | some type in main.list_of(policy.spec, "policyTypes")} == {"Ingress", "Egress"}
	count(main.list_of(policy.spec, "ingress")) == 0
	count(main.list_of(policy.spec, "egress")) == 0
}

default_denied if {
	some policy in main.of_kind("NetworkPolicy")
	denies_everything(policy)
}

deny contains "[obs-default-deny] no NetworkPolicy denies every pod of finapp-observability in both directions" if {
	not default_denied
}

# ------------------------------------------------------------------------------------------- the collector

collector := doc if {
	some doc in main.of_kind("Deployment")
	in_namespace(doc)
	doc.metadata.name == "finapp-collector"
}

collector_holds if {
	object.get(collector.spec, "replicas", 0) >= 2
	collector.spec.template.metadata.labels["finapp/role"] == "telemetry-collector"
	some container in main.list_of(collector.spec.template.spec, "containers")
	some port in main.list_of(container, "ports")
	port.containerPort == 4318
}

# P15-TSK-010's egress admits the collector by this label on 4318; without it every export is dropped silently.
deny contains "[obs-collector] finapp-collector is not rendered with two replicas or more, finapp/role: telemetry-collector and OTLP/HTTP on 4318" if {
	not collector_holds
}

# Every ingress rule of a policy selecting the collector names peers, and on 4318 every peer is the application's replicas.
collector_policies contains policy if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	policy.spec.podSelector.matchLabels["finapp/role"] == "telemetry-collector"
}

otlp_rule_admits_only_the_application(rule) if {
	main.every_peer_selects(rule, "ingress", "finapp", "app.kubernetes.io/name", "finapp-app")
}

rule_names_port(rule, number) if {
	some port in main.list_of(rule, "ports")
	port.port == number
}

deny contains msg if {
	some policy in collector_policies
	some rule in main.list_of(policy.spec, "ingress")
	rule_names_port(rule, 4318)
	not otlp_rule_admits_only_the_application(rule)
	msg := sprintf("[obs-collector-ingress] %s admits OTLP on 4318 from something other than the application's replicas", [main.label(policy)])
}

deny contains msg if {
	some policy in collector_policies
	some rule in main.list_of(policy.spec, "ingress")
	count(main.list_of(rule, "from")) == 0
	msg := sprintf("[obs-collector-ingress] %s has an ingress rule naming no peer: every pod in the cluster", [main.label(policy)])
}

# ------------------------------------------------------------------------------------------- nothing here reaches the data tier or out

data_tier_ports := {5432, 9092, 9093, 9094}

deny contains msg if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	some rule in main.list_of(policy.spec, "egress")
	some port in main.list_of(rule, "ports")
	port.port in data_tier_ports
	msg := sprintf("[obs-egress] %s lets a pod of the telemetry pipeline reach port %v of the data tier", [main.label(policy), port.port])
}

deny contains msg if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	some rule in main.list_of(policy.spec, "egress")
	count(main.list_of(rule, "to")) == 0
	msg := sprintf("[obs-egress] %s has an egress rule naming no peer: every address there is", [main.label(policy)])
}

deny contains msg if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	some rule in main.list_of(policy.spec, "egress")
	some peer in main.list_of(rule, "to")
	main.stated(peer, "ipBlock")
	msg := sprintf("[obs-egress] %s names an address block: the pipeline reaches pods of this cluster by label alone", [main.label(policy)])
}

# ------------------------------------------------------------------------------------------- the scraper

scraper_reaches_the_application if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	policy.spec.podSelector.matchLabels["finapp/role"] == "metrics-scraper"
	some rule in main.list_of(policy.spec, "egress")
	main.every_peer_selects(rule, "egress", "finapp", "app.kubernetes.io/name", "finapp-app")
	main.ports_within(rule, {["TCP", 8081]})
}

deny contains "[obs-scraper] no policy lets a pod labelled finapp/role: metrics-scraper reach the application's management port, and nothing else on that rule" if {
	not scraper_reaches_the_application
}

# ------------------------------------------------------------------------------------------- the application's one endpoint

collector_endpoints := {
	"FINAPP_TELEMETRY_TRACESENDPOINT": "http://finapp-collector.finapp-observability.svc.cluster.local:4318/v1/traces",
	"FINAPP_TELEMETRY_LOGSENDPOINT": "http://finapp-collector.finapp-observability.svc.cluster.local:4318/v1/logs",
}

app_states(name, value) if {
	some container in main.list_of(main.app.spec.template.spec, "containers")
	some env in main.list_of(container, "env")
	env.name == name
	env.value == value
}

deny contains msg if {
	some name, value in collector_endpoints
	not app_states(name, value)
	msg := sprintf("[obs-endpoint] finapp-app does not export to the cluster's own collector through %s", [name])
}

# ------------------------------------------------------------------------------------------- the second net

# Always defined: no ConfigMap reads as an empty configuration, which the last rule refuses, never as a rule that cannot run.
collector_config := yaml.unmarshal(doc.data["config.yaml"]) if {
	some doc in main.of_kind("ConfigMap")
	in_namespace(doc)
	startswith(doc.metadata.name, "finapp-collector-config")
} else := {}

scrubbed(pipeline) if {
	"transform/scrub" in main.list_of(pipeline, "processors")
}

deny contains msg if {
	some name, pipeline in main.section(main.section(collector_config, "service"), "pipelines")
	"otlp" in main.list_of(pipeline, "receivers")
	not scrubbed(pipeline)
	msg := sprintf("[obs-scrub] the collector's %s pipeline takes the application's telemetry and does not scrub it", [name])
}

deny contains "[obs-scrub] the collector's configuration renders no pipeline at all" if {
	count(main.section(main.section(collector_config, "service"), "pipelines")) == 0
}
