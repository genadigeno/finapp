package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.DueItemId;
import com.finapp.lending.LoanAgreementId;
import com.finapp.lending.LoanAmendment;
import com.finapp.lending.LoanAmendmentStatus;
import com.finapp.lending.LoanId;
import com.finapp.lending.RepaymentReversal;
import com.finapp.lending.RepaymentReversalStatus;
import com.finapp.lending.Waiver;
import com.finapp.lending.WaiverKind;
import com.finapp.lending.WaiverStatus;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("loan servicing proposals name their proposer, and receipts stored before it replay with none (P17-TSK-116)")
class LoanProposerViewsTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final String PROPOSER = "agent-who-proposed";

    @Test
    @DisplayName("an amendment's view names its proposer, never its reason; its receipt replays it, an older one with none")
    void anAmendmentNamesItsProposer() {
        LoanAmendment amendment = new LoanAmendment(IDS.next(), LoanId.of(IDS.next()), LoanAgreementId.of(IDS.next()), 1,
                new BigDecimal("0.10"), new BigDecimal("0.08"), 12, false, new byte[32], LoanAmendmentStatus.PROPOSED,
                PROPOSER, AT, "hardship", false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        LoanAmendmentDesk.AmendmentView view = LoanAmendmentDesk.view(amendment);
        assertThat(view.proposedBy()).isEqualTo(PROPOSER);
        byte[] stored = LoanAmendmentDesk.stored(view).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("hardship");
        assertThat(LoanAmendmentDesk.replayed(stored)).isEqualTo(view);
        assertThat(LoanAmendmentDesk.replayed(before(stored)).proposedBy()).isNull();
    }

    @Test
    @DisplayName("a waiver's view names its proposer, never its reason; its receipt replays it, an older one with none")
    void aWaiverNamesItsProposer() {
        Waiver waiver = new Waiver(IDS.next(), LoanId.of(IDS.next()), WaiverKind.FEE, Optional.of(IDS.next()),
                DueItemId.of(IDS.next()), Money.ofMinorUnits(5_00, CurrencyCode.of("EUR")), 1, WaiverStatus.PROPOSED,
                PROPOSER, AT, "goodwill", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        LoanWaiverDesk.WaiverView view = LoanWaiverDesk.view(waiver);
        assertThat(view.proposedBy()).isEqualTo(PROPOSER);
        byte[] stored = LoanWaiverDesk.stored(view).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("goodwill");
        assertThat(LoanWaiverDesk.replayed(stored)).isEqualTo(view);
        assertThat(LoanWaiverDesk.replayed(before(stored)).proposedBy()).isNull();
    }

    @Test
    @DisplayName("a repayment reversal's view names its proposer, never its reason; its receipt replays it, an older one"
            + " with none")
    void aReversalNamesItsProposer() {
        RepaymentReversal reversal = new RepaymentReversal(IDS.next(), LoanId.of(IDS.next()), IDS.next(), 1,
                RepaymentReversalStatus.PROPOSED, PROPOSER, AT, "wrong loan", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        LoanServicingDesk.ReversalView view = LoanServicingDesk.view(reversal);
        assertThat(view.proposedBy()).isEqualTo(PROPOSER);
        byte[] stored = LoanServicingDesk.stored(view).body();
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("wrong loan");
        assertThat(LoanServicingDesk.replayed(stored)).isEqualTo(view);
        assertThat(LoanServicingDesk.replayed(before(stored)).proposedBy()).isNull();
    }

    /** The receipt as a release before `P17-TSK-116` stored it: without the trailing proposer. */
    private static byte[] before(byte[] stored) {
        String text = new String(stored, StandardCharsets.UTF_8);
        return text.substring(0, text.lastIndexOf('|')).getBytes(StandardCharsets.UTF_8);
    }
}
