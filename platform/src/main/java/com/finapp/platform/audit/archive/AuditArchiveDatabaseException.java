package com.finapp.platform.audit.archive;

import java.io.Serial;

/**
 * A call of the audit archive's database functions failed (`P16-TSK-010`). Carries the operation and the database's SQLState, never
 * a value: the SQLStates the functions raise of their own are named here.
 *
 * <p><strong>No cause, on purpose</strong> ({@code StorageFailuresCarryNoRowTest}'s rule): PostgreSQL puts a refused row in a
 * violation's {@code DETAIL} and the driver puts that in the exception's message, and the rows here hold audit records'
 * {@code RESTRICTED-PII} columns. The SQLState is what decides anything.
 */
public final class AuditArchiveDatabaseException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** {@code complete_audit_archive_segment}: this claim no longer holds the segment; another instance took it over. */
    public static final String NOT_HELD = "AAHLD";

    /** {@code complete_audit_archive_segment}: the digests offered are not the ones the database computes from the rows. */
    public static final String DIGEST_REFUSED = "AADGS";

    /** {@code audit_archive_reviewed}: {@code platform.audit_record} has a column format 1 was not reviewed against. */
    public static final String NOT_REVIEWED = "55000";

    /** The claim: a queued audit id with no audit row (a deleted audit record). */
    public static final String DATA_CORRUPTED = "XX001";

    private final String operation;
    private final String sqlState;

    public AuditArchiveDatabaseException(String operation, String sqlState) {
        super("The audit archive's " + operation + " failed: SQLState " + sqlState);
        this.operation = operation;
        this.sqlState = sqlState;
    }

    /** The operation that failed: claim, content, manifest, complete or backlog. */
    public String operation() {
        return operation;
    }

    /** The database's SQLState, or {@code null}: locale-independent, and it carries no row's values. */
    public String sqlState() {
        return sqlState;
    }

    public boolean heldElsewhere() {
        return NOT_HELD.equals(sqlState);
    }

    public boolean digestRefused() {
        return DIGEST_REFUSED.equals(sqlState);
    }
}
