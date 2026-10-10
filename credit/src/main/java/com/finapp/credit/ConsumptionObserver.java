package com.finapp.credit;

/**
 * What a consumption reports (`P11-TSK-001`) - {@code app}'s {@code finapp.credit.consumption{outcome}} counter
 * implements it, so the module counts without a metrics dependency. Called <em>inside</em> the caller's transaction: an
 * implementation counts only once that transaction commits, so a rolled-back consumption counts nothing.
 */
public interface ConsumptionObserver {

    /** One consumption's answer. */
    void answered(Outcome outcome);

    /** The outcomes the counter counts - a closed set; never a decision, party, consumer or amount. */
    enum Outcome {
        CONSUMED,
        REPLAYED,
        NOT_APPROVED,
        LAPSED,
        ALREADY_CONSUMED,
        NOT_FOUND;

        /** The outcome of an answer. */
        public static Outcome of(CreditDecisionConsumptions.Consumption answer) {
            return switch (answer) {
                case CreditDecisionConsumptions.Consumption.Consumed consumed ->
                        consumed.replayed() ? REPLAYED : CONSUMED;
                case CreditDecisionConsumptions.Consumption.NotApproved ignored -> NOT_APPROVED;
                case CreditDecisionConsumptions.Consumption.Lapsed ignored -> LAPSED;
                case CreditDecisionConsumptions.Consumption.AlreadyConsumed ignored -> ALREADY_CONSUMED;
                case CreditDecisionConsumptions.Consumption.NotFound ignored -> NOT_FOUND;
            };
        }
    }

    /** Reports nothing. */
    ConsumptionObserver NONE = outcome -> { };
}
