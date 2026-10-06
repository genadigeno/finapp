package com.finapp.app.crossborder;

import com.finapp.crossborder.PaymentProgress;
import com.finapp.fx.CrossBorderCompletionBooking;
import com.finapp.fx.FxQuoteId;
import com.finapp.fx.FxTradeId;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.payments.OutboundCreditComposition;
import com.finapp.payments.OutboundCreditStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * payments' {@link OutboundCreditComposition} over crossborder's payment and fx's quote (`P9-TSK-020`,
 * PHASE_9_PLAN.md section 4's ports table): the completion's lines - fx's frozen plan beside the offer's frozen
 * fee, after the offer is checked against what payments held and instructed ({@code INV-XB-03}) - then, past the
 * posting, the trade booked onto the entry and the payment {@code IN_TRANSIT}; the delivery; the failure's
 * quote {@code ABANDONED} and payment {@code FAILED}. Every call runs in the applier's transaction and lets every
 * failure through.
 */
@RequiredArgsConstructor
public final class CrossBorderCompletion implements OutboundCreditComposition<Connection> {

    @NonNull private final PaymentProgress progress;
    @NonNull private final CrossBorderCompletionBooking booking;
    @NonNull private final Clock clock;
    @NonNull private final com.finapp.fx.ConversionParticipants participants;
    @NonNull private final com.finapp.ledger.ChartOfAccounts<Connection> chart;
    @NonNull private final io.micrometer.core.instrument.MeterRegistry meters;

    @Override
    public List<JournalLine> completionLines(Connection unitOfWork, Completion completion) {
        PaymentProgress.Locked locked = progress.lock(unitOfWork, completion.subject());
        // Offer = hold = instruction: what the customer was shown is what payments held and instructed.
        if (!locked.offer().totalDebit().equals(completion.held())
                || !locked.offer().destination().equals(completion.instructed())) {
            throw new IllegalStateException("the outbound credit's held or instructed amount differs from its frozen"
                    + " offer: INV-XB-03 is broken for payment " + completion.subject());
        }
        return booking.lines(unitOfWork, FxQuoteId.of(locked.payment().quote()), completion.wallet(), completion.clearing(),
                locked.offer().fee());
    }

    @Override
    public void completed(Connection unitOfWork, Completion completion, JournalEntryId entry) {
        PaymentProgress.Locked locked = progress.lock(unitOfWork, completion.subject());
        FxTradeId trade = booking.book(unitOfWork, FxQuoteId.of(locked.payment().quote()), entry.value(),
                "outbound-credit-" + completion.credit().value(), SecurityContext.require(),
                correlation());
        progress.inTransit(unitOfWork, completion.subject(), trade.value().toString(), completion.at(), correlation());
    }

    @Override
    public void delivered(Connection unitOfWork, UUID subject, Instant deliveredAt) {
        progress.lock(unitOfWork, subject);
        progress.delivered(unitOfWork, subject, Instant.now(clock), correlation());
    }

    @Override
    public void failed(Connection unitOfWork, UUID subject, OutboundCreditStore.FailureReason reason) {
        PaymentProgress.Locked locked = progress.lock(unitOfWork, subject);
        // The quote is abandoned before the payment fails: its cover's unwind keys off the abandonment (P9-TSK-021).
        booking.abandon(unitOfWork, FxQuoteId.of(locked.payment().quote()), "PAYMENT_FAILED_" + reason.name(),
                SecurityContext.require(), correlation());
        progress.failed(unitOfWork, subject, reason.name(), Instant.now(clock), correlation());
    }

    @Override
    public java.util.Optional<List<JournalLine>> returnLines(Connection unitOfWork, ReturnApplication application) {
        // The applicability rule's customer half (P9-TSK-023, INV-XB-04): an ACTIVE customer only - a closed one's
        // value stays parked for a person. Nothing is written before the answer is known.
        java.util.Optional<UUID> customer = participants.activeCustomer(unitOfWork, application.customerParty());
        if (customer.isEmpty()) {
            return java.util.Optional.empty();
        }
        PaymentProgress.Locked locked = progress.lock(unitOfWork, application.subject());
        com.finapp.sharedkernel.money.Money fee = locked.offer().fee();
        java.util.Optional<com.finapp.ledger.LedgerAccountId> sourceWallet =
                participants.wallet(unitOfWork, customer.get(), fee.currency());
        if (sourceWallet.isEmpty()) {
            return java.util.Optional.empty();
        }
        // The returned currency's wallet, opened if absent - in this transaction (section 12.4(i)).
        java.util.Optional<com.finapp.ledger.LedgerAccountId> destinationWallet =
                participants.openIfAbsent(unitOfWork, customer.get(), application.returned().currency());
        if (destinationWallet.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<JournalLine> lines = new java.util.ArrayList<>(List.of(
                new JournalLine(application.clearing(), com.finapp.ledger.Direction.DEBIT, application.returned()),
                new JournalLine(destinationWallet.get(), com.finapp.ledger.Direction.CREDIT, application.returned())));
        if (fee.isPositive()) {
            com.finapp.ledger.LedgerAccountId feeRevenue =
                    chart.resolve(unitOfWork, com.finapp.ledger.AccountPurpose.FEE_REVENUE, fee.currency()).id();
            lines.add(new JournalLine(feeRevenue, com.finapp.ledger.Direction.DEBIT, fee));
            lines.add(new JournalLine(sourceWallet.get(), com.finapp.ledger.Direction.CREDIT, fee));
        }
        return java.util.Optional.of(List.copyOf(lines));
    }

    @Override
    public void recallAnswered(Connection unitOfWork, UUID subject, com.finapp.payments.OutboundCreditStore.RecallOutcome outcome) {
        // Telemetry, never the count of record (the credit's recall_outcome is): the cancellation rate, the free-option
        // watch of PHASE_9_PLAN.md section 13.
        meters.counter("finapp.crossborder.cancellation", "outcome",
                outcome == com.finapp.payments.OutboundCreditStore.RecallOutcome.RECALLED ? "recalled" : "too_late")
                .increment();
    }

    @Override
    public void returned(Connection unitOfWork, UUID subject, String basis, Instant at) {
        progress.returned(unitOfWork, subject, basis, at, correlation());
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an outbound credit's outcome runs inside a correlation scope"))
                .correlationId();
    }
}
