package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The watermark's positions (`P13-TSK-019`; {@code DESIGN-019} section 0): a sequence is a UUIDv7's millisecond prefix,
 * and {@code positionAt} is that millisecond's lowest id - so every id the platform's generator mints at millisecond
 * {@code M} sorts at or after {@code positionAt(M)} and before {@code positionAt(M + 1)}, which is what lets the
 * reprocessing door restart at an instant and the keyset read continue just after it.
 */
@DisplayName("the monitoring watermark's positions (P13-TSK-019)")
class MonitoringWatermarkTest {

    @Test
    @DisplayName("an id minted at millisecond M sorts in [positionAt(M), positionAt(M + 1)) and reads back as M")
    void positionsBracketTheGeneratorsIds() {
        long millis = Instant.parse("2026-10-23T10:00:00.123Z").toEpochMilli();
        IdGenerator ids = new IdGenerator(Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC), new Random(7));
        UUID floor = MonitoringWatermark.positionAt(millis);
        UUID ceiling = MonitoringWatermark.positionAt(millis + 1);
        for (int i = 0; i < 1_000; i++) {
            UUID id = ids.next();
            assertThat(MonitoringIntake.compare(id, floor)).as("at or after the floor").isGreaterThanOrEqualTo(0);
            assertThat(MonitoringIntake.compare(id, ceiling)).as("before the next millisecond").isNegative();
            assertThat(MonitoringWatermark.sequenceOf(id)).isEqualTo(millis);
        }
        assertThat(floor.version()).isEqualTo(7);
        assertThat(floor.variant()).isEqualTo(2);
        assertThat(MonitoringWatermark.sequenceOf(floor)).isEqualTo(millis);
    }

    @Test
    @DisplayName("the floor is millisecond 0's position and sorts before every id ever minted")
    void theFloor() {
        assertThat(MonitoringWatermark.FLOOR).isEqualTo(UUID.fromString("00000000-0000-7000-8000-000000000000"));
        assertThat(MonitoringWatermark.sequenceOf(MonitoringWatermark.FLOOR)).isZero();
        IdGenerator ids = new IdGenerator(Clock.fixed(Instant.parse("2026-10-23T10:00:00Z"), ZoneOffset.UTC),
                new Random(7));
        assertThat(MonitoringIntake.compare(MonitoringWatermark.FLOOR, ids.next())).isNegative();
    }

    @Test
    @DisplayName("a sequence is a 48-bit millisecond: negative or beyond the format is refused")
    void theSequenceIsBounded() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MonitoringWatermark.positionAt(-1));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MonitoringWatermark.positionAt(1L << 48));
        assertThat(MonitoringWatermark.positionAt((1L << 48) - 1)).isNotNull();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new MonitoringWatermark("ledger.journal", MonitoringWatermark.FLOOR, -1, Instant.EPOCH));
    }
}
