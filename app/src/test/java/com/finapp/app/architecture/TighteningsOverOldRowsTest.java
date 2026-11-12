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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A tightening over old rows names its data fix, or leaves the old rows to a later release (`P15-TSK-032`; {@code THREAT_MODEL.md}
 * section 6 item 42 (j); {@code DATA_MIGRATIONS.md} section 4.1), on planted migrations.
 *
 * <p>Every plant is a real file in a planted repository, read by {@link MigrationLint#tightenings}, the one path the real tree takes
 * ({@code MigrationsAreExpandOnlyTest}): the floor sits at the module's first migration, which made the old table, and the plants
 * are the release above it. A refusal is asserted by its reason, so a plant refused for another reason than its own is red.
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

    private static final String NO_FIX = "its contract marker names no data fix";

    @TempDir Path root;

    private void write(String file, String sql) throws IOException {
        Path target = directory().resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, sql, StandardCharsets.UTF_8);
    }

    private Path directory() {
        return root.resolve(MODULE).resolve("src/main/resources/db/migration").resolve(MODULE);
    }

    private static Floor floor() {
        return MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "1"));
    }

    private Outcome check(Floor floor) throws IOException {
        return MigrationLint.tightenings(root, floor);
    }

    /** The planted release, on a directory emptied first: {@code V001} at the floor, and each plant above it in order. */
    private Outcome release(String... plants) throws IOException {
        return releasedThen(RELEASED, plants);
    }

    /** The same, with {@code V001} holding {@code released}. */
    private Outcome releasedThen(String released, String... plants) throws IOException {
        if (Files.isDirectory(directory())) {
            try (var files = Files.list(directory())) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        write("V001__released.sql", released);
        for (int index = 0; index < plants.length; index++) {
            write(String.format("V%03d__planted.sql", index + 2), plants[index]);
        }
        return check(floor());
    }

    private static List<String> refusedFiles(Outcome outcome) {
        return outcome.refusals().stream().map(Refusal::file).toList();
    }

    /** Exactly one refusal, of this rule's class, for {@code reason}, and the file named no fix. */
    private static void refusedFor(Outcome outcome, String plant, String reason) {
        assertThat(outcome.refusals()).as(plant).singleElement().satisfies(refusal -> {
            assertThat(refusal.rule()).isEqualTo(Refused.TIGHTENED_OVER_OLD_ROWS);
            assertThat(refusal.statement()).contains("(" + reason).contains(NO_FIX);
        });
    }

    static Stream<Arguments> tighteningsOfOldRows() {
        return Stream.of(
                Arguments.of("ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0);", "a CHECK judges every row"),
                Arguments.of("alter table\n  ledger.account\n  add check (amount > 0);", "a CHECK judges every row"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN note text, ADD CHECK (amount > 0);", "a CHECK judges every row"),
                Arguments.of("ALTER TABLE ONLY account ADD CONSTRAINT parent_fk FOREIGN KEY (parent) REFERENCES ledger.account (id);",
                        "a foreign key judges every row"),
                Arguments.of("ALTER TABLE ledger.account ADD CONSTRAINT one_code UNIQUE (code);", "a unique constraint judges every row"),
                Arguments.of("ALTER TABLE ledger.account ADD PRIMARY KEY (code);", "a primary key judges every row"),
                Arguments.of("ALTER TABLE ledger.account ADD CONSTRAINT no_overlap EXCLUDE USING gist (code WITH =);",
                        "an exclusion constraint judges every row"),
                Arguments.of("CREATE UNIQUE INDEX one_code ON ledger.account (code);", "a unique index judges every row"),
                Arguments.of("CREATE UNIQUE INDEX IF NOT EXISTS one_code ON ONLY ledger.account (code);", "a unique index judges every row"),
                Arguments.of("ALTER TABLE ledger.account ALTER COLUMN code SET NOT NULL;", "SET NOT NULL judges every row"),
                Arguments.of("ALTER TABLE ledger.account ALTER code SET NOT NULL;", "SET NOT NULL judges every row"),
                Arguments.of("ALTER TABLE ledger.account ADD CONSTRAINT code_present NOT NULL code;", "a NOT NULL constraint judges every row"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN kind text NOT NULL;", "a column added NOT NULL with no default"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN kind text CHECK (kind <> 'x');", "a column added with a CHECK"),
                Arguments.of("CREATE DOMAIN ledger.positive AS int CHECK (VALUE > 0);\nALTER TABLE ledger.account ADD COLUMN p ledger.positive;",
                        "a column added with a CHECK, or typed by a domain with one"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN k bigint PRIMARY KEY;", "a column added PRIMARY KEY"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN ref text UNIQUE NULLS NOT DISTINCT;", "a column added UNIQUE NULLS NOT DISTINCT"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN ref text DEFAULT 'r' UNIQUE;", "a column added UNIQUE gives every old row"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN n bigserial UNIQUE;", "a column added UNIQUE gives every old row"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN owner bigint DEFAULT 1 REFERENCES ledger.account (id);",
                        "a column added REFERENCES gives every old row"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN owner bigint GENERATED BY DEFAULT AS IDENTITY REFERENCES ledger.account (id);",
                        "a column added REFERENCES gives every old row"),
                Arguments.of("ALTER TABLE ledger.account ADD COLUMN twice numeric(20,4) GENERATED ALWAYS AS (amount * 2) STORED;",
                        "a stored generated column is computed for every row"),
                Arguments.of("ALTER TABLE ledger.account ALTER COLUMN amount TYPE numeric(20,2) USING round(amount, 2);",
                        "a type change USING an expression"),
                Arguments.of("CREATE DOMAIN ledger.positive AS numeric(20,4) CHECK (VALUE > 0);\n"
                        + "ALTER TABLE ledger.account ALTER COLUMN amount SET DATA TYPE ledger.positive;", "a type change to a domain with a constraint"),
                Arguments.of("ALTER DOMAIN ledger.code ADD CONSTRAINT short CHECK (length(VALUE) < 9);", "a domain's CHECK judges every column"),
                Arguments.of("ALTER DOMAIN ledger.code SET NOT NULL;", "a domain's SET NOT NULL"),
                Arguments.of("ALTER DOMAIN ledger.code VALIDATE CONSTRAINT nobody_added;", "it validates a constraint no migration of this module added"),
                // A table created only perhaps is old: a partition of an old one, one made where a moved search_path points.
                Arguments.of("CREATE TABLE ledger.account_low PARTITION OF ledger.account FOR VALUES FROM (0) TO (100);\n"
                        + "ALTER TABLE ledger.account_low ADD CHECK (amount > 0);", "a CHECK judges every row"),
                Arguments.of("SET search_path TO elsewhere;\nCREATE TABLE t (a bigint);\nALTER TABLE t ADD CHECK (a > 0);", "a CHECK judges every row"),
                // A domain of this release is old once an old table's column takes it.
                Arguments.of("CREATE DOMAIN ledger.fresh AS text;\nALTER TABLE ledger.account ADD COLUMN f ledger.fresh;\n"
                        + "ALTER DOMAIN ledger.fresh SET NOT NULL;", "a domain's SET NOT NULL"),
                // Copies of old rows: into a new table already constrained, and a new table made old by one.
                Arguments.of("CREATE TABLE ledger.moved (id bigint PRIMARY KEY, amount numeric(20,4) CHECK (amount > 0));\n"
                        + "INSERT INTO ledger.moved SELECT id, amount FROM ledger.account;", "a copy of old rows into moved, whose constraints judge them"),
                Arguments.of("CREATE TABLE ledger.moved (LIKE ledger.account);\nINSERT INTO ledger.moved TABLE ledger.account;",
                        "a copy of old rows into moved"),
                Arguments.of("CREATE TABLE ledger.moved (id bigint PRIMARY KEY);\n"
                        + "WITH source AS (SELECT id FROM ledger.account) INSERT INTO ledger.moved SELECT id FROM source;", "a copy of old rows into moved"),
                Arguments.of("CREATE TABLE ledger.moved (id bigint PRIMARY KEY, code text);\n"
                        + "UPDATE ledger.moved m SET code = a.code FROM ledger.account a WHERE a.id = m.id;", "a copy of old rows into moved"),
                Arguments.of("ALTER TABLE ledger.account RENAME TO account_old;\n"
                        + "CREATE TABLE ledger.account (id bigint PRIMARY KEY, code text NOT NULL, amount numeric(20,4), parent bigint);\n"
                        + "INSERT INTO ledger.account SELECT * FROM ledger.account_old;", "a copy of old rows into account, whose constraints judge them"),
                Arguments.of("CREATE TABLE ledger.moved (id bigint, amount numeric(20,4));\nINSERT INTO ledger.moved SELECT id, amount FROM ledger.account;\n"
                        + "ALTER TABLE ledger.moved ADD CHECK (amount > 0);", "a CHECK judges every row"),
                Arguments.of("CREATE TABLE ledger.moved AS SELECT * FROM ledger.account;\nALTER TABLE ledger.moved ADD PRIMARY KEY (id);",
                        "a primary key judges every row"),
                Arguments.of("SELECT * INTO ledger.moved FROM ledger.account;\nALTER TABLE ledger.moved ALTER COLUMN code SET NOT NULL;",
                        "SET NOT NULL judges every row"),
                Arguments.of("CREATE TABLE ledger.moved (a numeric(20,4));\nINSERT INTO ledger.moved SELECT amount FROM ledger.account;\n"
                        + "ALTER TABLE ledger.moved RENAME TO kept;\nALTER TABLE ledger.kept ADD CHECK (a > 0);", "a CHECK judges every row"),
                // What the expand-only reading cannot vouch for, on an old object or on one it cannot read.
                Arguments.of("DO $$ BEGIN UPDATE ledger.account SET amount = 0; END $$;", "a DO block: its body is not read"),
                Arguments.of("CREATE FUNCTION ledger.fix() RETURNS void LANGUAGE sql AS $$ UPDATE ledger.account SET amount = 0 $$;\n"
                        + "SELECT ledger.fix();", "a call of a function this module defined"),
                Arguments.of("CREATE TABLE ledger.parted (id bigint, code text, amount numeric(20,4), parent bigint) PARTITION BY RANGE (id);\n"
                        + "ALTER TABLE ledger.parted ATTACH PARTITION ledger.account FOR VALUES FROM (0) TO (100);",
                        "a form the expand-only lint does not read, on table account, which is old"),
                Arguments.of("ALTER TABLE ledger.account ALTER COLUMN amount SET EXPRESSION AS (0);",
                        "a form the expand-only lint does not read, on table account, which is old"),
                Arguments.of("ALTER TABLE ledger.account ALTER CONSTRAINT parent_fk ENFORCED;",
                        "ALTER CONSTRAINT can make a constraint judge every row of table account"),
                Arguments.of("CALL ledger.fix();", "a form the expand-only lint does not read, on an object it cannot read, which counts as old"));
    }

    @ParameterizedTest
    @MethodSource("tighteningsOfOldRows")
    @DisplayName("an unmarked tightening of a table or domain the last release holds is refused, for its reason, in each form that"
            + " judges old rows")
    void anUnmarkedTighteningOverOldRowsIsRefused(String plant, String reason) throws IOException {
        Outcome outcome = release(plant);
        assertThat(outcome.filesRead()).containsExactly("ledger/V002__planted.sql");
        refusedFor(outcome, plant, reason);
    }

    @Test
    @DisplayName("a marker that waives a class for release N - 1 is not a data fix: the tightening is still refused, for its rows")
    void aWaiverIsNotADataFix() throws IOException {
        assertThat(refusedFiles(release(MARKED + "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0);")))
                .containsExactly("V002__planted.sql");
        String block = "DO $$ BEGIN UPDATE ledger.account SET amount = 0; END $$;";
        assertThat(refusedFiles(release("-- finapp-contract: first-conforming-release=ANY; waives=OPAQUE_BLOCK; reason=reviewed\n" + block)))
                .as("an opaque block waived for release N - 1").containsExactly("V002__planted.sql");
        assertThat(release("UPDATE ledger.account SET amount = 0 WHERE amount < 0;",
                        "-- finapp-contract: first-conforming-release=ANY; waives=OPAQUE_BLOCK; data-fix=V002; reason=V002 fixes them\n" + block)
                .refusals())
                .as("and with its data fix named").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;",
                "ALTER TABLE ledger.account ADD CONSTRAINT parent_fk FOREIGN KEY (parent) REFERENCES ledger.account (id) NOT VALID;",
                "ALTER TABLE ledger.account ADD CONSTRAINT code_present NOT NULL code NOT VALID;",
                "ALTER DOMAIN ledger.code ADD CONSTRAINT short CHECK (length(VALUE) < 9) NOT VALID;",
                "ALTER TABLE ledger.account ADD COLUMN note text;",
                "ALTER TABLE ledger.account ADD COLUMN kind text NOT NULL DEFAULT 'x';",
                "ALTER TABLE ledger.account ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY NOT NULL;",
                "ALTER TABLE ledger.account ADD COLUMN ref text UNIQUE;",
                "ALTER TABLE ledger.account ADD COLUMN owner bigint REFERENCES ledger.account (id);",
                "ALTER TABLE ledger.account ADD COLUMN half numeric(20,4) GENERATED ALWAYS AS (amount / 2) VIRTUAL;",
                "CREATE INDEX by_code ON ledger.account (code);",
                "ALTER TABLE ledger.account ALTER COLUMN code SET DEFAULT 'x';",
                "-- ALTER TABLE ledger.account ADD CHECK (amount > 0);\nSELECT 1;",
                "COMMENT ON TABLE ledger.account IS 'ALTER TABLE ledger.account ADD CHECK (amount > 0)';",
                "CREATE FUNCTION ledger.h() RETURNS void LANGUAGE sql AS $$ ALTER TABLE ledger.account ADD CHECK (amount > 0) $$;",
                "CREATE TABLE ledger.source (a bigint);\nCREATE TABLE ledger.moved (a bigint CHECK (a > 0));\nINSERT INTO ledger.moved SELECT a FROM ledger.source;",
                "CREATE TABLE ledger.moved (a bigint PRIMARY KEY);\nINSERT INTO ledger.moved (a) VALUES (1), (2);",
                "CREATE TABLE ledger.moved (a bigint PRIMARY KEY);\nINSERT INTO ledger.moved SELECT g FROM generate_series(1, 3) g;",
                "CREATE TABLE ledger.moved (a bigint, b text);\nINSERT INTO ledger.moved SELECT id, code FROM ledger.account;",
                "CREATE TABLE ledger.moved AS TABLE ledger.account WITH NO DATA;\nALTER TABLE ledger.moved ADD PRIMARY KEY (id);",
                "CREATE TABLE ledger.parted (a bigint) PARTITION BY RANGE (a);\nCREATE TABLE ledger.part (a bigint);\n"
                        + "ALTER TABLE ledger.parted ATTACH PARTITION ledger.part FOR VALUES FROM (0) TO (9);",
                "CREATE TABLE ledger.moved (a bigint, b bigint GENERATED ALWAYS AS (a) STORED);\n"
                        + "ALTER TABLE ledger.moved ALTER COLUMN b SET EXPRESSION AS (a * 2);"
            })
    @DisplayName("NOT VALID, an additive change, a copy into a new table that judges nothing or of new rows only, a change of a new"
            + " table, or the words inside a comment, a string or a body, pass")
    void notValidAndAdditiveChangesPass(String plant) throws IOException {
        assertThat(release(plant).refusals()).as(plant).isEmpty();
    }

    @Test
    @DisplayName("a VALIDATE in a later release than the NOT VALID it validates passes; in the same release it is refused")
    void aValidationBelongsToALaterRelease() throws IOException {
        assertThat(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;\n",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT positive;")
                .refusals())
                .as("added NOT VALID by the last release, validated by this one")
                .isEmpty();
        assertThat(releasedThen(RELEASED + "ALTER DOMAIN ledger.code ADD CONSTRAINT short CHECK (length(VALUE) < 9) NOT VALID;\n",
                        "ALTER DOMAIN ledger.code VALIDATE CONSTRAINT short;")
                .refusals())
                .as("a domain's, the same")
                .isEmpty();

        refusedFor(release("ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT positive;"),
                "added and validated in one release: the old rows judged in it after all",
                "it validates in the same release the constraint was added NOT VALID");
        refusedFor(release("ALTER DOMAIN ledger.code ADD CONSTRAINT short CHECK (length(VALUE) < 9) NOT VALID;",
                        "ALTER DOMAIN ledger.code VALIDATE CONSTRAINT short;"),
                "a domain's, in one release", "it validates in the same release the constraint was added NOT VALID");
        refusedFor(release("ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT other;"),
                "another constraint's name", "it validates a constraint no migration of this module added NOT VALID");
        refusedFor(release("ALTER TABLE ledger.account ADD CHECK (amount > 0) NOT VALID;",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT account_amount_check;"),
                "a NOT VALID constraint must be named to be validated", "it validates a constraint no migration of this module added NOT VALID");
    }

    @Test
    @DisplayName("SET NOT NULL passes once a valid CHECK (column IS NOT NULL), or a validated not-null constraint, proves the column")
    void setNotNullRestsOnAValidCheck() throws IOException {
        String setNotNull = "ALTER TABLE ledger.account ALTER COLUMN code SET NOT NULL;";
        assertThat(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present CHECK (code IS NOT NULL) NOT VALID;\n",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT code_present;", setNotNull)
                .refusals())
                .as("added NOT VALID by the last release, validated by this one").isEmpty();
        assertThat(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present CHECK (code IS NOT NULL);\n", setNotNull)
                .refusals())
                .as("created valid by the last release").isEmpty();
        assertThat(releasedThen("CREATE TABLE ledger.account (id bigint PRIMARY KEY, code text CHECK (code IS NOT NULL), amount numeric(20,4));\n",
                        setNotNull)
                .refusals())
                .as("created valid with the table").isEmpty();
        assertThat(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present NOT NULL code NOT VALID;\n",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT code_present;", setNotNull)
                .refusals())
                .as("PostgreSQL 18's not-null constraint, added NOT VALID by the last release and validated by this one").isEmpty();

        refusedFor(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present CHECK (code IS NOT NULL) NOT VALID;\n",
                        "ALTER TABLE ledger.account VALIDATE CONSTRAINT code_present;",
                        "ALTER TABLE ledger.account ALTER COLUMN amount SET NOT NULL;"),
                "the valid check is on another column", "SET NOT NULL judges every row");
        refusedFor(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present CHECK (code IS NOT NULL) NOT VALID;\n",
                        setNotNull),
                "the check added NOT VALID and never validated", "SET NOT NULL judges every row");
        refusedFor(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present CHECK (code IS NOT NULL) NOT ENFORCED;\n",
                        setNotNull),
                "a check not enforced proves nothing", "SET NOT NULL judges every row");
        assertThat(releasedThen(RELEASED + "ALTER TABLE ledger.account ADD CONSTRAINT code_present CHECK (code IS NOT NULL);\n",
                        "-- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=reviewed\n"
                                + "ALTER TABLE ledger.account DROP CONSTRAINT code_present;",
                        setNotNull)
                .refusals())
                .as("a check dropped proves nothing").extracting(Refusal::file).containsExactly("V003__planted.sql");
    }

    @Test
    @DisplayName("a UNIQUE constraint on a unique index an earlier release built judges no row anew; on one this release built, or as a"
            + " primary key, it does")
    void aUniqueConstraintOnAnIndexBuiltBeforePasses() throws IOException {
        String index = "CREATE UNIQUE INDEX one_code ON ledger.account (code);\n";
        assertThat(releasedThen(RELEASED + index, "ALTER TABLE ledger.account ADD CONSTRAINT one_code UNIQUE USING INDEX one_code;")
                .refusals())
                .isEmpty();
        assertThat(refusedFiles(release(index, "ALTER TABLE ledger.account ADD CONSTRAINT one_code UNIQUE USING INDEX one_code;")))
                .as("the index built by this release").containsExactly("V002__planted.sql", "V003__planted.sql");
        refusedFor(release("ALTER TABLE ledger.account ADD CONSTRAINT one_code UNIQUE USING INDEX nobody_built;"),
                "an index nobody built", "a unique constraint judges every row");
        refusedFor(releasedThen(RELEASED + index, "ALTER TABLE ledger.account ADD CONSTRAINT account_code_pk PRIMARY KEY USING INDEX one_code;"),
                "a primary key makes its columns NOT NULL", "a primary key judges every row");
    }

    @Test
    @DisplayName("a domain's constraint is known however it came: created with it, added later, or SET NOT NULL")
    void aDomainsConstraintIsKnownHoweverItCame() throws IOException {
        refusedFor(releasedThen(RELEASED + "ALTER DOMAIN ledger.code ADD CONSTRAINT short CHECK (length(VALUE) < 9);\n",
                        "ALTER TABLE ledger.account ADD COLUMN alias ledger.code;"),
                "a check added to a released domain", "a column added with a CHECK, or typed by a domain with one");
        refusedFor(releasedThen(RELEASED + "ALTER DOMAIN ledger.code SET NOT NULL;\n", "ALTER TABLE ledger.account ADD COLUMN alias ledger.code;"),
                "a released domain made NOT NULL", "a column added NOT NULL with no default");
        refusedFor(releasedThen(RELEASED + "ALTER DOMAIN ledger.code ADD CONSTRAINT present NOT NULL;\n",
                        "ALTER TABLE ledger.account ADD COLUMN alias ledger.code;"),
                "a NOT NULL constraint added to a released domain", "a column added NOT NULL with no default");
        assertThat(release("CREATE DOMAIN ledger.fresh AS text;\nALTER DOMAIN ledger.fresh SET NOT NULL;").refusals())
                .as("a domain of this release no old table takes").isEmpty();
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
    @DisplayName("a marker may name its data fix alone, waiving nothing for release N - 1; one naming neither is malformed")
    void aMarkerMayNameOnlyItsDataFix() throws IOException {
        Outcome outcome = release(
                "UPDATE ledger.account SET amount = 1 WHERE amount <= 0;",
                "-- finapp-contract: first-conforming-release=ANY; data-fix=V002; reason=V002 leaves no amount at or below zero\n"
                        + "CREATE TABLE ledger.moved (id bigint PRIMARY KEY, amount numeric(20,4) CHECK (amount > 0));\n"
                        + "INSERT INTO ledger.moved SELECT id, amount FROM ledger.account;");
        assertThat(outcome.refusals()).as("the tightening answered").isEmpty();
        assertThat(MigrationLint.check(root, floor()).refusals()).as("and the expand-only lint reads the marker, which waives nothing")
                .isEmpty();
        write("V003__planted.sql", "-- finapp-contract: first-conforming-release=ANY; reason=x\nSELECT 1;");
        assertThat(MigrationLint.check(root, floor()).refusals()).extracting(Refusal::rule).containsExactly(Refused.MALFORMED_MARKER);
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
        refusedFor(release("CREATE TABLE IF NOT EXISTS ledger.account (id bigint);\nALTER TABLE ledger.account ADD CHECK (id > 0);"),
                "IF NOT EXISTS may have found the old table", "a CHECK judges every row");
        refusedFor(release("CREATE TEMP TABLE account (a bigint);\nALTER TABLE ledger.account ADD CHECK (amount > 0);"),
                "a temporary table does not excuse the old one", "a CHECK judges every row");
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
        Floor floor = floor();
        assertThat(MigrationLint.check(root, floor).refusals()).as("the old form waives what it names").isEmpty();
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=ANY; waives=DROP; data-fix=V0001; reason=x\n" + drop);
        assertThat(MigrationLint.check(root, floor).refusals()).as("the new form too").isEmpty();
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=ANY; waives=TIGHTENED_OVER_OLD_ROWS; reason=x\n" + drop);
        assertThat(MigrationLint.check(root, floor).refusals()).extracting(Refusal::rule).contains(Refused.MALFORMED_MARKER);
    }
}
