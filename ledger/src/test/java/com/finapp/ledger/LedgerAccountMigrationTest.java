package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The chart's enums and the schema artefacts generated from them are one definition
 * (`P3-TSK-002`) — the {@code KycCaseMigrationTest} idiom, with <strong>seven</strong>
 * generated fragments: five value lists and two coherence rules.
 *
 * <p>The coherence rules are the ones that would drift most expensively: a stored
 * {@code ASSET}/{@code CREDIT} pair is an account whose reports disagree about which side it
 * grows on, and a {@code FEE_REVENUE} row owned by a customer hands the platform's money a
 * person — both silent without this reconciliation.
 */
@DisplayName("the ledger_account schema and its enums agree (P3-TSK-002)")
class LedgerAccountMigrationTest {

    private static final String MIGRATION = "db/migration/ledger/V002__create_ledger_account.sql";

    @Test
    @DisplayName("every enum's CHECK lists exactly the values the enum declares")
    void everyValueListMatchesItsEnum() {
        assertThat(migration())
                .contains("CHECK (account_type IN (" + AccountType.sqlValueList() + "))")
                .contains("CHECK (normal_balance IN (" + NormalBalance.sqlValueList() + "))")
                .contains("CHECK (owner_kind IN (" + OwnerKind.sqlValueList() + "))")
                .contains("CHECK (purpose IN (" + AccountPurpose.sqlValueList() + "))")
                .contains("CHECK (status IN (" + LedgerAccountStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("both coherence rules are the derivations' own SQL")
    void theCoherenceRulesAreTheDerivations() {
        assertThat(migration())
                .as("the type -> normal-balance derivation must have one definition")
                .contains("CHECK (" + AccountType.sqlNormalBalanceRule() + ")");
        assertThat(migration())
                .as("the purpose -> owner-kind derivation must have one definition")
                .contains("CHECK (" + AccountPurpose.sqlOwnerKindRule() + ")");
    }

    @Test
    @DisplayName("no cross-schema foreign key, and the grant is column-narrowed")
    void theBoundaryAndTheGrantsHold() {
        assertThat(migration())
                .as("owner_ref is a value; an FK into accounts' schema is coupling neither"
                        + " Gradle nor ArchUnit can see (ADR-0029)")
                .doesNotContain("REFERENCES accounts.");
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON ledger.ledger_account TO finapp_app")
                .contains(
                        "GRANT UPDATE (status, status_changed_at) ON ledger.ledger_account"
                                + " TO finapp_app")
                .doesNotContain("DELETE ON ledger.ledger_account");
    }

    @Test
    @DisplayName("the freeze trigger exists and probes the posting table safely")
    void theFreezeTriggerIsWired() {
        // The artefact half of INV-LED-06's enforcement; the behavioural half - each forbidden
        // UPDATE refused against a live database - is LedgerAccountDatabaseTest's. The
        // to_regclass guard is pinned because it is what makes the forward reference to
        // P3-TSK-005's table safe: without it, every status update would fail from the day
        // this migration lands until that table exists.
        assertThat(migration())
                .contains("CREATE TRIGGER ledger_account_classification_is_frozen")
                .contains("BEFORE UPDATE ON ledger.ledger_account")
                .contains("to_regclass('ledger.journal_line')");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Without this a moved or renamed file makes every assertion above pass over an empty
        // string - and the doesNotContain assertions would pass most convincingly of all.
        assertThat(migration()).contains("CREATE TABLE ledger.ledger_account");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                LedgerAccountMigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
