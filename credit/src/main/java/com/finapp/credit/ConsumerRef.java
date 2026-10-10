package com.finapp.credit;

import java.util.Objects;
import java.util.UUID;

/**
 * The consumer of an approved credit decision (`P11-TSK-001`; ADR-0091): its kind and its own identifier - for a loan
 * agreement, the agreement's id. Credit stores the reference and never reads what it names: the consumer is another
 * context's fact, and no cross-schema foreign key binds it (ADR-0029). One consumer takes up at most one decision
 * ({@code UNIQUE (consumer_kind, consumer_ref)}), and the same consumer asking again replays its own consumption.
 *
 * @param kind what kind of consumer
 * @param ref the consumer's own identifier
 */
public record ConsumerRef(ConsumerKind kind, UUID ref) {

    public ConsumerRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(ref, "ref");
    }
}
