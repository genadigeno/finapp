# shellcheck shell=sh
# The end-to-end battery (P17-TST-001; PHASE_GATES.md section 5 Phase 17, criteria 2-4; OPERATIONS_RUNBOOK.md section 11.69): the seven
# mandatory scenarios of the Operator Console, each its console's Playwright spec against the real application at three replicas through
# the operator edge, and each judged from the run's own records - the console's report, the proxy's journal, the database. Functions only,
# sourced by kind-rehearsal.sh and called under REHEARSAL_SCOPE=battery, after the common prefix: release N deployed alone and smoked, its
# census green, the console loaded and asked through the edge, the telemetry pipeline up.
#
# THE CONSOLE'S HALF. The specs are finapp-admin-panel's, run from CONSOLE_REPOSITORY at CONSOLE_COMMIT - the commit the console's image is
# built from - and the battery refuses anything else: an image alone has no specs, and specs of another commit test another console. Each
# scenario runs `npx playwright test --project=kind --grep @battery-s<n> --reporter=json` there with the environment battery_spec hands
# over, and must leave in its JSON report one passed test tagged @battery-s<n> carrying two annotations: csp-violations (the console's
# Content-Security-Policy violations the browser reported during the test, a whole number) and session-token-at-rest (`none`: no session
# token in localStorage, sessionStorage, IndexedDB, a cookie or a URL, read after sign-in, after step-up, after the acts and after
# sign-out). Battery.java's report command reads it. What each spec does is the scenario table in OPERATIONS_RUNBOOK.md section 11.69.
#
# THE PROXY. The browser reaches the operator edge only through Battery.java's proxy, one per scenario, in the JDK image on the kind
# network, published on this machine's 127.0.0.1 at the edge's own port (BATTERY_PORT, 30444), so the console's origin is the edge's:
# https://operator.finapp.test:30444. It presents the operator edge's own certificate, read from the cluster for the run, and forwards to
# the real edge, verifying it against the run's local CA. Scenario 4's proxy drops the first refund approval whose answer is a 2xx -
# the backend has committed - and its journal, like every scenario's, is judged by Battery.java's journal command.
#
# THE PEOPLE. e2e-seed.sh battery-people, once: the run's administrator and ten operators, each with a factor and its roles, their
# credentials in a file of this run's WORK (BATTERY_PEOPLE), removed with it. Each scenario's subject is seeded by e2e-seed.sh just before
# its spec, and printed as KEY=value lines (BATTERY_SEED).
#
# THE COUNT. The fleet at the run's count, three, ready and unrestarted before and after each scenario (scenario 7 scales it to zero and
# back, and is judged by the count it comes back to); the replicas that served the battery's /v1/operator requests counted from
# Prometheus at the end; the census after the battery, its expected money the smoke's and the battery's own (scenario 4's top-up, and
# its refund once COMPLETED).
#
# WHAT IT NEVER DOES. It claims nothing a run did not show: a scenario whose spec, report, journal or reading is missing fails, and an
# exit 2 is a battery that could not run. The scenarios are seven, all of them, in a record; BATTERY_SCENARIOS names fewer in the
# development mode alone (REHEARSAL_KEEP=1, never under CI). The probe battery-no-drop runs scenario 4 without its drop, which its
# journal's judgement must refuse.

BATTERY_ALL="s1 s2 s3 s4 s5 s6 s7"
BATTERY_SCENARIOS=${BATTERY_SCENARIOS:-$BATTERY_ALL}
BATTERY_PORT=${BATTERY_PORT:-30444}
BATTERY_OPERATOR_ORIGIN="https://operator.finapp.test:30444"
BATTERY_FLEET_STOPPED=""
BATTERY_CSP_TOTAL=0
BATTERY_STARTED=0
# The census's battery terms: the refundable payment's top-up when scenario 4 seeds it, and its wallet net of the refund once COMPLETED.
BATTERY_TOPUPS=0
BATTERY_WALLET_MINOR=0

# The doors each scenario's journal is judged on: rule, method (or ANY) and path, as Battery.java's journal command takes them.
BATTERY_S1_RULE='answered|POST|^/v1/ledger/adjustments/[^/]+/approval$|403'
BATTERY_S2_RULE='revoked|ANY|^/v1/operator/events/.+$'
BATTERY_S3_RULE='conflict|POST|^/v1/operator/risk/cases/[^/]+/actions$'
BATTERY_S4_RULE='drop|POST|^/v1/operator/refund-proposals/[^/]+/approval$'
BATTERY_S5_RULE='stale|POST|^/v1/operator/reconciliation/resolutions/[^/]+/approval$'
BATTERY_S6_RULE='duplicate|POST|^/v1/operator/events/discards/[^/]+/approval$'
BATTERY_S7_RULE='outage'

# battery_resolve: everything the battery needs that can be judged before anything is built, refused here and not after an hour.
battery_resolve() {
    [ "$SCOPE" = battery ] || {
        [ "$BATTERY_SCENARIOS" = "$BATTERY_ALL" ] || { echo "?? BATTERY_SCENARIOS is the battery scope's" >&2; exit 2; }
        case $PROBE in battery-*) echo "?? REHEARSAL_PROBE=$PROBE is the battery scope's" >&2; exit 2 ;; esac
        return 0
    }
    case $PROBE in '' | battery-no-drop) ;; *) echo "?? the battery's probe is battery-no-drop, not $PROBE" >&2; exit 2 ;; esac
    [ "$CONSOLE_FROM" = repository ] || { echo "?? the battery runs the console's specs: name CONSOLE_REPOSITORY at CONSOLE_COMMIT, the commit its image is built from (an image or an archive alone has no specs)" >&2; exit 2; }
    printf '%s' "$CONSOLE_COMMIT" | grep -Eq '^[0-9a-f]{40}$' || { echo "?? CONSOLE_COMMIT is a full forty-character commit, not '$CONSOLE_COMMIT'" >&2; exit 2; }
    [ "$(git -C "$(dpath "$CONSOLE_REPOSITORY")" rev-parse HEAD 2>/dev/null)" = "$CONSOLE_COMMIT" ] \
        || { echo "?? the specs run from the console's checkout, whose HEAD must be CONSOLE_COMMIT ($CONSOLE_COMMIT): check it out there" >&2; exit 2; }
    [ -z "$(git -C "$(dpath "$CONSOLE_REPOSITORY")" status --porcelain --untracked-files=no 2>/dev/null)" ] \
        || { echo "?? the console's checkout has changes: the specs run are CONSOLE_COMMIT's, unchanged" >&2; exit 2; }
    [ -f "$CONSOLE_REPOSITORY/playwright.config.ts" ] && [ -d "$CONSOLE_REPOSITORY/node_modules/@playwright/test" ] \
        || { echo "?? the console's checkout has no installed Playwright: npm ci there first (the battery installs nothing)" >&2; exit 2; }
    command -v npx >/dev/null 2>&1 || { echo "?? npx is not on the PATH: the console's specs run with it" >&2; exit 2; }
    command -v java >/dev/null 2>&1 || { echo "?? java (21 or later) is not on the PATH: the battery's report and journal are read with it" >&2; exit 2; }
    case $BATTERY_PORT in '' | *[!0-9]*) echo "?? BATTERY_PORT is a port, not '$BATTERY_PORT'" >&2; exit 2 ;; esac
    if [ "$BATTERY_SCENARIOS" != "$BATTERY_ALL" ]; then
        [ -z "${CI:-}" ] || { echo "?? BATTERY_SCENARIOS is a development mode, refused under CI: a record is of all seven" >&2; exit 2; }
        [ "$KEEP" = 1 ] || { echo "?? BATTERY_SCENARIOS needs REHEARSAL_KEEP=1: fewer than seven is a development run" >&2; exit 2; }
        for br_id in $BATTERY_SCENARIOS; do
            case " $BATTERY_ALL " in *" $br_id "*) ;; *) echo "?? BATTERY_SCENARIOS names s1 to s7, not $br_id" >&2; exit 2 ;; esac
        done
    fi
}

# battery_clear: on every way out (finish), a fleet the battery stopped is started again - a kept cluster is never left without it.
battery_clear() {
    [ -n "$BATTERY_FLEET_STOPPED" ] || return 0
    kube -n finapp scale deployment/finapp-app --replicas="$FLEET_REPLICAS" >> "$ART/apply.log" 2>&1 || true
    BATTERY_FLEET_STOPPED=""
}

# battery_tool <arguments>: Battery.java's report, journal and tls-check commands, on this machine's JDK. The keystore's password is
# exported to tls-check alone, in its own subshell: no other process of the run - the console's specs, the seed - is handed it.
battery_tool() {
    (
        [ "$1" != tls-check ] || export BATTERY_PROXY_KEYSTORE_PASSWORD
        java --source 21 "$(dpath "$DIR/Battery.java")" "$@"
    )
}

# battery_judge <label> <command...>: a Battery.java judgement as an assertion - its JOURNAL, REPORT or TLS line a pass, anything else a
# fail - its line kept in BATTERY_JUDGED. Called in this shell, never in a command substitution: a fail there would finish a subshell.
battery_judge() {
    bj_label=$1
    shift
    BATTERY_JUDGED=$(battery_tool "$@" 2>&1) && pass "$bj_label: ${BATTERY_JUDGED#* }" || fail "$bj_label: $BATTERY_JUDGED"
}

# battery_value <file> <KEY>: one KEY=value line a seed printed, its value shape-checked as an identifier or an amount. Prints it, or
# fails its status: its callers fail, in this shell.
battery_value() {
    bv=$(sed -n "s/^$2=//p" "$1" | head -1)
    printf '%s' "$bv" | grep -Eq '^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9]+\.[0-9]{2}|[A-Z]{3})$' || return 1
    printf '%s' "$bv"
}

# battery_seed <scenario> <e2e-seed scenario>: its subject, through the seed, into <scenario>/seed.env (KEY=value lines, identifiers only).
battery_seed() {
    E2E_SEED_TARGET=kind E2E_SEED_NODE="$NODE" sh "$ROOT/infra/scripts/e2e-seed.sh" "$2" > "$BATTERY_DIR/$1/seed.env" 2> "$BATTERY_DIR/$1/seed.log" \
        || fail "$1: the seed's $2 could not be made: see battery/$1/seed.log"
    log "$1: seeded $2: $(tr '\n' ' ' < "$BATTERY_DIR/$1/seed.env")"
}

# battery_fleet: the fleet's pods and their restarts, one name=restarts line each, sorted - read before and after a scenario.
battery_fleet() {
    kube -n finapp get pods -l app.kubernetes.io/name=finapp-app \
        -o jsonpath='{range .items[*]}{.metadata.name}={.status.containerStatuses[0].restartCount}{"\n"}{end}' 2>/dev/null | sort
}

# battery_proxy <scenario> [drop <METHOD> <path regex>]: the scenario's own proxy, its TLS material checked first, up before its spec.
battery_proxy() {
    bp_id=$1
    shift
    bp_work="$BATTERY_WORK/$bp_id-proxy"
    mkdir -p "$bp_work"
    cp "$BATTERY_WORK/proxy.p12" "$BATTERY_WORK/edge-ca.crt" "$bp_work/"
    bp_name="finapp-battery-proxy-$bp_id"
    CONTAINERS="$CONTAINERS $bp_name"
    dock rm -f "$bp_name" >/dev/null 2>&1 || true
    # The password reaches the container by name (-e), from a subshell that exports it: never on a command line.
    (
        export BATTERY_PROXY_KEYSTORE_PASSWORD
        dock run -d --name "$bp_name" --network kind --add-host "operator.finapp.test:$OPERATOR_IP" -p "127.0.0.1:$BATTERY_PORT:8443" \
            -e BATTERY_PROXY_KEYSTORE_PASSWORD -v "$(dpath "$bp_work"):/work" -v "$(dpath "$DIR"):/src:ro" "$JDK_IMAGE" \
            java --source 21 /src/Battery.java proxy /work "$BATTERY_OPERATOR_ORIGIN" "$@"
    ) >> "$BATTERY_DIR/$bp_id/proxy-start.log" 2>&1 \
        || cannot "$bp_id: the proxy could not start on 127.0.0.1:$BATTERY_PORT: see battery/$bp_id/proxy-start.log"
    bp_deadline=$(( $(date +%s) + 120 ))
    until dock logs "$bp_name" 2>&1 | grep -q '^LISTENING '; do
        [ "$(date +%s)" -lt "$bp_deadline" ] || { dock logs "$bp_name" > "$BATTERY_DIR/$bp_id/proxy.log" 2>&1; cannot "$bp_id: the proxy never listened: see battery/$bp_id/proxy.log"; }
        sleep 2
    done
    log "$bp_id: $(dock logs "$bp_name" 2>&1 | grep '^LISTENING ' | head -1)"
}

# battery_proxy_stop <scenario>: the proxy gone, its journal and its log kept beside the scenario's other records. The proxy writes a
# request's line just after its answer, so it is given two seconds after the spec's last request before it is stopped.
battery_proxy_stop() {
    sleep 2
    dock logs "finapp-battery-proxy-$1" > "$BATTERY_DIR/$1/proxy.log" 2>&1 || true
    dock rm -f "finapp-battery-proxy-$1" >/dev/null 2>&1 || true
    cp "$BATTERY_WORK/$1-proxy/journal.tsv" "$BATTERY_DIR/$1/journal.tsv" 2>/dev/null || fail "$1: the proxy left no journal"
}

# battery_spec <scenario>: the console's spec for it, from the console's checkout, its report into <scenario>/report.json. Its own exit
# status is not the verdict: the report is. Scenario 7's runs in the background while battery_outage serves its handshake.
battery_spec() {
    bs_id=$1
    mkdir -p "$BATTERY_WORK/$bs_id-control"
    (
        cd "$CONSOLE_REPOSITORY" || exit 2
        CONSOLE_BASE_URL="$BATTERY_OPERATOR_ORIGIN/console/" \
        BATTERY_SCENARIO="$bs_id" \
        BATTERY_SEED="$(dpath "$BATTERY_DIR/$bs_id/seed.env")" \
        BATTERY_PEOPLE="$(dpath "$BATTERY_WORK/people.properties")" \
        BATTERY_CONTROL="$(dpath "$BATTERY_WORK/$bs_id-control")" \
        BATTERY_EDGE_CA="$(dpath "$BATTERY_WORK/edge-ca.crt")" \
        BATTERY_HOST_RULES="MAP operator.finapp.test 127.0.0.1" \
        PLAYWRIGHT_JSON_OUTPUT_NAME="$(dpath "$BATTERY_DIR/$bs_id/report.json")" \
            npx playwright test --project=kind --grep "@battery-$bs_id" --reporter=json \
            > "$BATTERY_DIR/$bs_id/playwright.out" 2> "$BATTERY_DIR/$bs_id/playwright.log"
    )
}

# battery_outage <spec pid>: scenario 7's handshake, served while its spec runs. The spec writes `stop`; the fleet is scaled to zero and,
# with no pod left, `stopped` is written. The spec writes `start`; the fleet is scaled to the run's count and, ready, `started` is written.
battery_outage() {
    bo_control="$BATTERY_WORK/s7-control"
    bo_stopped=""
    while kill -0 "$1" 2>/dev/null; do
        if [ -f "$bo_control/stop" ] && [ -z "$bo_stopped" ]; then
            log "s7: the spec asked for the backend away: the fleet to zero"
            BATTERY_FLEET_STOPPED=1
            kube -n finapp scale deployment/finapp-app --replicas=0 >> "$BATTERY_DIR/s7/fleet.log" 2>&1 || fail "s7: the fleet could not be scaled to zero"
            bo_deadline=$(( $(date +%s) + 600 ))
            until [ "$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name | wc -l | tr -d ' ')" = 0 ]; do
                [ "$(date +%s)" -lt "$bo_deadline" ] || fail "s7: the fleet did not stop"
                sleep 3
            done
            date -u +%Y-%m-%dT%H:%M:%SZ > "$bo_control/stopped"
            bo_stopped=1
            pass "s7: the backend away - no replica of the application, the edge left with nothing behind it"
        fi
        if [ -n "$bo_stopped" ] && [ -f "$bo_control/start" ] && [ ! -f "$bo_control/started" ]; then
            log "s7: the spec asked for the backend back: the fleet to $FLEET_REPLICAS"
            kube -n finapp scale deployment/finapp-app --replicas="$FLEET_REPLICAS" >> "$BATTERY_DIR/s7/fleet.log" 2>&1 || fail "s7: the fleet could not be scaled back"
            ready_fleet 900 || fail "s7: the fleet did not come back to $FLEET_REPLICAS"
            BATTERY_FLEET_STOPPED=""
            date -u +%Y-%m-%dT%H:%M:%SZ > "$bo_control/started"
            pass "s7: the backend back - $FLEET_REPLICAS replicas ready"
        fi
        sleep 2
    done
    [ -n "$bo_stopped" ] || fail "s7: the spec never asked for the backend away ($bo_control/stop)"
    [ -f "$bo_control/started" ] || fail "s7: the spec never asked for the backend back ($bo_control/start)"
}

# battery_reading <scenario> <file> [-v name=value]...: the scenario's reading, in the census's form - every line a PASS, as many as it states.
battery_reading() {
    br_id=$1
    br_file=$2
    shift 2
    psql_query "$@" -f - < "$DIR/battery/$br_file" > "$BATTERY_DIR/$br_id/reading.txt" 2>&1 || fail "$br_id: the reading could not run: see battery/$br_id/reading.txt"
    br_lines=$(grep -c '^SELECT CASE' "$DIR/battery/$br_file")
    [ "$(grep -c '^\(PASS\|FAIL\)|' "$BATTERY_DIR/$br_id/reading.txt")" = "$br_lines" ] \
        || fail "$br_id: the reading printed $(grep -c '^\(PASS\|FAIL\)|' "$BATTERY_DIR/$br_id/reading.txt") verdicts where $br_file has $br_lines: see battery/$br_id/reading.txt"
    ! grep -q '^FAIL|' "$BATTERY_DIR/$br_id/reading.txt" || fail "$br_id: the records say otherwise: $(grep '^FAIL|' "$BATTERY_DIR/$br_id/reading.txt" | tr '\n' ' ')"
    pass "$br_id: the records hold - $(cut -d'|' -f2 "$BATTERY_DIR/$br_id/reading.txt" | paste -sd ';' -)"
}

# battery_rule <scenario>: its journal judged by its rule, then for the session tokens.
battery_rule() {
    eval "br_rule=\$BATTERY_$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')_RULE"
    br_journal="$(dpath "$BATTERY_DIR/$1/journal.tsv")"
    case $br_rule in
        outage) battery_judge "$1: the journal" journal outage "$br_journal" ;;
        *)
            br_kind=$(printf '%s' "$br_rule" | cut -d'|' -f1)
            br_method=$(printf '%s' "$br_rule" | cut -d'|' -f2)
            br_path=$(printf '%s' "$br_rule" | cut -d'|' -f3)
            br_status=$(printf '%s' "$br_rule" | cut -d'|' -f4)
            # shellcheck disable=SC2086 # an absent status is no argument
            battery_judge "$1: the journal" journal "$br_kind" "$br_journal" "$br_method" "$br_path" $br_status
            ;;
    esac
    battery_judge "$1: no session token in a URL or a cookie (criterion 4, the proxy's half)" journal tokens "$br_journal"
}

# battery_report <scenario>: the console's report for it - one passed test - and its violation count added to the battery's.
battery_report() {
    [ -s "$BATTERY_DIR/$1/report.json" ] || fail "$1: the console's spec left no report: see battery/$1/playwright.log"
    battery_judge "$1: the console's spec (@battery-$1)" report "$(dpath "$BATTERY_DIR/$1/report.json")" "@battery-$1"
    brp_csp=$(printf '%s' "$BATTERY_JUDGED" | sed -n 's/.* csp-violations=\([0-9]*\) .*/\1/p')
    [ -n "$brp_csp" ] || fail "$1: the report's violation count could not be read"
    BATTERY_CSP_TOTAL=$((BATTERY_CSP_TOTAL + brp_csp))
}

# battery_scenario <scenario>: its start on the database's clock, its fleet before, its seed, its proxy, its spec, its records.
battery_scenario() {
    bsc_id=$1
    mkdir -p "$BATTERY_DIR/$bsc_id"
    ready_fleet 900 || fail "$bsc_id: the fleet is not at $FLEET_REPLICAS ready before the scenario"
    battery_fleet > "$BATTERY_DIR/$bsc_id/fleet-before.txt"
    since=$(psql_query -c "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')" | tr -d '[:space:]')
    printf '%s' "$since" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z$' || fail "$bsc_id: the database's clock read '$since'"
    printf 'SINCE=%s\n' "$since" > "$BATTERY_DIR/$bsc_id/since.env"
    case $bsc_id in
        s1 | s2 | s7) : > "$BATTERY_DIR/$bsc_id/seed.env" ;;
        s3) battery_seed s3 investigated-case ;;
        s4) battery_seed s4 refundable-payment ;;
        s5) battery_seed s5 fee-break ;;
        s6) battery_seed s6 parked-record ;;
    esac
    if [ "$bsc_id" = s4 ] && [ "$PROBE" != battery-no-drop ]; then
        battery_proxy s4 drop POST '^/v1/operator/refund-proposals/[^/]+/approval$'
    else
        [ "$bsc_id" != s4 ] || log "PROBE battery-no-drop: s4's proxy runs without its drop, which its journal's judgement must refuse"
        battery_proxy "$bsc_id"
    fi
    if [ "$bsc_id" = s7 ]; then
        battery_spec s7 &
        bsc_pid=$!
        BACKGROUND="$BACKGROUND $bsc_pid"
        battery_outage "$bsc_pid"
        wait "$bsc_pid" || true
    else
        battery_spec "$bsc_id" || true
    fi
    battery_proxy_stop "$bsc_id"
    battery_report "$bsc_id"
    battery_rule "$bsc_id"
    case $bsc_id in
        s1) battery_reading s1 s1-unauthorised.sql -v "since=$since" -v "viewer=$BATTERY_ID_viewer" ;;
        s2) battery_reading s2 s2-revoked.sql -v "since=$since" -v "revoked=$BATTERY_ID_revoked" -v "admin=$BATTERY_ID_admin" ;;
        s3)
            bsc_case=$(battery_value "$BATTERY_DIR/s3/seed.env" CASE_ID) || fail "s3: the seed printed no CASE_ID"
            battery_reading s3 s3-conflict.sql -v "since=$since" -v "case=$bsc_case" -v "analysta=$BATTERY_ID_analysta" \
                -v "analystb=$BATTERY_ID_analystb"
            ;;
        s4)
            bsc_payment=$(battery_value "$BATTERY_DIR/s4/seed.env" REFUNDABLE_PAYMENT_ID) || fail "s4: the seed printed no REFUNDABLE_PAYMENT_ID"
            bsc_amount=$(battery_value "$BATTERY_DIR/s4/seed.env" REFUNDABLE_AMOUNT) || fail "s4: the seed printed no REFUNDABLE_AMOUNT"
            bsc_minor=$(printf '%s' "$bsc_amount" | tr -d '.' | sed 's/^0*//')
            battery_refund_settled "$bsc_payment"
            battery_reading s4 s4-refund.sql -v "payment=$bsc_payment" -v "amount_minor=$bsc_minor" -v "refunder=$BATTERY_ID_refunder" \
                -v "controller=$BATTERY_ID_controller"
            # The census's terms: the top-up the seed was told succeeded, and the wallet it credited...
            BATTERY_TOPUPS=$((BATTERY_TOPUPS + 1))
            BATTERY_WALLET_MINOR=$((BATTERY_WALLET_MINOR + bsc_minor))
            # ...which the refund, COMPLETED of the whole amount - the reading above holds it - took back out.
            BATTERY_WALLET_MINOR=$((BATTERY_WALLET_MINOR - bsc_minor))
            ;;
        s5)
            bsc_break=$(battery_value "$BATTERY_DIR/s5/seed.env" BREAK_ID) || fail "s5: the seed printed no BREAK_ID"
            battery_reading s5 s5-stale.sql -v "since=$since" -v "break=$bsc_break" -v "reconcilera=$BATTERY_ID_reconcilera" \
                -v "reconcilerb=$BATTERY_ID_reconcilerb"
            ;;
        s6)
            bsc_park=$(battery_value "$BATTERY_DIR/s6/seed.env" PARKED_RECORD_ID) || fail "s6: the seed printed no PARKED_RECORD_ID"
            battery_reading s6 s6-duplicate.sql -v "since=$since" -v "park=$bsc_park" -v "eventsa=$BATTERY_ID_eventsa" \
                -v "eventsb=$BATTERY_ID_eventsb"
            ;;
        s7) : ;;
    esac
    ready_fleet 900 || fail "$bsc_id: the fleet is not at $FLEET_REPLICAS ready after the scenario"
    battery_fleet > "$BATTERY_DIR/$bsc_id/fleet-after.txt"
    if [ "$bsc_id" = s7 ]; then
        [ "$(grep -c . "$BATTERY_DIR/s7/fleet-after.txt")" = "$FLEET_REPLICAS" ] && ! grep -Fxq -f "$BATTERY_DIR/s7/fleet-before.txt" "$BATTERY_DIR/s7/fleet-after.txt" \
            && pass "s7: a new fleet of $FLEET_REPLICAS replaced the one scaled away" \
            || fail "s7: the fleet after is not $FLEET_REPLICAS new replicas: see battery/s7/fleet-*.txt"
    else
        cmp -s "$BATTERY_DIR/$bsc_id/fleet-before.txt" "$BATTERY_DIR/$bsc_id/fleet-after.txt" \
            && pass "$bsc_id: at $FLEET_REPLICAS replicas throughout - the same pods, none restarted" \
            || fail "$bsc_id: the fleet changed during the scenario: see battery/$bsc_id/fleet-*.txt"
    fi
}

# battery_person <who>: a person's identity id from the people file, shape-checked. Prints it, or fails its status, as battery_value.
battery_person() {
    bpe=$(sed -n "s/^$1\\.id=//p" "$BATTERY_WORK/people.properties" | head -1)
    printf '%s' "$bpe" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' || return 1
    printf '%s' "$bpe"
}

# battery_ids: every person's id and every seeded identifier a reading names, read into this shell before any reading - a missing one fails
# here, never inside a substitution.
battery_ids() {
    for bi_who in admin viewer revoked analysta analystb refunder controller reconcilera reconcilerb eventsa eventsb; do
        bi_id=$(battery_person "$bi_who") || fail "the battery's people hold no id for $bi_who"
        eval "BATTERY_ID_$bi_who=\$bi_id"
    done
}

# battery_refund_settled <payment>: scenario 4's refund out of the provider's hands - COMPLETED or FAILED - before it is read, bounded.
battery_refund_settled() {
    brs_deadline=$(( $(date +%s) + 300 ))
    while [ "$(date +%s)" -lt "$brs_deadline" ]; do
        brs_status=$(psql_query -v "payment=$1" -f - <<'SQL' | tr -d '[:space:]'
SELECT string_agg(r.status, ',') FROM payments.refund r JOIN payments.payment_attempt a ON a.id = r.attempt_id WHERE a.intent_id = :'payment'::uuid;
SQL
)
        case $brs_status in COMPLETED | FAILED | *,*) return 0 ;; esac
        sleep 5
    done
    log "s4: the refund was still '${brs_status:-absent}' after five minutes: read as it stands"
}

# battery_spread: how many replicas served the battery's /v1/operator requests, from Prometheus over the battery's window.
battery_spread() {
    sleep 45
    bsp_window=$(( $(date +%s) - BATTERY_STARTED + 60 ))
    telemetry_get finapp-prometheus:9090 "/api/v1/query?query=$(urlencode "count(sum by (instance) (increase(http_server_requests_seconds_count{job=\"finapp\",uri=~\"/v1/operator/.+\"}[${bsp_window}s])) > 0)")" \
        > "$BATTERY_DIR/spread.json" 2>&1 || true
    bsp_served=$(sed -n 's/.*"value":\[[^,]*,"\([0-9]*\)"\].*/\1/p' "$BATTERY_DIR/spread.json")
    [ -n "$bsp_served" ] && [ "$bsp_served" -ge "$FLEET_REPLICAS" ] \
        && pass "multi-instance: ${bsp_served} replicas served the battery's /v1/operator requests through the edge, at least the run's $FLEET_REPLICAS" \
        || fail "multi-instance: ${bsp_served:-no} replicas served the battery's /v1/operator requests, fewer than $FLEET_REPLICAS: see battery/spread.json"
}

# battery_scope: the battery, after the common prefix.
battery_scope() {
    BATTERY_DIR="$ART/battery"
    BATTERY_WORK="$WORK/battery"
    mkdir -p "$BATTERY_DIR" "$BATTERY_WORK"
    BATTERY_STARTED=$(date +%s)

    step battery-preflight
    # The proxy's certificate: the operator edge's own, for this run, in a keystore under a password generated here. The key leaves the
    # cluster into WORK only, and is removed with it.
    BATTERY_PROXY_KEYSTORE_PASSWORD=$(openssl rand -hex 24)
    kube -n finapp-edge get secret finapp-edge-operator-tls -o jsonpath='{.data.tls\.crt}' | base64 -d > "$BATTERY_WORK/tls.crt"
    ( umask 077; kube -n finapp-edge get secret finapp-edge-operator-tls -o jsonpath='{.data.tls\.key}' | base64 -d > "$BATTERY_WORK/tls.key" )
    [ -s "$BATTERY_WORK/tls.crt" ] && [ -s "$BATTERY_WORK/tls.key" ] || cannot "the operator edge's certificate could not be read"
    ( export BATTERY_PROXY_KEYSTORE_PASSWORD; openssl pkcs12 -export -in "$BATTERY_WORK/tls.crt" -inkey "$BATTERY_WORK/tls.key" -name edge \
        -out "$BATTERY_WORK/proxy.p12" -passout env:BATTERY_PROXY_KEYSTORE_PASSWORD ) >> "$BATTERY_DIR/preflight.log" 2>&1 \
        || cannot "the proxy's keystore could not be made"
    rm -f "$BATTERY_WORK/tls.key"
    cp "$CLIENT/edge-ca.crt" "$BATTERY_WORK/edge-ca.crt" || cannot "the edge's local CA was not read at the edge step"
    battery_judge "the proxy's TLS material" tls-check "$(dpath "$BATTERY_WORK")" operator.finapp.test
    # The people, once: their ids into the artefacts, their credentials into WORK alone.
    E2E_SEED_TARGET=kind E2E_SEED_NODE="$NODE" E2E_SEED_PEOPLE="$BATTERY_WORK/people.properties" sh "$ROOT/infra/scripts/e2e-seed.sh" battery-people \
        > "$BATTERY_DIR/people.env" 2> "$BATTERY_DIR/people.log" || fail "the battery's people could not be made: see battery/people.log"
    [ "$(grep -c '^BATTERY_[A-Z]*_ID=' "$BATTERY_DIR/people.env")" = 11 ] || fail "the battery's people: $(grep -c '^BATTERY_[A-Z]*_ID=' "$BATTERY_DIR/people.env") of 11 made"
    pass "the battery's people: the administrator and ten operators, each with a factor and its roles, granted through the door"
    battery_ids

    for bs_id in $BATTERY_SCENARIOS; do
        step "battery-$bs_id"
        battery_scenario "$bs_id"
    done

    step battery-end
    # Criterion 3: the console's policy, with no violation across the battery's scenarios.
    [ "$BATTERY_CSP_TOTAL" = 0 ] \
        && pass "criterion 3: no Content-Security-Policy violation across the battery's $(printf '%s\n' $BATTERY_SCENARIOS | wc -l | tr -d ' ') scenarios" \
        || fail "criterion 3: $BATTERY_CSP_TOTAL Content-Security-Policy violations across the battery: see battery/s*/report.json"
    # Criterion 4: every report said none at rest, and every journal saw no token in a URL or a cookie; said once more, together.
    pass "criterion 4: no session token at rest in the browser - storage, IndexedDB, cookies, URLs - in any scenario's report, nor in a URL or a cookie on any request"
    battery_spread
    census battery
}
