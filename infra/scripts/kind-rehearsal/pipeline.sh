# The event pipeline at load (P16-TSK-013; PHASE_16_PLAN.md section 7; OPERATIONS_RUNBOOK.md section 11.51): E47 and E48. Sourced by
# infra/scripts/kind-rehearsal.sh at its start, as load.sh is, so it only defines functions: pipeline_preflight after load_preflight,
# and pipeline_start, pipeline_tick and pipeline_report from load.sh's run and census. Off in every other load run.
#
# E47 (REHEARSAL_LOAD_PROFILE=pipeline, REHEARSAL_KAFKA_BROKERS=3, at 3 and at 7 replicas): checkouts alone at the target's peak - the
# flow that writes the most events, nine each, and the one whose stage found the relay behind (CAPACITY_MODEL.md section 3.4) - for ten
# minutes, a burst of three times that for a minute (W2), and fifteen minutes at the peak again. Read every 30 s while it runs: the
# outbox's backlog and oldest row, the events and aggregates published in the interval, each group's lag per partition and its members.
# Read after it: the relay's ceiling (the most published in an interval while a backlog stood at both its ends), each group's lag at the
# peak, the time the pipeline took to drain after the burst, and every topic's records per partition over the load (the skew by key).
#
# E48 (REHEARSAL_LOAD_SCALE="3 7 3", REHEARSAL_LOAD_REPLICAS=7, the budget at seven): the same profile with the fleet scaled through the
# counts, the first before the load and each next one at an equal share of it, the autoscaler held at each count. Every group's members
# follow the fleet, each rebalance counted from the members read; the census then counts one effect per record.
#
# A load run fails on correctness only (PHASE_16_PLAN.md section 5.3): the numbers here are recorded, never a verdict. What fails the
# run is the pipeline unread (a gap over 90 s in its samples), a fleet that never reached a count it was scaled to, a group whose members
# never followed it, and the load scope's own census - every outbox row published, every record a handler names in its inbox once.

PIPELINE_SCALE=${REHEARSAL_LOAD_SCALE:-}
PIPELINE=""
PIPELINE_STEP=0

# pipeline_preflight: whether this load run is one of the pipeline's, and its settings, refused before anything is built.
pipeline_preflight() {
    [ "$SCOPE" = load ] || return 0
    [ "$LOAD_PROFILE_NAME" = pipeline ] || [ -n "$PIPELINE_SCALE" ] || return 0
    PIPELINE=1
    [ "$KAFKA_BROKERS" = 3 ] || cannot "the pipeline's runs (E47, E48) are on three brokers: REHEARSAL_KAFKA_BROKERS=3, not $KAFKA_BROKERS"
    [ -z "$PIPELINE_SCALE" ] || {
        most=0
        steps=0
        for count in $PIPELINE_SCALE; do
            case $count in 3 | 4 | 5 | 6 | 7) ;; *) cannot "REHEARSAL_LOAD_SCALE's counts are 3 to 7, not '$count'" ;; esac
            [ "$count" -le "$most" ] || most=$count
            steps=$((steps + 1))
        done
        [ "$steps" -ge 2 ] || cannot "REHEARSAL_LOAD_SCALE names one count: a scale needs two"
        [ "$most" = "$LOAD_REPLICAS" ] || cannot "REHEARSAL_LOAD_SCALE reaches $most: the budget is sized for the fleet's largest count, so REHEARSAL_LOAD_REPLICAS=$most"
    }
    log "the pipeline's run: $KAFKA_BROKERS brokers, the profile $LOAD_PROFILE_NAME${PIPELINE_SCALE:+, the fleet scaled $PIPELINE_SCALE}"
}

# pipeline_scale_to <count>: the autoscaler held at the count and the Deployment scaled to it; not waited for here.
pipeline_scale_to() {
    kube -n finapp patch hpa finapp-app --type=merge -p "{\"spec\":{\"minReplicas\":$1,\"maxReplicas\":$1}}" >> "$LOAD_ART/pipeline-scale.log" 2>&1 \
        || fail "the autoscaler could not be held at $1"
    kube -n finapp scale deployment/finapp-app --replicas="$1" >> "$LOAD_ART/pipeline-scale.log" 2>&1 || fail "the fleet could not be scaled to $1"
    printf '%s\t%s\n' "$(date +%s)" "$1" >> "$LOAD_ART/pipeline-scale.tsv"
    log "the fleet scaled to $1"
}

# pipeline_offsets <file>: every catalogued topic's end offset per partition, topic:partition<TAB>offset.
pipeline_offsets() {
    kafka_admin kafka-get-offsets.sh 2>/dev/null | awk -F: '$1 ~ /^finapp\./ {print $1 ":" $2 "\t" $3}' > "$1"
}

# pipeline_start: before the load - the first count of a scale, waited for, the offsets at the start, and the sampler.
pipeline_start() {
    [ -n "$PIPELINE" ] || return 0
    : > "$LOAD_ART/pipeline-scale.tsv"
    if [ -n "$PIPELINE_SCALE" ]; then
        set -- $PIPELINE_SCALE
        pipeline_scale_to "$1"
        PIPELINE_STEP=1
        deadline=$(( $(date +%s) + 600 ))
        until [ "$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "$1" ] \
            && [ "$(kube -n finapp get pods -l app.kubernetes.io/name=finapp-app -o name 2>/dev/null | grep -c . || true)" = "$1" ]; do
            [ "$(date +%s)" -lt "$deadline" ] || fail "the fleet did not settle at $1 replicas before the load"
            sleep 5
        done
    fi
    pipeline_offsets "$LOAD_ART/offsets-start.tsv"
    [ -s "$LOAD_ART/offsets-start.tsv" ] || fail "the topics' offsets could not be read before the load"
    pipeline_sampler &
    BACKGROUND="$BACKGROUND $!"
}

# pipeline_tick <elapsed> <load seconds>: the next count of a scale, at its share of the load.
pipeline_tick() {
    [ -n "$PIPELINE_SCALE" ] || return 0
    set -- "$1" "$2" $PIPELINE_SCALE
    elapsed=$1
    total=$2
    shift 2
    steps=$#
    [ "$PIPELINE_STEP" -lt "$steps" ] || return 0
    [ "$elapsed" -ge $(( total * PIPELINE_STEP / steps )) ] || return 0
    PIPELINE_STEP=$((PIPELINE_STEP + 1))
    pipeline_scale_to "$(eval "printf '%s' \"\${$PIPELINE_STEP}\"")"
}

# Every 30 s until the load stops: the outbox, the fleet, each group's lag per partition and its members. Never judged here.
pipeline_sampler() {
    set +e
    printf 'epoch\tpending\toldest_s\tpublished_30s\taggregates_30s\twritten_30s\tready\n' > "$LOAD_ART/pipeline.tsv"
    printf 'epoch\tgroup\ttopic\tpartition\tlag\tmember\n' > "$LOAD_ART/lag.tsv"
    printf 'epoch\tgroup\tmembers\tidle\n' > "$LOAD_ART/members.tsv"
    while [ ! -f "$WORK/load-stop" ]; do
        started=$(date +%s)
        outbox=$(psql_query -c "SELECT count(*) FILTER (WHERE published_at IS NULL AND discarded_at IS NULL) || '|'
                || coalesce(extract(epoch FROM now() - min(occurred_at) FILTER (WHERE published_at IS NULL AND discarded_at IS NULL
                                                                                AND dead_lettered_at IS NULL))::bigint, 0) || '|'
                || count(*) FILTER (WHERE published_at >= now() - interval '30 seconds') || '|'
                || count(DISTINCT aggregate_id) FILTER (WHERE published_at >= now() - interval '30 seconds') || '|'
                || count(*) FILTER (WHERE occurred_at >= now() - interval '30 seconds')
              FROM platform.outbox_event" 2>/dev/null | tr -d '[:space:]')
        ready=$(kube -n finapp get deployment finapp-app -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
        [ -z "$outbox" ] || printf '%s\t%s\t%s\n' "$started" "$(printf '%s' "$outbox" | tr '|' '\t')" "${ready:-0}" >> "$LOAD_ART/pipeline.tsv"
        kafka_admin kafka-consumer-groups.sh --describe --all-groups 2>/dev/null \
            | awk -v e="$started" '$1 ~ /^finapp\./ && $2 ~ /^finapp\./ {print e "\t" $1 "\t" $2 "\t" $3 "\t" $6 "\t" $7}' >> "$LOAD_ART/lag.tsv"
        kafka_admin kafka-consumer-groups.sh --describe --all-groups --members 2>/dev/null \
            | awk -v e="$started" '$1 ~ /^finapp\./ {m[$1]++; if ($NF == 0) idle[$1]++} END {for (g in m) print e "\t" g "\t" m[g] "\t" idle[g] + 0}' \
            >> "$LOAD_ART/members.tsv"
        while [ "$(date +%s)" -lt $((started + 30)) ] && [ ! -f "$WORK/load-stop" ]; do
            sleep 1
        done
    done
}

# pipeline_report: after the load's census - the coverage judged, the numbers written to load/pipeline-summary.txt, a scale's counts and
# members judged.
pipeline_report() {
    [ -n "$PIPELINE" ] || return 0
    pipeline_offsets "$LOAD_ART/offsets-end.tsv"
    samples=$(awk -F'\t' 'NR > 1' "$LOAD_ART/pipeline.tsv" | grep -c . || true)
    gap=$(awk -F'\t' 'NR > 1 {if (last != "" && $1 - last > g) g = $1 - last; last = $1} END {print g + 0}' "$LOAD_ART/pipeline.tsv")
    [ "$samples" -ge $(( (LOAD_ENDED - LOAD_STARTED) / 30 - 2 )) ] && [ "$gap" -le 90 ] \
        || fail "the pipeline was read $samples times over the load, its longest gap $gap s: a pipeline nobody read throughout shows nothing (see load/pipeline.tsv)"
    lag_samples=$(awk -F'\t' 'NR > 1 {print $1}' "$LOAD_ART/lag.tsv" | sort -u | grep -c . || true)
    [ "$lag_samples" -ge $(( samples - 2 )) ] || fail "the groups' lag was read $lag_samples times of $samples samples: see load/lag.tsv"
    # The relay's ceiling: the most published in an interval with a backlog standing at both its ends.
    ceiling=$(awk -F'\t' 'NR > 2 && $2 > 0 && last > 0 {if ($4 > e) {e = $4; a = $5}} NR > 1 {last = $2} END {printf "%.1f %.1f", e / 30, a / 30}' "$LOAD_ART/pipeline.tsv")
    # Each group's lag at the peak: its sum over partitions, the most and the median over the samples.
    awk -F'\t' 'NR > 1 && $5 ~ /^[0-9]+$/ {s[$1 "\t" $2] += $5} END {for (k in s) print k "\t" s[k]}' "$LOAD_ART/lag.tsv" | sort -n > "$LOAD_ART/lag-by-group.tsv"
    lags=$(awk -F'\t' '{n[$2]++; v[$2, n[$2]] = $3; if ($3 > m[$2]) m[$2] = $3} END {for (g in n) printf "%s most %d; ", g, m[g]}' "$LOAD_ART/lag-by-group.tsv")
    # The drain after the burst (E47's profile: ten minutes at the peak, then the burst's minute): the first sample after it with every
    # group's lag under the consumption objective's 1,000 and the outbox's oldest row under the freshness objective's 60 s.
    drain="no burst in this profile"
    if [ "$LOAD_PROFILE_NAME" = pipeline ]; then
        burst_end=$((LOAD_STARTED + 660))
        drained_at=$(awk -F'\t' -v b="$burst_end" 'FNR == NR {if ($1 >= b && $3 >= 60) late[$1] = 1; next}
            $1 >= b {if ($3 > 1000) over[$1] = 1; seen[$1] = 1}
            END {first = ""; for (e in seen) if (!(e in over) && !(e in late) && (first == "" || e < first)) first = e; print first}' \
            "$LOAD_ART/pipeline.tsv" "$LOAD_ART/lag-by-group.tsv")
        if [ -n "$drained_at" ]; then drain="$((drained_at - burst_end)) s after the burst's end"; else drain="not within the load"; fi
    fi
    # The skew by key: each topic's records per partition over the load, its busiest partition against its mean.
    awk -F'\t' 'FNR == NR {start[$1] = $2; next} ($1 in start) {split($1, p, ":"); print p[1] "\t" p[2] "\t" $2 - start[$1]}' \
        "$LOAD_ART/offsets-start.tsv" "$LOAD_ART/offsets-end.tsv" > "$LOAD_ART/records-per-partition.tsv"
    awk -F'\t' '{n[$1]++; s[$1] += $3; if ($3 > m[$1]) m[$1] = $3}
        END {for (t in n) if (s[t] > 0) printf "%s\t%d\t%d\t%.2f\n", t, s[t], m[t], m[t] / (s[t] / n[t])}' "$LOAD_ART/records-per-partition.tsv" \
        | sort > "$LOAD_ART/skew.tsv"
    partitions=$(grep -c . "$LOAD_ART/records-per-partition.tsv" || true)
    [ "$partitions" -ge 12 ] || fail "the topics' offsets were read for $partitions partitions over the load: see load/offsets-*.tsv"
    skew=$(sort -t"$(printf '\t')" -k4 -nr "$LOAD_ART/skew.tsv" | head -1 | awk -F'\t' '{print $1 " " $4 " times its mean (" $2 " records)"}')
    {
        printf 'brokers\t%s\n' "$KAFKA_BROKERS"
        printf 'samples\t%s, the longest gap %s s\n' "$samples" "$gap"
        printf 'relay ceiling\t%s events and %s aggregates a second (the most published in 30 s with a backlog at both ends)\n' "${ceiling% *}" "${ceiling#* }"
        printf 'lag\t%s\n' "$lags"
        printf 'drain\t%s\n' "$drain"
        printf 'skew\tthe busiest partition: %s\n' "${skew:-no records}"
    } > "$LOAD_ART/pipeline-summary.txt"
    if [ -n "$PIPELINE_SCALE" ]; then
        pipeline_scale_judge
    fi
    pass "the pipeline read throughout the load ($samples samples, the longest gap $gap s); its numbers recorded in load/pipeline-summary.txt, never a verdict"
}

# pipeline_scale_judge: every count the fleet was scaled to reached, and every group's members following it; each rebalance counted.
pipeline_scale_judge() {
    while IFS="$(printf '\t')" read -r at count; do
        [ -n "$at" ] || continue
        reached=$(awk -F'\t' -v at="$at" -v c="$count" 'NR > 1 && $1 >= at && $7 == c {print $1 - at; exit}' "$LOAD_ART/pipeline.tsv")
        [ -n "$reached" ] || fail "the fleet scaled to $count at $(date -u -d "@$at" +%H:%M:%SZ) was never read at $count ready replicas: see load/pipeline.tsv"
        followed=$(awk -F'\t' -v at="$at" -v c="$count" 'NR > 1 && $1 >= at && $3 == c {g[$2] = 1} END {n = 0; for (k in g) n++; print n}' "$LOAD_ART/members.tsv")
        groups=$(awk -F'\t' 'NR > 1 {g[$2] = 1} END {n = 0; for (k in g) n++; print n}' "$LOAD_ART/members.tsv")
        [ "$followed" = "$groups" ] && [ "$groups" -ge 1 ] || fail "after the scale to $count, $followed of $groups groups were read with $count members: see load/members.tsv"
        printf 'scaled to %s\tready %s s after; every one of %s groups at %s members\n' "$count" "$reached" "$groups" "$count" >> "$LOAD_ART/pipeline-summary.txt"
    done < "$LOAD_ART/pipeline-scale.tsv"
    rebalances=$(awk -F'\t' 'NR > 1 {if (($2 in last) && last[$2] != $3) n++; last[$2] = $3} END {print n + 0}' "$LOAD_ART/members.tsv")
    idle=$(awk -F'\t' 'NR > 1 && $4 > 0' "$LOAD_ART/members.tsv" | grep -c . || true)
    printf 'rebalances\t%s membership changes read across the groups; %s readings of a group with an idle member\n' "$rebalances" "$idle" >> "$LOAD_ART/pipeline-summary.txt"
    pass "the fleet scaled through $PIPELINE_SCALE under load, every count reached and every group following it ($rebalances membership changes read)"
}
