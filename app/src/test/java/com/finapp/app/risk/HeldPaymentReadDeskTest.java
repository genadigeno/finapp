package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.OperatorCollection;
import com.finapp.payments.PaymentCollections;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The held card payments' request, without a database (`P17-TSK-032`): the cursor is its own collection's, exact to the
 * microsecond, and refused anywhere else. The read, its order, the withholding of the reader's own party, the permission and the
 * record are {@code PaymentReadsApiDatabaseTest}'s.
 */
@DisplayName("the held card payments' request (P17-TSK-032)")
class HeldPaymentReadDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @Test
    @DisplayName("the cursor is the held list's own, exact to the microsecond, and refused anywhere else")
    void theCursorIsItsOwn() {
        Instant due = Instant.parse("2026-11-23T10:15:30.123456Z");
        UUID intent = IDS.next();
        String held = OperatorCollection.cursor(HeldPaymentReadDesk.HELD, due.toString(), intent.toString());
        assertThat(HeldPaymentReadDesk.heldCursor(held)).contains(new PaymentCollections.HeldCursor(due, intent));
        assertThat(HeldPaymentReadDesk.heldCursor(null)).isEmpty();

        for (String foreign : new String[] {
                OperatorCollection.cursor("payments.payments", due.toString(), intent.toString()),
                OperatorCollection.cursor(HeldPaymentReadDesk.HELD, "soon", intent.toString()),
                "not-a-cursor"}) {
            assertThatThrownBy(() -> HeldPaymentReadDesk.heldCursor(foreign)).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
    }
}
