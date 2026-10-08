package com.finapp.app.credit;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.Scorecard;
import com.finapp.credit.ScorecardAdministration;
import com.finapp.credit.ScorecardFamily;
import com.finapp.credit.ScorecardModelVersionId;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the scorecard doors (`P10-TSK-011`): parse the points table exactly, key every act per
 * principal ({@code credit.scorecard:<type>:<id>}), run each in one transaction, and translate the domain's refusals
 * onto the error contract. The four-eyes rule, the machine and the frozen bands are the domain's and
 * {@code credit V006}'s; nothing here decides them.
 */
@RequiredArgsConstructor
public final class ScorecardAdministrationDesk {

    static final String SCOPE = "credit.scorecard:";

    @NonNull private final ScorecardAdministration administration;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;

    /** A version's receipt: its id, number and status, and the predecessor an activation retired. */
    public record ScorecardReceipt(String id, int version, String status, String retiredId) {}

    public ScorecardReceipt propose(String idempotencyKey, ScorecardAdministrationController.ScorecardProposalRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        ScorecardFamily family = guarded(() -> family(request.family()));
        Scorecard scorecard = guarded(() -> scorecard(request));
        return keyed(actor, idempotencyKey, "propose|" + request, uow -> {
            ScorecardAdministration.Proposed proposed =
                    administration.propose(uow, family, scorecard, request.reason(), actor, correlation);
            return new ScorecardReceipt(proposed.id().value().toString(), proposed.version(), "PROPOSED", null);
        });
    }

    public ScorecardReceipt approve(String idempotencyKey, String rawId, String reason) {
        ScorecardModelVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "approve|" + id.value() + "|" + reason,
                uow -> receipt(administration.approve(uow, id, actor, reason, correlation)));
    }

    public ScorecardReceipt reject(String idempotencyKey, String rawId, String reason) {
        ScorecardModelVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, idempotencyKey, "reject|" + id.value() + "|" + reason,
                uow -> receipt(administration.reject(uow, id, actor, reason, correlation)));
    }

    // ------------------------------------------------------------------ plumbing

    /** One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing. */
    private ScorecardReceipt keyed(
            Actor actor, String idempotencyKey, String fingerprinted, Function<java.sql.Connection, ScorecardReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.inTransaction(
                unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    ScorecardReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.version() + "|" + receipt.status() + "|"
                            + (receipt.retiredId() == null ? "" : receipt.retiredId());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                })));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a scorecard act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new ScorecardReceipt(stored[0], Integer.parseInt(stored[1]), stored[2], stored[3].isEmpty() ? null : stored[3]);
    }

    private static ScorecardFamily family(String raw) {
        try {
            return ScorecardFamily.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw new Scorecard.ScorecardInvalid("the family is not one this build holds");
        }
    }

    /** The request in the domain's words; any malformed term is the table's own defect. */
    private static Scorecard scorecard(ScorecardAdministrationController.ScorecardProposalRequest request) {
        List<Scorecard.AttributeBands> attributes = request.attributes().stream()
                .map(attribute -> new Scorecard.AttributeBands(code(attribute.code()), attribute.absentPoints(),
                        attribute.bands().stream().map(ScorecardAdministrationDesk::band).toList()))
                .toList();
        return new Scorecard(request.basePoints(), attributes);
    }

    private static Scorecard.Band band(ScorecardAdministrationController.BandRequest band) {
        if (band.codes() != null) {
            if (band.lower() != null || band.upper() != null) {
                throw new Scorecard.ScorecardInvalid("a band is a range or a code set, never both");
            }
            if (new HashSet<>(band.codes()).size() != band.codes().size()) {
                throw new Scorecard.ScorecardInvalid("a code set names each code once");
            }
            return new Scorecard.Codes(new HashSet<>(band.codes()), band.points());
        }
        return new Scorecard.Range(band.lower(), band.upper(), band.points());
    }

    private static CreditAttributeCode code(String raw) {
        try {
            return CreditAttributeCode.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            throw new Scorecard.ScorecardInvalid("an attribute code is not in the vocabulary");
        }
    }

    private static ScorecardReceipt receipt(ScorecardAdministration.Decided decided) {
        return new ScorecardReceipt(decided.id().value().toString(), decided.version(), decided.status().name(),
                decided.retired().map(id -> id.value().toString()).orElse(null));
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (ScorecardAdministration.ScorecardNotFound unknown) {
            throw notFound();
        } catch (ScorecardAdministration.SelfApprovalRefused self) {
            throw refused(CreditErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (ScorecardAdministration.PolicyStale stale) {
            throw refused(CreditErrorCode.POLICY_STALE, stale);
        } catch (ScorecardAdministration.ProposalPending pending) {
            throw refused(CreditErrorCode.PROPOSAL_PENDING, pending);
        } catch (ScorecardAdministration.ReasonRequired reason) {
            throw refused(CreditErrorCode.REASON_REQUIRED, reason);
        } catch (Scorecard.ScorecardInvalid invalid) {
            throw refused(CreditErrorCode.SCORECARD_INVALID, invalid);
        }
    }

    private static ApiException refused(CreditErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A scorecard administrator's command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(CreditErrorCode.NOT_FOUND, "No scorecard model version matches the requested identifier",
                "no such record.");
    }

    private static ScorecardModelVersionId versionId(String raw) {
        try {
            return ScorecardModelVersionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a scorecard command runs inside a correlation scope"))
                .correlationId();
    }
}
