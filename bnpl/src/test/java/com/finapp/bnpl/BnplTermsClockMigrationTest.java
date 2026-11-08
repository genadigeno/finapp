package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V021}'s three stamps as written (`P15-TSK-033`, PI-014 item 1, lending {@code V033}'s twin; {@code INV-HIST-04}) - a text pin, which proves only
 * the text: the clauses run, under a database clock stepped back on demand, in {@code BnplTermsDatabaseTest#anOfferBeforeABackwardStepStandsInsideItsVersion},
 * owed to {@code P15-TST-002} (C14). It keeps the retirement's end after the latest offer, the successor's start on its predecessor's end by row order, and the
 * offering instant on or after its version's start from leaving unnoticed.
 */
@DisplayName("bnpl V021: a terms version ends after its offers, its successor starts there, an offer is never before its version (P15-TSK-033)")
class BnplTermsClockMigrationTest {

    private static final String V021 = read("db/migration/bnpl/V021__a_terms_version_ends_after_its_offers_and_its_successor_starts_there.sql");

    @Test
    @DisplayName("the retirement ends the version one microsecond after its latest offer, beside V002's three terms - never the clock's reading alone")
    void theRetirementEndsAfterTheLatestOffer() {
        assertThat(V021).contains("NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond',\n"
                + "                (SELECT max(o.offered_at) FROM bnpl.bnpl_offer o WHERE o.terms_version_id = OLD.id) + interval '1 microsecond');");
        assertThat(V021).doesNotContain("NEW.effective_to := GREATEST(clock_timestamp(), transaction_timestamp(), OLD.effective_from + interval '1 microsecond');");
        assertThat(V021).contains("CREATE INDEX bnpl_offer_by_terms_version ON bnpl.bnpl_offer (terms_version_id, offered_at);");
    }

    @Test
    @DisplayName("the successor starts exactly at its predecessor's end, the product's highest-numbered RETIRED version, chosen by row order - never the"
            + " greater of the start and every end of the product")
    void theSuccessorStartsExactlyThere() {
        assertThat(V021).contains("NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN COALESCE(\n"
                + "                (SELECT v.effective_to FROM bnpl.bnpl_terms_version v\n"
                + "                  WHERE v.product = NEW.product AND v.status = 'RETIRED' AND v.version < NEW.version\n"
                + "                  ORDER BY v.version DESC LIMIT 1),\n"
                + "                transaction_timestamp()) END;");
        assertThat(V021).doesNotContain("max(v.effective_to)");
    }

    @Test
    @DisplayName("the offering instant is never before the pinned version's start, read with the pin's ACTIVE row; the closure still never before the offering")
    void theOfferIsNeverBeforeItsVersion() {
        assertThat(V021).contains("SELECT t.offer_validity_minutes, v.effective_from INTO validity_minutes, version_start")
                .contains("WHERE v.id = NEW.terms_version_id AND v.product = NEW.product AND v.status = 'ACTIVE';")
                .contains("NEW.offered_at := GREATEST(clock_timestamp(), version_start);")
                .contains("NEW.closed_at := GREATEST(clock_timestamp(), OLD.offered_at);");
    }

    @Test
    @DisplayName("V021 replaces exactly the two functions it names, and grants, drops and writes nothing")
    void itReplacesOnlyWhatItNames() {
        assertThat(V021.split("CREATE OR REPLACE FUNCTION ", -1)).hasSize(3);
        assertThat(V021).contains("CREATE OR REPLACE FUNCTION bnpl.bnpl_terms_version_permits_only_machine_edges()")
                .contains("CREATE OR REPLACE FUNCTION bnpl.bnpl_offer_permits_only_machine_edges()")
                .doesNotContain("GRANT ").doesNotContain("DROP ").doesNotContain("INSERT INTO").doesNotContain("ALTER TABLE");
    }

    private static String read(String resource) {
        try (InputStream stream = BnplTermsClockMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
