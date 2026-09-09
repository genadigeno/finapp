package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RoleName} and the {@code CHECK} constraint that persists it are one definition
 * (`P1-TSK-020`).
 *
 * <h2>Why this exists, and why it did not until the completion gate</h2>
 *
 * <p>{@code V010}'s own comment says <em>"RoleAssignmentMigrationTest fails the build if they
 * drift"</em>. <strong>No such test existed.</strong> That is the fifth occurrence of this pattern in
 * Phase 1 — after {@code V005}'s enum claim, {@code AuthenticationRequest}'s bounds claim,
 * {@code RequiresSession}'s fail-closed claim and {@code secretsAreWrapped}'s exemption — and each
 * time the harm is the same: <em>the sentence reads as true, so the next reader stops looking.</em>
 *
 * <p>{@code P1-TSK-013}'s gate found exactly this and {@code P1-TSK-007} had already established the
 * pattern. Writing the claim without the test is the failure mode; writing the test is the fix.
 *
 * <h2>What could have drifted is not cosmetic</h2>
 *
 * <p>A second {@link RoleName} added without touching the constraint is a value the domain produces
 * and the database refuses. It fails at the <strong>last write</strong>, after the permission model
 * has been reasoned about and the audit record composed, for a reason no error message explains —
 * and it fails on the table that decides who may do anything privileged at all.
 */
@DisplayName("RoleName and its CHECK constraint agree (P1-TSK-020)")
class RoleAssignmentMigrationTest {

    private static final String MIGRATION =
            "db/migration/identity/V010__create_role_assignment.sql";

    @Test
    @DisplayName("the LATEST definition of the role constraint lists exactly the enum's roles")
    void roleConstraintMatchesTheEnum() {
        // The latest, DERIVED - not V010, and the change is P2-TSK-004's. An applied migration
        // is history that cannot be edited, so a new role is a new migration replacing the
        // constraint, and pinning this check to any one version number is a list that goes
        // stale at the next role. The derivation walks every identity migration on the
        // classpath and takes the highest-numbered one that defines the constraint - so a
        // migration that widens the role set without the enum, or an enum value without its
        // migration, fails here whichever came first.
        assertThat(latestConstraintDefinition())
                .as("the newest migration defining role_assignment_role_is_known must match"
                        + " RoleName exactly")
                .contains("CHECK (role_name IN (" + RoleName.sqlValueList() + "))");
    }

    @Test
    @DisplayName("V010's original constraint is untouched history")
    void theOriginalConstraintIsHistory() {
        // The other half of forward-only migrations (ADR-0011): the derivation above frees the
        // enum to grow, and THIS pins what V010 said on the day it was applied - an edited
        // applied migration means the database and the repository disagree, which
        // flywayValidate refuses with a checksum mismatch nobody can repair.
        assertThat(migration()).contains("CHECK (role_name IN ('ADMINISTRATOR'))");
    }

    @Test
    @DisplayName("there is no permission column, because the mapping is code")
    void permissionsAreNotPersisted() {
        // ADR-0031's central decision, asserted rather than left to prose: WHO holds a role is data,
        // WHAT a role means is code. A permission column would make "why was this denied?" a
        // question you answer by evaluating rows - which is what the ADR rejected a policy engine to
        // avoid - and it would put the meaning of a role somewhere an insert can change it.
        //
        // Asserted against the COLUMN LIST rather than the file, and it took two attempts to locate
        // - the migration's `--` comments discuss permissions at length, and so does its
        // `COMMENT ON TABLE` body, which is a SQL statement and survives comment-stripping. Both
        // versions were right about the property and wrong about where to look. A test that asserts
        // the right thing in the wrong place is indistinguishable from one that works until the
        // prose changes.
        assertThat(columnList())
                .as("the role-to-permission mapping lives in RoleName, never in a column")
                .doesNotContain("permission");
    }

    @Test
    @DisplayName("the application role gets no DELETE")
    void theApplicationCannotDeleteAnAssignment() {
        // Revoked, never deleted. A role somebody held for a month is a fact about the past, and an
        // investigator asking "who could do this in March?" needs the row rather than its absence.
        assertThat(migration())
                .contains("GRANT SELECT, INSERT, UPDATE ON identity.role_assignment TO finapp_app")
                .doesNotContain("DELETE ON identity.role_assignment");
    }

    @Test
    @DisplayName("at most one LIVE assignment per identity and role, and revocation frees the slot")
    void theUniquenessRuleIsPartial() {
        // Partial deliberately, which is the credential shape rather than the login-identifier shape
        // (P1-TSK-005): re-granting a role somebody previously held is the ordinary thing that
        // happens when a person changes team and comes back, whereas reissuing a retired login
        // identifier would make somebody else's audit history ambiguous.
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX role_assignment_one_live_per_identity")
                .contains("WHERE revoked_at IS NULL");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Without this a moved or renamed file makes every assertion above pass over an empty string
        // - and the two `doesNotContain` assertions would pass most convincingly of all.
        assertThat(migration()).contains("CREATE TABLE identity.role_assignment");

        // And the column list specifically, because that is what permissionsAreNotPersisted reads.
        // An extraction returning nothing would make its `doesNotContain` vacuous, which is the one
        // failure mode a negative assertion cannot report on its own.
        assertThat(columnList()).contains("identity_id").contains("role_name");
    }

    /**
     * The {@code CREATE TABLE} body with its comments stripped - where a column can actually be.
     *
     * <p>Lower-cased, so a {@code PERMISSION} column would not slip past a case-sensitive check.
     */
    private static String columnList() {
        String sql = migration();
        int open = sql.indexOf("CREATE TABLE identity.role_assignment");
        int close = sql.indexOf("\n);", open);
        if (open < 0 || close < 0) {
            throw new IllegalStateException("V010 no longer declares a CREATE TABLE block");
        }
        return sql.substring(open, close)
                .lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .collect(java.util.stream.Collectors.joining("\n"))
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The content of the highest-numbered identity migration that defines the role constraint.
     *
     * <p>Derived from the migration directory rather than from a version literal, so the next
     * role's migration is found without anyone re-pointing this test — the stale-list defect,
     * closed the way this repository closes it everywhere. Fails loudly when the directory
     * cannot be listed or no migration defines the constraint, because a derivation returning
     * nothing would make the reconciliation above pass over an empty string.
     */
    private static String latestConstraintDefinition() {
        String latest = null;
        int latestVersion = -1;
        for (java.util.Map.Entry<String, String> migration : allMigrations().entrySet()) {
            java.util.regex.Matcher name =
                    java.util.regex.Pattern.compile("V(\\d+)__.*\\.sql").matcher(migration.getKey());
            if (!name.matches()) {
                continue;
            }
            String sql = migration.getValue();
            if (!sql.contains("role_assignment_role_is_known") || !sql.contains("CHECK")) {
                continue;
            }
            int version = Integer.parseInt(name.group(1));
            if (version > latestVersion) {
                latestVersion = version;
                latest = sql;
            }
        }
        if (latest == null) {
            throw new IllegalStateException("no migration defines role_assignment_role_is_known");
        }
        return latest;
    }

    /**
     * Every identity migration on the classpath, file name to content.
     *
     * <p>Handles both classpath shapes, because this module's own tests see its migrations
     * <strong>inside its jar</strong> ({@code java-library} packs before testing) while an IDE
     * run sees a resources directory — the {@code P0-TSK-036} finding that a sweep reading only
     * directories silently misses everything that arrives as a jar, met here on its first
     * outing as a file: the directory-only version of this helper threw on the jar URL, which
     * is the loud direction, and this is the fix rather than a narrower assertion.
     */
    private static java.util.Map<String, String> allMigrations() {
        String directory = "db/migration/identity";
        java.util.Map<String, String> migrations = new java.util.TreeMap<>();
        try {
            java.util.Enumeration<java.net.URL> roots =
                    RoleAssignmentMigrationTest.class.getClassLoader().getResources(directory);
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
                                try (InputStream sql = jar.getInputStream(entry)) {
                                    migrations.put(
                                            entry.getName().substring(directory.length() + 1),
                                            new String(sql.readAllBytes(), StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                } else {
                    java.io.File[] files = new java.io.File(root.getFile()).listFiles();
                    if (files != null) {
                        for (java.io.File file : files) {
                            if (file.getName().endsWith(".sql")) {
                                migrations.put(
                                        file.getName(),
                                        java.nio.file.Files.readString(
                                                file.toPath(), StandardCharsets.UTF_8));
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("unreadable jar URL for identity migrations", e);
        }
        if (migrations.isEmpty()) {
            throw new IllegalStateException("no identity migrations found on the test classpath");
        }
        return migrations;
    }

    /** From the classpath, as the two sibling migration tests do - one idiom, not three. */
    private static String migration() {
        try (InputStream migration =
                RoleAssignmentMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
