package com.finapp.settlement;

/**
 * What kind of counterparty statement a settlement source delivers (`P8-TSK-002`, ADR-0064).
 *
 * <p>The kind decides which format family may describe the source (each
 * {@link SettlementFormatId} names the one kind it parses) and whether the source discharges a
 * clearing position: the three report kinds each settle exactly one position
 * ({@code INV-SET-05}), while the bank statement recognises cash — its position binds when
 * {@code CASH_AT_BANK} joins the chart with its first poster (`P8-TSK-016`, ADR-0040's rule
 * that a purpose arrives with the capability needing it).
 */
public enum SourceKind {

    /** The card PSP's settlement report — hop 1 on the card rail's clearing (ADR-0065). */
    PSP_SETTLEMENT_REPORT,

    /** The instant scheme's cycle report — hop 1 on the instant rail's clearing. */
    SCHEME_CYCLE_REPORT,

    /** The payout provider's report — hop 1 on the payout clearing. */
    PAYOUT_PROVIDER_REPORT,

    /** The platform's bank statement — hop 2, cash against each attributed position. */
    BANK_STATEMENT,

    /**
     * An FX provider's trade report (`P9-TSK-011`): the cover legs it settled, per currency and
     * value date, discharging that provider's own {@code FX_PROVIDER_CLEARING} position.
     */
    FX_PROVIDER_REPORT;

    /** Whether sources of this kind settle a declared clearing position. */
    public boolean settlesAPosition() {
        return this != BANK_STATEMENT;
    }

    /** The values as a SQL literal list, for the {@code CHECK} constraint (settlement `V015`). */
    public static String sqlValueList() {
        return java.util.Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

}
