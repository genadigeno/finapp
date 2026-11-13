# The test-only broker cluster (P16-TSK-013; PHASE_16_PLAN.md section 7; ADR-0127 decision 4; OPERATIONS_RUNBOOK.md section 11.51).
# Sourced by infra/scripts/kind-rehearsal.sh at its start, as load.sh and experiments.sh are, so it only defines functions and the
# broker count: brokers_resolve once the scope is known, brokers_render on the run's rendered copy of the kind overlay, brokers_config
# for the brokers' server.properties, brokers_sans for their certificate and brokers_replication for the topics.
#
# HOW MANY. REHEARSAL_KAFKA_BROKERS: three in the experiments scope, always (E08, E09, E22, E23, E49 and the scope's per-broker proxies
# need them); one or three in the load scope (three for E47 and E48, the pipeline at load); one in every other scope, whose own steps
# assume one broker (platform.sh's outage scales it back to one; rolls.sh reads pod 0 and its claim) and whose records were taken on
# one. Anything else is refused before the run builds anything. Whether every scope should run three is THREAT_MODEL.md section 6 item
# 58 (a), the owner's.
#
# THREE BROKERS, test-only. The kind overlay's StatefulSet is committed at one replica and is three-capable: its start script gives a pod
# a node id (its ordinal + 1) and its own advertised names whenever the configuration names no node, which is what brokers_config
# writes for three. The run's own rendered copy then holds three replicas started in parallel (a KRaft quorum of three cannot form one
# pod at a time), a headless Service that publishes the brokers before they are ready (each must resolve the others to elect a
# leader) and a quota grown by the two brokers. Nothing of it reaches the repository's overlay.
#
# THE LISTENERS, at three:
#   SASL_SSL  9092  the clients: SCRAM-SHA-512 over TLS from the run's local CA, as at one; each broker advertises its own pod name,
#                   which its certificate names (brokers_sans);
#   CONTROLLER 9093 KRaft's quorum, over mutual TLS (SCRAM is not available to a controller listener: its credentials live in the
#                   metadata the quorum serves);
#   LOCAL     9094  the pod's own loopback, ANONYMOUS, as at one: the startup probe alone at three, since each broker advertises it
#                   as localhost and a tool there cannot reach a partition another broker leads;
#   INTERNAL  9095  replication between the brokers and the harness's admin tools (kafka_admin), over mutual TLS, the principal the
#                   certificate's subject (kind-rehearsal.sh's `issue kafka`: CN=kafka), the one super user beside loopback's
#                   ANONYMOUS.
# The posture is infra/kafka/broker.properties' unchanged: replication 3, min.insync.replicas 2, unclean election off. The topics take
# the catalogue's replication and min.insync.replicas (brokers_replication). At one broker, everything is as before this file.

KAFKA_BROKERS=${REHEARSAL_KAFKA_BROKERS:-}
# The broker kafka_admin runs its tools in: the first, unless an experiment has stopped it (E08 moves it to a survivor).
KAFKA_ADMIN_POD=finapp-kafka-0
KAFKA_SERVICE_DOMAIN=finapp-kafka.finapp.svc.cluster.local

# brokers_resolve: the run's broker count for its scope, or a refusal. Called once the scope is read, before anything is built.
brokers_resolve() {
    if [ -z "$KAFKA_BROKERS" ]; then
        KAFKA_BROKERS=1
        [ "$SCOPE" != experiments ] || KAFKA_BROKERS=3
    fi
    case $SCOPE:$KAFKA_BROKERS in
        experiments:3 | load:1 | load:3) ;;
        experiments:*) echo "?? REHEARSAL_KAFKA_BROKERS is 3 in the experiments scope, whose proxies are one per broker of three, not $KAFKA_BROKERS" >&2; exit 2 ;;
        load:*) echo "?? REHEARSAL_KAFKA_BROKERS is 1 or 3 in the load scope, not $KAFKA_BROKERS" >&2; exit 2 ;;
        *:1) ;;
        *) echo "?? REHEARSAL_KAFKA_BROKERS is 1 in the $SCOPE scope, whose own steps assume one broker, not $KAFKA_BROKERS" >&2; exit 2 ;;
    esac
}

# brokers_name <ordinal>: a broker's own name, the one it advertises and its certificate names.
brokers_name() {
    printf 'finapp-kafka-%s.%s' "$1" "$KAFKA_SERVICE_DOMAIN"
}

# brokers_sans: the broker certificate's names, the Service's and, for every broker, its own.
brokers_sans() {
    sans="DNS:$KAFKA_HOST,DNS:finapp-kafka,DNS:finapp-kafka.finapp,DNS:finapp-kafka.finapp.svc"
    i=0
    while [ "$i" -lt "$KAFKA_BROKERS" ]; do
        sans="$sans,DNS:$(brokers_name "$i")"
        i=$((i + 1))
    done
    printf '%s' "$sans"
}

# brokers_render <rendered overlay directory>: the run's own copy at the run's broker count. Nothing to do at one.
brokers_render() {
    [ "$KAFKA_BROKERS" != 1 ] || return 0
    set_file="$1/StatefulSet.finapp-kafka.yaml"
    sed -i "s/^\(  \)replicas: 1$/\1replicas: $KAFKA_BROKERS/" "$set_file"
    sed -i 's/^\(  \)serviceName: finapp-kafka$/&\n\1podManagementPolicy: Parallel/' "$set_file"
    sed -i 's/^\(  \)clusterIP: None$/&\n\1publishNotReadyAddresses: true/' "$1/Service.finapp-kafka.yaml"
    grep -q "^  replicas: $KAFKA_BROKERS\$" "$set_file" && grep -q '^  podManagementPolicy: Parallel$' "$set_file" \
        && grep -q '^  publishNotReadyAddresses: true$' "$1/Service.finapp-kafka.yaml" \
        || cannot "the rendered broker StatefulSet could not be set to $KAFKA_BROKERS brokers started in parallel: a value it patches has moved"
    # The quota grows by each broker past the first: one pod, 250m and 1 CPU, 1 GiB, as the overlay's broker asks.
    quota="$1/ResourceQuota.finapp.yaml"
    extra=$((KAFKA_BROKERS - 1))
    for field in pods requests.cpu requests.memory limits.cpu limits.memory; do
        value=$(sed -n "s/^    $field: \"\{0,1\}\([0-9a-zA-Z]*\)\"\{0,1\}$/\1/p" "$quota")
        case $field:$value in
            pods:[0-9]*) grown=$((value + extra)) ;;
            requests.cpu:*m) grown="$((${value%m} + 250 * extra))m" ;;
            limits.cpu:[0-9]*) grown=$((value + extra)) ;;
            *:*Mi) grown="$((${value%Mi} + 1024 * extra))Mi" ;;
            *) cannot "the quota's $field ($value) is not of a form the broker count can grow" ;;
        esac
        sed -i "s/^    $field: \"\{0,1\}$value\"\{0,1\}$/    $field: \"$grown\"/" "$quota"
        grep -q "^    $field: \"$grown\"\$" "$quota" || cannot "the quota's $field could not be grown by $extra brokers"
    done
    log "the brokers' copy: $KAFKA_BROKERS brokers started in parallel, published before ready, the quota grown by $extra ($(tr '\n' ' ' < "$quota" | sed 's/.*hard: *//'))"
}

# brokers_replication: "<replication factor> <min.insync.replicas>" for the run's topics: the catalogue's at three, one and one at one.
brokers_replication() {
    if [ "$KAFKA_BROKERS" = 1 ]; then
        printf '1 1'
        return 0
    fi
    catalogue="$ROOT/infra/kafka/topics.yaml"
    factor=$(sed -n '/^  domain-events:$/,/^topics:/s/^ *replication-factor: *\([0-9]*\) *$/\1/p' "$catalogue")
    in_sync=$(sed -n '/^  domain-events:$/,/^topics:/s/^ *min.insync.replicas: *"\([0-9]*\)".*/\1/p' "$catalogue")
    case $factor:$in_sync in [0-9]*:[0-9]*) ;; *) cannot "the catalogue's replication factor and min.insync.replicas could not be read from topics.yaml" ;; esac
    [ "$factor" -le "$KAFKA_BROKERS" ] && [ "$in_sync" -lt "$factor" ] \
        || cannot "the catalogue's replication $factor and min.insync.replicas $in_sync do not fit $KAFKA_BROKERS brokers"
    printf '%s %s' "$factor" "$in_sync"
}

# brokers_config: the brokers' server.properties, for the ConfigMap finapp-kind-kafka-config. At one, the lines the harness wrote before
# this file, unchanged; at three, the catalogued posture whole and the four listeners above, every pod's identity left to its start.
brokers_config() {
    if [ "$KAFKA_BROKERS" = 1 ]; then
        # The posture, from the catalogued file, overriding only replication: one node.
        sed -e '/^#/d' -e '/^$/d' \
            -e 's/^default.replication.factor=.*/default.replication.factor=1/' \
            -e 's/^min.insync.replicas=.*/min.insync.replicas=1/' \
            -e 's/^offsets.topic.replication.factor=.*/offsets.topic.replication.factor=1/' \
            "$ROOT/infra/kafka/broker.properties"
        cat <<PROPERTIES
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@localhost:9093
controller.listener.names=CONTROLLER
listeners=SASL_SSL://:9092,CONTROLLER://localhost:9093,LOCAL://localhost:9094
advertised.listeners=SASL_SSL://$KAFKA_HOST:9092,LOCAL://localhost:9094
inter.broker.listener.name=LOCAL
listener.security.protocol.map=CONTROLLER:PLAINTEXT,LOCAL:PLAINTEXT,SASL_SSL:SASL_SSL
listener.name.sasl_ssl.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required;
ssl.keystore.type=PEM
ssl.keystore.location=/etc/finapp-kind-kafka-tls/keystore.pem
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
super.users=User:ANONYMOUS
log.dirs=/var/lib/kafka/data
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
PROPERTIES
        return 0
    fi
    # The posture, whole: three copies, two in sync, no unclean election.
    sed -e '/^#/d' -e '/^$/d' "$ROOT/infra/kafka/broker.properties"
    voters=""
    i=0
    while [ "$i" -lt "$KAFKA_BROKERS" ]; do
        voters="${voters:+$voters,}$((i + 1))@$(brokers_name "$i"):9093"
        i=$((i + 1))
    done
    cat <<PROPERTIES
process.roles=broker,controller
controller.quorum.voters=$voters
controller.listener.names=CONTROLLER
listeners=SASL_SSL://:9092,CONTROLLER://:9093,LOCAL://localhost:9094,INTERNAL://:9095
inter.broker.listener.name=INTERNAL
listener.security.protocol.map=CONTROLLER:SSL,INTERNAL:SSL,LOCAL:PLAINTEXT,SASL_SSL:SASL_SSL
listener.name.sasl_ssl.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required;
listener.name.controller.ssl.client.auth=required
listener.name.internal.ssl.client.auth=required
ssl.keystore.type=PEM
ssl.keystore.location=/etc/finapp-kind-kafka-tls/keystore.pem
ssl.truststore.type=PEM
ssl.truststore.location=/etc/finapp-kind-kafka-tls/truststore.pem
authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
super.users=User:ANONYMOUS;User:CN=kafka
log.dirs=/var/lib/kafka/data
PROPERTIES
}

# brokers_ready: one line per broker pod, name|ready|node.
brokers_ready() {
    kube -n finapp get pods -l app.kubernetes.io/name=finapp-kafka \
        -o jsonpath='{range .items[*]}{.metadata.name}|{.status.conditions[?(@.type=="Ready")].status}|{.spec.nodeName}{"\n"}{end}' 2>/dev/null
}

# brokers_check: at three, every broker ready, the quorum of three with a leader, and every catalogued topic at the catalogue's
# replication and min.insync.replicas with every partition's replicas in sync. Nothing to read at one.
brokers_check() {
    [ "$KAFKA_BROKERS" != 1 ] || return 0
    ready=$(brokers_ready | awk -F'|' '$2 == "True"' | grep -c . || true)
    [ "$ready" = "$KAFKA_BROKERS" ] || fail "brokers: $ready of $KAFKA_BROKERS ready"
    kafka_admin kafka-metadata-quorum.sh describe --status > "$ART/kafka-quorum.txt" 2>&1 || fail "brokers: the quorum could not be read: see kafka-quorum.txt"
    # 4.x lists each voter as {"id": n, ...}; earlier versions as [1,2,3].
    line=$(sed -n 's/^CurrentVoters:[[:space:]]*//p' "$ART/kafka-quorum.txt")
    case $line in
        *'"id"'*) voters=$(printf '%s\n' "$line" | grep -o '"id":[[:space:]]*[0-9][0-9]*' | grep -c . || true) ;;
        *) voters=$(printf '%s\n' "$line" | tr -d '[] ' | tr ',' '\n' | grep -c '^[0-9][0-9]*$' || true) ;;
    esac
    leader=$(sed -n 's/^LeaderId:[[:space:]]*\([0-9]*\)[[:space:]]*$/\1/p' "$ART/kafka-quorum.txt")
    [ -n "$leader" ] && [ "$leader" -ge 1 ] || fail "brokers: the quorum has no leader: see kafka-quorum.txt"
    [ "$voters" = "$KAFKA_BROKERS" ] || fail "brokers: the quorum lists $voters voters, not $KAFKA_BROKERS: see kafka-quorum.txt"
    replication=$(brokers_replication)
    case $replication in [0-9]*' '[0-9]*) ;; *) cannot "brokers: the topics' replication could not be read: '$replication'" ;; esac
    set -- $replication
    kafka_admin kafka-topics.sh --describe --topics-with-overrides > "$ART/kafka-topics.txt" 2>&1 || fail "brokers: the topics could not be described"
    topics=$(sed -n 's/^  \(finapp\.[a-z]*\): domain-events$/\1/p' "$ROOT/infra/kafka/topics.yaml" | grep -c .)
    posture=$(awk -v rf="$1" -v isr="$2" '$1 == "Topic:" && $2 ~ /^finapp\./ {
        ok = 0; for (i = 1; i <= NF; i++) { if ($i == "ReplicationFactor:" && $(i + 1) == rf) ok++; if ($i ~ /^Configs:/ && $0 ~ ("min[.]insync[.]replicas=" isr "(,|[[:space:]]|$)")) ok++ }
        if (ok == 2) n++ } END {print n + 0}' "$ART/kafka-topics.txt")
    [ "$posture" = "$topics" ] || fail "brokers: $posture of $topics topics at replication $1 and min.insync.replicas $2: see kafka-topics.txt"
    kafka_admin kafka-topics.sh --describe --under-replicated-partitions > "$ART/kafka-under-replicated.txt" 2>&1 || fail "brokers: the replicas' state could not be read"
    under=$(grep -c 'Topic: finapp\.' "$ART/kafka-under-replicated.txt" || true)
    [ "$under" = 0 ] || fail "brokers: $under partitions with a replica out of sync: see kafka-under-replicated.txt"
    pass "brokers: $KAFKA_BROKERS ready, a quorum of $voters with leader $leader, every one of $topics topics at replication $1 and min.insync.replicas $2, no replica out of sync"
}
