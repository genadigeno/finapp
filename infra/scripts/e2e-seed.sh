#!/bin/sh
# TEST-ONLY. The Operator Console's end-to-end seed (P17-TSK-106, P17-TSK-108, P17-TSK-110, P17-TSK-112; finapp-admin-panel
# docs/FINAPP_BACKEND_WORK.md B2.1, B3.5, B4.4, B5.5).
#
#   infra/scripts/e2e-seed.sh <scenario>...
#
# Puts in place, on a TEST stack, what the console's M2, M3 and M4 end-to-end tests act on, and prints what it created as KEY=value lines on
# stdout - one line per identifier, nothing else there - for the console's harness to read. Everything else goes to stderr.
#
#   abandoned-event   an outbox event its relay gave up on: the console retries it, or two persons discard it
#                     -> ABANDONED_EVENT_ID
#   parked-record     a parked inbox record whose event is published: the console releases it, or two persons discard it
#                     -> PARKED_RECORD_ID (the door's park id), PARKED_EVENT_ID
#   parsed-file       a simulated-PSP report uploaded by the seed's own operator and PARSED, awaiting a second person's
#                     attestation: the console's operator attests or declines it (never the submitter: INV-SET-07)
#                     -> PARSED_FILE_ID, PARSED_FILE_SUBMITTER_ID
#   fee-break         an OPEN FEE_MISMATCH of 1.75 EUR: a report uploaded by one of the seed's operators, attested by another,
#                     accepted and matched by the platform's own sweeps. ACKNOWLEDGE admits it, four-eyes, and moves no money; an
#                     approval echoing an operand the proposal does not name is refused 409 ResolutionStale, and the plain
#                     re-confirmation approves -> BREAK_ID, BREAK_FILE_ID
#   factorless-holding  a test identity registered with no second factor, holding LEDGER_OPERATOR - a role that needs one, which
#                     the grant door now refuses it (409 identity.FactorRequiredForRole) - so planted, as a holding from before the
#                     rule. The factorless review names it; the console revokes the role, or the person enrols
#                     -> FACTORLESS_IDENTITY_ID, FACTORLESS_ROLE
#   sod-conflict      a test identity with a factor holding ACCOUNTANT, granted through the door by the run's administrator, and
#                     FINANCIAL_CONTROLLER - its pair in the owner's segregation-of-duties table, which the door now refuses
#                     (409 identity.RoleConflict) - so planted, as a holding from before the rule. The SoD review names the pair;
#                     the console revokes one half -> SOD_CONFLICT_IDENTITY_ID, SOD_CONFLICT_ROLES (the review's order)
#   open-alert        an OPEN ACCOUNT_TAKEOVER alert on a fresh party: two of the console's analysts suppress it
#                     -> OPEN_ALERT_ID, OPEN_ALERT_PARTY_ID
#   investigated-case a case opened by hand on such an alert by the seed's analyst and taken, IN_INVESTIGATION: the console's
#                     analyst takes it over and proposes a disposition, a second approves -> CASE_ID, CASE_ALERT_ID
#   overridable-assessment  a sealed REVIEW of a 20,000,000.00 EUR transfer: one of the console's officers proposes its
#                     override, a second approves -> OVERRIDABLE_ASSESSMENT_ID
#   proposed-policy   a PROPOSED TRANSFER version - the seed's own content - proposed by the seed's officer: a different
#                     officer, the console's, activates it -> POLICY_VERSION_ID, POLICY_PROPOSER_ID
#   all               each of the above once
#
# The finance scenarios (P17-TSK-112) - each proposal by the seed's accountant, never by the console's people, so the console's
# controller approves it:
#   closable-period   the month before today's LOCKED with its valuation rate set captured: the console's controller approves the
#                     set, the close run completes (the schedule's checks, about half a minute), its accountant proposes the close
#                     and its controller approves it -> CLOSABLE_PERIOD_ID, RATE_SET_ID, RATE_SET_CAPTURER_ID
#   prior-period-adjustment  a correction of the CLOSED month two before today's, 0.50 EUR, dated today: the console's controller
#                     approves it -> PPA_ID, PPA_PROPOSER_ID
#   proposed-mapping  a GL mapping version PROPOSED from next month: the console's controller activates it -> MAPPING_VERSION_ID,
#                     MAPPING_PROPOSER_ID
#   draft-submission  a reference return of the CLOSED month, VALIDATED under an ACTIVE obligation of a fresh form: the console's
#                     controller approves it -> SUBMISSION_ID, OBLIGATION_ID, SUBMISSION_PREPARER_ID
#   accounting-break  an OPEN accounting break in today's month: the console's accountant proposes its acknowledgement, its
#                     controller approves or rejects it -> ACCOUNTING_BREAK_ID
#   finance           each of the five once
#
# THE PERIOD SCENARIOS NEED THE BOOKS OPENED MONTHS BACK (ADR-0152). closable-period, prior-period-adjustment and draft-submission
# need the two months before today's held and OPEN, which only a local stack started on a fresh database with
# FINAPP_ACCOUNTING_PERIOD_BOOKSOPENMONTHSBACK=2 holds; the seed then closes the earlier one end to end, once per stack - a GL
# mapping version in force from it, a manual adjustment in it, the four required sources' nil reports and the month-end reference
# rates for both months (fx-rates.sql: a local stack has no reference feed), the lock, the set, the run, the close by four eyes.
# They are refused on kind, whose books open in today's month and whose reference feed observes only the present.
#
# THE RISK SCENARIOS CHANGE THE STACK'S TRANSFER POLICY. Every risk queue starts empty and no policy is active, under which the
# engine ALLOWs every movement and nothing can be overridden. So the first of them proposes and activates - two of the seed's
# own officers, through the doors - a TRANSFER version that ALLOWs every movement a test stack makes and REVIEWs only an amount
# above 10,000,000.00 EUR (Seed.java's testPolicy); the alerts and the assessment are judged under it (the owner's decision of
# 2026-11-18).
#
# Every run creates fresh rows, identities and references, so it reruns without collision and two runs never share a row. The two
# settlement scenarios wait for the platform's sweeps (fifteen seconds each by default): up to a few minutes on a slow stack. The two
# reviews serve the oldest 200 holdings: on a stack that keeps more than 200 unrevoked seeded holdings, a new one is past the page -
# revoke what a test reviews, as the console does, or start from a fresh stack.
#
# WHERE IT RUNS (E2E_SEED_TARGET), and nowhere else - an unknown target is refused, never defaulted:
#   compose (default)  this checkout's compose stack: PostgreSQL through `docker compose exec`, as finapp_migrator, the role that
#                      owns the platform schema; the application at FINAPP_BACKEND_URL, which must be this machine's
#                      (http://localhost or http://127.0.0.1, any port). The HTTP half runs on the local JDK (21 or later).
#   kind               a kind cluster of the kind rehearsal: E2E_SEED_NODE names its control-plane node container, which must carry
#                      the label kind gives its nodes (io.x-k8s.kind.cluster); PostgreSQL through that node's kubectl, in pod
#                      E2E_SEED_PG_POD (default finapp-postgres-0), as the test-only server's superuser, as the rehearsal's census
#                      does; the HTTP half in the application's own JDK image on the kind network, through the real edge's public
#                      and operator hosts (PUBLIC_PORT 30443, OPERATOR_PORT 30444 unless set), trusting the edge's local CA read
#                      from the cluster. Run by the console's battery on kind (P17-TST-001), which owns that cluster.
#
# HOW: a state no door reaches - an abandoned relay, a parked record, a holding from before a rule the grant door now enforces, an
# alert, a judged movement's sealed assessment (it needs a funded, KYC-approved customer, which locally only the database makes) - is
# planted by its owning module's rules, one SQL statement per plant (infra/scripts/e2e-seed/*.sql: the dead letters proven against
# PostgreSQL and the dead-letter doors by platform's E2eSeedPlantsDatabaseTest, the holding against the reviews and the revocation
# door by app's E2eSeedReviewsDatabaseTest, the alert and the assessment against the suppression, case and override doors by app's
# E2eSeedRiskDatabaseTest; a plant's values are the database client's variables, quoted by the client itself,
# after the shell has checked their shape). Every other effect goes through the platform's own doors (infra/scripts/e2e-seed/Seed.java,
# which has no route to the database): registration, a second factor, step-up, the role grant, the settlement upload and attestation. The
# two things only the database can answer are asked here, as the console's own harness asks them: a test identity's id (no API
# exposes it before it holds a role - the console's gap G-10) and the run's first administrator, by the one documented break-glass
# step (README section 5e).
#
# WHAT IT NEVER IS: in an image, on a route, or in a deployed environment. Nothing under any module's src/main, deploy/ or
# infra/terraform names it (app's E2eSeedStaysTestOnlyTest), and it refuses a stack that is not a test one before it touches anything.
# The planted rows page the stack's own alerting (OutboxDeadLetter, InboxDeadLetterParked) as real ones would: on a test stack, by
# design. Do not run it while a kind rehearsal's experiments count dead letters.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SEED="$ROOT/infra/scripts/e2e-seed"
TARGET=${E2E_SEED_TARGET:-compose}
SCENARIOS="abandoned-event, parked-record, parsed-file, fee-break, factorless-holding, sod-conflict, open-alert, investigated-case, overridable-assessment, proposed-policy, all, closable-period, prior-period-adjustment, proposed-mapping, draft-submission, accounting-break or finance"

say() { printf '%s\n' "e2e-seed: $*" >&2; }
refuse() { printf '%s\n' "e2e-seed: refused: $*" >&2; exit 2; }

# Git Bash on Windows otherwise rewrites the paths handed to docker. Unset and harmless elsewhere.
MSYS_NO_PATHCONV=1
export MSYS_NO_PATHCONV
dpath() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

[ "$#" -ge 1 ] || refuse "name a scenario: $SCENARIOS"
HTTP=no
for scenario in "$@"; do
    case "$scenario" in
        abandoned-event|parked-record) ;;
        parsed-file|fee-break|factorless-holding|sod-conflict|open-alert|investigated-case|overridable-assessment|proposed-policy|all) HTTP=yes ;;
        closable-period|prior-period-adjustment|proposed-mapping|draft-submission|accounting-break|finance) HTTP=yes ;;
        *) refuse "unknown scenario '$scenario': $SCENARIOS" ;;
    esac
done

# ---------------------------------------------------------------------------------------------------
# The target, judged before anything is touched.
case "$TARGET" in
    compose)
        FINAPP_BACKEND_URL=${FINAPP_BACKEND_URL:-http://localhost:8080}
        # Anchored, host and port and nothing after: a glob's '*' would admit http://localhost:8.example.org.
        printf '%s' "$FINAPP_BACKEND_URL" | grep -Eq '^http://(localhost|127\.0\.0\.1)(:[0-9]{1,5})?/?$' \
            || refuse "FINAPP_BACKEND_URL is '$FINAPP_BACKEND_URL': the compose target seeds this machine's stack only (http://localhost or http://127.0.0.1)"
        FINAPP_BACKEND_URL=${FINAPP_BACKEND_URL%/}
        [ -f "$ROOT/compose.yaml" ] || refuse "no compose.yaml at $ROOT"
        command -v docker >/dev/null 2>&1 || refuse "docker is not on the PATH"
        [ "$HTTP" = no ] || command -v java >/dev/null 2>&1 || refuse "java (21 or later) is not on the PATH, and the scenarios through the doors need it"
        ;;
    kind)
        [ -n "${E2E_SEED_NODE:-}" ] || refuse "E2E_SEED_NODE is unset: name the kind cluster's control-plane node container"
        cluster=$(docker inspect -f '{{index .Config.Labels "io.x-k8s.kind.cluster"}}' "$E2E_SEED_NODE" 2>/dev/null || true)
        case "$cluster" in
            ''|'<no value>') refuse "E2E_SEED_NODE '$E2E_SEED_NODE' is not a kind node (no io.x-k8s.kind.cluster label)" ;;
        esac
        E2E_SEED_PG_POD=${E2E_SEED_PG_POD:-finapp-postgres-0}
        say "kind cluster '$cluster', node $E2E_SEED_NODE, database pod $E2E_SEED_PG_POD"
        ;;
    *)
        refuse "E2E_SEED_TARGET is '$TARGET': compose or kind"
        ;;
esac

# psql_seed [psql arguments]: the target's database, one statement or file at a time, stopping on the first error.
psql_seed() {
    case "$TARGET" in
        compose)
            docker compose -f "$(dpath "$ROOT/compose.yaml")" exec -T postgres \
                psql -X -q -A -t -v ON_ERROR_STOP=1 -U finapp_migrator -d finapp "$@"
            ;;
        kind)
            docker exec -i "$E2E_SEED_NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf -n finapp exec -i "$E2E_SEED_PG_POD" \
                -c postgres -- psql -X -q -A -t -v ON_ERROR_STOP=1 -U finapp -d finapp "$@"
            ;;
    esac
}

# plant <file> [psql options]: runs one plant, its one statement, and prints the row it returned (fields separated by '|'). Its
# values, if any, are psql variables (-v name=value), never text spliced into the statement.
plant() {
    file=$1
    shift
    psql_seed "$@" -f - < "$SEED/$file"
}

uuid_like() {
    printf '%s' "$1" | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
}

# ---------------------------------------------------------------------------------------------------
# The dead letters: planted.
seed_abandoned_event() {
    event=$(plant abandoned-event.sql | tr -d '[:space:]')
    uuid_like "$event" || { say "the abandoned event's plant printed '$event'"; exit 1; }
    say "planted an abandoned event ($event): retry it, or propose and approve its discard"
    printf 'ABANDONED_EVENT_ID=%s\n' "$event"
}

seed_parked_record() {
    row=$(plant parked-record.sql | tr -d '[:space:]')
    park=${row%%|*}
    event=${row#*|}
    uuid_like "$park" && uuid_like "$event" || { say "the parked record's plant printed '$row'"; exit 1; }
    say "planted a parked record ($park) naming the published event $event: release it, or propose and approve its discard"
    printf 'PARKED_RECORD_ID=%s\n' "$park"
    printf 'PARKED_EVENT_ID=%s\n' "$event"
}

# ---------------------------------------------------------------------------------------------------
# Through the doors, as the seed's own test identities.
STATE=
cleanup() { [ -z "$STATE" ] || rm -rf "$STATE"; }
trap cleanup EXIT
trap 'exit 130' INT TERM

# seed_http <command> [arguments]: Seed.java against the target's application. Its stdout is KEY=value lines for this script only.
seed_http() {
    case "$TARGET" in
        compose)
            java --source 21 "$(dpath "$SEED/Seed.java")" "$FINAPP_BACKEND_URL" "$FINAPP_BACKEND_URL" "$(dpath "$STATE")" "$@"
            ;;
        kind)
            docker run --rm --network kind --add-host "public.finapp.test:$PUBLIC_IP" --add-host "operator.finapp.test:$OPERATOR_IP" \
                -v "$(dpath "$STATE"):/work" -v "$(dpath "$SEED"):/src:ro" "$JDK_IMAGE" java --source 21 /src/Seed.java \
                "https://public.finapp.test:${PUBLIC_PORT:-30443}" "https://operator.finapp.test:${OPERATOR_PORT:-30444}" /work "$@"
            ;;
    esac
}

# value <KEY> <output>: the one KEY=value line Seed.java printed for KEY.
value() {
    printf '%s\n' "$2" | sed -n "s/^$1=//p" | head -1
}

# identity_of <login>: the id of a test identity Seed.java registered. Prints the id.
identity_of() {
    login=$1
    # Checked before it reaches SQL: the shape Seed.java makes and nothing else.
    printf '%s' "$login" | grep -Eq '^e2e-seed\.[a-z]+\.[0-9a-f]{12}$' || { say "a registration printed '$login'"; exit 1; }
    id=$(psql_seed -c "SELECT id FROM identity.identity WHERE login_identifier = '$login'" | tr -d '[:space:]')
    uuid_like "$id" || { say "no identity found for $login"; exit 1; }
    printf '%s\n' "$id"
}

# operator <who>: a fresh test identity with a factor, registered through the door. Prints its identity id and login.
operator() {
    login=$(value LOGIN "$(seed_http register "$1")")
    id=$(identity_of "$login")
    printf '%s %s\n' "$id" "$login"
}

# The run's first administrator: README section 5e's break-glass step, the one effect here with no door by design. Each assignment
# below takes its command substitution's status, so a failed step stops the run under set -e rather than handing on an empty id.
ADMIN_READY=no
administrator() {
    [ "$ADMIN_READY" = no ] || return 0
    STATE=$(mktemp -d)
    if [ "$TARGET" = kind ]; then
        JDK_IMAGE=$(sed -n 's/^FROM \(eclipse-temurin:[^ ]*\) AS builder$/\1/p' "$ROOT/Dockerfile")
        [ -n "$JDK_IMAGE" ] || { say "no JDK image found in the Dockerfile"; exit 1; }
        node_ip=$(docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' "$E2E_SEED_NODE")
        PUBLIC_IP=${E2E_SEED_PUBLIC_IP:-$node_ip}
        OPERATOR_IP=${E2E_SEED_OPERATOR_IP:-$node_ip}
        docker exec "$E2E_SEED_NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf -n finapp-edge get secret finapp-edge-local-ca \
            -o jsonpath='{.data.ca\.crt}' | base64 -d > "$STATE/edge-ca.crt"
        [ -s "$STATE/edge-ca.crt" ] || { say "the edge's local CA could not be read"; exit 1; }
    fi
    admin=$(operator admin)
    ADMIN_ID=${admin%% *}
    admin_login=${admin#* }
    psql_seed -c "INSERT INTO identity.role_assignment (id, identity_id, role_name, assigned_by, assigned_at) SELECT gen_random_uuid(), i.id, 'ADMINISTRATOR', i.id, now() FROM identity.identity i WHERE i.login_identifier = '$admin_login'" >/dev/null
    say "the run's administrator: $ADMIN_ID"
    ADMIN_READY=yes
}

SETTLEMENT_READY=no
settlement_operators() {
    [ "$SETTLEMENT_READY" = no ] || return 0
    administrator
    submitter=$(operator submitter)
    SUBMITTER_ID=${submitter%% *}
    attester=$(operator attester)
    ATTESTER_ID=${attester%% *}
    seed_http grant admin RECONCILIATION_OPERATOR "$SUBMITTER_ID" "$ATTESTER_ID" >/dev/null
    say "the run's operators: submitter $SUBMITTER_ID and attester $ATTESTER_ID, each RECONCILIATION_OPERATOR with a factor"
    SETTLEMENT_READY=yes
}

seed_parsed_file() {
    settlement_operators
    file=$(value FILE_ID "$(seed_http upload submitter parsed)")
    uuid_like "$file" || { say "the upload printed '$file'"; exit 1; }
    seed_http await-file submitter "$file" PARSED
    say "file $file PARSED, unattested: attest or decline it as anyone but its submitter $SUBMITTER_ID"
    printf 'PARSED_FILE_ID=%s\n' "$file"
    printf 'PARSED_FILE_SUBMITTER_ID=%s\n' "$SUBMITTER_ID"
}

seed_fee_break() {
    settlement_operators
    file=$(value FILE_ID "$(seed_http upload submitter fee)")
    uuid_like "$file" || { say "the upload printed '$file'"; exit 1; }
    seed_http await-file submitter "$file" PARSED
    seed_http attest attester "$file"
    seed_http await-file submitter "$file" ACCEPTED
    found=$(value BREAK_ID "$(seed_http await-break submitter "$file")")
    uuid_like "$found" || { say "the break search printed '$found'"; exit 1; }
    say "break $found: an OPEN FEE_MISMATCH of 1.75 EUR from file $file; propose ACKNOWLEDGE (FEE_ACCEPTED_AS_CHARGED) and approve as another person"
    printf 'BREAK_ID=%s\n' "$found"
    printf 'BREAK_FILE_ID=%s\n' "$file"
}

# ---------------------------------------------------------------------------------------------------
# The access reviews' rows: holdings from before the rules the grant door now enforces.

# held_role <identity id> <role>: plants a live holding of <role>, granted by the run's administrator (held-role.sql).
held_role() {
    uuid_like "$1" && uuid_like "$ADMIN_ID" || { say "held_role was handed '$1' by '$ADMIN_ID'"; exit 1; }
    held=$(plant held-role.sql -v identity="$1" -v role="$2" -v granter="$ADMIN_ID" | tr -d '[:space:]')
    uuid_like "$held" || { say "the holding's plant printed '$held'"; exit 1; }
}

seed_factorless_holding() {
    administrator
    login=$(value LOGIN "$(seed_http register-bare unenrolled)")
    holder=$(identity_of "$login")
    held_role "$holder" LEDGER_OPERATOR
    say "identity $holder holds LEDGER_OPERATOR with no second factor: the factorless review names it; revoke the role, or the person enrols"
    printf 'FACTORLESS_IDENTITY_ID=%s\n' "$holder"
    printf 'FACTORLESS_ROLE=%s\n' LEDGER_OPERATOR
}

seed_sod_conflict() {
    administrator
    conflicted=$(operator conflicted)
    holder=${conflicted%% *}
    seed_http grant admin ACCOUNTANT "$holder" >/dev/null
    held_role "$holder" FINANCIAL_CONTROLLER
    say "identity $holder holds ACCOUNTANT, granted through the door, and FINANCIAL_CONTROLLER, planted from before the rule: the SoD review names the pair; revoke one half"
    printf 'SOD_CONFLICT_IDENTITY_ID=%s\n' "$holder"
    printf 'SOD_CONFLICT_ROLES=%s\n' ACCOUNTANT,FINANCIAL_CONTROLLER
}

# ---------------------------------------------------------------------------------------------------
# The risk desks' rows (P17-TSK-110): under the seed's own ACTIVE TRANSFER version.

# The run's risk desks: an analyst, two officers - the seed's policy proposed by one and activated by the other (four eyes).
RISK_READY=no
risk_desks() {
    [ "$RISK_READY" = no ] || return 0
    administrator
    analyst=$(operator analyst)
    ANALYST_ID=${analyst%% *}
    officer=$(operator officer)
    OFFICER_ID=${officer%% *}
    approver=$(operator approver)
    APPROVER_ID=${approver%% *}
    seed_http grant admin RISK_ANALYST "$ANALYST_ID" >/dev/null
    seed_http grant admin RISK_OFFICER "$OFFICER_ID" "$APPROVER_ID" >/dev/null
    proposed=$(value VERSION_ID "$(seed_http propose-policy officer seed)")
    uuid_like "$proposed" || { say "the seed's policy proposal printed '$proposed'"; exit 1; }
    seed_http activate-policy approver "$proposed" >/dev/null
    TEST_POLICY_ID=$proposed
    say "the run's risk desks: analyst $ANALYST_ID, officers $OFFICER_ID and $APPROVER_ID; TRANSFER version $TEST_POLICY_ID ACTIVE, REVIEWing only above 10,000,000.00 EUR"
    RISK_READY=yes
}

# risk_alert: plants an OPEN alert under the seed's version (risk-alert.sql). Prints its id and party.
risk_alert() {
    uuid_like "$TEST_POLICY_ID" || { say "risk_alert was handed version '$TEST_POLICY_ID'"; exit 1; }
    row=$(plant risk-alert.sql -v version="$TEST_POLICY_ID" | tr -d '[:space:]')
    alert=${row%%|*}
    party=${row#*|}
    uuid_like "$alert" && uuid_like "$party" || { say "the alert's plant printed '$row'"; exit 1; }
    printf '%s %s\n' "$alert" "$party"
}

seed_open_alert() {
    risk_desks
    raised=$(risk_alert)
    say "alert ${raised%% *} OPEN on party ${raised#* }: two analysts suppress it"
    printf 'OPEN_ALERT_ID=%s\n' "${raised%% *}"
    printf 'OPEN_ALERT_PARTY_ID=%s\n' "${raised#* }"
}

seed_investigated_case() {
    risk_desks
    raised=$(risk_alert)
    on=${raised%% *}
    opened=$(value CASE_ID "$(seed_http open-case analyst "$on")")
    uuid_like "$opened" || { say "the case opening printed '$opened'"; exit 1; }
    seed_http take-case analyst "$opened" >/dev/null
    say "case $opened IN_INVESTIGATION on alert $on, taken by the seed's analyst: take it over, propose a disposition, a second approves"
    printf 'CASE_ID=%s\n' "$opened"
    printf 'CASE_ALERT_ID=%s\n' "$on"
}

seed_overridable_assessment() {
    risk_desks
    uuid_like "$TEST_POLICY_ID" || { say "the assessment's plant was handed version '$TEST_POLICY_ID'"; exit 1; }
    sealed=$(plant sealed-assessment.sql -v version="$TEST_POLICY_ID" | tr -d '[:space:]')
    uuid_like "$sealed" || { say "the assessment's plant printed '$sealed'"; exit 1; }
    say "assessment $sealed: a sealed REVIEW of 20,000,000.00 EUR; one officer proposes its override, a second approves"
    printf 'OVERRIDABLE_ASSESSMENT_ID=%s\n' "$sealed"
}

seed_proposed_policy() {
    risk_desks
    pending=$(value VERSION_ID "$(seed_http propose-policy officer proposal)")
    uuid_like "$pending" || { say "the policy proposal printed '$pending'"; exit 1; }
    say "TRANSFER version $pending PROPOSED by officer $OFFICER_ID: an officer other than its proposer activates it"
    printf 'POLICY_VERSION_ID=%s\n' "$pending"
    printf 'POLICY_PROPOSER_ID=%s\n' "$OFFICER_ID"
}

# ---------------------------------------------------------------------------------------------------
# The finance desks' rows (P17-TSK-112).

# The run's finance desks: an accountant who also prepares returns (ACCOUNTANT and REGULATORY_REPORTER, a pair the segregation-of-
# duties table admits) and a controller (FINANCIAL_CONTROLLER) - the seed's proposers and its approver. The console's own are other
# identities: none of these approves what it proposed, and the console's controller decides what these propose.
FINANCE_READY=no
finance_desks() {
    [ "$FINANCE_READY" = no ] || return 0
    administrator
    accountant=$(operator accountant)
    ACCOUNTANT_ID=${accountant%% *}
    controller=$(operator controller)
    CONTROLLER_ID=${controller%% *}
    seed_http grant admin ACCOUNTANT "$ACCOUNTANT_ID" >/dev/null
    seed_http grant admin REGULATORY_REPORTER "$ACCOUNTANT_ID" >/dev/null
    seed_http grant admin FINANCIAL_CONTROLLER "$CONTROLLER_ID" >/dev/null
    BOOKS=$(seed_http books accountant)
    CURRENT_MONTH=$(value CURRENT_MONTH "$BOOKS")
    NEXT_MONTH=$(value NEXT_MONTH "$BOOKS")
    month_like "$CURRENT_MONTH" && month_like "$NEXT_MONTH" || { say "the books printed months '$CURRENT_MONTH' and '$NEXT_MONTH'"; exit 1; }
    say "the run's finance desks: accountant $ACCOUNTANT_ID (also the reporter) and controller $CONTROLLER_ID"
    FINANCE_READY=yes
}

month_like() {
    printf '%s' "$1" | grep -Eq '^[0-9]{4}-(0[1-9]|1[0-2])$'
}

# decimal <minor>: a two-decimal amount from minor units, its sign kept.
decimal() {
    minor=$1
    sign=
    case "$minor" in -*) sign=-; minor=${minor#-} ;; esac
    while [ "${#minor}" -lt 3 ]; do minor="0$minor"; done
    printf '%s%s.%s' "$sign" "${minor%??}" "${minor#"${minor%??}"}"
}

# rates <instant>: a month's four reference rates (fx-rates.sql), refused unless all four were kept - the plant writes nothing on a
# stack whose feed observed something later.
rates() {
    printf '%s' "$1" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T12:00:00Z$' || { say "the books printed rates at '$1'"; exit 1; }
    kept=$(plant fx-rates.sql -v observed="$1" | grep -c '^ *[A-Z][A-Z][A-Z] *$' || true)
    [ "$kept" = 4 ] || refuse "only $kept of the four reference rates at $1 were kept: a reference feed has observed later than the month, so a past month's valuation set cannot be captured here (ADR-0152: the period scenarios run on a local stack)"
}

# The books, closed once per stack: the month two before today's closed end to end by the seed's own desks, and the month before
# today's left OPEN for the console. The books must have opened months back (ADR-0152), which only a fresh database started with
# FINAPP_ACCOUNTING_PERIOD_BOOKSOPENMONTHSBACK at 2 or more does; a stack whose closing month is no longer OPEN has been seeded already.
BOOKS_READY=no
finance_books() {
    [ "$BOOKS_READY" = no ] || return 0
    [ "$TARGET" = compose ] || refuse "the finance books are a local stack's: the kind rehearsal keeps the books' first month at today's and its reference feed observes only the present (ADR-0152)"
    finance_desks
    CLOSING_MONTH=$(value CLOSING_MONTH "$BOOKS")
    CLOSABLE_MONTH=$(value CLOSABLE_MONTH "$BOOKS")
    CLOSING_ID=$(value CLOSING_ID "$BOOKS")
    CLOSABLE_ID=$(value CLOSABLE_ID "$BOOKS")
    if [ "$(value CLOSING_STATUS "$BOOKS")" != OPEN ] || [ "$(value CLOSABLE_STATUS "$BOOKS")" != OPEN ]; then
        refuse "the finance books need $CLOSING_MONTH and $CLOSABLE_MONTH held and OPEN: start the platform on a fresh database with FINAPP_ACCOUNTING_PERIOD_BOOKSOPENMONTHSBACK=2 (ADR-0152) - the books are seeded once per stack"
    fi
    month_like "$CLOSING_MONTH" && month_like "$CLOSABLE_MONTH" && uuid_like "$CLOSING_ID" && uuid_like "$CLOSABLE_ID" \
        || { say "the books printed '$CLOSING_MONTH' $CLOSING_ID and '$CLOSABLE_MONTH' $CLOSABLE_ID"; exit 1; }
    booker=$(operator booker)
    BOOKER_ID=${booker%% *}
    checker=$(operator checker)
    CHECKER_ID=${checker%% *}
    seed_http grant admin LEDGER_OPERATOR "$BOOKER_ID" "$CHECKER_ID" >/dev/null
    settlement_operators
    mapping=$(value MAPPING_VERSION_ID "$(seed_http propose-mapping accountant "$CLOSING_MONTH" "$SEED/gl-mapping.json")")
    uuid_like "$mapping" || { say "the books' mapping proposal printed '$mapping'"; exit 1; }
    seed_http activate-mapping controller "$mapping" >/dev/null
    seed_http adjust booker checker "$CLOSING_MONTH-15" >/dev/null
    chain=$(psql_seed -c "SELECT b.statement_sequence || '|' || b.closing_minor FROM settlement.batch b JOIN settlement.source s ON s.id = b.source_id WHERE s.code = 'simulated-bank.statement' AND b.currency = 'EUR' AND b.status = 'ACCEPTED' ORDER BY b.statement_sequence DESC LIMIT 1" | tr -d '[:space:]')
    sequence=${chain%%|*}
    closing=${chain#*|}
    [ -n "$chain" ] || { sequence=0; closing=0; }
    printf '%s|%s' "$sequence" "$closing" | grep -Eq '^[0-9]+\|-?[0-9]+$' || { say "the bank statement chain read '$chain'"; exit 1; }
    opening=$(decimal "$closing")
    seed_http nil-reports submitter attester "$CLOSING_MONTH-15" "$((sequence + 1))" "$opening" >/dev/null
    seed_http nil-reports submitter attester "$CLOSABLE_MONTH-15" "$((sequence + 2))" "$opening" >/dev/null
    rates "$(value CLOSING_RATES_AT "$BOOKS")"
    rates "$(value CLOSABLE_RATES_AT "$BOOKS")"
    seed_http close-month accountant controller "$CLOSING_ID" >/dev/null
    say "the books: $CLOSING_MONTH CLOSED by the seed's accountant and controller, under mapping version $mapping; $CLOSABLE_MONTH OPEN with its nil reports and rates"
    BOOKS_READY=yes
}

seed_closable_period() {
    finance_books
    captured=$(value RATE_SET_ID "$(seed_http lock-and-capture accountant "$CLOSABLE_ID")")
    uuid_like "$captured" || { say "the capture printed '$captured'"; exit 1; }
    say "$CLOSABLE_MONTH LOCKED, its rate set $captured captured by $ACCOUNTANT_ID: a controller approves the set, the close run completes, then an accountant proposes the close and a controller approves it"
    printf 'CLOSABLE_PERIOD_ID=%s\n' "$CLOSABLE_ID"
    printf 'RATE_SET_ID=%s\n' "$captured"
    printf 'RATE_SET_CAPTURER_ID=%s\n' "$ACCOUNTANT_ID"
}

seed_prior_period_adjustment() {
    finance_books
    revision=$(value CLOSING_REVISION "$(seed_http books accountant)")
    printf '%s' "$revision" | grep -Eq '^[0-9]+$' || { say "the books printed revision '$revision'"; exit 1; }
    ppa=$(value PPA_ID "$(seed_http propose-ppa accountant "$CLOSING_ID" "$revision")")
    uuid_like "$ppa" || { say "the prior-period adjustment printed '$ppa'"; exit 1; }
    say "prior-period adjustment $ppa of $CLOSING_MONTH PROPOSED by $ACCOUNTANT_ID: a controller approves it"
    printf 'PPA_ID=%s\n' "$ppa"
    printf 'PPA_PROPOSER_ID=%s\n' "$ACCOUNTANT_ID"
}

seed_proposed_mapping() {
    finance_desks
    pending=$(value MAPPING_VERSION_ID "$(seed_http propose-mapping accountant "$NEXT_MONTH" "$SEED/gl-mapping.json")")
    uuid_like "$pending" || { say "the mapping proposal printed '$pending'"; exit 1; }
    say "GL mapping version $pending PROPOSED from $NEXT_MONTH by $ACCOUNTANT_ID: a controller activates it"
    printf 'MAPPING_VERSION_ID=%s\n' "$pending"
    printf 'MAPPING_PROPOSER_ID=%s\n' "$ACCOUNTANT_ID"
}

seed_draft_submission() {
    finance_books
    obligation=$(value OBLIGATION_ID "$(seed_http obligation accountant controller)")
    uuid_like "$obligation" || { say "the obligation printed '$obligation'"; exit 1; }
    submission=$(value SUBMISSION_ID "$(seed_http draft-submission accountant "$obligation" "$CLOSING_ID")")
    uuid_like "$submission" || { say "the draft printed '$submission'"; exit 1; }
    say "return $submission of $CLOSING_MONTH VALIDATED, prepared by $ACCOUNTANT_ID under ACTIVE obligation $obligation: a controller approves it"
    printf 'SUBMISSION_ID=%s\n' "$submission"
    printf 'OBLIGATION_ID=%s\n' "$obligation"
    printf 'SUBMISSION_PREPARER_ID=%s\n' "$ACCOUNTANT_ID"
}

seed_accounting_break() {
    finance_desks
    subject="e2e-seed:$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
    printf '%s' "$subject" | grep -Eq '^e2e-seed:[0-9a-f]{12}$' || { say "a break subject came out '$subject'"; exit 1; }
    raised=$(plant accounting-break.sql -v month="$CURRENT_MONTH-01" -v subject="$subject" | tr -d '[:space:]')
    uuid_like "$raised" || { say "the break's plant printed '$raised'"; exit 1; }
    say "accounting break $raised OPEN in $CURRENT_MONTH: an accountant proposes its acknowledgement, a controller approves or rejects it"
    printf 'ACCOUNTING_BREAK_ID=%s\n' "$raised"
}

for scenario in "$@"; do
    case "$scenario" in
        abandoned-event) seed_abandoned_event ;;
        parked-record) seed_parked_record ;;
        parsed-file) seed_parsed_file ;;
        fee-break) seed_fee_break ;;
        factorless-holding) seed_factorless_holding ;;
        sod-conflict) seed_sod_conflict ;;
        open-alert) seed_open_alert ;;
        investigated-case) seed_investigated_case ;;
        overridable-assessment) seed_overridable_assessment ;;
        proposed-policy) seed_proposed_policy ;;
        closable-period) seed_closable_period ;;
        prior-period-adjustment) seed_prior_period_adjustment ;;
        proposed-mapping) seed_proposed_mapping ;;
        draft-submission) seed_draft_submission ;;
        accounting-break) seed_accounting_break ;;
        finance) seed_closable_period; seed_prior_period_adjustment; seed_proposed_mapping; seed_draft_submission; seed_accounting_break ;;
        all) seed_abandoned_event; seed_parked_record; seed_parsed_file; seed_fee_break; seed_factorless_holding; seed_sod_conflict
             seed_open_alert; seed_investigated_case; seed_overridable_assessment; seed_proposed_policy ;;
    esac
done
