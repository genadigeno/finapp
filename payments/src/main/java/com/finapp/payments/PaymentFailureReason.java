package com.finapp.payments;

/**
 * The mapped, enumerated reason a {@link PaymentAttempt} is {@code FAILED} — the attempt's fact
 * ({@code PAYMENT_LIFECYCLES.md} §2–§3): the intent's {@code FAILED} carries no copy of it, and
 * the provider's own code is never here ({@code INV-PAY-03} — that lives in the retained
 * evidence, where an investigation of the provider wants it).
 *
 * <p><strong>Two values, each with a producer already recorded in shipped javadoc</strong>
 * (ADR-0044's doctrine applied to reasons — a reason nothing produces is vocabulary waiting to
 * mislead): {@link ProviderAnswer.Verdict#DECLINED} maps to {@code FAILED(DECLINED)}, and
 * {@link ProviderAnswer.Verdict#NOTHING_SENT} — a connection refused before anything was
 * transmitted, knowledge rather than ambiguity — maps to {@code FAILED(PROVIDER_UNAVAILABLE)}.
 * The callers that perform those mappings are the commands and resolvers
 * ({@code P5-TSK-009}/{@code -010}/{@code -013}/{@code -014}).
 *
 * <p><strong>Deliberately absent</strong>: a value for the sweeper resolving a stranded dispatch
 * on an explicit {@link QueryAnswer.Verdict#UNRECOGNISED} — it arrives with its producer
 * ({@code P5-TSK-014}), whose design fixes its meaning (the deliberately-few licence, the
 * audit-action precedent in {@code package-info}); and any finer decline taxonomy — reason
 * vocabulary with no consumer ({@code ProviderAnswer}'s recorded refusal).
 */
public enum PaymentFailureReason {

    /** A parsed answer: the provider refused the operation. The provider's code is evidence. */
    DECLINED,

    /**
     * The connection was refused before anything was transmitted — the operation cannot have
     * happened ({@code PAYMENT_LIFECYCLES.md} §3). Never used for a timeout: silence is
     * ambiguity and commits {@code *_UNKNOWN}, not a failure ({@code INV-LIFE-03}).
     */
    PROVIDER_UNAVAILABLE
}
