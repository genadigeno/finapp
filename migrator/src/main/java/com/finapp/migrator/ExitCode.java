package com.finapp.migrator;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What the process tells the Job that started it ({@code DATA_MIGRATIONS.md} section 2.1).
 *
 * <p>A Job can act on two questions only: did it work, and would running it again help? So there are three failures, and
 * the numbers are chosen to mean nothing else: a JVM that dies uncaught exits {@code 1}, one that ran out of heap under
 * {@code -XX:+ExitOnOutOfMemoryError} exits {@code 3}, and a killed container {@code 137} or {@code 143}. All of those are
 * left to the Job's {@code backoffLimit}.
 */
@Getter
@RequiredArgsConstructor
enum ExitCode {

    /** Every schema is at the latest migration: this run applied them, or there was nothing to apply. */
    APPLIED(0),

    /** A migration or a validation failed. It will fail the same way again, so the Job must not retry. */
    FAILED(10),

    /** A lock could not be had within its bound, or the database was unavailable. Another attempt may succeed. */
    TRANSIENT(11),

    /** This process was given a configuration or an identity it must not run with. Nothing was changed. */
    REFUSED(12);

    private final int code;
}
