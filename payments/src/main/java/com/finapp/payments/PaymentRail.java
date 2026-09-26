package com.finapp.payments;

import java.util.Objects;

/**
 * A declared payment rail (`P7-TSK-001`, ADR-0059 §1): its name and its capabilities, as one
 * immutable declaration.
 *
 * <h2>The declared half of a rail; the operations are the interaction model's contract</h2>
 *
 * <p>This port is deliberately <em>data</em>. What a rail can do — reverse, refund, settle,
 * bound its ambiguity, occupy a clearing position — is declared here and consulted by the
 * domain ({@code INV-RAIL-01}); <em>how</em> its operations are performed stays on the
 * interaction model's own operations contract, which for the two-step model is
 * {@link PaymentProvider}, unchanged. The composition root binds a declaration to its
 * operations (`PaymentBeans`), which is also why this is not an interface the adapter
 * implements: the wired provider bean is the metering decorator, and a capability that only
 * existed on the undecorated instance would be a capability the running system cannot see.
 * The per-model contracts the other rails need arrive with their machines (`P7-TSK-002`).
 *
 * <p>Declared beside the adapter whose operations it names — the one place a rail's name is
 * written ({@code RailVocabularyIsConfinedTest}) — and registered with {@link PaymentRails}.
 */
public record PaymentRail(RailId id, int declarationVersion, RailCapabilities capabilities) {

    public PaymentRail {
        Objects.requireNonNull(id, "a rail's id must not be null");
        Objects.requireNonNull(capabilities, "a rail's capabilities must not be null");
        if (declarationVersion < 1) {
            throw new IllegalArgumentException(
                    "a declaration version is numbered from 1: routing decisions record which"
                            + " declaration they judged (ADR-0060 section 2, P7-TSK-003), and"
                            + " an adapter bumps it when its declared capabilities change");
        }
    }
}
