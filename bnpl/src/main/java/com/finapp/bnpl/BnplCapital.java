package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;

/**
 * Lending capital, as bnpl judges it - a port {@code app} implements over lending's capital headroom (`P12-TSK-005`;
 * owner decision B2, ADR-0104 decisions 1 to 5, PHASE_12_PLAN.md sections 7.2, 7.3(f) and 12.9; {@code INV-BNPL-10},
 * {@code INV-LND-13}).
 *
 * <p><strong>One pool, one proof.</strong> A BNPL receivable is funded from the currency's {@code LENDING_CAPITAL}, the
 * same capital every loan and draw is funded from; there is no BNPL capital and no BNPL headroom of its own. Lending's
 * headroom already counts BNPL principal by ledger purpose, so the figure returned here is the one lending's own
 * consumers judge. bnpl has no build edge to {@code lending} ({@code BnplModuleIsolationTest}), so {@code app}
 * implements this ({@code LendingBnplCapital}).
 *
 * <p><strong>Under L6, in bnpl's transaction.</strong> {@link #underLock} takes the currency's capital
 * {@code ledger_account} row {@code FOR UPDATE} on {@code unitOfWork} - the caller's own transaction - and holds it to
 * that transaction's commit; only then is the headroom derived from journal lines. A BNPL purchase (`P12-TSK-013`) and a
 * principal-re-instating reversal (`-024`) call it after every bnpl row they lock and before any wallet or payable row
 * (PHASE_12_PLAN.md section 7.2) - the place lending's acceptance, draw and reversal take the same row, so one order
 * holds across both modules and two consumers in one currency can never both see the last capital free.
 *
 * <p><strong>No answer is not zero.</strong> A read that fails throws, failing the caller's transaction - never a
 * headroom of zero, of the old formula or from a cache. Code on every instance; no state of its own.
 */
public interface BnplCapital {

    /** The currency's headroom, the capital row locked {@code FOR UPDATE} first on {@code unitOfWork}. */
    BnplHeadroom underLock(Connection unitOfWork, CurrencyCode currency);
}
