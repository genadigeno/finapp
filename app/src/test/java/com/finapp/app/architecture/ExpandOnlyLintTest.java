package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The expand-only lint's rules, on planted migrations (`P15-TSK-005`).
 *
 * <p>Every plant is a real file in a planted repository, read by {@link MigrationLint#check}, the one path the real tree
 * takes: the floor sits at the module's first migration and the plant is its second. {@code MigrationsAreExpandOnlyTest}
 * runs the same path over the real files.
 */
@Tag("architecture")
@DisplayName("the expand-only lint refuses what can break release N - 1, and nothing else (P15-TSK-005)")
class ExpandOnlyLintTest {

    private static final String MODULE = "ledger";

    /** What a release already holds: a table, a function and a view, for a plant to change. */
    private static final String RELEASED = """
            CREATE TABLE ledger.account (id bigint PRIMARY KEY, code text, amount numeric(20,4));
            CREATE FUNCTION ledger.refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$;
            CREATE VIEW ledger.account_codes AS SELECT code FROM ledger.account;
            """;

    private static final String MARKER = "-- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=no release writes this column\n";

    @TempDir Path root;

    private Floor atTheFirstMigration() {
        return MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "1"));
    }

    private void write(String file, String sql) throws IOException {
        Path target = root.resolve(MODULE).resolve("src/main/resources/db/migration").resolve(MODULE).resolve(file);
        Files.createDirectories(target.getParent());
        Files.writeString(target, sql, StandardCharsets.UTF_8);
    }

    private Outcome lint(String plant) throws IOException {
        write("V001__released.sql", RELEASED);
        write("V002__planted.sql", plant);
        return MigrationLint.check(root, atTheFirstMigration());
    }

    private List<Refused> rulesFor(String plant) throws IOException {
        return lint(plant).refusals().stream().map(Refusal::rule).toList();
    }

    static Stream<Arguments> everyRefusedClass() {
        return Stream.of(
                Arguments.of("DROP COLUMN", "ALTER TABLE ledger.account DROP COLUMN code;", Refused.DROP),
                Arguments.of("in lower case, over several lines", "alter table\n  ledger.account\n  drop\n  column code;", Refused.DROP),
                Arguments.of("DROP TABLE", "DROP TABLE ledger.account;", Refused.DROP),
                Arguments.of("DROP CONSTRAINT", "ALTER TABLE ledger.account DROP CONSTRAINT account_pkey;", Refused.DROP),
                Arguments.of("DROP DEFAULT", "ALTER TABLE ledger.account ALTER COLUMN code DROP DEFAULT;", Refused.DROP),
                Arguments.of("DROP NOT NULL", "ALTER TABLE ledger.account ALTER COLUMN code DROP NOT NULL;", Refused.DROP),
                Arguments.of("DROP TRIGGER", "DROP TRIGGER IF EXISTS t ON ledger.account;", Refused.DROP),
                Arguments.of("RENAME COLUMN", "ALTER TABLE ledger.account RENAME COLUMN code TO label;", Refused.RENAME),
                Arguments.of("RENAME TABLE", "ALTER TABLE ledger.account RENAME TO accounts;", Refused.RENAME),
                Arguments.of("RENAME an index", "ALTER INDEX ledger.account_pkey RENAME TO a_pk;", Refused.RENAME),
                Arguments.of("SET SCHEMA", "ALTER TABLE ledger.account SET SCHEMA archive;", Refused.RENAME),
                Arguments.of("TYPE", "ALTER TABLE ledger.account ALTER COLUMN amount TYPE numeric(30,8);", Refused.TYPE_CHANGE),
                Arguments.of("SET DATA TYPE", "ALTER TABLE ledger.account ALTER amount SET DATA TYPE numeric(30,8);", Refused.TYPE_CHANGE),
                Arguments.of("TRUNCATE", "TRUNCATE ledger.account;", Refused.TRUNCATE),
                Arguments.of("ADD CHECK", "ALTER TABLE ledger.account ADD CHECK (amount > 0);", Refused.CHECK),
                Arguments.of("ADD CONSTRAINT CHECK", "ALTER TABLE ledger.account ADD CONSTRAINT positive CHECK (amount > 0) NOT VALID;", Refused.CHECK),
                Arguments.of("inline CHECK", "ALTER TABLE ledger.account ADD COLUMN kind text CHECK (kind <> 'x');", Refused.CHECK),
                Arguments.of("a trigger", "CREATE TRIGGER t BEFORE UPDATE ON ledger.account FOR EACH ROW EXECUTE FUNCTION ledger.refuse_change();", Refused.TRIGGER),
                Arguments.of("a constraint trigger", "CREATE CONSTRAINT TRIGGER t AFTER INSERT ON ledger.account DEFERRABLE FOR EACH ROW EXECUTE FUNCTION ledger.refuse_change();", Refused.TRIGGER),
                Arguments.of("a trigger on insert or truncate", "CREATE TRIGGER t BEFORE INSERT OR TRUNCATE ON ledger.account EXECUTE FUNCTION ledger.refuse_change();", Refused.TRIGGER),
                Arguments.of("ENABLE TRIGGER", "ALTER TABLE ledger.account ENABLE TRIGGER t;", Refused.TRIGGER),
                Arguments.of("DISABLE TRIGGER", "ALTER TABLE ledger.account DISABLE TRIGGER t;", Refused.TRIGGER),
                Arguments.of("ALTER CONSTRAINT", "ALTER TABLE ledger.account ALTER CONSTRAINT c DEFERRABLE INITIALLY DEFERRED;", Refused.ALTERED_CONSTRAINT),
                Arguments.of("UNIQUE INDEX", "CREATE UNIQUE INDEX one_code ON ledger.account (code);", Refused.UNIQUE),
                Arguments.of("UNIQUE INDEX IF NOT EXISTS", "CREATE UNIQUE INDEX IF NOT EXISTS one_code ON ONLY ledger.account (code);", Refused.UNIQUE),
                Arguments.of("ADD UNIQUE", "ALTER TABLE ledger.account ADD CONSTRAINT one_code UNIQUE (code);", Refused.UNIQUE),
                Arguments.of("inline UNIQUE", "ALTER TABLE ledger.account ADD COLUMN ref text UNIQUE;", Refused.UNIQUE),
                Arguments.of("ADD FOREIGN KEY", "ALTER TABLE ledger.account ADD FOREIGN KEY (id) REFERENCES ledger.other (id);", Refused.FOREIGN_KEY),
                Arguments.of("inline REFERENCES", "ALTER TABLE ledger.account ADD COLUMN parent bigint REFERENCES ledger.account (id);", Refused.FOREIGN_KEY),
                Arguments.of("SET NOT NULL", "ALTER TABLE ledger.account ALTER COLUMN code SET NOT NULL;", Refused.SET_NOT_NULL),
                Arguments.of("ADD COLUMN NOT NULL", "ALTER TABLE ledger.account ADD COLUMN kind text NOT NULL;", Refused.NOT_NULL_WITHOUT_DEFAULT),
                Arguments.of("NOT NULL DEFAULT NULL", "ALTER TABLE ledger.account ADD COLUMN kind text DEFAULT NULL NOT NULL;", Refused.NOT_NULL_WITHOUT_DEFAULT),
                Arguments.of("one action of several", "ALTER TABLE ledger.account ADD COLUMN a int, DROP COLUMN code;", Refused.DROP),
                Arguments.of("REVOKE on a table", "REVOKE DELETE ON ledger.account FROM finapp_app;", Refused.REVOKE),
                Arguments.of("REVOKE on TABLE", "REVOKE UPDATE ON TABLE ledger.account FROM finapp_app;", Refused.REVOKE),
                Arguments.of("REVOKE on a function", "REVOKE EXECUTE ON FUNCTION ledger.refuse_change() FROM finapp_app;", Refused.REVOKE),
                Arguments.of("REVOKE on a whole schema", "REVOKE ALL ON ALL TABLES IN SCHEMA ledger FROM finapp_app;", Refused.REVOKE),
                Arguments.of("REVOKE a role", "REVOKE finapp_reader FROM finapp_app;", Refused.REVOKE),
                Arguments.of("row security", "ALTER TABLE ledger.account ENABLE ROW LEVEL SECURITY;", Refused.ROW_SECURITY),
                Arguments.of("a policy", "CREATE POLICY p ON ledger.account USING (true);", Refused.ROW_SECURITY),
                Arguments.of("a rule", "CREATE RULE r AS ON UPDATE TO ledger.account DO INSTEAD NOTHING;", Refused.RULE),
                Arguments.of("a replaced function", "CREATE OR REPLACE FUNCTION ledger.refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'no'; END; $$;", Refused.REPLACED_BODY),
                Arguments.of("a replaced view", "CREATE OR REPLACE VIEW ledger.account_codes AS SELECT code FROM ledger.account;", Refused.REPLACED_BODY),
                Arguments.of("a DO block", "DO $$ BEGIN ALTER TABLE ledger.account DROP COLUMN code; END $$;", Refused.OPAQUE_BLOCK),
                Arguments.of("a function created and called", "CREATE FUNCTION ledger.h() RETURNS void LANGUAGE sql AS 'SELECT 1';\nSELECT ledger.h();", Refused.OPAQUE_BLOCK),
                Arguments.of("a released function called", "SELECT ledger.refuse_change();", Refused.OPAQUE_BLOCK),
                Arguments.of("SET GENERATED", "ALTER TABLE ledger.account ALTER COLUMN id SET GENERATED ALWAYS;", Refused.SET_GENERATED),
                Arguments.of("SET DEFAULT NULL", "ALTER TABLE ledger.account ALTER COLUMN code SET DEFAULT NULL;", Refused.DROP),
                Arguments.of("SET DEFAULT (NULL)", "ALTER TABLE ledger.account ALTER COLUMN code SET DEFAULT (NULL);", Refused.DROP),
                Arguments.of("DELETE", "DELETE FROM ledger.account;", Refused.DELETE),
                Arguments.of("DELETE in a CTE", "WITH gone AS (DELETE FROM ledger.account RETURNING id) SELECT count(*) FROM gone;", Refused.DELETE),
                Arguments.of("a column named serial, not null", "ALTER TABLE ledger.account ADD COLUMN serial text NOT NULL;", Refused.NOT_NULL_WITHOUT_DEFAULT),
                Arguments.of("a column named generated, not null", "ALTER TABLE ledger.account ADD COLUMN generated boolean NOT NULL;", Refused.NOT_NULL_WITHOUT_DEFAULT),
                Arguments.of("NOT NULL DEFAULT (NULL)", "ALTER TABLE ledger.account ADD COLUMN kind text NOT NULL DEFAULT (NULL);", Refused.NOT_NULL_WITHOUT_DEFAULT),
                Arguments.of("DEFAULT CAST(NULL)", "ALTER TABLE ledger.account ADD COLUMN kind text DEFAULT CAST(NULL AS text) NOT NULL;", Refused.NOT_NULL_WITHOUT_DEFAULT),
                Arguments.of("a partition with a CHECK of its own", "CREATE TABLE ledger.p PARTITION OF ledger.account (CHECK (id > 0)) FOR VALUES IN (1);", Refused.CHECK),
                Arguments.of("a NOT NULL domain on a new column", "CREATE DOMAIN ledger.d AS int NOT NULL;\nALTER TABLE ledger.account ADD COLUMN x ledger.d;", Refused.NOT_NULL_WITHOUT_DEFAULT),
                Arguments.of("a CHECK domain on a new column", "CREATE DOMAIN ledger.d AS int CHECK (VALUE > 0);\nALTER TABLE ledger.account ADD COLUMN x ledger.d;", Refused.CHECK),
                Arguments.of("another schema's function replaced", "CREATE OR REPLACE FUNCTION platform.f() RETURNS void LANGUAGE sql AS 'SELECT 1';", Refused.REPLACED_BODY),
                Arguments.of("a form the lint does not know", "CLUSTER ledger.account;", Refused.UNRECOGNISED),
                Arguments.of("ALTER DEFAULT PRIVILEGES", "ALTER DEFAULT PRIVILEGES IN SCHEMA ledger GRANT SELECT ON TABLES TO finapp_app;", Refused.UNRECOGNISED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRefusedClass")
    @DisplayName("every class is refused, unmarked, on a table a release holds")
    void everyRefusedClassIsRefused(String name, String plant, Refused expected) throws IOException {
        assertThat(rulesFor(plant)).as(plant).contains(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyRefusedClass")
    @DisplayName("and every one of them passes once the file carries a contract marker")
    void aMarkedContractIsAccepted(String name, String plant, Refused expected) throws IOException {
        assertThat(lint("-- finapp-contract: first-conforming-release=ANY; waives=" + expected + "; reason=reviewed\n" + plant).refusals()).as(plant).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ALTER TABLE t ADD CHECK (a > 0);",
                "ALTER TABLE t ADD CONSTRAINT one UNIQUE (a);",
                "ALTER TABLE t ADD FOREIGN KEY (a) REFERENCES ledger.account (id);",
                "ALTER TABLE t ALTER COLUMN a SET NOT NULL;",
                "ALTER TABLE t ADD COLUMN b text NOT NULL;",
                "CREATE TRIGGER x BEFORE UPDATE ON t FOR EACH ROW EXECUTE FUNCTION ledger.refuse_change();",
                "CREATE UNIQUE INDEX one ON t (a);",
                "REVOKE DELETE ON t FROM finapp_app;",
                "ALTER TABLE ledger.t ENABLE ROW LEVEL SECURITY;"
            })
    @DisplayName("a tightening on a table this same file created refuses nothing a release makes, and needs no marker")
    void tighteningOnANewTableNeedsNoMarker(String tightening) throws IOException {
        assertThat(lint("CREATE TABLE ledger.t (a bigint);\n" + tightening).refusals()).as(tightening).isEmpty();
    }

    @Test
    @DisplayName("a table only perhaps created here (IF NOT EXISTS), or a partition of a released one, earns no exemption")
    void onlyATableCertainlyCreatedHereIsExempt() throws IOException {
        assertThat(rulesFor("CREATE TABLE IF NOT EXISTS ledger.t (a bigint);\nALTER TABLE ledger.t ADD CHECK (a > 0);"))
                .containsExactly(Refused.CHECK);
        assertThat(rulesFor("CREATE TABLE ledger.p PARTITION OF ledger.account FOR VALUES IN (1);\nALTER TABLE ledger.p ADD CHECK (id > 0);"))
                .containsExactly(Refused.CHECK);
    }

    @Test
    @DisplayName("a temporary table, or one made wherever a moved search_path points, cannot excuse a released table of the same name")
    void aShadowingTableIsNotExempt() throws IOException {
        assertThat(rulesFor("CREATE TEMP TABLE account (a bigint);\nALTER TABLE ledger.account ADD CHECK (a > 0);")).containsExactly(Refused.CHECK);
        assertThat(rulesFor("SET search_path = elsewhere;\nCREATE TABLE account (a bigint);\nALTER TABLE ledger.account ADD CHECK (a > 0);"))
                .containsExactly(Refused.CHECK);
        assertThat(rulesFor("SELECT set_config('search_path', 'elsewhere', true);\nCREATE TABLE account (a bigint);\nALTER TABLE ledger.account ADD CHECK (a > 0);"))
                .containsExactly(Refused.CHECK);
        assertThat(lint("SET search_path = elsewhere;\nCREATE TABLE ledger.fresh (a bigint);\nALTER TABLE ledger.fresh ADD CHECK (a > 0);").refusals())
                .as("a table named with its schema is the table named")
                .isEmpty();
    }

    @Test
    @DisplayName("a destructive statement earns no exemption from a table created in the same file")
    void destructiveIsNeverExempt() throws IOException {
        assertThat(rulesFor("CREATE TABLE ledger.t (a bigint);\nALTER TABLE ledger.t DROP COLUMN a;")).containsExactly(Refused.DROP);
    }

    @Test
    @DisplayName("a table a later statement creates does not excuse an earlier one")
    void theTableMustAlreadyBeCreated() throws IOException {
        assertThat(rulesFor("ALTER TABLE ledger.t ADD CHECK (a > 0);\nCREATE TABLE ledger.t (a bigint);")).containsExactly(Refused.CHECK);
    }

    @Test
    @DisplayName("names resolve as PostgreSQL resolves them: unqualified is the module's, unquoted is folded")
    void namesAreNormalised() throws IOException {
        assertThat(lint("CREATE TABLE t (a bigint);\nALTER TABLE ledger.T ADD CHECK (a > 0);\nALTER TABLE \"t\" ADD CHECK (a > 1);").refusals())
                .isEmpty();
        assertThat(rulesFor("CREATE TABLE \"T\" (a bigint);\nALTER TABLE t ADD CHECK (a > 0);")).containsExactly(Refused.CHECK);
    }

    @Test
    @DisplayName("a REVOKE on a function this file created is the shape of a new definer function, and needs no marker")
    void aRevokeOnANewFunctionNeedsNoMarker() throws IOException {
        assertThat(lint("""
                CREATE FUNCTION ledger.g(bigint, integer) RETURNS integer LANGUAGE sql AS 'SELECT 1';
                REVOKE ALL ON FUNCTION ledger.g(bigint, integer) FROM PUBLIC;
                GRANT EXECUTE ON FUNCTION ledger.g(bigint, integer) TO finapp_app;
                """).refusals()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-- DROP TABLE ledger.account;\nSELECT 1;",
                "/* DROP TABLE ledger.account; */ SELECT 1;",
                "/* outer /* nested */ REVOKE ALL ON ledger.account FROM x; */ SELECT 1;",
                "SELECT 'DROP TABLE ledger.account; REVOKE ALL ON ledger.account FROM x;';",
                "SELECT 'it''s; DROP TABLE ledger.account;';",
                "SELECT E'a\\'; DROP TABLE ledger.account;';",
                "COMMENT ON TABLE ledger.account IS 'do not DROP; ALTER TABLE x RENAME TO y';",
                "CREATE FUNCTION ledger.h() RETURNS void LANGUAGE plpgsql AS $$ BEGIN DROP TABLE ledger.account; END; $$;",
                "CREATE FUNCTION ledger.h() RETURNS void LANGUAGE plpgsql AS $body$ BEGIN ALTER TABLE a ADD CHECK (b); $$ END; $body$;",
                "CREATE FUNCTION ledger.h() RETURNS void LANGUAGE sql AS 'DROP TABLE ledger.account';",
                "ALTER TABLE ledger.account ADD COLUMN \"check\" int;",
                "ALTER TABLE ledger.account ADD COLUMN \"drop\" int;"
            })
    @DisplayName("comments, string literals, function bodies and quoted names do not count")
    void wordsInsideThemDoNotCount(String sql) throws IOException {
        assertThat(lint(sql).refusals()).as(sql).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT 1;\nDROP TABLE ledger.account;",
                "SELECT 'quoted';DROP TABLE ledger.account;",
                "SELECT $$ body $$; DROP TABLE ledger.account;",
                "-- a comment ending at the line\nDROP TABLE ledger.account;",
                "/* a comment */ DROP TABLE ledger.account;"
            })
    @DisplayName("and what follows them still does")
    void whatFollowsThemStillCounts(String sql) throws IOException {
        assertThat(rulesFor(sql)).as(sql).containsExactly(Refused.DROP);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CREATE TABLE ledger.t (a bigint NOT NULL CHECK (a > 0) UNIQUE, b bigint REFERENCES ledger.account (id));",
                "ALTER TABLE ledger.account ADD COLUMN note text;",
                "ALTER TABLE ledger.account ADD COLUMN IF NOT EXISTS note text;",
                "ALTER TABLE ledger.account ADD COLUMN kind text NOT NULL DEFAULT 'x';",
                "ALTER TABLE ledger.account ADD COLUMN at timestamptz DEFAULT now() NOT NULL;",
                "ALTER TABLE ledger.account ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY NOT NULL;",
                "ALTER TABLE ledger.account ADD COLUMN seq bigserial NOT NULL;",
                "ALTER TABLE ledger.account ALTER COLUMN code SET DEFAULT 'x';",
                "ALTER TABLE ledger.account VALIDATE CONSTRAINT c;",
                "CREATE INDEX a_by_code ON ledger.account (code);",
                "CREATE INDEX IF NOT EXISTS a_by_code ON ledger.account (code);",
                "CREATE FUNCTION ledger.h() RETURNS void LANGUAGE sql AS 'SELECT 1';",
                "CREATE OR REPLACE FUNCTION ledger.brand_new() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$;",
                "CREATE TYPE ledger.k AS ENUM ('a');",
                "CREATE SEQUENCE ledger.s;",
                "CREATE VIEW ledger.v AS SELECT 1;",
                "GRANT SELECT ON ledger.account TO finapp_app;",
                "COMMENT ON TABLE ledger.account IS 'x';",
                "INSERT INTO ledger.account VALUES (1, 'a', 0);",
                "UPDATE ledger.account SET code = 'b';",
                "SET LOCAL lock_timeout = '1s';",
                "ALTER TYPE ledger.k ADD VALUE 'b';"
            })
    @DisplayName("what is additive passes unmarked")
    void additivePasses(String sql) throws IOException {
        assertThat(lint(sql).refusals()).as(sql).isEmpty();
    }

    @Test
    @DisplayName("a trigger firing on TRUNCATE alone needs no marker: the application holds no TRUNCATE, so it refuses nothing a release makes")
    void aTruncateOnlyTriggerNeedsNoMarker() throws IOException {
        assertThat(lint("CREATE TRIGGER t BEFORE TRUNCATE ON ledger.account EXECUTE FUNCTION ledger.refuse_change();").refusals()).isEmpty();
    }

    @Test
    @DisplayName("a replaced body is judged against the floor: one defined only since it refuses nothing released")
    void onlyWhatAReleaseHoldsIsAReplacement() throws IOException {
        write("V001__released.sql", RELEASED);
        write("V002__new.sql", "CREATE FUNCTION ledger.fresh() RETURNS void LANGUAGE sql AS 'SELECT 1';");
        write("V003__replaced.sql", "CREATE OR REPLACE FUNCTION ledger.fresh() RETURNS void LANGUAGE sql AS 'SELECT 2';");
        assertThat(MigrationLint.check(root, atTheFirstMigration()).refusals()).isEmpty();
    }

    // -----------------------------------------------------------------
    // The marker
    // -----------------------------------------------------------------

    private static final String DROP = "ALTER TABLE ledger.account DROP COLUMN code;";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "-- finapp-contract: first-conforming-release=; waives=DROP; reason=x\n",
                "-- finapp-contract: first-conforming-release=1.4; waives=DROP; reason=x\n",
                "-- finapp-contract: first-conforming-release=latest; waives=DROP; reason=x\n",
                "-- finapp-contract: first-conforming-release=ANY\n",
                "-- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=\n",
                "-- finapp-contract first-conforming-release=ANY; waives=DROP; reason=x\n",
                "--finapp-contract: first-conforming-release=ANY; waives=DROP; reason=x\n",
                "-- FINAPP-CONTRACT: first-conforming-release=ANY; waives=DROP; reason=x\n",
                "-- finapp-contract: first-conforming-release=ANY; reason=x\n",
                "-- finapp-contract: first-conforming-release=ANY; waives=; reason=x\n",
                "-- finapp-contract: first-conforming-release=ANY; waives=NO_SUCH_CLASS; reason=x\n",
                "-- finapp-contract: first-conforming-release=ANY; waives=UNREADABLE; reason=x\n",
            })
    @DisplayName("a marker that does not parse passes nothing, and is itself refused")
    void aMalformedMarkerIsRefused(String marker) throws IOException {
        assertThat(rulesFor(marker + DROP)).as(marker).contains(Refused.MALFORMED_MARKER, Refused.DROP);
    }

    @Test
    @DisplayName("a marker waives the classes it names and no others: a reason given for a replaced body does not cover a dropped column")
    void aMarkerWaivesOnlyWhatItNames() throws IOException {
        String both = "CREATE OR REPLACE FUNCTION ledger.refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$;\n" + DROP;
        assertThat(rulesFor("-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY; reason=its clock calls only\n" + both))
                .containsExactly(Refused.DROP);
        assertThat(lint("-- finapp-contract: first-conforming-release=ANY; waives=REPLACED_BODY,DROP; reason=both reviewed\n" + both).refusals()).isEmpty();
    }

    @Test
    @DisplayName("two markers, or one after the first statement, pass nothing")
    void aMarkerIsOneAndInTheHeader() throws IOException {
        assertThat(rulesFor(MARKER + MARKER + DROP)).contains(Refused.MALFORMED_MARKER, Refused.DROP);
        assertThat(rulesFor("SELECT 1;\n" + MARKER + DROP)).contains(Refused.MALFORMED_MARKER, Refused.DROP);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/* -- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=x */\n",
                "/*\n-- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=x\n*/\n",
                "SELECT '\n-- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=x\n';\n",
                "  -- finapp-contract: first-conforming-release=ANY; waives=DROP; reason=x\n"
            })
    @DisplayName("a marker inside a block comment or a string, or indented, is not a marker")
    void onlyALineCommentAtTheStartOfALineIsAMarker(String notAMarker) throws IOException {
        assertThat(rulesFor(notAMarker + DROP)).as(notAMarker).contains(Refused.DROP);
    }

    @Test
    @DisplayName("a marker naming a release not yet out is refused: release N - 1 is running and does not conform")
    void aMarkerAheadOfTheLastReleaseIsRefused() throws IOException {
        write("V001__released.sql", RELEASED);
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=1.5.0; waives=DROP; reason=x\n" + DROP);
        assertThat(MigrationLint.check(root, MigrationLint.floor("1.4.0", Map.of(MODULE, "1"))).refusals())
                .extracting(Refusal::rule)
                .containsExactly(Refused.MARKER_AHEAD_OF_THE_RELEASE, Refused.DROP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.4.0", "1.3.9", "0.9.0", "ANY"})
    @DisplayName("a marker naming the last release or an earlier one, or ANY, stands")
    void aMarkerAtOrBelowTheLastReleaseStands(String named) throws IOException {
        write("V001__released.sql", RELEASED);
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=" + named + "; waives=DROP; reason=x\n" + DROP);
        assertThat(MigrationLint.check(root, MigrationLint.floor("1.4.0", Map.of(MODULE, "1"))).refusals()).isEmpty();
    }

    @Test
    @DisplayName("a release that is compared is compared as a version: 1.10.0 is after 1.9.0")
    void releasesAreComparedAsVersions() throws IOException {
        write("V001__released.sql", RELEASED);
        write("V002__planted.sql", "-- finapp-contract: first-conforming-release=1.9.0; waives=DROP; reason=x\n" + DROP);
        assertThat(MigrationLint.check(root, MigrationLint.floor("1.10.0", Map.of(MODULE, "1"))).refusals()).isEmpty();
    }

    @Test
    @DisplayName("while no release has been cut, there is no release N - 1 and any well-formed name stands")
    void noReleaseYetMeansNoMarkerIsAhead() throws IOException {
        assertThat(lint("-- finapp-contract: first-conforming-release=99.0.0; waives=DROP; reason=x\n" + DROP).refusals()).isEmpty();
    }

    // -----------------------------------------------------------------
    // The floor
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a migration at or below its module's floor is not read; one above it is")
    void theFloorDividesReadFromUnread() throws IOException {
        write("V001__released.sql", DROP);
        write("V002__planted.sql", "SELECT 1;");
        Outcome outcome = MigrationLint.check(root, atTheFirstMigration());
        assertThat(outcome.filesRead()).containsExactly("ledger/V002__planted.sql");
        assertThat(outcome.refusals()).isEmpty();
    }

    @Test
    @DisplayName("the floor is compared as a version: V010 is above a floor of 9, though '010' sorts before '9'")
    void theFloorIsComparedAsAVersion() throws IOException {
        write("V010__planted.sql", DROP);
        assertThat(MigrationLint.check(root, MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of(MODULE, "9"))).refusals())
                .extracting(Refusal::rule)
                .containsExactly(Refused.DROP);
    }

    @Test
    @DisplayName("a module the floor does not name is read from its first migration")
    void aModuleAbsentFromTheFloorIsReadWhole() throws IOException {
        write("V001__first.sql", DROP);
        assertThat(MigrationLint.check(root, MigrationLint.floor(MigrationLint.NO_RELEASE, Map.of())).refusals())
                .extracting(Refusal::rule)
                .containsExactly(Refused.DROP);
    }

    @Test
    @DisplayName("a floor file naming a release that is neither none nor a version is refused")
    void aFloorMustNameARelease() {
        assertThatThrownBy(() -> Floor.parse("release: \"the next release\"\nfloors:\n  ledger: \"1\"\n"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------
    // What cannot be read
    // -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 'never closed;", "/* never closed", "SELECT \"never closed", "CREATE FUNCTION f() AS $$ never closed;"})
    @DisplayName("a file that cannot be read to its end is refused, and a marker does not waive it")
    void anUnreadableFileIsRefused(String sql) throws IOException {
        assertThat(rulesFor(sql)).as(sql).containsExactly(Refused.UNREADABLE);
        assertThat(rulesFor(MARKER + sql)).as(sql).containsExactly(Refused.UNREADABLE);
    }

    @Test
    @DisplayName("a script in a subdirectory, which Flyway would find and the migrator image would not stage, is refused rather than unread")
    void aScriptInASubdirectoryIsRefused() throws IOException {
        write("nested/V002__below.sql", "SELECT 1;");
        assertThat(MigrationLint.check(root, atTheFirstMigration()).refusals())
                .extracting(Refusal::rule)
                .containsExactly(Refused.NOT_A_MIGRATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"R__repeatable.sql", "V1_description.sql", "notes.txt"})
    @DisplayName("a file that is not a versioned migration is refused")
    void anythingElseInTheDirectoryIsRefused(String file) throws IOException {
        write(file, "SELECT 1;");
        assertThat(MigrationLint.check(root, atTheFirstMigration()).refusals())
                .extracting(Refusal::rule)
                .containsExactly(Refused.NOT_A_MIGRATION);
    }
}
