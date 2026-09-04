package com.finapp.platform.audit;

/**
 * Writes an audit record, in the transaction of the action it records.
 *
 * <p><strong>The transaction is the caller's, and that is the guarantee.</strong> ADR-0010 is
 * explicit: an action that succeeds without its audit record is not permitted. If the record
 * could commit separately, the trail would either contain actions that were rolled back or —
 * far worse — omit actions that happened. The second is unrecoverable: nothing afterwards can
 * tell you that an unrecorded action occurred, which is precisely the property an audit trail
 * is bought for.
 *
 * <p>So the unit of work is passed in and never created here, exactly as for
 * {@code OutboxWriter} and {@code InboxRecordStore}. A writer that opened its own transaction
 * would look identical in every test and would silently reintroduce the gap.
 *
 * <p><strong>Failure raises.</strong> There is no "log it and continue" path. Continuing means
 * committing the action while knowing its audit record was lost, which converts an infrastructure
 * problem into a permanent hole in the trail.
 *
 * <p><strong>Append-only is not this interface's promise.</strong> There is no update and no
 * delete here, but their absence from an interface is a convention — the guarantee lives in the
 * database grants ({@code V009}, {@code INV-HIST-03}), because the next writer may not be this
 * interface at all.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface AuditWriter<T> {

    /**
     * Appends {@code record} to the audit trail.
     *
     * @throws AuditWriteException if the record could not be written — which must fail the
     *     caller's transaction, because an action committed without its audit record is
     *     undetectable afterwards
     */
    void append(T unitOfWork, AuditRecord record);
}
