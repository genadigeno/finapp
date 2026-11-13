# E65's reader (P16-TSK-012, ADR-0142; OPERATIONS_RUNBOOK.md section 11.57). Two modes:
#
#   -v mode=delta <before> <after>   one report run's cost, from reports.sql read before and after it: every finapp_reporting statement
#                                    the run called, its time and calls as differences. Prints "total_ms <tab> largest_ms <tab> calls",
#                                    largest_ms the slowest statement's time per call: the four-minute bound is a statement's, not a run's.
#   -v mode=fit -v bound_ms=<ms> <reports.tsv>
#                                    the runs' largest statement against the journal's lines when each ran, fitted by least squares:
#                                    the time a statement takes per million lines and its fixed part, then the lines at which one
#                                    statement reaches the generation bound (read from the code by load.sh, never copied here).
#
# reports.tsv's columns: sample, epoch, journal lines, journal entries, run, status, generation ms, total ms, largest ms, calls.
# Nothing here judges: load.sh fails a run on a report that did not complete or on a reader that saw nothing; the reading is the record's.
BEGIN { FS = (mode == "fit") ? "\t" : "|" }

# By file name, never FNR == NR: the first run's "before" holds no statement of the role yet, and an empty first file would make
# every line of the second one look like the first's.
mode == "delta" && FILENAME == ARGV[1] && $1 == "reporting" { calls[$2] = $3; spent[$2] = $4; next }
mode == "delta" && FILENAME == ARGV[2] && $1 == "reporting" {
    c = $3 - calls[$2]; t = $4 - spent[$2]
    if (c > 0) { total += t; n += c; if (t / c > largest) largest = t / c }
    next
}

mode == "fit" && FNR > 1 && $6 != "" {
    x = $3 + 0; y = $9 + 0
    k++; sx += x; sy += y; sxx += x * x; sxy += x * y
    if (k == 1 || x < lo) lo = x
    if (x > hi) hi = x
}

END {
    if (mode == "delta") { printf "%.3f\t%.3f\t%d\n", total, largest, n; exit }
    if (mode != "fit") { print "reports.awk: mode is delta or fit" > "/dev/stderr"; exit 2 }
    printf "runs\t%d\njournal lines, first to last\t%d\t%d\n", k, lo, hi
    d = k * sxx - sx * sx
    if (k < 2 || d == 0) { print "fit\tnone: fewer than two journal sizes"; exit }
    slope = (k * sxy - sx * sy) / d
    intercept = (sy - slope * sx) / k
    printf "largest statement, ms a million lines\t%.3f\nlargest statement, fixed ms\t%.3f\n", slope * 1000000, intercept
    printf "generation bound, ms\t%d\n", bound_ms
    if (slope <= 0) { print "lines at the bound\tnone measurable: the statement did not grow with the journal over this run's sizes"; exit }
    printf "lines at the bound\t%.0f\n", (bound_ms - intercept) / slope
}
