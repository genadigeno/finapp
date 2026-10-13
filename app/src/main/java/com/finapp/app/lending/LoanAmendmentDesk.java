package com.finapp.app.lending;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.lending.AcceptanceEvidence;
import com.finapp.lending.AllocationAnomaly;
import com.finapp.lending.LendingCapitalAdministration;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanAmendment;
import com.finapp.lending.LoanAmendments;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanTerms;
import com.finapp.lending.RepaymentReversals;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the restructuring doors (`P11-TSK-027`; {@code PHASE_11_PLAN.md} sections 9 and 11, ADR-0099 points
 * 1, 2 and 5): the operator's proposal, approval and rejection under four eyes - each keyed per principal under the
 * servicing doors' one scope ({@code lending.servicing:<type>:<id>}) - and the customer's read and acceptance of an
 * amendment offered to them, the acceptance a {@code MULTI_FACTOR} act keyed {@code lending.amendment-acceptance:<type>:<id>}.
 * Each act runs in one transaction under the {@link IdempotentExecutor} claim, so a retried key replays the recorded receipt
 * and a refusal, which writes nothing, rolls the claim back with it. The four-eyes rule, the bounds, the lock order and the
 * arbiters are the domain's and {@code lending V023}'s; nothing here decides them.
 *
 * <p><strong>Self-dealing</strong> is the one refusal recorded: a {@code lending.SelfDealingRefused} {@code FAILED} record in
 * a transaction of its own after the act rolled back. Every other loser records nothing.
 */
@RequiredArgsConstructor
public final class LoanAmendmentDesk {

    static final String ACCEPTANCE_SCOPE = "lending.amendment-acceptance:";

    /** A rate as the body spells it: a plain decimal fraction, no sign, no exponent. */
    private static final Pattern RATE = Pattern.compile("(0|1)(\\.[0-9]{1,8})?");

    @NonNull private final LoanAmendments amendments;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final LoanAmendmentMetrics metrics;

    /**
     * An amendment as answered - and replayed: the version it amends and that version's rate, the amended rate, the
     * remaining instalments, whether arrears are re-scheduled, its machine (derived now), the document's hash, its instants
     * and - accepted - the agreement version it made. Never a reason or a person (held on the record and the audit trail).
     */
    public record AmendmentView(
            String amendmentId,
            String loanId,
            int amendsVersion,
            String currentRate,
            String nominalAnnualRate,
            int remainingInstalments,
            boolean rescheduleArrears,
            String status,
            String documentSha256,
            String proposedAt,
            String decidedAt,
            String expiresAt,
            Integer agreementVersion) {}

    /** What the customer reads: the amendment offered to them and the document they accept by its hash. */
    public record OfferedAmendmentView(AmendmentView amendment, String document) {}

    /**
     * An acceptance as answered - and replayed: the amendment {@code ACCEPTED}, the agreement and schedule versions it
     * made and the arrears it re-scheduled into the new schedule (an exact decimal in {@code currency}, zero for none).
     */
    public record AmendmentAcceptedView(
            String amendmentId,
            String loanId,
            String status,
            int agreementVersion,
            int scheduleVersion,
            String arrearsRescheduled,
            String currency) {}

    // ------------------------------------------------------------------ the operator's acts

    /** Proposes an amendment - a holder of {@code LOAN_SERVICE}; nothing is posted, nothing amended. */
    public AmendmentView propose(String rawLoanId, String idempotencyKey,
            LoanServicingController.AmendmentProposalRequest body) {
        LoanId loan = loanId(rawLoanId);
        BigDecimal rate = rate(body.nominalAnnualRate());
        if (body.remainingInstalments() == null || body.rescheduleArrears() == null) {
            throw invalid("the remaining instalments and whether arrears are re-scheduled are required.");
        }
        int remaining = body.remainingInstalments();
        boolean arrears = body.rescheduleArrears();
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, LoanAmendmentMetrics.Outcome.PROPOSED,
                String.join("|", "propose", loan.value().toString(), rate.toPlainString(), Integer.toString(remaining),
                        Boolean.toString(arrears), String.valueOf(body.reason())),
                uow -> new Acted(amendments.propose(uow, loan, rate, remaining, arrears, body.reason(), actor,
                        correlation), false));
    }

    /** Approves a pending amendment - a holder of {@code LOAN_SERVICE_APPROVE} other than its proposer. */
    public AmendmentView approve(String rawLoanId, String rawAmendmentId, String idempotencyKey,
            LoanServicingController.AmendmentDecisionRequest body) {
        LoanId loan = loanId(rawLoanId);
        UUID amendment = id(rawAmendmentId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, LoanAmendmentMetrics.Outcome.APPROVED,
                String.join("|", "approve", loan.value().toString(), amendment.toString(), String.valueOf(body.reason())),
                uow -> decided(amendments.approve(uow, loan, amendment, body.reason(), actor, correlation)));
    }

    /** Rejects a pending amendment - a holder of {@code LOAN_SERVICE}, the proposer's withdrawal included. */
    public AmendmentView reject(String rawLoanId, String rawAmendmentId, String idempotencyKey,
            LoanServicingController.AmendmentDecisionRequest body) {
        LoanId loan = loanId(rawLoanId);
        UUID amendment = id(rawAmendmentId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, LoanAmendmentMetrics.Outcome.REJECTED,
                String.join("|", "reject", loan.value().toString(), amendment.toString(), String.valueOf(body.reason())),
                uow -> decided(amendments.reject(uow, loan, amendment, body.reason(), actor, correlation)));
    }

    // ------------------------------------------------------------------ the customer's read and acceptance

    /** The caller's own loan's amendment offered to them, with its document; anything else is the uniform {@code 404}. */
    public OfferedAmendmentView read(Session current, String rawLoanId, String rawAmendmentId) {
        LoanId loan = loanId(rawLoanId);
        UUID amendment = id(rawAmendmentId);
        try {
            return transactions.inSnapshot(unitOfWork -> {
                LoanAmendments.Offered offered = amendments.read(unitOfWork, loan, partyOf(unitOfWork, current), amendment);
                return new OfferedAmendmentView(view(offered.amendment()), offered.document());
            });
        } catch (LoanAmendments.AmendmentNotFound absent) {
            throw notFound();
        }
    }

    /** Accepts the caller's own loan's amendment - a {@code MULTI_FACTOR} act: agreement and schedule version n+1. */
    public AmendmentAcceptedView accept(Session current, String rawLoanId, String rawAmendmentId, String idempotencyKey,
            LoanController.AmendmentAcceptanceBody body, Optional<String> clientIp, Optional<String> userAgent) {
        LoanId loan = loanId(rawLoanId);
        UUID amendment = id(rawAmendmentId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(ACCEPTANCE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.amendment-acceptance",
                loan.value().toString(), amendment.toString(), body.documentSha256()).getBytes(StandardCharsets.UTF_8));
        AcceptanceEvidence evidence = new AcceptanceEvidence(current.identityId().value(), current.id().value(),
                current.assurance().name(), clientIp, userAgent);
        LoanAmendments.Acceptance acceptance = new LoanAmendments.Acceptance(body.documentSha256(), evidence);
        AtomicReference<Boolean> converged = new AtomicReference<>(Boolean.FALSE);
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireMultiFactor(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    LoanAmendments.Accepted accepted =
                            amendments.accept(claimed, loan, party, amendment, acceptance, actor, correlation);
                    converged.set(accepted.replayed());
                    return CommandResult.succeeded(stored(view(accepted)));
                });
            });
        } catch (LoanAmendments.AmendmentNotFound absent) {
            throw notFound();
        } catch (LoanAmendments.AmendmentLapsed lapsed) {
            metrics.record(LoanAmendmentMetrics.Outcome.LAPSED);
            throw refused(LendingErrorCode.AMENDMENT_LAPSED, lapsed);
        } catch (LoanAmendments.DocumentChanged changed) {
            metrics.record(LoanAmendmentMetrics.Outcome.DOCUMENT_CHANGED);
            throw new ApiException(LendingErrorCode.TERMS_CHANGED, "The document accepted is not the amendment's",
                    changed.getMessage() + "; read the amendment and accept the document it shows.");
        } catch (LoanTerms.TermsInvalid invalid) {
            metrics.record(LoanAmendmentMetrics.Outcome.TERMS_INVALID);
            throw refused(LendingErrorCode.TERMS_INVALID, invalid);
        } catch (AllocationAnomaly anomaly) {
            throw anomaly;
        }
        AmendmentAcceptedView accepted =
                replayedAcceptance(outcome.body().orElseThrow(() -> new IllegalStateException("an acceptance stores its receipt")));
        if (outcome.executed() && !converged.get()) {
            metrics.record(LoanAmendmentMetrics.Outcome.ACCEPTED);
        }
        return accepted;
    }

    // ------------------------------------------------------------------ the views

    static AmendmentView view(LoanAmendment amendment) {
        return new AmendmentView(
                amendment.id().toString(),
                amendment.loan().value().toString(),
                amendment.amendsVersion(),
                amendment.amendsRate().toPlainString(),
                amendment.nominalAnnualRate().toPlainString(),
                amendment.remainingInstalments(),
                amendment.rescheduleArrears(),
                amendment.status().name(),
                amendment.documentSha256Hex(),
                amendment.proposedAt().toString(),
                amendment.decidedAt().map(Instant::toString).orElse(null),
                amendment.expiresAt().map(Instant::toString).orElse(null),
                amendment.acceptedVersion().orElse(null));
    }

    private static AmendmentAcceptedView view(LoanAmendments.Accepted accepted) {
        return new AmendmentAcceptedView(accepted.amendment().id().toString(),
                accepted.amendment().loan().value().toString(), accepted.amendment().status().name(),
                accepted.agreementVersion(), accepted.scheduleVersion(),
                accepted.arrearsRescheduled().toBigDecimal().toPlainString(), accepted.arrearsRescheduled().currency().code());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed operator act in one transaction - a lost response replays the stored receipt; a refusal stores nothing. The
     * act's meter is told after the commit, once (a replay or a converged retry counts nothing); a refusal is counted by
     * reason; a self-dealing attempt is recorded {@code FAILED} in a transaction of its own.
     */
    private AmendmentView keyed(
            Actor actor,
            CorrelationId correlation,
            String idempotencyKey,
            LoanAmendmentMetrics.Outcome success,
            String fingerprinted,
            Function<Connection, Acted> act) {
        IdempotencyKey key = new IdempotencyKey(LoanServicingDesk.SCOPE + actor.type().name() + ":" + actor.id(),
                idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<Acted> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                Acted done = act.apply(uow);
                acted.set(done);
                return CommandResult.succeeded(stored(view(done.amendment())));
            }));
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                amendments.recordRefusal(uow, selfDealing, actor, correlation);
                return null;
            });
            metrics.record(LoanAmendmentMetrics.Outcome.SELF_DEALING_REFUSED);
            throw refused(LendingErrorCode.SELF_DEALING_REFUSED, selfDealing);
        } catch (LoanAmendments.AmendmentNotFound unknown) {
            throw notFound();
        } catch (LendingCapitalAdministration.ReasonRequired reason) {
            throw refused(LendingErrorCode.REASON_REQUIRED, reason);
        } catch (LoanTerms.TermsInvalid invalid) {
            metrics.record(LoanAmendmentMetrics.Outcome.TERMS_INVALID);
            throw refused(LendingErrorCode.TERMS_INVALID, invalid);
        } catch (LoanAmendments.AmendmentLapsed lapsed) {
            metrics.record(LoanAmendmentMetrics.Outcome.LAPSED);
            throw refused(LendingErrorCode.AMENDMENT_LAPSED, lapsed);
        } catch (LoanAmendments.LoanClosed closed) {
            metrics.record(LoanAmendmentMetrics.Outcome.LOAN_CLOSED);
            throw refused(LendingErrorCode.LOAN_CLOSED, closed);
        } catch (LoanAmendments.SelfApprovalRefused self) {
            metrics.record(LoanAmendmentMetrics.Outcome.SELF_APPROVAL_REFUSED);
            throw refused(LendingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (LoanAmendments.ProposalPending pending) {
            metrics.record(LoanAmendmentMetrics.Outcome.PROPOSAL_PENDING);
            throw refused(LendingErrorCode.PROPOSAL_PENDING, pending);
        } catch (LoanAmendments.ProposalNotPending decided) {
            metrics.record(LoanAmendmentMetrics.Outcome.PROPOSAL_NOT_PENDING);
            throw refused(LendingErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (AllocationAnomaly anomaly) {
            throw anomaly;
        }
        if (outcome.executed() && acted.get() != null && !acted.get().replayed()) {
            metrics.record(success);
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("an amendment act stores its receipt")));
    }

    /** What an act did: the amendment it left, and whether the same person's retry converged on their own decision. */
    private record Acted(LoanAmendment amendment, boolean replayed) {}

    private static Acted decided(LoanAmendments.Decided decided) {
        return new Acted(decided.amendment(), decided.replayed());
    }

    /** The receipt: the view's fields, '|'-separated, an absent one empty. */
    private static StoredResponse stored(AmendmentView view) {
        String body = String.join("|", view.amendmentId(), view.loanId(), Integer.toString(view.amendsVersion()),
                view.currentRate(), view.nominalAnnualRate(), Integer.toString(view.remainingInstalments()),
                Boolean.toString(view.rescheduleArrears()), view.status(), view.documentSha256(), view.proposedAt(),
                nullable(view.decidedAt()), nullable(view.expiresAt()),
                view.agreementVersion() == null ? "" : view.agreementVersion().toString());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static AmendmentView replayed(byte[] body) {
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new AmendmentView(f[0], f[1], Integer.parseInt(f[2]), f[3], f[4], Integer.parseInt(f[5]),
                Boolean.parseBoolean(f[6]), f[7], f[8], f[9], f[10].isEmpty() ? null : f[10],
                f[11].isEmpty() ? null : f[11], f[12].isEmpty() ? null : Integer.valueOf(f[12]));
    }

    private static StoredResponse stored(AmendmentAcceptedView view) {
        String body = String.join("|", view.amendmentId(), view.loanId(), view.status(),
                Integer.toString(view.agreementVersion()), Integer.toString(view.scheduleVersion()),
                view.arrearsRescheduled(), view.currency());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static AmendmentAcceptedView replayedAcceptance(byte[] body) {
        String[] f = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new AmendmentAcceptedView(f[0], f[1], f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]), f[5], f[6]);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    /** The body's rate: a plain decimal fraction 0 to 1, at most 8 places - its bounds and canonical spelling the domain's. */
    private static BigDecimal rate(String raw) {
        if (raw == null || !RATE.matcher(raw).matches()) {
            throw invalid("the nominal annual rate must be a decimal fraction from 0 to 1 - 0.05 for 5 %.");
        }
        return com.finapp.app.api.DecimalText.parse(raw);
    }

    /** A {@code MULTI_FACTOR} session, always - the acceptance changes the borrower's contract (A19). */
    private static void requireMultiFactor(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "An amendment's acceptance requires a MULTI_FACTOR session");
        }
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static ApiException refused(LendingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A loan amendment command was refused", cause.getMessage() + ".");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The amendment was not valid", detail);
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan or amendment matches the request", "no such record.");
    }

    /** A loan id is a UUIDv7 (ADR-0013): any other identifier names no loan - the uniform 404, never a 500. */
    private static LoanId loanId(String raw) {
        try {
            return LoanId.of(id(raw));
        } catch (IllegalArgumentException notALoanId) {
            throw notFound();
        }
    }

    private static UUID id(String raw) {
        if (raw == null) {
            throw notFound();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an amendment command runs inside a correlation scope"))
                .correlationId();
    }
}
