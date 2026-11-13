package com.finapp.app.shedding;

import com.finapp.app.surface.RequestSurfaces;
import com.finapp.app.surface.Surface;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

/**
 * The shedding door (`P16-TSK-016`, ADR-0137): the first of the application's interceptors, ahead of the idempotency check, the
 * session's and the merchant key's authentication and the per-source count - every one of which may borrow a writer connection.
 *
 * <p><strong>It only refuses.</strong> A request it admits goes on through every interceptor behind it, authentication included, exactly
 * as before; it never marks a request as anything, so it cannot let through what authentication would refuse. What it refuses never
 * reaches its handler, so nothing of the request was done ({@link LoadShedding}). The answer, {@code 503 api.Overloaded} with
 * {@code Retry-After}, is the same for every caller and every route and reads nothing of the request but its method and its handler's
 * declarations, so an unauthenticated caller learns that the instance is busy and nothing else - what an edge's {@code 429} or a
 * {@code 503} already says.
 *
 * <p><strong>What it does not shed.</strong> A handler that is not the platform's own (a framework's error page); the operator surface,
 * by the edges' marker ({@link RequestSurfaces}, which only the edges can set), so staff can act on a shedding platform - its own classes
 * keep their own bounds, the operational report door's permits and report generation's pool; and a door that declares
 * {@link NotShed}, with its reason. Anything else on the public surface - an unmarked or single-port request included - is shed.
 *
 * <p><strong>The permit goes back on every way out.</strong> Spring calls {@link #afterCompletion} for an interceptor whose
 * {@code preHandle} returned true, whatever happens behind it - a later interceptor's refusal, the handler's exception, the answer. The
 * admission is kept on the request and removed when it is given back, so it is given back once. No handler of this platform is
 * asynchronous; were one to become so, {@link #afterConcurrentHandlingStarted} gives the permit back as the request leaves its thread,
 * and an async re-dispatch is not admitted a second time.
 */
public final class ShedAtTheDoor implements AsyncHandlerInterceptor {

    /** The request attribute holding the door's admission until it is given back. */
    static final String ADMISSION = ShedAtTheDoor.class.getName() + ".admission";

    /** Methods that cannot move money ({@code API_CONVENTIONS.md} §6): a read. Every other method is a command. */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /**
     * The platform's own handlers, as {@code ApiVersionConfiguration} versions them: by package, so a new controller is shed without
     * anyone remembering to list it. The trailing dot keeps a {@code com.finappsomething} out.
     */
    private static final String OUR_HANDLERS = "com.finapp.";

    private final LoadShedding shedding;
    private final RequestSurfaces surfaces;

    public ShedAtTheDoor(LoadShedding shedding, RequestSurfaces surfaces) {
        this.shedding = Objects.requireNonNull(shedding, "shedding must not be null");
        this.surfaces = Objects.requireNonNull(surfaces, "surfaces must not be null");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        Optional<LoadShedding.Type> type = classOf(method, request.getMethod());
        if (type.isEmpty() || surfaces.of(request) == Surface.OPERATOR) {
            return true;
        }
        request.setAttribute(ADMISSION, shedding.admit(type.get()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception thrown) {
        giveBack(request);
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        giveBack(request);
    }

    /**
     * The class the door bounds a request to {@code method} by {@code httpMethod} in, or empty for a handler it does not shed: not the
     * platform's own, or declaring {@link NotShed} on itself or its controller.
     */
    public static Optional<LoadShedding.Type> classOf(HandlerMethod method, String httpMethod) {
        Objects.requireNonNull(method, "method must not be null");
        if (!method.getBeanType().getName().startsWith(OUR_HANDLERS) || declaresNotShed(method)) {
            return Optional.empty();
        }
        return Optional.of(SAFE_METHODS.contains(httpMethod) ? LoadShedding.Type.READ : LoadShedding.Type.COMMAND);
    }

    /** The declaration on the handler or its controller, if any. */
    public static Optional<NotShed> notShed(HandlerMethod method) {
        NotShed onMethod = method.getMethodAnnotation(NotShed.class);
        return Optional.ofNullable(onMethod != null ? onMethod : method.getBeanType().getAnnotation(NotShed.class));
    }

    private static boolean declaresNotShed(HandlerMethod method) {
        return notShed(method).isPresent();
    }

    private void giveBack(HttpServletRequest request) {
        if (request.getAttribute(ADMISSION) instanceof LoadShedding.Admission admission) {
            request.removeAttribute(ADMISSION);
            shedding.release(admission);
        }
    }
}
