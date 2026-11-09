package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, as text, kyc's two check reads and its latest-case read, ranked by a sequence and no longer a stamp (`P15-TSK-037`; {@code THREAT_MODEL.md} T13), and kyc {@code V012}
 * that adds the sequences. Hermetic: their database cases are owed to {@code P15-TST-002}.
 */
@DisplayName("kyc's newest-check, latest-screening and latest-case reads rank by a sequence, never by a stamp (P15-TSK-037)")
class KycStampOrderStatementsTest {

    private static final String V012 = read("db/migration/kyc/V012__checks_and_cases_are_ranked_by_a_sequence.sql");

    @Test
    @DisplayName("the newest onboarding check is ranked by check_order, the stamps only for rows from before V012")
    void theNewestCheckIsRankedByItsOrder() {
        assertThat(JdbcCheckStore.NEWEST_ORDER).isEqualTo(" ORDER BY check_order DESC NULLS LAST, requested_at DESC, id DESC LIMIT 1");
    }

    @Test
    @DisplayName("the latest decided screening per type is ranked by check_order, the stamps only for rows from before V012")
    void theLatestDecidedScreeningIsRankedByItsOrder() {
        assertThat(JdbcCheckStore.LATEST_DECIDED_SCREENINGS)
                .contains("ORDER BY check_order DESC NULLS LAST, requested_at DESC, status_changed_at DESC, id DESC LIMIT 1");
    }

    @Test
    @DisplayName("a customer's latest case is ranked by case_order, a sequence, the UUIDv7 only breaking a tie among rows from before V012")
    void theLatestCaseIsRankedByItsOrder() {
        assertThat(JdbcKycCaseStore.LATEST_FOR_CUSTOMER).endsWith("ORDER BY case_order DESC NULLS LAST, id DESC LIMIT 1");
    }

    @Test
    @DisplayName("V012 adds each column with no default first, so no rewrite numbers an existing row, and only then the sequence default; the old index goes")
    void theColumnsArriveWithoutRewritingHistory() {
        assertThat(V012).contains("ALTER TABLE kyc.verification_check ADD COLUMN check_order bigint;\nALTER TABLE kyc.verification_check ALTER COLUMN check_order SET DEFAULT nextval('kyc.check_order');")
                .contains("ALTER TABLE kyc.kyc_case ADD COLUMN case_order bigint;\nALTER TABLE kyc.kyc_case ALTER COLUMN case_order SET DEFAULT nextval('kyc.case_order');")
                .contains("GRANT USAGE ON SEQUENCE kyc.check_order TO finapp_app;")
                .contains("GRANT USAGE ON SEQUENCE kyc.case_order TO finapp_app;")
                .contains("DROP INDEX kyc.verification_check_decided_latest;")
                .contains("CREATE INDEX verification_check_decided_latest_by_order");
        assertThat(V012.lines().filter(line -> line.startsWith("ALTER TABLE") && line.contains(" DEFAULT nextval") && line.contains("ADD COLUMN"))).isEmpty();
    }

    private static String read(String resource) {
        try (InputStream stream = KycStampOrderStatementsTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
