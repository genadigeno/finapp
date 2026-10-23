package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The journal as velocity evidence (`P13-TSK-006`; `-019`'s monitored transactions): a transfer counts its debited
 * account as the source and its credited customer account as the destination, whichever side the classification chose
 * as the subject, at the entry's own instant; nothing else in the journal is evidence of a counted movement.
 */
@DisplayName("the monitored transfers are the velocity evidence, for the source and the destination (P13-TSK-006)")
class VelocityEvidenceTest {

    private static final UUID FROM = UUID.fromString("018f0000-0000-7000-8000-0000000000a1");
    private static final UUID TO = UUID.fromString("018f0000-0000-7000-8000-0000000000b2");
    private static final Instant POSTED = Instant.parse("2031-03-09T10:15:00Z");

    @Test
    @DisplayName("a transfer seen from its source (OUT) and from its destination (IN) is the same movement")
    void aTransferEitherWayRound() {
        VelocityEvidence.Counted out = VelocityEvidence.transferOf(transfer(MonitoredDirection.OUT, FROM, TO)).orElseThrow();
        VelocityEvidence.Counted in = VelocityEvidence.transferOf(transfer(MonitoredDirection.IN, TO, FROM)).orElseThrow();
        for (VelocityEvidence.Counted counted : new VelocityEvidence.Counted[] {out, in}) {
            assertThat(counted.at()).isEqualTo(POSTED);
            assertThat(counted.movement().subject(VelocityDimension.SOURCE_ACCOUNT)).contains(FROM.toString());
            assertThat(counted.movement().subject(VelocityDimension.DESTINATION)).contains(TO.toString());
            assertThat(counted.movement().subjects()).hasSize(2);
            assertThat(counted.movement().amount()).isEqualTo(Money.ofMinorUnits(120_00, RiskPolicyFixtures.EUR));
        }
        assertThat(new JdbcVelocityEvidence(new UnusedWatermarks()).provable())
                .isEqualTo(EnumSet.of(VelocityDimension.SOURCE_ACCOUNT, VelocityDimension.DESTINATION));
    }

    @Test
    @DisplayName("a withdrawal, a card capture, a payout, a reversal - posted at their outcome or never - are no evidence"
            + " of a counted movement; a transfer to a non-customer counterparty names its source alone")
    void nothingElseIsEvidence() {
        for (MonitoredKind kind : EnumSet.complementOf(EnumSet.of(MonitoredKind.TRANSFER, MonitoredKind.REVERSAL))) {
            assertThat(VelocityEvidence.transferOf(row(kind, MonitoredEntry.EntryType.POSTING, MonitoredDirection.OUT,
                    FROM, MonitoredCounterpartyKind.CUSTOMER, TO))).as(kind.name()).isEmpty();
        }
        assertThat(VelocityEvidence.transferOf(row(MonitoredKind.REVERSAL, MonitoredEntry.EntryType.REVERSAL,
                MonitoredDirection.IN, FROM, MonitoredCounterpartyKind.CUSTOMER, TO))).isEmpty();
        VelocityEvidence.Counted toSuspense = VelocityEvidence.transferOf(row(MonitoredKind.TRANSFER,
                MonitoredEntry.EntryType.POSTING, MonitoredDirection.OUT, FROM, MonitoredCounterpartyKind.SUSPENSE, TO))
                .orElseThrow();
        assertThat(toSuspense.movement().subjects().keySet()).containsExactly(VelocityDimension.SOURCE_ACCOUNT);
    }

    private static MonitoredTransaction transfer(MonitoredDirection direction, UUID subject, UUID counterparty) {
        return row(MonitoredKind.TRANSFER, MonitoredEntry.EntryType.POSTING, direction, subject,
                MonitoredCounterpartyKind.CUSTOMER, counterparty);
    }

    private static MonitoredTransaction row(MonitoredKind kind, MonitoredEntry.EntryType type,
            MonitoredDirection direction, UUID subject, MonitoredCounterpartyKind counterpartyKind, UUID counterparty) {
        return new MonitoredTransaction(MonitoredTransactionId.of(UUID.fromString("018f0000-0000-7000-8000-0000000000c3")),
                UUID.fromString("018f0000-0000-7000-8000-0000000000d4"), 1L, type, "transfer:", kind,
                Optional.of(MonitoredSubjectKind.ACCOUNT), Optional.of(subject), Optional.of(direction), counterpartyKind,
                Optional.of(counterparty), Optional.of("p"), 120_00, 2, RiskPolicyFixtures.EUR, LocalDate.of(2031, 3, 9),
                POSTED, type == MonitoredEntry.EntryType.REVERSAL
                        ? Optional.of(UUID.fromString("018f0000-0000-7000-8000-0000000000e5")) : Optional.empty());
    }

    /** The evidence's provable set is a constant - no watermark is read for it. */
    private static final class UnusedWatermarks implements MonitoringWatermarkStore {
        @Override
        public Optional<MonitoringWatermark> claimDue(java.sql.Connection unitOfWork, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MonitoringWatermark> lock(java.sql.Connection unitOfWork, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<MonitoringWatermark> read(java.sql.Connection unitOfWork, String source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void move(java.sql.Connection unitOfWork, String source, UUID lastEntryId, java.time.Duration permitDelay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hint(java.sql.Connection unitOfWork, String source) {
            throw new UnsupportedOperationException();
        }
    }
}
