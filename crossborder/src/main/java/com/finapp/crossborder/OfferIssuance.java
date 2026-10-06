package com.finapp.crossborder;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The cross-border offer (`P9-TSK-018`, PHASE_9_PLAN.md section 12.3; {@code INV-XB-02}, {@code INV-XB-03},
 * {@code INV-HIST-04}): fx's {@code CROSS_BORDER} quote and crossborder's frozen terms beside it.
 *
 * <h2>The sequence</h2>
 *
 * <p>{@link #claim} (Tx1, beside the door's idempotency claim): the beneficiary read {@code FOR SHARE} and
 * payable - the owner's, {@code ACTIVE}, clear - on an available corridor its issuing rail serves, under the
 * pinned corridor version; the destination limit when the destination is fixed; a re-screen requested in the
 * same unit of work when the clearance has lapsed; the request stored; fx's claim on the same connection.
 * Between the transactions: {@link #rescreen} and {@link #judgeRescreen} (kyc decides in its own T-e, moving
 * the beneficiary on a hit), then {@link #firmQuote} - no connection held. {@link #complete} (Tx2): the
 * corridor version re-checked, fx's quote and the offer inserted together, the fee computed once and frozen.
 */
@RequiredArgsConstructor
public final class OfferIssuance {

    @NonNull private final OfferStore offers;
    @NonNull private final BeneficiaryStore beneficiaries;
    @NonNull private final CorridorPolicyStore policies;
    @NonNull private final CorridorAvailabilityStore availability;
    @NonNull private final CounterpartyScreening screening;
    @NonNull private final CrossBorderFx fx;
    @NonNull private final com.finapp.sharedkernel.id.IdGenerator ids;

    /** What a customer asks: their beneficiary, the source currency, which side is fixed, and its amount. */
    public record Ask(UUID owner, BeneficiaryId beneficiary, CurrencyCode source, boolean fixedSource, Money amount) {
        public Ask {
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(beneficiary, "beneficiary must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (!amount.isPositive()) {
                throw new IllegalArgumentException("an offer is for a positive amount");
            }
        }
    }

    /** Tx1's outcome. */
    public record Claimed(OfferStore.RequestRow request, CorridorTerms terms, CrossBorderFx.Claim fxClaim) {}

    /** An offer and fx's quote beside it. */
    public record Offered(OfferStore.OfferRow offer, CrossBorderFx.Quote quote) {}

    /** A refusal with crossborder's code. */
    public static final class OfferRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final CrossborderErrorCode code;

        OfferRefused(CrossborderErrorCode code) {
            super(code.code());
            this.code = code;
        }

        public CrossborderErrorCode code() {
            return code;
        }
    }

    // ------------------------------------------------------------------ Tx1

    /**
     * The claim's work. A takeover by the same key converges on the stored request, its pinned corridor
     * version and its re-screen; payability is judged again on every flight.
     *
     * @throws OfferRefused {@code BENEFICIARY_NOT_PAYABLE} (one answer for every non-payable state, an unknown
     *     or another's beneficiary included), {@code CORRIDOR_NOT_OFFERED}, {@code AMOUNT_EXCEEDS_CORRIDOR_LIMIT}
     * @throws CrossBorderFx.FxRefused for fx's refusals
     */
    public Claimed claim(Connection unitOfWork, String claimKey, Ask ask, Instant now, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(claimKey, "claimKey must not be null");
        Objects.requireNonNull(ask, "ask must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        BeneficiaryStore.BeneficiaryRow beneficiary = beneficiaries.lockOwnedForShare(unitOfWork, ask.beneficiary(), ask.owner())
                .filter(row -> row.status() == BeneficiaryStatus.ACTIVE)
                .orElseThrow(() -> new OfferRefused(CrossborderErrorCode.BENEFICIARY_NOT_PAYABLE));
        Optional<OfferStore.RequestRow> existing = offers.requestByClaim(unitOfWork, claimKey);
        CorridorPolicyStore.VersionView version = (existing.isPresent()
                        ? policies.version(unitOfWork, existing.get().corridorPolicy())
                        : policies.active(unitOfWork))
                .orElseThrow(() -> new OfferRefused(CrossborderErrorCode.CORRIDOR_NOT_OFFERED));
        CorridorKey corridor;
        try {
            corridor = new CorridorKey(ask.source(), beneficiary.currency(), beneficiary.country());
        } catch (IllegalArgumentException sameCurrency) {
            throw new OfferRefused(CrossborderErrorCode.CORRIDOR_NOT_OFFERED);
        }
        CorridorTerms terms = version.corridors().stream()
                .filter(candidate -> candidate.key().equals(corridor))
                .findFirst()
                .filter(candidate -> candidate.rails().contains(beneficiary.rail()))
                .orElseThrow(() -> new OfferRefused(CrossborderErrorCode.CORRIDOR_NOT_OFFERED));
        if (!availability.isAvailable(unitOfWork, corridor)) {
            throw new OfferRefused(CrossborderErrorCode.CORRIDOR_NOT_OFFERED);
        }
        Money amount = ask.amount();
        if (!amount.currency().equals(ask.fixedSource() ? corridor.source() : corridor.destination())) {
            throw new IllegalArgumentException("the fixed amount is in the fixed side's currency");
        }
        if (!ask.fixedSource() && amount.compareTo(terms.maximum()) > 0) {
            throw new OfferRefused(CrossborderErrorCode.AMOUNT_EXCEEDS_CORRIDOR_LIMIT);
        }
        CounterpartyScreening.Clearance clearance = screening.clearance(unitOfWork, beneficiary.screeningId())
                .filter(CounterpartyScreening.Clearance::clears)
                .orElseThrow(() -> new OfferRefused(CrossborderErrorCode.BENEFICIARY_NOT_PAYABLE));
        OfferStore.RequestRow request = existing.orElseGet(() -> {
            UUID id = ids.next();
            boolean lapsed = clearance.decidedAt().map(at -> !at.plus(terms.screeningValidity()).isAfter(now)).orElse(true);
            Optional<UUID> rescreen = lapsed
                    ? Optional.of(screening.rescreenWithin(unitOfWork, beneficiary.screeningId(),
                            Beneficiaries.rescreenReference(beneficiary.id(), id)))
                    : Optional.empty();
            OfferStore.RequestRow fresh = new OfferStore.RequestRow(id, claimKey, ask.owner(), beneficiary.id(),
                    version.row().id(), corridor, ask.fixedSource(), amount, rescreen, now);
            return offers.insertRequest(unitOfWork, fresh)
                    ? fresh
                    : offers.requestByClaim(unitOfWork, claimKey).orElseThrow(() -> new CrossborderStorageException(
                            "an offer request was refused as a duplicate but none is visible; retry", null));
        });
        CrossBorderFx.Claim fxClaim = fx.begin(unitOfWork, claimKey,
                new CrossBorderFx.Ask(ask.owner(), corridor.source(), corridor.destination(), ask.fixedSource(), amount),
                correlation);
        return new Claimed(request, terms, fxClaim);
    }

    // ------------------------------------------------------------------ between the transactions

    /** Asks kyc for the re-screen the claim requested, if any - no connection held. */
    public void rescreen(Claimed claimed, CorrelationId correlation) {
        Objects.requireNonNull(claimed, "claimed must not be null");
        claimed.request().rescreen().ifPresent(id -> screening.screenNow(id, correlation));
    }

    /**
     * After the re-screen: the beneficiary still {@code ACTIVE} and the re-screen clear, or the quote refused
     * - {@code BENEFICIARY_NOT_PAYABLE} when kyc moved the beneficiary (a hit, an indeterminate answer, an
     * unverified payee), {@code SCREENING_UNAVAILABLE} when the provider could not be asked.
     */
    public void judgeRescreen(Connection unitOfWork, Claimed claimed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(claimed, "claimed must not be null");
        Optional<UUID> rescreen = claimed.request().rescreen();
        if (rescreen.isEmpty()) {
            return;
        }
        CounterpartyScreening.Clearance clearance = screening.clearance(unitOfWork, rescreen.get())
                .orElseThrow(() -> new IllegalStateException("a requested re-screen is always readable"));
        if (clearance.unanswered()) {
            throw new OfferRefused(CrossborderErrorCode.SCREENING_UNAVAILABLE);
        }
        boolean active = beneficiaries.lockOwnedForShare(unitOfWork, claimed.request().beneficiary(), claimed.request().owner())
                .filter(row -> row.status() == BeneficiaryStatus.ACTIVE)
                .isPresent();
        if (!clearance.clears() || !active) {
            throw new OfferRefused(CrossborderErrorCode.BENEFICIARY_NOT_PAYABLE);
        }
    }

    /** fx's firm quote - no connection held. */
    public CrossBorderFx.Sourcing firmQuote(Claimed claimed) {
        Objects.requireNonNull(claimed, "claimed must not be null");
        return fx.firmQuote(claimed.fxClaim());
    }

    // ------------------------------------------------------------------ Tx2

    /**
     * Records fx's quote and the frozen offer together - or the offer a racing flight recorded.
     *
     * @throws OfferRefused {@code POLICY_STALE} when the pinned corridor version was superseded;
     *     {@code AMOUNT_EXCEEDS_CORRIDOR_LIMIT} when a fixed source buys more than the corridor's maximum
     * @throws CrossBorderFx.FxRefused for fx's refusals
     */
    public Offered complete(
            Connection unitOfWork, Claimed claimed, CrossBorderFx.Sourcing sourcing, Actor actor, Instant now,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(claimed, "claimed must not be null");
        Objects.requireNonNull(sourcing, "sourcing must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        OfferStore.RequestRow request = claimed.request();
        Optional<OfferStore.OfferRow> racing = offers.offerOfRequest(unitOfWork, request.id());
        if (racing.isPresent()) {
            return new Offered(racing.get(), fx.read(unitOfWork, racing.get().quoteId(), request.owner())
                    .orElseThrow(() -> new IllegalStateException("an offer's quote always reads back")));
        }
        boolean stillActive = policies.active(unitOfWork)
                .map(active -> active.row().id().equals(request.corridorPolicy()))
                .orElse(false);
        if (!stillActive) {
            throw new OfferRefused(CrossborderErrorCode.POLICY_STALE);
        }
        CrossBorderFx.Quote quote = fx.issue(unitOfWork, claimed.fxClaim(), sourcing, actor, correlation);
        CorridorTerms terms = claimed.terms();
        if (quote.customerReceives().compareTo(terms.maximum()) > 0) {
            throw new OfferRefused(CrossborderErrorCode.AMOUNT_EXCEEDS_CORRIDOR_LIMIT);
        }
        Money fee = fee(terms, quote.customerPays());
        OfferStore.OfferRow offer = new OfferStore.OfferRow(ids.next(), request.id(), quote.id(), request.owner(),
                request.beneficiary(), request.corridorPolicy(), request.corridor(), fee, quote.customerPays(),
                quote.customerPays().plus(fee), quote.customerReceives(), Math.toIntExact(terms.deliveryEstimate().toHours()), now);
        offers.insertOffer(unitOfWork, offer);
        return new Offered(offer, quote);
    }

    /** The corridor's fee, computed once: the fixed amount plus the margin on the source, under the named rounding. */
    public static Money fee(CorridorTerms terms, Money customerPays) {
        Objects.requireNonNull(terms, "terms must not be null");
        Objects.requireNonNull(customerPays, "customerPays must not be null");
        Money proportional = Money.of(customerPays.toBigDecimal().multiply(terms.feeMargin()), customerPays.currency(),
                terms.feeRounding());
        return terms.feeFixed().plus(proportional);
    }

    // ------------------------------------------------------------------ reads

    /** The destination currency of {@code owner}'s beneficiary {@code id} - what a fixed destination is stated in. */
    public Optional<CurrencyCode> destinationOf(Connection unitOfWork, BeneficiaryId id, UUID owner) {
        return beneficiaries.findOwned(unitOfWork, id, owner).map(BeneficiaryStore.BeneficiaryRow::currency);
    }

    /** {@code owner}'s offer on quote {@code quoteId}, with fx's quote beside it. */
    public Optional<Offered> read(Connection unitOfWork, UUID quoteId, UUID owner) {
        return offers.offerOwned(unitOfWork, quoteId, owner)
                .flatMap(offer -> fx.read(unitOfWork, quoteId, owner).map(quote -> new Offered(offer, quote)));
    }
}
