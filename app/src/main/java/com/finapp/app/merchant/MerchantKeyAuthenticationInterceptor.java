package com.finapp.app.merchant;

import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantApiKey;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantApiKeySecret;
import com.finapp.merchant.MerchantApiKeyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates a merchant's API key and establishes the tenant (`P6-TSK-002`, ADR-0052) —
 * the {@code SessionAuthenticationInterceptor} shape at a new door, with the differences that
 * matter stated rather than inherited.
 *
 * <h2>Authoritative on every request, and that is not an optimisation to reclaim</h2>
 *
 * <p>A session is short-lived, so much of its safety comes from expiry. An API key has none:
 * no expiry, no browser to close, no human at a keyboard. Every control therefore comes from
 * asking authoritative state <em>per request</em> — one query that answers "is this key
 * {@code ACTIVE} <strong>and</strong> is its merchant trading?" ({@code MerchantApiKeyStore}'s
 * join). Caching that answer would make revocation and suspension promises the architecture
 * cannot keep, on every instance that held the cache, for as long as it held it. The single
 * most tempting change to this class is the one that breaks it.
 *
 * <h2>The presented credential, and why its causes are conflated</h2>
 *
 * <p>{@code Authorization: Bearer <keyId>.<secret>} — never a query parameter, for the reason
 * {@code SessionAuthenticationInterceptor} records: a query string reaches access logs,
 * proxies and browser history, and this value <em>is</em> the merchant. Unknown key, revoked
 * key, suspended merchant, closed merchant, malformed value and wrong secret are one
 * {@code 401}: an authentication surface that distinguishes them is an oracle over other
 * companies' integrations ({@code INV-IDN-07}'s reasoning at the tenant boundary).
 *
 * <h2>Fails closed</h2>
 *
 * <p>A storage failure propagates: no key, no tenant, no request served. Authenticating a
 * counterparty against an unreadable database is the one outcome worse than an outage.
 */
public final class MerchantKeyAuthenticationInterceptor implements HandlerInterceptor {

    /** Where the authenticated tenant is left for the handler — a request attribute, never a
     * field: a field would be shared by every concurrent request on this singleton. */
    public static final String CURRENT_MERCHANT =
            MerchantKeyAuthenticationInterceptor.class.getName();

    private static final String SCOPE_ATTRIBUTE = CURRENT_MERCHANT + ".scope";
    private static final String SCHEME = "Bearer ";
    private static final String SEPARATOR = ".";

    private final MerchantApiKeyStore<Connection> keys;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public MerchantKeyAuthenticationInterceptor(
            MerchantApiKeyStore<Connection> keys,
            TransactionTemplate transactions,
            DataSource dataSource) {
        this.keys = Objects.requireNonNull(keys, "keys must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)
                || declaration(handlerMethod) == null) {
            return true;
        }

        AuthenticatedMerchant merchant =
                presented(request)
                        .flatMap(this::authenticate)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                PlatformErrorCode.UNAUTHENTICATED,
                                                "No live merchant API key was presented"));

        request.setAttribute(CURRENT_MERCHANT, merchant);
        request.setAttribute(
                SCOPE_ATTRIBUTE,
                // The actor is the MERCHANT, identified by the merchant rather than the key:
                // an audit record answers "which counterparty did this", and the key travels
                // in the record's detail where a reader needs it (ActorType.MERCHANT's note).
                SecurityContext.enter(
                        new Actor(merchant.merchantId().value().toString(), ActorType.MERCHANT)));
        return true;
    }

    /**
     * Closes the scope, whatever happened — {@code afterCompletion} rather than
     * {@code postHandle}, because the latter is skipped when the handler throws and a scope
     * left open on a pooled worker would hand the next unrelated request this merchant's
     * identity ({@code P0-TSK-032}'s leak, at a new door).
     */
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Object scope = request.getAttribute(SCOPE_ATTRIBUTE);
        if (scope instanceof SecurityContext.Scope open) {
            open.close();
        }
    }

    /** The tenant this request authenticated as. The only legitimate source of one. */
    public static AuthenticatedMerchant require(HttpServletRequest request) {
        Object merchant = request.getAttribute(CURRENT_MERCHANT);
        if (merchant instanceof AuthenticatedMerchant authenticated) {
            return authenticated;
        }
        // Unreachable through a declared handler; loud rather than a default, because a
        // default here would be somebody else's tenant.
        throw new IllegalStateException(
                "no authenticated merchant on this request: the handler must declare"
                        + " @RequiresMerchantKey");
    }

    // -----------------------------------------------------------------

    /**
     * Looks the key up and verifies the secret, in its own short read-only transaction.
     *
     * <p>The lookup is by the PUBLIC key id — one row by primary key, never a scan over
     * hashes (ADR-0052's prefix rule) — and the secret is then compared in constant time by
     * the aggregate, which never surrenders its stored hash.
     */
    private Optional<AuthenticatedMerchant> authenticate(Presented presented) {
        return Optional.ofNullable(
                transactions.execute(
                        status -> {
                            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                            try {
                                return keys.findLiveFor(unitOfWork, presented.keyId())
                                        .filter(key -> key.verifies(presented.secret()))
                                        .map(MerchantKeyAuthenticationInterceptor::tenantOf)
                                        .orElse(null);
                            } finally {
                                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                            }
                        }));
    }

    private static AuthenticatedMerchant tenantOf(MerchantApiKey key) {
        return new AuthenticatedMerchant(key.merchantId(), key.id());
    }

    /**
     * The presented credential, split into its public half and its secret half.
     *
     * <p>A malformed value yields empty and is refused exactly as an unknown key is — no
     * shape complaint, because telling a caller its guess had the right form is a free bit
     * ({@code MerchantApiKeySecret.of}'s rule).
     */
    private static Optional<Presented> presented(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String credential = header.substring(SCHEME.length()).trim();
        int separator = credential.indexOf(SEPARATOR);
        if (separator <= 0 || separator == credential.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new Presented(
                            MerchantApiKeyId.of(
                                    UUID.fromString(credential.substring(0, separator))),
                            MerchantApiKeySecret.of(credential.substring(separator + 1))));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static RequiresMerchantKey declaration(HandlerMethod handlerMethod) {
        RequiresMerchantKey onMethod =
                handlerMethod.getMethodAnnotation(RequiresMerchantKey.class);
        return onMethod != null
                ? onMethod
                : handlerMethod.getBeanType().getAnnotation(RequiresMerchantKey.class);
    }

    private record Presented(MerchantApiKeyId keyId, MerchantApiKeySecret secret) {}
}
