package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code bnpl V004}'s text held to the code (`P12-TSK-011`; {@code BnplApplicationMigrationTest}'s idiom - hermetic, so it
 * runs on every build while the live ranks are {@code app}'s {@code BnplOriginationDatabaseTest}'s): the offer's expiry is
 * stamped from the decision's validity AND the session's deadline AND the pinned validity, and the row's CHECK holds it
 * under both; the arbiters ({@code UNIQUE (application_id)}, {@code UNIQUE (decision_id)}); the terms hash held to the
 * bytes; the decline and the expiry each on its side of the database clock; {@code -> OFFERED} only beside the offer;
 * the status lists the enums'; no money on the machine row; the grants column-scoped; every stamp after the locks; no
 * other schema named.
 */
@DisplayName("bnpl V004 holds the offer machine's ranks as the code declares them (P12-TSK-011)")
class BnplOfferMigrationTest {

    private static final String RESOURCE = "db/migration/bnpl/V004__the_bnpl_offer.sql";

    @Test
    @DisplayName("the expiry is LEAST(the decision's validity, the session's deadline, offered_at + the pinned validity),"
            + " stamped by the trigger, and the row's CHECK never lets it outlive either")
    void theExpiryNeverOutlivesTheSessionOrTheDecision() {
        assertThat(statements()).contains("NEW.expires_at := LEAST(NEW.decision_valid_until, NEW.session_expires_at,\n"
                + "                                NEW.offered_at + make_interval(mins => validity_minutes));")
                .contains("NEW.offered_at := clock_timestamp();")
                .contains("NEW.next_step_at := NEW.expires_at;")
                .contains("IF NEW.expires_at <= NEW.offered_at THEN");
        assertThat(constraint("bnpl_offer_expiry_bounded")).contains("expires_at <= decision_valid_until")
                .contains("expires_at <= session_expires_at")
                .contains("expires_at <= offered_at + interval '" + BnplTerms.MAXIMUM_OFFER_VALIDITY_MINUTES
                        + " minutes'");
    }

    @Test
    @DisplayName("one offer per application and per decision; the application OFFERED only beside its offer; an offer"
            + " commits only beside its application's OFFERED edge and its terms")
    void theArbitersArePresent() {
        String sql = statements();
        assertThat(sql).contains("CONSTRAINT bnpl_offer_application_unique UNIQUE (application_id)")
                .contains("CONSTRAINT bnpl_offer_decision_unique UNIQUE (decision_id)")
                .contains("IF NEW.status = 'OFFERED' AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_offer o WHERE"
                        + " o.application_id = NEW.id) THEN")
                .contains("application.status <> 'AWAITING_DECISION'")
                .contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("committed without its application''s OFFERED edge")
                .contains("committed without its terms");
    }

    @Test
    @DisplayName("the decline and the expiry each on its side of the database clock, bracketing the domain's conditional;"
            + " ACCEPTED refused until P12-TSK-013")
    void theClockSidedEdges() {
        String sql = statements();
        assertThat(sql).contains("IF NEW.status = 'DECLINED' THEN\n        IF OLD.expires_at <= statement_timestamp() THEN")
                .contains("ELSIF NEW.status = 'EXPIRED' THEN\n        IF OLD.expires_at > clock_timestamp() THEN")
                .contains("is not an edge until its task admits it");
    }

    @Test
    @DisplayName("the canonical terms are text with their SHA-256 held to the bytes by CHECK - never jsonb")
    void theTermsAreHashedOverTheirBytes() {
        assertThat(constraint("bnpl_offer_terms_hash_is_the_terms"))
                .contains("terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8'))");
        assertThat(constraint("bnpl_offer_terms_canonical_shape")).contains("canonical_format = " + BnplOfferTerms.FORMAT);
        assertThat(statements()).contains("canonical_terms           text").doesNotContain("jsonb");
    }

    @Test
    @DisplayName("the status, product and plan CHECKs list exactly the enums' members")
    void theValueListsAreTheEnums() {
        assertThat(constraint("bnpl_offer_status_is_known")).contains(list(BnplOfferStatus.values()));
        assertThat(constraint("bnpl_offer_product_is_offered")).contains(list(BnplProduct.values()));
        assertThat(constraint("bnpl_offer_event_edge")).contains("to_status IN ('ACCEPTED', 'DECLINED', 'EXPIRED')");
    }

    @Test
    @DisplayName("no money on the machine row; the offer moves only its status and permit; the terms and history"
            + " INSERT-only; never DELETE; every stamp after the locks")
    void theGrantsAndStamps() {
        String sql = statements();
        String table = sql.substring(sql.indexOf("CREATE TABLE bnpl.bnpl_offer ("),
                sql.indexOf("CREATE INDEX bnpl_offer_due"));
        assertThat(table).doesNotContain("_minor").doesNotContain("currency");
        assertThat(grants(sql)).containsExactly(
                "GRANT SELECT, INSERT ON bnpl.bnpl_offer TO finapp_app",
                "GRANT UPDATE (status, next_step_at) ON bnpl.bnpl_offer TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_offer_terms TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_offer_event TO finapp_app");
        assertThat(sql).doesNotContain("transaction_timestamp()").doesNotContain("now()")
                .contains("NEW.closed_at := GREATEST(clock_timestamp(), OLD.offered_at);");
    }

    @Test
    @DisplayName("the migration names no other schema - credit's decision and checkout's session are the domain's")
    void namesNoOtherSchema() {
        Matcher schemas = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant|identity|accounts"
                + "|payments|public)\\.").matcher(statements());
        assertThat(schemas.results().map(MatchResult::group).toList()).isEmpty();
    }

    // -----------------------------------------------------------------

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

    private static String constraint(String name) {
        String sql = statements();
        int start = sql.indexOf("CONSTRAINT " + name + " CHECK");
        assertThat(start).as(name).isNotNegative();
        int next = sql.indexOf("CONSTRAINT ", start + 1);
        int end = sql.indexOf(");\n", start);
        int stop = next < 0 ? end : Math.min(next, end < 0 ? sql.length() : end);
        return sql.substring(start, stop < 0 ? sql.length() : stop).replaceAll("\\s+", " ");
    }

    private static List<String> grants(String sql) {
        return Arrays.stream(sql.split(";"))
                .map(statement -> statement.strip().replaceAll("\\s+", " "))
                .filter(statement -> statement.startsWith("GRANT") || statement.startsWith("REVOKE"))
                .toList();
    }

    private static String statements() {
        return migration().lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        try (InputStream in = BnplOfferMigrationTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
