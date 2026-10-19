package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplCorrectionStatus;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplRepaymentReversal;
import com.finapp.bnpl.BnplWaiver;
import com.finapp.bnpl.BnplWaiverKind;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The correction doors' boundary, hermetic (`P12-TSK-024`): the receipts a retried key replays - the machine, the target
 * and the entry, never a reason ({@code INV-IDEM-01}, {@code INV-AUD-02}); the views closed against a reason or a party;
 * the waiver's amount exact at the currency's minor units; and the two meters' closed, eager series. The doors' routes and
 * permissions are {@code RoutePermissionRegisterTest}'s; the domain's acts are the bnpl suites'.
 */
@DisplayName("the BNPL correction doors' boundary (P12-TSK-024)")
class BnplCorrectionDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("a reversal's receipt replays it - proposed and approved - and never carries a reason")
    void theReversalsReceiptReplays() {
        BnplRepaymentReversal proposed = reversal(BnplCorrectionStatus.PROPOSED);
        BnplCorrectionDesk.BnplReversalView view = BnplCorrectionDesk.view(proposed);
        assertThat(BnplCorrectionDesk.replayedReversal(BnplCorrectionDesk.stored(view).body())).isEqualTo(view);
        assertThat(view.journalEntryId()).isNull();
        BnplRepaymentReversal approved = reversal(BnplCorrectionStatus.APPROVED);
        BnplCorrectionDesk.BnplReversalView decided = BnplCorrectionDesk.view(approved);
        byte[] stored = BnplCorrectionDesk.stored(decided).body();
        assertThat(BnplCorrectionDesk.replayedReversal(stored)).isEqualTo(decided);
        assertThat(decided.valueDate()).isEqualTo("2026-11-15");
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("wrong agreement").doesNotContain("checked");
    }

    @Test
    @DisplayName("a waiver's receipt replays it with its amount as an exact decimal and its closure - never a reason")
    void theWaiversReceiptReplays() {
        BnplWaiver approved = waiver(BnplCorrectionStatus.APPROVED);
        BnplCorrectionDesk.BnplWaiverView view = BnplCorrectionDesk.view(approved, "REPAID");
        byte[] stored = BnplCorrectionDesk.stored(view).body();
        assertThat(BnplCorrectionDesk.replayedWaiver(stored)).isEqualTo(view);
        assertThat(view.amount()).isEqualTo("5.00");
        assertThat(view.closure()).isEqualTo("REPAID");
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("goodwill");
        BnplCorrectionDesk.BnplWaiverView proposed = BnplCorrectionDesk.view(waiver(BnplCorrectionStatus.PROPOSED), null);
        assertThat(BnplCorrectionDesk.replayedWaiver(BnplCorrectionDesk.stored(proposed).body())).isEqualTo(proposed);
    }

    @Test
    @DisplayName("the views are closed: no member names a reason, a party, a wallet or a proposer")
    void theViewsAreClosed() {
        for (Class<?> view : List.of(BnplCorrectionDesk.BnplReversalView.class, BnplCorrectionDesk.BnplWaiverView.class)) {
            assertThat(Arrays.stream(view.getRecordComponents()).map(RecordComponent::getName))
                    .noneMatch(name -> name.contains("reason") || name.contains("party") || name.contains("wallet")
                            || name.contains("proposedBy") || name.contains("decidedBy"));
        }
    }

    @Test
    @DisplayName("the waiver's amount is exact at the currency's minor units; an exponent, a sub-cent amount or an unknown"
            + " currency is 422 before anything")
    void theAmountIsExact() {
        assertThat(BnplCorrectionDesk.amount("5.00", "EUR")).isEqualTo(Money.ofMinorUnits(5_00, EUR));
        for (String[] invalid : List.of(new String[] {"1e3", "EUR"}, new String[] {"1.001", "EUR"},
                new String[] {"1.00", "XXQ"})) {
            assertThatThrownBy(() -> BnplCorrectionDesk.amount(invalid[0], invalid[1])).isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("both meters register every outcome eagerly, one series per outcome, and count once per act")
    void theMetersAreEagerAndClosed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BnplCorrectionMetrics metrics = new BnplCorrectionMetrics(registry);
        assertThat(registry.find(BnplCorrectionMetrics.REVERSAL).counters())
                .hasSize(BnplCorrectionMetrics.ReversalOutcome.values().length);
        assertThat(registry.find(BnplCorrectionMetrics.WAIVER).counters())
                .hasSize(BnplCorrectionMetrics.WaiverOutcome.values().length);
        metrics.record(BnplCorrectionMetrics.ReversalOutcome.APPROVED);
        metrics.record(BnplCorrectionMetrics.WaiverOutcome.EXCEEDS_DUE);
        assertThat(registry.get(BnplCorrectionMetrics.REVERSAL).tags("outcome", "approved").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(BnplCorrectionMetrics.WAIVER).tags("outcome", "exceeds_due").counter().count())
                .isEqualTo(1.0);
    }

    private static BnplRepaymentReversal reversal(BnplCorrectionStatus status) {
        boolean decided = status != BnplCorrectionStatus.PROPOSED;
        boolean approved = status == BnplCorrectionStatus.APPROVED;
        return new BnplRepaymentReversal(IDS.next(), BnplObligationId.next(IDS), IDS.next(), BnplAgreementId.next(IDS), 1,
                status, "operator-a", Instant.parse("2026-11-15T10:00:00Z"), "applied to the wrong agreement",
                decided ? Optional.of("operator-b") : Optional.empty(),
                decided ? Optional.of(Instant.parse("2026-11-15T11:00:00Z")) : Optional.empty(),
                decided ? Optional.of("checked") : Optional.empty(),
                approved ? Optional.of(JournalEntryId.of(IDS.next())) : Optional.empty(),
                approved ? Optional.of(LocalDate.of(2026, 11, 15)) : Optional.empty());
    }

    private static BnplWaiver waiver(BnplCorrectionStatus status) {
        boolean decided = status != BnplCorrectionStatus.PROPOSED;
        boolean approved = status == BnplCorrectionStatus.APPROVED;
        return new BnplWaiver(IDS.next(), BnplObligationId.next(IDS), BnplWaiverKind.FEE, IDS.next(), IDS.next(),
                Money.ofMinorUnits(5_00, EUR), BnplAgreementId.next(IDS), 1, status, "operator-a",
                Instant.parse("2026-11-15T10:00:00Z"), "goodwill", decided ? Optional.of("operator-b") : Optional.empty(),
                decided ? Optional.of(Instant.parse("2026-11-15T11:00:00Z")) : Optional.empty(),
                decided ? Optional.of("upheld") : Optional.empty(),
                approved ? Optional.of(JournalEntryId.of(IDS.next())) : Optional.empty());
    }
}
