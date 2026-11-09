package com.finapp.platform.sourcejoin;

import java.util.Objects;
import java.util.UUID;

/**
 * One journal entry as a join sees it: its id, its command key past the namespace ({@code suffix} - the record's identity, or
 * for an adjustment its proposal) and its reference. Identifiers only: never an amount.
 *
 * @param entry the entry's id
 * @param suffix the command key after its namespace; for an adjustment the proposal id
 * @param reference the entry's {@code reference} column
 */
public record EntryRef(UUID entry, String suffix, String reference) {

    public EntryRef {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(suffix, "suffix");
        Objects.requireNonNull(reference, "reference");
    }
}
