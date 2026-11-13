# E65's growth reading (P16-TSK-012, ADR-0142; OPERATIONS_RUNBOOK.md section 11.57): the soak's named tables (load/pg-soak-tables.tsv,
# written every 60 s by E39's sampler from load/soak.sql) read into each table's growth over the run, per hour and per money movement.
# The soak holds the target's mix at a fraction of its rate, so a figure per movement, multiplied by the target's movements a day
# (CAPACITY_MODEL.md section 3.1, derived there, never copied here), is the table's growth a day at the target; the record does that
# multiplication. -v moved=<the run's money movements, from k6's report>.
#
# pg-soak-tables.tsv's columns: time, epoch, table, live rows, dead rows, inserted, updated, HOT-updated, deleted, autovacuum runs,
# autoanalyze runs, vacuum runs, heap bytes, index bytes, total bytes, last autovacuum. Counters are cumulative since the server's
# start, so growth is the last sample less the first. Dead rows are read against live rows at every sample: the largest share, and the
# share at the end. Nothing here judges; load.sh fails the reading only when a named table was never read or the journal grew by nothing.
BEGIN {
    FS = "\t"; OFS = "\t"
    print "table", "samples", "hours", "inserted", "deleted", "inserted_an_hour", "deleted_an_hour", "live_first", "live_last",
        "dead_share_max", "dead_share_last", "autovacuum_runs", "total_bytes_first", "total_bytes_last", "bytes_a_live_row_last",
        "inserted_a_movement", "deleted_a_movement", "bytes_a_movement"
}

NF >= 15 {
    t = $3
    if (!(t in samples)) { order[++tables] = t; e0[t] = $2; ins0[t] = $6; del0[t] = $9; live0[t] = $4; bytes0[t] = $15; av0[t] = $10 }
    samples[t]++
    e1[t] = $2; ins1[t] = $6; del1[t] = $9; live1[t] = $4; dead1[t] = $5; bytes1[t] = $15; av1[t] = $10
    share = ($4 + 0 > 0) ? $5 / $4 : 0
    if (share > deadmax[t]) deadmax[t] = share
}

END {
    for (i = 1; i <= tables; i++) {
        t = order[i]
        hours = (e1[t] - e0[t]) / 3600
        inserted = ins1[t] - ins0[t]; deleted = del1[t] - del0[t]
        last = (live1[t] + 0 > 0) ? dead1[t] / live1[t] : 0
        print t, samples[t], sprintf("%.2f", hours), inserted, deleted,
            (hours > 0) ? sprintf("%.0f", inserted / hours) : "-", (hours > 0) ? sprintf("%.0f", deleted / hours) : "-",
            live0[t], live1[t], sprintf("%.3f", deadmax[t]), sprintf("%.3f", last), av1[t] - av0[t], bytes0[t], bytes1[t],
            (live1[t] + 0 > 0) ? sprintf("%.0f", bytes1[t] / live1[t]) : "-",
            (moved + 0 > 0) ? sprintf("%.3f", inserted / moved) : "-", (moved + 0 > 0) ? sprintf("%.3f", deleted / moved) : "-",
            (moved + 0 > 0) ? sprintf("%.0f", (bytes1[t] - bytes0[t]) / moved) : "-"
    }
}
