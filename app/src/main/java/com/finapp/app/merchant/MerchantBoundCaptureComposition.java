package com.finapp.app.merchant;

import com.finapp.ledger.JournalLine;
import com.finapp.merchant.MerchantSettlement;
import com.finapp.payments.CaptureComposition;
import com.finapp.payments.CaptureSettlement;
import com.finapp.payments.WalletTopUpComposition;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * The composition seam, closed (`P6-TSK-005`, ADR-0050 §6) — the whole of what this class does
 * is decide which module composes a capture's entry, and it is deliberately the only place
 * that can.
 *
 * <p>{@code payments} cannot see {@code merchant} and must not: fee and merchant vocabulary
 * never enter the payment domain model ({@code INV-PAY-03}'s discipline at a second
 * vocabulary). {@code merchant} cannot see {@code payments} either — it knows an intent only
 * as a {@code UUID} it was handed. So the join lives here, in the composition root, exactly as
 * {@code JdbcPaymentParticipants} joins payments to party, accounts and paymentmethods.
 *
 * <p><strong>Ask, then fall back.</strong> A merchant-bound payment has a fee pin; a wallet
 * top-up has none, and gets Phase 5's two lines from {@link WalletTopUpComposition} — the same
 * expression that class inherited from {@code PaymentOutcomes}, so the top-up path is
 * byte-identical after this task, which is one of its acceptance criteria.
 *
 * <p>There is no third branch and no default-if-unsure: {@code merchant} answers empty or it
 * answers four lines, and everything it cannot answer it throws about
 * ({@code MerchantSettlementException}), which fails the capture's whole transaction rather
 * than posting fewer lines than the money owes.
 */
public final class MerchantBoundCaptureComposition implements CaptureComposition<Connection> {

    private final MerchantSettlement settlement;
    private final CaptureComposition<Connection> walletTopUp;

    public MerchantBoundCaptureComposition(
            MerchantSettlement settlement, CaptureComposition<Connection> walletTopUp) {
        this.settlement = Objects.requireNonNull(settlement, "settlement must not be null");
        this.walletTopUp = Objects.requireNonNull(walletTopUp, "walletTopUp must not be null");
    }

    @Override
    public List<JournalLine> settle(Connection unitOfWork, CaptureSettlement capture) {
        return settlement
                .settle(
                        unitOfWork,
                        capture.intent().value(),
                        capture.clearing(),
                        capture.credit(),
                        capture.captured(),
                        capture.correlation(),
                        capture.at())
                .orElseGet(() -> walletTopUp.settle(unitOfWork, capture));
    }
}
