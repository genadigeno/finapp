package com.finapp.paymentmethods;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PaymentMethod} rows (`P5-TSK-004`), on the caller's connection — the
 * {@code BeneficiaryStore} shape, clause for clause, because the aggregate is that shape.
 *
 * <p><strong>Attachment converges on the natural key.</strong> "Attach this instrument" means
 * the same thing however many times and however concurrently it is said, so racing and retried
 * attaches land on the one live (party, token) row — the partial unique index arbitrates, and
 * the loser is handed the winner's row rather than an error. A consequence is recorded rather
 * than discovered: a retry carrying <em>different display metadata</em> converges onto the
 * existing row and its existing metadata — the metadata comes from the tokenisation provider,
 * so divergence is a retry artefact, and updating it is detach-and-reattach, a new aggregate
 * through the freed slot, never an edit ({@code INV-LIFE-04}).
 *
 * <p><strong>Detachment is a conditional {@code UPDATE} whose row count is the outcome</strong>,
 * carrying the ownership predicate in the statement (ADR-0031): one of N concurrent detaches
 * wins, the rest converge on {@code false}, and a stranger's attempt matches nothing — the
 * caller cannot tell "not yours" from "already detached", the one-404 surface shape prepared
 * at the port.
 *
 * <p><strong>The surface reads arrived with their surface</strong> (`P5-TSK-005`, exactly as
 * this javadoc deferred them): {@code findOwned} — the DELETE's already-detached-versus-404
 * second half — and the party's live listing, the read {@code V002}'s by-party index was named
 * for (the `P4-TSK-006` → `-007` split verbatim).
 */
public interface PaymentMethodStore<T> {

    /** The attached method, and whether this call created it ({@code created = false} means
     * the caller converged onto a live row another call — possibly its own retry — created). */
    record Attachment(PaymentMethod method, boolean created) {}

    /**
     * Insert the fresh payment method, or converge onto the live row already holding its
     * (party, token) slot.
     */
    Attachment attachOrConverge(T unitOfWork, PaymentMethod fresh);

    /**
     * The live payment method for this party and token, if one stands — the converge read,
     * whose liveness predicate is generated from
     * {@link PaymentMethodStatus#sqlTerminalValueList()} so it cannot disagree with the index.
     */
    Optional<PaymentMethod> findLive(T unitOfWork, UUID partyId, TokenReference token);

    /**
     * The identified payment method in <em>any</em> status, if it is the caller's —
     * {@code party_id = ?} in the statement (ADR-0031). What the detach surface uses to tell
     * "already detached, converge on 204" from "not yours or never existed, one 404"
     * (`P5-TSK-005`) after {@link #detach} answered {@code false}.
     */
    Optional<PaymentMethod> findOwned(T unitOfWork, PaymentMethodId method, UUID partyId);

    /**
     * The party's live payment methods, oldest first — `P5-TSK-005`'s listing. Live only: a
     * detached instrument is evidence, not something to pay with.
     */
    java.util.List<PaymentMethod> listLiveFor(T unitOfWork, UUID partyId);

    /**
     * Move the identified payment method {@code ACTIVE → DETACHED}, if it is the caller's and
     * still live. {@code party_id = ?} is the ownership check, in the statement (ADR-0031); the
     * row count converges retries and refuses strangers with one indistinguishable
     * {@code false}. The HTTP caller is `P5-TSK-005`'s {@code DELETE}, which converges on 204.
     */
    boolean detach(T unitOfWork, PaymentMethodId method, UUID partyId, Instant at);
}
