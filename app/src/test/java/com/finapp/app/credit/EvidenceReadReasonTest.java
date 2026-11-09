package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.credit.CreditInvestigations;
import com.finapp.credit.CreditRecordId;
import com.finapp.credit.JdbcCreditReads;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.SpringObjenesis;

/**
 * An evidence read's reason is screened for a minimum, on the server, before anything is read (`P15-TSK-039`). Through the
 * real door on PostgreSQL: {@code DecisionExplanationDatabaseTest}, owed.
 */
@DisplayName("an evidence read's reason is screened for a minimum length (P15-TSK-039)")
class EvidenceReadReasonTest {

    private static final com.finapp.sharedkernel.id.IdGenerator IDS = new com.finapp.sharedkernel.id.IdGenerator(
            java.time.Clock.fixed(java.time.Instant.parse("2026-11-09T09:00:00Z"), java.time.ZoneOffset.UTC), new java.security.SecureRandom());
    private static final Actor INVESTIGATOR = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final CorrelationId CORRELATION = CorrelationId.of("the-read");

    @Test
    @DisplayName("under ten characters, spaces aside, is refused before anything is read or recorded")
    void aShortReasonIsRefusedFirst() {
        CreditInvestigations investigations = new SpringObjenesis().newInstance(CreditInvestigations.class);
        for (String tooShort : List.of("x", "complaint", "   nine   ")) {
            assertThatThrownBy(() -> investigations.readEvidence(connectionThatFails(), record(), tooShort, INVESTIGATOR, CORRELATION))
                    .as(tooShort)
                    .isInstanceOfSatisfying(CreditInvestigations.ReasonRequired.class,
                            refused -> assertThat(refused.getMessage()).contains("at least 10 characters"));
        }
    }

    @Test
    @DisplayName("exactly the minimum passes the screen and goes on to the read")
    void theMinimumItselfPasses() throws Exception {
        CreditInvestigations investigations = new SpringObjenesis().newInstance(CreditInvestigations.class);
        Field reads = CreditInvestigations.class.getDeclaredField("reads");
        reads.setAccessible(true);
        reads.set(investigations, new SpringObjenesis().newInstance(JdbcCreditReads.class));

        assertThatThrownBy(() -> investigations.readEvidence(connectionThatFails(), record(), "a complain", INVESTIGATOR, CORRELATION))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reached the read");
    }

    private static CreditRecordId record() {
        return CreditRecordId.next(IDS);
    }

    /** A connection no statement can be prepared on: reaching it is the signal the screen passed. */
    private static Connection connectionThatFails() {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    throw new IllegalStateException("reached the read");
                });
    }
}
