package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.MigrationLint.Floor;
import com.finapp.app.architecture.MigrationLint.Outcome;
import com.finapp.app.architecture.MigrationLint.Refusal;
import com.finapp.app.architecture.MigrationLint.Refused;
import com.finapp.app.database.SchemaVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No migration added since the last release can break that release's code (`P15-TSK-005`; ADR-0122 decision 5).
 *
 * <p>The rules are {@code ExpandOnlyLintTest}'s, on planted files; this class holds them on the real tree, through the same
 * {@link MigrationLint#check}. A floor at every module's head reads nothing, which on its own proves nothing, so
 * {@link #theLintReadsEveryRealMigration} reads the whole real tree with no floor at all, and {@link #aRealMigrationAboveAFloorIsRead}
 * reads a real migration above one.
 */
@Tag("architecture")
@DisplayName("every migration since the last release is expand-only, or marked (P15-TSK-005)")
class MigrationsAreExpandOnlyTest {

    /** Where the next floor is left, so that a merge or the release procedure can copy it over the committed file. */
    static final Path GENERATED = Path.of("build", "schema-floor", "released-schema-floor.yaml");

    /** The guard's own migration: above the floor just under it, it is a real file the lint must read and pass. */
    private static final String THE_GUARDS_MIGRATION = "__the_readiness_read_of_applied_versions.sql";

    private static Path root;
    private static Floor committed;
    private static SortedMap<String, SchemaVersion> head;

    @BeforeAll
    static void readTheTree() throws IOException {
        root = EvidenceCitations.repositoryRoot();
        committed = Floor.committed(root);
        head = MigrationLint.head(root);
        // Before any assertion, so that the next floor is left even when a test below goes red: that is how it is re-recorded.
        Files.createDirectories(GENERATED.getParent());
        Files.writeString(GENERATED, committed.rendered(head), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every migration above its module's floor is expand-only, or carries a contract marker")
    void everyMigrationSinceTheLastReleaseIsExpandOnly() throws IOException {
        assertThat(MigrationLint.check(root, committed).refusals())
                .as("expand first; if this really is a contract change, carry the marker (DATA_MIGRATIONS.md section 4.1)")
                .isEmpty();
    }

    @Test
    @DisplayName("the floor names real modules and no version above what is on disk")
    void theFloorIsHonest() throws IOException {
        List<String> modules = MigrationLint.modules(root);
        assertThat(modules).as("the walk found the migrating modules").contains("ledger", "platform").hasSizeGreaterThanOrEqualTo(21);
        assertThat(committed.floors().keySet()).as("a floor naming no module").isSubsetOf(modules);
        committed.floors().forEach((module, floor) -> assertThat(floor.compareTo(head.get(module)))
                .as("%s: a floor above every file would never read the next one added below it", module)
                .isLessThanOrEqualTo(0));
    }

    @Test
    @DisplayName("with no floor at all, every real migration is read to its end, each statement is understood, and what is refused is real")
    void theLintReadsEveryRealMigration() throws IOException {
        Outcome outcome = MigrationLint.check(root, MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of()));

        long files = 0;
        for (String module : MigrationLint.modules(root)) {
            try (var migrations = Files.list(root.resolve(module).resolve("src/main/resources/db/migration").resolve(module))) {
                files += migrations.count();
            }
        }
        assertThat(files).as("the walk found the migrations").isGreaterThan(300);
        assertThat(outcome.filesRead()).as("every file on disk was read").hasSize((int) files);

        assertThat(outcome.refusals().stream().filter(refusal -> refusal.rule() == Refused.UNREADABLE).toList())
                .as("a real migration the tokeniser could not finish")
                .isEmpty();
        assertThat(outcome.refusals().stream().filter(refusal -> refusal.rule() == Refused.UNRECOGNISED).toList())
                .as("a statement form the repository uses and the lint does not know; teach it, or the next one is refused")
                .isEmpty();

        // What P15-TSK-029 routed here: a real REVOKE in a real file, found once the floor is below it.
        assertThat(outcome.refusals())
                .filteredOn(refusal -> refusal.module().equals("platform") && refusal.file().startsWith("V012"))
                .extracting(Refusal::rule)
                .contains(Refused.REVOKE);
        assertThat(outcome.refusals()).extracting(Refusal::rule).contains(Refused.DROP, Refused.TRIGGER, Refused.CHECK);
    }

    @Test
    @DisplayName("a real migration replacing bodies a release holds is refused once the floor stands just below it: lending V035")
    void aRealReplacementIsRefusedAboveItsFloor() throws IOException {
        // With no floor nothing is released, so nothing can be a replacement: this needs a floor, as a real release has.
        SortedMap<String, SchemaVersion> floors = new TreeMap<>(head);
        floors.put("lending", SchemaVersion.parse("34"));

        assertThat(MigrationLint.check(root, new Floor(MigrationLint.NO_RELEASE, floors)).refusals())
                .filteredOn(refusal -> refusal.module().equals("lending") && refusal.file().startsWith("V035"))
                .extracting(Refusal::rule)
                .contains(Refused.REPLACED_BODY);
    }

    @Test
    @DisplayName("a real migration above a floor is read through the same path, and the guard's own is expand-only")
    void aRealMigrationAboveAFloorIsRead() throws IOException {
        Path guards;
        try (var migrations = Files.list(root.resolve("platform/src/main/resources/db/migration/platform"))) {
            guards = migrations.filter(file -> file.getFileName().toString().endsWith(THE_GUARDS_MIGRATION)).findFirst().orElseThrow();
        }
        String file = guards.getFileName().toString();
        SchemaVersion version = SchemaVersion.parse(file.substring(1, file.indexOf("__")));
        SortedMap<String, SchemaVersion> floors = new TreeMap<>(head);
        floors.put("platform", new SchemaVersion(List.of(version.parts().getFirst().subtract(java.math.BigInteger.ONE))));

        Outcome outcome = MigrationLint.check(root, new Floor(MigrationLint.NO_RELEASE, floors));
        assertThat(outcome.filesRead()).contains("platform/" + file);
        assertThat(outcome.refusals().stream().filter(refusal -> refusal.file().equals(file)).toList()).isEmpty();
    }

    /**
     * Migrations above the floor that tighten an older table or domain with neither {@code NOT VALID} nor a named data fix, each
     * with the reason it may stand (`P15-TSK-032`). They are applied, so never edited: this list is how one stands, and the test
     * holds it equal to what the rule refuses, so it can neither keep a stale entry nor grow without a version and a reason. Empty
     * when the rule was written: no migration above the floor tightened an old table.
     */
    private static final Map<String, String> GRANDFATHERED = Map.of(
            // P16-TSK-010: the audit archive's queue backfilled from the audit table's own primary key, a uuid NOT NULL and unique by
            // that key, into a new table whose only constraints are that key and a defaulted NOT NULL time: no old row can fail them,
            // and the copy changes no old row.
            "platform/V015__audit_archival.sql",
            "the archive's queue is filled from platform.audit_record's primary key, which already holds every constraint the queue judges");

    @Test
    @DisplayName("every tightening since the last release leaves the old rows to a later release or names its data fix, and nothing"
            + " stands but the grandfathered list (P15-TSK-032)")
    void everyTighteningSinceTheLastReleaseNamesItsDataFix() throws IOException {
        Outcome outcome = MigrationLint.tightenings(root, committed);
        assertThat(outcome.filesRead()).as("the migrations above the floor were read").isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(MigrationLint.check(root, committed).filesRead());
        assertThat(outcome.refusals().stream().map(refusal -> refusal.module() + "/" + refusal.file()).distinct().toList())
                .as("a tightening over old rows passes in production's data only by luck: NOT VALID now and VALIDATE in a later"
                        + " release, or a data fix of this release named in its marker (DATA_MIGRATIONS.md section 4.1); %s",
                        outcome.refusals())
                .containsExactlyInAnyOrderElementsOf(GRANDFATHERED.keySet());
    }

    @Test
    @DisplayName("a real tightening over old rows is refused once the floor stands just below it: accounting V012's CHECKs on V005's table")
    void aRealTighteningOverOldRowsIsRefusedAboveItsFloor() throws IOException {
        SortedMap<String, SchemaVersion> floors = new TreeMap<>(head);
        floors.put("accounting", SchemaVersion.parse("11"));

        Outcome outcome = MigrationLint.tightenings(root, new Floor(MigrationLint.NO_RELEASE, floors));
        assertThat(outcome.refusals())
                .filteredOn(refusal -> refusal.module().equals("accounting") && refusal.file().startsWith("V012"))
                .as("its two CHECKs, in one ALTER TABLE, each for its reason")
                .hasSize(2)
                .allSatisfy(refusal -> {
                    assertThat(refusal.rule()).isEqualTo(Refused.TIGHTENED_OVER_OLD_ROWS);
                    assertThat(refusal.statement()).startsWith("ALTER TABLE ACCOUNTING . VALUATION_RATE_SET ADD CONSTRAINT")
                            .contains("(a CHECK judges every row)").contains("its contract marker names no data fix");
                });
    }

    @Test
    @DisplayName("the marker's form is the one DATA_MIGRATIONS.md section 4.1 fixes for the promotion script")
    void theMarkerFormIsTheDocumentedOne() {
        assertThat(section("### 4.1")).contains("`" + MigrationLint.MARKER_FORM + "`");
    }

    @Test
    @DisplayName("every refused class is named in DATA_MIGRATIONS.md section 4.1")
    void everyRefusedClassIsDocumented() {
        String section = section("### 4.1");
        assertThat(java.util.Arrays.stream(Refused.values()).map(Enum::name).collect(Collectors.toList()))
                .allSatisfy(name -> assertThat(section).contains("`" + name + "`"));
    }

    private static String section(String heading) {
        String text = EvidenceCitations.read(root.resolve("docs/architecture/DATA_MIGRATIONS.md")).replace("\r\n", "\n");
        int start = text.indexOf(heading);
        assertThat(start).as(heading).isNotNegative();
        int end = text.indexOf("\n### ", start + heading.length());
        return text.substring(start, end < 0 ? text.length() : end);
    }
}
