# The policies the controllers' own manifests must pass (P15-TSK-032; OPERATIONS_RUNBOOK.md sections 11.32 and 11.36).
#
# Run by infra/scripts/controller-check.sh, conftest with --combine over one controller's rendered stream: Argo CD's install manifest at
# ARGOCD_VERSION (its file's sha256 the pin), its images rewritten to the pinned digests, with this repository's argocd-cm keys and its
# restricted `default` project; and the External Secrets chart at ESO_VERSION (its archive's sha256 the pin) with the committed values.
# These are the first controllers of a cluster, installed by the pipeline role and not by any Application (THREAT_MODEL.md section 6 item
# 31), so nothing else reads what they are given: a chart or a release that widened its own rights would reach the cluster unread.
#
#   data.controller   {name, namespace, images}: which controller this stream is, the namespace it is installed into, and the
#                     images it may run, each by digest, from infra/scanner-pins.sh
#
# WHAT A CONTROLLER MAY HOLD is the named list below and nothing else: a ClusterRole by name, its ClusterRoleBinding by name to
# exactly that role and exactly that controller's own ServiceAccount, and Secrets only in the namespaces listed for it. Every message
# begins with [rule-id]; controller-check.sh plants a violation of every rule id here and requires the id back, and
# ControllerRenderContractTest requires that list to be this file's.
#
# NEVER `not` OVER AN EXPRESSION WHOSE PARTS CAN BE UNDEFINED, and nothing walks into a value it has not checked (finapp.rego says why:
# a null-valued key passed a rule written to refuse it). section() answers {} unless the value is an object, list_of() [] unless an
# array, and a rule asking "is this stated?" negates a helper whose body may be undefined safely.
package main

import rego.v1

# ------------------------------------------------------------------------------------- the named lists

# THE CLUSTER ROLES EACH CONTROLLER MAY HOLD, one per line, and whether each may read Secrets in every namespace. Adding one is adding its
# line here, in a reviewed change, with the reason beside it.
#
# Argo CD (v3.6.1's install.yaml, read 2026-11-12). Each of the three reaches every Secret of the cluster, and that is the published
# install, not a choice made here:
#   argocd-application-controller   `*` on `*`, and every non-resource URL: it applies whatever an Application's project admits, so it
#                                   holds what any project could. The projects are the bound (deploy/delivery/base), and everything under
#                                   deploy/delivery/ is admin-equivalent (section 11.36);
#   argocd-server                   get, patch and delete on `*`: the API server's view of every managed object, Secrets included;
#   argocd-applicationset-controller  get, list and watch on Secrets everywhere. This platform writes no ApplicationSet.
# The External Secrets Operator holds none: its rights are a Role in finapp (deploy/operators/external-secrets/values.yaml, scopedRBAC).
cluster_roles := {
	"argocd": {
		"argocd-application-controller": {"secrets": true},
		"argocd-server": {"secrets": true},
		"argocd-applicationset-controller": {"secrets": true},
	},
	"external-secrets": {},
}

# Where each controller may read or write a Secret through a Role or a RoleBinding: Argo CD its own namespace (its configuration, its
# repository and cluster credentials), the operator finapp alone, the one namespace whose Secrets it writes (SECRET_MANAGEMENT.md 3.5).
secret_namespaces := {
	"argocd": {"argocd"},
	"external-secrets": {"finapp"},
}

# The workload that says the stream is the controller's own render, not an empty file or another chart.
anchors := {
	"argocd": {"kind": "StatefulSet", "name": "argocd-application-controller"},
	"external-secrets": {"kind": "Deployment", "name": "external-secrets"},
}

# ------------------------------------------------------------------------------------- reading the stream

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

name_of(doc) := object.get(section(doc, "metadata"), "name", "with no name")

namespace_of(doc) := object.get(section(doc, "metadata"), "namespace", controller_namespace)

label(doc) := sprintf("%s/%s", [doc.kind, name_of(doc)])

# Read with a default, so that data missing a field is a controller no list knows, refused below, and never an undefined that passes.
controller := name if {
	name := data.controller.name
	is_string(name)
} else := "(none named)"

controller_namespace := namespace if {
	namespace := data.controller.namespace
	is_string(namespace)
} else := "(none named)"

listed_cluster_roles := object.get(cluster_roles, controller, {})

pod_template_kinds := {"Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "ReplicationController"}

pod_specs contains [doc, section(section(section(doc, "spec"), "template"), "spec")] if {
	some doc in documents
	doc.kind in pod_template_kinds
}

pod_specs contains [doc, section(doc, "spec")] if {
	some doc in documents
	doc.kind == "Pod"
}

pod_specs contains [doc, section(section(section(section(section(doc, "spec"), "jobTemplate"), "spec"), "template"), "spec")] if {
	some doc in documents
	doc.kind == "CronJob"
}

containers(spec) := [container |
	some list in ["containers", "initContainers", "ephemeralContainers"]
	some container in list_of(spec, list)
]

# ------------------------------------------------------------------------------------- the stream was read

known_controller if {
	cluster_roles[controller]
}

deny contains msg if {
	not known_controller
	msg := sprintf("[controller-stream] the data names controller %v, which this policy has no list for", [controller])
}

anchored if {
	anchor := anchors[controller]
	some doc in of_kind(anchor.kind)
	name_of(doc) == anchor.name
}

deny contains msg if {
	known_controller
	not anchored
	msg := sprintf("[controller-stream] no %s %s in the rendered stream: nothing was checked", [anchors[controller].kind, anchors[controller].name])
}

# ------------------------------------------------------------------------------------- images: the pins, by digest

allowed_image(image) if {
	some pinned in data.controller.images
	image == pinned
}

deny contains msg if {
	some [doc, spec] in pod_specs
	some container in containers(spec)
	not allowed_image(object.get(container, "image", "no image"))
	msg := sprintf("[controller-images] %s runs %v, which is not one of the images pinned by digest for %s", [label(doc), object.get(container, "image", "no image"), controller])
}

# ------------------------------------------------------------------------------------- cluster roles and their bindings

deny contains msg if {
	some role in of_kind("ClusterRole")
	not listed_cluster_roles[name_of(role)]
	msg := sprintf("[controller-cluster-role] %s is not on %s's list of the cluster roles it may hold", [label(role), controller])
}

# A listed binding is the role of its own name, given to exactly the controller's ServiceAccount of that name in its namespace. Any other
# subject, a Group or a User or a second account, is the role handed to someone the list never named.
the_listed_binding(binding) if {
	listed_cluster_roles[name_of(binding)]
	ref := section(binding, "roleRef")
	ref.kind == "ClusterRole"
	ref.name == name_of(binding)
	subjects := list_of(binding, "subjects")
	count(subjects) == 1
	subject := subjects[0]
	subject.kind == "ServiceAccount"
	subject.name == name_of(binding)
	object.get(subject, "namespace", "absent") == controller_namespace
}

deny contains msg if {
	some binding in of_kind("ClusterRoleBinding")
	not the_listed_binding(binding)
	msg := sprintf("[controller-cluster-binding] %s is not on %s's list, or gives %v to %v and not its own role to its own ServiceAccount in %s", [label(binding), controller, section(binding, "roleRef"), list_of(binding, "subjects"), controller_namespace])
}

# The cluster merges a role carrying one of these labels into view, edit or admin, so every person holding those would gain it; and an
# aggregated role's rules are filled in by the cluster, so nothing here can read what it grants.
deny contains msg if {
	some role in (of_kind("ClusterRole") | of_kind("Role"))
	some key, _ in section(section(role, "metadata"), "labels")
	startswith(key, "rbac.authorization.k8s.io/aggregate-to-")
	msg := sprintf("[controller-aggregate] %s carries %s, so the cluster would merge it into a built-in role", [label(role), key])
}

deny contains msg if {
	some role in (of_kind("ClusterRole") | of_kind("Role"))
	stated(role, "aggregationRule")
	msg := sprintf("[controller-aggregate] %s is aggregated, so nothing here can read what it grants", [label(role)])
}

# ------------------------------------------------------------------------------------- Secrets

# A rule reaches Secrets when its group is the core one or `*`, and a resource is secrets, a subresource of it, or `*`.
rule_reaches_secrets(rule) if {
	some group in list_of(rule, "apiGroups")
	group in {"", "*"}
	some resource in list_of(rule, "resources")
	resource_is_secrets(resource)
}

resource_is_secrets(resource) if {
	resource in {"secrets", "*"}
}

resource_is_secrets(resource) if {
	startswith(resource, "secrets/")
}

resource_is_secrets(resource) if {
	startswith(resource, "*/")
}

reaches_secrets(role) if {
	some rule in list_of(role, "rules")
	rule_reaches_secrets(rule)
}

secrets_allowed_in(namespace) if {
	namespace in object.get(secret_namespaces, controller, set())
}

# A Role that reaches Secrets in a namespace not listed for the controller, bound or not: an unbound one is a binding away.
deny contains msg if {
	some role in of_kind("Role")
	reaches_secrets(role)
	not secrets_allowed_in(namespace_of(role))
	msg := sprintf("[controller-secrets] %s reaches Secrets in namespace %v, and %s may reach them only in %v", [label(role), namespace_of(role), controller, object.get(secret_namespaces, controller, set())])
}

# A RoleBinding that gives a ClusterRole reaching Secrets, in a namespace not listed: the ClusterRole's rules apply there.
deny contains msg if {
	some binding in of_kind("RoleBinding")
	ref := section(binding, "roleRef")
	ref.kind == "ClusterRole"
	some role in of_kind("ClusterRole")
	name_of(role) == ref.name
	reaches_secrets(role)
	not secrets_allowed_in(namespace_of(binding))
	msg := sprintf("[controller-secrets] %s gives ClusterRole %s, which reaches Secrets, in namespace %v", [label(binding), ref.name, namespace_of(binding)])
}

# A ClusterRole that reaches Secrets reaches them in every namespace once bound: refused unless the list says that one may, with why.
may_reach_every_secret(role) if {
	listed_cluster_roles[name_of(role)].secrets == true
}

deny contains msg if {
	some role in of_kind("ClusterRole")
	reaches_secrets(role)
	not may_reach_every_secret(role)
	msg := sprintf("[controller-secrets] %s reaches Secrets in every namespace it is bound in, and %s's list does not say it may", [label(role), controller])
}

# ------------------------------------------------------------------------------------- Argo CD: its configuration and its default project

argocd_cm := doc if {
	some doc in of_kind("ConfigMap")
	name_of(doc) == "argocd-cm"
}

# The keys deploy/delivery/controller/argocd-cm.yaml commits, as rendered into the install: the waves wait on these and nothing else.
config_holds(key) if {
	value := section(argocd_cm, "data")[key]
	is_string(value)
	value != ""
}

deny contains msg if {
	controller == "argocd"
	some key in [
		"application.resourceTrackingMethod",
		"resource.customizations.health.argoproj.io_Application",
		"resource.customizations.health.policy.sigstore.dev_ClusterImagePolicy",
	]
	not config_holds(key)
	msg := sprintf("[controller-config] argocd-cm does not carry %s from deploy/delivery/controller/argocd-cm.yaml", [key])
}

deny contains msg if {
	controller == "argocd"
	config_holds("application.resourceTrackingMethod")
	section(argocd_cm, "data")["application.resourceTrackingMethod"] != "annotation"
	msg := sprintf("[controller-config] argocd-cm tracks by %v, not by annotation", [section(argocd_cm, "data")["application.resourceTrackingMethod"]])
}

default_project := doc if {
	some doc in of_kind("AppProject")
	name_of(doc) == "default"
	namespace_of(doc) == controller_namespace
}

# Absent, null and [] all mean none to Argo CD; anything else is a door.
reaches_none(spec, key) if {
	not stated(spec, key)
}

reaches_none(spec, key) if {
	spec[key] == []
}

everything_namespaced_refused(spec) if {
	some entry in list_of(spec, "namespaceResourceBlacklist")
	object.get(entry, "group", "") == "*"
	object.get(entry, "kind", "") == "*"
}

deny contains "[controller-default-project] the stream holds no `default` AppProject in argocd: Argo CD would create one open to everything" if {
	controller == "argocd"
	not default_project
}

deny contains msg if {
	controller == "argocd"
	spec := section(default_project, "spec")
	some key in ["sourceRepos", "sourceNamespaces", "destinations", "clusterResourceWhitelist", "namespaceResourceWhitelist"]
	not reaches_none(spec, key)
	msg := sprintf("[controller-default-project] the default project states %s %v: it must reach nothing", [key, spec[key]])
}

deny contains "[controller-default-project] the default project does not refuse every namespaced kind (namespaceResourceBlacklist */*)" if {
	controller == "argocd"
	default_project
	not everything_namespaced_refused(section(default_project, "spec"))
}
