package com.finapp.risk;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

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
 *       {@code CLOSED_NO_ACTION} that prohibit nothing - decided by its own proposal (D2) - <em>unless the case holds
 *       an alert</em>.</li>
 *   <li><strong>A second person and a taken case for a {@code CLOSED_*} of a case holding an alert</strong> (owner
 *       item 78, fixed at the Phase 13 gate, 2026-10-29): closing such a case without action dismisses its alerts,
 *       which is what the alert's own four-eyes suppression controls ({@code risk_alert_suppression_four_eyes}) - one
 *       {@code RISK_INVESTIGATE} holder opening a case on an open alert and closing it alone reached it. Every kind but
 *       {@link CaseKind#COLLECTIONS}, whose one-person {@code CLOSED_NO_ACTION} is the ratified C3 (a default is a
 *       fact, never a suspicion to suppress). {@code risk V019}'s {@code risk_case_alerts_closed_by_two} holds the same
 *       for every writer.</li>
 * </ul>
 *
 * <p>{@code PROHIBITED} is asked, never implied (D3): only a {@code CONFIRMED_*} disposition of a case whose subject is
 * a {@code PARTY} may carry it - an account-takeover case's confirmed takeover names its victim, and a confirmed
 * suspicion that refused the customer everywhere would tip them off ({@code INV-RSK-08}).
 */
public final class DispositionRules {

    private DispositionRules() {}

    /** True when {@code disposition} (prohibiting or not) is two persons' act whatever the case holds. */
    public static boolean needsSecondPerson(Disposition disposition, boolean prohibits) {
        Objects.requireNonNull(disposition, "disposition");
        return disposition.fourEyes() || prohibits;
    }

    /**
     * True when {@code disposition} of a case of {@code kind} that does or does not hold an alert is two persons' act -
     * the rule above, and a {@code CLOSED_*} of a case holding an alert (item 78), {@code COLLECTIONS} excepted (C3).
     */
    public static boolean needsSecondPerson(Disposition disposition, boolean prohibits, CaseKind kind,
            boolean holdsAlerts) {
        Objects.requireNonNull(kind, "kind");
        return needsSecondPerson(disposition, prohibits) || (holdsAlerts && kind != CaseKind.COLLECTIONS);
    }

    /**
     * True when {@code disposition} may only be proposed on a taken case ({@code IN_INVESTIGATION} or
     * {@code ESCALATED}) - exactly the two persons' ones.
     */
    public static boolean needsTaken(Disposition disposition, boolean prohibits) {
        return needsSecondPerson(disposition, prohibits);
    }

    /** True when the disposition, on a case of {@code kind} holding alerts or not, needs a taken case - item 78's too. */
    public static boolean needsTaken(Disposition disposition, boolean prohibits, CaseKind kind, boolean holdsAlerts) {
        return needsSecondPerson(disposition, prohibits, kind, holdsAlerts);
    }

    /** True when {@code PROHIBITED} may be asked of {@code disposition} on a case whose subject is of {@code subject}. */
    public static boolean mayProhibit(Disposition disposition, SubjectKind subject) {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(subject, "subject");
        return disposition.confirms() && subject == SubjectKind.PARTY;
    }

    /** The dispositions a {@link CaseKind#COLLECTIONS} case takes (`P13-TSK-025`, C3) - facts, never a finding. */
    public static final Set<Disposition> COLLECTIONS = EnumSet.of(Disposition.CLOSED_NO_ACTION, Disposition.REFERRED);

    /**
     * True when a case of {@code kind} may be disposed {@code disposition} - every disposition for every kind but
     * {@link CaseKind#COLLECTIONS} (`P13-TSK-025`, owner decision C3), which takes {@code CLOSED_NO_ACTION} (worked, or
     * cured: nothing to do - one person, from {@code OPEN} too) and {@code REFERRED} (to the owning domain's own
     * restructuring or write-off path - a recommendation, never an action; two persons). A default is a fact: never a
     * false positive, never a fraud or a suspicion confirmed - and so never {@code PROHIBITED}. {@code risk V017}'s
     * {@code risk_case_collections_disposed_by_facts} holds the same for every writer.
     */
    public static boolean admitted(CaseKind kind, Disposition disposition) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(disposition, "disposition");
        return kind != CaseKind.COLLECTIONS || COLLECTIONS.contains(disposition);
    }
}
