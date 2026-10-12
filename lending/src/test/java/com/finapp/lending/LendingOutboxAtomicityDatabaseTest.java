package com.finapp.lending;

import static com.finapp.lending.DisbursementWorld.acceptedLoan;
import static com.finapp.lending.DisbursementWorld.count;
import static com.finapp.lending.DisbursementWorld.disburse;
import static com.finapp.lending.DisbursementWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.EventPublisher;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.outbox.PendingEvent;
import com.finapp.platform.outbox.RetryPolicy;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Scenario 9 - a service crashes after the ledger posting but before the event's publication (`P11-TSK-014`;
 * {@code PHASE_11_PLAN.md} sections 10 and 13.2, ADR-0005, ADR-0097 point 9; {@code INV-EVT-01}, {@code INV-LND-07}):
 * the disbursement's posting, its {@code lending.LoanDisbursed} outbox row and the ledger's own
 * {@code ledger.JournalEntryPosted} row are one transaction with the loan's edge, so a crash before the commit leaves
 * neither the money nor an event, and a relay killed after the commit loses nothing - the restarted relay publishes the
 * event, once. Against the real relay ({@link OutboxRelay}) as the application role, over {@link DisbursementWorld}.
 */
@Tag("database")
@DisplayName("scenario 9 - a crash between posting and publication (P11-TSK-014)")
class LendingOutboxAtomicityDatabaseTest {

    @Test
    @DisplayName("a crash between posting and publication loses nothing: rolled back after the posting and the event row,"
            + " neither the entry nor any event exists and the relay publishes nothing for the loan; re-driven and"
            + " committed, a relay killed before the broker's answer leaves both events pending, and the restarted relay"
            + " publishes each exactly once")
    void aCrashBetweenPostingAndPublicationLosesNothing() throws Exception {
        DisbursementWorld.Accepted accepted = acceptedLoan(3_300_00, 12);
        UUID loan = accepted.loan().value();

        // The crash: the transaction dies right after the posting AND the LoanDisbursed row were written.
        OutboxWriter<Connection> crashing = (unitOfWork, envelope, payload, mediaType) -> {
            new JdbcOutboxWriter().write(unitOfWork, envelope, payload, mediaType);
            if (envelope.eventType().equals(LoanDisbursements.DISBURSED_EVENT)) {
                throw new IllegalStateException("the instance died after the posting and the event row");
            }
        };
        LoanDisbursements dying = DisbursementWorld.disbursements(crashing, new JdbcAuditWriter());
        assertThatThrownBy(() -> disburse(dying, accepted.loan())).hasMessageContaining("instance died");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:lending.disbursement:" + loan)).as("no posting without its event").isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?", loan))
                .as("the acceptance's event alone - no LoanDisbursed without its posting").isEqualTo(1);
        assertThat(scalar("SELECT status FROM lending.loan WHERE id = ?", loan)).isEqualTo("PENDING_DISBURSEMENT");
        Recorder rolledBack = new Recorder(Set.of(loan), false);
        drain(relay(rolledBack));
        assertThat(rolledBack.published(LoanDisbursements.DISBURSED_EVENT)).as("nothing invented").isZero();

        // Re-driven: committed - the posting, the loan's edge and both events together.
        assertThat(disburse(accepted.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        UUID entry = UUID.fromString(scalar("SELECT journal_entry_id::text FROM lending.loan_disbursement"
                + " WHERE loan_id = ?", loan));
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanDisbursed'", loan)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'ledger.JournalEntryPosted'", entry)).as("the ledger's event beside it").isEqualTo(1);

        // The relay is killed before the broker answers: nothing is recorded published.
        Recorder killed = new Recorder(Set.of(loan, entry), true);
        relay(killed).pollOnce();
        relay(killed).pollOnce();
        assertThat(unpublished(loan, LoanDisbursements.DISBURSED_EVENT)).as("still pending after the kill").isTrue();

        // The restart: the relay publishes each event exactly once, and nothing more on a later poll.
        Recorder restarted = new Recorder(Set.of(loan, entry), false);
        OutboxRelay relay = relay(restarted);
        drain(relay);
        assertThat(restarted.published(LoanDisbursements.DISBURSED_EVENT)).as("published once after the restart")
                .isEqualTo(1);
        assertThat(restarted.published("ledger.JournalEntryPosted")).isEqualTo(1);
        assertThat(unpublished(loan, LoanDisbursements.DISBURSED_EVENT)).isFalse();
        drain(relay);
        assertThat(restarted.published(LoanDisbursements.DISBURSED_EVENT)).as("never twice").isEqualTo(1);
    }

    // ------------------------------------------------------------------ plumbing

    /** Polls until the relay finds nothing to do (bounded): every pending event in the database drained. */
    private static void drain(OutboxRelay relay) throws InterruptedException {
        for (int poll = 0; poll < 200; poll++) {
            if (!relay.pollOnce().didWork()) {
                return;
            }
            Thread.sleep(5);
        }
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(DatabaseRoles::application, publisher,
                new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 50), 10_000, 100);
    }

    private static boolean unpublished(UUID aggregate, String type) {
        return count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ? AND event_type = ?"
                + " AND published_at IS NULL", aggregate, type) == 1;
    }

    /**
     * A broker that records what reaches it for the watched aggregates - or, killed, throws for them before
     * acknowledging (the relay's process dying mid-publish); every other aggregate's event it accepts, as a live relay
     * would.
     */
    private static final class Recorder implements EventPublisher {

        private final Set<UUID> watched;
        private final boolean killed;
        private final List<String> received = new CopyOnWriteArrayList<>();

        Recorder(Set<UUID> watched, boolean killed) {
            this.watched = watched;
            this.killed = killed;
        }

        @Override
        public void publish(PendingEvent event) {
            if (!watched.contains(event.aggregateId())) {
                return;
            }
            if (killed) {
                throw new IllegalStateException("the relay died before the broker acknowledged");
            }
            received.add(event.eventType());
        }

        long published(String type) {
            return received.stream().filter(type::equals).count();
        }
    }
}
