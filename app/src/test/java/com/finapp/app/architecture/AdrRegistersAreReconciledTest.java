package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The ADR governance registers, build-reconciled (P4-TSK-002).
 *
 * <p>An ADR's status is a fact written in <strong>two places</strong> — the file's own
 * {@code Status:} line and the status column of {@code docs/adr/README.md}'s index — with nothing
 * reconciling them. That is the exact mechanism behind every register decay this repository has
 * catalogued: {@code P2-DOC-001} found all four Phase 2 ADRs still {@code Proposed} in the index
 * after the files said {@code Accepted}, and {@code P3-DOC-001} met the identical decay one phase
 * later. Twice carried as "derive the index" and twice deferred, the item is paid here as the
 * pattern's own conclusion prescribes: <em>the registers with build guards have not decayed
 * once</em>.
 *
 * <p>Four claims, each its own test so a failure names its own defect:
 *
 * <ol>
 *   <li>The file set and the index rows are a <strong>bijection</strong> — every ADR file has
 *       exactly one row, every row a file, and every row's link resolves to the file it names.
 *   <li>The two status copies <strong>agree</strong>, compared on the leading token because the
 *       file form legitimately carries provenance ({@code Status: Accepted (2026-09-17,
 *       P3-DOC-001)}) that the index column does not.
 *   <li>Every status is in the <strong>closed vocabulary</strong> the README's own rules declare
 *       ({@code Proposed} → {@code Accepted} → {@code Superseded}) — because an equality-only
 *       check is satisfied by a typo present in both copies, and the vocabulary check is what
 *       catches it.
 *   <li>Every line inside the index section that looks like a row <strong>parses</strong> as one —
 *       the {@code P1-TSK-024} lesson applied at design time rather than found by a gate: a row
 *       the parser cannot read must be a build failure, never a silent absence that leaves its ADR
 *       "covered" by nothing.
 * </ol>
 *
 * <p>{@code DECISIONS.md} is deliberately not reconciled: it is curated prose rather than a status
 * copy, and a coverage check over prose is a false precision (the backlog's own scoping).
 * {@code ADR_TEMPLATE.md} is excluded by construction — the {@code ADR-*.md} glob does not match
 * it. The documents are declared {@code :app:test} inputs in {@code app/build.gradle.kts} as a
 * <strong>file tree, not a list</strong>, so a new ADR file re-runs this guard without anyone
 * remembering.
 */
@Tag("architecture")
@DisplayName("ADR governance registers are reconciled (P4-TSK-002)")
class AdrRegistersAreReconciledTest {

    /** The README's own rules line: Proposed → Accepted → Superseded. */
    private static final Set<String> STATUS_VOCABULARY = Set.of("Proposed", "Accepted", "Superseded");

    /**
     * The status line at the head of an ADR file. Anchored to the line start, so prose such as
     * ADR-0007's "- Status model: ..." cannot match; the optional parenthesised tail is
     * provenance (who flipped it, when) and is not part of the status.
     */
    private static final Pattern FILE_STATUS =
            Pattern.compile("^Status: (\\S+)(?: \\((.*)\\))?\\s*$", Pattern.MULTILINE);

    /**
     * An index row: {@code | [NNNN](file.md) | title | Status | phase | concern |}. The status
     * column is captured whole and validated against the vocabulary separately, so a malformed
     * status fails the vocabulary check by name rather than making the row invisible.
     */
    private static final Pattern INDEX_ROW = Pattern.compile(
            "^\\| \\[(\\d{4})\\]\\(([^)]+)\\) \\| [^|]+ \\| ([^|]+) \\| [^|]+ \\| [^|]+ \\|\\s*$");

    private record IndexEntry(String number, String linkTarget, String status) {}

    private static Map<String, String> fileStatuses; // ADR number -> status token
    private static Map<String, IndexEntry> indexEntries; // ADR number -> row
    private static List<String> unparseableIndexLines;

    @BeforeAll
    static void parseBothRegisters() {
        fileStatuses = new LinkedHashMap<>();
        Path adrDirectory = repositoryRoot().resolve("docs/adr");
        try (Stream<Path> files = Files.list(adrDirectory)) {
            files.filter(f -> f.getFileName().toString().matches("ADR-\\d{4}-.*\\.md"))
                    .sorted()
                    .forEach(file -> {
                        String number = file.getFileName().toString().substring(4, 8);
                        Matcher status = FILE_STATUS.matcher(read(file));
                        // No readable status line is a failure, not a skip: a file this parser
                        // passes over is an ADR nothing reconciles, which is the defect class
                        // this whole test exists to close.
                        assertThat(status.find())
                                .as("%s must carry a 'Status: <value>' line", file.getFileName())
                                .isTrue();
                        fileStatuses.put(number, status.group(1));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + adrDirectory, e);
        }

        indexEntries = new LinkedHashMap<>();
        unparseableIndexLines = new ArrayList<>();
        boolean inIndex = false;
        for (String line : read(adrDirectory.resolve("README.md")).split("\\R")) {
            if (line.startsWith("## ")) {
                inIndex = line.equals("## Index");
                continue;
            }
            // Only lines that LOOK like rows are considered; the header and separator rows of
            // the table do not start with "| [". A row-looking line that fails the pattern is
            // recorded rather than dropped — check 4's subject.
            if (!inIndex || !line.startsWith("| [")) {
                continue;
            }
            Matcher row = INDEX_ROW.matcher(line);
            if (!row.matches()) {
                unparseableIndexLines.add(line);
                continue;
            }
            IndexEntry entry = new IndexEntry(row.group(1), row.group(2), row.group(3).trim());
            IndexEntry previous = indexEntries.put(entry.number(), entry);
            assertThat(previous)
                    .as("ADR-%s has more than one index row", entry.number())
                    .isNull();
        }
    }

    @Test
    @DisplayName("every ADR file has exactly one index row, and every row a file its link resolves to")
    void everyAdrFileHasExactlyOneIndexRowAndEveryRowAFile() {
        // Non-vacuity: an empty parse would make every set-comparison below trivially green.
        // ADR-0001 is the anchor because it is the row that can never legitimately leave —
        // numbering is permanent and a superseded ADR stays indexed as Superseded.
        assertThat(fileStatuses).as("parsed ADR files").isNotEmpty().containsKey("0001");
        assertThat(indexEntries).as("parsed index rows").isNotEmpty().containsKey("0001");

        assertThat(indexEntries.keySet())
                .as("the index must hold exactly one row per ADR file — a missing row is an ADR "
                        + "the index denies exists; an extra row is a decision record that does "
                        + "not exist (a false owner is worse than no owner)")
                .containsExactlyInAnyOrderElementsOf(fileStatuses.keySet());

        Path adrDirectory = repositoryRoot().resolve("docs/adr");
        indexEntries.values().forEach(entry ->
                assertThat(adrDirectory.resolve(entry.linkTarget()))
                        .as("ADR-%s's index link (%s) must resolve to the file it names — a row "
                                + "whose link points at nothing is the orphan case wearing a "
                                + "working number", entry.number(), entry.linkTarget())
                        .exists());
    }

    @Test
    @DisplayName("the index status agrees with the file status, per ADR")
    void theIndexStatusAgreesWithTheFileStatus() {
        fileStatuses.forEach((number, fileStatus) -> {
            IndexEntry entry = indexEntries.get(number);
            if (entry == null) {
                return; // The bijection test owns that failure; one defect, one test.
            }
            assertThat(entry.status())
                    .as("ADR-%s: the README index says '%s' while the file says '%s' — the "
                            + "second-copy decay P2-DOC-001 and P3-DOC-001 each found by hand, "
                            + "now a build failure", number, entry.status(), fileStatus)
                    .isEqualTo(fileStatus);
        });
    }

    @Test
    @DisplayName("every status, in both copies, is in the closed vocabulary")
    void everyStatusIsInTheClosedVocabulary() {
        // Equality alone is satisfied by a typo present in both copies; the vocabulary is what
        // catches it. The set is the README's own rules line: Proposed -> Accepted -> Superseded.
        fileStatuses.forEach((number, status) ->
                assertThat(STATUS_VOCABULARY)
                        .as("ADR-%s's file status '%s'", number, status)
                        .contains(status));
        indexEntries.values().forEach(entry ->
                assertThat(STATUS_VOCABULARY)
                        .as("ADR-%s's index status '%s'", entry.number(), entry.status())
                        .contains(entry.status()));
    }

    @Test
    @DisplayName("every row-looking line inside the index section parses as a row")
    void everyIndexSectionLineParsesAsARow() {
        // A row the pattern cannot read is not in the maps at all, so every other check here
        // would silently stop covering its ADR — the exact shape P1-TSK-024 found in the
        // mutation register's parser and had to close one level out, applied at design time.
        assertThat(unparseableIndexLines)
                .as("index lines that look like rows but did not parse")
                .isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
