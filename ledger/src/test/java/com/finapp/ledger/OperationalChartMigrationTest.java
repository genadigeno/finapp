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
                    "db/migration/ledger/V014__dispute_accounts_join_the_chart.sql",
                    // P8-TSK-009: the counterparties' processing costs, with hop 1's poster.
                    "db/migration/ledger/V016__processing_costs_joins_the_chart.sql",
                    // P8-TSK-015: the resolution's P&L accounts, with their poster.
                    "db/migration/ledger/V017__reconciliation_losses_and_gains_join_the_chart.sql",
                    // P8-TSK-016: the platform's cash, with its one poster.
                    "db/migration/ledger/V018__cash_at_bank_joins_the_chart.sql");

    /**
     * The seed's type decisions, pinned as its contract. Changing one is a reclassification of
     * a platform account and deserves exactly this edit plus its reasoning in the migration —
     * and once anything has posted, the trigger refuses it anyway ({@code INV-LED-06}).
     */
    private static final Map<AccountPurpose, AccountType> SEEDED_TYPES =
            Map.ofEntries(
                    Map.entry(AccountPurpose.SETTLEMENT_CLEARING, AccountType.ASSET),
                    // ADR-0057: instructed and not yet settled is an obligation we still owe,
                    // so it grows on the credit side the payout credits.
                    Map.entry(AccountPurpose.PAYOUT_CLEARING, AccountType.LIABILITY),
                    // ADR-0062 §4: the net receivable on the instant scheme - pay-ins
                    // debit it, withdrawals credit it, Phase 8 discharges it per cycle.
                    Map.entry(AccountPurpose.INSTANT_CLEARING, AccountType.ASSET),
                    // ADR-0065 section 3 (P8-TSK-016): the cash at the settlement bank -
                    // debited by a statement's net credits, credited by its net debits.
                    Map.entry(AccountPurpose.CASH_AT_BANK, AccountType.ASSET),
                    // ADR-0061 §3: a claim - on the network by representment, or on the
                    // counterparty for a parked share - growing on the debit side.
                    Map.entry(AccountPurpose.CHARGEBACK_RECOVERABLE, AccountType.ASSET),
                    // ADR-0061 §4: the written-off excess and the PSP's dispute fees.
                    Map.entry(AccountPurpose.DISPUTE_COSTS, AccountType.EXPENSE),
                    // ADR-0065 §2 (P8-TSK-009): the counterparties' processing fees, each
                    // recognised at its report's acceptance - growing on the debit side the
                    // recognition debits, a rebate crediting it back.
                    Map.entry(AccountPurpose.PROCESSING_COSTS, AccountType.EXPENSE),
                    // ADR-0071 section 2 (P8-TSK-015): an approved WRITE_OFF's loss - growing
                    // on the debit side the write-off debits.
                    Map.entry(AccountPurpose.RECONCILIATION_LOSSES, AccountType.EXPENSE),
                    Map.entry(AccountPurpose.FEE_REVENUE, AccountType.REVENUE),
                    // ADR-0070 section 4 (P8-TSK-015): an approved RECOGNISE_GAIN's income.
                    Map.entry(AccountPurpose.RECONCILIATION_GAINS, AccountType.REVENUE),
                    Map.entry(AccountPurpose.FX_POSITION, AccountType.ASSET),
                    Map.entry(AccountPurpose.ROUNDING_RESIDUAL, AccountType.EXPENSE),
                    Map.entry(AccountPurpose.SUSPENSE_UNMATCHED, AccountType.LIABILITY));

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

    /**
     * The lock-order rule the Phase 7 review ruled on (`P7-DOC-001`; `DISTRIBUTED_EXECUTION.md`
     * §3): the balance projection takes an entry's rows sorted by account id, and a chargeback
     * stage posts TWO entries - the external fact on the clearing and the recoverable, then the
     * attribution on the counterparty. Its order agrees with every single-entry posting that
     * touches a seeded account and the same counterparty (a capture, a refund) ONLY because every
     * seeded account's id sorts before every id a running instance mints. Runtime ids are UUIDv7
     * from the instance's clock, so the rule holds when every seed's embedded timestamp precedes
     * any clock a deployment runs on: seeds are hand-picked below this ceiling - `V013` and
     * `V014` already did, `01a0e000-0000-7000-8000-00000000000b` style - and a seed authored
     * with a fresh generator's id fails here instead of reversing a lock order in production.
     */
    @Test
    @DisplayName("every seeded account sorts before any account a running instance can mint -"
            + " the chargeback stage's lock order rests on it")
    void everySeededIdSortsBeforeEveryRuntimeId() {
        long ceilingMillis = java.time.Instant.parse("2026-09-28T00:00:00Z").toEpochMilli();
        for (MatchResult row : rows()) {
            java.util.UUID id = java.util.UUID.fromString(row.group(1));
            long embeddedMillis = id.getMostSignificantBits() >>> 16;
            assertThat(embeddedMillis)
                    .as("seed %s (%s) must embed a timestamp before the ceiling, or a running"
                                    + " instance can mint an account that sorts before it",
                            row.group(1), row.group(6))
                    .isLessThan(ceilingMillis);
        }
        // The runtime side of the same comparison, from the platform's own generator.
        java.util.UUID minted =
                new com.finapp.sharedkernel.id.IdGenerator(
                                java.time.Clock.systemUTC(), new java.security.SecureRandom())
                        .next();
        for (MatchResult row : rows()) {
            assertThat(java.util.UUID.fromString(row.group(1)))
                    .as("a freshly minted id sorts after seed %s", row.group(1))
                    .isLessThan(minted);
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
