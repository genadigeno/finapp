package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of instrument the payment draws on or pays to (`P7-TSK-003`, ADR-0060 §1) — a
 * routing matcher and an eligibility input, never the instrument itself ({@code INV-PAY-02}:
 * the token stays behind its boundary, and routing needs only its kind).
 *
 * <p><strong>Which machine can carry which kind is a declared fact, not a rail name check</strong>
 * (`INV-RAIL-01`): a card token rides the two-step machine (authorize, then capture), a bank
 * account rides a push (the payer's PSP executes), and the wallet is the platform's own book.
 * {@link #carriedBy} is that table; the eligibility rejection
 * {@link RoutingRejection#MODEL_CANNOT_CARRY_INSTRUMENT} is its refusal.
 */
public enum InstrumentKind {
    CARD_TOKEN,
    BANK_ACCOUNT,
    WALLET;

    /** Whether this instrument kind can travel on a rail of {@code model} (ADR-0060 §3). */
    public boolean carriedBy(InteractionModel model) {
        return switch (this) {
            case CARD_TOKEN -> model == InteractionModel.TWO_STEP;
            case BANK_ACCOUNT -> model == InteractionModel.PUSH;
            case WALLET -> model == InteractionModel.BOOK;
        };
    }

    /** The values as a SQL literal list — `V013`'s `CHECK`s are generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
