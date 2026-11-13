# The platform's side of a load, per stage (P16-TSK-007; OPERATIONS_RUNBOOK.md section 11.45): every saturation signal the run sampled,
# cut by the stages the generator ran. kind-rehearsal/load.sh writes the signals as one "kind name epoch value" line each (kind g, a
# gauge, read as its mean and its maximum over the stage; kind c, a cumulative counter, read as its rate a second between the stage's
# first and last samples) and runs
#
#   awk -v stages=<stages.txt> -v t0=<start> -v speed=<k6 seconds per host second> -f resources.awk signals > stages-resources.tsv
#
# The stages are the generator's, in its own seconds; the samples are stamped with the host's. On the reference machine the Linux VM's
# monotonic clock, which k6 schedules by, runs faster than the wall clock (load/clock.tsv), so a stage of s k6 seconds lasts s / speed
# host seconds and its window is cut that way. A sample after the last stage falls in "tail".
BEGIN {
    n = 0
    while ((getline line < stages) > 0) {
        split(line, f, " ")
        n++
        secs[n] = f[1] / speed
        upto[n] = upto[n - 1] + secs[n]
    }
    close(stages)
}
function stage_of(ts,    i) {
    for (i = 1; i <= n; i++) {
        if (ts < t0 + upto[i]) return (ts < t0) ? "before" : i
    }
    return "tail"
}
NF == 4 {
    s = stage_of($3 + 0)
    key = $2 SUBSEP s
    names[$2] = $1
    if ($1 == "g") {
        sum[key] += $4; cnt[key]++
        if (!(key in mx) || $4 + 0 > mx[key]) mx[key] = $4 + 0
    } else {
        if (!(key in t_first) || $3 + 0 < t_first[key]) { t_first[key] = $3 + 0; v_first[key] = $4 + 0 }
        if (!(key in t_last) || $3 + 0 >= t_last[key]) { t_last[key] = $3 + 0; v_last[key] = $4 + 0 }
    }
}
END {
    header = "stage\thost_seconds"
    k = 0
    for (name in names) sorted[++k] = name
    # A plain insertion sort: the columns in name order, the same in every run.
    for (i = 2; i <= k; i++) { v = sorted[i]; j = i - 1; while (j > 0 && sorted[j] > v) { sorted[j + 1] = sorted[j]; j-- } sorted[j + 1] = v }
    for (i = 1; i <= k; i++) {
        name = sorted[i]
        header = header "\t" ((names[name] == "g") ? name "_mean\t" name "_max" : name "_per_s")
    }
    print header
    for (i = 1; i <= n + 1; i++) {
        s = (i <= n) ? i : "tail"
        row = s "\t" ((i <= n) ? sprintf("%.0f", secs[i]) : "-")
        for (j = 1; j <= k; j++) {
            name = sorted[j]; key = name SUBSEP s
            if (names[name] == "g") {
                row = row "\t" ((cnt[key] > 0) ? sprintf("%.4g", sum[key] / cnt[key]) : "") "\t" ((key in mx) ? sprintf("%.4g", mx[key]) : "")
            } else {
                dt = t_last[key] - t_first[key]
                row = row "\t" ((dt > 0) ? sprintf("%.4g", (v_last[key] - v_first[key]) / dt) : "")
            }
        }
        print row
    }
}
