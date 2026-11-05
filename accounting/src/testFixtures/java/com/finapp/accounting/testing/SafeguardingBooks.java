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
 * A safeguarding return's books in memory (`P14-TSK-019`): the journal {@link InMemoryTrialBalanceReads} holds, the acts that post to it as the real posting rules post, and {@link LendingFundedAsOfCutoff} answering over them: {@code committed} by the port's own real fold of each loan's frozen facts, {@code writtenOff} by the lines of each write-off entry, {@code heldPerAccount} by each holder's own balance. What the statements do against PostgreSQL is {@code
 * SafeguardingReturnDatabaseTest}'s and {@code SafeguardingCommittedDatabaseTest}'s (owed, C5). An act is recorded at noon UTC of its day unless stated, so a PROVISIONAL cut can fall between two. An act names a holder only where it matters; otherwise every wallet and payable line is {@link #THE_ACCOUNT}'s. Shared by accounting's suites and app's. Test code: it ships in no jar.
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
    public static final String LOAN_INTEREST_INCOME = "LOAN_INTEREST_INCOME/OPERATIONAL";
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

    /** The holder of every wallet and payable line an act does not name one for. */
    public static final String THE_ACCOUNT = "the-account";

    /** One write-off entry's principal: credit-positive, as the port reads it. */
    private record WriteOff(LocalDate posted, Instant createdAt, String currency, int scale, long principal) {}

    /** One line on an account of a purpose read per account, debit-positive. */
    private record AccountLine(String purpose, String holder, LocalDate posted, Instant createdAt, String currency, int scale, long debitLessCredit) {}

    public final InMemoryTrialBalanceReads journal = new InMemoryTrialBalanceReads();
    private final Map<String, LoanFacts> loans = new LinkedHashMap<>();
    private final List<WriteOff> writeOffs = new ArrayList<>();
    private final List<AccountLine> accountLines = new ArrayList<>();
    /** Set, and every one of the port's reads fails as a store's does: how a suite shows an unreadable proof is never a zero. */
    public volatile boolean unreadable;
    /** Set, and only {@link #committed} fails. */
    public volatile boolean committedUnreadable;
    /** Set, and only {@link #writtenOff} fails. */
    public volatile boolean writtenOffUnreadable;
    /** Set, and only {@link #heldPerAccount} fails. */
    public volatile boolean heldUnreadable;
    /** Added to what {@link #heldPerAccount} says is owed: how a suite shows the two reads drifting is seen. */
    public volatile long plantedDrift;

    /** Noon UTC of {@code day}. */
    public static Instant noon(LocalDate day) {
        return day.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    /** One balanced two-line entry, each account {@link #THE_ACCOUNT}'s. */
    public SafeguardingBooks post(LocalDate day, String currency, int scale, long minor, String debit, String credit) {
        return postFor(THE_ACCOUNT, day, currency, scale, minor, debit, credit);
    }

    /** One balanced two-line entry, a purpose read per account being {@code holder}'s. */
    public SafeguardingBooks postFor(String holder, LocalDate day, String currency, int scale, long minor, String debit, String credit) {
        journal.post(day, noon(day), currency, scale, minor, debit, credit);
        held(holder, debit, day, noon(day), currency, scale, minor);
        held(holder, credit, day, noon(day), currency, scale, -minor);
        return this;
    }

    /** One balanced two-line entry, a counterparty's account among the two being {@code counterparty}'s. */
    public SafeguardingBooks postCounterparty(String counterparty, LocalDate day, String currency, int scale, long minor, String debit, String credit) {
        journal.postFor(counterparty, day, noon(day), currency, scale, minor, debit, credit);
        held(THE_ACCOUNT, debit, day, noon(day), currency, scale, minor);
        held(THE_ACCOUNT, credit, day, noon(day), currency, scale, -minor);
        return this;
    }

    private void held(String holder, String key, LocalDate day, Instant at, String currency, int scale, long debitLessCredit) {
        String purpose = key.split("/", 2)[0];
        if (HELD_PER_ACCOUNT.contains(purpose)) {
            accountLines.add(new AccountLine(purpose, holder, day, at, currency, scale, debitLessCredit));
        }
    }

    /** A customer's money in: cash up, a wallet up. */
    public SafeguardingBooks deposit(LocalDate day, String currency, int scale, long minor) {
        return post(day, currency, scale, minor, CASH, WALLET);
    }

    /** {@code holder}'s money in. */
    public SafeguardingBooks depositFor(String holder, LocalDate day, String currency, int scale, long minor) {
        return postFor(holder, day, currency, scale, minor, CASH, WALLET);
    }

    /** A customer's money out: a wallet down, cash down. */
    public SafeguardingBooks withdraw(LocalDate day, String currency, int scale, long minor) {
        return post(day, currency, scale, minor, WALLET, CASH);
    }

    /** {@code holder}'s money out, whatever the wallet holds: a charged-back top-up already spent leaves it in debit. */
    public SafeguardingBooks withdrawFor(String holder, LocalDate day, String currency, int scale, long minor) {
        return postFor(holder, day, currency, scale, minor, WALLET, CASH);
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

    /** Rule 1, the fee deducted: DR LOAN_PRINCIPAL P / CR CUSTOMER_WALLET P - f, CR LOAN_FEE_INCOME f; the journal line dated the value date, as {@code LoanDisbursements} dates it. */
    public SafeguardingBooks disbursed(String loan, LocalDate on, long fee) {
        return disbursed(loan, on, on, noon(on), fee);
    }

    public SafeguardingBooks disbursed(String loan, LocalDate on) {
        return disbursed(loan, on, 0);
    }

    /** A disbursement whose journal line is dated {@code posted} and whose outcome is dated {@code valued}: what the real poster never does, so a suite can show what its one date is holding up. */
    public SafeguardingBooks disbursedOnTwoDates(String loan, LocalDate valued, LocalDate posted) {
        return disbursed(loan, valued, posted, noon(valued), 0);
    }

    /** A disbursement whose entry is created at noon of {@code on} and decided at {@code decidedAt}, as one transaction's two statements are: what a PROVISIONAL cut can fall between. */
    public SafeguardingBooks disbursedDecidedAt(String loan, LocalDate on, Instant decidedAt) {
        return disbursed(loan, on, on, decidedAt, 0);
    }

    private SafeguardingBooks disbursed(String loan, LocalDate valued, LocalDate posted, Instant decidedAt, long fee) {
        LoanFacts held = loans.get(loan);
        long principal = held.principal().longValueExact();
        journal.line(new InMemoryTrialBalanceReads.JournalLine(posted, noon(posted), "LOAN_PRINCIPAL", "LOAN", held.currency(), held.scale(), Side.DEBIT, principal));
        journal.line(new InMemoryTrialBalanceReads.JournalLine(posted, noon(posted), "CUSTOMER_WALLET", "CUSTOMER", held.currency(), held.scale(), Side.CREDIT, principal - fee));
        held(THE_ACCOUNT, WALLET, posted, noon(posted), held.currency(), held.scale(), -(principal - fee));
        if (fee > 0) {
            journal.line(new InMemoryTrialBalanceReads.JournalLine(posted, noon(posted), "LOAN_FEE_INCOME", "OPERATIONAL", held.currency(), held.scale(), Side.CREDIT, fee));
        }
        loans.put(loan, new LoanFacts(held.currency(), held.scale(), held.principal(), held.openedOn(), held.openedAt(), Optional.of(valued), Optional.empty(), Optional.of(decidedAt)));
        return this;
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

    @Override
    public List<HeldPerAccount> heldPerAccount(Connection snapshot, LocalDate cutoff, Optional<Instant> cut) {
        refuseWhen(unreadable || heldUnreadable);
        Map<String, Long> balances = new TreeMap<>();
        Map<String, AccountLine> exemplars = new TreeMap<>();
        for (AccountLine line : accountLines) {
            if (!line.posted().isAfter(cutoff) && cut.filter(line.createdAt()::isAfter).isEmpty()) {
                String account = String.join("\u0000", line.purpose(), line.currency(), Integer.toString(line.scale()), line.holder());
                balances.merge(account, line.debitLessCredit(), Long::sum);
                exemplars.putIfAbsent(account, line);
            }
        }
        Map<String, HeldPerAccount> folded = new TreeMap<>();
        balances.forEach((account, balance) -> {
            AccountLine line = exemplars.get(account);
            HeldPerAccount one = new HeldPerAccount(line.purpose(), line.currency(), line.scale(), BigInteger.valueOf(Math.max(-balance, 0)), BigInteger.valueOf(Math.max(balance, 0)), balance > 0 ? 1 : 0);
            folded.merge(String.join("\u0000", line.purpose(), line.currency(), Integer.toString(line.scale())), one,
                    (held, other) -> new HeldPerAccount(held.purpose(), held.currency(), held.scale(), held.owedMinor().add(other.owedMinor()), held.debtMinor().add(other.debtMinor()), held.debtors() + other.debtors()));
        });
        List<HeldPerAccount> answered = new ArrayList<>();
        folded.values().forEach(held -> answered.add(plantedDrift == 0 ? held : new HeldPerAccount(held.purpose(), held.currency(), held.scale(), held.owedMinor().add(BigInteger.valueOf(plantedDrift)), held.debtMinor(), held.debtors())));
        return answered;
    }

    private static void refuseWhen(boolean unreadable) {
        if (unreadable) {
            throw new AccountingStorageException("reading the return's frozen rows as of the cutoff: SQLSTATE 08006");
        }
    }
}
