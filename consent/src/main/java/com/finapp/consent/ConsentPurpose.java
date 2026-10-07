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
 * <p>Phase 2 gates two things ({@code PHASE_2_PLAN.md} §4). Phase 10's credit data access
 * ({@code INV-CRD-03}) was reserved here as a single third member and arrived as <em>two</em>
 * ({@code P10-TSK-002}, consent {@code V003}), each with its own consent text: a bureau pull and
 * a financial-data pull are different processings, and a basis for one must never admit the
 * other - so there is deliberately no combined "credit" purpose. Members are appended, never
 * reordered, and never removed: a purpose that stops being used still names the basis of
 * history rows that must stay interpretable ({@code INV-CNS-02}; {@code ConsentMigrationTest}
 * fails the build if a purpose any migration ever admitted is gone).
 *
 * <p>Persisted values, in generated {@code CHECK} constraints on both consent tables
 * ({@code P0-TSK-022}; {@code ConsentMigrationTest} reconciles).
 */
public enum ConsentPurpose {

    /** Processing identity data and documents to verify the person — the KYC case's work. */
    KYC_PROCESSING,

    /** Screening identity data against sanctions, PEP and adverse media sources. */
    SCREENING,

    /**
     * Requesting the person's credit report from a credit bureau, to assess a credit decision
     * ({@code P10-TSK-002}, {@code INV-CRD-03}). Never admits a financial-data pull.
     */
    CREDIT_BUREAU_ACCESS,

    /**
     * Retrieving the person's account and transaction data from a financial-data provider, to
     * assess affordability for a credit decision ({@code P10-TSK-002}, {@code INV-CRD-03}).
     * Never admits a bureau pull.
     */
    FINANCIAL_DATA_ACCESS;

    /** The purposes as a SQL literal list, for the {@code CHECK} constraints. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(purpose -> "'" + purpose.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
