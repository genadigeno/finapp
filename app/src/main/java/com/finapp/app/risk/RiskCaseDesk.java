package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AlertEvidence;
import com.finapp.risk.Alerts;
import com.finapp.risk.CaseKind;
import com.finapp.risk.Cases;
import com.finapp.risk.RiskAlertId;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSignalId;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.SignalSubject;
import com.finapp.risk.SubjectKind;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The boundary half of the case doors (`P13-TSK-016`; `-012`'s {@code RiskOverrideDesk} shape): each act keyed per
 * principal ({@code risk.case:<type>:<id>}) and run in one transaction under its span; the domain's refusals translated
 * onto the error contract - a refused self-approval or self-dealing recorded {@code FAILED} in a transaction of its own
 * first; and, after an approval's transaction has committed - and again on every replay of its key - the approved
 * disposition's {@code PROHIBITED} applied to the profile in a transaction of its own ({@link CaseProhibitionDriver}:
 * the profile head R8 is never taken under the case row R4). The machine, the four eyes and the self-dealing rule are
 * the domain's and {@code risk V012}'s; nothing here decides them.
 *
 * <p><strong>The key's answer</strong> is the case's id (and, for an opening, whether this call opened it): a replayed
 * act answers the case as it stands now. Persons are never served ({@code taken} says only that someone holds it); the
 * subject is - this is the analyst's desk, behind {@code RISK_INVESTIGATE}, never logged.
 */
public final class RiskCaseDesk {

    static final String SCOPE = "risk.case:";

    private final Cases cases;
    private final CaseProhibitionDriver prohibitions;
    private final IdempotentExecutor executor;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskCaseDesk(Cases cases, CaseProhibitionDriver prohibitions, IdempotentExecutor executor,
            RiskTransactions transactions, RiskFlowScope flow) {
        this.cases = Objects.requireNonNull(cases, "cases");
        this.prohibitions = Objects.requireNonNull(prohibitions, "prohibitions");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /**
     * A case's head as an act answers it: the kind, the subject, the state with its revision (what the next act must
     * carry), the level and queue, whether it is taken, the SLA, what opened it and what it follows, and the
     * disposition slot. Instants ISO-8601 UTC; no person. {@code opened} is true only on the answer of an opening that
     * opened it.
     */
    public record RiskCaseView(String caseId, String kind, String subjectKind, String subjectRef, String status,
            int revision, String level, String queue, boolean taken, int slaHours, String slaDeadline,
            String openingAlertId, String openingAssessmentId, String predecessorCaseId, String openedAt,
            boolean opened, RiskDispositionView disposition) {}

    /**
     * The case's disposition slot: its id (what the approval and rejection name), the disposition, whether it sets
     * {@code PROHIBITED}, {@code PROPOSED} (awaiting a second person) or {@code DECIDED}, whether a second person is
     * required, and the database's stamps.
     */
    public record RiskDispositionView(String dispositionId, String disposition, boolean prohibits, String status,
            boolean requiresSecondPerson, String proposedAt, String disposedAt) {}

    // ------------------------------------------------------------------ acts

    public RiskCaseView open(String idempotencyKey, RiskCaseController.RiskCaseOpenRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Cases.OpenRequest asked = openRequest(request);
        String target = asked.alert().map(alert -> alert.value().toString()).orElse("new");
        Keyed act = flow.door(RiskSpans.CASE_OPEN, () -> keyed(actor, target, idempotencyKey,
                "open|" + request.alertId() + "|" + request.kind() + "|" + request.subjectKind() + "|"
                        + request.subjectRef() + "|" + request.reason(),
                uow -> {
                    Cases.Opened opened = cases.open(uow, asked, request.reason(), actor, correlation);
                    return opened.riskCase().id().value() + "|" + opened.opened();
                }));
        String[] stored = act.stored().split("\\|", -1);
        return receipt(caseId(stored[0]), Boolean.parseBoolean(stored[1]));
    }

    public RiskCaseView act(String idempotencyKey, String rawCaseId, RiskCaseController.RiskCaseActionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        int revision = request.revision();
        Function<Connection, String> work = switch (request.act()) {
            case "TAKE" -> uow -> cases.take(uow, caseId, revision, actor, correlation).riskCase().id().value()
                    .toString();
            case "NOTE" -> uow -> cases.note(uow, caseId, revision, request.text(), actor, correlation).riskCase().id()
                    .value().toString();
            case "ATTACH_EVIDENCE" -> {
                AlertEvidence evidence = evidence(request);
                yield uow -> cases.attachEvidence(uow, caseId, revision, evidence, request.text(), actor, correlation)
                        .riskCase().id().value().toString();
            }
            case "ESCALATE" -> uow -> cases.escalate(uow, caseId, revision, request.queue(), request.text(), actor,
                    correlation).riskCase().id().value().toString();
            default -> throw invalid("an act is TAKE, NOTE, ATTACH_EVIDENCE or ESCALATE");
        };
        Keyed act = flow.door(RiskSpans.CASE_ACT, () -> keyed(actor, caseId.value().toString(), idempotencyKey,
                "act|" + caseId.value() + "|" + request.act() + "|" + revision + "|" + request.text() + "|"
                        + request.queue() + "|" + request.signalIds() + "|" + request.assessmentIds() + "|"
                        + request.journalEntryIds(),
                work));
        return receipt(caseId(act.stored()), false);
    }

    public RiskCaseView propose(String idempotencyKey, String rawCaseId,
            RiskCaseController.RiskCaseDispositionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        boolean prohibit = Boolean.TRUE.equals(request.prohibit());
        Keyed act = flow.door(RiskSpans.CASE_DISPOSE, () -> keyed(actor, caseId.value().toString(), idempotencyKey,
                "propose|" + caseId.value() + "|" + request.revision() + "|" + request.disposition() + "|" + prohibit
                        + "|" + request.reason(),
                uow -> cases.proposeDisposition(uow, caseId, request.revision(), request.disposition(), prohibit,
                        request.reason(), actor, correlation).riskCase().id().value().toString()));
        return receipt(caseId(act.stored()), false);
    }

    public RiskCaseView approve(String idempotencyKey, String rawCaseId, String rawDispositionId,
            RiskCaseController.RiskCaseDecisionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        UUID dispositionId = dispositionId(rawDispositionId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Keyed act = flow.door(RiskSpans.CASE_DISPOSE, () -> keyed(actor, caseId.value().toString(), idempotencyKey,
                "approve|" + caseId.value() + "|" + dispositionId + "|" + request.revision() + "|" + request.reason(),
                uow -> cases.approveDisposition(uow, caseId, dispositionId, request.revision(), request.reason(),
                        actor, correlation).riskCase().id().value().toString()));
        // The approval has committed (or its key replayed it): now, in a transaction of its own and holding no case
        // row, the profile's PROHIBITED it sets - again on every retry, so a crash between the two is re-driven by the
        // door's own retry as well as by the case leg.
        prohibitions.applyIfDue(caseId, correlation);
        return receipt(caseId(act.stored()), false);
    }

    public RiskCaseView reject(String idempotencyKey, String rawCaseId, String rawDispositionId,
            RiskCaseController.RiskCaseDecisionRequest request) {
        RiskCaseId caseId = caseId(rawCaseId);
        UUID dispositionId = dispositionId(rawDispositionId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Keyed act = flow.door(RiskSpans.CASE_DISPOSE, () -> keyed(actor, caseId.value().toString(), idempotencyKey,
                "reject|" + caseId.value() + "|" + dispositionId + "|" + request.revision() + "|" + request.reason(),
                uow -> cases.rejectDisposition(uow, caseId, dispositionId, request.revision(), request.reason(), actor,
                        correlation).riskCase().id().value().toString()));
        return receipt(caseId(act.stored()), false);
    }

    // ------------------------------------------------------------------ plumbing

    /** A keyed act's stored answer, and whether this call ran it. */
    private record Keyed(String stored, boolean executed) {}

    /**
     * One keyed act in one transaction - a lost response replays the stored answer; a refusal stores nothing, and a
     * refused self-approval or self-dealing is recorded {@code FAILED} in a transaction of its own first.
     */
    private Keyed keyed(Actor actor, String target, String idempotencyKey, String fingerprinted,
            Function<Connection, String> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> refusalsRecorded(actor, target,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow ->
                        CommandResult.succeeded(StoredResponse.of(act.apply(uow).getBytes(StandardCharsets.UTF_8),
                                "text/plain"))))));
        String stored = new String(outcome.body().orElseThrow(() -> new IllegalStateException(
                "a case act stores its case's id")), StandardCharsets.UTF_8);
        return new Keyed(stored, outcome.executed());
    }

    /** The receipt of an act just performed or replayed - the case as it stands now. */
    private RiskCaseView receipt(RiskCaseId id, boolean opened) {
        RiskCase current = transactions.inTransaction(uow -> cases.current(uow, id))
                .orElseThrow(() -> new IllegalStateException("a case this door acted on is not found"));
        return view(current, opened);
    }

    /**
     * {@code work}, a refused self-approval or self-dealing recorded {@code FAILED} in a transaction of its own after
     * the act rolled back ({@code INV-RSK-07}), then refused as before.
     */
    private <R> R refusalsRecorded(Actor actor, String target, Supplier<R> work) {
        try {
            return work.get();
        } catch (Cases.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                cases.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                cases.recordRefusal(uow, target, dealing, actor, correlation);
                return null;
            });
            throw dealing;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (Cases.CaseNotFound unknown) {
            throw notFound();
        } catch (Cases.SelfApprovalRefused self) {
            throw refused(RiskErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RiskAssessments.SelfDealingRefused dealing) {
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk case act was refused",
                    "a holder never works a case of their own party, nor one whose subject the platform cannot place;"
                            + " nothing was done.");
        } catch (Cases.CaseRevisionStale stale) {
            throw refused(RiskErrorCode.CASE_REVISION_STALE, stale);
        } catch (Cases.CaseNotOpen disposed) {
            throw refused(RiskErrorCode.CASE_NOT_OPEN, disposed);
        } catch (Cases.CaseActNotAdmitted notAdmitted) {
            throw refused(RiskErrorCode.CASE_ACT_NOT_ADMITTED, notAdmitted);
        } catch (Cases.CaseNotTaken notTaken) {
            throw refused(RiskErrorCode.CASE_NOT_TAKEN, notTaken);
        } catch (Cases.CaseRequestInvalid invalid) {
            throw refused(RiskErrorCode.CASE_REQUEST_INVALID, invalid);
        } catch (Alerts.AlertNotOpen notOpen) {
            throw refused(RiskErrorCode.ALERT_NOT_OPEN, notOpen);
        } catch (RiskReasons.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        }
    }

    static RiskCaseView view(RiskCase riskCase, boolean opened) {
        RiskDispositionView disposition = riskCase.disposition().map(slot -> new RiskDispositionView(
                slot.id().toString(),
                slot.disposition().name(),
                slot.prohibits(),
                slot.standing() ? "PROPOSED" : "DECIDED",
                slot.needsSecondPerson(),
                slot.proposedAt().map(Instant::toString).orElse(null),
                slot.disposedAt().map(Instant::toString).orElse(null))).orElse(null);
        return new RiskCaseView(
                riskCase.id().value().toString(),
                riskCase.kind().name(),
                riskCase.subject().kind().name(),
                riskCase.subject().ref(),
                riskCase.status().name(),
                riskCase.revision(),
                riskCase.level().name(),
                riskCase.queue().name(),
                riskCase.takenBy().isPresent(),
                riskCase.slaHours(),
                riskCase.slaDeadline().toString(),
                riskCase.openingAlert().map(id -> id.value().toString()).orElse(null),
                riskCase.openingAssessment().map(id -> id.value().toString()).orElse(null),
                riskCase.predecessor().map(id -> id.value().toString()).orElse(null),
                riskCase.openedAt().toString(),
                opened,
                disposition);
    }

    /** The opening's request, judged at the boundary: an alert, or a kind and a subject - malformed is a 422. */
    private static Cases.OpenRequest openRequest(RiskCaseController.RiskCaseOpenRequest request) {
        if (request.alertId() != null) {
            if (request.kind() != null || request.subjectKind() != null || request.subjectRef() != null) {
                throw invalid("a case is opened on an alert, or on a kind and a subject - not both");
            }
            return Cases.OpenRequest.fromAlert(RiskAlertId.of(uuid(request.alertId(), "the alert")));
        }
        if (request.kind() == null || request.subjectKind() == null || request.subjectRef() == null) {
            throw invalid("a case is opened on an alert, or on a kind and a subject");
        }
        return Cases.OpenRequest.of(CaseKind.valueOf(request.kind()),
                SignalSubject.of(SubjectKind.valueOf(request.subjectKind()), uuid(request.subjectRef(), "the subject")));
    }

    /** The evidence's references - malformed, repeated or none at all is a 422. */
    private static AlertEvidence evidence(RiskCaseController.RiskCaseActionRequest request) {
        try {
            return new AlertEvidence(
                    references(request.signalIds()).stream().map(RiskSignalId::of).toList(),
                    references(request.assessmentIds()).stream().map(RiskAssessmentId::of).toList(),
                    references(request.journalEntryIds()));
        } catch (IllegalArgumentException repeated) {
            throw invalid("each reference is named once, at most 100 of each kind");
        }
    }

    private static List<UUID> references(List<String> raw) {
        return raw == null ? List.of() : raw.stream().map(value -> uuid(value, "a reference")).toList();
    }

    private static UUID uuid(String raw, String what) {
        try {
            return UUID.fromString(Objects.requireNonNull(raw, "raw"));
        } catch (IllegalArgumentException malformed) {
            throw invalid(what + " is named by its identifier");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(RiskErrorCode.CASE_REQUEST_INVALID, "A risk case command was refused", detail);
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A risk case command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }

    private static RiskCaseId caseId(String raw) {
        try {
            return RiskCaseId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static UUID dispositionId(String raw) {
        try {
            return UUID.fromString(Objects.requireNonNull(raw, "raw"));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk case act runs inside a correlation scope"))
                .correlationId();
    }
}
