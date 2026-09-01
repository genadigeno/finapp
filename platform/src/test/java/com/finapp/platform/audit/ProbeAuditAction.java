package com.finapp.platform.audit;

/**
 * Actions a test performs, standing in for the ones business modules will declare.
 *
 * <p>In test sources deliberately. The registry guard scans production classes only, so these do
 * not enter the catalogue — and {@code AuditableActionRegistryTest.theGuardSeesRealContent}
 * asserts the registry holds the platform's real actions, which would fail if test constants
 * leaked into it.
 *
 * <p>They are named for actions real modules will genuinely have, rather than {@code PROBE_ONE}
 * and {@code PROBE_TWO}, because the writer's tests read as documentation of what an audit record
 * is for and "kyc.CaseApproved" carries that where a placeholder would not.
 */
enum ProbeAuditAction implements AuditableAction {

    KYC_CASE_APPROVED("kyc.CaseApproved", "A reviewer approved a KYC case.", false),

    /** Requires a reason, so the enforcement in {@link AuditRecord} has something to enforce. */
    BREAK_RESOLVED(
            "reconciliation.BreakResolved",
            "An operator resolved a reconciliation break with a compensating entry.",
            true),

    MANUAL_ADJUSTMENT_REQUESTED(
            "ledger.ManualAdjustmentRequested", "An operator requested a manual adjustment.", true),

    CONCURRENT_PROBE("audit.ConcurrentProbe", "Used by the concurrency test.", false);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    ProbeAuditAction(String code, String description, boolean requiresReason) {
        this.code = code;
        this.description = description;
        this.requiresReason = requiresReason;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
