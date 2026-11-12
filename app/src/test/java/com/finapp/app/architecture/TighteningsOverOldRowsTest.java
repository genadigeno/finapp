package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.MigrationLint.Floor;
import com.finapp.app.architecture.MigrationLint.Outcome;
import com.finapp.app.architecture.MigrationLint.Refusal;
import com.finapp.app.architecture.MigrationLint.Refused;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A tightening over old rows names its data fix, or leaves the old rows to a later release (`P15-TSK-032`; {@code THREAT_MODEL.md}
 * section 6 item 42 (j); {@code DATA_MIGRATIONS.md} section 4.1), on planted migrations.
 *
 * <p>Every plant is a real file in a planted repository, read by {@link MigrationLint#tightenings}, the one path the real tree takes
 * ({@code MigrationsAreExpandOnlyTest}): the floor sits at the module's first migration, which made the old table, and the plants
 * are the release above it.
 */
@Tag("architecture")
@DisplayName("a tightening over old rows names its data fix, or leaves the old rows to a later release (P15-TSK-032)")
class TighteningsOverOldRowsTest {

    private static final String MODULE = "ledger";

    /** What a release already holds: a table with rows somewhere, and a domain. */
    private static final String RELEASED = """
            CREATE TABLE ledger.account (id bigint PRIMARY KEY, code text, amount numeric(20,4), parent bigint);
            CREATE DOMAIN ledger.code AS text;
            """;

    private static final String MARKED = "-- finapp-contract: first-conforming-release=ANY; waives=CHECK; reason=reviewed\n";

    @TempDir Path root;

    private void write(String file, String sql) throws IOException {
        Path target = root.resolve(MODULE).resolve("src/main/resources/db/migration").resolve(MODULE).resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, sql, StandardCharsets.UTF_8);
    }

    private Outcome check(Floor floor) throws IOException {
        return MigrationLint.tightenings(root, floor);
    }

    /** The planted release, on a directory emptied first: {@code V001} at the floor, and each plant above it in order. */
    private Outcome release(String... plants) throws IOException {
        Path directory = root.resolve(MODULE).resolve("src/main/resources/db/migration").resolve(MODULE);
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        write("V001__released.sql", RELEASED);
        for (int index = 0; index < plants.length; index++) {
            write(String.format("V%03d__planted.sql", index + 2), plants[index]);
        }
        return check(MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "1")));
    }

    private static List<String> refusedFiles(Outcome outcome) {
        return outcome.refusals().stream().map(Refusal::file).toList();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0);",
                "alter table\n  ledger.account\n  add check (amount > 0);",
                "ALTER TABLE ONLY account ADD CONSTRAINT parent_fk FOREIGN KEY (parent) REFERENCES ledger.account (id);",
                "ALTER TABLE ledger.account ADD CONSTRAINT one_code UNIQUE (code);",
                "ALTER TABLE ledger.account ADD PRIMARY KEY (code);",
                "CREATE UNIQUE INDEX one_code ON ledger.account (code);",
                "CREATE UNIQUE INDEX IF NOT EXISTS one_code ON ONLY ledger.account (code);",
                "ALTER TABLE ledger.account ALTER COLUMN code SET NOT NULL;",
                "ALTER TABLE ledger.account ALTER code SET NOT NULL;",
                "ALTER TABLE ledger.account ADD COLUMN kind text NOT NULL;",
                "ALTER TABLE ledger.account ADD COLUMN kind text CHECK (kind <> 'x');",
                "ALTER TABLE ledger.account ADD COLUMN ref text UNIQUE;",
                "ALTER TABLE ledger.account ADD COLUMN owner bigint REFERENCES ledger.account (id);",
                "ALTER DOMAIN ledger.code ADD CONSTRAINT short CHECK (length(VALUE) < 9);",
                "ALTER DOMAIN ledger.code SET NOT NULL;",
                "CREATE DOMAIN ledger.positive AS int CHECK (VALUE > 0);\nALTER TABLE ledger.account ADD COLUMN p ledger.positive;",
                "ALTER TABLE ledger.account ADD COLUMN note text, ADD CHECK (amount > 0);"
            })
    @DisplayName("an unmarked tightening of a table or domain the last release holds is refused, in each form that judges old rows")
    void anUnmarkedTighteningOverOldRowsIsRefused(String plant) throws IOException {
        Outcome outcome = release(plant);
        assertThat(outcome.filesRead()).containsExactly("ledger/V002__planted.sql");
        assertThat(outcome.refusals()).as(plant).extracting(Refusal::rule).isNotEmpty().containsOnly(Refused.TIGHTENED_OVER_OLD_ROWS);
    }

    @Test
    @DisplayName("a marker that waives the class for release N - 1 is not a data fix: the tightening is still refused, for its rows")
    void aWaiverIsNotADataFix() throws IOException {
        assertThat(refusedFiles(release(MARKED + "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0);")))
                .containsExactly("V002__planted.sql");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;",
                "ALTER TABLE ledger.account ADD CONSTRAINT parent_fk FOREIGN KEY (parent) REFERENCES ledger.account (id) NOT VALID;",
                "ALTER DOMAIN ledger.code ADD CONSTRAINT short CHECK (length(VALUE) < 9) NOT VALID;",
                "ALTER TABLE ledger.account ADD COLUMN note text;",
                "ALTER TABLE ledger.account ADD COLUMN kind text NOT NULL DEFAULT 'x';",
                "ALTER TABLE ledger.account ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY NOT NULL;",
                "CREATE INDEX by_code ON ledger.account (code);",
                "ALTER TABLE ledger.account ALTER COLUMN code SET DEFAULT 'x';",
                "-- ALTER TABLE ledger.account ADD CHECK (amount > 0);\nSELECT 1;",
                "COMMENT ON TABLE ledger.account IS 'ALTER TABLE ledger.account ADD CHECK (amount > 0)';",
                "CREATE FUNCTION ledger.h() RETURNS void LANGUAGE sql AS $$ ALTER TABLE ledger.account ADD CHECK (amount > 0) $$;"
            })
    @DisplayName("NOT VALID, an additive change, or the words inside a comment, a string or a body, pass")
    void notValidAndAdditiveChangesPass(String plant) throws IOException {
        assertThat(release(plant).refusals()).as(plant).isEmpty();
    }

    @Test
    @DisplayName("a VALIDATE in a later release than the NOT VALID it validates passes; in the same release it is refused")
    void aValidationBelongsToALaterRelease() throws IOException {
        write("V001__released.sql", RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;\n");
        write("V002__validated.sql", "ALTER TABLE ledger.account VALIDATE CONSTRAINT positive;");
        assertThat(check(MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "1"))).refusals())
                .as("added NOT VALID by the last release, validated by this one")
                .isEmpty();

        assertThat(refusedFiles(release(
                        "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT positive;")))
                .as("added and validated in one release: the old rows judged in it after all")
                .containsExactly("V003__planted.sql");
        assertThat(refusedFiles(release("ALTER TABLE ledger.account VALIDATE CONSTRAINT nobody_added;")))
                .as("a validation of a constraint no migration of the module added NOT VALID")
                .containsExactly("V002__planted.sql");
    }

    @Test
    @DisplayName("SET NOT NULL passes once a CHECK (column IS NOT NULL) added NOT VALID has been validated by a later release")
    void setNotNullRestsOnAValidatedCheck() throws IOException {
        write("V001__released.sql",
                RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present CHECK (code IS NOT NULL) NOT VALID;\n");
        write("V002__validated.sql", "ALTER TABLE ledger.account VALIDATE CONSTRAINT code_present;");
        write("V003__not_null.sql", "ALTER TABLE ledger.account ALTER COLUMN code SET NOT NULL;");
        Floor floor = MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "1"));
        assertThat(check(floor).refusals()).as("the path the rule documents").isEmpty();

        write("V003__not_null.sql", "ALTER TABLE ledger.account ALTER COLUMN amount SET NOT NULL;");
        assertThat(check(floor).refusals()).as("the validated check is on another column").extracting(Refusal::file)
                .containsExactly("V003__not_null.sql");

        Files.delete(root.resolve(MODULE).resolve("src/main/resources/db/migration").resolve(MODULE).resolve("V002__validated.sql"));
        write("V003__not_null.sql", "ALTER TABLE ledger.account ALTER COLUMN code SET NOT NULL;");
        assertThat(check(floor).refusals()).as("the check added NOT VALID and never validated").extracting(Refusal::file)
                .containsExactly("V003__not_null.sql");
    }

    @Test
    @DisplayName("a marker naming a data fix of the same release, before it, that writes rows, passes")
    void aNamedDataFixPasses() throws IOException {
        assertThat(release(
                        "UPDATE ledger.account SET amount = 0 WHERE amount < 0;",
                        "-- finapp-contract: first-conforming-release=ANY; waives=CHECK; data-fix=V002; reason=V002 raises every negative"
                                + " amount to zero\nALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount >= 0);")
                .refusals())
                .isEmpty();
        assertThat(release(
                        "WITH fixed AS (UPDATE ledger.account SET code = 'c' || id WHERE code IS NULL RETURNING id) SELECT count(*) FROM fixed;",
                        "-- finapp-contract: first-conforming-release=ANY; waives=SET_NOT_NULL; data-fix=V2; reason=every code filled\n"
                                + "ALTER TABLE ledger.account ALTER COLUMN code SET NOT NULL;")
                .refusals())
                .as("a fix written as a CTE, named as V2 for V002")
                .isEmpty();
    }

    @Test
    @DisplayName("a marker naming a data fix that does not come before it, is not of this release, does not exist or writes no row,"
            + " is refused")
    void aDataFixThatDoesNotAnswerIsRefused() throws IOException {
        String check = "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount >= 0);";
        String fix = "UPDATE ledger.account SET amount = 0 WHERE amount < 0;";
        assertThat(release("-- finapp-contract: first-conforming-release=ANY; waives=CHECK; data-fix=V003; reason=x\n" + check, fix)
                        .refusals())
                .as("named after it").extracting(Refusal::statement).singleElement().asString().contains("does not come before it");
        assertThat(release("-- finapp-contract: first-conforming-release=ANY; waives=CHECK; data-fix=V002; reason=x\n" + check)
                        .refusals())
                .as("naming itself").extracting(Refusal::statement).singleElement().asString().contains("does not come before it");
        assertThat(release(fix, "-- finapp-contract: first-conforming-release=ANY; waives=CHECK; data-fix=V001; reason=x\n" + check)
                        .refusals())
                .as("the floor's own migration, of the last release").extracting(Refusal::statement).singleElement().asString()
                .contains("no migration of this release");
        assertThat(release(fix, "SELECT 1;",
                        "-- finapp-contract: first-conforming-release=ANY; waives=CHECK; data-fix=V003; reason=x\n" + check)
                        .refusals())
                .as("a migration of the release that writes no row").extracting(Refusal::statement).singleElement().asString()
                .contains("writes no row");
        assertThat(release(fix, "-- finapp-contract: first-conforming-release=ANY; waives=CHECK; data-fix=2; reason=x\n" + check)
                        .refusals())
                .as("a data fix not written V<version> is no marker at all").extracting(Refusal::statement).singleElement().asString()
                .contains("names no data fix");
    }

    @Test
    @DisplayName("a table or domain created by this release is new, in the same migration or an earlier one of the release, and"
            + " its tightening passes; one created only perhaps is not")
    void aNewTablesConstraintPasses() throws IOException {
        assertThat(release("CREATE TABLE ledger.t (a bigint);\nALTER TABLE ledger.t ADD CHECK (a > 0);").refusals()).isEmpty();
        assertThat(release(
                        "CREATE TABLE ledger.t (a bigint);",
                        "ALTER TABLE ledger.t ADD CONSTRAINT a_positive CHECK (a > 0);\nCREATE UNIQUE INDEX one_a ON ledger.t (a);\n"
                                + "ALTER TABLE ledger.t ALTER COLUMN a SET NOT NULL;")
                .refusals())
                .as("created by an earlier migration of the same release")
                .isEmpty();
        assertThat(release("CREATE DOMAIN ledger.fresh AS text;\nALTER DOMAIN ledger.fresh SET NOT NULL;").refusals()).isEmpty();
        assertThat(refusedFiles(release("CREATE TABLE IF NOT EXISTS ledger.account (id bigint);\nALTER TABLE ledger.account ADD CHECK (id > 0);")))
                .as("IF NOT EXISTS may have found the old table").containsExactly("V002__planted.sql");
        assertThat(refusedFiles(release("CREATE TEMP TABLE account (a bigint);\nALTER TABLE ledger.account ADD CHECK (amount > 0);")))
                .as("a temporary table does not excuse the old one").containsExactly("V002__planted.sql");
    }

    @Test
    @DisplayName("before any release is cut the floors still divide the files: a table at or below them is old; after a cut, the same")
    void theFloorDividesOldFromNewWhetherOrNotAReleaseIsCut() throws IOException {
        write("V001__released.sql", RELEASED);
        write("V002__planted.sql", "ALTER TABLE ledger.account ADD CHECK (amount > 0);");
        assertThat(check(MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "1"))).refusals()).hasSize(1);
        assertThat(check(MigrationLint.floor("1.4.0", Map.of(MODULE, "1"))).refusals()).hasSize(1);
        assertThat(check(MigrationLint.floor("1.4.0", Map.of(MODULE, "2"))).refusals())
                .as("at or below the floor nothing is judged: it is released").isEmpty();
        assertThat(check(MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of())).refusals())
                .as("with no floor every file is the release, so every table is new").isEmpty();
    }

    @Test
    @DisplayName("the marker form, extended by data-fix, still reads every marker written without it, for both rules")
    void theExtendedMarkerKeepsTheOldForm() throws IOException {
        String drop = "ALTER TABLE ledger.account DROP COLUMN code;";
        write("V001__released.sql", RELEASED);
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=x\n" + drop);
        Floor floor = MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "1"));
        assertThat(MigrationLint.check(root, floor).refusals()).as("the old form waives what it names").isEmpty();
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=ANY; waives=DROP; data-fix=V0001; reason=x\n" + drop);
        assertThat(MigrationLint.check(root, floor).refusals()).as("the new form too").isEmpty();
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=ANY; waives=TIGHTENED_OVER_OLD_ROWS; reason=x\n" + drop);
        assertThat(MigrationLint.check(root, floor).refusals()).extracting(Refusal::rule).contains(Refused.MALFORMED_MARKER);
    }
}
