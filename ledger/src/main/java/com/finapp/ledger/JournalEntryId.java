package com.finapp.ledger;

import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.UUID;

/**
 * Identifies a {@link JournalEntry}. Typed (ADR-0013) — and time-ordered by construction, which
 * matters more here than anywhere: the journal is the platform's append-only record, and a
 * random primary key would turn its append-only write pattern into a random one.
 */
public final class JournalEntryId extends EntityId {

    private JournalEntryId(UUID value) {
        super(value);
    }

    public static JournalEntryId next(IdGenerator ids) {
        return new JournalEntryId(ids.next());
    }

    /** For values read back from storage or a message. Validates shape, including UUIDv7. */
    public static JournalEntryId of(UUID value) {
        return new JournalEntryId(value);
    }
}
