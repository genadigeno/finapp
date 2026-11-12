#!/bin/sh
# Check the alert rules and the routing (P15-TSK-025, ADR-0124).
#
# Four checks over infra/prometheus and the Alertmanager configurations:
#
#   promtool check config   the scrape configuration and every rule it loads
#   promtool test rules     every infra/prometheus/tests/*.test.yml: each rule fires on its planted series and is silent when
#                           healthy, over two instances
#   amtool check-config     compose's configuration and the one deploy/ carries
#   amtool config routes    a page reaches the paging receiver and a ticket the ticket receiver
#
# THEN THREE CHECKS AGAIN, OVER A PLANT THEY MUST REFUSE. A check that is wired but cannot fail reads as assurance it does not
# give, so each run copies what it checks, plants one defect per copy and requires the check to fail and to name it:
#
#   no-trial-balance   the alert LedgerTrialBalanceNonZero removed   promtool test rules, naming the alert
#   bad-routing        a route naming a receiver that does not exist amtool check-config
#   offset-term        TelemetryExportFailing's "new series" term    promtool test rules, naming the alert: its silent cases
#                      written as `x offset 10m` (P15-TSK-032)       (an old count through a staleness marker and a gap) fire
#
# SAME BYTES HERE AND IN CI: both tools are images pinned by digest in infra/scanner-pins.sh. PROMETHEUS_IMAGE and
# ALERTMANAGER_IMAGE override an image for a machine whose TLS is intercepted (dependency-scan.sh allows the same); CI never sets them.
#
# Nothing here changes the tree: the directories are mounted read-only, the plants live in the container, and it needs no
# credential.
#
# Usage:
#   infra/scripts/alert-rules-check.sh
#
# Exit status: 0 every check green and every plant refused for its reason; 1 otherwise; 2 no Docker.
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ROOT=$(CDPATH= cd -- "$DIR/.." && pwd)
# shellcheck disable=SC1091
. "$DIR/scanner-pins.sh"

PROMETHEUS_IMAGE=${PROMETHEUS_IMAGE:-$PROMETHEUS_REPOSITORY:$PROMETHEUS_VERSION@$PROMETHEUS_DIGEST}
ALERTMANAGER_IMAGE=${ALERTMANAGER_IMAGE:-$ALERTMANAGER_REPOSITORY:$ALERTMANAGER_VERSION@$ALERTMANAGER_DIGEST}

command -v docker >/dev/null 2>&1 || { echo "!! docker is required" >&2; exit 2; }

# MSYS_NO_PATHCONV stops Git Bash on Windows rewriting /prom and /am into host paths. Unset and harmless elsewhere.
prometheus() {
    MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh \
        -v "$DIR/prometheus:/prom:ro" \
        "$PROMETHEUS_IMAGE" -c "$1"
}

alertmanager() {
    MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/sh \
        -v "$DIR/alertmanager:/am:ro" \
        -v "$ROOT/deploy/observability/alertmanager:/deployed:ro" \
        "$ALERTMANAGER_IMAGE" -c "$1"
}

echo "== promtool check config (and every rule it loads)"
prometheus 'cd /prom && promtool check config prometheus.yml'

echo "== promtool test rules (every test file)"
prometheus 'cd /prom/tests && promtool test rules *.test.yml'

echo "== amtool check-config (compose and deploy/)"
alertmanager 'amtool check-config /am/alertmanager.yml /deployed/alertmanager.yml'

echo "== amtool: a page and a ticket reach their receivers"
alertmanager '
    for config in /am/alertmanager.yml /deployed/alertmanager.yml; do
        page=$(amtool config routes test --config.file="$config" severity=page)
        ticket=$(amtool config routes test --config.file="$config" severity=ticket)
        [ "$page" = placeholder-page ] || { echo "!! severity=page reached [$page] in $config"; exit 1; }
        [ "$ticket" = placeholder-ticket ] || { echo "!! severity=ticket reached [$ticket] in $config"; exit 1; }
        echo "ok $config"
    done
'

echo "== the plants (each must be refused, for its reason)"
prometheus '
    rm -rf /tmp/plant && cp -R /prom /tmp/plant
    awk "/- alert: LedgerTrialBalanceNonZero/ { skip=1; next } /- alert: / { skip=0 } !skip" /prom/rules/correctness.yml > /tmp/plant/rules/correctness.yml
    if cmp -s /prom/rules/correctness.yml /tmp/plant/rules/correctness.yml; then
        echo "!!   plant no-trial-balance was not planted: its rule is gone or renamed"; exit 1
    fi
    cd /tmp/plant/tests
    if output=$(promtool test rules correctness.test.yml 2>&1); then
        echo "!!   plant no-trial-balance was ACCEPTED: promtool passed with the trial-balance rule removed"; exit 1
    fi
    case "$output" in
        *LedgerTrialBalanceNonZero*) echo "ok no-trial-balance refused by promtool test rules" ;;
        *) echo "!!   plant no-trial-balance refused for another reason:"; echo "$output"; exit 1 ;;
    esac
'
prometheus '
    rm -rf /tmp/plant && cp -R /prom /tmp/plant
    sed "s/unless present_over_time(otelcol_exporter_send_failed_spans\[1h\] offset 10m)/unless otelcol_exporter_send_failed_spans offset 10m/" /prom/rules/telemetry.yml > /tmp/plant/rules/telemetry.yml
    if cmp -s /prom/rules/telemetry.yml /tmp/plant/rules/telemetry.yml; then
        echo "!!   plant offset-term was not planted: the term is gone or rewritten"; exit 1
    fi
    cd /tmp/plant/tests
    if output=$(promtool test rules telemetry.test.yml 2>&1); then
        echo "!!   plant offset-term was ACCEPTED: promtool passed with an offset term that pages an old count"; exit 1
    fi
    case "$output" in
        *TelemetryExportFailing*) echo "ok offset-term refused by promtool test rules" ;;
        *) echo "!!   plant offset-term refused for another reason:"; echo "$output"; exit 1 ;;
    esac
'
alertmanager '
    sed "s/receiver: placeholder-page/receiver: nobody-of-that-name/" /am/alertmanager.yml > /tmp/plant.yml
    if cmp -s /am/alertmanager.yml /tmp/plant.yml; then
        echo "!!   plant bad-routing was not planted: its route is gone or renamed"; exit 1
    fi
    if output=$(amtool check-config /tmp/plant.yml 2>&1); then
        echo "!!   plant bad-routing was ACCEPTED: amtool passed a route to a receiver that does not exist"; exit 1
    fi
    case "$output" in
        *nobody-of-that-name*) echo "ok bad-routing refused by amtool check-config" ;;
        *) echo "!!   plant bad-routing refused for another reason:"; echo "$output"; exit 1 ;;
    esac
'

echo "All checks green, every plant refused."
