package com.finapp.accounting.reporting;

import static com.finapp.accounting.testing.ReportBodies.minor;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.row;
import static com.finapp.accounting.testing.ReportBodies.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.SuspenseAsOfCutoff;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.testing.InMemoryTrialBalanceReads;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code SUSPENSE} (`P14-TSK-017`; {@code INV-ACC-04}, {@code INV-ACC-05}, {@code INV-REC-05}): the ledger's balance, the register as
 * of the cutoff beside it, gross, never netted, and a FINAL body that reads only the frozen port - what the port itself reads is
 * {@code ReconciliationMonthReadsTest}'s; a later adoption, release or resolution against a real register is {@code
 * OperationalReportsDatabaseTest}'s (owed, C5).
 */
@DisplayName("SUSPENSE (P14-TSK-017)")
class SuspenseDefinitionTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(
            SuspenseDefinitionTest.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                throw new UnsupportedOperationException("a report reads only through its ports");
            });
    private static final YearMonth MONTH = YearMonth.of(2097, 6);
    private static final Instant EARLY = Instant.parse("2097-06-01T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2097-07-01T00:00:00Z");
    private static final Instant LOCK = Instant.parse("2097-07-05T10:00:00Z");
    private static final String SUSPENSE = "SUSPENSE_UNMATCHED/SUSPENSE";
    private static final String CASH = "CASH_AT_BANK/OPERATIONAL";

    private final AccountingPeriodId periodId = AccountingPeriodId.next(IDS);
    private final GlMappingVersionId pinned = GlMappingVersionId.next(IDS);

    @Test
    @DisplayName("the definition: SUSPENSE, version 1, no rate set, FINAL - not operational: its register read is frozen")
    void theDefinition() {
        SuspenseDefinition definition = new SuspenseDefinition(books(), (snapshot, period) -> register());
        assertThat(definition.code()).isEqualTo(ReportKind.SUSPENSE.name());
        assertThat(definition.version()).isEqualTo(1);
        assertThat(definition.operational()).isFalse();
        assertThat(definition.takesValuationRateSet()).isFalse();
    }

    @Test
    @DisplayName("the ledger's balance equals its lines; the register's items stand beside it, CREDIT and DEBIT gross, per currency and scale,"
            + " never netted, with its ageing and its instant")
    void theLedgerAndTheRegisterApart() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(1).minusDays(3), EARLY, "EUR", 2, 1_000, CASH, SUSPENSE);
        reads.post(MONTH.atDay(5), EARLY, "EUR", 2, 400, SUSPENSE, CASH);
        reads.post(MONTH.atDay(6), EARLY, "EUR", 2, 90, CASH, SUSPENSE);
        CanonicalJson.Obj body = new SuspenseDefinition(reads, (snapshot, period) -> register())
                .generate(SNAPSHOT, inputs());

        CanonicalJson.Obj ledger = row(body, "SUSPENSE_UNMATCHED", "EUR").orElseThrow();
        assertThat(minor(ledger, "opening")).isEqualTo(BigInteger.valueOf(-1_000));
        assertThat(minor(ledger, "debits")).isEqualTo(BigInteger.valueOf(400));
        assertThat(minor(ledger, "credits")).isEqualTo(BigInteger.valueOf(90));
        assertThat(minor(ledger, "closing")).isEqualTo(BigInteger.valueOf(-690));

        CanonicalJson.Obj register = object(body, "register");
        assertThat(text(register, "asOf")).isEqualTo(AS_OF.toString());
        assertThat(text(register, "cutoff")).isEqualTo("2097-06-30");
        CanonicalJson.Obj eur = objects(register, "groups").get(0);
        assertThat(text(eur, "creditItems")).isEqualTo("2");
        assertThat(text(eur, "creditUnreleasedMinor")).isEqualTo("600");
        assertThat(text(eur, "debitItems")).isEqualTo("1");
        assertThat(text(eur, "debitUnreleasedMinor")).as("gross: a debit item never nets a credit item").isEqualTo("90");
        assertThat(text(object(eur, "ageing"), "over-30")).isEqualTo("1");
        assertThat(text(eur, "oldestOpenedOn")).isEqualTo("2097-05-01");
        assertThat(CanonicalJson.text(body)).contains(CanonicalJson.text(
                new SuspenseDefinition(reads, (snapshot, period) -> register()).statement().asValue()));
    }

    @Test
    @DisplayName("INV-ACC-04: the same frozen register and journal, the same bytes - a FINAL run regenerates identical")
    void regeneratesIdentical() {
        InMemoryTrialBalanceReads reads = books();
        reads.post(MONTH.atDay(5), EARLY, "EUR", 2, 400, SUSPENSE, CASH);
        SuspenseDefinition definition = new SuspenseDefinition(reads, (snapshot, period) -> register());
        assertThat(CanonicalJson.text(definition.generate(SNAPSHOT, inputs())))
                .isEqualTo(CanonicalJson.text(definition.generate(SNAPSHOT, inputs())));
    }

    @Test
    @DisplayName("a register that cannot be read fails the report - never an empty register beside the ledger's figure")
    void anUnreadableRegisterFails() {
        SuspenseDefinition definition = new SuspenseDefinition(books(), (snapshot, period) -> {
            throw new IllegalStateException("the register is away");
        });
        assertThatThrownBy(() -> definition.generate(SNAPSHOT, inputs())).isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------

    private static SuspenseAsOfCutoff.Register register() {
        TreeMap<String, Long> ageing = new TreeMap<>();
        ageing.put("0-2", 2L);
        ageing.put("over-30", 1L);
        return new SuspenseAsOfCutoff.Register(LocalDate.of(2097, 6, 30), AS_OF, List.of(new SuspenseAsOfCutoff.Group("EUR", 2, 2,
                BigInteger.valueOf(600), 1, BigInteger.valueOf(90), ageing, Optional.of(LocalDate.of(2097, 5, 1)))));
    }

    private InMemoryTrialBalanceReads books() {
        return new InMemoryTrialBalanceReads()
                .period(new TrialBalanceReads.Period(periodId, MONTH.atDay(1), MONTH.atEndOfMonth(), PeriodStatus.LOCKED,
                        Optional.of(pinned)))
                .version(pinned, ReferenceGlMapping.version(YearMonth.of(2097, 1)));
    }

    private ReportInputs inputs() {
        return new ReportInputs(periodId, MONTH, 1, RunBasis.FINAL, Optional.of(LOCK), Optional.of(pinned), Optional.of(1), LOCK,
                RunParameters.none());
    }
}
