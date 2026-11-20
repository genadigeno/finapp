package com.finapp.app.accounts;

import com.finapp.accounts.AccountRestriction;
import com.finapp.accounts.AccountRestrictionId;
import com.finapp.accounts.AccountRestrictionRefusedException;
import com.finapp.accounts.AccountRestrictions;
import com.finapp.accounts.AccountsAuditAction;
import com.finapp.accounts.AccountsErrorCode;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.RestrictionKind;
import com.finapp.accounts.RestrictionReason;
import com.finapp.accounts.RestrictionStatus;
import com.finapp.app.api.RecordedReads;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The boundary half of an agreement's restriction (`P17-TSK-028`, ADR-0153 decision 4): parse exactly, key each act per
 * principal, run it in one transaction, translate accounts' refusals onto the error contract. The rules - the kind's status,
 * one open proposal, four eyes, the reason code's kind, a screened reason - are accounts' and V003's.
 *
 * <p><strong>Keyed</strong> ({@code INV-IDEM-01}): the platform's {@link IdempotentExecutor} in the act's own transaction,
 * scoped {@value #SCOPE} and the principal ({@code X-TSK-003}), fingerprinted over the route's fields with the reason as its
 * digest, so the stored key carries no prose. A retry after a lost answer replays the first receipt; another request under
 * the same key is {@code 409} ({@code INV-IDEM-03}).
 */
@RequiredArgsConstructor
public final class AccountRestrictionDesk {

    static final String SCOPE = "accounts.restriction:";

    @NonNull private final AccountRestrictions restrictions;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final RecordedReads reads;

    /**
     * A restriction as its approver reads it (`P17-TSK-124`): the agreement and its status now, the kind and its reason code,
     * who proposed it, when and why, whether the caller did, and once decided the decision's instant and reason ({@code null}
     * until then). Never who decided.
     */
    public record AccountRestrictionView(
            String restrictionId, String accountId, String accountStatus, String kind, String reasonCode, String status,
            String reason, String proposedBy, boolean proposedByCaller, String proposedAt, String decidedAt,
            String decisionReason) {

        @Override
        public String toString() {
            // The reasons are a person's prose about a customer's account: they never reach a log.
            return "AccountRestrictionView[" + restrictionId + ", " + kind + ", " + status + "]";
        }
    }

    /**
     * Restriction {@code rawRestrictionId}, recorded {@code accounts.RestrictionRead} in the read's own transaction
     * (`P17-TSK-124`). An unknown or malformed identifier: 404, and nothing recorded.
     */
    public AccountRestrictionView read(String rawRestrictionId) {
        AccountRestrictionId id = uuid(rawRestrictionId).map(AccountRestrictionId::of)
                .orElseThrow(AccountRestrictionDesk::notFound);
        String caller = SecurityContext.require().id();
        AccountRestrictions.Read read = transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return restrictions.read(unitOfWork, id).map(found -> {
                    reads.record(unitOfWork, AccountsAuditAction.RESTRICTION_READ, "AccountRestriction",
                            id.value().toString(), "account=" + found.restriction().accountId().value() + ", kind="
                                    + found.restriction().kind() + ", status=" + found.restriction().status());
                    return found;
                });
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        }).orElseThrow(AccountRestrictionDesk::notFound);
        AccountRestriction restriction = read.restriction();
        return new AccountRestrictionView(restriction.id().value().toString(), restriction.accountId().value().toString(),
                read.accountStatus().name(), restriction.kind().name(), restriction.reasonCode().name(),
                restriction.status().name(), restriction.proposalReason(), restriction.proposedBy(),
                restriction.proposedBy().equals(caller), restriction.proposedAt().toString(),
                restriction.decision().map(AccountRestriction.Decision::decidedAt).map(Instant::toString).orElse(null),
                restriction.decision().map(AccountRestriction.Decision::reason).orElse(null));
    }

    /** A restriction's receipt: identifiers, the kind, its status and the agreement's status after the act. */
    public record RestrictionReceipt(String restrictionId, String accountId, String kind, String status, String accountStatus) {}

    /** Proposes {@code kind} on the agreement. */
    public RestrictionReceipt propose(String idempotencyKey, String rawAccountId, RestrictionKind kind, String rawReasonCode,
            String reason) {
        CustomerAccountId accountId = uuid(rawAccountId).map(CustomerAccountId::of).orElseThrow(AccountRestrictionDesk::notFound);
        RestrictionReason code = reasonCode(rawReasonCode);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("PROPOSE|" + accountId.value() + "|" + kind.name() + "|"
                + code.name() + "|" + digest(reason)).getBytes(StandardCharsets.UTF_8));
        return keyed(key, fingerprint, uow -> restrictions.propose(uow, accountId, kind, code, reason)
                .map(proposed -> receipt(proposed, kind.from().name()))
                .orElseThrow(AccountRestrictionDesk::notFound));
    }

    /** Approves or rejects the restriction. */
    public RestrictionReceipt decide(String idempotencyKey, String rawRestrictionId, RestrictionStatus outcome, String reason) {
        AccountRestrictionId id = uuid(rawRestrictionId).map(AccountRestrictionId::of).orElseThrow(AccountRestrictionDesk::notFound);
        Actor actor = SecurityContext.require();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(("DECIDE|" + id.value() + "|" + outcome.name() + "|"
                + digest(reason)).getBytes(StandardCharsets.UTF_8));
        return keyed(key, fingerprint, uow -> (outcome == RestrictionStatus.APPROVED
                        ? restrictions.approve(uow, id, reason)
                        : restrictions.reject(uow, id, reason))
                .map(decided -> receipt(decided.restriction(), decided.accountStatus().name()))
                .orElseThrow(AccountRestrictionDesk::notFound));
    }

    private RestrictionReceipt keyed(IdempotencyKey key, RequestFingerprint fingerprint,
            Function<Connection, RestrictionReceipt> act) {
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            return executor.execute(unitOfWork, key, fingerprint, uow -> {
                RestrictionReceipt receipt = act.apply(uow);
                String stored = String.join("|", receipt.restrictionId(), receipt.accountId(), receipt.kind(),
                        receipt.status(), receipt.accountStatus());
                return CommandResult.succeeded(StoredResponse.of(stored.getBytes(StandardCharsets.UTF_8), "text/plain"));
            });
        }));
        String[] stored = new String(outcome.body().orElseThrow(
                () -> new IllegalStateException("a restriction act stores its receipt")), StandardCharsets.UTF_8).split("\\|", -1);
        return new RestrictionReceipt(stored[0], stored[1], stored[2], stored[3], stored[4]);
    }

    private static RestrictionReceipt receipt(AccountRestriction restriction, String accountStatus) {
        return new RestrictionReceipt(restriction.id().value().toString(), restriction.accountId().value().toString(),
                restriction.kind().name(), restriction.status().name(), accountStatus);
    }

    /** accounts' refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (AccountRestrictionRefusedException refused) {
            AccountsErrorCode code = switch (refused.reason()) {
                case NOT_APPLICABLE -> AccountsErrorCode.RESTRICTION_NOT_APPLICABLE;
                case ALREADY_PROPOSED -> AccountsErrorCode.RESTRICTION_ALREADY_PROPOSED;
                case ALREADY_DECIDED -> AccountsErrorCode.RESTRICTION_ALREADY_DECIDED;
                case SELF_DECISION -> AccountsErrorCode.RESTRICTION_SELF_DECISION;
                case REASON_INVALID -> AccountsErrorCode.RESTRICTION_REASON_INVALID;
            };
            throw new ApiException(code, "An account restriction act was refused", refused.getMessage());
        }
    }

    private static RestrictionReason reasonCode(String raw) {
        try {
            return RestrictionReason.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new ApiException(AccountsErrorCode.RESTRICTION_REASON_INVALID, "An account restriction act was refused",
                    "reasonCode is one of " + Arrays.toString(RestrictionReason.values()) + ".");
        }
    }

    /** A canonical UUIDv7 (ADR-0013), or nothing: anything else names nothing - a 404. */
    private static Optional<UUID> uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) && parsed.version() == 7 && parsed.variant() == 2
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No account or restriction matches the requested identifier",
                "no such record.");
    }
}
