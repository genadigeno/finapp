package com.finapp.kyc;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Who a KYC/KYB decision's actor was ({@code INV-KYC-02}: <em>"a reviewer, or the platform
 * under a stated automatic policy"</em> — the invariant's own two cases, verbatim).
 *
 * <p>Recorded as its own column rather than inferred from {@code decided_by IS NULL}, because
 * the basis is a fact about the decision an investigator queries by ("which decisions did no
 * person look at?") and a nullable column's absence is a poor place to keep a fact. The
 * coherence between the two — a {@code REVIEWER} decision names its person, an
 * {@code AUTOMATIC} one cannot — is a {@code CHECK} in {@code V007} and a constructor
 * invariant on {@link KycDecision}, so the pair cannot disagree.
 */
public enum DecisionBasis {

    /** The platform's own act under the stated automatic policy: every check answered CLEAR. */
    AUTOMATIC,

    /** A person's judgement, named in {@code decided_by}. */
    REVIEWER;

    /** The bases as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(basis -> "'" + basis.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
