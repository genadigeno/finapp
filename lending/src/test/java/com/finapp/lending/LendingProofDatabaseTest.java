package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.CLOCK;
import static com.finapp.lending.DisbursementWorld.IDS;
import static com.finapp.lending.DisbursementWorld.LOAN_STORE;
import static com.finapp.lending.DisbursementWorld.acceptedLine;
import static com.finapp.lending.DisbursementWorld.asOwner;
import static com.finapp.lending.DisbursementWorld.businessDate;
import static com.finapp.lending.DisbursementWorld.cashAtBank;
import static com.finapp.lending.DisbursementWorld.correlation;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.employee;
import static com.finapp.lending.DisbursementWorld.inOneTransaction;
import static com.finapp.lending.DisbursementWorld.loanAccount;
import static com.finapp.lending.DisbursementWorld.netDebit;
import static com.finapp.lending.DisbursementWorld.scalar;
import static com.finapp.lending.InstalmentBillingDatabaseTest.partyOf;
import static com.finapp.lending.InstalmentBillingDatabaseTest.reproject;
import static com.finapp.lending.InstalmentBillingDatabaseTest.serviceUntilCaughtUp;
import static com.finapp.lending.InterestAccrualDatabaseTest.eur;
import static com.finapp.lending.InterestAccrualDatabaseTest.postFixture;
import static com.finapp.lending.InterestAccrualDatabaseTest.service;
import static com.finapp.lending.LendingProofsWorld.PROOFS;
import static com.finapp.lending.LendingProofsWorld.RECORDS;
import static com.finapp.lending.LendingProofsWorld.onSnapshot;
import static com.finapp.lending.LendingProofsWorld.proven;
import static com.finapp.lending.RepaymentDatabaseTest.due;
import static com.finapp.lending.RepaymentDatabaseTest.key;
import static com.finapp.lending.RepaymentDatabaseTest.repay;
import static com.finapp.lending.RepaymentDatabaseTest.topUp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationLine;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Lending's two proofs and the explanation against a real database (`P11-TSK-029`; PHASE_11_PLAN.md sections 9, 12.8,
 * 13.1 and 13.3; ADR-0092 points 8-9, ADR-0096 point 3; {@code INV-LND-01}, {@code INV-LND-03}, {@code INV-LND-04},
 * {@code INV-LND-05}, {@code INV-LND-09}, {@code INV-BAL-02}, {@code INV-LED-04}).
 *
 * <p>Every account here is built by the real paths - T-dis, T-svc, T-rep, T-drw, the waiver, the reversal, the amendment,
 * the prepayment and the payoff - and the only fixture clock is {@link InstalmentBillingDatabaseTest#reproject} on the
 * agreement's own repayment day before anything is serviced (a loan disbursed earlier, exactly as its pinned engine
 * projects it) and a line's opening and first draw dated earlier before anything is serviced. A forged fact - a byte
 * flipped, a posting no row names - is planted by the owner with the triggers off, and restored. Each reading is a fresh
 * read-only {@code REPEATABLE READ} snapshot of the application role.
 */
@Tag("database")
@DisplayName("the lending proofs and the explanation door (P11-TSK-029)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LendingProofDatabaseTest {

    // ------------------------------------------------------------------ the whole history

    @Test
    @DisplayName("an instalment loan's whole life proves and replays IDENTICAL at every step: disbursed 75 days ago on its own"
            + " repayment day, overpaid before its catch-up (the credit applied at billing), accrued, billed, charged late"
            + " fees, part-repaid, a repayment reversed, interest and a fee waived, restructured with its arrears"
            + " re-scheduled (format 3), caught up and prepaid (format 4), paid off - CLOSED, its six balances zero; the"
            + " trial balance zero per currency throughout (F1)")
    void anInstalmentLoansWholeLifeReplaysIdentically() {
        LoanId loan = aged(5_000_00, 75);
        topUp(partyOf(loan), 20_000_00);
        repay(loan, key(), 40_00); // held as credit: nothing billed yet
        assertProven(loan);
        serviceUntilCaughtUp(loan);
        assertThat(count("SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", loan.value()))
                .isGreaterThanOrEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending.credit_balance_application WHERE loan_id = ?", loan.value()))
                .as("the overpayment applied at the first billing").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.fee_assessment WHERE loan_id = ?", loan.value()))
                .as("unpaid past the late-fee day").isPositive();
        assertProven(loan);

        // Part-repaid, then that repayment reversed under four eyes.
        Repayments.Repaid part = repay(loan, key(), 25_00);
        assertProven(loan);
        RepaymentReversalDatabaseTest.proposeAndApprove(loan, part.repayment());
        assertProven(loan);

        // An interest waiver and a fee waiver, under four eyes.
        UUID item = UUID.fromString(scalar("SELECT id::text FROM lending.instalment_billing WHERE loan_id = ?"
                + " ORDER BY due_date DESC LIMIT 1", loan.value()));
        Waiver interest = FeeAndWaiverDatabaseTest.propose(loan, WaiverKind.INTEREST, item, 1_00, employee());
        FeeAndWaiverDatabaseTest.approve(loan, interest.id(), employee());
        UUID fee = UUID.fromString(scalar("SELECT id::text FROM lending.fee_assessment WHERE loan_id = ?"
                + " ORDER BY created_at LIMIT 1", loan.value()));
        Waiver waived = FeeAndWaiverDatabaseTest.propose(loan, WaiverKind.FEE, fee, 2_00, employee());
        FeeAndWaiverDatabaseTest.approve(loan, waived.id(), employee());
        assertProven(loan);

        // Restructured: a lower rate, its arrears re-scheduled - agreement and schedule version 2.
        int unbilled = Math.toIntExact(count("SELECT count(*) FROM lending.loan_instalment i WHERE i.loan_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM lending.instalment_billing b WHERE b.instalment_id = i.id)",
                loan.value()));
        LoanAmendment amendment = AmendmentDatabaseTest.propose(loan, AmendmentDatabaseTest.lowerRate(loan), unbilled + 1,
                true, employee());
        AmendmentDatabaseTest.approve(loan, amendment.id(), employee());
        AmendmentDatabaseTest.accept(loan, amendment.id());
        assertThat(count("SELECT count(*) FROM lending.loan_amendment_rescheduling WHERE loan_id = ?", loan.value()))
                .as("the arrears re-scheduled").isPositive();
        assertProven(loan);

        // Caught up, then prepaid: agreement and schedule version 3.
        repay(loan, key(), due(loan));
        PrepaymentDatabaseTest.prepay(loan, key(), 500_00);
        assertThat(count("SELECT max(version) FROM lending.loan_agreement WHERE loan_id = ?", loan.value())).isEqualTo(3);
        assertProven(loan);

        // Paid off: CLOSED, nothing owed either way.
        PayoffQuote quote = PayoffDatabaseTest.quote(loan, key(), Optional.empty()).quote();
        PayoffDatabaseTest.execute(loan, quote, key());
        assertThat(RepaymentDatabaseTest.loanStatus(loan)).isEqualTo("CLOSED");
        LendingProofs.Proven closed = assertProven(loan);
        assertThat(closed.subledger().terminalZero()).isTrue();
        assertThat(closed.subledger().figures()).allMatch(figure -> figure.ledger().isZero());
        assertThat(closed.replay().replayed()).as("not vacuous: every fact re-derived").isGreaterThan(80);
        assertTrialBalanceZero();
    }

    /** F1, {@code INV-ACC-01}: every currency's journal lines - the whole ledger's - debit what they credit. */
    static void assertTrialBalanceZero() {
        assertThat(scalar("SELECT coalesce(string_agg(currency || '=' || net, ','), '') FROM (SELECT currency,"
                + " sum(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) AS net"
                + " FROM ledger.journal_line GROUP BY currency) t WHERE net <> 0"))
                .as("the trial balance per currency").isEmpty();
    }

    @Test
    @DisplayName("a credit line's life proves and replays IDENTICAL: opened 70 days ago and drawn on its second day, stated"
            + " twice by the step (the minimum from the pinned statement engine), repaid beyond the minimum - the rest"
            + " paying principal down - drawn again, paid off (the open cycle stated early): CLOSED, six balances zero")
    void aCreditLinesLifeReplaysIdentically() {
        DisbursementWorld.Accepted line = acceptedLine(4_000_00);
        LocalDate opened = businessDate(line.loan()).minusDays(70);
        CreditLineStatementDatabaseTest.openOn(line, opened);
        CreditLineDraw first = CreditLineStatementDatabaseTest.draw(line, 1_500_00);
        CreditLineStatementDatabaseTest.dateDraw(first, opened.plusDays(1));
        serviceUntilCaughtUp(line.loan());
        assertThat(count("SELECT count(*) FROM lending.credit_line_statement WHERE loan_id = ?", line.loan().value()))
                .isGreaterThanOrEqualTo(2);
        assertProven(line.loan());

        topUp(line.party(), 5_000_00);
        CreditLineRepaymentDatabaseTest.repay(line, due(line.loan()) + 300_00);
        CreditLineStatementDatabaseTest.draw(line, 200_00);
        assertProven(line.loan());

        // Paid off: the open cycle stated early, everything repaid, the line CLOSED.
        PayoffQuote quote = PayoffDatabaseTest.quote(line.loan(), key(), Optional.empty()).quote();
        PayoffDatabaseTest.execute(line.loan(), quote, key());
        assertThat(RepaymentDatabaseTest.loanStatus(line.loan())).isEqualTo("CLOSED");
        LendingProofs.Proven closed = assertProven(line.loan());
        assertThat(closed.subledger().figures()).allMatch(figure -> figure.ledger().isZero());
    }

    // ------------------------------------------------------------------ the replay catches a flipped byte

    @Test
    @DisplayName("a byte flipped in an accrual, a billing, an allocation, an engine version or an agreement's canonical"
            + " terms (re-hashed, as a forger would) makes the replay DIVERGED naming that fact's kind - and the agreement's"
            + " only through the terms_sha256 re-verification; each restored, the account replays IDENTICAL again")
    void aByteFlippedInAnyFactDiverges() {
        LoanId loan = aged(3_000_00, 70);
        topUp(partyOf(loan), 10_000_00);
        serviceUntilCaughtUp(loan);
        repay(loan, key(), 30_00);
        assertProven(loan);
        UUID id = loan.value();

        String accrual = scalar("SELECT id::text FROM lending.interest_accrual WHERE loan_id = ? AND posted_minor > 0"
                + " ORDER BY accrual_date LIMIT 1", id);
        flipped(loan, "UPDATE lending.interest_accrual SET posted_minor = posted_minor + 1 WHERE id = '" + accrual + "'",
                "UPDATE lending.interest_accrual SET posted_minor = posted_minor - 1 WHERE id = '" + accrual + "'",
                LoanReplayProof.Check.ACCRUAL);
        flipped(loan, "UPDATE lending.interest_accrual SET accrual_engine_version = 2 WHERE id = '" + accrual + "'",
                "UPDATE lending.interest_accrual SET accrual_engine_version = 1 WHERE id = '" + accrual + "'",
                LoanReplayProof.Check.ENGINE_ABSENT);

        String billing = scalar("SELECT id::text FROM lending.instalment_billing WHERE loan_id = ? ORDER BY due_date LIMIT 1",
                id);
        flipped(loan, "UPDATE lending.instalment_billing SET principal_due_minor = principal_due_minor + 1 WHERE id = '"
                        + billing + "'",
                "UPDATE lending.instalment_billing SET principal_due_minor = principal_due_minor - 1 WHERE id = '"
                        + billing + "'",
                LoanReplayProof.Check.BILLING);

        String schedule = scalar("SELECT id::text FROM lending.repayment_schedule WHERE loan_id = ? AND schedule_version = 1",
                id);
        flipped(loan, "UPDATE lending.repayment_schedule SET total_interest_minor = total_interest_minor + 1 WHERE id = '"
                        + schedule + "'",
                "UPDATE lending.repayment_schedule SET total_interest_minor = total_interest_minor - 1 WHERE id = '"
                        + schedule + "'",
                LoanReplayProof.Check.SCHEDULE);

        List<String> lines = new ArrayList<>();
        for (String row : scalar("SELECT string_agg(a.id::text, ',' ORDER BY a.ordinal) FROM lending.repayment_allocation a"
                + " JOIN lending.repayment r ON r.id = a.repayment_id WHERE r.loan_id = ? AND r.kind = 'CUSTOMER'"
                + " AND a.target = 'DUE_ITEM'", id).split(",")) {
            lines.add(row);
        }
        assertThat(lines).as("the repayment paid two components at least").hasSizeGreaterThanOrEqualTo(2);
        String move = "UPDATE lending.repayment_allocation SET amount_minor = amount_minor %s 1 WHERE id = '%s';";
        flipped(loan, String.format(move, "+", lines.get(0)) + String.format(move, "-", lines.get(1)),
                String.format(move, "-", lines.get(0)) + String.format(move, "+", lines.get(1)),
                LoanReplayProof.Check.ALLOCATION);

        // The agreement: a byte of its canonical terms flipped - the template version - and re-hashed, so the CHECK
        // admits it. Only re-rendering the terms from the rows and re-verifying the hash can see it.
        String terms = scalar("SELECT canonical_terms FROM lending.loan_agreement WHERE loan_id = ? AND version = 1", id);
        String forged = terms.replace("\"template\":{", "\"template\":{\"x\":\"1\",");
        assertThat(forged).isNotEqualTo(terms);
        String agreement = scalar("SELECT id::text FROM lending.loan_agreement WHERE loan_id = ? AND version = 1", id);
        LendingProofs.Proven seen = flipped(loan, terms(agreement, forged), terms(agreement, terms),
                LoanReplayProof.Check.TERMS);
        assertThat(seen.replay().checks()).as("only the re-verification sees it").containsExactly(
                LoanReplayProof.Check.TERMS);
        assertThat(seen.subledger().holds()).as("no money moved: the subledger proof still holds").isTrue();
    }

    @Test
    @DisplayName("a principal line value-dated into a date already accrued - as a clock stepped back across a midnight"
            + " dated a repayment before the Phase 11 gate's R1 - makes the replay DIVERGED naming the accrual whose base"
            + " the final ledger no longer explains; restored, IDENTICAL")
    void aPrincipalLineValuedIntoAnAccruedDateDiverges() {
        LoanId loan = aged(3_100_00, 70);
        topUp(partyOf(loan), 10_000_00);
        serviceUntilCaughtUp(loan);
        Repayments.Repaid repaid = repay(loan, key(), due(loan) + 500_00);
        assertThat(repaid.repayment().to(AllocationLine.Target.NOT_DUE_PRINCIPAL).isPositive()
                || repaid.repayment().paid(AllocationComponent.PRINCIPAL).isPositive()).as("it moved principal").isTrue();
        assertProven(loan);
        LocalDate accrued = LocalDate.parse(scalar("SELECT max(accrual_date)::text FROM lending.interest_accrual"
                + " WHERE loan_id = ?", loan.value()));
        String entry = repaid.repayment().journalEntry().value().toString();
        String valued = scalar("SELECT value_date::text FROM ledger.journal_entry WHERE id = ?::uuid", entry);
        flipped(loan, "UPDATE ledger.journal_entry SET value_date = DATE '" + accrued + "' WHERE id = '" + entry + "'",
                "UPDATE ledger.journal_entry SET value_date = DATE '" + valued + "' WHERE id = '" + entry + "'",
                LoanReplayProof.Check.ACCRUAL);
    }

    @Test
    @DisplayName("a credit line's statement with a byte flipped in its interest (its minimum kept whole, so the CHECK admits"
            + " it) makes the replay DIVERGED naming the statement; restored, IDENTICAL")
    void aFlippedStatementDiverges() {
        DisbursementWorld.Accepted line = acceptedLine(3_000_00);
        LocalDate opened = businessDate(line.loan()).minusDays(45);
        CreditLineStatementDatabaseTest.openOn(line, opened);
        CreditLineStatementDatabaseTest.dateDraw(CreditLineStatementDatabaseTest.draw(line, 1_000_00), opened.plusDays(1));
        serviceUntilCaughtUp(line.loan());
        assertProven(line.loan());
        String statement = scalar("SELECT id::text FROM lending.credit_line_statement WHERE loan_id = ?"
                + " AND interest_billed_minor > 0 ORDER BY cycle_end LIMIT 1", line.loan().value());
        String flip = "UPDATE lending.credit_line_statement SET interest_billed_minor = interest_billed_minor %1$s 1,"
                + " minimum_payment_minor = minimum_payment_minor %1$s 1 WHERE id = '" + statement + "'";
        flipped(line.loan(), String.format(flip, "+"), String.format(flip, "-"), LoanReplayProof.Check.STATEMENT);
    }

    // ------------------------------------------------------------------ the subledger proof reads journal lines

    @Test
    @DisplayName("a posting to a loan account no lending row names - an entry the ledger accepted, then a raw-SQL entry"
            + " beneath every door - fails the subledger proof (the account's identity and the unexplained entry), never"
            + " corrected; a raw edit of the ledger's balance projection is invisible to it: the proof reads journal lines"
            + " and stays green")
    void aPostingNoRowNamesFailsAndAProjectionEditDoesNot() {
        LoanId loan = aged(2_000_00, 40);
        serviceUntilCaughtUp(loan);
        assertProven(loan);

        asOwner("UPDATE ledger.account_balance SET posted_minor = posted_minor + 99999 WHERE ledger_account_id = '"
                + loanAccount(loan, AccountPurpose.LOAN_PRINCIPAL).value() + "'");
        LendingProofs.Proven projected = proven(loan);
        assertThat(projected.subledger().holds()).as("the projection is not the ledger's balance").isTrue();
        assertThat(projected.replay().verdict()).isEqualTo(LoanReplayProof.Verdict.IDENTICAL);

        postFixture("p11t29-rogue:" + UUID.randomUUID(), businessDate(loan), List.of(
                new JournalLine(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE), Direction.DEBIT, eur(7_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(7_00))));
        LendingProofs.Proven rogue = proven(loan);
        assertThat(rogue.subledger().holds()).isFalse();
        assertThat(rogue.subledger().failures()).contains("LOAN_FEES_DUE", "UNEXPLAINED_ENTRIES");
        assertThat(rogue.subledger().figure(AccountPurpose.LOAN_FEES_DUE).ledger().minorUnits()
                - rogue.subledger().figure(AccountPurpose.LOAN_FEES_DUE).rows().minorUnits()).isEqualTo(7_00);
        assertThat(rogue.subledger().toString()).as("identifiers and arm names, never an amount").doesNotContain("700");
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE)).minorUnits())
                .as("nothing repaired: the ledger still holds the rogue line").isGreaterThanOrEqualTo(7_00);

        // A raw-SQL posting beneath every door - a balanced entry straight into the ledger's tables on the loan's
        // LOAN_INTEREST_DUE - fails it the same way: the proof reads what the ledger holds, however it got there.
        String entry = IDS.next().toString();
        asOwner("INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, actor_id,"
                + " correlation_id, causation_id, idempotency_scope, created_at) VALUES ('" + entry + "', DATE '"
                + businessDate(loan) + "', DATE '" + businessDate(loan) + "', 'POSTING', 'raw', 'raw', 'raw', 'raw',"
                + " 'raw:" + entry + "', statement_timestamp());"
                + " INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction, amount_minor, currency,"
                + " scale, seq) VALUES (gen_random_uuid(), '" + entry + "', '"
                + loanAccount(loan, AccountPurpose.LOAN_INTEREST_DUE).value() + "', 'DEBIT', 300, 'EUR', 2, 0),"
                + " (gen_random_uuid(), '" + entry + "', '" + cashAtBank().value() + "', 'CREDIT', 300, 'EUR', 2, 1)");
        assertThat(proven(loan).subledger().failures()).contains("LOAN_INTEREST_DUE", "LOAN_FEES_DUE",
                "UNEXPLAINED_ENTRIES");
    }

    @Test
    @DisplayName("no due account is negative: a raw-SQL entry beneath every door crediting a loan's LOAN_FEES_DUE below zero"
            + " - no fee ever assessed - fails the subledger proof's NEGATIVE rule, beside the figure and the unexplained"
            + " entry; the account is named, never an amount, and nothing is corrected")
    void aNegativeDueAccountFailsTheNegativeRule() {
        LoanId loan = aged(1_800_00, 20);
        LendingProofs.Proven clean = assertProven(loan);
        assertThat(clean.subledger().noneNegative()).isTrue();

        String entry = IDS.next().toString();
        asOwner("INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type, reference, actor_id,"
                + " correlation_id, causation_id, idempotency_scope, created_at) VALUES ('" + entry + "', DATE '"
                + businessDate(loan) + "', DATE '" + businessDate(loan) + "', 'POSTING', 'raw', 'raw', 'raw', 'raw',"
                + " 'raw:" + entry + "', statement_timestamp());"
                + " INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction, amount_minor, currency,"
                + " scale, seq) VALUES (gen_random_uuid(), '" + entry + "', '"
                + loanAccount(loan, AccountPurpose.LOAN_FEES_DUE).value() + "', 'CREDIT', 500, 'EUR', 2, 0),"
                + " (gen_random_uuid(), '" + entry + "', '" + cashAtBank().value() + "', 'DEBIT', 500, 'EUR', 2, 1)");

        LoanSubledgerProof.Verdict negative = proven(loan).subledger();
        assertThat(negative.figure(AccountPurpose.LOAN_FEES_DUE).ledger().isNegative()).as("fees due below zero").isTrue();
        assertThat(negative.noneNegative()).as("the NEGATIVE rule").isFalse();
        assertThat(negative.holds()).isFalse();
        assertThat(negative.failures()).contains("NEGATIVE", "LOAN_FEES_DUE", "UNEXPLAINED_ENTRIES");
        assertThat(negative.toString()).as("identifiers and arm names, never an amount").contains("NEGATIVE")
                .doesNotContain("500");
        assertThat(netDebit(loanAccount(loan, AccountPurpose.LOAN_FEES_DUE)).minorUnits())
                .as("nothing repaired: the ledger still holds the rogue line").isEqualTo(-5_00);
    }

    @Test
    @DisplayName("the database never checks that a POSTED disbursement's journal_entry_id names a real entry (lending V028):"
            + " a disbursement planted POSTED naming an entry the ledger never held - nothing posted to the loan's accounts"
            + " - fails that loan's subledger proof on LOAN_PRINCIPAL (the rows claim the agreement's principal, the"
            + " journal lines hold none); restored, it proves again")
    void aDisbursementNamingNoEntryFailsTheProof() {
        DisbursementWorld.Accepted pending = DisbursementWorld.acceptedLoan(1_700_00, 12);
        LoanId loan = pending.loan();
        assertThat(subledger(loan).holds()).as("PENDING, nothing claimed, nothing posted").isTrue();
        String id = loan.value().toString();
        // An identifier of the platform's own shape (UUIDv7, ADR-0013) that no journal entry carries.
        String neverPosted = IDS.next().toString();
        asOwner("UPDATE lending.loan_disbursement SET status = 'POSTED', journal_entry_id = '" + neverPosted + "',"
                + " value_date = (statement_timestamp() AT TIME ZONE 'UTC')::date, decided_at = statement_timestamp()"
                + " WHERE loan_id = '" + id + "'");
        try {
            assertThat(count("SELECT count(*) FROM lending.loan_disbursement d WHERE d.loan_id = ? AND d.status = 'POSTED'"
                    + " AND NOT EXISTS (SELECT 1 FROM ledger.journal_entry e WHERE e.id = d.journal_entry_id)", loan.value()))
                    .as("POSTED, naming an entry that does not exist").isEqualTo(1);
            LoanSubledgerProof.Verdict forged = subledger(loan);
            assertThat(forged.loan()).as("the verdict names the loan").isEqualTo(loan);
            assertThat(forged.holds()).isFalse();
            assertThat(forged.failures()).contains("LOAN_PRINCIPAL");
            assertThat(forged.figure(AccountPurpose.LOAN_PRINCIPAL).rows()).as("the rows claim the principal")
                    .isEqualTo(pending.principal());
            assertThat(forged.figure(AccountPurpose.LOAN_PRINCIPAL).ledger().isZero()).as("the ledger holds none")
                    .isTrue();
        } finally {
            asOwner("UPDATE lending.loan_disbursement SET status = 'PENDING', journal_entry_id = NULL, value_date = NULL,"
                    + " decided_at = NULL WHERE loan_id = '" + id + "'");
        }
        assertThat(subledger(loan).holds()).as("restored").isTrue();
    }

    /** {@code loan}'s subledger verdict alone, on a fresh snapshot. */
    private static LoanSubledgerProof.Verdict subledger(LoanId loan) {
        return onSnapshot(snapshot -> LendingProofsWorld.SUBLEDGER.prove(RECORDS.read(snapshot, loan).orElseThrow()));
    }

    @Test
    @DisplayName("every closed account is zero: two loans paid off and a line closed prove with six zero balances; a line"
            + " posted to a CLOSED account afterwards fails it - TERMINAL_NOT_ZERO and the unexplained entry")
    void everyClosedAccountIsZero() {
        List<LoanId> closed = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            LoanId loan = aged(1_500_00 + i * 100_00, 40);
            topUp(partyOf(loan), 3_000_00);
            serviceUntilCaughtUp(loan);
            PayoffQuote quote = PayoffDatabaseTest.quote(loan, key(), Optional.empty()).quote();
            PayoffDatabaseTest.execute(loan, quote, key());
            closed.add(loan);
        }
        DisbursementWorld.Accepted line = acceptedLine(1_000_00);
        CreditLineRepaymentDatabaseTest.requestClosure(line, key());
        closed.add(line.loan());
        for (LoanId loan : closed) {
            assertThat(RepaymentDatabaseTest.loanStatus(loan)).isEqualTo("CLOSED");
            LendingProofs.Proven proven = assertProven(loan);
            assertThat(proven.subledger().terminalZero()).isTrue();
            assertThat(proven.subledger().figures()).allMatch(figure -> figure.ledger().isZero());
        }
        LoanId reopened = closed.get(0);
        postFixture("p11t29-after-closure:" + UUID.randomUUID(), businessDate(reopened), List.of(
                new JournalLine(loanAccount(reopened, AccountPurpose.LOAN_PRINCIPAL), Direction.DEBIT, eur(1_00)),
                new JournalLine(cashAtBank(), Direction.CREDIT, eur(1_00))));
        assertThat(proven(reopened).subledger().failures()).contains("TERMINAL_NOT_ZERO", "UNEXPLAINED_ENTRIES",
                "LOAN_PRINCIPAL");
    }

    // ------------------------------------------------------------------ ten readers, one verdict

    @Test
    @DisplayName("a proof during servicing is stable: while ten loans are caught up by the servicing step, two lines are"
            + " drawn and repaid and every loan is repaid, readers on their own snapshots prove every account again and"
            + " again - every reading HOLDS and IDENTICAL whatever commits beside it, and the last at rest too")
    void aProofDuringServicingIsStable() throws Exception {
        List<LoanId> loans = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            LoanId loan = aged(1_000_00 + i * 10_00, 35 + i);
            topUp(partyOf(loan), 3_000_00);
            loans.add(loan);
        }
        List<DisbursementWorld.Accepted> lines = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            DisbursementWorld.Accepted line = acceptedLine(2_000_00);
            topUp(line.party(), 3_000_00);
            lines.add(line);
        }
        List<LoanId> all = new ArrayList<>(loans);
        lines.forEach(line -> all.add(line.loan()));

        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicInteger readings = new AtomicInteger();
        Set<String> broken = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> writers = new ArrayList<>();
            writers.add(pool.submit(() -> {
                start.await();
                for (LoanId loan : loans) {
                    serviceUntilCaughtUp(loan);
                    repay(loan, key(), Math.max(1_00, due(loan)));
                }
                return null;
            }));
            writers.add(pool.submit(() -> {
                start.await();
                for (int round = 0; round < 3; round++) {
                    for (DisbursementWorld.Accepted line : lines) {
                        CreditLineStatementDatabaseTest.draw(line, 100_00);
                        CreditLineRepaymentDatabaseTest.repay(line, 50_00);
                    }
                }
                return null;
            }));
            List<Future<?>> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                readers.add(pool.submit(() -> {
                    start.await();
                    do {
                        onSnapshot(snapshot -> {
                            for (LoanId loan : all) {
                                LendingProofs.Proven proven = PROOFS.prove(snapshot, loan).orElseThrow();
                                if (!proven.subledger().holds()
                                        || proven.replay().verdict() != LoanReplayProof.Verdict.IDENTICAL) {
                                    broken.add(proven.subledger() + " " + proven.replay().findings());
                                }
                            }
                            return null;
                        });
                        readings.incrementAndGet();
                    } while (writing.get());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(5, TimeUnit.MINUTES);
            }
            writing.set(false);
            for (Future<?> reader : readers) {
                reader.get(5, TimeUnit.MINUTES);
            }
        } finally {
            writing.set(false);
            pool.shutdownNow();
        }
        assertThat(readings.get()).as("counted over rounds: the readers read while the writers wrote").isGreaterThan(8);
        assertThat(broken).as("every snapshot is one state: a step half-seen would be a red proof").isEmpty();
        all.forEach(LendingProofDatabaseTest::assertProven);
    }

    // ------------------------------------------------------------------ the lock's order (lending V025)

    @Test
    @DisplayName("a decision is ordered by the lock, never by its transaction's start (lending V025): a reversal's approval"
            + " whose transaction began before a later repayment, and was decided after it, replays after it - the later"
            + " repayment re-derived against the due state it saw, IDENTICAL")
    void aDecisionIsOrderedByTheLockNotByItsTransactionsStart() throws Exception {
        LoanId loan = aged(3_000_00, 40);
        topUp(partyOf(loan), 10_000_00);
        serviceUntilCaughtUp(loan);
        long interest = count("SELECT interest_due_minor FROM lending.instalment_billing WHERE loan_id = ?"
                + " ORDER BY due_date LIMIT 1", loan.value());
        Repayments.Repaid first = repay(loan, key(), interest);
        RepaymentReversal proposal = RepaymentReversalDatabaseTest.propose(loan, first.repayment().id(), employee());

        try (java.sql.Connection approval = com.finapp.platform.testing.database.DatabaseRoles.application()) {
            approval.setAutoCommit(false);
            try (java.sql.Statement begin = approval.createStatement()) {
                begin.execute("SELECT 1"); // the approval's transaction begins - its transaction_timestamp() fixed here
            }
            Thread.sleep(20);
            repay(loan, key(), due(loan)); // a later repayment, committed while the approval's transaction is open
            Thread.sleep(20);
            Actor approver = employee();
            RepaymentReversalDatabaseTest.as(approver, correlation -> RepaymentReversalDatabaseTest.REVERSALS.approve(
                    approval, loan, first.repayment().id(), proposal.id(), "matched the bank statement", approver,
                    correlation));
            approval.commit();
        }
        // Placed after the repayment it waited behind in the record's order - the replay's (lending.record_order, drawn
        // under L3) - never compared by the clock's stamps, which a database clock stepped back between the two reverses
        // (P11-TSK-036).
        assertThat(count("SELECT count(*) FROM lending.repayment_reversal WHERE loan_id = ? AND status = 'APPROVED'"
                + " AND decided_order > (SELECT max(recorded_order) FROM lending.repayment WHERE loan_id = ?)", loan.value(),
                loan.value())).as("ordered after the repayment it waited behind").isEqualTo(1);
        assertProven(loan);
    }

    @Test
    @DisplayName("a database clock stepped back never reorders the history (lending V025): a prepayment's repayment,"
            + " agreement version 2 and schedule version 2 stamped an hour BEFORE the catch-up that preceded them - as a"
            + " backward step of the clock would stamp them - still replay IDENTICAL: the replay walks recorded_order,"
            + " drawn under the loan's lock, never the wall clock")
    void aClockSteppedBackNeverReordersTheHistory() {
        LoanId loan = aged(4_000_00, 40);
        topUp(partyOf(loan), 10_000_00);
        serviceUntilCaughtUp(loan);
        repay(loan, key(), due(loan));
        PrepaymentDatabaseTest.prepay(loan, key(), 400_00);
        assertProven(loan);
        String id = loan.value().toString();
        String earlier = "(SELECT min(created_at) - interval '1 hour' FROM lending.interest_accrual WHERE loan_id = '" + id
                + "')";
        asOwner("UPDATE lending.repayment SET created_at = " + earlier + " WHERE loan_id = '" + id
                + "' AND kind = 'PREPAYMENT';"
                + " UPDATE lending.loan_agreement SET created_at = " + earlier + ", effective_from = effective_from"
                + " WHERE loan_id = '" + id + "' AND version = 2;"
                + " UPDATE lending.repayment_schedule SET created_at = " + earlier + " WHERE loan_id = '" + id
                + "' AND schedule_version = 2");
        assertThat(count("SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ? AND version = 2 AND created_at <"
                + " (SELECT min(created_at) FROM lending.interest_accrual WHERE loan_id = ?)", loan.value(), loan.value()))
                .as("the stamps say the prepayment came first").isEqualTo(1);
        assertProven(loan);
    }

    // ------------------------------------------------------------------ the explanation

    @Test
    @DisplayName("the explanation is complete from rows: every row of the loan's tables and every journal entry on its six"
            + " accounts is in it, each entry named by the row that explains it, the six balances its journal lines' -"
            + " audited lending.ExplanationRead with counts and the verdict, never an amount; the loan's own party's"
            + " investigator is refused before anything is read; an unknown loan is LoanNotFound")
    void theExplanationIsCompleteFromRows() {
        LoanId loan = aged(2_500_00, 75);
        topUp(partyOf(loan), 10_000_00);
        repay(loan, key(), 10_00);
        serviceUntilCaughtUp(loan);
        repay(loan, key(), 60_00);
        UUID investigatorsParty = UUID.randomUUID();
        Actor investigator = employee();
        LoanExplanations explanations = new LoanExplanations(RECORDS, PROOFS, LendingProofsWorld.SUBLEDGER, LOAN_STORE,
                (unitOfWork, actor) -> Optional.of(actor.id().equals(investigator.id()) ? investigatorsParty
                        : partyOf(loan)),
                new JdbcAuditWriter(), IDS, CLOCK);
        CorrelationId correlation = correlation();

        LoanExplanations.Explanation explanation = inOneTransaction(uow -> {
            repeatableRead(uow);
            return explanations.explain(uow, loan, investigator, correlation);
        });
        LoanRecord record = explanation.record();
        UUID id = loan.value();
        assertThat(record.agreements()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.loan_agreement WHERE loan_id = ?", id)));
        assertThat(record.schedules()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.repayment_schedule WHERE loan_id = ?", id)));
        assertThat(record.schedules().get(0).instalments()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.loan_instalment WHERE loan_id = ?", id)));
        assertThat(record.accruals()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.interest_accrual WHERE loan_id = ?", id))).isNotEmpty();
        assertThat(record.billings()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.instalment_billing WHERE loan_id = ?", id))).isNotEmpty();
        assertThat(record.repayments()).hasSize(2);
        assertThat(record.repayments().stream().mapToLong(payment -> payment.lines().size()).sum())
                .isEqualTo(count("SELECT count(*) FROM lending.repayment_allocation a JOIN lending.repayment r"
                        + " ON r.id = a.repayment_id WHERE r.loan_id = ?", id));
        assertThat(record.applications()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.credit_balance_application WHERE loan_id = ?", id))).isNotEmpty();
        assertThat(record.fees()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.fee_assessment WHERE loan_id = ?", id)));
        assertThat(record.conditions()).hasSize(Math.toIntExact(count(
                "SELECT count(*) FROM lending.loan_condition_event WHERE loan_id = ?", id)));
        assertThat(record.disbursement()).isPresent();
        assertThat(record.offer()).isPresent();
        assertThat(record.ledgerAccounts()).hasSize(6);
        assertThat(record.journalLines()).hasSize(Math.toIntExact(count("SELECT count(*) FROM ledger.journal_line l"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id WHERE a.owner_ref = ?", id)));
        assertThat(LoanSubledgerProof.namedEntries(record).keySet())
                .as("every entry on the loan's accounts named by the row that explains it")
                .containsAll(record.journalLines().stream().map(line -> line.entry()).toList());
        assertThat(explanation.subledger().holds()).isTrue();
        for (AccountPurpose purpose : LoanSubledgerProof.PURPOSES) {
            long ledger = netDebit(loanAccount(loan, purpose)).minorUnits();
            assertThat(explanation.subledger().figure(purpose).ledger().minorUnits())
                    .isEqualTo(purpose == AccountPurpose.LOAN_CREDIT_BALANCE ? -ledger : ledger);
        }
        assertThat(record.toString()).doesNotContain("250000");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.ExplanationRead'"
                + " AND target_id = ? AND actor_id = ? AND outcome = 'SUCCEEDED' AND change_summary LIKE"
                + " '%subledger=HOLDS%' AND change_summary NOT LIKE '%2500%'", id.toString(), investigator.id()))
                .isEqualTo(1);

        Actor insider = employee();
        assertThatExceptionOfType(RepaymentReversals.SelfDealingRefused.class).isThrownBy(() -> inOneTransaction(uow -> {
            repeatableRead(uow);
            return explanations.explain(uow, loan, insider, correlation);
        }));
        assertThatExceptionOfType(LoanExplanations.LoanNotFound.class).isThrownBy(() -> inOneTransaction(uow ->
                explanations.explain(uow, LoanId.next(IDS), investigator, correlation)));
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'lending.ExplanationRead'"
                + " AND target_id = ?", id.toString())).as("a refused read serves and records nothing").isEqualTo(1);
    }

    // ------------------------------------------------------------------ plumbing

    /** A fresh twelve-month loan disbursed by T-dis, its disbursement moved {@code daysAgo} back on its own repayment day. */
    static LoanId aged(long minor, int daysAgo) {
        LoanId loan = InterestAccrualDatabaseTest.disbursed(minor, 12);
        int day = Math.toIntExact(count("SELECT repayment_day FROM lending.loan_agreement WHERE loan_id = ? AND version = 1",
                loan.value()));
        reproject(loan, businessDate(loan).minusDays(daysAgo), day);
        return loan;
    }

    /** {@code loan} proven on a fresh snapshot: its subledger proof holds and it replays IDENTICAL, non-vacuously. */
    static LendingProofs.Proven assertProven(LoanId loan) {
        LendingProofs.Proven proven = proven(loan);
        assertThat(proven.subledger().holds()).as("the subledger proof: %s", proven.subledger()).isTrue();
        assertThat(proven.replay().findings()).as("the replay of %s", loan.value()).isEmpty();
        assertThat(proven.replay().verdict()).isEqualTo(LoanReplayProof.Verdict.IDENTICAL);
        assertThat(proven.replay().replayed()).isPositive();
        return proven;
    }

    /** {@code flip} planted: the replay names {@code check}; {@code restore} planted: IDENTICAL again. */
    static LendingProofs.Proven flipped(LoanId loan, String flip, String restore, LoanReplayProof.Check check) {
        asOwner(flip);
        LendingProofs.Proven seen;
        try {
            seen = proven(loan);
            assertThat(seen.replay().verdict()).as(flip).isEqualTo(LoanReplayProof.Verdict.DIVERGED);
            assertThat(seen.replay().checks()).as(flip).contains(check);
        } finally {
            asOwner(restore);
        }
        assertProven(loan);
        return seen;
    }

    private static String terms(String agreement, String canonical) {
        return "UPDATE lending.loan_agreement SET canonical_terms = '" + canonical + "', terms_sha256 ="
                + " sha256(convert_to('" + canonical + "', 'UTF8')) WHERE id = '" + agreement + "'";
    }

    private static void repeatableRead(java.sql.Connection uow) {
        try (java.sql.Statement statement = uow.createStatement()) {
            statement.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
