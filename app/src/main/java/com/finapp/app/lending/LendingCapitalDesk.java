package com.finapp.app.lending;

import com.finapp.ledger.SupportedCurrencies;
import com.finapp.lending.CapitalContributionId;
import com.finapp.lending.CapitalContributionStatus;
import com.finapp.lending.CapitalContributionStore;
import com.finapp.lending.CapitalRecognitions;
import com.finapp.lending.LendingAuditAction;
import com.finapp.lending.LendingCapitalAdministration;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingErrorCode;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the lending-capital doors (`P11-TSK-004`; ADR-0096 sections 4-6, PHASE_11_PLAN.md section 9):
 * key every act per principal ({@code lending.capital:<type>:<id>}), run each in one transaction, and translate the
 * domain's refusals onto the error contract; serve the capital report from one {@code REPEATABLE READ} snapshot with
 * its audit record in the same transaction. The four-eyes rule, the machine and the arbiters are the domain's and
 * {@code lending V002}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class LendingCapitalDesk {

    static final String SCOPE = "lending.capital:";

    /** The report's bound on contributions: {@code truncated} says when there were more. */
    static final int CONTRIBUTIONS_BOUND = 100;

    @NonNull private final LendingCapitalAdministration administration;
    @NonNull private final CapitalContributionStore store;
    @NonNull private final CapitalRecognitions recognitions;
    @NonNull private final LendingCapitalProof proof;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A contribution's receipt: its id, status, and - once approved - the expectation it opened. */
    public record CapitalContributionReceipt(String id, String status, String expectationRef) {}

    /** The capital report: per supported currency, and the most recent contributions with their recognition. */
    public record CapitalReport(
            List<CurrencyCapitalView> currencies, List<ContributionView> contributions, boolean truncated) {}

    /**
     * One currency, in minor units at its scale: the capital recognised from bank evidence, the principal committed and
     * outstanding, the headroom, the safeguarding term {@code lending_funded}, and whether the capital proof holds.
     */
    public record CurrencyCapitalView(
            String currency,
            int scale,
            long recognisedMinor,
            long committedMinor,
            long outstandingMinor,
            long headroomMinor,
            long lendingFundedMinor,
            boolean proofHolds) {}

    /** One contribution: the proposal, its decision, and - once approved - its derived recognition. */
    public record ContributionView(
            String id,
            String currency,
            int scale,
            long amountMinor,
            String reference,
            String status,
            String recognition,
            String proposedAt,
            String decidedAt,
            String expectedBy,
            String recognisedOn) {}

    public CapitalContributionReceipt propose(
            String idempotencyKey, LendingCapitalController.ContributionProposalRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CurrencyCode currency = currency(request.currency());
        return keyed(actor, idempotencyKey, "propose|" + request.currency() + "|" + request.amountMinor() + "|"
                        + request.reference() + "|" + request.reason(),
                uow -> new CapitalContributionReceipt(
                        administration.propose(uow, currency, request.amountMinor(), request.reference(),
                                        request.reason(), actor, correlation)
                                .id().value().toString(),
                        CapitalContributionStatus.PROPOSED.name(),
                        null));
    }

    public CapitalContributionReceipt approve(String idempotencyKey, String rawId, String reason) {
        CapitalContributionId id = contributionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "approve|" + id.value() + "|" + reason,
                uow -> receipt(administration.approve(uow, id, actor, reason, correlation)));
    }

    public CapitalContributionReceipt reject(String idempotencyKey, String rawId, String reason) {
        CapitalContributionId id = contributionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "reject|" + id.value() + "|" + reason,
                uow -> receipt(administration.reject(uow, id, actor, reason, correlation)));
    }

    /** The capital report - one snapshot, its audit record ({@code lending.ReportRead}) in the same transaction. */
    public CapitalReport capital() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return transactions.inTransaction(unitOfWork -> {
            try (Statement snapshot = unitOfWork.createStatement()) {
                snapshot.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            } catch (SQLException failure) {
                throw new IllegalStateException("the capital report could not open its snapshot: " + failure.getSQLState());
            }
            List<CurrencyCapitalView> currencies = new ArrayList<>();
            for (CurrencyCode currency : SupportedCurrencies.ALL) {
                LendingCapitalProof.CurrencyVerdict verdict = proof.prove(unitOfWork, currency);
                currencies.add(new CurrencyCapitalView(
                        currency.code(),
                        verdict.terms().capital().scale(),
                        verdict.terms().capital().minorUnits(),
                        verdict.terms().committed().minorUnits(),
                        verdict.terms().outstanding().minorUnits(),
                        verdict.terms().headroom().minorUnits(),
                        verdict.lendingFunded().minorUnits(),
                        verdict.holds()));
            }
            List<CapitalContributionStore.ContributionRow> rows = store.recent(unitOfWork, CONTRIBUTIONS_BOUND + 1);
            boolean truncated = rows.size() > CONTRIBUTIONS_BOUND;
            List<CapitalContributionStore.ContributionRow> served =
                    truncated ? rows.subList(0, CONTRIBUTIONS_BOUND) : rows;
            Map<String, CapitalRecognitions.ExpectationReading> readings =
                    recognitions.expectations(unitOfWork, served.stream()
                            .flatMap(row -> row.expectationRef().stream())
                            .toList());
            List<ContributionView> contributions = new ArrayList<>();
            for (CapitalContributionStore.ContributionRow row : served) {
                Optional<CapitalRecognitions.ExpectationReading> reading =
                        row.expectationRef().map(readings::get);
                contributions.add(new ContributionView(
                        row.id().value().toString(),
                        row.currency().code(),
                        row.amount().scale(),
                        row.amount().minorUnits(),
                        row.reference(),
                        row.status().name(),
                        reading.map(found -> found.status().name()).orElse(null),
                        row.proposedAt().toString(),
                        row.decidedAt().map(Instant::toString).orElse(null),
                        reading.map(found -> found.expectedBy().toString()).orElse(null),
                        reading.flatMap(CapitalRecognitions.ExpectationReading::recognisedOn)
                                .map(LocalDate::toString).orElse(null)));
            }
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    LendingAuditAction.REPORT_READ, "lending_report", "capital", Optional.empty(), AuditOutcome.SUCCEEDED,
                    correlation,
                    Optional.of("report=capital, currencies=" + currencies.size() + ", contributions="
                            + contributions.size() + ", truncated=" + truncated)));
            return new CapitalReport(List.copyOf(currencies), List.copyOf(contributions), truncated);
        });
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private CapitalContributionReceipt keyed(
            Actor actor,
            String idempotencyKey,
            String fingerprinted,
            Function<Connection, CapitalContributionReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    CapitalContributionReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.status() + "|"
                            + (receipt.expectationRef() == null ? "" : receipt.expectationRef());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a capital act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new CapitalContributionReceipt(stored[0], stored[1], stored[2].isEmpty() ? null : stored[2]);
    }

    private static CapitalContributionReceipt receipt(LendingCapitalAdministration.Decided decided) {
        return new CapitalContributionReceipt(
                decided.id().value().toString(), decided.status().name(), decided.expectationRef().orElse(null));
    }

    /**
     * {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own after the act rolled back
     * (the Phase 11 gate's R4), then refused as before.
     */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (LendingCapitalAdministration.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                administration.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (LendingCapitalAdministration.ContributionNotFound unknown) {
            throw notFound();
        } catch (LendingCapitalAdministration.SelfApprovalRefused self) {
            throw refused(LendingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (LendingCapitalAdministration.ProposalPending pending) {
            throw refused(LendingErrorCode.PROPOSAL_PENDING, pending);
        } catch (LendingCapitalAdministration.ProposalNotPending decided) {
            throw refused(LendingErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (LendingCapitalAdministration.ReasonRequired reason) {
            throw refused(LendingErrorCode.REASON_REQUIRED, reason);
        } catch (LendingCapitalAdministration.ContributionInvalid invalid) {
            throw refused(LendingErrorCode.CONTRIBUTION_INVALID, invalid);
        }
    }

    private static ApiException refused(LendingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A lending-capital command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No capital contribution answers the request", "no such record.");
    }

    private static CurrencyCode currency(String raw) {
        try {
            return CurrencyCode.of(raw);
        } catch (RuntimeException malformed) {
            throw new ApiException(LendingErrorCode.CONTRIBUTION_INVALID, "A lending-capital command was refused",
                    "the currency is not an ISO 4217 code.");
        }
    }

    private static CapitalContributionId contributionId(String raw) {
        try {
            return CapitalContributionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a lending-capital command runs inside a correlation scope"))
                .correlationId();
    }
}
