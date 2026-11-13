# The disaster-recovery exercise (P16-TSK-025; ADR-0149, ADR-0128 tier 3, ADR-0140; OPERATIONS_RUNBOOK.md section 11.64): sourced by
# infra/scripts/kind-rehearsal.sh, functions only, called under REHEARSAL_SCOPE=dr. It uses the harness's own helpers (dock, kube, log,
# pass, fail, cannot, step, psql_query, client, start_workload, stop_workload, census, smoke, normalized, dpath, iso, db_epoch, jdk).
#
# WHAT IT EXERCISES. Tier 3's local analogue: the environment lost whole and rebuilt from what was kept outside it. Two runs of the
# harness, one command. THE ENVIRONMENT BEFORE (REHEARSAL_DR_PHASE unset): release N at three replicas with its census green; a
# workload; the backup in one snapshot, encrypted into the vault; the key-ring escrow the environment's rings were made from; the
# release record (the digests the fleet ran); the image set the node held; each replica's configuration fingerprint; the client's
# memory of its participants; then a window of further acknowledged money movements, which the disaster will lose; then the disaster:
# the cluster deleted, its node, its database's volume and everything in it with it. THE REBUILD (REHEARSAL_DR_PHASE=rebuild, started
# by the first run as a run of its own, from the repository at the same commit and the vault alone): a new cluster whose node can
# reach no registry; every image imported from the vault; every credential generated again and the rings recovered from the escrow;
# the data tier and the broker from the repository; the bootstrap; the platform's roles held NOLOGIN before anything is restored; the
# restore, its counter, its comparison with the manifest and the platform's own verification; the window; a person's recorded release;
# only then the migrator, the fleet at three, the edge, the audited replay of the window before the restore point, money moved and the
# census; the configuration fingerprint compared; the fleet scaled to the run's count; the registry still cut. Each step is timed.
#
# WHAT IT NEVER DOES. It keeps no plaintext at rest: the backup, the manifest, the client's memory and the escrow are written through
# openssl into a vault inside the first run's work directory, outside the repository and the artefacts, removed when that run ends; a
# part is refused by its digest before it is decrypted. It restores no credential: kind-secrets.sh takes only the rings from the escrow
# (KIND_SECRETS_RINGS=stdin) and generates everything else again. The verification reaches the database the way an administrator does,
# through the API server's port-forward from a container on the kind network, never through a policy opened for it.
#
# E87 is the exercise, E88 the registry cut throughout the rebuild, E89 a ring's version lost from the escrow (RESILIENCE_EXPERIMENTS.md).
# The probes: skip-verification (the release must refuse, and the database must refuse the fleet) and config-drift (one FINANCIAL value
# changed in the rebuild: the fingerprint must differ). Both must turn the run red.

DR_PHASE=${REHEARSAL_DR_PHASE:-}
DR_VAULT=${REHEARSAL_DR_VAULT:-}
DR_RELEASED_BY=${REHEARSAL_DR_RELEASED_BY:-}
DR_WORKLOAD_SECONDS=${REHEARSAL_DR_WORKLOAD_SECONDS:-300}
DR_LOST_SECONDS=${REHEARSAL_DR_LOST_SECONDS:-60}
# The window replayed before the restore point when the groups' lag at it cannot be read (OPERATIONS_RUNBOOK.md section 11.35 step 6.3):
# the metrics store is the lost cluster's, so it never can here.
DR_REPLAY_HOURS=24
# E89's ring: the MFA ring, whose rows the operators' enrolments write in every run, so its loss is a loss of rows.
DR_PLANTED_RING=FINAPP_MFA_KEY
# The FINANCIAL value the config-drift probe changes, as section 11.31's plant does.
DR_DRIFT_LINE='  FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_EUR: "1"'
DR_IMPORTED=""
DR_TUNNEL_PORT=""
DR_TRANSFERS=0
DR_TOPUPS=0
DR_CHECKOUTS=0
DR_PAYOUTS=0
DR_WALLET_MINOR=0

# dr_resolve: what the exercise needs, refused before anything is built. Every scope calls it; only the dr scope may name a phase.
dr_resolve() {
    if [ "$SCOPE" != dr ]; then
        [ -z "$DR_PHASE" ] && [ -z "$DR_VAULT" ] || { echo "?? REHEARSAL_DR_PHASE and REHEARSAL_DR_VAULT belong to REHEARSAL_SCOPE=dr" >&2; exit 2; }
        return 0
    fi
    case $DR_PHASE in '' | rebuild) ;; *) echo "?? REHEARSAL_DR_PHASE is unset or rebuild, not $DR_PHASE" >&2; exit 2 ;; esac
    # The release is a person's act (ADR-0128 decision 3): the run is told whose, and records it.
    case $DR_RELEASED_BY in
        '' | *'	'* | *'|'*) echo "?? REHEARSAL_DR_RELEASED_BY names the person who releases the recovered database (no tab or bar)" >&2; exit 2 ;;
    esac
    case $PROBE in '' | skip-verification | config-drift) ;; *) echo "?? the dr scope's probes are skip-verification and config-drift, not $PROBE" >&2; exit 2 ;; esac
    # The recovered fleet starts at three and is scaled out: a count of three would scale nothing (fleet_resolve has read the ceiling).
    [ "$FLEET_REPLICAS" != 3 ] || { echo "?? REHEARSAL_DR_SCALE is the count the recovered fleet is scaled to from three: four to the overlay's ceiling" >&2; exit 2; }
    # A record of the exercise is only ever of a whole run, on a cluster it made.
    [ -z "${REHEARSAL_STEPS:-}" ] || { echo "?? the dr scope runs whole: REHEARSAL_STEPS is refused" >&2; exit 2; }
    [ -z "${REHEARSAL_REUSE_CLUSTER:-}" ] || { echo "?? the dr scope destroys its cluster: REHEARSAL_REUSE_CLUSTER is refused" >&2; exit 2; }
    for dr_whole in REHEARSAL_DR_WORKLOAD_SECONDS REHEARSAL_DR_LOST_SECONDS; do
        eval "dr_value=\${$dr_whole:-1}"
        case $dr_value in '' | *[!0-9]* | 0) echo "?? $dr_whole is a whole number of seconds, not '$dr_value'" >&2; exit 2 ;; esac
    done
    if [ -z "$DR_PHASE" ]; then
        [ -z "$DR_VAULT" ] || { echo "?? the environment before the disaster makes its own vault: REHEARSAL_DR_VAULT is the rebuild's" >&2; exit 2; }
        return 0
    fi
    # The rebuild: the vault the first run filled, outside the repository, every part present; the images never built again (the
    # released bytes are the vault's); the repository at the commit the environment was built from.
    [ -n "$DR_VAULT" ] && [ -d "$DR_VAULT" ] || { echo "?? the rebuild needs REHEARSAL_DR_VAULT naming the first run's vault" >&2; exit 2; }
    case "$(CDPATH= cd -- "$DR_VAULT" && pwd)/" in "$ROOT"/*) echo "?? the vault is inside the repository" >&2; exit 2 ;; esac
    for dr_part in backup.enc backup.sha256 manifest.enc manifest.sha256 client.enc client.sha256 rings.enc rings.sha256 rings.checkvalues \
        backup.key escrow.key images.tar images.sha256 images.tsv release.txt fingerprint.txt expected.env instants.tsv repository.ref; do
        [ -s "$DR_VAULT/$dr_part" ] || { echo "?? the vault holds no $dr_part" >&2; exit 2; }
    done
    [ -n "${REHEARSAL_SKIP_BUILD:-}" ] || { echo "?? the rebuild deploys the vault's images and never builds: REHEARSAL_SKIP_BUILD is required" >&2; exit 2; }
    [ "$(git -C "$(dpath "$ROOT")" rev-parse HEAD)" = "$(cat "$DR_VAULT/repository.ref")" ] \
        || { echo "?? the repository is not at the commit the lost environment was built from ($(cat "$DR_VAULT/repository.ref"))" >&2; exit 2; }
    # What the restored books held at the backup's instant, as the census before it was told (census-<label>.expected).
    DR_TRANSFERS=$(dr_expected transfers)
    DR_TOPUPS=$(dr_expected topups)
    DR_CHECKOUTS=$(dr_expected checkouts)
    DR_PAYOUTS=$(dr_expected payouts)
    DR_WALLET_MINOR=$(dr_expected wallets)
}

dr_expected() {
    dr_read=$(sed -n "s/^$1=\([0-9][0-9]*\)$/\1/p" "$DR_VAULT/expected.env")
    [ -n "$dr_read" ] || { echo "?? the vault's expected.env holds no $1" >&2; exit 2; }
    printf '%s' "$dr_read"
}

# dr_mark <name>: an instant of the exercise, by this machine's clock, for the record's recovery times.
dr_mark() {
    printf '%s\t%s\n' "$1" "$(date +%s)" >> "$ART/dr-marks.tsv"
}

dr_millis() {
    echo $(($(date +%s%N) / 1000000))
}

# ---------------------------------------------------------------------------------------------------
# The vault: every part ciphertext, its digest beside it, decrypted only into a pipe after the digest holds.
# ---------------------------------------------------------------------------------------------------

# dr_seal <part> <key>: stdin encrypted into <part>.enc under the vault's <key>.key, and its digest kept.
dr_seal() {
    MSYS_NO_PATHCONV=1 openssl enc -e -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass "file:$(dpath "$DR_VAULT/$2.key")" \
        -out "$(dpath "$DR_VAULT/$1.enc")" || return 1
    MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$DR_VAULT/$1.enc")" | cut -d' ' -f1 > "$DR_VAULT/$1.sha256"
}

# dr_holds <part...>: each part's ciphertext against its kept digest, in this shell, never inside a pipeline.
dr_holds() {
    for dr_part in "$@"; do
        [ "$(MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$DR_VAULT/$dr_part.$(dr_suffix "$dr_part")")" | cut -d' ' -f1)" = "$(cat "$DR_VAULT/$dr_part.sha256")" ] \
            || fail "the vault's $dr_part does not match its digest: refused before it is read"
    done
}

dr_suffix() {
    case $1 in images) printf tar ;; *) printf enc ;; esac
}

# dr_open <part>: the part decrypted to stdout. Its key: the escrow's for the rings, the backup's for the rest.
dr_open() {
    dr_key=backup
    [ "$1" != rings ] || dr_key=escrow
    MSYS_NO_PATHCONV=1 openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -pass "file:$(dpath "$DR_VAULT/$dr_key.key")" \
        -in "$(dpath "$DR_VAULT/$1.enc")"
}

# Nothing in the vault is a dump, a base backup or a tar but the image set: pg_dump's custom format begins PGDMP, and a tar names itself
# ustar at byte 257.
dr_no_plaintext() {
    for dr_file in "$DR_VAULT"/*; do
        [ -f "$dr_file" ] || continue
        [ "$(basename "$dr_file")" != images.tar ] || continue
        [ "$(head -c 5 "$dr_file")" != PGDMP ] || fail "a plaintext dump is in the vault: $(basename "$dr_file")"
        [ "$(head -c 262 "$dr_file" | tail -c 5)" != ustar ] || fail "a plaintext archive is in the vault: $(basename "$dr_file")"
    done
}

# ---------------------------------------------------------------------------------------------------
# The key rings: made once into the escrow, read from it by both environments; never generated in a recovery.
# ---------------------------------------------------------------------------------------------------

# dr_ring_names: every version of every field-level key ring the inventory holds, by kind-secrets.sh's own rule (a name ending in _KEY
# with a second version beside it, or a numbered version), from the ExternalSecret.
dr_ring_names() {
    dr_names=$(sed -n 's/^ *- secretKey: *\(FINAPP_[A-Z0-9_]*\) *$/\1/p' "$ROOT/deploy/components/secret-delivery/externalsecret-app.yaml")
    for dr_name in $dr_names; do
        case $dr_name in
            *_V[0-9]_KEY) printf '%s\n' "$dr_name" ;;
            *_KEY)
                if printf '%s\n' "$dr_names" | grep -qx "${dr_name%_KEY}_V2_KEY"; then
                    printf '%s\n' "$dr_name"
                fi
                ;;
        esac
    done
}

# dr_check_value: a key's check value, the key on stdin (OPERATIONS_RUNBOOK.md section 11.29's tool, as a replica computes it).
dr_check_value() {
    dock run --rm -i -v "$(dpath "$ROOT/infra/scripts"):/src:ro" "$JDK_IMAGE" java --source 21 /src/KeyCheckValue.java
}

# dr_rings_ready, at the secrets step. Before the disaster: the vault made, its two keys apart, and every ring's every version made into
# the escrow, with version 1's check value of each ring kept beside it (not secret: published in both environments' configuration). In
# the rebuild: the escrow's digest, and E89's first line - an escrow short of one version refused before any object is written.
dr_rings_ready() {
    if [ "$DR_PHASE" = rebuild ]; then
        dr_holds rings
        dr_escrow_short
        return 0
    fi
    DR_VAULT="$WORK/vault"
    mkdir -p "$DR_VAULT" || cannot "the vault could not be made"
    chmod 700 "$DR_VAULT"
    openssl rand -hex 32 > "$DR_VAULT/backup.key" || cannot "the backup's key could not be made"
    openssl rand -hex 32 > "$DR_VAULT/escrow.key" || cannot "the escrow's key could not be made"
    dr_ring_names > "$WORK/dr-ring-names" || cannot "the rings could not be read from the inventory"
    dr_rings=$(grep -c . "$WORK/dr-ring-names")
    dr_bases=$(grep -vc '_V[0-9]_KEY$' "$WORK/dr-ring-names")
    [ "$dr_bases" -ge 1 ] && [ "$dr_rings" -gt "$dr_bases" ] || cannot "the inventory's rings could not be read: $dr_rings names, $dr_bases rings"
    : > "$DR_VAULT/rings.checkvalues"
    while read -r dr_name; do
        dr_value=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
        printf '%s=%s\n' "$dr_name" "$dr_value"
        case $dr_name in
            *_V[0-9]_KEY) ;;
            *) printf '%s_RING_CHECKVALUES=1:%s\n' "${dr_name%_KEY}" "$(printf '%s' "$dr_value" | dr_check_value)" >> "$DR_VAULT/rings.checkvalues" ;;
        esac
    done < "$WORK/dr-ring-names" | dr_seal rings escrow || cannot "the escrow could not be written"
    [ "$(grep -c '^FINAPP_[A-Z0-9_]*_RING_CHECKVALUES=1:[0-9a-f]\{16\}$' "$DR_VAULT/rings.checkvalues")" = "$dr_bases" ] \
        || cannot "the rings' check values could not be computed: see the JDK image's run"
    dr_no_plaintext
    pass "the escrow: $dr_rings versions of $dr_bases key rings, sealed under a key apart from the backup's, each ring's check value beside it"
}

# E89, its first line: the escrow with the planted ring's version withheld, given to kind-secrets.sh in its render mode, refused before
# it writes any object, naming the version. Nothing reaches the cluster.
dr_escrow_short() {
    mkdir -p "$WORK/dr-short" || cannot "the plant's directory could not be made"
    dr_code=0
    dr_open rings | grep -v "^$DR_PLANTED_RING=" | KIND_SECRETS_RINGS=stdin FINAPP_DB_URL=planted FINAPP_DB_APP_USER=planted \
        sh "$ROOT/infra/scripts/kind-secrets.sh" render "$WORK/dr-short" > "$ART/dr-escrow-short.log" 2>&1 || dr_code=$?
    [ "$dr_code" = 1 ] || fail "E89: an escrow without $DR_PLANTED_RING was not refused (exit $dr_code): see dr-escrow-short.log"
    grep -q "no value for $DR_PLANTED_RING on stdin" "$ART/dr-escrow-short.log" || fail "E89: the refusal did not name $DR_PLANTED_RING: see dr-escrow-short.log"
    [ -z "$(ls -A "$WORK/dr-short")" ] || fail "E89: the refused recovery wrote an object"
    pass "E89: an escrow that lost $DR_PLANTED_RING is refused before any object is written: a ring's version is never generated in its place"
}

# dr_render <rendered overlay>: every ring's check value published in the run's own ConfigMap, in both environments, so a replica - or
# the verification - given another value under that version refuses to start. The config-drift probe changes one FINANCIAL value too.
dr_render() {
    dr_config=$(ls "$1"/ConfigMap.finapp-app-config*.yaml)
    sed 's/^\(FINAPP_[A-Z0-9_]*_RING_CHECKVALUES\)=\(1:[0-9a-f]*\)$/  \1: "\2"/' "$DR_VAULT/rings.checkvalues" > "$WORK/dr-config-lines"
    if [ "$DR_PHASE" = rebuild ] && [ "$PROBE" = config-drift ]; then
        printf '%s\n' "$DR_DRIFT_LINE" >> "$WORK/dr-config-lines"
        log "probe config-drift: $DR_DRIFT_LINE in the rebuild's configuration"
    fi
    sed -i "/^  FINAPP_DB_INSTANCES: /r $WORK/dr-config-lines" "$dr_config" "$1/all.yaml"
    while IFS= read -r dr_line; do
        grep -qxF -e "$dr_line" "$dr_config" && grep -qxF -e "$dr_line" "$1/all.yaml" || cannot "the run's configuration could not be given '$dr_line'"
    done < "$WORK/dr-config-lines"
    log "every ring's check value published in the run's own configuration ($(grep -c . "$DR_VAULT/rings.checkvalues") rings)"
}

# ---------------------------------------------------------------------------------------------------
# The registry, cut (E88; THREAT_MODEL.md T42): every node of the rebuild refuses outbound TCP to anything outside the cluster's own
# networks from the moment it exists, so no image can come from a registry; the vault's set is imported instead.
# ---------------------------------------------------------------------------------------------------

dr_cluster_made() {
    [ "$DR_PHASE" = rebuild ] || return 0
    dr_kind_net=$(dock network inspect kind -f '{{range .IPAM.Config}}{{.Subnet}} {{end}}' | tr ' ' '\n' | grep -v ':' | grep . | head -1)
    dr_pods=$(dock exec "$NODE" cat /etc/kubernetes/manifests/kube-controller-manager.yaml | sed -n 's/.*--cluster-cidr=\([0-9./]*\).*/\1/p' | head -1)
    dr_services=$(dock exec "$NODE" cat /etc/kubernetes/manifests/kube-apiserver.yaml | sed -n 's/.*--service-cluster-ip-range=\([0-9./]*\).*/\1/p' | head -1)
    [ -n "$dr_kind_net" ] && [ -n "$dr_pods" ] && [ -n "$dr_services" ] \
        || cannot "the cluster's networks could not be read: node '$dr_kind_net', pods '$dr_pods', services '$dr_services'"
    for node in $NODES; do
        dock exec "$node" sh -c "iptables -N FINAPP-DR-NO-REGISTRY && iptables -A FINAPP-DR-NO-REGISTRY -d $dr_kind_net -j RETURN \
            && iptables -A FINAPP-DR-NO-REGISTRY -d $dr_pods -j RETURN && iptables -A FINAPP-DR-NO-REGISTRY -d $dr_services -j RETURN \
            && iptables -A FINAPP-DR-NO-REGISTRY -d 127.0.0.0/8 -j RETURN && iptables -A FINAPP-DR-NO-REGISTRY -p tcp -j REJECT --reject-with tcp-reset \
            && iptables -I OUTPUT 1 -j FINAPP-DR-NO-REGISTRY \
            && { ip6tables -I OUTPUT 1 -p tcp ! -d ::1/128 -j REJECT --reject-with tcp-reset || ! test -e /proc/net/if_inet6; }" \
            >> "$ART/dr-registry.log" 2>&1 || cannot "the node $node's registry could not be cut: see dr-registry.log"
    done
    log "every node refuses outbound TCP outside $dr_kind_net, $dr_pods, $dr_services and loopback"
    dr_registry_refused start
}

# dr_registry_refused <label>: the cut seen, not assumed - a pull of an image no vault holds (the fault proxy, by its pinned digest) refused
# on every node, and the image absent after it; the store read first, so a node whose store cannot be listed is not read as cut.
dr_registry_refused() {
    dr_control="$(normalized "$TOXIPROXY_REPOSITORY")@$TOXIPROXY_DIGEST"
    for node in $NODES; do
        dock exec "$node" ctr -n k8s.io images ls -q > "$WORK/dr-store" 2>&1 || cannot "the node $node's image store could not be listed"
        grep -q '^registry.k8s.io/' "$WORK/dr-store" || cannot "the node $node's store lists none of the node image's own: not a reading"
        if dock exec "$node" timeout 120 ctr -n k8s.io images pull --platform linux/amd64 "$dr_control" > "$ART/dr-registry-$1-$node.log" 2>&1; then
            fail "E88 ($1): the node $node pulled $dr_control: its registry is not cut"
        fi
        [ -z "$(dock exec "$node" ctr -n k8s.io images ls -q "name==$dr_control")" ] || fail "E88 ($1): $dr_control is in the node $node's store after a refused pull"
    done
    pass "E88 ($1): no node reaches a registry: a pull of $TOXIPROXY_REPOSITORY by its pinned digest, which no vault holds, refused on each"
}

# dr_held <reference> [node]: in the rebuild, once the vault's images are imported, whether every node (or the one named) holds it. The
# image loaders skip what it holds, so nothing is fetched again behind the cut.
dr_held() {
    [ -n "$DR_IMPORTED" ] || return 1
    dr_ref=$(normalized "$1")
    for dr_node in ${2:-$NODES}; do
        [ -n "$(dock exec "$dr_node" ctr -n k8s.io images ls -q "name==$dr_ref" 2>/dev/null)" ] || return 1
    done
}

# The image set, before the disaster: every image the node held that its own node image does not bring, exported from its store by the
# names it holds them under, with each name's digest. A mirror stands in for this in a real environment (THREAT_MODEL.md T42).
dr_image_set() {
    dock exec "$NODE" ctr -n k8s.io images ls | awk 'NR > 1 && $1 !~ /^sha256:/ && $1 !~ /^registry\.k8s\.io\// && $1 !~ /^docker\.io\/kindest\// {print $1 "\t" $3}' \
        > "$DR_VAULT/images.tsv" || cannot "the node's images could not be listed"
    grep -q "$(normalized "${IMAGE_APP_N%@*}")@" "$DR_VAULT/images.tsv" || cannot "the image set holds no finapp-app by digest"
    # shellcheck disable=SC2046
    dock exec "$NODE" ctr -n k8s.io images export --platform linux/amd64 /var/tmp/finapp-image-set.tar $(cut -f1 "$DR_VAULT/images.tsv") \
        >> "$ART/images.log" 2>&1 || cannot "the image set could not be exported: see images.log"
    dock cp "$NODE:/var/tmp/finapp-image-set.tar" "$(dpath "$DR_VAULT/images.tar")" >> "$ART/images.log" 2>&1 || cannot "the image set could not be copied out"
    dock exec "$NODE" rm -f /var/tmp/finapp-image-set.tar || true
    MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$DR_VAULT/images.tar")" | cut -d' ' -f1 > "$DR_VAULT/images.sha256"
    log "the image set: $(grep -c . "$DR_VAULT/images.tsv") names, $(wc -c < "$DR_VAULT/images.tar" | tr -d ' ') bytes"
}

# dr_images_loaded, the rebuild's images-loaded step: the set imported into every node behind the cut, every name back under its digest,
# and the release record's two digests among them. Nothing is built and nothing is pulled.
dr_images_loaded() {
    dr_holds images
    for node in $NODES; do
        dock exec -i "$node" ctr -n k8s.io images import --platform linux/amd64 - < "$DR_VAULT/images.tar" >> "$ART/images.log" 2>&1 \
            || cannot "the image set could not be imported into the node $node: see images.log"
    done
    while IFS="$(printf '\t')" read -r dr_name dr_digest; do
        for node in $NODES; do
            [ "$(dock exec "$node" ctr -n k8s.io images ls "name==$dr_name" | awk 'NR == 2 {print $3}')" = "$dr_digest" ] \
                || fail "E88: the node $node does not hold $dr_name at $dr_digest after the import"
        done
    done < "$DR_VAULT/images.tsv"
    DR_IMPORTED=1
    IMAGE_APP_N=$(sed -n 's/^app\.n=//p' "$DR_VAULT/release.txt")
    IMAGE_MIGRATOR_N=$(sed -n 's/^migrator\.n=//p' "$DR_VAULT/release.txt")
    case $IMAGE_APP_N in finapp-app@sha256:*) ;; *) cannot "the release record names no finapp-app by digest: '$IMAGE_APP_N'" ;; esac
    case $IMAGE_MIGRATOR_N in finapp-migrator@sha256:*) ;; *) cannot "the release record names no finapp-migrator by digest: '$IMAGE_MIGRATOR_N'" ;; esac
    dr_held "$IMAGE_APP_N" && dr_held "$IMAGE_MIGRATOR_N" || fail "the release record's digests are not among the images the vault brought"
    IMAGE_APP_NM1=$IMAGE_APP_N
    IMAGE_MIGRATOR_NM1=$IMAGE_MIGRATOR_N
    if [ "$POLICY_ENGINE" = kube-router ]; then
        dr_held "$KUBE_ROUTER_REPOSITORY@$KUBE_ROUTER_DIGEST" || fail "the vault brought no kube-router, which this machine's policy engine is"
        sed "s#KUBE_ROUTER_IMAGE#$KUBE_ROUTER_REPOSITORY@$KUBE_ROUTER_DIGEST#" "$DIR/kube-router.yaml" | kube apply -f - >> "$ART/apply.log" 2>&1 \
            || cannot "kube-router could not be applied"
    fi
    pass "E88: $(grep -c . "$DR_VAULT/images.tsv") images imported from the vault into every node, each under its digest; the fleet's are the release record's"
}

# ---------------------------------------------------------------------------------------------------
# The backup and what else must outlive the environment, before the disaster.
# ---------------------------------------------------------------------------------------------------

# The four statements the restore rehearsal reads its manifest and its counter with, read from that script rather than copied: each line
# a plain double-quoted assignment, evaluated as the script itself evaluates it, and refused unless it is one.
dr_statements() {
    for dr_statement in COUNTS_SQL SEQUENCES_SQL HISTORY_SQL XID8_SQL; do
        dr_line=$(grep "^$dr_statement=\"" "$ROOT/infra/scripts/restore-rehearsal.sh")
        printf '%s\n' "$dr_line" | grep -Eqx "$dr_statement=\"[^\"\$\`]+\"" || cannot "restore-rehearsal.sh's $dr_statement is not one plain assignment"
        eval "$dr_line"
    done
}

# Run inside the source's pod over its own socket, as the restore rehearsal's kind source is read: one REPEATABLE READ READ ONLY session
# exports its snapshot and reads the manifest in it - every table's count, every sequence, each schema history's head, the instant, T -
# and pg_dump writes the data from that same snapshot to stdout, into openssl on the host.
DR_INNER='set -eu
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
printf "%s\n" "\\o /tmp/dr-manifest-counts" "$COUNTS_SQL;" "\\o /tmp/dr-manifest-sequences" "$SEQUENCES_SQL;" \
    "\\o /tmp/dr-manifest-history" "$HISTORY_SQL;" "\\o /tmp/dr-manifest-instant" "SELECT now() || '"'"'|'"'"' || extract(epoch FROM now())::bigint;" \
    "\\o" "SELECT '"'"'counted'"'"';" >&3
i=0
until grep -q "^counted" "$d/out"; do
    i=$((i + 1)); { [ "$i" -lt 3000 ] && kill -0 "$session" 2>/dev/null; } || { cat "$d/err" >&2; exit 3; }; sleep 0.1
done
pg_dump --snapshot="$snapshot" --format=custom
printf "%s\n" "COMMIT;" >&3
exec 3>&-
wait "$session"
rm -rf "$d"
echo done > /tmp/dr-backup-status'

dr_backup() {
    dr_statements
    kube -n finapp exec -i "$PG_POD" -c postgres -- env PGUSER=finapp PGDATABASE=finapp COUNTS_SQL="$COUNTS_SQL" SEQUENCES_SQL="$SEQUENCES_SQL" \
        HISTORY_SQL="$HISTORY_SQL" sh -c "$DR_INNER" < /dev/null 2> "$ART/dr-backup.err" | dr_seal backup backup || cannot "the backup could not be written"
    [ "$(kube -n finapp exec "$PG_POD" -c postgres -- sh -c 'cat /tmp/dr-backup-status && rm -f /tmp/dr-backup-status' 2>/dev/null | tr -d '\r')" = done ] \
        || { cat "$ART/dr-backup.err" >&2; cannot "the dump did not complete in its snapshot: see dr-backup.err"; }
    mkdir "$WORK/dr-manifest" || cannot "the manifest's directory could not be made"
    for dr_part in counts sequences history instant; do
        kube -n finapp exec "$PG_POD" -c postgres -- sh -c "cat /tmp/dr-manifest-$dr_part && rm -f /tmp/dr-manifest-$dr_part" | tr -d '\r' \
            > "$WORK/dr-manifest/$dr_part" || cannot "the manifest's $dr_part could not be read"
        [ -s "$WORK/dr-manifest/$dr_part" ] || cannot "the manifest's $dr_part is empty"
    done
    DR_BACKUP_AT=$(cut -d'|' -f1 "$WORK/dr-manifest/instant")
    DR_BACKUP_EPOCH=$(cut -d'|' -f2 "$WORK/dr-manifest/instant")
    case $DR_BACKUP_EPOCH in '' | *[!0-9]*) cannot "the backup's instant could not be read: '$DR_BACKUP_EPOCH'" ;; esac
    DR_BACKED_UP_ENTRIES=$(sed -n 's/^ledger\.journal_entry|\([0-9]*\)$/\1/p' "$WORK/dr-manifest/counts")
    [ -n "$DR_BACKED_UP_ENTRIES" ] || cannot "the manifest counts no journal entry"
    tar -C "$WORK" -cf - dr-manifest | dr_seal manifest backup || cannot "the manifest could not be sealed"
    rm -rf "$WORK/dr-manifest"
    dr_no_plaintext
    pass "backed up in one snapshot at $DR_BACKUP_AT: $(wc -c < "$DR_VAULT/backup.enc" | tr -d ' ') encrypted bytes, $DR_BACKED_UP_ENTRIES journal entries, the manifest sealed beside it"
}

# The restore's two commands, built once from this tree's test source set on this machine, as the restore rehearsal builds them: the
# rebuild runs them against the recovered database.
dr_launcher() {
    (
        cd "$ROOT"
        if [ -x ./gradlew.bat ] && command -v cmd.exe >/dev/null 2>&1; then
            ./gradlew.bat --console=plain -q :app:restoreRehearsalLauncher
        else
            ./gradlew --console=plain -q :app:restoreRehearsalLauncher
        fi
    ) > "$ART/dr-launcher.log" 2>&1 || cannot "the restore's commands could not be built: see dr-launcher.log"
    [ -s "$ROOT/app/build/restore-rehearsal/java" ] && [ -s "$ROOT/app/build/restore-rehearsal/classpath.args" ] \
        || cannot "the launcher wrote no java or classpath"
}

# dr_fingerprints: each running replica's configuration fingerprint (section 11.31's finapp_config_fingerprint), read through the scraper
# the network-policy step leaves, one line per replica: its address and the digest, or `unread`.
dr_fingerprints() {
    for dr_ip in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app --field-selector=status.phase=Running -o jsonpath='{range .items[*]}{.status.podIP}{"\n"}{end}'); do
        dr_fp=$(kube -n finapp-observability exec np-scraper -c probe -- curl -s --max-time 15 "http://$dr_ip:8081/actuator/prometheus" 2>/dev/null \
            | sed -n 's/^finapp_config_fingerprint{.*fingerprint="\([^"]*\)".*/\1/p' | head -1)
        printf '%s\t%s\n' "$dr_ip" "${dr_fp:-unread}"
    done
}

# dr_fingerprint_judged <label> <count>: as many replicas as asked, every one read, and every one the fingerprint the lost fleet ran.
dr_fingerprint_judged() {
    dr_fingerprints > "$ART/dr-fingerprints-$1.tsv"
    dr_recorded=$(cat "$DR_VAULT/fingerprint.txt")
    [ "$(grep -c . "$ART/dr-fingerprints-$1.tsv")" -ge "$2" ] || fail "configuration ($1): $(grep -c . "$ART/dr-fingerprints-$1.tsv") replicas read, not $2"
    ! grep -q '	unread$' "$ART/dr-fingerprints-$1.tsv" || fail "configuration ($1): a replica's fingerprint could not be read: see dr-fingerprints-$1.tsv"
    dr_drifted=$(awk -F'\t' -v want="$dr_recorded" '$2 != want' "$ART/dr-fingerprints-$1.tsv" | grep -c . || true)
    [ "$dr_drifted" = 0 ] || fail "configuration ($1): $dr_drifted replicas run another configuration than the lost fleet's ($dr_recorded): see dr-fingerprints-$1.tsv"
    pass "configuration ($1): every one of $(grep -c . "$ART/dr-fingerprints-$1.tsv") replicas runs the lost fleet's fingerprint"
}

dr_before() {
    step dr-launcher
    dr_launcher

    step dr-workload
    start_workload dr
    sleep "$DR_WORKLOAD_SECONDS"
    stop_workload dr
    [ "$(workload_failed dr)" = 0 ] || fail "the workload before the disaster failed $(workload_failed dr) times: see client.log"
    census dr-before

    step dr-backup
    dr_backup
    # The client's account of what it was told, at T: the census's own expectations, read rather than computed again.
    cp "$ART/census-dr-before.expected" "$DR_VAULT/expected.env" || cannot "the census's expectations could not be kept"
    # The client's memory of its participants (their sign-ins, the operators' factors), never its record of what it moved.
    tar -C "$CLIENT" -cf - --exclude='workload-*' --exclude='stop-*' --exclude='edge-ca.crt' . | dr_seal client backup \
        || cannot "the client's memory could not be sealed"
    dr_fingerprints > "$ART/dr-fingerprints-before.tsv"
    [ "$(grep -c . "$ART/dr-fingerprints-before.tsv")" -ge "$FLEET_START" ] && ! grep -q '	unread$' "$ART/dr-fingerprints-before.tsv" \
        && [ "$(cut -f2 "$ART/dr-fingerprints-before.tsv" | sort -u | grep -c .)" = 1 ] \
        || cannot "the fleet's configuration fingerprint could not be read as one: see dr-fingerprints-before.tsv"
    cut -f2 "$ART/dr-fingerprints-before.tsv" | head -1 > "$DR_VAULT/fingerprint.txt"
    dr_image_set
    cp "$ART/images.txt" "$DR_VAULT/release.txt" || cannot "the release record could not be kept"
    git -C "$(dpath "$ROOT")" rev-parse HEAD > "$DR_VAULT/repository.ref" || cannot "the repository's commit could not be read"
    dr_no_plaintext

    # The window the disaster loses: money moved and acknowledged after T. Its transfers carry their own prefix, so the rebuild can look
    # for any of them; the positive control is that the lost environment held them.
    step dr-lost-window
    start_workload dr-lost
    sleep "$DR_LOST_SECONDS"
    stop_workload dr-lost
    [ "$(workload_failed dr-lost)" = 0 ] || fail "the window's workload failed $(workload_failed dr-lost) times: see client.log"
    drained 240 || fail "the outbox did not drain before the disaster"
    dr_lost=$(psql_query -c "SELECT count(*) FROM transfers.transfer WHERE reference LIKE 'wl-dr-lost-%'" | tr -d '[:space:]')
    dr_acknowledged=$(awk -F'\t' '$1 == "transfer" && $NF == "ok" {print $3}' "$CLIENT/workload-dr-lost.tsv" | sort -u | grep -c . || true)
    [ "${dr_lost:-0}" -gt 0 ] && [ "$dr_lost" = "$dr_acknowledged" ] \
        || fail "the window before the disaster: $dr_lost transfers in the books, $dr_acknowledged acknowledged to the client"
    dr_entries=$(psql_query -c "SELECT count(*) FROM ledger.journal_entry" | tr -d '[:space:]')
    DR_DISASTER_DB=$(db_epoch)
    {
        printf 'backup-at\t%s\nbackup-epoch\t%s\n' "$DR_BACKUP_AT" "$DR_BACKUP_EPOCH"
        printf 'disaster-db-epoch\t%s\nlost-transfers\t%s\nlost-entries\t%s\n' "$DR_DISASTER_DB" "$dr_lost" "$((dr_entries - DR_BACKED_UP_ENTRIES))"
    } > "$DR_VAULT/instants.tsv"
    pass "the window after the backup: $dr_lost transfers acknowledged and in the books, $((dr_entries - DR_BACKED_UP_ENTRIES)) journal entries written after T"

    # The disaster: the cluster gone, and with it its node, the database's volume (the provisioner keeps it on the node), every Secret,
    # the broker and the simulator's memory. What is left is the repository and the vault.
    step dr-disaster
    printf 'disaster-host-epoch\t%s\n' "$(date +%s)" >> "$DR_VAULT/instants.tsv"
    "$KIND_BIN" delete cluster --name "$CLUSTER" >> "$ART/cluster.log" 2>&1 || cannot "the cluster could not be deleted"
    CLUSTER_UP=""
    ! "$KIND_BIN" get clusters 2>/dev/null | grep -qx "$CLUSTER" || fail "the cluster $CLUSTER is still listed after its deletion"
    [ -z "$(dock ps -aq --filter "label=io.x-k8s.kind.cluster=$CLUSTER")" ] || fail "a node of $CLUSTER survived the disaster"
    pass "the disaster: $CLUSTER, its node, the database's volume and every Secret in it are gone; the vault and the repository are what is left"

    # The rebuild: a run of its own, from the repository at the same commit and the vault alone; a new cluster of another name, so a
    # cluster the deletion missed could never be mistaken for the recovered one.
    step dr-rebuild
    rm -rf "$ART/rebuild"
    dr_code=0
    REHEARSAL_SCOPE=dr REHEARSAL_DR_PHASE=rebuild REHEARSAL_DR_VAULT="$DR_VAULT" REHEARSAL_CLUSTER="$CLUSTER-recovered" \
        REHEARSAL_ARTEFACTS="$ART/rebuild" REHEARSAL_SKIP_BUILD=1 sh "$ROOT/infra/scripts/kind-rehearsal.sh" || dr_code=$?
    case $dr_code in
        0) pass "the platform rebuilt from the repository and the vault: see rebuild/rehearsal.log" ;;
        1) fail "the rebuild went red: see rebuild/rehearsal.log and rebuild/assertions.tsv" ;;
        *) cannot "the rebuild could not run: see rebuild/rehearsal.log" ;;
    esac

    step dr-record
    dr_record
}

# The exercise's record: each step's seconds in both runs, the window lost between the backup and the disaster by the database's clock,
# and the recovery times by this machine's clock from the disaster to the release, to the fleet ready, to money moved and proven, and to
# the fleet scaled. What it measures is the procedure at this size on this machine: never a managed service's (ADR-0128).
dr_record() {
    dr_rec="$ART/dr-record.tsv"
    dr_instant() { sed -n "s/^$1	//p" "$DR_VAULT/instants.tsv" | head -1; }
    dr_at() { sed -n "s/^$1	//p" "$ART/rebuild/dr-marks.tsv" | head -1; }
    dr_host=$(dr_instant disaster-host-epoch)
    {
        printf 'measure\tvalue\tunit\n'
        printf 'backup-at\t%s\tthe database'"'"'s instant\n' "$(dr_instant backup-at)"
        printf 'window-lost\t%s\ts, the database'"'"'s clock\n' "$(( $(dr_instant disaster-db-epoch) - $(dr_instant backup-epoch) ))"
        printf 'lost-transfers\t%s\tacknowledged, absent after the restore\n' "$(dr_instant lost-transfers)"
        printf 'lost-entries\t%s\tjournal entries\n' "$(dr_instant lost-entries)"
        for dr_mark_name in released fleet-ready money-moved scaled; do
            dr_value=$(dr_at "$dr_mark_name")
            [ -n "$dr_value" ] || cannot "the rebuild's record has no $dr_mark_name instant"
            printf 'to-%s\t%s\ts from the disaster, this machine'"'"'s clock\n' "$dr_mark_name" "$((dr_value - dr_host))"
        done
        awk -F'\t' '{print "restore-" $1 "\t" $3 "\tbytes in " $4 " ms"}' "$ART/rebuild/dr-rates.tsv"
        awk -F'\t' '{print "step-before-" $1 "\t" $2 "\ts"}' "$ART/timings.tsv"
        awk -F'\t' '{print "step-rebuild-" $1 "\t" $2 "\ts"}' "$ART/rebuild/timings.tsv"
    } > "$dr_rec"
    pass "the exercise's record: $(grep -c '^step-' "$dr_rec") steps timed, the window lost and four recovery times from the disaster (dr-record.tsv)"
}

# ---------------------------------------------------------------------------------------------------
# The rebuild: the restore held, verified and released before anything else can log in (OPERATIONS_RUNBOOK.md sections 11.35 and 11.55).
# ---------------------------------------------------------------------------------------------------

# dr_role_refused <role>: 0 when the database refuses the role's log-in because it may not log in, over the pod's own socket, where its
# password is not the question.
dr_role_refused() {
    if kube -n finapp exec "$PG_POD" -c postgres -- psql -X -A -t -U "$1" -d finapp -c 'SELECT 1' > "$WORK/dr-login.out" 2>&1; then
        return 1
    fi
    grep -q 'is not permitted to log in' "$WORK/dr-login.out"
}

dr_restore() {
    # 1. Held: the bootstrap made the platform's roles able to log in, on an empty database nothing yet reaches; they are held before
    #    anything is restored, each refused for that reason.
    step dr-roles-held
    psql_query -v ON_ERROR_STOP=1 -c "ALTER ROLE finapp_app NOLOGIN; ALTER ROLE finapp_migrator NOLOGIN; ALTER ROLE finapp_reporting NOLOGIN" \
        >> "$ART/dr-restore.log" 2>&1 || cannot "the platform's roles could not be held"
    for dr_role in finapp_app finapp_migrator finapp_reporting; do
        dr_role_refused "$dr_role" || fail "$dr_role is not refused at log-in before the restore: $(tr '\n' ' ' < "$WORK/dr-login.out" | cut -c1-200)"
    done
    pass "finapp_app, finapp_migrator and finapp_reporting each refused at log-in before anything is restored"
    dr_holds client
    dr_open client | tar -C "$CLIENT" -xf - || cannot "the client's memory could not be restored"
    [ -s "$CLIENT/state.properties" ] || cannot "the client's memory holds no state"

    # 2. The restore, all or nothing, read from the vault through a pipe; its output kept in the work directory, where a failed COPY's
    #    quoted row stays.
    step dr-restore
    dr_holds backup manifest
    dr_started=$(dr_millis)
    dr_open backup | kube -n finapp exec -i "$PG_POD" -c postgres -- pg_restore -U finapp -d finapp --single-transaction --exit-on-error \
        > "$WORK/dr-pg-restore.log" 2>&1 || cannot "pg_restore failed: $(sed -n 's/^\(pg_restore: error: [^:]*\).*/\1/p' "$WORK/dr-pg-restore.log" | head -3 | tr '\n' ' ')"
    dr_took=$(($(dr_millis) - dr_started))
    dr_bytes=$(psql_query -c "SELECT pg_database_size('finapp')" | tr -d '[:space:]')
    printf 'logical\t%s\t%s\t%s\n' "$CLUSTER" "$dr_bytes" "$dr_took" > "$ART/dr-rates.tsv"
    pass "restored from the vault: $dr_bytes bytes in $dr_took ms, the roles still held"

    # 3. The counter (section 11.35 step 4): past every restored xid8, the server stopped cleanly and its epoch set on its own volume.
    step dr-counter
    dr_statements
    dr_highest=$(psql_query -c "$XID8_SQL" | tr -d '[:space:]')
    dr_counter=$(psql_query -c "SELECT pg_current_xact_id()" | tr -d '[:space:]')
    case $dr_highest$dr_counter in '' | *[!0-9]*) cannot "the counter could not be read: highest '$dr_highest', counter '$dr_counter'" ;; esac
    if [ "$(psql_query -c "SELECT $dr_counter::numeric <= $dr_highest::numeric" | tr -d '[:space:]')" = t ]; then
        dr_epoch "$(psql_query -c "SELECT floor($dr_highest::numeric / 4294967296)::bigint + 1" | tr -d '[:space:]')"
    fi
    [ "$(psql_query -c "SELECT pg_snapshot_xmin(pg_current_snapshot())::text::numeric > $dr_highest::numeric" | tr -d '[:space:]')" = t ] \
        || fail "the counter is still at or below the highest restored xid8 ($dr_highest)"
    for dr_role in finapp_app finapp_migrator finapp_reporting; do
        dr_role_refused "$dr_role" || fail "$dr_role can log in after the server's restart, before any check"
    done
    pass "the counter moved past the highest restored xid8 ($dr_highest); the roles still held after the restart"

    # 4. Compared with the manifest the backup's own session read: every table's count, every sequence at or past, each history's head.
    step dr-compare
    dr_compare

    # 5. The platform's own verification (section 11.35's checks, the evidence held by the key rings among them), and E89's second line.
    step dr-verify
    if [ "$PROBE" = skip-verification ]; then
        log "probe skip-verification: the verification is not run"
    else
        dr_tunnel
        dr_verify planted
        dr_verify recovered
    fi

    # 6. The window: on kind no provider file survives the disaster - the simulator's memory was the cluster's - so nothing is delivered
    #    again here; the window's delivery is E61's drill (section 11.55). Recorded, not skipped silently.
    step dr-window
    printf 'WINDOW|not delivered|no provider file survives a kind cluster: the simulator kept its files in the lost cluster (E61 delivers a window)\n' \
        > "$ART/dr-window.txt"
    log "the window since $(sed -n 's/^backup-at	//p' "$DR_VAULT/instants.tsv"): no provider's file survives here to deliver again"

    # 7. The release: a person's, recorded, and only after every verdict above passed.
    step dr-release
    if ! dr_releasable; then
        [ "$PROBE" = skip-verification ] || cannot "the recovered database is not releasable and no probe asked for that: see dr-verdicts.txt"
        dr_held_fleet
        fail "probe skip-verification: the release refused without the verification's verdicts; the roles stayed NOLOGIN and the database refused the fleet, which moved no money"
    fi
    [ "$PROBE" != skip-verification ] || fail "probe skip-verification: the release went ahead without the verification"
    dr_release
}

# dr_epoch <epoch>: section 11.35 step 4 on the kind StatefulSet - scaled to zero, so its server stops cleanly; pg_resetwal -e on its own
# volume from a pod of the same image, under the same restricted profile; scaled back to one.
dr_epoch() {
    dr_image=$(kube -n finapp get statefulset finapp-postgres -o jsonpath='{.spec.template.spec.containers[0].image}')
    [ -n "$dr_image" ] || cannot "the test-only server's image could not be read"
    kube -n finapp scale statefulset finapp-postgres --replicas=0 >> "$ART/apply.log" 2>&1 || cannot "the test-only server could not be stopped"
    kube -n finapp wait --for=delete "pod/$PG_POD" --timeout=180s >> "$ART/apply.log" 2>&1 || cannot "the test-only server did not stop"
    cat <<YAML | kube apply -f - >> "$ART/apply.log" 2>&1 || cannot "the counter's pod could not be applied"
apiVersion: v1
kind: Pod
metadata:
  name: finapp-dr-counter
  namespace: finapp
  labels:
    finapp/test-only: "true"
spec:
  restartPolicy: Never
  serviceAccountName: finapp-test-only
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 999
    runAsGroup: 999
    fsGroup: 999
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: counter
      image: $dr_image
      imagePullPolicy: IfNotPresent
      command: ["sh", "-c", "pg_resetwal -e \"\$EPOCH\" \"\$PGDATA\""]
      env:
        - name: EPOCH
          value: "$1"
      resources:
        requests: {cpu: 100m, memory: 128Mi}
        limits: {cpu: 500m, memory: 256Mi}
      securityContext:
        allowPrivilegeEscalation: false
        privileged: false
        readOnlyRootFilesystem: true
        capabilities: {drop: ["ALL"]}
      volumeMounts:
        - name: data
          mountPath: /var/lib/postgresql
  volumes:
    - name: data
      persistentVolumeClaim:
        claimName: data-$PG_POD
YAML
    dr_deadline=$(( $(date +%s) + 180 ))
    dr_phase=""
    while [ "$(date +%s)" -lt "$dr_deadline" ]; do
        dr_phase=$(kube -n finapp get pod finapp-dr-counter -o jsonpath='{.status.phase}' 2>/dev/null || true)
        case $dr_phase in Succeeded | Failed) break ;; esac
        sleep 2
    done
    kube -n finapp logs finapp-dr-counter > "$ART/dr-counter.log" 2>&1 || true
    kube -n finapp delete pod finapp-dr-counter --wait=true >> "$ART/apply.log" 2>&1 || true
    [ "$dr_phase" = Succeeded ] || cannot "pg_resetwal did not complete ($dr_phase): see dr-counter.log"
    kube -n finapp scale statefulset finapp-postgres --replicas=1 >> "$ART/apply.log" 2>&1 || cannot "the test-only server could not be started again"
    kube -n finapp rollout status statefulset/finapp-postgres --timeout=240s >> "$ART/apply.log" 2>&1 || fail "the test-only server did not return after its counter moved"
    log "the counter's epoch set to $1 (dr-counter.log)"
}

dr_compare() {
    dr_holds manifest
    dr_open manifest | tar -C "$WORK" -xf - || cannot "the manifest could not be read"
    dr_statements
    : > "$ART/dr-verdicts-compare.txt"
    psql_query -c "$COUNTS_SQL" | tr -d '\r' > "$WORK/dr-counts"
    if cmp -s "$WORK/dr-manifest/counts" "$WORK/dr-counts"; then
        echo "PASS|row counts equal to the backup's|$(grep -c . "$WORK/dr-counts") tables" >> "$ART/dr-verdicts-compare.txt"
    else
        echo "FAIL|row counts equal to the backup's|$(diff "$WORK/dr-manifest/counts" "$WORK/dr-counts" | grep -c '^[<>]') lines differ" >> "$ART/dr-verdicts-compare.txt"
    fi
    psql_query -c "$SEQUENCES_SQL" | tr -d '\r' > "$WORK/dr-sequences"
    dr_behind=$(awk -F'|' '
        function at_or_past(r, s) {
            if (s == "none") return 1
            if (r == "none") return 0
            if (length(r) != length(s)) return length(r) > length(s)
            return r >= s
        }
        NR == FNR { restored[$1] = $2; next }
        !($1 in restored) || !at_or_past(restored[$1], $2) { n++ }
        END { print n + 0 }' "$WORK/dr-sequences" "$WORK/dr-manifest/sequences")
    if [ "$dr_behind" = 0 ]; then
        echo "PASS|sequences at or past the backup's|$(grep -c . "$WORK/dr-manifest/sequences") sequences" >> "$ART/dr-verdicts-compare.txt"
    else
        echo "FAIL|sequences at or past the backup's|$dr_behind behind or gone" >> "$ART/dr-verdicts-compare.txt"
    fi
    psql_query -c "$HISTORY_SQL" | tr -d '\r' > "$WORK/dr-history"
    if cmp -s "$WORK/dr-manifest/history" "$WORK/dr-history"; then
        echo "PASS|schema histories at the backup's heads|$(grep -c . "$WORK/dr-history") schemas" >> "$ART/dr-verdicts-compare.txt"
    else
        echo "FAIL|schema histories at the backup's heads|$(diff "$WORK/dr-manifest/history" "$WORK/dr-history" | grep -c '^[<>]') lines differ" >> "$ART/dr-verdicts-compare.txt"
    fi
    rm -rf "$WORK/dr-manifest" "$WORK/dr-counts" "$WORK/dr-sequences" "$WORK/dr-history"
    [ "$(grep -c '^PASS|' "$ART/dr-verdicts-compare.txt")" = 3 ] \
        || fail "the restore differs from the backup's manifest, so it stays held: $(grep '^FAIL|' "$ART/dr-verdicts-compare.txt" | tr '\n' ';')"
    pass "the restore equal to the backup's manifest: $(cut -d'|' -f2 "$ART/dr-verdicts-compare.txt" | paste -sd, -)"
}

# The verification's path to the recovered database: the API server's port-forward, run by kubectl in a container of the node image on the
# kind network, published on this machine's loopback alone. NetworkPolicy does not apply to it, as it does not to kubectl exec: it is an
# administrator's path, and no policy is opened for it.
dr_tunnel() {
    dock exec "$NODE" cat /etc/kubernetes/admin.conf > "$WORK/dr-admin.conf" || cannot "the cluster's administrator configuration could not be read"
    dr_tunnel_name="$CLUSTER-dr-tunnel"
    dock run -d --name "$dr_tunnel_name" --network kind --publish 127.0.0.1::15432 \
        --mount "type=bind,source=$(dpath "$WORK/dr-admin.conf"),target=/admin.conf,readonly" \
        --entrypoint kubectl "$KIND_NODE_IMAGE" --kubeconfig /admin.conf -n finapp port-forward --address 0.0.0.0 "pod/$PG_POD" 15432:5432 \
        >/dev/null || cannot "the verification's tunnel could not start"
    CONTAINERS="$CONTAINERS $dr_tunnel_name"
    dr_deadline=$(( $(date +%s) + 60 ))
    until dock logs "$dr_tunnel_name" 2>&1 | grep -q 'Forwarding from 0.0.0.0:15432'; do
        [ "$(date +%s)" -lt "$dr_deadline" ] || cannot "the verification's tunnel did not forward: $(dock logs "$dr_tunnel_name" 2>&1 | tail -3 | tr '\n' ' ')"
        sleep 2
    done
    DR_TUNNEL_PORT=$(dock port "$dr_tunnel_name" 15432/tcp | head -1 | sed 's/.*://' | tr -d '\r')
    case $DR_TUNNEL_PORT in '' | *[!0-9]*) cannot "the tunnel's port could not be read" ;; esac
}

# dr_verify <recovered|planted>: RestoreVerification as the overlay's administrator, every session read-only by default, over the tunnel
# with the run's CA, its rings the escrow's and every ring's check value published. `planted` is E89's second line: the planted ring's
# version 1 given a value that is not the escrow's, which must refuse to start on its published check value - what a replica would do.
dr_verify() {
    dr_java=$(tr -d '\r\n' < "$ROOT/app/build/restore-rehearsal/java")
    dr_args=$(dpath "$ROOT/app/build/restore-rehearsal/classpath.args")
    dr_admin=$(kube -n finapp get secret finapp-kind-postgres -o jsonpath='{.data.password}' | base64 -d)
    [ -n "$dr_admin" ] || cannot "the administrator's password could not be read from the run's own Secret"
    dr_ca=$(dpath "$CA_DIR/ca.crt" | sed 's/:/%3A/g')
    rm -f "$ART/dr-verification-$1.txt"
    dr_code=0
    dr_holds rings
    # Each line is NAME=value, exported as it stands, in this subshell alone: the escrow's from the pipe, the check values from the vault.
    dr_open rings | (
        while IFS= read -r dr_line; do
            export "${dr_line?}"
        done
        while IFS= read -r dr_line; do
            export "${dr_line?}"
        done < "$DR_VAULT/rings.checkvalues"
        if [ "$1" = planted ]; then
            eval "export $DR_PLANTED_RING=\$(head -c 32 /dev/urandom | base64 | tr -d '\\n')"
        fi
        FINAPP_DB_URL="jdbc:postgresql://127.0.0.1:$DR_TUNNEL_PORT/finapp?sslmode=verify-ca&sslrootcert=$dr_ca&options=-c%20default_transaction_read_only%3Don" \
            FINAPP_DB_APP_USER=finapp FINAPP_DB_APP_PASSWORD="$dr_admin" \
            FINAPP_DB_REPORTING_USER=finapp_reporting FINAPP_DB_REPORTING_PASSWORD="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')" \
            exec "$dr_java" -Xmx1g "@$dr_args" com.finapp.app.restore.RestoreVerification "$(dpath "$ART/dr-verification-$1.txt")" \
            < /dev/null > "$ART/dr-verification-$1.log" 2>&1
    ) || dr_code=$?
    tr -d '\r' < "$ART/dr-verification-$1.txt" 2>/dev/null | tail -1 | grep -q "^EXIT|$dr_code\$" \
        || cannot "the verification ($1) ended without its report (exit $dr_code): see dr-verification-$1.log"
    if [ "$1" = planted ]; then
        [ "$dr_code" = 2 ] && grep -q 'does not give its published check value' "$ART/dr-verification-$1.log" \
            || fail "E89: the verification started with a value of $DR_PLANTED_RING that is not the escrow's (exit $dr_code): see dr-verification-planted.log"
        pass "E89: a value of $DR_PLANTED_RING that is not the escrow's refused at the start by its published check value; nothing is read under it"
        return 0
    fi
    [ "$dr_code" != 2 ] || cannot "the verification could not run: see dr-verification-recovered.log"
    dr_checks=$(tr -d '\r' < "$ART/dr-verification-$1.txt" | sed -n 's/^CHECKS|\([1-9][0-9]*\)$/\1/p')
    [ -n "$dr_checks" ] || cannot "the verification stated no checks"
    tr -d '\r' < "$ART/dr-verification-$1.txt" | grep '^\(PASS\|FAIL\)|' > "$ART/dr-verdicts.txt"
    [ "$(grep -c . "$ART/dr-verdicts.txt")" = "$dr_checks" ] || cannot "the verification printed a short report"
    ! grep -q '^FAIL|' "$ART/dr-verdicts.txt" \
        || fail "the recovered database is not financially whole, so it stays held: $(grep '^FAIL|' "$ART/dr-verdicts.txt" | tr '\n' ';' | cut -c1-600)"
    pass "the recovered database is financially whole: $(cut -d'|' -f2 "$ART/dr-verdicts.txt" | paste -sd, -)"
}

# dr_releasable: every check of the verification's catalogue and of the comparison a pass, in reports this run wrote.
dr_releasable() {
    [ -s "$ART/dr-verdicts.txt" ] && [ -s "$ART/dr-verdicts-compare.txt" ] || return 1
    ! grep -q '^FAIL|' "$ART/dr-verdicts.txt" "$ART/dr-verdicts-compare.txt" || return 1
    [ "$(grep -c '^PASS|' "$ART/dr-verdicts.txt")" = "$(tr -d '\r' < "$ART/dr-verification-recovered.txt" | sed -n 's/^CHECKS|\([1-9][0-9]*\)$/\1/p')" ]
}

# The release (ADR-0128 decision 3, ADR-0140 decision 4): the three roles in one transaction with the database's instant read, the record
# naming the person, the reason, that instant and the digest of every report it rests on; each role then confirmed to log in. Into the
# restored database's own audit trail it goes with the first real environment's managed restore rehearsal (THREAT_MODEL.md section 6 item
# 62 (a)); here, beside the artefacts, as the incident's record would hold it.
dr_release() {
    dr_rests_on=""
    for dr_report in dr-verdicts-compare.txt dr-verdicts.txt dr-window.txt; do
        dr_rests_on="$dr_rests_on $dr_report=$(MSYS_NO_PATHCONV=1 openssl dgst -sha256 -r "$(dpath "$ART/$dr_report")" | cut -d' ' -f1)"
    done
    dr_released=$(psql_query -v ON_ERROR_STOP=1 -c "BEGIN; ALTER ROLE finapp_app LOGIN; ALTER ROLE finapp_migrator LOGIN; ALTER ROLE finapp_reporting LOGIN;
        SELECT now(); COMMIT;") || cannot "the release failed"
    dr_released_at=$(printf '%s\n' "$dr_released" | tr -d '\r' | grep -v '^\(BEGIN\|COMMIT\|ALTER ROLE\)$' | grep . | tail -1)
    [ -n "$dr_released_at" ] || cannot "the release read no instant"
    printf 'restore\t%s\nreleased_by\t%s\nreason\t%s\nreleased_at\t%s\nbackup\t%s\nrests_on\t%s\n' "$CLUSTER" "$DR_RELEASED_BY" \
        "the platform rebuilt after the loss of its environment: the restore equal to the backup's manifest and financially whole by the platform's own checks" \
        "$dr_released_at" "$(cat "$DR_VAULT/backup.sha256")" "${dr_rests_on# }" > "$ART/release-dr.tsv"
    for dr_role in finapp_app finapp_migrator finapp_reporting; do
        ! dr_role_refused "$dr_role" && tr -d '\r' < "$WORK/dr-login.out" | grep -qx 1 \
            || fail "after the release, $dr_role still cannot log in: $(tr '\n' ' ' < "$WORK/dr-login.out" | cut -c1-200)"
    done
    dr_mark released
    pass "released by $DR_RELEASED_BY at $dr_released_at, recorded with the reports it rests on (release-dr.tsv): the platform's roles log in"
}

# The skip-verification probe's observation: the fleet deployed onto the held database anyway - every replica refused by the database at
# log-in, none ready, and the journal unchanged - before the run is failed for the release it refused.
dr_held_fleet() {
    dr_entries=$(psql_query -c "SELECT count(*) FROM ledger.journal_entry" | tr -d '[:space:]')
    apply_files "$A/Deployment.finapp-app.yaml" "$A/HorizontalPodAutoscaler.finapp-app.yaml"
    sleep 180
    dr_ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
    [ "${dr_ready:-0}" = 0 ] || cannot "probe skip-verification: $dr_ready replicas ready on a held database"
    dr_refused=0
    for dr_pod in $(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name 2>/dev/null); do
        kube -n finapp logs "$dr_pod" > "$ART/dr-held-${dr_pod##*/}.log" 2>&1 || true
        ! grep -q 'is not permitted to log in' "$ART/dr-held-${dr_pod##*/}.log" || dr_refused=$((dr_refused + 1))
    done
    [ "$dr_refused" -ge 1 ] || cannot "probe skip-verification: no replica's log shows the database's refusal"
    [ "$(psql_query -c "SELECT count(*) FROM ledger.journal_entry" | tr -d '[:space:]')" = "$dr_entries" ] \
        || cannot "probe skip-verification: the journal grew on a held database"
    [ "$(psql_query -c "SELECT count(*) FILTER (WHERE rolcanlogin) FROM pg_roles WHERE rolname IN ('finapp_app', 'finapp_migrator', 'finapp_reporting')" | tr -d '[:space:]')" = 0 ] \
        || cannot "probe skip-verification: a platform role can log in"
    log "probe skip-verification: no replica ready, $dr_refused refused by the database at log-in, the journal at $dr_entries entries, every role NOLOGIN"
}

# ---------------------------------------------------------------------------------------------------
# After the release, in the rebuild.
# ---------------------------------------------------------------------------------------------------

# The audited replay of the window before the restore point (section 11.35 step 6.3), the fleet started: m the stated 24 hours, since the
# lag at T lived in the lost metrics store, and never before the restored outbox's first publication; per event type, in slices one
# command can carry. A consumer that handled an event keeps its restored dedupe row and refuses the copy.
dr_replay_window() {
    dr_to=$(sed -n 's/^backup-epoch	//p' "$DR_VAULT/instants.tsv")
    dr_first=$(psql_query -c "SELECT coalesce(extract(epoch FROM min(COALESCE(first_published_at, published_at)))::bigint, $dr_to) FROM platform.outbox_event" | tr -d '[:space:]')
    dr_from=$((dr_to - DR_REPLAY_HOURS * 3600))
    [ "$dr_from" -ge "$dr_first" ] || dr_from=$dr_first
    dr_requeued=0
    dr_commands=0
    for dr_type in $(psql_query -c "SELECT DISTINCT event_type FROM platform.outbox_event WHERE COALESCE(first_published_at, published_at) >= to_timestamp($dr_from) AND COALESCE(first_published_at, published_at) < to_timestamp($dr_to) ORDER BY 1" | tr -d '\r'); do
        dr_t=$dr_from
        while [ "$dr_t" -lt "$dr_to" ]; do
            dr_end=$((dr_t + 300))
            [ "$dr_end" -le "$dr_to" ] || dr_end=$dr_to
            dr_in=$(psql_query -c "SELECT count(*) FROM platform.outbox_event WHERE event_type = '$dr_type' AND COALESCE(first_published_at, published_at) >= to_timestamp($dr_t) AND COALESCE(first_published_at, published_at) < to_timestamp($dr_end)" | tr -d '[:space:]')
            if [ "${dr_in:-0}" -gt 0 ]; then
                client replay "$dr_type" "$(iso "$dr_t")" "$(iso "$dr_end")" "disaster recovery: the window before the restore point" > "$WORK/dr-replay.out" 2>&1 \
                    || { cat "$WORK/dr-replay.out" >> "$ART/client.log"; fail "the replay of $dr_type over [$(iso "$dr_t"), $(iso "$dr_end")) failed: see client.log"; }
                cat "$WORK/dr-replay.out" >> "$ART/client.log"
                dr_requeued=$((dr_requeued + $(sed -n 's/.* requeued=\([0-9]*\) .*/\1/p' "$WORK/dr-replay.out" | head -1)))
                dr_commands=$((dr_commands + 1))
            fi
            dr_t=$dr_end
        done
    done
    pass "the window before the restore point replayed from the restored outbox: [$(iso "$dr_from"), $(iso "$dr_to")), $dr_commands commands, $dr_requeued events requeued"
}

dr_after_rebuild() {
    # Money moved and proven on the recovered books: the census of the smoke step was green.
    step dr-recovered
    dr_lost=$(psql_query -c "SELECT count(*) FROM transfers.transfer WHERE reference LIKE 'wl-dr-lost-%'" | tr -d '[:space:]')
    [ "$dr_lost" = 0 ] || fail "$dr_lost transfers of the window after the backup are in the recovered books"
    pass "none of the $(sed -n 's/^lost-transfers	//p' "$DR_VAULT/instants.tsv") transfers acknowledged after the backup is in the recovered books: the window after T is what was lost"
    dr_fingerprint_judged recovered "$FLEET_START"

    # Scaled out behind the cut: the replicas that start now are T42's case, a replica starting while no registry can be reached.
    step dr-scaled
    kube -n finapp patch hpa finapp-app --type=merge -p "{\"spec\":{\"minReplicas\":$FLEET_REPLICAS}}" >> "$ART/apply.log" 2>&1 \
        || cannot "the fleet's floor could not be raised to $FLEET_REPLICAS"
    dr_deadline=$(( $(date +%s) + 900 ))
    until [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)" = "$FLEET_REPLICAS" ]; do
        [ "$(date +%s)" -lt "$dr_deadline" ] || fail "the fleet did not reach $FLEET_REPLICAS ready replicas behind the cut: see pods-final.txt"
        sleep 5
    done
    dr_mark scaled
    smoke dr-scaled
    census dr-scaled
    dr_fingerprint_judged scaled "$FLEET_REPLICAS"

    # E88 over the whole rebuild: nothing pulled. The containerd journal of each node holds every CRI call since it started; a reading
    # with no sandbox started in it is no reading. The pods' events say the same for the last hour.
    step dr-registry-held
    for node in $NODES; do
        dock exec "$node" journalctl -u containerd --no-pager > "$ART/dr-containerd-$node.log" 2>&1 || cannot "the node $node's containerd journal could not be read"
        grep -q 'RunPodSandbox' "$ART/dr-containerd-$node.log" || cannot "the node $node's containerd journal holds no sandbox started: not a reading of this run"
        dr_pulls=$(grep -c 'PullImage' "$ART/dr-containerd-$node.log" || true)
        [ "$dr_pulls" = 0 ] || fail "E88: the node $node was asked to pull $dr_pulls times during the rebuild: see dr-containerd-$node.log"
    done
    kube get events -A -o jsonpath='{range .items[*]}{.reason}|{.message}{"\n"}{end}' > "$ART/dr-events.txt" 2>&1 || cannot "the events could not be read"
    dr_pulling=$(grep -c '^Pulling|' "$ART/dr-events.txt" || true)
    dr_failed=$(grep -ci '^\(Failed\|BackOff\)|.*pull' "$ART/dr-events.txt" || true)
    [ "$dr_pulling" = 0 ] && [ "$dr_failed" = 0 ] || fail "E88: $dr_pulling pulls and $dr_failed failed pulls in the events: see dr-events.txt"
    dr_registry_refused end
    pass "E88: the rebuild, its fleet at three and its scale to $FLEET_REPLICAS ran with no image pulled: every replica started from the vault's images"
}

# dr_scope, at the harness's dispatch: the environment before and its disaster, or the rebuild's last steps.
dr_scope() {
    if [ "$DR_PHASE" = rebuild ]; then
        dr_after_rebuild
    else
        dr_before
    fi
}
