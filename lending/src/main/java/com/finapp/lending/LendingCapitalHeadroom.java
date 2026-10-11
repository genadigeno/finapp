package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The lending capital's headroom (`P11-TSK-004`; ADR-0096 section 5, PHASE_11_PLAN.md section 12.9;
 * {@code INV-LND-13}) - what makes "every loan is funded from the platform's own capital" binding:
 *
 * <pre>
 * headroom(c) = CR-DR(LENDING_CAPITAL, c)
 *             - committed principal of loans PENDING_DISBURSEMENT in c
 *             - DR-CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE) over every loan and line in c      &gt;= 0
 * </pre>
 *
 * <p><strong>Derived from journal lines, never from a projection or a contribution's status</strong>
 * ({@code INV-BAL-05}): the capital is the {@code LENDING_CAPITAL} ledger balance - credited only by bank
 * recognition (rule 0) - so an approved contribution whose money never arrived counts for nothing.
 *
 * <h2>Under the capital lock (L6)</h2>
 *
 * <p>{@link #underLock} takes the currency's {@code LENDING_CAPITAL} {@code ledger_account} row {@code FOR UPDATE}
 * first - L6 of the lending lock order ({@code PHASE_11_PLAN.md} section 7.2: after the act's own row, L5, and before
 * any wallet row, L7) - then derives. Every consumer of headroom (the acceptance, `-012`; the draw, `-021`; the reversal
 * that re-instates principal, `-020`) serialises on that row, so two consumers in one currency can never both see the
 * last capital free. The seeded capital accounts' ids sort before every runtime id, so wherever a capital account joins
 * a multi-account lock its row sorts first ({@code lockBalancesInOrder}'s id order, L8). Bank recognition posting a
 * contribution meets the lock as a one-way
 * wait: its journal line's foreign-key share on the row waits for the holder to commit, and no lending transaction
 * ever waits on anything the recognition holds (section 7.3(f)).
 *
 * <p>The capital row lock leaves the capital stable for the rest of the holder's transaction (its only poster waits on
 * it); the principal outstanding is read in ONE statement over both purposes ({@link PurposeBalances}), so a billing
 * moving principal from not-due to due is wholly in or wholly out. <strong>The committed term is zero until
 * `P11-TSK-012`</strong> brings the loan table: when it lands it must be read in the same statement as the
 * outstanding, or before it - a disbursement moving principal from committed to outstanding between the two reads would
 * otherwise be counted in neither.
 *
 * <p>{@link #atRest} is the same derivation without the lock - for the capital proof and the capital report, which
 * read in one {@code REPEATABLE READ} snapshot and decide nothing.
 */
@RequiredArgsConstructor
public final class LendingCapitalHeadroom {

    /** The principal a loan or line still owes, not yet due and billed. */
    static final Set<AccountPurpose> PRINCIPAL = EnumSet.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE);

    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final PurposeBalances<Connection> balances;

    /** One currency's headroom and its terms. */
    public record Headroom(CurrencyCode currency, Money capital, Money committed, Money outstanding) {

        public Headroom {
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(capital, "capital must not be null");
            Objects.requireNonNull(committed, "committed must not be null");
            Objects.requireNonNull(outstanding, "outstanding must not be null");
        }

        /** Capital less what is committed and outstanding - never negative at any commit ({@code INV-LND-13}). */
        public Money headroom() {
            return capital.minus(committed).minus(outstanding);
        }

        /**
         * The safeguarding adjustment ADR-0096 section 6 publishes: wallet money lending created and still funds -
         * principal outstanding plus committed.
         */
        public Money lendingFunded() {
            return outstanding.plus(committed);
        }

        /** Whether {@code principal} more can be deployed. */
        public boolean admits(Money principal) {
            Objects.requireNonNull(principal, "principal must not be null");
            return headroom().minus(principal).minorUnits() >= 0;
        }

        /** The currency only - every term is an amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Headroom[" + currency.code() + "]";
        }
    }

    /**
     * The currency's headroom judged under L6: the {@code LENDING_CAPITAL} row locked {@code FOR UPDATE} first, held to
     * the caller's commit.
     */
    public Headroom underLock(Connection unitOfWork, CurrencyCode currency) {
        LedgerAccount capital = capitalAccount(unitOfWork, currency);
        accounts.lockForUpdate(unitOfWork, capital.id())
                .orElseThrow(() -> new IllegalStateException("the seeded capital account cannot vanish"));
        return derive(unitOfWork, capital, currency);
    }

    /** The currency's headroom without the lock - a reading for the proof and the report, deciding nothing. */
    public Headroom atRest(Connection unitOfWork, CurrencyCode currency) {
        return derive(unitOfWork, capitalAccount(unitOfWork, currency), currency);
    }

    /** The currency's seeded {@code LENDING_CAPITAL} account (ledger {@code V026}). */
    public LedgerAccount capitalAccount(Connection unitOfWork, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!SupportedCurrencies.ALL.contains(currency)) {
            throw new IllegalArgumentException("no lending capital is held in an unsupported currency");
        }
        return accounts.findOperational(unitOfWork, AccountPurpose.LENDING_CAPITAL, currency)
                .orElseThrow(() -> new IllegalStateException(
                        "ledger V026 seeds the lending capital account in every supported currency"));
    }

    private Headroom derive(Connection unitOfWork, LedgerAccount capital, CurrencyCode currency) {
        // CR-DR: the capital is credit-normal EQUITY.
        Money recognised = balances.netDebit(unitOfWork, EnumSet.of(capital.purpose()), currency).negated();
        Money outstanding = balances.netDebit(unitOfWork, PRINCIPAL, currency);
        Money committed = Money.zero(currency);
        return new Headroom(currency, adopt(recognised, outstanding), adopt(committed, outstanding), adopt(outstanding, recognised));
    }

    /** A zero takes the other term's persisted scale, so the arithmetic never meets two scales. */
    private static Money adopt(Money term, Money other) {
        return term.isZero() && term.scale() != other.scale() ? Money.ofPersisted(0, term.currency(), other.scale()) : term;
    }
}
