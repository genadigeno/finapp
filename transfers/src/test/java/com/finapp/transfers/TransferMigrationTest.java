package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.money.MoneyColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002` and the code cannot drift (`P4-TSK-004`, the {@code P0-TSK-022} pattern).
 *
 * <p>Four generated artefacts, each with one definition: the status {@code CHECK} from
 * {@link TransferStatus#sqlValueList()} (twice more on the history's from/to columns), the
 * reason {@code CHECK} from {@link FailureReason#sqlValueList()}, the monetary shape from
 * {@link MoneyColumns.ColumnNames#ddl()} — pinned verbatim so the three-column shape cannot
 * drift per table — and, the sharp one, <strong>the transition trigger's edge conditions from
 * {@link TransferStatus#permittedTransitions()}</strong>: an edge added to the machine without
 * its trigger half is a transition the aggregate permits and every other writer is refused, and
 * an edge in the trigger the machine lost is a move raw SQL can make that the domain cannot —
 * both silent without this reconciliation.
 *
 * <p>`V002` is pinned directly for everything it still defines; the <strong>reason
 * {@code CHECK} moved to the latest-definition derivation</strong> when `V004` replaced it
 * (`P4-TSK-010`) — the {@code RoleAssignmentMigrationTest} applied-history lesson, learned on
 * the day this class's own javadoc predicted it would have to be: the latest definition is
 * reconciled against the enum, and `V002`'s original five-value literal is pinned separately,
 * because history keeping its shape is its own claim.
 */
@DisplayName("transfer migration reconciliation (P4-TSK-004)")
class TransferMigrationTest {

    private static final String MIGRATION =
            "db/migration/transfers/V002__create_transfer_and_history.sql";

    @Test
    @DisplayName("the status CHECKs are generated from the machine, on all three columns")
    void statusChecksMatchTheEnum() {
        String list = TransferStatus.sqlValueList();
        assertThat(migration())
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("the LATEST reason CHECK is generated from the reason enum (P4-TSK-010)")
    void reasonCheckMatchesTheEnum() {
        // The latest, DERIVED - not V002, whose applied CHECK is history that cannot be edited
        // (ADR-0011): a reason added to the enum without its widening migration, or a widened
        // constraint the enum does not carry, fails whichever came first - and the derivation
        // finds the next widening's migration without anyone re-pointing this test.
        assertThat(latestReasonConstraintDefinition())
                .contains("CHECK (failure_reason IN (" + FailureReason.sqlValueList() + "))");
    }

    @Test
    @DisplayName("V002's original five-value reason literal keeps its shape, as history")
    void originalReasonCheckIsPinnedAsHistory() {
        assertThat(migration())
                .contains("CHECK (failure_reason IN ('INSUFFICIENT_FUNDS',"
                        + " 'SOURCE_NOT_POSTABLE', 'DESTINATION_NOT_POSTABLE',"
                        + " 'CURRENCY_MISMATCH', 'SELF_TRANSFER'))");
    }

    @Test
    @DisplayName("the reference bound is Transfer.MAX_REFERENCE_LENGTH (P4-TSK-008)")
    void referenceBoundMatchesTheConstant() {
        // The boundary DTO references the constant directly, so this is the one place the
        // number could drift from the column: a widened constant with the old CHECK would 500
        // at the last write on a value the boundary accepted.
        assertThat(migration())
                .contains("CHECK (length(reference) <= " + Transfer.MAX_REFERENCE_LENGTH + ")");
    }

    @Test
    @DisplayName("the monetary shape is MoneyColumns.ddl(), verbatim")
    void monetaryShapeIsTheGeneratedFragment() {
        assertThat(migration())
                .contains(
                        new MoneyColumns.ColumnNames("amount_minor", "currency", "scale").ddl());
    }

    @Test
    @DisplayName("the trigger's edge conditions are generated from permittedTransitions()")
    void triggerEdgesMatchTheMachine() {
        for (TransferStatus from : TransferStatus.values()) {
            if (from.isTerminal()) {
                // A terminal state must appear in NO edge condition as a source: the trigger
                // grants exits only to states the machine gives exits to (INV-LIFE-04).
                assertThat(migration())
                        .doesNotContain("OLD.status = '" + from.name() + "'");
                continue;
            }
            String condition = "(OLD.status = '" + from.name() + "' AND NEW.status IN ("
                    + from.permittedTransitions().stream()
                            .map(to -> "'" + to.name() + "'")
                            .collect(Collectors.joining(", "))
                    + "))";
            assertThat(migration())
                    .as("the trigger must carry %s's exact edge set", from)
                    .contains(condition);
        }
    }

    @Test
    @DisplayName("the grants are the planned set: the reversal columns and nothing more")
    void grantsAreTheColumnNarrowedSet() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON transfers.transfer TO finapp_app;")
                .contains("GRANT UPDATE (status, reversal_entry_id, reversed_by, reversed_at)"
                        + " ON transfers.transfer TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON transfers.transfer_event TO finapp_app;")
                .doesNotContain("GRANT UPDATE ON transfers")
                .doesNotContain("GRANT DELETE");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE transfers.transfer (");
    }

    /**
     * The content of the highest-numbered transfers migration defining the reason constraint —
     * the {@code RoleAssignmentMigrationTest} derivation, jar-aware for the same reason: this
     * module's own tests see its migrations inside the jar {@code java-library} packs, while an
     * IDE run sees a resources directory. Fails loudly when nothing defines the constraint,
     * because a derivation returning nothing would let the reconciliation pass over an empty
     * string.
     */
    private static String latestReasonConstraintDefinition() {
        String latest = null;
        int latestVersion = -1;
        for (java.util.Map.Entry<String, String> entry : allMigrations().entrySet()) {
            java.util.regex.Matcher name =
                    java.util.regex.Pattern.compile("V(\\d+)__.*\\.sql").matcher(entry.getKey());
            if (!name.matches()) {
                continue;
            }
            String sql = entry.getValue();
            if (!sql.contains("transfer_failure_reason_is_known") || !sql.contains("CHECK")) {
                continue;
            }
            int version = Integer.parseInt(name.group(1));
            if (version > latestVersion) {
                latestVersion = version;
                latest = sql;
            }
        }
        if (latest == null) {
            throw new IllegalStateException(
                    "no migration defines transfer_failure_reason_is_known");
        }
        return latest;
    }

    /** Every transfers migration on the classpath, file name to content — jar and directory. */
    private static java.util.Map<String, String> allMigrations() {
        String directory = "db/migration/transfers";
        java.util.Map<String, String> migrations = new java.util.TreeMap<>();
        try {
            java.util.Enumeration<java.net.URL> roots =
                    TransferMigrationTest.class.getClassLoader().getResources(directory);
            while (roots.hasMoreElements()) {
                java.net.URL root = roots.nextElement();
                if ("jar".equals(root.getProtocol())) {
                    java.net.JarURLConnection connection =
                            (java.net.JarURLConnection) root.openConnection();
                    // toURI, not getFile: on Windows the latter yields "/C:/..." with URL
                    // escaping intact, which is a path only sometimes.
                    try (java.util.jar.JarFile jar =
                            new java.util.jar.JarFile(
                                    java.nio.file.Path.of(connection.getJarFileURL().toURI())
                                            .toFile())) {
                        java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            java.util.jar.JarEntry entry = entries.nextElement();
                            if (entry.getName().startsWith(directory + "/")
                                    && entry.getName().endsWith(".sql")) {
                                try (InputStream in = jar.getInputStream(entry)) {
                                    migrations.put(
                                            entry.getName()
                                                    .substring(directory.length() + 1),
                                            new String(
                                                    in.readAllBytes(),
                                                    StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                } else {
                    java.nio.file.Path dir = java.nio.file.Path.of(root.toURI());
                    try (java.util.stream.Stream<java.nio.file.Path> files =
                            java.nio.file.Files.list(dir)) {
                        for (java.nio.file.Path file : files.toList()) {
                            if (file.getFileName().toString().endsWith(".sql")) {
                                migrations.put(
                                        file.getFileName().toString(),
                                        java.nio.file.Files.readString(file));
                            }
                        }
                    }
                }
            }
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "could not enumerate the transfers migrations", failure);
        }
        if (migrations.isEmpty()) {
            throw new IllegalStateException("no transfers migrations found on the classpath");
        }
        return migrations;
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                TransferMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
