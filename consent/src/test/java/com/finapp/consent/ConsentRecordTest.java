package com.finapp.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The record refuses the unpinnable, and both factories pin what `INV-CNS-04` demands. */
@DisplayName("a consent record (P2-TSK-017)")
class ConsentRecordTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("a record without a real text version is unconstructible - on BOTH kinds")
    void theVersionPinIsUnconditional() {
        // INV-CNS-04's reference is unconditional: a withdrawal is also a dated fact against
        // an artefact, so neither factory admits an unpinned record.
        UUID party = IDS.next();
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 0));
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        ConsentRecord.withdrawal(IDS, CLOCK, party, ConsentPurpose.SCREENING, -1));
    }

    @Test
    @DisplayName("the factories fix the action - a caller cannot mint a grant that withdraws")
    void theFactoriesFixTheAction() {
        UUID party = IDS.next();
        assertThat(ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.SCREENING, 1).action())
                .isEqualTo(ConsentAction.GRANT);
        assertThat(
                        ConsentRecord.withdrawal(IDS, CLOCK, party, ConsentPurpose.SCREENING, 1)
                                .action())
                .isEqualTo(ConsentAction.WITHDRAWAL);
    }
}
