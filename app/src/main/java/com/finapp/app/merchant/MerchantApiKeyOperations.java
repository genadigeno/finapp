package com.finapp.app.merchant;

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
    public record KeyView(String keyId, String status, String issuedAt, String revokedAt) {}

    @NonNull private final MerchantApiKeys keys;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** Issues a key, or converges on the one a retried request already minted. */
    public IssuedKeyView issue(String rawMerchantId, String idempotencyKey) {
        MerchantId merchant = parsedMerchantOrAbsent(rawMerchantId);
        try {
            MerchantApiKeys.IssuedKey issued =
                    inOneTransaction(
                            unitOfWork -> keys.issue(unitOfWork, merchant, idempotencyKey));
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
                key.revokedAt().map(Object::toString).orElse(null));
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
