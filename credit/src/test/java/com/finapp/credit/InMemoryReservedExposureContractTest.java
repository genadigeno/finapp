package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;

/**
 * The reserved-exposure contract over an in-memory fake (`P10-TSK-010`): the fake's fixed instant stands for the
 * database's {@code statement_timestamp()}. It pins the contract's meaning until `P10-TSK-016`'s JDBC implementation
 * runs the same suite against {@code credit_decision}.
 */
@DisplayName("the reserved-exposure contract, over the in-memory fake (P10-TSK-010)")
class InMemoryReservedExposureContractTest extends ReservedExposureContract<Void> {

    private final InMemoryReservedExposure fake = new InMemoryReservedExposure(Instant.parse("2026-10-08T12:00:00Z"));

    @Override
    protected ReservedExposure<Void> implementation() {
        return fake;
    }

    @Override
    protected Void unitOfWork() {
        return null;
    }

    @Override
    protected void decided(UUID party, DecisionOutcome outcome, Money approved, Duration validityFromNow, boolean consumed) {
        fake.decisions.add(new Decision(party, outcome, approved, fake.now.plus(validityFromNow), consumed));
    }

    private record Decision(UUID party, DecisionOutcome outcome, Money approved, Instant validUntil, boolean consumed) {}

    /** The contract's meaning, stated as plainly as code allows: approved, still valid, unconsumed, this party's. */
    private static final class InMemoryReservedExposure implements ReservedExposure<Void> {

        private final Instant now;
        private final List<Decision> decisions = new ArrayList<>();

        InMemoryReservedExposure(Instant now) {
            this.now = now;
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public Money reservedFor(Void unitOfWork, UUID partyId, CurrencyCode currency) {
            Money sum = Money.zero(currency);
            for (Decision decision : decisions) {
                if (decision.party().equals(partyId)
                        && decision.outcome() == DecisionOutcome.APPROVED
                        && decision.validUntil().isAfter(now)
                        && !decision.consumed()) {
                    sum = sum.plus(decision.approved());
                }
            }
            return sum;
        }
    }
}
