package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The local database roles hold no administrative right ({@code P15-TSK-001}'s fix round; {@code THREAT_MODEL.md}
 * T7 and T33).
 *
 * <p>The register first cited {@code infra/postgres/initdb/00-roles.sql} by its existence. This reads every
 * statement of the initialisation scripts: each {@code CREATE ROLE} must say {@code NOSUPERUSER NOCREATEDB
 * NOCREATEROLE NOINHERIT} and claim no right or membership, no {@code ALTER ROLE} may lift a right afterwards, and no
 * {@code GRANT <role> TO <role>} may hand one role another's. It holds the local stack and the test databases built
 * from those scripts, nothing deployed: in a deployed database the roles come from the one-time bootstrap step,
 * {@code P15-TSK-004}'s.
 */
@Tag("architecture")
@DisplayName("the local database roles are unprivileged (P15-TSK-001)")
class LocalDatabaseRolesAreUnprivilegedTest {

    private static final Path SCRIPTS = Path.of("infra/postgres/initdb");
    private static final List<String> REQUIRED = List.of("NOSUPERUSER", "NOCREATEDB", "NOCREATEROLE", "NOINHERIT");

    /** A right a role may never hold or be given, and the clauses of CREATE ROLE that hand it memberships. */
    private static final Set<String> LIFTING = Set.of(
            "SUPERUSER", "CREATEDB", "CREATEROLE", "INHERIT", "BYPASSRLS", "REPLICATION", "IN", "ROLE", "ADMIN",
            "USER", "GROUP");

    private static final Pattern CREATE_ROLE = Pattern.compile("(?is)\\bCREATE\\s+ROLE\\s+(\\w+)(.*)");
    private static final Pattern ALTER_ROLE = Pattern.compile("(?is)\\bALTER\\s+(?:ROLE|USER)\\s+(\\w+)(.*)");
    private static final Pattern GRANT = Pattern.compile("(?is)\\bGRANT\\b(.*?)\\bTO\\b");

    @Test
    @DisplayName("every role the initialisation scripts create is NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT, and"
            + " nothing alters or grants that away")
    void everyRoleIsUnprivileged() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<Path> scripts;
        try (Stream<Path> files = Files.list(root.resolve(SCRIPTS))) {
            scripts = files.sorted().toList();
        }
        assertThat(scripts)
                .as("the entrypoint runs .sh and .sql.gz scripts too, and this reads SQL only")
                .allSatisfy(script -> assertThat(script.toString()).endsWith(".sql"));
        Map<String, String> roles = new TreeMap<>();
        List<String> lifted = new ArrayList<>();
        for (Path script : scripts) {
            for (String statement : statements(EvidenceCitations.read(script))) {
                Matcher create = CREATE_ROLE.matcher(statement);
                if (create.find()) {
                    roles.put(create.group(1), create.group(2));
                    tokens(create.group(2)).stream().filter(LIFTING::contains)
                            .forEach(token -> lifted.add(create.group(1) + " is created with " + token));
                }
                Matcher alter = ALTER_ROLE.matcher(statement);
                if (alter.find() && tokens(alter.group(2)).stream().anyMatch(LIFTING::contains)) {
                    lifted.add("ALTER ROLE " + alter.group(1) + " lifts a right: " + statement.trim());
                }
                Matcher grant = GRANT.matcher(statement);
                while (grant.find()) {
                    if (!Pattern.compile("(?i)\\bON\\b").matcher(grant.group(1)).find()) {
                        lifted.add("a role membership is granted: " + statement.trim());
                    }
                }
            }
        }
        assertThat(roles.keySet())
                .as("the scripts' roles were read: the migrator, the application and report generation's (P15-TSK-021)")
                .containsExactlyInAnyOrder("finapp_migrator", "finapp_app", "finapp_reporting");
        roles.forEach((name, attributes) -> assertThat(tokens(attributes))
                .as(name + "'s attributes")
                .containsAll(REQUIRED));
        assertThat(lifted).as("a right or a membership the local roles must never hold").isEmpty();
    }

    /**
     * The script's statements, split on semicolons, its comments and its password literals removed first. Other
     * literals stay: a {@code GRANT} built as dynamic SQL ({@code EXECUTE format('GRANT ... TO ...')}) is a grant.
     */
    private static List<String> statements(String script) {
        String code = script.replaceAll("--[^\\n]*", "").replaceAll("(?i)PASSWORD\\s+'[^']*'", "PASSWORD ''");
        return Arrays.asList(code.split(";"));
    }

    private static List<String> tokens(String text) {
        return Arrays.stream(text.toUpperCase().split("[^A-Z_]+")).filter(token -> !token.isEmpty()).toList();
    }
}
