package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a rail declares about itself (`P7-TSK-001`, ADR-0059 §1) — the descriptor the payments
 * domain consults before it acts, in the platform's own vocabulary ({@code INV-PAY-03}: enums
 * and an {@link AccountPurpose}, never a scheme's message types or a provider's strings).
 *
 * <h2>Immutable per adapter version, and coherent by construction</h2>
 *
 * <p>The descriptor is compiled-in data: every instance of a build holds the identical one, so
 * there is nothing to cache, refresh, or read stale. What the domain knew when it decided is
 * stored where the decision is — the attempt's rail, and later the routing decision's recorded
 * descriptor (ADR-0060) — never re-derived from whatever an adapter says today.
 *
 * <p>The compact constructor refuses the combinations ADR-0059 §1's table makes structurally
 * impossible, so an incoherent declaration fails the build's own tests rather than mis-posting
 * money: a void on anything but a two-step rail, a book rail with a clearing position, finality
 * at posting for a rail with an outside decider, chargebacks without the revocable window they
 * are. The checks encode the table, not speculation about future rails.
 *
 * @param interactionModel how the payment is conducted; the machine keys on it (`P7-TSK-002`)
 * @param finality whether and until when the payee's credit can be taken back — never whether
 *     the interbank obligation is discharged, which is {@code settlement}'s ({@code INV-SET-01})
 * @param reversals the reversal operations the rail honours; a reversal outside this set is
 *     refused by the domain, never attempted and failed at the provider ({@code INV-REV-03},
 *     enforced by the void's own task, `P7-TSK-004`)
 * @param refundMode how a refund — a new forward movement, never a reversal ({@code INV-REV-01})
 *     — executes on this rail
 * @param settlement how the external obligation is discharged, if one exists
 * @param outcomeDeadline the scheme's bound on a final answer, after which a status inquiry is
 *     authoritative — a push rail's property; a two-step rail's ambiguity ends only when the
 *     provider or a query says so
 * @param disputes what can be forced back through the rail's own rulebook
 * @param currencies the currencies the rail carries, or empty when the rail declares no
 *     restriction — read by routing eligibility (`P7-TSK-003`), never enforced here
 * @param perCurrencyMaximum the rail's declared per-payment ceilings, empty when none; keys are
 *     each entry's own currency, and a restricted rail's ceilings name only carried currencies
 * @param clearingPurpose the ledger position this rail's value in flight occupies — each
 *     external rail its own, so one counterparty's receivable never nets against another's
 *     (`INV-RAIL-04`); empty exactly when nothing external settles
 */
public record RailCapabilities(
        InteractionModel interactionModel,
        Finality finality,
        Set<Reversal> reversals,
        RefundMode refundMode,
        SettlementModel settlement,
        Optional<Duration> outcomeDeadline,
        DisputeModel disputes,
        Optional<Set<CurrencyCode>> currencies,
        Map<CurrencyCode, Money> perCurrencyMaximum,
        Optional<AccountPurpose> clearingPurpose) {

    /** Whether the payee's credit can be taken back, and until when. */
    public enum Finality {
        /** A card capture: void, refund and the scheme's dispute window all still bite. */
        REVOCABLE_UNTIL_DISPUTE_WINDOW_ENDS,
        /** An instant credit transfer: irrevocable once the scheme confirms. */
        FINAL_ON_ACCEPTANCE,
        /** A book movement: final when the posting commits — there is nobody else to decide. */
        FINAL_ON_POSTING
    }

    /** A reversal operation a rail may honour. Refunds are deliberately not here. */
    public enum Reversal {
        /** Releasing an uncaptured authorization at the issuer (`P7-TSK-004`). */
        VOID
    }

    /** How a refund executes on this rail. */
    public enum RefundMode {
        /** Against the capture, at the provider — the card shape (`P5-TSK-015`). */
        PROVIDER_REFUND,
        /** A new credit transfer back to the payer (`P7-TSK-010`). */
        RETURN_PAYMENT,
        /** A compensating book movement in one transaction (`P7-TSK-011`). */
        BOOK_REFUND,
        /**
         * None executes here (`P9-TSK-014`, ADR-0080 section 1): the rail carries no pay-in, so there is
         * nothing to refund on it - the corridor's truthful declaration. Only a push rail may declare
         * it, and routing refuses a {@code PAY_IN} on it with
         * {@link RoutingRejection#DIRECTION_UNSUPPORTED}.
         */
        NONE
    }

    /** How the external obligation is discharged. */
    public enum SettlementModel {
        /** Later, through clearing, reported by the PSP — Phase 8's card evidence. */
        DEFERRED_VIA_CLEARING,
        /** On the scheme's own cycle, reported by the scheme. */
        SCHEME_REPORTED,
        /** Nothing external to settle — the book rail. */
        NONE
    }

    /** What the rail's rulebook can force back. */
    public enum DisputeModel {
        /** Card-scheme chargebacks, with their stages and deadlines (ADR-0061). */
        CARD_SCHEME_CHARGEBACKS,
        /** No dispute mechanism exists on the rail. */
        NONE
    }

    public RailCapabilities {
        Objects.requireNonNull(interactionModel, "interactionModel must not be null");
        Objects.requireNonNull(finality, "finality must not be null");
        Objects.requireNonNull(reversals, "reversals must not be null - none is the empty set");
        Objects.requireNonNull(refundMode, "refundMode must not be null");
        Objects.requireNonNull(settlement, "settlement must not be null");
        Objects.requireNonNull(outcomeDeadline, "outcomeDeadline must not be null");
        Objects.requireNonNull(disputes, "disputes must not be null");
        Objects.requireNonNull(currencies, "currencies must not be null - no restriction is empty");
        Objects.requireNonNull(perCurrencyMaximum, "perCurrencyMaximum must not be null");
        Objects.requireNonNull(clearingPurpose, "clearingPurpose must not be null");
        reversals = Set.copyOf(reversals);
        currencies = currencies.map(Set::copyOf);
        perCurrencyMaximum = Map.copyOf(perCurrencyMaximum);

        if (reversals.contains(Reversal.VOID)
                && interactionModel != InteractionModel.TWO_STEP) {
            throw new IllegalArgumentException(
                    "a void releases an uncaptured authorization, and only a two-step rail has"
                            + " one (ADR-0059 section 1)");
        }
        switch (refundMode) {
            case PROVIDER_REFUND -> {
                if (interactionModel != InteractionModel.TWO_STEP) {
                    throw new IllegalArgumentException(
                            "a provider refund executes against a capture, and only a two-step"
                                    + " rail captures (ADR-0059 section 1)");
                }
            }
            case RETURN_PAYMENT -> {
                if (interactionModel != InteractionModel.PUSH) {
                    throw new IllegalArgumentException(
                            "a return payment is a new push, and only a push rail can carry it"
                                    + " (ADR-0059 section 1)");
                }
            }
            case BOOK_REFUND -> {
                if (interactionModel != InteractionModel.BOOK) {
                    throw new IllegalArgumentException(
                            "a book refund is a compensating book movement, the book rail's own"
                                    + " (ADR-0059 section 1)");
                }
            }
            case NONE -> {
                if (interactionModel != InteractionModel.PUSH) {
                    throw new IllegalArgumentException(
                            "a rail that refunds nothing carries no pay-in, and only a push rail can"
                                    + " carry credits alone (ADR-0080 section 1, P9-TSK-014)");
                }
            }
        }
        if ((refundMode == RefundMode.BOOK_REFUND)
                != (interactionModel == InteractionModel.BOOK)) {
            throw new IllegalArgumentException(
                    "the book rail refunds by book movement and nothing else does (ADR-0059"
                            + " section 1)");
        }
        if ((finality == Finality.FINAL_ON_POSTING)
                != (interactionModel == InteractionModel.BOOK)) {
            throw new IllegalArgumentException(
                    "final-on-posting is the book rail's finality and nobody else's: every"
                            + " other rail has an outside decider (ADR-0059 section 1)");
        }
        if ((settlement == SettlementModel.NONE)
                != (interactionModel == InteractionModel.BOOK)) {
            throw new IllegalArgumentException(
                    "only the book rail has nothing external to settle, and it never does"
                            + " (ADR-0059 section 4, INV-SET-01)");
        }
        if (clearingPurpose.isPresent() != (settlement != SettlementModel.NONE)) {
            throw new IllegalArgumentException(
                    "a rail has a clearing position exactly when something external settles"
                            + " through it (ADR-0059 section 4, INV-RAIL-04)");
        }
        if ((disputes == DisputeModel.CARD_SCHEME_CHARGEBACKS)
                != (finality == Finality.REVOCABLE_UNTIL_DISPUTE_WINDOW_ENDS)) {
            throw new IllegalArgumentException(
                    "the dispute window is what the revocable finality IS: chargebacks without"
                            + " it, or it without them, is an incoherent declaration (ADR-0059"
                            + " section 1)");
        }
        if (outcomeDeadline.isPresent() != (interactionModel == InteractionModel.PUSH)) {
            throw new IllegalArgumentException(
                    "the outcome deadline is the push rail's bound on a final answer: a"
                            + " two-step rail's ambiguity ends only when the provider or a"
                            + " query says so, and a book rail has none (ADR-0059 section 1)");
        }
        if (outcomeDeadline.filter(deadline -> deadline.isNegative() || deadline.isZero())
                .isPresent()) {
            throw new IllegalArgumentException("an outcome deadline must be positive");
        }
        if (currencies.filter(Set::isEmpty).isPresent()) {
            throw new IllegalArgumentException(
                    "a restricted rail names its currencies; no restriction is the empty"
                            + " Optional, never the empty set");
        }
        for (Map.Entry<CurrencyCode, Money> ceiling : perCurrencyMaximum.entrySet()) {
            if (!ceiling.getValue().isPositive()) {
                throw new IllegalArgumentException(
                        "a per-currency maximum must be positive: " + ceiling.getKey().code());
            }
            if (!ceiling.getValue().currency().equals(ceiling.getKey())) {
                throw new IllegalArgumentException(
                        "a per-currency maximum is keyed by its own currency: "
                                + ceiling.getKey().code() + " does not price "
                                + ceiling.getValue().currency().code());
            }
            if (currencies.map(carried -> !carried.contains(ceiling.getKey())).orElse(false)) {
                throw new IllegalArgumentException(
                        "a restricted rail's ceilings name only currencies it carries: "
                                + ceiling.getKey().code() + " is not carried");
            }
        }
    }
}
