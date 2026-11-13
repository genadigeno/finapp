# The test-only server's own record of its locks during a load whose profile asks for it (P16-TSK-008; OPERATIONS_RUNBOOK.md section
# 11.48), read from its log (kubectl logs of finapp-postgres-0 over the load) once the run sets log_lock_waits on its own server.
# kind-rehearsal/load.sh runs
#
#   awk -v dir=<artefacts> -f locks.awk postgres.log > locks-summary.txt
#
# and judges the summary. The server writes every line as "<time> UTC [<pid>] <LEVEL>:  <message>", a message running on over lines
# that begin with a tab, and the lines of one report (its ERROR or LOG, then DETAIL, HINT, CONTEXT, STATEMENT) under one pid. It writes:
#
#   deadlocks.txt   every deadlock (SQLSTATE 40P01, "deadlock detected") and every serialisation failure (40001, "could not serialize
#                   access") whole: the ERROR, its DETAIL with each process's waits and statement, its CONTEXT (the row: "while
#                   locking tuple (p,t) in relation ...") and its STATEMENT. The statements are parameterised as the application sent
#                   them; the server logs no parameter (log_parameter_max_length_on_error stays 0). What explains each one is the run's
#                   record (section 11.48), read against the known paths: never this script's;
#   lock-waits.tsv  every wait of at least deadlock_timeout (1 s) that ended: the pid, the lock awaited, the milliseconds it waited, how
#                   it ended (acquired, or a deadlock detected while waiting) and the relation the server names in its CONTEXT.
#
# and prints "deadlocks N", "serialisation N", "waits N", "unended N" (a wait logged as still waiting with no later line saying it
# ended: the load's end, or the log cut).
function flush(    p) {
    if (kind == "") return
    n[kind]++
    printf "=== %s %d (pid %s)\n%s\n", kind, n[kind], pid, body >> (dir "/deadlocks.txt")
    kind = ""
    body = ""
}
function context_of(text) {
    if (match(text, /relation "[^"]*"/)) return substr(text, RSTART + 10, RLENGTH - 11)
    return "-"
}
BEGIN {
    n["deadlock"] = 0; n["serialisation"] = 0
    waits = 0; unended = 0
    printf "pid\tlock\tms\tended\trelation\n" > (dir "/lock-waits.tsv")
}
# A continuation of the report being kept.
/^\t/ {
    if (kind != "") body = body "\n" $0
    next
}
{
    # "<date> <time> UTC [<pid>] <LEVEL>:  <message>"
    if (!match($0, /\[[0-9]+\] [A-Z]+:  /)) { flush(); next }
    p = substr($0, RSTART + 1, index(substr($0, RSTART), "]") - 2)
    level = substr($0, RSTART, RLENGTH)
    sub(/^\[[0-9]+\] /, "", level); sub(/:  $/, "", level)
    message = substr($0, RSTART + RLENGTH)
    # The rest of a kept report: the same pid's DETAIL, HINT, CONTEXT and STATEMENT.
    if (kind != "" && p == pid && level ~ /^(DETAIL|HINT|CONTEXT|STATEMENT)$/) { body = body "\n" $0; next }
    flush()
    if (level == "ERROR" && message ~ /^deadlock detected/) { kind = "deadlock"; pid = p; body = $0; next }
    if (level == "ERROR" && message ~ /^could not serialize access/) { kind = "serialisation"; pid = p; body = $0; next }
    # log_lock_waits: "process N still waiting for <lock> on <what> after T ms", then "process N acquired <lock> on <what> after T ms"
    # or "process N detected deadlock while waiting for <lock> on <what> after T ms"; each followed by its CONTEXT under the same pid.
    if (level == "LOG" && message ~ /^process [0-9]+ (still waiting for|acquired|detected deadlock while waiting for) /) {
        lock = message
        sub(/^process [0-9]+ (still waiting for|acquired|detected deadlock while waiting for) /, "", lock)
        sub(/ after [0-9.]+ ms.*$/, "", lock)
        ms = message
        sub(/^.* after /, "", ms); sub(/ ms.*$/, "", ms)
        if (message ~ /still waiting for/) { waiting[p] = lock; pending_ctx = ""; last = p; last_ended = ""; next }
        last = p
        last_ended = (message ~ /acquired/) ? "acquired" : "deadlock"
        last_lock = lock; last_ms = ms
        delete waiting[p]
        next
    }
    if (level == "CONTEXT" && p == last && last_ended != "") {
        printf "%s\t%s\t%s\t%s\t%s\n", last, last_lock, last_ms, last_ended, context_of(message) >> (dir "/lock-waits.tsv")
        waits++
        last_ended = ""
        next
    }
}
END {
    flush()
    # A wait that ended with no CONTEXT line after it is still a wait.
    if (last_ended != "") { printf "%s\t%s\t%s\t%s\t-\n", last, last_lock, last_ms, last_ended >> (dir "/lock-waits.tsv"); waits++ }
    for (q in waiting) unended++
    printf "deadlocks %d\nserialisation %d\nwaits %d\nunended %d\n", n["deadlock"], n["serialisation"], waits, unended
}
