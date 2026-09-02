package com.finapp.platform.api;

/**
 * The version of the public HTTP contract, and where it appears.
 *
 * <p>Contract vocabulary, so it sits beside {@link ErrorCode} and {@link ProblemDetail} in
 * {@code platform} rather than in the web layer. A version is a promise to clients; it is not a
 * routing detail, and it must not become a function of whichever web stack is underneath
 * ({@code ERROR_CONTRACT.md} §6 makes the same argument for the error contract).
 *
 * <h2>Why the version is in the path</h2>
 *
 * <p>Because the path is the only place it survives everything that matters here. It appears in
 * an access log, in an audit record's target, in a proxy's cache key, in a support ticket
 * containing a pasted {@code curl}, and in a firewall rule. A version negotiated through an
 * {@code Accept} header or a custom header appears in none of those: reconstructing which
 * contract a call used would need a packet capture, and {@code CLAUDE.md} §Security and Audit
 * requires a consequential action to be reconstructable from the record.
 *
 * <p>The cost is honest and accepted: a URI identifies a resource, and versioning the URI means
 * the same resource has two names. That is a purity argument, and it loses to operability here.
 * ADR-0015 records the alternatives and why each was rejected.
 *
 * <h2>What the number means</h2>
 *
 * <p>It increments <strong>only</strong> for a change that breaks a client written against the
 * previous version. Everything backwards compatible — a new endpoint, a new optional field, a new
 * error code — happens inside the current version, which is why there is no minor component:
 * a minor version that never breaks anything is a number clients would have to send and could
 * never act on.
 *
 * <p>The build decides what "breaking" means rather than a reviewer's memory: the generated
 * OpenAPI document is compared against the committed one on every build, and each difference is
 * classified. See {@code docs/api/openapi.json} and ADR-0015.
 *
 * <h2>What is deliberately not versioned</h2>
 *
 * <p>Operational endpoints — health, readiness, metrics. They are consumed by orchestrators and
 * scrapers whose configuration is deployment-scoped, not by API clients holding a contract, and
 * moving them on a version bump would break a liveness probe for no benefit.
 */
public final class ApiVersion {

    /** The current contract version. One number, incremented only by a breaking change. */
    public static final int CURRENT = 1;

    /** The path segment carrying the version, {@code v1}. */
    public static final String CURRENT_SEGMENT = "v" + CURRENT;

    /** The prefix every versioned route sits under, {@code /v1}. */
    public static final String CURRENT_PREFIX = "/" + CURRENT_SEGMENT;

    private ApiVersion() {
        throw new AssertionError("not instantiable");
    }
}
