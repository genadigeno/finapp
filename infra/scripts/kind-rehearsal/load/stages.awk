# The client's side of a load, per operation and stage (P16-TSK-007; OPERATIONS_RUNBOOK.md section 11.45), read from every generator
# instance's series (k6's CSV, gunzipped and concatenated, each instance's header row included) - never from a summary, which cannot be
# cut by time. kind-rehearsal/load.sh runs it twice, streaming, so a two-hour series is never held in memory:
#
#   gzip -dc series... | awk -v pass=count  -v stages=<stages.txt> -v t0=<start> -v out=<latencies>  > <counts>
#   sort <latencies>   | awk -v pass=report -v stages=<stages.txt> -v counts=<counts> -v ops="<op ...>" > k6/stages.tsv
#
# stages.txt holds one "seconds factor operation" line per stage ("-": every operation), in k6's seconds; speed (load.sh reads it from
# load/clock.tsv) is how many of those pass in one of the host's. A sample belongs to the stage its timestamp falls
# in, counted from t0, the earliest first sample of any instance (k6 writes its virtual users from its start); a sample after the last
# stage, an iteration finishing in the graceful stop, falls in the stage "tail". An iteration is counted when it arrives (k6 records
# iterations_<op> as it starts), an outcome when it concludes and a request when it is answered: a stage's arrivals are its own, its
# outcomes those that concluded in it.
function stage_of(ts,    i) {
    for (i = 1; i <= n; i++) {
        if (ts < t0 + upto[i]) return i
    }
    return "tail"
}
BEGIN {
    # The stages are in k6's seconds, the samples' stamps in the host's: k6 runs on the Linux VM's monotonic clock, which on the reference
    # machine is faster than the wall clock (speed, k6 seconds a host second, from load/clock.tsv), so a stage's window is cut in host seconds.
    if (speed == "") speed = 1
    n = 0
    while ((getline line < stages) > 0) {
        k = split(line, f, " ")
        n++
        secs[n] = f[1]; factor[n] = f[2]; only[n] = (k > 2) ? f[3] : "-"
        upto[n] = upto[n - 1] + f[1] / speed
    }
    close(stages)
    if (pass == "count") {
        FS = ","
        t0 += 0
    } else {
        FS = "\t"
        while ((getline line < counts) > 0) {
            split(line, f, "\t")
            c[f[1] SUBSEP f[2] SUBSEP f[3]] = f[4]
            seen[f[1] SUBSEP f[2]] = 1
        }
        close(counts)
    }
}
# ---------------------------------------------------------------------------------------------- pass one: counts, and the latencies out
pass == "count" && $1 == "metric_name" { for (i = 1; i <= NF; i++) col[$i] = i; next }
pass == "count" && $1 ~ /^(iterations|ok|refused|unresolved|abandoned|attempts|failed_attempts|conflicts)_/ {
    op = $1; sub(/^[a-z_]*_/, "", op)
    if ($1 ~ /^failed_attempts_/) name = "failed"; else { name = $1; sub(/_[a-z]*$/, "", name) }
    count[op SUBSEP stage_of($2 + 0) SUBSEP name] += $3
    next
}
pass == "count" && $1 == "http_reqs" {
    scenario = $col["scenario"]; status = $col["status"]
    cls = (status == "" || status == "0") ? "s0" : (status ~ /^5/) ? "s5xx" : (status == "429") ? "s429" : ""
    if (scenario != "" && cls != "") count[scenario SUBSEP stage_of($2 + 0) SUBSEP cls]++
    next
}
pass == "count" && $1 == "dropped_iterations" {
    if ($col["scenario"] != "") count[$col["scenario"] SUBSEP stage_of($2 + 0) SUBSEP "dropped"] += $3
    next
}
pass == "count" && $1 ~ /^latency_/ {
    op = $1; sub(/^latency_/, "", op)
    print op "\t" stage_of($2 + 0) "\t" $3 > out
    next
}
# ---------------------------------------------------------------------------------------------- pass two: the percentiles
pass == "report" {
    key = $1 SUBSEP $2
    if (key != current) { flush(); current = key; m = 0 }
    v[++m] = $3 + 0
    next
}
function at(p,    i) { if (m == 0) return ""; i = int(m * p + 0.999999); if (i < 1) i = 1; return sprintf("%.1f", v[i]) }
function flush() {
    if (current == "") return
    p50[current] = at(0.50); p95[current] = at(0.95); p99[current] = at(0.99); pmax[current] = sprintf("%.1f", v[m])
    seen[current] = 1
}
END {
    if (pass == "count") {
        close(out)
        for (key in count) { split(key, parts, SUBSEP); print parts[1] "\t" parts[2] "\t" parts[3] "\t" count[key] }
        exit
    }
    flush()
    print "op\tstage\tfrom_host_s\tto_host_s\tfactor\tarrivals\tarrivals_per_host_s\tok\trefused\tunresolved\tattempts\tfailed_attempts\tconflicts\tanswers_5xx\tanswers_429\tanswers_none\tdropped\tp50_ms\tp95_ms\tp99_ms\tmax_ms\tabandoned"
    split(ops, list, " ")
    for (o = 1; o in list; o++) {
        op = list[o]
        for (i = 1; i <= n + 1; i++) {
            s = (i <= n) ? i : "tail"
            key = op SUBSEP s
            if (s == "tail" && !(key in seen)) continue
            from = sprintf("%.0f", (i <= n) ? upto[i - 1] : upto[n])
            to = (i <= n) ? sprintf("%.0f", upto[i]) : "-"
            fct = (i <= n) ? ((only[i] == "-" || only[i] == op) ? factor[i] : 0) : "-"
            arrivals = c[key SUBSEP "iterations"] + 0
            rate = (i <= n && secs[i] > 0) ? sprintf("%.3f", arrivals * speed / secs[i]) : "-"
            printf "%s\t%s\t%s\t%s\t%s\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%s\t%s\t%s\t%s\t%d\n", op, s, from, to, fct, arrivals,
                rate, c[key SUBSEP "ok"], c[key SUBSEP "refused"], c[key SUBSEP "unresolved"], c[key SUBSEP "attempts"],
                c[key SUBSEP "failed"], c[key SUBSEP "conflicts"], c[key SUBSEP "s5xx"], c[key SUBSEP "s429"], c[key SUBSEP "s0"],
                c[key SUBSEP "dropped"], p50[key], p95[key], p99[key], pmax[key], c[key SUBSEP "abandoned"]
        }
    }
}
