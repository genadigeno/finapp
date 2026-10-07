package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link ReasonCode} enum and {@code credit V002}'s seeded catalogue agree in both directions
 * (P10-TSK-001, {@code INV-CRD-02}'s catalogue element).
 *
 * <p>A member without its row would let a policy rule name a code the database refuses, or - once
 * the decision's reason rows reference the catalogue - make a decision unstorable; a row without
 * its member would be a code no rule can name and no explanation can render. Each field is held:
 * the code, the category, the customer text and the adverse flag. This class reads the
 * migration's seed; {@code CreditMigrationTest} holds the enum to the live rows the same way.
 *
 * <p>The comparison is probed in-suite: a planted extra on each side must be reported, so a
 * comparison that reported nothing would fail here first.
 */
@DisplayName("the reason-code catalogue mirrors its enum both ways (P10-TSK-001)")
class ReasonCodeCatalogueTest {

    private static final String MIGRATION = "db/migration/credit/V002__the_reason_code_catalogue.sql";

    /** One seeded tuple: ('CRD-…', 'CATEGORY', 'text with '' doubled', TRUE|FALSE). */
    private static final Pattern ROW = Pattern.compile(
            "\\(\\s*'(CRD-[A-Z-]+)'\\s*,\\s*'([A-Z_]+)'\\s*,\\s*'((?:[^']|'')*)'\\s*,\\s*(TRUE|FALSE)\\s*\\)");

    private static final Pattern CATEGORY_CHECK = Pattern.compile("category IN \\(([^)]*)\\)");

    record Row(String code, String category, String customerText, boolean adverse) {}

    @Test
    @DisplayName("every member has its row and every row its member - code, category, customer text and"
            + " adverse flag")
    void theEnumAndTheSeedAgreeBothWays() {
        Set<Row> seeded = seededRows();
        assertThat(disagreements(enumRows(), seeded)).isEmpty();
    }

    @Test
    @DisplayName("the seed parsed is the whole seed - no tuple slipped past the pattern")
    void theParseSawEveryTuple() {
        // Non-vacuity: a pattern that silently missed a tuple would make the comparison above
        // report a missing row, but a pattern that missed EVERY tuple and an enum with no members
        // would agree. Count the tuples by their opening independently of the pattern.
        String seed = seedStatement();
        int openings = seed.split("\\('CRD-", -1).length - 1;
        assertThat(seededRows()).hasSize(openings).hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("the category CHECK lists exactly the ReasonCategory members")
    void theCategoryCheckIsTheEnum() {
        Matcher check = CATEGORY_CHECK.matcher(migration());
        assertThat(check.find()).as("the category CHECK in V002").isTrue();
        Set<String> listed = Arrays.stream(check.group(1).split(","))
                .map(value -> value.strip().replace("'", ""))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(listed).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ReasonCategory.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("the comparison bites: a planted extra member and a planted extra row are each reported")
    void aPlantedExtraOnEachSideIsCaught() {
        Set<Row> members = enumRows();
        Set<Row> rows = seededRows();

        Set<Row> withExtraMember = new LinkedHashSet<>(members);
        withExtraMember.add(new Row("CRD-PLANTED-MEMBER", "DATA", "planted", true));
        assertThat(disagreements(withExtraMember, rows))
                .singleElement().asString().contains("CRD-PLANTED-MEMBER").contains("no row");

        Set<Row> withExtraRow = new LinkedHashSet<>(rows);
        withExtraRow.add(new Row("CRD-PLANTED-ROW", "DATA", "planted", true));
        assertThat(disagreements(members, withExtraRow))
                .singleElement().asString().contains("CRD-PLANTED-ROW").contains("no member");

        // A field-level drift is a disagreement too, not a match on the code alone.
        Set<Row> drifted = rows.stream()
                .map(row -> row.code().equals("CRD-SOURCE-UNAVAILABLE")
                        ? new Row(row.code(), row.category(), row.customerText(), !row.adverse())
                        : row)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(disagreements(members, drifted)).anySatisfy(line -> assertThat(line).contains("CRD-SOURCE-UNAVAILABLE"));
    }

    @Test
    @DisplayName("the codes the plan names are catalogued - the unavailable-source fallback and the auto-approval"
            + " ceiling - and the ceiling alone is non-adverse")
    void theNamedCodesAreCatalogued() {
        assertThat(ReasonCode.SOURCE_UNAVAILABLE.code()).isEqualTo("CRD-SOURCE-UNAVAILABLE");
        assertThat(ReasonCode.AUTO_APPROVAL_CEILING.code()).isEqualTo("CRD-AUTO-APPROVAL-CEILING");
        assertThat(Arrays.stream(ReasonCode.values()).filter(code -> !code.adverse()))
                .containsExactly(ReasonCode.AUTO_APPROVAL_CEILING);
    }

    @Test
    @DisplayName("no customer text names a number - never a score, a threshold or a bureau's data (INV-CRD-02)")
    void noCustomerTextNamesANumber() {
        for (ReasonCode code : ReasonCode.values()) {
            assertThat(code.customerText()).as(code.code()).doesNotContainPattern("[0-9]").hasSizeLessThanOrEqualTo(300);
        }
    }

    // -----------------------------------------------------------------

    /** Every disagreement, one line each; empty when the two sides are the same set. */
    static List<String> disagreements(Set<Row> members, Set<Row> rows) {
        List<String> lines = new ArrayList<>();
        for (Row member : members) {
            if (!rows.contains(member)) {
                lines.add(member.code() + ": a member with no row matching it " + member);
            }
        }
        for (Row row : rows) {
            if (!members.contains(row)) {
                lines.add(row.code() + ": a row with no member matching it " + row);
            }
        }
        return lines;
    }

    static Set<Row> enumRows() {
        return Arrays.stream(ReasonCode.values())
                .map(code -> new Row(code.code(), code.category().name(), code.customerText(), code.adverse()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Row> seededRows() {
        Matcher matcher = ROW.matcher(seedStatement());
        Set<Row> rows = new LinkedHashSet<>();
        while (matcher.find()) {
            rows.add(new Row(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3).replace("''", "'"),
                    "TRUE".equals(matcher.group(4))));
        }
        return rows;
    }

    /** The INSERT statement alone, so a code quoted in a comment is never read as a row. */
    private static String seedStatement() {
        String migration = migration();
        int start = migration.indexOf("INSERT INTO credit.reason_code");
        assertThat(start).as("the seed INSERT in V002").isNotNegative();
        int end = migration.indexOf(";", start);
        return migration.substring(start, end);
    }

    private static String migration() {
        try (InputStream stream = ReasonCodeCatalogueTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
