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
 * <p><strong>Three attempt values</strong> — the sweeper's arrived with its producer exactly as
 * promised ({@code P5-TSK-014}, the deliberately-few licence): {@link #NEVER_RECEIVED} is the
 * resolution of an explicit {@link QueryAnswer.Verdict#UNRECOGNISED}, and only that — the
 * provider answering in so many words that it never saw our reference. <strong>Deliberately
 * absent still</strong>: any finer decline taxonomy — reason vocabulary with no consumer
 * ({@code ProviderAnswer}'s recorded refusal).
 */
public enum PaymentFailureReason {

    /** A parsed answer: the provider refused the operation. The provider's code is evidence. */
    DECLINED,

    /**
     * The connection was refused before anything was transmitted — the operation cannot have
     * happened ({@code PAYMENT_LIFECYCLES.md} §3). Never used for a timeout: silence is
     * ambiguity and commits {@code *_UNKNOWN}, not a failure ({@code INV-LIFE-03}).
     */
    PROVIDER_UNAVAILABLE,

    /**
     * The provider explicitly answered a resolution query that it never saw our reference
     * ({@code P5-TSK-014}: {@link QueryAnswer.Verdict#UNRECOGNISED}, the sweeper's licence) —
     * the dispatched operation never reached the provider, so failing it destroys nothing.
     * Never produced by a 404 or any other status code: only the parsed answer in so many
     * words earns this, because a misrouted load balancer resolving a live operation to
     * {@code FAILED} is the phase's most expensive mistake ({@code QueryAnswer}'s recorded
     * rule, consumed here).
     */
    NEVER_RECEIVED,

    /**
     * Risk refused the payment at its confirmation (`P13-TSK-010`; {@code PHASE_13_PLAN.md} §7.3; payments
     * {@code V034}): the <strong>intent's</strong> reason, never an attempt's - a refusal is committed in the
     * confirmation's first transaction <em>before</em> any attempt is born or anything is sent, so the intent is the
     * only row that can carry it ({@code INV-PAY-03}'s one-fact-one-place, applied to the one fact that has no attempt).
     * Terminal for the intent: {@code REQUIRES_CONFIRMATION → FAILED}, never re-confirmable - the customer's path is a
     * new intent. Internal: the customer's and the merchant's answers render the intent's generic {@code FAILED} with no
     * reason ({@code INV-RSK-08}, C6).
     */
    RISK_REFUSED;

    /** Whether an attempt may fail with this reason - every reason but {@link #RISK_REFUSED}, which is the intent's. */
    public boolean isAttemptReason() {
        return this != RISK_REFUSED;
    }

    /**
     * The attempt's reasons as a SQL literal list — the one definition of the attempt table's
     * failure-reason {@code CHECK}, reconciled by {@code PaymentsMigrationTest}
     * ({@code P5-TSK-008}; the {@code FailureReason.sqlValueList()} precedent). {@link #RISK_REFUSED} is not among
     * them (`P13-TSK-010`): the attempt's {@code CHECK} is unchanged since {@code V007}.
     */
    public static String sqlValueList() {
        return java.util.Arrays.stream(values())
                .filter(PaymentFailureReason::isAttemptReason)
                .map(reason -> "'" + reason.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /** The intent's reasons as a SQL literal list - {@code V034}'s {@code payment_intent_failure_reason_is_known}. */
    public static String intentSqlValueList() {
        return java.util.Arrays.stream(values())
                .filter(reason -> !reason.isAttemptReason())
                .map(reason -> "'" + reason.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
