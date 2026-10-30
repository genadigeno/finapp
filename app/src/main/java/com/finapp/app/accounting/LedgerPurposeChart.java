package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.LedgerChart;
import com.finapp.bnpl.BnplAccounts;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.lending.LoanAccounts;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * accounting's {@link LedgerChart} over the ledger's own vocabulary and tables (`P14-TSK-003`; ADR-0113 decisions 1, 3;
 * {@code INV-ACC-06}) - in {@code app}, the one module that sees both: the purposes are the ledger's closed enum
 * {@link AccountPurpose} (the totality's domain - all of them, whether or not an account of one exists yet), each with
 * its {@link AccountPurpose#ownerKind()}; a purpose's types are the {@code account_type}s of the ledger's accounts of
 * that purpose, joined by the type its owning module declares for the accounts it opens at runtime ({@link #declared}).
 * The join fails closed: a declaration the ledger's rows contradict gives the purpose two types, which
 * {@code MappingJudgement} refuses - so a disagreement stops the activation instead of passing it.
 *
 * <p>Every statement is a {@code SELECT} under the application role's existing grants on {@code ledger.ledger_account}
 * and {@code ledger.counterparty}, on the caller's unit of work - the activation's own transaction - locking nothing
 * (the {@code LedgerMonitoredLedger} precedent of an {@code app} adapter reading the ledger's tables).
 */
public final class LedgerPurposeChart implements LedgerChart {

    /** The types the ledger's accounts of each purpose hold. */
    static final String TYPES = "SELECT DISTINCT purpose, account_type FROM ledger.ledger_account";

    /** The counterparties - a dimension under their purpose's GL account. */
    static final String COUNTERPARTIES = "SELECT code, kind FROM ledger.counterparty ORDER BY code";

    @Override
    public Chart read(Connection unitOfWork) {
        Map<AccountPurpose, Set<String>> types = new EnumMap<>(AccountPurpose.class);
        List<ChartCounterparty> counterparties = new ArrayList<>();
        try {
            try (PreparedStatement read = unitOfWork.prepareStatement(TYPES); ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    types.computeIfAbsent(AccountPurpose.valueOf(rows.getString(1)), p -> new TreeSet<>())
                            .add(rows.getString(2));
                }
            }
            try (PreparedStatement read = unitOfWork.prepareStatement(COUNTERPARTIES);
                    ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    counterparties.add(new ChartCounterparty(rows.getString(1), rows.getString(2)));
                }
            }
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the ledger's chart", failure));
        }
        return chart(types, counterparties);
    }

    /** The pure half of {@link #read}: every purpose of the enum, its observed types joined by its declared one. */
    static Chart chart(Map<AccountPurpose, Set<String>> observed, List<ChartCounterparty> counterparties) {
        List<ChartPurpose> purposes = new ArrayList<>();
        for (AccountPurpose purpose : AccountPurpose.values()) {
            Set<String> held = new TreeSet<>(observed.getOrDefault(purpose, Set.of()));
            declared(purpose).ifPresent(type -> held.add(type.name()));
            purposes.add(new ChartPurpose(purpose.name(), purpose.ownerKind().name(), held));
        }
        return new Chart(purposes, counterparties);
    }

    /**
     * The type the owning module gives the accounts of a purpose it opens at runtime: lending's
     * {@link LoanAccounts#declaredTypeOf} and bnpl's {@link BnplAccounts#declaredTypeOf} for the per-loan and
     * per-obligation purposes; a customer's wallet and a merchant's payable are the platform's {@code LIABILITY}
     * (ADR-0042 - {@code WalletAccounts}; {@code INV-MER-02} - {@code MerchantOnboarding}, each opening its account with
     * that type inline). Empty for a purpose the ledger seeds by migration - its rows are its type.
     */
    static Optional<AccountType> declared(AccountPurpose purpose) {
        return switch (purpose.ownerKind()) {
            case LOAN -> Optional.of(LoanAccounts.declaredTypeOf(purpose));
            case BNPL_OBLIGATION -> Optional.of(BnplAccounts.declaredTypeOf(purpose));
            case CUSTOMER, MERCHANT -> Optional.of(AccountType.LIABILITY);
            case OPERATIONAL, SUSPENSE, COUNTERPARTY -> Optional.empty();
        };
    }
}
