package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Opens one loan's six ledger accounts (`P11-TSK-003`; ADR-0096 section 1, PHASE_11_PLAN.md
 * section 12.8; {@code INV-LND-01}, {@code INV-LED-06}).
 *
 * <p><strong>The account plan, and why it is six.</strong> A loan is several claims, not one:
 * principal not yet due ({@code LOAN_PRINCIPAL}), principal billed ({@code LOAN_PRINCIPAL_DUE}),
 * interest earned ({@code LOAN_INTEREST_ACCRUED}), interest billed ({@code LOAN_INTEREST_DUE}),
 * fees billed ({@code LOAN_FEES_DUE}) - each an ASSET, debit-normal - and money owed back to the
 * borrower ({@code LOAN_CREDIT_BALANCE}), a LIABILITY, credit-normal. Splitting due from not-due
 * makes "how much is past due" a balance rather than a calculation over rows, and every figure a
 * borrower is charged a balance of one of these, derived from journal lines - lending stores none.
 *
 * <p><strong>The type is declared here, once</strong> ({@link #declaredTypeOf}): the ledger's
 * owned-account factory takes the type from its opener, as a wallet's does from accounts', and the
 * normal balance and owner kind derive from it and from the purpose. A converged account whose
 * stored type, currency or owner is not this declaration is refused loudly, never adopted: a loan
 * account of the wrong type would grow on the wrong side of every report ({@code INV-LED-06}).
 *
 * <p><strong>Concurrency.</strong> In the caller's transaction (the loan's acceptance,
 * `P11-TSK-012`), so the six commit with the loan or not at all. Each account goes through
 * {@link LedgerAccountStore#createOrConverge}, whose arbiter is the ledger's partial unique index
 * on {@code (owner_ref, purpose, currency)} - the owner kind derives from the purpose, so this is
 * the {@code (owner_kind, owner_ref, purpose, currency)} identity - and whose savepoint lets a lost
 * race converge without aborting the caller's other writes. Ten openers of one loan produce one set
 * of six. <strong>The fixed order is load-bearing</strong>: every opener inserts in
 * {@link #purposes()} order, so two openers can only ever wait on each other's first contested
 * index entry, never hold one each of two (no deadlock cycle). No lock of its own, no JVM state.
 *
 * <p>Wired by `P11-TSK-012`: the acceptance ({@link LoanAcceptances}) calls it after the loan is born, in the same
 * transaction.
 */
@RequiredArgsConstructor
public final class LoanAccounts {

    @NonNull private final LedgerAccountStore<Connection> store;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The six per-loan purposes, in the order they are opened - {@link LoanLedgerAccounts}' order. */
    public static List<AccountPurpose> purposes() {
        return List.of(
                AccountPurpose.LOAN_PRINCIPAL,
                AccountPurpose.LOAN_PRINCIPAL_DUE,
                AccountPurpose.LOAN_INTEREST_ACCRUED,
                AccountPurpose.LOAN_INTEREST_DUE,
                AccountPurpose.LOAN_FEES_DUE,
                AccountPurpose.LOAN_CREDIT_BALANCE);
    }

    /**
     * The per-loan purposes whose balances are the loan's principal - not yet due and billed (`P11-TSK-013`): what the
     * platform lent and is still owed, the principal credit's exposure counts (A3). Interest, fees and the credit balance
     * are not principal. Lending's declaration, so a reader in {@code app} (the exposure read and its census) binds these
     * rather than naming the loan book itself.
     */
    public static List<AccountPurpose> principalPurposes() {
        return List.of(AccountPurpose.LOAN_PRINCIPAL, AccountPurpose.LOAN_PRINCIPAL_DUE);
    }

    /**
     * The declared type of a per-loan purpose (ADR-0096 section 1): the five receivables ASSET,
     * the credit balance LIABILITY. Any other purpose is not a loan account and is refused.
     */
    public static AccountType declaredTypeOf(AccountPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        return switch (purpose) {
            case LOAN_PRINCIPAL,
                    LOAN_PRINCIPAL_DUE,
                    LOAN_INTEREST_ACCRUED,
                    LOAN_INTEREST_DUE,
                    LOAN_FEES_DUE -> AccountType.ASSET;
            case LOAN_CREDIT_BALANCE -> AccountType.LIABILITY;
            default -> throw new IllegalArgumentException(
                    purpose + " is not a per-loan purpose; the loan book's operational accounts"
                            + " are seeded by ledger V026 and resolved from the chart");
        };
    }

    /**
     * The loan's six accounts in {@code currency}, opened or converged on, in the caller's
     * transaction. Only the loan's own currency: a loan never holds an account in another.
     *
     * @throws IllegalArgumentException {@code currency} is not one the platform supports - a loan
     *     there would have no seeded income, fee or capital account to post against
     * @throws IllegalStateException a converged account's stored type, normal balance, currency
     *     or owner is not the declaration - a defect to investigate, never adopted
     */
    public LoanLedgerAccounts openFor(Connection unitOfWork, LoanId loanId, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(loanId, "loanId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!SupportedCurrencies.ALL.contains(currency)) {
            throw new IllegalArgumentException(
                    "a loan account is opened only in a supported currency - " + currency.code()
                            + " has no seeded loan income, fee or capital account (ledger V026)");
        }
        List<LedgerAccount> opened =
                purposes().stream().map(purpose -> open(unitOfWork, loanId, purpose, currency)).toList();
        return new LoanLedgerAccounts(
                opened.get(0), opened.get(1), opened.get(2), opened.get(3), opened.get(4), opened.get(5));
    }

    private LedgerAccount open(
            Connection unitOfWork, LoanId loanId, AccountPurpose purpose, CurrencyCode currency) {
        AccountType declared = declaredTypeOf(purpose);
        LedgerAccount account =
                store.createOrConverge(
                                unitOfWork,
                                LedgerAccount.owned(ids, clock, declared, purpose, currency, loanId.value()))
                        .account();
        boolean asDeclared =
                account.purpose() == purpose
                        && account.accountType() == declared
                        && account.normalBalance() == declared.normalBalance()
                        && account.currency().equals(currency)
                        && account.ownerRef().filter(loanId.value()::equals).isPresent();
        if (!asDeclared) {
            throw new IllegalStateException(
                    "loan " + loanId.value() + "'s " + purpose + " account in " + currency.code()
                            + " exists as " + account.accountType() + "/" + account.normalBalance()
                            + " in " + account.currency().code() + ", not the declared " + declared
                            + "/" + declared.normalBalance() + " - refused, never adopted (INV-LED-06)");
        }
        return account;
    }
}
