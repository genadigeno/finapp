package com.finapp.app.session;

import com.finapp.identity.Session;
import java.time.Instant;

/**
 * One session, as its owner sees it (`P1-TSK-016`).
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>The token and its hash. The token is a <strong>bearer credential</strong> — whoever holds it
 * is the customer — and the hash is the value a database leak would be worth something for. Neither
 * has any business in a response, and {@code CredentialReachesNoEmittedSinkTest} fails the build if
 * a credential-named member reaches the published contract.
 *
 * <h2>The identifier is present, and it is not a credential</h2>
 *
 * <p>{@code P1-TSK-013} separated the two on purpose: {@code SessionId} is a UUIDv7 for foreign
 * keys, logs and audit records, and the token is 32 random bytes. Knowing somebody else's session
 * identifier is worth nothing, because revocation is scoped by owner in the statement — which is
 * the property that makes publishing it safe rather than a convention that says it is.
 *
 * <p>Named {@code id} rather than {@code sessionId}: it is the identifier of the resource being
 * represented, and the longer name would also collide with the contract guard's vocabulary — which
 * would be the guard being conservative rather than wrong.
 *
 * <h2>No {@code @Schema} annotations, and that is ADR-0015 rather than an omission</h2>
 *
 * <p>springdoc is <strong>test scope</strong>: the running application serves no {@code
 * /v3/api-docs} and ships no documentation library, so a production type cannot carry its
 * annotations. The published contract is generated from these types in test scope and compared
 * byte for byte against {@code docs/api/openapi.json}. The compiler caught the first attempt.
 */
public record SessionSummary(
        String id,
        String assurance,
        Instant issuedAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        String device,
        boolean current) {

    /** Projects a session for its owner, naming whether it is the one being used right now. */
    public static SessionSummary of(Session session, Session current) {
        return new SessionSummary(
                session.id().value().toString(),
                session.assurance().name(),
                session.issuedAt(),
                session.idleExpiresAt(),
                session.absoluteExpiresAt(),
                session.device().orElse(null),
                session.id().equals(current.id()));
    }
}
