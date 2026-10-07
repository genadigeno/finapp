package com.finapp.credit;

import java.util.Objects;

/**
 * The closed reason-code catalogue (ADR-0084 section 7, ADR-0086 section 2, {@code INV-CRD-02}).
 *
 * <p>A triggered policy rule names one of these; a decision carries them in rule order,
 * deduplicated keeping the first, and every adverse decision carries at least one. The customer is
 * told the adverse codes' {@link #customerText() customer texts} in order - which is why no text
 * names a score, a threshold, an attribute or a bureau's data.
 *
 * <p><strong>Mirrored, both ways.</strong> Each member is a row of {@code credit.reason_code}
 * ({@code V002}) with the same code, category, customer text and adverse flag;
 * {@code ReasonCodeCatalogueTest} holds the enum to the migration's seed and
 * {@code CreditMigrationTest} to the live rows, each in both directions. A new code is a member
 * <em>and</em> a migration in the same reviewed change. A seeded row is never updated or deleted -
 * the catalogue is part of every decision that cites it - so a member is never renamed or removed
 * either: a code no longer used stays, for replay.
 */
public enum ReasonCode {

    AGE_INELIGIBLE(ReasonCategory.ELIGIBILITY,
            "You do not meet the minimum age requirement for this product.", true),
    RESIDENCY_INELIGIBLE(ReasonCategory.ELIGIBILITY,
            "This product is not available in your country of residence.", true),
    INSOLVENCY(ReasonCategory.CREDIT_HISTORY,
            "Your credit report shows an insolvency proceeding.", true),
    PRIOR_DEFAULT(ReasonCategory.CREDIT_HISTORY,
            "Your credit report shows a default on a previous credit agreement.", true),
    RECENT_DELINQUENCY(ReasonCategory.CREDIT_HISTORY,
            "Your credit report shows recent late or missed payments.", true),
    INSUFFICIENT_CREDIT_HISTORY(ReasonCategory.CREDIT_HISTORY,
            "Your credit report does not show enough credit history for us to assess this application.", true),
    SCORE_INSUFFICIENT(ReasonCategory.SCORE,
            "Your overall credit assessment does not meet the requirements for this product.", true),
    AFFORDABILITY_INSUFFICIENT(ReasonCategory.AFFORDABILITY,
            "Your income after expenses and existing commitments is not sufficient for the repayments.", true),
    EXPOSURE_LIMIT(ReasonCategory.EXPOSURE,
            "The amount requested, together with your existing credit, exceeds the total credit we can offer you.",
            true),
    INCOME_UNVERIFIED(ReasonCategory.DATA,
            "We could not verify your income.", true),
    /** The unavailable-source fallback's code ({@code INV-CRD-10}, ADR-0085 section 6). */
    SOURCE_UNAVAILABLE(ReasonCategory.DATA,
            "We could not obtain the information we need to assess your application.", true),
    CURRENCY_NOT_SUPPORTED(ReasonCategory.DATA,
            "Some of your financial information is in a currency we cannot assess for this product.", true),
    RISK_REFERRAL(ReasonCategory.RISK,
            "Your application did not pass our internal checks.", true),
    /**
     * The auto-approval ceiling's code - a policy parameter, not a rule (PHASE_10_PLAN.md section
     * 12.6). The one non-adverse code: it caps an automated approval at the policy's automation
     * limit and says nothing about the applicant.
     */
    AUTO_APPROVAL_CEILING(ReasonCategory.POLICY,
            "The amount approved is the most we can approve automatically for this product.", false);

    private static final String PREFIX = "CRD-";

    private final ReasonCategory category;
    private final String customerText;
    private final boolean adverse;

    ReasonCode(ReasonCategory category, String customerText, boolean adverse) {
        this.category = Objects.requireNonNull(category, "category");
        this.customerText = Objects.requireNonNull(customerText, "customerText");
        if (customerText.isBlank()) {
            throw new IllegalArgumentException("a reason code's customer text must not be blank: " + name());
        }
        this.adverse = adverse;
    }

    /** The catalogue's code - {@code CRD-} and the member's name with hyphens, e.g. {@code CRD-SOURCE-UNAVAILABLE}. */
    public String code() {
        return PREFIX + name().replace('_', '-');
    }

    public ReasonCategory category() {
        return category;
    }

    /** What the customer is told - never a score, a threshold, an attribute or a bureau's data. */
    public String customerText() {
        return customerText;
    }

    /** Whether this code explains a judgement of the applicant that went against them. */
    public boolean adverse() {
        return adverse;
    }
}
