package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplServicing;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/**
 * A real late fee on a real agreement (`P12-DOC-001`, for the waiver doors' suites): a pay-in-4 purchase whose wallet held
 * only instalment 1, its record date-shifted so instalment 2 is five days past due ({@code BnplCollectionLedgerDatabaseTest}'s
 * owner-role shift), and one servicing step under the system actor - the collection failing on the empty wallet and the
 * one late fee assessed. Answers the fee assessment's id.
 */
@SuppressWarnings("try") // The Scope is used for its close side effect (the idiom).
final class BnplLateFee {

    private BnplLateFee() {}

    /** The agreement's obligation aged and serviced once; its one fee assessment's id. */
    static String assessed(BnplTransactions transactions, BnplServicing servicing, String agreement) throws Exception {
        String obligation = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE agreement_id ="
                + " ?::uuid", agreement);
        toAge(obligation, 2, 5);
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            transactions.inTransaction(uow -> servicing.service(uow, BnplObligationId.of(UUID.fromString(obligation))));
        }
        String fee = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_fee_assessment WHERE obligation_id ="
                + " ?::uuid", obligation);
        assertThat(fee).as("the servicing step assessed the late fee").isNotNull();
        return fee;
    }

    /** The date shift to instalment {@code sequence} being {@code age} days past its due date today. */
    private static void toAge(String obligation, int sequence, int age) throws Exception {
        long days = age + BnplApplicationClient.count("SELECT i.due_date - (now() AT TIME ZONE 'UTC')::date FROM"
                + " bnpl.bnpl_instalment i WHERE i.obligation_id = ?::uuid AND i.sequence = ?", obligation, sequence);
        String o = "'" + obligation + "'";
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE bnpl.purchase SET business_date = business_date - " + days + " WHERE id ="
                        + " (SELECT purchase_id FROM bnpl.bnpl_obligation WHERE id = " + o + ");"
                        + " UPDATE bnpl.bnpl_obligation SET purchase_date = purchase_date - " + days + " WHERE id = " + o
                        + "; UPDATE bnpl.bnpl_schedule SET start_date = start_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_instalment SET due_date = due_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_billing SET value_date = value_date - " + days + ", period_start ="
                        + " period_start - " + days + ", business_date = business_date - " + days
                        + " WHERE obligation_id = " + o + "; UPDATE bnpl.bnpl_repayment SET value_date = value_date - "
                        + days + " WHERE obligation_id = " + o + "; UPDATE ledger.journal_entry SET posting_date ="
                        + " posting_date - " + days + ", value_date = value_date - " + days + " WHERE id IN (SELECT"
                        + " l.entry_id FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                        + " l.ledger_account_id WHERE a.owner_ref = " + o + "::uuid)");
            }
            owner.commit();
        }
    }
}
