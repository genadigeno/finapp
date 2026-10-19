package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V009}'s text held to the code (`P12-TSK-017`; {@code BnplServicingMigrationTest}'s idiom - hermetic, so it
 * runs on every build while the live ranks are {@code BnplAccrualDatabaseTest}'s): the writer's grant (INSERT, never
 * UPDATE or DELETE), the trigger replaced with V008's ranks kept and the writer's added - B4 for every writer, the agreement
 * version in force and its engine, an instalment period of the latest schedule version, the cumulative chain - and its stamp
 * after the lock; no schema but bnpl's.
 */
@DisplayName("bnpl V009 holds the accrual writer's ranks as the code declares them (P12-TSK-017)")
class BnplAccrualMigrationTest {

    private static final String V009 = "db/migration/bnpl/V009__pay_in_n_interest_accrual_the_writer.sql";

    @Test
    @DisplayName("the application role may insert an accrual - never update or delete one")
    void theWriterIsGrantedInsertOnly() {
        assertThat(statements()).contains("GRANT INSERT ON bnpl.bnpl_interest_accrual TO finapp_app;")
                .doesNotContainPattern("GRANT[^;]*(DELETE|UPDATE|TRUNCATE)");
    }

    @Test
    @DisplayName("the trigger keeps V008's ranks: INSERT only, ACTIVE pay-in-N, never early, from the purchase date,"
            + " oldest first, never into a billed period")
    void v008sRanksAreKept() {
        assertThat(body("bnpl.bnpl_interest_accrual_is_born_once()"))
                .contains("IF TG_OP <> 'INSERT' THEN")
                .contains("obligation.status <> 'ACTIVE' OR obligation.product <> 'BNPL_PAY_IN_N'")
                .contains("NEW.accrual_date >= bnpl.business_date_latest(obligation.servicing_zone)")
                .contains("NEW.accrual_date < obligation.purchase_date")
                .contains("a.obligation_id = NEW.obligation_id AND a.accrual_date >= NEW.accrual_date")
                .contains("b.obligation_id = NEW.obligation_id AND b.value_date > NEW.accrual_date");
    }

    @Test
    @DisplayName("the writer's ranks: B4 first for every writer, the agreement in force and its pinned engine, an"
            + " instalment period of the latest schedule version, the cumulative chain, the stamp after the lock")
    void theWritersRanks() {
        String body = body("bnpl.bnpl_interest_accrual_is_born_once()");
        assertThat(body.indexOf("FOR UPDATE")).as("B4 before any rank").isPositive()
                .isLessThan(body.indexOf("obligation.status <> 'ACTIVE'"));
        assertThat(body)
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("NEW.agreement_version <> (SELECT max(x.version) FROM bnpl.bnpl_agreement x")
                .contains("agreement.accrual_engine IS DISTINCT FROM NEW.accrual_engine")
                .contains("i.schedule_id = schedule.id AND i.due_date = NEW.period_end")
                .contains("schedule.start_date <> obligation.purchase_date")
                .contains("NEW.cumulative_exact < previous_cumulative")
                .contains("NEW.created_at := clock_timestamp();");
    }

    @Test
    @DisplayName("V009 names no schema but bnpl's - no ledger table read by a bnpl trigger")
    void noOtherSchemaIsNamed() {
        assertThat(statements()).doesNotContain("ledger.").doesNotContain("lending.").doesNotContain("credit.");
    }

    private static String body(String signature) {
        String sql = statements();
        int start = sql.indexOf("CREATE OR REPLACE FUNCTION " + signature);
        assertThat(start).as(signature).isNotNegative();
        int end = sql.indexOf("$$;", sql.indexOf("$$", start) + 2);
        return sql.substring(start, end);
    }

    private static String statements() {
        return migration().lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        try (InputStream in = BnplAccrualMigrationTest.class.getClassLoader().getResourceAsStream(V009)) {
            assertThat(in).as(V009 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
