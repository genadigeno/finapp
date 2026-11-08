#!/bin/sh
# Runs the migrator image against a throwaway PostgreSQL and refuses it unless it does what the build does (P15-TSK-004;
# DATA_MIGRATIONS.md section 2.1; THREAT_MODEL.md T7, T12, T13, T33). A database of its own, on a random host port, in a
# container of its own: nothing here can touch a developer's compose stack.
#
#   the bootstrap   creates the three roles with the local script's attributes, hashed client-side, the database's grants
#                   and PUBLIC revoked; run again it changes nothing; a role holding a right it must not is refused.
#   the build's     ./gradlew flywayMigrate into one database, the image into another: every schema's history identical
#   equal           (rank, version, description, type, script, checksum, installed_by, success) and pg_dump --schema-only
#                   identical. The image never sees the build's database.
#   idempotent      a second run applies nothing and rewrites no history.
#   serialised      N runs at once on an empty database (MIGRATOR_VERIFY_RACERS, default 2): every run exits 0, the applied
#                   counts sum to the number of migrations - each applied exactly once, whichever run took it - and the
#                   history is identical to the build's.
#   bounded         a table held by another session: the run fails with exit 11 inside its bound rather than queueing, and
#                   leaves no history behind. A statement that fails: exit 10, once, and no failed row, because a
#                   transactional migration rolls back (Flyway writes a failed row only for a non-transactional one).
#   refused         the superuser's credential: exit 12, nothing changed.
#   the debt rows   a TRUNCATE as the migrator refused on every reconciliation and settlement table; a shadowing schema
#                   first in a role's path changes what an unqualified clock reads and not what lending's does.
#
# The migrator runs in the database container's network namespace, so 127.0.0.1 is the database and the image's loopback
# rule holds as written, restricted as a pod will be: read-only root, /tmp a tmpfs, every capability dropped.
#
# Usage:   infra/scripts/migrator-verify.sh [image]   (default: finapp-migrator:<revision>, migrator-image-build.sh's tag)
# Needs:   Docker, a JDK for Gradle. Generates its own throwaway credentials and prints none of them.
# Exit:    0 every assertion held; 1 an assertion failed; 2 a step could not run.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

RACERS=${MIGRATOR_VERIFY_RACERS:-2}
# The Job contract's memory limit (DATA_MIGRATIONS.md section 2.1): every run below is under it, so the figure is measured.
MIGRATOR_MEMORY=512m
# 5 attempts x (3 s lock_timeout) + 4 x 5 s apart, plus a JVM's start and the catalogue reads, with room for a loaded runner.
LOCK_BOUND_SECONDS=120

fail() {
    echo "!! $*" >&2
    exit 1
}

cannot() {
    echo "?? $*" >&2
    exit 2
}

dock() {
    MSYS_NO_PATHCONV=1 docker "$@"
}

if [ "$#" -ge 1 ]; then
    IMAGE=$1
else
    REVISION=$(git rev-parse HEAD)
    if [ -n "$(git status --porcelain)" ]; then
        REVISION="$REVISION-dirty"
    fi
    IMAGE="finapp-migrator:$REVISION"
fi
dock image inspect "$IMAGE" >/dev/null 2>&1 || cannot "no image $IMAGE: build it with infra/scripts/migrator-image-build.sh"

POSTGRES_VERSION=$(sed -n 's/^postgresImage = "\(.*\)".*$/\1/p' gradle/libs.versions.toml | tr -d '\r')
[ -n "$POSTGRES_VERSION" ] || cannot "postgresImage not found in the version catalog"
SCHEMAS=$(grep -v '^#' migrator/src/main/resources/com/finapp/migrator/schema-order.txt | sed '/^$/d' | tr -d '\r')

secret() {
    od -An -N24 -tx1 /dev/urandom | tr -d ' \n'
}

generated_for_master=$(secret)
generated_for_migrator=$(secret)
generated_for_app=$(secret)
generated_for_reporting=$(secret)
POSTGRES_PASSWORD=${generated_for_master}
FINAPP_DB_MASTER_PASSWORD=${generated_for_master}
FINAPP_DB_MASTER_USER=postgres
FINAPP_DB_MIGRATOR_PASSWORD=${generated_for_migrator}
FINAPP_DB_APP_PASSWORD=${generated_for_app}
FINAPP_DB_REPORTING_PASSWORD=${generated_for_reporting}
export POSTGRES_PASSWORD FINAPP_DB_MASTER_PASSWORD FINAPP_DB_MASTER_USER FINAPP_DB_MIGRATOR_PASSWORD FINAPP_DB_APP_PASSWORD \
    FINAPP_DB_REPORTING_PASSWORD

NAME="finapp-migrator-verify-$$"
PG="$NAME-postgres"
WORK=$(mktemp -d)
cleanup() {
    dock ps -aq --filter "name=$NAME" 2>/dev/null | while read -r container; do
        dock rm -f "$container" >/dev/null 2>&1 || true
    done
    rm -rf "$WORK"
}
trap cleanup EXIT

echo "== a throwaway PostgreSQL $POSTGRES_VERSION on a random host port"
dock run --detach --name "$PG" --publish 127.0.0.1::5432 --env POSTGRES_PASSWORD "postgres:$POSTGRES_VERSION" >/dev/null \
    || cannot "PostgreSQL did not start"
PORT=$(dock port "$PG" 5432/tcp | head -1 | sed 's/.*://' | tr -d '\r')
[ -n "$PORT" ] || cannot "no host port for PostgreSQL"

# As the bootstrap superuser, over TCP so the init's Unix-socket-only server cannot answer for the real one.
sql() {
    db=$1
    shift
    # Through a variable, not a pipe: a pipeline's status is its last command's, and this one's failures must be seen.
    answer=$(dock exec --env PGPASSWORD="${POSTGRES_PASSWORD}" "$PG" \
        psql -h 127.0.0.1 -U postgres -d "$db" -v ON_ERROR_STOP=1 -AtX -c "$*") || return $?
    [ -z "$answer" ] || printf '%s\n' "$answer" | tr -d '\r'
}

waited=0
until sql postgres 'SELECT 1' >/dev/null 2>&1; do
    waited=$((waited + 1))
    [ "$waited" -le 90 ] || cannot "PostgreSQL did not become ready"
    sleep 1
done

for db in finapp_build finapp_image finapp_race; do
    sql postgres "CREATE DATABASE $db" >/dev/null
done

url() {
    printf 'jdbc:postgresql://127.0.0.1:5432/%s' "$1"
}

bootstrap() {
    dock run --rm --network "container:$PG" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
        --env FINAPP_DB_URL="$(url "$1")" --env FINAPP_DB_MASTER_USER --env FINAPP_DB_MASTER_PASSWORD \
        --env FINAPP_DB_MIGRATOR_PASSWORD --env FINAPP_DB_APP_PASSWORD --env FINAPP_DB_REPORTING_PASSWORD \
        --entrypoint /usr/bin/java "$IMAGE" -cp '/migrator/lib/*' com.finapp.migrator.RoleBootstrapMain
}

# migrator <database> [docker run flags ...]
migrator() {
    db=$1
    shift
    dock run --rm --network "container:$PG" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
        --memory "$MIGRATOR_MEMORY" --env FINAPP_DB_URL="$(url "$db")" --env FINAPP_DB_MIGRATOR_PASSWORD "$@" "$IMAGE"
}

# expect_exit <wanted> <got> <what> [log]
expect_exit() {
    wanted=$1
    got=$2
    what=$3
    if [ "$got" != "$wanted" ]; then
        [ "$#" -lt 4 ] || tail -40 "$4" >&2
        fail "$what: exit $got, not $wanted"
    fi
}

echo "== the bootstrap"
code=0
bootstrap finapp_build > "$WORK/bootstrap.log" 2>&1 || code=$?
expect_exit 0 "$code" "the first bootstrap" "$WORK/bootstrap.log"
grep -q 'role=finapp_migrator created' "$WORK/bootstrap.log" || fail "the bootstrap did not create the migrator's role"
grep -q 'role=finapp_app created' "$WORK/bootstrap.log" || fail "the bootstrap did not create the application's role"
grep -q 'role=finapp_reporting created' "$WORK/bootstrap.log" || fail "the bootstrap did not create the reporting role"
attributes=$(sql postgres "SELECT string_agg(rolname || ':' || rolcanlogin || rolsuper || rolcreatedb || rolcreaterole || rolinherit
    || rolbypassrls || rolreplication, ' ' ORDER BY rolname) FROM pg_roles WHERE rolname LIKE 'finapp\_%'")
[ "$attributes" = "finapp_app:truefalsefalsefalsefalsefalsefalse finapp_migrator:truefalsefalsefalsefalsefalsefalse finapp_reporting:truefalsefalsefalsefalsefalsefalse" ] \
    || fail "the roles' attributes are not LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION: $attributes"
hashed=$(sql postgres "SELECT count(*) FROM pg_authid WHERE rolname LIKE 'finapp\_%' AND rolpassword LIKE 'SCRAM-SHA-256\$%'")
[ "$hashed" = 3 ] || fail "$hashed of the 3 roles hold a SCRAM verifier"
grants=$(sql finapp_build "SELECT has_database_privilege('finapp_migrator', current_database(), 'CREATE'),
    has_database_privilege('finapp_app', current_database(), 'CONNECT'), has_database_privilege('finapp_reporting', current_database(), 'CONNECT'),
    has_database_privilege('finapp_app', current_database(), 'CREATE'), has_database_privilege('finapp_reporting', current_database(), 'CREATE'),
    (SELECT count(*) FROM pg_database d, aclexplode(d.datacl) a WHERE d.datname = current_database() AND a.grantee = 0)")
[ "$grants" = "t|t|t|f|f|0" ] || fail "the database's grants are not the local script's: $grants"
code=0
bootstrap finapp_build > "$WORK/bootstrap2.log" 2>&1 || code=$?
expect_exit 0 "$code" "the second bootstrap" "$WORK/bootstrap2.log"
[ "$(grep -c 'present, unprivileged, unchanged' "$WORK/bootstrap2.log")" = 3 ] || fail "the second bootstrap changed something"
sql postgres 'ALTER ROLE finapp_reporting CREATEDB' >/dev/null
code=0
bootstrap finapp_build > "$WORK/bootstrap3.log" 2>&1 || code=$?
expect_exit 12 "$code" "a bootstrap finding a role with a right it must not hold" "$WORK/bootstrap3.log"
sql postgres 'ALTER ROLE finapp_reporting NOCREATEDB' >/dev/null
for db in finapp_image finapp_race; do
    code=0
    bootstrap "$db" > "$WORK/bootstrap-$db.log" 2>&1 || code=$?
    expect_exit 0 "$code" "the bootstrap of $db" "$WORK/bootstrap-$db.log"
done
echo "three roles created with the local script's attributes, SCRAM-hashed, grants as the local script's; idempotent; a wrong role refused"

echo "== the build's flywayMigrate into one database"
FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$PORT/finapp_build" FINAPP_DB_MIGRATOR_USER=finapp_migrator \
    ./gradlew --console=plain -q flywayMigrate > "$WORK/gradle.log" 2>&1 || { tail -40 "$WORK/gradle.log" >&2; fail "the build's flywayMigrate failed"; }

echo "== the image into another"
# The peak of the run's own connections, sampled throughout: the Job contract states the number.
( peak=0
  while [ ! -e "$WORK/first.done" ]; do
      now=$(sql postgres "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'finapp-migrator'" 2>/dev/null || echo 0)
      [ "$now" -le "$peak" ] || peak=$now
      sleep 0.2
  done
  echo "$peak" > "$WORK/first.peak" ) &
sampler=$!
applying_since=$(date +%s)
code=0
migrator finapp_image > "$WORK/first.log" 2>&1 || code=$?
: > "$WORK/first.done"
wait "$sampler"
applying_took=$(($(date +%s) - applying_since))
expect_exit 0 "$code" "the image against an empty database" "$WORK/first.log"
peak=$(cat "$WORK/first.peak")
[ "$peak" -ge 1 ] && [ "$peak" -le 2 ] || fail "the run held $peak connections at its peak; the Job contract says two at most"

history_of() {
    for schema in $SCHEMAS; do
        echo "-- $schema"
        sql "$1" "SELECT installed_rank, version, description, type, script, checksum, installed_by, success
            FROM $schema.flyway_schema_history ORDER BY installed_rank"
    done
}

schema_of() {
    # \restrict lines are random per dump (PostgreSQL 18), and name nothing about the schema.
    dump=$(dock exec --env PGPASSWORD="${POSTGRES_PASSWORD}" "$PG" pg_dump -h 127.0.0.1 -U postgres -d "$1" --schema-only) || return $?
    printf '%s\n' "$dump" | tr -d '\r' | grep -Ev '^\\(un)?restrict '
}

history_of finapp_build > "$WORK/build.history"
history_of finapp_image > "$WORK/image.history"
if ! cmp -s "$WORK/build.history" "$WORK/image.history"; then
    diff "$WORK/build.history" "$WORK/image.history" >&2 || true
    fail "the image's schema history is not the build's (< the build's, > the image's)"
fi
MIGRATIONS=$(grep -c '|SQL|' "$WORK/build.history")
[ "$MIGRATIONS" -gt 0 ] || fail "no migrations were read from the build's history"
[ "$(grep -c '^-- ' "$WORK/build.history")" = "$(printf '%s\n' "$SCHEMAS" | wc -l | tr -d ' ')" ] || fail "a schema's history is missing"
schema_of finapp_build > "$WORK/build.schema"
schema_of finapp_image > "$WORK/image.schema"
if ! cmp -s "$WORK/build.schema" "$WORK/image.schema"; then
    diff "$WORK/build.schema" "$WORK/image.schema" >&2 | head -40 || true
    fail "the image's schema is not the build's"
fi
applied=$(sed -n 's/.*result=APPLIED schemas=[0-9]* applied=\([0-9]*\).*/\1/p' "$WORK/first.log")
[ "$applied" = "$MIGRATIONS" ] || fail "the run says it applied ${applied:-nothing}, the history holds $MIGRATIONS"
echo "schema history identical to the build's: $MIGRATIONS migrations, $(grep -c '^-- ' "$WORK/build.history") schemas; pg_dump --schema-only identical ($(wc -l < "$WORK/build.schema" | tr -d ' ') lines); $applying_took s, peak $peak connections, under a $MIGRATOR_MEMORY limit"

stamps() {
    for schema in $SCHEMAS; do
        sql "$1" "SELECT installed_rank, installed_on FROM $schema.flyway_schema_history ORDER BY installed_rank"
    done
}

echo "== a second run"
stamps finapp_image > "$WORK/before.stamps"
code=0
migrator finapp_image > "$WORK/second.log" 2>&1 || code=$?
expect_exit 0 "$code" "the second run" "$WORK/second.log"
grep -q 'result=APPLIED schemas=[0-9]* applied=0$' "$WORK/second.log" || fail "the second run applied something: $(tail -1 "$WORK/second.log")"
stamps finapp_image > "$WORK/after.stamps"
cmp -s "$WORK/before.stamps" "$WORK/after.stamps" || fail "the second run rewrote the schema history"
echo "applied nothing, history untouched"

echo "== $RACERS runs at once on an empty database"
pids=""
racer=1
while [ "$racer" -le "$RACERS" ]; do
    ( code=0; migrator finapp_race > "$WORK/race.$racer.log" 2>&1 || code=$?; echo "$code" > "$WORK/race.$racer.exit" ) &
    pids="$pids $!"
    racer=$((racer + 1))
done
# shellcheck disable=SC2086
wait $pids
total=0
racer=1
while [ "$racer" -le "$RACERS" ]; do
    if [ "$(cat "$WORK/race.$racer.exit")" != 0 ]; then
        tail -40 "$WORK/race.$racer.log" >&2
        fail "runner $racer of $RACERS exited $(cat "$WORK/race.$racer.exit")"
    fi
    this=$(sed -n 's/.*result=APPLIED schemas=[0-9]* applied=\([0-9]*\).*/\1/p' "$WORK/race.$racer.log")
    [ -n "$this" ] || fail "runner $racer printed no result line"
    echo "  runner $racer applied $this"
    total=$((total + this))
    racer=$((racer + 1))
done
[ "$total" = "$MIGRATIONS" ] || fail "the $RACERS runs applied $total between them, not $MIGRATIONS: a migration was applied twice, or not at all"
history_of finapp_race > "$WORK/race.history"
cmp -s "$WORK/build.history" "$WORK/race.history" || fail "the racing runs' schema history is not the build's"
echo "every run exited 0; applied $total between them, each migration once; schema history identical to the build's"

# A migration the image does not carry, added to a stopped container's filesystem: the only way to have one pending on a
# database the image has finished, with no test-only switch in the program. Not --read-only, because docker cp refuses that.
migrator_with() {
    file=$1
    sql_text=$2
    dir=$(mktemp -d "$WORK/extra.XXXXXX")
    printf '%s\n' "$sql_text" > "$dir/$file"
    container="$NAME-$file"
    dock create --name "$container" --network "container:$PG" --cap-drop ALL --security-opt no-new-privileges \
        --env FINAPP_DB_URL="$(url finapp_image)" --env FINAPP_DB_MIGRATOR_PASSWORD "$IMAGE" >/dev/null
    tar -C "$dir" -cf - "$file" | dock cp - "$container:/migrator/sql/ledger"
    code=0
    dock start --attach "$container" > "$WORK/$file.log" 2>&1 || code=$?
    dock rm -f "$container" >/dev/null 2>&1 || true
    return "$code"
}

echo "== a table held by another session"
dock exec --detach --env PGPASSWORD="${POSTGRES_PASSWORD}" "$PG" psql -h 127.0.0.1 -U postgres -d finapp_image \
    -c "BEGIN; LOCK TABLE ledger.journal_entry IN ACCESS SHARE MODE; SELECT pg_sleep(300);" >/dev/null
waited=0
until [ "$(sql finapp_image "SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation WHERE c.relname = 'journal_entry' AND l.mode = 'AccessShareLock' AND l.granted")" != 0 ]; do
    waited=$((waited + 1))
    [ "$waited" -le 30 ] || cannot "the blocking session did not take its lock"
    sleep 1
done
started=$(date +%s)
code=0
migrator_with V9001__needs_the_journal.sql "ALTER TABLE ledger.journal_entry ADD COLUMN verify_probe boolean;" || code=$?
elapsed=$(($(date +%s) - started))
sql finapp_image "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query LIKE '%pg_sleep(300)%' AND pid <> pg_backend_pid()" >/dev/null
expect_exit 11 "$code" "a migration behind a held lock" "$WORK/V9001__needs_the_journal.sql.log"
[ "$elapsed" -le "$LOCK_BOUND_SECONDS" ] || fail "the run took $elapsed s behind the lock, over its $LOCK_BOUND_SECONDS s bound"
grep -q 'schema=ledger attempts=5 failure=LOCK_CONTENTION' "$WORK/V9001__needs_the_journal.sql.log" || fail "the run did not try five times and stop"
[ "$(sql finapp_image "SELECT count(*) FROM ledger.flyway_schema_history WHERE version = '9001'")" = 0 ] || fail "the timed-out migration left a history row"
[ "$(sql finapp_image "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ledger' AND column_name = 'verify_probe'")" = 0 ] \
    || fail "the timed-out migration left its column"
echo "exit 11 after five attempts, $elapsed s, no row and no column left behind"

echo "== a statement that fails"
code=0
migrator_with V9002__fails.sql "SELECT 1/0;" || code=$?
expect_exit 10 "$code" "a failing migration" "$WORK/V9002__fails.sql.log"
grep -q 'schema=ledger attempts=1 failure=DEFINITE' "$WORK/V9002__fails.sql.log" || fail "a definite failure was retried"
[ "$(sql finapp_image "SELECT count(*) FROM ledger.flyway_schema_history WHERE NOT success")" = 0 ] \
    || fail "a transactional migration's failure left a failed row: the runbook says it does not"
echo "exit 10 on the first attempt; rolled back, no failed row"

echo "== the wrong identity"
code=0
dock run --rm --network "container:$PG" --env FINAPP_DB_URL="$(url finapp_image)" \
    --env FINAPP_DB_MIGRATOR_USER=postgres --env FINAPP_DB_MIGRATOR_PASSWORD="${POSTGRES_PASSWORD}" "$IMAGE" > "$WORK/identity.log" 2>&1 || code=$?
expect_exit 12 "$code" "the migrator run as the superuser" "$WORK/identity.log"
echo "exit 12: the superuser's credential is refused"

echo "== the debt rows, on the migrated database"
for schema in reconciliation settlement; do
    tables=$(sql finapp_image "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = '$schema' AND c.relkind IN ('r','p') AND c.relname <> 'flyway_schema_history' ORDER BY 1")
    count=0
    for table in $tables; do
        out=$(dock exec --env PGPASSWORD="${FINAPP_DB_MIGRATOR_PASSWORD}" "$PG" psql -h 127.0.0.1 -U finapp_migrator -d finapp_image \
            -v ON_ERROR_STOP=1 -AtX -c "TRUNCATE $schema.$table CASCADE" 2>&1 || true)
        case "$out" in
            *"$schema.$table is never truncated"*) count=$((count + 1)) ;;
            *) fail "TRUNCATE $schema.$table CASCADE as the migrator was not refused by its own trigger: $out" ;;
        esac
    done
    expected=33
    [ "$schema" = settlement ] && expected=13
    [ "$count" = "$expected" ] || fail "$schema has $count tables, not $expected"
    echo "TRUNCATE ... CASCADE as the migrator refused by each table's own trigger: $schema, $count of $count"
done
sql finapp_image "CREATE SCHEMA verifyclock; GRANT USAGE ON SCHEMA verifyclock TO PUBLIC;
    CREATE FUNCTION verifyclock.statement_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS \$\$ SELECT timestamptz '2000-01-01' \$\$;
    CREATE FUNCTION verifyclock.transaction_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS \$\$ SELECT timestamptz '2000-01-01' \$\$;" >/dev/null
shadow=$(dock exec --env PGPASSWORD="${FINAPP_DB_APP_PASSWORD}" "$PG" psql -h 127.0.0.1 -U finapp_app -d finapp_image -AtX \
    -c "SET search_path = verifyclock, pg_catalog, \"\$user\", public" \
    -c "SELECT statement_timestamp() < timestamptz '2001-01-01', lending.business_date_latest('UTC') > date '2001-01-01',
        lending.business_date_earliest('UTC') > date '2001-01-01'" | tail -1 | tr -d '\r')
[ "$shadow" = "t|t|t" ] || fail "a shadowing schema first in the path: expected the shadow seen and lending unmoved, got $shadow"
sql finapp_image 'DROP SCHEMA verifyclock CASCADE' >/dev/null
echo "a shadowing schema first on finapp_app's path moves an unqualified clock and not lending's"

echo "every assertion held: $IMAGE"
