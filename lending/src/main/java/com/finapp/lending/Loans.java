package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's loan accounts as they read them (`P11-TSK-012`; {@code PHASE_11_PLAN.md} section 9): the account, the
 * agreement version that governs it, and its balances <strong>derived from journal lines</strong> - never stored by
 * lending, never read from a projection ({@code INV-LND-01}, {@code INV-BAL-05}). Owner-scoped in the statement; another
 * party's loan is the same empty answer as an absent one.
 *
 * <p>The six balances are read one account at a time, so the caller reads them in one {@code REPEATABLE READ} snapshot
 * (the door does): a billing moving principal from not-due to due is then wholly in or wholly out of the view. A read
 * decides nothing.
 */
@RequiredArgsConstructor
public final class Loans {

    /** The most loans one listing answers. */
    public static final int LISTING_LIMIT = 100;

    /**
     * What a customer reads beside a {@code WRITTEN_OFF} status (`P14-TSK-021`). Truthful, and never "paid", "settled" or
     * "closed": a write-off is a bookkeeping entry, the amount stands owed. <strong>The route to a person</strong> (`P14-TSK-022`, from the owner's
     * (c)): it promises a review, never an outcome, because no door settles the matter until {@code PI-005} is built;
     * {@code OPERATIONS_RUNBOOK.md} section 7.13 is what that person may do. "Contact us" is the only route this product names to a customer: no
     * channel is invented here. <strong>Wording a qualified reviewer confirms before production</strong> (the owner's (d)).
     */
    public static final String WRITTEN_OFF_NOTICE = "This account is in default and has been written off in our accounts. That is a"
            + " bookkeeping entry: the amount has not been paid or forgiven, and it is still owed. Please contact us about repaying it."
            + " If you think this is wrong, for example because you never received the money, contact us and a person will review it; we cannot promise the outcome.";

    @NonNull private final LoanStore store;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final LoanPayoutStore payouts;
    @NonNull private final LoanConditions conditions;
    @NonNull private final LoanWriteOffStore writeOffs;

    /**
     * A loan, its governing agreement, its derived balances, - path X - its payout's status (`P11-TSK-015`; empty for a
     * wallet loan or a line) and its condition (`P11-TSK-024`: DPD, bucket and default derived in the read's snapshot on
     * the database clock's business date; empty for an account not in servicing).
     */
    public record Read(LoanStore.Owned owned, Balances balances, Optional<PayoutStatus> payout,
            Optional<DelinquencyState> condition) {

        public Read {
            Objects.requireNonNull(owned, "owned must not be null");
            Objects.requireNonNull(balances, "balances must not be null");
            Objects.requireNonNull(payout, "payout must not be null");
            Objects.requireNonNull(condition, "condition must not be null");
        }

        /**
         * A line's available limit (`P11-TSK-021`, {@link AvailableLimit}): the governing agreement's limit less the drawn
         * principal of these balances while the line is {@code ACTIVE}; zero once it is not (a closing or closed line takes
         * no draw); empty for an instalment loan. Derived in the read's snapshot, never stored - it decides nothing: the
         * draw derives its own under the line's lock.
         */
        public Optional<Money> availableLimit() {
            Loan loan = owned.loan();
            if (loan.kind() != LoanKind.REVOLVING) {
                return Optional.empty();
            }
            Money limit = owned.agreement().amount();
            if (loan.status() != LoanStatus.ACTIVE) {
                return Optional.of(Money.ofPersisted(0, limit.currency(), limit.scale()));
            }
            return Optional.of(new AvailableLimit(limit, balances.principalOutstanding()).available());
        }

        /**
         * Whether a line's draws are suspended (`P11-TSK-024`, A25) - past due or defaulted, from this read's condition;
         * empty for an instalment loan and for a line not {@code ACTIVE} (a closing or closed line takes no draw whatever
         * its condition - its available limit reads zero). It decides nothing: the draw derives its own under the line's
         * lock.
         */
        public Optional<Boolean> drawsSuspended() {
            if (owned.loan().kind() != LoanKind.REVOLVING || owned.loan().status() != LoanStatus.ACTIVE) {
                return Optional.empty();
            }
            return condition.map(DelinquencyState::suspendsDraws);
        }

        @Override
        public String toString() {
            return "Read[" + owned.loan() + "]";
        }
    }

    /**
     * The six per-loan balances, each on its normal side: the receivables DR-CR, the credit balance CR-DR. Zero for an
     * account not yet opened (never, after an acceptance) or never posted to (every balance until the disbursement). On a
     * {@code WRITTEN_OFF} account (`P14-TSK-021`): what its write-off took, which is what stands owed - the entry moved it off
     * the platform's assets and said nothing about the debt, so a read of zero would tell the customer it was paid. The figures
     * are the write-off's row, which the subledger proof holds to the entry's lines.
     */
    public record Balances(
            Money principalNotDue,
            Money principalDue,
            Money interestAccrued,
            Money interestDue,
            Money feesDue,
            Money creditBalance) {

        public Balances {
            Objects.requireNonNull(principalNotDue, "principalNotDue must not be null");
            Objects.requireNonNull(principalDue, "principalDue must not be null");
            Objects.requireNonNull(interestAccrued, "interestAccrued must not be null");
            Objects.requireNonNull(interestDue, "interestDue must not be null");
            Objects.requireNonNull(feesDue, "feesDue must not be null");
            Objects.requireNonNull(creditBalance, "creditBalance must not be null");
        }

        /** The principal outstanding - not due and due (A3's principal). */
        public Money principalOutstanding() {
            return principalNotDue.plus(principalDue);
        }

        @Override
        public String toString() {
            return "Balances[redacted]";
        }
    }

    /** {@code party}'s loan {@code id}. */
    public Optional<Read> read(Connection unitOfWork, LoanId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.ownedBy(unitOfWork, id, party).map(owned -> readOf(unitOfWork, owned, party));
    }

    /** {@code party}'s loans, newest first. */
    public List<Read> list(Connection unitOfWork, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.listOwnedBy(unitOfWork, party, LISTING_LIMIT).stream()
                .map(owned -> readOf(unitOfWork, owned, party))
                .toList();
    }

    private Read readOf(Connection unitOfWork, LoanStore.Owned owned, UUID party) {
        return new Read(owned, balancesOf(unitOfWork, owned),
                payouts.statusOwnedBy(unitOfWork, owned.loan().id(), party), conditions.read(unitOfWork, owned));
    }

    /** Each balance at the agreement's persisted scale - the scale every posting to the loan is made in. */
    private Balances balancesOf(Connection unitOfWork, LoanStore.Owned owned) {
        Loan loan = owned.loan();
        int scale = owned.agreement().amount().scale();
        if (loan.status() == LoanStatus.WRITTEN_OFF) {
            LoanWriteOffPosition owed = writeOffs.approvedOf(unitOfWork, loan.id())
                    .orElseThrow(() -> new IllegalStateException("a WRITTEN_OFF loan has its approved write-off (lending V032)"))
                    .position();
            return new Balances(owed.principal(), owed.principalDue(), owed.interestAccrued(), owed.interestDue(), owed.feesDue(),
                    Money.ofPersisted(0, loan.currency(), scale));
        }
        return new Balances(
                netDebit(unitOfWork, loan, AccountPurpose.LOAN_PRINCIPAL, scale),
                netDebit(unitOfWork, loan, AccountPurpose.LOAN_PRINCIPAL_DUE, scale),
                netDebit(unitOfWork, loan, AccountPurpose.LOAN_INTEREST_ACCRUED, scale),
                netDebit(unitOfWork, loan, AccountPurpose.LOAN_INTEREST_DUE, scale),
                netDebit(unitOfWork, loan, AccountPurpose.LOAN_FEES_DUE, scale),
                netDebit(unitOfWork, loan, AccountPurpose.LOAN_CREDIT_BALANCE, scale).negated());
    }

    /** DR-CR over one per-loan account's lines, folded through {@link Money} (never a SQL sum, {@code INV-MON-01}). */
    private Money netDebit(Connection unitOfWork, Loan loan, AccountPurpose purpose, int scale) {
        CurrencyCode currency = loan.currency();
        Optional<LedgerAccount> account = accounts.findOwned(unitOfWork, loan.id().value(), purpose, currency);
        Money net = Money.ofPersisted(0, currency, scale);
        if (account.isEmpty()) {
            return net;
        }
        for (PurposeBalances.EntryLine line : balances.linesOn(unitOfWork, account.get().id())) {
            net = line.direction() == Direction.DEBIT ? net.plus(line.amount()) : net.minus(line.amount());
        }
        return net;
    }
}
