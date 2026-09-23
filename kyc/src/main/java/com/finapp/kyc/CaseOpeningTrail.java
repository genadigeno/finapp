package com.finapp.kyc;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The record and the announcement of a case that was <strong>created</strong> — the
 * {@code kyc.CaseOpened} audit record and the {@code kyc.KycCaseOpened} event, in the creating
 * transaction (`P2-TSK-007`, `P2-TSK-006`).
 *
 * <p>Extracted from {@link CustomerOpenedOpensCase} the moment its second caller arrived — the
 * person's own {@code POST /v1/me/kyc} — because the announcement's shape (event type, version,
 * payload fields) and the audit record's shape are one contract however many doors produce them,
 * and two copies are the drift the {@code CheckOutcomeTrail} extraction closed for check
 * outcomes (`P2-TSK-011`'s rule: composition is earned by a second caller, never invented for
 * one).
 *
 * <h2>The actor is the door's, deliberately — unlike {@code CheckOutcomeTrail}</h2>
 *
 * <p>That trail carries its own {@code enterSystem()} scope, because a check outcome is the
 * platform's act through every door. A case opening is not: the consumer door is the platform's
 * policy act ({@code enterSystem()} at its enumerated site), while the endpoint door is the
 * <strong>person's own act</strong>, performed under the interceptor's proven scope — attributing
 * it to the platform would fail {@code AuditNamesTheActorDatabaseTest}, correctly. So this trail
 * resolves {@link SecurityContext#require()} and lets each door's scope answer.
 *
 * <h2>The cause is the door's too</h2>
 *
 * <p>The consumer's opening is caused by the consumed {@code party.CustomerOpened}; the
 * endpoint's is caused by the request (the {@code PartyRegistration} flow-root idiom). Both are
 * facts only the door knows, so the cause is a parameter rather than a lookup here.
 */
@RequiredArgsConstructor
public final class CaseOpeningTrail {

    private static final String EVENT_TYPE = "kyc.KycCaseOpened";

    private static final String PRODUCER = "kyc";

    private static final int EVENT_VERSION = 1;

    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final OutboxWriter<Connection> outboxWriter;

    /**
     * Records and announces a created case on the creating transaction's own unit of work.
     *
     * <p>Only ever called on the {@code created} branch of {@code openOrConverge}: a converged
     * open records nothing, because two opening records on one case is the ambiguity
     * {@code INV-KYC-03} exists to prevent.
     */
    public void record(Connection unitOfWork, KycCase kycCase, CausationId cause) {
        // The unit of work is the caller's and goes unchecked here, deliberately: the writers
        // own that contract, and a hermetic caller drives this with fakes and no connection.
        Objects.requireNonNull(kycCase, "kycCase must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        audit(unitOfWork, kycCase);
        announce(unitOfWork, kycCase, cause);
    }

    private void audit(Connection unitOfWork, KycCase kycCase) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        KycAuditAction.KYC_CASE_OPENED,
                        "Customer",
                        kycCase.customerId().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        currentCorrelation().correlationId(),
                        Optional.of(
                                "case="
                                        + kycCase.id()
                                        + " policyVersion="
                                        + kycCase.policyVersion())));
    }

    private void announce(Connection unitOfWork, KycCase kycCase, CausationId cause) {
        outboxWriter.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        kycCase.id(),
                        "KycCase",
                        kycCase.openedAt(),
                        PRODUCER,
                        currentCorrelation().correlationId(),
                        cause),
                EventPayload.of()
                        .with("caseId", kycCase.id().value().toString())
                        .with("customerId", kycCase.customerId().toString())
                        .with("policyVersion", kycCase.policyVersion().value())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static Correlation currentCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "no correlation in scope; every door enters one before"
                                                + " opening a case"));
    }
}
