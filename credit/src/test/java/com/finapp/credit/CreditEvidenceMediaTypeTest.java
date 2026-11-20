package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("credit evidence names its media type (P17-TSK-116, credit V027)")
class CreditEvidenceMediaTypeTest {

    private static final String MIGRATION = "db/migration/credit/V027__credit_evidence_names_its_media_type.sql";

    @Test
    @DisplayName("the provider's Content-Type is kept as it arrived; an absent, blank, unbounded or shapeless one reads"
            + " application/octet-stream - never refused, the evidence is kept whatever it is called")
    void theMediaTypeIsTheProvidersOrOctetStream() {
        assertThat(CreditEvidence.mediaTypeOf(Optional.of("application/json"))).isEqualTo("application/json");
        assertThat(CreditEvidence.mediaTypeOf(Optional.of(" application/json; charset=utf-8 ")))
                .isEqualTo("application/json; charset=utf-8");
        assertThat(CreditEvidence.mediaTypeOf(Optional.empty())).isEqualTo(CreditEvidence.OCTET_STREAM);
        assertThat(CreditEvidence.mediaTypeOf(Optional.of(""))).isEqualTo(CreditEvidence.OCTET_STREAM);
        assertThat(CreditEvidence.mediaTypeOf(Optional.of("json"))).isEqualTo(CreditEvidence.OCTET_STREAM);
        assertThat(CreditEvidence.mediaTypeOf(Optional.of("text/html\r\nX-Injected: 1")))
                .isEqualTo(CreditEvidence.OCTET_STREAM);
        assertThat(CreditEvidence.mediaTypeOf(Optional.of("a/" + "b".repeat(300)))).isEqualTo(CreditEvidence.OCTET_STREAM);
        assertThat(new CreditEvidence(new byte[] {1}).mediaType()).isEqualTo(CreditEvidence.OCTET_STREAM);
        assertThatThrownBy(() -> new CreditEvidence(new byte[] {1}, "not a type"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an answer's evidence is named after its normaliser, and the length and media type alone reach toString")
    void theAnswerIsNamedAfterItsNormaliser() {
        CreditDataAnswer.Unavailable unavailable = new CreditDataAnswer.Unavailable(
                CreditDataAnswer.UnavailableCause.PROVIDER_ERROR, Optional.of(new CreditEvidence("{\"secret\":1}".getBytes(StandardCharsets.UTF_8))));
        CreditDataAnswer named = unavailable.withEvidenceMediaType(Optional.of("application/problem+json"));
        assertThat(((CreditDataAnswer.Unavailable) named).evidence()).hasValueSatisfying(evidence -> {
            assertThat(evidence.mediaType()).isEqualTo("application/problem+json");
            assertThat(evidence.toString()).doesNotContain("secret").contains("application/problem+json");
        });
        assertThat(new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.TIMEOUT, Optional.empty())
                .withEvidenceMediaType(Optional.of("application/json"))).isEqualTo(
                        new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.TIMEOUT, Optional.empty()));
    }

    @Test
    @DisplayName("V027 adds a nullable column, its CHECK NOT VALID (expand-only), and a column grant - no definer replaced")
    void theMigrationIsExpandOnly() {
        assertThat(migration())
                .contains("ADD COLUMN media_type text;")
                .contains("char_length(media_type) BETWEEN 3 AND 255")
                .contains(") NOT VALID;")
                .contains("GRANT SELECT (media_type) ON credit.credit_evidence TO finapp_app;")
                .doesNotContain("CREATE OR REPLACE")
                .doesNotContain("UPDATE credit.credit_evidence");
    }

    private static String migration() {
        try (InputStream migration = CreditEvidenceMediaTypeTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
