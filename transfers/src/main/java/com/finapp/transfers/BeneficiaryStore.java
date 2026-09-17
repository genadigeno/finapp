package com.finapp.transfers;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Beneficiary} rows ({@code P4-TSK-006}), on the caller's connection —
 * the store joins whatever transaction commands it (ADR-0033's discipline throughout this
 * module).
 *
 * <p><strong>Creation converges on the natural key.</strong> "Save this destination" means the
 * same thing however many times and however concurrently it is said, so racing and retried
 * creates land on the one live (party, destination) row — the partial unique index arbitrates,
 * and the loser is handed the winner's row rather than an error. A consequence is recorded
 * rather than discovered: a retry carrying a <em>different display name</em> converges onto the
 * existing row and its existing name — renaming a saved destination is remove-and-recreate, a
 * new aggregate through the freed slot, never an edit ({@code INV-LIFE-04}).
 *
 * <p><strong>Removal is a conditional {@code UPDATE} whose row count is the outcome</strong>,
 * carrying the ownership predicate in the statement (ADR-0031): one of N concurrent removals
 * wins, the rest converge on {@code false}, and a stranger's attempt matches nothing — the
 * caller cannot tell "not yours" from "already removed", which is {@code P4-TSK-007}'s one-404
 * surface shape prepared at the port.
 */
public interface BeneficiaryStore<T> {

    /** The saved beneficiary, and whether this call created it ({@code created = false} means
     * the caller converged onto a live row another call — possibly its own retry — created). */
    record Creation(Beneficiary beneficiary, boolean created) {}

    /**
     * Insert the fresh beneficiary, or converge onto the live row already holding its
     * (party, destination) slot.
     */
    Creation createOrConverge(T unitOfWork, Beneficiary fresh);

    /**
     * The live beneficiary for this party and destination, if one stands — the converge read,
     * whose liveness predicate is generated from
     * {@link BeneficiaryStatus#sqlTerminalValueList()} so it cannot disagree with the index.
     */
    Optional<Beneficiary> findLive(T unitOfWork, UUID partyId, UUID destinationAccountId);

    /**
     * The identified beneficiary in <em>any</em> status, if it is the caller's —
     * {@code party_id = ?} in the statement (ADR-0031). What the removal surface uses to tell
     * "already removed, converge on 204" from "not yours or never existed, one 404"
     * (`P4-TSK-007`) after {@link #remove} answered {@code false}.
     */
    Optional<Beneficiary> findOwned(T unitOfWork, BeneficiaryId beneficiary, UUID partyId);

    /**
     * The party's live beneficiaries, oldest first — `P4-TSK-007`'s listing, the read
     * {@code V003}'s {@code beneficiary_by_party} index was named for. Live only: a removed
     * destination is evidence, not an address-book entry.
     */
    java.util.List<Beneficiary> listLiveFor(T unitOfWork, UUID partyId);

    /**
     * Move the identified beneficiary {@code ACTIVE → REMOVED}, if it is the caller's and still
     * live. {@code party_id = ?} is the ownership check, in the statement (ADR-0031); the row
     * count converges retries and refuses strangers with one indistinguishable {@code false}.
     * The HTTP caller is {@code P4-TSK-007}'s {@code DELETE}, which converges on 204.
     */
    boolean remove(T unitOfWork, BeneficiaryId beneficiary, UUID partyId, Instant at);
}
