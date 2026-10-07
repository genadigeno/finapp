package com.finapp.credit;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A party's credit profile (`P10-TSK-004`, ADR-0087 section 5): the row every deciding
 * transaction for the party locks first, and nothing more.
 *
 * <p><strong>No figure, by design</strong> ({@code INV-CRD-04}): no score, limit, exposure or
 * balance lives here. Exposure is recomputed from the decisions and records under this row's lock
 * ({@code INV-CRD-09}); a stored figure would be a second answer free to disagree with them. The
 * profile is born once per party ({@code UNIQUE (party_id)}) and never changes.
 *
 * @param id the profile's identity
 * @param partyId the party it serialises - a reference, never a cross-schema foreign key
 * @param createdAt when it was born, on the database's clock
 */
public record CreditProfile(CreditProfileId id, UUID partyId, Instant createdAt) {

    public CreditProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
