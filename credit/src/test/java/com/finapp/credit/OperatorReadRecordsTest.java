package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the investigator's and the underwriter's reads now put on the record (`P15-TSK-039`): the policy read at an instant
 * and the review queue's served cases. On PostgreSQL through the real doors: {@code CreditPolicyEndpointDatabaseTest},
 * {@code UnderwritingCaseDatabaseTest}, owed.
 */
@DisplayName("the operator reads that were not on the record (P15-TSK-039)")
class OperatorReadRecordsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor INVESTIGATOR = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final CorrelationId CORRELATION = CorrelationId.of("the-read");

    private final List<AuditRecord> written = new ArrayList<>();
    private final AuditWriter<Connection> audit = (uow, record) -> written.add(record);

    @Test
    @DisplayName("the policy read at an instant is recorded in its own unit of work, naming the version served and the instant")
    void thePolicyReadIsRecorded() {
        CreditPolicyVersionId served = CreditPolicyVersionId.next(IDS);
        CreditPolicyAdministration administration = new CreditPolicyAdministration(answering(served), audit,
                (uow, envelope, payload, mediaType) -> { }, IDS, CLOCK);
        Instant at = Instant.parse("2026-10-01T00:00:00Z");

        Connection unitOfWork = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
        administration.readActiveAt(unitOfWork, CreditProduct.PERSONAL_LOAN, Optional.of(at), INVESTIGATOR, CORRELATION);

        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(CreditAuditAction.POLICY_READ);
            assertThat(record.actor()).isEqualTo(INVESTIGATOR);
            assertThat(record.targetId()).isEqualTo(served.value().toString());
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.changeSummary()).hasValue("the policy for PERSONAL_LOAN at 2026-10-01T00:00:00Z");
        });
    }

    @Test
    @DisplayName("the review queue's record names every case it served, and an empty queue's says so")
    void theServedCasesAreNamed() {
        UnderwritingCaseId first = UnderwritingCaseId.next(IDS);
        UnderwritingCaseId second = UnderwritingCaseId.next(IDS);

        assertThat(UnderwritingCases.queueSummary(List.of(first, second), Optional.of(UnderwritingCaseStatus.OPEN)))
                .isEqualTo("review queue read: 2 case(s), status OPEN, served [" + first.value() + ", " + second.value() + "]");
        assertThat(UnderwritingCases.queueSummary(List.of(), Optional.empty()))
                .isEqualTo("review queue read: 0 case(s), status any, served []");
    }

    private static CreditPolicyStore answering(CreditPolicyVersionId id) {
        return (CreditPolicyStore) Proxy.newProxyInstance(CreditPolicyStore.class.getClassLoader(),
                new Class<?>[] {CreditPolicyStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "activeAt", "active" -> Optional.of(id);
                    case "policy" -> Optional.of(new CreditPolicyStore.PolicyVersion(
                            new CreditPolicyStore.VersionRow(id, CreditProduct.PERSONAL_LOAN, 1, CreditPolicyStatus.ACTIVE,
                                    "officer", Optional.of("approver")),
                            null, Optional.of(Instant.parse("2026-01-01T00:00:00Z")), Optional.empty()));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
