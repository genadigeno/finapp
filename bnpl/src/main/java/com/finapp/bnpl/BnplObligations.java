package com.finapp.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's BNPL agreements, as their owner reads them (`P12-TSK-013`; {@code PHASE_12_PLAN.md} section 9; lending's
 * {@code Loans}): the list, one agreement - its state, product and plan, the agreement version that governs it and its
 * six balances <strong>derived from journal lines</strong>, never stored ({@code INV-BNPL-01}) - and its schedule with
 * each instalment's billed mark. Every read owner-scoped in the statement: another party's agreement is the same empty
 * answer as an absent one. It decides nothing; the door runs it in one {@code REPEATABLE READ} snapshot so the six
 * balances are one instant's.
 */
@RequiredArgsConstructor
public final class BnplObligations {

    /** The newest agreements the list answers. */
    public static final int LIST_LIMIT = 100;

    @NonNull private final BnplPurchaseStore store;
    @NonNull private final BnplBook book;

    /** An agreement, its purchase and obligation, the financed gross and its balances. */
    public record Read(BnplPurchaseStore.Owned owned, Balances balances) {

        public Read {
            Objects.requireNonNull(owned, "owned must not be null");
            Objects.requireNonNull(balances, "balances must not be null");
        }
    }

    /** The obligation's six balances, each by its normal balance; zero for an account not yet posted to. */
    public record Balances(Money principal, Money principalDue, Money interestAccrued, Money interestDue, Money feesDue,
            Money creditBalance) {

        public Balances {
            Objects.requireNonNull(principal, "principal must not be null");
            Objects.requireNonNull(principalDue, "principalDue must not be null");
            Objects.requireNonNull(interestAccrued, "interestAccrued must not be null");
            Objects.requireNonNull(interestDue, "interestDue must not be null");
            Objects.requireNonNull(feesDue, "feesDue must not be null");
            Objects.requireNonNull(creditBalance, "creditBalance must not be null");
        }

        /** The principal outstanding - not yet due and billed. */
        public Money principalOutstanding() {
            return principal.plus(principalDue);
        }

        @Override
        public String toString() {
            return "Balances[redacted]";
        }
    }

    /** A schedule as its owner reads it: the version, its instalments, and which have been billed. */
    public record Schedule(BnplPurchaseStore.Owned owned, BnplPurchaseStore.StoredSchedule schedule, Set<UUID> billed) {

        public Schedule {
            Objects.requireNonNull(owned, "owned must not be null");
            Objects.requireNonNull(schedule, "schedule must not be null");
            billed = Set.copyOf(Objects.requireNonNull(billed, "billed must not be null"));
        }
    }

    /** {@code party}'s agreements, newest first. */
    public List<BnplPurchaseStore.Owned> list(Connection unitOfWork, UUID party) {
        return store.ownedBy(Objects.requireNonNull(unitOfWork, "unitOfWork"), Objects.requireNonNull(party, "party"),
                LIST_LIMIT);
    }

    /** {@code party}'s agreement {@code id} with its balances; another party's is the same empty answer. */
    public Optional<Read> read(Connection unitOfWork, BnplAgreementId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.agreementOwnedBy(unitOfWork, Objects.requireNonNull(id, "id"), Objects.requireNonNull(party, "party"))
                .map(owned -> new Read(owned, balancesOf(unitOfWork, owned)));
    }

    /** {@code party}'s agreement {@code id}'s schedule version 1 and its billed marks. */
    public Optional<Schedule> schedule(Connection unitOfWork, BnplAgreementId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.agreementOwnedBy(unitOfWork, Objects.requireNonNull(id, "id"), Objects.requireNonNull(party, "party"))
                .map(owned -> new Schedule(owned, store.schedule(unitOfWork, owned.obligation().id(), 1)
                        .orElseThrow(() -> new IllegalStateException("an obligation commits with schedule version 1")),
                        Set.copyOf(store.billedInstalments(unitOfWork, owned.obligation().id()))));
    }

    private Balances balancesOf(Connection unitOfWork, BnplPurchaseStore.Owned owned) {
        Map<AccountPurpose, Money> byPurpose = new EnumMap<>(AccountPurpose.class);
        for (LedgerAccount account : book.accountsOf(unitOfWork, owned.obligation().id())) {
            if (!BnplAccounts.purposes().contains(account.purpose()) || byPurpose.put(account.purpose(),
                    book.balanceOf(unitOfWork, account)) != null) {
                throw new IllegalStateException("an obligation's accounts are its six declared purposes, each once");
            }
        }
        Money zero = Money.ofPersisted(0, owned.gross().currency(), owned.gross().scale());
        return new Balances(
                byPurpose.getOrDefault(AccountPurpose.BNPL_PRINCIPAL, zero),
                byPurpose.getOrDefault(AccountPurpose.BNPL_PRINCIPAL_DUE, zero),
                byPurpose.getOrDefault(AccountPurpose.BNPL_INTEREST_ACCRUED, zero),
                byPurpose.getOrDefault(AccountPurpose.BNPL_INTEREST_DUE, zero),
                byPurpose.getOrDefault(AccountPurpose.BNPL_FEES_DUE, zero),
                byPurpose.getOrDefault(AccountPurpose.BNPL_CREDIT_BALANCE, zero));
    }
}
