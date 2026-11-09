package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins, as text, the payments reads and the routing-version birth fixed by `P15-TSK-037` ({@code THREAT_MODEL.md} T13): the attempt ranked by a sequence, the
 * refund named by its dispatch key, the chosen decision first, an immediate version's start floored by version number. Hermetic: the database cases are owed
 * to {@code P15-TST-002}.
 */
@DisplayName("payments' single-row reads are ranked by the rows; a routing version's start cannot be reversed by a clock (P15-TSK-037)")
class PaymentsStampOrderStatementsTest {

    private static final String V038 = read("db/migration/payments/V038__attempts_are_ranked_by_a_sequence.sql");

    @Test
    @DisplayName("an intent's attempt is ranked by attempt_order, the stamp only for rows from before V038")
    void theAttemptIsRankedByItsOrder() {
        assertThat(JdbcPaymentAttemptStore.FOR_INTENT).endsWith("ORDER BY attempt_order DESC NULLS LAST, created_at DESC, id DESC LIMIT 1");
    }

    @Test
    @DisplayName("a refund is named by its dispatch key: no ORDER BY over a set the unique index holds at one row")
    void theRefundIsNamedByItsKey() {
        assertThat(JdbcRefundStore.BY_DISPATCH_KEY).endsWith("WHERE dispatch_key = ?").doesNotContain("ORDER BY");
    }

    @Test
    @DisplayName("the routing decision read is the chosen one first, named by the partial unique; refusals keep their stamp only for an operator's explanation")
    void theChosenDecisionComesFirst() {
        assertThat(JdbcRoutingStore.LATEST_DECISION_ORDER).isEqualTo(" ORDER BY (chosen_rail IS NOT NULL) DESC, created_at DESC, id DESC LIMIT 1");
    }

    @Test
    @DisplayName("a routing version's start is floored at the latest immediate lower-numbered version's, chosen by version number")
    void anImmediateVersionsStartIsFlooredByVersionNumber() {
        assertThat(JdbcRoutingStore.INSERT_VERSION)
                .contains("GREATEST(CAST(? AS timestamptz), (SELECT max(v.effective_from) FROM payments.routing_policy_version v WHERE v.version < ? AND v.effective_from = v.created_at))");
    }

    @Test
    @DisplayName("V038 adds the column with no default first, so no rewrite numbers an existing row, and only then the sequence default")
    void theColumnArrivesWithoutRewritingHistory() {
        assertThat(V038).contains("ALTER TABLE payments.payment_attempt ADD COLUMN attempt_order bigint;\nALTER TABLE payments.payment_attempt ALTER COLUMN attempt_order SET DEFAULT nextval('payments.attempt_order');")
                .contains("GRANT USAGE ON SEQUENCE payments.attempt_order TO finapp_app;")
                .doesNotContain("INSERT INTO").doesNotContain("UPDATE payments");
    }

    private static String read(String resource) {
        try (InputStream stream = PaymentsStampOrderStatementsTest.class.getClassLoader().getResourceAsStream(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
