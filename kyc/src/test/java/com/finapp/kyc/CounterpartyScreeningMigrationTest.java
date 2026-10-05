package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CounterpartyScreeningVocabulary.EntityType;
import com.finapp.kyc.CounterpartyScreeningVocabulary.PayeeVerdict;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReviewReason;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code kyc V009} and the code that writes it are one definition (`P9-TSK-016`) - the
 * {@code DecisionMigrationTest} idiom: every closed list is the enum's, the backlog's decision-basis
 * CHECKs are present verbatim, the attempts are append-only by grant, and no column grants an update
 * the machine does not need.
 */
@DisplayName("the counterparty screening schema and the code agree (P9-TSK-016)")
class CounterpartyScreeningMigrationTest {

    private static final String MIGRATION = "db/migration/kyc/V009__counterparty_screening.sql";

    @Test
    @DisplayName("every closed list is exactly what the enums declare")
    void listsMatchTheEnums() {
        String sql = migration();
        assertThat(sql)
                .contains("CHECK (status IN (" + CounterpartyScreeningStatus.sqlValueList() + "))")
                .contains("CHECK (entity_type IN (" + CounterpartyScreeningVocabulary.sqlValueList(EntityType.values()) + "))")
                .contains("CHECK (payee_verdict IN (" + CounterpartyScreeningVocabulary.sqlValueList(PayeeVerdict.values()) + "))")
                .contains("CHECK (review_reason IN (" + CounterpartyScreeningVocabulary.sqlValueList(ReviewReason.values()) + "))")
                .contains("CHECK (decision_reason_code IN (" + CounterpartyScreeningVocabulary.sqlValueList(ReasonCode.values()) + "))")
                .contains("CHECK (verdict IN (" + CounterpartyScreeningVocabulary.sqlValueList(Verdict.values()) + "))")
                .contains("CHECK (decision_basis IN (" + DecisionBasis.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the decision-basis CHECKs: REVIEWER names a person, and no AUTOMATIC CLEAR without a payee MATCH")
    void theDecisionBasisChecks() {
        assertThat(migration())
                .contains("CHECK ((decision_basis = 'REVIEWER') = (decided_by IS NOT NULL))")
                .contains("CHECK (NOT (decision_basis = 'AUTOMATIC' AND status = 'CLEAR') OR payee_verdict = 'MATCH')")
                .contains("CHECK ((status = 'REQUESTED') = (decision_basis IS NULL)");
    }

    @Test
    @DisplayName("attempts are append-only by grant; the screening updates only its machine columns")
    void grants() {
        String sql = migration();
        assertThat(sql)
                .contains("GRANT SELECT, INSERT ON kyc.counterparty_screening_attempt TO finapp_app")
                .doesNotContain("UPDATE ON kyc.counterparty_screening_attempt")
                .doesNotContainPattern("GRANT[^;]*DELETE")
                .contains("GRANT SELECT, INSERT ON kyc.counterparty_screening TO finapp_app");
        int update = sql.indexOf("GRANT UPDATE (");
        String columns = sql.substring(update, sql.indexOf(')', update));
        assertThat(columns)
                .doesNotContain("subject_ciphertext")
                .doesNotContain("payee_verdict")
                .doesNotContain("request_reference")
                .doesNotContain("country");
    }

    @Test
    @DisplayName("the narrative carries the reason screen")
    void theNarrativeIsScreened() {
        assertThat(migration()).contains("NOT kyc.holds_instrument_shape(decision_narrative)");
    }

    private static String migration() {
        try (InputStream in = CounterpartyScreeningMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION + " must be on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
