package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.bnpl.BnplApplicationId;
import com.finapp.bnpl.BnplIllustration;
import com.finapp.bnpl.BnplOffer;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplOfferStatus;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTermsVersionId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL offer doors' boundary, hermetic (`P12-TSK-011`): the decline's recorded receipt replays exactly what it
 * stored - the offer's id, product, status and closure, never a figure; the illustration answered as exact decimal
 * strings and labelled illustrative. The doors over a real schema are {@code BnplOfferApiTest}'s and {@code BnplOriginationDatabaseTest}'s.
 */
@DisplayName("the BNPL offer doors' boundary (P12-TSK-011)")
class BnplOfferDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("a decline's receipt replays the offer as it stood - id, product, status, closure - and no figure")
    void theReceiptReplays() {
        Instant closed = Instant.parse("2026-10-17T10:20:00Z");
        BnplOffer offer = new BnplOffer(BnplOfferId.next(IDS), BnplApplicationId.next(IDS), UUID.randomUUID(),
                BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, BnplOfferStatus.DECLINED, UUID.randomUUID(),
                BnplTermsVersionId.of(IDS.next()), 1, UUID.randomUUID(), Instant.parse("2026-10-17T10:10:00Z"),
                Instant.parse("2026-10-17T10:40:00Z"), Optional.of(closed), CorrelationId.of("p12-tsk-011-desk"));
        byte[] stored = BnplOfferDesk.stored(offer).body();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("EUR");
        BnplOfferDesk.BnplOfferDecision replayed = BnplOfferDesk.replayed(stored);
        assertThat(replayed).isEqualTo(new BnplOfferDesk.BnplOfferDecision(offer.id().value().toString(),
                "BNPL_PAY_IN_4", "DECLINED", closed.toString()));
    }

    @Test
    @DisplayName("the illustration is answered in exact decimal strings, labelled illustrative, every instalment listed")
    void theIllustrationView() {
        Money nothing = Money.zero(EUR);
        BnplIllustration illustration = new BnplIllustration("BNPL_SCHEDULE_ENGINE_V1", LocalDate.of(2026, 10, 17),
                Money.ofMinorUnits(100_03, EUR), List.of(
                        line(1, LocalDate.of(2026, 10, 17), 25_03, nothing),
                        line(2, LocalDate.of(2026, 10, 31), 25_00, nothing),
                        line(3, LocalDate.of(2026, 11, 14), 25_00, nothing),
                        line(4, LocalDate.of(2026, 11, 28), 25_00, nothing)));
        BnplOfferDesk.BnplIllustrationView view = BnplOfferDesk.illustration(illustration);
        assertThat(view.basis()).startsWith("ILLUSTRATIVE");
        assertThat(view.firstInstalment()).isEqualTo("25.03");
        assertThat(view.regularInstalment()).isEqualTo("25.00");
        assertThat(view.totalPayable()).isEqualTo("100.03");
        assertThat(view.totalInterest()).isEqualTo("0.00");
        assertThat(view.instalments()).hasSize(4);
        assertThat(view.lastDueDate()).isEqualTo("2026-11-28");
    }

    @Test
    @DisplayName("an offered application's read names its offer's id - how the customer finds the offer; a replayed"
            + " submission names none")
    void theApplicationNamesItsOffer() {
        com.finapp.bnpl.BnplApplication application = new com.finapp.bnpl.BnplApplication(BnplApplicationId.next(IDS),
                UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4,
                com.finapp.bnpl.BnplApplicationStatus.OFFERED, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BnplTermsVersionId.of(IDS.next()), 1, Optional.empty(), Instant.parse("2026-10-17T10:00:00Z"),
                Optional.of(Instant.parse("2026-10-17T10:10:00Z")), CorrelationId.of("p12-tsk-011-desk"));
        String offer = BnplOfferId.next(IDS).value().toString();
        assertThat(BnplApplicationDesk.view(application, List.of(), Optional.of(offer)).offerId()).isEqualTo(offer);
        assertThat(BnplApplicationDesk.view(application, List.of()).offerId()).isNull();
    }

    private static BnplIllustration.Line line(int sequence, LocalDate due, long minor, Money interest) {
        Money principal = Money.ofMinorUnits(minor, EUR);
        return new BnplIllustration.Line(sequence, due, principal, interest, principal.plus(interest));
    }
}
