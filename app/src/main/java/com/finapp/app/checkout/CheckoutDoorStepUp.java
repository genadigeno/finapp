package com.finapp.app.checkout;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentParticipants;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The checkout confirmation door's step-up check, before the confirmation writes anything (`P13-TSK-010`;
 * {@code PHASE_13_PLAN.md} section 12.3; ADR-0108 decision 3) - the transfer's ({@code TransferDoorStepUp}) and the
 * withdrawal's ({@code WithdrawalDoorStepUp}) rules at the checkout: a proposed card or wallet payment the acting session
 * cannot satisfy a challenge for is answered {@code 403 identity.AssuranceRequired} <strong>before any row exists</strong>
 * - no intent opened, no fee pinned, no session moved - so the customer steps up and confirms again instead of reading a
 * refused payment. Run by {@link CheckoutService} in the door's own short transaction, which writes nothing (and is not
 * {@code READ ONLY}: the advice's pin is {@code FOR SHARE}).
 *
 * <p><strong>Three answers it must not give.</strong>
 *
 * <ul>
 *   <li><strong>A judged payment is never advised.</strong> Only a payment risk has not yet judged is advised: a session
 *       still {@code OPEN} (the confirmation will open its intent), or one {@code PAYMENT_PENDING} over an intent still
 *       awaiting confirmation (its Tx1 rolled back - an unfunded wallet, a rail out of service - so nothing was judged
 *       or counted). A session whose intent was dispatched, refused or paid converges or is refused by the command;
 *       advising it would read windows that include its own count and could answer a different thing for the same
 *       payment ({@code INV-IDEM-01}).</li>
 *   <li><strong>No advice over somebody else's instrument or a dead offer.</strong> The card must resolve as the
 *       caller's own card, the wallet as the caller's own wallet in the offer's currency, the offer open and in date -
 *       otherwise the advice is skipped and the command answers its own refusal as it always has: the advice is no
 *       oracle.</li>
 *   <li><strong>Nothing of the judgement.</strong> The port answers a boolean; the refusal is identity's step-up code
 *       with identity's words - which factor to use, never why ({@code INV-RSK-08}).</li>
 * </ul>
 */
final class CheckoutDoorStepUp {

    /** What the door answers before the command. */
    enum Answer {
        /** No step-up needed, or not this door's to advise: the command runs. */
        PROCEED,
        /** A challenge the session does not satisfy, and the identity holds a factor to step up with. */
        STEP_UP,
        /** A challenge the session does not satisfy, and the identity holds no factor yet. */
        ENROL_AND_STEP_UP
    }

    /** The offer the presented token names - {@code CheckoutSessions.byToken}'s read, empty for an unknown token. */
    @FunctionalInterface
    interface Offers {
        Optional<CheckoutSession> presented(Connection unitOfWork, CheckoutSessionToken token);

        /** The production read. */
        static Offers over(CheckoutSessions sessions) {
            Objects.requireNonNull(sessions, "sessions");
            return (unitOfWork, token) -> {
                try {
                    return Optional.of(sessions.byToken(unitOfWork, token));
                } catch (UnknownCheckoutSessionException unknown) {
                    return Optional.empty();
                }
            };
        }
    }

    private final CheckoutStepUpAdvice advice;
    private final Offers sessions;
    private final PaymentIntentStore<Connection> intents;
    private final PaymentParticipants<Connection> participants;
    private final CustomerAccountStore<Connection> products;
    private final MfaEnrolmentStore<Connection> enrolments;
    private final Clock clock;

    CheckoutDoorStepUp(
            CheckoutStepUpAdvice advice,
            Offers sessions,
            PaymentIntentStore<Connection> intents,
            PaymentParticipants<Connection> participants,
            CustomerAccountStore<Connection> products,
            MfaEnrolmentStore<Connection> enrolments,
            Clock clock) {
        this.advice = Objects.requireNonNull(advice, "advice");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.participants = Objects.requireNonNull(participants, "participants");
        this.products = Objects.requireNonNull(products, "products");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The door's answer for the presented offer, on the door's own {@code unitOfWork}: the offer and the instrument
     * resolved as the caller's first, a judged payment never advised. Writes nothing.
     *
     * @param paymentMethodId the body's card, or empty when the body names the wallet
     */
    Answer advise(
            Connection unitOfWork,
            UUID party,
            IdentityId identity,
            CheckoutSessionToken presented,
            Optional<UUID> paymentMethodId) {
        Optional<CheckoutSession> found = sessions.presented(unitOfWork, presented);
        if (found.isEmpty()) {
            return Answer.PROCEED;
        }
        CheckoutSession session = found.get();
        if (session.financedByRef().isPresent() || session.hasExpired(clock)) {
            return Answer.PROCEED;
        }
        Optional<Instrument> instrument;
        if (session.status() == CheckoutSessionStatus.OPEN && session.acceptsNewWork()) {
            instrument = paymentMethodId.isPresent()
                    ? card(unitOfWork, party, paymentMethodId.get())
                    : wallet(unitOfWork, party, session.amount());
        } else if (session.status() == CheckoutSessionStatus.PAYMENT_PENDING) {
            // The retry of a confirmation whose Tx1 rolled back: the intent's OWN instrument, frozen at birth.
            instrument = session.paymentIntentRef()
                    .flatMap(ref -> intents.findOwned(unitOfWork, PaymentIntentId.of(ref), party))
                    .filter(intent -> intent.status() == PaymentIntentStatus.REQUIRES_CONFIRMATION)
                    .flatMap(intent -> instrumentOf(unitOfWork, party, intent));
        } else {
            return Answer.PROCEED;
        }
        if (instrument.isEmpty()) {
            return Answer.PROCEED;
        }
        if (!advice.stepUpRequired(
                unitOfWork,
                new CheckoutStepUpAdvice.Proposed(party, identity.value(), session.merchantRef(),
                        instrument.get().walletAccount(), session.amount()))) {
            return Answer.PROCEED;
        }
        return enrolments.findActive(unitOfWork, identity, MfaFactorType.TOTP).isPresent()
                ? Answer.STEP_UP
                : Answer.ENROL_AND_STEP_UP;
    }

    /** The judged instrument: the caller's own card (no account), or the caller's wallet by its customer account. */
    private record Instrument(Optional<UUID> walletAccount) {}

    private Optional<Instrument> instrumentOf(Connection unitOfWork, UUID party, PaymentIntent intent) {
        return intent.debitAccount().isPresent()
                ? wallet(unitOfWork, party, intent.amount())
                : card(unitOfWork, party, intent.paymentMethodId());
    }

    /** The caller's own CARD - a bank instrument's checkout is not judged, so it is never advised. */
    private Optional<Instrument> card(Connection unitOfWork, UUID party, UUID paymentMethodId) {
        return participants.instrumentKindOwnedBy(unitOfWork, party, paymentMethodId)
                .filter(InstrumentKind.CARD_TOKEN::equals)
                .map(kind -> new Instrument(Optional.empty()));
    }

    /** The caller's own wallet in the offer's currency, named by the wallet product's customer account id. */
    private Optional<Instrument> wallet(Connection unitOfWork, UUID party, Money amount) {
        return participants.payerWalletOwnedBy(unitOfWork, party, amount.currency())
                .flatMap(wallet -> products.findLive(unitOfWork, wallet.customerId(), ProductType.WALLET))
                .map(CustomerAccount::id)
                .map(id -> new Instrument(Optional.of(id.value())));
    }

    /** The {@code 403} for a step-up answer - identity's code and words, nothing of the judgement. */
    static ApiException refusal(Answer answer) {
        if (answer == Answer.PROCEED) {
            throw new IllegalArgumentException("PROCEED is not a refusal");
        }
        return new ApiException(
                IdentityErrorCode.ASSURANCE_REQUIRED,
                "A checkout confirmation was answered with a step-up before the command: the session is below"
                        + " MULTI_FACTOR",
                answer == Answer.STEP_UP
                        ? "this payment requires a second factor: step up with your enrolled factor and confirm"
                                + " again."
                        : "this payment requires a second factor: enrol one, step up with it, and confirm again.");
    }
}
