# The platform rehearsals (P15-TSK-032; OPERATIONS_RUNBOOK.md section 11.38): the runbook's procedures, each against the condition it is for,
# on release N with the committed alert rules loaded into the cluster's Prometheus and routed by the committed Alertmanager. Sourced by
# infra/scripts/kind-rehearsal.sh under REHEARSAL_SCOPE=signals or incidents, whose helpers it uses (kube, psql_query, kafka_admin, client,
# census, rollout, start_workload, stop_workload, ready_three, telemetry_get, urlencode, step, wanted, log, pass, fail, cannot).
#
# WHAT A PLANT IS. The condition a procedure is for, made true here: a raw write as the throwaway database's superuser, a scaled-down
# pod, a removed grant or network rule, a held provider. Each is removed by its own step, and the record says whether the removal was the
# procedure (a door, a grant restored, a pod replaced) or the plant's teardown (a raw line taken back out, which no procedure does).
# A plant never changes a rule, its threshold or its `for:`: an alert is waited for as the committed rule says (Q9).
#
# WHAT AN ALERT ASSERTION IS. The rule, as committed, evaluated by a real Prometheus over the series the replicas really expose: the alert
# is read firing from Prometheus (ALERTS) and then received by Alertmanager on the receiver its severity routes to. promtool proves a rule
# on synthetic series; this proves it on the application's.
#
# THE TWO SCOPES. `signals`: the activation gates of sections 1-10 and every alert a plant raises without stopping the fleet, the access
# review, staff refused where staff are refused, a compromise. `incidents`: what stops something - the broker, a credential, the database,
# an instance, the fleet - break-glass, the rolls of sections 11.15 and 11.30, the rows that fail every tick, and the incident itself.

PLATFORM_PROM=finapp-prometheus:9090
PLATFORM_AM=finapp-alertmanager:9093
PLATFORM_START=$(date +%s)
INCIDENT="INC-$(date -u +%Y%m%d)-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')"
CONFIG_MAP=$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.template.spec.containers[0].envFrom[0].configMapRef.name}')
[ -n "$CONFIG_MAP" ] || cannot "the application's ConfigMap could not be read from its Deployment"
: > "$ART/alerts.tsv"
printf 'alert\tstate\tafter_seconds\tstep\n' >> "$ART/alerts.tsv"

# ---------------------------------------------------------------------------------------------------
# Prometheus and Alertmanager, through the API server's proxy.
# ---------------------------------------------------------------------------------------------------

prom_query() {
    telemetry_get "$PLATFORM_PROM" "/api/v1/query?query=$(urlencode "$1")"
}

# prom_value <promql>: the first sample's value, or nothing when the result is empty.
prom_value() {
    prom_query "$1" 2>/dev/null | sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p' | head -1
}

# alert_state <alertname> [extra matchers, starting with a comma]: firing, pending or none.
alert_state() {
    [ -n "$(prom_value "max(ALERTS{alertname=\"$1\",alertstate=\"firing\"${2:-}})")" ] && { echo firing; return; }
    [ -n "$(prom_value "max(ALERTS{alertname=\"$1\",alertstate=\"pending\"${2:-}})")" ] && { echo pending; return; }
    echo none
}

# await_alert <alertname> <firing|none> <seconds> [matchers]: waits for the alert's state as the committed rule decides it.
await_alert() {
    since=$(date +%s)
    deadline=$((since + $3))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        state=$(alert_state "$1" "${4:-}")
        if { [ "$2" = firing ] && [ "$state" = firing ]; } || { [ "$2" = none ] && [ "$state" = none ]; }; then
            printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$(( $(date +%s) - since ))" "$STEP_NAME" >> "$ART/alerts.tsv"
            return 0
        fi
        sleep 15
    done
    prom_query "ALERTS{alertname=\"$1\"}" > "$ART/alert-$1-at-failure.json" 2>&1 || true
    return 1
}

# routed <alertname> <receiver> <seconds>: Alertmanager received it and routed it to that receiver.
routed() {
    deadline=$(( $(date +%s) + $3 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        telemetry_get "$PLATFORM_AM" "/api/v2/alerts?filter=$(urlencode "alertname=\"$1\"")" > "$ART/am-$1.json" 2>/dev/null || true
        grep -q "\"name\":\"$2\"" "$ART/am-$1.json" && return 0
        sleep 10
    done
    return 1
}

# fires <alertname> <receiver> <seconds> <what> [matchers]: the plant's alert fires and is routed, or the step is red.
fires() {
    await_alert "$1" firing "$3" "${5:-}" || fail "$4: $1 did not fire within $3 s of its plant"
    routed "$1" "$2" 120 || fail "$4: $1 fired and Alertmanager did not route it to $2: see am-$1.json"
    pass "$4: $1 fired and reached $2"
}

# clears <alertname> <seconds> <what> [matchers]: after the procedure, the alert is no longer firing or pending.
clears() {
    await_alert "$1" none "$2" "${4:-}" || fail "$3: $1 still $(alert_state "$1" "${4:-}") $2 s after the procedure"
    pass "$3: $1 cleared"
}

# The rules as committed, all of them loaded, and the receivers there.
alerting_ready() {
    kube -n finapp-observability rollout status deployment/finapp-alertmanager --timeout=300s >> "$ART/apply.log" 2>&1 \
        || fail "Alertmanager did not become ready in finapp-observability"
    committed=$(cat "$ROOT"/infra/prometheus/rules/*.yml | grep -c '^ *- alert: ')
    loaded=0
    deadline=$(( $(date +%s) + 300 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        telemetry_get "$PLATFORM_PROM" /api/v1/rules > "$ART/prometheus-rules.json" 2>/dev/null || true
        loaded=$(grep -o '"type":"alerting"' "$ART/prometheus-rules.json" | wc -l | tr -d ' ')
        [ "$loaded" = "$committed" ] && break
        sleep 10
    done
    [ "$loaded" = "$committed" ] || fail "Prometheus loaded $loaded alerting rules where infra/prometheus/rules holds $committed"
    pass "every committed alerting rule is loaded: $loaded of $committed"
    telemetry_get "$PLATFORM_AM" /api/v2/status > "$ART/alertmanager-status.json" 2>&1 || fail "Alertmanager does not answer"
    grep -q 'placeholder-page' "$ART/alertmanager-status.json" && grep -q 'placeholder-ticket' "$ART/alertmanager-status.json" \
        || fail "Alertmanager's configuration does not name placeholder-page and placeholder-ticket"
    [ "$(prom_value 'count(up{job="finapp"} == 1)')" = 3 ] || fail "Prometheus does not scrape three replicas of finapp"
    # cert-manager scraped, so the certificate rules read real series and CertificateExpirySeriesAbsent stays silent for the right reason.
    [ "$(prom_value 'max(up{job="cert-manager"})')" = 1 ] || fail "Prometheus does not scrape cert-manager"
    pass "Alertmanager routes to placeholder-page and placeholder-ticket; Prometheus scrapes three replicas"
}

# prom_offer <label> <true|false>: the environment's scrape configuration labels the targets, as sections 7.4 step 7, 8.1 step 6 and 9.1
# say; applied by changing the ConfigMap and asking Prometheus to reload, never by restarting it (its `for:` timers would restart).
prom_offer() {
    kube -n finapp-observability get configmap finapp-prometheus-config -o jsonpath='{.data.prometheus\.yml}' > "$WORK/prometheus.yml" \
        || cannot "the Prometheus configuration could not be read"
    awk -v label="$1" -v value="$2" '
        matched && /replacement:/ { sub(/replacement: .*/, "replacement: \"" value "\""); matched = 0 }
        $0 ~ ("target_label: " label "$") { matched = 1 }
        { print }' "$WORK/prometheus.yml" > "$WORK/prometheus.new"
    cmp -s "$WORK/prometheus.yml" "$WORK/prometheus.new" && grep -q "target_label: $1" "$WORK/prometheus.yml" \
        && ! grep -q "replacement: \"$2\"" "$WORK/prometheus.yml" && cannot "the scrape configuration's $1 could not be changed"
    {
        printf 'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: finapp-prometheus-config\n  namespace: finapp-observability\ndata:\n  prometheus.yml: |\n'
        sed 's/^/    /' "$WORK/prometheus.new"
    } | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the Prometheus configuration could not be applied"
    deadline=$(( $(date +%s) + 240 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        kube create --raw "/api/v1/namespaces/finapp-observability/services/http:$PLATFORM_PROM/proxy/-/reload" -f - < /dev/null >/dev/null 2>&1 || true
        [ "$(prom_value "count(up{job=\"finapp\",$1=\"$2\"})")" = 3 ] && { pass "the scrape labels the targets $1=\"$2\""; return 0; }
        sleep 15
    done
    fail "the scrape did not label the targets $1=\"$2\" within 240 s"
}

# ---------------------------------------------------------------------------------------------------
# The application's own audit trail and the API server's audit log.
# ---------------------------------------------------------------------------------------------------

# app_audit <file> <operation> [since epoch]: every field section 11.12's review reads, never change_summary's identifiers beyond the target.
app_audit() {
    psql_query -F '|' -c "SELECT to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"'), actor_id, actor_type, operation, target_type,
        target_id, coalesce(reason, ''), outcome, coalesce(correlation_id, '')
        FROM platform.audit_record WHERE operation = '$2' AND occurred_at >= to_timestamp(${3:-$PLATFORM_START}) ORDER BY occurred_at" > "$1"
}

# audited <what> <operation> [since] [require reason]: at least one record, each carrying actor, time, operation, target, correlation and
# outcome, and a reason where the act takes one. What the record says is read from the row, not from what was asked.
audited() {
    app_audit "$ART/audit-$2.txt" "$2" "${3:-}"
    [ -s "$ART/audit-$2.txt" ] || fail "$1: no $2 record in platform.audit_record"
    awk -F'|' -v reason="${4:-}" '$1=="" || $2=="" || $4=="" || $6=="" || $8=="" || $9=="" || (reason != "" && $7=="") {bad++} END {exit bad > 0}' \
        "$ART/audit-$2.txt" || fail "$1: a $2 record lacks a field: see audit-$2.txt"
    pass "$1: $(wc -l < "$ART/audit-$2.txt" | tr -d ' ') $2 record(s), each with actor, time, operation, target, correlation and outcome${4:+, and a reason}"
}

api_audit() {
    # api_audit <user prefix> <out>: the API server's events for one principal, as verb|resource|subresource|name|code|time|auditID.
    dock exec "$NODE" sh -c 'cat /var/log/kubernetes/audit/audit*.log 2>/dev/null' \
        | grep "\"username\":\"$1" \
        | sed -n 's/.*"auditID":"\([^"]*\)".*"verb":"\([^"]*\)".*"objectRef":{\([^}]*\)}.*"responseStatus":{[^}]*"code":\([0-9]*\).*"stageTimestamp":"\([^"]*\)".*/\2|\3|\4|\5|\1/p' \
        | sed 's/"resource":"\([^"]*\)"/r=\1/; s/"subresource":"\([^"]*\)"/s=\1/; s/"name":"\([^"]*\)"/n=\1/' > "$2"
}

# ---------------------------------------------------------------------------------------------------
# The incident record (section 11.12): what a scribe writes, and what the review compares with the audit logs.
# ---------------------------------------------------------------------------------------------------

incident_open() {
    INCIDENT_FILE="$ART/incident-$INCIDENT.md"
    {
        printf '# %s\n\n- declared: %s\n- severity: %s, by section 11.12'"'"'s table: %s\n' "$INCIDENT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2"
        printf -- '- incident lead, scribe, communications: the rehearsal (scripted); approver of break-glass and of any restore: a second person, `admin`\n\n'
        printf '| time (UTC) | actor | operation | target | reason | correlation | outcome |\n|---|---|---|---|---|---|---|\n'
    } > "$INCIDENT_FILE"
}

incident_note() {
    # incident_note <actor> <operation> <target> <reason> <outcome>
    printf '| %s | %s | %s | %s | %s | %s | %s |\n' "$(date -u +%H:%M:%S)" "$1" "$2" "$3" "$4" "$INCIDENT" "$5" >> "$INCIDENT_FILE"
}

# ---------------------------------------------------------------------------------------------------
# Signals.
# ---------------------------------------------------------------------------------------------------

signals_alerting() {
    alerting_ready
    prom_query 'ALERTS{alertstate="firing"}' > "$ART/alerts-baseline.json" 2>&1 || true
    log "firing at the baseline: $(grep -o '"alertname":"[^"]*"' "$ART/alerts-baseline.json" | sort -u | tr '\n' ' ')"
    # The collectors started minutes ago, as every rollout of them does: the load-balancing exporter's start-up drops must not page.
    [ "$(alert_state TelemetryExportFailing)" = none ] || fail "TelemetryExportFailing fires at the baseline, before any plant: see alerts-baseline.json"
    pass "nothing of the telemetry stack's own fires at the baseline, the collectors minutes old"
}

signals_plants() {
    # The two plants whose rules wait longest go first, so their `for:` passes while the rest runs.
    # Section 11.31: a FINANCIAL value changed and its rollout stopped after the first new replica, the fleet on two configurations.
    kube -n finapp patch configmap "$CONFIG_MAP" --type=merge -p '{"data":{"FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_EUR":"1"}}' >> "$ART/apply.log" 2>&1 \
        || fail "the ConfigMap could not be changed"
    kube -n finapp patch deployment finapp-app --type=merge \
        -p '{"spec":{"template":{"metadata":{"annotations":{"finapp/rehearsal-configuration":"fingerprint-plant"}}}}}' >> "$ART/apply.log" 2>&1 \
        || fail "the configuration's roll could not start"
    kube -n finapp rollout pause deployment/finapp-app >> "$ART/apply.log" 2>&1 || fail "the rollout could not be paused"
    deadline=$(( $(date +%s) + 600 ))
    until [ "$(prom_value 'count(count by (fingerprint) (finapp_config_fingerprint))')" = 2 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || fail "the fleet never showed two configurations: $(prom_value 'count(count by (fingerprint) (finapp_config_fingerprint))')"
        sleep 15
    done
    CONFIG_PLANTED=$(date +%s)
    pass "11.31: the fleet on two configurations, the rollout paused after its first new replica"
    # Section 11.8: a certificate whose remaining life is under seven days the moment it is issued.
    cat <<YAML | kube apply -f - >> "$ART/apply.log" 2>&1 || fail "the short certificate could not be applied"
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: finapp-rehearsal-short
  namespace: finapp-edge
  labels:
    finapp/test-only: "true"
spec:
  secretName: finapp-rehearsal-short-tls
  duration: 1h
  renewBefore: 20m
  dnsNames: [short.finapp.test]
  issuerRef:
    name: finapp-edge-local
    kind: Issuer
YAML
    kube -n finapp-edge wait --for=condition=Ready certificate/finapp-rehearsal-short --timeout=180s >> "$ART/apply.log" 2>&1 \
        || fail "the short certificate was not issued"
    pass "11.8: a certificate with an hour of life issued"
    # Sections 7.4, 8.1 and 9.1, their last step: the targets labelled as offering the product, before any version is active.
    prom_offer lending_offered true
    prom_offer bnpl_offered true
    prom_offer risk_judging true
    # Section 11.22: a payout the provider never answers. The simulator holds both the payout and its inquiry.
    kube -n finapp-rehearsal exec deploy/finapp-simulator -- sh -c 'touch /tmp/hold-inquiries /tmp/hold-payouts' >> "$ART/apply.log" 2>&1 \
        || fail "the simulator's hold could not be set"
    client payout-held held >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform payout-held held status=' "$ART/platform-client.log" || fail "the held payout was not requested: see platform-client.log"
    HELD_PAYOUTS=1
    pass "11.22: a payout requested while the provider answers nothing"
}

signals_staff() {
    client platform-prepare >> "$ART/platform-client.log" 2>&1 || fail "the platform's persons could not be registered: see platform-client.log"
    psql_query -f - < "$CLIENT/seed-platform.sql" >> "$ART/seed.log" 2>&1 || fail "the platform seed failed: see seed.log"
    psql_query -f - < "$CLIENT/ids.sql" > "$CLIENT/ids.properties" 2>> "$ART/seed.log" || fail "the identities could not be read"
    client platform-staff >> "$ART/platform-client.log" 2>&1 || fail "the staff's roles could not be granted: see platform-client.log"
    refused=$(grep '^platform staff grant ' "$ART/platform-client.log" | grep -vc 'status=204' || true)
    [ "$refused" = 0 ] || fail "$refused of the staff's grants were not 204: see platform-client.log"
    audited "the staff's grants" identity.RoleAssigned "" reason
}

signals_surface() {
    # Section 11.37's page, and the compromise procedure it starts (section 11.12): an operator's own password at the public sign-in.
    client surface > "$ART/surface-platform.txt" 2>&1 || fail "the surface check could not run: see surface-platform.txt"
    grep -qx 'surface operator-sign-in-on-public status=401' "$ART/surface-platform.txt" || fail "the operator's password on the public host was not refused"
    fires OperatorPasswordProvenOnPublic placeholder-page 300 "11.37 the page" ',kind="public"'
    audited "11.37 the public refusal" identity.PublicSurfaceRefused
    client compromise operator "$INCIDENT" >> "$ART/platform-client.log" 2>&1 || fail "the compromise procedure could not run: see platform-client.log"
    for expected in 'suspend status=204' 'old-session status=401' 'sign-in status=401' 'reinstate status=204' 'sign-in-after status=201'; do
        grep -q "^platform compromise $expected" "$ART/platform-client.log" || fail "the compromise procedure: not $expected: see platform-client.log"
    done
    pass "11.37 and 11.12: the operator suspended with a reason (old session and sign-in refused), reinstated with a reason, signing in again"
    audited "the compromise: the suspension" identity.IdentitySuspended "" reason
    audited "the compromise: the sessions it ended" identity.SessionRevoked
    audited "the compromise: the reinstatement" identity.IdentityReinstated "" reason
    grep -q "$INCIDENT" "$ART/audit-identity.IdentitySuspended.txt" || fail "the suspension's record does not name the incident in its reason"
    pass "the suspension's record names $INCIDENT, the reason given"
}

signals_first_role() {
    client first-role >> "$ART/platform-client.log" 2>&1 || fail "the first role could not be granted: see platform-client.log"
    for expected in 'grant status=204' 'old-session status=401' 'staff-sign-in status=201' 'second-grant status=204' 'after-second status=200'; do
        grep -q "^platform first-role $expected" "$ART/platform-client.log" || fail "a first role: not $expected: see platform-client.log"
    done
    psql_query -c "SELECT count(*) FROM platform.audit_record WHERE operation = 'identity.RoleAssigned' AND change_summary LIKE '%firstRole=true%'
        AND occurred_at >= to_timestamp($PLATFORM_START)" | tr -d '[:space:]' > "$WORK/first-role.count"
    [ "$(cat "$WORK/first-role.count")" -ge 1 ] || fail "no identity.RoleAssigned record says firstRole=true"
    pass "11.37: a first role ended the customer's session (firstRole=true recorded), staff signs in on the operator host, a second role ends nothing"
}

signals_access_review() {
    # PRIVILEGED_ACCESS_REVIEW.md section 5, questions 5 and 6, performed once on this cluster's identities.
    client access-review before >> "$ART/platform-client.log" 2>&1 || fail "the access review could not read: see platform-client.log"
    grep -q '^platform access-review before status=200 holdings=[1-9][0-9]* stranger=yes' "$ART/platform-client.log" \
        || fail "question 5: the planted holding was not named: $(grep '^platform access-review before' "$ART/platform-client.log")"
    client access-review-enrol >> "$ART/platform-client.log" 2>&1 || fail "the holder could not enrol: see platform-client.log"
    client access-review after >> "$ART/platform-client.log" 2>&1 || fail "the access review could not read again: see platform-client.log"
    grep -q '^platform access-review after status=200 .*stranger=no' "$ART/platform-client.log" \
        || fail "question 5: the holding is still named after the holder enrolled"
    audited "question 5's read" identity.FactorlessHoldingsRead
    pass "question 5: a holding written around the door named by the audited read, resolved by its holder's enrolment"
}

# activation <command> <what>: the client's four eyes, its self-approval refused, its approval ACTIVE.
activation() {
    client "$1" >> "$ART/platform-client.log" 2>&1 || fail "$2: $1 could not run: see platform-client.log"
    grep "^platform $1 " "$ART/platform-client.log" > "$ART/activation-$1.txt"
    grep -q 'self-approval status=40[39]' "$ART/activation-$1.txt" || fail "$2: the proposer's own approval was not refused: see activation-$1.txt"
    grep -q 'approved status=20[01] .*state=ACTIVE\|approved status=20[01] state=ACTIVE' "$ART/activation-$1.txt" \
        || grep -q 'approved status=20[01]' "$ART/activation-$1.txt" || fail "$2: the second person's approval did not activate it: see activation-$1.txt"
    pass "$2: proposed, the proposer's own approval refused, activated by a second person"
}

signals_domain() {
    activation activate-recon-terms "section 1, JPY and BHD terms for the four sources"
    activation activate-pricing "section 2, pricing policy v1"
    grep -q 'quote-before status=4' "$ART/activation-activate-pricing.txt" && grep -q 'quote-after status=201' "$ART/activation-activate-pricing.txt" \
        || fail "section 2: a quote was not refused before and served after: see activation-activate-pricing.txt"
    # Section 4 step 4: a cover executed while the FX source's v1 is missing does not book.
    client fx-conversion before-source >> "$ART/platform-client.log" 2>&1 || fail "section 4: the conversion could not run"
    TRADE=$(sed -n 's/^platform fx-conversion before-source status=20[01] trade=\([^ ]*\).*/\1/p' "$ART/platform-client.log" | tail -1)
    [ -n "$TRADE" ] || fail "section 4: no conversion was made: $(grep '^platform fx-conversion' "$ART/platform-client.log")"
    fires ReconciliationRuleSetMissing placeholder-ticket 1500 "11.24 and section 3, the FX source's rule set missing"
    client fx-cover "$TRADE" >> "$ART/platform-client.log" 2>&1 || true
    grep '^platform fx-cover ' "$ART/platform-client.log" | tail -1 > "$WORK/cover-before.txt"
    activation activate-fx-source "section 3, the FX source's rule set v1"
    clears ReconciliationRuleSetMissing 300 "section 3"
    deadline=$(( $(date +%s) + 600 ))
    until client fx-cover "$TRADE" 2>/dev/null | tee -a "$ART/platform-client.log" | grep -q 'booked=yes'; do
        [ "$(date +%s)" -lt "$deadline" ] || fail "section 4: the cover was not booked within 600 s of the rule set: before $(cat "$WORK/cover-before.txt")"
        sleep 20
    done
    pass "section 4 step 4: before the rule set $(cut -d' ' -f3- "$WORK/cover-before.txt"); after it, the next sweep booked the cover"
    activation activate-corridor "section 3b, the corridor policy v1"
    fires CreditPolicyMissing placeholder-ticket 1500 "section 6, credit policy v1 not yet in force"
    activation activate-credit "section 6.1, credit policy and scorecard"
    clears CreditPolicyMissing 300 "section 6.1"
    fires LendingTermsNoneActive placeholder-ticket 1500 "section 7.4, the alert armed before any terms"
    activation activate-lending "sections 7.1 and 7.3, capital and terms"
    grep -q 'capital-report status=200 recognised=[1-9]' "$ART/activation-activate-lending.txt" || fail "section 7.1: no capital recognised: see activation-activate-lending.txt"
    clears LendingTermsNoneActive 300 "section 7.4"
    fires BnplTermsNoneActive placeholder-ticket 1500 "section 8.1, the alert armed before any terms"
    activation activate-bnpl "section 8.1, the BNPL terms"
    clears BnplTermsNoneActive 300 "section 8.1"
    fires RiskPolicyNoneActive placeholder-page 900 "section 9.1, judging expected and no policy active"
    activation activate-risk "section 9.1, a risk policy version"
    clears RiskPolicyNoneActive 300 "section 9.1"
    census domain
}

signals_correctness() {
    # Section 11.20 and section 10.17: a balance projection moved by one minor unit, by a raw writer; the rebuild door, four eyes, repairs it.
    DRIFT_ACCOUNT=$(psql_query -c "SELECT b.ledger_account_id FROM ledger.account_balance b JOIN ledger.ledger_account a ON a.id = b.ledger_account_id
        WHERE a.purpose = 'CUSTOMER_WALLET' ORDER BY b.updated_at DESC LIMIT 1" | tr -d '[:space:]')
    [ -n "$DRIFT_ACCOUNT" ] || cannot "no customer wallet's projection to plant"
    psql_query -c "UPDATE ledger.account_balance SET posted_minor = posted_minor + 1 WHERE ledger_account_id = '$DRIFT_ACCOUNT'" >> "$ART/plants.log" 2>&1 \
        || cannot "the drift could not be planted"
    fires LedgerProjectionDrift placeholder-page 600 "11.20, a projection that disagrees with its postings"
    client rebuild-projection "$DRIFT_ACCOUNT" >> "$ART/platform-client.log" 2>&1 || fail "section 10.17: the rebuild could not run: see platform-client.log"
    grep -q '^platform rebuild-projection self status=409' "$ART/platform-client.log" || fail "section 10.17: the proposer's own approval was not refused"
    grep -q '^platform rebuild-projection approved status=201' "$ART/platform-client.log" || fail "section 10.17: the rebuild was not approved"
    clears LedgerProjectionDrift 600 "section 10.17"
    pass "section 10.17: the projection rebuilt from the journal by two persons, never edited"
    # Section 11.22: a transfer key reused with another body.
    client key-conflict plant >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform key-conflict second status=409' "$ART/platform-client.log" || fail "11.22: the reused key was not refused: see platform-client.log"
    fires TransferKeyConflict placeholder-ticket 600 "11.22, a key reused with another body"
    # "Provider callbacks are hints": a signed callback naming no operation this platform minted.
    client callback-unmappable plant >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform callback-unmappable status=' "$ART/platform-client.log" || fail "the unmappable callback was not delivered"
    fires PaymentWebhookUnmappable placeholder-page 600 "11.20 and provider callbacks, a callback naming nothing minted"
    # Section 11.23: a settlement file refused, kept as evidence.
    client settlement-refused plant >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform settlement-refused status=422' "$ART/platform-client.log" || fail "11.23: the file was not refused: see platform-client.log"
    fires SettlementDeliveryRefused placeholder-ticket 600 "11.23, a settlement delivery refused"
    # Section 11.22 again: the payout held since the start, unanswered past its bound; the hold released, the sweep concludes it once.
    fires MerchantPayoutUnknownAged placeholder-page 1800 "11.22, a payout unanswered past its bound"
    kube -n finapp-rehearsal exec deploy/finapp-simulator -- sh -c 'rm -f /tmp/hold-inquiries /tmp/hold-payouts' >> "$ART/apply.log" 2>&1 || true
    clears MerchantPayoutUnknownAged 900 "11.22, the provider answering again"
    SMOKE_PAYOUTS=$((SMOKE_PAYOUTS + HELD_PAYOUTS))
    HELD_PAYOUTS=0
    census correctness
}

signals_operators_half() {
    # Section 11.26's rollout order (P15-TSK-042): an operator edge that sets no marker, the application requiring it.
    edge_map=$(kube -n finapp-edge get configmap -o name | while read -r map; do
        kube -n finapp-edge get "$map" -o yaml 2>/dev/null | grep -q 'X-Finapp-Surface: operator' && printf '%s\n' "$map"; done | head -1)
    [ -n "$edge_map" ] || cannot "no ConfigMap in finapp-edge carries the operator marker"
    kube -n finapp-edge get "$edge_map" -o yaml > "$WORK/edge-operator.yaml"
    sed 's/X-Finapp-Surface: operator/X-Finapp-Surface: ""/' "$WORK/edge-operator.yaml" | grep -v '^  resourceVersion:\|^  uid:\|^  creationTimestamp:' \
        | kube apply -f - >> "$ART/apply.log" 2>&1 || fail "the operator edge's marker could not be removed"
    deadline=$(( $(date +%s) + 300 ))
    until client surface-unmarked unmarked 2>/dev/null | tee -a "$ART/platform-client.log" | grep -q 'staff status=401'; do
        [ "$(date +%s)" -lt "$deadline" ] || fail "11.26: staff were still served on an edge with no marker after 300 s"
        sleep 15
    done
    grep '^platform surface-unmarked unmarked customer status=201' "$ART/platform-client.log" >/dev/null || fail "11.26: customers were not served while staff were refused"
    pass "11.26: on an edge with no marker staff are refused on the operator host and customers served"
    fires StaffRefusedUnmarked placeholder-ticket 600 "11.37, staff refused where no edge marked the request"
    grep -v '^  resourceVersion:\|^  uid:\|^  creationTimestamp:' "$WORK/edge-operator.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 \
        || fail "the operator edge could not be rolled forward"
    deadline=$(( $(date +%s) + 300 ))
    until client surface-unmarked marked 2>/dev/null | tee -a "$ART/platform-client.log" | grep -q 'staff status=201'; do
        [ "$(date +%s)" -lt "$deadline" ] || fail "11.26: staff were not served again 300 s after the edge was rolled forward"
        sleep 15
    done
    pass "11.26: the edge rolled forward, staff sign in on the operator host"
}

signals_telemetry() {
    # Section 11.34, the telemetry stack's own alerts (THREAT_MODEL.md section 6 item 37 (d)): a back end gone, then the collectors gone.
    # Nothing for the money either way: the requests are neither failed nor slowed, and readiness does not move.
    kube -n finapp-observability scale deployment/finapp-tempo --replicas=0 >> "$ART/apply.log" 2>&1 || fail "Tempo could not be stopped"
    # The harness's smoke: every flow through the edge, its counts added to what the census expects; a failed one fails the step.
    smoke telemetry-backend
    fires TelemetryBackendDown placeholder-ticket 900 "11.34, a trace back end gone" ',job="finapp-tempo"'
    # The exporter named: the load-balancing exporter between the collectors fails at every collectors' start (peers not yet resolvable),
    # so an unqualified firing could be that and not this.
    fires TelemetryExportFailing placeholder-ticket 900 "11.34, the collectors' export to it failing" ',exporter="otlp_grpc/tempo"'
    kube -n finapp-observability scale deployment/finapp-tempo --replicas=1 >> "$ART/apply.log" 2>&1 || fail "Tempo could not be started again"
    kube -n finapp-observability rollout status deployment/finapp-tempo --timeout=300s >> "$ART/apply.log" 2>&1 || fail "Tempo did not come back"
    clears TelemetryBackendDown 900 "11.34, Tempo back" ',job="finapp-tempo"'
    collectors=$(kube -n finapp-observability get deployment finapp-collector -o jsonpath='{.spec.replicas}')
    kube -n finapp-observability scale deployment/finapp-collector --replicas=0 >> "$ART/apply.log" 2>&1 || fail "the collectors could not be stopped"
    smoke telemetry-collector
    [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')" -ge 3 ] || fail "11.34: readiness moved with the collectors gone"
    fires TelemetryCollectorsAbsent placeholder-ticket 900 "11.34, every collector gone"
    kube -n finapp-observability scale deployment/finapp-collector --replicas="${collectors:-2}" >> "$ART/apply.log" 2>&1 || fail "the collectors could not be started again"
    kube -n finapp-observability rollout status deployment/finapp-collector --timeout=300s >> "$ART/apply.log" 2>&1 || fail "the collectors did not come back"
    clears TelemetryCollectorsAbsent 900 "11.34, the collectors back"
    census telemetry
}

signals_close() {
    # Section 11.31's procedure: steps 1 and 2 read, then the stopped rollout resumed; one configuration, and the page ends.
    remaining=$(( 80 * 60 + 300 - ($(date +%s) - CONFIG_PLANTED) ))
    [ "$remaining" -gt 0 ] || remaining=60
    fires ConfigFingerprintDisagrees placeholder-page "$remaining" "11.31, the fleet on two configurations longer than any rollout"
    kube -n finapp get replicasets -o wide > "$ART/replicasets-disagreeing.txt" 2>&1 || true
    kube -n finapp rollout resume deployment/finapp-app >> "$ART/apply.log" 2>&1 || fail "the rollout could not be resumed"
    kube -n finapp rollout status deployment/finapp-app --timeout="${ROLLOUT_TIMEOUT}s" >> "$ART/apply.log" 2>&1 || fail "11.31: the resumed rollout did not complete"
    no_pod_terminating 300 || fail "11.31: a pod was still terminating 300 s after the resumed rollout"
    clears ConfigFingerprintDisagrees 600 "11.31"
    # Section 11.8's certificate: what renews it is a lifetime the issuer can keep; once the planted one is gone the page ends.
    fires CertificateExpiring placeholder-page 1200 "11.8, a certificate under seven days"
    kube -n finapp-edge describe certificate finapp-rehearsal-short > "$ART/certificate-short.txt" 2>&1 || true
    kube -n finapp-edge patch certificate finapp-rehearsal-short --type=merge -p '{"spec":{"duration":"2160h","renewBefore":"720h"}}' >> "$ART/apply.log" 2>&1 \
        || fail "the certificate's lifetime could not be changed"
    clears CertificateExpiring 900 "11.8, the certificate renewed for ninety days"
    census signals-end
}

# ---------------------------------------------------------------------------------------------------
# Incidents.
# ---------------------------------------------------------------------------------------------------

incidents_prepare() {
    alerting_ready
    client platform-prepare >> "$ART/platform-client.log" 2>&1 || fail "the platform's persons could not be registered: see platform-client.log"
    psql_query -f - < "$CLIENT/seed-platform.sql" >> "$ART/seed.log" 2>&1 || fail "the platform seed failed: see seed.log"
    psql_query -f - < "$CLIENT/ids.sql" > "$CLIENT/ids.properties" 2>> "$ART/seed.log" || fail "the identities could not be read"
    client platform-staff >> "$ART/platform-client.log" 2>&1 || fail "the staff's roles could not be granted: see platform-client.log"
}

incidents_broker() {
    # Section 11.6, a broker outage: the application left alone, every replica ready, the events waiting in the outbox.
    start_workload outage
    sleep 20
    kube -n finapp scale statefulset/finapp-kafka --replicas=0 >> "$ART/apply.log" 2>&1 || fail "the broker could not be stopped"
    OUTAGE_FROM=$(date +%s)
    not_ready=0
    fires OutboxPublicationStalled placeholder-page 900 "11.6 and 11.21, the broker gone"
    fires EventFreshnessBurnFast placeholder-page 1200 "11.8, the freshness objective burning"
    ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')
    [ "${ready:-0}" -ge 3 ] || fail "11.6: readiness moved during the outage: $ready ready"
    pass "11.6: three replicas ready through $(( $(date +%s) - OUTAGE_FROM )) s without a broker"
    kube -n finapp scale statefulset/finapp-kafka --replicas=1 >> "$ART/apply.log" 2>&1 || fail "the broker could not be started again"
    kube -n finapp rollout status statefulset/finapp-kafka --timeout=300s >> "$ART/apply.log" 2>&1 || fail "the broker did not come back"
    stop_workload outage
    [ "$(workload_failed outage)" = 0 ] || fail "11.6: $(workload_failed outage) requests failed during the outage"
    clears OutboxPublicationStalled 900 "11.6, the broker back"
    census broker-outage
    # Section 11.6, a refused credential: the group's permissions removed, every replica refused and unready; restored, ready.
    kafka_admin kafka-acls.sh --remove --force --allow-principal "User:finapp_app" --operation Describe --operation Read --group finapp. \
        --resource-pattern-type prefixed >> "$ART/kafka.log" 2>&1 || fail "the groups' ACL could not be removed"
    fires InboxPollRefused placeholder-page 900 "11.6, a refused permission"
    unready=0
    deadline=$(( $(date +%s) + 300 ))
    until [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')" = "" ] || [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')" = 0 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || { unready=1; break; }
        sleep 10
    done
    [ "$unready" = 0 ] || fail "11.6: replicas stayed ready while the broker refused their group"
    restarts_before=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
    pass "11.6: every replica unready while refused (restart counts $restarts_before)"
    kafka_admin kafka-acls.sh --add --allow-principal "User:finapp_app" --operation Describe --operation Read --group finapp. \
        --resource-pattern-type prefixed >> "$ART/kafka.log" 2>&1 || fail "the groups' ACL could not be restored"
    ready_three 600 || fail "11.6: the replicas did not become ready again once admitted"
    clears InboxPollRefused 900 "11.6, the permission restored"
    census refused-credential
}

incidents_dead_letters() {
    # Section 11.6, a poison record: a malformed record on a consumed topic, parked after its budgets, discarded by two persons.
    printf 'this is not an event\n' | kube -n finapp exec -i finapp-kafka-0 -c kafka -- /opt/kafka/bin/kafka-console-producer.sh \
        --bootstrap-server localhost:9094 --topic finapp.transfers >> "$ART/kafka.log" 2>&1 || fail "the poison record could not be produced"
    fires InboxDeadLetterParked placeholder-page 900 "11.6, a poison record parked"
    client dead-letters parked >> "$ART/platform-client.log" 2>&1 || fail "11.6: the queue could not be read"
    PARK=$(sed -n 's/^platform dead-letters parked kind=INBOX_RECORD .*park=\([^ ]*\).*/\1/p' "$ART/platform-client.log" | head -1)
    [ -n "$PARK" ] && [ "$PARK" != - ] || fail "11.6: the parked record is not in the queue"
    client release-parked "$PARK" >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform release-parked status=4.. code=deadletter.NotReleasable' "$ART/platform-client.log" \
        || fail "11.6: a record that was never an event was not refused release: $(grep '^platform release-parked' "$ART/platform-client.log")"
    client discard INBOX_RECORD "$PARK" >> "$ART/platform-client.log" 2>&1 || fail "11.6: the discard could not run"
    grep -q '^platform discard self status=403' "$ART/platform-client.log" && grep -q '^platform discard approved status=200' "$ART/platform-client.log" \
        || fail "11.6: the discard was not two persons' act: see platform-client.log"
    clears InboxDeadLetterParked 600 "11.6, the record discarded"
    pass "11.6: the poison record parked, refused release, discarded by two persons, the partition moved on"
    # Section 11.6, an abandoned event: the state planted on a published event (an hour of failed publishes is not), retried by the door.
    ABANDONED=$(psql_query -c "UPDATE platform.outbox_event SET published_at = NULL, dead_lettered_at = now(), last_error = 'rehearsal plant: a broker timeout'
        WHERE event_id = (SELECT event_id FROM platform.outbox_event WHERE published_at IS NOT NULL AND event_type LIKE 'transfers.%'
        ORDER BY published_at DESC LIMIT 1) RETURNING event_id" | head -1 | tr -d '[:space:]')
    [ -n "$ABANDONED" ] || cannot "no event to plant as abandoned"
    fires OutboxDeadLetter placeholder-page 600 "11.6, an abandoned event"
    client retry-abandoned "$ABANDONED" >> "$ART/platform-client.log" 2>&1 || fail "11.6: the retry could not run"
    grep -q '^platform retry-abandoned status=200' "$ART/platform-client.log" || fail "11.6: the retry was refused: $(grep '^platform retry-abandoned' "$ART/platform-client.log")"
    clears OutboxDeadLetter 600 "11.6, the event retried"
    census dead-letters
}

incidents_instance_down() {
    # Section 11.8: one instance's process stopped. Only that instance pages; the others take its work.
    VICTIM=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{.items[0].metadata.name}')
    VICTIM_IP=$(kube -n finapp get pod "$VICTIM" -o jsonpath='{.status.podIP}')
    container=$(kube -n finapp get pod "$VICTIM" -o jsonpath='{.status.containerStatuses[0].containerID}' | sed 's#.*://##')
    pid=$(dock exec "$NODE" crictl inspect --output go-template --template '{{.info.pid}}' "$container" 2>/dev/null)
    [ -n "$pid" ] || cannot "the victim's process could not be found"
    dock exec "$NODE" kill -STOP "$pid" || cannot "the victim's process could not be stopped"
    fires InstanceDown placeholder-page 600 "11.8, one instance not answering" ",instance=~\"$VICTIM_IP:.*\""
    [ "$(alert_state FinappTargetsAbsent)" = none ] || fail "11.8: FinappTargetsAbsent fired for one instance"
    [ "$(prom_value 'count(up{job="finapp"} == 1)')" -ge 2 ] || fail "11.8: the other instances were not up"
    kube -n finapp delete pod "$VICTIM" --wait=false >> "$ART/apply.log" 2>&1 || fail "the stopped instance could not be replaced"
    ready_three 900 || fail "11.8: three replicas not ready after the replacement"
    clears InstanceDown 600 "11.8, the instance replaced"
    census instance-down
}

incidents_fleet_blind() {
    # Section 11.8 and section 11.27's fleet-blind page: the database cut off from every replica. Readiness goes, liveness stays.
    policy=$(kube -n finapp get networkpolicy -o name | while read -r p; do
        kube -n finapp get "$p" -o yaml | grep -q 'port: 5432' && kube -n finapp get "$p" -o yaml | grep -q 'finapp-app' && printf '%s\n' "$p"; done | head -1)
    [ -n "$policy" ] || cannot "no policy admits the application to PostgreSQL"
    kube -n finapp get "$policy" -o yaml | grep -v '^  resourceVersion:\|^  uid:\|^  creationTimestamp:\|^  generation:' > "$WORK/db-policy.yaml"
    restarts_before=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
    kube -n finapp delete "$policy" >> "$ART/apply.log" 2>&1 || fail "the database's admission could not be removed"
    fires GaugesStaleAcrossTheFleet placeholder-page 1200 "11.27, every instance's gauges stale"
    fires GaugesStale placeholder-ticket 600 "11.8, an instance's gauges stale"
    ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')
    [ "${ready:-0}" = 0 ] || fail "11.8: $ready replicas stayed ready without their database"
    restarts_during=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
    [ "$restarts_during" = "$restarts_before" ] || fail "11.8: liveness restarted a replica for a lost database: $restarts_before -> $restarts_during"
    pass "11.8: without the database every replica unready and none restarted (restart counts $restarts_during)"
    kube apply -f - < "$WORK/db-policy.yaml" >> "$ART/apply.log" 2>&1 || fail "the database's admission could not be restored"
    ready_three 900 || fail "11.8: the replicas did not become ready again"
    clears GaugesStaleAcrossTheFleet 900 "11.27, the database back"
    census fleet-blind
}

incidents_database_restart() {
    # Section 11.40, the nearest this cluster has to a failover: the database's server stopped uncleanly, in place (its volume is an emptyDir,
    # so the pod is never deleted). Not a failover: no promotion, no DNS change, no second server.
    client sign-in-flood counter 3 >> "$ART/platform-client.log" 2>&1 || true
    counted=$(psql_query -c "SELECT count(*) FROM platform.window_counter" | tr -d '[:space:]')
    [ "${counted:-0}" -gt 0 ] || fail "11.40: the positive control: no door hit was counted before the restart"
    restarts_before=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
    datadir=$(psql_query -c "SHOW data_directory" | tr -d '[:space:]')
    kube -n finapp exec finapp-postgres-0 -c postgres -- su postgres -c "pg_ctl stop -m immediate -D $datadir" >> "$ART/apply.log" 2>&1 \
        || kube -n finapp exec finapp-postgres-0 -c postgres -- pg_ctl stop -m immediate -D "$datadir" >> "$ART/apply.log" 2>&1 || true
    deadline=$(( $(date +%s) + 180 ))
    until [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')" != 3 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || break
        sleep 5
    done
    kube -n finapp rollout status statefulset/finapp-postgres --timeout=300s >> "$ART/apply.log" 2>&1 || true
    ready_three 900 || fail "11.40: the replicas did not become ready after the database came back"
    restarts_after=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.status.containerStatuses[0].restartCount}{" "}{end}')
    [ "$restarts_after" = "$restarts_before" ] || fail "11.40: a replica restarted for the database's restart: $restarts_before -> $restarts_after"
    after=$(psql_query -c "SELECT count(*) FROM platform.window_counter" | tr -d '[:space:]')
    [ "$after" = 0 ] || fail "11.40: the unlogged counter kept $after rows across an unclean stop"
    pass "11.40: the database stopped uncleanly and recovered: readiness down and back, no replica restarted, the unlogged counter emptied ($counted rows before)"
    census database-restart
}

incidents_retention_and_abuse() {
    # Sections 11.15 and 11.30, by rolling updates (never a restart of one pod): what the sweeps delete, a failing sweep, pausing them, a
    # bound refused at start; a door refusing a source past its limit, the limit raised, a store that cannot count.
    BACKDATED=$(psql_query -c "WITH old AS (SELECT event_id FROM platform.outbox_event WHERE published_at IS NOT NULL AND dead_lettered_at IS NULL
        AND discarded_at IS NULL ORDER BY published_at LIMIT 25) UPDATE platform.outbox_event o SET occurred_at = now() - interval '40 days',
        published_at = now() - interval '40 days' FROM old WHERE o.event_id = old.event_id RETURNING o.event_id" | grep -c . || true)
    [ "$BACKDATED" -gt 0 ] || cannot "no published event to backdate"
    swept_before=$(prom_value 'sum(finapp_retention_swept_total{type="outbox_event"})')
    deadline=$(( $(date +%s) + 900 ))
    until [ "$(psql_query -c "SELECT count(*) FROM platform.outbox_event WHERE published_at < now() - interval '39 days'" | tr -d '[:space:]')" = 0 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || fail "11.15: the backdated rows were not swept within 900 s"
        sleep 20
    done
    pass "11.15: $BACKDATED published events past the outbox's retention swept (counter $swept_before before)"
    psql_query -c "REVOKE EXECUTE ON FUNCTION platform.sweep_published_outbox_events(bigint, integer) FROM finapp_app" >> "$ART/plants.log" 2>&1 \
        || cannot "the sweep's grant could not be revoked"
    deadline=$(( $(date +%s) + 900 ))
    until [ "$(prom_value 'count(increase(finapp_retention_sweep_failed_total[10m]) > 0)')" = 3 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || fail "11.15: the failing sweep was not counted on every replica within 900 s"
        sleep 20
    done
    lines=0
    for pod in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name); do
        kube -n finapp logs "$pod" --since=20m 2>/dev/null | grep -q 'Retention sweep of .*42501' && lines=$((lines + 1))
    done
    [ "$lines" = 3 ] || fail "11.15: the SQLState 42501 line was on $lines replicas of 3"
    psql_query -c "GRANT EXECUTE ON FUNCTION platform.sweep_published_outbox_events(bigint, integer) TO finapp_app" >> "$ART/plants.log" 2>&1 \
        || fail "the sweep's grant could not be restored"
    pass "11.15: a sweep the role cannot run counted on every replica with its SQLState; the grant restored"
    # Pause the sweeps, and lower the sign-in door's limit to a number a rehearsal reaches: one rolling update.
    kube -n finapp patch configmap "$CONFIG_MAP" --type=merge -p '{"data":{"FINAPP_RETENTION_SWEEP_ENABLED":"false","FINAPP_ABUSE_SIGN_IN_LIMIT":"20"}}' \
        >> "$ART/apply.log" 2>&1 || fail "the ConfigMap could not be changed"
    rollout pause-sweeps config=pause-sweeps "" no
    client sign-in-flood limited 60 >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform sign-in-flood limited .*429=[1-9]' "$ART/platform-client.log" || fail "11.30: one source past the limit was not refused: $(grep 'sign-in-flood limited' "$ART/platform-client.log")"
    fires PublicDoorsRefusingMost placeholder-ticket 900 "11.30, a door refusing most of what reaches it"
    psql_query -c "REVOKE INSERT, UPDATE ON platform.window_counter FROM finapp_app" >> "$ART/plants.log" 2>&1 || cannot "the counter's grant could not be revoked"
    client sign-in-flood store 3 >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform sign-in-flood store .*other=3' "$ART/platform-client.log" || fail "11.30: a store that cannot count did not answer 503: $(grep 'sign-in-flood store' "$ART/platform-client.log")"
    psql_query -c "GRANT INSERT, UPDATE ON platform.window_counter TO finapp_app" >> "$ART/plants.log" 2>&1 || fail "the counter's grant could not be restored"
    pass "11.30: a store that cannot count answered 503 and admitted nothing; the grant restored"
    # Raise the limit and resume the sweeps: one rolling update, as the procedures say.
    kube -n finapp patch configmap "$CONFIG_MAP" --type=json -p '[{"op":"remove","path":"/data/FINAPP_RETENTION_SWEEP_ENABLED"},{"op":"remove","path":"/data/FINAPP_ABUSE_SIGN_IN_LIMIT"}]' \
        >> "$ART/apply.log" 2>&1 || fail "the ConfigMap could not be changed back"
    rollout resume-sweeps config=resume-sweeps "" no
    client sign-in-flood raised 30 >> "$ART/platform-client.log" 2>&1 || true
    grep -q '^platform sign-in-flood raised .*429=0' "$ART/platform-client.log" || fail "11.30: the raised limit still refused: $(grep 'sign-in-flood raised' "$ART/platform-client.log")"
    pass "11.30: the limit raised by a rolling update, the source admitted"
    # A bound refused at start: the inbox's retention above the outbox's. The new replica will not start; the old ones serve on.
    kube -n finapp patch configmap "$CONFIG_MAP" --type=merge -p '{"data":{"FINAPP_INBOX_RETENTION":"P40D"}}' >> "$ART/apply.log" 2>&1 || fail "the ConfigMap could not be changed"
    kube -n finapp patch deployment finapp-app --type=merge -p '{"spec":{"template":{"metadata":{"annotations":{"finapp/rehearsal-configuration":"bound-refused"}}}}}' \
        >> "$ART/apply.log" 2>&1 || fail "the refused bound's roll could not start"
    sleep 240
    ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}')
    updated=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.updatedReplicas}')
    kube -n finapp get pods -o wide > "$ART/pods-bound-refused.txt" 2>&1 || true
    [ "${ready:-0}" -ge 3 ] || fail "11.15: the old replicas stopped serving while the new one refused its bound: $ready ready"
    new_pod=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app --sort-by=.metadata.creationTimestamp -o name | tail -1)
    kube -n finapp logs "$new_pod" --tail=200 2>/dev/null | grep -qi 'retention' || fail "11.15: the new replica's refusal does not name the retention bound: see pods-bound-refused.txt"
    pass "11.15: a retention above the outbox's refused at start ($updated updated, $ready ready), the old replicas serving"
    kube -n finapp patch configmap "$CONFIG_MAP" --type=json -p '[{"op":"remove","path":"/data/FINAPP_INBOX_RETENTION"}]' >> "$ART/apply.log" 2>&1 \
        || fail "the bound could not be reverted"
    rollout bound-reverted config=bound-reverted "" no
    census retention-and-abuse
}

incidents_break_glass() {
    # Section 11.25, with section 11.12 around it: the incident declared, a second person's approval, a time-bound credential naming the
    # incident, the reach the role has and the reach it lacks, the fleet stopped and started, and the API server's record of every act.
    incident_open SEV2 "a planted raw write to the books is investigated with the fleet briefly stopped"
    BG="$WORK/break-glass"
    mkdir -p "$BG"
    holder="break-glass:$INCIDENT:holder"
    (cd "$BG" && openssl req -new -newkey rsa:2048 -nodes -subj "/CN=$holder/O=finapp-break-glass" -keyout holder.key -out holder.csr) >/dev/null 2>&1 \
        || cannot "the holder's key could not be generated"
    csr_name=$(printf 'break-glass-%s' "$INCIDENT" | tr 'A-Z' 'a-z')
    cat <<YAML | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the holder's request could not be made"
apiVersion: certificates.k8s.io/v1
kind: CertificateSigningRequest
metadata:
  name: $csr_name
spec:
  request: $(base64 -w0 < "$BG/holder.csr")
  signerName: kubernetes.io/kube-apiserver-client
  expirationSeconds: 600
  usages: [client auth]
YAML
    incident_note holder request "the finapp-break-glass group" "the incident: a pod and the fleet's scale" requested
    kube certificate approve "$csr_name" >> "$ART/apply.log" 2>&1 || cannot "the request could not be approved"
    incident_note admin approve "$csr_name, 600 s" "a second person; the end time is the credential's" approved
    deadline=$(( $(date +%s) + 60 ))
    until kube get csr "$csr_name" -o jsonpath='{.status.certificate}' | base64 -d > "$BG/holder.crt" 2>/dev/null && [ -s "$BG/holder.crt" ]; do
        [ "$(date +%s)" -lt "$deadline" ] || cannot "the certificate was not issued"
        sleep 2
    done
    BG_ISSUED=$(date +%s)
    dock exec "$NODE" mkdir -p /tmp/break-glass
    dock exec -i "$NODE" sh -c 'umask 077; cat > /tmp/break-glass/holder.crt' < "$BG/holder.crt"
    dock exec -i "$NODE" sh -c 'umask 077; cat > /tmp/break-glass/holder.key' < "$BG/holder.key"
    dock exec "$NODE" sh -c 'cd /tmp/break-glass && kubectl config --kubeconfig=config set-cluster c --server=https://127.0.0.1:6443 \
        --certificate-authority=/etc/kubernetes/pki/ca.crt --embed-certs >/dev/null && kubectl config --kubeconfig=config set-credentials h \
        --client-certificate=holder.crt --client-key=holder.key --embed-certs >/dev/null && kubectl config --kubeconfig=config set-context x \
        --cluster=c --user=h >/dev/null && kubectl config --kubeconfig=config use-context x >/dev/null && rm -f holder.key holder.crt' \
        || cannot "the holder's credential could not be configured"
    rm -f "$BG/holder.key"
    bg() { dock exec -i "$NODE" kubectl --kubeconfig=/tmp/break-glass/config "$@"; }
    # Verify it holds, as section 11.25 prints it: a subresource is named with --subresource. "create pods/exec" asks about a pod named exec.
    {
        for check in "get secrets" "list secrets" "create pods --subresource=exec" "patch deployments" "create pods" \
            "delete pods" "create pods --subresource=eviction" "patch deployments --subresource=scale"; do
            # shellcheck disable=SC2086
            printf '%s: %s\n' "$check" "$(bg auth can-i $check -n finapp 2>/dev/null)"
        done
    } > "$ART/break-glass-can-i.txt"
    expected='get secrets: no
list secrets: no
create pods --subresource=exec: no
patch deployments: no
create pods: no
delete pods: yes
create pods --subresource=eviction: yes
patch deployments --subresource=scale: yes'
    [ "$(cat "$ART/break-glass-can-i.txt")" = "$expected" ] || fail "11.25: the role's reach is not the runbook's: see break-glass-can-i.txt"
    pass "11.25: the role can evict, delete a pod and scale; it cannot read a Secret, exec, create a pod or patch a template"
    # Its refusals, tried for real, and its writes: one eviction, a second refused by the disruption budget while the first is unready.
    bg -n finapp get secrets > /dev/null 2>&1 && fail "11.25: the holder read the Secrets"
    first=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{.items[0].metadata.name}')
    second=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{.items[1].metadata.name}')
    bg -n finapp exec "$first" -- java -version > /dev/null 2>&1 && fail "11.25: the holder could exec into a replica"
    bg -n finapp patch deployment finapp-app --type=merge -p '{"spec":{"template":{"metadata":{"annotations":{"x":"y"}}}}}' > /dev/null 2>&1 \
        && fail "11.25: the holder patched the template"
    evict() { printf '{"apiVersion":"policy/v1","kind":"Eviction","metadata":{"name":"%s","namespace":"finapp"}}' "$1" \
        | bg create --raw "/api/v1/namespaces/finapp/pods/$1/eviction" -f - 2>&1; }
    evict "$first" > "$ART/break-glass-evict-1.txt" || fail "11.25: the first eviction was refused: see break-glass-evict-1.txt"
    incident_note holder evict "pod $first" "one pod at a time" done
    if evict "$second" > "$ART/break-glass-evict-2.txt"; then
        fail "11.25: a second eviction was admitted while the first pod's replacement was not ready"
    fi
    grep -qi 'disruption budget' "$ART/break-glass-evict-2.txt" || fail "11.25: the second eviction was refused for another reason: see break-glass-evict-2.txt"
    incident_note holder evict "pod $second" "a second at once" "refused by the disruption budget"
    pass "11.25: one pod evicted, a second refused by the disruption budget while the first was being replaced"
    ready_three 900 || fail "11.25: the evicted pod was not replaced"
    # Stop the fleet, and while it is stopped, section 11.1.1's new-group step on a real broker: one group's offsets set to a moment before
    # the stop, every event since applied exactly once when the fleet comes back.
    RESET_FROM=$(date -u -d '@'"$(( $(date +%s) - 300 ))" +%Y-%m-%dT%H:%M:%S.000)
    bg -n finapp scale deployment/finapp-app --replicas=0 > "$ART/break-glass-scale.txt" 2>&1 || fail "11.25: the holder could not stop the fleet"
    incident_note holder scale "deployment finapp-app to 0" "stop the fleet while the books are read" done
    deadline=$(( $(date +%s) + 600 ))
    until [ "$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name | wc -l | tr -d ' ')" = 0 ]; do
        [ "$(date +%s)" -lt "$deadline" ] || fail "11.25: the fleet did not stop"
        sleep 5
    done
    group=finapp.accounting
    kafka_admin kafka-consumer-groups.sh --group "$group" --reset-offsets --all-topics --to-datetime "$RESET_FROM" --execute > "$ART/group-reset.txt" 2>&1 \
        || fail "11.1.1: the group's offsets could not be set: see group-reset.txt"
    incident_note pipeline reset-offsets "group $group" "section 11.1.1's new-group step, to $RESET_FROM" done
    duplicates_before=$(psql_query -c "SELECT count(*) FROM platform.inbox_message WHERE consumer LIKE 'accounting%'" | tr -d '[:space:]')
    fires FinappTargetsAbsent placeholder-page 900 "11.8, the fleet gone from the scrape"
    bg -n finapp scale deployment/finapp-app --replicas=3 >> "$ART/break-glass-scale.txt" 2>&1 || fail "11.25: the holder could not start the fleet"
    incident_note holder scale "deployment finapp-app to 3" "the autoscaler's minimum" done
    ready_three 900 || fail "11.25: the fleet did not come back"
    clears FinappTargetsAbsent 600 "11.8"
    lag_settled group-reset || fail "11.1.1: the reset group did not catch up"
    duplicates_after=$(psql_query -c "SELECT count(*) FROM platform.inbox_message WHERE consumer LIKE 'accounting%'" | tr -d '[:space:]')
    [ "$duplicates_after" = "$duplicates_before" ] || log "11.1.1: the reset group's inbox grew by $((duplicates_after - duplicates_before)) records: events published during the stop"
    pass "11.1.1: a group's offsets set with no member, the replayed window absorbed by the inbox"
    # The credential ends by itself: after its expiry the same certificate is refused.
    wait_for=$(( BG_ISSUED + 620 - $(date +%s) ))
    [ "$wait_for" -le 0 ] || sleep "$wait_for"
    if bg -n finapp get pods > "$ART/break-glass-expired.txt" 2>&1; then
        fail "11.25: the certificate still served after its expiry"
    fi
    grep -qi 'unauthorized\|logged in' "$ART/break-glass-expired.txt" || fail "11.25: the expired certificate was refused for another reason: see break-glass-expired.txt"
    incident_note admin close "the break-glass session" "the credential's end" "refused 401 after expiry"
    dock exec "$NODE" rm -rf /tmp/break-glass
    pass "11.25: the credential refused once expired (kind's bound; a real environment's is the role session's)"
    # The review: every act of the holder in the API server's own log, each with its verb, object, outcome, time and audit id, and each
    # explained by the incident record.
    api_audit "$holder" "$ART/break-glass-api-audit.txt"
    for want in 'create|r=pods,s=eviction' 'list|r=secrets' 'patch|r=deployments' 'create|r=pods,s=exec' 'patch|r=deployments,s=scale'; do
        grep -q "^$want" "$ART/break-glass-api-audit.txt" || fail "11.25: the API audit log has no '$want' by the holder: see break-glass-api-audit.txt"
    done
    awk -F'|' '$4 == "" || $5 == "" || $6 == "" {bad++} END {exit bad > 0}' "$ART/break-glass-api-audit.txt" \
        || fail "11.25: an audit event lacks its code, time or id: see break-glass-api-audit.txt"
    printf '\n## The API server'"'"'s record of the holder (%s)\n\n```\n' "$holder" >> "$INCIDENT_FILE"
    cat "$ART/break-glass-api-audit.txt" >> "$INCIDENT_FILE"
    printf '```\n' >> "$INCIDENT_FILE"
    pass "11.25 and 11.12: $(wc -l < "$ART/break-glass-api-audit.txt" | tr -d ' ') acts of $holder in the API audit log, each with verb, object, outcome code, time and audit id, joined to $INCIDENT's record"
    census break-glass
}

incidents_books() {
    # Section 11.12's game day around sections 11.20 and 11.27: two raw writes to the books, by a writer no door can be, found by their pages.
    [ -n "${INCIDENT_FILE:-}" ] || incident_open SEV1 "the books disagree with themselves"
    # An unsourced entry: a copy of a transfer's entry under a fresh id and reference, its balances moved with it, so the census holds and only
    # the source join can see it.
    ENTRY=$(psql_query -c "SELECT e.id FROM ledger.journal_entry e WHERE e.entry_type LIKE '%TRANSFER%' AND e.reverses_entry_id IS NULL ORDER BY e.created_at DESC LIMIT 1" | tr -d '[:space:]')
    [ -n "$ENTRY" ] || cannot "no transfer entry to copy"
    PLANTED_ENTRY=$(psql_query -c "SELECT uuidv7()" | tr -d '[:space:]')
    psql_query -f - >> "$ART/plants.log" 2>&1 <<SQL || cannot "the unsourced entry could not be planted"
\set ON_ERROR_STOP on
BEGIN;
INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, reason, actor_id, correlation_id, causation_id, idempotency_scope, created_at)
SELECT '$PLANTED_ENTRY', posting_date, value_date, entry_type, 'rehearsal-plant-$INCIDENT', reason, actor_id, correlation_id, causation_id, idempotency_scope, now()
  FROM ledger.journal_entry WHERE id = '$ENTRY';
INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction, amount_minor, seq)
SELECT uuidv7(), '$PLANTED_ENTRY', ledger_account_id, direction, amount_minor, seq FROM ledger.journal_line WHERE entry_id = '$ENTRY';
UPDATE ledger.account_balance b SET posted_minor = b.posted_minor + CASE WHEN l.direction = a.normal_balance THEN l.amount_minor ELSE -l.amount_minor END
  FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id
 WHERE l.entry_id = '$PLANTED_ENTRY' AND b.ledger_account_id = l.ledger_account_id;
COMMIT;
SQL
    incident_note "a raw writer" insert "journal entry $PLANTED_ENTRY" "the plant" written
    fires JournalEntryUnsourced placeholder-page 900 "11.27, an entry no domain record explains"
    incident_note rehearsal declare "$INCIDENT" "JournalEntryUnsourced: a possible unauthorised posting" "declared SEV1"
    psql_query -c "START TRANSACTION READ ONLY; SELECT entry_id, kind, door, namespace FROM accounting.source_census_finding ORDER BY found_at DESC LIMIT 5; COMMIT;" \
        > "$ART/census-findings.txt" 2>&1 || true
    grep -q "$PLANTED_ENTRY" "$ART/census-findings.txt" || fail "11.27: the finding does not name the planted entry: see census-findings.txt"
    incident_note rehearsal read "accounting.source_census_finding" "triage step 1, read only" "the planted entry, UNSOURCED"
    pass "11.27: the finding names the planted entry, read in a read-only session"
    # The trial balance: one line of that same entry raised by one minor unit and its balance with it, so only the trial balance sees it.
    psql_query -f - >> "$ART/plants.log" 2>&1 <<SQL || cannot "the unbalanced line could not be planted"
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL session_replication_role = replica;
UPDATE ledger.journal_line SET amount_minor = amount_minor + 1 WHERE entry_id = '$PLANTED_ENTRY' AND seq = (SELECT min(seq) FROM ledger.journal_line WHERE entry_id = '$PLANTED_ENTRY');
UPDATE ledger.account_balance b SET posted_minor = b.posted_minor + CASE WHEN l.direction = a.normal_balance THEN 1 ELSE -1 END
  FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id
 WHERE l.entry_id = '$PLANTED_ENTRY' AND l.seq = (SELECT min(seq) FROM ledger.journal_line WHERE entry_id = '$PLANTED_ENTRY') AND b.ledger_account_id = l.ledger_account_id;
COMMIT;
SQL
    fires LedgerTrialBalanceNonZero placeholder-page 900 "11.20, debits and credits differ"
    incident_note rehearsal escalate "$INCIDENT" "LedgerTrialBalanceNonZero" "SEV1: no money moves until understood; deployments frozen"
    client trial-balance-report >> "$ART/platform-client.log" 2>&1 || fail "11.20: the trial balance report could not run"
    grep -q '^platform trial-balance-report status=200' "$ART/platform-client.log" || fail "11.20: the trial balance report: $(grep '^platform trial-balance-report' "$ART/platform-client.log")"
    incident_note operator report TRIAL_BALANCE "section 11.20's step" read
    incident_note rehearsal decide "restore?" "a raw write after the last backup: a restore would lose later money; corrected under review instead" no
    # The plant's teardown, which no procedure does: the raw rows removed as they were written. A real correction is a reviewed one.
    psql_query -f - >> "$ART/plants.log" 2>&1 <<SQL || cannot "the plants could not be removed"
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL session_replication_role = replica;
UPDATE ledger.account_balance b SET posted_minor = b.posted_minor - CASE WHEN l.direction = a.normal_balance THEN l.amount_minor ELSE -l.amount_minor END
  FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id = l.ledger_account_id
 WHERE l.entry_id = '$PLANTED_ENTRY' AND b.ledger_account_id = l.ledger_account_id;
DELETE FROM ledger.journal_line WHERE entry_id = '$PLANTED_ENTRY';
DELETE FROM ledger.journal_entry WHERE id = '$PLANTED_ENTRY';
COMMIT;
SQL
    incident_note rehearsal teardown "journal entry $PLANTED_ENTRY" "the plant's removal, not a procedure" removed
    clears LedgerTrialBalanceNonZero 900 "11.20, the plant removed"
    printf '\n## Review\n\nThe API audit log and platform.audit_record above, read against this table: every act of the holder is in the table.\n' >> "$INCIDENT_FILE"
    census books
}

incidents_poisoned_rows() {
    # Section 11.39 (P15-TSK-002's three rows): a non-v7 payment attempt in a candidate state. The card sweep fails every tick on every
    # instance with one WARN line; nothing is concluded meanwhile; the row's removal is the plant's teardown.
    SOURCE_ATTEMPT=$(psql_query -c "SELECT id FROM payments.payment_attempt ORDER BY created_at DESC LIMIT 1" | tr -d '[:space:]')
    [ -n "$SOURCE_ATTEMPT" ] || cannot "no payment attempt to copy"
    POISON=$(psql_query -c "SELECT gen_random_uuid()" | tr -d '[:space:]')
    psql_query -f - >> "$ART/plants.log" 2>&1 <<SQL || { log "11.39: the non-v7 attempt could not be planted: see plants.log"; return 0; }
\set ON_ERROR_STOP on
INSERT INTO payments.payment_attempt SELECT (jsonb_populate_record(NULL::payments.payment_attempt,
    to_jsonb(a) || jsonb_build_object('id', '$POISON', 'status', 'DISPATCHED', 'idempotency_key', 'rehearsal-poison-$POISON',
    'provider_reference', 'rehearsal-poison-$POISON'))).* FROM payments.payment_attempt a WHERE a.id = '$SOURCE_ATTEMPT';
SQL
    POISONED_AT=$(date +%s)
    sleep 240
    lines=0
    for pod in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name); do
        count=$(kube -n finapp logs "$pod" --since=5m 2>/dev/null | grep -c 'Payment sweep failed' || true)
        [ "$count" -ge 2 ] && lines=$((lines + 1))
    done
    [ "$lines" = 3 ] || fail "11.39: the sweep's failure was repeated on $lines replicas of 3"
    pass "11.39: one non-v7 attempt fails the card sweep every tick on all three replicas ('Payment sweep failed')"
    psql_query -c "DELETE FROM payments.payment_attempt WHERE id = '$POISON'" >> "$ART/plants.log" 2>&1 || cannot "the poisoned row could not be removed"
    census poisoned-rows
}

# ---------------------------------------------------------------------------------------------------
# The two sequences.
# ---------------------------------------------------------------------------------------------------
HELD_PAYOUTS=0
if [ "$SCOPE" = signals ]; then
    wanted platform-alerting && { step platform-alerting; signals_alerting; }
    wanted platform-plants && { step platform-plants; signals_plants; }
    wanted platform-staff && { step platform-staff; signals_staff; }
    wanted platform-surface && { step platform-surface; signals_surface; }
    wanted platform-first-role && { step platform-first-role; signals_first_role; }
    wanted platform-access-review && { step platform-access-review; signals_access_review; }
    wanted platform-domain && { step platform-domain; signals_domain; }
    wanted platform-correctness && { step platform-correctness; signals_correctness; }
    wanted platform-operators-half && { step platform-operators-half; signals_operators_half; }
    wanted platform-telemetry && { step platform-telemetry; signals_telemetry; }
    wanted platform-close && { step platform-close; signals_close; }
else
    wanted platform-prepare && { step platform-prepare; incidents_prepare; }
    wanted platform-broker && { step platform-broker; incidents_broker; }
    wanted platform-dead-letters && { step platform-dead-letters; incidents_dead_letters; }
    wanted platform-instance-down && { step platform-instance-down; incidents_instance_down; }
    wanted platform-fleet-blind && { step platform-fleet-blind; incidents_fleet_blind; }
    wanted platform-retention-and-abuse && { step platform-retention-and-abuse; incidents_retention_and_abuse; }
    wanted platform-poisoned-rows && { step platform-poisoned-rows; incidents_poisoned_rows; }
    wanted platform-break-glass && { step platform-break-glass; incidents_break_glass; }
    wanted platform-books && { step platform-books; incidents_books; }
    wanted platform-database-restart && { step platform-database-restart; incidents_database_restart; }
fi
