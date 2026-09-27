package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Who is acting on a dispute, and what bounds them (`P7-TSK-014`, ADR-0061 §7) — the tenancy of
 * every evidence and response path, handed in by the boundary that authenticated the actor.
 * {@code payments} still never learns what a merchant is: a counterparty is its ledger accounts,
 * an operator's reach is the account purposes its policy names.
 */
public sealed interface DisputeActor {

    /**
     * The payment's counterparty — the merchant over its key: its tenancy is the accounts the
     * caller resolved from the authenticated merchant through the ledger's owner-scoped read, and
     * every statement carries them ({@code INV-MER-01}).
     */
    record Counterparty(Set<LedgerAccountId> accounts) implements DisputeActor {

        public Counterparty {
            accounts = Set.copyOf(Objects.requireNonNull(accounts, "accounts must not be null"));
        }
    }

    /**
     * An operator under the dispute permission — across tenants for reads, and for acts only on a
     * payment whose credited account's purpose is in {@code actsFor} (a payment with no merchant:
     * the merchant owns its dispute posture). An act carries the operator's reason
     * ({@code INV-AUD-03}); a read needs none.
     */
    record Operator(Optional<String> reason, Set<AccountPurpose> actsFor) implements DisputeActor {

        public Operator {
            Objects.requireNonNull(reason, "reason must not be null");
            actsFor = Set.copyOf(Objects.requireNonNull(actsFor, "actsFor must not be null"));
            if (reason.isPresent() && reason.get().isBlank()) {
                throw new IllegalArgumentException("a stated reason is never blank");
            }
        }
    }
}
