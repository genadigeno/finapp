package com.finapp.fx;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for FX quotes (`P9-TSK-008`) - on the caller's unit of work. {@code fx V005} holds the
 * plan identity, the residual bound, the window formula, the freeze, edge and live-quote cap
 * triggers for every writer beneath this port.
 */
public interface QuoteStore {

    /** A quote claim's durable half, to insert. */
    record RequestDraft(
            UUID id,
            String reference,
            String claimKey,
            UUID owner,
            PricingPurpose purpose,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            Money fixedAmount,
            PricingPolicyId version,
            String correlationId) {}

    /** A stored claim: our reference {@code QR}, the pinned version and the database's requested_at. */
    record RequestRow(
            UUID id,
            String reference,
            UUID owner,
            PricingPurpose purpose,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            Money fixedAmount,
            PricingPolicyId version,
            Instant requestedAt) {}

    /** A sourcing step's outcome - the column's closed list. */
    enum StepOutcome {
        QUOTED,
        DECLINED,
        UNAVAILABLE,
        INCOHERENT,
        IMPLAUSIBLE,
        NOTHING_SENT,
        INDETERMINATE,
        CHOSEN
    }

    /** One candidate's outcome, in the pinned version's order. */
    record Step(int position, String providerCode, int declarationVersion, StepOutcome outcome, Optional<String> detail) {
        public Step {
            Objects.requireNonNull(providerCode, "providerCode must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }

    /** Everything a quote stores, to insert - the database computes issued_at and expires_at. */
    record QuoteDraft(
            FxQuoteId id,
            RequestRow request,
            String providerCode,
            String providerQuoteReference,
            ProviderQuote providerQuote,
            LocalDate valueDate,
            Instant obtainedAt,
            RateSnapshot reference,
            PolicyPair terms,
            ConversionPlan.Plan plan,
            BigDecimal disclosedMargin,
            UUID issuedEventId,
            String correlationId) {}

    /** What inserting a quote did. */
    sealed interface Insertion permits Inserted, CapReached, WindowTooShort, AlreadyIssued {}

    /** Stored, with the database's window. */
    record Inserted(Instant issuedAt, Instant expiresAt) implements Insertion {}

    /** The owner holds the cap of live quotes (the trigger, namespace 5). Nothing stored. */
    record CapReached() implements Insertion {}

    /** Under five seconds of validity were left. Nothing stored. */
    record WindowTooShort() implements Insertion {}

    /** A quote of this request already exists - a racing flight issued it. Nothing stored. */
    record AlreadyIssued(FxQuoteId id) implements Insertion {}

    /** A quote as its owner sees it, the status effective on the database clock. */
    record QuoteRow(
            FxQuoteId id,
            UUID owner,
            PricingPurpose purpose,
            FixedSide fixedSide,
            QuoteStatus status,
            Money customerSource,
            Money customerDestination,
            ExchangeRate customerRate,
            int rateScale,
            BigDecimal disclosedMargin,
            PricingPolicyId version,
            Instant issuedAt,
            Instant expiresAt,
            String correlationId,
            UUID issuedEventId) {}

    /**
     * A quote's whole frozen plan, as the trade copies it (`P9-TSK-009`) - read under the
     * caller's row lock.
     */
    record PlanRow(
            FxQuoteId id,
            UUID owner,
            PricingPurpose purpose,
            CurrencyCode source,
            CurrencyCode destination,
            FixedSide fixedSide,
            PricingPolicyId version,
            String providerCode,
            String providerQuoteReference,
            BigDecimal customerRate,
            int sourceScale,
            int destinationScale,
            long customerSourceMinor,
            long customerDestinationMinor,
            long positionSourceMinor,
            long positionDestinationMinor,
            long marginMinor,
            long spreadMarginMinor,
            long markupMarginMinor,
            long residualMinor,
            String correlationId,
            UUID issuedEventId) {

        public Money customerSource() {
            return Money.ofPersisted(customerSourceMinor, source, sourceScale);
        }

        public Money customerDestination() {
            return Money.ofPersisted(customerDestinationMinor, destination, destinationScale);
        }

        public Money positionSource() {
            return Money.ofPersisted(positionSourceMinor, source, sourceScale);
        }

        public Money positionDestination() {
            return Money.ofPersisted(positionDestinationMinor, destination, destinationScale);
        }

        /** The computed leg's currency: where the margin and the residual arise. */
        public CurrencyCode computedCurrency() {
            return fixedSide == FixedSide.FIXED_SOURCE ? destination : source;
        }

        public int computedScale() {
            return fixedSide == FixedSide.FIXED_SOURCE ? destinationScale : sourceScale;
        }
    }

    /** A quote the sweeper expired. */
    record ExpiredRow(FxQuoteId id, CurrencyCode source, CurrencyCode destination, String correlationId, UUID issuedEventId) {}

    Optional<RequestRow> requestByClaim(Connection unitOfWork, String claimKey);

    /** Inserts the claim's request unless its claim key exists, and returns the stored row. */
    RequestRow insertRequestIfAbsent(Connection unitOfWork, RequestDraft draft);

    /** The owner's live quotes: {@code ISSUED} and not yet past expiry on the database clock. */
    int liveCount(Connection unitOfWork, UUID owner);

    /** The next sourcing attempt's number for the request (1 for the first). */
    int nextAttempt(Connection unitOfWork, UUID requestId);

    void insertSteps(Connection unitOfWork, UUID requestId, int attempt, List<Step> steps);

    List<Step> steps(Connection unitOfWork, UUID requestId, int attempt);

    Optional<FxQuoteId> quoteOfRequest(Connection unitOfWork, UUID requestId);

    /** Inserts the quote; a refusal of the cap or the window rolls back only this statement. */
    Insertion insertQuote(Connection unitOfWork, QuoteDraft draft);

    void appendEvent(
            Connection unitOfWork,
            FxQuoteId id,
            Optional<QuoteStatus> from,
            QuoteStatus to,
            String actorId,
            String actorType,
            Optional<String> detectedBy,
            String correlationId);

    /** The quote, only if {@code owner} owns it. */
    Optional<QuoteRow> findOwned(Connection unitOfWork, FxQuoteId id, UUID owner);

    /** The quote {@code FOR UPDATE}, only if {@code owner} owns it. */
    Optional<QuoteRow> lockOwned(Connection unitOfWork, FxQuoteId id, UUID owner);

    /** {@code ISSUED -> CANCELLED} while live; false when the conditional did not match. */
    boolean cancel(Connection unitOfWork, FxQuoteId id);

    /** {@code ISSUED -> ACCEPTED} while live on the database clock; false otherwise (`P9-TSK-009`). */
    boolean accept(Connection unitOfWork, FxQuoteId id);

    /**
     * {@code ISSUED -> EXPIRED} once lapsed on the database clock - an acceptance that found the
     * quote late performs the sweeper's own conditional; false when it did not match.
     */
    boolean expire(Connection unitOfWork, FxQuoteId id);

    /** {@code ACCEPTED -> EXECUTED}, admitted by the edge trigger only beside the quote's trade. */
    boolean execute(Connection unitOfWork, FxQuoteId id);

    /** The quote's whole plan - the caller holds its row lock. */
    Optional<PlanRow> plan(Connection unitOfWork, FxQuoteId id);

    /**
     * Expires up to {@code limit} lapsed quotes in one statement - {@code FOR UPDATE SKIP LOCKED}
     * and the conditional on {@code statement_timestamp()} - returning the rows it moved.
     */
    List<ExpiredRow> expirePage(Connection unitOfWork, int limit);

    /** Live quotes per pair ({@code "EUR-USD"}), for the gauge. */
    Map<String, Integer> liveByPair(Connection unitOfWork);
}
