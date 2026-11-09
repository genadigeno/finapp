# The edge's policies (P15-TSK-011, ADR-0120 decision 6).
#
# Run by infra/scripts/edge-check.sh, conftest with --combine over one overlay's application stream and edge stream together, under
# this file's package. Every message begins with [rule-id], and edge-check.sh plants a violation of every rule id here and requires the id
# back; EdgeManifestsTest requires that list to be this file's.
#
# THE GENERAL HARDENING IS REUSED, NOT COPIED. finapp.rego's rules read every workload in the stream, so the controllers meet the restricted
# profile, the digest pin and the rest by the same rule that holds the application. Its rules about a single application
# (the budget, the rollout, [service-api-only]) are not passed through: this stream holds Services that are meant to be load balancers.
#
# NEVER `not` OVER AN EXPRESSION WHOSE PARTS CAN BE UNDEFINED: finapp.rego's header says why. Each rule asks a helper.
package edge

import rego.v1

import data.main

reused_prefixes := {"[security-", "[resources-", "[images-digest]", "[probes-present]", "[no-secret-", "[no-test-only]", "[no-cronjob]"}

deny contains msg if {
	some msg in main.deny
	some prefix in reused_prefixes
	startswith(msg, prefix)
}

# ------------------------------------------------------------------------------------------- the two controllers

controller_names := {"finapp-edge-public", "finapp-edge-operator"}

rendered_with_replicas(name) if {
	some doc in main.of_kind("Deployment")
	doc.metadata.name == name
	object.get(doc.spec, "replicas", 0) >= 2
}

deny contains msg if {
	some name in controller_names
	not rendered_with_replicas(name)
	msg := sprintf("[edge-controllers] %s is not rendered with at least two replicas", [name])
}

# P15-TSK-010's policy admits the controllers by this label, on the application's side of their one egress.
carries_the_role(name) if {
	some doc in main.of_kind("Deployment")
	doc.metadata.name == name
	doc.spec.template.metadata.labels["finapp/role"] == "ingress-controller"
}

deny contains msg if {
	some name in controller_names
	not carries_the_role(name)
	msg := sprintf("[edge-controllers] %s does not carry finapp/role: ingress-controller, so nothing would admit it to the application", [name])
}

# A stream with no routes at all would pass every rule below that walks them.
routes_render(prefix) if {
	config := config_map_with_prefix(prefix)
	count(main.section(main.section(yaml.unmarshal(config.data["routes.yaml"]), "http"), "routers")) > 0
}

deny contains msg if {
	some prefix in {"finapp-edge-public-routes-", "finapp-edge-operator-routes-"}
	not routes_render(prefix)
	msg := sprintf("[edge-controllers] the %s ConfigMap renders no routers", [prefix])
}

# ------------------------------------------------------------------------------------------- the static configuration

static_config := yaml.unmarshal(config_map_with_prefix("finapp-edge-static-").data["traefik.yaml"])

config_map_with_prefix(prefix) := doc if {
	some doc in main.of_kind("ConfigMap")
	startswith(doc.metadata.name, prefix)
}

websecure := main.section(main.section(static_config, "entryPoints"), "websecure")

websecure_http := main.section(websecure, "http")

deny contains "[edge-forwarded] the edge may believe a client's forwarded headers: forwardedHeaders.insecure must be false" if {
	not forwarded_is_not_insecure
}

forwarded_is_not_insecure if {
	main.section(websecure, "forwardedHeaders").insecure == false
}

deny contains "[edge-forwarded] the edge names trusted forwarders: no address is trusted to set a forwarded header" if {
	main.stated(main.section(websecure, "forwardedHeaders"), "trustedIPs")
}

deny contains "[edge-forwarded] aliasHeadersStrategy must be delete or reject, so that X_Forwarded_For never reaches a replica" if {
	not aliases_are_dropped
}

aliases_are_dropped if {
	websecure_http.aliasHeadersStrategy in {"delete", "reject"}
}

encoded_options := {
	"allowEncodedSlash",
	"allowEncodedBackSlash",
	"allowEncodedNullCharacter",
	"allowEncodedSemicolon",
	"allowEncodedPercent",
	"allowEncodedQuestionMark",
	"allowEncodedHash",
}

encoded_is_refused(option) if {
	main.section(websecure_http, "encodedCharacters")[option] == false
}

deny contains msg if {
	some option in encoded_options
	not encoded_is_refused(option)
	msg := sprintf("[edge-encoded] %s must be false: every one defaults to allowed", [option])
}

deny contains "[edge-encoded] sanitizePath must be true" if {
	not path_is_sanitized
}

path_is_sanitized if {
	websecure_http.sanitizePath == true
}

deny contains msg if {
	some name, _ in main.section(static_config, "entryPoints")
	not name in {"websecure", "management"}
	msg := sprintf("[edge-listener] entry point %s: only websecure and management exist, and no plain listener", [name])
}

deny contains "[edge-listener] websecure must serve TLS on every router" if {
	not main.stated(websecure_http, "tls")
}

deny contains msg if {
	some name, _ in main.section(static_config, "providers")
	name != "file"
	msg := sprintf("[edge-provider] provider %s: the edge reads the file provider alone, so no controller holds a Kubernetes credential", [name])
}

deny contains "[edge-provider] the file provider is not configured" if {
	not main.stated(main.section(static_config, "providers"), "file")
}

# ------------------------------------------------------------------------------------------- the routes as rendered

public_routes := config_map_with_prefix("finapp-edge-public-routes-").data["routes.yaml"]

operator_routes := config_map_with_prefix("finapp-edge-operator-routes-").data["routes.yaml"]

deny contains "[edge-routes-public] the public routes name /v1/operator" if {
	contains(public_routes, "/v1/operator")
}

public_router_is_limited(router) if {
	some middleware in main.list_of(router, "middlewares")
	startswith(middleware, "source-")
}

deny contains msg if {
	some name, router in main.section(yaml.unmarshal(public_routes), "http").routers
	not public_router_is_limited(router)
	msg := sprintf("[edge-routes-public] %s carries no per-source limit", [name])
}

operator_router_is_internal(router) if {
	"internal-only" in main.list_of(router, "middlewares")
}

deny contains msg if {
	some name, router in main.section(yaml.unmarshal(operator_routes), "http").routers
	not operator_router_is_internal(router)
	msg := sprintf("[edge-routes-operator] %s is not limited to the internal ranges", [name])
}

# ------------------------------------------------------------------------------------------- no affinity

route_config_prefixes := {"finapp-edge-public-routes-", "finapp-edge-operator-routes-"}

edge_file(prefix) := yaml.unmarshal(config_map_with_prefix(prefix).data["edge.yaml"])

deny contains msg if {
	some prefix in route_config_prefixes
	some service, definition in main.section(main.section(edge_file(prefix), "http"), "services")
	main.stated(main.section(definition, "loadBalancer"), "sticky")
	msg := sprintf("[edge-no-affinity] %s service %s is sticky: sessions live in PostgreSQL, and any replica serves any request", [prefix, service])
}

reuse_is_off(prefix) if {
	edge_file(prefix).http.serversTransports["no-reuse"].maxIdleConnsPerHost == -1
}

deny contains msg if {
	some prefix in route_config_prefixes
	not reuse_is_off(prefix)
	msg := sprintf("[edge-no-affinity] %s keeps backend connections, or states no transport: a request must pick a ready replica each time", [prefix])
}

# ------------------------------------------------------------------------------------------- the Services

deny contains msg if {
	some doc in main.of_kind("Service")
	doc.metadata.name in controller_names
	some port in doc.spec.ports
	not edge_port_is_tls(port)
	msg := sprintf("[edge-service-port] %s exposes %v: 443 to the TLS listener alone", [main.label(doc), port])
}

edge_port_is_tls(port) if {
	port.port == 443
	port.targetPort == "https"
}

load_balancer_scheme(doc) := doc.metadata.annotations["service.beta.kubernetes.io/aws-load-balancer-scheme"]

deny contains msg if {
	data.overlay != "kind"
	some doc in main.of_kind("Service")
	doc.metadata.name == "finapp-edge-operator"
	not load_balancer_scheme(doc) == "internal"
	msg := sprintf("[edge-operator-internal] %s is not an internal load balancer", [main.label(doc)])
}

keeps_source_address(doc) if {
	doc.spec.externalTrafficPolicy == "Local"
}

deny contains msg if {
	data.overlay != "kind"
	some doc in main.of_kind("Service")
	doc.metadata.name in controller_names
	not keeps_source_address(doc)
	msg := sprintf("[edge-source-address] %s would lose the client's address: externalTrafficPolicy must be Local", [main.label(doc)])
}

# Nothing else routes traffic into the cluster: no Ingress or route object bypasses the generated allow-list.
routing_kinds := {"Ingress", "IngressRoute", "HTTPRoute", "Gateway", "GRPCRoute", "TLSRoute"}

deny contains msg if {
	some doc in main.documents
	doc.kind in routing_kinds
	msg := sprintf("[edge-no-ingress] %s: traffic enters by the two controllers and their generated route files alone", [main.label(doc)])
}

# ------------------------------------------------------------------------------------------- whose word the address is

app_config := config_map_with_prefix("finapp-app-config-")

declared_proxies := object.get(app_config.data, "FINAPP_EDGE_PROXIES", "")

proxies_are_a_network if {
	net.cidr_is_valid(declared_proxies)
	to_number(split(declared_proxies, "/")[1]) >= 8
}

deny contains "[edge-proxies] FINAPP_EDGE_PROXIES is not one network of at least /8 in the application's ConfigMap: blank trusts nobody, and everything trusts everyone" if {
	not proxies_are_a_network
}

# ------------------------------------------------------------------------------------------- the local CA

deny contains msg if {
	data.overlay != "kind"
	some doc in main.documents
	doc.kind in {"Issuer", "ClusterIssuer", "Certificate"}
	msg := sprintf("[edge-local-ca] %s in overlay %s: the local CA is the kind rehearsal's alone", [main.label(doc), data.overlay])
}

# ------------------------------------------------------------------------------------------- the namespace

denies_everything(policy) if {
	count(main.section(policy.spec, "podSelector")) == 0
	{type | some type in policy.spec.policyTypes} == {"Ingress", "Egress"}
	count(main.list_of(policy.spec, "ingress")) == 0
	count(main.list_of(policy.spec, "egress")) == 0
}

default_denied if {
	some policy in main.of_kind("NetworkPolicy")
	denies_everything(policy)
}

deny contains "[edge-network] no NetworkPolicy denies every pod in the edge's namespace in both directions" if {
	not default_denied
}

operator_network_is_internal if {
	some policy in main.of_kind("NetworkPolicy")
	policy.metadata.name == "finapp-edge-operator"
	some rule in policy.spec.ingress
	some port in rule.ports
	port.port == 8443
	count(rule.from) > 0
	every peer in rule.from {
		peer.ipBlock.cidr in {"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"}
	}
}

deny contains "[edge-network] the operator controller's TLS listener is not limited to the internal ranges" if {
	not operator_network_is_internal
}
