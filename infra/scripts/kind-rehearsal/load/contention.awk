# Each contended row's waits per stage (P16-TSK-008; PHASE_16_PLAN.md section 6.4; OPERATIONS_RUNBOOK.md section 11.48), read from the
# 250 ms samples of load/contention.sql. kind-rehearsal/load.sh runs
#
#   awk -F'|' -v stages=<stages.txt> -v t0=<start> -v speed=<k6 seconds per host second> -f contention.awk contention-samples.txt \
#       > contention-rows.tsv
#
# A wait is one backend's transaction waiting on one target (a row, an advisory key, a table) across consecutive samples: the same pid,
# the same transaction start, the same target, seen in one sample and the next. Its length is estimated as the time between the first
# and the last sample that saw it plus one sampling interval, the interval being the samples' own median spacing: a wait seen once lasted
# at most about one interval, and a wait shorter than the interval is seen with a chance of its length over the interval, so the count
# of short waits is a lower bound and the long tail is read whole (log_lock_waits adds every wait of a second or more, lock-waits.tsv).
# A wait belongs to the stage its first sample falls in, cut in the host's seconds as resources.awk cuts them.
#
# One line per target and stage: the waits seen, the waiting seconds summed (the mean number waiting on the target over the stage is that
# over the stage's seconds), the estimated p50, p95 and longest wait in milliseconds, and the most backends seen waiting on it in one
# sample. The last line, "samples", is the sampler's own count and its largest gap: a gap is time no wait could be seen in.
function stage_of(ts,    i) {
    for (i = 1; i <= n; i++) {
        if (ts < t0 + upto[i]) return (ts < t0) ? "before" : i
    }
    return "tail"
}
# A wait's span between the first and the last sample that saw it; the interval is added at the end, once it is known.
function close_wait(k,    s) {
    s = stage_of(first[k])
    key = target[k] SUBSEP s
    count[key]++
    # The span in sampling intervals, on the 250 ms grid the sampler reads on: the waits are counted per length, never sorted one by one.
    b = int((last[k] - first[k]) / 0.25 + 0.5)
    hist[key, b]++
    if (b > maxb[key]) maxb[key] = b
    span[key] += last[k] - first[k]
    targets[target[k]] = 1
    delete first[k]; delete last[k]; delete target[k]; delete seenat[k]
}
BEGIN {
    if (speed == "") speed = 1
    n = 0
    while ((getline line < stages) > 0) {
        split(line, f, " ")
        n++
        upto[n] = upto[n - 1] + f[1] / speed
    }
    close(stages)
    ticks = 0
    step = 0.25
}
$1 == "tick" {
    ticks++
    at[ticks] = $2 + 0
    if (ticks > 1 && $2 - at[ticks - 1] > gap) gap = $2 - at[ticks - 1]
    # The waits not seen in the sample before this one have ended.
    for (k in last) if (seenat[k] < ticks - 1) close_wait(k)
    next
}
$1 == "wait" {
    k = $3 SUBSEP $4 SUBSEP $6
    if (!(k in first)) { first[k] = $2 + 0; target[k] = $6 }
    last[k] = $2 + 0
    seenat[k] = ticks
    per[$6 SUBSEP ticks]++
    if (per[$6 SUBSEP ticks] > most[$6 SUBSEP stage_of($2 + 0)]) most[$6 SUBSEP stage_of($2 + 0)] = per[$6 SUBSEP ticks]
}
END {
    # The samples' median spacing, the interval a wait's length is estimated with.
    # Counted per 10 ms, so the median is read off the counts rather than a sort of every spacing.
    m = 0
    for (i = 2; i <= ticks; i++) { g = int((at[i] - at[i - 1]) * 100 + 0.5); spacing[g]++; if (g > maxg) maxg = g; m++ }
    run = 0
    for (g = 0; g <= maxg && m > 0; g++) { run += spacing[g]; if (run >= m / 2) { step = g / 100; break } }
    for (k in last) close_wait(k)
    print "target\tstage\twaits\twaiting_seconds\tp50_ms\tp95_ms\tlongest_ms\tmost_waiting"
    for (t in targets) {
        for (i = 0; i <= n + 2; i++) {
            s = (i == 0) ? "before" : (i <= n) ? i : (i == n + 1) ? "tail" : ""
            if (s == "") continue
            key = t SUBSEP s
            c = count[key]
            if (c == 0) continue
            total = span[key] + c * step
            # The p-th wait in length order: the first length whose running count reaches p of the waits.
            p50 = ""; p95 = ""; run = 0
            for (b = 0; b <= maxb[key]; b++) {
                run += hist[key, b]
                if (p50 == "" && run >= c * 0.50) p50 = b * 0.25 + step
                if (p95 == "" && run >= c * 0.95) p95 = b * 0.25 + step
            }
            printf "%s\t%s\t%d\t%.3f\t%.0f\t%.0f\t%.0f\t%d\n", t, s, c, total, p50 * 1000, p95 * 1000, (maxb[key] * 0.25 + step) * 1000, most[key]
        }
    }
    printf "samples\t%d\tinterval_ms %.0f\tlargest_gap_ms %.0f\n", ticks, step * 1000, gap * 1000
}
