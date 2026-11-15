package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A change to how a line is classified retires every stored checkpoint (`P16-REM-001`; ADR-0133 decision 3): a checkpoint's buckets
 * are a fold under the rules of the build that stored it, and a build whose rules differ would add its lines to buckets the old rules
 * made - a figure no single set of rules gives. So the rules carry a version ({@link CheckpointedPositionBreakdown#CLASSIFICATION_VERSION})
 * that every checkpoint records and every read matches, and this test pins what the version names: the statement's classifying columns,
 * {@link JdbcPositionBreakdown#line}, {@link PositionBreakdown.Bucket#of} and {@link BucketFold#key}, by a fingerprint of their text. A
 * change to any of them fails here until the version is bumped and its fingerprint recorded - a deliberate act that costs only a fold of
 * the whole history on each account's next read. A change of layout alone fails too, harmlessly: a bump is always safe.
 */
@DisplayName("the classification a checkpoint was folded under is versioned, and the version moves with it (P16-REM-001)")
class PositionClassificationIsVersionedTest {

    /** Each version's fingerprint. A new version adds its line; an old one is never edited. */
    private static final Map<Integer, String> FINGERPRINTS = Map.of(
            1, "37baf47c6b63bffbe7b5a60b4c63e2908ebdabf437742413dd5b6ee883afc399");

    private static final Path SOURCES = Path.of("src/main/java/com/finapp/ledger");

    @Test
    @DisplayName("the rules' text is the text the current version was recorded for")
    void theRulesAreTheVersionsRules() {
        assertThat(FINGERPRINTS.get(CheckpointedPositionBreakdown.CLASSIFICATION_VERSION))
                .as("the classification changed: bump CheckpointedPositionBreakdown.CLASSIFICATION_VERSION and record its fingerprint"
                        + " here (every stored checkpoint is then retired, and each account's next read folds its whole history)")
                .isEqualTo(fingerprint());
    }

    @Test
    @DisplayName("the fingerprint reads all four rules, not an empty text")
    void theFingerprintReadsTheRules() {
        assertThat(method("PositionBreakdown.java", "public static Bucket of(")).contains("byOrigin");
        assertThat(method("BucketFold.java", "static String key(Bucket line)")).contains("RECONCILIATION");
        assertThat(method("JdbcPositionBreakdown.java", "static Bucket line(ResultSet row")).contains("AdjustmentOrigin::named");
        assertThat(JdbcPositionBreakdown.CLASSIFIED_LINES).contains("AS counterparty").contains("AS namespace").contains("AS origin");
    }

    static String fingerprint() {
        String rules = String.join("\n",
                JdbcPositionBreakdown.CLASSIFIED_LINES,
                method("JdbcPositionBreakdown.java", "static Bucket line(ResultSet row"),
                method("PositionBreakdown.java", "public static Bucket of("),
                method("BucketFold.java", "static String key(Bucket line)"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rules.replaceAll("\\s+", " ").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException(absent);
        }
    }

    /** A method's text, from its signature to its closing brace. */
    private static String method(String file, String signature) {
        String source;
        try {
            source = Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        int start = source.indexOf(signature);
        assertThat(start).as("%s declares %s", file, signature).isNotNegative();
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int at = open; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, at + 1);
            }
        }
        throw new AssertionError(signature + " never closes in " + file);
    }
}
