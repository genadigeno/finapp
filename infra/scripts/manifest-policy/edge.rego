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

# "[rbac-" since P15-TSK-032's review: a person's role added under deploy/edge is held by the rules that hold the application's, and a
# person's binding in finapp-edge is refused ([rbac-human-namespace]: finapp and finapp-observability alone).
reused_prefixes := {"[security-", "[resources-", "[images-digest]", "[probes-present]", "[no-secret-", "[no-test-only]", "[no-cronjob]", "[rbac-"}

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
	some prefix in route_config_prefixes
	not routes_render(prefix)
	msg := sprintf("[edge-controllers] the %s ConfigMap renders no routers", [prefix])
}

# ------------------------------------------------------------------------------------------- the spool

tmp_volume(name) := volume if {
	some doc in main.of_kind("Deployment")
	doc.metadata.name == name
	some volume in doc.spec.template.spec.volumes
	volume.name == "tmp"
}

memory_limit(name) := units.parse_bytes(container.resources.limits.memory) if {
	some doc in main.of_kind("Deployment")
	doc.metadata.name == name
	some container in doc.spec.template.spec.containers
}

# Memory-backed, because a disk-backed emptyDir gets the pod evicted at its sizeLimit where tmpfs answers the write with ENOSPC. Sized, and inside
# the container's memory limit with room for the process, because a tmpfs counts against it.
spool_is_bounded(name) if {
	empty_dir := tmp_volume(name).emptyDir
	empty_dir.medium == "Memory"
	memory_limit(name) >= units.parse_bytes(empty_dir.sizeLimit) + (200 * 1024 * 1024)
}

deny contains msg if {
	some name in controller_names
	not spool_is_bounded(name)
	msg := sprintf("[edge-spool] %s's /tmp must be memory-backed, sized, and inside its memory limit with 200Mi to spare", [name])
}

# ------------------------------------------------------------------------------------------- the static configuration, per surface

static_keys := {"traefik.yaml", "traefik-operator.yaml"}

config_map_with_prefix(prefix) := doc if {
	some doc in main.of_kind("ConfigMap")
	startswith(doc.metadata.name, prefix)
}

static_config(key) := yaml.unmarshal(config_map_with_prefix("finapp-edge-static-").data[key])

websecure(key) := main.section(main.section(static_config(key), "entryPoints"), "websecure")

websecure_http(key) := main.section(websecure(key), "http")

forwarded_is_not_insecure(key) if {
	main.section(websecure(key), "forwardedHeaders").insecure == false
}

deny contains msg if {
	some key in static_keys
	not forwarded_is_not_insecure(key)
	msg := sprintf("[edge-forwarded] %s may believe a client's forwarded headers: forwardedHeaders.insecure must be false", [key])
}

names_trusted_forwarders(key) if {
	main.stated(main.section(websecure(key), "forwardedHeaders"), "trustedIPs")
}

deny contains msg if {
	some key in static_keys
	names_trusted_forwarders(key)
	msg := sprintf("[edge-forwarded] %s names trusted forwarders: no address is trusted to set a forwarded header", [key])
}

aliases_are_dropped(key) if {
	websecure_http(key).aliasHeadersStrategy in {"delete", "reject"}
}

deny contains msg if {
	some key in static_keys
	not aliases_are_dropped(key)
	msg := sprintf("[edge-forwarded] %s: aliasHeadersStrategy must be delete or reject, so that X_Forwarded_For never reaches a replica", [key])
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

encoded_is_refused(key, option) if {
	main.section(websecure_http(key), "encodedCharacters")[option] == false
}

deny contains msg if {
	some key in static_keys
	some option in encoded_options
	not encoded_is_refused(key, option)
	msg := sprintf("[edge-encoded] %s: %s must be false: every one defaults to allowed", [key, option])
}

path_is_sanitized(key) if {
	websecure_http(key).sanitizePath == true
}

deny contains msg if {
	some key in static_keys
	not path_is_sanitized(key)
	msg := sprintf("[edge-encoded] %s: sanitizePath must be true", [key])
}

deny contains msg if {
	some key in static_keys
	some name, _ in main.section(static_config(key), "entryPoints")
	not name in {"websecure", "management"}
	msg := sprintf("[edge-listener] %s entry point %s: only websecure and management exist, and no plain listener", [key, name])
}

serves_tls(key) if {
	main.stated(websecure_http(key), "tls")
}

deny contains msg if {
	some key in static_keys
	not serves_tls(key)
	msg := sprintf("[edge-listener] %s: websecure must serve TLS on every router", [key])
}

deny contains msg if {
	some key in static_keys
	some name, _ in main.section(static_config(key), "providers")
	name != "file"
	msg := sprintf("[edge-provider] %s provider %s: the edge reads the file provider alone, so no controller holds a Kubernetes credential", [key, name])
}

reads_the_file_provider(key) if {
	main.stated(main.section(static_config(key), "providers"), "file")
}

deny contains msg if {
	some key in static_keys
	not reads_the_file_provider(key)
	msg := sprintf("[edge-provider] %s: the file provider is not configured", [key])
}

responding_timeouts_are_stated(key) if {
	timeouts := main.section(main.section(websecure(key), "transport"), "respondingTimeouts")
	main.stated(timeouts, "readTimeout")
	main.stated(timeouts, "writeTimeout")
}

deny contains msg if {
	some key in static_keys
	not responding_timeouts_are_stated(key)
	msg := sprintf("[edge-timeouts] %s must state readTimeout and writeTimeout: an unset writeTimeout never ends", [key])
}

# ------------------------------------------------------------------------------------------- the routes as rendered

route_config_prefixes := {"finapp-edge-public-routes-", "finapp-edge-operator-routes-"}

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

# ------------------------------------------------------------------------------------------- what one source can hold

edge_file(prefix) := yaml.unmarshal(config_map_with_prefix(prefix).data["edge.yaml"])

public_middlewares := main.section(main.section(edge_file("finapp-edge-public-routes-"), "http"), "middlewares")

hold_is_keyed_on_the_connection(name) if {
	limit := public_middlewares[name].inFlightReq
	limit.amount > 0
	main.stated(main.section(limit, "sourceCriterion"), "ipStrategy")
	not main.stated(main.section(limit, "sourceCriterion"), "requestHost")
	not main.stated(main.section(limit, "sourceCriterion"), "requestHeaderName")
}

public_router_holds(router) if {
	some middleware in main.list_of(router, "middlewares")
	startswith(middleware, "hold-")
	hold_is_keyed_on_the_connection(middleware)
}

deny contains msg if {
	some name, router in main.section(yaml.unmarshal(public_routes), "http").routers
	not public_router_holds(router)
	msg := sprintf("[edge-hold] %s has no in-flight limit keyed on the connection: the default key is the Host header, which the client chooses", [name])
}

# ------------------------------------------------------------------------------------------- no affinity, no tunnel, no hung replica

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

answers_within_a_bound(prefix) if {
	main.stated(edge_file(prefix).http.serversTransports["no-reuse"].forwardingTimeouts, "responseHeaderTimeout")
}

deny contains msg if {
	some prefix in route_config_prefixes
	not answers_within_a_bound(prefix)
	msg := sprintf("[edge-timeouts] %s states no responseHeaderTimeout: a hung replica would hold the request and its spool", [prefix])
}

upgrade_is_stripped(prefix) if {
	edge_file(prefix).http.middlewares["edge-headers"].headers.customRequestHeaders.Upgrade == ""
}

deny contains msg if {
	some prefix in route_config_prefixes
	not upgrade_is_stripped(prefix)
	msg := sprintf("[edge-upgrade] %s forwards a client's Upgrade header: an h2c upgrade would be a tunnel past the allow-list", [prefix])
}

# Each edge starts every trace (P15-TSK-023's review for the public one, P15-TSK-043 for the operators', THREAT_MODEL.md section 6
# item 37 (f)): a caller's W3C context would set the sampler's flag for its own requests or choose the trace ids the collectors route by,
# and staff are callers too. Asked per edge of the constant set, so an edge whose ConfigMap is missing, whose middleware is renamed or
# whose value is null or anything but empty is refused: the helper is undefined, never false, and `not` over it denies.
trace_headers := {"traceparent", "tracestate", "baggage"}

trace_context_is_stripped(prefix) if {
	every name in trace_headers {
		edge_file(prefix).http.middlewares["edge-headers"].headers.customRequestHeaders[name] == ""
	}
}

deny contains msg if {
	some prefix in route_config_prefixes
	not trace_context_is_stripped(prefix)
	msg := sprintf("[edge-trace-context] %s forwards a client's traceparent, tracestate or baggage: a caller, staff included, would sample its own requests or pick the collectors' routing", [prefix])
}

# ------------------------------------------------------------------------------------------- which surface (P15-TSK-042)

# Each edge states its own surface, replacing whatever the client sent, on every router: the application refuses staff at the doors
# that sign in, step up, enrol, change a password or recover unless the operator edge said so. A missing or wrong word here would make
# the operator edge's staff refused (fail closed) or, worse, the public edge's admitted.
surface_word := {"finapp-edge-public-routes-": "public", "finapp-edge-operator-routes-": "operator"}

states_its_surface(prefix) if {
	edge_file(prefix).http.middlewares["edge-headers"].headers.customRequestHeaders["X-Finapp-Surface"] == surface_word[prefix]
}

deny contains msg if {
	some prefix in route_config_prefixes
	not states_its_surface(prefix)
	msg := sprintf("[edge-surface] %s does not set X-Finapp-Surface: %s in edge-headers", [prefix, surface_word[prefix]])
}

router_marks_its_surface(router) if {
	"edge-headers" in main.list_of(router, "middlewares")
}

deny contains msg if {
	some prefix in route_config_prefixes
	some name, router in main.section(yaml.unmarshal(config_map_with_prefix(prefix).data["routes.yaml"]), "http").routers
	not router_marks_its_surface(router)
	msg := sprintf("[edge-surface] %s router %s does not run edge-headers, so its requests reach the application unmarked or marked by the client", [prefix, name])
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

# A real environment's load balancer sends to pod addresses, where externalTrafficPolicy does nothing: the target group's attribute keeps the client's
# address, and AWS leaves it off for TCP. Without it every client is the load balancer, one rate-limit bucket and one recorded address.
keeps_source_address(doc) if {
	data.overlay != "kind"
	doc.metadata.annotations["service.beta.kubernetes.io/aws-load-balancer-nlb-target-type"] == "ip"
	contains(doc.metadata.annotations["service.beta.kubernetes.io/aws-load-balancer-target-group-attributes"], "preserve_client_ip.enabled=true")
}

# kind's NodePort goes through kube-proxy, where this field is the one that keeps it.
keeps_source_address(doc) if {
	data.overlay == "kind"
	doc.spec.externalTrafficPolicy == "Local"
}

deny contains msg if {
	some doc in main.of_kind("Service")
	doc.metadata.name in controller_names
	not keeps_source_address(doc)
	msg := sprintf("[edge-source-address] %s would lose the client's address, and every client would share one bucket and one recorded address", [main.label(doc)])
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
