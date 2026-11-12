# The findings the manifest scan carries instead of failing on (P16-TSK-004; THREAT_MODEL.md section 6 item 48). Trivy's
# --ignore-policy, read by infra/scripts/manifest-check.sh alone; the image scan's carries are accepted-findings.yaml's.
#
# This is the one place a HIGH or CRITICAL finding of `trivy config` over the rendered manifests stops failing the build, so it is
# short, reviewed and expiring, in item 12's form:
#
#   - THE THREAT MODEL ACCEPTS, THIS FILE ONLY APPLIES. Each entry is listed in docs/architecture/THREAT_MODEL.md section 6 item 48
#     by its check, its object and its date, and KubernetesManifestsTest fails the build if the two disagree. Only the owner accepts
#     a risk (the register's section 1.4).
#   - ONE FINDING, NEVER AN OBJECT OR A CHECK. An entry names the check's id and the finding's WHOLE message as an anchored pattern.
#     Trivy's message names the object and every key it flagged, so a second key flagged in the same ConfigMap, or the same check on
#     another object, fails the run (manifest-check.sh's trivy-carry-widened plant shows it). A generated name's hash is the one
#     thing a pattern leaves open.
#   - EVERY ENTRY HAS A REASON AND AN EXPIRY. Through its `until` date an entry applies; from the day after it does not, and the
#     finding fails the job again; KubernetesManifestsTest goes red on the same day.
#   - REMOVED WHEN ITS CAUSE GOES. A carried finding that no longer occurs leaves a silent entry: manifest-check.sh prints every
#     finding as read before the verdict, so compare the two at each change to the carried objects.
#
# As read on 2026-11-12 by Trivy 0.76.2 over every kustomize root: two HIGH, no CRITICAL, both below, both on the kind overlay.
package trivy

import rego.v1

carried := [
	{
		"id": "KSV-0109",
		"object": "ConfigMap/finapp-kind-kafka-start",
		"message": `^ConfigMap 'finapp-kind-kafka-start' in 'finapp' namespace stores secrets in key\(s\) or value\(s\) '\{"    --add-scram \\"SCRAM-SHA-512"\}'$`,
		"until": "2027-06-30",
		"statement": "kind's test-only broker start script names $KAFKA_APP_USER and $KAFKA_APP_PASSWORD, which the pod reads from the Secret the rehearsal generates per run; the ConfigMap holds no credential, and Trivy reads the word in the script's text",
	},
	{
		"id": "KSV-0109",
		"object": "ConfigMap/finapp-app-config",
		"message": `^ConfigMap 'finapp-app-config-[a-z0-9]+' in 'finapp' namespace stores secrets in key\(s\) or value\(s\) '\{"FINAPP_PAYMENTMETHODS_TOKENISATION_TIMEOUT"\}'$`,
		"until": "2027-06-30",
		"statement": "kind's PT15S timeout for tokenising a card, a duration and no credential; finapp.rego's tokenisation_timeouts holds this one name to a duration value, the same false reading decided for the platform's own [no-secret-literal]",
	},
]

default ignore := false

ignore if {
	some entry in carried
	input.ID == entry.id
	regex.match(entry.message, input.Message)
	time.now_ns() < time.add_date(time.parse_ns("2006-01-02", entry.until), 0, 0, 1)
}
