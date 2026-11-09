package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What a committed ring configuration may say (`P15-TSK-019`): every literal in the one form the activation check reads, naming a
 * ring that exists, with its active version listed and each listed version a Secret entry. A typo here would be read
 * by nothing at all, and the ring would stay exactly as it was while the commit said otherwise.
 */
@Tag("architecture")
@DisplayName("a committed key-ring configuration is well-formed and names only real rings and versions (P15-TSK-019)")
class KeyRingDeployConfigurationTest {

    /** Anything that reads like a ring setting, however spelled: the script's pattern. The first character stops "spring". */
    private static final Pattern LITERAL = Pattern.compile("(?i)(^|.*[^a-z])ring[._-]*(active[._-]*version|retired[._-]*versions|versions).*");
    private static final Pattern FORM =
            Pattern.compile("^\\s*-\\s*(FINAPP_[A-Z0-9_]+)_RING_(ACTIVEVERSION|VERSIONS|RETIREDVERSIONS)=([0-9, ]*)$");

    /** The eight rings, by the stem their variables carry. */
    private static final Set<String> RINGS = Set.of(
            "FINAPP_MFA", "FINAPP_DOC", "FINAPP_PAYMENTS_EVIDENCE", "FINAPP_PAYMENTS_DISPUTE_EVIDENCE", "FINAPP_MERCHANT_PAYOUT_EVIDENCE",
            "FINAPP_SETTLEMENT_FILE", "FINAPP_FX_EVIDENCE", "FINAPP_CREDIT_EVIDENCE");

    @Test
    @DisplayName("every ring literal under deploy/ is in the one form, names a real ring, lists its active version, and has an entry for each listed version")
    void everyRingLiteralIsWellFormed() throws IOException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        Set<String> entries = secretEntries(root);
        assertThat(entries).as("the ExternalSecret was read").contains("FINAPP_DOC_V2_KEY", "FINAPP_DOC_V3_KEY");

        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root.resolve("deploy"))) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile).filter(KeyRingDeployConfigurationTest::isYaml)::iterator) {
                offences.addAll(judge(Files.readAllLines(file, StandardCharsets.UTF_8), entries, root.relativize(file).toString()));
            }
        }
        assertThat(offences).isEmpty();
    }

    @Test
    @DisplayName("the judge refuses each mistake it exists to catch, on planted lines, so an empty tree proves nothing about it")
    void theJudgeRefusesEachMistake() {
        Set<String> entries = Set.of("FINAPP_DOC_V2_KEY", "FINAPP_DOC_V3_KEY");
        assertThat(judge(List.of("      - FINAPP_DOC_RING_VERSIONS=1,2"), entries, "a")).isEmpty();
        assertThat(judge(List.of("      - FINAPP_DOC_RING_VERSIONS=1,2", "      - FINAPP_DOC_RING_ACTIVEVERSION=2"), entries, "a")).isEmpty();

        assertThat(judge(List.of("      - FINAPP_DOC_RING_ACTIVEVERSION: 2"), entries, "a")).as("another shape").hasSize(1);
        assertThat(judge(List.of("      - FINAPP_DCO_RING_VERSIONS=1"), entries, "a")).as("a ring nothing reads").hasSize(1);
        assertThat(judge(List.of("      - FINAPP_DOC_RING_VERSIONS=1", "      - FINAPP_DOC_RING_ACTIVEVERSION=2"), entries, "a"))
                .as("active is not listed").hasSize(1);
        assertThat(judge(List.of("      - FINAPP_DOC_RING_ACTIVEVERSION=2"), entries, "a")).as("active with no list").hasSize(1);
        assertThat(judge(List.of("      - FINAPP_DOC_RING_ACTIVE_VERSION=2"), entries, "a")).as("the legacy spelling Spring still binds").hasSize(1);
        assertThat(judge(List.of("      - finapp.doc.ring.active-version=2"), entries, "a")).as("a dotted property Spring still binds").hasSize(1);
        assertThat(judge(List.of("      - spring.versions=2"), entries, "a")).as("spring is not a ring").isEmpty();
        assertThat(judge(List.of("      - FINAPP_DOC_RING_VERSIONS=1,2,3,4", "      - FINAPP_DOC_RING_ACTIVEVERSION=2"), entries, "a"))
                .as("a version with no entry").hasSize(1);
    }

    @Test
    @DisplayName("the activation check is committed executable, carries its plants, and CI runs both them and the check against the base commit")
    void theActivationCheckRuns() throws IOException, InterruptedException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        String script = Files.readString(root.resolve("infra/scripts/key-ring-activation-check.sh"), StandardCharsets.UTF_8);
        assertThat(script).contains("_RING_ACTIVEVERSION").contains("_RING_VERSIONS").contains("--self-test").contains("top-version-dropped")
                .contains("git archive");

        String workflow = Files.readString(root.resolve(".github/workflows/ci.yml"), StandardCharsets.UTF_8);
        assertThat(workflow).contains("key-ring-activation-check.sh --self-test").contains("key-ring-activation-check.sh \"$BASE_REF\"");

        Process git = new ProcessBuilder("git", "ls-files", "-s", "infra/scripts/key-ring-activation-check.sh")
                .directory(root.toFile()).redirectErrorStream(true).start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the mode the index holds").startsWith("100755 ");
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static Set<String> secretEntries(Path root) {
        try {
            String delivery = Files.readString(root.resolve("deploy/components/secret-delivery/externalsecret-app.yaml"), StandardCharsets.UTF_8);
            Matcher entry = Pattern.compile("(?m)^\\s*- secretKey:\\s*(\\S+)\\s*$").matcher(delivery);
            Set<String> entries = new HashSet<>();
            while (entry.find()) {
                entries.add(entry.group(1));
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What is wrong with these lines of one file, per the three rules; empty when nothing is. */
    static List<String> judge(List<String> lines, Set<String> secretEntries, String file) {
        List<String> offences = new ArrayList<>();
        java.util.Map<String, String> versions = new java.util.HashMap<>();
        java.util.Map<String, String> active = new java.util.HashMap<>();
        for (String line : lines) {
            if (line.trim().startsWith("#") || !LITERAL.matcher(line).matches()) {
                continue;
            }
            Matcher form = FORM.matcher(line);
            if (!form.matches()) {
                offences.add(file + ": a ring literal not in the one form: " + line.trim());
                continue;
            }
            String ring = form.group(1);
            if (!RINGS.contains(ring)) {
                offences.add(file + ": no ring is read from " + ring);
                continue;
            }
            switch (form.group(2)) {
                case "VERSIONS" -> versions.put(ring, form.group(3).replace(" ", ""));
                case "ACTIVEVERSION" -> active.put(ring, form.group(3).replace(" ", ""));
                default -> { }
            }
        }
        for (java.util.Map.Entry<String, String> pointer : active.entrySet()) {
            String listed = versions.get(pointer.getKey());
            if (listed == null || !Arrays.asList(listed.split(",")).contains(pointer.getValue())) {
                offences.add(file + ": " + pointer.getKey() + " activates version " + pointer.getValue() + " which is not listed");
            }
        }
        for (java.util.Map.Entry<String, String> list : versions.entrySet()) {
            for (String version : list.getValue().split(",")) {
                if (!version.equals("1") && !secretEntries.contains(list.getKey() + "_V" + version + "_KEY")) {
                    offences.add(file + ": " + list.getKey() + " lists version " + version + " with no entry in the ExternalSecret");
                }
            }
        }
        return offences;
    }
}
