package com.finapp.lending;

import java.sql.Connection;
import java.util.UUID;

/**
 * Whether a party may take up credit now (`P11-TSK-010`; {@code PHASE_11_PLAN.md} sections 3.2 and 11) - a port
 * {@code app} implements over credit's {@code CreditPartyStanding}, because lending sees neither {@code credit} nor
 * {@code party} (A16).
 *
 * <p>Read authoritatively on the caller's unit of work, never cached: a suspension committed on another instance refuses
 * this one's very next read. Checked in-transaction by the application (`P11-TSK-010`), the acceptance (`-012`) and a
 * draw (`-021`) - never by a repayment: a suspended customer may still repay.
 */
public interface LoanPartyStanding {

    /** A verified customer in good standing, now. */
    boolean inGoodStanding(Connection unitOfWork, UUID party);
}
