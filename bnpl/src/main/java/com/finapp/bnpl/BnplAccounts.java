package com.finapp.bnpl;

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
 * Opens one customer repayment obligation's six ledger accounts (`P12-TSK-003`; ADR-0103 decision 1,
 * PHASE_12_PLAN.md section 12.11; {@code INV-BNPL-01}, {@code INV-LED-06}) - the lending opener's shape
 * (`P11-TSK-003`), for bnpl's own purposes under its own owner kind.
 *
 * <p><strong>The account plan, and why it is six.</strong> An obligation is several claims, not one:
 * purchase principal not yet due ({@code BNPL_PRINCIPAL}), principal billed ({@code BNPL_PRINCIPAL_DUE}),
 * pay-in-N interest earned ({@code BNPL_INTEREST_ACCRUED}) and billed ({@code BNPL_INTEREST_DUE}), late fees
 * billed ({@code BNPL_FEES_DUE}) - each an ASSET, debit-normal - and money owed back to the customer
 * ({@code BNPL_CREDIT_BALANCE}), a LIABILITY, credit-normal. Splitting due from not-due makes "how much is
 * past due" a balance, and a refund's latest-first walk cancels against the two principal balances rather
 * than a stored figure: every figure a BNPL customer owes is a balance of one of these, derived from journal
 * lines - bnpl stores none. A pay-in-4 obligation opens the interest pair too and posts nothing to it, so
 * the plan is one shape for both products.
 *
 * <p><strong>The type is declared here, once</strong> ({@link #declaredTypeOf}): the ledger's owned-account
 * factory takes the type from its opener, and the normal balance and owner kind derive from it and from the
 * purpose. An account of this obligation whose stored purpose, type, normal balance, currency or owner is not
 * this declaration is refused loudly, never adopted: a BNPL account of the wrong type would grow on the wrong
 * side of every report ({@code INV-LED-06}).
 *
 * <p><strong>Refused before any write.</strong> An unsupported currency is refused before the ledger is
 * asked. The obligation's existing accounts are then read once ({@link LedgerAccountStore#findAllOwned}, no
 * lock) and judged against the declaration before the first insert, so a wrong-shaped account already there -
 * or an account of this owner under any other purpose or currency, which would mean the obligation's id is
 * already another owner's - is refused with nothing written. The same judgement is applied again to each
 * account {@code createOrConverge} hands back, because a concurrent writer can land one between the read and
 * the insert; that refusal propagates and the caller's transaction rolls back, so nothing commits either way.
 *
 * <p><strong>Concurrency.</strong> In the caller's transaction (the purchase, `P12-TSK-013`), so the six
 * commit with the obligation or not at all. Each account goes through {@link LedgerAccountStore#createOrConverge},
 * whose arbiter is the ledger's partial unique index on {@code (owner_ref, purpose, currency)} - the owner kind
 * derives from the purpose, so this is the {@code (owner_kind, owner_ref, purpose, currency)} identity - and
 * whose savepoint lets a lost race converge without aborting the caller's other writes. Concurrent openers of
 * one obligation, on any number of instances, produce one set of six. <strong>The fixed order is
 * load-bearing</strong>: every opener inserts in {@link #purposes()} order, so two openers can only ever wait
 * on each other's first contested index entry, never hold one each of two (no deadlock cycle). No lock of its
 * own, no JVM state.
 *
 * <p>Unwired until `P12-TSK-013`: the purchase will call it after the obligation is born, in the same
 * transaction. It opens accounts and posts nothing - every posting rule is its own task's (ADR-0103 Follow-up).
 */
@RequiredArgsConstructor
public final class BnplAccounts {

    @NonNull private final LedgerAccountStore<Connection> store;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The six per-obligation purposes, in the order they are opened - {@link BnplLedgerAccounts}' order. */
    public static List<AccountPurpose> purposes() {
        return List.of(
                AccountPurpose.BNPL_PRINCIPAL,
                AccountPurpose.BNPL_PRINCIPAL_DUE,
                AccountPurpose.BNPL_INTEREST_ACCRUED,
                AccountPurpose.BNPL_INTEREST_DUE,
                AccountPurpose.BNPL_FEES_DUE,
                AccountPurpose.BNPL_CREDIT_BALANCE);
    }

    /**
     * The declared type of a per-obligation purpose (ADR-0103 decision 1): the five receivables ASSET, the
     * credit balance LIABILITY. Any other purpose is not an obligation account and is refused.
     */
    public static AccountType declaredTypeOf(AccountPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        return switch (purpose) {
            case BNPL_PRINCIPAL,
                    BNPL_PRINCIPAL_DUE,
                    BNPL_INTEREST_ACCRUED,
                    BNPL_INTEREST_DUE,
                    BNPL_FEES_DUE -> AccountType.ASSET;
            case BNPL_CREDIT_BALANCE -> AccountType.LIABILITY;
            default -> throw new IllegalArgumentException(
                    purpose + " is not a per-obligation purpose; the BNPL book's operational accounts are"
                            + " seeded by ledger V027 and resolved from the chart");
        };
    }

    /**
     * The obligation's six accounts in {@code currency}, opened or converged on, in the caller's transaction.
     * Only the obligation's own currency: an obligation never holds an account in another.
     *
     * @throws IllegalArgumentException {@code currency} is not one the platform supports - an obligation
     *     there would have no seeded merchant-fee, interest or fee income account to post against; refused
     *     before the ledger is asked
     * @throws IllegalStateException an account of this obligation exists, or converges, with a stored
     *     purpose, type, normal balance, currency or owner other than the declaration - a defect to
     *     investigate, never adopted (refused before any write when it is already there)
     */
    public BnplLedgerAccounts openFor(Connection unitOfWork, BnplObligationId obligationId, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(obligationId, "obligationId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (!SupportedCurrencies.ALL.contains(currency)) {
            throw new IllegalArgumentException(
                    "a BNPL obligation account is opened only in a supported currency - " + currency.code()
                            + " has no seeded BNPL income account (ledger V027)");
        }
        for (LedgerAccount existing : store.findAllOwned(unitOfWork, obligationId.value())) {
            judge(existing, obligationId, currency);
        }
        List<LedgerAccount> opened =
                purposes().stream().map(purpose -> open(unitOfWork, obligationId, purpose, currency)).toList();
        return new BnplLedgerAccounts(
                opened.get(0), opened.get(1), opened.get(2), opened.get(3), opened.get(4), opened.get(5));
    }

    private LedgerAccount open(
            Connection unitOfWork, BnplObligationId obligationId, AccountPurpose purpose, CurrencyCode currency) {
        LedgerAccount account =
                store.createOrConverge(
                                unitOfWork,
                                LedgerAccount.owned(
                                        ids, clock, declaredTypeOf(purpose), purpose, currency, obligationId.value()))
                        .account();
        if (account.purpose() != purpose) {
            throw refused(account, "is not the " + purpose + " the opener asked for");
        }
        judge(account, obligationId, currency);
        return account;
    }

    /** Refuses {@code account} unless it is exactly a declared account of this obligation in {@code currency}. */
    private static void judge(LedgerAccount account, BnplObligationId obligationId, CurrencyCode currency) {
        if (!purposes().contains(account.purpose())) {
            throw refused(account, "is not one of the six per-obligation purposes - the obligation's id already"
                    + " names another owner's account");
        }
        AccountType declared = declaredTypeOf(account.purpose());
        boolean asDeclared =
                account.accountType() == declared
                        && account.normalBalance() == declared.normalBalance()
                        && account.currency().equals(currency)
                        && account.ownerRef().filter(obligationId.value()::equals).isPresent();
        if (!asDeclared) {
            throw refused(account, "is not the declared " + declared + "/" + declared.normalBalance()
                    + " in " + currency.code());
        }
    }

    private static IllegalStateException refused(LedgerAccount account, String why) {
        // No obligation id in an exception message (the Phase 11 gate's Security criterion; P11-DOC-001).
        return new IllegalStateException(
                "a BNPL obligation's " + account.purpose() + " account exists as " + account.accountType() + "/"
                        + account.normalBalance() + " in " + account.currency().code() + ", which " + why
                        + " - refused, never adopted (INV-LED-06)");
    }
}
