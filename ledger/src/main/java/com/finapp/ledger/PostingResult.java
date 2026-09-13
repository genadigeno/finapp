package com.finapp.ledger;

import java.util.Objects;

/**
 * What a posting command came to: the entry that exists, and whether this call created it or
 * replayed the recorded outcome (`P3-TSK-006`, {@code INV-IDEM-01}).
 *
 * <p>{@code replayed} is exposed because the caller is trusted code and the distinction is
 * operationally useful — the registration endpoint's reasoning for hiding it (a replay header
 * tells a replaying <em>stranger</em> something) does not apply to an internal command.
 */
public record PostingResult(JournalEntryId entryId, boolean replayed) {

    public PostingResult {
        Objects.requireNonNull(entryId, "entryId must not be null");
    }
}
