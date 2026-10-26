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
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.ScreeningList;
import com.finapp.risk.ScreeningListVersion;
import com.finapp.risk.ScreeningListVersionId;
import com.finapp.risk.ScreeningListVersions;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the screening list doors (`P13-TSK-022`; {@link RiskPolicyDesk}'s shape): key every act per
 * principal ({@code risk.list:<type>:<id>}), run each in one transaction under its span, and translate the domain's
 * refusals onto the error contract - {@code 404 risk.NotFound}, {@code 409 risk.PolicyStale} (a second activation of one
 * version, or a version recorded before the one in force - the entry's code), {@code 403 risk.SelfApprovalRefused}
 * (recorded {@code FAILED} in a transaction of its own), {@code 422 risk.ReasonRequired}. The machine, four eyes and the
 * persons are the domain's and {@code risk V017}'s; nothing here decides them. The stored answer is the receipt: ids,
 * codes and the provider's label - never the reason.
 */
@RequiredArgsConstructor
public final class RiskScreeningListDesk {

    static final String SCOPE = "risk.list:";

    @NonNull private final ScreeningListVersions lists;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final RiskTransactions transactions;
    @NonNull private final RiskFlowScope flow;

    /** A list version's receipt: its id, list, label, ordinal and status, and the predecessor an activation retired. */
    public record RiskScreeningListReceipt(String id, String list, String version, int ordinal, String status,
            String retiredId) {}

    public RiskScreeningListReceipt record(String idempotencyKey, RiskScreeningListController.RiskScreeningListVersionRequest request) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        ScreeningList list = ScreeningList.valueOf(request.list());
        return flow.door(RiskSpans.LIST, () -> keyed(actor, idempotencyKey,
                "record|" + request.list() + "|" + request.version() + "|" + request.reason(),
                uow -> receipt(lists.record(uow, list, request.version(), request.reason(), actor, correlation)
                        .version(), null)));
    }

    public RiskScreeningListReceipt activate(String idempotencyKey, String rawId, String reason) {
        ScreeningListVersionId id = versionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(RiskSpans.LIST, () -> keyed(actor, idempotencyKey, "activate|" + id.value() + "|" + reason,
                uow -> {
                    ScreeningListVersions.Activated activated = lists.activate(uow, id, reason, actor, correlation);
                    return receipt(activated.version(),
                            activated.retired().map(retired -> retired.value().toString()).orElse(null));
                }));
    }

    // ------------------------------------------------------------------ plumbing

    private static RiskScreeningListReceipt receipt(ScreeningListVersion version, String retiredId) {
        return new RiskScreeningListReceipt(version.id().value().toString(), version.list().name(), version.version(),
                version.ordinal(), version.status().name(), retiredId);
    }

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private RiskScreeningListReceipt keyed(Actor actor, String idempotencyKey, String fingerprinted,
            Function<Connection, RiskScreeningListReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    RiskScreeningListReceipt receipt = act.apply(uow);
                    String stored = receipt.id() + "|" + receipt.list() + "|" + receipt.version() + "|" + receipt.ordinal()
                            + "|" + receipt.status() + "|" + (receipt.retiredId() == null ? "" : receipt.retiredId());
                    return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
                }))));
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a list act stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new RiskScreeningListReceipt(stored[0], stored[1], stored[2], Integer.parseInt(stored[3]), stored[4],
                stored[5].isEmpty() ? null : stored[5]);
    }

    /** {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own, then refused as before. */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (ScreeningListVersions.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                lists.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (ScreeningListVersions.ListVersionNotFound unknown) {
            throw notFound();
        } catch (ScreeningListVersions.SelfApprovalRefused self) {
            throw refused(RiskErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (ScreeningListVersions.ListVersionStale stale) {
            throw refused(RiskErrorCode.POLICY_STALE, stale);
        } catch (RiskReasons.ReasonRequired reason) {
            throw refused(RiskErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(RiskErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A screening list command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No screening list version answers the request",
                "no such record.");
    }

    private static ScreeningListVersionId versionId(String raw) {
        try {
            return ScreeningListVersionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a screening list command runs inside a correlation scope"))
                .correlationId();
    }
}
