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
 * {@code INV-LND-13}) - what makes "every loan is funded from the platform's own capital" binding - and, since
 * `P12-TSK-005`, every BNPL receivable too (owner decision B2: one pool, one proof; ADR-0104 decisions 1 and 2,
 * PHASE_12_PLAN.md section 12.9; {@code INV-BNPL-10}):
 *
 * <pre>
 * headroom(c) = CR-DR(LENDING_CAPITAL, c)
 *             - committed principal of loans PENDING_DISBURSEMENT in c
 *             - DR-CR(LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE
 *                     + BNPL_PRINCIPAL + BNPL_PRINCIPAL_DUE) over every account of c                  &gt;= 0
 * </pre>
 *
 * <p><strong>The BNPL principal by ledger purpose, never by a bnpl type.</strong> The outstanding term sums the
 * ledger's purposes across every account of the currency, so lending counts a BNPL obligation's principal without
 * learning what a purchase, an obligation or a merchant is, and without an edge to {@code bnpl} (no module depends on
 * lending, and lending depends on no bnpl type - {@code LendingModuleIsolationTest}). Lending READS the two purposes and
 * never posts them ({@code BnplBooksHaveOnePosterTest}'s reader permit). BNPL has <strong>no committed term</strong>: its
 * acceptance, its financing and its obligation are one commit (ADR-0104 Context 5), so its principal exists only as
 * outstanding, from the instant it exists. With no BNPL line in a currency the two purposes add nothing and the figure
 * is Phase 11's exactly. bnpl judges this headroom through its {@code BnplCapital} port, which {@code app} implements
 * over {@link #underLock} in bnpl's own transaction.
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
 * that re-instates principal, `-020`; and bnpl's purchase and principal-re-instating reversal through its port,
 * `P12-TSK-013`, `-024` - each after its own module's rows and before any wallet or payable row, PHASE_12_PLAN.md section
 * 7.3(f)) serialises on that row, so two consumers in one currency can never both see the last capital free. The seeded capital accounts' ids sort before every runtime id, so wherever a capital account joins
 * a multi-account lock its row sorts first ({@code lockBalancesInOrder}'s id order, L8). Bank recognition posting a
 * contribution meets the lock as a one-way
 * wait: its journal line's foreign-key share on the row waits for the holder to commit, and no lending transaction
 * ever waits on anything the recognition holds (section 7.3(f)).
 *
 * <p>The capital row lock leaves the capital stable for the rest of the holder's transaction (its only poster waits on
 * it); the principal outstanding is read in ONE statement over all four purposes ({@link PurposeBalances}), so a loan's
 * or an obligation's billing moving principal from not-due to due is wholly in or wholly out. Every act that RAISES that
 * principal - a disbursement aside, whose principal was committed at acceptance - holds L6 to its commit, so none can
 * commit while a holder derives; an act that lowers it (a repayment, a BNPL refund) committing between the statements
 * can only read the headroom low - a refusal at worst, never an over-deployment. <strong>The committed term</strong> (`P11-TSK-012`)
 * is the agreement principal of the currency's loans {@code PENDING_DISBURSEMENT} ({@link LoanStore#committedPrincipal}),
 * read <strong>before</strong> the outstanding term: under {@code READ COMMITTED} each statement sees what had committed
 * when it began, so a disbursement (`P11-TSK-014`, which does not take L6 - its principal was committed at acceptance)
 * committing between the two reads moves its principal from a term already read to one not yet read and is counted
 * twice - a headroom read LOW, a refusal at worst - never in neither, which would read it high and over-deploy. A
 * cancellation between them releases a commitment already read - low again. A line commits no capital at acceptance
 * (A28): its principal arrives by draws, counted as outstanding.
 *
 * <p>{@link #atRest} is the same derivation without the lock - for the capital proof and the capital report, which
 * read in one {@code REPEATABLE READ} snapshot and decide nothing.
 */
@RequiredArgsConstructor
public final class LendingCapitalHeadroom {

    /**
     * The principal capital funds, not yet due and billed: a loan's or line's, and - by ledger purpose, never by a bnpl
     * type (`P12-TSK-005`) - a BNPL obligation's. Principal only: interest, fees and credit balances are not capital's
     * (lending A29, ADR-0104 decision 6).
     */
    static final Set<AccountPurpose> PRINCIPAL = EnumSet.of(
            AccountPurpose.LOAN_PRINCIPAL,
            AccountPurpose.LOAN_PRINCIPAL_DUE,
            AccountPurpose.BNPL_PRINCIPAL,
            AccountPurpose.BNPL_PRINCIPAL_DUE);

    /**
     * The principal purposes the outstanding term sums - the one set, for every derivation of the headroom that cannot
     * call this class (the audited as-of-date capital report, `P11-TSK-030`, folds dated lines in its own snapshot), so
     * no second copy of the formula can fall behind it (`P12-TSK-005`'s gate). Unmodifiable.
     */
    public static Set<AccountPurpose> principalPurposes() {
        return java.util.Collections.unmodifiableSet(PRINCIPAL);
    }

    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final LoanStore loans;

    /**
     * One currency's headroom and its terms: the capital recognised, the committed principal of loans awaiting
     * disbursement, and the principal outstanding of every loan, line and - since `P12-TSK-005` - BNPL obligation, one
     * figure read in one statement.
     */
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
         * The safeguarding adjustment ADR-0096 section 6 publishes: the platform liabilities capital created and still
         * funds - principal outstanding (loans, lines and BNPL obligations; ADR-0104 decision 7: BNPL creates
         * merchant-payable liabilities without cash entering, as a disbursement creates wallet money) plus committed.
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
        // The committed term FIRST, the outstanding after it: a disbursement between the two is counted twice, never in
        // neither (the class comment).
        Money committed = loans.committedPrincipal(unitOfWork, currency);
        // CR-DR: the capital is credit-normal EQUITY.
        Money recognised = balances.netDebit(unitOfWork, EnumSet.of(capital.purpose()), currency).negated();
        Money outstanding = balances.netDebit(unitOfWork, PRINCIPAL, currency);
        Money scale = !outstanding.isZero() ? outstanding : !recognised.isZero() ? recognised : committed;
        return new Headroom(currency, adopt(recognised, scale), adopt(committed, scale), adopt(outstanding, scale));
    }

    /** A zero takes the other term's persisted scale, so the arithmetic never meets two scales. */
    private static Money adopt(Money term, Money other) {
        return term.isZero() && term.scale() != other.scale() ? Money.ofPersisted(0, term.currency(), other.scale()) : term;
    }
}
