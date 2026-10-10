package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Duration;
import java.util.Objects;

/**
 * The loan products lending offers (`P11-TSK-002`; owner decision L3, {@code PHASE_11_PLAN.md} section 12.1,
 * ADR-0090 point 3).
 *
 * <p><strong>Closed, and named for credit's products.</strong> Each member is the priced, serviced counterpart of the
 * {@code CreditProduct} of the same name - {@code PERSONAL_LOAN} and {@code CREDIT_LINE} - whose decision it consumes.
 * Lending has no build edge to {@code credit} (A16), so the correspondence is by name and is checked where both types
 * are visible: {@code app}'s {@code LendingVocabularyTest} holds the two enums to one bijection, the same currency, and
 * kinds that agree with credit's term bounds (an {@link LoanKind#INSTALMENT} product's credit counterpart has a term, a
 * {@link LoanKind#REVOLVING} one has none).
 *
 * <p><strong>No amount or term bounds here, deliberately.</strong> The principal (or limit) and the term an offer
 * carries are exactly the approved decision's (A20), and the bounds a request may name are credit's
 * ({@code CreditProduct}, {@code 422 credit.AmountOutOfRange}). A second copy here would be a second truth that could
 * drift from the first; what a product declares is only what lending itself decides.
 *
 * <p><strong>Every member declares every property</strong>, checked as the class loads:
 * <ul>
 *   <li>its currency - every amount of its agreements, postings and facts is in it;</li>
 *   <li>its {@link LoanKind} - frozen on each loan at birth;</li>
 *   <li>its {@link RepaymentFrequency} - {@code MONTHLY}, the only one the engines implement;</li>
 *   <li>its offer validity default - an offer expires at the earlier of the decision's {@code valid_until} and
 *       {@code offered_at} plus this (a terms version may set its own), judged on the database clock;</li>
 *   <li>its disbursement deadline - how long a {@code PENDING_DISBURSEMENT} loan waits for a postable wallet before it
 *       is {@code CANCELLED (DISBURSEMENT_FAILED)}; it binds instalment loans only, a revolving line being born
 *       {@code ACTIVE} and drawing to the wallet per draw.</li>
 * </ul>
 */
public enum LoanProduct {

    /** The amortising instalment loan, in EUR, monthly; an offer valid 14 days, a disbursement deadline of 24 hours. */
    PERSONAL_LOAN(euro(), LoanKind.INSTALMENT, RepaymentFrequency.MONTHLY, Duration.ofDays(14), Duration.ofHours(24)),

    /** The revolving credit line, in EUR, stated monthly; an offer valid 14 days (the deadline is the shared one). */
    CREDIT_LINE(euro(), LoanKind.REVOLVING, RepaymentFrequency.MONTHLY, Duration.ofDays(14), Duration.ofHours(24));

    private final CurrencyCode currency;
    private final LoanKind kind;
    private final RepaymentFrequency frequency;
    private final Duration offerValidity;
    private final Duration disbursementDeadline;

    LoanProduct(
            CurrencyCode currency,
            LoanKind kind,
            RepaymentFrequency frequency,
            Duration offerValidity,
            Duration disbursementDeadline) {
        this.currency = Objects.requireNonNull(currency, "currency");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.frequency = Objects.requireNonNull(frequency, "frequency");
        this.offerValidity = Objects.requireNonNull(offerValidity, "offerValidity");
        this.disbursementDeadline = Objects.requireNonNull(disbursementDeadline, "disbursementDeadline");
        if (offerValidity.isNegative() || offerValidity.isZero()
                || disbursementDeadline.isNegative() || disbursementDeadline.isZero()) {
            throw new IllegalStateException(name() + ": the offer validity and the disbursement deadline must be positive");
        }
    }

    /** The product's one currency. */
    public CurrencyCode currency() {
        return currency;
    }

    /** The account kind every loan of this product is born with. */
    public LoanKind kind() {
        return kind;
    }

    /** How often its repayments fall due. */
    public RepaymentFrequency frequency() {
        return frequency;
    }

    /** How long an offer stays acceptable by default, capped by the decision's own validity. */
    public Duration offerValidity() {
        return offerValidity;
    }

    /** How long an instalment loan may wait for its disbursement before it is cancelled. */
    public Duration disbursementDeadline() {
        return disbursementDeadline;
    }

    private static CurrencyCode euro() {
        return CurrencyCode.of("EUR");
    }
}
