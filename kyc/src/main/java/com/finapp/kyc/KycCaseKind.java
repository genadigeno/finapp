package com.finapp.kyc;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which variant of verification a case is: a person's ({@code KYC}) or an organisation's
 * ({@code KYB}) (`P2-TSK-015`).
 *
 * <p><strong>The kind is not the model — the ownership graph is.</strong> The glossary is
 * explicit that a KYB case is <em>"not a KYC Case with a flag set"</em>, and what makes that
 * true here is everything the kind arms rather than the kind itself: the
 * {@code kyc.beneficial_owner} rows a {@code KYB} case owns, the composite foreign keys that
 * let owner rows attach only to a {@code KYB} case and let a verification reference name only
 * a {@code KYC} case, and the ownership predicate on every transition into
 * {@code READY_FOR_DECISION}. The <em>lifecycle</em> is deliberately shared —
 * {@code PHASE_2_PLAN.md} §5: "one machine; KYB adds the ownership precondition to
 * decisioning" — so a second aggregate class or table would fork the phase's proven spine for
 * no structural gain.
 *
 * <p>Fixed at open, like the policy version ({@code INV-HIST-04}'s reasoning): which regime a
 * case is verified under is a fact about the case, and V008 narrows the {@code UPDATE} grant
 * so the column cannot be rewritten by the application role — a {@code KYB → KYC} flip would
 * silently disarm the ownership gate.
 *
 * <p>Persisted values, in a generated {@code CHECK} constraint ({@code P0-TSK-022};
 * {@code KycCaseMigrationTest} reconciles).
 */
public enum KycCaseKind {

    /** A natural person's verification. May be referenced as an owner's verification case. */
    KYC,

    /**
     * An organisation's verification: additionally owns the beneficial-ownership graph, and
     * cannot reach {@code READY_FOR_DECISION} until the graph is declared and every owner's
     * verification is terminal.
     */
    KYB;

    /** The kinds as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(kind -> "'" + kind.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
