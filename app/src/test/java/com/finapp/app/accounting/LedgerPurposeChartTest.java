package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.LedgerChart;
import com.finapp.accounting.MappingJudgement;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.bnpl.BnplAccounts;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.lending.LoanAccounts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * accounting's {@code LedgerChart} over the ledger's own vocabulary (`P14-TSK-003`; ADR-0113 decision 3;
 * {@code INV-ACC-06}) and the reference GL mapping held to it - in {@code app}, the one module that sees both. Hermetic:
 * the chart's observed half is read here from the ledger's own seed migrations (every operational, suspense and
 * counterparty account the ledger has is born by one of them), the declared half is the adapter's; together they are the
 * chart the activation reads on a fresh deployment. {@code GlMappingDatabaseTest} reads the live one (owed, C5).
 */
@DisplayName("the ledger's chart as accounting reads it, and the reference mapping total and compatible over it (P14-TSK-003)")
class LedgerPurposeChartTest {

    /** A seeded {@code ledger_account} row: type, normal side, currency, owner kind, owner ref, purpose. */
    private static final Pattern SEEDED = Pattern.compile("'(ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE)',\\s*'(?:DEBIT|CREDIT)',"
            + "\\s*'[A-Z]{3}',\\s*'([A-Z_]+)',\\s*[^,]*,\\s*'([A-Z_]+)'");

    @Test
    @DisplayName("the chart is every purpose of the ledger's enum - forty - each with its owner kind; the runtime-opened"
            + " ones carry their owning module's declared type even with no account yet")
    void everyPurposeOfTheEnum() {
        LedgerChart.Chart chart = LedgerPurposeChart.chart(Map.of(), List.of());
        assertThat(chart.purposes()).hasSize(40).hasSize(AccountPurpose.values().length);
        for (AccountPurpose purpose : AccountPurpose.values()) {
            LedgerChart.ChartPurpose read = chart.purposes().get(purpose.ordinal());
            assertThat(read.purpose()).isEqualTo(purpose.name());
            assertThat(read.ownerKind()).isEqualTo(purpose.ownerKind().name());
            switch (purpose.ownerKind()) {
                case LOAN -> assertThat(read.types()).containsExactly(LoanAccounts.declaredTypeOf(purpose).name());
                case BNPL_OBLIGATION -> assertThat(read.types()).containsExactly(BnplAccounts.declaredTypeOf(purpose).name());
                case CUSTOMER, MERCHANT -> assertThat(read.types()).containsExactly("LIABILITY");
                case OPERATIONAL, SUSPENSE, COUNTERPARTY -> assertThat(read.types())
                        .as("%s is seeded by migration: its rows are its type", purpose).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("a declaration the ledger's rows contradict gives the purpose two types - fail closed, never a pass")
    void aContradictionIsTwoTypes() {
        Map<AccountPurpose, Set<String>> observed = new EnumMap<>(AccountPurpose.class);
        observed.put(AccountPurpose.CUSTOMER_WALLET, Set.of("ASSET"));
        LedgerChart.ChartPurpose wallet = LedgerPurposeChart.chart(observed, List.of()).purposes()
                .get(AccountPurpose.CUSTOMER_WALLET.ordinal());
        assertThat(wallet.types()).containsExactlyInAnyOrder("ASSET", "LIABILITY");
        MappingJudgement judgement = MappingJudgement.judge(ReferenceGlMapping.version(),
                LedgerPurposeChart.chart(seeded(), List.of()).purposes().stream()
                        .map(p -> p.purpose().equals("CUSTOMER_WALLET") ? wallet : p)
                        .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),
                                purposes -> new LedgerChart.Chart(purposes, List.of()))));
        assertThat(judgement.incompatible()).singleElement().asString().startsWith("CUSTOMER_WALLET/CUSTOMER is ASSET+LIABILITY");
    }

    @Test
    @DisplayName("the reference GL mapping version (C2, test-only) is total over the ledger's forty purposes and"
            + " classification-compatible with the chart a fresh deployment holds - its seed migrations and the"
            + " declared types")
    void theReferenceIsTotalAndCompatible() {
        Map<AccountPurpose, Set<String>> seeded = seeded();
        for (AccountPurpose purpose : AccountPurpose.values()) {
            if (LedgerPurposeChart.declared(purpose).isEmpty()) {
                assertThat(seeded).as("%s is born by a ledger seed migration", purpose).containsKey(purpose);
                assertThat(seeded.get(purpose)).as("%s has one type", purpose).hasSize(1);
            } else {
                assertThat(seeded).as("%s is opened at runtime, never seeded", purpose).doesNotContainKey(purpose);
            }
        }
        GlMappingVersion reference = ReferenceGlMapping.version();
        MappingJudgement judgement = MappingJudgement.judge(reference, LedgerPurposeChart.chart(seeded, List.of()));
        assertThat(judgement.unmapped()).isEmpty();
        assertThat(judgement.unknown()).isEmpty();
        assertThat(judgement.incompatible()).isEmpty();
        assertThat(ReferenceGlMapping.ownerKinds().keySet())
                .containsExactlyElementsOf(Arrays.stream(AccountPurpose.values()).map(Enum::name).toList());
        for (AccountPurpose purpose : AccountPurpose.values()) {
            assertThat(ReferenceGlMapping.ownerKinds()).containsEntry(purpose.name(), purpose.ownerKind().name());
        }
    }

    @Test
    @DisplayName("the adapter reads the ledger with SELECT only - its two statements, no lock")
    void selectOnly() {
        for (String statement : List.of(LedgerPurposeChart.TYPES, LedgerPurposeChart.COUNTERPARTIES)) {
            assertThat(statement).startsWith("SELECT ").doesNotContain("FOR UPDATE").doesNotContain("FOR SHARE");
        }
        assertThat(Arrays.stream(AccountType.values()).map(Enum::name)).contains("ASSET", "LIABILITY");
    }

    // -----------------------------------------------------------------

    /** Every (purpose, type) the ledger's seed migrations write - the chart's observed half on a fresh deployment. */
    static Map<AccountPurpose, Set<String>> seeded() {
        Map<AccountPurpose, Set<String>> types = new EnumMap<>(AccountPurpose.class);
        Path migrations = repositoryRoot().resolve("ledger/src/main/resources/db/migration/ledger");
        try (Stream<Path> files = Files.list(migrations)) {
            for (Path file : files.sorted().toList()) {
                Matcher row = SEEDED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (row.find()) {
                    AccountPurpose purpose = AccountPurpose.valueOf(row.group(3));
                    assertThat(row.group(2)).as("%s's owner kind in %s", purpose, file.getFileName())
                            .isEqualTo(purpose.ownerKind().name());
                    types.computeIfAbsent(purpose, p -> new TreeSet<>()).add(row.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(types).as("the seed is read").isNotEmpty();
        return types;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("the repository root (settings.gradle.kts) is not above the working directory");
        }
        return current;
    }
}
