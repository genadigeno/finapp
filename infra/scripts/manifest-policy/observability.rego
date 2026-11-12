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

# "[rbac-" since P15-TSK-032 (item 42 (k)): a person's roles here are held by the rules that hold the application's - no secrets, no wildcard,
# no way inside a pod, no escalate, bind or impersonate, each role's own group, a person's binding in finapp and finapp-observability alone.
reused_prefixes := {"[security-", "[resources-", "[images-digest]", "[probes-present]", "[no-secret-", "[no-test-only]", "[no-cronjob]", "[rbac-"}

deny contains msg if {
	some msg in main.deny
	some prefix in reused_prefixes
	startswith(msg, prefix)
}

namespace := "finapp-observability"

in_namespace(doc) if {
	doc.metadata.namespace == namespace
}

# The pipeline's own rules hold where the pipeline is rendered: the kind stream renders it with the application, while staging's and
# production's application overlays, rendered alone, are held to the endpoint rule below and to nothing that needs the pipeline.
pipeline_rendered if {
	some doc in main.documents
	in_namespace(doc)
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
	pipeline_rendered
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
	pipeline_rendered
	not collector_holds
}

# Every ingress rule of a policy selecting the collector names peers, and on 4318 every peer is the application's replicas.
# Every policy that may select the collector's pods: an empty selector, or one whose every label the collector's pods carry, or one
# with expressions, which is read as selecting them. A policy reaches the collector by what it selects, not by naming its role.
collector_labels := object.get(object.get(object.get(collector.spec, "template", {}), "metadata", {}), "labels", {})

may_select_collector(policy) if {
	selector := main.section(policy.spec, "podSelector")
	count(main.list_of(selector, "matchExpressions")) > 0
}

may_select_collector(policy) if {
	selector := main.section(policy.spec, "podSelector")
	count(main.list_of(selector, "matchExpressions")) == 0
	every key, value in main.section(selector, "matchLabels") {
		collector_labels[key] == value
	}
}

collector_policies contains policy if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	may_select_collector(policy)
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

# A rule naming no port admits every port: the peers' unscrubbed 4317 and the counters among them.
deny contains msg if {
	some policy in collector_policies
	some rule in main.list_of(policy.spec, "ingress")
	count(main.list_of(rule, "ports")) == 0
	msg := sprintf("[obs-collector-ingress] %s has an ingress rule naming no port: every port of the collector, the peers' 4317 among them", [main.label(policy)])
}

# The second tier's receiver takes spans already scrubbed by a peer and scrubs nothing itself: only a collector of this namespace may
# send to it.
peer_is_a_collector(peer) if {
	object.keys(peer) == {"podSelector"}
	peer.podSelector == {"matchLabels": {"finapp/role": "telemetry-collector"}}
}

deny contains msg if {
	some policy in collector_policies
	some rule in main.list_of(policy.spec, "ingress")
	rule_names_port(rule, 4317)
	some peer in main.list_of(rule, "from")
	not peer_is_a_collector(peer)
	msg := sprintf("[obs-collector-peers] %s admits the peers' unscrubbed 4317 from something other than a collector of finapp-observability", [main.label(policy)])
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

# A rule naming no port is every port, the data tier's among them.
deny contains msg if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	some rule in main.list_of(policy.spec, "egress")
	count(main.list_of(rule, "ports")) == 0
	msg := sprintf("[obs-egress] %s has an egress rule naming no port: every port, the data tier's among them", [main.label(policy)])
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
	pipeline_rendered
	not scraper_reaches_the_application
}

# ------------------------------------------------------------------------------------------- what the scraper reaches, and who reaches a backend

# P15-TSK-032: the scraper reads the alerts' inputs and sends what fires to Alertmanager, so it reaches more than the application. Every flow
# is named here, each target by its peer exactly and with the ports it answers on: a metrics port, Alertmanager's API, DNS. Every egress rule
# of a policy that may select the scraper is one of these, every peer of the rule the same target and every port that target's. A target added
# to the scrape is added here in the same review, and a widened peer (cert-manager's whole namespace, its webhook's port) is refused.
scrape_targets := [
	{"peer": {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "finapp"}}, "podSelector": {"matchLabels": {"app.kubernetes.io/name": "finapp-app"}}}, "ports": {["TCP", 8081]}},
	{"peer": {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "finapp-edge"}}, "podSelector": {"matchLabels": {"finapp/role": "ingress-controller"}}}, "ports": {["TCP", 8081]}},
	{"peer": {"podSelector": {"matchLabels": {"finapp/role": "telemetry-collector"}}}, "ports": {["TCP", 8888]}},
	{"peer": {"podSelector": {"matchLabels": {"finapp/role": "trace-store"}}}, "ports": {["TCP", 3200]}},
	{"peer": {"podSelector": {"matchLabels": {"finapp/role": "log-store"}}}, "ports": {["TCP", 3100]}},
	{"peer": {"podSelector": {"matchLabels": {"finapp/role": "alert-router"}}}, "ports": {["TCP", 9193]}},
	{"peer": {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "cert-manager"}}, "podSelector": {"matchLabels": {"app.kubernetes.io/name": "cert-manager", "app.kubernetes.io/component": "controller"}}}, "ports": {["TCP", 9402]}},
	{"peer": {"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "kube-system"}}, "podSelector": {"matchLabels": {"k8s-app": "kube-dns"}}}, "ports": {["UDP", 53], ["TCP", 53]}},
]

# The labels a rendered workload of this namespace carries on its pods, by role; the role alone where none is rendered.
pod_labels(role) := labels if {
	some doc in main.of_kind("Deployment")
	in_namespace(doc)
	labels := object.get(object.get(object.get(doc.spec, "template", {}), "metadata", {}), "labels", {})
	labels["finapp/role"] == role
} else := {"finapp/role": role}

# A policy reaches a pod by what it selects: an empty selector, one whose every label the pod carries, or one with expressions, read as
# selecting it.
may_select(policy, labels) if {
	selector := main.section(policy.spec, "podSelector")
	count(main.list_of(selector, "matchExpressions")) > 0
}

may_select(policy, labels) if {
	selector := main.section(policy.spec, "podSelector")
	count(main.list_of(selector, "matchExpressions")) == 0
	every key, value in main.section(selector, "matchLabels") {
		labels[key] == value
	}
}

reaches_a_scrape_target(rule) if {
	some target in scrape_targets
	count(main.list_of(rule, "to")) > 0
	every peer in main.list_of(rule, "to") {
		peer == target.peer
	}
	main.ports_within(rule, target.ports)
}

deny contains msg if {
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	may_select(policy, pod_labels("metrics-scraper"))
	some rule in main.list_of(policy.spec, "egress")
	not reaches_a_scrape_target(rule)
	msg := sprintf("[obs-scraper-egress] %s lets the scraper reach something other than a scrape target's own port, Alertmanager or DNS", [main.label(policy)])
}

# Who may reach a backend, and on which port: the collectors on its ingest port and the scraper on its counters' port; Alertmanager the
# scraper alone; and the scraper itself nobody (P15-TSK-032's review): on kind it runs with --web.enable-lifecycle, whose /-/reload and
# /-/quit answer anyone who reaches it, so only its node (the API server's proxy) may. Each peer exactly a role of this namespace.
store_admits := {
	"trace-store": [{"role": "telemetry-collector", "ports": {["TCP", 4317]}}, {"role": "metrics-scraper", "ports": {["TCP", 3200]}}],
	"log-store": [{"role": "telemetry-collector", "ports": {["TCP", 3100]}}, {"role": "metrics-scraper", "ports": {["TCP", 3100]}}],
	"alert-router": [{"role": "metrics-scraper", "ports": {["TCP", 9193]}}],
	"metrics-scraper": [],
}

admitted_to_store(rule, role) if {
	some admitted in store_admits[role]
	count(main.list_of(rule, "from")) > 0
	every peer in main.list_of(rule, "from") {
		peer == {"podSelector": {"matchLabels": {"finapp/role": admitted.role}}}
	}
	main.ports_within(rule, admitted.ports)
}

deny contains msg if {
	some role, _ in store_admits
	some policy in main.of_kind("NetworkPolicy")
	in_namespace(policy)
	may_select(policy, pod_labels(role))
	some rule in main.list_of(policy.spec, "ingress")
	not admitted_to_store(rule, role)
	msg := sprintf("[obs-store-ingress] %s admits to the %s a peer or a port its list does not name", [main.label(policy), role])
}

# ------------------------------------------------------------------------------------------- a person's break-glass here evicts

# P15-TSK-032 (THREAT_MODEL.md section 6 item 42 (k)): in this namespace break-glass is one act, a pod evicted, which the collector's
# PodDisruptionBudget admits one at a time. finapp.rego's closed list admits deleting a pod and changing a replica count in the application's
# namespace; here neither. Read from every role the break-glass group is bound to here, and from any role here of its name.
break_glass_here contains role if {
	some binding in main.rbac_bindings
	in_namespace(binding)
	some subject in main.list_of(binding, "subjects")
	subject.kind == "Group"
	subject.name == "finapp-break-glass"
	some role in main.rbac_roles
	main.binds(binding, role)
}

break_glass_here contains role if {
	some role in main.rbac_roles
	in_namespace(role)
	role.metadata.name == "finapp-break-glass"
}

deny contains msg if {
	some role in break_glass_here
	some rule in main.list_of(role, "rules")
	some verb in main.list_of(rule, "verbs")
	not verb in {"get", "list", "watch"}
	some resource in main.list_of(rule, "resources")
	not [verb, resource] == ["create", "pods/eviction"]
	msg := sprintf("[obs-break-glass-evictions] %s may %s %s: in finapp-observability break-glass evicts a pod and writes nothing else", [main.label(role), verb, resource])
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
	pipeline_rendered
	count(main.section(main.section(collector_config, "service"), "pipelines")) == 0
}
