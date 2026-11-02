package com.finapp.accounting.reporting;

import static com.finapp.accounting.testing.ReportBodies.flag;
import static com.finapp.accounting.testing.ReportBodies.isNull;
import static com.finapp.accounting.testing.ReportBodies.minor;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.scale;
import static com.finapp.accounting.testing.ReportBodies.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlAccount;
import com.finapp.accounting.GlClassification;
import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.Side;
import com.finapp.accounting.StatementSection;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code GL_ACTIVITY} and the reclassification bridge (`P14-TSK-017`; {@code INV-ACC-02}, {@code INV-ACC-04}, {@code INV-ACC-06};
 * `P14-TSK-009`'s review, M-5; `P14-TSK-015`'s review, M-6): per GL account and currency the period's movement, with its components,
 * every unmapped line explicit, at the data's scale; and when the previous period pins another version, what the change moved.
 */
@DisplayName("GL_ACTIVITY and the reclassification bridge (P14-TSK-017)")
class GlActivityDefinitionTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            GlActivityDefinitionTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("a report reads only through its port");
            });
    private static final YearMonth MONTH = YearMonth.of(2097, 6);
    private static final Instant EARLY = Instant.parse("2097-06-01T00:00:00Z");
    private static final Instant CUT = Instant.parse("2097-07-05T10:00:00Z");
    private static final GlMappingVersion VERSION = ReferenceGlMapping.version(YearMonth.of(2097, 1));
    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";
    private static final String FEES = "FEE_REVENUE/OPERATIONAL";
    private static final String LOAN_PRINCIPAL = "LOAN_PRINCIPAL/LOAN";
    private static final String LOAN_DUE = "LOAN_PRINCIPAL_DUE/LOAN";

    private final AccountingPeriodId periodId = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId pinned = GlMappingVersionId.next(IDS);

    @Test
    @DisplayName("the definition: its ReportKind's name, version 1, no rate set, FINAL by default")
    void theDefinition() {
        GlActivityDefinition definition = new GlActivityDefinition(books(Optional.empty()));
        assertThat(definition.code()).isEqualTo(ReportKind.GL_ACTIVITY.name());
        assertThat(definition.version()).isEqualTo(1);
        assertThat(definition.takesValuationRateSet()).isFalse();
        assertThat(definition.operational()).isFalse();
    }

    @Test
    @DisplayName("per GL account and currency: the movement, its components by purpose summing to it, the line count, each currency's own"
            + " totals balanced; only the month's lines")
    void perAccountAndCurrency() {
        InMemoryTrialBalanceReads reads = books(Optional.empty());
        reads.post(MONTH.atDay(1).minusDays(1), EARLY, "EUR", 2, 9_999, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atEndOfMonth().plusDays(1), EARLY, "EUR", 2, 9_999, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(1), EARLY, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atEndOfMonth(), EARLY, "EUR", 2, 50, LOAN_DUE, LOAN_PRINCIPAL);
        reads.post(MONTH.atDay(3), EARLY, "BHD", 3, 7_000, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj body = new GlActivityDefinition(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));

        CanonicalJson.Obj loans = account(body, "1300", "EUR");
        assertThat(text(loans, "name")).isEqualTo("Loans - principal");
        assertThat(minor(loans, "debits")).isEqualTo(BigInteger.valueOf(150));
        assertThat(minor(loans, "credits")).isEqualTo(BigInteger.valueOf(50));
        assertThat(text(loans, "lines")).isEqualTo("3");
        assertThat(objects(loans, "components")).extracting(component -> text(component, "purpose"))
                .containsExactly("LOAN_PRINCIPAL", "LOAN_PRINCIPAL_DUE");
        assertThat(minor(account(body, "1300", "BHD"), "debits")).isEqualTo(BigInteger.valueOf(7_000));
        assertThat(scale(account(body, "1300", "BHD"), "debits")).isEqualTo(3);

        List<CanonicalJson.Obj> currencies = objects(body, "currencies");
        assertThat(currencies).extracting(currency -> text(currency, "currency")).containsExactly("BHD", "EUR");
        assertThat(currencies).allSatisfy(currency -> assertThat(flag(currency, "balanced")).isTrue());
        assertThat(flag(body, "fullyMapped")).isTrue();
        assertThat(isNull(body, "reclassification")).as("no version change, no bridge").isTrue();
    }

    @Test
    @DisplayName("every line the version does not map is an explicit row - never dropped; with no version, all of them")
    void unmappedIsExplicit() {
        InMemoryTrialBalanceReads reads = new InMemoryTrialBalanceReads().period(new TrialBalanceReads.Period(periodId,
                MONTH.atDay(1), MONTH.atEndOfMonth(), PeriodStatus.LOCKED, Optional.of(pinned)));
        reads.version(pinned, ReferenceGlMapping.without(YearMonth.of(2097, 1), Set.of("FEE_REVENUE")));
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 25, CASH, FEES);
        CanonicalJson.Obj body = new GlActivityDefinition(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(flag(body, "fullyMapped")).isFalse();
        CanonicalJson.Obj gap = objects(body, "unmapped").get(0);
        assertThat(text(gap, "purpose")).isEqualTo("FEE_REVENUE");
        assertThat(text(gap, "currency")).isEqualTo("EUR");
        assertThat(minor(gap, "credits")).isEqualTo(BigInteger.valueOf(25));
        assertThat(flag(objects(body, "currencies").get(0), "balanced"))
                .as("the unmapped row stays in the currency's totals").isTrue();
    }

    @Test
    @DisplayName("M-6: the data's scale, never GeneralLedger.zero's JDK table - JPY held at 2 is written at 2")
    void theDatasScale() {
        InMemoryTrialBalanceReads reads = books(Optional.empty());
        reads.post(MONTH.atDay(3), EARLY, "JPY", 2, 150, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj body = new GlActivityDefinition(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(scale(account(body, "1300", "JPY"), "credits")).as("an empty side at the data's scale").isEqualTo(2);
        assertThat(text(objects(body, "currencies").get(0), "scale")).isEqualTo("2");
    }

    @Test
    @DisplayName("the pin: a FINAL run under another version is refused; INV-ACC-04: byte-identical whatever order the journal serves")
    void thePinAndTheBytes() {
        InMemoryTrialBalanceReads forward = books(Optional.empty());
        forward.post(MONTH.atDay(1), EARLY, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        forward.post(MONTH.atDay(2), EARLY, "USD", 2, 70, CASH, FEES);
        InMemoryTrialBalanceReads backward = books(Optional.empty());
        backward.post(MONTH.atDay(2), EARLY, "USD", 2, 70, CASH, FEES);
        backward.post(MONTH.atDay(1), EARLY, "EUR", 2, 100, LOAN_PRINCIPAL, CASH);
        assertThat(CanonicalJson.text(new GlActivityDefinition(forward).generate(SNAPSHOT, inputs(RunBasis.FINAL))))
                .isEqualTo(CanonicalJson.text(new GlActivityDefinition(backward).generate(SNAPSHOT, inputs(RunBasis.FINAL))));

        GlMappingVersionId other = GlMappingVersionId.next(IDS);
        forward.version(other, VERSION);
        assertThatThrownBy(() -> new GlActivityDefinition(forward).generate(SNAPSHOT, new ReportInputs(periodId, MONTH, 1,
                RunBasis.FINAL, Optional.of(CUT), Optional.of(other), Optional.of(2), CUT, RunParameters.none())))
                .isInstanceOf(TrialBalanceDefinition.PinMismatch.class);
    }

    @Test
    @DisplayName("the bridge (P14-TSK-009's review, M-5): the previous period pins another version - the balance each purpose carried across,"
            + " per account the previous closing beside this opening, footing to zero per currency; the movement untouched")
    void theBridge() {
        GlMappingVersionId previous = GlMappingVersionId.next(IDS);
        InMemoryTrialBalanceReads reads = books(Optional.of(previous));
        reads.version(previous, ReferenceGlMapping.version(YearMonth.of(2097, 1)));
        GlAccount extra = new GlAccount("1399", "Loans - principal, restated", GlClassification.ASSET,
                StatementSection.LOANS_AND_BNPL_RECEIVABLES);
        reads.version(pinned, ReferenceGlMapping.remapped(YearMonth.of(2097, 1), "LOAN_PRINCIPAL", extra));
        reads.post(MONTH.atDay(1).minusDays(5), EARLY, "EUR", 2, 1_000, LOAN_PRINCIPAL, CASH);
        reads.post(MONTH.atDay(1).minusDays(4), EARLY, "EUR", 2, 300, LOAN_DUE, CASH);
        reads.post(MONTH.atDay(3), EARLY, "EUR", 2, 40, LOAN_PRINCIPAL, CASH);
        CanonicalJson.Obj body = new GlActivityDefinition(reads).generate(SNAPSHOT, inputs(RunBasis.FINAL));

        assertThat(minor(account(body, "1399", "EUR"), "debits")).as("the movement under this period's version")
                .isEqualTo(BigInteger.valueOf(40));
        CanonicalJson.Obj bridge = object(body, "reclassification");
        assertThat(text(bridge, "previousPeriodPin")).isEqualTo(previous.value().toString());
        assertThat(text(bridge, "version")).isEqualTo(pinned.value().toString());
        assertThat(flag(bridge, "footsToZero")).isTrue();
        CanonicalJson.Obj move = objects(bridge, "moves").get(0);
        assertThat(objects(bridge, "moves")).hasSize(1);
        assertThat(text(move, "purpose")).isEqualTo("LOAN_PRINCIPAL");
        assertThat(text(move, "from")).isEqualTo("1300");
        assertThat(text(move, "to")).isEqualTo("1399");
        assertThat(minor(move, "balance")).as("the balance carried, not the movement").isEqualTo(BigInteger.valueOf(1_000));
        List<CanonicalJson.Obj> accounts = objects(bridge, "accounts");
        assertThat(accounts).extracting(account -> text(account, "account")).containsExactly("1300", "1399");
        assertThat(minor(accounts.get(0), "previousClosing")).isEqualTo(BigInteger.valueOf(1_300));
        assertThat(minor(accounts.get(0), "opening")).isEqualTo(BigInteger.valueOf(300));
        assertThat(minor(accounts.get(0), "difference")).isEqualTo(BigInteger.valueOf(-1_000));
        assertThat(minor(accounts.get(1), "difference")).isEqualTo(BigInteger.valueOf(1_000));
    }

    @Test
    @DisplayName("the walk from inception is paid only for a version change: the same version, or none before it, reads no opening")
    void theOpeningIsReadOnlyForABridge() {
        InMemoryTrialBalanceReads held = books(Optional.of(pinned));
        Counting counting = new Counting(held);
        new GlActivityDefinition(counting).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(counting.befores).as("the previous period pins this version").isZero();
        Counting none = new Counting(books(Optional.empty()));
        new GlActivityDefinition(none).generate(SNAPSHOT, inputs(RunBasis.FINAL));
        assertThat(none.befores).as("no previous pin").isZero();
    }

    @Test
    @DisplayName("Reclassification.between: unmapped under either version is the bucket, no version at all moves every balance out, a zero"
            + " balance moves nothing, and two currencies foot apart")
    void betweenTheVersions() {
        GlMappingVersionId previous = GlMappingVersionId.next(IDS);
        GlMappingVersion mapped = ReferenceGlMapping.version(YearMonth.of(2097, 1));
        Reclassification noVersion = Reclassification.between(previous, mapped, Optional.empty(), Optional.empty(), List.of(
                aggregate("LOAN_PRINCIPAL", "LOAN", "EUR", Side.DEBIT, 500),
                aggregate("CASH_AT_BANK", "OPERATIONAL", "EUR", Side.CREDIT, 500),
                aggregate("FEE_REVENUE", "OPERATIONAL", "USD", Side.DEBIT, 10),
                aggregate("FEE_REVENUE", "OPERATIONAL", "USD", Side.CREDIT, 10)));
        assertThat(noVersion.moves()).extracting(Reclassification.Move::purpose)
                .containsExactly("CASH_AT_BANK", "LOAN_PRINCIPAL");
        assertThat(noVersion.moves()).allSatisfy(move -> assertThat(move.to()).isEmpty());
        assertThat(noVersion.footsToZero()).isTrue();
        assertThat(noVersion.accounts()).as("the balances left their accounts; the unmapped bucket nets to nothing, so it is no row")
                .extracting(Reclassification.Bridge::account).containsExactly(Optional.of("1000"), Optional.of("1300"));

        GlMappingVersion withoutFees = ReferenceGlMapping.without(YearMonth.of(2097, 1), Set.of("FEE_REVENUE"));
        Reclassification unmapped = Reclassification.between(previous, mapped, Optional.of(pinned), Optional.of(withoutFees),
                List.of(aggregate("FEE_REVENUE", "OPERATIONAL", "EUR", Side.CREDIT, 75),
                        aggregate("CASH_AT_BANK", "OPERATIONAL", "EUR", Side.DEBIT, 75)));
        assertThat(unmapped.moves()).singleElement().satisfies(move -> {
            assertThat(move.from()).contains("4000");
            assertThat(move.to()).isEmpty();
            assertThat(move.balance()).isEqualByComparingTo(new BigDecimal("-0.75"));
        });
        assertThat(unmapped.footsToZero()).isTrue();

        assertThatThrownBy(() -> Reclassification.between(previous, mapped, Optional.of(pinned), Optional.empty(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private InMemoryTrialBalanceReads books(Optional<GlMappingVersionId> previousPin) {
        return new InMemoryTrialBalanceReads()
                .period(new TrialBalanceReads.Period(periodId, MONTH.atDay(1), MONTH.atEndOfMonth(), PeriodStatus.LOCKED,
                        Optional.of(pinned), previousPin))
                .version(pinned, VERSION);
    }

    private ReportInputs inputs(RunBasis basis) {
        return new ReportInputs(periodId, MONTH, 1, basis, basis == RunBasis.FINAL ? Optional.of(CUT) : Optional.empty(),
                Optional.of(pinned), Optional.of(1), CUT, RunParameters.none());
    }

    private static CanonicalJson.Obj account(CanonicalJson.Obj body, String code, String currency) {
        return objects(body, "accounts").stream()
                .filter(row -> text(row, "account").equals(code) && text(row, "currency").equals(currency))
                .findFirst().orElseThrow(() -> new AssertionError("no row " + code + " " + currency));
    }

    private static JournalReads.Aggregate aggregate(String purpose, String ownerKind, String currency, Side side, long minor) {
        return new JournalReads.Aggregate(purpose, ownerKind, Optional.empty(), currency, 2, side, BigInteger.valueOf(minor), 1);
    }

    /** The reads, counting the walk from inception. */
    private static final class Counting implements TrialBalanceReads {
        private final TrialBalanceReads reads;
        private int befores;

        Counting(TrialBalanceReads reads) {
            this.reads = reads;
        }

        @Override
        public Optional<Period> period(Connection snapshot, AccountingPeriodId id) {
            return reads.period(snapshot, id);
        }

        @Override
        public GlMappingVersion version(Connection snapshot, GlMappingVersionId id) {
            return reads.version(snapshot, id);
        }

        @Override
        public List<JournalReads.Aggregate> before(Connection snapshot, java.time.LocalDate before, Optional<Instant> cut) {
            befores++;
            return reads.before(snapshot, before, cut);
        }

        @Override
        public List<JournalReads.Aggregate> within(Connection snapshot, JournalReads.Range range, Optional<Instant> cut) {
            return reads.within(snapshot, range, cut);
        }

        @Override
        public List<LedgerWindows> ledger(Connection snapshot, Period period, Optional<Instant> cut) {
            return reads.ledger(snapshot, period, cut);
        }
    }
}
