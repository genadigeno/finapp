# The rotations (P15-TSK-020; PHASE_15_PLAN.md section 10; SECRET_MANAGEMENT.md sections 5.1 and 5.2; OPERATIONS_RUNBOOK.md sections
# 11.7, 11.29 and 11.33). Sourced by infra/scripts/kind-rehearsal.sh under REHEARSAL_SCOPE=rotations, once release N is deployed, smoked
# and its census green: every function and variable of the harness is in scope here.
#
# WHAT A GREEN RUN SAYS. Each class's credential was rotated on a real API server while the scripted workload ran through the real
# edge, with zero failed requests across every roll, the fleet's connections within its budget and an operator admitted at each peak;
# each rotation's rollback (the previous version restored) was performed once, under the same workload; and after each the census read
# trial balance zero, one effect per key at the platform and at the provider, the outbox drained, nothing parked and GL = ledger. A
# rotation that passed with no traffic would prove nothing, so none runs without the workload.
#
#   setup        the bootstrap step run again with N (re-run safe; the alternating users present), the External Secrets Operator installed
#                from its pinned chart with deploy/operators/external-secrets/values.yaml, and a store generated per run (the operator's
#                `fake` provider, never committed) from which it takes over the two Secrets: its five observations on a cluster;
#   database     the fleet's database credential by alternating users, finapp_app and finapp_reporting together: each alternate holding
#                exactly its carrier's rights (grants.sql), a password given to the inactive one, the store, the sync, a pod started
#                between the sync and the roll reading the new value, the roll, the old users retired with no session left; back again;
#   kafka        the SCRAM credential by add, roll, remove: a second user holding exactly the first's ACLs before the roll, the first's
#                credential and ACLs then removed, so a replica still on it could neither produce nor consume; back again;
#   callback     the payment webhook door's HMAC secret through its window: the window opened by a roll, then current and previous in the
#                store and a roll while the provider still signs with the old secret, the provider switching, the window closing itself
#                at its instant with nothing restarted; back again;
#   key          the MFA key ring: fresh material in the unlisted version 2, listed with every held version's check value, activated,
#                a version 1 row read after it and a version 2 row written; version 2's stored value replaced after it was listed
#                refuses a replica at its start; the pointer moved back and a version 2 row read under it;
#   certificate  the public edge certificate renewed early by cert-manager and served with no controller restarted; the previous
#                certificate restored and served the same way, and what cert-manager does next recorded.
#
# NOTHING GENERATED IS KEPT. Every password, SCRAM user's password, key and the store's contents live in $WORK and in the cluster, both
# removed at exit; the artefacts hold counts, verdicts, timings and check values (which are published values, not secrets). Passwords
# reach PostgreSQL as SCRAM verifiers computed in the client; the broker's crosses kubectl on standard input and reaches kafka-configs as
# its argument inside the test-only broker's own container, whose output stays out of the artefacts.
#
# REHEARSAL_ROTATIONS selects and orders them (default: all, as above). REHEARSAL_PROBE `grant-missing` plants an alternating user missing
# one grant before the grant census; `hmac-previous-dropped` stores the new secret with a previous one that is not the old.

ROTATIONS=${REHEARSAL_ROTATIONS:-database kafka callback key certificate}
STORE="$WORK/store"
mkdir -p "$STORE"
GENERATION=0
ESO_SA=""

# ---------------------------------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------------------------------

random_password() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }
random_key() { head -c 32 /dev/urandom | base64 | tr -d '\n'; }

# Names held in variables, so that no line of this script assigns a value to a credential's name (CommittedConfigurationHoldsNoSecretTest).
APP_DB_ENTRY=FINAPP_DB_APP_PASSWORD
REPORTING_DB_ENTRY=FINAPP_DB_REPORTING_PASSWORD
KAFKA_SCRAM_ENTRY=password
ESO_ENTRY_FIELD=secretKey

# A value of a Secret, decoded, into stdout: for the rehearsal's own use, never an artefact.
secret_value() { kube -n "$1" get "secret/$2" -o "jsonpath={.data.$3}" | base64 -d; }

# The sha256 of a Secret's data, sorted by key: what "it changed" and "it did not" are judged on, never the values.
secret_digest() { kube -n finapp get "secret/$1" -o go-template='{{range $k, $v := .data}}{{$k}}={{$v}}{{"\n"}}{{end}}' | sort | sha256sum | cut -c1-16; }

store_get() { sed -n "s/^$1=//p" "$STORE/$2.env" | head -1; }

store_set() {
    # store_set <entry> <value> <store>: one line per entry, its value never on a command line of another process.
    grep -v "^$1=" "$STORE/$3.env" > "$STORE/$3.tmp" || true
    printf '%s=%s\n' "$1" "$2" >> "$STORE/$3.tmp"
    mv "$STORE/$3.tmp" "$STORE/$3.env"
}

# A SecretStore of the operator's fake provider, generated from one env file: the store's values live in its spec, in etcd, for this run.
store_apply() {
    for store in app migrator; do
        {
            printf 'apiVersion: external-secrets.io/v1\nkind: SecretStore\nmetadata:\n  name: finapp-%s-store\n  namespace: finapp\n  labels:\n    finapp/test-only: "true"\nspec:\n  provider:\n    fake:\n      data:\n' "$store"
            while IFS= read -r line; do
                printf "        - key: %s\n          value: '%s'\n" "${line%%=*}" "$(printf '%s' "${line#*=}" | sed "s/'/''/g")"
            done < "$STORE/$store.env"
        } | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the store finapp-$store-store could not be applied"
    done
    {
        printf 'apiVersion: external-secrets.io/v1\nkind: SecretStore\nmetadata:\n  name: finapp-app-kafka-store\n  namespace: finapp\n  labels:\n    finapp/test-only: "true"\nspec:\n  provider:\n    fake:\n      data:\n'
        printf "        - key: application\n          value: '{\"username\":\"%s\",\"password\":\"%s\"}'\n" "$(store_get username kafka)" "$(store_get password kafka)"
    } | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the store finapp-app-kafka-store could not be applied"
}

# sync <external secret>: step 2 of SECRET_MANAGEMENT.md section 5.2. The object's generation annotation moves; the operator reads the
# store and writes the Secret once; Ready=True with a refresh time after the move is what says it worked.
sync() {
    GENERATION=$((GENERATION + 1))
    before=$(kube -n finapp get "externalsecret/$1" -o jsonpath='{.status.refreshTime}' 2>/dev/null || true)
    sleep 1
    kube -n finapp annotate "externalsecret/$1" "finapp/rotation-generation=$GENERATION" --overwrite >> "$ART/apply.log" 2>&1 || fail "the generation of $1 could not be moved"
    deadline=$(( $(date +%s) + 120 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        state=$(kube -n finapp get "externalsecret/$1" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}|{.status.refreshTime}' 2>/dev/null || true)
        case $state in True\|*) [ "${state#True|}" != "$before" ] && [ -n "${state#True|}" ] && return 0 ;; esac
        sleep 2
    done
    kube -n finapp describe "externalsecret/$1" > "$ART/externalsecret-$1-at-failure.txt" 2>&1 || true
    fail "$1 did not report Ready=True with a refresh after generation $GENERATION: see externalsecret-$1-at-failure.txt"
}

# The configuration a roll carries besides the Secret: the live ConfigMap the overlay generated, as an overlay commit would change it.
CONFIG_MAP=$(kube -n finapp get deployment finapp-app -o jsonpath='{.spec.template.spec.containers[0].envFrom[0].configMapRef.name}')
[ -n "$CONFIG_MAP" ] || cannot "the application's ConfigMap could not be read from its Deployment"
config_set() {
    # config_set <name> <value>, any number of pairs: one patch, so a roll carries them together.
    patch='{"data":{'
    sep=""
    while [ $# -ge 2 ]; do
        patch="$patch$sep\"$1\":\"$2\""
        sep=,
        shift 2
    done
    kube -n finapp patch configmap "$CONFIG_MAP" --type=merge -p "$patch}}" >> "$ART/apply.log" 2>&1 || fail "the ConfigMap could not be changed"
}

# roll <label> <secret|config>: step 3, under the workload and every assertion a rollout owes. After a sync the pod template takes the
# ExternalSecret's generation (never ahead of it, as the manifest policy holds); a change to the ConfigMap alone rolls on a counter of its own.
CONFIGURATION=0
roll() {
    if [ "$2" = config ]; then
        CONFIGURATION=$((CONFIGURATION + 1))
        rollout "$1" "config=$CONFIGURATION" "" no
    else
        rollout "$1" "generation=$GENERATION" "" no
    fi
    assert_floor "$1"
}

app_pods() {
    kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o jsonpath='{range .items[*]}{.metadata.deletionTimestamp}|{.metadata.name}|{.status.podIP}|{.status.conditions[?(@.type=="Ready")].status}|{.metadata.uid}|{.status.containerStatuses[0].restartCount}{"\n"}{end}' 2>/dev/null \
        | awk -F'|' '$1=="" {print $2"|"$3"|"$4"|"$5"|"$6}'
}

# The fleet's sessions, as user|address|count.
fleet_sessions() {
    psql_query -c "SELECT usename || '|' || host(client_addr) || '|' || count(*) FROM pg_stat_activity WHERE application_name = 'finapp' GROUP BY usename, client_addr ORDER BY 1"
}

# The password of a role, written as its SCRAM verifier computed in the client: the password reaches neither a statement nor the server.
set_password() {
    # set_password <role> <password>
    verifier=$(printf '%s\n' "$2" | dock run --rm -i -v "$(dpath "$DIR"):/src:ro" "$JDK_IMAGE" java --source 21 /src/Rehearsal.java /tmp scram-verifier 2>> "$ART/client.log")
    case $verifier in SCRAM-SHA-256\$*) ;; *) cannot "no SCRAM verifier was computed for $1" ;; esac
    printf "ALTER ROLE %s PASSWORD '%s';\n" "$1" "$verifier" | psql_query -f - >> "$ART/rotation-sql.log" 2>&1 || fail "the password of $1 could not be set"
}

# can_sign_in <role> <password>: 0 when PostgreSQL admits the role with that password over TLS, 1 when it refuses.
can_sign_in() {
    answer=$(printf '%s\n%s\n' "$2" "$1" | kube -n finapp exec -i finapp-postgres-0 -c postgres -- /bin/sh -c \
        'IFS= read -r PGPASSWORD; IFS= read -r PGUSER; export PGPASSWORD PGUSER; psql "host=127.0.0.1 dbname=finapp sslmode=require" -X -q -A -t -c "select 1" 2>&1' 2>&1 || true)
    [ "$answer" = 1 ]
}

check_value() {
    # check_value <base64 key on stdin>: the published value, from the tool a person runs (infra/scripts/KeyCheckValue.java).
    dock run --rm -i -v "$(dpath "$ROOT/infra/scripts"):/tools:ro" "$JDK_IMAGE" java --source 21 /tools/KeyCheckValue.java 2>> "$ART/client.log"
}

# ---------------------------------------------------------------------------------------------------
# Setup: the bootstrap again, the operator, the store.
# ---------------------------------------------------------------------------------------------------
step rotation-setup
kube -n finapp delete job finapp-role-bootstrap --ignore-not-found --wait=true >> "$ART/apply.log" 2>&1 || cannot "the first bootstrap Job could not be removed"
sed "s#MIGRATOR_IMAGE#$IMAGE_MIGRATOR_N#; s#PROBE_IMAGE#$CURL_IMAGE#" "$DIR/bootstrap-job.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "could not apply the bootstrap Job"
wait_job finapp finapp-role-bootstrap 300 role-bootstrap-again
for role in finapp_app_a finapp_app_b finapp_reporting_a finapp_reporting_b; do
    grep -q "role=$role \(present\|created\)" "$ART/role-bootstrap-again.log" || fail "the bootstrap step with N does not account for the alternating user $role: see role-bootstrap-again.log"
done
pass "the bootstrap step run again with N: every role present or created, the alternating users among them, nothing else changed"

# The operator, from the chart at the pinned version with the committed values.
ESO_CHART_VERSION=${ESO_VERSION#v}
eso_chart="$WORK/eso-chart"
mkdir -p "$eso_chart"
if [ -n "$CA_BUNDLE" ]; then
    set -- -v "$(dpath "$CA_BUNDLE"):/etc/ssl/certs/ca-certificates.crt:ro"
else
    set --
fi
dock run --rm "$@" -v "$(dpath "$eso_chart"):/chart" "$HELM_IMAGE" pull external-secrets --repo https://charts.external-secrets.io --version "$ESO_CHART_VERSION" --destination /chart > "$ART/eso-helm.log" 2>&1 \
    || cannot "the External Secrets chart could not be pulled: see eso-helm.log"
printf 'external-secrets chart %s sha256 %s\n' "$ESO_CHART_VERSION" "$(sha256sum "$eso_chart"/external-secrets-*.tgz | cut -c1-64)" | tee -a "$ART/rehearsal.log" > "$ART/eso-chart.txt"
dock run --rm -v "$(dpath "$eso_chart"):/chart" -v "$(dpath "$ROOT/deploy/operators/external-secrets"):/values:ro" "$HELM_IMAGE" template external-secrets \
    "/chart/external-secrets-$ESO_CHART_VERSION.tgz" --namespace external-secrets -f /values/values.yaml > "$WORK/eso.yaml" 2>> "$ART/eso-helm.log" \
    || cannot "the External Secrets chart could not be rendered: see eso-helm.log"
grep -q "image: $ESO_REPOSITORY:$ESO_VERSION@$ESO_DIGEST" "$WORK/eso.yaml" || fail "the rendered operator is not the pinned image digest"
grep -q '^kind: ClusterRoleBinding$' "$WORK/eso.yaml" && fail "the rendered operator holds a ClusterRoleBinding, which section 3.5 says it does not"
kube delete namespace external-secrets --ignore-not-found --wait=true --timeout=300s >> "$ART/apply.log" 2>&1 || true
kube create namespace external-secrets >> "$ART/apply.log" 2>&1 || cannot "could not create the operator's namespace"
dock exec "$NODE" crictl pull "$ESO_REPOSITORY@$ESO_DIGEST" >> "$ART/images.log" 2>&1 || cannot "the node could not pull the operator's image: see images.log"
kube apply --server-side -f - < "$WORK/eso.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply the operator: see apply.log"
kube -n external-secrets rollout status deployment/external-secrets --timeout=240s >> "$ART/apply.log" 2>&1 || fail "the operator did not become ready"
ESO_SA=$(kube -n external-secrets get deployment external-secrets -o jsonpath='{.spec.template.spec.serviceAccountName}')
pass "the External Secrets Operator runs from its pinned chart and image with the committed values, its rights a Role in finapp"

# (5) Its identity cannot read a Secret in another namespace; the control is its own namespace, where it must.
can_eso() { kube auth can-i "$1" secrets -n "$2" --as="system:serviceaccount:external-secrets:$ESO_SA" 2>/dev/null || true; }
[ "$(can_eso get finapp)" = yes ] || fail "eso 5 control: the operator cannot read a Secret in finapp, so the refusal below proves nothing"
for namespace in finapp-edge cert-manager kube-system finapp-rehearsal; do
    [ "$(can_eso get "$namespace")" = no ] && [ "$(can_eso list "$namespace")" = no ] || fail "eso 5: the operator can read Secrets in $namespace"
done
[ "$(kube auth can-i list secrets --all-namespaces --as="system:serviceaccount:external-secrets:$ESO_SA" 2>/dev/null || true)" = no ] || fail "eso 5: the operator can list Secrets cluster-wide"
pass "eso 5: the operator's ServiceAccount reads Secrets in finapp and in no other namespace, and lists none cluster-wide"

# The store, generated from what kind-secrets.sh generated: one entry per row of each ExternalSecret, and the simulators' URLs, which a
# real provider's would be (SECRET_MANAGEMENT.md section 3.3), as three entries of the application's.
: > "$STORE/app.env"
: > "$STORE/migrator.env"
for key in $(sed -n 's/^ *- secret[K]ey: *\(FINAPP_[A-Z0-9_]*\) *$/\1/p' "$ROOT/deploy/components/secret-delivery/externalsecret-app.yaml") \
    FINAPP_PAYMENTS_PROVIDER_URL FINAPP_PAYMENTMETHODS_TOKENISATION_URL FINAPP_MERCHANT_PAYOUT_PROVIDER_URL; do
    case $key in FINAPP_KAFKA_SASL_*) continue ;; esac
    printf '%s=%s\n' "$key" "$(secret_value finapp finapp-app-secrets "$key")" >> "$STORE/app.env"
done
for key in $(sed -n 's/^ *- secret[K]ey: *\(FINAPP_[A-Z0-9_]*\) *$/\1/p' "$ROOT/deploy/components/secret-delivery/externalsecret-migrator.yaml"); do
    printf '%s=%s\n' "$key" "$(secret_value finapp finapp-migrator-secrets "$key")" >> "$STORE/migrator.env"
done
printf 'username=%s\npassword=%s\n' "$(secret_value finapp finapp-app-secrets FINAPP_KAFKA_SASL_USERNAME)" "$(secret_value finapp finapp-app-secrets FINAPP_KAFKA_SASL_PASSWORD)" > "$STORE/kafka.env"
grep -q "^${APP_DB_ENTRY}=.\{64\}\$" "$STORE/app.env" || cannot "the store was not filled from the generated Secret"
APP_DIGEST_BEFORE=$(secret_digest finapp-app-secrets)
store_apply

# The two ExternalSecrets: the component's, unchanged, and the application's with the simulators' three URLs added for kind alone.
{
    cat "$ROOT/deploy/components/secret-delivery/externalsecret-app.yaml"
    for key in FINAPP_PAYMENTS_PROVIDER_URL FINAPP_PAYMENTMETHODS_TOKENISATION_URL FINAPP_MERCHANT_PAYOUT_PROVIDER_URL; do
        printf '    - %s: %s\n      remoteRef:\n        key: %s\n' "$ESO_ENTRY_FIELD" "$key" "$key"
    done
} > "$WORK/externalsecret-app.yaml"
# Owner: the operator writes the target. The Secrets kind-secrets.sh wrote are handed over: removed, then written by the operator, while the
# running pods keep their environment.
for workload in migrator app; do
    kube -n finapp delete "secret/finapp-$workload-secrets" --wait=true >> "$ART/apply.log" 2>&1 || cannot "the generated Secret finapp-$workload-secrets could not be handed over"
done
kube -n finapp apply -f - < "$ROOT/deploy/components/secret-delivery/externalsecret-migrator.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply the migrator's ExternalSecret"
kube -n finapp apply -f - < "$WORK/externalsecret-app.yaml" >> "$ART/apply.log" 2>&1 || cannot "could not apply the application's ExternalSecret"
for workload in migrator app; do
    kube -n finapp wait --for=condition=Ready "externalsecret/finapp-$workload-secrets" --timeout=180s >> "$ART/apply.log" 2>&1 \
        || { kube -n finapp describe "externalsecret/finapp-$workload-secrets" > "$ART/externalsecret-$workload-at-failure.txt" 2>&1; fail "eso 1: finapp-$workload-secrets never became Ready: see externalsecret-$workload-at-failure.txt"; }
done
owner=$(kube -n finapp get secret finapp-app-secrets -o jsonpath='{.metadata.ownerReferences[0].kind}/{.metadata.ownerReferences[0].name}')
[ "$owner" = ExternalSecret/finapp-app-secrets ] || fail "eso 1: the application's Secret is not owned by its ExternalSecret ($owner)"
[ "$(secret_digest finapp-app-secrets)" = "$APP_DIGEST_BEFORE" ] || fail "eso 1: the Secret the operator wrote differs from the one the store was filled from"
VERSION_WRITTEN=$(kube -n finapp get secret finapp-app-secrets -o jsonpath='{.metadata.resourceVersion}')
sleep 45
[ "$(kube -n finapp get secret finapp-app-secrets -o jsonpath='{.metadata.resourceVersion}')" = "$VERSION_WRITTEN" ] || fail "eso 1: the operator wrote the Secret again with nothing changed"
pass "eso 1: each ExternalSecret wrote its Secret once, owned by it, with exactly the store's values, and did not write it again in 45 s"

# (4) Deleting an ExternalSecret deletes its Secret (Owner). The migrator's: nothing runs it until the next release.
kube -n finapp delete externalsecret finapp-migrator-secrets --wait=true >> "$ART/apply.log" 2>&1 || fail "the migrator's ExternalSecret could not be deleted"
gone=""
deadline=$(( $(date +%s) + 90 ))
while [ "$(date +%s)" -lt "$deadline" ]; do
    kube -n finapp get secret finapp-migrator-secrets >/dev/null 2>&1 || { gone=1; break; }
    sleep 2
done
[ -n "$gone" ] || fail "eso 4: the migrator's Secret outlived its ExternalSecret"
kube -n finapp apply -f - < "$ROOT/deploy/components/secret-delivery/externalsecret-migrator.yaml" >> "$ART/apply.log" 2>&1 || fail "the migrator's ExternalSecret could not be restored"
kube -n finapp wait --for=condition=Ready externalsecret/finapp-migrator-secrets --timeout=180s >> "$ART/apply.log" 2>&1 || fail "eso 4: the restored ExternalSecret never became Ready"
kube -n finapp get secret finapp-migrator-secrets >/dev/null 2>&1 || fail "eso 4: the restored ExternalSecret wrote no Secret"
pass "eso 4: deleting the migrator's ExternalSecret removed its Secret; restoring it wrote the Secret again"

# The reporting session an operator opens, through every rotation: whoever the store says.
REPORTING_USER=finapp_reporting

# ---------------------------------------------------------------------------------------------------
# The database: alternating users.
# ---------------------------------------------------------------------------------------------------
grants_census() {
    # grants_census <label>: every alternating user against its carrier; any FAIL fails the step.
    : > "$ART/grants-$1.txt"
    for pair in finapp_app:finapp_app_a finapp_app:finapp_app_b finapp_reporting:finapp_reporting_a finapp_reporting:finapp_reporting_b; do
        psql_query -v "carrier=${pair%%:*}" -v "alternate=${pair#*:}" -f - < "$DIR/grants.sql" >> "$ART/grants-$1.txt" 2>&1 || fail "the grant census could not run: see grants-$1.txt"
    done
    [ "$(grep -c '^\(PASS\|FAIL\)|' "$ART/grants-$1.txt")" = 12 ] || fail "the grant census printed $(grep -c '^\(PASS\|FAIL\)|' "$ART/grants-$1.txt") verdicts where four users have three each: see grants-$1.txt"
    if grep -q '^FAIL' "$ART/grants-$1.txt"; then
        fail "grants $1: $(grep '^FAIL' "$ART/grants-$1.txt" | tr '\n' ';' | cut -c1-700)"
    fi
    pass "grants $1: each of the four alternating users holds exactly its carrier's rights, by its one membership, and no grant names it ($(grep -m1 'pairs compared' "$ART/grants-$1.txt" | cut -d'|' -f3 | cut -c1-80))"
}

plant_missing_grant() {
    # The probe: finapp_app_b made the way a clone is made, its carrier's grants copied to it directly, all but one, and no membership.
    psql_query -f - >> "$ART/rotation-sql.log" 2>&1 <<'SQL' || cannot "the probe's plant could not be made"
\set ON_ERROR_STOP on
REVOKE finapp_app FROM finapp_app_b;
DO $$
DECLARE r record; skipped boolean := false;
BEGIN
    FOR r IN SELECT n.nspname, c.relname, c.relkind, a.privilege_type
               FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace, aclexplode(c.relacl) a
              WHERE a.grantee = 'finapp_app'::regrole ORDER BY 1, 2, 4 LOOP
        IF NOT skipped THEN skipped := true; CONTINUE; END IF;
        EXECUTE format('GRANT %s ON %s %I.%I TO finapp_app_b', r.privilege_type, CASE WHEN r.relkind = 'S' THEN 'SEQUENCE' ELSE 'TABLE' END, r.nspname, r.relname);
    END LOOP;
    FOR r IN SELECT n.nspname, a.privilege_type FROM pg_namespace n, aclexplode(n.nspacl) a WHERE a.grantee = 'finapp_app'::regrole LOOP
        EXECUTE format('GRANT %s ON SCHEMA %I TO finapp_app_b', r.privilege_type, r.nspname);
    END LOOP;
    FOR r IN SELECT n.nspname, c.relname, t.attname, a.privilege_type
               FROM pg_attribute t JOIN pg_class c ON c.oid = t.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace, aclexplode(t.attacl) a
              WHERE a.grantee = 'finapp_app'::regrole LOOP
        EXECUTE format('GRANT %s (%I) ON %I.%I TO finapp_app_b', r.privilege_type, r.attname, r.nspname, r.relname);
    END LOOP;
    FOR r IN SELECT f.oid::regprocedure AS signature FROM pg_proc f, aclexplode(f.proacl) a WHERE a.grantee = 'finapp_app'::regrole LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO finapp_app_b', r.signature);
    END LOOP;
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO finapp_app_b', current_database());
END
$$;
SQL
    log "PROBE grant-missing: finapp_app_b made a clone of finapp_app's grants, all but one, with no membership"
}

# assert_sessions <label> <app user> <reporting user>: every fleet session is one of the two, and at least one is the application's.
assert_sessions() {
    fleet_sessions > "$ART/sessions-$1.txt" 2>&1 || true
    others=$(awk -F'|' -v a="$2" -v r="$3" '$1 != a && $1 != r' "$ART/sessions-$1.txt" | wc -l | tr -d ' ')
    mine=$(awk -F'|' -v a="$2" '$1 == a' "$ART/sessions-$1.txt" | wc -l | tr -d ' ')
    [ "$others" = 0 ] && [ "$mine" -gt 0 ] || fail "$1: the fleet's sessions are not all $2 and $3: see sessions-$1.txt"
    pass "$1: every session of the fleet signs in as $2 or $3"
}

rotate_database() {
    step rotation-database
    [ "$PROBE" != grant-missing ] || plant_missing_grant
    grants_census before
    [ "$PROBE" != grant-missing ] || fail "PROBE grant-missing: the grant census did not see a user missing one grant, so it cannot fail"

    app_before=$(store_get "$APP_DB_ENTRY" app)
    reporting_before=$(store_get "$REPORTING_DB_ENTRY" app)
    app_fresh=$(random_password)
    reporting_fresh=$(random_password)
    can_sign_in finapp_app_a "$app_fresh" && fail "finapp_app_a signed in before it was given a password"
    set_password finapp_app_a "$app_fresh"
    set_password finapp_reporting_a "$reporting_fresh"
    can_sign_in finapp_app_a "$app_fresh" || fail "finapp_app_a cannot sign in with the password just set"
    can_sign_in finapp_app_b "$app_fresh" && fail "finapp_app_b, given no password, signed in"
    pass "database: the inactive alternating users given a password, as a SCRAM verifier computed in the client; the other alternates still sign in with none"

    # (2) A changed value in the store changes nothing until the generation moves.
    store_set FINAPP_DB_APP_USER finapp_app_a app
    store_set "$APP_DB_ENTRY" "$app_fresh" app
    store_set FINAPP_DB_REPORTING_USER finapp_reporting_a app
    store_set "$REPORTING_DB_ENTRY" "$reporting_fresh" app
    unsynced=$(secret_digest finapp-app-secrets)
    store_apply
    sleep 45
    [ "$(secret_digest finapp-app-secrets)" = "$unsynced" ] || fail "eso 2: the Secret changed with the store, before its generation moved"
    pass "eso 2: the store changed and the Secret did not, for 45 s, until the generation moved"
    sync finapp-app-secrets
    [ "$(secret_value finapp finapp-app-secrets FINAPP_DB_APP_USER)" = finapp_app_a ] || fail "eso 3: the sync did not write the new user"
    pass "eso 3: moving the generation wrote the new values once, Ready=True with a refresh after the move"

    # (3) A pod started between the sync and the roll reads the new value; the others keep theirs until they roll.
    victim=$(app_pods | head -1 | cut -d'|' -f1)
    kube -n finapp delete pod "$victim" --wait=true >> "$ART/apply.log" 2>&1 || fail "could not restart $victim"
    ready_three 400 || fail "the fleet did not return to three after a pod was restarted between the sync and the roll"
    sleep 20
    new_ip=$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].status.podIP}')
    [ -n "$new_ip" ] || fail "eso 3: the restarted pod has no address"
    fleet_sessions > "$ART/sessions-between.txt" 2>&1 || true
    [ "$(awk -F'|' -v ip="$new_ip" '$2 == ip && $1 == "finapp_app_a"' "$ART/sessions-between.txt" | wc -l | tr -d ' ')" -gt 0 ] \
        && [ "$(awk -F'|' -v ip="$new_ip" '$2 == ip && $1 != "finapp_app_a" && $1 != "finapp_reporting_a"' "$ART/sessions-between.txt" | wc -l | tr -d ' ')" = 0 ] \
        || fail "eso 3: the pod started between the sync and the roll ($new_ip) does not sign in as the new user alone: see sessions-between.txt"
    [ "$(awk -F'|' -v ip="$new_ip" '$2 != ip && $1 == "finapp_app_a"' "$ART/sessions-between.txt" | wc -l | tr -d ' ')" = 0 ] \
        || fail "eso 3: a pod that has not rolled signs in as the new user: see sessions-between.txt"
    pass "eso 3: the pod started between the sync and the roll signs in as finapp_app_a, every pod not yet rolled as finapp_app: a half-rolled fleet on both credentials"

    roll database-forward secret
    assert_sessions database-forward finapp_app_a finapp_reporting_a
    census database-forward

    # The old users retired: no password, and no session of theirs left. Then the fleet works on: a smoke and the census.
    printf 'ALTER ROLE finapp_app PASSWORD NULL;\nALTER ROLE finapp_reporting PASSWORD NULL;\n' | psql_query -f - >> "$ART/rotation-sql.log" 2>&1 || fail "the old users could not be retired"
    REPORTING_USER=finapp_reporting_a
    REPORTING_LOGIN=$reporting_fresh
    [ "$(psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE usename IN ('finapp_app', 'finapp_reporting')" | tr -d '[:space:]')" = 0 ] \
        || fail "database: a session of a retired user is still open"
    can_sign_in finapp_app "$app_before" && fail "database: the retired finapp_app still signs in with its password"
    pass "database: finapp_app and finapp_reporting retired, no session of theirs open and their passwords refused"
    smoke database-retired
    census database-retired

    # The rollback: the previous version restored, the store's and the user's, then the same three steps.
    set_password finapp_app "$app_before"
    set_password finapp_reporting "$reporting_before"
    store_set FINAPP_DB_APP_USER finapp_app app
    store_set "$APP_DB_ENTRY" "$app_before" app
    store_set FINAPP_DB_REPORTING_USER finapp_reporting app
    store_set "$REPORTING_DB_ENTRY" "$reporting_before" app
    store_apply
    sync finapp-app-secrets
    roll database-rollback secret
    assert_sessions database-rollback finapp_app finapp_reporting
    printf 'ALTER ROLE finapp_app_a PASSWORD NULL;\nALTER ROLE finapp_reporting_a PASSWORD NULL;\n' | psql_query -f - >> "$ART/rotation-sql.log" 2>&1 || fail "the alternates could not be retired"
    REPORTING_USER=finapp_reporting
    REPORTING_LOGIN=$reporting_before
    [ "$(psql_query -c "SELECT count(*) FROM pg_stat_activity WHERE usename IN ('finapp_app_a', 'finapp_reporting_a')" | tr -d '[:space:]')" = 0 ] \
        || fail "database rollback: a session of a retired alternate is still open"
    census database-rollback
    grants_census after
}

# ---------------------------------------------------------------------------------------------------
# Kafka: add, roll, remove.
# ---------------------------------------------------------------------------------------------------
KAFKA_TOPICS=$(sed -n 's/^  \(finapp\.[a-z]*\): domain-events$/\1/p' "$ROOT/infra/kafka/topics.yaml")

kafka_acls() {
    # kafka_acls <add|remove> <user>: exactly the application's grants (topics-and-acls above), for one SCRAM user.
    set --  "$1" "$2"
    action=$1
    user=$2
    extra=""
    [ "$action" = remove ] && extra=--force
    set --
    for topic in $KAFKA_TOPICS; do
        set -- "$@" --topic "$topic"
    done
    # shellcheck disable=SC2086
    kafka_admin kafka-acls.sh "--$action" $extra --allow-principal "User:$user" --operation Describe --operation Read --operation Write "$@" >> "$ART/kafka.log" 2>&1 \
        || fail "the topics' ACLs could not be ${action}ed for $user"
    # shellcheck disable=SC2086
    kafka_admin kafka-acls.sh "--$action" $extra --allow-principal "User:$user" --operation Describe --operation Read --group finapp. --resource-pattern-type prefixed >> "$ART/kafka.log" 2>&1 \
        || fail "the groups' ACLs could not be ${action}ed for $user"
}

# Every ACL naming the user, as resource|operation|permission, one per line, sorted: two users' sets compare line for line.
acl_set_of() {
    kafka_admin kafka-acls.sh --list --principal "User:$1" 2>/dev/null | awk -v who="principal=User:$1," '
        /ResourcePattern\(/ { resource = $0; sub(/.*ResourcePattern\(/, "", resource); sub(/\)`?:.*$/, "", resource) }
        index($0, who) { operation = $0; sub(/.*operation=/, "", operation); sub(/,.*/, "", operation)
                         permission = $0; sub(/.*permissionType=/, "", permission); sub(/\).*/, "", permission)
                         print resource "|" operation "|" permission }' | sort
}

scram_add() {
    # scram_add <user> <password>: the password crosses kubectl on standard input and reaches kafka-configs as its argument inside the
    # test-only broker's own container, as the harness's first user reaches kafka-storage there. kafka-configs refuses a SCRAM credential
    # from --add-config-file, and echoes what it refuses, so its output stays in the run's directory and never reaches an artefact.
    if printf '%s\n' "$2" | kube -n finapp exec -i finapp-kafka-0 -c kafka -- /bin/sh -c 'IFS= read -r secret; exec /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9094 --alter --add-config "SCRAM-SHA-512=[iterations=8192,password=$secret]" --entity-type users --entity-name "$1"' scram "$1" > "$WORK/scram.out" 2>&1; then
        rm -f "$WORK/scram.out"
    else
        fail "the SCRAM user $1 could not be added ($(grep -c . "$WORK/scram.out") lines of output, kept out of the artefacts)"
    fi
}

scram_remove() {
    kafka_admin kafka-configs.sh --alter --delete-config SCRAM-SHA-512 --entity-type users --entity-name "$1" >> "$ART/kafka.log" 2>&1 || fail "the SCRAM user $1 could not be removed"
}

# kafka_signs_in <user> <password>: 0 when the broker's SASL_SSL listener admits the credential, from inside the broker's pod.
kafka_signs_in() {
    {
        printf 'security.protocol=SASL_SSL\nsasl.mechanism=SCRAM-SHA-512\nssl.truststore.type=PEM\nssl.truststore.location=/tmp/rotation-ca.pem\n'
        cat <<JAAS
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="${1}" password="${2}";
JAAS
    } | kube -n finapp exec -i finapp-kafka-0 -c kafka -- /bin/sh -c 'umask 077; cat > /tmp/rotation-client.properties' || return 2
    kube -n finapp exec -i finapp-kafka-0 -c kafka -- /bin/sh -c 'cat > /tmp/rotation-ca.pem' < "$CA_DIR/ca.crt" || return 2
    if kube -n finapp exec finapp-kafka-0 -c kafka -- /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server "$KAFKA_HOST:9092" \
        --command-config /tmp/rotation-client.properties > "$WORK/kafka-signin.out" 2>&1; then
        signed=0
    else
        signed=1
    fi
    kube -n finapp exec finapp-kafka-0 -c kafka -- rm -f /tmp/rotation-client.properties /tmp/rotation-ca.pem >> "$ART/kafka.log" 2>&1 || true
    grep -qi 'authentication failed\|SaslAuthenticationException' "$WORK/kafka-signin.out" && return 1
    [ "$signed" = 0 ] && grep -q 'ApiVersion' "$WORK/kafka-signin.out" && return 0
    return 1
}

rotate_kafka() {
    step rotation-kafka
    old_user=$(store_get username kafka)
    old_scram=$(store_get "$KAFKA_SCRAM_ENTRY" kafka)
    new_user=${old_user}_alternate
    new_scram=$(random_password)

    # The second user exists with exactly the first's ACLs before the fleet rolls (P15-TSK-015's contract).
    scram_add "$new_user" "$new_scram"
    kafka_acls add "$new_user"
    acl_set_of "$old_user" > "$WORK/acls-old.txt"
    acl_set_of "$new_user" > "$WORK/acls-new.txt"
    [ -s "$WORK/acls-old.txt" ] && cmp -s "$WORK/acls-old.txt" "$WORK/acls-new.txt" || fail "kafka: the second user's ACLs are not exactly the first's"
    kafka_signs_in "$new_user" "$new_scram" || fail "kafka: the second user cannot sign in"
    pass "kafka: the second SCRAM user exists with exactly the first's $(wc -l < "$WORK/acls-new.txt" | tr -d ' ') ACLs before the fleet rolls, and signs in"

    store_set username "$new_user" kafka
    store_set "$KAFKA_SCRAM_ENTRY" "$new_scram" kafka
    store_apply
    sync finapp-app-secrets
    roll kafka-forward secret

    # Remove the old: its credential and every ACL naming it. A replica still on it could neither produce nor consume from here.
    scram_remove "$old_user"
    kafka_acls remove "$old_user"
    [ -z "$(acl_set_of "$old_user")" ] || fail "kafka: an ACL still names the removed user"
    kafka_signs_in "$old_user" "$old_scram" && fail "kafka: the removed user still signs in"
    pass "kafka: the first user removed, its credential refused at the broker and no ACL naming it"
    smoke kafka-removed
    census kafka-forward

    # The rollback: the previous version restored (the first user again, from the store's previous value), then roll and remove the second.
    scram_add "$old_user" "$old_scram"
    kafka_acls add "$old_user"
    store_set username "$old_user" kafka
    store_set "$KAFKA_SCRAM_ENTRY" "$old_scram" kafka
    store_apply
    sync finapp-app-secrets
    roll kafka-rollback secret
    scram_remove "$new_user"
    kafka_acls remove "$new_user"
    kafka_signs_in "$new_user" "$new_scram" && fail "kafka rollback: the second user still signs in after its removal"
    smoke kafka-restored
    census kafka-rollback
}

# ---------------------------------------------------------------------------------------------------
# The payment webhook door's HMAC secret, through its window.
# ---------------------------------------------------------------------------------------------------
CALLBACK_DOOR=FINAPP_PAYMENTS_WEBHOOK
provider_signs_with() {
    # provider_signs_with <name> <base64 key>: the provider's current secret, switched by replacing the file the sender reads.
    printf '%s\n' "$2" > "$CLIENT/provider-key.next"
    mv "$CLIENT/provider-key.next" "$CLIENT/provider-key"
    printf '%s\n' "$1" > "$CLIENT/provider-key-name"
}

start_callbacks() {
    name="$CLUSTER-callbacks-$1"
    dock run -d --name "$name" --network kind --add-host "public.finapp.test:$NODE_IP" --add-host "operator.finapp.test:$NODE_IP" \
        -v "$(dpath "$CLIENT"):/work" -v "$(dpath "$DIR"):/src:ro" "$JDK_IMAGE" java --source 21 /src/Rehearsal.java /work callbacks "$1" >/dev/null \
        || cannot "the provider's callbacks could not start"
    CONTAINERS="$CONTAINERS $name"
}

stop_callbacks() {
    name="$CLUSTER-callbacks-$1"
    touch "$CLIENT/stop-callbacks-$1"
    dock wait "$name" >/dev/null 2>&1 || true
    dock logs "$name" >> "$ART/client.log" 2>&1 || true
    dock rm -f "$name" >/dev/null 2>&1 || true
    cp "$CLIENT/callbacks-$1.tsv" "$ART/" 2>/dev/null || true
    [ -f "$CLIENT/callbacks-$1.summary" ] || fail "the callbacks $1 wrote no summary"
    log "$(cat "$CLIENT/callbacks-$1.summary")"
}

# callbacks_counted <label> <name> <status>: how many deliveries signed with that key got that answer.
callbacks_counted() { awk -F'\t' -v n="$2" -v s="$3" '$2 == n && $3 == s' "$CLIENT/callbacks-$1.tsv" | wc -l | tr -d ' '; }
callbacks_not_accepted() { awk -F'\t' '$3 != 204' "$CLIENT/callbacks-$1.tsv" | wc -l | tr -d ' '; }

# assert_callbacks <label>: every delivery acknowledged, and enough of them that a refused one would have been seen.
assert_callbacks() {
    total=$(wc -l < "$CLIENT/callbacks-$1.tsv" | tr -d ' ')
    refused=$(callbacks_not_accepted "$1")
    [ "$total" -ge 20 ] || fail "callbacks $1: only $total deliveries, too few to have reached every replica"
    [ "$refused" = 0 ] || fail "callbacks $1: $refused of $total deliveries were refused (counted by key in callbacks-$1.tsv): the provider's secret was not accepted by every replica"
    pass "callbacks $1: all $total signed deliveries acknowledged by whichever replica took them, none refused"
}

burst() {
    # burst <name> <base64 key> <count>: "accepted|refused".
    printf '%s\n' "$2" > "$CLIENT/burst-key"
    line=$(client callbacks-burst "$1" "$3" 2>> "$ART/client.log" | tail -1)
    rm -f "$CLIENT/burst-key"
    # To the log alone: this function's standard output is its answer.
    log "$line" >&2
    printf '%s|%s\n' "$(printf '%s' "$line" | sed -n 's/.* accepted=\([0-9]*\).*/\1/p')" "$(printf '%s' "$line" | sed -n 's/.* refused=\([0-9]*\).*/\1/p')"
}

rotate_callback() {
    step rotation-callback
    old_key=$(store_get ${CALLBACK_DOOR}_KEY app)
    new_key=$(random_key)
    provider_signs_with old "$old_key"

    # Step 1: the window opened, the previous slot still its generated value; nothing verifies under it yet.
    config_set ${CALLBACK_DOOR}_PREVIOUS_VALIDUNTIL "$(date -u -d '+40 minutes' +%Y-%m-%dT%H:%M:%SZ)"
    start_callbacks callback-window
    roll callback-window config
    stop_callbacks callback-window
    assert_callbacks callback-window

    # Step 2: the new secret current and the old one previous, in the store; synced; the fleet rolled while the provider still signs with
    # the old. The window's instant moves with it, a quarter of an hour ahead: past this roll and the provider's switch.
    if [ "$PROBE" = hmac-previous-dropped ]; then
        log "PROBE hmac-previous-dropped: the previous slot holds a fresh value, not the secret the provider still signs with"
        store_set ${CALLBACK_DOOR}_PREVIOUS_KEY "$(random_key)" app
    else
        store_set ${CALLBACK_DOOR}_PREVIOUS_KEY "$old_key" app
    fi
    store_set ${CALLBACK_DOOR}_KEY "$new_key" app
    store_apply
    sync finapp-app-secrets
    CLOSES=$(date -u -d '+15 minutes' +%s)
    config_set ${CALLBACK_DOOR}_PREVIOUS_VALIDUNTIL "$(iso "$CLOSES")"
    start_callbacks callback-new
    roll callback-new secret
    stop_callbacks callback-new
    assert_callbacks callback-new
    [ "$PROBE" != hmac-previous-dropped ] || fail "PROBE hmac-previous-dropped: no delivery was refused, so the assertion cannot fail"

    # The provider switches; every replica now verifies the new secret as its current one.
    provider_signs_with new "$new_key"
    start_callbacks callback-switched
    sleep 40
    stop_callbacks callback-switched
    assert_callbacks callback-switched

    # The window closes itself at its instant on every replica, nothing restarted: the old secret refused, the new one accepted.
    app_pods | cut -d'|' -f1,4,5 | sort > "$WORK/pods-before-close.txt"
    while [ "$(date -u +%s)" -lt $((CLOSES + 20)) ]; do sleep 5; done
    after_old=$(burst old "$old_key" 30)
    after_new=$(burst new "$new_key" 30)
    app_pods | cut -d'|' -f1,4,5 | sort > "$WORK/pods-after-close.txt"
    cmp -s "$WORK/pods-before-close.txt" "$WORK/pods-after-close.txt" || fail "callback: a replica restarted while the window closed"
    [ "$after_old" = "0|30" ] || fail "callback: after the window's instant the old secret got accepted|refused $after_old of 30, where every one is refused"
    [ "$after_new" = "30|0" ] || fail "callback: after the window's instant the new secret got accepted|refused $after_new of 30"
    pass "callback: the window closed itself at its instant on every replica with nothing restarted: 30 deliveries under the old secret refused (counted), 30 under the new accepted"
    census callback-forward

    # The rollback: the previous version current again, the one being retired previous, a new window; the provider signs with the new one
    # through the roll, then switches back.
    store_set ${CALLBACK_DOOR}_KEY "$old_key" app
    store_set ${CALLBACK_DOOR}_PREVIOUS_KEY "$new_key" app
    store_apply
    sync finapp-app-secrets
    config_set ${CALLBACK_DOOR}_PREVIOUS_VALIDUNTIL "$(date -u -d '+30 minutes' +%Y-%m-%dT%H:%M:%SZ)"
    start_callbacks callback-rollback
    roll callback-rollback secret
    stop_callbacks callback-rollback
    assert_callbacks callback-rollback
    provider_signs_with old "$old_key"
    start_callbacks callback-restored
    sleep 40
    stop_callbacks callback-restored
    assert_callbacks callback-restored
    census callback-rollback
}

# ---------------------------------------------------------------------------------------------------
# A field-level key: the MFA ring.
# ---------------------------------------------------------------------------------------------------
# new_person <who>: registered through the edge with the run's password, as the operator was, then given a TOTP factor through the
# edge. Neither needs the customer active.
new_person() {
    client register-person "$1" >> "$ART/client.log" 2>&1 || return 1
    client enrol-person "$1" >> "$ART/client.log" 2>&1
}

enrolment_version() {
    psql_query -c "SELECT e.key_version FROM identity.mfa_enrolment e JOIN identity.identity i ON i.id = e.identity_id WHERE i.login_identifier = '$(sed -n "s/^$1\\.login=//p" "$CLIENT/state.properties")' ORDER BY e.key_version DESC LIMIT 1" | tr -d '[:space:]'
}

rotate_key() {
    step rotation-key
    v1=$(store_get FINAPP_MFA_KEY app)
    v2=$(random_key)
    # Fresh material into the next unlisted slot (runbook 11.29): never a slot already listed. The check values from the tool a person uses.
    cv1=$(printf '%s\n' "$v1" | check_value)
    cv2=$(printf '%s\n' "$v2" | check_value)
    case "$cv1$cv2" in *[!0-9a-f]* | "") cannot "no check value was computed" ;; esac
    [ "${#cv1}" = 16 ] && [ "${#cv2}" = 16 ] || cannot "a check value is not sixteen hexadecimal characters"
    printf 'mfa ring check values: 1:%s 2:%s\n' "$cv1" "$cv2" | tee -a "$ART/rehearsal.log" > "$ART/check-values.txt"
    store_set FINAPP_MFA_V2_KEY "$v2" app
    store_apply
    sync finapp-app-secrets
    [ "$(enrolment_version operator)" = 1 ] || fail "key: the operator's factor is not under version 1 before the rotation"

    # Step 1: listed, with every held version's check value.
    config_set FINAPP_MFA_RING_VERSIONS 1,2 FINAPP_MFA_RING_CHECKVALUES "1:$cv1,2:$cv2"
    roll key-listed config
    census key-listed

    # Version 2's stored value replaced after it was listed: a replica started then refuses, on the check value, and the others go on.
    store_set FINAPP_MFA_V2_KEY "$(random_key)" app
    store_apply
    sync finapp-app-secrets
    victim=$(app_pods | head -1 | cut -d'|' -f1)
    kube -n finapp delete pod "$victim" --wait=true >> "$ART/apply.log" 2>&1 || fail "could not restart $victim"
    refused_pod=""
    deadline=$(( $(date +%s) + 300 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        for pod in $(app_pods | awk -F'|' '$3 != "True" {print $1}'); do
            if kube -n finapp logs "$pod" --all-containers 2>/dev/null | grep -q 'does not give its published check value'; then refused_pod=$pod; fi
            if kube -n finapp logs "$pod" --previous 2>/dev/null | grep -q 'does not give its published check value'; then refused_pod=$pod; fi
        done
        [ -n "$refused_pod" ] && break
        sleep 5
    done
    [ -n "$refused_pod" ] || fail "key: a replica started on a replaced version 2 value did not refuse on its check value"
    [ "$(kube -n finapp get pod "$refused_pod" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" != True ] || fail "key: the refusing replica went ready"
    [ "$(app_pods | awk -F'|' '$3 == "True"' | wc -l | tr -d ' ')" -ge 2 ] || fail "key: the replicas that had not restarted stopped serving"
    pass "key: a replica started on a replaced version 2 value refused to start on its check value, naming the version; the two others served on"
    store_set FINAPP_MFA_V2_KEY "$v2" app
    store_apply
    sync finapp-app-secrets
    kube -n finapp delete pod "$refused_pod" --wait=true >> "$ART/apply.log" 2>&1 || fail "could not restart $refused_pod"
    ready_three 400 || fail "key: the fleet did not return to three once version 2's value was restored"
    pass "key: version 2's value restored, a replica started on it is ready"

    # Step 2: active.
    config_set FINAPP_MFA_RING_ACTIVEVERSION 2
    roll key-active config
    new_person rotated || fail "key: a person could not enrol a factor with version 2 active"
    [ "$(enrolment_version rotated)" = 2 ] || fail "key: the factor enrolled with version 2 active is under version $(enrolment_version rotated)"
    client elevate operator 3 >> "$ART/client.log" 2>&1 || fail "key: the operator's version 1 factor could not be read after the rotation"
    client elevate rotated 3 >> "$ART/client.log" 2>&1 || fail "key: the version 2 factor could not be read"
    pass "key: version 2 active writes version 2 rows, and a version 1 row written before the rotation is read after it, through the real store, on whichever replica took it"
    census key-active

    # The rollback: the pointer back, version 2 kept listed; a version 2 row read under active version 1, and new rows under 1 again.
    config_set FINAPP_MFA_RING_ACTIVEVERSION 1
    roll key-rollback config
    client elevate rotated 3 >> "$ART/client.log" 2>&1 || fail "key rollback: the version 2 row could not be read with the pointer moved back"
    new_person after-rollback || fail "key rollback: a person could not enrol a factor"
    [ "$(enrolment_version after-rollback)" = 1 ] || fail "key rollback: the factor enrolled after the pointer moved back is under version $(enrolment_version after-rollback)"
    pass "key rollback: the pointer moved back with version 2 still listed: a version 2 row read under active version 1, and a new row under version 1"
    census key-rollback
}

# ---------------------------------------------------------------------------------------------------
# The edge certificate.
# ---------------------------------------------------------------------------------------------------
secret_serial() { secret_value finapp-edge finapp-edge-public-tls 'tls\.crt' | openssl x509 -noout -serial | sed 's/^serial=//; s/^0*//' | tr 'a-f' 'A-F'; }
served_serial() { client served-serial 2>> "$ART/client.log" | sed -n 's/^served serial=\([0-9A-F]*\) .*/\1/p' | sed 's/^0*//'; }
edge_pods() { kube -n finapp-edge get pods -o jsonpath='{range .items[*]}{.metadata.name}|{.metadata.uid}|{.status.containerStatuses[0].restartCount}{"\n"}{end}' | sort; }

# wait_served <serial> <seconds>: the public host serves that certificate, on a connection of its own; the seconds it took.
wait_served() {
    started=$(date +%s)
    deadline=$((started + $2))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        [ "$(served_serial)" = "$1" ] && { echo $(( $(date +%s) - started )); return 0; }
        sleep 5
    done
    return 1
}

rotate_certificate() {
    step rotation-certificate
    mkdir -p "$WORK/edge-previous"
    for field in tls.crt tls.key ca.crt; do
        kube -n finapp-edge get secret finapp-edge-public-tls -o "jsonpath={.data.$(printf '%s' "$field" | sed 's/\./\\./')}" > "$WORK/edge-previous/$field"
    done
    previous=$(secret_serial)
    [ "$(served_serial)" = "$previous" ] || fail "certificate: the edge does not serve the Secret's certificate before the renewal"
    edge_pods > "$WORK/edge-pods-before.txt"
    revision=$(kube -n finapp-edge get certificate finapp-edge-public -o jsonpath='{.status.revision}')

    # Renewed early: cert-manager's manual trigger, the Issuing condition cmctl sets, with the workload running through the edge.
    start_workload certificate-renewal
    sleep 8
    kube -n finapp-edge patch certificate finapp-edge-public --subresource=status --type=json \
        -p "[{\"op\":\"add\",\"path\":\"/status/conditions/-\",\"value\":{\"type\":\"Issuing\",\"status\":\"True\",\"reason\":\"ManuallyTriggered\",\"message\":\"renewed early by the rotation rehearsal\",\"lastTransitionTime\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}}]" \
        >> "$ART/apply.log" 2>&1 || fail "certificate: the renewal could not be triggered"
    renewed=""
    deadline=$(( $(date +%s) + 180 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        renewed=$(secret_serial)
        [ -n "$renewed" ] && [ "$renewed" != "$previous" ] && break
        sleep 3
    done
    [ -n "$renewed" ] && [ "$renewed" != "$previous" ] || fail "certificate: cert-manager did not renew the certificate"
    [ "$(kube -n finapp-edge get certificate finapp-edge-public -o jsonpath='{.status.revision}')" -gt "${revision:-0}" ] || fail "certificate: the revision did not move"
    took=$(wait_served "$renewed" 300) || fail "certificate: the renewed certificate was not served within 300 s"
    sleep 10
    stop_workload certificate-renewal
    edge_pods > "$WORK/edge-pods-after.txt"
    cmp -s "$WORK/edge-pods-before.txt" "$WORK/edge-pods-after.txt" || fail "certificate: an edge controller restarted or was replaced"
    [ "$(workload_failed certificate-renewal)" = 0 ] || fail "certificate: $(workload_failed certificate-renewal) requests failed across the renewal"
    assert_flows_ran certificate-renewal
    pass "certificate: renewed early by cert-manager and served $took s after the Secret changed, with no edge controller restarted and zero failed requests"

    # The rollback: the previous certificate restored into the Secret and served the same way, with cert-manager's controller stopped so
    # that the edge's own reload is what this measures, not an issuance racing it. Started again, cert-manager issued over the restored
    # copy on the record's run (OPERATIONS_RUNBOOK.md 11.33): what it does is recorded, and a re-issue must be served too.
    start_workload certificate-rollback
    sleep 8
    kube -n cert-manager scale deployment cert-manager --replicas=0 >> "$ART/apply.log" 2>&1 || fail "certificate rollback: cert-manager's controller could not be stopped"
    kube -n cert-manager rollout status deployment cert-manager --timeout=120s >> "$ART/apply.log" 2>&1 || true
    {
        printf '{"data":{'
        printf '"tls.crt":"%s","tls.key":"%s","ca.crt":"%s"' "$(cat "$WORK/edge-previous/tls.crt")" "$(cat "$WORK/edge-previous/tls.key")" "$(cat "$WORK/edge-previous/ca.crt")"
        printf '}}'
    } | kube -n finapp-edge patch secret finapp-edge-public-tls --type=merge --patch-file /dev/stdin >> "$ART/apply.log" 2>&1 || fail "certificate: the previous certificate could not be restored"
    took=$(wait_served "$previous" 300) || fail "certificate rollback: the previous certificate was not served within 300 s"
    pass "certificate rollback: the previous certificate restored and served $took s later, with no edge controller restarted"
    kube -n cert-manager scale deployment cert-manager --replicas=1 >> "$ART/apply.log" 2>&1 || fail "certificate rollback: cert-manager's controller could not be started again"
    kube -n cert-manager rollout status deployment cert-manager --timeout=180s >> "$ART/apply.log" 2>&1 || fail "certificate rollback: cert-manager's controller did not come back"
    sleep 90
    after=$(secret_serial)
    if [ "$after" = "$previous" ]; then
        log "certificate rollback: cert-manager, started again, left the restored certificate in place for 90 s"
        printf 'certificate rollback: cert-manager kept the restored certificate\n' > "$ART/certificate-after-rollback.txt"
    else
        log "certificate rollback: cert-manager issued again over the restored certificate: under cert-manager a rollback is a re-issue"
        printf 'certificate rollback: cert-manager re-issued over the restored certificate (serial changed again)\n' > "$ART/certificate-after-rollback.txt"
        wait_served "$after" 300 > /dev/null || fail "certificate rollback: cert-manager's re-issued certificate was not served within 300 s"
    fi
    stop_workload certificate-rollback
    edge_pods > "$WORK/edge-pods-after.txt"
    cmp -s "$WORK/edge-pods-before.txt" "$WORK/edge-pods-after.txt" || fail "certificate rollback: an edge controller restarted or was replaced"
    [ "$(workload_failed certificate-rollback)" = 0 ] || fail "certificate rollback: $(workload_failed certificate-rollback) requests failed"
    assert_flows_ran certificate-rollback
    pass "certificate rollback: zero failed requests through the edge, no controller restarted, and what cert-manager did next recorded"
    rm -rf "$WORK/edge-previous"
    census certificate
}

# ---------------------------------------------------------------------------------------------------
# The sequence.
# ---------------------------------------------------------------------------------------------------
for rotation in $ROTATIONS; do
    case $rotation in
        database) rotate_database ;;
        kafka) rotate_kafka ;;
        callback) rotate_callback ;;
        key) rotate_key ;;
        certificate) rotate_certificate ;;
        *) cannot "no rotation is called $rotation" ;;
    esac
done
census rotations-final
