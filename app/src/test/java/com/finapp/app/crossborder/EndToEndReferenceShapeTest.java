package com.finapp.app.crossborder;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Our end-to-end reference's shape (`P9-TSK-022`): letters only, so no provider report screen ever reads a run of its
 * digits as a card number - the corridor format refuses a 13-digit run, which 32 hexadecimal digits carry in roughly
 * one reference in fifty - and unique as the identifier it renders.
 */
@DisplayName("the end-to-end reference carries no digit (P9-TSK-022)")
class EndToEndReferenceShapeTest {

    @Test
    @DisplayName("ten thousand references: 32 letters each, no digit, all distinct, and the rendering a bijection")
    void referencesAreLettersOnly() {
        IdGenerator ids = new IdGenerator(Clock.systemUTC(), new SecureRandom());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            UUID id = ids.next();
            String reference = PaymentsCrossBorderExecution.lettersOnly(id);
            assertThat(reference).hasSize(32).matches("[a-p]{32}");
            assertThat(seen.add(reference)).isTrue();
        }
        assertThat(PaymentsCrossBorderExecution.lettersOnly(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef")))
                .isEqualTo("ghijklmnopabcdefghijklmnopabcdef");
    }
}
