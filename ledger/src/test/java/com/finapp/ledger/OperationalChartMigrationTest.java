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
                    "db/migration/ledger/V018__cash_at_bank_joins_the_chart.sql",
                    // P9-TSK-003: the thirteen operational purposes in JPY and BHD.
                    "db/migration/ledger/V019__jpy_and_bhd_join_the_chart.sql",
                    // P9-TSK-009: the conversion's spread revenue, with its one poster.
                    "db/migration/ledger/V020__fx_spread_revenue_joins_the_chart.sql");

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
                    // ADR-0076 section 4 (P9-TSK-009): the conversion's spread and markup -
                    // earned, so revenue, growing on the credit side ConversionLines credits.
                    Map.entry(AccountPurpose.FX_SPREAD_REVENUE, AccountType.REVENUE),
                    // ADR-0071 section 2 (P8-TSK-015): an approved WRITE_OFF's loss - growing
                    // on the debit side the write-off debits.
                    Map.entry(AccountPurpose.RECONCILIATION_LOSSES, AccountType.EXPENSE),
                    Map.entry(AccountPurpose.FEE_REVENUE, AccountType.REVENUE),
                    // ADR-0070 section 4 (P8-TSK-015): an approved RECOGNISE_GAIN's income.
                    Map.entry(AccountPurpose.RECONCILIATION_GAINS, AccountType.REVENUE),
                    Map.entry(AccountPurpose.FX_POSITION, AccountType.ASSET),
                    // ADR-0078 / PHASE_9_PLAN.md section 12.6 (P9-TSK-010): what one FX provider
                    // owes the platform - a receivable, growing on the debit side the cover's
                    // provider leg debits. Decided here, with the purpose's admission (V021),
                    // before its first account exists (V022, P9-TSK-011; INV-LED-06).
                    Map.entry(AccountPurpose.FX_PROVIDER_CLEARING, AccountType.ASSET),
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

    // ------------------------------------------------------------------------------------------
    // THE COUNTERPARTY PART (P9-TSK-010, ADR-0078 section 4): each counterparty is registered and
    // seeded with its accounts by the migration that admits it - never minted at runtime - so
    // the same rules hold for its rows as for the operational seed: a registry row's id and an
    // account's id are UUIDv7 literals below the ceiling (the lock order), each (counterparty,
    // purpose, currency) is seeded once, each account names a counterparty registered in the
    // same or an earlier admitting migration, its purpose is counterparty-owned, and its type is
    // the pinned decision. V021 admits the mechanism and no counterparty; V022 (P9-TSK-011) is
    // the first to add rows here. The rules are proven against planted seeds below, so they are
    // live before the first real row lands.

    /** Every migration that registers counterparties or seeds their accounts, in order. */
    private static final List<String> COUNTERPARTY_SEEDS =
            List.of("db/migration/ledger/V021__counterparty_keyed_clearing_positions.sql");

    private static final Pattern REGISTRY_ROW =
            Pattern.compile("\\('([0-9a-f-]{36})',\\s*'([a-z][a-z0-9-]*)',\\s*'([A-Z_]+)'\\)");

    private static final Pattern COUNTERPARTY_ROW =
            Pattern.compile(
                    "\\('([0-9a-f-]{36})',\\s*'([A-Z_]+)',\\s*'([A-Z]+)',\\s*'([A-Z]{3})',"
                            + "\\s*'COUNTERPARTY',\\s*'([0-9a-f-]{36})',\\s*'([A-Z_]+)',");

    @Test
    @DisplayName("every counterparty seed holds the chart's rules - registered first, once per"
            + " (counterparty, purpose, currency), typed as pinned, below the ceiling")
    void theCounterpartySeedHoldsTheRules() {
        List<String> seeds = COUNTERPARTY_SEEDS.stream().map(OperationalChartMigrationTest::migration).toList();
        assertThat(counterpartyViolations(seeds)).isEmpty();
        assertThat(migration(COUNTERPARTY_SEEDS.get(0)))
                .as("not vacuous: the first counterparty migration is the one creating the registry")
                .contains("CREATE TABLE ledger.counterparty");
    }

    @Test
    @DisplayName("the counterparty rules bite: each planted defect in a seed is named")
    void theCounterpartyRulesBite() {
        String registry = "INSERT INTO ledger.counterparty (id, code, kind) VALUES"
                + " ('01a0e2bc-8200-7021-8000-000000000001', 'fx-sim-a', 'FX_PROVIDER');\n";
        String account = "('01a0e2bc-8200-7021-8000-000000000101', 'ASSET', 'DEBIT', 'EUR', 'COUNTERPARTY',"
                + " '01a0e2bc-8200-7021-8000-000000000001', 'FX_PROVIDER_CLEARING', NULL, 'ACTIVE')";
        assertThat(counterpartyViolations(List.of(registry + account))).as("the control seed").isEmpty();

        assertThat(counterpartyViolations(List.of(registry + account + ",\n" + account.replace("0101", "0102"))))
                .singleElement().asString().contains("seeded twice");
        assertThat(counterpartyViolations(List.of(account + "\n" + registry)))
                .singleElement().asString().contains("no counterparty registered before it");
        assertThat(counterpartyViolations(List.of(registry + account.replace("'ASSET', 'DEBIT'", "'LIABILITY', 'CREDIT'"))))
                .singleElement().asString().contains("pinned");
        assertThat(counterpartyViolations(List.of(registry + account.replace("'ASSET', 'DEBIT'", "'ASSET', 'CREDIT'"))))
                .singleElement().asString().contains("normal balance");
        assertThat(counterpartyViolations(List.of(registry + account.replace("FX_PROVIDER_CLEARING", "SETTLEMENT_CLEARING"))))
                .anySatisfy(violation -> assertThat(violation).contains("not counterparty-owned"));
        // A registry id stamped after the ceiling (2026-10-04, 0x019... -> 01a1...).
        assertThat(counterpartyViolations(List.of(registry.replace("01a0e2bc-8200-7021", "01a10820-3976-7021")
                        + account.replace("'01a0e2bc-8200-7021-8000-000000000001'", "'01a10820-3976-7021-8000-000000000001'"))))
                .singleElement().asString().contains("ceiling");
        assertThat(counterpartyViolations(List.of(registry + account.replace("01a0e2bc-8200-7021-8000-000000000101", "01a0e2bc-8200-4021-8000-000000000101"))))
                .singleElement().asString().contains("version 7");
    }

    /** Every rule a counterparty seed breaks, across {@code seeds} read in order. */
    static List<String> counterpartyViolations(List<String> seeds) {
        long ceilingMillis = java.time.Instant.parse("2026-09-28T00:00:00Z").toEpochMilli();
        List<String> violations = new java.util.ArrayList<>();
        java.util.Set<String> registered = new java.util.HashSet<>();
        java.util.Set<String> seeded = new java.util.HashSet<>();
        java.util.function.BiConsumer<String, String> idRules =
                (id, what) -> {
                    if (id.charAt(14) != '7') {
                        violations.add(what + " " + id + " is not version 7");
                    } else if ((java.util.UUID.fromString(id).getMostSignificantBits() >>> 16) >= ceilingMillis) {
                        violations.add(what + " " + id + " embeds a timestamp at or after the ceiling");
                    }
                };
        for (String seed : seeds) {
            // A counterparty is registered before its accounts: in an earlier admitting
            // migration, or earlier in this one.
            java.util.Map<String, Integer> registeredAt = new java.util.HashMap<>();
            for (MatchResult row : REGISTRY_ROW.matcher(seed).results().toList()) {
                idRules.accept(row.group(1), "registry row");
                registeredAt.putIfAbsent(row.group(1), row.start());
            }
            for (MatchResult row : COUNTERPARTY_ROW.matcher(seed).results().toList()) {
                String id = row.group(1);
                AccountPurpose purpose = AccountPurpose.valueOf(row.group(6));
                AccountType type = AccountType.valueOf(row.group(2));
                idRules.accept(id, "account");
                boolean before = registered.contains(row.group(5))
                        || registeredAt.getOrDefault(row.group(5), Integer.MAX_VALUE) < row.start();
                if (!before) {
                    violations.add("account " + id + " names " + row.group(5)
                            + ", with no counterparty registered before it");
                }
                if (purpose.ownerKind() != OwnerKind.COUNTERPARTY) {
                    violations.add("account " + id + " is COUNTERPARTY-owned but " + purpose
                            + " is not counterparty-owned");
                }
                if (type != SEEDED_TYPES.get(purpose)) {
                    violations.add("account " + id + " is " + type + ", not the pinned "
                            + SEEDED_TYPES.get(purpose) + " of " + purpose);
                } else if (NormalBalance.valueOf(row.group(3)) != type.normalBalance()) {
                    violations.add("account " + id + "'s normal balance is not its type's derivation");
                }
                if (!seeded.add(row.group(5) + "|" + purpose + "|" + row.group(4))) {
                    violations.add("(" + row.group(5) + ", " + purpose + ", " + row.group(4)
                            + ") is seeded twice");
                }
            }
            registered.addAll(registeredAt.keySet());
        }
        return violations;
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
