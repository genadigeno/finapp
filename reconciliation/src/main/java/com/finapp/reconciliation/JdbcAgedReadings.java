package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link AgedReadings} over explicit JDBC (ADR-0033), lock-free (`P16-TSK-029`). The breaks' count is one {@code GROUP BY} with a
 * cutoff per severity, written from {@link Severity}'s own values so a new severity cannot be left uncounted, over the open breaks'
 * {@code (severity, raised_at)} index; the suspense count is one {@code count(*)} over the items still holding a remainder.
 */
public final class JdbcAgedReadings implements AgedReadings {

    /** One disjunct per severity, in declaration order, each binding that severity's cutoff. */
    static final String AGED_BREAKS =
            "SELECT severity, count(*) AS aged FROM reconciliation.break WHERE status <> 'RESOLVED' AND ("
                    + Arrays.stream(Severity.values())
                            .map(severity -> "(severity = '" + severity.name() + "' AND raised_at <= ?)")
                            .collect(Collectors.joining(" OR "))
                    + ") GROUP BY severity";

    static final String AGED_SUSPENSE =
            "SELECT count(*) FROM reconciliation.suspense_item WHERE status <> 'RELEASED' AND opened_on <= ?";

    @Override
    public Map<Severity, Long> agedBreaks(Connection connection, Map<Severity, Instant> raisedAtOrBefore) {
        Objects.requireNonNull(raisedAtOrBefore, "raisedAtOrBefore must not be null");
        try (PreparedStatement read = connection.prepareStatement(AGED_BREAKS)) {
            int parameter = 1;
            for (Severity severity : Severity.values()) {
                Instant cutoff = raisedAtOrBefore.get(severity);
                if (cutoff == null) {
                    throw new IllegalArgumentException("no cutoff for " + severity + ": every severity is counted");
                }
                read.setObject(parameter++, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
            }
            Map<Severity, Long> aged = new EnumMap<>(Severity.class);
            for (Severity severity : Severity.values()) {
                aged.put(severity, 0L);
            }
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    aged.put(Severity.valueOf(row.getString("severity")), row.getLong("aged"));
                }
            }
            return Map.copyOf(aged);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not count the breaks past their bounds", failure);
        }
    }

    @Override
    public long agedSuspense(Connection connection, LocalDate openedOnOrBefore) {
        Objects.requireNonNull(openedOnOrBefore, "openedOnOrBefore must not be null");
        try (PreparedStatement read = connection.prepareStatement(AGED_SUSPENSE)) {
            read.setObject(1, openedOnOrBefore);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not count the suspense items past their bound", failure);
        }
    }
}
