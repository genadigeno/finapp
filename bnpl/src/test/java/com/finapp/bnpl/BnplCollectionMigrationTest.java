package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V011}'s text held to the code (`P12-TSK-019`; {@code BnplRepaymentMigrationTest}'s idiom - hermetic, so it
 * runs on every build while the live ranks are {@code BnplCollectionDatabaseTest}'s): the attempt's uniques, its outcomes as
 * {@link CollectionOutcome} declares them and their shape, B4 for every writer, the authority of the pinned terms, the
 * database clock's date, the billed instalment due by it and never back-filled, the conservation beside every payer, its
 * repayment under the attempt's key on its instalment alone; the deferred "every automatic repayment has its attempt"; the
 * repayment's trigger admitting {@code AUTO_COLLECTION} and still refusing {@code PAYOFF}; the grants; every stamp after the
 * locks.
 */
@DisplayName("bnpl V011 holds the collection attempt's ranks as the code declares them (P12-TSK-019)")
class BnplCollectionMigrationTest {

    private static final String V011 = "db/migration/bnpl/V011__auto_collection_from_the_wallet.sql";

    @Test
    @DisplayName("once per instalment and date, a repayment one attempt's money, the outcomes CollectionOutcome's and"
            + " shaped - a repayment exactly when money moved, never more than due")
    void theAttemptsUniquesAndShape() {
        String sql = statements();
        assertThat(sql).contains("CONSTRAINT bnpl_collection_attempt_instalment_date_unique UNIQUE (instalment_id,"
                        + " attempt_date)")
                .contains("CONSTRAINT bnpl_collection_attempt_repayment_unique UNIQUE (repayment_id)")
                .contains("((outcome IN ('COLLECTED', 'PARTIAL')) = (repayment_id IS NOT NULL))")
                .contains("AND (outcome <> 'PARTIAL' OR (collected_minor > 0 AND collected_minor < due_minor))");
        String outcomes = Arrays.stream(CollectionOutcome.values()).map(outcome -> "'" + outcome.name() + "'")
                .collect(Collectors.joining(", "));
        assertThat(sql).contains("outcome IN (" + outcomes + ")");
    }

    @Test
    @DisplayName("the trigger: B4 for every writer, an ACTIVE obligation under its version in force whose pinned terms"
            + " collect, partial only where allowed, the clock's date, a billed instalment due by it, never back-filled,"
            + " conserving beside every payer, its own repayment on its instalment alone, stamped after the lock")
    void theAttemptsTrigger() {
        String trigger = body("bnpl.bnpl_collection_attempt_is_born_once()");
        assertThat(trigger)
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN")
                .contains("IF terms.terms_version_id IS NULL OR NOT terms.auto_collection THEN")
                .contains("IF NEW.outcome = 'PARTIAL' AND NOT terms.partial_collection THEN")
                .contains("OR NEW.attempt_date > bnpl.business_date_latest(obligation.servicing_zone) THEN")
                .contains("instalment.due_date > NEW.attempt_date")
                .contains("WHERE c.instalment_id = NEW.instalment_id AND c.attempt_date > NEW.attempt_date) THEN")
                .contains("IF NEW.due_minor - NEW.collected_minor <> still_due THEN")
                .contains("OR repayment.request_key <> NEW.instalment_id::text || ':' || NEW.attempt_date::text")
                .contains("AND (a.target <> 'DUE_ITEM' OR a.instalment_id <> NEW.instalment_id)) THEN")
                .contains("NEW.created_at := clock_timestamp();");
        assertThat(BnplCollectionAttempt.requestKey(java.util.UUID.fromString("00000000-0000-7000-8000-000000000001"),
                java.time.LocalDate.of(2026, 10, 31))).isEqualTo("00000000-0000-7000-8000-000000000001:2026-10-31");
    }

    @Test
    @DisplayName("at commit every AUTO_COLLECTION repayment is named by its attempt; the repayment's trigger admits"
            + " AUTO_COLLECTION and still refuses PAYOFF, floored as before")
    void theRepaymentSide() {
        String sql = statements();
        assertThat(sql).contains("    AFTER INSERT ON bnpl.bnpl_repayment\n    DEFERRABLE INITIALLY DEFERRED\n"
                + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.bnpl_auto_collection_has_its_attempt();");
        assertThat(body("bnpl.bnpl_auto_collection_has_its_attempt()"))
                .contains("IF NEW.kind = 'AUTO_COLLECTION'")
                .contains("NOT EXISTS (SELECT 1 FROM bnpl.bnpl_collection_attempt c WHERE c.repayment_id = NEW.id)");
        String repayment = body("bnpl.bnpl_repayment_is_born_once()");
        assertThat(repayment).contains("IF NEW.kind = 'PAYOFF' THEN")
                .doesNotContain("IF NEW.kind IN ('AUTO_COLLECTION', 'PAYOFF') THEN")
                .contains("value_floor := bnpl.principal_value_floor(NEW.obligation_id);")
                .contains("NEW.received_at := clock_timestamp();");
        assertThat(BnplPurchaseStore.Repayment.AUTO_COLLECTION).isEqualTo("AUTO_COLLECTION");
    }

    @Test
    @DisplayName("SELECT and INSERT only, never truncated; no schema but bnpl's named")
    void theGrants() {
        String sql = statements();
        assertThat(sql).contains("GRANT SELECT, INSERT ON bnpl.bnpl_collection_attempt TO finapp_app;")
                .contains("BEFORE TRUNCATE ON bnpl.bnpl_collection_attempt")
                .doesNotContainPattern("GRANT[^;]*(DELETE|UPDATE)")
                .doesNotContain("ledger.").doesNotContain("lending.").doesNotContain("payments.");
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
        try (InputStream in = BnplCollectionMigrationTest.class.getClassLoader().getResourceAsStream(V011)) {
            assertThat(in).as(V011 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
