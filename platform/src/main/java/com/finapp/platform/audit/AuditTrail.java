package com.finapp.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The audit trail's read port (`P17-TSK-017`, gap G-20; finapp-admin-panel ADR-C134 decision 1): a window of records with its
 * filters, and one record by its id. Read-only: nothing here, nor any other door, updates or deletes a record, and the application
 * role holds neither privilege on {@code platform.audit_record} ({@code V009}).
 *
 * <p><strong>What a page promises about late commits.</strong> A record's {@code audit_id} and {@code occurred_at} are minted on
 * the writing instance's clock, and its transaction may commit after a record with a later stamp has. So a keyset walk - newest
 * first - can pass a position, and a record stamped inside the range already served can commit afterwards: that record is in no
 * later page of that walk, and a fresh walk shows it. Each page is exact for what was committed when it was read. No time after
 * which a window is final is claimed - the platform bounds no transaction's length - and the trail's one reader that never misses
 * a late row is the archive's queue, which every row joins in its own transaction ({@code V015}).
 */
public interface AuditTrail<T> {

    /** The window, half-open {@code [from, to)} on {@code occurred_at}, and the optional exact-match filters. */
    record Filter(
            Instant from,
            Instant to,
            Optional<String> actorId,
            Optional<String> operation,
            Optional<String> targetType,
            Optional<String> targetId,
            Optional<String> correlationId,
            Optional<AuditOutcome> outcome) {}

    /** Where a page ended: the last record it served, by its stamp and id. */
    record Cursor(Instant occurredAt, UUID auditId) {}

    /** One record as stored. */
    record Row(
            UUID auditId,
            Instant occurredAt,
            String actorId,
            String actorType,
            String operation,
            String targetType,
            String targetId,
            Optional<String> reason,
            AuditOutcome outcome,
            String correlationId,
            Optional<String> changeSummary) {}

    /**
     * A page of the window in the declared total order - {@code occurred_at} newest first, the id breaking the tie - strictly after
     * {@code after} in it.
     */
    List<Row> page(T unitOfWork, Filter filter, Optional<Cursor> after, int limit);

    Optional<Row> byId(T unitOfWork, UUID auditId);
}
