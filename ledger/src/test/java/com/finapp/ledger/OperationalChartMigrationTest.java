package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seed migration and {@link SupportedCurrencies} are one definition (`P3-TSK-003`).
 *
 * <p>A currency added to the list without its seed rows leaves {@code ChartOfAccounts.resolve}
 * throwing for a currency the platform claims to support; seed rows for a currency the list
 * dropped are a chart nothing resolves — both directions are reconciled, the register idiom.
 */
@DisplayName("the operational chart seed and its definitions agree (P3-TSK-003)")
class OperationalChartMigrationTest {

    /**
     * Every migration that seeds operational rows, read together: `V003` seeded the Phase 3
     * chart and `V012` adds `PAYOUT_CLEARING` (`P6-TSK-012`) — applied history is never
     * edited, so the definition "one row per operational purpose per currency" spans both.
     */
    private static final List<String> SEED_MIGRATIONS =
            List.of(
                    "db/migration/ledger/V003__seed_operational_chart.sql",
                    "db/migration/ledger/V012__payout_clearing_joins_the_chart.sql",
                    "db/migration/ledger/V013__instant_clearing_joins_the_chart.sql",
                    // P7-TSK-013: the dispute accounts, each with its first poster.
                    "db/migration/ledger/V014__dispute_accounts_join_the_chart.sql");

    /**
     * The seed's type decisions, pinned as its contract. Changing one is a reclassification of
     * a platform account and deserves exactly this edit plus its reasoning in the migration —
     * and once anything has posted, the trigger refuses it anyway ({@code INV-LED-06}).
     */
    private static final Map<AccountPurpose, AccountType> SEEDED_TYPES =
            Map.of(
                    AccountPurpose.SETTLEMENT_CLEARING, AccountType.ASSET,
                    // ADR-0057: instructed and not yet settled is an obligation we still owe,
                    // so it grows on the credit side the payout credits.
                    AccountPurpose.PAYOUT_CLEARING, AccountType.LIABILITY,
                    // ADR-0062 §4: the net receivable on the instant scheme - pay-ins
                    // debit it, withdrawals credit it, Phase 8 discharges it per cycle.
                    AccountPurpose.INSTANT_CLEARING, AccountType.ASSET,
                    // ADR-0061 §3: a claim - on the network by representment, or on the
                    // counterparty for a parked share - growing on the debit side.
                    AccountPurpose.CHARGEBACK_RECOVERABLE, AccountType.ASSET,
                    // ADR-0061 §4: the written-off excess and the PSP's dispute fees.
                    AccountPurpose.DISPUTE_COSTS, AccountType.EXPENSE,
                    AccountPurpose.FEE_REVENUE, AccountType.REVENUE,
                    AccountPurpose.FX_POSITION, AccountType.ASSET,
                    AccountPurpose.ROUNDING_RESIDUAL, AccountType.EXPENSE,
                    AccountPurpose.SUSPENSE_UNMATCHED, AccountType.LIABILITY);

    private static final Pattern ROW =
            Pattern.compile(
                    "\\('([0-9a-f-]{36})',\\s*'([A-Z_]+)',\\s*'([A-Z]+)',\\s*'([A-Z]{3})',"
                            + "\\s*'([A-Z_]+)',\\s*NULL,\\s*'([A-Z_]+)',");

    @Test
    @DisplayName("every operational purpose is seeded in every supported currency, exactly once")
    void everyPurposeIsSeededInEveryCurrency() {
        List<MatchResult> rows = rows();
        for (AccountPurpose purpose : AccountPurpose.values()) {
            if (purpose.ownerKind().requiresOwnerRef()) {
                // Owned purposes - customer wallets, merchant payables - are opened per
                // owner, never seeded. The predicate is the kind's own requiresOwnerRef()
                // since P6-TSK-003: "== CUSTOMER" was correct while exactly one kind had an
                // owner, the same correct-while-one assumption V011 retired from V002's
                // owner-ref rule.
                continue;
            }
            for (CurrencyCode currency : SupportedCurrencies.ALL) {
                assertThat(
                                rows.stream()
                                        .filter(
                                                row ->
                                                        row.group(6).equals(purpose.name())
                                                                && row.group(4)
                                                                        .equals(currency.code())))
                        .as("one seed row for %s in %s", purpose, currency)
                        .hasSize(1);
            }
        }
    }

    @Test
    @DisplayName("the seed holds no stray row: the count is exactly purposes x currencies")
    void theSeedHoldsNoStrayRow() {
        long operationalPurposes =
                java.util.Arrays.stream(AccountPurpose.values())
                        .filter(purpose -> !purpose.ownerKind().requiresOwnerRef())
                        .count();
        assertThat(rows())
                .as("a row outside the definition would be an account nothing resolves")
                .hasSize((int) (operationalPurposes * SupportedCurrencies.ALL.size()));
    }

    @Test
    @DisplayName("each seeded type is the pinned decision, and its normal balance the derivation")
    void theSeededTypesAreThePinnedContract() {
        for (MatchResult row : rows()) {
            AccountPurpose purpose = AccountPurpose.valueOf(row.group(6));
            AccountType type = AccountType.valueOf(row.group(2));
            assertThat(type)
                    .as("the seeded type of %s is this test's pinned contract", purpose)
                    .isEqualTo(SEEDED_TYPES.get(purpose));
            assertThat(NormalBalance.valueOf(row.group(3)))
                    .as("and its normal balance is the type's own derivation")
                    .isEqualTo(type.normalBalance());
            assertThat(OwnerKind.valueOf(row.group(5)))
                    .as("and its owner kind is the purpose's own derivation")
                    .isEqualTo(purpose.ownerKind());
        }
    }

    @Test
    @DisplayName("every seeded id is a UUIDv7 literal, because rehydrate validates version 7")
    void everyIdIsVersionSeven() {
        // gen_random_uuid() is v4, and a v4 seed would read back as malformed the first time
        // anything resolves it (ADR-0013; the P1-TSK-028 v4/v7 lesson, at authoring time).
        for (MatchResult row : rows()) {
            assertThat(row.group(1).charAt(14))
                    .as("id %s must be version 7", row.group(1))
                    .isEqualTo('7');
        }
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Each seed file on its own: a moved V012 must not hide behind V003's rows.
        for (String seed : SEED_MIGRATIONS) {
            assertThat(migration(seed)).as(seed).contains("INSERT INTO ledger.ledger_account");
            assertThat(ROW.matcher(migration(seed)).results()).as(seed).isNotEmpty();
        }
    }

    private static List<MatchResult> rows() {
        return SEED_MIGRATIONS.stream()
                .flatMap(seed -> ROW.matcher(migration(seed)).results())
                .toList();
    }

    private static String migration(String path) {
        try (InputStream migration =
                OperationalChartMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + path);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
