package com.finapp.lending;

import static com.finapp.platform.sourcejoin.SourceDoor.POSTING;
import static com.finapp.platform.sourcejoin.SourceDoor.REVERSAL;

import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.platform.sourcejoin.Keys;
import com.finapp.platform.sourcejoin.SourceFigures;
import com.finapp.platform.sourcejoin.SourceJoin;
import com.finapp.platform.sourcejoin.SqlSourceJoin;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Predicate;

/**
 * The {@code lending} module's read port for the source-join census (`P15-TSK-035`): every namespace the loan book posts, joined to
 * the row that states it. An accrual posts one row per loan and date; a billing, one row per instalment or loan and due date; a
 * statement, one row per line and cycle end. The fee taken from a disbursement is a line of the same total: debits equal the
 * principal, and so do the credits.
 */
public final class LendingJournalSources implements JournalSources {

    private static final Predicate<String> BILLING = Keys.uuidOnly().or(Keys.uuidThenDate());
    private static final Predicate<String> APPLICATION = Keys.uuidOnly().or(Keys.uuidThenCatchUp());

    static final String AGREEMENT = "SELECT loan_id::text AS key_text, amount_minor, currency, scale FROM lending.loan_agreement"
            + " WHERE version = 1 AND loan_id = ANY (?)";
    static final String DRAW = "SELECT id::text AS key_text, amount_minor, currency, scale FROM lending.credit_line_draw WHERE id = ANY (?)";
    static final String ACCRUAL = "SELECT k.v AS key_text, a.posted_minor, a.currency, a.scale FROM unnest(?::text[]) k(v)"
            + " JOIN lending.interest_accrual a ON a.loan_id = split_part(k.v, ':', 1)::uuid AND a.accrual_date = split_part(k.v, ':', 2)::date";
    static final String BILLED = "SELECT k.v AS key_text, b.interest_due_minor::numeric + b.principal_due_minor AS amount_minor, b.currency, b.scale FROM unnest(?::text[]) k(v)"
            + " JOIN lending.instalment_billing b ON b.instalment_id = CASE WHEN k.v !~ ':' THEN k.v::uuid END"
            + " OR (b.loan_id = CASE WHEN k.v ~ ':' THEN split_part(k.v, ':', 1)::uuid END AND b.due_date = CASE WHEN k.v ~ ':' THEN split_part(k.v, ':', 2)::date END)";
    static final String STATEMENT = "SELECT k.v AS key_text, s.interest_billed_minor::numeric + s.principal_billed_minor AS amount_minor, s.currency, s.scale FROM unnest(?::text[]) k(v)"
            + " JOIN lending.credit_line_statement s ON s.loan_id = split_part(k.v, ':', 1)::uuid AND s.cycle_end = split_part(k.v, ':', 2)::date";
    static final String FEE = "SELECT k.v AS key_text, f.amount_minor, f.currency, f.scale FROM unnest(?::text[]) k(v)"
            + " JOIN lending.fee_assessment f ON f.due_item_id = split_part(k.v, ':', 1)::uuid AND f.kind = split_part(k.v, ':', 2)";
    static final String REPAYMENT = "SELECT id::text AS key_text, amount_minor, currency, scale FROM lending.repayment WHERE id = ANY (?)";
    static final String CREDIT_APPLICATION = "SELECT k.v AS key_text, a.amount_minor, a.currency, a.scale FROM unnest(?::text[]) k(v)"
            + " JOIN lending.credit_balance_application a ON a.billing_id = CASE WHEN k.v !~ ':' THEN k.v::uuid END"
            + " OR (a.loan_id = CASE WHEN k.v ~ ':' THEN split_part(k.v, ':', 1)::uuid END AND a.catch_up_sequence = CASE WHEN k.v ~ ':' THEN split_part(k.v, ':', 3)::int END)";
    static final String CLOSURE = "SELECT loan_id::text AS key_text, refund_minor, currency, scale FROM lending.loan_closure WHERE loan_id = ANY (?)";
    static final String WAIVER = "SELECT id::text AS key_text, amount_minor, currency, scale FROM lending.waiver WHERE id = ANY (?)";
    static final String REPAYMENT_REVERSAL = "SELECT r.id::text AS key_text, p.amount_minor, p.currency, p.scale FROM lending.repayment_reversal r"
            + " JOIN lending.repayment p ON p.id = r.repayment_id WHERE r.id = ANY (?)";
    static final String AMENDMENT = "SELECT amendment_id::text AS key_text, sum(principal_minor) AS principal_minor, currency, scale"
            + " FROM lending.loan_amendment_rescheduling WHERE amendment_id = ANY (?) GROUP BY amendment_id, currency, scale";
    static final String WRITE_OFF = "SELECT loan_id::text AS key_text, principal_minor::numeric + principal_due_minor + interest_accrued_minor + interest_due_minor + fees_due_minor AS amount_minor,"
            + " currency, scale FROM lending.loan_write_off WHERE loan_id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            join(POSTING, "lending.disbursement:", "lending.loan_agreement version 1 by the loan id: debits and credits equal the agreement's amount (the fee is one of the credits)", AGREEMENT, Keys.suffixUuid(), "amount_minor"),
            join(POSTING, "lending.draw:", "lending.credit_line_draw by the draw id: debits and credits equal its amount", DRAW, Keys.suffixUuid(), "amount_minor"),
            join(POSTING, "lending.accrual:", "lending.interest_accrual by loan and date: debits and credits equal the accrual's posted interest", ACCRUAL, Keys.suffixMatching(Keys.uuidThenDate()), "posted_minor"),
            join(POSTING, "lending.billing:", "lending.instalment_billing by instalment, or by loan and due date: debits and credits equal the interest plus principal billed", BILLED, Keys.suffixMatching(BILLING), "amount_minor"),
            join(POSTING, "lending.statement:", "lending.credit_line_statement by line and cycle end: debits and credits equal the interest plus principal billed", STATEMENT, Keys.suffixMatching(Keys.uuidThenDate()), "amount_minor"),
            join(POSTING, "lending.fee:", "lending.fee_assessment by the billed item and kind: debits and credits equal its amount", FEE, Keys.suffixMatching(Keys.uuidThenWord("LATE")), "amount_minor"),
            join(POSTING, "lending.repayment:", "lending.repayment by the repayment id: debits and credits equal its amount", REPAYMENT, Keys.suffixUuid(), "amount_minor"),
            join(POSTING, "lending.credit-application:", "lending.credit_balance_application by billing, or by loan and catch-up sequence: debits and credits equal its amount", CREDIT_APPLICATION, Keys.suffixMatching(APPLICATION), "amount_minor"),
            join(POSTING, "lending.closure-refund:", "lending.loan_closure by the loan id: debits and credits equal the refund", CLOSURE, Keys.suffixUuid(), "refund_minor"),
            join(POSTING, "lending.waiver:", "lending.waiver by the waiver id: debits and credits equal its amount", WAIVER, Keys.suffixUuid(), "amount_minor"),
            join(REVERSAL, "lending.waiver:", "lending.waiver by the waiver id: the fee's reversal, debits and credits equal the waiver's amount", WAIVER, Keys.suffixUuid(), "amount_minor"),
            join(REVERSAL, "lending.repayment-reversal:", "lending.repayment_reversal and its repayment by the reversal id: the mirror, debits and credits equal the repayment's amount", REPAYMENT_REVERSAL, Keys.suffixUuid(), "amount_minor"),
            join(POSTING, "lending.amendment:", "lending.loan_amendment_rescheduling by the amendment id: debits and credits equal the arrears rescheduled", AMENDMENT, Keys.suffixUuid(), "principal_minor"),
            join(POSTING, "lending.write-off:", "lending.loan_write_off by the loan id: debits and credits equal the principal, principal due, interest accrued, interest due and fees due written off", WRITE_OFF, Keys.suffixUuid(), "amount_minor"));

    @Override
    public String module() {
        return "lending";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }

    private static SourceJoin join(com.finapp.platform.sourcejoin.SourceDoor door, String namespace, String statement, String sql,
            Keys.KeyForm key, String minor) {
        return new SqlSourceJoin(door, namespace, statement, sql, key, row -> total(row, minor));
    }

    /** Read as a decimal: a sum of {@code bigint} columns may exceed one, and that must be this record's mismatch, not a failed batch. */
    private static SourceFigures total(ResultSet row, String minor) throws SQLException {
        return SourceFigures.total(row.getString("currency").trim(), row.getInt("scale"), row.getBigDecimal(minor).toBigIntegerExact());
    }
}
