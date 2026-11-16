package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.MigrationLint.ForeignKeys;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0006's data boundary, "no foreign keys across module schemas", held over every migration of every module at once
 * (`P15-REM-001`).
 *
 * <p>Until this guard the rule had only per-migration checks: the expand-only lint reads a migration above the release floor,
 * and each module's migration test reads its own. The Phase 15 exit review found the tree clean by reading it - every
 * cross-schema {@code REFERENCES} in a migration is inside a comment - and nothing held it so. This reads every file, below
 * the floor too, through {@link MigrationLint#foreignKeys}: the expand-only lint's tokeniser, so a comment, a string literal
 * or a quoted name never matches, and every dollar-quoted body is read as well, because a {@code DO} block can add a
 * constraint the statement-level reading never sees.
 *
 * <p>Hermetic: it reads files only.
 */
@Tag("architecture")
@DisplayName("no migration's foreign key names another module's schema (P15-REM-001; ADR-0006)")
class NoForeignKeyCrossesASchemaTest {

    private static Path root;

    @BeforeAll
    static void findTheRepository() {
        root = EvidenceCitations.repositoryRoot();
    }

    @Test
    @DisplayName("every REFERENCES in every migration names a table of its own module's schema, qualified - and the reading saw the tree's keys")
    void noForeignKeyCrossesASchema() throws IOException {
        ForeignKeys keys = MigrationLint.foreignKeys(root);

        assertThat(keys.crossings())
                .as("cross-context integrity is a domain concern enforced at the boundary (ADR-0006, ADR-0029): reference by value")
                .isEmpty();
        // An empty list from a reading that found nothing would prove nothing. 467 REFERENCES clauses were counted on
        // 2026-11-15 (bnpl's alone are most of them); the floor is set well under that so a new module never trips it.
        assertThat(keys.read()).as("the REFERENCES clauses the reading found across every module's migrations").isGreaterThan(400);
    }

    @Test
    @DisplayName("a key into another module's schema is refused however it is written: inline, added, quoted, in any case, inside a DO block,"
            + " or with no schema at all")
    void aCrossingIsRefusedHoweverItIsWritten() {
        assertCrosses("CREATE TABLE accounts.a (id uuid PRIMARY KEY, party_id uuid REFERENCES party.party (id));", "party.party");
        assertCrosses("ALTER TABLE accounts.a ADD CONSTRAINT a_fk FOREIGN KEY (account) REFERENCES ledger.ledger_account (id);",
                "ledger.ledger_account");
        assertCrosses("ALTER TABLE accounts.a ADD FOREIGN KEY (p) REFERENCES \"party\".\"party\" (id);", "party.party");
        assertCrosses("ALTER TABLE accounts.a ADD FOREIGN KEY (p) references Party.Party (id);", "party.party");
        assertCrosses("DO $$ BEGIN ALTER TABLE accounts.a ADD FOREIGN KEY (p) REFERENCES party.party (id); END $$;",
                "inside a dollar-quoted body");
        assertCrosses("DO $guard$ BEGIN EXECUTE 'SELECT 1'; ALTER TABLE accounts.a ADD FOREIGN KEY (p) REFERENCES kyc.kyc_case (id);"
                + " END $guard$;", "kyc.kyc_case");
        assertCrosses("CREATE TABLE accounts.a (party_id uuid REFERENCES party (id));", "names no schema");
    }

    @Test
    @DisplayName("what is not a key into another schema passes: a key into its own, a comment, a string, a COMMENT ON, a REFERENCES privilege")
    void whatIsNotACrossingPasses() {
        ForeignKeys own = MigrationLint.foreignKeys(
                "CREATE TABLE accounts.b (id uuid PRIMARY KEY, a_id uuid NOT NULL REFERENCES accounts.a (id));", "accounts", "planted.sql");
        assertThat(own.crossings()).as("a key inside the module's own schema").isEmpty();
        assertThat(own.read()).as("and it was read, not skipped").isEqualTo(1);

        ForeignKeys none = MigrationLint.foreignKeys(String.join("\n",
                "-- customer_id REFERENCES party.customer by value, deliberately (ADR-0029)",
                "/* REFERENCES ledger.ledger_account, /* nested */ by value */",
                "COMMENT ON COLUMN accounts.a.party_id IS 'references party.party by value';",
                "INSERT INTO accounts.note (text) VALUES ('REFERENCES party.party');",
                "GRANT REFERENCES ON accounts.a TO finapp_app;",
                "GRANT SELECT, REFERENCES ON accounts.a TO finapp_app;",
                "GRANT REFERENCES (party_id) ON accounts.a TO finapp_app;",
                "CREATE FUNCTION accounts.f() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                        + " RAISE EXCEPTION 'a row references party.party (INV-X)'; END $$;"), "accounts", "planted.sql");
        assertThat(none.crossings()).isEmpty();
        assertThat(none.read()).as("no key at all: a comment, a string, a privilege and a message are not keys").isZero();
    }

    private static void assertCrosses(String sql, String named) {
        ForeignKeys keys = MigrationLint.foreignKeys(sql, "accounts", "planted.sql");
        assertThat(keys.crossings()).as("refused: %s", sql).hasSize(1);
        assertThat(keys.crossings().get(0)).contains("planted.sql").contains(named);
    }
}
