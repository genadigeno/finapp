package com.finapp.bnpl;

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
 * The {@code bnpl} module's read port for the source-join census (`P15-TSK-035`): every namespace the BNPL book posts, joined to the
 * row that states it. A merchant's fee is taken net of the financing inside the same entry, debits and credits equal the gross plus
 * the fee (one debits the payable by the fee, the other credits the income); a refund's entry states every part of what it moves.
 */
public final class BnplJournalSources implements JournalSources {

    private static final Predicate<String> APPLICATION = Keys.uuidOnly().or(Keys.uuidThenCatchUp());

    static final String FINANCING = "SELECT purchase_id::text AS key_text, gross_minor::numeric + fee_minor AS amount_minor, currency, scale FROM bnpl.merchant_financing WHERE purchase_id = ANY (?)";
    static final String BILLING = "SELECT instalment_id::text AS key_text, principal_minor::numeric + interest_minor AS amount_minor, currency, scale FROM bnpl.bnpl_billing WHERE instalment_id = ANY (?)";
    static final String ACCRUAL = "SELECT k.v AS key_text, a.posted_minor AS amount_minor, a.currency, a.scale FROM unnest(?::text[]) k(v)"
            + " JOIN bnpl.bnpl_interest_accrual a ON a.obligation_id = split_part(k.v, ':', 1)::uuid AND a.accrual_date = split_part(k.v, ':', 2)::date";
    static final String REPAYMENT = "SELECT id::text AS key_text, amount_minor, currency, scale FROM bnpl.bnpl_repayment WHERE id = ANY (?)";
    static final String CREDIT_APPLICATION = "SELECT k.v AS key_text, a.amount_minor, a.currency, a.scale FROM unnest(?::text[]) k(v)"
            + " JOIN bnpl.bnpl_credit_balance_application a ON a.billing_id = CASE WHEN k.v !~ ':' THEN k.v::uuid END"
            + " OR (a.obligation_id = CASE WHEN k.v ~ ':' THEN split_part(k.v, ':', 1)::uuid END AND a.catch_up_sequence = CASE WHEN k.v ~ ':' THEN split_part(k.v, ':', 3)::int END)";
    static final String CLOSURE = "SELECT obligation_id::text AS key_text, refund_minor AS amount_minor, currency, scale FROM bnpl.bnpl_obligation_closure WHERE obligation_id = ANY (?)";
    static final String FEE = "SELECT k.v AS key_text, f.amount_minor, f.currency, f.scale FROM unnest(?::text[]) k(v)"
            + " JOIN bnpl.bnpl_fee_assessment f ON f.instalment_id = split_part(k.v, ':', 1)::uuid AND f.kind = split_part(k.v, ':', 2)";
    static final String REFUND = "SELECT id::text AS key_text, amount_minor::numeric + fee_returned_minor + interest_accrued_released_minor + interest_due_released_minor + late_fee_released_minor AS amount_minor,"
            + " currency, scale FROM bnpl.bnpl_refund WHERE id = ANY (?)";
    static final String WAIVER = "SELECT id::text AS key_text, amount_minor, currency, scale FROM bnpl.bnpl_waiver WHERE id = ANY (?)";
    static final String REPAYMENT_REVERSAL = "SELECT r.id::text AS key_text, p.amount_minor, p.currency, p.scale FROM bnpl.bnpl_repayment_reversal r"
            + " JOIN bnpl.bnpl_repayment p ON p.id = r.repayment_id WHERE r.id = ANY (?)";
    static final String FEE_RELEASE = "SELECT reversal_id::text AS key_text, sum(amount_minor) AS amount_minor, currency, scale FROM bnpl.bnpl_reversal_fee_release WHERE reversal_id = ANY (?) GROUP BY reversal_id, currency, scale";
    static final String WRITE_OFF = "SELECT obligation_id::text AS key_text, principal_minor::numeric + principal_due_minor + interest_accrued_minor + interest_due_minor + fees_due_minor AS amount_minor,"
            + " currency, scale FROM bnpl.bnpl_write_off WHERE obligation_id = ANY (?)";

    private static final List<SourceJoin> JOINS = List.of(
            join(POSTING, "bnpl.financing:", "bnpl.merchant_financing by the purchase id: debits and credits equal the gross plus the merchant's fee taken in the same entry", FINANCING, Keys.suffixUuid()),
            join(POSTING, "bnpl.billing:", "bnpl.bnpl_billing by the instalment id: debits and credits equal the principal plus interest billed", BILLING, Keys.suffixUuid()),
            join(POSTING, "bnpl.accrual:", "bnpl.bnpl_interest_accrual by obligation and date: debits and credits equal the posted interest", ACCRUAL, Keys.suffixMatching(Keys.uuidThenDate())),
            join(POSTING, "bnpl.repayment:", "bnpl.bnpl_repayment by the repayment id: debits and credits equal its amount", REPAYMENT, Keys.suffixUuid()),
            join(POSTING, "bnpl.credit-application:", "bnpl.bnpl_credit_balance_application by billing, or by obligation and catch-up sequence: debits and credits equal its amount", CREDIT_APPLICATION, Keys.suffixMatching(APPLICATION)),
            join(POSTING, "bnpl.closure-refund:", "bnpl.bnpl_obligation_closure by the obligation id: debits and credits equal the refund", CLOSURE, Keys.suffixUuid()),
            join(POSTING, "bnpl.fee:", "bnpl.bnpl_fee_assessment by the instalment and kind: debits and credits equal its amount", FEE, Keys.suffixMatching(Keys.uuidThenWord("LATE"))),
            join(POSTING, "bnpl.refund:", "bnpl.bnpl_refund by the refund id: debits and credits equal the refund, the fee returned and the interest and late fees released", REFUND, Keys.suffixUuid()),
            join(REVERSAL, "bnpl.waiver:", "bnpl.bnpl_waiver by the waiver id: the fee's reversal, debits and credits equal the waiver's amount", WAIVER, Keys.suffixUuid()),
            join(REVERSAL, "bnpl.repayment-reversal:", "bnpl.bnpl_repayment_reversal and its repayment by the reversal id: the mirror, debits and credits equal the repayment's amount", REPAYMENT_REVERSAL, Keys.suffixUuid()),
            join(POSTING, "bnpl.reversal-fee-release:", "bnpl.bnpl_reversal_fee_release by the reversal id: debits and credits equal the fees released", FEE_RELEASE, Keys.suffixUuid()),
            join(POSTING, "bnpl.write-off:", "bnpl.bnpl_write_off by the obligation id: debits and credits equal the principal, principal due, interest accrued, interest due and fees due written off", WRITE_OFF, Keys.suffixUuid()));

    @Override
    public String module() {
        return "bnpl";
    }

    @Override
    public List<SourceJoin> joins() {
        return JOINS;
    }

    private static SourceJoin join(com.finapp.platform.sourcejoin.SourceDoor door, String namespace, String statement, String sql, Keys.KeyForm key) {
        return new SqlSourceJoin(door, namespace, statement, sql, key, BnplJournalSources::total);
    }

    /** Read as a decimal: a sum of {@code bigint} columns may exceed one, and that must be this record's mismatch, not a failed batch. */
    private static SourceFigures total(ResultSet row) throws SQLException {
        return SourceFigures.total(row.getString("currency").trim(), row.getInt("scale"), row.getBigDecimal("amount_minor").toBigIntegerExact());
    }
}
