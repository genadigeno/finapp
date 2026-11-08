package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The retention policy says what C12 requires it to say (`P15-TSK-029`): every class of data named, a jurisdiction's periods
 * open and never invented, no audit record deleted, and no claim of compliance.
 *
 * <p>Read from the rows of its table, so a class in a paragraph cannot pass for one in the policy. It holds what the document
 * states. Whether code matches is held by {@code AuditImmutabilityTest} and the sweeps' own tests.
 */
@Tag("architecture")
@DisplayName("the retention policy is complete (P15-TSK-029, C12)")
class RetentionPolicyIsCompleteTest {

    private static final String POLICY = "docs/architecture/DATA_RETENTION.md";

    private static final int PERIOD = 2;

    private static final int DATA = 1;

    private static final int CLASS = 0;

    @Test
    @DisplayName("the five classes each have a row, and the named data sits in the class C12 and the rulings gave it")
    void everyClassHasARow() {
        assertThat(rows("Operational")).as("operational").hasSizeGreaterThanOrEqualTo(4);
        assertThat(rows("Financial")).as("financial").isNotEmpty();
        assertThat(rows("Evidence")).as("evidence").hasSizeGreaterThanOrEqualTo(3);
        assertThat(rows("Audit")).as("audit").hasSize(1);
        assertThat(rows("Regulatory")).as("regulatory").hasSize(1);

        assertInClass("Operational", "platform.outbox_event", "platform.inbox_message", "platform.idempotency_record", "identity.session");
        assertInClass("Financial", "Journal entries");
        assertInClass("Evidence", "credit.credit_evidence", "lending.loan_agreement", "lending.loan_acceptance",
                "bnpl.bnpl_agreement", "bnpl.bnpl_acceptance");
        assertInClass("Audit", "platform.audit_record");
        assertInClass("Regulatory", "risk.regulatory_report");
    }

    @Test
    @DisplayName("every period a jurisdiction would set is open, and states no number of its own")
    void everyJurisdictionDependentPeriodIsOpen() {
        for (String klass : List.of("Evidence", "Regulatory")) {
            for (String[] row : rows(klass)) {
                assertThat(row[PERIOD]).as("the period of %s", row[DATA]).contains("`OPEN`");
                assertThat(row[PERIOD]).as("no invented period for %s", row[DATA]).doesNotContainPattern("\\b\\d+\\s*(?:days?|months?|years?)\\b");
            }
        }
        assertThat(rows("Audit").get(0)[PERIOD]).as("an audit archive's period").contains("`OPEN`");
    }

    @Test
    @DisplayName("no audit record is ever deleted: the policy says so, and its archival is a verified copy to write-once storage")
    void auditIsNeverDeletedAndItsArchivalIsACopy() {
        assertThat(rows("Audit").get(0)[PERIOD]).contains("Never deleted");
        String archival = section("## 3. ");
        assertThat(archival)
                .contains("No audit record is ever deleted")
                .contains("**copy** to write-once storage")
                .contains("Verify, then record")
                .contains("Claim leaderlessly")
                .contains("queryable")
                .contains("Phase 16");
    }

    @Test
    @DisplayName("nothing financial has a period that ends it")
    void nothingFinancialEnds() {
        for (String[] row : rows("Financial")) {
            assertThat(row[PERIOD]).isEqualTo("Never deleted");
        }
    }

    @Test
    @DisplayName("the swept classes name the settings that bound them, and no idempotency record is swept")
    void theBoundsAreNamed() {
        assertThat(row("platform.outbox_event")[PERIOD]).contains("`finapp.outbox.retention`");
        assertThat(row("platform.inbox_message")[PERIOD]).contains("`finapp.inbox.retention`");
        assertThat(row("platform.idempotency_record")[PERIOD]).contains("not swept");
        assertThat(row("identity.session")[PERIOD]).contains("not swept");
    }

    @Test
    @DisplayName("it claims no compliance: no standard or law is named as met")
    void itClaimsNoCompliance() {
        String policy = policy().toLowerCase(Locale.ROOT);
        for (String word : List.of("compliant", "certified", "gdpr", "pci", "soc 2", "iso 27001", "attestation", "satisfies the")) {
            assertThat(policy).as("the policy must not say '%s' (PHASE_15_PLAN.md section 1.1)", word).doesNotContain(word);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static void assertInClass(String klass, String... data) {
        String joined = String.join(" ", rows(klass).stream().map(row -> row[DATA]).toList());
        for (String item : data) {
            assertThat(joined).as("%s holds %s", klass, item).contains(item);
        }
    }

    private static String[] row(String data) {
        return allRows().stream()
                .filter(row -> row[DATA].contains(data))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row names " + data));
    }

    private static List<String[]> rows(String klass) {
        return allRows().stream().filter(row -> row[CLASS].equals("**" + klass + "**")).toList();
    }

    /** The table of section 1, each row's cells trimmed. */
    private static List<String[]> allRows() {
        List<String[]> rows = new ArrayList<>();
        for (String line : section("## 1. ").split("\\R")) {
            if (line.startsWith("| **")) {
                String[] cells = line.substring(1, line.length() - 1).split("\\s\\|\\s?");
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].trim();
                }
                assertThat(cells).as("six cells: %s", line).hasSize(6);
                rows.add(cells);
            }
        }
        assertThat(rows).as("the table was read").hasSizeGreaterThan(8);
        return rows;
    }

    private static String section(String heading) {
        String policy = policy();
        int start = policy.indexOf("\n" + heading);
        assertThat(start).as("the section %s exists", heading).isPositive();
        int end = policy.indexOf("\n## ", start + 1);
        return end < 0 ? policy.substring(start) : policy.substring(start, end);
    }

    private static String policy() {
        try {
            return Files.readString(RepositoryPaths.locate(POLICY), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
