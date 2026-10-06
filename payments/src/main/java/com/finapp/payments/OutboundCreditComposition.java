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

    /** After the entry posted: the subject's own completion - the trade booked onto {@code entry}, its edges. */
    void completed(T unitOfWork, Completion completion, JournalEntryId entry);

    /** The provider confirmed delivery to the beneficiary's institution. */
    void delivered(T unitOfWork, UUID subject, Instant deliveredAt);

    /** The credit will never execute: the subject fails - nothing posted, the hold already released. */
    void failed(T unitOfWork, UUID subject, OutboundCreditStore.FailureReason reason);
}
