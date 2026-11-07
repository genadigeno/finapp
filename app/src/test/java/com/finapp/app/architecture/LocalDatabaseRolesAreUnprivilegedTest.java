package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * {@code CREATE ROLE} in the initialisation scripts and holds each to {@code NOSUPERUSER NOCREATEDB NOCREATEROLE
 * NOINHERIT}. It holds the local stack and the test databases built from those scripts, nothing deployed: in a
 * deployed database the roles come from the one-time bootstrap step, {@code P15-TSK-004}'s.
 */
@Tag("architecture")
@DisplayName("the local database roles are unprivileged (P15-TSK-001)")
class LocalDatabaseRolesAreUnprivilegedTest {

    private static final Path SCRIPTS = Path.of("infra/postgres/initdb");
    private static final List<String> REQUIRED = List.of("NOSUPERUSER", "NOCREATEDB", "NOCREATEROLE", "NOINHERIT");
    private static final Pattern CREATE_ROLE = Pattern.compile("(?s)CREATE ROLE\\s+(\\w+)(.*?);");

    @Test
    @DisplayName("every role the initialisation scripts create is NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT")
    void everyRoleIsUnprivileged() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        Map<String, String> roles = new TreeMap<>();
        try (Stream<Path> scripts = Files.list(root.resolve(SCRIPTS))) {
            for (Path script : scripts.filter(file -> file.toString().endsWith(".sql")).sorted().toList()) {
                Matcher role = CREATE_ROLE.matcher(EvidenceCitations.read(script));
                while (role.find()) {
                    roles.put(role.group(1), role.group(2));
                }
            }
        }
        assertThat(roles.keySet())
                .as("the scripts' roles were read: the migrator and the application")
                .containsExactlyInAnyOrder("finapp_migrator", "finapp_app");
        roles.forEach((name, attributes) -> assertThat(attributes.split("\\s+"))
                .as(name + "'s attributes")
                .contains(REQUIRED.toArray(String[]::new)));
    }
}
