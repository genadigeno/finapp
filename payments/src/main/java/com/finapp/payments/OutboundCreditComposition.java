package com.finapp.payments;

import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What an outbound credit's outcome means to its subject (`P9-TSK-020`, PHASE_9_PLAN.md section 4's ports
 * table; the {@code CaptureComposition} shape): payments owns the credit's machine, the claim, the hold, the
 * posting and the expectation; the subject's owners - crossborder's payment and fx's quote, composed in
 * {@code app} - say which lines the completion posts and take their own edges. Each call runs in the
 * applier's transaction, past its acting exit, and never swallows a failure: a throw rolls the outcome back
 * whole for the next resolver.
 *
 * @param <T> the unit of work
 */
public interface OutboundCreditComposition<T> {

    /**
     * One completion's facts, as payments holds them.
     *
     * @param wallet the account the credit's hold rested on - the debit side
     * @param clearing the corridor's clearing account, read off the rail's declaration - the credit side
     * @param held the total debit held at authorization
     * @param instructed the destination amount the provider was instructed to credit
     */
    record Completion(
            OutboundCreditId credit,
            UUID subject,
            RailId rail,
            LedgerAccountId wallet,
            LedgerAccountId clearing,
            Money held,
            Money instructed,
            EndToEndReference reference,
            ProviderReference providerReference,
            Instant at) {
        public Completion {
            Objects.requireNonNull(credit, "credit must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(rail, "rail must not be null");
            Objects.requireNonNull(wallet, "wallet must not be null");
            Objects.requireNonNull(clearing, "clearing must not be null");
            Objects.requireNonNull(held, "held must not be null");
            Objects.requireNonNull(instructed, "instructed must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(providerReference, "providerReference must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }

    /**
     * The completion entry's lines (PHASE_9_PLAN.md section 12.4(g)): the wallet debited the held total, the
     * fee, the conversion's frozen lines and the clearing credited the instructed amount. Payments verifies the
     * disclosure-to-posting identity on them before posting ({@code INV-XB-03}).
     */
    List<JournalLine> completionLines(T unitOfWork, Completion completion);

    /**
     * Before a completion releases the hold or posts: the subject's rows locked in the global lock order - after the
     * credit the caller holds, before any wallet or projection row - so a completion never holds the FX position's
     * projection rows while waiting for the quote a cover outcome holds (the Phase 9 -> 10 transition's deadlock).
     */
    void lockSubject(T unitOfWork, UUID subject);

    /** After the entry posted: the subject's own completion - the trade booked onto {@code entry}, its edges. */
    void completed(T unitOfWork, Completion completion, JournalEntryId entry);

    /** The provider confirmed delivery to the beneficiary's institution. */
    void delivered(T unitOfWork, UUID subject, Instant deliveredAt);

    /** The credit will never execute: the subject fails - nothing posted, the hold already released. */
    void failed(T unitOfWork, UUID subject, OutboundCreditStore.FailureReason reason);

    /**
     * One applicable return's facts (`P9-TSK-023`): the credit, its subject, the customer it was paid for, the
     * corridor's clearing it is debited from, and the amount that came back - exactly the instructed credit.
     */
    record ReturnApplication(
            OutboundCreditId credit, UUID subject, UUID customerParty, LedgerAccountId clearing, Money returned) {
        public ReturnApplication {
            Objects.requireNonNull(credit, "credit must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(customerParty, "customerParty must not be null");
            Objects.requireNonNull(clearing, "clearing must not be null");
            Objects.requireNonNull(returned, "returned must not be null");
        }
    }

    /**
     * The return entry's lines (PHASE_9_PLAN.md section 12.4(i)): the clearing debited and the customer's wallet in
     * the returned currency credited - the wallet opened if absent, in the caller's transaction - and the fee
     * refunded from {@code FEE_REVENUE} to the wallet in the source currency. Empty when the return is not
     * applicable to its customer - not {@code ACTIVE}, or the source wallet gone: nothing is written then.
     */
    java.util.Optional<List<JournalLine>> returnLines(T unitOfWork, ReturnApplication application);

    /** After the return posted (or a person's resolution recorded it): the subject's {@code RETURNED} edge. */
    void returned(T unitOfWork, UUID subject, String basis, Instant at);

    /**
     * The provider answered a cancellation's recall (`P9-TSK-024`) - telemetry for the composition's own tally,
     * never a record; the conclusion itself goes through {@link #failed} or the completion as ever.
     */
    default void recallAnswered(T unitOfWork, UUID subject, OutboundCreditStore.RecallOutcome outcome) {}
}
