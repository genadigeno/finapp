package com.finapp.consent;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What processing a consent record is the lawful basis <em>for</em> (`P2-TSK-017`).
 *
 * <p><strong>A closed enumeration, deliberately</strong> (ADR-0037): a free-string purpose is a
 * vocabulary nobody controls and a check nobody can enumerate — the gate (`P2-TSK-019`) must be
 * able to ask "is this capability consent-gated, and under which purpose?" against a set the
 * compiler knows. The {@code AuditableAction} shape: adding a purpose is a reviewed act that
 * arrives with the capability it gates, never a string somebody typed at a boundary.
 *
 * <p>Two members, because Phase 2 gates two things ({@code PHASE_2_PLAN.md} §4). Phase 10's
 * bureau access ({@code INV-CRD-03}) arrives as a third member with its own consent text — the
 * plan's §Deferred names it — and a member is never removed: a purpose that stops being used
 * still names the basis of history rows that must stay interpretable ({@code INV-CNS-02}).
 *
 * <p>Persisted values, in generated {@code CHECK} constraints on both consent tables
 * ({@code P0-TSK-022}; {@code ConsentMigrationTest} reconciles).
 */
public enum ConsentPurpose {

    /** Processing identity data and documents to verify the person — the KYC case's work. */
    KYC_PROCESSING,

    /** Screening identity data against sanctions, PEP and adverse media sources. */
    SCREENING;

    /** The purposes as a SQL literal list, for the {@code CHECK} constraints. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(purpose -> "'" + purpose.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
