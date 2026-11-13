package com.finapp.ledger;

import java.io.Serial;

/**
 * The journal already holds an entry under this entry's idempotency scope, so nothing was appended (`P16-TSK-011`, ledger
 * {@code V037}, {@code INV-IDEM-01}).
 *
 * <p>Thrown by {@link JournalEntryStore#append} when the unique index on {@code journal_entry.idempotency_scope} refuses the
 * entry: the insert is {@code ON CONFLICT (idempotency_scope) DO NOTHING}, so the caller's transaction is <em>not</em> aborted
 * and may read the recorded entry and answer it as the stored outcome. A command that posts under a claimed key
 * ({@link PostingService}, {@link ReversalService}) does exactly that; any other writer lets it propagate, a refusal and never
 * a second entry.
 *
 * <p>The message names the refused entry's own fresh identifier, never the scope: a scope carries a client's key.
 */
public final class PostingScopeTakenException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public PostingScopeTakenException(String message) {
        super(message);
    }
}
