package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.settlement.format.SettlementFormat;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A key version this instance does not hold is not corruption (`P15-TSK-019`): a replica that has not rolled, or an activation
 * done in the wrong order, must not leave a false integrity verdict on an intact file. Each operator read, hermetically, over
 * stores that answer as the real one does.
 */
@DisplayName("a key version not held is recorded as that, never as a corrupt file (P15-TSK-019)")
class SettlementKeyVersionNotHeldTest {

    private static final UUID FILE = UUID.fromString("00000000-0000-0000-0000-00000000f001");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor OPERATOR = new Actor("op-alice", ActorType.EMPLOYEE);
    private static final Correlation CORRELATION = Correlation.startingWith(CorrelationId.of("corr-not-held"));
    private static final SettlementFileStore.FileRow ROW = new SettlementFileStore.FileRow(
            FILE, "source", UUID.fromString("00000000-0000-0000-0000-00000000f002"), DeliveryChannel.UPLOAD, FileStatus.RECEIVED, Optional.empty(),
            SettlementFormatId.SIM_PSP_CSV, 1, new byte[32], 1, 1, Optional.empty(), Optional.empty(), CLOCK.instant(), CorrelationId.of("corr"),
            Optional.empty(), Optional.empty());

    private final List<AuditRecord> recorded = new ArrayList<>();

    /** The store as the real one answers a file this instance holds no key for. */
    @SuppressWarnings("unchecked")
    private SettlementFileStore<Connection> notHeldStore() {
        return (SettlementFileStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SettlementFileStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "fileById" -> Optional.of(ROW);
                    case "readContent" -> throw new SettlementKeyVersionNotHeldException(FILE, 2);
                    default -> throw new AssertionError("unexpected call: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private AuditWriter<Connection> audit() {
        return (AuditWriter<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AuditWriter.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("append")) {
                        recorded.add((AuditRecord) args[1]);
                        return null;
                    }
                    throw new AssertionError("unexpected call: " + method.getName());
                });
    }

    @Test
    @DisplayName("a verification answers KEY_VERSION_NOT_HELD, never CORRUPT, and the record names the version")
    void aVerificationRecordsTheVersionNotHeldRatherThanCorrupt() {
        SettlementBatchStore<Connection> batches = batchStoreWithABatch();
        SettlementFormat format = (SettlementFormat) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SettlementFormat.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "version" -> 1;
                    default -> throw new AssertionError("unexpected call: " + method.getName());
                });
        FileVerification verification = new FileVerification(notHeldStore(), batches, Map.of(SettlementFormatId.SIM_PSP_CSV, format), audit(), IDS);

        FileVerification.Verified verified = verification.verify(unitOfWork(), FILE, OPERATOR, "a reason of ten or more characters", CLOCK.instant(), CORRELATION);

        assertThat(verified.verdict()).isEqualTo("KEY_VERSION_NOT_HELD");
        assertThat(verified.keyVersionNotHeld()).contains(2);
        assertThat(recorded).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(entry.changeSummary()).get().asString().contains("verdict=KEY_VERSION_NOT_HELD").contains("keyVersionNotHeld=2").doesNotContain("CORRUPT");
        });
    }

    @Test
    @DisplayName("a content read answers the version not held, never the corrupt outcome, and the record names the version")
    void aContentReadRecordsTheVersionNotHeldRatherThanCorrupt() {
        EvidenceContentReads<Connection> reads = new EvidenceContentReads<>(notHeldStore(), audit(), IDS, CLOCK);

        EvidenceContentReads.Outcome outcome = reads.read(unitOfWork(), FILE, OPERATOR, "a reason of ten or more characters", CORRELATION);

        assertThat(outcome).isInstanceOfSatisfying(EvidenceContentReads.Outcome.KeyVersionNotHeld.class, notHeld -> assertThat(notHeld.keyVersion()).isEqualTo(2));
        assertThat(recorded).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(entry.changeSummary()).get().asString().contains("key version 2 not held").doesNotContain("verification failed");
        });
    }

    /** Nothing here reaches a connection: the stores are the planted ones. */
    private Connection unitOfWork() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    throw new AssertionError("nothing reaches the connection: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private SettlementBatchStore<Connection> batchStoreWithABatch() {
        return (SettlementBatchStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SettlementBatchStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "batchByFileId" -> Optional.of(new Object());
                    default -> throw new AssertionError("unexpected call: " + method.getName());
                });
    }
}
