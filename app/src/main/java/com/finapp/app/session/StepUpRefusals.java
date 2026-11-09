package com.finapp.app.session;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Records every refused step-up an operation answers itself (`P15-TSK-021`; {@code identity.StepUpRefused};
 * {@code THREAT_MODEL.md} T38; the debt row "a refused step-up is not audited").
 *
 * <h2>Two places refuse a step-up, and one records each</h2>
 *
 * <p>A handler declaring {@link RequiresAssurance} is refused by {@link SessionAuthenticationInterceptor}, which records
 * the refusal itself and marks the request. Every other {@code identity.AssuranceRequired} - the conditional step-up of
 * a bank-account registration, a withdrawal, a wallet payment, a loan's or a BNPL agreement's acceptance, thirty-odd
 * throw sites - is thrown by the operation, inside the handler, where the handler's transaction
 * rolls back with it. They all reach the error contract, which calls {@link #record} for that code alone: one choke
 * point, so a new conditional step-up is recorded with no edit.
 *
 * <h2>Once per refused request, committed on its own</h2>
 *
 * <p>The record is written in the session's {@code REQUIRES_NEW} template, so it commits whatever happened to the
 * handler's work. A request the interceptor already recorded is skipped, so no refusal is recorded twice; a client's
 * retry is a new attempt and records again, as a denial does.
 *
 * <p><strong>Only a person's refusal, against the actor the interceptor proved.</strong> No actor, or an actor that is
 * not a session's person, records nothing: the code is only ever thrown behind a session, and naming anybody else would
 * put an unproven claim in a permanent record. The summary is the operation's route pattern - never the path's values,
 * the presented code, the factor or the session.
 */
@RequiredArgsConstructor
public final class StepUpRefusals {

    /** Set on a request whose refusal has been recorded. */
    static final String RECORDED = StepUpRefusals.class.getName() + ".recorded";

    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final Authorization authorization;

    /**
     * Records the request's refused step-up, unless it is recorded already or no person is acting.
     *
     * @return whether this call wrote the record
     */
    public boolean record(HttpServletRequest request) {
        if (request.getAttribute(RECORDED) != null) {
            return false;
        }
        Optional<IdentityId> person = SecurityContext.current().flatMap(StepUpRefusals::personOf);
        if (person.isEmpty()) {
            return false;
        }
        String operation = operationOf(request);
        transactions.executeWithoutResult(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                authorization.recordStepUpRefusal(unitOfWork, person.get(), operation);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
        markRecorded(request);
        return true;
    }

    /** The refused operation as {@code <METHOD> <route pattern>}: the pattern the dispatcher matched, never the path. */
    static String operationOf(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return request.getMethod() + " " + (pattern == null ? "(unmatched)" : pattern.toString());
    }

    static void markRecorded(HttpServletRequest request) {
        request.setAttribute(RECORDED, Boolean.TRUE);
    }

    /** A session's person: a {@code CUSTOMER} or {@code EMPLOYEE} actor whose id is an identity's. */
    private static Optional<IdentityId> personOf(Actor actor) {
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            return Optional.empty();
        }
        try {
            return Optional.of(IdentityId.of(UUID.fromString(actor.id())));
        } catch (IllegalArgumentException notAnIdentity) {
            return Optional.empty();
        }
    }
}
