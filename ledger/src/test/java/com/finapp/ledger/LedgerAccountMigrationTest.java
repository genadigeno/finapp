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

    /**
     * Where the four enum-fed constraints live NOW: `V011` recreated them when
     * `MERCHANT_PAYABLE` widened two enums (`P6-TSK-003`), because `V002` is applied history
     * and cannot follow its enums. The reconciliation follows the latest definition — an enum
     * member added without a fresh recreation migration fails here, which is the entire point.
     */
    private static final String LATEST_CHART_RULES =
            "db/migration/ledger/V011__merchant_payable_joins_the_chart.sql";

    @Test
    @DisplayName("every enum's CHECK lists exactly the values the enum declares")
    void everyValueListMatchesItsEnum() {
        // account_type, normal_balance and status are untouched since V002; owner_kind and
        // purpose moved to V011 with MERCHANT_PAYABLE.
        assertThat(migration())
                .contains("CHECK (account_type IN (" + AccountType.sqlValueList() + "))")
                .contains("CHECK (normal_balance IN (" + NormalBalance.sqlValueList() + "))")
                .contains("CHECK (status IN (" + LedgerAccountStatus.sqlValueList() + "))");
        assertThat(chartRules())
                .contains("CHECK (owner_kind IN (" + OwnerKind.sqlValueList() + "))")
                .contains("CHECK (purpose IN (" + AccountPurpose.sqlValueList() + "))");
    }

    @Test
    @DisplayName("all three coherence rules are the derivations' own SQL")
    void theCoherenceRulesAreTheDerivations() {
        assertThat(migration())
                .as("the type -> normal-balance derivation must have one definition")
                .contains("CHECK (" + AccountType.sqlNormalBalanceRule() + ")");
        assertThat(chartRules())
                .as("the purpose -> owner-kind derivation must have one definition")
                .contains("CHECK (" + AccountPurpose.sqlOwnerKindRule() + ")");
        assertThat(chartRules())
                .as("the owner-ref presence rule is generated since V011 - V002's hand-written"
                        + " CUSTOMER-only form was correct while exactly one kind had an owner")
                .contains("CHECK (" + OwnerKind.sqlOwnerRefRule() + ")");
    }

    @Test
    @DisplayName("V011 recreates exactly the four constraints the widened enums feed")
    void theRecreationDropsWhatItAdds() {
        // Each ADD must replace a DROP of the same name in the same statement: a recreation
        // that forgets the DROP fails migration outright, but a DROP that forgets its ADD
        // silently removes a constraint - the direction this assertion exists for.
        for (String constraint :
                new String[] {
                    "ledger_account_owner_kind_is_known",
                    "ledger_account_purpose_is_known",
                    "ledger_account_owner_kind_matches_purpose",
                    "ledger_account_owner_ref_matches_kind"
                }) {
            assertThat(chartRules())
                    .contains("DROP CONSTRAINT " + constraint)
                    .contains("ADD CONSTRAINT " + constraint);
        }
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
    @DisplayName("the guard can actually read both migrations")
    void theGuardIsNotVacuous() {
        // Without this a moved or renamed file makes every assertion above pass over an empty
        // string - and the doesNotContain assertions would pass most convincingly of all.
        assertThat(migration()).contains("CREATE TABLE ledger.ledger_account");
        assertThat(chartRules()).contains("ALTER TABLE ledger.ledger_account");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        return read(MIGRATION);
    }

    private static String chartRules() {
        return read(LATEST_CHART_RULES);
    }

    private static String read(String resource) {
        try (InputStream migration =
                LedgerAccountMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + resource);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
