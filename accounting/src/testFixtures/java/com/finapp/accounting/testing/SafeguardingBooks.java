package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.Side;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A safeguarding return's books in memory (`P14-TSK-019`): the journal {@link InMemoryTrialBalanceReads} holds, the acts that post to it as the real posting rules post, and {@link LendingFundedAsOfCutoff} answering over them: {@code committed} by the port's own real fold of each loan's frozen facts, {@code writtenOff} by the lines of each write-off entry. What the statements do against PostgreSQL is {@code
 * SafeguardingReturnDatabaseTest}'s (owed, C5). An act is recorded at noon UTC of its day unless stated, so a PROVISIONAL cut can fall between two. Shared by accounting's suites and app's. Test code: it ships in no jar.
 */
public final class SafeguardingBooks implements LendingFundedAsOfCutoff {

    public static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    public static final String WALLET = "CUSTOMER_WALLET/CUSTOMER";
    public static final String MERCHANT = "MERCHANT_PAYABLE/MERCHANT";
    public static final String CAPITAL = "LENDING_CAPITAL/OPERATIONAL";
    public static final String LOAN_PRINCIPAL = "LOAN_PRINCIPAL/LOAN";
    public static final String LOAN_PRINCIPAL_DUE = "LOAN_PRINCIPAL_DUE/LOAN";
    public static final String LOAN_INTEREST_DUE = "LOAN_INTEREST_DUE/LOAN";
    public static final String LOAN_FEE_INCOME = "LOAN_FEE_INCOME/OPERATIONAL";
    public static final String LOAN_WRITE_OFF = "LOAN_WRITE_OFF_EXPENSE/OPERATIONAL";
    public static final String BNPL_PRINCIPAL = "BNPL_PRINCIPAL/BNPL_OBLIGATION";
    public static final String BNPL_WRITE_OFF = "BNPL_WRITE_OFF_EXPENSE/OPERATIONAL";
    public static final String SETTLEMENT = "SETTLEMENT_CLEARING/OPERATIONAL";
    public static final String INSTANT = "INSTANT_CLEARING/OPERATIONAL";
    public static final String PAYOUT = "PAYOUT_CLEARING/OPERATIONAL";
    public static final String FX_PROVIDER = "FX_PROVIDER_CLEARING/COUNTERPARTY";
    public static final String CORRIDOR = "CORRIDOR_CLEARING/COUNTERPARTY";
    public static final String FX_POSITION = "FX_POSITION/OPERATIONAL";
    public static final String CHARGEBACK = "CHARGEBACK_RECOVERABLE/OPERATIONAL";
    public static final String SUSPENSE = "SUSPENSE_UNMATCHED/SUSPENSE";
    public static final String CREDIT_BALANCE = "LOAN_CREDIT_BALANCE/LOAN";
    public static final String BNPL_CREDIT_BALANCE = "BNPL_CREDIT_BALANCE/BNPL_OBLIGATION";

    /** One write-off entry's principal: credit-positive, as the port reads it. */
    private record WriteOff(LocalDate posted, Instant createdAt, String currency, int scale, long principal) {}

    public final InMemoryTrialBalanceReads journal = new InMemoryTrialBalanceReads();
    private final Map<String, LoanFacts> loans = new LinkedHashMap<>();
    private final List<WriteOff> writeOffs = new ArrayList<>();
    /** Set, and both of the port's reads fail as a store's do: how a suite shows an unreadable proof is never a zero. */
    public volatile boolean unreadable;
    /** Set, and only {@link #committed} fails. */
    public volatile boolean committedUnreadable;
    /** Set, and only {@link #writtenOff} fails. */
    public volatile boolean writtenOffUnreadable;

    /** Noon UTC of {@code day}. */
    public static Instant noon(LocalDate day) {
        return day.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    /** One balanced two-line entry. */
    public SafeguardingBooks post(LocalDate day, String currency, int scale, long minor, String debit, String credit) {
        journal.post(day, noon(day), currency, scale, minor, debit, credit);
        return this;
    }

    /** A customer's money in: cash up, a wallet up. */
    public SafeguardingBooks deposit(LocalDate day, String currency, int scale, long minor) {
        return post(day, currency, scale, minor, CASH, WALLET);
    }

    /** A customer's money out: a wallet down, cash down. */
    public SafeguardingBooks withdraw(LocalDate day, String currency, int scale, long minor) {
        return post(day, currency, scale, minor, WALLET, CASH);
    }

    /** Capital recognised: rule 0. */
    public SafeguardingBooks capital(LocalDate day, String currency, int scale, long minor) {
        return post(day, currency, scale, minor, CASH, CAPITAL);
    }

    /** An acceptance: nothing posts. */
    public SafeguardingBooks accepted(String loan, LocalDate on, String currency, int scale, long principal) {
        loans.put(loan, new LoanFacts(currency, scale, BigInteger.valueOf(principal), on, noon(on), Optional.empty(), Optional.empty(), Optional.empty()));
        return this;
    }

    /** Rule 1, the fee deducted: DR LOAN_PRINCIPAL P / CR CUSTOMER_WALLET P - f, CR LOAN_FEE_INCOME f; the journal line dated the value date. */
    public SafeguardingBooks disbursed(String loan, LocalDate on, long fee) {
        LoanFacts held = loans.get(loan);
        long principal = held.principal().longValueExact();
        journal.line(new InMemoryTrialBalanceReads.JournalLine(on, noon(on), "LOAN_PRINCIPAL", "LOAN", held.currency(), held.scale(), Side.DEBIT, principal));
        journal.line(new InMemoryTrialBalanceReads.JournalLine(on, noon(on), "CUSTOMER_WALLET", "CUSTOMER", held.currency(), held.scale(), Side.CREDIT, principal - fee));
        if (fee > 0) {
            journal.line(new InMemoryTrialBalanceReads.JournalLine(on, noon(on), "LOAN_FEE_INCOME", "OPERATIONAL", held.currency(), held.scale(), Side.CREDIT, fee));
        }
        loans.put(loan, new LoanFacts(held.currency(), held.scale(), held.principal(), held.openedOn(), held.openedAt(), Optional.of(on), Optional.empty(), Optional.of(noon(on))));
        return this;
    }

    public SafeguardingBooks disbursed(String loan, LocalDate on) {
        return disbursed(loan, on, 0);
    }

    /** A failed disbursement: {@code CANCELLED}, nothing posted. */
    public SafeguardingBooks cancelled(String loan, LocalDate on) {
        LoanFacts held = loans.get(loan);
        loans.put(loan, new LoanFacts(held.currency(), held.scale(), held.principal(), held.openedOn(), held.openedAt(), Optional.empty(), Optional.of(on), Optional.of(noon(on))));
        return this;
    }

    /** Rule 14: DR LOAN_WRITE_OFF_EXPENSE P + I / CR LOAN_PRINCIPAL P, CR LOAN_INTEREST_DUE I - the principal the port finds beside the expense. */
    public SafeguardingBooks writtenOff(LocalDate day, String currency, int scale, long principal, long interest) {
        journal.line(new InMemoryTrialBalanceReads.JournalLine(day, noon(day), "LOAN_WRITE_OFF_EXPENSE", "OPERATIONAL", currency, scale, Side.DEBIT, principal + interest));
        journal.line(new InMemoryTrialBalanceReads.JournalLine(day, noon(day), "LOAN_PRINCIPAL", "LOAN", currency, scale, Side.CREDIT, principal));
        if (interest > 0) {
            journal.line(new InMemoryTrialBalanceReads.JournalLine(day, noon(day), "LOAN_INTEREST_DUE", "LOAN", currency, scale, Side.CREDIT, interest));
        }
        writeOffs.add(new WriteOff(day, noon(day), currency, scale, principal));
        return this;
    }

    // ------------------------------------------------------------------ the port

    @Override
    public List<Committed> committed(Connection snapshot, LocalDate cutoff, Optional<Instant> cut) {
        refuseWhen(unreadable || committedUnreadable);
        return LendingFundedAsOfCutoff.committedAt(new ArrayList<>(loans.values()), cutoff, cut);
    }

    @Override
    public List<WrittenOff> writtenOff(Connection snapshot, LocalDate cutoff, Optional<Instant> cut) {
        refuseWhen(unreadable || writtenOffUnreadable);
        Map<String, WrittenOff> folded = new TreeMap<>();
        for (WriteOff held : writeOffs) {
            if (!held.posted().isAfter(cutoff) && cut.filter(held.createdAt()::isAfter).isEmpty()) {
                folded.merge(held.currency() + ":" + held.scale(), new WrittenOff(held.currency(), held.scale(), BigInteger.valueOf(held.principal()), 1),
                        (one, other) -> new WrittenOff(one.currency(), one.scale(), one.minor().add(other.minor()), one.lines() + other.lines()));
            }
        }
        return new ArrayList<>(folded.values());
    }

    private static void refuseWhen(boolean unreadable) {
        if (unreadable) {
            throw new AccountingStorageException("reading lending_funded as of the cutoff: SQLSTATE 08006");
        }
    }
}
