package com.finapp.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.File;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The consent schema and the code that writes it are one definition (`P2-TSK-017`) — the
 * {@code ReviewTaskMigrationTest} idiom. The sharper artefacts here are the grants: the record
 * table's {@code SELECT, INSERT} and nothing else IS {@code INV-CNS-02}, and the text table's
 * {@code SELECT} alone is what makes a consent text a migration-reviewed artefact rather than a
 * row the application can quietly rewrite.
 */
@DisplayName("the consent schema and the code agree (P2-TSK-017; the newest definitions since P10-TSK-002)")
class ConsentMigrationTest {

    private static final String MIGRATION =
            "db/migration/consent/V002__create_consent_text_and_record.sql";

    @Test
    @DisplayName("each table's newest purpose constraint lists exactly what the enum declares")
    void purposeConstraintsMatchTheEnum() {
        // Two tables, two constraints, one enum: a purpose added to the code without both
        // constraints widening is a value the domain produces and the database refuses,
        // failing at the last write (the V005/IdentityEnumMigrationTest lesson). The NEWEST
        // definition, DERIVED from the migration directory - not V002: an applied migration is
        // history that cannot be edited, so a new purpose is a new migration redefining the
        // constraint (V003, P10-TSK-002 - the RoleAssignmentMigrationTest derivation).
        for (String constraint : PURPOSE_CONSTRAINTS) {
            assertThat(purposeListIn(latestDefinitionOf(constraint), constraint))
                    .as("the newest migration defining %s must match ConsentPurpose", constraint)
                    .isEqualTo(ConsentPurpose.sqlValueList());
        }
    }

    @Test
    @DisplayName("V002's original purpose constraints are untouched history")
    void theOriginalConstraintsAreHistory() {
        // The other half of forward-only migrations (ADR-0011): the derivation above frees the
        // enum to grow, and THIS pins what V002 said on the day it was applied.
        assertThat(countOf(migration(), "CHECK (purpose IN ('KYC_PROCESSING', 'SCREENING'))"))
                .as("consent_text and consent_record each constrained the purpose in V002")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a purpose is never removed: every purpose any consent migration ever admitted is still a member")
    void purposesAreNeverRemoved() {
        // ADR-0037's rule, made a build failure (P10-TSK-002): a purpose that stops being used
        // still names the basis of history rows that must stay interpretable (INV-CNS-02), and
        // a deleted member would leave those rows naming a basis the code cannot express.
        // Derived from every CHECK any consent migration ever wrote, so a removal fails here
        // whichever migration admitted the purpose.
        Set<String> everAdmitted = new TreeSet<>();
        Matcher check = Pattern.compile("CHECK \\(purpose IN \\(([^)]*)\\)\\)")
                .matcher(String.join("\n", allMigrations().values()));
        while (check.find()) {
            for (String value : check.group(1).split(",")) {
                everAdmitted.add(value.strip().replace("'", ""));
            }
        }
        assertThat(everAdmitted).as("the walk must see V002's two purposes at least")
                .contains("KYC_PROCESSING", "SCREENING");
        assertThat(Arrays.stream(ConsentPurpose.values()).map(Enum::name).toList())
                .as("a purpose is never removed (ADR-0037)")
                .containsAll(everAdmitted);
    }

    @Test
    @DisplayName("the action constraint lists exactly what the enum declares")
    void actionConstraintMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (action IN (" + ConsentAction.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the record table's grants are SELECT and INSERT, and nothing else")
    void recordGrantsAreThePrivilegeModel() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON consent.consent_record TO finapp_app")
                .as("an UPDATE or DELETE grant here is INV-CNS-02 repealed")
                .doesNotContain("UPDATE ON consent.consent_record")
                .doesNotContain("DELETE ON consent.consent_record");
    }

    @Test
    @DisplayName("the text table grants SELECT alone - texts arrive only by migration")
    void textsAreUnwritableByTheApplication() {
        assertThat(migration())
                .contains("GRANT SELECT ON consent.consent_text TO finapp_app")
                .doesNotContain("INSERT ON consent.consent_text TO")
                .doesNotContain("UPDATE ON consent.consent_text")
                .doesNotContain("DELETE ON consent.consent_text");
    }

    @Test
    @DisplayName("the version pin is composite - a record cannot pin another purpose's text")
    void theVersionPinIsComposite() {
        // The V008 lesson: two single-column FKs would let a record reference an artefact the
        // person was never shown; the composite makes the pinned version a version OF THIS
        // purpose at DB-CONSTRAINT rank.
        assertThat(migration())
                .contains("FOREIGN KEY (purpose, text_version)")
                .contains("REFERENCES consent.consent_text (purpose, version)");
    }

    @Test
    @DisplayName("the order of the history is server-assigned, and no client can supply it")
    void theOrderIsTheServers() {
        assertThat(migration())
                .as("GENERATED ALWAYS refuses client-supplied values - BY DEFAULT would accept"
                        + " them, and an instance's own counter deciding which racing fact is"
                        + " later is exactly what the backlog's P0-TST-009 citation forbids")
                .contains("GENERATED ALWAYS AS IDENTITY")
                .doesNotContain("GENERATED BY DEFAULT");
    }

    @Test
    @DisplayName("every enum member's v1 text is seeded by some consent migration, so currentTextFor cannot come"
            + " up empty")
    void everyPurposeHasASeededText() {
        // Across every consent migration, not V002 alone: the credit purposes' texts arrive in
        // V003 (P10-TSK-002), and a later purpose's in its own migration.
        String migrations = String.join("\n", allMigrations().values());
        for (ConsentPurpose purpose : ConsentPurpose.values()) {
            assertThat(migrations)
                    .as("a purpose without a seeded text is a purpose nothing can grant against: %s", purpose)
                    .contains("('" + purpose.name() + "', 1,");
        }
    }

    @Test
    @DisplayName("no cross-schema foreign key")
    void partyIsReferencedByValue() {
        assertThat(migration())
                .doesNotContain("REFERENCES party.")
                .doesNotContain("REFERENCES identity.")
                .doesNotContain("REFERENCES kyc.");
    }

    // -----------------------------------------------------------------

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** The two generated purpose constraints - one per table. */
    private static final List<String> PURPOSE_CONSTRAINTS =
            List.of("consent_text_purpose_is_known", "consent_record_purpose_is_known");

    /** The value list of the purpose CHECK that follows the named constraint in a migration. */
    private static String purposeListIn(String migration, String constraint) {
        Matcher definition = Pattern.compile(
                        Pattern.quote(constraint) + "\\s+CHECK \\(purpose IN \\(([^)]*)\\)\\)")
                .matcher(migration);
        if (!definition.find()) {
            throw new IllegalStateException("no CHECK follows " + constraint + " in its newest migration");
        }
        return definition.group(1);
    }

    /**
     * The content of the highest-numbered consent migration that defines the constraint with a
     * CHECK - derived, so the next purpose's migration is found without re-pointing this test.
     */
    private static String latestDefinitionOf(String constraint) {
        String latest = null;
        int latestVersion = -1;
        Pattern definition = Pattern.compile(Pattern.quote(constraint) + "\\s+CHECK");
        for (Map.Entry<String, String> migration : allMigrations().entrySet()) {
            Matcher name = Pattern.compile("V(\\d+)__.*\\.sql").matcher(migration.getKey());
            if (!name.matches() || !definition.matcher(migration.getValue()).find()) {
                continue;
            }
            int version = Integer.parseInt(name.group(1));
            if (version > latestVersion) {
                latestVersion = version;
                latest = migration.getValue();
            }
        }
        if (latest == null) {
            throw new IllegalStateException("no consent migration defines " + constraint);
        }
        return latest;
    }

    /**
     * Every consent migration on the classpath, file name to content - both classpath shapes, a
     * jar and a directory (the RoleAssignmentMigrationTest helper and its P0-TSK-036 reason).
     */
    private static Map<String, String> allMigrations() {
        String directory = "db/migration/consent";
        Map<String, String> migrations = new TreeMap<>();
        try {
            Enumeration<URL> roots = ConsentMigrationTest.class.getClassLoader().getResources(directory);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("jar".equals(root.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) root.openConnection();
                    try (JarFile jar = new JarFile(Path.of(connection.getJarFileURL().toURI()).toFile())) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            if (entry.getName().startsWith(directory + "/") && entry.getName().endsWith(".sql")) {
                                try (InputStream sql = jar.getInputStream(entry)) {
                                    migrations.put(
                                            entry.getName().substring(directory.length() + 1),
                                            new String(sql.readAllBytes(), StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                } else {
                    File[] files = new File(root.toURI()).listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.getName().endsWith(".sql")) {
                                migrations.put(file.getName(), Files.readString(file.toPath(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("unreadable URL for the consent migrations", e);
        }
        if (migrations.size() < 3) {
            throw new IllegalStateException("the consent migrations were not all found: " + migrations.keySet());
        }
        return migrations;
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream stream =
                ConsentMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
