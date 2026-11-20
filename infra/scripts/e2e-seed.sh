#!/bin/sh
# TEST-ONLY. The Operator Console's end-to-end seed (P17-TSK-106, P17-TSK-108, P17-TSK-110, P17-TSK-112, P17-TSK-113,
# P17-TSK-115, P17-TSK-117, P17-TSK-119; finapp-admin-panel docs/FINAPP_BACKEND_WORK.md B2.1, B3.5, B4.4, B5.5, B6.7, B7.4, B8.6,
# B9.4).
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
# The merchant scenarios (P17-TSK-115) - the seed's own merchant administrator onboards and proposes, never the console's:
#   onboardable-organisation  a KYB-verified organisation that is no merchant yet (merchant-organisation.sql): the console onboards
#                     it -> MERCHANT_PARTY_ID
#   active-merchant   such an organisation onboarded as a EUR merchant, ACTIVE, with an API key: the console suspends it (and
#                     reinstates it, or revokes the key) -> MERCHANT_ID, MERCHANT_KEY_ID
#   proposed-destination  an ACTIVE merchant's payout destination PROPOSED by the seed's administrator (payout-destination.sql: no
#                     test stack configures the destination tokenisation the proposal door exchanges at): a different administrator,
#                     the console's, approves it -> PAYOUT_DESTINATION_ID, DESTINATION_MERCHANT_ID, DESTINATION_PROPOSER_ID
#   merchants         each of the three once
#   open-dispute      KIND ONLY. A 12.50 EUR card top-up into a fresh customer's own wallet, captured, and charged back by the card
#                     network: a dispute at CHARGED_BACK, the stage that takes evidence and a representment, on a payment with no
#                     merchant, so an operator answers it - the console's LEDGER_OPERATOR uploads evidence and represents it, or
#                     accepts it -> DISPUTE_ID, DISPUTE_PAYMENT_ID
#
# The credit scenarios (P17-TSK-117) - each proposal by the seed's own officer, never by the console's people, so the console's
# officer approves it; on both stacks:
#   review-case       a fresh customer's 5,000.00 EUR PERSONAL_LOAN request, REFERred under the seed's policy: its review case OPEN,
#                     for the console's underwriter to take and decide - DECLINED, the one decision a stack without a credit bureau
#                     admits -> REVIEW_CASE_ID, REVIEW_CASE_REQUEST_ID
#   proposed-credit-policy  a PERSONAL_LOAN credit policy version PROPOSED - the seed's own content: a different officer, the
#                     console's, approves it -> CREDIT_POLICY_VERSION_ID, CREDIT_POLICY_PROPOSER_ID
#   proposed-lending-terms  PERSONAL_LOAN lending terms PROPOSED: the console's LENDING_OFFICER approves them
#                     -> LENDING_TERMS_VERSION_ID, LENDING_TERMS_PROPOSER_ID
#   proposed-bnpl-terms  BNPL_PAY_IN_4 terms PROPOSED: the console's BNPL_OFFICER approves them -> BNPL_TERMS_VERSION_ID,
#                     BNPL_TERMS_PROPOSER_ID
#   credit            each of the four once
#
# The FX scenarios (P17-TSK-119) - each proposal by the seed's own FX controller, never by the console's people, so the console's
# FX_CONTROLLER approves it; on both stacks but booked-trade:
#   proposed-fx-policy  an FX pricing policy version PROPOSED - the runbook's version 1: a different FX controller, the console's,
#                     approves it -> FX_POLICY_VERSION_ID, FX_POLICY_PROPOSER_ID
#   proposed-corridor-policy  a corridor policy version PROPOSED - the runbook's version 1: the console's FX controller approves it
#                     -> CORRIDOR_POLICY_VERSION_ID, CORRIDOR_POLICY_PROPOSER_ID
#   fx-enable-request  the pair BHD-JPY disabled and its enabling proposed: the console's FX controller approves it
#                     -> FX_ENABLE_REQUEST_ID, FX_ENABLE_SUBJECT (PAIR:BHD-JPY), FX_ENABLE_PROPOSER_ID
#   corridor-enable-request  the corridor GBP-USD-US disabled and its enabling proposed: the console's FX controller approves it
#                     -> CORRIDOR_ENABLE_REQUEST_ID, CORRIDOR_ENABLE_CORRIDOR (GBP-USD-US), CORRIDOR_ENABLE_PROPOSER_ID
#   fx                each of the four once
#   booked-trade      KIND ONLY, and only where the rehearsal named an FX provider and a reference feed. A fresh customer's 10.00 EUR
#                     conversion to USD, funded by a 12.50 EUR card top-up and priced from the stack's own reference rate and FX
#                     provider: a BOOKED trade, whose provenance the console's RECONCILIATION_OPERATOR reads -> FX_TRADE_ID
#   kyc-review        a fresh customer, consented to KYC processing, whose case is IN_REVIEW on one sanctions check answered HIT, with
#                     that check's open review task: the console's KYC_REVIEWER resolves the task, then decides the case
#                     -> KYC_CASE_ID, KYC_REVIEW_TASK_ID, KYC_CHECK_ID, KYC_CUSTOMER_ID
#   screening-review  a counterparty screening IN_REVIEW on a HIT, requested by a fresh customer: the console's KYC_REVIEWER releases
#                     or blocks it -> COUNTERPARTY_SCREENING_ID
#   kyc               each of the two once
#
# THE CREDIT SCENARIOS CHANGE THE STACK'S PERSONAL_LOAN POLICY, AND HOLD ONE VERSION PER PRODUCT PENDING. No test stack has a credit
# bureau or a financial-data provider - the platform refuses either's URL until it holds what a pull needs (unresolved questions
# #13 and #14) - so a request is scored on absent data. The first credit scenario of a run therefore approves the platform's own
# reference scorecard if none is ACTIVE (its migration proposed it), and two of the seed's officers propose and approve a
# PERSONAL_LOAN policy that reads no source and REFERs every request to an underwriter (Seed.java's referringPolicy). A product holds
# one PROPOSED version at a time, so before proposing, the seed withdraws through the rejection door the version an earlier run or
# the platform's migration left pending; two runs at once on one stack would withdraw each other's - run them one after another,
# as the console's harness does.
#
# NOT SEEDED: A LOAN, A BNPL AGREEMENT, AND WHAT HANGS ON THEM. No running finapp can approve credit: every policy bounds exposure by a
# rule that reads the bureau's total balance, so without a bureau an automatic approval falls back to REFER or DECLINE, and a
# person's approval is refused (credit.ExposureUnassessable). No loan offer, loan or BNPL agreement can exist, and so neither a
# loan's posted repayment and proposed waiver, nor an agreement's proposed write-off, nor a BNPL dispute awaiting resolution. A
# waiver and a write-off would also need time to pass (a billed instalment, a default); and planting the chain would write loans,
# schedules and postings no financial event made. They wait for a bureau (#13); until then the console tests those acts against its
# own stubs (P17-TSK-117's record).
#
# THE FX SCENARIOS CHANGE THE STACK'S PRICING AND CORRIDOR POLICIES, AND DISABLE ONE PAIR AND ONE CORRIDOR. The first FX scenario of
# a run makes two FX controllers of the seed's and, when no pricing policy or no corridor policy is ACTIVE, one proposes the runbook's
# version 1 (OPERATIONS_RUNBOOK section 2; Seed.java's fxPricingPolicy and corridorPolicy) and the other approves it, so a proposal the
# console approves retires a version in force, and a stack's pricing and corridors never narrow - every proposal is version 1 again.
# One pricing version and one corridor version can be PROPOSED at a time, platform-wide, and one enable request per subject, so
# before proposing the seed withdraws through the rejection door what an earlier run left pending; two runs at once on one stack
# would withdraw each other's - run them one after another, as the console's harness does. The enable requests' subjects, the pair
# BHD-JPY and the corridor GBP-USD-US, are used by nothing else seeded: each stays disabled until the console approves its enabling.
#
# THE BOOKED TRADE NEEDS AN FX PROVIDER AND A REFERENCE FEED, AND ONLY KIND'S FX SCOPES HAVE THEM. A conversion is priced from a
# reference rate the platform fetched within the last 120 seconds and the provider's own quote - no rate is ever typed, and the
# trade is booked with its entry - so nothing can plant one. A local stack configures neither; the kind rehearsal names both, its
# simulator behind each, only under REHEARSAL_SCOPE signals, incidents or experiments. booked-trade is refused on compose, and on a
# kind stack whose Secret (finapp-app-secrets) holds no FINAPP_FX_PROVIDER_URL or no FINAPP_FX_REFERENCE_URL - judged before anything
# is touched, by their presence alone. The customer is made ACTIVE and funded as the dispute's payer is.
#
# NOT SEEDED: A CROSS-BORDER PAYMENT, AND SO ITS TRACE. A payment across a border is screened and leaves on a corridor rail, and no test
# stack configures a corridor provider or a verification provider - neither compose nor any kind scope - so none can be made; a
# planted one would book an outflow no customer made. Its trace waits for a stack with both; until then the console tests the trace
# against its own stubs (P17-TSK-119's record).
#
# THE KYC SCENARIOS ARE PLANTED: NO TEST STACK RUNS A CASE'S CHECKS OR SCREENS A COUNTERPARTY. A case's checks are run by no
# production path and asked of a verification provider no test stack configures; a screening is requested only by a cross-border
# beneficiary's registration, which needs a corridor provider as well. So both review states are planted as the platform leaves them
# (kyc-review.sql, counterparty-screening.sql) - new kyc rows only, on both stacks - while the customers and the consent go through
# their doors. Everything the console does to them is the platform's own doors, as E2eSeedKycDatabaseTest proves.
#
# NOT SEEDED YET: THE KYC CUSTOMER'S ACCOUNT, TRANSFERS AND CONSENT HISTORY (P17-TSK-121). They are read through the operator doors
# P17-TSK-026 and P17-TSK-027 add, which this script's line does not have yet; they follow once both are merged.
#
# THE DISPUTE NEEDS THE CARD PSP, AND ONLY KIND HAS ONE. A local stack configures no card or tokenisation provider, so no card payment
# exists there; open-dispute is refused on compose. On kind the rehearsal's simulator is the card PSP. A dispute is the network's word
# alone: the seed hands the simulator the PSP's statement of a chargeback on the top-up's capture (kubectl exec, its pod's
# /tmp/disputes), then delivers the PSP's webhook that the dispute exists, signed with the stack's payment webhook key read from its
# Secret as the rehearsal's load client reads it; the platform asks the simulator for the statement, and that answer opens the
# dispute and posts the chargeback - nothing here writes a dispute or a posting. The payer's KYC verdict, which no door reaches on a
# test stack (no verification provider), is planted (customer-verdict.sql), as the rehearsal makes its customers ACTIVE.
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
SCENARIOS="abandoned-event, parked-record, parsed-file, fee-break, factorless-holding, sod-conflict, open-alert, investigated-case, overridable-assessment, proposed-policy, all, closable-period, prior-period-adjustment, proposed-mapping, draft-submission, accounting-break, finance, onboardable-organisation, active-merchant, proposed-destination, merchants, open-dispute, review-case, proposed-credit-policy, proposed-lending-terms, proposed-bnpl-terms, credit, proposed-fx-policy, proposed-corridor-policy, fx-enable-request, corridor-enable-request, fx, booked-trade, kyc-review, screening-review or kyc"

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
        onboardable-organisation) ;;
        active-merchant|proposed-destination|merchants) HTTP=yes ;;
        review-case|proposed-credit-policy|proposed-lending-terms|proposed-bnpl-terms|credit) HTTP=yes ;;
        proposed-fx-policy|proposed-corridor-policy|fx-enable-request|corridor-enable-request|fx) HTTP=yes ;;
        kyc-review|screening-review|kyc) HTTP=yes ;;
        booked-trade)
            HTTP=yes
            [ "$TARGET" = kind ] || refuse "booked-trade needs an FX provider and a reference feed, and only kind's FX scopes have them (its simulator): a local stack configures neither, so no conversion can be priced"
            ;;
        open-dispute)
            HTTP=yes
            [ "$TARGET" = kind ] || refuse "open-dispute needs a card PSP, and only kind has one (its simulator): a local stack configures no card provider, so no card payment exists to dispute"
            ;;
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

# ---------------------------------------------------------------------------------------------------
# The merchant desks' rows (P17-TSK-115).

# kube [arguments]: the kind cluster's kubectl, on its control-plane node.
kube() {
    docker exec -i "$E2E_SEED_NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf "$@"
}

# The run's merchant administrator (MERCHANT_ADMINISTRATOR): the seed's onboarder and proposer. The console's is another identity.
MERCHANT_READY=no
merchant_desks() {
    [ "$MERCHANT_READY" = no ] || return 0
    administrator
    onboarder=$(operator onboarder)
    ONBOARDER_ID=${onboarder%% *}
    seed_http grant admin MERCHANT_ADMINISTRATOR "$ONBOARDER_ID" >/dev/null
    say "the run's merchant administrator: $ONBOARDER_ID"
    MERCHANT_READY=yes
}

# organisation: plants a KYB-verified organisation (merchant-organisation.sql). Prints its party's id.
organisation() {
    planted=$(plant merchant-organisation.sql | tr -d '[:space:]')
    uuid_like "$planted" || { say "the organisation's plant printed '$planted'"; exit 1; }
    printf '%s\n' "$planted"
}

# merchant: such an organisation onboarded by the seed's administrator, with an API key. Prints the merchant's id and its key's id.
merchant() {
    organised=$(organisation)
    onboarded=$(value MERCHANT_ID "$(seed_http onboard-merchant onboarder "$organised")")
    uuid_like "$onboarded" || { say "the onboarding printed '$onboarded'"; exit 1; }
    keyed=$(value MERCHANT_KEY_ID "$(seed_http issue-key onboarder "$onboarded")")
    [ -n "$keyed" ] || { say "the API key's issue printed no key id"; exit 1; }
    printf '%s %s\n' "$onboarded" "$keyed"
}

seed_onboardable_organisation() {
    organised=$(organisation)
    say "organisation $organised: KYB-verified, no merchant yet - onboard it"
    printf 'MERCHANT_PARTY_ID=%s\n' "$organised"
}

seed_active_merchant() {
    merchant_desks
    made=$(merchant)
    say "merchant ${made%% *} ACTIVE with API key ${made#* }, onboarded by $ONBOARDER_ID: suspend it"
    printf 'MERCHANT_ID=%s\n' "${made%% *}"
    printf 'MERCHANT_KEY_ID=%s\n' "${made#* }"
}

seed_proposed_destination() {
    merchant_desks
    made=$(merchant)
    owner=${made%% *}
    reference="pdr_$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
    printf '%s' "$reference" | grep -Eq '^pdr_[0-9a-f]{12}$' || { say "a destination reference came out '$reference'"; exit 1; }
    uuid_like "$owner" && uuid_like "$ONBOARDER_ID" || { say "the destination's plant was handed '$owner' by '$ONBOARDER_ID'"; exit 1; }
    proposed=$(plant payout-destination.sql -v merchant="$owner" -v proposer="$ONBOARDER_ID" -v reference="$reference" | tr -d '[:space:]')
    uuid_like "$proposed" || { say "the destination's plant printed '$proposed'"; exit 1; }
    say "payout destination $proposed of merchant $owner PROPOSED by $ONBOARDER_ID: an administrator other than its proposer approves it"
    printf 'PAYOUT_DESTINATION_ID=%s\n' "$proposed"
    printf 'DESTINATION_MERCHANT_ID=%s\n' "$owner"
    printf 'DESTINATION_PROPOSER_ID=%s\n' "$ONBOARDER_ID"
}

# Kind only: refused on compose before anything was touched.
seed_open_dispute() {
    administrator
    payer_login=$(value LOGIN "$(seed_http register-bare payer)")
    payer=$(identity_of "$payer_login")
    uuid_like "$payer" || { say "the payer's id came out '$payer'"; exit 1; }
    verdict=$(plant customer-verdict.sql -v identity="$payer" | tr -d '[:space:]')
    uuid_like "$verdict" || { say "the payer's verdict printed '$verdict'"; exit 1; }
    intent=$(value PAYMENT_ID "$(seed_http top-up payer)")
    uuid_like "$intent" || { say "the top-up printed '$intent'"; exit 1; }
    operation=$(psql_seed -c "SELECT capture_reference FROM payments.payment_attempt WHERE intent_id = '$intent' AND status = 'CAPTURED'" | tr -d '[:space:]')
    printf '%s' "$operation" | grep -Eq '^[A-Za-z0-9-]{1,64}$' || { say "the top-up's capture reference came out '$operation'"; exit 1; }
    dispute="dp_e2e_$(od -An -N6 -tx1 /dev/urandom | tr -d ' \n')"
    printf '%s' "$dispute" | grep -Eq '^dp_e2e_[0-9a-f]{12}$' || { say "a dispute reference came out '$dispute'"; exit 1; }
    seed_http dispute-statement "$operation" "$dispute" 1250
    # The simulator's statement of the chargeback, which the platform's inquiry reads.
    kube -n finapp-rehearsal exec -i deploy/finapp-simulator -- sh -c 'mkdir -p /tmp/disputes && cat > "/tmp/disputes/$1"' statement "$dispute" \
        < "$STATE/dispute-$dispute.json"
    # The provider's signing key as the stack holds it, in the run's state: removed with the run, never printed.
    kube -n finapp get secret finapp-app-secrets -o jsonpath='{.data.FINAPP_PAYMENTS_WEBHOOK_KEY}' | base64 -d > "$STATE/provider-key"
    [ -s "$STATE/provider-key" ] || { say "the stack's Secret holds no payment webhook key"; exit 1; }
    seed_http dispute-hint "$operation" "$dispute"
    opened=
    for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        opened=$(psql_seed -c "SELECT id FROM payments.dispute WHERE provider_dispute_reference = '$dispute' AND stage = 'CHARGED_BACK'" | tr -d '[:space:]')
        uuid_like "$opened" && break
        sleep 2
    done
    uuid_like "$opened" || { say "no CHARGED_BACK dispute $dispute after the hint: is the simulator answering /disputes/?"; exit 1; }
    say "dispute $opened CHARGED_BACK on top-up $intent: upload evidence and represent it, or accept it"
    printf 'DISPUTE_ID=%s\n' "$opened"
    printf 'DISPUTE_PAYMENT_ID=%s\n' "$intent"
}

# ---------------------------------------------------------------------------------------------------
# The credit desks' rows (P17-TSK-117).

# The platform's reference scorecard, RETAIL_SCORECARD v1, as credit's migration V006 proposed it.
REFERENCE_SCORECARD=0190a1b2-5c0e-7000-8000-00000000c001

# pending <table> <product>: the product's one PROPOSED version, or nothing. Both arguments are this script's own literals.
pending() {
    found=$(psql_seed -c "SELECT id FROM $1 WHERE product = '$2' AND status = 'PROPOSED'" | tr -d '[:space:]')
    [ -z "$found" ] || uuid_like "$found" || { say "a pending $2 version came out '$found'"; exit 1; }
    printf '%s\n' "$found"
}

# withdraw <who> <kind> <table> <product>: the product's version left PROPOSED, rejected through the door by the seed's officer.
withdraw() {
    left=$(pending "$3" "$4")
    [ -z "$left" ] || { seed_http decide-version "$1" "$2" "$left" rejection >/dev/null; say "withdrew $4's pending $2 version $left"; }
}

# The run's credit desks: two credit policy officers - the seed's PERSONAL_LOAN policy proposed by one and approved by the other (four
# eyes) - the first of whom approves the reference scorecard when no scorecard is ACTIVE.
CREDIT_READY=no
credit_desks() {
    [ "$CREDIT_READY" = no ] || return 0
    administrator
    officer=$(operator creditofficer)
    CREDIT_OFFICER_ID=${officer%% *}
    approver=$(operator creditapprover)
    CREDIT_APPROVER_ID=${approver%% *}
    seed_http grant admin CREDIT_POLICY_OFFICER "$CREDIT_OFFICER_ID" "$CREDIT_APPROVER_ID" >/dev/null
    scored=$(psql_seed -c "SELECT count(*) FROM credit.scorecard_model_version WHERE family = 'RETAIL_SCORECARD' AND status = 'ACTIVE'" | tr -d '[:space:]')
    if [ "$scored" = 0 ]; then
        seed_http approve-scorecard creditofficer "$REFERENCE_SCORECARD" >/dev/null
        say "approved the platform's reference scorecard $REFERENCE_SCORECARD"
    fi
    withdraw creditofficer credit-policy credit.credit_policy_version PERSONAL_LOAN
    proposed=$(value VERSION_ID "$(seed_http propose-version creditofficer credit-policy seed)")
    uuid_like "$proposed" || { say "the seed's credit policy proposal printed '$proposed'"; exit 1; }
    seed_http decide-version creditapprover credit-policy "$proposed" approval >/dev/null
    say "the run's credit desks: officers $CREDIT_OFFICER_ID and $CREDIT_APPROVER_ID; PERSONAL_LOAN version $proposed ACTIVE, REFERring every request"
    CREDIT_READY=yes
}

seed_review_case() {
    credit_desks
    borrower_login=$(value LOGIN "$(seed_http register borrower)")
    borrower=$(identity_of "$borrower_login")
    uuid_like "$borrower" || { say "the borrower's id came out '$borrower'"; exit 1; }
    verdict=$(plant customer-verdict.sql -v identity="$borrower" | tr -d '[:space:]')
    uuid_like "$verdict" || { say "the borrower's verdict printed '$verdict'"; exit 1; }
    request=$(value CREDIT_REQUEST_ID "$(seed_http credit-request borrower)")
    uuid_like "$request" || { say "the credit request printed '$request'"; exit 1; }
    opened=
    tries=0
    while [ "$tries" -lt 60 ]; do
        opened=$(psql_seed -c "SELECT id FROM credit.underwriting_case WHERE decision_request_id = '$request' AND status = 'OPEN'" | tr -d '[:space:]')
        uuid_like "$opened" && break
        tries=$((tries + 1))
        sleep 2
    done
    uuid_like "$opened" || { say "no OPEN review case for credit request $request: is the platform's credit progress sweep running?"; exit 1; }
    say "review case $opened OPEN on credit request $request (PERSONAL_LOAN, 5,000.00 EUR, REFERred): an underwriter takes it and declines it"
    printf 'REVIEW_CASE_ID=%s\n' "$opened"
    printf 'REVIEW_CASE_REQUEST_ID=%s\n' "$request"
}

seed_proposed_credit_policy() {
    credit_desks
    pending_policy=$(value VERSION_ID "$(seed_http propose-version creditofficer credit-policy proposal)")
    uuid_like "$pending_policy" || { say "the credit policy proposal printed '$pending_policy'"; exit 1; }
    say "PERSONAL_LOAN policy version $pending_policy PROPOSED by officer $CREDIT_OFFICER_ID: an officer other than its proposer approves it"
    printf 'CREDIT_POLICY_VERSION_ID=%s\n' "$pending_policy"
    printf 'CREDIT_POLICY_PROPOSER_ID=%s\n' "$CREDIT_OFFICER_ID"
}

# terms_officer <who> <role>: a fresh officer of the seed's holding <role>, granted by the run's administrator (made first, outside
# this command substitution, whose variables the caller never sees). Prints its id.
terms_officer() {
    made=$(operator "$1")
    seed_http grant admin "$2" "${made%% *}" >/dev/null
    printf '%s\n' "${made%% *}"
}

seed_proposed_lending_terms() {
    administrator
    lender=$(terms_officer lendingofficer LENDING_OFFICER)
    withdraw lendingofficer lending-terms lending.loan_terms_version PERSONAL_LOAN
    pending_terms=$(value VERSION_ID "$(seed_http propose-version lendingofficer lending-terms proposal)")
    uuid_like "$pending_terms" || { say "the lending terms proposal printed '$pending_terms'"; exit 1; }
    say "PERSONAL_LOAN terms $pending_terms PROPOSED by lending officer $lender: a lending officer other than its proposer approves them"
    printf 'LENDING_TERMS_VERSION_ID=%s\n' "$pending_terms"
    printf 'LENDING_TERMS_PROPOSER_ID=%s\n' "$lender"
}

seed_proposed_bnpl_terms() {
    administrator
    bnpl=$(terms_officer bnplofficer BNPL_OFFICER)
    withdraw bnplofficer bnpl-terms bnpl.bnpl_terms_version BNPL_PAY_IN_4
    pending_terms=$(value VERSION_ID "$(seed_http propose-version bnplofficer bnpl-terms proposal)")
    uuid_like "$pending_terms" || { say "the BNPL terms proposal printed '$pending_terms'"; exit 1; }
    say "BNPL_PAY_IN_4 terms $pending_terms PROPOSED by BNPL officer $bnpl: a BNPL officer other than its proposer approves them"
    printf 'BNPL_TERMS_VERSION_ID=%s\n' "$pending_terms"
    printf 'BNPL_TERMS_PROPOSER_ID=%s\n' "$bnpl"
}

# ---------------------------------------------------------------------------------------------------
# The FX controller's rows (P17-TSK-119).

# The enable requests' subjects: a pair and a corridor nothing else seeded uses.
ENABLE_PAIR=BHD-JPY
ENABLE_CORRIDOR=GBP-USD-US

# proposed_one <table>: the one version PROPOSED platform-wide, or nothing. The table is this script's own literal.
proposed_one() {
    found=$(psql_seed -c "SELECT id FROM $1 WHERE status = 'PROPOSED'" | tr -d '[:space:]')
    [ -z "$found" ] || uuid_like "$found" || { say "a pending version in $1 came out '$found'"; exit 1; }
    printf '%s\n' "$found"
}

# withdraw_version <kind> <table>: the version left PROPOSED, rejected through the door by the seed's FX controller.
withdraw_version() {
    left=$(proposed_one "$2")
    [ -z "$left" ] || { seed_http decide-version fxcontroller "$1" "$left" rejection >/dev/null; say "withdrew the pending $1 version $left"; }
}

# in_force <kind> <table>: when no version is ACTIVE, the runbook's version 1 proposed by one of the seed's FX controllers and approved
# by the other (four eyes).
in_force() {
    active=$(psql_seed -c "SELECT count(*) FROM $2 WHERE status = 'ACTIVE'" | tr -d '[:space:]')
    [ "$active" = 0 ] || return 0
    withdraw_version "$1" "$2"
    proposed=$(value VERSION_ID "$(seed_http propose-version fxcontroller "$1" seed)")
    uuid_like "$proposed" || { say "the seed's $1 proposal printed '$proposed'"; exit 1; }
    seed_http decide-version fxapprover "$1" "$proposed" approval >/dev/null
    say "$1 version $proposed ACTIVE: the runbook's version 1"
}

# The run's FX desks: two FX controllers - the second the first's approver - and a pricing and a corridor policy in force.
FX_READY=no
fx_desks() {
    [ "$FX_READY" = no ] || return 0
    administrator
    controller=$(operator fxcontroller)
    FX_CONTROLLER_ID=${controller%% *}
    approver=$(operator fxapprover)
    seed_http grant admin FX_CONTROLLER "$FX_CONTROLLER_ID" "${approver%% *}" >/dev/null
    in_force fx-pricing fx.pricing_policy_version
    in_force corridor-policy crossborder.corridor_policy_version
    say "the run's FX desks: controllers $FX_CONTROLLER_ID and ${approver%% *}"
    FX_READY=yes
}

seed_proposed_fx_policy() {
    fx_desks
    withdraw_version fx-pricing fx.pricing_policy_version
    pending_fx=$(value VERSION_ID "$(seed_http propose-version fxcontroller fx-pricing proposal)")
    uuid_like "$pending_fx" || { say "the FX pricing policy proposal printed '$pending_fx'"; exit 1; }
    say "FX pricing policy version $pending_fx PROPOSED by FX controller $FX_CONTROLLER_ID: an FX controller other than its proposer approves it"
    printf 'FX_POLICY_VERSION_ID=%s\n' "$pending_fx"
    printf 'FX_POLICY_PROPOSER_ID=%s\n' "$FX_CONTROLLER_ID"
}

seed_proposed_corridor_policy() {
    fx_desks
    withdraw_version corridor-policy crossborder.corridor_policy_version
    pending_corridors=$(value VERSION_ID "$(seed_http propose-version fxcontroller corridor-policy proposal)")
    uuid_like "$pending_corridors" || { say "the corridor policy proposal printed '$pending_corridors'"; exit 1; }
    say "corridor policy version $pending_corridors PROPOSED by FX controller $FX_CONTROLLER_ID: an FX controller other than its proposer approves it"
    printf 'CORRIDOR_POLICY_VERSION_ID=%s\n' "$pending_corridors"
    printf 'CORRIDOR_POLICY_PROPOSER_ID=%s\n' "$FX_CONTROLLER_ID"
}

# enable_requested <kind> <subject> <left>: <left>, the subject's enable request an earlier run left pending (one per subject), withdrawn
# through the rejection door; the subject disabled - UNCHANGED when it already is - and its enabling proposed by the seed's FX
# controller. Sets REQUESTED.
enable_requested() {
    [ -z "$3" ] || uuid_like "$3" || { say "a pending enable request of $2 came out '$3'"; exit 1; }
    [ -z "$3" ] || { seed_http decide-enable fxcontroller "$1" "$3" rejection >/dev/null; say "withdrew $2's pending enable request $3"; }
    seed_http disable fxcontroller "$1" "$2" >/dev/null
    REQUESTED=$(value REQUEST_ID "$(seed_http propose-enable fxcontroller "$1" "$2")")
    uuid_like "$REQUESTED" || { say "the enabling of $2 printed '$REQUESTED'"; exit 1; }
}

seed_fx_enable_request() {
    fx_desks
    left=$(psql_seed -c "SELECT id FROM fx.availability_enable_request WHERE subject_kind = 'PAIR' AND subject = '$ENABLE_PAIR' AND status = 'PROPOSED'" | tr -d '[:space:]')
    enable_requested fx-pair "$ENABLE_PAIR" "$left"
    say "pair $ENABLE_PAIR disabled, its enabling $REQUESTED PROPOSED by FX controller $FX_CONTROLLER_ID: an FX controller other than its proposer approves it"
    printf 'FX_ENABLE_REQUEST_ID=%s\n' "$REQUESTED"
    printf 'FX_ENABLE_SUBJECT=PAIR:%s\n' "$ENABLE_PAIR"
    printf 'FX_ENABLE_PROPOSER_ID=%s\n' "$FX_CONTROLLER_ID"
}

seed_corridor_enable_request() {
    fx_desks
    left=$(psql_seed -c "SELECT id FROM crossborder.corridor_enable_request WHERE corridor = '$ENABLE_CORRIDOR' AND status = 'PROPOSED'" | tr -d '[:space:]')
    enable_requested corridor "$ENABLE_CORRIDOR" "$left"
    say "corridor $ENABLE_CORRIDOR disabled, its enabling $REQUESTED PROPOSED by FX controller $FX_CONTROLLER_ID: an FX controller other than its proposer approves it"
    printf 'CORRIDOR_ENABLE_REQUEST_ID=%s\n' "$REQUESTED"
    printf 'CORRIDOR_ENABLE_CORRIDOR=%s\n' "$ENABLE_CORRIDOR"
    printf 'CORRIDOR_ENABLE_PROPOSER_ID=%s\n' "$FX_CONTROLLER_ID"
}

# fx_feeds: booked-trade's FX provider and reference feed, as the stack's Secret delivers them to the application. Their presence is
# read, never printed.
fx_feeds() {
    for name in FINAPP_FX_PROVIDER_URL FINAPP_FX_REFERENCE_URL; do
        delivered=$(kube -n finapp get secret finapp-app-secrets -o jsonpath="{.data.$name}" 2>/dev/null || true)
        [ -n "$delivered" ] || refuse "booked-trade needs the FX provider and the reference feed, and the stack's Secret holds no $name: run the kind rehearsal with REHEARSAL_SCOPE signals, incidents or experiments"
    done
}

# Kind only, with the FX provider and the reference feed: refused before anything was touched.
seed_booked_trade() {
    fx_desks
    trader_login=$(value LOGIN "$(seed_http register-bare trader)")
    trader=$(identity_of "$trader_login")
    uuid_like "$trader" || { say "the trader's id came out '$trader'"; exit 1; }
    verdict=$(plant customer-verdict.sql -v identity="$trader" | tr -d '[:space:]')
    uuid_like "$verdict" || { say "the trader's verdict printed '$verdict'"; exit 1; }
    funded=$(value PAYMENT_ID "$(seed_http top-up trader)")
    uuid_like "$funded" || { say "the trader's top-up printed '$funded'"; exit 1; }
    trade=$(value FX_TRADE_ID "$(seed_http fx-trade trader)")
    uuid_like "$trade" || { say "the conversion printed '$trade'"; exit 1; }
    say "FX trade $trade BOOKED: 10.00 EUR to USD by customer $trader, funded by top-up $funded - read its provenance"
    printf 'FX_TRADE_ID=%s\n' "$trade"
}

# A fresh customer, consented through the consent door, and its case planted IN_REVIEW on a HIT with the check's open task.
seed_kyc_review() {
    administrator
    applicant_login=$(value LOGIN "$(seed_http register-bare applicant)")
    applicant=$(identity_of "$applicant_login")
    uuid_like "$applicant" || { say "the applicant's id came out '$applicant'"; exit 1; }
    consented=$(value CONSENTED "$(seed_http kyc-consent applicant)")
    [ "$consented" = KYC_PROCESSING ] || { say "the applicant's consent printed '$consented'"; exit 1; }
    task=$(plant kyc-review.sql -v identity="$applicant" | tr -d '[:space:]')
    uuid_like "$task" || { say "the case's plant printed '$task'"; exit 1; }
    reviewed=$(psql_seed -c "SELECT case_id FROM kyc.review_task WHERE id = '$task'" | tr -d '[:space:]')
    hit=$(psql_seed -c "SELECT check_id FROM kyc.review_task WHERE id = '$task'" | tr -d '[:space:]')
    uuid_like "$reviewed" && uuid_like "$hit" || { say "the task's case and check came out '$reviewed' '$hit'"; exit 1; }
    customer=$(psql_seed -c "SELECT customer_id FROM kyc.kyc_case WHERE id = '$reviewed'" | tr -d '[:space:]')
    uuid_like "$customer" || { say "the case's customer came out '$customer'"; exit 1; }
    say "KYC case $reviewed IN_REVIEW for customer $customer: sanctions check $hit HIT, review task $task OPEN - resolve it, then decide"
    printf 'KYC_CASE_ID=%s
KYC_REVIEW_TASK_ID=%s
KYC_CHECK_ID=%s
KYC_CUSTOMER_ID=%s
' "$reviewed" "$task" "$hit" "$customer"
}

# A screening planted IN_REVIEW on a HIT, requested by a fresh customer - so the console's reviewer is another person.
seed_screening_review() {
    administrator
    remitter_login=$(value LOGIN "$(seed_http register-bare remitter)")
    remitter=$(identity_of "$remitter_login")
    uuid_like "$remitter" || { say "the remitter's id came out '$remitter'"; exit 1; }
    screening=$(plant counterparty-screening.sql -v requester="$remitter" | tr -d '[:space:]')
    uuid_like "$screening" || { say "the screening's plant printed '$screening'"; exit 1; }
    say "counterparty screening $screening IN_REVIEW on a HIT, requested by customer $remitter - release or block it"
    printf 'COUNTERPARTY_SCREENING_ID=%s
' "$screening"
}

# booked-trade's provider and feed, judged before any scenario touches the stack.
for scenario in "$@"; do
    [ "$scenario" != booked-trade ] || fx_feeds
done

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
        onboardable-organisation) seed_onboardable_organisation ;;
        active-merchant) seed_active_merchant ;;
        proposed-destination) seed_proposed_destination ;;
        merchants) seed_onboardable_organisation; seed_active_merchant; seed_proposed_destination ;;
        open-dispute) seed_open_dispute ;;
        review-case) seed_review_case ;;
        proposed-credit-policy) seed_proposed_credit_policy ;;
        proposed-lending-terms) seed_proposed_lending_terms ;;
        proposed-bnpl-terms) seed_proposed_bnpl_terms ;;
        credit) seed_review_case; seed_proposed_credit_policy; seed_proposed_lending_terms; seed_proposed_bnpl_terms ;;
        proposed-fx-policy) seed_proposed_fx_policy ;;
        proposed-corridor-policy) seed_proposed_corridor_policy ;;
        fx-enable-request) seed_fx_enable_request ;;
        corridor-enable-request) seed_corridor_enable_request ;;
        fx) seed_proposed_fx_policy; seed_proposed_corridor_policy; seed_fx_enable_request; seed_corridor_enable_request ;;
        booked-trade) seed_booked_trade ;;
        kyc-review) seed_kyc_review ;;
        screening-review) seed_screening_review ;;
        kyc) seed_kyc_review; seed_screening_review ;;
        all) seed_abandoned_event; seed_parked_record; seed_parsed_file; seed_fee_break; seed_factorless_holding; seed_sod_conflict
             seed_open_alert; seed_investigated_case; seed_overridable_assessment; seed_proposed_policy ;;
    esac
done
