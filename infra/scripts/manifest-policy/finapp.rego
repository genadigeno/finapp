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

# All three container lists: an init container is as able to escalate as the main one.
containers(spec) := array.concat(array.concat(object.get(spec, "containers", []), object.get(spec, "initContainers", [])), object.get(spec, "ephemeralContainers", []))

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

pod_or_container(spec, container, key) := container.securityContext[key]

pod_or_container(spec, container, key) := spec.securityContext[key] if {
	not has_key(object.get(container, "securityContext", {}), key)
}

has_key(obj, key) if {
	_ = obj[key]
}

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

deny contains msg if {
	some [doc, spec, container] in all_containers
	seccomp := pod_or_container(spec, container, "seccompProfile")
	not seccomp.type in {"RuntimeDefault", "Localhost"}
	msg := sprintf("[security-seccomp] %s container %s has seccomp %v", [label(doc), container.name, seccomp])
}

deny contains msg if {
	some [doc, spec, container] in all_containers
	not has_key(object.get(container, "securityContext", {}), "seccompProfile")
	not has_key(object.get(spec, "securityContext", {}), "seccompProfile")
	msg := sprintf("[security-seccomp] %s container %s states no seccomp profile", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	not object.get(object.get(container, "securityContext", {}), "allowPrivilegeEscalation", true) == false
	msg := sprintf("[security-escalation] %s container %s may escalate privileges", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	not object.get(object.get(container, "securityContext", {}), "readOnlyRootFilesystem", false) == true
	msg := sprintf("[security-read-only] %s container %s has a writable root filesystem", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	not "ALL" in object.get(object.get(object.get(container, "securityContext", {}), "capabilities", {}), "drop", [])
	msg := sprintf("[security-capabilities] %s container %s does not drop ALL capabilities", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	count(object.get(object.get(object.get(container, "securityContext", {}), "capabilities", {}), "add", [])) > 0
	msg := sprintf("[security-capabilities] %s container %s adds a capability", [label(doc), container.name])
}

deny contains msg if {
	some [doc, _, container] in all_containers
	object.get(object.get(container, "securityContext", {}), "privileged", false) != false
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
	some volume in object.get(spec, "volumes", [])
	has_key(volume, "hostPath")
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

# ------------------------------------------------------------------------------------- probes

deny contains msg if {
	some [doc, spec] in workloads
	doc.kind in long_running_kinds
	some container in spec.containers
	some probe in {"startupProbe", "livenessProbe", "readinessProbe"}
	not has_key(container, probe)
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
	not has_key(get, "host")
}

# The pool's own wait, read from application.yaml, in whole seconds rounded up.
pool_wait_seconds := ceil(data.spring.datasource.hikari["connection-timeout"] / 1000)

deny contains msg if {
	timeout := object.get(app_container.readinessProbe, "timeoutSeconds", 1)
	timeout <= pool_wait_seconds
	msg := sprintf("[probes-readiness-timeout] readiness timeoutSeconds %d does not outlast the pool's %d s wait: with the database gone the probe would read a timeout rather than the 503", [timeout, pool_wait_seconds])
}

deny contains msg if {
	period := object.get(app_container.readinessProbe, "periodSeconds", 10)
	timeout := object.get(app_container.readinessProbe, "timeoutSeconds", 1)
	period <= timeout
	msg := sprintf("[probes-readiness-timeout] readiness periodSeconds %d is not longer than its timeoutSeconds %d: probes would overlap", [period, timeout])
}

# Liveness is the process alone (ADR-0016): it never waits on the pool.
deny contains msg if {
	timeout := object.get(app_container.livenessProbe, "timeoutSeconds", 1)
	timeout >= pool_wait_seconds
	msg := sprintf("[probes-liveness-timeout] liveness timeoutSeconds %d is as long as the pool's wait: a process check has no reason to be that patient", [timeout])
}

# ------------------------------------------------------------------------------------- requests and limits

deny contains msg if {
	some [doc, _, container] in all_containers
	some section in {"requests", "limits"}
	some resource in {"cpu", "memory"}
	not has_key(object.get(object.get(container, "resources", {}), section, {}), resource)
	msg := sprintf("[resources-present] %s container %s states no %s %s", [label(doc), container.name, resource, section])
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
	count(object.get(doc, field, {})) > 0
	msg := sprintf("[no-secret-data] %s carries %s: a secret is never committed", [label(doc), field])
}

# A credential written inline escapes the rule above, so a literal under a credential's name is refused too.
credential_name := `(?i)(password|passwd|secret|token|credential|api[-_]?key|private[-_]?key|jaas)`

deny contains msg if {
	some [doc, _, container] in all_containers
	some env in object.get(container, "env", [])
	regex.match(credential_name, env.name)
	has_key(env, "value")
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
rolling_field(name) := object.get(object.get(object.get(app.spec, "strategy", {}), "rollingUpdate", {}), name, "absent")

deny contains msg if {
	object.get(object.get(app.spec, "strategy", {}), "type", "absent") != "RollingUpdate"
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
	not has_key(app_spec, "terminationGracePeriodSeconds")
	msg := "[rollout-grace] terminationGracePeriodSeconds is not stated: the default 30 s cannot cover the drain"
}

deny contains msg if {
	has_key(app.spec, "replicas")
	msg := "[rollout-replicas] spec.replicas is stated: each apply would reset the HorizontalPodAutoscaler"
}

startup_budget := object.get(app_container.startupProbe, "periodSeconds", 10) * object.get(app_container.startupProbe, "failureThreshold", 3)

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
	some constraint in object.get(app_spec, "topologySpreadConstraints", [])
	constraint.topologyKey == "kubernetes.io/hostname"
}

deny contains msg if {
	not zone_spread
	msg := "[topology-spread] finapp-app does not spread across zones, DoNotSchedule outside kind"
}

zone_spread if {
	some constraint in object.get(app_spec, "topologySpreadConstraints", [])
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
	not has_key(found[0], "value")
}

# ------------------------------------------------------------------------------------- the connection budget

declared(name) := to_number(config.data[name])

# application.yaml's own placeholder, ${VARIABLE:default}: the default a deployment that says nothing gets.
placeholder_default(text) := to_number(regex.find_all_string_submatch_n(`:([0-9]+)}$`, text, 1)[0][1])

writer_pool := to_number(data.spring.datasource.hikari["maximum-pool-size"])

reporting_pool := to_number(config.data.FINAPP_DB_REPORTING_POOL_SIZE)

reporting_pool := placeholder_default(data.finapp.database.reporting["pool-size"]) if {
	not has_key(config.data, "FINAPP_DB_REPORTING_POOL_SIZE")
}

# The arithmetic has one source for each number: an overlay does not get to move the pool behind the budget's back.
deny contains msg if {
	some [doc, _, container] in all_containers
	some env in object.get(container, "env", [])
	env.name in {"FINAPP_DB_INSTANCES", "FINAPP_DB_SERVER_MAX_CONNECTIONS", "FINAPP_DB_RESERVED_CONNECTIONS", "FINAPP_DB_REPORTING_POOL_SIZE", "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", "SPRING_APPLICATION_JSON"}
	msg := sprintf("[budget-one-source] %s container %s sets %s itself: the budget reads the ConfigMap", [label(doc), container.name, env.name])
}

deny contains msg if {
	some key in {"SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE", "SPRING_APPLICATION_JSON"}
	has_key(config.data, key)
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
	surge := object.get(object.get(object.get(app.spec, "strategy", {}), "rollingUpdate", {}), "maxSurge", 0)
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

scale_down := object.get(object.get(autoscaler.spec, "behavior", {}), "scaleDown", {})

deny contains msg if {
	autoscaler
	count(object.get(scale_down, "policies", [])) == 0
	msg := "[hpa-scale-down] no scale-down policy: the default takes every pod above the target at once"
}

deny contains msg if {
	some policy in object.get(scale_down, "policies", [])
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
	object.get(object.get(doc, "spec", {}), "selector", {})["app.kubernetes.io/name"] == "finapp-app"
	some port in doc.spec.ports
	not service_port_is_the_api(port)
	msg := sprintf("[service-api-only] %s exposes %v: only 8080, and never the management port", [label(doc), port])
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
	has_key(container, field)
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
