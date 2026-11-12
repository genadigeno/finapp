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

# A token only to a controller the list below names, in its own namespace (P16-TSK-003: the cluster watcher of finapp-observability, the first
# workload of these namespaces that reads the API server): its workload mounts it and its ServiceAccount offers it. Every other workload and
# account is held as before, the application's and the migrator's above all.
deny contains msg if {
	some [doc, spec] in workloads
	not object.get(spec, "automountServiceAccountToken", true) == false
	not runs_as_a_listed_controller(doc, spec)
	msg := sprintf("[security-token] %s mounts a service-account token", [label(doc)])
}

deny contains msg if {
	some doc in of_kind("ServiceAccount")
	not object.get(doc, "automountServiceAccountToken", true) == false
	not is_a_listed_controller_account(doc)
	msg := sprintf("[security-token] %s offers a service-account token", [label(doc)])
}

runs_as_a_listed_controller(doc, spec) if {
	account_listed({"namespace": namespace_of(doc), "name": object.get(spec, "serviceAccountName", "default")})
}

is_a_listed_controller_account(doc) if {
	account_listed({"namespace": namespace_of(doc), "name": doc.metadata.name})
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

# ------------------------------------------------------------------------------------- the release (P15-TSK-017)
#
# In an environment GitOps delivers, every container of every workload, init containers included, runs exactly the pair that
# environment's release record names (deploy/overlays/<environment>/release.yaml, read into this run's data by manifest-check.sh):
# wherever an image is named, in the overlay's images:, a patch, a component or a base literal, it is one of the two. CI's recheck
# holds the overlay's images: to the record; this holds everything the overlay renders to it. A record this run could not read
# refuses rather than passes.
release_overlays := {"staging", "production"}

release_recorded if {
	is_string(data.release_app)
	is_string(data.release_migrator)
}

release_images contains sprintf("finapp-app@%s", [data.release_app]) if release_recorded

release_images contains sprintf("finapp-migrator@%s", [data.release_migrator]) if release_recorded

deny contains msg if {
	data.overlay in release_overlays
	not release_recorded
	msg := sprintf("[release-images] overlay %s has no release record in this run's data: what it may run cannot be read", [data.overlay])
}

deny contains msg if {
	data.overlay in release_overlays
	release_recorded
	some [doc, _, container] in all_containers
	not object.get(container, "image", "") in release_images
	msg := sprintf("[release-images] %s container %s runs %v, which is not the pair deploy/overlays/%s/release.yaml names", [label(doc), container.name, object.get(container, "image", null), data.overlay])
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

# Two names "token" reads wrongly in: a client's timeout for tokenising a card, each named whole, and only with a value that is a duration.
tokenisation_timeouts := {"FINAPP_PAYMENTMETHODS_TOKENISATION_TIMEOUT", "FINAPP_MERCHANT_PAYOUTDESTINATION_TOKENISATION_TIMEOUT"}

iso_duration := `^PT[0-9]+(\.[0-9]+)?[SMH]$`

a_tokenisation_timeout(key, value) if {
	tokenisation_timeouts[key]
	regex.match(iso_duration, value)
}

deny contains msg if {
	some [doc, _, container] in all_containers
	some env in list_of(container, "env")
	regex.match(credential_name, env.name)
	stated(env, "value")
	msg := sprintf("[no-secret-literal] %s container %s writes %s inline", [label(doc), container.name, env.name])
}

deny contains msg if {
	some doc in of_kind("ConfigMap")
	some key, value in doc.data
	regex.match(credential_name, key)
	not a_tokenisation_timeout(key, value)
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

# A headless Service (the scrape's, P15-TSK-023) publishes the pods' own addresses and balances nothing, so it is not the route
# the edge takes: it does not count (P15-TSK-017, which found the plant below accepted once the scrape's Service existed).
api_service_rendered if {
	some doc in of_kind("Service")
	section(section(doc, "spec"), "selector")["app.kubernetes.io/name"] == "finapp-app"
	object.get(section(doc, "spec"), "clusterIP", "") != "None"
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

# ------------------------------------------------------------------------------------- namespaces (P15-TSK-010)
#
# EVERYTHING BELOW HOLDS WHAT A CLUSTER WOULD BE TOLD, read from the rendered stream, and proves nothing about a network
# plugin or an admission controller doing it. That is the kind rehearsal's. Each flow, role and number is read from the
# manifests; only the allowed flows themselves are written here, because they are the decision.

managed_namespaces := {"finapp", "finapp-edge", "finapp-observability"}

namespace_of(doc) := object.get(section(doc, "metadata"), "namespace", "absent")

namespace_rendered(name) if {
	some doc in of_kind("Namespace")
	doc.metadata.name == name
}

deny contains msg if {
	some name in managed_namespaces
	not namespace_rendered(name)
	msg := sprintf("[namespace-present] namespace %s is not rendered: nothing would state its Pod Security level", [name])
}

deny contains msg if {
	some [doc, _] in workloads
	not namespace_of(doc) in managed_namespaces
	msg := sprintf("[namespace-workload] %s is in namespace %v, which none of these policies is written for", [label(doc), namespace_of(doc)])
}

namespace_label(doc, name) := section(section(doc, "metadata"), "labels")[name]

pod_security_restricted(doc, mode) if {
	namespace_label(doc, sprintf("pod-security.kubernetes.io/%s", [mode])) == "restricted"
}

pod_security_version_pinned(doc, mode) if {
	regex.match(`^v1\.[0-9]+$`, namespace_label(doc, sprintf("pod-security.kubernetes.io/%s-version", [mode])))
}

deny contains msg if {
	some doc in of_kind("Namespace")
	some mode in ["enforce", "audit", "warn"]
	not pod_security_restricted(doc, mode)
	msg := sprintf("[psa-restricted] namespace %s does not state Pod Security %s: restricted", [doc.metadata.name, mode])
}

# `latest`, or nothing, means the next upgrade changes what is refused without a commit saying so.
deny contains msg if {
	some doc in of_kind("Namespace")
	some mode in ["enforce", "audit", "warn"]
	not pod_security_version_pinned(doc, mode)
	msg := sprintf("[psa-version] namespace %s does not pin Pod Security's %s version to a minor", [doc.metadata.name, mode])
}

# A projected volume can mint a token whatever automountServiceAccountToken says.
deny contains msg if {
	some [doc, spec] in workloads
	some volume in list_of(spec, "volumes")
	some source in list_of(section(volume, "projected"), "sources")
	stated(source, "serviceAccountToken")
	msg := sprintf("[security-token] %s projects a service-account token into volume %v", [label(doc), object.get(volume, "name", "with no name")])
}

# ------------------------------------------------------------------------------------- network policy (P15-TSK-010)

network_policies := of_kind("NetworkPolicy")

policy_spec(policy) := section(policy, "spec")

policy_types(policy) := {type | some type in list_of(policy_spec(policy), "policyTypes")}

# Every pod, both directions, no rule. `ingress: [{}]` is the opposite: one rule admitting everything.
is_default_deny(policy) if {
	count(section(policy_spec(policy), "podSelector")) == 0
	policy_types(policy) == {"Ingress", "Egress"}
	count(list_of(policy_spec(policy), "ingress")) == 0
	count(list_of(policy_spec(policy), "egress")) == 0
}

default_denied(namespace) if {
	some policy in network_policies
	is_default_deny(policy)
	namespace_of(policy) == namespace
}

# The edge's and telemetry's namespaces are the workloads' own tasks' to close: THREAT_MODEL.md section 6 item 21.
deny contains msg if {
	some [doc, _] in workloads
	namespace_of(doc) == "finapp"
	not default_denied("finapp")
	msg := sprintf("[netpol-default-deny] %s runs where no NetworkPolicy denies every pod in both directions", [label(doc)])
}

selector_is_simple(selector) if {
	count(selector) == 0
}

selector_is_simple(selector) if {
	object.keys(selector) == {"matchLabels"}
	is_object(selector.matchLabels)
}

deny contains msg if {
	some policy in network_policies
	not selector_is_simple(section(policy_spec(policy), "podSelector"))
	msg := sprintf("[netpol-selector] %s selects by more than matchLabels, so these rules cannot say which pods it covers", [label(policy)])
}

direction_peer := {"ingress": "from", "egress": "to"}

peers(rule, direction) := list_of(rule, direction_peer[direction])

all_rules contains [policy, direction, rule] if {
	some policy in network_policies
	some direction, _ in direction_peer
	some rule in list_of(policy_spec(policy), direction)
}

# No peers is the whole world, not nobody. Likewise no ports: every port. Both are what default-deny exists to refuse.
deny contains msg if {
	some [policy, direction, rule] in all_rules
	count(peers(rule, direction)) == 0
	msg := sprintf("[netpol-open-peer] %s has an %s rule naming no peer, which admits everything", [label(policy), direction])
}

port_is_exact(port) if {
	is_number(port.port)
	not stated(port, "endPort")
}

rule_names_its_ports(rule) if {
	count(list_of(rule, "ports")) > 0
	every port in list_of(rule, "ports") {
		port_is_exact(port)
	}
}

deny contains msg if {
	some [policy, direction, rule] in all_rules
	not rule_names_its_ports(rule)
	msg := sprintf("[netpol-ports] %s has an %s rule that does not state exact numbered ports", [label(policy), direction])
}

ports_within(rule, allowed) if {
	count(list_of(rule, "ports")) > 0
	every port in list_of(rule, "ports") {
		[object.get(port, "protocol", "TCP"), port.port] in allowed
	}
}

# The data tier's addresses are per overlay and never real: RFC 5737's documentation ranges only, routed nowhere.
documentation_ranges := {"192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"}

in_documentation_range(cidr) if {
	some range in documentation_ranges
	net.cidr_contains(range, cidr)
}

deny contains msg if {
	some [policy, direction, rule] in all_rules
	some peer in peers(rule, direction)
	stated(peer, "ipBlock")
	not in_documentation_range(object.get(section(peer, "ipBlock"), "cidr", "absent"))
	msg := sprintf("[netpol-placeholder-cidr] %s names %v: an address committed here is real or wrong", [label(policy), section(peer, "ipBlock")])
}

# A namespace AND a pod in one entry. Two entries would be OR.
peer_selects(peer, namespace, key, value) if {
	object.keys(peer) == {"namespaceSelector", "podSelector"}
	peer.namespaceSelector == {"matchLabels": {"kubernetes.io/metadata.name": namespace}}
	peer.podSelector == {"matchLabels": {key: value}}
}

peer_is_local(peer, names) if {
	object.keys(peer) == {"podSelector"}
	some name in names
	peer.podSelector == {"matchLabels": {"app.kubernetes.io/name": name}}
}

peer_is_an_address(peer) if {
	object.keys(peer) == {"ipBlock"}
	in_documentation_range(peer.ipBlock.cidr)
	not stated(peer.ipBlock, "except")
}

every_peer_selects(rule, direction, namespace, key, value) if {
	count(peers(rule, direction)) > 0
	every peer in peers(rule, direction) {
		peer_selects(peer, namespace, key, value)
	}
}

every_peer_local(rule, direction, names) if {
	count(peers(rule, direction)) > 0
	every peer in peers(rule, direction) {
		peer_is_local(peer, names)
	}
}

every_peer_an_address(rule) if {
	count(peers(rule, "egress")) > 0
	every peer in peers(rule, "egress") {
		peer_is_an_address(peer)
	}
}

# THE ALLOWED FLOWS, PHASE_15_PLAN.md section 6. A rule that is none of these opens something nobody decided.
egress_flow(rule, "dns") if {
	every_peer_selects(rule, "egress", "kube-system", "k8s-app", "kube-dns")
	ports_within(rule, {["UDP", 53], ["TCP", 53]})
}

egress_flow(rule, "collector") if {
	every_peer_selects(rule, "egress", "finapp-observability", "finapp/role", "telemetry-collector")
	ports_within(rule, {["TCP", 4318]})
}

egress_flow(rule, "postgresql") if {
	data.overlay != "kind"
	every_peer_an_address(rule)
	ports_within(rule, {["TCP", 5432]})
}

egress_flow(rule, "postgresql") if {
	data.overlay == "kind"
	every_peer_local(rule, "egress", {"finapp-postgres"})
	ports_within(rule, {["TCP", 5432]})
}

egress_flow(rule, "kafka") if {
	data.overlay != "kind"
	every_peer_an_address(rule)
	ports_within(rule, {["TCP", 9096]})
}

egress_flow(rule, "kafka") if {
	data.overlay == "kind"
	every_peer_local(rule, "egress", {"finapp-kafka"})
	ports_within(rule, {["TCP", 9092]})
}

egress_flow(rule, "providers") if {
	every_peer_an_address(rule)
	ports_within(rule, {["TCP", 443]})
}

# kind's providers are simulated, by one test-only pod in the rehearsal's own namespace on its own port. Refused in every other overlay.
egress_flow(rule, "providers") if {
	data.overlay == "kind"
	every_peer_selects(rule, "egress", "finapp-rehearsal", "finapp/role", "provider-simulator")
	ports_within(rule, {["TCP", 8443]})
}

ingress_flow(rule, "edge") if {
	every_peer_selects(rule, "ingress", "finapp-edge", "finapp/role", "ingress-controller")
	ports_within(rule, {["TCP", 8080]})
}

ingress_flow(rule, "scrape") if {
	every_peer_selects(rule, "ingress", "finapp-observability", "finapp/role", "metrics-scraper")
	ports_within(rule, {["TCP", 8081]})
}

ingress_flow(rule, "postgresql-clients") if {
	data.overlay == "kind"
	every_peer_local(rule, "ingress", {"finapp-app", "finapp-migrator"})
	ports_within(rule, {["TCP", 5432]})
}

ingress_flow(rule, "kafka-clients") if {
	data.overlay == "kind"
	every_peer_local(rule, "ingress", {"finapp-app"})
	ports_within(rule, {["TCP", 9092]})
}

egress_flow_names := {"dns", "collector", "postgresql", "kafka", "providers"}

ingress_flow_names := {"edge", "scrape", "postgresql-clients", "kafka-clients"}

flows_of(rule, "egress") := {flow | some flow in egress_flow_names; egress_flow(rule, flow)}

flows_of(rule, "ingress") := {flow | some flow in ingress_flow_names; ingress_flow(rule, flow)}

# What each workload may do. Anything not listed may do nothing.
flows_allowed := {
	"egress": {
		"finapp-app": {"dns", "collector", "postgresql", "kafka", "providers"},
		"finapp-migrator": {"dns", "postgresql"},
		"finapp-kafka": {"dns"},
	},
	"ingress": {
		"finapp-app": {"edge", "scrape"},
		"finapp-postgres": {"postgresql-clients"},
		"finapp-kafka": {"kafka-clients"},
	},
}

# Default-deny breaks whatever is forgotten, so what each workload needs is held too.
flows_required := {
	"egress": {
		"finapp-app": {"dns", "collector", "postgresql", "kafka", "providers"},
		"finapp-migrator": {"dns", "postgresql"},
		"finapp-kafka": {"dns"},
	},
	"ingress": {
		"finapp-app": {"edge", "scrape"},
		"finapp-postgres": {"postgresql-clients"},
		"finapp-kafka": {"kafka-clients"},
	},
}

workload_labels(doc) := section(section(doc, "metadata"), "labels") if {
	doc.kind == "Pod"
} else := section(section(section(section(doc, "spec"), "template"), "metadata"), "labels")

workload_name(doc) := object.get(workload_labels(doc), "app.kubernetes.io/name", "unlabelled")

policy_applies(policy, labels) if {
	selector := section(policy_spec(policy), "podSelector")
	selector_is_simple(selector)
	every key, value in section(selector, "matchLabels") {
		labels[key] == value
	}
}

# `policyTypes` decides whether a direction's rules count at all: one left out is ignored by the API, whatever is written.
direction_type := {"ingress": "Ingress", "egress": "Egress"}

workload_rules contains [doc, direction, policy, rule] if {
	some [policy, direction, rule] in all_rules
	some [doc, _] in workloads
	namespace_of(policy) == namespace_of(doc)
	policy_applies(policy, workload_labels(doc))
}

workload_has_flow(doc, direction, flow) if {
	some [candidate, direction_seen, policy, rule] in workload_rules
	candidate == doc
	direction_seen == direction
	direction_type[direction] in policy_types(policy)
	flow in flows_of(rule, direction)
}

deny contains msg if {
	some [doc, "egress", policy, rule] in workload_rules
	count(flows_of(rule, "egress") & object.get(flows_allowed.egress, workload_name(doc), set())) == 0
	msg := sprintf("[netpol-egress-closed] %s opens to %s an egress no flow of PHASE_15_PLAN.md section 6 allows: %v", [label(policy), workload_name(doc), rule])
}

deny contains msg if {
	some [doc, "ingress", policy, rule] in workload_rules
	count(flows_of(rule, "ingress") & object.get(flows_allowed.ingress, workload_name(doc), set())) == 0
	msg := sprintf("[netpol-ingress-closed] %s admits to %s what no flow of PHASE_15_PLAN.md section 6 allows: %v", [label(policy), workload_name(doc), rule])
}

deny contains msg if {
	some [doc, _] in workloads
	some direction, _ in direction_peer
	some flow in object.get(flows_required[direction], workload_name(doc), set())
	not workload_has_flow(doc, direction, flow)
	msg := sprintf("[netpol-flow-missing] %s has no %s rule for %s, and default-deny refuses it", [label(doc), direction, flow])
}

resolver_open(doc, protocol) if {
	some [candidate, "egress", policy, rule] in workload_rules
	candidate == doc
	"Egress" in policy_types(policy)
	egress_flow(rule, "dns")
	some port in list_of(rule, "ports")
	object.get(port, "protocol", "TCP") == protocol
}

# UDP alone fails only for an answer too long for a datagram, which is the worst way to find out.
deny contains msg if {
	some [doc, _] in workloads
	"dns" in object.get(flows_required.egress, workload_name(doc), set())
	some protocol in {"UDP", "TCP"}
	not resolver_open(doc, protocol)
	msg := sprintf("[netpol-dns] %s cannot reach the cluster's resolver over %s", [label(doc), protocol])
}

# ------------------------------------------------------------------------------------- RBAC (P15-TSK-010)
#
# WHOSE ROLE IS IT is read from the bindings, failing closed: a role is a controller's only if some binding names a
# ServiceAccount on the list below and no binding names anything else. Everything else, an unbound role included, is held to
# the human rules. So a listed controller's role that writes Secrets is left alone, and the same role bound to a person is
# refused. A ServiceAccount is a credential a pod holds, so a binding to one not on the list is refused whatever it grants:
# a role given to the wrong account reaches exactly as far as one given to a person.

# THE CONTROLLER ACCOUNTS, one per line: adding a controller is adding its line, and nothing else.
# The cluster watcher (P16-TSK-003): list and watch in finapp, granted by deploy/base/rbac-cluster-watch.yaml; the only account that holds a token.
controller_accounts := [
	{"namespace": "finapp-observability", "name": "finapp-cluster-watch"},
]

# Accepted in the kind overlay alone, so a plant can show a controller's role is left alone while no real overlay trusts it.
test_only_controller_accounts := [{"namespace": "finapp", "name": "finapp-test-only-controller"}]

account_listed(account) if {
	account in controller_accounts
}

account_listed(account) if {
	data.overlay == "kind"
	account in test_only_controller_accounts
}

rbac_roles := of_kind("Role") | of_kind("ClusterRole")

rbac_bindings := of_kind("RoleBinding") | of_kind("ClusterRoleBinding")

binds(binding, role) if {
	section(binding, "roleRef").kind == role.kind
	section(binding, "roleRef").name == role.metadata.name
	binding_reaches(binding, role)
}

# A RoleBinding names a Role of its own namespace: a same-named Role elsewhere is another role, and must not borrow this
# one's standing as a controller's.
binding_reaches(_, role) if {
	role.kind == "ClusterRole"
}

binding_reaches(binding, role) if {
	role.kind == "Role"
	namespace_of(binding) == namespace_of(role)
}

is_service_account(subject) if {
	subject.kind == "ServiceAccount"
}

is_listed_controller(subject) if {
	is_service_account(subject)
	account_listed({"namespace": object.get(subject, "namespace", "absent"), "name": subject.name})
}

held_by_other_than_a_controller(role) if {
	some binding in rbac_bindings
	binds(binding, role)
	some subject in list_of(binding, "subjects")
	not is_listed_controller(subject)
}

controller_role(role) if {
	some binding in rbac_bindings
	binds(binding, role)
	some subject in list_of(binding, "subjects")
	is_listed_controller(subject)
	not held_by_other_than_a_controller(role)
}

human_roles := {role | some role in rbac_roles; not controller_role(role)}

human_rules contains [role, rule] if {
	some role in human_roles
	some rule in list_of(role, "rules")
}

break_glass_role := "finapp-break-glass"

read_verbs := {"get", "list", "watch"}

# Each group is the one role's alone. A person arrives through the identity layer: nobody is named here.
human_groups := {"finapp-read-only": "finapp-read-only", "finapp-break-glass": "finapp-break-glass"}

human_role_bound(name) if {
	some role in of_kind("Role")
	role.metadata.name == name
	some binding in of_kind("RoleBinding")
	binds(binding, role)
	some subject in list_of(binding, "subjects")
	subject.kind == "Group"
	subject.name == name
}

deny contains msg if {
	some name, _ in human_groups
	not human_role_bound(name)
	msg := sprintf("[rbac-present] role %s is not rendered and bound to its group: the rules below would hold nothing", [name])
}

# get, list and watch each return the data. Refused in any verb, by name or not.
deny contains msg if {
	some [role, rule] in human_rules
	some resource in list_of(rule, "resources")
	resource_is_secrets(resource)
	msg := sprintf("[rbac-human-secrets] %s, a role a person could hold, names %s", [label(role), resource])
}

resource_is_secrets(resource) if {
	resource == "secrets"
}

resource_is_secrets(resource) if {
	startswith(resource, "secrets/")
}

# `*` on a verb or a resource includes everything above, and RBAC reads `*/exec` as every resource's exec.
deny contains msg if {
	some [role, rule] in human_rules
	some field in ["verbs", "resources", "apiGroups", "nonResourceURLs"]
	some entry in list_of(rule, field)
	contains(entry, "*")
	msg := sprintf("[rbac-human-wildcard] %s has a wildcard in %s: %s", [label(role), field, entry])
}

# Ways inside a pod or into another identity, refused in any verb: a WebSocket upgrade makes `get` enough for some.
reaches_inside := {
	"pods/exec",
	"pods/attach",
	"pods/portforward",
	"pods/ephemeralcontainers",
	"pods/proxy",
	"services/proxy",
	"nodes/proxy",
	"serviceaccounts/token",
}

deny contains msg if {
	some [role, rule] in human_rules
	some resource in list_of(rule, "resources")
	resource in reaches_inside
	msg := sprintf("[rbac-human-subresource] %s names %s", [label(role), resource])
}

deny contains msg if {
	some [role, rule] in human_rules
	some verb in list_of(rule, "verbs")
	verb in {"impersonate", "escalate", "bind"}
	msg := sprintf("[rbac-human-privilege] %s holds %s", [label(role), verb])
}

deny contains msg if {
	some [role, rule] in human_rules
	role.metadata.name != break_glass_role
	some verb in list_of(rule, "verbs")
	not verb in read_verbs
	msg := sprintf("[rbac-human-verbs] %s holds %s: a person's standing role is read-only", [label(role), verb])
}

# An aggregated role's rules are filled in by the cluster, so what is rendered here is not what it would grant.
deny contains msg if {
	some role in human_roles
	stated(role, "aggregationRule")
	msg := sprintf("[rbac-human-aggregated] %s is aggregated, so nothing here can read what it grants", [label(role)])
}

# Stopping a pod and changing a replica count cannot read a Secret. Creating a pod or patching a template can.
break_glass_writes := {
	["delete", "pods"],
	["create", "pods/eviction"],
	["patch", "deployments/scale"],
	["update", "deployments/scale"],
}

deny contains msg if {
	some [role, rule] in human_rules
	role.metadata.name == break_glass_role
	some verb in list_of(rule, "verbs")
	not verb in read_verbs
	some resource in list_of(rule, "resources")
	not [verb, resource] in break_glass_writes
	msg := sprintf("[rbac-break-glass-writes] %s may %s %s: its writes are a closed list", [label(role), verb, resource])
}

subject_is_the_roles_group(binding, subject) if {
	subject.kind == "Group"
	human_groups[subject.name] == section(binding, "roleRef").name
}

deny contains msg if {
	some binding in rbac_bindings
	some subject in list_of(binding, "subjects")
	not is_service_account(subject)
	not subject_is_the_roles_group(binding, subject)
	msg := sprintf("[rbac-human-subject] %s binds %v: only a role's own group may hold it", [label(binding), subject])
}

role_rendered(binding) if {
	some role in rbac_roles
	binds(binding, role)
}

# cluster-admin, admin, edit and view are not in the stream: a role nothing here can read is a role nothing here can hold,
# for a person or an account.
deny contains msg if {
	some binding in rbac_bindings
	not role_rendered(binding)
	msg := sprintf("[rbac-binding-rendered] %s gives %v, which this stream does not render", [label(binding), section(binding, "roleRef")])
}

deny contains msg if {
	some binding in rbac_bindings
	some subject in list_of(binding, "subjects")
	is_service_account(subject)
	not is_listed_controller(subject)
	msg := sprintf("[rbac-account-unlisted] %s binds ServiceAccount %v/%v, which is not on the list of controller accounts", [label(binding), object.get(subject, "namespace", "with no namespace"), object.get(subject, "name", "with no name")])
}

# The cluster merges a role carrying one of these labels into view, edit or admin, which is every person holding those.
deny contains msg if {
	some role in of_kind("ClusterRole")
	some key, _ in section(section(role, "metadata"), "labels")
	startswith(key, "rbac.authorization.k8s.io/aggregate-to-")
	msg := sprintf("[rbac-aggregate-label] %s carries %s, so the cluster would merge it into a built-in role", [label(role), key])
}

# A person's access is these namespaces': the application's, and the telemetry pipeline's (P15-TSK-032, THREAT_MODEL.md section 6 item 42 (k):
# deploy/observability/base/rbac-humans.yaml, held by these rules through observability.rego). A Role and binding of the same names anywhere
# else would pass every rule above there.
person_namespaces := {"finapp", "finapp-observability"}

in_a_person_namespace(binding) if {
	namespace_of(binding) in person_namespaces
}

deny contains msg if {
	some binding in rbac_bindings
	some subject in list_of(binding, "subjects")
	not is_service_account(subject)
	not in_a_person_namespace(binding)
	msg := sprintf("[rbac-human-namespace] %s gives a person access in namespace %v", [label(binding), namespace_of(binding)])
}

deny contains msg if {
	some binding in of_kind("ClusterRoleBinding")
	some subject in list_of(binding, "subjects")
	not is_service_account(subject)
	msg := sprintf("[rbac-human-cluster-wide] %s reaches every namespace: a person's access is one namespace's", [label(binding)])
}

# THE CLUSTER WATCHER'S READ, EXACTLY (P16-TSK-003). Its account is a listed controller, so no rule written for a person's role reads its
# role, and a controller's role is otherwise left alone: this rule reads it. List and watch on the kinds its receiver lists and on events,
# in finapp alone, by a RoleBinding; a Secret, a ConfigMap, pods/log, a write, a wildcard or a ClusterRoleBinding is refused.
cluster_watch_account := {"namespace": "finapp-observability", "name": "finapp-cluster-watch"}

cluster_watch_reads := {
	["", "pods"], ["", "events"], ["", "services"], ["", "replicationcontrollers"], ["", "resourcequotas"],
	["apps", "deployments"], ["apps", "replicasets"], ["apps", "statefulsets"], ["apps", "daemonsets"],
	["batch", "jobs"], ["batch", "cronjobs"], ["autoscaling", "horizontalpodautoscalers"],
}

binds_the_cluster_watch(binding) if {
	some subject in list_of(binding, "subjects")
	is_service_account(subject)
	{"namespace": object.get(subject, "namespace", "absent"), "name": object.get(subject, "name", "absent")} == cluster_watch_account
}

cluster_watch_may(group, resource, verb) if {
	[group, resource] in cluster_watch_reads
	verb in {"list", "watch"}
}

deny contains msg if {
	some binding in rbac_bindings
	binds_the_cluster_watch(binding)
	some role in rbac_roles
	binds(binding, role)
	some rule in list_of(role, "rules")
	some group in list_of(rule, "apiGroups")
	some resource in list_of(rule, "resources")
	some verb in list_of(rule, "verbs")
	not cluster_watch_may(group, resource, verb)
	msg := sprintf("[rbac-cluster-watch] %s gives the cluster watcher %v %v in API group %v: it lists and watches the kinds its receiver reads, and nothing else", [label(role), verb, resource, group])
}

watch_bound_in_finapp(binding) if {
	binding.kind == "RoleBinding"
	namespace_of(binding) == "finapp"
}

deny contains msg if {
	some binding in rbac_bindings
	binds_the_cluster_watch(binding)
	not watch_bound_in_finapp(binding)
	msg := sprintf("[rbac-cluster-watch] %s binds the cluster watcher other than by a RoleBinding in finapp", [label(binding)])
}

workload_accounts := {"finapp-app", "finapp-migrator"}

deny contains msg if {
	some binding in rbac_bindings
	some subject in list_of(binding, "subjects")
	is_service_account(subject)
	subject.name in workload_accounts
	msg := sprintf("[rbac-workload-none] %s gives %s RBAC: neither workload calls the Kubernetes API", [label(binding), subject.name])
}

# ------------------------------------------------------------------------------------- quota and limits (P15-TSK-010)

resource_quota_rendered if {
	some doc in of_kind("ResourceQuota")
	doc.metadata.name == "finapp"
	namespace_of(doc) == "finapp"
}

quota_hard := hard if {
	some doc in of_kind("ResourceQuota")
	doc.metadata.name == "finapp"
	hard := section(section(doc, "spec"), "hard")
}

deny contains msg if {
	app
	not resource_quota_rendered
	msg := "[quota-present] no ResourceQuota in namespace finapp: a pod with no requests would be admitted"
}

# Two refusals, in admission's order. A pod stating nothing meets the LimitRange's Pod item first ("No request is specified").
# A pod where one container states everything and another nothing passes that item, whose per-pod sum exists, and meets this
# quota, which checks each container and only for the keys it states ("must specify"). So every key is needed.
deny contains msg if {
	resource_quota_rendered
	some key in {"pods", "requests.cpu", "requests.memory", "limits.cpu", "limits.memory"}
	not stated(quota_hard, key)
	msg := sprintf("[quota-requires] the quota does not state %s", [key])
}

# A scoped quota covers only the pods its scope names, and BestEffort is precisely the pods with no requests.
deny contains msg if {
	some doc in of_kind("ResourceQuota")
	some field in {"scopes", "scopeSelector"}
	stated(section(doc, "spec"), field)
	msg := sprintf("[quota-scoped] %s has %s, so some pods go unmeasured", [label(doc), field])
}

quantity(value) := units.parse(sprintf("%v", [value]))

# Held equal, in both directions: a quota above the worst moment bounds nothing. Rounded to milli-units, the finest a quantity has.
milli(value) := round(value * 1000)

# A pod inside its grace is still charged, so the worst moment is the budget's: PHASE_15_PLAN.md section 6.1.
app_peak_pods := autoscaler.spec.maxReplicas + (2 * rolling_field("maxSurge")) + 1

workload_peak(doc) := app_peak_pods if {
	doc.kind == "Deployment"
	doc.metadata.name == "finapp-app"
} else := object.get(doc.spec, "parallelism", 1) if {
	doc.kind == "Job"
} else := object.get(doc.spec, "replicas", 1) if {
	doc.kind in {"StatefulSet", "ReplicaSet", "ReplicationController", "Deployment"}
} else := 1

finapp_workloads := {[doc, spec] | some [doc, spec] in workloads; namespace_of(doc) == "finapp"}

pods_required := sum([workload_peak(doc) | some [doc, _] in finapp_workloads])

deny contains msg if {
	milli(quantity(quota_hard.pods)) != milli(pods_required)
	msg := sprintf("[quota-pods] the quota states %v pods and the worst moment of a rollout is %d: maxReplicas + 2 x maxSurge + 1, the migrator and anything else rendered. Below it a rollout stalls on its own quota; above it the quota bounds nothing", [quota_hard.pods, pods_required])
}

# One pod's share: the sum of its containers, or its largest init container, whichever is greater.
pod_total(spec, bound, resource) := max(array.concat([sum(main)], init)) if {
	main := [quantity(section(section(container, "resources"), bound)[resource]) | some container in list_of(spec, "containers")]
	init := [quantity(section(section(container, "resources"), bound)[resource]) | some container in list_of(spec, "initContainers")]
}

compute_required(bound, resource) := sum([share |
	some [doc, spec] in finapp_workloads
	share := workload_peak(doc) * pod_total(spec, bound, resource)
])

deny contains msg if {
	some bound in {"requests", "limits"}
	some resource in {"cpu", "memory"}
	key := sprintf("%s.%s", [bound, resource])
	milli(quantity(quota_hard[key])) != milli(compute_required(bound, resource))
	msg := sprintf("[quota-compute] the quota's %s is %v, and the worst moment of a rollout needs %v exactly", [key, quota_hard[key], compute_required(bound, resource)])
}

limit_items contains [doc, item] if {
	some doc in of_kind("LimitRange")
	some item in list_of(section(doc, "spec"), "limits")
}

pod_limit_items := {item | some [_, item] in limit_items; object.get(item, "type", "absent") == "Pod"}

# Both bounds and both resources: an item with a null or missing bound refuses nothing for that resource.
limit_item_states_its_bounds(item) if {
	every bound in ["min", "max"] {
		every resource in ["cpu", "memory"] {
			stated(section(item, bound), resource)
		}
	}
}

complete_pod_limit_items := {item | some item in pod_limit_items; limit_item_states_its_bounds(item)}

deny contains msg if {
	app
	count(complete_pod_limit_items) == 0
	msg := "[limitrange-present] no LimitRange of type Pod states min and max for cpu and memory: nothing bounds one pod, and nothing refuses one with no request by its own rule"
}

# The API server writes a Container item's default from its max, and its defaultRequest from that, even when neither is
# stated, so a Container item with only min or max injects what a pod left out: the pod is admitted rather than refused.
deny contains msg if {
	some [doc, item] in limit_items
	not object.get(item, "type", "absent") == "Pod"
	msg := sprintf("[limitrange-injects] %s has an item of type %v: only type Pod is left undefaulted by the API server", [label(doc), object.get(item, "type", "absent")])
}

deny contains msg if {
	some [doc, item] in limit_items
	some field in {"default", "defaultRequest"}
	stated(item, field)
	msg := sprintf("[limitrange-injects] %s states %s, which would admit a pod that states no requests", [label(doc), field])
}

# The max is the largest pod rendered, exactly. Below it that pod is refused at admission, so a rollout could never start;
# above it the bound holds nothing back. The min is a floor, and only a floor above a pod can do harm.
largest_pod(resource) := max([total | some [_, spec] in finapp_workloads; total := pod_total(spec, "limits", resource)])

deny contains msg if {
	some item in pod_limit_items
	some resource, value in section(item, "max")
	milli(quantity(value)) != milli(largest_pod(resource))
	msg := sprintf("[limitrange-admits] the LimitRange's max %s is %v and the largest pod rendered limits it to %v", [resource, value, largest_pod(resource)])
}

deny contains msg if {
	some item in pod_limit_items
	some [doc, spec] in finapp_workloads
	some resource, value in section(item, "min")
	pod_total(spec, "requests", resource) < quantity(value)
	msg := sprintf("[limitrange-admits] %s requests %s below the LimitRange's min %v", [label(doc), resource, value])
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
