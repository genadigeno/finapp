package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V013}'s text held to the code (`P12-TSK-020`; {@code BnplCollectionMigrationTest}'s idiom - hermetic, so it
 * runs on every build while the live ranks are {@code BnplConditionDatabaseTest}'s and {@code BnplLateFeeDatabaseTest}'s):
 * the two uniques, the kinds and buckets as the enums declare them, B4 for every writer, DPD and the bucket derived from the
 * rows with one rule, only on a change, the fee's day, fee, cap and owing; a fee joining its instalment's FEES; the
 * release's columns, shape and conservation (OQ-14); the grants; every stamp after the locks.
 */
@DisplayName("bnpl V013 holds the condition history's and the late fee's ranks as the code declares them (P12-TSK-020)")
class BnplConditionMigrationTest {

    private static final String V013 = "db/migration/bnpl/V013__delinquency_default_and_late_fees.sql";

    @Test
    @DisplayName("born once per (obligation, date, kind) and per (instalment, kind); the kinds and buckets the enums'")
    void theUniquesAndVocabularies() {
        String sql = statements();
        assertThat(sql).contains("CONSTRAINT bnpl_condition_event_once_a_day UNIQUE (obligation_id, business_date, kind)")
                .contains("CONSTRAINT bnpl_fee_assessment_once_per_instalment UNIQUE (instalment_id, kind)")
                .contains("CONSTRAINT bnpl_fee_assessment_entry_unique UNIQUE (journal_entry_id)")
                .contains("kind IN (" + BnplConditionKind.sqlValueList() + ")")
                .contains("bucket IN (" + BnplDelinquencyBucket.sqlValueList() + ")")
                .contains("CONSTRAINT bnpl_fee_assessment_kind_is_known CHECK (kind IN ('" + BnplFeeKind.LATE.name() + "'))");
        assertThat(BnplFeeKind.values()).containsExactly(BnplFeeKind.LATE);
    }

    @Test
    @DisplayName("the condition's trigger: B4 for every writer, ACTIVE, the version in force, the clock's date, never"
            + " before a recorded change, DPD and bucket from the rows, the flag's rule, only on a change, stamped after")
    void theConditionsTrigger() {
        String trigger = body("bnpl.bnpl_condition_event_is_born_once()");
        assertThat(trigger)
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("IF NOT FOUND OR obligation.status <> 'ACTIVE' THEN")
                .contains("OR NEW.business_date > bnpl.business_date_latest(obligation.servicing_zone) THEN")
                .contains("WHERE e.obligation_id = NEW.obligation_id AND e.business_date > NEW.business_date) THEN")
                .contains("derived := bnpl.days_past_due(NEW.obligation_id, NEW.business_date);")
                .contains("OR NEW.bucket <> bnpl.delinquency_bucket(derived, terms.delinquency_bounds) THEN")
                .contains("IF NEW.defaulted <> (derived >= terms.default_threshold_days OR (was_defaulted AND derived > 0)) THEN")
                .contains("OR (NEW.kind = 'DEFAULT' AND NEW.defaulted = was_defaulted) THEN")
                .contains("NEW.created_at := clock_timestamp();");
        assertThat(body("bnpl.days_past_due(obligation uuid, on_date date)"))
                .contains("SELECT coalesce(on_date - min(b.value_date), 0)")
                .contains("FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)) > 0");
        assertThat(body("bnpl.delinquency_bucket(dpd integer, bounds integer[])"))
                .contains("WHEN dpd <= 0 THEN 'CURRENT'").contains("WHEN dpd >= bounds[4] THEN 'B4_90_PLUS'");
    }

    @Test
    @DisplayName("the fee's trigger: B4, ACTIVE, a billed instalment of its own obligation, the version in force, the clock's"
            + " date, its own age at least the pinned day, at most the fee, within the cap, still owing; and the fee joins"
            + " its instalment's FEES")
    void theFeesTrigger() {
        String trigger = body("bnpl.bnpl_fee_assessment_is_born_once()");
        assertThat(trigger)
                .contains("SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = NEW.obligation_id FOR UPDATE;")
                .contains("WHERE b.instalment_id = NEW.instalment_id AND b.obligation_id = NEW.obligation_id;")
                .contains("IF NEW.business_date - billing.value_date < terms.late_fee_day THEN")
                .contains("IF NEW.amount_minor > terms.late_fee_minor OR assessed + NEW.amount_minor > terms.late_fee_cap_minor THEN")
                .contains("FROM (VALUES ('INTEREST'), ('PRINCIPAL')) AS c (component);")
                .contains("NEW.created_at := clock_timestamp();");
        assertThat(body("bnpl.billing_component_billed(billing uuid, billed_component text)"))
                .contains("ELSE (SELECT coalesce(sum(f.amount_minor), 0) FROM bnpl.bnpl_fee_assessment f")
                .contains("WHERE f.instalment_id = b.instalment_id)");
        assertThat(BnplFeeAssessment.postingKey(java.util.UUID.fromString("00000000-0000-7000-8000-000000000001"),
                BnplFeeKind.LATE)).isEqualTo("bnpl.fee:00000000-0000-7000-8000-000000000001:LATE");
    }

    @Test
    @DisplayName("OQ-14's release: the columns, a fee released only on a billed instalment cancelled whole, settled as"
            + " FEES, summed and never over-settled at commit, and nothing left due of FEES on a whole cancellation")
    void theRelease() {
        String sql = statements();
        assertThat(sql)
                .contains("ALTER TABLE bnpl.bnpl_refund_adjustment ADD COLUMN late_fee_released_minor bigint NOT NULL DEFAULT 0;")
                .contains("AND (late_fee_released_minor = 0 OR (kind = 'BILLED_UNPAID' AND whole)));")
                .contains("ALTER TABLE bnpl.bnpl_refund ADD COLUMN late_fee_released_minor bigint NOT NULL DEFAULT 0;");
        assertThat(body("bnpl.billing_component_paid(billing uuid, paid_component text)"))
                .contains("WHEN 'FEES' THEN x.late_fee_released_minor");
        assertThat(body("bnpl.bnpl_refund_conserves()"))
                .contains("OR released <> refund.interest_due_released_minor OR fees_released <> refund.late_fee_released_minor THEN")
                .contains("CROSS JOIN (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)")
                .contains("AND bnpl.billing_component_paid(b.id, 'FEES') <> bnpl.billing_component_billed(b.id, 'FEES')) THEN");
    }

    @Test
    @DisplayName("SELECT and INSERT only, never truncated; no schema but bnpl's named")
    void theGrants() {
        String sql = statements();
        assertThat(sql).contains("GRANT SELECT, INSERT ON bnpl.bnpl_condition_event TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON bnpl.bnpl_fee_assessment TO finapp_app;")
                .contains("BEFORE TRUNCATE ON bnpl.bnpl_condition_event")
                .contains("BEFORE TRUNCATE ON bnpl.bnpl_fee_assessment")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_condition_event")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_fee_assessment")
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
        try (InputStream in = BnplConditionMigrationTest.class.getClassLoader().getResourceAsStream(V013)) {
            assertThat(in).as(V013 + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
