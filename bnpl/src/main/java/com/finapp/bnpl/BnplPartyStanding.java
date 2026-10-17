package com.finapp.bnpl;

import java.sql.Connection;
import java.util.UUID;

/**
 * Whether a party may take up BNPL credit now (`P12-TSK-010`; {@code PHASE_12_PLAN.md} sections 3.3 and 11, lending's
 * {@code LoanPartyStanding} precedent) - a port {@code app} implements over credit's {@code CreditPartyStanding}, because
 * bnpl sees neither {@code credit} nor {@code party} (ADR-0101 decision 6, {@code BnplModuleIsolationTest}).
 *
 * <p>Read authoritatively on the caller's unit of work, never cached: a suspension committed on another instance refuses
 * this one's very next read. Checked in-transaction by the application (`P12-TSK-010`) and the acceptance (`-013`) -
 * never by a repayment: a suspended customer may still repay.
 */
public interface BnplPartyStanding {

    /** A verified customer in good standing, now. */
    boolean inGoodStanding(Connection unitOfWork, UUID party);
}
