package com.finapp.app.abuse;

import com.finapp.app.abuse.SourceLimitMeters.Outcome;
import com.finapp.platform.abuse.Admission;
import com.finapp.platform.abuse.SourceBucket;
import com.finapp.platform.abuse.WindowCounter;
import com.finapp.platform.abuse.WindowCounterUnavailableException;
import com.finapp.platform.abuse.WindowLimit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
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
 * <p><strong>Two counts for IPv6, in this order.</strong> The source's, then, only if that admitted, its /48's on the doors that
 * have one. A /64 past its own limit is refused without touching the /48's count, so one /64 cannot spend its neighbours'. The two are
 * separate statements: a request the /48 refuses has spent one of its /64's, which is the conservative way round.
 *
 * <p><strong>It decides on the address alone, before the body is read</strong>, so the answer is the same for every identifier
 * and says nothing about any of them, and the door's expensive work never starts.
 *
 * <p><strong>Registered last.</strong> A request with no usable {@code Idempotency-Key} header never reaches this, so it costs no
 * write. That is the header's shape only: a same-key retry is counted like any request, and past the limit it gets {@code 429}
 * where the stored {@code 201} would have been replayed, and that stored answer after the window ends.
 *
 * <p><strong>Closed, never open.</strong> A count that could not be taken refuses the request ({@code 503}), on every door. These
 * doors need the same database for what they do next, so there is nothing to keep serving, and a limit that failed open would
 * lift itself at exactly the moment the database was in trouble.
 *
 * <p><strong>What a refusal records</strong>: a meter ({@link SourceLimitMeters}) carrying the cause, and the count itself. Nothing else.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class SourceLimitInterceptor implements HandlerInterceptor {

    @NonNull private final WindowCounter counter;
    @NonNull private final SourceBucket buckets;
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
            admission = counted(door, buckets.of(request.getRemoteAddr()));
        } catch (WindowCounterUnavailableException unavailable) {
            meters.record(door, outcomeOf(unavailable.reason()));
            throw SourceLimitRefused.storeUnavailable();
        }
        if (!admission.admitted()) {
            meters.record(door, Outcome.REFUSED);
            throw SourceLimitRefused.exceeded(admission.retryAfterSeconds());
        }
        meters.record(door, Outcome.ADMITTED);
        return true;
    }

    private Admission counted(Door door, SourceBucket.Source source) {
        Admission admission = counter.admit(door.scope(), source.key(), limits.of(door));
        Optional<WindowLimit> siteLimit = limits.siteOf(door);
        if (admission.admitted() && siteLimit.isPresent() && source.site().isPresent()) {
            return counter.admit(door.siteScope(), source.site().get(), siteLimit.get());
        }
        return admission;
    }

    private static Outcome outcomeOf(WindowCounterUnavailableException.Reason reason) {
        return switch (reason) {
            case AT_CAPACITY -> Outcome.AT_CAPACITY;
            case UNREACHABLE -> Outcome.STORE_UNREACHABLE;
            case STATEMENT_FAILED -> Outcome.STORE_ERROR;
        };
    }
}
