# The top statements' plans, as the test-only server executed them (P16-TSK-008; PHASE_16_PLAN.md section 6.5; OPERATIONS_RUNBOOK.md
# section 11.48). auto_explain is preloaded on the kind overlay's test-only server and inert until a run opens its window
# (load_plans_window: every statement's plan with ANALYZE and BUFFERS, timing off, verbose so each ends in its Query Identifier, no
# parameter value). kind-rehearsal/load.sh then runs
#
#   awk -v top=<rank, queryid, total ms, calls, one a line> -v dir=<plans directory> -f plans.awk postgres.log > plans-summary.txt
#
# A plan in the server's log is "<time> UTC [<pid>] LOG:  duration: <ms> ms  plan:" and its lines, each beginning with a tab, the last
# "Query Identifier: <n>", the identifier pg_stat_statements ranks the statement by. For each statement of the top list, the slowest plan
# the window caught is written to <dir>/<rank>-<queryid>.txt with its duration and how many of its plans the window caught; a top
# statement the window caught none of (it ran nowhere in the window) is listed as such. Every sequential scan of a table in any plan
# caught is counted in <dir>/seq-scans.tsv by table and statement (section 6.5: any sequential scan on a growing table is recorded; which
# table grows is the soak's reading, soak-tables.tsv). A plan's conditions can hold the run's own generated values, as a custom plan
# prints its bound parameters into them; they stay in the run's artefacts, on the machine that ran it.
function finish_plan(    qid, i) {
    if (!inplan) return
    inplan = 0
    qid = ""
    if (match(plan, /\tQuery Identifier: -?[0-9]+/)) qid = substr(plan, RSTART + 19, RLENGTH - 19)
    caught++
    if (qid == "") { unidentified++; return }
    seen[qid]++
    if (qid in rank && (!(qid in best) || ms + 0 > best[qid] + 0)) { best[qid] = ms; text[qid] = plan }
    rest = plan
    while (match(rest, /Seq Scan on [a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*/)) {
        seq[substr(rest, RSTART + 12, RLENGTH - 12) "\t" qid]++
        rest = substr(rest, RSTART + RLENGTH)
    }
}
BEGIN {
    FS = "\t"
    wanted = 0
    while ((getline line < top) > 0) {
        split(line, f, "\t")
        if (f[2] == "") continue
        rank[f[2]] = f[1]; total[f[2]] = f[3]; calls[f[2]] = f[4]; order[++wanted] = f[2]
    }
    close(top)
    caught = 0; unidentified = 0
}
/^\t/ { if (inplan) plan = plan "\n" $0; next }
{
    finish_plan()
    if ($0 ~ /\] LOG:  duration: [0-9.]+ ms  plan:$/) {
        inplan = 1
        ms = $0; sub(/^.*duration: /, "", ms); sub(/ ms .*$/, "", ms)
        plan = ""
    }
}
END {
    finish_plan()
    found = 0
    for (i = 1; i <= wanted; i++) {
        qid = order[i]
        if (qid in best) {
            found++
            file = dir "/" rank[qid] "-" qid ".txt"
            printf "statement ranked %s by total time (%s ms over %s calls); the slowest of %d plans caught, %s ms:%s\n", rank[qid], total[qid],
                calls[qid], seen[qid], best[qid], text[qid] > file
            close(file)
        } else {
            printf "%s\t%s\tno plan caught\n", rank[qid], qid >> (dir "/not-caught.tsv")
        }
    }
    printf "table\tqueryid\tplans\n" > (dir "/seq-scans.tsv")
    for (k in seq) printf "%s\t%d\n", k, seq[k] >> (dir "/seq-scans.tsv")
    printf "top %d\ncaught %d\nplans %d\nunidentified %d\n", wanted, found, caught, unidentified
}
