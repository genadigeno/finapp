package com.finapp.identity;

import java.io.Serial;

/**
 * The identity already has a verified contact channel of this kind (`X-TSK-004`).
 *
 * <h2>Refused, not replaced, and {@code INV-IDN-06} is why</h2>
 *
 * <p>The channel already verified is what recovery trusts: <em>"a previously registered and
 * verified channel"</em>. Adding a channel needs only a session, so a stolen password is enough to
 * register a mailbox. If verifying it displaced the channel already verified, whoever held the
 * password could move the account's recovery to themselves, with no step-up and no word to the
 * address being replaced - a temporary compromise made permanent. Changing the verified channel is a
 * flow of its own (step-up, notice to the old channel, cooling-off), and until one exists the answer
 * is no.
 *
 * <h2>Raised from the index, not from a check</h2>
 *
 * <p>{@code V011}'s partial unique index on {@code (identity_id, kind) WHERE verified_at IS NOT NULL}
 * refuses it, so two instances verifying two channels of one identity are arbitrated by the
 * database: the loser waits for the winner's commit, then is refused. A pre-flight {@code SELECT}
 * would not be a substitute - both would see no verified channel - which is
 * {@link ActiveCredentialAlreadyExistsException}'s argument.
 *
 * <h2>The unit of work is still usable when this is thrown</h2>
 *
 * <p>Unlike {@link ActiveCredentialAlreadyExistsException}: the store rolls back to a savepoint
 * before throwing ({@code JdbcPaymentFeePinStore}'s shape), so the refusal is an answer rather than
 * an aborted transaction, and whatever else the caller did in that transaction can still commit.
 *
 * <h2>It carries nothing</h2>
 *
 * <p>Not the identity, not the kind, not the address - and no cause. PostgreSQL's detail for this
 * violation names the index key, which is the identity, and an exception reaches a log line
 * ({@code INV-AUD-02}).
 */
public final class VerifiedChannelAlreadyExistsException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    VerifiedChannelAlreadyExistsException() {
        super("The identity already has a verified contact channel of this kind");
    }
}
