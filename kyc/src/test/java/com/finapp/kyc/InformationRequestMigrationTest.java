package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * kyc {@code V016} and the domain are one definition (`P17-TSK-126`; the {@code KycCaseMigrationTest} idiom): the request's
 * status and reason checks are the enums' lists, its bounds the value types', one open request per case, the expiry's range,
 * a close written once, and no {@code DELETE} for the application.
 */
@DisplayName("V016 and the information request agree (P17-TSK-126)")
class InformationRequestMigrationTest {

    private static final String MIGRATION = "db/migration/kyc/V016__a_case_awaits_the_customer.sql";

    @Test
    @DisplayName("the status and reason constraints list exactly the enums' values")
    void theVocabulariesMatchTheEnums() {
        assertThat(migration())
                .contains("CHECK (status IN (" + InformationRequestStatus.sqlValueList() + "))")
                .contains("CHECK (reason_code IN (" + InformationRequestReason.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the text's and the answer's bounds are the value types'")
    void theBoundsMatchTheTypes() {
        assertThat(migration())
                .contains("char_length(request_text) BETWEEN " + CustomerRequestText.MIN_LENGTH + " AND "
                        + CustomerRequestText.MAX_LENGTH)
                .contains("char_length(answer_text) BETWEEN 1 AND " + CustomerAnswer.MAX_LENGTH);
    }

    @Test
    @DisplayName("one open request per case, the sweep's range over the open ones, and a close written once")
    void theRulesTheDatabaseHolds() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX information_request_one_open_per_case")
                .contains("CREATE INDEX information_request_open_by_due")
                .contains("ON kyc.information_request (due_at, id)")
                .contains("CREATE TRIGGER information_request_closes_once")
                .contains("OLD.status <> 'OPEN' OR NEW.status NOT IN ('ANSWERED', 'EXPIRED')");
    }

    @Test
    @DisplayName("the application inserts and closes, and never deletes or rewrites the asking")
    void theGrantsAreNarrow() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON kyc.information_request TO finapp_app")
                .contains("GRANT UPDATE (status, closed_at, answer_text, answered_by) ON kyc.information_request TO finapp_app")
                .doesNotContain("DELETE ON kyc.information_request");
    }

    @Test
    @DisplayName("the replaced case status check carries the expand-only marker, and is not judged over old rows")
    void theCaseCheckIsAnExpand() {
        assertThat(migration())
                .startsWith("-- finapp-contract: first-conforming-release=ANY; waives=DROP,CHECK; reason=")
                .contains("'AWAITING_CUSTOMER'")
                .contains(") NOT VALID;");
    }

    private static String migration() {
        try (InputStream migration =
                InformationRequestMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("migration not on the classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
