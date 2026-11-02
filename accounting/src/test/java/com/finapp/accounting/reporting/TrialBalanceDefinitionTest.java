package com.finapp.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.Side;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSetStatus;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code TRIAL_BALANCE} definition (`P14-TSK-009`; ADR-0115; {@code INV-ACC-01} reported, {@code INV-ACC-04},
 * {@code INV-ACC-06}): what it is, the version it reads (the period's pin for a FINAL run, the claim's for a PROVISIONAL
 * one), the cut a PROVISIONAL run honours (`P14-TSK-008`'s review, M-8), a run with no version, and the content's exact
 * shape - signed balances, one scale per currency reached exactly, sums past {@code long}, the verdict the close reads.
 */
@DisplayName("the TRIAL_BALANCE definition (P14-TSK-009)")
class TrialBalanceDefinitionTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            TrialBalanceDefinitionTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("the trial balance reads only through its port");
            });
    private static final YearMonth MONTH = YearMonth.of(2097, 6);
    private static final Instant CUT = Instant.parse("2097-06-20T10:00:00Z");
    private static final GlMappingVersion VERSION = ReferenceGlMapping.version(YearMonth.of(2097, 1));
    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    private static final String WALLET = "CUSTOMER_WALLET/CUSTOMER";
    private static final String FEES = "FEE_REVENUE/OPERATIONAL";
    private static final UUID SET = UUID.fromString("00000000-0000-4000-8000-000000000015");
    private static final UUID SNAPSHOT_ID = UUID.fromString("00000000-0000-4000-8000-000000000016");

    private final AccountingPeriodId periodId = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId pinned = GlMappingVersionId.next(IDS);
    private final GlMappingVersionId other = GlMappingVersionId.next(IDS);

    @Test
    @DisplayName("the definition: TRIAL_BALANCE version 1 - the ReportKind's name - taking no parameter and no rate set;"
            + " the close reads its verdict at {body,verdict,passes}")
    void theDefinition() {
        TrialBalanceDefinition definition = new TrialBalanceDefinition(new InMemoryTrialBalanceReads());
        assertThat(definition.code()).isEqualTo(ReportKind.TRIAL_BALANCE.name()).isEqualTo("TRIAL_BALANCE");
        assertThat(definition.version()).isEqualTo(1);
        assertThat(definition.parameterNames()).isEmpty();
        assertThat(definition.needsValuationRateSet()).isFalse();
        assertThat(ReportDefinitions.of(definition).current("TRIAL_BALANCE")).containsSame(definition);
        assertThat(TrialBalanceDefinition.VERDICT_PATH).isEqualTo("{body,verdict,passes}");
    }

    @Test
    @DisplayName("the version is the period's pin: a FINAL run reads the version its period's row pins, and one naming"
            + " another is refused before anything is read - never a FINAL trial balance under the wrong version")
    void theVersionIsThePeriodsPin() {
        InMemoryTrialBalanceReads reads = books(PeriodStatus.LOCKED, Optional.of(pinned));
        reads.version(other, ReferenceGlMapping.without(YearMonth.of(2097, 1), java.util.Set.of("FEE_REVENUE")));
        TrialBalance balance = new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.FINAL, Optional.of(pinned)));
        assertThat(balance.fullyMapped()).isTrue();
        assertThat(balance.passes()).isTrue();
        assertThatThrownBy(() -> new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.FINAL, Optional.of(other))))
                .isInstanceOf(TrialBalanceDefinition.PinMismatch.class);
        assertThatThrownBy(() -> new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.FINAL, Optional.empty())))
                .as("a FINAL run naming no version while its period pins one")
                .isInstanceOf(TrialBalanceDefinition.PinMismatch.class);
        assertThat(new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.PROVISIONAL, Optional.of(other))).fullyMapped())
                .as("a PROVISIONAL run reads the version its claim found in force - here one not mapping FEE_REVENUE")
                .isFalse();
    }

    @Test
    @DisplayName("the cut (P14-TSK-008's review, M-8): a PROVISIONAL run counts only entries created at or before its"
            + " claim, on both sides - still zero and equal to the ledger; a FINAL run reads every entry")
    void aProvisionalRunReadsAtItsCut() {
        InMemoryTrialBalanceReads reads = books(PeriodStatus.OPEN, Optional.empty());
        reads.post(MONTH.atDay(21), CUT.plusSeconds(1), "EUR", 2, 99_999, CASH, WALLET);
        reads.post(MONTH.atDay(3), CUT.plusSeconds(60), "EUR", 2, 77_777, CASH, WALLET);
        reads.post(MONTH.minusMonths(1).atDay(3), CUT.plusSeconds(60), "EUR", 2, 55_555, CASH, WALLET);
        TrialBalance provisional = new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.PROVISIONAL, Optional.of(pinned)));
        TrialBalance.AccountRow cash = row(provisional, "1000", "EUR");
        assertThat(cash.movement().debits()).as("the period's lines created at or before the cut")
                .isEqualByComparingTo("150.00");
        assertThat(cash.openingBalance()).as("the opening's too").isEqualByComparingTo("1000.00");
        assertThat(provisional.passes()).isTrue();

        reads.period(new TrialBalanceReads.Period(periodId, MONTH.atDay(1), MONTH.atEndOfMonth(), PeriodStatus.LOCKED,
                Optional.of(pinned)));
        TrialBalance whole = new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.FINAL, Optional.of(pinned)));
        assertThat(row(whole, "1000", "EUR").movement().debits()).isEqualByComparingTo("1927.76");
        assertThat(row(whole, "1000", "EUR").openingBalance()).isEqualByComparingTo("1555.55");
        assertThat(whole.passes()).isTrue();
    }

    @Test
    @DisplayName("no version pinned (a period locked before any activation): every line in an unmapped row, zero and equal"
            + " to the ledger still judged, fullyMapped false - the check still passes on the ledger's arithmetic")
    void noVersionPinnedLeavesEveryLineUnmapped() {
        InMemoryTrialBalanceReads reads = books(PeriodStatus.LOCKED, Optional.empty());
        TrialBalance balance = new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.FINAL, Optional.empty()));
        assertThat(balance.accounts()).isEmpty();
        assertThat(balance.unmapped()).extracting(TrialBalance.UnmappedRow::purpose)
                .contains("CASH_AT_BANK", "CUSTOMER_WALLET", "FEE_REVENUE");
        assertThat(balance.fullyMapped()).isFalse();
        assertThat(balance.balanced()).isTrue();
        assertThat(balance.equalsLedger()).isTrue();
        assertThat(balance.passes()).isTrue();
        assertThat(CanonicalJson.text(balance.body())).contains("\"fullyMapped\":false");
    }

    @Test
    @DisplayName("the content: signed debit-positive balances; a currency written at one scale, the largest it carries,"
            + " reached exactly; a sum past long written whole; the verdict and both ranges named")
    void theContentIsExact() {
        InMemoryTrialBalanceReads reads = books(PeriodStatus.LOCKED, Optional.of(pinned));
        reads.post(MONTH.atDay(5), CUT, "EUR", 3, 1, CASH, FEES); // EUR at a second scale: 0.001
        reads.post(MONTH.atDay(6), CUT, "JPY", 0, 9_000_000_000_000_000_000L, CASH, WALLET);
        reads.post(MONTH.atDay(7), CUT, "JPY", 0, 9_000_000_000_000_000_000L, CASH, WALLET);
        // BHD's own lines at scale 2: the content says 2 - the data's scale, never a JDK currency table's 3 (M-3).
        reads.post(MONTH.atDay(8), CUT, "BHD", 2, 150, CASH, WALLET);
        String body = CanonicalJson.text(new TrialBalanceDefinition(reads).generate(SNAPSHOT,
                inputs(RunBasis.FINAL, Optional.of(pinned))));
        assertThat(body)
                .contains("{\"account\":\"1000\",\"classification\":\"ASSET\",\"closing\":{\"currency\":\"EUR\","
                        + "\"minor\":\"1150001\",\"scale\":\"3\"},\"credits\":{\"currency\":\"EUR\",\"minor\":\"0\","
                        + "\"scale\":\"3\"},\"currency\":\"EUR\",\"debits\":{\"currency\":\"EUR\",\"minor\":\"150001\","
                        + "\"scale\":\"3\"},\"lines\":\"3\",\"name\":\"Cash at bank\",\"normalBalance\":\"DEBIT\","
                        + "\"opening\":{\"currency\":\"EUR\",\"minor\":\"1000000\",\"scale\":\"3\"}}")
                .contains("{\"account\":\"2000\",\"classification\":\"LIABILITY\",\"closing\":{\"currency\":\"EUR\","
                        + "\"minor\":\"-1100000\",\"scale\":\"3\"}")
                .contains("\"minor\":\"18000000000000000000\",\"scale\":\"0\"")
                .contains("\"sign\":\"DEBIT_POSITIVE\"")
                .contains("\"range\":{\"from\":\"2097-06-01\",\"through\":\"2097-06-30\"}")
                .contains("\"periodBounds\":{\"from\":\"2097-06-01\",\"through\":\"2097-06-30\"}")
                .contains("{\"currency\":\"BHD\",\"minor\":\"150\",\"scale\":\"2\"}")
                .doesNotContain("\"currency\":\"BHD\",\"minor\":\"1500\",\"scale\":\"3\"")
                .contains("\"openingReclassified\":false,\"periodBounds\"").contains("\"previousPeriodPin\":null")
                .contains("\"verdict\":{\"balanced\":true,\"disagreeing\":[],\"equalsLedger\":true,\"fullyMapped\":true,"
                        + "\"openingBalanced\":true,\"outOfBalance\":[],\"passes\":true,\"periodBalanced\":true,"
                        + "\"rangeIsThePeriods\":true}")
                .doesNotContain(".0").doesNotContain("E+");
    }

    @Test
    @DisplayName("a plain run's content is what it always was (P14-TSK-015): the bytes of a FINAL and a PROVISIONAL run, with"
            + " no rate set, pinned by the hash computed on the code before translation existed - TRIAL_BALANCE stays version 1")
    void aPlainRunsContentIsUnchanged() {
        InMemoryTrialBalanceReads reads = books(PeriodStatus.LOCKED, Optional.of(pinned));
        reads.post(MONTH.atDay(5), CUT, "EUR", 3, 1, CASH, FEES);
        reads.post(MONTH.atDay(6), CUT, "JPY", 0, 9_000_000_000_000_000_000L, CASH, WALLET);
        reads.post(MONTH.atDay(7), CUT, "JPY", 0, 9_000_000_000_000_000_000L, CASH, WALLET);
        reads.post(MONTH.atDay(8), CUT, "BHD", 3, 1_501, CASH, WALLET);
        reads.post(MONTH.atDay(9), CUT, "USD", 2, 12_345, WALLET, FEES);
        reads.post(MONTH.atDay(10), CUT, "GBP", 2, 777, "UNCHARTED_PURPOSE/OPERATIONAL", CASH);
        ReportGeneration generation = new ReportGeneration((snapshot, id) -> Optional.of(
                new ReportGeneration.PeriodState(PeriodStatus.LOCKED, Optional.of(Instant.EPOCH))), Duration.ofMinutes(4),
                () -> 0L);
        ValuationRateReads untouched = (snapshot, id) -> {
            throw new AssertionError("a plain run reads no rate");
        };
        TrialBalanceDefinition definition = new TrialBalanceDefinition(reads, untouched);
        assertThat(generation.generate(SNAPSHOT, definition, inputs(RunBasis.FINAL, Optional.of(pinned))).sha256())
                .isEqualTo("fa8a8e52d286d5a2281f7b691b206d7740e69186d03744f6438b1be868fda957");
        assertThat(generation.generate(SNAPSHOT, definition, inputs(RunBasis.PROVISIONAL, Optional.of(pinned))).sha256())
                .isEqualTo("f63da63808b49befa09de119106f33557d3eee6b771aa634ccb7dfebdbb3b838");
        assertThat(TrialBalanceDefinition.VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("how a run asks (P14-TSK-015): the production definition may take a rate set and never needs one; one with"
            + " no way to the rates takes none - its claim refuses")
    void aRunAsksByNamingTheSet() {
        InMemoryTrialBalanceReads reads = new InMemoryTrialBalanceReads();
        TrialBalanceDefinition plain = new TrialBalanceDefinition(reads);
        assertThat(plain.takesValuationRateSet()).isFalse();
        TrialBalanceDefinition production = new TrialBalanceDefinition(reads, (snapshot, id) -> Optional.empty());
        assertThat(production.takesValuationRateSet()).isTrue();
        assertThat(production.needsValuationRateSet()).as("the close asks it plain").isFalse();
        assertThat(production.code()).isEqualTo(TrialBalanceDefinition.CODE);
        assertThat(production.version()).isEqualTo(TrialBalanceDefinition.VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("translation does not touch the trial balance: the translated body holds every plain member as it is, the"
            + " verdict the same bytes at the same path, each row its originals with a translated member beside them")
    void aTranslatedBodyKeepsThePlainOneWhole() {
        InMemoryTrialBalanceReads reads = mixedBooks();
        CanonicalJson.Obj plain = new TrialBalanceDefinition(reads).generate(SNAPSHOT,
                inputs(RunBasis.FINAL, Optional.of(pinned)));
        CanonicalJson.Obj translated = new TrialBalanceDefinition(reads, rates()).generate(SNAPSHOT, translatedInputs());
        assertThat(CanonicalJson.text(translated.members().get("verdict")))
                .isEqualTo(CanonicalJson.text(plain.members().get("verdict")))
                .contains("\"passes\":true");
        assertThat(translated.members().keySet()).containsAll(plain.members().keySet()).contains("translation")
                .hasSize(plain.members().size() + 1);
        for (String member : plain.members().keySet()) {
            if (!member.equals("accounts") && !member.equals("unmapped")) {
                assertThat(CanonicalJson.text(translated.members().get(member))).as(member)
                        .isEqualTo(CanonicalJson.text(plain.members().get(member)));
            }
        }
        for (String rows : List.of("accounts", "unmapped")) {
            List<CanonicalJson.Value> plainRows = ((CanonicalJson.Arr) plain.members().get(rows)).elements();
            List<CanonicalJson.Value> translatedRows = ((CanonicalJson.Arr) translated.members().get(rows)).elements();
            assertThat(translatedRows).hasSameSizeAs(plainRows).isNotEmpty();
            for (int i = 0; i < plainRows.size(); i++) {
                Map<String, CanonicalJson.Value> originals = ((CanonicalJson.Obj) plainRows.get(i)).members();
                Map<String, CanonicalJson.Value> beside = ((CanonicalJson.Obj) translatedRows.get(i)).members();
                assertThat(beside.keySet()).containsAll(originals.keySet()).contains("translated");
                originals.forEach((name, value) -> assertThat(CanonicalJson.text(beside.get(name))).as(name)
                        .isEqualTo(CanonicalJson.text(value)));
            }
        }
    }

    @Test
    @DisplayName("the translated content: each row's columns in EUR at two places beside its originals, past long included;"
            + " per currency the original total, the translated parts, the difference line and the translated total; the"
            + " set, every rate with its provenance, EUR's identity stated, the rounding named")
    void theTranslatedContentIsExact() {
        String body = CanonicalJson.text(new TrialBalanceDefinition(mixedBooks(), rates()).generate(SNAPSHOT,
                translatedInputs()));
        assertThat(body)
                // JPY's 18000000000000000000 at 131.25: 137142857142857142.857... rounds to ...142.86 - past long in minor.
                .contains("\"translated\":{\"closing\":{\"currency\":\"EUR\",\"minor\":\"-13714285714285714286\","
                        + "\"scale\":\"2\"},\"credits\":{\"currency\":\"EUR\",\"minor\":\"13714285714285714286\","
                        + "\"scale\":\"2\"},\"debits\":{\"currency\":\"EUR\",\"minor\":\"0\",\"scale\":\"2\"},"
                        + "\"opening\":{\"currency\":\"EUR\",\"minor\":\"0\",\"scale\":\"2\"}}")
                .contains("\"reportingCurrency\":\"EUR\"").contains("\"rounding\":\"HALF_EVEN\"")
                .contains("\"complete\":true").contains("\"untranslated\":[]")
                .contains("{\"basis\":\"IDENTITY\",\"currency\":\"EUR\",\"rate\":\"1\"}")
                .contains("{\"basis\":\"PINNED_RATE\",\"currency\":\"JPY\",\"observedAt\":\"2097-06-30T16:00:00Z\","
                        + "\"pair\":\"EUR/JPY\",\"rate\":\"131.2500000000\",\"snapshot\":\"" + SNAPSHOT_ID + "\","
                        + "\"source\":\"reference.test\"}")
                .contains("\"rateSet\":{\"asOf\":\"2097-07-01T00:00:00Z\",\"id\":\"" + SET + "\",\"maxAgeHours\":\"96\","
                        + "\"period\":\"2097-06\"}")
                .contains("\"translationDifference\":{\"currency\":\"EUR\"")
                .doesNotContain("E+");
    }

    @Test
    @DisplayName("a currency the pinned set does not value: its row keeps its originals, translated null, named untranslated,"
            + " the EUR totals withheld - never dropped, never at one; the run completes; the verdict does not move")
    void aCurrencyWithNoRateIsDisclosed() {
        InMemoryTrialBalanceReads reads = mixedBooks();
        reads.post(MONTH.atDay(11), CUT, "CHF", 2, 12_345, CASH, WALLET);
        String body = CanonicalJson.text(new TrialBalanceDefinition(reads, rates()).generate(SNAPSHOT, translatedInputs()));
        assertThat(body)
                .contains("\"complete\":false").contains("\"untranslated\":[\"CHF\"]")
                .contains("{\"basis\":\"NO_RATE\",\"columns\":{\"closing\":{\"original\":{\"currency\":\"CHF\","
                        + "\"minor\":\"0\",\"scale\":\"2\"},\"translatedParts\":null,\"translatedTotal\":null,"
                        + "\"translationDifference\":null}")
                .contains("\"total\":{\"closing\":null,\"credits\":null,\"debits\":null,\"opening\":null}")
                .contains("\"currency\":\"CHF\",\"debits\":{\"currency\":\"CHF\",\"minor\":\"12345\",\"scale\":\"2\"}")
                .contains("\"translated\":null")
                .contains("\"passes\":true");
    }

    @Test
    @DisplayName("through the report generation: the header names the pinned set, a regeneration is byte-identical, and a"
            + " PROVISIONAL run at its period's approved set names its cut - the set read by the same judged way")
    void theHeaderNamesThePinnedSet() {
        ReportGeneration generation = new ReportGeneration((snapshot, id) -> Optional.of(
                new ReportGeneration.PeriodState(PeriodStatus.LOCKED, Optional.of(Instant.EPOCH))), Duration.ofMinutes(4),
                () -> 0L);
        TrialBalanceDefinition definition = new TrialBalanceDefinition(mixedBooks(), rates());
        ReportGeneration.Generated first = generation.generate(SNAPSHOT, definition, translatedInputs());
        assertThat(first.content()).contains("\"valuationRateSet\":\"" + SET + "\"").contains("\"basis\":\"FINAL\"")
                .contains("\"definitionVersion\":\"1\"");
        ReportGeneration.Generated again = generation.generate(SNAPSHOT,
                new TrialBalanceDefinition(mixedBooks(), rates()), translatedInputs());
        assertThat(again.content()).isEqualTo(first.content());
        assertThat(again.sha256()).isEqualTo(first.sha256());
        ReportInputs provisional = new ReportInputs(periodId, MONTH, 1, RunBasis.PROVISIONAL, Optional.empty(),
                Optional.of(pinned), Optional.of(1), CUT, RunParameters.of(Map.of(), Optional.of(SET)));
        assertThat(generation.generate(SNAPSHOT, definition, provisional).content())
                .contains("\"basis\":\"PROVISIONAL\"").contains("\"cut\":\"" + CUT + "\"")
                .contains("\"translation\":{");
    }

    @Test
    @DisplayName("the snapshot's period is required: a run whose period row the snapshot does not hold fails the"
            + " generation - never a trial balance without the period's own bounds")
    void aMissingPeriodFailsTheGeneration() {
        InMemoryTrialBalanceReads reads = new InMemoryTrialBalanceReads().version(pinned, VERSION);
        assertThatThrownBy(() -> new TrialBalanceDefinition(reads).trialBalance(SNAPSHOT,
                inputs(RunBasis.PROVISIONAL, Optional.of(pinned))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not in the snapshot");
    }

    @Test
    @DisplayName("through the report generation: the header binds the question (TRIAL_BALANCE v1, the period, FINAL, the"
            + " pinned version, no cut) to the body, and a PROVISIONAL run names its cut")
    void theHeaderBindsTheQuestion() {
        InMemoryTrialBalanceReads reads = books(PeriodStatus.LOCKED, Optional.of(pinned));
        ReportGeneration generation = new ReportGeneration((snapshot, id) -> Optional.of(
                new ReportGeneration.PeriodState(PeriodStatus.LOCKED, Optional.of(Instant.EPOCH))), Duration.ofMinutes(4),
                () -> 0L);
        String content = generation.generate(SNAPSHOT, new TrialBalanceDefinition(reads),
                inputs(RunBasis.FINAL, Optional.of(pinned))).content();
        assertThat(content).startsWith("{\"basis\":\"FINAL\",\"body\":{")
                .contains("\"cut\":null,\"definition\":\"TRIAL_BALANCE\",\"definitionVersion\":\"1\"")
                .contains("\"mappingVersion\":\"1\"").contains("\"period\":\"2097-06\"");
        String provisional = generation.generate(SNAPSHOT, new TrialBalanceDefinition(reads),
                inputs(RunBasis.PROVISIONAL, Optional.of(pinned))).content();
        assertThat(provisional).contains("\"cut\":\"2097-06-20T10:00:00Z\"");
    }

    // ------------------------------------------------------------------

    /** The books: a period, the reference version as {@link #pinned}, three entries before the cut. */
    private InMemoryTrialBalanceReads books(PeriodStatus status, Optional<GlMappingVersionId> pin) {
        return new InMemoryTrialBalanceReads()
                .version(pinned, VERSION)
                .period(new TrialBalanceReads.Period(periodId, MONTH.atDay(1), MONTH.atEndOfMonth(), status, pin))
                .post(MONTH.minusMonths(1).atDay(28), CUT.minusSeconds(3600), "EUR", 2, 100_000, CASH, WALLET)
                .post(MONTH.atDay(1), CUT.minusSeconds(60), "EUR", 2, 10_000, CASH, WALLET)
                .post(MONTH.atEndOfMonth(), CUT, "EUR", 2, 5_000, CASH, FEES)
                .line(new InMemoryTrialBalanceReads.JournalLine(MONTH.atDay(2), CUT, "CUSTOMER_WALLET", "CUSTOMER",
                        "EUR", 2, Side.DEBIT, 1))
                .line(new InMemoryTrialBalanceReads.JournalLine(MONTH.atDay(2), CUT, "CUSTOMER_WALLET", "CUSTOMER",
                        "EUR", 2, Side.CREDIT, 1));
    }

    /** The base books plus four other currencies at three scales, an unmapped line among them and a sum past long. */
    private InMemoryTrialBalanceReads mixedBooks() {
        InMemoryTrialBalanceReads reads = books(PeriodStatus.LOCKED, Optional.of(pinned));
        reads.post(MONTH.atDay(5), CUT, "EUR", 3, 1, CASH, FEES);
        reads.post(MONTH.atDay(6), CUT, "JPY", 0, 9_000_000_000_000_000_000L, WALLET, FEES);
        reads.post(MONTH.atDay(7), CUT, "JPY", 0, 9_000_000_000_000_000_000L, WALLET, FEES);
        reads.post(MONTH.atDay(8), CUT, "BHD", 3, 1_501, CASH, WALLET);
        reads.post(MONTH.atDay(9), CUT, "USD", 2, 12_345, WALLET, FEES);
        reads.post(MONTH.atDay(10), CUT, "GBP", 2, 777, "UNCHARTED_PURPOSE/OPERATIONAL", CASH);
        return reads;
    }

    /** The approved set the translated inputs name. */
    private ValuationRateReads rates() {
        return (snapshot, id) -> Optional.of(new ValuationRateReads.PinnedSet(id, periodId, MONTH,
                ValuationRateSetStatus.APPROVED, Instant.parse("2097-07-01T00:00:00Z"), 96, List.of(
                        rate("GBP", "0.7012"), rate("USD", "1.1561"), rate("JPY", "131.25"), rate("BHD", "0.4358123456"))));
    }

    private static ValuationRate rate(String quote, String value) {
        return new ValuationRate(CurrencyCode.of("EUR"), CurrencyCode.of(quote), new BigDecimal(value), "reference.test",
                SNAPSHOT_ID, Instant.parse("2097-06-30T16:00:00Z"), Instant.parse("2097-06-30T16:00:01Z"));
    }

    private ReportInputs translatedInputs() {
        return new ReportInputs(periodId, MONTH, 1, RunBasis.FINAL, Optional.of(Instant.EPOCH), Optional.of(pinned),
                Optional.of(1), CUT, RunParameters.of(Map.of(), Optional.of(SET)));
    }

    private ReportInputs inputs(RunBasis basis, Optional<GlMappingVersionId> version) {
        return new ReportInputs(periodId, MONTH, 1, basis,
                basis == RunBasis.FINAL ? Optional.of(Instant.EPOCH) : Optional.empty(), version,
                version.map(ignored -> 1), CUT, RunParameters.none());
    }

    private static TrialBalance.AccountRow row(TrialBalance balance, String code, String currency) {
        return balance.accounts().stream()
                .filter(row -> row.account().code().equals(code) && row.currency().equals(currency))
                .findFirst().orElseThrow();
    }
}
