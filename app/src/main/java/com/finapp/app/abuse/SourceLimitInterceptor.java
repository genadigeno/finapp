package com.finapp.app.abuse;

import com.finapp.app.abuse.SourceLimitMeters.Outcome;
import com.finapp.platform.abuse.Admission;
import com.finapp.platform.abuse.SourceBucket;
import com.finapp.platform.abuse.WindowCounter;
import com.finapp.platform.abuse.WindowCounterUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Counts a declared door's request per source before it is served (`P15-TSK-022`, {@code THREAT_MODEL.md} T1, T40, T47).
 *
 * <p><strong>The key is the connection's address.</strong> {@code request.getRemoteAddr()}: Tomcat sets it from a forwarded header
 * only for a connection the edge made, and the edge replaced whatever the client sent. No forwarded header is read here, so a
 * client cannot choose its bucket by setting one.
 *
 * <p><strong>It decides on the address alone, before the body is read</strong>, so the answer is the same for every identifier
 * and says nothing about any of them, and the door's expensive work never starts.
 *
 * <p><strong>Registered last.</strong> A request the idempotency-key check refuses never reaches this, so it costs no write.
 *
 * <p><strong>Closed, never open.</strong> A count that could not be taken refuses the request ({@code 503}), on every door. These
 * doors need the same database for what they do next, so there is nothing to keep serving, and a limit that failed open would
 * lift itself at exactly the moment the database was in trouble.
 *
 * <p><strong>What a refusal records</strong>: a meter ({@link SourceLimitMeters}), and the count itself. Nothing else.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class SourceLimitInterceptor implements HandlerInterceptor {

    @NonNull private final WindowCounter counter;
    @NonNull private final SourceLimits limits;
    @NonNull private final SourceLimitMeters meters;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        SourceLimited declared = method.getMethodAnnotation(SourceLimited.class);
        if (declared == null) {
            return true;
        }
        Door door = declared.value();
        Admission admission;
        try {
            admission = counter.admit(door.scope(), SourceBucket.keyOf(request.getRemoteAddr()), limits.of(door));
        } catch (WindowCounterUnavailableException unavailable) {
            meters.record(door, Outcome.STORE_UNAVAILABLE);
            throw SourceLimitRefused.storeUnavailable();
        }
        if (!admission.admitted()) {
            meters.record(door, Outcome.REFUSED);
            throw SourceLimitRefused.exceeded(admission.retryAfterSeconds());
        }
        meters.record(door, Outcome.ADMITTED);
        return true;
    }
}
