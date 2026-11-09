package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * No Spring profile decides behaviour (`P15-TSK-031`, ADR-0125 decision 2): an environment differs by values, never by a code path,
 * because a profile is a switch whose name can relax a control. The guards key on loopback, not on a profile's name
 * ({@code DatabaseCredentialGuard}).
 *
 * <p>Read as text, over every module's production source and test source, every configuration file of the application and everything
 * that starts it: the Dockerfile, compose, CI, {@code deploy/} and {@code infra/}. A profile can be chosen by any of those, so a guard
 * over the Java alone would hold half of the rule. {@code @ActiveProfiles} in a test is refused too, so no test exercises a
 * configuration production never runs.
 */
@Tag("architecture")
@DisplayName("no Spring profile decides behaviour (P15-TSK-031)")
class NoSpringProfileDecidesBehaviourTest {

    /** Every way this codebase's languages could choose or depend on a profile. */
    private static final Pattern PROFILE = Pattern.compile(
            "@Profile\\b|@ActiveProfiles\\b|org\\.springframework\\.context\\.annotation\\.Profile\\b"
                    + "|\\b(?:getActiveProfiles|getDefaultProfiles|acceptsProfiles|setActiveProfiles|addActiveProfile|setDefaultProfiles|setAdditionalProfiles)\\b"
                    + "|\\.profiles\\s*\\("
                    + "|spring\\.profiles\\b|spring\\.config\\.activate\\.on-profile|SPRING_PROFILES_(?:ACTIVE|DEFAULT|INCLUDE)\\b");

    private static final Set<String> TEXT_FILES = Set.of(".yaml", ".yml", ".properties", ".sh", ".kts", ".conf", ".env");
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".gradle", "build", "docs", ".claude", "node_modules");

    /** This class spells the things it refuses, and the tests' plants do too. */
    private static final Set<String> SPELLS_THE_RULE = Set.of("NoSpringProfileDecidesBehaviourTest.java");

    @Test
    @DisplayName("no source, configuration or deployment file names a profile")
    void nothingNamesAProfile() throws IOException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        Set<String> offences = new TreeSet<>();
        int[] read = {0};
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).filter(file -> isRead(root.relativize(file))).forEach(file -> {
                read[0]++;
                if (namesAProfile(text(file))) {
                    offences.add(root.relativize(file).toString().replace('\\', '/'));
                }
            });
        }
        assertThat(read[0]).as("the tree was read").isGreaterThan(3000);
        assertThat(offences).as("an environment differs by values, never by a code path").isEmpty();
    }

    @Test
    @DisplayName("no application-<name> configuration file exists anywhere a profile would load one from")
    void noProfileNamedConfigurationFile() throws IOException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        List<String> found;
        try (Stream<Path> walk = Files.walk(root)) {
            found = walk.filter(Files::isRegularFile)
                    .filter(file -> isRead(root.relativize(file)))
                    .map(file -> root.relativize(file).toString().replace('\\', '/'))
                    .filter(path -> Pattern.compile("(^|/)application-[^/]+\\.(ya?ml|properties)$").matcher(path).find())
                    .toList();
        }
        assertThat(found).isEmpty();
    }

    @ParameterizedTest
    @DisplayName("each way of naming a profile is refused")
    @ValueSource(strings = {
        "@Profile(\"production\")",
        "@org.springframework.context.annotation.Profile(\"!local\")",
        "import org.springframework.context.annotation.Profile;",
        "@ActiveProfiles(\"staging\")",
        "if (environment.acceptsProfiles(Profiles.of(\"production\"))) {}",
        "environment.getActiveProfiles()",
        "application.setAdditionalProfiles(profile);",
        "environment.setActiveProfiles(profile);",
        "new SpringApplicationBuilder(Finapp.class).profiles(\"production\")",
        "spring.profiles.active=production",
        "spring.profiles.include: extra",
        "spring.config.activate.on-profile: production",
        "- SPRING_PROFILES_ACTIVE=production",
        "env:\n  - name: SPRING_PROFILES_DEFAULT\n"
    })
    void thePlantsAreRefused(String plant) {
        assertThat(namesAProfile(plant)).as(plant).isTrue();
    }

    @Test
    @DisplayName("a YAML profile key nested the way Spring reads it is refused: the flat spelling above is not the only one")
    void aNestedYamlProfileKeyIsRefused() {
        assertThat(profilesKeyIn(Map.of("spring", Map.of("profiles", Map.of("active", "production"))))).isTrue();
        assertThat(profilesKeyIn(Map.of("spring", Map.of("config", Map.of("activate", Map.of("on-profile", "production")))))).isTrue();
        assertThat(profilesKeyIn(Map.of("spring", Map.of("application", Map.of("name", "finapp"))))).isFalse();
        assertThat(profilesKeyIn(SecretInventoryIsCompleteTest.loadAll(SecretInventoryIsCompleteTest.read("app/src/main/resources/application.yaml")).get(0)))
                .as("application.yaml itself").isFalse();
    }

    @ParameterizedTest
    @DisplayName("a word that only looks like one is not: the domain's own profiles are something else")
    @ValueSource(strings = {
        "ProfileService.Profile read(Session current)",
        "RiskProfileController",
        "@GetMapping(\"/v1/me/profile\")",
        "seccompProfile:",
        "finapp.risk.profile.cadence"
    })
    void aDomainProfileIsNotASpringProfile(String source) {
        assertThat(namesAProfile(source)).as(source).isFalse();
    }

    private static boolean namesAProfile(String text) {
        return PROFILE.matcher(text).find();
    }

    private static boolean profilesKeyIn(Map<?, ?> tree) {
        Object spring = tree.get("spring");
        if (!(spring instanceof Map<?, ?> springMap)) {
            return false;
        }
        if (springMap.containsKey("profiles")) {
            return true;
        }
        return springMap.get("config") instanceof Map<?, ?> config && config.get("activate") instanceof Map<?, ?> activate
                && activate.containsKey("on-profile");
    }

    /** {@code relative} to the repository's root, because the root itself may sit under a directory excluded below. */
    private static boolean isRead(Path relative) {
        for (Path part : relative) {
            if (EXCLUDED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        String name = relative.getFileName().toString();
        if (SPELLS_THE_RULE.contains(name)) {
            return false;
        }
        return name.endsWith(".java") || name.equals("Dockerfile") || TEXT_FILES.stream().anyMatch(name::endsWith);
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.nio.charset.MalformedInputException binary) {
            return "";
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
