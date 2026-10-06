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

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an outbound credit's outcome runs inside a correlation scope"))
                .correlationId();
    }
}
