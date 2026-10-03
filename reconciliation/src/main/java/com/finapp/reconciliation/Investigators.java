package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.UUID;

/**
 * Who may be named a break's assignee (the Phase 8 -> 9 transition, SEC-06): an existing,
 * {@code ACTIVE} identity holding {@code RECONCILIATION_INVESTIGATE} through a live role — the
 * permission the investigator's desk itself requires. Reconciliation compiles against no sibling
 * but the ledger (ADR-0064), so {@code app} composes this over identity's public reads, on the
 * assignment's own connection: judged at the assignment's moment, as the boundary judges a
 * request's permission — a role revoked afterwards leaves the record naming who was assigned.
 */
@FunctionalInterface
public interface Investigators {

    /** Whether {@code principal} is an active identity holding the investigator's permission. */
    boolean investigates(Connection unitOfWork, UUID principal);
}
