#!/bin/sh
# Rehearses OPERATIONS_RUNBOOK.md section 11.4, a failed migration, on a throwaway PostgreSQL and the migrator image as built
# (P15-TSK-032; DATA_MIGRATIONS.md sections 2.1 and 3.1). migrator-verify.sh's pattern: a database of its own on a random host
# port, in a container of its own, nothing of a developer's compose stack touched, every credential generated for the run.
#
# The migrations it plants are never the repository's. They are written into the run's temporary directory and reach the image
# the way migrator-verify.sh's do, copied into a stopped container's filesystem, so the program carries no test-only switch; on
# the developer's path they are a second Flyway location named on the Gradle command line.
#
# The deployed path, the image, and a migration that depends on data (the usual case):
#   release N - 1  every migration and a plant table applied; rows written that the next release's CHECK refuses.
#   release N      an expand step, a CHECK the rows violate, and a migration of a later schema: exit 10 on the first attempt,
#                  failure=DEFINITE; the expand step applied and kept; no history row for the CHECK and no failed row; the
#                  constraint absent; the later schema's migration never reached; the rows' values not in the log; and the
#                  application's own read of what is applied (the readiness guard's) showing the schema short of the CHECK, older
#                  than a replica of N expects.
#   again          the Job run again with nothing fixed: exit 10 the same way, nothing applied, the history unchanged.
#   ahead          in an environment that had applied the CHECK, a fix with a lower version is refused at validation.
#   the fix        a migration below the CHECK and above the last applied fixes the rows: exit 0, the fix, the CHECK and the later
#                  schema applied in that order, the constraint validated; a second run applies nothing.
#
# A migration Flyway runs outside a transaction, which the build refuses in the repository (EveryMigrationRunsInATransactionTest).
# This is what the refusal saves a deployment from, on the pinned Flyway with PostgreSQL's default transactional lock, which neither
# the build nor the image changes. Flyway writes a failed row inside the transaction that holds that lock, and the transaction
# rolls back: so such a migration leaves NO row, failed or not, while its effect stays.
#   on the image   CREATE UNIQUE INDEX CONCURRENTLY over duplicate values: exit 10, DEFINITE; no row; the index left INVALID.
#                  Run again: exit 10 on the leftover, "already exists". On clean rows, the leftover dropped: the index waits for
#                  the runner's own lock-holding session and the runner's lock_timeout ends it, exit 10, INVALID again, no row.
#                  Forward: the leftover dropped by its owner and the never-applied migration corrected to build the index in its
#                  transaction: exit 0, the index valid.
#   on Gradle      the developer's path: over duplicates, no row and an INVALID index; the next flywayMigrate trips over it,
#                  flywayValidate calls the migration pending, and flywayRepair finds no failed row and changes nothing. On clean
#                  rows the index waits for Flyway's own session with no bound at all, and is cancelled by hand. Forward as above.
#
# The migrator runs in the database container's network namespace, so 127.0.0.1 is the database and the image's loopback rule
# holds as written.
#
# Usage:   infra/scripts/migration-failure-rehearsal.sh [image]   (default: finapp-migrator:<revision>, migrator-image-build.sh's tag)
# Needs:   Docker, a JDK for Gradle. Generates its own throwaway credentials and prints none of them.
# Exit:    0 every assertion held; 1 an assertion failed; 2 a step could not run.
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
cd "$ROOT"

# The Job contract's memory limit (DATA_MIGRATIONS.md section 2.1), as migrator-verify.sh runs it.
MIGRATOR_MEMORY=512m

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

# A path as the JVM on this machine reads it: Git Bash's /tmp is not a Windows path.
native() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s' "$1"
    fi
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

NAME="finapp-migration-failure-$$"
PG="$NAME-postgres"
WORK=$(mktemp -d)
cleanup() {
    dock ps -aq --filter "name=$NAME" 2>/dev/null | while read -r container; do
        dock rm -f "$container" >/dev/null 2>&1 || true
    done
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 2' INT TERM HUP PIPE

# ---------------------------------------------------------------------------------------------------------------------------
# The plants: <schema>/<file> under $PLANTS, each one a rehearsal's own and never the repository's.
# ---------------------------------------------------------------------------------------------------------------------------
PLANTS="$WORK/plants"
plant() {
    mkdir -p "$PLANTS/$1"
    printf '%s\n' "$3" > "$PLANTS/$1/$2"
}

plant ledger V9001__a_rehearsal_limit.sql \
"-- The failed-migration rehearsal's own table (infra/scripts/migration-failure-rehearsal.sh), never the repository's.
CREATE TABLE ledger.rehearsal_limit (id integer PRIMARY KEY, ceiling_minor bigint NOT NULL);"
plant ledger V9002__a_rehearsal_limit_carries_a_note.sql \
"-- Release N's expand step: applied before the CHECK fails, and kept.
ALTER TABLE ledger.rehearsal_limit ADD COLUMN note text;"
plant ledger V9004__a_rehearsal_limit_is_never_negative.sql \
"-- finapp-contract: first-conforming-release=ANY; waives=CHECK; reason=the rehearsal's plant, as a real one would carry it
-- A CHECK that depends on the rows a database holds: it passed in the build, on no rows, and fails here.
ALTER TABLE ledger.rehearsal_limit ADD CONSTRAINT rehearsal_limit_never_negative CHECK (ceiling_minor >= 0);"
plant transfers V9001__a_later_schema_in_the_same_release.sql \
"-- Release N's migration in a schema after ledger's: a run that stops at ledger never reaches it.
CREATE TABLE transfers.rehearsal_later (id integer PRIMARY KEY);"
plant ledger V9003__rehearsal_limits_made_valid.sql \
"-- The forward fix: below the failed version and above the last applied, so outOfOrder=false lets it run first.
UPDATE ledger.rehearsal_limit SET ceiling_minor = 0, note = 'raised to zero by the rehearsal''s fix' WHERE ceiling_minor < 0;"
# The index outside a transaction, on the image: its table, a data fix below it, and the index built concurrently.
plant ledger V9201__a_rehearsal_code.sql \
"-- The image's plant table for a migration outside a transaction (infra/scripts/migration-failure-rehearsal.sh).
CREATE TABLE ledger.rehearsal_code (id integer PRIMARY KEY, code text NOT NULL);"
plant ledger V9220__rehearsal_codes_made_unique.sql \
"-- A data fix below the index and above the last applied.
UPDATE ledger.rehearsal_code c SET code = c.code || '-' || c.id
  WHERE EXISTS (SELECT 1 FROM ledger.rehearsal_code e WHERE e.code = c.code AND e.id < c.id);"
plant ledger V9230__a_rehearsal_code_is_unique.sql \
"-- Outside a transaction: Flyway's PostgreSQL parser runs CREATE INDEX CONCURRENTLY so, and the build refuses it in the repository.
CREATE UNIQUE INDEX CONCURRENTLY rehearsal_code_unique ON ledger.rehearsal_code (code);"
# The same on the developer's path.
plant ledger V9101__a_rehearsal_reference.sql \
"-- The developer's-path plant table (infra/scripts/migration-failure-rehearsal.sh), never the repository's.
CREATE TABLE ledger.rehearsal_reference (id integer PRIMARY KEY, reference text NOT NULL);"
plant ledger V9120__rehearsal_references_made_unique.sql \
"-- A data fix below the index and above the last applied.
UPDATE ledger.rehearsal_reference r SET reference = r.reference || '-' || r.id
  WHERE EXISTS (SELECT 1 FROM ledger.rehearsal_reference e WHERE e.reference = r.reference AND e.id < r.id);"
plant ledger V9130__a_rehearsal_reference_is_unique.sql \
"-- Outside a transaction: Flyway's PostgreSQL parser runs CREATE INDEX CONCURRENTLY so, and the build refuses it in the repository.
CREATE UNIQUE INDEX CONCURRENTLY rehearsal_reference_unique ON ledger.rehearsal_reference (reference);"

RELEASE_N1="ledger/V9001__a_rehearsal_limit.sql"
RELEASE_N="$RELEASE_N1 ledger/V9002__a_rehearsal_limit_carries_a_note.sql ledger/V9004__a_rehearsal_limit_is_never_negative.sql transfers/V9001__a_later_schema_in_the_same_release.sql"
FIXED="$RELEASE_N ledger/V9003__rehearsal_limits_made_valid.sql"
OUTSIDE_TABLE="$RELEASE_N1 ledger/V9201__a_rehearsal_code.sql"
OUTSIDE="$OUTSIDE_TABLE ledger/V9230__a_rehearsal_code_is_unique.sql"
OUTSIDE_FIXED="$OUTSIDE_TABLE ledger/V9220__rehearsal_codes_made_unique.sql ledger/V9230__a_rehearsal_code_is_unique.sql"

# ---------------------------------------------------------------------------------------------------------------------------
# The database and its helpers.
# ---------------------------------------------------------------------------------------------------------------------------
echo "== a throwaway PostgreSQL $POSTGRES_VERSION on a random host port"
dock run --detach --name "$PG" --publish 127.0.0.1::5432 --env POSTGRES_PASSWORD "postgres:$POSTGRES_VERSION" >/dev/null \
    || cannot "PostgreSQL did not start"
PORT=$(dock port "$PG" 5432/tcp | head -1 | sed 's/.*://' | tr -d '\r')
[ -n "$PORT" ] || cannot "no host port for PostgreSQL"

# psql_as <role> <password> <database> <statement>: over TCP, so the init's Unix-socket-only server cannot answer for the real
# one. Docker copies the password from this process's environment, so no command line and no `docker inspect` holds it. Every
# variable here is the function's own name: a shell function shares its caller's variables, and a caller's loop over databases
# must not find its own variable moved.
psql_as() {
    # Through a variable, not a pipe: a pipeline's status is its last command's, and this one's failures must be seen.
    psql_answer=$(PGPASSWORD="$2" MSYS_NO_PATHCONV=1 docker exec --env PGPASSWORD "$PG" \
        psql -h 127.0.0.1 -U "$1" -d "$3" -v ON_ERROR_STOP=1 -AtX -c "$4") || return $?
    [ -z "$psql_answer" ] || printf '%s\n' "$psql_answer" | tr -d '\r'
}

# As the bootstrap superuser: the plants' rows, and every read of the catalogue and the history.
sql() {
    psql_as postgres "${POSTGRES_PASSWORD}" "$1" "$2"
}

# As a replica reads: the application's role, through the readiness guard's own function.
app_sql() {
    psql_as finapp_app "${FINAPP_DB_APP_PASSWORD}" "$1" "$2"
}

# As the schema's owner, the migrator, which is how a person removes what a failed migration left.
owner_sql() {
    psql_as finapp_migrator "${FINAPP_DB_MIGRATOR_PASSWORD}" "$1" "$2"
}

waited=0
until sql postgres 'SELECT 1' >/dev/null 2>&1; do
    waited=$((waited + 1))
    [ "$waited" -le 90 ] || cannot "PostgreSQL did not become ready"
    sleep 1
done

url() {
    printf 'jdbc:postgresql://127.0.0.1:5432/%s' "$1"
}

# The one-time step, per database: the roles, and this database's rights for them.
bootstrap() {
    bootstrap_exit=0
    dock run --rm --network "container:$PG" --read-only --tmpfs /tmp --cap-drop ALL --security-opt no-new-privileges \
        --env FINAPP_DB_URL="$(url "$1")" --env FINAPP_DB_MASTER_USER --env FINAPP_DB_MASTER_PASSWORD \
        --env FINAPP_DB_MIGRATOR_PASSWORD --env FINAPP_DB_APP_PASSWORD --env FINAPP_DB_REPORTING_PASSWORD \
        --entrypoint /usr/bin/java "$IMAGE" -cp '/migrator/lib/*' com.finapp.migrator.RoleBootstrapMain \
        > "$WORK/bootstrap-$1.log" 2>&1 || bootstrap_exit=$?
    [ "$bootstrap_exit" = 0 ] || { tail -40 "$WORK/bootstrap-$1.log" >&2; cannot "the role bootstrap of $1 exited $bootstrap_exit"; }
    [ "$(sql "$1" "SELECT has_database_privilege('finapp_migrator', current_database(), 'CREATE')")" = t ] \
        || cannot "the bootstrap did not give the migrator its rights on $1"
}

# image <database> <log> [<schema>/<file> ...]: the image run once, as the Job runs it, with each plant added to its filesystem.
# Not --read-only, because docker cp refuses that. Returns the image's exit code.
image() {
    image_db=$1
    image_log=$2
    shift 2
    image_container="$NAME-$image_log"
    dock create --name "$image_container" --network "container:$PG" --cap-drop ALL --security-opt no-new-privileges \
        --memory "$MIGRATOR_MEMORY" --env FINAPP_DB_URL="$(url "$image_db")" --env FINAPP_DB_MIGRATOR_PASSWORD "$IMAGE" >/dev/null \
        || cannot "the migrator's container for $image_log could not be created"
    for image_plant in "$@"; do
        tar -C "$PLANTS/${image_plant%%/*}" -cf - "${image_plant#*/}" | dock cp - "$image_container:/migrator/sql/${image_plant%%/*}" \
            || cannot "$image_plant could not be added to the image's filesystem"
    done
    image_exit=0
    dock start --attach "$image_container" > "$WORK/$image_log.log" 2>&1 || image_exit=$?
    dock rm -f "$image_container" >/dev/null 2>&1 || true
    return "$image_exit"
}

# expect_exit <wanted> <got> <what> [log]
expect_exit() {
    if [ "$2" != "$1" ]; then
        [ "$#" -lt 4 ] || tail -40 "$4" >&2
        fail "$3: exit $2, not $1"
    fi
}

# expect_failure <got> <what> <log>: Gradle's failures are its own exit codes, so anything but 0.
expect_failure() {
    if [ "$1" = 0 ]; then
        tail -40 "$3" >&2
        fail "$2: exit 0, where it must fail"
    fi
}

expect_log() {
    grep -q -- "$2" "$WORK/$1.log" || { tail -40 "$WORK/$1.log" >&2; fail "$3"; }
}

# The rehearsal's own versions in a schema's history, in the order they were installed: version:success.
plants_in() {
    sql "$1" "SELECT coalesce(string_agg(version || ':' || success, ' ' ORDER BY installed_rank), 'none')
        FROM $2.flyway_schema_history WHERE version LIKE '9%' AND length(version) = 4"
}

# The rehearsal's versions the readiness guard counts as applied, read as the application's role.
guard_reads() {
    app_sql "$1" "SELECT coalesce(string_agg(version, ' ' ORDER BY version), 'none') FROM platform.applied_migration_versions()
        WHERE schema_name = '$2' AND version LIKE '9%' AND length(version) = 4"
}

# Every row of a schema's history, null-safe, so that "unchanged" compares the whole of it.
whole_history() {
    sql "$1" "SELECT string_agg(concat_ws(':', installed_rank, version, checksum, success, installed_by), ' ' ORDER BY installed_rank)
        FROM $2.flyway_schema_history ${3:-}"
}

# Whether the named index is valid: t, f, or nothing when it does not exist.
index_valid() {
    sql "$1" "SELECT i.indisvalid FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'ledger' AND c.relname = '$2'"
}

# What a person does with what a failed migration outside a transaction left: drops it as the schema's owner, within a bound.
drop_leftover() {
    owner_sql "$1" "SET lock_timeout = '3s'; DROP INDEX ledger.$2" >/dev/null
    [ -z "$(index_valid "$1" "$2")" ] || fail "the leftover index $2 is still there"
}

for database in finapp_release finapp_dev; do
    sql postgres "CREATE DATABASE $database" >/dev/null
    bootstrap "$database"
done

# ===========================================================================================================================
# The deployed path: the image, and a CHECK that depends on the data.
# ===========================================================================================================================
echo "== release N - 1: every migration and the plant table, then rows the next release's CHECK refuses"
code=0
image finapp_release n1 $RELEASE_N1 || code=$?
expect_exit 0 "$code" "release N - 1 on an empty database" "$WORK/n1.log"
expect_log n1 'result=APPLIED schemas=[0-9]* applied=' "release N - 1 printed no APPLIED result"
[ "$(plants_in finapp_release ledger)" = "9001:true" ] || fail "release N - 1's plant is not the one applied row: $(plants_in finapp_release ledger)"
# Another environment at the same point, copied before the rows exist: it will apply the CHECK, and so be ahead of the fix. And a
# third, for the migration outside a transaction.
for database in finapp_ahead finapp_outside; do
    sql postgres "CREATE DATABASE $database TEMPLATE finapp_release" >/dev/null
    bootstrap "$database"
done
# Computed, so that the value appears only in the rows and never in a statement Flyway could quote.
sql finapp_release "INSERT INTO ledger.rehearsal_limit SELECT g, CASE WHEN g % 4 = 0 THEN -(2121 * 2) ELSE 500 END FROM generate_series(1, 8) g" >/dev/null
[ "$(sql finapp_release "SELECT count(*) FROM ledger.rehearsal_limit WHERE ceiling_minor < 0")" = 2 ] || fail "the rows the CHECK refuses were not written"
echo "applied, $(sed -n 's/.*result=APPLIED schemas=[0-9]* applied=\([0-9]*\).*/\1/p' "$WORK/n1.log") migrations; two of eight rows below zero"

echo "== release N: an expand step, a CHECK the rows violate, a later schema's migration"
code=0
image finapp_release n $RELEASE_N || code=$?
expect_exit 10 "$code" "release N's run with a CHECK the data violates" "$WORK/n.log"
expect_log n 'schema=ledger attempts=1 failure=DEFINITE' "a definite failure was retried, or not classed DEFINITE"
expect_log n 'result=FAILED schema=ledger' "the run did not say FAILED at ledger"
expect_log n 'is violated by some row' "the migration did not fail for its own reason"
if grep -q '4242' "$WORK/n.log"; then
    fail "a failed migration wrote row data to the Job's log"
fi
if grep -q 'schema=transfers' "$WORK/n.log"; then
    fail "the run went on past the failed schema"
fi
[ "$(plants_in finapp_release ledger)" = "9001:true 9002:true" ] \
    || fail "ledger's history is not N - 1's and the expand step's, applied and kept: $(plants_in finapp_release ledger)"
[ "$(sql finapp_release "SELECT count(*) FROM ledger.flyway_schema_history WHERE NOT success")" = 0 ] \
    || fail "a transactional migration's failure left a failed row: the runbook says it does not"
[ "$(sql finapp_release "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ledger' AND table_name = 'rehearsal_limit' AND column_name = 'note'")" = 1 ] \
    || fail "the expand step's column is not there"
[ "$(sql finapp_release "SELECT count(*) FROM pg_constraint WHERE conname = 'rehearsal_limit_never_negative'")" = 0 ] \
    || fail "the failed CHECK left its constraint"
[ "$(plants_in finapp_release transfers)" = "none" ] || fail "the later schema's migration ran: $(plants_in finapp_release transfers)"
[ "$(guard_reads finapp_release ledger)" = "9001 9002" ] \
    || fail "the readiness guard's read does not show ledger short of the CHECK: $(guard_reads finapp_release ledger)"
[ "$(app_sql finapp_release "SELECT has_table_privilege('ledger.flyway_schema_history', 'SELECT')")" = f ] \
    || fail "the application's role can read the history table, which the guard's function exists to avoid"
echo "exit 10 on the first attempt, DEFINITE; the expand step kept, no row for the CHECK and no failed row, the later schema not reached;"
echo "  no row data in the log; the guard reads ledger at 9002 where N's build expects 9004, so a replica of N stays unready"

echo "== the Job again, nothing fixed"
before=$(whole_history finapp_release ledger)
code=0
image finapp_release again $RELEASE_N || code=$?
expect_exit 10 "$code" "the same release run again" "$WORK/again.log"
expect_log again 'schema=ledger attempts=1 failure=DEFINITE' "the second run did not fail the same way"
if grep -q 'applied=[1-9]' "$WORK/again.log"; then
    fail "the second run applied something: $(grep 'applied=[1-9]' "$WORK/again.log" | head -3)"
fi
[ "$(whole_history finapp_release ledger)" = "$before" ] || fail "the second run changed ledger's history"
echo "exit 10 again, nothing applied, the history unchanged: a retry cannot help, which is why the Job does not retry exit 10"

echo "== ahead: an environment that applied the CHECK refuses a fix below it"
code=0
image finapp_ahead ahead $RELEASE_N || code=$?
expect_exit 0 "$code" "release N where no row violates the CHECK" "$WORK/ahead.log"
[ "$(plants_in finapp_ahead ledger)" = "9001:true 9002:true 9004:true" ] || fail "release N did not apply where the rows allow it: $(plants_in finapp_ahead ledger)"
code=0
image finapp_ahead ahead-fix $FIXED || code=$?
expect_exit 10 "$code" "a fix below a version already applied" "$WORK/ahead-fix.log"
expect_log ahead-fix 'Detected resolved migration not applied to database: 9003' "the fix was not refused at validation"
[ "$(plants_in finapp_ahead ledger)" = "9001:true 9002:true 9004:true" ] || fail "the refused fix changed the history: $(plants_in finapp_ahead ledger)"
echo "exit 10: \"Detected resolved migration not applied to database: 9003\"; outOfOrder=false keeps it out, so this fix is only for"
echo "  environments still behind the CHECK, and one ahead of it is fixed by an audited operational procedure instead"

echo "== the fix: a migration below the CHECK and above the last applied"
code=0
image finapp_release fixed $FIXED || code=$?
expect_exit 0 "$code" "the release with its fix" "$WORK/fixed.log"
expect_log fixed 'result=APPLIED schemas=[0-9]* applied=3$' "the fixed run did not apply the fix, the CHECK and the later schema's migration"
[ "$(plants_in finapp_release ledger)" = "9001:true 9002:true 9003:true 9004:true" ] \
    || fail "the fix and the CHECK were not applied in that order: $(plants_in finapp_release ledger)"
[ "$(plants_in finapp_release transfers)" = "9001:true" ] || fail "the later schema's migration was not applied: $(plants_in finapp_release transfers)"
[ "$(sql finapp_release "SELECT convalidated FROM pg_constraint WHERE conname = 'rehearsal_limit_never_negative'")" = t ] \
    || fail "the CHECK is not there, validated"
[ "$(guard_reads finapp_release ledger)" = "9001 9002 9003 9004" ] || fail "the guard does not read ledger at the CHECK: $(guard_reads finapp_release ledger)"
code=0
image finapp_release settled $FIXED || code=$?
expect_exit 0 "$code" "the run after the fix" "$WORK/settled.log"
expect_log settled 'result=APPLIED schemas=[0-9]* applied=0$' "the run after the fix applied something"
echo "exit 0, applied 3: 9003 then 9004, then transfers' 9001; the CHECK validated; the guard reads ledger at 9004; a second run applies 0"

# ===========================================================================================================================
# A migration outside a transaction, on the image: what the build's refusal saves a deployment from.
# ===========================================================================================================================
echo "== outside a transaction, on the image: a unique index built concurrently over duplicate values"
code=0
image finapp_outside outside-table $OUTSIDE_TABLE || code=$?
expect_exit 0 "$code" "the image's plant table" "$WORK/outside-table.log"
sql finapp_outside "INSERT INTO ledger.rehearsal_code VALUES (1, 'C-1'), (2, 'C-2'), (3, 'C-1')" >/dev/null
before=$(whole_history finapp_outside ledger)
code=0
image finapp_outside outside $OUTSIDE || code=$?
expect_exit 10 "$code" "a unique index built concurrently over duplicate values" "$WORK/outside.log"
expect_log outside 'schema=ledger attempts=1 failure=DEFINITE' "the image did not fail it once, DEFINITE"
expect_log outside '\[non-transactional\] failed' "Flyway did not run the index outside a transaction"
expect_log outside 'could not create unique index' "the index did not fail for its own reason"
[ "$(whole_history finapp_outside ledger)" = "$before" ] \
    || fail "the failed migration wrote to the history, where Flyway's transactional lock rolls its row back: $(plants_in finapp_outside ledger)"
[ "$(index_valid finapp_outside rehearsal_code_unique)" = f ] || fail "the failed build did not leave an INVALID index"
[ "$(guard_reads finapp_outside ledger)" = "9001 9201" ] || fail "the guard's read moved: $(guard_reads finapp_outside ledger)"
echo "exit 10 on the first attempt, DEFINITE; NO history row for 9230, failed or not, and the index left INVALID behind it"

echo "== outside, again: the next Job trips over what the failed one left"
code=0
image finapp_outside outside-again $OUTSIDE || code=$?
expect_exit 10 "$code" "the same migration run again over its leftover" "$WORK/outside-again.log"
expect_log outside-again 'schema=ledger attempts=1 failure=DEFINITE' "the second run did not fail once, DEFINITE"
expect_log outside-again 'relation "rehearsal_code_unique" already exists' "the second run did not trip over the leftover"
[ "$(whole_history finapp_outside ledger)" = "$before" ] || fail "the second run changed the history"
[ "$(index_valid finapp_outside rehearsal_code_unique)" = f ] || fail "the leftover is no longer INVALID"
echo "exit 10, \"already exists\": every later Job is refused by an index no row records, and only a person can drop it"

echo "== outside, on clean rows: the index waits for the runner's own lock, and the runner's bound ends it"
drop_leftover finapp_outside rehearsal_code_unique
code=0
image finapp_outside outside-clean $OUTSIDE_FIXED || code=$?
expect_exit 10 "$code" "the index built concurrently over rows that allow it" "$WORK/outside-clean.log"
expect_log outside-clean 'schema=ledger attempts=1 failure=DEFINITE' "the lock timeout was retried, or not classed DEFINITE"
expect_log outside-clean 'canceling statement due to lock timeout' "the index did not end on the runner's lock_timeout"
[ "$(plants_in finapp_outside ledger)" = "9001:true 9201:true 9220:true" ] \
    || fail "the data fix is not applied alone, or the index has a row: $(plants_in finapp_outside ledger)"
[ "$(sql finapp_outside "SELECT count(*) FROM (SELECT code FROM ledger.rehearsal_code GROUP BY code HAVING count(*) > 1) d")" = 0 ] \
    || fail "the data fix left duplicates"
[ "$(index_valid finapp_outside rehearsal_code_unique)" = f ] || fail "the timed-out build did not leave an INVALID index"
echo "exit 10, DEFINITE, \"canceling statement due to lock timeout\": the data fix applied, the index INVALID again and no row;"
echo "  CREATE INDEX CONCURRENTLY waits for every open transaction, Flyway's own lock-holding one among them, so it cannot finish here"

echo "== outside, forward: the leftover dropped by its owner, the never-applied migration corrected into a transaction"
drop_leftover finapp_outside rehearsal_code_unique
plant ledger V9230__a_rehearsal_code_is_unique.sql \
"-- Corrected: never applied anywhere, so it may change; the index is built in the migration's transaction, as ledger V029 does.
CREATE UNIQUE INDEX rehearsal_code_unique ON ledger.rehearsal_code (code);"
code=0
image finapp_outside outside-forward $OUTSIDE_FIXED || code=$?
expect_exit 0 "$code" "the corrected migration" "$WORK/outside-forward.log"
expect_log outside-forward 'result=APPLIED schemas=[0-9]* applied=1$' "the corrected run did not apply the index alone"
[ "$(plants_in finapp_outside ledger)" = "9001:true 9201:true 9220:true 9230:true" ] \
    || fail "the corrected index was not applied after the fix: $(plants_in finapp_outside ledger)"
[ "$(index_valid finapp_outside rehearsal_code_unique)" = t ] || fail "the corrected index is not valid"
echo "exit 0, applied 1: 9230 in its transaction, the index valid"

# ===========================================================================================================================
# The developer's path: Gradle, and the same migration outside a transaction.
# ===========================================================================================================================
LEDGER_LOCATION=$(native "$ROOT/ledger/src/main/resources/db/migration/ledger")
DEV_LOCATION="$WORK/dev-location"
mkdir -p "$DEV_LOCATION"
# The module's own location and the plants', both named, because a location given on the command line replaces the build's.
LOCATIONS="-Pflyway.locations=filesystem:$LEDGER_LOCATION,filesystem:$(native "$DEV_LOCATION")"

# gradle <log> <task and arguments ...>: the developer's Flyway tasks against finapp_dev, as finapp_migrator.
gradle() {
    gradle_log=$1
    shift
    FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$PORT/finapp_dev" FINAPP_DB_MIGRATOR_USER=finapp_migrator \
        ./gradlew --console=plain "$@" > "$WORK/$gradle_log.log" 2>&1
}

develop() {
    cp "$PLANTS/ledger/$1" "$DEV_LOCATION/"
}

# The pid of a session building the index concurrently that waits on a lock held only by sessions of its own client, each idle
# in a transaction that holds an advisory lock: Flyway's history connection, holding the lock it takes around a migration.
waiting_on_its_own_lock() {
    sql finapp_dev "SELECT a.pid FROM pg_stat_activity a
        WHERE a.datname = 'finapp_dev' AND a.pid <> pg_backend_pid() AND a.state = 'active' AND a.wait_event_type = 'Lock'
          AND a.query LIKE '%CREATE UNIQUE INDEX CONCURRENTLY rehearsal_reference_unique%'
          AND cardinality(pg_blocking_pids(a.pid)) > 0
          AND NOT EXISTS (SELECT 1 FROM unnest(pg_blocking_pids(a.pid)) b(pid) JOIN pg_stat_activity s ON s.pid = b.pid
                WHERE s.application_name IS DISTINCT FROM a.application_name OR s.state <> 'idle in transaction'
                   OR NOT EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = s.pid AND l.locktype = 'advisory' AND l.granted))"
}

echo "== the developer's database: every migration by flywayMigrate, then the plant table"
code=0
gradle dev-head -q flywayMigrate || code=$?
expect_exit 0 "$code" "the build's flywayMigrate on an empty database" "$WORK/dev-head.log"
develop V9101__a_rehearsal_reference.sql
code=0
gradle dev-table -q :ledger:flywayMigrate "$LOCATIONS" || code=$?
expect_exit 0 "$code" "the plant table by the developer's flywayMigrate" "$WORK/dev-table.log"
[ "$(plants_in finapp_dev ledger)" = "9101:true" ] || fail "the location on the command line was not read: $(plants_in finapp_dev ledger)"
sql finapp_dev "INSERT INTO ledger.rehearsal_reference VALUES (1, 'R-1'), (2, 'R-2'), (3, 'R-1')" >/dev/null
echo "every migration applied by Gradle; the plant table, read from the second location; two rows share a reference"

echo "== fails, on Gradle: the same index over duplicate values"
develop V9130__a_rehearsal_reference_is_unique.sql
kept=$(whole_history finapp_dev ledger)
code=0
gradle dev-concurrently -q :ledger:flywayMigrate "$LOCATIONS" || code=$?
expect_failure "$code" "a unique index over duplicate values" "$WORK/dev-concurrently.log"
expect_log dev-concurrently '\[non-transactional\] failed' "Flyway did not run the index outside a transaction"
expect_log dev-concurrently 'could not create unique index' "the index did not fail for its own reason"
[ "$(whole_history finapp_dev ledger)" = "$kept" ] || fail "the failed migration wrote to the history: $(plants_in finapp_dev ledger)"
[ "$(index_valid finapp_dev rehearsal_reference_unique)" = f ] || fail "the failed build did not leave an INVALID index"
[ "$(guard_reads finapp_dev ledger)" = "9101" ] || fail "the guard's read moved: $(guard_reads finapp_dev ledger)"
echo "no history row for 9130, failed or not; the index left INVALID"

echo "== nothing to repair: the next run trips over the leftover, validation calls it pending, flywayRepair changes nothing"
code=0
gradle dev-again -q :ledger:flywayMigrate "$LOCATIONS" || code=$?
expect_failure "$code" "flywayMigrate over the leftover" "$WORK/dev-again.log"
expect_log dev-again 'relation "rehearsal_reference_unique" already exists' "flywayMigrate did not trip over the leftover"
code=0
gradle dev-validate -q :ledger:flywayValidate "$LOCATIONS" || code=$?
expect_failure "$code" "flywayValidate with the migration unrecorded" "$WORK/dev-validate.log"
expect_log dev-validate 'Detected resolved migration not applied to database: 9130' "flywayValidate did not call the migration pending"
if grep -q 'Detected failed migration' "$WORK/dev-validate.log"; then
    fail "flywayValidate found a failed row that the history does not hold"
fi
code=0
gradle dev-repair :ledger:flywayRepair "$LOCATIONS" || code=$?
expect_exit 0 "$code" "flywayRepair" "$WORK/dev-repair.log"
[ "$(whole_history finapp_dev ledger)" = "$kept" ] || fail "flywayRepair changed the history"
[ "$(index_valid finapp_dev rehearsal_reference_unique)" = f ] || fail "something removed the leftover"
echo "\"already exists\"; \"Detected resolved migration not applied to database: 9130\"; flywayRepair exit 0 and the history unchanged:"
echo "  there is no failed row to remove, and what blocks every run is an index no row records"

echo "== hangs, on Gradle: on clean rows the index waits for Flyway's own lock, with no bound on this path"
drop_leftover finapp_dev rehearsal_reference_unique
develop V9120__rehearsal_references_made_unique.sql
rm -f "$WORK/dev-hang.exit"
( hang_exit=0; gradle dev-hang -q :ledger:flywayMigrate "$LOCATIONS" || hang_exit=$?; echo "$hang_exit" > "$WORK/dev-hang.exit" ) &
hanging=$!
waiter=""
waited=0
while [ -z "$waiter" ]; do
    waited=$((waited + 1))
    [ "$waited" -le 180 ] || { kill "$hanging" 2>/dev/null || true; cannot "the index never waited on its own client's lock"; }
    [ ! -e "$WORK/dev-hang.exit" ] || { tail -40 "$WORK/dev-hang.log" >&2; fail "flywayMigrate ended where it was to wait: exit $(cat "$WORK/dev-hang.exit")"; }
    sleep 1
    waiter=$(waiting_on_its_own_lock | head -1)
done
# Still the same session, still waiting on the same lock, a while later: the client holds what its own statement waits for.
sleep 20
[ "$(waiting_on_its_own_lock | head -1)" = "$waiter" ] || fail "the index stopped waiting on its client's own lock within 20 s"
[ ! -e "$WORK/dev-hang.exit" ] || fail "flywayMigrate ended while its index waited"
[ "$(plants_in finapp_dev ledger)" = "9101:true 9120:true" ] || fail "the data fix is not applied before the index: $(plants_in finapp_dev ledger)"
sql finapp_dev "SELECT pg_cancel_backend($waiter)" >/dev/null
wait "$hanging" || true
[ -e "$WORK/dev-hang.exit" ] || cannot "flywayMigrate left no exit status"
expect_failure "$(cat "$WORK/dev-hang.exit")" "the cancelled index" "$WORK/dev-hang.log"
expect_log dev-hang 'canceling statement due to user request' "the index did not end on the cancel"
[ "$(plants_in finapp_dev ledger)" = "9101:true 9120:true" ] || fail "the cancelled index has a row: $(plants_in finapp_dev ledger)"
[ "$(index_valid finapp_dev rehearsal_reference_unique)" = f ] || fail "the cancelled build did not leave an INVALID index"
echo "the data fix applied; the index waited more than 20 s on Flyway's own session, idle in a transaction holding its advisory"
echo "  lock, and was cancelled by hand: no row, the index INVALID"

echo "== forward, on Gradle: the leftover dropped by its owner, the never-applied migration corrected into a transaction"
drop_leftover finapp_dev rehearsal_reference_unique
plant ledger V9130__a_rehearsal_reference_is_unique.sql \
"-- Corrected: never applied anywhere, so it may change; the index is built in the migration's transaction, as ledger V029 does.
CREATE UNIQUE INDEX rehearsal_reference_unique ON ledger.rehearsal_reference (reference);"
develop V9130__a_rehearsal_reference_is_unique.sql
code=0
gradle dev-forward -q :ledger:flywayMigrate "$LOCATIONS" || code=$?
expect_exit 0 "$code" "the corrected migration" "$WORK/dev-forward.log"
[ "$(plants_in finapp_dev ledger)" = "9101:true 9120:true 9130:true" ] || fail "the corrected index was not applied: $(plants_in finapp_dev ledger)"
[ "$(index_valid finapp_dev rehearsal_reference_unique)" = t ] || fail "the corrected index is not valid"
code=0
gradle dev-validated -q :ledger:flywayValidate "$LOCATIONS" || code=$?
expect_exit 0 "$code" "flywayValidate after the correction" "$WORK/dev-validated.log"
[ "$(guard_reads finapp_dev ledger)" = "9101 9120 9130" ] || fail "the guard does not read the index applied: $(guard_reads finapp_dev ledger)"
echo "9130 applied in its transaction, the index valid, flywayValidate clean"

echo "every assertion held: $IMAGE"
