package com.finapp.paymentmethods;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The scheme directory's confirmation-of-payee result, as stored with a
 * {@code BANK_ACCOUNT} instrument (`P7-TSK-007`, ADR-0062 §2) — one of the exactly three
 * values the platform may keep from a grant exchange ({@code INV-RAIL-03}).
 *
 * <p>The rail port's four words ({@code ExchangeAnswer.ConfirmationOfPayee}), restated rather
 * than imported: this module sees no business sibling (the PCI build-graph decision
 * {@link TokenReference} records), so the {@code app} layer maps the port's word onto this one
 * exhaustively. The words themselves are the platform's, not any one scheme's — an adapter
 * translates its scheme's vocabulary before the answer crosses the port.
 *
 * <p>{@link #NO_MATCH} is special by ADR-0062 §2's own sentence: recording it requires the
 * customer's explicit acknowledgement, carried as the aggregate's
 * {@code noMatchAcknowledgedAt} instant and bound both ways by {@code V003}'s {@code CHECK} —
 * an unacknowledged {@code NO_MATCH} instrument cannot exist, for any writer. Risk scoring of
 * the result is Phase 13's, deliberately (the backlog's own out-of-scope line).
 */
public enum PayeeCheck {

    /** The account holder's name matched the payee the customer named. */
    MATCH,

    /** A near match — the scheme's own judgement of "close", stored verbatim as a word. */
    CLOSE_MATCH,

    /** No match: storable only with the customer's recorded acknowledgement. */
    NO_MATCH,

    /** The directory could not answer; the instrument is registered on the customer's say. */
    UNAVAILABLE;

    /** The values as a SQL literal list — {@code V003}'s payee-check {@code CHECK} is
     * generated from this, reconciled by {@code PaymentMethodMigrationTest}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
