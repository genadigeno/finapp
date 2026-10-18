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
 * {@code bnpl V006} and {@code V007}'s text held to the code (`P12-TSK-013`, `P12-TSK-012`; {@code BnplOfferMigrationTest}'s
 * idiom - hermetic, so it runs on every build while the live ranks are {@code BnplAcceptanceDatabaseTest}'s and
 * {@code app}'s {@code BnplPurchaseDatabaseTest}'s): the purchase's arbiters, the financing's conservation, the deferred
 * INV-BNPL-02 triggers, the schedule's deferred conservation, the principal value floor and its ranks, the agreement's
 * exact copy, the evidence's MULTI_FACTOR, the offer's ACCEPTED edge, no money on a machine row, the grants, every stamp
 * after the locks, no other schema named; and the exposure view's columns and its grant.
 */
@DisplayName("bnpl V006 and V007 hold the acceptance's and the exposure view's ranks as the code declares them"
        + " (P12-TSK-013, P12-TSK-012)")
class BnplAcceptanceMigrationTest {

    private static final String V006 = "db/migration/bnpl/V006__the_acceptance_purchase_financing_and_obligation.sql";
    private static final String V007 = "db/migration/bnpl/V007__the_exposure_view.sql";

    @Test
    @DisplayName("one purchase per checkout session and per offer; one financing per purchase and per entry; one"
            + " obligation per purchase, agreement and decision (INV-BNPL-03)")
    void theArbitersArePresent() {
        assertThat(statements(V006))
                .contains("CONSTRAINT purchase_checkout_session_unique UNIQUE (checkout_session_id)")
                .contains("CONSTRAINT purchase_offer_unique UNIQUE (offer_id)")
                .contains("CONSTRAINT merchant_financing_pk PRIMARY KEY (purchase_id)")
                .contains("CONSTRAINT merchant_financing_journal_entry_unique UNIQUE (journal_entry_id)")
                .contains("CONSTRAINT bnpl_obligation_purchase_unique UNIQUE (purchase_id)")
                .contains("CONSTRAINT bnpl_obligation_agreement_unique UNIQUE (agreement_id)")
                .contains("CONSTRAINT bnpl_obligation_decision_unique UNIQUE (decision_id)")
                .contains("CONSTRAINT bnpl_agreement_version_unique UNIQUE (obligation_id, version)")
                .contains("CONSTRAINT bnpl_schedule_version_unique UNIQUE (obligation_id, schedule_version)")
                .contains("CONSTRAINT bnpl_instalment_sequence_unique UNIQUE (schedule_id, sequence)")
                .contains("CONSTRAINT bnpl_billing_instalment_unique UNIQUE (instalment_id)")
                .contains("CONSTRAINT bnpl_repayment_request_unique UNIQUE (obligation_id, kind, request_key)");
    }

    @Test
    @DisplayName("the financing conserves the purchase: gross = fee + net, the fee never negative, the net positive"
            + " (INV-BNPL-04)")
    void theFinancingConserves() {
        assertThat(constraint(V006, "merchant_financing_conserves")).contains("gross_minor = fee_minor + net_minor")
                .contains("fee_minor >= 0").contains("net_minor > 0");
    }

    @Test
    @DisplayName("a purchase, its financing and its obligation commit together - three deferred triggers on one function;"
            + " the purchase FINANCED only beside its financing and order; the offer ACCEPTED only beside its FINANCED"
            + " purchase, on the acceptance's side of the clock (INV-BNPL-02)")
    void theFinancingAndTheObligationAreOneFact() {
        String sql = statements(V006);
        for (String table : List.of("purchase", "merchant_financing", "bnpl_obligation")) {
            assertThat(sql).contains("    AFTER INSERT ON bnpl." + table + "\n    DEFERRABLE INITIALLY DEFERRED\n"
                    + "    FOR EACH ROW\n    EXECUTE FUNCTION bnpl.purchase_is_financed_at_commit();");
        }
        assertThat(sql).contains("purchase.status IS DISTINCT FROM 'FINANCED'")
                .contains("NOT EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.purchase_id = purchase.id)")
                .contains("OR obligation.id IS NULL THEN")
                .contains("IF NOT EXISTS (SELECT 1 FROM bnpl.merchant_financing f WHERE f.purchase_id = NEW.id) THEN")
                .contains("ELSIF NEW.status = 'ACCEPTED' THEN\n        IF OLD.expires_at <= statement_timestamp() THEN")
                .contains("IF NOT EXISTS (SELECT 1 FROM bnpl.purchase p WHERE p.offer_id = NEW.id AND p.status ="
                        + " 'FINANCED') THEN");
        assertThat(sql.substring(sql.indexOf("ELSIF NEW.status = 'ACCEPTED' THEN")))
                .contains("IF OLD.expires_at <= statement_timestamp() THEN");
    }

    @Test
    @DisplayName("a schedule conserves at commit: numbered 1..n and its principal the instalments' sum, version 1 exactly"
            + " the financed gross (INV-BNPL-05)")
    void theScheduleConserves() {
        assertThat(statements(V006)).contains("scheduled IS DISTINCT FROM schedule.principal_minor")
                .contains("highest IS DISTINCT FROM counted")
                .contains("f.gross_minor = schedule.principal_minor")
                .contains("EXECUTE FUNCTION bnpl.bnpl_schedule_conserves();")
                .contains("NEW.due_date <> schedule.start_date + 14 * (NEW.sequence - 1)");
    }

    @Test
    @DisplayName("the principal value floor from the first table: the purchase's day, then the latest billing; a billing"
            + " and a repayment refused before it and after the later of today and it (the Phase 11 gate's R1)")
    void thePrincipalValueFloor() {
        String sql = statements(V006);
        assertThat(sql).contains("CREATE OR REPLACE FUNCTION bnpl.principal_value_floor(obligation uuid)")
                .contains("(SELECT o.purchase_date FROM bnpl.bnpl_obligation o WHERE o.id = obligation)")
                .contains("(SELECT max(b.value_date) FROM bnpl.bnpl_billing b WHERE b.obligation_id = obligation)");
        assertThat(sql.split("value_floor := bnpl.principal_value_floor\\(NEW.obligation_id\\);", -1)).hasSize(3);
        assertThat(sql.split(Pattern.quote("IF NEW.value_date < value_floor\n            OR NEW.value_date >"
                + " GREATEST(bnpl.business_date_latest(obligation.servicing_zone), value_floor) THEN"), -1)).hasSize(3);
    }

    @Test
    @DisplayName("agreement version 1 is an exact copy of the offer's canonical terms, its hash held to the bytes, and"
            + " immutable for every role; the evidence MULTI_FACTOR by CHECK")
    void theAgreementIsAnExactCopy() {
        String sql = statements(V006);
        assertThat(constraint(V006, "bnpl_agreement_hash_is_the_terms"))
                .contains("terms_sha256 = sha256(convert_to(canonical_terms, 'UTF8'))");
        assertThat(constraint(V006, "bnpl_agreement_origin")).contains("version = 1 AND offer_id IS NOT NULL");
        assertThat(constraint(V006, "bnpl_acceptance_multi_factor")).contains("assurance = 'MULTI_FACTOR'");
        assertThat(sql).contains("OR terms.canonical_terms <> NEW.canonical_terms")
                .contains("OR terms.terms_sha256 <> NEW.terms_sha256 OR terms.document_sha256 <> NEW.document_sha256")
                .contains("RAISE EXCEPTION 'a BNPL agreement is immutable from insert, for every role")
                .doesNotContain("jsonb");
    }

    @Test
    @DisplayName("the status and reason CHECKs list exactly the enums' members")
    void theValueListsAreTheEnums() {
        assertThat(constraint(V006, "purchase_status_is_known")).contains(list(BnplPurchaseStatus.values()));
        assertThat(constraint(V006, "bnpl_obligation_status_is_known")).contains(list(BnplObligationStatus.values()));
        assertThat(constraint(V006, "bnpl_obligation_closure_coherent")).contains(list(BnplClosureReason.values()));
        assertThat(constraint(V006, "bnpl_repayment_kind_is_known")).contains("'"
                + BnplPurchaseStore.Repayment.AT_PURCHASE + "'");
    }

    @Test
    @DisplayName("no money on the purchase or the obligation; the purchase moves only its status and order; everything"
            + " else INSERT-only; never DELETE; every stamp the database's after the locks")
    void theGrantsAndStamps() {
        String sql = statements(V006);
        for (String machine : List.of("purchase", "bnpl_obligation")) {
            String table = sql.substring(sql.indexOf("CREATE TABLE bnpl." + machine + " ("));
            assertThat(table.substring(0, table.indexOf(");"))).as(machine).doesNotContain("_minor");
        }
        assertThat(grants(sql)).containsExactly(
                "GRANT SELECT, INSERT ON bnpl.purchase TO finapp_app",
                "GRANT UPDATE (status, order_id) ON bnpl.purchase TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.purchase_event TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.merchant_financing TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_obligation TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_obligation_event TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_agreement TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_acceptance TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_schedule TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_instalment TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_billing TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_repayment TO finapp_app",
                "GRANT SELECT, INSERT ON bnpl.bnpl_repayment_allocation TO finapp_app");
        assertThat(sql).doesNotContain("now()")
                .contains("NEW.authorised_at := clock_timestamp();")
                .contains("NEW.business_date := bnpl.business_date(NEW.authorised_at, zone);")
                .contains("NEW.opened_at := clock_timestamp();")
                .contains("NEW.accepted_at := clock_timestamp();")
                .contains("NEW.financed_at := GREATEST(clock_timestamp(), OLD.authorised_at);");
    }

    @Test
    @DisplayName("the migrations name no other schema - the ledger, credit, checkout and merchant are the domain's")
    void namesNoOtherSchema() {
        for (String resource : List.of(V006, V007)) {
            Matcher schemas = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant|identity|accounts"
                    + "|payments|public)\\.").matcher(statements(resource));
            assertThat(schemas.results().map(MatchResult::group).toList()).as(resource).isEmpty();
        }
    }

    @Test
    @DisplayName("the exposure view is the read's columns alone - a definer view, no figure, SELECT to the application"
            + " role and nothing else (P12-TSK-012)")
    void theExposureViewIsTheReadsAlone() {
        String sql = statements(V007);
        assertThat(sql).contains("CREATE VIEW bnpl.party_principal_exposure WITH (security_invoker = false) AS")
                .contains("SELECT o.id          AS obligation_id,\n       o.party_id,\n       o.decision_id,\n"
                        + "       o.product,\n       o.status,\n       o.currency\n  FROM bnpl.bnpl_obligation o;")
                .doesNotContain("_minor");
        assertThat(grants(sql)).containsExactly("GRANT SELECT ON bnpl.party_principal_exposure TO finapp_app");
    }

    // -----------------------------------------------------------------

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

    private static String constraint(String resource, String name) {
        String sql = statements(resource);
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

    private static String statements(String resource) {
        return migration(resource).lines()
                .filter(line -> !line.strip().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration(String resource) {
        try (InputStream in = BnplAcceptanceMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
