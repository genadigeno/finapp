# The policies every rendered overlay must pass (P15-TSK-009, ADR-0120).
#
# Run by infra/scripts/manifest-check.sh, conftest with --combine over one overlay's rendered stream, so a
# rule can hold a Deployment against its HPA and its ConfigMap. Three data sources, none copied here:
#   data.spring, data.finapp   app/src/main/resources/application.yaml itself: the pools and the timeout
#   data.shutdownBudget        manifest-policy/shutdown-budget.yaml, which KubernetesManifestsTest holds to the code
#   data.overlay               which overlay this is, from the directory the script rendered
#
# Every message begins with [rule-id]. manifest-check.sh plants a violation of every rule id in this file and
# requires the id back, and KubernetesManifestsTest requires that list to be this file's, so a rule that cannot
# fire fails the job. Rules read every workload kind and every container list, because a policy that only
# fires on the case it was written against is the defect this file exists to avoid.
#
# NEVER `not` OVER AN EXPRESSION WHOSE PARTS CAN BE UNDEFINED. `not f(x) == y` with f(x) undefined is not true: the
# compiler moves f(x) outside the negation and the whole body is undefined, so the rule passes on the very absence it
# was written to refuse (found by this file's plants: an exec preStop and a null label both passed). A rule that asks
# "is this stated?" negates a helper rule, whose body may be undefined safely.
#
# A KEY STATED WITH NO VALUE IS NULL, AND A TYPE ERROR IS UNDEFINED. `securityContext:` with its children deleted reads as
# null; object.get(null, ...) and array.concat(x, null) are type errors, the body is undefined and the rule passes with
# the hardening gone (the independent review's F1, confirmed by a plant). Nothing here walks into a value it has not
# checked: section(obj, key) answers {} unless the value is an object, list_of(obj, key) answers [] unless it is an array,
# and stated(obj, key) is false for a null. manifest-check.sh plants a null for each of those keys.
package main

import rego.v1

# ------------------------------------------------------------------------------------- reading the stream

# --combine gives [{path, contents}]; contents is a list for a multi-document file, an object for a single one.
documents contains doc if {
	some file in input
	is_array(file.contents)
	some doc in file.contents
	is_object(doc)
}

documents contains doc if {
	some file in input
	is_object(file.contents)
	doc := file.contents
}

of_kind(wanted) := {doc | some doc in documents; doc.kind == wanted}

# Every kind that runs a pod, whether or not this platform uses it today.
pod_template_kinds := {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "ReplicationController"}

long_running_kinds := {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "ReplicationController"}

label(doc) := sprintf("%s/%s", [doc.kind, doc.metadata.name])

workloads contains [doc, doc.spec.template.spec] if {
	some doc in documents
	doc.kind in pod_template_kinds
}

workloads contains [doc, doc.spec] if {
	some doc in documents
	doc.kind == "Pod"
}

workloads contains [doc, doc.spec.jobTemplate.spec.template.spec] if {
	some doc in documents
	doc.kind == "CronJob"
}

section(obj, key) := obj[key] if {
	is_object(obj)
	is_object(obj[key])
} else := {}

list_of(obj, key) := obj[key] if {
	is_object(obj)
	is_array(obj[key])
} else := []

stated(obj, key) if {
	is_object(obj)
	obj[key] != null
}

# All three container lists: an init container is as able to escalate as the main one.
containers(spec) := [container |
	some list in ["containers", "initContainers", "ephemeralContainers"]
	some container in list_of(spec, list)
]

all_containers contains [doc, spec, container] if {
	some [doc, spec] in workloads
	some container in containers(spec)
}

app := doc if {
	some doc in of_kind("Deployment")
	doc.metadata.name == "finapp-app"
}

app_spec := app.spec.template.spec

app_container := container if {
	some container in app_spec.containers
	container.name == "app"
}

autoscaler := doc if {
	some doc in of_kind("HorizontalPodAutoscaler")
	doc.spec.scaleTargetRef.kind == "Deployment"
	doc.spec.scaleTargetRef.name == "finapp-app"
}

config := doc if {
	some doc in of_kind("ConfigMap")
	startswith(doc.metadata.name, "finapp-app-config")
}

# ------------------------------------------------------------------------------------- the stream was read

deny contains "[stream-empty] no finapp-app Deployment in the rendered stream: nothing was checked" if {
	not app
}

deny contains "[stream-empty] no container named app in the finapp-app Deployment: nothing was checked" if {
	app
	not app_container
}

deny contains "[stream-overlay] the script named no overlay" if {
	not data.overlay
}

# ------------------------------------------------------------------------------------- the restricted profile

pod_or_container(spec, container, key) := section(container, "securityContext")[key] if {
	stated(section(container, "securityContext"), key)
} else := section(spec, "securityContext")[key]

runs_as_non_root(spec, container) if {
	pod_or_container(spec, container, "runAsNonRoot") == true
}

runs_as_a_numbered_user(spec, container) if {
	pod_or_container(spec, container, "runAsUser") > 0
}

deny contains msg if {
	some [doc, spec, container] in all_containers
	not runs_as_non_root(spec, container)
	msg := sprintf("[security-non-root] %s container %s does not state runAsNonRoot: true", [label(doc), container.name])
}

deny contains msg if {
	some [doc, spec, container] in all_containers
	not runs_as_a_numbered_user(spec, container)
	msg := sprintf("[security-non-root-uid] %s container %s has no non-zero numeric runAsUser", [label(doc), container.name])
}

seccomp_is_confined(spec, container) if {
	pod_or_container(spec, container, "seccompProfile").type in {"RuntimeDefault", "Localhost"}
}

deny contains msg if {
	some [doc, spec, container] in all_containers
	not seccomp_is_confined(spec, container)
	msg := sprintf("[security-seccomp] %s container %s states no RuntimeDefault or Localhost seccomp profile", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	not object.get(section(container, "securityContext"), "allowPrivilegeEscalation", true) == false
	msg := sprintf("[security-escalation] %s container %s may escalate privileges", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	not object.get(section(container, "securityContext"), "readOnlyRootFilesystem", false) == true
	msg := sprintf("[security-read-only] %s container %s has a writable root filesystem", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	not "ALL" in list_of(section(section(container, "securityContext"), "capabilities"), "drop")
	msg := sprintf("[security-capabilities] %s container %s does not drop ALL capabilities", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	count(list_of(section(section(container, "securityContext"), "capabilities"), "add")) > 0
	msg := sprintf("[security-capabilities] %s container %s adds a capability", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	object.get(section(container, "securityContext"), "privileged", false) != false
	msg := sprintf("[security-privileged] %s container %s is privileged", [label(doc), container.name])
}

deny contains msg if {
	some [doc, spec] in workloads
	some field in {"hostNetwork", "hostPID", "hostIPC"}
	object.get(spec, field, false) != false
	msg := sprintf("[security-host] %s sets %s", [label(doc), field])
}

deny contains msg if {
	some [doc, spec] in workloads
	some volume in list_of(spec, "volumes")
	stated(volume, "hostPath")
	msg := sprintf("[security-host] %s mounts a hostPath volume %s", [label(doc), volume.name])
}

deny contains msg if {
	some [doc, spec] in workloads
	not object.get(spec, "automountServiceAccountToken", true) == false
	msg := sprintf("[security-token] %s mounts a service-account token", [label(doc)])
}

deny contains msg if {
	some doc in of_kind("ServiceAccount")
	not object.get(doc, "automountServiceAccountToken", true) == false
	msg := sprintf("[security-token] %s offers a service-account token", [label(doc)])
}

deny contains msg if {
	some [doc, spec] in workloads
	object.get(spec, "serviceAccountName", "default") == "default"
	msg := sprintf("[security-service-account] %s runs as the namespace's default ServiceAccount", [label(doc)])
}

# A name is not an account: a workload whose ServiceAccount is not rendered does not start, or takes whatever is there.
deny contains msg if {
	some [doc, spec] in workloads
	name := object.get(spec, "serviceAccountName", "default")
	name != "default"
	not service_account_rendered(name)
	msg := sprintf("[security-service-account] %s names ServiceAccount %v, which this overlay does not render", [label(doc), name])
}

service_account_rendered(name) if {
	some doc in of_kind("ServiceAccount")
	doc.metadata.name == name
}

# ------------------------------------------------------------------------------------- probes

deny contains msg if {
	some [doc, spec] in workloads
	doc.kind in long_running_kinds
	some container in list_of(spec, "containers")
	some probe in {"startupProbe", "livenessProbe", "readinessProbe"}
	not stated(container, probe)
	msg := sprintf("[probes-present] %s container %s has no %s", [label(doc), container.name, probe])
}

# A named port resolves to 8081 only through the container's own port list.
management_port(port) if {
	port == 8081
}

management_port(port) if {
	some declared in app_container.ports
	declared.name == port
	declared.containerPort == 8081
}

deny contains msg if {
	some [probe, path] in [
		["startupProbe", "/actuator/health/liveness"],
		["livenessProbe", "/actuator/health/liveness"],
		["readinessProbe", "/actuator/health/readiness"],
	]
	not app_probe_is(probe, path)
	msg := sprintf("[probes-management] %s must be %s on the management port, 8081", [probe, path])
}

app_probe_is(probe, path) if {
	get := app_container[probe].httpGet
	get.path == path
	management_port(get.port)
	object.get(get, "scheme", "HTTP") == "HTTP"
	not stated(get, "host")
}

# The pool's own wait, read from application.yaml, in whole seconds rounded up.
pool_wait_seconds := ceil(data.spring.datasource.hikari["connection-timeout"] / 1000)

deny contains msg if {
	timeout := object.get(section(app_container, "readinessProbe"), "timeoutSeconds", 1)
	timeout <= pool_wait_seconds
	msg := sprintf("[probes-readiness-timeout] readiness timeoutSeconds %d does not outlast the pool's %d s wait: with the database gone the probe would read a timeout rather than the 503", [timeout, pool_wait_seconds])
}

deny contains msg if {
	period := object.get(section(app_container, "readinessProbe"), "periodSeconds", 10)
	timeout := object.get(section(app_container, "readinessProbe"), "timeoutSeconds", 1)
	period <= timeout
	msg := sprintf("[probes-readiness-timeout] readiness periodSeconds %d is not longer than its timeoutSeconds %d: probes would overlap", [period, timeout])
}

# Liveness is the process alone (ADR-0016): it never waits on the pool.
deny contains msg if {
	timeout := object.get(section(app_container, "livenessProbe"), "timeoutSeconds", 1)
	timeout >= pool_wait_seconds
	msg := sprintf("[probes-liveness-timeout] liveness timeoutSeconds %d is as long as the pool's wait: a process check has no reason to be that patient", [timeout])
}

# ------------------------------------------------------------------------------------- requests and limits

deny contains msg if {
	some [doc, _, container] in all_containers
	some bound in {"requests", "limits"}
	some resource in {"cpu", "memory"}
	not stated(section(section(container, "resources"), bound), resource)
	msg := sprintf("[resources-present] %s container %s states no %s %s", [label(doc), container.name, resource, bound])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	requested := container.resources.requests.memory
	limit := container.resources.limits.memory
	units.parse_bytes(requested) != units.parse_bytes(limit)
	msg := sprintf("[resources-memory] %s container %s: memory request %s is not its limit %s", [label(doc), container.name, requested, limit])
}

# ------------------------------------------------------------------------------------- images

digest_only := `^[a-z0-9][a-z0-9._/:-]*@sha256:[0-9a-f]{64}$`

pinned_by_digest(container) if {
	regex.match(digest_only, container.image)
}

deny contains msg if {
	some [doc, _, container] in all_containers
	not pinned_by_digest(container)
	msg := sprintf("[images-digest] %s container %s: %s is not pinned by digest", [label(doc), container.name, object.get(container, "image", "no image at all")])
}

# ------------------------------------------------------------------------------------- what must not exist

deny contains msg if {
	some doc in of_kind("CronJob")
	msg := sprintf("[no-cronjob] %s: a CronJob is a single-instance scheduler by construction (ADR-0024)", [label(doc)])
}

deny contains msg if {
	data.overlay != "kind"
	some doc in of_kind("StatefulSet")
	msg := sprintf("[no-statefulset] %s in overlay %s: state lives outside the cluster (C3)", [label(doc), data.overlay])
}

labelled_test_only(doc) if {
	doc.metadata.labels["finapp/test-only"] == "true"
}

deny contains msg if {
	data.overlay == "kind"
	some doc in of_kind("StatefulSet")
	not labelled_test_only(doc)
	msg := sprintf("[no-statefulset] %s is not labelled finapp/test-only", [label(doc)])
}

deny contains msg if {
	data.overlay != "kind"
	some doc in documents
	labelled_test_only(doc)
	msg := sprintf("[no-test-only] %s is labelled test-only in overlay %s", [label(doc), data.overlay])
}

deny contains msg if {
	some doc in of_kind("Secret")
	some field in {"data", "stringData"}
	count(section(doc, field)) > 0
	msg := sprintf("[no-secret-data] %s carries %s: a secret is never committed", [label(doc), field])
}

# A credential written inline escapes the rule above, so a literal under a credential's name is refused too.
credential_name := `(?i)(password|passwd|secret|token|credential|api[-_]?key|private[-_]?key|jaas)`

deny contains msg if {
	some [doc, _, container] in all_containers
	some env in list_of(container, "env")
	regex.match(credential_name, env.name)
	stated(env, "value")
	msg := sprintf("[no-secret-literal] %s container %s writes %s inline", [label(doc), container.name, env.name])
}

deny contains msg if {
	some doc in of_kind("ConfigMap")
	some key, _ in doc.data
	regex.match(credential_name, key)
	msg := sprintf("[no-secret-literal] %s holds %s: a credential is never configuration", [label(doc), key])
}

# Endpoints arrive with the secret store's values, in every overlay: a committed URL or host is real or wrong.
endpoint_key := `(?i)(_URL|_URI|_HOSTS?|_ENDPOINTS?|BOOTSTRAP_SERVERS)$`

endpoint_value := `(?i)(://|^[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z][a-z0-9-]*(:[0-9]+)?$|^[a-z0-9.-]+:[0-9]{2,5}$)`

deny contains msg if {
	some doc in of_kind("ConfigMap")
	some key, value in doc.data
	is_endpoint(key, value)
	msg := sprintf("[no-endpoint] %s holds an endpoint at %s", [label(doc), key])
}

is_endpoint(key, _) if {
	regex.match(endpoint_key, key)
}

is_endpoint(_, value) if {
	regex.match(endpoint_value, value)
}

# ------------------------------------------------------------------------------------- the rollout

# Always defined while the Deployment is: a missing field reads as "absent", which equals no number.
rolling_field(name) := object.get(section(section(app.spec, "strategy"), "rollingUpdate"), name, "absent")

deny contains msg if {
	object.get(section(app.spec, "strategy"), "type", "absent") != "RollingUpdate"
	msg := "[rollout-strategy] finapp-app is not a RollingUpdate"
}

deny contains msg if {
	rolling_field("maxUnavailable") != 0
	msg := sprintf("[rollout-strategy] maxUnavailable must be 0, not %v", [rolling_field("maxUnavailable")])
}

deny contains msg if {
	rolling_field("maxSurge") != 1
	msg := sprintf("[rollout-strategy] maxSurge must be the integer 1, not %v", [rolling_field("maxSurge")])
}

deny contains msg if {
	object.get(app.spec, "minReadySeconds", 0) < app_spec.terminationGracePeriodSeconds
	msg := sprintf("[rollout-min-ready] minReadySeconds %d is below terminationGracePeriodSeconds %d: terminating pods would stack", [object.get(app.spec, "minReadySeconds", 0), app_spec.terminationGracePeriodSeconds])
}

deny contains msg if {
	not stated(app_spec, "terminationGracePeriodSeconds")
	msg := "[rollout-grace] terminationGracePeriodSeconds is not stated: the default 30 s cannot cover the drain"
}

deny contains msg if {
	stated(app.spec, "replicas")
	msg := "[rollout-replicas] spec.replicas is stated: each apply would reset the HorizontalPodAutoscaler"
}

startup_budget := object.get(section(app_container, "startupProbe"), "periodSeconds", 10) * object.get(section(app_container, "startupProbe"), "failureThreshold", 3)

deny contains msg if {
	object.get(app.spec, "progressDeadlineSeconds", 600) <= startup_budget + object.get(app.spec, "minReadySeconds", 0)
	msg := sprintf("[rollout-deadline] progressDeadlineSeconds %d cannot hold one step: %d s startup and minReadySeconds %d", [object.get(app.spec, "progressDeadlineSeconds", 600), startup_budget, object.get(app.spec, "minReadySeconds", 0)])
}

# A sleep action, because the image has no shell and no sleep binary for an exec.
pre_stop_seconds := app_container.lifecycle.preStop.sleep.seconds

pauses_before_stopping if {
	pre_stop_seconds > 0
}

deny contains msg if {
	app_container
	not pauses_before_stopping
	msg := "[rollout-prestop] finapp-app has no preStop sleep action: endpoints would still route to a stopping pod"
}

shutdown_sum := sum([seconds | some seconds in data.shutdownBudget])

deny contains msg if {
	floor := pre_stop_seconds + shutdown_sum
	app_spec.terminationGracePeriodSeconds < floor
	msg := sprintf("[rollout-grace] terminationGracePeriodSeconds %d is below the stop's worst case %d: preStop %d plus %d", [app_spec.terminationGracePeriodSeconds, floor, pre_stop_seconds, shutdown_sum])
}

deny contains msg if {
	not node_spread
	msg := "[topology-spread] finapp-app does not spread across nodes"
}

node_spread if {
	some constraint in list_of(app_spec, "topologySpreadConstraints")
	constraint.topologyKey == "kubernetes.io/hostname"
}

deny contains msg if {
	not zone_spread
	msg := "[topology-spread] finapp-app does not spread across zones, DoNotSchedule outside kind"
}

zone_spread if {
	some constraint in list_of(app_spec, "topologySpreadConstraints")
	constraint.topologyKey == "topology.kubernetes.io/zone"
	zone_rule_holds(constraint)
}

# kind's nodes carry no zone label, so DoNotSchedule there would schedule nothing.
zone_rule_holds(constraint) if {
	constraint.whenUnsatisfiable == "DoNotSchedule"
}

zone_rule_holds(_) if {
	data.overlay == "kind"
}

deny contains msg if {
	not app_management_address_from_pod
	msg := "[management-address] FINAPP_MANAGEMENT_ADDRESS must come from the downward API's status.podIP"
}

app_management_address_from_pod if {
	found := [env | some env in app_container.env; env.name == "FINAPP_MANAGEMENT_ADDRESS"]
	count(found) == 1
	found[0].valueFrom.fieldRef.fieldPath == "status.podIP"
	not stated(found[0], "value")
}

# ------------------------------------------------------------------------------------- the connection budget

declared(name) := to_number(config.data[name])

# application.yaml's own placeholder, ${VARIABLE:default}: the default a deployment that says nothing gets.
placeholder_default(text) := to_number(regex.find_all_string_submatch_n(`:([0-9]+)}$`, text, 1)[0][1])

writer_pool := to_number(data.spring.datasource.hikari["maximum-pool-size"])

reporting_pool := to_number(config.data.FINAPP_DB_REPORTING_POOL_SIZE)

reporting_pool := placeholder_default(data.finapp.database.reporting["pool-size"]) if {
	not stated(config.data, "FINAPP_DB_REPORTING_POOL_SIZE")
}

# The arithmetic has one source for each number: an overlay does not get to move the pool behind the budget's back.
# Every spelling Spring's relaxed binding reads as the pool or the budget's own numbers, the canonical
# SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE and finapp.database.* among them.
budget_override := `(?i)^(SPRING_DATASOURCE_HIKARI_(MAXIMUM_?POOL_?SIZE|MINIMUM_?IDLE)|SPRING_APPLICATION_JSON|FINAPP_DATABASE_[A-Z0-9_]+)$`

sets_the_budget(name) if {
	name in {"FINAPP_DB_INSTANCES", "FINAPP_DB_SERVER_MAX_CONNECTIONS", "FINAPP_DB_RESERVED_CONNECTIONS", "FINAPP_DB_REPORTING_POOL_SIZE"}
}

sets_the_budget(name) if {
	regex.match(budget_override, name)
}

deny contains msg if {
	some [doc, _, container] in all_containers
	some env in list_of(container, "env")
	sets_the_budget(env.name)
	msg := sprintf("[budget-one-source] %s container %s sets %s itself: the budget reads the ConfigMap", [label(doc), container.name, env.name])
}

deny contains msg if {
	some key, _ in section(config, "data")
	regex.match(budget_override, key)
	msg := sprintf("[budget-one-source] the ConfigMap sets %s: the pool's size is application.yaml's, read by this budget", [key])
}

is_declared(name) if {
	regex.match(`^[0-9]+$`, config.data[name])
}

deny contains msg if {
	some name in {"FINAPP_DB_INSTANCES", "FINAPP_DB_SERVER_MAX_CONNECTIONS", "FINAPP_DB_RESERVED_CONNECTIONS"}
	not is_declared(name)
	msg := sprintf("[budget-declared] %s is not declared in the ConfigMap as a whole number", [name])
}

deny contains msg if {
	not autoscaler
	msg := "[budget-declared] no HorizontalPodAutoscaler targets finapp-app, so its fleet has no bound"
}

deny contains msg if {
	surge := object.get(section(section(app.spec, "strategy"), "rollingUpdate"), "maxSurge", 0)
	is_number(surge)
	peak := autoscaler.spec.maxReplicas + (2 * surge) + 1
	peak > declared("FINAPP_DB_INSTANCES")
	msg := sprintf("[budget-instances] maxReplicas %d + 2 x maxSurge %d + 1 = %d exceeds FINAPP_DB_INSTANCES %d: a rollout's terminating pods each keep their pool", [autoscaler.spec.maxReplicas, surge, peak, declared("FINAPP_DB_INSTANCES")])
}

deny contains msg if {
	demand := declared("FINAPP_DB_INSTANCES") * (writer_pool + reporting_pool)
	available := declared("FINAPP_DB_SERVER_MAX_CONNECTIONS") - declared("FINAPP_DB_RESERVED_CONNECTIONS")
	demand > available
	msg := sprintf("[budget-fleet] %d instances x (%d writer + %d reporting) = %d exceeds the %d available: ConnectionPoolSizingGuard would refuse every pod", [declared("FINAPP_DB_INSTANCES"), writer_pool, reporting_pool, demand, available])
}

# The test-only server states the max_connections the fleet was told about, so the kind rehearsal measures the budget it says.
deny contains msg if {
	data.overlay == "kind"
	some doc in of_kind("StatefulSet")
	doc.metadata.name == "finapp-postgres"
	some container in doc.spec.template.spec.containers
	not told_the_declared_maximum(container)
	msg := "[budget-server-max] the test-only PostgreSQL's max_connections is not FINAPP_DB_SERVER_MAX_CONNECTIONS"
}

told_the_declared_maximum(container) if {
	sprintf("max_connections=%d", [declared("FINAPP_DB_SERVER_MAX_CONNECTIONS")]) in container.args
}

# ------------------------------------------------------------------------------------- the autoscaler

deny contains msg if {
	object.get(autoscaler.spec, "minReplicas", 1) < 3
	msg := sprintf("[hpa-min] minReplicas %d is below three: N is never 1, and a rollout or a drain must leave more than one", [object.get(autoscaler.spec, "minReplicas", 1)])
}

scale_down := section(section(autoscaler.spec, "behavior"), "scaleDown")

deny contains msg if {
	autoscaler
	count(list_of(scale_down, "policies")) == 0
	msg := "[hpa-scale-down] no scale-down policy: the default takes every pod above the target at once"
}

deny contains msg if {
	some policy in list_of(scale_down, "policies")
	not hpa_policy_holds(policy)
	msg := sprintf("[hpa-scale-down] scale-down policy %v must be one Pod per period of at least the grace", [policy])
}

hpa_policy_holds(policy) if {
	policy.type == "Pods"
	policy.value == 1
	policy.periodSeconds >= app_spec.terminationGracePeriodSeconds
}

deny contains msg if {
	autoscaler
	object.get(scale_down, "stabilizationWindowSeconds", 0) < 60
	msg := "[hpa-scale-down] the scale-down stabilisation window is under a minute: every step is a consumer rebalance"
}

# ------------------------------------------------------------------------------------- the rest of the shape

deny contains msg if {
	app
	not pdb_holds
	msg := "[pdb] no PodDisruptionBudget of maxUnavailable: 1 selects finapp-app"
}

pdb_holds if {
	some doc in of_kind("PodDisruptionBudget")
	doc.spec.maxUnavailable == 1
	doc.spec.selector.matchLabels["app.kubernetes.io/name"] == app.spec.selector.matchLabels["app.kubernetes.io/name"]
}

deny contains msg if {
	some doc in of_kind("Service")
	section(section(doc, "spec"), "selector")["app.kubernetes.io/name"] == "finapp-app"
	some port in doc.spec.ports
	not service_port_is_the_api(port)
	msg := sprintf("[service-api-only] %s exposes %v: only 8080, and never the management port", [label(doc), port])
}

# What a rule over Services cannot see: no Service at all.
deny contains msg if {
	app
	not api_service_rendered
	msg := "[service-present] no Service selects finapp-app on the API port: nothing could route to it"
}

api_service_rendered if {
	some doc in of_kind("Service")
	section(section(doc, "spec"), "selector")["app.kubernetes.io/name"] == "finapp-app"
	some port in list_of(doc.spec, "ports")
	service_port_is_the_api(port)
}

service_port_is_the_api(port) if {
	port.port == 8080
	port.targetPort in {8080, "http"}
}

deny contains msg if {
	some doc in of_kind("Service")
	not object.get(doc.spec, "type", "ClusterIP") == "ClusterIP"
	msg := sprintf("[service-api-only] %s is of type %s: traffic enters through the ingress alone", [label(doc), doc.spec.type])
}

deny contains msg if {
	some doc in of_kind("ConfigMap")
	startswith(doc.metadata.name, "finapp-app-config")
	not regex.match(`^finapp-app-config-[a-z0-9]{10}$`, doc.metadata.name)
	msg := sprintf("[config-hash] %s has no content hash in its name: a change would not roll the Deployment", [label(doc)])
}

deny contains msg if {
	app
	not config_is_referenced
	msg := "[config-hash] finapp-app does not read the rendered ConfigMap"
}

config_is_referenced if {
	some source in app_container.envFrom
	source.configMapRef.name == config.metadata.name
}

# The migrator's contract: run before the rollout, bounded, and never restarted in place.
deny contains msg if {
	some doc in of_kind("Job")
	not job_is_bounded(doc)
	msg := sprintf("[job-bounds] %s must state a backoffLimit of at most 2, an activeDeadlineSeconds and restartPolicy: Never", [label(doc)])
}

job_is_bounded(doc) if {
	doc.spec.backoffLimit <= 2
	doc.spec.activeDeadlineSeconds > 0
	doc.spec.template.spec.restartPolicy == "Never"
}

# The migrator's exit codes 10 and 12 are failures a retry cannot help (DATA_MIGRATIONS.md section 2.1).
deny contains msg if {
	some doc in of_kind("Job")
	doc.metadata.name == "finapp-migrator"
	not fails_at_once_on_a_lasting_failure(doc)
	msg := "[job-failure-policy] finapp-migrator must FailJob on exit codes 10 and 12"
}

fails_at_once_on_a_lasting_failure(doc) if {
	some rule in doc.spec.podFailurePolicy.rules
	rule.action == "FailJob"
	rule.onExitCodes.operator == "In"
	10 in rule.onExitCodes.values
	12 in rule.onExitCodes.values
}

# The entrypoint is the migrator and takes no arguments: a manifest setting either is doing something else.
deny contains msg if {
	some doc in of_kind("Job")
	doc.metadata.name == "finapp-migrator"
	some container in doc.spec.template.spec.containers
	some field in {"command", "args"}
	stated(container, field)
	msg := sprintf("[job-entrypoint] finapp-migrator sets %s: the image's entrypoint is the migrator, and takes no arguments", [field])
}

deny contains msg if {
	app
	not migrator_exists
	msg := "[job-bounds] no finapp-migrator Job in the rendered stream"
}

migrator_exists if {
	some doc in of_kind("Job")
	doc.metadata.name == "finapp-migrator"
}

# ------------------------------------------------------------------------------------- secret delivery (P15-TSK-018)

# An unnamed object must not make a rule's message undefined, which would pass it: label() walks into metadata.name.
name_of(doc) := object.get(section(doc, "metadata"), "name", "(unnamed)")

# Refusing a Secret that carries data leaves one a rule above cannot read: a value of the wrong shape, or none at all under the
# very name a workload reads, which an ExternalSecret would then have to fight. Nothing is rendered as a Secret in any overlay: in a
# deployed environment the operator writes them, and on kind the rehearsal does. This reads the object, so a Secret
# rendered by a Kustomize secretGenerator or inside a List is refused like any other.
deny contains msg if {
	some doc in of_kind("Secret")
	msg := sprintf("[no-secret-manifest] Secret/%v is rendered: a Secret is written by the operator, or by the kind rehearsal, and never committed", [name_of(doc)])
}

secret_readers := {"finapp-app-secrets": "finapp-app", "finapp-migrator-secrets": "finapp-migrator"}

external_secrets_for(target) := {doc |
	some doc in of_kind("ExternalSecret")
	section(section(doc, "spec"), "target").name == target
}

deny contains msg if {
	data.overlay == "kind"
	some wanted in {"ExternalSecret", "SecretStore"}
	some doc in of_kind(wanted)
	msg := sprintf("[secret-delivery] %s/%v in the kind overlay: there is no store on kind, the rehearsal generates its Secrets per run", [wanted, name_of(doc)])
}

deny contains msg if {
	data.overlay != "kind"
	some target, _ in secret_readers
	count(external_secrets_for(target)) != 1
	msg := sprintf("[secret-delivery] overlay %s needs exactly one ExternalSecret writing %s: the workload reading it does not start without it", [data.overlay, target])
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	not writes_a_known_secret(doc)
	msg := sprintf("[secret-delivery] ExternalSecret/%v writes a Secret no workload is known to read", [name_of(doc)])
}

writes_a_known_secret(doc) if {
	object.get(secret_readers, section(section(doc, "spec"), "target").name, "") != ""
}

env_secret_names(spec) := {source.secretRef.name |
	some container in containers(spec)
	some source in list_of(container, "envFrom")
}

deny contains msg if {
	some [doc, spec] in workloads
	some name in env_secret_names(spec)
	object.get(secret_readers, name, "") == ""
	msg := sprintf("[secret-delivery] %s reads Secret %v through envFrom, which nothing delivers", [label(doc), name])
}

references(spec, name) if {
	walk(spec, [_, value])
	value == name
}

# Anywhere in a pod spec: envFrom, a secretKeyRef, a volume or a projection are all ways to read it.
deny contains msg if {
	some [doc, spec] in workloads
	some target, reader in secret_readers
	references(spec, target)
	name_of(doc) != reader
	msg := sprintf("[secret-separation] %s reads %s, which only %s may read", [label(doc), target, reader])
}

# Through envFrom and not optional: an optional Secret that is absent starts the pod with no URL and every marked default.
deny contains msg if {
	some target, reader in secret_readers
	some doc in documents
	doc.kind in pod_template_kinds
	name_of(doc) == reader
	spec := doc.spec.template.spec
	not requires_its_secret(spec, target)
	msg := sprintf("[secret-delivery] %s must read %s through envFrom, and not optionally", [label(doc), target])
}

requires_its_secret(spec, target) if {
	some container in list_of(spec, "containers")
	some source in list_of(container, "envFrom")
	source.secretRef.name == target
	object.get(source.secretRef, "optional", false) == false
}

entry_keys(doc) := {entry.secretKey | some entry in list_of(section(doc, "spec"), "data")}

# The migrator's own, which the application must never hold, and the only entries the migrator's Secret may have.
migrator_only := {"FINAPP_DB_MIGRATOR_USER", "FINAPP_DB_MIGRATOR_PASSWORD", "FINAPP_DB_MASTER_USER", "FINAPP_DB_MASTER_PASSWORD"}

migrator_entries := {"FINAPP_DB_URL", "FINAPP_DB_MIGRATOR_USER", "FINAPP_DB_MIGRATOR_PASSWORD"}

deny contains msg if {
	some doc in external_secrets_for("finapp-app-secrets")
	some key in entry_keys(doc)
	key in migrator_only
	msg := sprintf("[secret-separation] finapp-app-secrets carries %s, which is the migrator's", [key])
}

deny contains msg if {
	some doc in external_secrets_for("finapp-migrator-secrets")
	entry_keys(doc) != migrator_entries
	msg := sprintf("[secret-separation] finapp-migrator-secrets carries %v: it is the URL, the migrator's user and its password, and nothing else", [entry_keys(doc)])
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	not synced_on_change(doc)
	msg := sprintf("[secret-sync] ExternalSecret/%v must be refreshPolicy OnChange with refreshInterval 0s: a value changing on the operator's clock reaches a restarted pod before anyone rolled the fleet", [name_of(doc)])
}

synced_on_change(doc) if {
	doc.spec.refreshPolicy == "OnChange"
	doc.spec.refreshInterval == "0s"
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	not owns_its_target(doc)
	msg := sprintf("[secret-sync] ExternalSecret/%v must be creationPolicy Owner and deletionPolicy Retain, with no template", [name_of(doc)])
}

owns_its_target(doc) if {
	doc.spec.target.creationPolicy == "Owner"
	doc.spec.target.deletionPolicy == "Retain"
	not stated(section(section(doc, "spec"), "target"), "template")
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	stated(section(doc, "spec"), "dataFrom")
	msg := sprintf("[secret-entries] ExternalSecret/%v uses dataFrom: every entry is named, or a stray entry in the store would reach a pod", [name_of(doc)])
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	count(list_of(section(doc, "spec"), "data")) == 0
	msg := sprintf("[secret-entries] ExternalSecret/%v has no entries", [name_of(doc)])
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	some entry in list_of(section(doc, "spec"), "data")
	not entry_is_plain(entry)
	not entry_reads_the_brokers_credential(entry)
	msg := sprintf("[secret-entries] ExternalSecret/%v entry %v must name one environment variable and read the store entry of the same name", [name_of(doc), entry])
}

# The one exception to a same-named entry: the managed brokers dictate this entry's name and shape, JSON with both fields.
brokers_entries := {"FINAPP_KAFKA_SASL_USERNAME": "username", "FINAPP_KAFKA_SASL_PASSWORD": "password"}

brokers_store := "finapp-app-kafka-store"

is_a_brokers_entry(entry) if {
	object.get(brokers_entries, entry.secretKey, "") != ""
}

entry_reads_the_brokers_credential(entry) if {
	entry.remoteRef.key == "application"
	entry.remoteRef.property == brokers_entries[entry.secretKey]
	count(object.keys(entry.remoteRef)) == 2
	entry.sourceRef.storeRef.name == brokers_store
	entry.sourceRef.storeRef.kind == "SecretStore"
	count(object.keys(entry.sourceRef)) == 1
	count(object.keys(entry)) == 3
}

entry_is_plain(entry) if {
	not is_a_brokers_entry(entry)
	regex.match(`^FINAPP_[A-Z0-9_]+$`, entry.secretKey)
	entry.remoteRef.key == entry.secretKey
	count(object.keys(entry.remoteRef)) == 1
	count(object.keys(entry)) == 2
}

# target, the store's path segment, the ServiceAccount the operator exchanges a token for.
store_parts := {
	"finapp-app-store": ["finapp-app-secrets", "app", "finapp-app-secret-reader"],
	"finapp-migrator-store": ["finapp-migrator-secrets", "migrator", "finapp-migrator-secret-reader"],
}

deny contains msg if {
	some forbidden in {"ClusterSecretStore", "ClusterExternalSecret", "PushSecret", "ClusterPushSecret"}
	some doc in of_kind(forbidden)
	msg := sprintf("[secret-store] %s/%v: a store is namespaced and read-only here", [forbidden, name_of(doc)])
}

deny contains msg if {
	data.overlay != "kind"
	some store in every_store
	not store_rendered(store)
	msg := sprintf("[secret-store] SecretStore %s is not rendered", [store])
}

every_store := object.keys(store_parts) | {brokers_store}

store_rendered(store) if {
	some doc in of_kind("SecretStore")
	name_of(doc) == store
}

deny contains msg if {
	some doc in of_kind("SecretStore")
	not store_is_its_own(doc)
	msg := sprintf("[secret-store] SecretStore/%v must be the store of one workload: AWS Secrets Manager under this overlay's own path, reached through that workload's reader ServiceAccount and nothing else", [name_of(doc)])
}

store_is_its_own(doc) if {
	parts := store_parts[name_of(doc)]
	reaches_the_store_as(doc, sprintf("finapp/%s/%s/", [data.overlay, parts[1]]), parts[2])
}

store_is_its_own(doc) if {
	name_of(doc) == brokers_store
	reaches_the_store_as(doc, sprintf("AmazonMSK_finapp-%s-", [data.overlay]), "finapp-app-secret-reader")
}

reaches_the_store_as(doc, prefix, reader) if {
	count(object.keys(doc.spec.provider)) == 1
	aws := doc.spec.provider.aws
	aws.service == "SecretsManager"
	aws.prefix == prefix
	count(object.keys(aws.auth)) == 1
	aws.auth.jwt.serviceAccountRef.name == reader
	not stated(aws, "role")
	not stated(aws, "additionalRoles")
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	not reads_its_own_store(doc)
	msg := sprintf("[secret-store] ExternalSecret/%v must read the namespaced SecretStore of its own workload", [name_of(doc)])
}

reads_its_own_store(doc) if {
	some store, parts in store_parts
	parts[0] == doc.spec.target.name
	doc.spec.secretStoreRef.name == store
	doc.spec.secretStoreRef.kind == "SecretStore"
}

# Placeholders by the stated convention (SECRET_MANAGEMENT.md section 3.5), as the sixty-four zeros are for a digest.
placeholder_region := "placeholder-region-0"

placeholder_role := `^arn:aws:iam::000000000000:role/placeholder-[a-z0-9-]+$`

deny contains msg if {
	some doc in of_kind("SecretStore")
	not region_is_the_placeholder(doc)
	msg := sprintf("[secret-placeholder] SecretStore/%v names a region: a real one is the environment's, not this repository's", [name_of(doc)])
}

region_is_the_placeholder(doc) if {
	doc.spec.provider.aws.region == placeholder_region
}

deny contains msg if {
	data.overlay != "kind"
	some _, parts in store_parts
	not reader_is_the_placeholder(parts[2])
	msg := sprintf("[secret-placeholder] ServiceAccount %s must exist and carry the placeholder role, not a real one", [parts[2]])
}

reader_is_the_placeholder(name) if {
	some doc in of_kind("ServiceAccount")
	name_of(doc) == name
	regex.match(placeholder_role, doc.metadata.annotations["eks.amazonaws.com/role-arn"])
}

# An ARN in any field of anything rendered: its account number is real unless it is the placeholder.
deny contains msg if {
	some doc in documents
	walk(doc, [_, value])
	is_string(value)
	some found in regex.find_all_string_submatch_n(`arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:([0-9]{12}):`, value, -1)
	found[1] != "000000000000"
	msg := sprintf("[secret-placeholder] %s/%v names an account: a real one is never committed", [object.get(doc, "kind", "?"), name_of(doc)])
}

# I3: a cloud identity belongs to the two readers, which run no pod, and to nothing else.
reader_accounts := {parts[2] | some _, parts in store_parts}

deny contains msg if {
	some [doc, spec] in workloads
	object.get(spec, "serviceAccountName", "default") in reader_accounts
	msg := sprintf("[secret-identity] %s runs as a store reader, which would give its pods the cloud identity that reads the store", [label(doc)])
}

deny contains msg if {
	some doc in of_kind("ServiceAccount")
	not name_of(doc) in reader_accounts
	object.get(section(section(doc, "metadata"), "annotations"), "eks.amazonaws.com/role-arn", "") != ""
	msg := sprintf("[secret-identity] ServiceAccount/%v carries a cloud role, and only the store readers may", [name_of(doc)])
}

generation_annotation := "finapp/rotation-generation"

generation_of(template) := object.get(section(section(template, "metadata"), "annotations"), generation_annotation, "absent")

is_a_generation(value) if {
	is_string(value)
	regex.match(`^[0-9]+$`, value)
}

deny contains msg if {
	app
	not is_a_generation(generation_of(app.spec.template))
	msg := sprintf("[secret-generation] finapp-app's pod template has no %s: nothing could roll a rotated secret to the fleet", [generation_annotation])
}

deny contains msg if {
	some doc in of_kind("ExternalSecret")
	not is_a_generation(generation_of(doc))
	msg := sprintf("[secret-generation] ExternalSecret/%v has no %s: a rotation could not make the operator read the store", [name_of(doc), generation_annotation])
}

# A rollout ahead of the sync would restart the fleet onto the value it already has, and report a rotation that never happened.
deny contains msg if {
	some doc in external_secrets_for("finapp-app-secrets")
	is_a_generation(generation_of(doc))
	is_a_generation(generation_of(app.spec.template))
	to_number(generation_of(app.spec.template)) > to_number(generation_of(doc))
	msg := sprintf("[secret-generation] finapp-app's pod template is at generation %v and its ExternalSecret at %v: sync first, then roll", [generation_of(app.spec.template), generation_of(doc)])
}
