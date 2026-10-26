package com.finapp.kyc;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Why a {@link VerificationCheck} was asked (`P13-TSK-022`, the owner's decision O1;
 * {@code kyc V011}).
 *
 * <p><strong>{@link #ONBOARDING}</strong> - a question of the case's own verification run: the
 * evidence its decision rests on, assessed by {@link ChecksAssessment}, routed to review on a
 * hit. Every check before {@code kyc V011} is one.
 *
 * <p><strong>{@link #RESCREEN}</strong> - a sanctions or PEP screening asked again because the
 * list changed: risk's rescreening of a party under an activated list version, keyed by risk's
 * permit. <strong>Evidence beside the decision, never a re-decision</strong> (the coordinator's
 * default, to be ratified by the owner at {@code P13-DOC-001}; the {@code INV-LIFE-04}
 * amendment): it never reaches the case's assessment, never opens a review task, never moves
 * the case or the customer - kyc's onboarding readers do not see it ({@link CheckStore#forCase}),
 * and the callback door does not re-assess on it. Its answer is risk's to act on: a hit is
 * risk's {@code SCREENING_HIT} alert, folded into the party's {@code AML} case, which a person
 * works.
 */
public enum CheckPurpose {
    ONBOARDING,
    RESCREEN;

    /** The quoted, comma-separated value list the migration's {@code CHECK} constraint uses. */
    public static String sqlValueList() {
        return Stream.of(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
