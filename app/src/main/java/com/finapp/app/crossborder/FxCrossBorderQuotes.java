package com.finapp.app.crossborder;

import com.finapp.crossborder.CrossBorderFx;
import com.finapp.fx.FixedSide;
import com.finapp.fx.FxQuoteId;
import com.finapp.fx.PricingPurpose;
import com.finapp.fx.QuoteIssuance;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.fx.QuoteRefusal;
import com.finapp.fx.QuoteStore;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * crossborder's {@link CrossBorderFx} over fx's {@code QuoteIssuance} and {@code QuoteLifecycle}
 * (`P9-TSK-018`, PHASE_9_PLAN.md section 12.3): fx's claim, firm quote and issue for a {@code CROSS_BORDER}
 * quote, run under crossborder's claim key on crossborder's connection. fx's refusals cross as their code's
 * name; the handles wrap fx's own outcomes, opaque to crossborder.
 */
public final class FxCrossBorderQuotes implements CrossBorderFx {

    private final QuoteIssuance issuance;
    private final QuoteLifecycle lifecycle;
    private final com.finapp.fx.CrossBorderAcceptance acceptance;
    private final com.finapp.fx.CoverDispatchNudge covers;

    public FxCrossBorderQuotes(
            QuoteIssuance issuance,
            QuoteLifecycle lifecycle,
            com.finapp.fx.CrossBorderAcceptance acceptance,
            com.finapp.fx.CoverDispatchNudge covers) {
        this.issuance = Objects.requireNonNull(issuance, "issuance must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance must not be null");
        this.covers = Objects.requireNonNull(covers, "covers must not be null");
    }

    @Override
    public Accepted acceptWithin(
            Connection unitOfWork, UUID quoteId, UUID owner, UUID payment, Actor actor, CorrelationId correlation) {
        try {
            com.finapp.fx.CrossBorderAcceptance.Accepted accepted =
                    acceptance.acceptWithin(unitOfWork, FxQuoteId.of(quoteId), owner, payment, actor, correlation);
            return new Accepted(accepted.coverId(), accepted.customerPays(), accepted.customerReceives(), accepted.sourceWallet());
        } catch (com.finapp.fx.ConversionRefusal.Refused refused) {
            throw new FxRefused(refused.refusal().code().name());
        }
    }

    @Override
    public void dispatchCover(UUID coverId) {
        // fx's post-commit nudge: the platform's own trade, sent as the platform (FxCoverNudge.send).
        covers.nudge(coverId);
    }

    private record FxClaim(QuoteIssuance.Claimed claimed) implements Claim {}

    private record FxSourcing(QuoteIssuance.Sourced sourced) implements Sourcing {}

    @Override
    public Claim begin(Connection unitOfWork, String claimKey, Ask ask, CorrelationId correlation) {
        try {
            return new FxClaim(issuance.claim(unitOfWork, claimKey, new QuoteIssuance.QuoteRequest(
                    ask.owner(), ask.source(), ask.destination(),
                    ask.fixedSource() ? FixedSide.FIXED_SOURCE : FixedSide.FIXED_DESTINATION, ask.amount(),
                    PricingPurpose.CROSS_BORDER), correlation));
        } catch (QuoteRefusal.Refused refused) {
            throw new FxRefused(refused.refusal().code().name());
        }
    }

    @Override
    public Sourcing firmQuote(Claim claim) {
        return new FxSourcing(issuance.source(((FxClaim) claim).claimed()));
    }

    @Override
    public Quote issue(Connection unitOfWork, Claim claim, Sourcing sourcing, Actor actor, CorrelationId correlation) {
        try {
            return figures(issuance.issue(unitOfWork, ((FxClaim) claim).claimed(), ((FxSourcing) sourcing).sourced(), actor,
                    correlation).quote());
        } catch (QuoteRefusal.Refused refused) {
            throw new FxRefused(refused.refusal().code().name());
        }
    }

    @Override
    public Optional<Quote> read(Connection unitOfWork, UUID id, UUID owner) {
        return lifecycle.read(unitOfWork, FxQuoteId.of(id), owner).map(FxCrossBorderQuotes::figures);
    }

    private static Quote figures(QuoteStore.QuoteRow row) {
        return new Quote(row.id().value(), row.owner(), row.status().name(), row.fixedSide() == FixedSide.FIXED_SOURCE,
                row.customerSource(), row.customerDestination(), row.customerRate().value(), row.disclosedMargin(),
                row.issuedAt(), row.expiresAt());
    }
}
