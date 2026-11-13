#!/bin/sh
# The restore rehearsal (P15-TSK-030; OPERATIONS_RUNBOOK.md section 11.35; THREAT_MODEL.md T24, T32): a logical backup of a
# PostgreSQL holding the platform's books, restored into a fresh PostgreSQL, and proven financially whole by the platform's own checks
# before any role the platform runs as can log in to it. Then each kind of corruption, planted in a restore of its own, refused by the
# check built to see it and by no other.
#
# THE FIRST RULE: NO MONEY MOVES UNTIL THE VERIFICATION PASSES. Every schedule, consumer, the outbox relay and every door reach the
# database only as finapp_app (and report generation as finapp_reporting). So a restored database's three platform roles are created
# NOLOGIN, in the same transaction as the role step, before the backup is restored into it; the run asserts that each is refused at
# log-in; and only a verification that passed whole releases them. A replica pointed at the database before that cannot open a
# connection: readiness is down and the schema-version hold never proves READY, so its schedules, relay and consumers do no work.
#
# WHAT A GREEN RUN SAYS. The source, whole by the same checks before it is backed up. The backup: pg_dump in one exported snapshot, and
# the manifest read in that same session (every table's row count, every sequence's value, each schema history's head, the snapshot's
# instant, the roles' attributes and settings, the database's locale and grants, the definer functions, the TRUNCATE refusals, the
# UNLOGGED tables, the grants, the schema), both encrypted at rest under the one key generated for the run. The restore, held: the
# transaction counter moved past every restored xid8 (ledger V034's txid, the census's head), flywayValidate, the counts, the catalogue
# and the schema equal to the manifest's, every sequence at or past it, and RestoreVerification: the trial balance per currency, GL =
# ledger for every period, the projection check, every product proof and the source-join census over the whole journal, PASS. Then the
# release, and a log-in.
#
# THE SOURCE (RESTORE_SOURCE). `seeded`, the default and what CI runs: a container migrated and seeded here through the platform's own
# services, verified by the same checks before it is backed up. `kind:<node>`: the PostgreSQL of a kind rehearsal's cluster left up
# after its workload (REHEARSAL_KEEP=1), read from inside its pod as the overlay's superuser over the pod's own socket; nothing here
# can reach it from the host, so it is not verified before the backup, and its default plant is the phase gate's alone.
#
# THE PLANTS (RESTORE_PLANTS, default all), each in a restore of its own, each required to fail exactly the checks named:
#   unbalanced-entry      one line of a two-person adjustment raised by one minor unit, its proposal line and its balance row with
#                         it, so the census and the projection agree and only the trial balance can see it
#   balance-disagrees     one wallet's balance row moved by one minor unit: the projection check alone
#   missing-row           one audit record deleted: the row counts alone (no financial check reads the audit trail)
#   grant-dropped         finapp_app's INSERT on the journal's lines revoked: the catalogue and the schema, each from its own side
#   role-altered          finapp_app's connection limit changed, a cluster object pg_dump never carries: the catalogue alone
#   trigger-dropped       the journal entry's append-only trigger dropped, a row trigger and so no TRUNCATE refusal: the schema alone
#   sequence-behind       the most advanced sequence set back to its start: the sequences alone (no unique index stands behind every
#                         value a sequence hands out, so a sequence restored behind is not refused by the first insert)
#   history-checksum      one migration's checksum changed in a schema's history: flywayValidate alone
#   gl-rule-repointed     the active GL mapping's customer-wallet rule moved to a key no ledger account has: GL = ledger alone
#   counter-not-moved     the restore's counter step skipped: the source-join census alone, which then cannot see the journal
#   journal-line-removed  one line of a transfer's entry deleted, the phase gate's own plant (PHASE_GATES.md section 5, Phase 15,
#                         criterion 6): the counts, the trial balance, the projection and the census, each from its own side
# A planted restore is never released.
#
# WHAT IT NEVER DOES. It holds no credential beyond the run: every password and the backup key are generated for it, and the key and
# the administrator's password file live in a temporary directory outside the repository, which the exit trap removes with the
# encrypted backup in it. No password is on a command line: Docker takes each from this shell's environment (--env NAME, no value),
# the servers read the administrator's from that file, so `docker inspect` shows a path, and Gradle and Java read the environment. The
# dump never reaches a disk unencrypted: pg_dump writes to a pipe into openssl, and the restore reads openssl's output from a pipe. The
# manifest's plaintext exists only while it is read: in the source's /tmp until it is copied out, which removes it there, and in the
# work directory while it is encrypted and while one comparison reads it, each time removed after. It talks to no cloud.
#
# THE MANAGED HALF (not rehearsed here): a managed restore is a point-in-time restore into a new instance (infra/terraform/modules/
# postgres, its README's "Operating it"), which carries the transaction counter; section 11.35 says what it needs.
#
# Usage:   infra/scripts/restore-rehearsal.sh
# Needs:   Docker, a JDK for Gradle, openssl, od. Artefacts (logs, reports, assertions - never the backup) go to RESTORE_ARTEFACTS
#          (build/restore-rehearsal by default).
# Exit:    0 every assertion held; 1 an assertion failed; 2 a step could not run. A step that cannot run never looks like a pass.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

ART=${RESTORE_ARTEFACTS:-$ROOT/build/restore-rehearsal}
PLANTS=${RESTORE_PLANTS:-unbalanced-entry balance-disagrees missing-row grant-dropped role-altered trigger-dropped sequence-behind history-checksum gl-rule-repointed counter-not-moved journal-line-removed key-version-missing}
PITR_PLANTS=${RESTORE_PITR_PLANTS:-wal-gap wal-damaged}
CHART="$ROOT/infra/scripts/kind-rehearsal/gl-mapping.json"
SOURCE_MODE=${RESTORE_SOURCE:-seeded}
# A seeded source is built to hold every product, so its verifications are a drill's: each proof must also judge a subject (item 44 (a)).
DRILL_ARG=--drill
KIND_NODE=""
case $SOURCE_MODE in
    seeded) ;;
    kind:?*)
        KIND_NODE=${SOURCE_MODE#kind:}
        PLANTS=${RESTORE_PLANTS:-journal-line-removed}
        PITR_PLANTS=""
        DRILL_ARG=""
        ;;
    *) echo "?? RESTORE_SOURCE is seeded or kind:<node>, not $SOURCE_MODE" >&2; exit 2 ;;
esac

mkdir -p "$ART"
: > "$ART/rehearsal.log"
: > "$ART/assertions.tsv"

log() {
    printf '%s %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$ART/rehearsal.log"
}

FAILED=0
pass() {
    log "ok $*"
    printf 'PASS\t%s\n' "$*" >> "$ART/assertions.tsv"
}

fail() {
    log "!! $*"
    printf 'FAIL\t%s\n' "$*" >> "$ART/assertions.tsv"
    FAILED=1
    exit 1
}

cannot() {
    log "?? $*"
    FAILED=2
    exit 2
}

dock() {
    MSYS_NO_PATHCONV=1 docker "$@"
}

# A path as Docker, Java and openssl on this host take it.
dpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
}

secret() {
    od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

# The version the platform declares (the version catalog), by the digest the kind overlay pins for it: the same bytes as the
# rehearsal's cluster runs, read the way kind-rehearsal.sh reads them. A version the overlay does not pin is refused, not guessed.
POSTGRES_VERSION=$(sed -n 's/^postgresImage = "\(.*\)".*$/\1/p' gradle/libs.versions.toml | tr -d '\r')
[ -n "$POSTGRES_VERSION" ] || cannot "postgresImage not found in the version catalog"
POSTGRES_DIGEST=$(sed -n "s/.*postgres:$POSTGRES_VERSION@\(sha256:[0-9a-f]*\).*/\1/p" deploy/overlays/kind/postgres.yaml | head -1)
[ -n "$POSTGRES_DIGEST" ] || cannot "deploy/overlays/kind/postgres.yaml pins no digest for postgres:$POSTGRES_VERSION"
IMAGE="postgres:$POSTGRES_VERSION@$POSTGRES_DIGEST"
ROLES_SCRIPT="$ROOT/infra/postgres/initdb/00-roles.sql"
[ -f "$CHART" ] || cannot "the reference GL chart is missing: $CHART"
# The administrator: the image's own superuser for a seeded source; for a kind source the overlay's, so that every owner and grantor
# the dump names exists on the restore under the same name.
ADMIN=postgres
if [ -n "$KIND_NODE" ]; then
    ADMIN=$(sed -n '/name: POSTGRES_USER$/{n;s/^ *value: *//p;}' deploy/overlays/kind/postgres.yaml | tr -d '\r' | head -1)
    [ -n "$ADMIN" ] || cannot "the kind overlay's POSTGRES_USER was not found"
fi

NAME="finapp-restore-$$-$(date +%s)"
WORK=$(mktemp -d)
chmod 700 "$WORK"
cleanup() {
    status=$?
    dock ps -aq --filter "name=$NAME" 2>/dev/null | while read -r container; do
        dock rm -f -v "$container" >/dev/null 2>&1 || true
    done
    # The WAL archive and its planted copies: volumes of the run's own, holding ciphertext only.
    dock volume ls -q --filter "name=$NAME" 2>/dev/null | while read -r volume; do
        dock volume rm -f "$volume" >/dev/null 2>&1 || true
    done
    rm -rf "$WORK"
    if [ -e "$WORK" ]; then
        log "?? the work directory holding the encrypted backup could not be removed: $WORK"
        status=2
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 2' INT TERM HUP PIPE

# Generated for this run, printed nowhere and never on a command line. One superuser password serves every server: the restore's
# administrator. The servers read it from a file in the work directory; psql reads PGPASSWORD from its environment, which Docker
# copies from this shell's for one command (--env PGPASSWORD with no value).
generated_for_admin=$(secret)
generated_for_app=$(secret)
generated_for_migrator=$(secret)
generated_for_reporting=$(secret)
openssl rand -hex 32 > "$WORK/backup.key"
printf '%s' "${generated_for_admin}" > "$WORK/admin"
chmod 644 "$WORK/admin"
# The WAL archive's own key (point-in-time recovery, OPERATIONS_RUNBOOK.md section 11.55): read by the source's archive_command and by
# a recovering server's restore_command, mounted read-only into each, and removed with the work directory.
openssl rand -hex 32 > "$WORK/archive.key"
chmod 644 "$WORK/archive.key"

step() {
    log "== $1"
}

# admin_dock <docker arguments>: docker with the administrator's password in its environment, for that one command only.
admin_dock() {
    PGPASSWORD="${generated_for_admin}" MSYS_NO_PATHCONV=1 docker "$@"
}

# sql <container> <statement>: as the administrator, over TCP, ON_ERROR_STOP; the answer on stdout.
sql() {
    container=$1
    shift
    answer=$(admin_dock exec --env PGPASSWORD "$container" \
        psql -h 127.0.0.1 -U "$ADMIN" -d finapp -v ON_ERROR_STOP=1 -AtX -c "$*") || return $?
    [ -z "$answer" ] || printf '%s\n' "$answer" | tr -d '\r'
}

# sql_file <container> < file: a script as the administrator, in one transaction.
sql_file() {
    admin_dock exec -i --env PGPASSWORD "$1" \
        psql -h 127.0.0.1 -U "$ADMIN" -d finapp -v ON_ERROR_STOP=1 -AtXq --single-transaction -f -
}

port_of() {
    dock port "$1" 5432/tcp | head -1 | sed 's/.*://' | tr -d '\r'
}

ready() {
    waited=0
    until sql "$1" 'SELECT 1' >/dev/null 2>&1; do
        waited=$((waited + 1))
        [ "$waited" -le 120 ] || cannot "PostgreSQL $1 did not become ready"
        sleep 1
    done
}

# server <container> [archiving]: a throwaway PostgreSQL on a random loopback port, its database `finapp`, its superuser the
# administrator, whose password the image reads from the mounted file. `archiving` is the point-in-time source: every finished WAL
# segment encrypted into the archive volume by ARCHIVE_COMMAND.
server() {
    if [ "${2:-}" = archiving ]; then
        dock run --detach --name "$1" --publish 127.0.0.1::5432 \
            --mount "type=bind,source=$(dpath "$WORK/admin"),target=/run/finapp-admin,readonly" --env POSTGRES_PASSWORD_FILE=/run/finapp-admin \
            --mount "type=volume,source=$ARCHIVE_VOLUME,target=/archive" \
            --mount "type=bind,source=$(dpath "$WORK/archive.key"),target=/run/finapp-archive-key,readonly" \
            --env POSTGRES_USER="$ADMIN" --env POSTGRES_DB=finapp "$IMAGE" -c max_connections=100 \
            -c wal_level=replica -c archive_mode=on -c "archive_command=$ARCHIVE_COMMAND" >/dev/null || cannot "PostgreSQL $1 did not start"
    else
        dock run --detach --name "$1" --publish 127.0.0.1::5432 \
            --mount "type=bind,source=$(dpath "$WORK/admin"),target=/run/finapp-admin,readonly" --env POSTGRES_PASSWORD_FILE=/run/finapp-admin \
            --env POSTGRES_USER="$ADMIN" --env POSTGRES_DB=finapp "$IMAGE" -c max_connections=100 >/dev/null || cannot "PostgreSQL $1 did not start"
    fi
    ready "$1"
}

# ---------------------------------------------------------------------------------------------------
# Point-in-time recovery (OPERATIONS_RUNBOOK.md section 11.55; ADR-0128 decision 4, ADR-0140): the seeded source archives every WAL
# segment it finishes into a volume of the run's own, the local store, each segment encrypted as it is written (AES-256 under the
# archive's key, never plaintext at rest) with the SHA-256 of its ciphertext beside it; a recovering server reads a segment back only
# when that digest holds, so a damaged segment is refused like a missing one. Neither command overwrites: an archive keeps what it was
# given. Both run as the image's postgres user inside the server's container, where the key is mounted read-only.
# ---------------------------------------------------------------------------------------------------
ARCHIVE_VOLUME="$NAME-archive"
ARCHIVE_COMMAND='test ! -f /archive/%f.enc && openssl enc -e -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass file:/run/finapp-archive-key -in %p -out /archive/%f.part && sha256sum /archive/%f.part | cut -d" " -f1 > /archive/%f.sha256 && mv /archive/%f.part /archive/%f.enc'
RESTORE_COMMAND='test -f /archive/%f.enc && test "$(sha256sum /archive/%f.enc | cut -d" " -f1)" = "$(cat /archive/%f.sha256)" && openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -pass file:/run/finapp-archive-key -in /archive/%f.enc -out %p'
# The words PostgreSQL ends a recovery with when the archive cannot carry it to its target: a gap, or a segment refused by its digest.
NOT_REACHED='recovery ended before configured recovery target was reached'

# epoch <container> <epoch>: the cluster stopped cleanly, its transaction counter's epoch set, started again. pg_resetwal on a
# cleanly stopped cluster with -e alone moves no transaction id's low 32 bits, so nothing about visibility or wraparound changes:
# only the epoch-extended ids (xid8) the platform stores and compares grow by whole epochs.
epoch() {
    data=$(dock exec "$1" sh -c 'printf %s "$PGDATA"')
    [ -n "$data" ] || cannot "no PGDATA in $1"
    dock stop -t 120 "$1" >/dev/null || cannot "$1 did not stop"
    dock run --rm --volumes-from "$1" --user postgres --entrypoint pg_resetwal "$IMAGE" -e "$2" "$data" >> "$ART/rehearsal.log" 2>&1 \
        || cannot "pg_resetwal could not set the epoch of $1"
    dock start "$1" >/dev/null || cannot "$1 did not start again"
    ready "$1"
}

# The roles step: the local bootstrap script, the run's own passwords, and - on a restore - every platform role NOLOGIN, all in one
# transaction, before anything is restored. roles <container> held|open
roles() {
    {
        cat "$ROLES_SCRIPT"
        # The values are hex, so a single quote around each is the whole of their quoting. No literal follows the keyword here, which is
        # what CommittedConfigurationHoldsNoSecretTest reads for.
        printf 'ALTER ROLE %s PASSWORD %s;\n' finapp_app "'${generated_for_app}'"
        printf 'ALTER ROLE %s PASSWORD %s;\n' finapp_migrator "'${generated_for_migrator}'"
        printf 'ALTER ROLE %s PASSWORD %s;\n' finapp_reporting "'${generated_for_reporting}'"
        if [ "$2" = held ]; then
            printf '%s\n' "ALTER ROLE finapp_app NOLOGIN;" "ALTER ROLE finapp_migrator NOLOGIN;" "ALTER ROLE finapp_reporting NOLOGIN;"
        fi
    } | sql_file "$1" > /dev/null 2>> "$ART/rehearsal.log" || cannot "the roles step failed on $1"
}

# login <container> <role> <password>: 0 when the role opens a session, 1 when the server refuses it; the server's words kept.
login() {
    PGPASSWORD="${3}" MSYS_NO_PATHCONV=1 docker exec --env PGPASSWORD "$1" psql -h 127.0.0.1 -U "$2" -d finapp -AtX -c 'SELECT 1' \
        > "$WORK/login.out" 2>&1
}

generated_for_role() {
    case $1 in
        finapp_app) printf '%s' "${generated_for_app}" ;;
        finapp_migrator) printf '%s' "${generated_for_migrator}" ;;
        finapp_reporting) printf '%s' "${generated_for_reporting}" ;;
    esac
}

# Every table's row count, one line each, read by the session that runs it.
COUNTS_SQL="SELECT n.nspname || '.' || c.relname || '|' || (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I.%I', n.nspname, c.relname), false, true, '')))[1]::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%' ORDER BY 1"

# What makes the platform's guarantees and lives outside the rows: the roles (cluster objects pg_dump never carries), their attributes,
# expiry and settings, the database's locale, connection limit, grants and settings (per role too), every definer function and its
# owner, every TRUNCATE refusal, every UNLOGGED table, every grant to a platform role. Log-in is left out: a held restore differs there
# by design, and the release is asserted on its own.
CLUSTER_SQL="SELECT line FROM (
  SELECT 'role|' || rolname || '|super=' || rolsuper || ' inherit=' || rolinherit || ' createrole=' || rolcreaterole || ' createdb=' || rolcreatedb
         || ' replication=' || rolreplication || ' bypassrls=' || rolbypassrls || ' connlimit=' || rolconnlimit || ' validuntil=' || coalesce(rolvaliduntil::text, 'never')
         || '|' || coalesce(array_to_string(rolconfig, ','), '') AS line
    FROM pg_roles WHERE rolname LIKE 'finapp\\_%'
  UNION ALL SELECT 'member|' || r.rolname || '|' || m.rolname FROM pg_auth_members a JOIN pg_roles r ON r.oid = a.roleid JOIN pg_roles m ON m.oid = a.member
   WHERE r.rolname LIKE 'finapp\\_%' OR m.rolname LIKE 'finapp\\_%'
  UNION ALL SELECT 'database|encoding=' || pg_encoding_to_char(encoding) || ' collate=' || datcollate || ' ctype=' || datctype || ' provider=' || datlocprovider::text
         || ' locale=' || coalesce(datlocale, '') || ' connlimit=' || datconnlimit FROM pg_database WHERE datname = current_database()
  UNION ALL SELECT 'database-acl|' || coalesce(array_to_string(ARRAY(SELECT x::text FROM unnest(datacl) x ORDER BY 1), ','), '') FROM pg_database WHERE datname = current_database()
  UNION ALL SELECT 'database-setting|' || CASE WHEN s.setrole = 0 THEN 'every role' ELSE pg_get_userbyid(s.setrole) END || '|' || coalesce(array_to_string(s.setconfig, ','), '')
    FROM pg_db_role_setting s WHERE s.setdatabase = (SELECT oid FROM pg_database WHERE datname = current_database())
  UNION ALL SELECT 'definer|' || p.oid::regprocedure::text || '|owner=' || pg_get_userbyid(p.proowner) || '|' || coalesce(array_to_string(p.proconfig, ','), '')
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE p.prosecdef AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  UNION ALL SELECT 'truncate-refusal|' || t.tgrelid::regclass::text || '|' || t.tgname FROM pg_trigger t WHERE NOT t.tgisinternal AND (t.tgtype & 32) <> 0
  UNION ALL SELECT 'unlogged|' || c.oid::regclass::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relpersistence = 'u' AND c.relkind IN ('r', 'p') AND n.nspname NOT LIKE 'pg\\_%'
  UNION ALL SELECT 'grant|' || table_schema || '.' || table_name || '|' || grantee || '|' || string_agg(privilege_type, ',' ORDER BY privilege_type)
    FROM information_schema.role_table_grants WHERE grantee LIKE 'finapp\\_%' GROUP BY table_schema, table_name, grantee
) catalogue ORDER BY line"

# The largest epoch-extended transaction id stored in any column, read from the catalogue, not from a list of columns.
XID8_SQL="SELECT coalesce(max((xpath('/row/m/text()', query_to_xml(format('SELECT max(%I::text::numeric) AS m FROM %I.%I', a.attname, n.nspname, c.relname), false, true, '')))[1]::text::numeric), 0) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE a.atttypid = 'xid8'::regtype AND a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r', 'p') AND n.nspname NOT LIKE 'pg\\_%'"

# Every sequence's value, read outside MVCC: a sequence never goes back, so pg_dump, reading it after this, writes one at least this far
# on. No unique index stands behind every value one hands out (bnpl's record_order, kyc's check_order and case_order, accounting's
# recorded_order are ordering keys, and even a unique seq column takes a value that falls in a gap), so a sequence restored behind is not
# refused by the first insert: it is compared here. A sequence never called reads `none`.
SEQUENCES_SQL="SELECT schemaname || '.' || sequencename || '|' || coalesce(last_value::text, 'none') FROM pg_sequences WHERE schemaname NOT LIKE 'pg\\_%' ORDER BY 1"

# Each schema's Flyway history head: the release whose migrations the backup was taken at, which is the migration set flywayValidate
# must be run with on its restore.
HISTORY_SQL="SELECT n.nspname || '|' || coalesce((xpath('/row/v/text()', query_to_xml(format('SELECT version AS v FROM %I.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1', n.nspname), false, true, '')))[1]::text, 'none') FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relname = 'flyway_schema_history' AND c.relkind IN ('r', 'p') ORDER BY 1"

schema_of() {
    # \restrict lines are random per dump (PostgreSQL 18), and name nothing about the schema.
    admin_dock exec --env PGPASSWORD "$1" pg_dump -h 127.0.0.1 -U "$ADMIN" -d finapp --schema-only | tr -d '\r' | grep -Ev '^\\(un)?restrict '
}

# ---------------------------------------------------------------------------------------------------
step launcher
./gradlew --console=plain -q :app:restoreRehearsalLauncher > "$ART/gradle-launcher.log" 2>&1 \
    || { tail -40 "$ART/gradle-launcher.log" >&2; cannot "the commands could not be built: see gradle-launcher.log"; }
JAVA=$(tr -d '\r\n' < app/build/restore-rehearsal/java)
ARGS=$(dpath "$ROOT/app/build/restore-rehearsal/classpath.args")
[ -x "$JAVA" ] || [ -f "$JAVA" ] || cannot "no java at $JAVA"

# command <class> <log> [args...]: one of the two commands, with the FINAPP_DB_* environment the caller exported.
command_run() {
    class=$1
    out=$2
    shift 2
    "$JAVA" -Xmx1g "@$ARGS" "com.finapp.app.restore.$class" "$@" > "$out" 2>&1
}

# The credit evidence key ring the source is seeded under (ADR-0128 decision 5; OPERATIONS_RUNBOOK.md section 11.29): two versions
# generated for the run, version 2 active, each with its published check value, so the credit evidence the seed writes stands under a
# version every restore must still hold. Each key reaches a command through its environment, never as an argument, and its check
# value is computed from a pipe, as section 11.29 says.
generated_credit_evidence_v1=$(openssl rand -base64 32)
generated_credit_evidence_v2=$(openssl rand -base64 32)
check_value() {
    printf '%s' "$1" | "$JAVA" --source 21 "$(dpath "$ROOT/infra/scripts/KeyCheckValue.java")"
}
CREDIT_EVIDENCE_CHECKS="1:$(check_value "${generated_credit_evidence_v1}"),2:$(check_value "${generated_credit_evidence_v2}")"
case $CREDIT_EVIDENCE_CHECKS in
    1:????????????????,2:????????????????) ;;
    *) cannot "the credit evidence ring's check values could not be computed" ;;
esac

# ringed <whole|without-v2> <command...>: a command with the credit evidence ring in its environment. `whole` is the ring the source
# was seeded under; `without-v2` is a restore whose key store lost version 2, as a revert of a rotation's listing leaves it (section
# 11.29): it starts, and every row written under version 2 can no longer be read.
ringed() {
    (
        export FINAPP_CREDIT_EVIDENCE_KEY="${generated_credit_evidence_v1}"
        if [ "$1" = whole ]; then
            export FINAPP_CREDIT_EVIDENCE_V2_KEY="${generated_credit_evidence_v2}" FINAPP_CREDIT_EVIDENCE_RING_VERSIONS=1,2 \
                FINAPP_CREDIT_EVIDENCE_RING_ACTIVEVERSION=2 FINAPP_CREDIT_EVIDENCE_RING_CHECKVALUES="$CREDIT_EVIDENCE_CHECKS"
        else
            export FINAPP_CREDIT_EVIDENCE_RING_VERSIONS=1 FINAPP_CREDIT_EVIDENCE_RING_ACTIVEVERSION=1 \
                FINAPP_CREDIT_EVIDENCE_RING_CHECKVALUES="${CREDIT_EVIDENCE_CHECKS%%,*}"
        fi
        shift
        "$@"
    )
}

# ---------------------------------------------------------------------------------------------------
# The source. Seeded: an aged cluster, migrated, seeded through the platform's own services, and whole by its own checks. Kind: the
# rehearsal cluster's database, read from inside its pod.
# ---------------------------------------------------------------------------------------------------
SOURCE="$NAME-source"

# verify <container> <label>: RestoreVerification as the restore's administrator, every session read-only by default. The reporting
# pool must name a role other than the application's (ReportingPoolBeans refuses to start otherwise); it is lazy and no check
# generates a report, so it never connects, and on a held restore finapp_reporting could not. The report of an earlier run is removed
# first, so a report this run did not write can never be read as its verdicts.
verify() {
    port=$(port_of "$1")
    code=0
    rm -f "$ART/verification-$2.txt"
    FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$port/finapp?options=-c%20default_transaction_read_only%3Don" \
        FINAPP_DB_APP_USER="$ADMIN" FINAPP_DB_APP_PASSWORD="${generated_for_admin}" \
        FINAPP_DB_REPORTING_USER=finapp_reporting FINAPP_DB_REPORTING_PASSWORD="${generated_for_reporting}" \
        ringed "${VERIFY_RING:-whole}" command_run RestoreVerification "$ART/verification-$2.log" "$(dpath "$ART/verification-$2.txt")" \
        $DRILL_ARG || code=$?
    tr -d '\r' < "$ART/verification-$2.txt" 2>/dev/null | tail -1 | grep -q "^EXIT|$code\$" \
        || { tail -60 "$ART/verification-$2.log" >&2; cannot "the verification of $2 ended without its report (exit $code): see verification-$2.log"; }
    [ "$code" != 2 ] || { tail -60 "$ART/verification-$2.log" >&2; cannot "the verification of $2 could not run: see verification-$2.log"; }
    # As many verdicts as the catalogue has checks, the number the report states first: a report that is short is not a pass.
    checks=$(tr -d '\r' < "$ART/verification-$2.txt" | sed -n 's/^CHECKS|\([1-9][0-9]*\)$/\1/p')
    [ -n "$checks" ] || cannot "the verification of $2 stated no checks"
    [ "$(grep -c '^\(PASS\|FAIL\)|' "$ART/verification-$2.txt")" = "$checks" ] || cannot "the verification of $2 printed a short report"
    VERIFIED_CHECKS=$checks
    grep '^\(PASS\|FAIL\)|' "$ART/verification-$2.txt" >> "$ART/verdicts-$2.txt"
    # What each proof judged, which a pass does not say: described, never decided on.
    log "verification $2: subjects judged: $(tr -d '\r' < "$ART/verification-$2.txt" | sed -n 's/^SUBJECTS|\([A-Z_]*\)|\(.*\)$/\1 \2/p' | paste -sd';' -)"
}

# source_exec <command...>: a command beside the source's PostgreSQL, as its administrator, with no terminal and no stdin. The seeded
# source over TCP with the password from this shell's environment; the kind source over its pod's own socket, which the overlay's
# pg_hba trusts, so no password is involved there.
source_exec() {
    if [ -n "$KIND_NODE" ]; then
        dock exec "$KIND_NODE" kubectl --kubeconfig=/etc/kubernetes/admin.conf -n finapp exec finapp-postgres-0 -c postgres -- \
            env PGUSER="$ADMIN" PGDATABASE=finapp "$@"
    else
        admin_dock exec --env PGPASSWORD --env PGHOST=127.0.0.1 --env PGUSER="$ADMIN" --env PGDATABASE=finapp "$SOURCE" "$@"
    fi
}

# millis: the shell's clock in milliseconds, for the durations a rate is computed from (GNU date's nanoseconds).
millis() {
    echo $(($(date +%s%N) / 1000000))
}

# switched <container>: the WAL segment in progress finished, and waited for until the archive holds every segment before the one now
# in use; the last of them named. pg_switch_wal() does nothing when nothing was written since the last switch (a backup's stop makes
# one), and then answers a position inside the segment in use, which is never archived until it too is finished: so the segment
# waited for is read from where the insert position now stands, the one before its own (a position one byte on, never on a boundary).
switched() {
    sql "$1" 'SELECT pg_switch_wal()' > /dev/null
    segment=$(sql "$1" "SELECT pg_walfile_name(position - f.file_offset - 1) FROM (SELECT pg_current_wal_insert_lsn() + 1 AS position) p,
        LATERAL pg_walfile_name_offset(p.position) f")
    [ -n "$segment" ] || cannot "the segment before the one in use could not be named"
    waited=0
    until [ "$(sql "$1" "SELECT coalesce(last_archived_wal >= '$segment', false) FROM pg_stat_archiver")" = t ]; do
        waited=$((waited + 1))
        [ "$waited" -le 120 ] || cannot "the WAL segment $segment was not archived; the archiver: $(sql "$1" 'SELECT failed_count, last_failed_wal FROM pg_stat_archiver')"
        sleep 1
    done
    printf '%s' "$segment"
}

if [ -z "$KIND_NODE" ]; then
    step source
    dock volume create "$ARCHIVE_VOLUME" >/dev/null || cannot "the WAL archive's volume could not be made"
    dock run --rm --mount "type=volume,source=$ARCHIVE_VOLUME,target=/archive" --entrypoint chown "$IMAGE" postgres:postgres /archive \
        || cannot "the WAL archive's volume could not be given to the server's user"
    server "$SOURCE" archiving
    # A cluster that has run for a while has an epoch past zero; this one is given epoch 1, so its rows' xid8 values stand where a
    # fresh cluster's counter cannot reach without the restore's own counter step.
    epoch "$SOURCE" 1
    roles "$SOURCE" open
    SOURCE_PORT=$(port_of "$SOURCE")
    FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$SOURCE_PORT/finapp" FINAPP_DB_MIGRATOR_USER=finapp_migrator \
        FINAPP_DB_MIGRATOR_PASSWORD="${generated_for_migrator}" ./gradlew --console=plain -q flywayMigrate > "$ART/gradle-migrate.log" 2>&1 \
        || { tail -40 "$ART/gradle-migrate.log" >&2; cannot "the source's migrations failed: see gradle-migrate.log"; }
    log "source migrated; its counter $(sql "$SOURCE" 'SELECT pg_current_xact_id()')"

    # The base backup a point-in-time recovery starts from, taken before the seed so that the recovery replays the seed's whole WAL
    # from the archive: pg_basebackup's one tar, the WAL it needs to be consistent inside it, written into openssl and never to a disk
    # unencrypted. The segment in progress is then finished, so the first segment a recovery must fetch from the archive is a whole
    # one of its own, the one the plants withhold and damage.
    step base-backup
    source_exec pg_basebackup --format=tar --wal-method=fetch --checkpoint=fast --no-manifest -D - 2> "$ART/basebackup.err" \
        | MSYS_NO_PATHCONV=1 openssl enc -e -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass "file:$(dpath "$WORK/backup.key")" -out "$(dpath "$WORK/base.enc")" \
        || { cat "$ART/basebackup.err" >&2; cannot "the base backup could not be written: see basebackup.err"; }
    [ -s "$WORK/base.enc" ] || cannot "the base backup is empty"
    MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$WORK/base.enc")" | cut -d' ' -f1 > "$WORK/base.sha256"
    switched "$SOURCE" > /dev/null
    # The segment the insert position now stands in (one byte on, since a position exactly on a boundary is named by the segment before).
    FIRST_AFTER_BASE=$(sql "$SOURCE" 'SELECT pg_walfile_name(pg_current_wal_insert_lsn() + 1)')
    pass "base backup taken before the seed: $(wc -c < "$WORK/base.enc" | tr -d ' ') encrypted bytes; the first segment a recovery fetches from the archive is $FIRST_AFTER_BASE"

    step seed
    if ! FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$SOURCE_PORT/finapp" FINAPP_DB_APP_USER=finapp_app FINAPP_DB_APP_PASSWORD="${generated_for_app}" \
        FINAPP_DB_REPORTING_USER=finapp_reporting FINAPP_DB_REPORTING_PASSWORD="${generated_for_reporting}" \
        ringed whole command_run RestoreRehearsalSeed "$ART/seed.log" "$(dpath "$CHART")"; then
        tail -60 "$ART/seed.log" >&2
        cannot "the seed failed: see seed.log"
    fi
    tr -d '\r' < "$ART/seed.log" | grep '^seed:' | while read -r line; do log "$line"; done

    step verify-source
    : > "$ART/verdicts-source.txt"
    verify "$SOURCE" source
    if grep -q '^FAIL|' "$ART/verdicts-source.txt"; then
        fail "the source is not whole, so no restore of it can prove anything: $(grep '^FAIL|' "$ART/verdicts-source.txt" | tr '\n' ';' | cut -c1-600)"
    fi
    pass "the source is whole by the platform's own checks: $(cut -d'|' -f2 "$ART/verdicts-source.txt" | paste -sd, -)"
    # RESTORE_STOP_AFTER=source: the seed and the source's verification alone, a check of the seed that restores nothing.
    if [ "${RESTORE_STOP_AFTER:-}" = source ]; then
        log "stopped after the source, as RESTORE_STOP_AFTER asked: nothing was backed up or restored"
        exit 0
    fi
else
    step source
    # The rehearsal's census was green at every step it ran; nothing here reaches the pod's database from the host, so the source is not
    # verified before the backup, and a restore that fails its checks says the backup or the source, not which.
    [ "$(source_exec psql -XAt -c 'SELECT 1' | tr -d '\r')" = 1 ] || cannot "the kind source on $KIND_NODE did not answer"
    log "source: the kind rehearsal's database on $KIND_NODE, its administrator $ADMIN; its counter $(source_exec psql -XAt -c 'SELECT pg_current_xact_id()' | tr -d '\r')"
fi

# ---------------------------------------------------------------------------------------------------
# The backup: the dump and the manifest in one exported snapshot, encrypted as they are written.
# ---------------------------------------------------------------------------------------------------
step backup
# Beside the source's PostgreSQL: a REPEATABLE READ READ ONLY session exports its snapshot and, in it, reads the manifest (every table's
# count, the catalogue, every sequence, each history's head, the instant); pg_dump reads the same snapshot, the schema alone to a file
# and the data to stdout (this pipe); the session commits after both. The data touches no disk there.
#
# THE SCHEMA THE RESTORE IS COMPARED WITH has been through one restore itself, in the source's own cluster where every role exists.
# PostgreSQL keeps a CHECK as a parse tree, and pg_dump writes it back as text: restored, an AND written inside an AND is read as one,
# so a faithful restore's dump differs from the source's by parentheses alone (55 constraints here, seen on the first run). The schema
# taken once through dump and restore is what any faithful restore reproduces byte for byte; what the target's restore loses or
# changes, a role or a grant or an object, still differs. That scratch database is dropped again, and the schema dump removed.
INNER='set -eu
d=$(mktemp -d)
mkfifo "$d/in"
psql -X -q -A -t -v ON_ERROR_STOP=1 < "$d/in" > "$d/out" 2> "$d/err" &
session=$!
exec 3> "$d/in"
printf "%s\n" "BEGIN ISOLATION LEVEL REPEATABLE READ, READ ONLY;" "SELECT '"'"'snapshot='"'"' || pg_export_snapshot();" >&3
i=0
until grep -q "^snapshot=" "$d/out"; do
    i=$((i + 1)); { [ "$i" -lt 600 ] && kill -0 "$session" 2>/dev/null; } || { cat "$d/err" >&2; exit 3; }; sleep 0.1
done
snapshot=$(sed -n "s/^snapshot=//p" "$d/out")
printf "%s\n" "\\o /tmp/manifest-counts" "$COUNTS_SQL;" "\\o /tmp/manifest-cluster" "$CLUSTER_SQL;" "\\o /tmp/manifest-sequences" "$SEQUENCES_SQL;" \
    "\\o /tmp/manifest-history" "$HISTORY_SQL;" "\\o /tmp/manifest-instant" "SELECT now();" "\\o" "SELECT '"'"'counted'"'"';" >&3
i=0
until grep -q "^counted" "$d/out"; do
    i=$((i + 1)); { [ "$i" -lt 3000 ] && kill -0 "$session" 2>/dev/null; } || { cat "$d/err" >&2; exit 3; }; sleep 0.1
done
pg_dump --snapshot="$snapshot" --schema-only --format=custom --file=/tmp/schema.dump
pg_dump --snapshot="$snapshot" --format=custom
printf "%s\n" "COMMIT;" >&3
exec 3>&-
wait "$session"
rm -rf "$d"
createdb finapp_schema_reference > /dev/null
pg_restore -d finapp_schema_reference --single-transaction --exit-on-error /tmp/schema.dump > /dev/null
pg_dump -d finapp_schema_reference --schema-only | grep -Ev "^\\\\(un)?restrict " > /tmp/manifest-schema
dropdb finapp_schema_reference
rm -f /tmp/schema.dump
echo done > /tmp/backup-status'
source_exec env COUNTS_SQL="$COUNTS_SQL" CLUSTER_SQL="$CLUSTER_SQL" SEQUENCES_SQL="$SEQUENCES_SQL" HISTORY_SQL="$HISTORY_SQL" sh -c "$INNER" \
    2> "$ART/backup.err" \
    | MSYS_NO_PATHCONV=1 openssl enc -e -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass "file:$(dpath "$WORK/backup.key")" -out "$(dpath "$WORK/backup.enc")" \
    || cannot "the backup could not be written"
[ "$(source_exec sh -c 'cat /tmp/backup-status && rm -f /tmp/backup-status' 2>/dev/null | tr -d '\r')" = done ] \
    || { cat "$ART/backup.err" >&2; cannot "the dump did not complete in its snapshot: see backup.err"; }
# Each part copied out of the source and removed there in the same command; then encrypted, and the plaintext removed here.
mkdir "$WORK/manifest"
for part in counts cluster sequences history instant schema; do
    source_exec sh -c "cat /tmp/manifest-$part && rm -f /tmp/manifest-$part" | tr -d '\r' > "$WORK/manifest/$part" \
        || cannot "the manifest's $part could not be read"
    [ -s "$WORK/manifest/$part" ] || cannot "the manifest's $part is empty"
done
INSTANT=$(sed -n '1p' "$WORK/manifest/instant")
HISTORIES=$(grep -c . "$WORK/manifest/history")
tar -C "$WORK" -cf - manifest | MSYS_NO_PATHCONV=1 openssl enc -e -aes-256-cbc -pbkdf2 -iter 600000 -salt \
    -pass "file:$(dpath "$WORK/backup.key")" -out "$(dpath "$WORK/manifest.enc")" || cannot "the manifest could not be encrypted"
rm -rf "$WORK/manifest"
# Integrity against a damaged file: each ciphertext's digest, checked before it is decrypted.
for part in backup manifest; do
    MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$WORK/$part.enc")" | cut -d' ' -f1 > "$WORK/$part.sha256"
done
# The bytes at rest are not a dump and not a base backup: pg_dump's custom format begins PGDMP and a tar names itself ustar at byte 257,
# and nothing in the work directory may do either. Asserted after every step that writes there.
no_plaintext_at_rest() {
    for file in "$WORK"/*; do
        [ -f "$file" ] || continue
        if [ "$(head -c 5 "$file")" = PGDMP ]; then
            fail "a plaintext dump is at rest: $(basename "$file")"
        fi
        if [ "$(head -c 262 "$file" | tail -c 5)" = ustar ]; then
            fail "a plaintext base backup is at rest: $(basename "$file")"
        fi
    done
}
no_plaintext_at_rest
TABLES=$(MSYS_NO_PATHCONV=1 openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -pass "file:$(dpath "$WORK/backup.key")" -in "$(dpath "$WORK/manifest.enc")" \
    | tar -xOf - manifest/counts | grep -c .)
pass "backed up in one snapshot at $INSTANT: $(wc -c < "$WORK/backup.enc" | tr -d ' ') encrypted bytes, a manifest of $TABLES tables and $HISTORIES schema histories' heads, no plaintext dump at rest"
# ---------------------------------------------------------------------------------------------------
# After the backup, on the seeded source (section 11.55): the restore point T; then a provider's file that no restore to T or to the
# backup holds; then the bad write a point-in-time recovery must stop before. Nothing writes to the source between the backup's
# snapshot and T, so a recovery to T is compared with the backup's own manifest.
# ---------------------------------------------------------------------------------------------------
if [ -z "$KIND_NODE" ]; then
    step after-the-backup
    T=$(sql "$SOURCE" 'SELECT clock_timestamp()')
    [ -n "$T" ] || cannot "the restore point could not be read"
    sleep 1
    # The second capital contribution's bank statement, sent through intake on the source after T. The bank keeps its copy, which the
    # post-recovery gate delivers again; the copy is encrypted at once, its plaintext removed, and its digest kept.
    if ! FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$SOURCE_PORT/finapp" FINAPP_DB_APP_USER=finapp_app FINAPP_DB_APP_PASSWORD="${generated_for_app}" \
        FINAPP_DB_REPORTING_USER=finapp_reporting FINAPP_DB_REPORTING_PASSWORD="${generated_for_reporting}" \
        ringed whole command_run RestoreRehearsalSeed "$ART/statement.log" --statement "$(dpath "$WORK/statement.txt")"; then
        tail -60 "$ART/statement.log" >&2
        cannot "the statement mode failed: see statement.log"
    fi
    tr -d '\r' < "$ART/statement.log" | grep '^statement:' | while read -r line; do log "$line"; done
    [ -s "$WORK/statement.txt" ] || cannot "the bank's copy of the statement was not written"
    MSYS_NO_PATHCONV=1 openssl enc -e -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass "file:$(dpath "$WORK/backup.key")" \
        -in "$(dpath "$WORK/statement.txt")" -out "$(dpath "$WORK/statement.enc")" || cannot "the bank's copy could not be encrypted"
    rm -f "$WORK/statement.txt"
    MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$WORK/statement.enc")" | cut -d' ' -f1 > "$WORK/statement.sha256"
    # The bad write: a raw writer, with the triggers suspended, removes one wallet line of a transfer's entry. Its id is kept, so a
    # recovery to T can be shown to hold the line again.
    BAD_LINE=$(sql "$SOURCE" "SET session_replication_role = replica;
        WITH gone AS (DELETE FROM ledger.journal_line WHERE id = (SELECT l.id FROM ledger.journal_line l
                          JOIN ledger.journal_entry e ON e.id = l.entry_id JOIN ledger.ledger_account a ON a.id = l.ledger_account_id
                         WHERE e.entry_type = 'POSTING' AND e.reference IN (SELECT t.id::text FROM transfers.transfer t)
                           AND a.purpose = 'CUSTOMER_WALLET'
                         ORDER BY l.id DESC LIMIT 1)
                      RETURNING id)
        SELECT id FROM gone" | tail -1)
    [ -n "$BAD_LINE" ] || cannot "the bad write did not land"
    LAST_SEGMENT=$(switched "$SOURCE")
    # The archive holds ciphertext and digests only: every name a segment's, a backup label's or a history file's, then .enc or .sha256.
    dock run --rm --mount "type=volume,source=$ARCHIVE_VOLUME,target=/archive,readonly" --entrypoint ls "$IMAGE" -1 /archive \
        | tr -d '\r' > "$WORK/archive.list" || cannot "the WAL archive could not be listed"
    ARCHIVED=$(grep -c '\.enc$' "$WORK/archive.list" || true)
    [ "$ARCHIVED" -gt 0 ] || cannot "the WAL archive holds no segment"
    if grep -Ev '^[0-9A-F]{24}(\.[0-9A-F]{8}\.backup|\.history)?\.(enc|sha256)$' "$WORK/archive.list" | grep -q .; then
        fail "the WAL archive holds a file that is not ciphertext or its digest: $(grep -Ev '\.(enc|sha256)$' "$WORK/archive.list" | head -3 | tr '\n' ' ')"
    fi
    no_plaintext_at_rest
    pass "after the backup: the restore point $T; the bank statement accepted after it; one journal line removed by a raw writer after that; $ARCHIVED WAL files archived through $LAST_SEGMENT, each encrypted with its digest beside it"
fi
# The source is gone from here on: everything a restore is compared with is the backup's, and a recovery's the archive's.
[ -n "$KIND_NODE" ] || dock rm -f -v "$SOURCE" >/dev/null 2>&1 || true

# A damaged backup is refused by its digest before it is decrypted: a copy with one byte changed, judged as every restore judges the
# backup, and no restore is ever made from it.
step damaged-backup
cp "$WORK/backup.enc" "$WORK/damaged.enc"
size=$(wc -c < "$WORK/damaged.enc" | tr -d ' ')
printf '\377' | dd of="$WORK/damaged.enc" bs=1 seek=$((size / 2)) conv=notrunc 2>/dev/null
cmp -s "$WORK/backup.enc" "$WORK/damaged.enc" && printf '\000' | dd of="$WORK/damaged.enc" bs=1 seek=$((size / 2)) conv=notrunc 2>/dev/null
if [ "$(MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$WORK/damaged.enc")" | cut -d' ' -f1)" = "$(cat "$WORK/backup.sha256")" ]; then
    fail "a backup with one byte changed still matches the backup's digest"
fi
rm -f "$WORK/damaged.enc"
pass "a backup with one byte changed is refused by its digest before it is decrypted"

# ---------------------------------------------------------------------------------------------------
# A restore: held, restored, its counter moved, compared and verified. restore <label> [counter|no-counter]
# ---------------------------------------------------------------------------------------------------
# Checked in this shell, never inside a pipeline, so a damaged file stops the run here rather than feeding a restore nothing.
# digests_hold [parts...]: each ciphertext's digest checked, the backup and its manifest by default.
digests_hold() {
    for part in ${*:-backup manifest}; do
        [ "$(MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$WORK/$part.enc")" | cut -d' ' -f1)" = "$(cat "$WORK/$part.sha256")" ] \
            || fail "the $part's ciphertext does not match its digest: refused before decrypting"
    done
}

decrypt() {
    MSYS_NO_PATHCONV=1 openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -pass "file:$(dpath "$WORK/backup.key")" -in "$(dpath "$WORK/$1.enc")"
}

# rate <container> <label> <logical|point-in-time> <milliseconds>: what one restore took against the size of what it restored, one line of
# rates.tsv (label, kind, the restored database's bytes, milliseconds), the figures a restore rate per GiB is computed from (section 11.55).
# Measured on this machine at this size, which is all it says.
: > "$ART/rates.tsv"
rate() {
    bytes=$(sql "$1" "SELECT pg_database_size('finapp')")
    printf '%s\t%s\t%s\t%s\n' "$2" "$3" "$bytes" "$4" >> "$ART/rates.tsv"
    log "restore $2: $3, $bytes bytes in $4 ms"
}

restore() {
    label=$1
    target="$NAME-$label"
    server "$target"
    roles "$target" held
    # The first rule, measured: each platform role is refused at log-in before anything is restored into the database.
    for role in finapp_app finapp_migrator finapp_reporting; do
        if login "$target" "$role" "$(generated_for_role "$role")"; then
            fail "restore $label: $role logged in to a restore that has not been verified"
        fi
        grep -q 'not permitted to log in' "$WORK/login.out" || cannot "restore $label: $role was refused for another reason: $(tr '\n' ' ' < "$WORK/login.out" | cut -c1-200)"
    done
    pass "restore $label: finapp_app, finapp_migrator and finapp_reporting each refused at log-in before the restore"
    digests_hold
    # pg_restore's output stays in the work directory: a failed COPY quotes the row it failed on, and the artefacts are kept by CI.
    # What leaves it is the error's first clause, which names the object and never a value.
    started=$(millis)
    decrypt backup | admin_dock exec -i --env PGPASSWORD "$target" \
        pg_restore -h 127.0.0.1 -U "$ADMIN" -d finapp --single-transaction --exit-on-error > "$WORK/restore-$label.log" 2>&1 \
        || cannot "restore $label: pg_restore failed: $(sed -n 's/^\(pg_restore: error: [^:]*\).*/\1/p' "$WORK/restore-$label.log" | head -3 | tr '\n' ' ')"
    rate "$target" "$label" logical $(($(millis) - started))
    highest=$(sql "$target" "$XID8_SQL")
    counter=$(sql "$target" 'SELECT pg_current_xact_id()')
    if [ "$2" = counter ]; then
        # The counter step: a fresh cluster's counter restarts below the restored rows' xid8 values (ledger V034's txid, the census's
        # head). The epoch moves past the highest of them, so every restored position is behind every transaction to come.
        if [ "$(sql "$target" "SELECT $counter::numeric <= $highest::numeric")" = t ]; then
            epoch "$target" "$(sql "$target" "SELECT floor($highest::numeric / 4294967296)::bigint + 1")"
        fi
        [ "$(sql "$target" "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::numeric > $highest::numeric")" = t ] \
            || fail "restore $label: the counter is still at or below the highest restored xid8 ($highest)"
        log "restore $label: the highest restored xid8 $highest, the counter $counter, moved to $(sql "$target" 'SELECT pg_current_xact_id()')"
    else
        log "restore $label: the counter step skipped: the highest restored xid8 $highest, the counter $counter"
    fi
}

# plant <container> <name>: the corruption, as the superuser, with replication role so no trigger refuses it.
plant() {
    case $2 in
        unbalanced-entry) sql "$1" "SET session_replication_role = replica;
            WITH chosen AS (
                SELECT p.id AS proposal_id, l.id AS line_id, l.ledger_account_id, l.direction, a.normal_balance
                  FROM ledger.adjustment_proposal p
                  JOIN ledger.journal_line l ON l.entry_id = p.journal_entry_id
                  JOIN ledger.ledger_account a ON a.id = l.ledger_account_id
                 WHERE p.status = 'APPROVED' AND p.origin = 'MANUAL' AND l.direction = 'CREDIT'
                 ORDER BY p.id LIMIT 1),
            line AS (UPDATE ledger.journal_line SET amount_minor = amount_minor + 1 WHERE id = (SELECT line_id FROM chosen) RETURNING id),
            proposed AS (UPDATE ledger.adjustment_proposal_line pl SET amount_minor = pl.amount_minor + 1 FROM chosen
                 WHERE pl.proposal_id = chosen.proposal_id AND pl.ledger_account_id = chosen.ledger_account_id AND pl.direction = chosen.direction
                 RETURNING pl.proposal_id),
            balance AS (UPDATE ledger.account_balance b SET posted_minor = b.posted_minor + CASE WHEN chosen.normal_balance = chosen.direction THEN 1 ELSE -1 END
                  FROM chosen WHERE b.ledger_account_id = chosen.ledger_account_id RETURNING b.ledger_account_id)
            SELECT (SELECT count(*) FROM line) || ' ' || (SELECT count(*) FROM proposed) || ' ' || (SELECT count(*) FROM balance)" ;;
        balance-disagrees) sql "$1" "SET session_replication_role = replica;
            WITH chosen AS (SELECT b.ledger_account_id FROM ledger.account_balance b JOIN ledger.ledger_account a ON a.id = b.ledger_account_id
                             WHERE a.purpose = 'CUSTOMER_WALLET' ORDER BY b.ledger_account_id LIMIT 1),
            moved AS (UPDATE ledger.account_balance b SET posted_minor = b.posted_minor + 1 FROM chosen
                       WHERE b.ledger_account_id = chosen.ledger_account_id RETURNING b.ledger_account_id)
            SELECT (SELECT count(*) FROM moved) || ' 1 1'" ;;
        missing-row) sql "$1" "SET session_replication_role = replica;
            WITH gone AS (DELETE FROM platform.audit_record WHERE audit_id = (SELECT audit_id FROM platform.audit_record ORDER BY audit_id LIMIT 1)
                          RETURNING audit_id)
            SELECT (SELECT count(*) FROM gone) || ' 1 1'" ;;
        grant-dropped) sql "$1" "REVOKE INSERT ON ledger.journal_line FROM finapp_app" >/dev/null && echo '1 1 1' ;;
        role-altered) sql "$1" "ALTER ROLE finapp_app CONNECTION LIMIT 7" >/dev/null && echo '1 1 1' ;;
        trigger-dropped) sql "$1" "DROP TRIGGER journal_entry_is_append_only ON ledger.journal_entry" >/dev/null && echo '1 1 1' ;;
        sequence-behind) sql "$1" "WITH chosen AS (SELECT format('%I.%I', schemaname, sequencename) AS name FROM pg_sequences
                                     WHERE schemaname NOT LIKE 'pg\\_%' AND last_value > 1 ORDER BY last_value DESC, 1 LIMIT 1),
            moved AS (SELECT setval(name::regclass, 1, false) FROM chosen)
            SELECT (SELECT count(*) FROM moved) || ' 1 1'" ;;
        history-checksum) sql "$1" "WITH chosen AS (SELECT installed_rank FROM ledger.flyway_schema_history WHERE checksum IS NOT NULL
                                      ORDER BY installed_rank DESC LIMIT 1),
            bumped AS (UPDATE ledger.flyway_schema_history h SET checksum = CASE WHEN h.checksum = 2147483647 THEN h.checksum - 1 ELSE h.checksum + 1 END
                         FROM chosen WHERE h.installed_rank = chosen.installed_rank RETURNING h.installed_rank)
            SELECT (SELECT count(*) FROM bumped) || ' 1 1'" ;;
        gl-rule-repointed) sql "$1" "SET session_replication_role = replica;
            WITH chosen AS (SELECT r.version_id, r.ledger_purpose, r.owner_kind FROM accounting.gl_mapping_rule r
                              JOIN accounting.gl_mapping_version v ON v.id = r.version_id
                             WHERE v.status = 'ACTIVE' AND r.ledger_purpose = 'CUSTOMER_WALLET' ORDER BY r.owner_kind LIMIT 1),
            moved AS (UPDATE accounting.gl_mapping_rule r SET ledger_purpose = 'RESTORE_PLANT_UNMAPPED' FROM chosen
                       WHERE r.version_id = chosen.version_id AND r.ledger_purpose = chosen.ledger_purpose AND r.owner_kind = chosen.owner_kind
                       RETURNING r.version_id)
            SELECT (SELECT count(*) FROM moved) || ' 1 1'" ;;
        counter-not-moved) echo '1 1 1' ;;
        key-version-missing) echo '1 1 1' ;;
        journal-line-removed) sql "$1" "SET session_replication_role = replica;
            WITH gone AS (DELETE FROM ledger.journal_line WHERE id = (SELECT l.id FROM ledger.journal_line l
                              JOIN ledger.journal_entry e ON e.id = l.entry_id JOIN ledger.ledger_account a ON a.id = l.ledger_account_id
                             WHERE e.entry_type = 'POSTING' AND e.reference IN (SELECT t.id::text FROM transfers.transfer t)
                               AND a.purpose = 'CUSTOMER_WALLET'
                             ORDER BY l.id LIMIT 1)
                          RETURNING id)
            SELECT (SELECT count(*) FROM gone) || ' 1 1'" ;;
        *) cannot "no plant named $2" ;;
    esac
}

# compare <container> <label>: flywayValidate, then the counts, the catalogue, the schema and the sequences against the manifest; one
# verdict line each, COMPARED of them.
COMPARED=5
compare() {
    port=$(port_of "$1")
    verdicts="$ART/verdicts-$2.txt"
    before=$(grep -c . "$verdicts" || true)
    digests_hold
    decrypt manifest | tar -C "$WORK" -xf - || cannot "the manifest could not be read"
    # flywayValidate checks a schema's history against the migrations of the tree it runs from, so it runs from the release whose
    # migrations the backup's heads name: this tree here, which wrote the source. A backup taken at an older release is validated from
    # that release's tree (OPERATIONS_RUNBOOK.md section 11.35), and the restore is migrated forward only after it is released.
    if FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$port/finapp?options=-c%20default_transaction_read_only%3Don" FINAPP_DB_MIGRATOR_USER="$ADMIN" \
        FINAPP_DB_MIGRATOR_PASSWORD="${generated_for_admin}" ./gradlew --console=plain -q flywayValidate > "$ART/gradle-validate-$2.log" 2>&1; then
        echo "PASS|flywayValidate|$(grep -c . "$WORK/manifest/history") schemas' histories, each at the head the backup recorded, validated against this tree's migrations" >> "$verdicts"
    else
        echo "FAIL|flywayValidate|$(grep -c . "$WORK/manifest/history") schemas' histories recorded by the backup; the validation failed: see gradle-validate-$2.log" >> "$verdicts"
    fi
    sql "$1" "$COUNTS_SQL" > "$WORK/counts-$2"
    if cmp -s "$WORK/manifest/counts" "$WORK/counts-$2"; then
        echo "PASS|row counts equal to the source's|$(grep -c . "$WORK/counts-$2") tables, each the count the dump's snapshot read" >> "$verdicts"
    else
        diff "$WORK/manifest/counts" "$WORK/counts-$2" > "$ART/counts-$2.diff" || true
        echo "FAIL|row counts equal to the source's|$(grep -c '^[<>]' "$ART/counts-$2.diff") lines differ (< the source's, > the restore's): $(grep '^[<>]' "$ART/counts-$2.diff" | head -4 | tr '|\n' '= ')" >> "$verdicts"
    fi
    sql "$1" "$CLUSTER_SQL" > "$WORK/cluster-$2"
    if cmp -s "$WORK/manifest/cluster" "$WORK/cluster-$2"; then
        echo "PASS|catalogue equal to the source's|$(grep -c '^role|' "$WORK/cluster-$2") roles with their attributes and settings, the database's locale and grants, $(grep -c '^definer|' "$WORK/cluster-$2") definer functions with their owners, $(grep -c '^truncate-refusal|' "$WORK/cluster-$2") TRUNCATE refusals, $(grep -c '^unlogged|' "$WORK/cluster-$2") UNLOGGED tables, $(grep -c '^grant|' "$WORK/cluster-$2") tables' grants to the platform's roles" >> "$verdicts"
    else
        diff "$WORK/manifest/cluster" "$WORK/cluster-$2" > "$ART/catalogue-$2.diff" || true
        echo "FAIL|catalogue equal to the source's|$(grep -c '^[<>]' "$ART/catalogue-$2.diff") lines differ (< the source's, > the restore's): $(grep '^[<>]' "$ART/catalogue-$2.diff" | head -3 | tr '|\n' '= ' | cut -c1-300)" >> "$verdicts"
    fi
    schema_of "$1" > "$WORK/schema-$2"
    if cmp -s "$WORK/manifest/schema" "$WORK/schema-$2"; then
        echo "PASS|schema equal to the source's|pg_dump --schema-only identical, $(grep -c . "$WORK/schema-$2") lines: every object, constraint, trigger, ACL and comment" >> "$verdicts"
    else
        diff "$WORK/manifest/schema" "$WORK/schema-$2" > "$ART/schema-$2.diff" || true
        echo "FAIL|schema equal to the source's|$(grep -c '^[<>]' "$ART/schema-$2.diff") lines differ (< the source's, > the restore's): $(grep '^[<>]' "$ART/schema-$2.diff" | head -3 | tr '|\n' '= ' | cut -c1-300)" >> "$verdicts"
    fi
    # Every sequence at or past the value the backup's session read; a sequence missing, never called where the source's was, or behind
    # it, fails. Compared as digit strings, so no value is rounded.
    sql "$1" "$SEQUENCES_SQL" > "$WORK/sequences-$2"
    behind=$(awk -F'|' '
        function at_or_past(r, s) {
            if (s == "none") return 1
            if (r == "none") return 0
            if (r ~ /^-/ || s ~ /^-/) return (r + 0 >= s + 0)
            if (length(r) != length(s)) return length(r) > length(s)
            return r >= s
        }
        NR == FNR { restored[$1] = $2; next }
        !($1 in restored) { print $1 "=gone"; next }
        !at_or_past(restored[$1], $2) { print $1 "=" restored[$1] "<" $2 }
    ' "$WORK/sequences-$2" "$WORK/manifest/sequences")
    if [ -z "$behind" ]; then
        echo "PASS|sequences at or past the source's|$(grep -c . "$WORK/manifest/sequences") sequences, each at or past the value the backup's session read" >> "$verdicts"
    else
        echo "FAIL|sequences at or past the source's|$(printf '%s\n' "$behind" | grep -c .) behind (restored < source): $(printf '%s\n' "$behind" | head -3 | tr '|\n' '= ' | cut -c1-300)" >> "$verdicts"
    fi
    rm -rf "$WORK/manifest"
    [ "$(($(grep -c . "$verdicts") - before))" = "$COMPARED" ] || cannot "the comparison of $2 wrote $(($(grep -c . "$verdicts") - before)) verdicts, not $COMPARED"
}

# ---------------------------------------------------------------------------------------------------
# The post-recovery gate (ADR-0128 decision 3, ADR-0140; sections 11.35 and 11.55), on a restore whose verification passed with the
# roles still held. Money moves only after all three.
#   1. The window: every file a provider delivered after the restore's point delivered again through the platform's own intake, and
#      reconciliation run over it, by RestoreReconciliation acting as finapp_app while the three roles still cannot log in. Here the
#      provider is the simulated bank, and the window holds the statement sent after the backup. Its contribution was approved before
#      the point, so a clean window raises no break; any break is a finding a person explains before the release.
#   2. The books again: RestoreVerification over the database the window changed.
#   3. The release: a person's, recorded (released by, reason, the database's instant, and the digest of every report it rests on), and
#      only then each role able to log in. RESTORE_RELEASED_BY names the person; a run with nobody named is released by the rehearsal's
#      second operator, as its record says.
# gate <container> <label> <window start>
# ---------------------------------------------------------------------------------------------------
gate() {
    port=$(port_of "$1")
    if [ -n "$KIND_NODE" ]; then
        # The kind rehearsal's provider files are its simulator's, which this harness does not hold: the window is a person's step there.
        log "restore $2: the window since $3 is not delivered here: a kind source's provider files are its simulator's (section 11.55)"
    else
        code=0
        rm -f "$ART/window-$2.txt"
        digests_hold statement
        decrypt statement | FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$port/finapp?options=-c%20role%3Dfinapp_app" \
            FINAPP_DB_APP_USER="$ADMIN" FINAPP_DB_APP_PASSWORD="${generated_for_admin}" \
            FINAPP_DB_REPORTING_USER=finapp_reporting FINAPP_DB_REPORTING_PASSWORD="${generated_for_reporting}" \
            ringed whole command_run RestoreReconciliation "$ART/window-$2.log" "$(dpath "$ART/window-$2.txt")" "$3" simulated-bank.statement \
            || code=$?
        tr -d '\r' < "$ART/window-$2.txt" 2>/dev/null | tail -1 | grep -q "^EXIT|$code\$" \
            || { tail -60 "$ART/window-$2.log" >&2; cannot "the window of $2 ended without its report (exit $code): see window-$2.log"; }
        [ "$code" != 2 ] || { tail -60 "$ART/window-$2.log" >&2; cannot "the window of $2 could not run: see window-$2.log"; }
        tr -d '\r' < "$ART/window-$2.txt" | grep -q '^HOLD|acting as finapp_app' || fail "restore $2: the window ran without the hold"
        tr -d '\r' < "$ART/window-$2.txt" | grep -q '^FILE|ACCEPTED|' \
            || fail "restore $2: the bank's statement was not accepted in the window: $(grep '^FILE|' "$ART/window-$2.txt" | cut -c1-200)"
        tr -d '\r' < "$ART/window-$2.txt" | grep -q '^BREAKS|0|' \
            || fail "restore $2: the window raised breaks no clean window raises: $(grep '^BREAKS|' "$ART/window-$2.txt" | cut -c1-200)"
        pass "restore $2: the window since $3 reconciled with money held: the bank's statement delivered again and accepted, no break raised"
        : > "$ART/verdicts-$2-window.txt"
        verify "$1" "$2-window"
        if grep -q '^FAIL|' "$ART/verdicts-$2-window.txt"; then
            fail "restore $2: after the window the books are not whole, so it stays held: $(grep '^FAIL|' "$ART/verdicts-$2-window.txt" | tr '\n' ';' | cut -c1-600)"
        fi
        pass "restore $2: whole again after the window: $(cut -d'|' -f2 "$ART/verdicts-$2-window.txt" | paste -sd, -)"
    fi
    # The release. Nothing before this line lets a platform role log in to the restore.
    released_by=${RESTORE_RELEASED_BY:-restore-rehearsal-operator-b}
    rests_on=""
    for report in "verdicts-$2.txt" "window-$2.txt" "verdicts-$2-window.txt"; do
        [ -f "$ART/$report" ] || continue
        rests_on="$rests_on $report=$(MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$ART/$report")" | cut -d' ' -f1)"
    done
    # One transaction: the three roles released and the database's instant read together.
    released=$(sql "$1" "BEGIN; ALTER ROLE finapp_app LOGIN; ALTER ROLE finapp_migrator LOGIN; ALTER ROLE finapp_reporting LOGIN;
        SELECT now(); COMMIT;") || cannot "the release of $2 failed"
    released_at=$(printf '%s\n' "$released" | grep -v '^\(BEGIN\|COMMIT\|ALTER ROLE\)$' | tail -1)
    [ -n "$released_at" ] || cannot "the release of $2 read no instant"
    printf 'restore\t%s\nreleased_by\t%s\nreason\t%s\nreleased_at\t%s\nrests_on\t%s\n' "$2" "$released_by" \
        "every check passed on the restore, the window since $3 reconciled, the books whole after it" "$released_at" "${rests_on# }" \
        > "$ART/release-$2.tsv"
    for role in finapp_app finapp_migrator finapp_reporting; do
        login "$1" "$role" "$(generated_for_role "$role")" || fail "after the release, $role still cannot log in: $(tr '\n' ' ' < "$WORK/login.out" | cut -c1-200)"
    done
    pass "restore $2 released by $released_by at $released_at, recorded with the reports it rests on: finapp_app, finapp_migrator and finapp_reporting log in"
}

# ---------------------------------------------------------------------------------------------------
# A point-in-time recovery (section 11.55): a new server whose data directory is the base backup, before it first starts, recovering
# from the archive volume named to the instant given, then promoted. The physical restore brings the roles back as the source had them,
# able to log in, so the hold is placed before the server first starts: pg_hba.conf refuses the three platform roles from every address
# and the socket; once promoted they are made NOLOGIN as the logical restore's are, the refusal lines removed, and each role's refusal is
# asserted with the words a NOLOGIN role gets. Ten replicas pointed at it are refused exactly as one is, at every moment.
# pitr <label> <archive volume> <target instant>: 0 promoted and held; 1 the server refused to reach the target (its log kept).
# ---------------------------------------------------------------------------------------------------
HBA_HOLD='host all finapp_app,finapp_migrator,finapp_reporting all reject
local all finapp_app,finapp_migrator,finapp_reporting reject'
pitr() {
    label=$1
    target="$NAME-$label"
    dock create --name "$target" --publish 127.0.0.1::5432 \
        --mount "type=bind,source=$(dpath "$WORK/admin"),target=/run/finapp-admin,readonly" --env POSTGRES_PASSWORD_FILE=/run/finapp-admin \
        --mount "type=volume,source=$2,target=/archive,readonly" \
        --mount "type=bind,source=$(dpath "$WORK/archive.key"),target=/run/finapp-archive-key,readonly" \
        --env POSTGRES_USER="$ADMIN" --env POSTGRES_DB=finapp "$IMAGE" -c max_connections=100 >/dev/null \
        || cannot "recovery $label: its server could not be made"
    digests_hold base
    decrypt base | dock run --rm -i --volumes-from "$target" --user postgres --env RESTORE_COMMAND="$RESTORE_COMMAND" \
        --env TARGET_TIME="$3" --env HBA_HOLD="$HBA_HOLD" --entrypoint sh "$IMAGE" -c '
            set -eu
            mkdir -p "$PGDATA"
            chmod 700 "$PGDATA"
            cd "$PGDATA"
            tar -xf -
            { printf "%s\n" "$HBA_HOLD"; cat pg_hba.conf; } > pg_hba.held
            mv pg_hba.held pg_hba.conf
            printf "restore_command = '"'"'%s'"'"'\nrecovery_target_time = '"'"'%s'"'"'\nrecovery_target_inclusive = off\nrecovery_target_action = '"'"'promote'"'"'\n" \
                "$RESTORE_COMMAND" "$TARGET_TIME" >> postgresql.auto.conf
            touch recovery.signal' > "$WORK/recover-$label.log" 2>&1 \
        || cannot "recovery $label: the base backup could not be laid down: $(head -3 "$WORK/recover-$label.log" | tr '\n' ' ')"
    started=$(millis)
    dock start "$target" >/dev/null || cannot "recovery $label: its server did not start"
    waited=0
    until [ "$(sql "$target" 'SELECT NOT pg_is_in_recovery()' 2>/dev/null)" = t ]; do
        if [ "$(dock inspect -f '{{.State.Running}}' "$target" | tr -d '\r')" != true ]; then
            dock logs "$target" > "$ART/recovery-$label.log" 2>&1 || true
            return 1
        fi
        waited=$((waited + 1))
        [ "$waited" -le 600 ] || cannot "recovery $label: not promoted within ten minutes"
        sleep 1
    done
    rate "$target" "$label" point-in-time $(($(millis) - started))
    dock logs "$target" > "$ART/recovery-$label.log" 2>&1 || true
    sql "$target" "ALTER ROLE finapp_app NOLOGIN; ALTER ROLE finapp_migrator NOLOGIN; ALTER ROLE finapp_reporting NOLOGIN" >/dev/null \
        || cannot "recovery $label: the roles could not be held"
    dock exec --user postgres "$target" sh -c 'grep -vxF -e "host all finapp_app,finapp_migrator,finapp_reporting all reject" \
        -e "local all finapp_app,finapp_migrator,finapp_reporting reject" "$PGDATA/pg_hba.conf" > "$PGDATA/pg_hba.open" \
        && mv "$PGDATA/pg_hba.open" "$PGDATA/pg_hba.conf"' || cannot "recovery $label: the refusal lines could not be removed"
    [ "$(sql "$target" 'SELECT pg_reload_conf()')" = t ] || cannot "recovery $label: the server did not reload its configuration"
    for role in finapp_app finapp_migrator finapp_reporting; do
        if login "$target" "$role" "$(generated_for_role "$role")"; then
            fail "recovery $label: $role logged in to a recovery that has not been verified"
        fi
        grep -q 'not permitted to log in' "$WORK/login.out" || cannot "recovery $label: $role was refused for another reason: $(tr '\n' ' ' < "$WORK/login.out" | cut -c1-200)"
    done
    pass "recovery $label: promoted at its target; finapp_app, finapp_migrator and finapp_reporting refused from its first start, and NOLOGIN before any check"
    return 0
}

# archive_copy <volume> <plant>: the run's archive copied into a volume of its own, and the plant made in the copy alone: the first
# segment a recovery fetches after the base backup withheld (wal-gap), or one byte of its ciphertext changed with its digest kept
# (wal-damaged).
archive_copy() {
    dock volume create "$1" >/dev/null || cannot "the archive's copy $1 could not be made"
    dock run --rm --mount "type=volume,source=$ARCHIVE_VOLUME,target=/from,readonly" --mount "type=volume,source=$1,target=/to" \
        --env SEGMENT="$FIRST_AFTER_BASE" --env PLANT="$2" --entrypoint sh "$IMAGE" -c '
            set -eu
            cp -a /from/. /to/
            test -f "/to/$SEGMENT.enc"
            case $PLANT in
                wal-gap) rm "/to/$SEGMENT.enc" ;;
                wal-damaged) printf "\377" | dd of="/to/$SEGMENT.enc" bs=1 seek=4096 conv=notrunc 2>/dev/null ;;
            esac
            chown -R postgres:postgres /to' >/dev/null 2>&1 \
        || cannot "plant $2: the archive's copy could not be planted"
}

# ---------------------------------------------------------------------------------------------------
# The restore: verified with the roles held, then the gate, then released.
# ---------------------------------------------------------------------------------------------------
step restore
: > "$ART/verdicts-restore.txt"
restore restore counter
compare "$NAME-restore" restore
verify "$NAME-restore" restore
if grep -q '^FAIL|' "$ART/verdicts-restore.txt"; then
    fail "the restore is not financially whole, so it stays held: $(grep '^FAIL|' "$ART/verdicts-restore.txt" | tr '\n' ';' | cut -c1-600)"
fi
# The verification's checks and compare()'s (flywayValidate, the counts, the catalogue, the schema, the sequences), every one a pass.
[ "$(grep -c '^PASS|' "$ART/verdicts-restore.txt")" = "$((VERIFIED_CHECKS + COMPARED))" ] \
    || cannot "the restore has $(grep -c '^PASS|' "$ART/verdicts-restore.txt") passes, not $((VERIFIED_CHECKS + COMPARED))"
pass "the restore is financially whole: $(cut -d'|' -f2 "$ART/verdicts-restore.txt" | paste -sd, -)"
gate "$NAME-restore" restore "$INSTANT"
dock rm -f -v "$NAME-restore" >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------------------------------
# The point-in-time recovery to T, the instant before the statement and the bad write (seeded source): the bad write is not in it,
# every count is the backup's at T, every check passes with the roles held, and then the gate.
# ---------------------------------------------------------------------------------------------------
if [ -z "$KIND_NODE" ]; then
    step point-in-time
    : > "$ART/verdicts-pitr.txt"
    pitr pitr "$ARCHIVE_VOLUME" "$T" || { tail -20 "$ART/recovery-pitr.log" >&2; fail "the recovery to $T did not reach it: see recovery-pitr.log"; }
    [ "$(sql "$NAME-pitr" "SELECT count(*) FROM ledger.journal_line WHERE id = '$BAD_LINE'")" = 1 ] \
        || fail "the recovery to $T does not hold the journal line the bad write after it removed"
    compare "$NAME-pitr" pitr
    verify "$NAME-pitr" pitr
    if grep -q '^FAIL|' "$ART/verdicts-pitr.txt"; then
        fail "the recovery to $T is not financially whole, so it stays held: $(grep '^FAIL|' "$ART/verdicts-pitr.txt" | tr '\n' ';' | cut -c1-600)"
    fi
    [ "$(grep -c '^PASS|' "$ART/verdicts-pitr.txt")" = "$((VERIFIED_CHECKS + COMPARED))" ] \
        || cannot "the recovery has $(grep -c '^PASS|' "$ART/verdicts-pitr.txt") passes, not $((VERIFIED_CHECKS + COMPARED))"
    pass "the recovery to $T holds the line the bad write removed and is financially whole: $(cut -d'|' -f2 "$ART/verdicts-pitr.txt" | paste -sd, -)"
    gate "$NAME-pitr" pitr "$T"
    dock rm -f -v "$NAME-pitr" >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------------------------------------------
# The plants: each kind of corruption in a restore of its own, refused by exactly the checks named, and never released. Ten are
# built so that one check alone can see them, which shows each of those checks working on its own; a dropped grant is seen by the
# catalogue and the schema each; the phase gate's, a line gone from the journal, must be seen by every check that reads the lines.
# key-version-missing changes nothing in the restore: it is verified with a key ring that lost version 2, as a restore whose key store
# lost a version would be. The names are in the order the verdicts are written.
# ---------------------------------------------------------------------------------------------------
expected_check() {
    case $1 in
        unbalanced-entry) echo "trial balance per currency" ;;
        balance-disagrees) echo "projection check" ;;
        missing-row) echo "row counts equal to the source's" ;;
        grant-dropped) echo "catalogue equal to the source's,schema equal to the source's" ;;
        role-altered) echo "catalogue equal to the source's" ;;
        trigger-dropped) echo "schema equal to the source's" ;;
        sequence-behind) echo "sequences at or past the source's" ;;
        history-checksum) echo "flywayValidate" ;;
        gl-rule-repointed) echo "GL = ledger per period" ;;
        counter-not-moved) echo "source-join census" ;;
        journal-line-removed) echo "row counts equal to the source's,trial balance per currency,projection check,source-join census" ;;
        key-version-missing) echo "evidence held by the key rings" ;;
    esac
}

for name in $PLANTS; do
    step "plant $name"
    : > "$ART/verdicts-$name.txt"
    if [ "$name" = counter-not-moved ]; then
        restore "$name" no-counter
    else
        restore "$name" counter
    fi
    planted=$(plant "$NAME-$name" "$name" | tail -1)
    [ "$planted" = '1 1 1' ] || cannot "plant $name did not land whole: $planted"
    compare "$NAME-$name" "$name"
    if [ "$name" = key-version-missing ]; then
        VERIFY_RING=without-v2
        verify "$NAME-$name" "$name"
        VERIFY_RING=whole
    else
        verify "$NAME-$name" "$name"
    fi
    expected=$(expected_check "$name")
    failing=$(grep '^FAIL|' "$ART/verdicts-$name.txt" | cut -d'|' -f2 | paste -sd, -)
    if [ "$failing" != "$expected" ]; then
        fail "plant $name: refused by [${failing:-nothing}], where only [$expected] must refuse it"
    fi
    if login "$NAME-$name" finapp_app "${generated_for_app}"; then
        fail "plant $name: the refused restore let finapp_app log in"
    fi
    pass "plant $name: refused by [$expected] and by no other check ($(grep '^FAIL|' "$ART/verdicts-$name.txt" | cut -d'|' -f3 | head -1 | cut -c1-200)); never released"
    dock rm -f -v "$NAME-$name" >/dev/null 2>&1 || true
done

# The recovery's plants: the archive's first segment after the base backup withheld, or damaged under its kept digest. PostgreSQL
# refuses to end a recovery short of its target, so the server stops, no platform role ever reaches it, and nothing is compared.
for name in $PITR_PLANTS; do
    step "plant $name"
    archive_copy "$NAME-$name-archive" "$name"
    if pitr "$name" "$NAME-$name-archive" "$T"; then
        fail "plant $name: the recovery passed the archive's $name and was promoted"
    fi
    grep -q "$NOT_REACHED" "$ART/recovery-$name.log" \
        || { tail -20 "$ART/recovery-$name.log" >&2; cannot "plant $name: the recovery stopped for another reason: see recovery-$name.log"; }
    pass "plant $name: the recovery refused to pass it ($NOT_REACHED, at $FIRST_AFTER_BASE); its server stopped and no platform role reached it"
    dock rm -f -v "$NAME-$name" >/dev/null 2>&1 || true
    dock volume rm -f "$NAME-$name-archive" >/dev/null 2>&1 || true
done

no_plaintext_at_rest
log "every assertion held: $(grep -c '^PASS' "$ART/assertions.tsv") passed; rates in rates.tsv: $(paste -sd';' "$ART/rates.tsv")"
exit 0
