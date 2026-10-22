package com.finapp.app.merchant;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.PermissionName;
import com.finapp.merchant.MerchantApiKey;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantApiKeys;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantNotKeyableException;
import com.finapp.merchant.UnknownMerchantApiKeyException;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** The operator's API-key surface behind the controller (`P6-TSK-002`). */
@RequiredArgsConstructor
public class MerchantApiKeyOperations {

    /**
     * The issuance response. {@code secret} is <strong>null on a replay</strong> and carries
     * the plaintext exactly once, on the call that minted the key — see
     * {@link MerchantApiKeys}: it is never stored, so a replay has nothing to render.
     *
     * <p>A plain {@code String} rather than a {@code Sensitive}, for the reason
     * {@code AuthenticatedSession} records: the platform's serialiser renders a
     * {@code Sensitive} as its mask, and the client would receive «redacted» for the one
     * value whose whole purpose is to be transmitted. {@code toString} is overridden instead,
     * which closes the logging half of {@code INV-AUD-02}.
     */
    public record IssuedKeyView(String keyId, String secret, boolean alreadyIssued) {

        @Override
        public String toString() {
            return "IssuedKeyView[keyId=" + keyId + ", alreadyIssued=" + alreadyIssued
                    + ", secret=" + com.finapp.sharedkernel.security.Sensitive.MASK + "]";
        }
    }

    /** A key as the operator lists it: metadata only — no secret, and no hash. */
    public record KeyView(String keyId, String status, String issuedAt, String revokedAt, List<String> scopes) {}

    /**
     * The permission each scope asks of its issuer BESIDE the route's {@code MERCHANT_ADMINISTER} (the Phase 12 -> 13
     * gate's A5-F1) - the conditional half of the route's register ({@code RoutePermissionRegisterTest} pins it). A key
     * carrying {@code BNPL_REFUND} refunds the merchant's BNPL purchases and accepts its disputes, and its issuer sees the
     * secret once: so issuing it is a money-operating act, {@code PAYMENT_REFUND}'s - the operator refund door's own
     * permission. A {@code MERCHANT_ADMINISTER} holder alone could otherwise mint the power to refund their own purchase.
     */
    public static final java.util.Map<com.finapp.merchant.MerchantApiKeyScope, PermissionName> SCOPE_PERMISSIONS =
            java.util.Map.of(com.finapp.merchant.MerchantApiKeyScope.BNPL_REFUND, PermissionName.PAYMENT_REFUND);

    @NonNull private final MerchantApiKeys keys;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final Authorization authorization;

    /** Issues a key with no scope, or converges on the one a retried request already minted. */
    public IssuedKeyView issue(String rawMerchantId, String idempotencyKey) {
        return issue(rawMerchantId, idempotencyKey, List.of());
    }

    /**
     * Issues a key carrying the named scopes (`P12-TSK-021`) - an unknown name is {@code 422} before anything; a scope
     * whose permission the operator does not hold ({@link #SCOPE_PERMISSIONS}) is {@code 403 api.Forbidden}, the denial
     * recorded {@code FAILED} ({@code identity.AuthorizationDenied}) in a transaction of its own and nothing minted - or
     * converges on the one a retried request already minted.
     */
    public IssuedKeyView issue(String rawMerchantId, String idempotencyKey, List<String> rawScopes) {
        MerchantId merchant = parsedMerchantOrAbsent(rawMerchantId);
        java.util.Set<com.finapp.merchant.MerchantApiKeyScope> scopes = scopes(rawScopes);
        for (com.finapp.merchant.MerchantApiKeyScope scope : scopes) {
            PermissionName required = SCOPE_PERMISSIONS.get(scope);
            if (required != null) {
                requirePermission(required);
            }
        }
        try {
            MerchantApiKeys.IssuedKey issued =
                    inOneTransaction(
                            unitOfWork -> keys.issue(unitOfWork, merchant, idempotencyKey, scopes));
            return new IssuedKeyView(
                    issued.keyId().value().toString(),
                    // The one unwrap on this path, at the boundary that must transmit it.
                    issued.secret()
                            .map(com.finapp.sharedkernel.security.Sensitive::expose)
                            .orElse(null),
                    issued.replayed());
        } catch (UnknownMerchantException unknown) {
            throw notFound();
        } catch (MerchantNotKeyableException closed) {
            throw new ApiException(
                    MerchantErrorCode.NOT_KEYABLE,
                    "An API key was requested for a closed merchant",
                    "a closed merchant cannot be issued an API key.");
        }
    }

    /** The merchant's keys, newest first. Metadata only. */
    public List<KeyView> list(String rawMerchantId) {
        MerchantId merchant = parsedMerchantOrAbsent(rawMerchantId);
        return inOneTransaction(unitOfWork -> keys.list(unitOfWork, merchant)).stream()
                .map(MerchantApiKeyOperations::render)
                .toList();
    }

    /** Revokes one of the merchant's keys with the operator's reason. Converges on a repeat. */
    public void revoke(String rawMerchantId, String rawKeyId, MerchantStandingRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        MerchantId merchant = parsedMerchantOrAbsent(rawMerchantId);
        MerchantApiKeyId key = parsedKeyOrAbsent(rawKeyId);
        try {
            inOneTransaction(
                    unitOfWork -> {
                        keys.revoke(unitOfWork, merchant, key, body.reason());
                        return null;
                    });
        } catch (UnknownMerchantApiKeyException unknown) {
            throw notFound();
        }
    }

    private static KeyView render(MerchantApiKey key) {
        return new KeyView(
                key.id().value().toString(),
                key.status().name(),
                key.issuedAt().toString(),
                key.revokedAt().map(Object::toString).orElse(null),
                key.scopes().stream().map(Enum::name).sorted().toList());
    }

    /** The named scopes - each one this platform knows, else {@code 422}; none named is none. */
    static java.util.Set<com.finapp.merchant.MerchantApiKeyScope> scopes(List<String> raw) {
        java.util.Set<com.finapp.merchant.MerchantApiKeyScope> scopes =
                java.util.EnumSet.noneOf(com.finapp.merchant.MerchantApiKeyScope.class);
        for (String name : raw == null ? List.<String>of() : raw) {
            try {
                scopes.add(com.finapp.merchant.MerchantApiKeyScope.valueOf(name));
            } catch (IllegalArgumentException | NullPointerException unknown) {
                throw new ApiException(
                        com.finapp.platform.api.PlatformErrorCode.VALIDATION_FAILED,
                        "An API key scope was not recognised",
                        "a scope must be one of "
                                + java.util.Arrays.toString(com.finapp.merchant.MerchantApiKeyScope.values())
                                + ".");
            }
        }
        return scopes;
    }

    /**
     * Refuses an operator who does not hold {@code permission} - read from the roles they hold NOW, on this request
     * ({@code SessionAuthenticationInterceptor}'s rule: never from the session), the denial audited in the transaction
     * that read the roles and committed before the refusal is thrown (the interceptor's shape: a refused privileged
     * attempt is the only trace an attacker leaves, {@code INV-AUD-03}). An actor the platform cannot read as an identity
     * holds nothing: refused.
     */
    private void requirePermission(PermissionName permission) {
        com.finapp.platform.security.Actor actor = com.finapp.platform.security.SecurityContext.require();
        IdentityId identity;
        try {
            identity = IdentityId.of(UUID.fromString(actor.id()));
        } catch (IllegalArgumentException notAnIdentity) {
            throw forbidden(permission);
        }
        boolean permitted = Boolean.TRUE.equals(inOneTransaction(unitOfWork -> {
            if (authorization.permits(unitOfWork, identity, permission)) {
                return true;
            }
            authorization.recordDenial(unitOfWork, identity, permission);
            return false;
        }));
        if (!permitted) {
            throw forbidden(permission);
        }
    }

    private static ApiException forbidden(PermissionName permission) {
        return new ApiException(PlatformErrorCode.FORBIDDEN, "A session without " + permission + " asked for a scope"
                + " that requires it");
    }

    private static MerchantId parsedMerchantOrAbsent(String raw) {
        try {
            return MerchantId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static MerchantApiKeyId parsedKeyOrAbsent(String raw) {
        try {
            return MerchantApiKeyId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    /** Malformed, unknown and another merchant's are one answer ({@code INV-MER-01}). */
    private static ApiException notFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A merchant API key read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
