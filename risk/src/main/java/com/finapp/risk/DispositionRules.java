package com.finapp.risk;

import java.util.Objects;

/**
 * Who a disposition needs (`P13-TSK-016`; {@code PHASE_13_PLAN.md} sections 3.1, 5; ADR-0110 decision 6;
 * {@code INV-RSK-07}, {@code INV-AUD-04}) - the one place the rule is written, the domain's half of
 * {@code risk_case_disposition_four_eyes}.
 *
 * <ul>
 *   <li><strong>A second person and a taken case</strong> for every {@code CONFIRMED_*}, every {@code REFERRED}
 *       ({@link Disposition#fourEyes()}) and every disposition that sets the profile {@code PROHIBITED}
 *       ({@code prohibits}, `DESIGN-016` D3). <em>From `-017`</em> any disposition that issues an action joins them -
 *       no disposition issues one before the action machine exists (D4).</li>
 *   <li><strong>One person, from {@code OPEN} too</strong>, for {@code CLOSED_FALSE_POSITIVE} and
 *       {@code CLOSED_NO_ACTION} that prohibit nothing - decided by its own proposal (D2).</li>
 * </ul>
 *
 * <p>{@code PROHIBITED} is asked, never implied (D3): only a {@code CONFIRMED_*} disposition of a case whose subject is
 * a {@code PARTY} may carry it - an account-takeover case's confirmed takeover names its victim, and a confirmed
 * suspicion that refused the customer everywhere would tip them off ({@code INV-RSK-08}).
 */
public final class DispositionRules {

    private DispositionRules() {}

    /** True when {@code disposition} (prohibiting or not) is two persons' act. */
    public static boolean needsSecondPerson(Disposition disposition, boolean prohibits) {
        Objects.requireNonNull(disposition, "disposition");
        return disposition.fourEyes() || prohibits;
    }

    /**
     * True when {@code disposition} may only be proposed on a taken case ({@code IN_INVESTIGATION} or
     * {@code ESCALATED}) - exactly the two persons' ones.
     */
    public static boolean needsTaken(Disposition disposition, boolean prohibits) {
        return needsSecondPerson(disposition, prohibits);
    }

    /** True when {@code PROHIBITED} may be asked of {@code disposition} on a case whose subject is of {@code subject}. */
    public static boolean mayProhibit(Disposition disposition, SubjectKind subject) {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(subject, "subject");
        return disposition.confirms() && subject == SubjectKind.PARTY;
    }
}
