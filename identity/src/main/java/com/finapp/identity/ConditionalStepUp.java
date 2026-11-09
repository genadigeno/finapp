package com.finapp.identity;

import com.finapp.platform.api.ApiException;
import java.sql.Connection;
import java.util.Objects;

/**
 * The platform's conditional step-up, in one place (`P15-TSK-039`; the {@code P4-TSK-007} rule): {@code MULTI_FACTOR} is
 * required exactly of an identity that has an active factor. An identity with none may act on a password session, and
 * an identity with one may not, because a stolen password must not be enough for somebody who enrolled.
 *
 * <h2>The rule, and only the rule</h2>
 *
 * <p>It was seventeen private copies of six lines. It is one method now, and each caller keeps its own call site and its
 * own refusal message: <strong>where in an operation the check runs decides what a refused caller learns and which
 * refusal is recorded first</strong>, so moving the body moved nothing about the order. A caller without the permission
 * is still refused by the interceptor before it reaches any of these, and a caller refused here is still recorded by
 * the error contract ({@code StepUpRefusals}), at the one choke point every {@code 403 identity.AssuranceRequired}
 * passes through.
 *
 * <p><strong>An authoritative read per decision, never a cache</strong>: the factor is read on the caller's own unit of
 * work, every time, whatever the session says, so a factor confirmed a moment ago on another instance counts on this
 * one. {@code atLeast}, so a {@code STRONG} session satisfies it.
 *
 * <p>{@code CredentialChange} applies the same condition and is not a copy: it audits and returns a result instead of
 * throwing, inside the identity module's own credential transaction.
 */
public final class ConditionalStepUp {

    private ConditionalStepUp() {}

    /**
     * Refuses {@code current} unless its identity has no active factor or the session is {@code MULTI_FACTOR}.
     *
     * @param refusal the log message of the {@code 403 identity.AssuranceRequired}, naming the act: it reaches the log and
     *     never the wire
     */
    public static void require(
            MfaEnrolmentStore<Connection> enrolments, Connection unitOfWork, Session current, String refusal) {
        Objects.requireNonNull(refusal, "refusal must not be null");
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, refusal);
        }
    }
}
