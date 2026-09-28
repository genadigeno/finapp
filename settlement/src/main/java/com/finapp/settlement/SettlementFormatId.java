package com.finapp.settlement;

import java.util.Objects;

/**
 * The format families settlement evidence arrives in (`P8-TSK-002`, ADR-0066 §8).
 *
 * <p>Each format parses exactly one {@link SourceKind}'s statements, and the pairing is
 * compiled here so a descriptor declaring a format for another kind is refused at construction
 * (the {@code RailCapabilities} coherence precedent). The parsers arrive with `P8-TSK-008`
 * (the PSP's) and `P8-TSK-016`…`-018` (the rest); each <em>version</em> of a format is frozen
 * by golden-file tests, and any change to a screen or a parser is a new version — never an
 * edit (the {@code RailMoneySemanticsArePinnedTest} rule applied to formats).
 */
public enum SettlementFormatId {

    /** The simulated card PSP's CSV settlement report (`P8-TSK-008`). */
    SIM_PSP_CSV(SourceKind.PSP_SETTLEMENT_REPORT),

    /** The simulated instant scheme's JSON cycle report (`P8-TSK-017`). */
    SIM_SCHEME_JSON(SourceKind.SCHEME_CYCLE_REPORT),

    /** The simulated payout provider's CSV report (`P8-TSK-018`). */
    SIM_PAYOUT_CSV(SourceKind.PAYOUT_PROVIDER_REPORT),

    /** The simulated bank's tagged statement (`P8-TSK-016`). */
    SIM_STATEMENT_TAGGED(SourceKind.BANK_STATEMENT);

    private final SourceKind kind;

    SettlementFormatId(SourceKind kind) {
        this.kind = Objects.requireNonNull(kind);
    }

    /** The one source kind this format parses. */
    public SourceKind kind() {
        return kind;
    }
}
