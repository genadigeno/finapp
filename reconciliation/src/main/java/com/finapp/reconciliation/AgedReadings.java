package com.finapp.reconciliation;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * What is past its bound, counted (`P16-TSK-029`, {@code THREAT_MODEL.md} §6 item 42 (a)): the open breaks of each severity raised at
 * or before that severity's cutoff, and the suspense items holding a remainder opened on or before the suspense cutoff. The caller
 * computes each cutoff from its bound and one reading of its clock, the instant the age gauges are measured from, so a count of at
 * least one is exactly an age gauge past its bound. Counts only, never an identifier or a value at issue (ADR-0072).
 */
public interface AgedReadings {

    /** Per severity, the open breaks raised at or before its cutoff; a severity with none reads 0. */
    Map<Severity, Long> agedBreaks(Connection connection, Map<Severity, Instant> raisedAtOrBefore);

    /** The suspense items with a remainder opened on or before the cutoff. */
    long agedSuspense(Connection connection, LocalDate openedOnOrBefore);
}
