package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision request's machine and terms (`P10-TSK-014`; CREDIT_DECISIONING_LIFECYCLES.md section 3.1,
 * {@code INV-LIFE-01}, {@code INV-CRD-12}): the aggregate's half of the three layers, swept exhaustively against the
 * lifecycle document's table, and the product's bounds at their edges.
 */
@DisplayName("the decision request's machine and terms (P10-TSK-014)")
class DecisionRequestTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** The lifecycle document's edges, written apart from the enum. */
    private static final Map<DecisionRequestStatus, Set<DecisionRequestStatus>> DOCUMENTED = Map.of(
            DecisionRequestStatus.SUBMITTED, EnumSet.of(DecisionRequestStatus.COLLECTING, DecisionRequestStatus.CANCELLED,
                    DecisionRequestStatus.EXPIRED, DecisionRequestStatus.ABANDONED),
            DecisionRequestStatus.COLLECTING, EnumSet.of(DecisionRequestStatus.READY, DecisionRequestStatus.CANCELLED,
                    DecisionRequestStatus.EXPIRED, DecisionRequestStatus.ABANDONED),
            DecisionRequestStatus.READY, EnumSet.of(DecisionRequestStatus.COLLECTING, DecisionRequestStatus.EVALUATED,
                    DecisionRequestStatus.CANCELLED, DecisionRequestStatus.EXPIRED, DecisionRequestStatus.ABANDONED),
            DecisionRequestStatus.EVALUATED, EnumSet.of(DecisionRequestStatus.DECIDED, DecisionRequestStatus.IN_REVIEW,
                    DecisionRequestStatus.EXPIRED, DecisionRequestStatus.ABANDONED),
            DecisionRequestStatus.IN_REVIEW, EnumSet.of(DecisionRequestStatus.DECIDED, DecisionRequestStatus.EXPIRED,
                    DecisionRequestStatus.ABANDONED),
            DecisionRequestStatus.DECIDED, EnumSet.noneOf(DecisionRequestStatus.class),
            DecisionRequestStatus.CANCELLED, EnumSet.noneOf(DecisionRequestStatus.class),
            DecisionRequestStatus.EXPIRED, EnumSet.noneOf(DecisionRequestStatus.class),
            DecisionRequestStatus.ABANDONED, EnumSet.noneOf(DecisionRequestStatus.class));

    @Test
    @DisplayName("every (from, to) pair: permitted exactly when the lifecycle document lists it")
    void everyPairAgainstTheDocument() {
        for (DecisionRequestStatus from : DecisionRequestStatus.values()) {
            for (DecisionRequestStatus to : DecisionRequestStatus.values()) {
                assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(DOCUMENTED.get(from).contains(to));
            }
        }
        assertThat(DecisionRequestStatus.OPEN).containsExactlyInAnyOrder(DecisionRequestStatus.SUBMITTED,
                DecisionRequestStatus.COLLECTING, DecisionRequestStatus.READY, DecisionRequestStatus.EVALUATED,
                DecisionRequestStatus.IN_REVIEW);
        for (DecisionRequestStatus status : DecisionRequestStatus.values()) {
            assertThat(status.open()).as(status.name()).isEqualTo(!status.permittedTransitions().isEmpty());
        }
        assertThat(DecisionRequestStatus.CANCELLABLE).allSatisfy(
                status -> assertThat(status.canTransitionTo(DecisionRequestStatus.CANCELLED)).isTrue());
    }

    @Test
    @DisplayName("the bounds at their edges, for both products: the minimum and maximum accepted, one minor unit beyond refused")
    void theBoundsAtTheirEdges() {
        for (CreditProduct product : CreditProduct.values()) {
            Optional<Integer> term = product.termBounds().map(CreditProduct.TermBounds::minimumMonths);
            Money one = Money.ofMinorUnits(1, EUR);
            submitted(product, product.minimumAmount(), term);
            submitted(product, product.maximumAmount(), term);
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as(product.name())
                    .isThrownBy(() -> submitted(product, product.minimumAmount().minus(one), term));
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as(product.name())
                    .isThrownBy(() -> submitted(product, product.maximumAmount().plus(one), term));
        }
        CreditProduct.TermBounds loan = CreditProduct.PERSONAL_LOAN.termBounds().orElseThrow();
        Money amount = Money.ofMinorUnits(1_000_000, EUR);
        submitted(CreditProduct.PERSONAL_LOAN, amount, Optional.of(loan.maximumMonths()));
        for (Optional<Integer> term : java.util.List.of(Optional.of(loan.minimumMonths() - 1),
                Optional.of(loan.maximumMonths() + 1), Optional.<Integer>empty())) {
            assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as(term.toString())
                    .isThrownBy(() -> submitted(CreditProduct.PERSONAL_LOAN, amount, term));
        }
        assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("a line has no term")
                .isThrownBy(() -> submitted(CreditProduct.CREDIT_LINE, Money.ofMinorUnits(100_000, EUR), Optional.of(12)));
        assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("another currency")
                .isThrownBy(() -> submitted(CreditProduct.CREDIT_LINE, Money.ofMinorUnits(100_000, CurrencyCode.of("USD")),
                        Optional.empty()));
        assertThatExceptionOfType(DecisionRequest.AmountOutOfRange.class).as("a negative declared figure")
                .isThrownBy(() -> DecisionRequest.Application.submitted(CreditProduct.CREDIT_LINE,
                        Money.ofMinorUnits(100_000, EUR), Optional.empty(), Optional.of(Money.ofMinorUnits(-1, EUR)),
                        Optional.empty()));
    }

    @Test
    @DisplayName("a recorded request reads back whatever the bounds are now; an abandoned one, and only it, says why")
    void aRecordedRequestReadsBack() {
        DecisionRequest.Application beyond = new DecisionRequest.Application(CreditProduct.PERSONAL_LOAN,
                Money.ofMinorUnits(99_999_999, EUR), Optional.of(120), Optional.empty(), Optional.empty());
        assertThat(beyond.toString()).doesNotContain("99").as("no amount renders");
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> new DecisionRequest(
                DecisionRequestId.of(java.util.UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f0001")),
                java.util.UUID.randomUUID(),
                CreditProfileId.of(java.util.UUID.fromString("0190a1b2-5c0e-7000-8000-0000000f0002")), beyond,
                DecisionRequestStatus.ABANDONED, Optional.empty(), Optional.empty(), java.time.Instant.EPOCH,
                java.time.Instant.EPOCH, "c"));
    }

    private static void submitted(CreditProduct product, Money amount, Optional<Integer> term) {
        DecisionRequest.Application.submitted(product, amount, term, Optional.empty(), Optional.empty());
    }
}
