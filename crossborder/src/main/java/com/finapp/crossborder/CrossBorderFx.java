package com.finapp.crossborder;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * fx's quote, as crossborder needs it (`P9-TSK-018`, ADR-0079; PHASE_9_PLAN.md section 3's ports table and
 * section 12.3) - declared here, implemented in {@code app} over fx's {@code QuoteIssuance}, running fx's
 * steps under crossborder's claim, on crossborder's connection. crossborder never sees fx's types: the claim
 * and the sourcing are opaque handles, the quote comes back as its frozen figures, and a refusal carries fx's
 * code by name for the door to answer.
 */
public interface CrossBorderFx {

    /**
     * Tx1: fx's claim for a {@code CROSS_BORDER} quote - the pricing version pinned, the pair, bounds and cap
     * judged, fx's request stored - on the caller's unit of work, keyed by {@code claimKey}.
     *
     * @throws FxRefused for each refusal; nothing is stored by a refused claim
     */
    Claim begin(Connection unitOfWork, String claimKey, Ask ask, CorrelationId correlation);

    /** The wire: each candidate provider asked for a firm quote - no connection held. */
    Sourcing firmQuote(Claim claim);

    /**
     * Tx2: fx's quote inserted in the caller's unit of work - the pinned pricing version re-checked, the plan
     * computed, the expiry bounded - or the quote a racing flight issued.
     *
     * @throws FxRefused for each refusal
     */
    Quote issue(Connection unitOfWork, Claim claim, Sourcing sourcing, Actor actor, CorrelationId correlation);

    /** {@code owner}'s quote {@code id}, if it is theirs. */
    Optional<Quote> read(Connection unitOfWork, UUID id, UUID owner);

    /**
     * The payment's acceptance (`P9-TSK-019`, PHASE_9_PLAN.md section 12.8): the quote locked and judged -
     * the owner's, {@code CROSS_BORDER}, {@code ISSUED}, unexpired on the database clock - moved
     * {@code ACCEPTED}, and its cover born {@code DISPATCHED}, in the caller's unit of work, under the caller's
     * claim. No trade and no posting: the hold is the customer's whole effect until completion.
     *
     * @throws FxRefused for each refusal
     */
    Accepted acceptWithin(Connection unitOfWork, UUID quoteId, UUID owner, UUID payment, Actor actor, CorrelationId correlation);

    /**
     * Asks for the accepted quote's cover to be sent now, off the request thread and as the platform - a
     * hint, never the guarantee: the cover row, born with its reference and permit, is, and the cover sweep
     * sends whatever a hint drops.
     */
    void dispatchCover(UUID coverId);

    /** An accepted quote: its cover, the plan's amounts and the wallet the customer pays from. */
    record Accepted(UUID coverId, Money customerPays, Money customerReceives, com.finapp.ledger.LedgerAccountId sourceWallet) {
        public Accepted {
            Objects.requireNonNull(coverId, "coverId must not be null");
            Objects.requireNonNull(customerPays, "customerPays must not be null");
            Objects.requireNonNull(customerReceives, "customerReceives must not be null");
            Objects.requireNonNull(sourceWallet, "sourceWallet must not be null");
        }
    }

    /** What is asked: who, the pair, which side is fixed, and the fixed amount. */
    record Ask(UUID owner, CurrencyCode source, CurrencyCode destination, boolean fixedSource, Money amount) {
        public Ask {
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(source, "source must not be null");
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            if (!amount.currency().equals(fixedSource ? source : destination)) {
                throw new IllegalArgumentException("the fixed amount is in the fixed side's currency");
            }
        }
    }

    /** fx's claim - opaque to crossborder. */
    interface Claim {}

    /** The wire's outcome - opaque to crossborder. */
    interface Sourcing {}

    /** An issued quote's frozen figures - what the customer pays and receives, at which rate, until when. */
    record Quote(
            UUID id,
            UUID owner,
            String status,
            boolean fixedSource,
            Money customerPays,
            Money customerReceives,
            BigDecimal customerRate,
            BigDecimal disclosedMarginOverMid,
            Instant issuedAt,
            Instant expiresAt) {
        public Quote {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(customerPays, "customerPays must not be null");
            Objects.requireNonNull(customerReceives, "customerReceives must not be null");
            Objects.requireNonNull(customerRate, "customerRate must not be null");
            Objects.requireNonNull(disclosedMarginOverMid, "disclosedMarginOverMid must not be null");
            Objects.requireNonNull(issuedAt, "issuedAt must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    /** fx refused - {@code code} is fx's error code by name, answered by the door. */
    final class FxRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String code;

        public FxRefused(String code) {
            super("fx refused the quote: " + code);
            this.code = Objects.requireNonNull(code, "code must not be null");
        }

        public String code() {
            return code;
        }
    }
}
