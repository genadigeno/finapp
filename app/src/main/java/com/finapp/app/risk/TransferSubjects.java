package com.finapp.app.risk;

import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.transfers.Transfer;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * A judged transfer's subjects as risk counts them (`P13-TSK-008`; {@code RiskEvaluationRequest}'s roles): whose
 * movement it is, and the two customer accounts by id. The transfer aggregate carries ledger accounts and the
 * initiating identity; risk's windows and evidence name the <strong>party</strong> and the <strong>customer account
 * ids</strong> (the ledger's {@code owner_ref}), never a {@code LedgerAccountId} (`-006`) - so the adapter resolves
 * them, on the transfer's own unit of work, inside its lock.
 */
@FunctionalInterface
public interface TransferSubjects {

    /** The payer party, and the source's and destination's customer account ids. */
    record Subjects(UUID party, UUID sourceAccount, UUID destinationAccount) {
        public Subjects {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(sourceAccount, "sourceAccount");
            Objects.requireNonNull(destinationAccount, "destinationAccount");
        }
    }

    /** {@code transfer}'s subjects, read through {@code unitOfWork}. */
    Subjects of(Connection unitOfWork, Transfer transfer);

    /**
     * The production resolution, three primary-key reads:
     *
     * <ul>
     *   <li><strong>the party</strong> - the initiating identity's ({@code Identity.partyId}): the execution proved the
     *       source is owned by exactly that party's live customer ({@code TransferParticipants.sourceOwnedBy}), so it is
     *       the payer - {@code TransferService}'s own {@code Session → Identity → Party} chain. An identity that
     *       resolves to nothing is an invariant already broken, and loud;</li>
     *   <li><strong>the two accounts</strong> - each wallet's {@code owner_ref}, re-read with
     *       {@link LedgerAccountStore#lockForShare} on rows the execution <em>already holds</em> {@code FOR UPDATE}
     *       ({@code lockBothInFixedOrder}): a lock the holder already exceeds is granted without a new one, so this
     *       read adds no lock and changes no order (the ledger port has no unlocked read by id, and the money module
     *       is not changed for one).</li>
     * </ul>
     */
    static TransferSubjects over(IdentityStore<Connection> identities, LedgerAccountStore<Connection> ledgerAccounts) {
        Objects.requireNonNull(identities, "identities");
        Objects.requireNonNull(ledgerAccounts, "ledgerAccounts");
        return (unitOfWork, transfer) -> {
            UUID party = identities.findById(unitOfWork, IdentityId.of(transfer.initiatedBy()))
                    .map(Identity::partyId)
                    .orElseThrow(() -> new IllegalStateException("a transfer's initiating identity resolved to no"
                            + " identity: the execution parsed it from the proven actor, so an invariant is broken"));
            return new Subjects(party, owner(unitOfWork, ledgerAccounts, transfer.sourceAccount()),
                    owner(unitOfWork, ledgerAccounts, transfer.destinationAccount()));
        };
    }

    private static UUID owner(Connection unitOfWork, LedgerAccountStore<Connection> ledgerAccounts,
            LedgerAccountId account) {
        return ledgerAccounts.lockForShare(unitOfWork, account)
                .flatMap(LedgerAccount::ownerRef)
                .orElseThrow(() -> new IllegalStateException("a transfer's wallet " + account + " is gone or owned by"
                        + " nobody under the execution's own lock - an invariant is broken"));
    }
}
