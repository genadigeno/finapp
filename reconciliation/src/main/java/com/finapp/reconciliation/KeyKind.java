package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The typed references an expectation is reachable by (`P8-TSK-004`, ADR-0067 §5,
 * ADR-0068 §1): the internal key index's vocabulary, every key scoped per source under
 * {@code UNIQUE (source_id, key_kind, key_value)} — no kind exempt (the transition's A5:
 * the announced settlement cycle is an attribute on the expectation row, never a key,
 * because one cycle names many operations).
 *
 * <p>The whole decided vocabulary is stated now (rule set v1's seeded rows name it); the
 * writers arrive task by task — the card kinds and the ARN alias with `P8-TSK-004`, the
 * dispute and scheme kinds with `-005`, the payout's with `-005` (a payout <em>return</em>
 * deliberately opens no key of its own: its references are its payout's, and the
 * operation-anchored rule reaches it through the operation), the remittance's with `-009`.
 *
 * <p>{@code CARD_ATTEMPT} is an <strong>anchor</strong> kind: an alias (the ARN) resolves to
 * it, and an operation-anchored rule walks key → operation → that operation's expectation of
 * the rule's kind — the keyed expectation is only ever the anchor, never a candidate.
 */
public enum KeyKind {
    PSP_CAPTURE_REF,
    PSP_REFUND_REF,
    OUR_REF,
    CARD_ATTEMPT,
    ACQUIRER_REF,
    DISPUTE_CB_REF,
    DISPUTE_REV_REF,
    DISPUTE_FEE_REF,
    SCHEME_REF,
    END_TO_END_REF,
    PAYOUT_PROVIDER_REF,
    REMITTANCE_REF,

    /** The platform's cover reference {@code T-...}: an FX leg's key (`P9-TSK-011`). */
    COVER_REF,

    /** The FX provider's trade reference: an FX leg's alias, recorded for the trace. */
    FX_TRADE_REF;

    /** The `V002` {@code CHECK}s' value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The members of {@code members}, in declaration order, as a SQL literal list. */
    public static String sqlValueList(java.util.Set<KeyKind> members) {
        return Arrays.stream(values())
                .filter(members::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
