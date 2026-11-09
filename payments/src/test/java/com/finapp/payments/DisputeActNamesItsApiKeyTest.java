package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A merchant's act on a dispute names the API key that made it (`P15-TSK-039`; Phase 7's #63). The evidence read is driven
 * through its real service here; the upload and the response write the same note ({@code DisputeResponseDatabaseTest},
 * owed).
 */
@DisplayName("a merchant's dispute act names its API key, and an operator's does not (P15-TSK-039)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class DisputeActNamesItsApiKeyTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final List<AuditRecord> written = new ArrayList<>();

    @Test
    @DisplayName("the key note: a merchant's key by its public id, and nothing for an operator or a keyless value")
    void theNoteSaysWhichKey() {
        String key = UUID.randomUUID().toString();
        assertThat(DisputeActs.keyNote(new DisputeActor.Counterparty(Set.of(), Optional.of(key))))
                .isEqualTo(", apiKey=" + key);
        assertThat(DisputeActs.keyNote(new DisputeActor.Counterparty(Set.of()))).isEmpty();
        assertThat(DisputeActs.keyNote(new DisputeActor.Operator(Optional.of("a reason"), Set.of()))).isEmpty();
    }

    @Test
    @DisplayName("a merchant's evidence read is recorded with the key that read it")
    void aMerchantsEvidenceReadNamesItsKey() throws Exception {
        String key = UUID.randomUUID().toString();
        DisputeId dispute = DisputeId.next(IDS);
        DisputeEvidenceId document = DisputeEvidenceId.next(IDS);
        DisputeActor.Counterparty merchant =
                new DisputeActor.Counterparty(Set.of(com.finapp.ledger.LedgerAccountId.next(IDS)), Optional.of(key));

        try (var correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("the-read")));
                var actor = SecurityContext.enter(new Actor(UUID.randomUUID().toString(), ActorType.MERCHANT))) {
            access().read(merchant, dispute, document);
        }

        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(PaymentsAuditAction.DISPUTE_EVIDENCE_READ);
            assertThat(record.changeSummary()).hasValue("dispute=" + dispute + ", apiKey=" + key);
        });
    }

    private DisputeEvidenceAccess access() {
        return new DisputeEvidenceAccess(new Immediately(), unanswering(DisputeStore.class),
                unanswering(PaymentAttemptStore.class), unanswering(PaymentIntentStore.class),
                unanswering(com.finapp.ledger.LedgerAccountStore.class), evidence(), unanswering(DisputeResponseStore.class),
                (uow, record) -> written.add(record), IDS, CLOCK);
    }

    @SuppressWarnings("unchecked")
    private static DisputeEvidenceStore<Connection> evidence() {
        return (DisputeEvidenceStore<Connection>) Proxy.newProxyInstance(DisputeEvidenceStore.class.getClassLoader(),
                new Class<?>[] {DisputeEvidenceStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("readContentForCounterparties")) {
                        return Optional.of(new DisputeEvidenceStore.Content(null, null));
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <S> S unanswering(Class<?> type) {
        return (S) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new UnsupportedOperationException(method.getName());
        });
    }

    /** Runs the work where it stands: a read needs no real transaction to prove what it records. */
    private static final class Immediately implements TransactionRunner {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return work.apply(null);
        }
    }
}
