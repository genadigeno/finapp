package com.finapp.app.api;

import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RefusedActs;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Where a door's refusal is recorded (`P15-TSK-039`): every problem the error contract renders passes through here on its
 * way to the wire, whichever handler rendered it, so a domain refusal, a validation failure, a framework's rejection and
 * an accident are one path and a new one needs no edit. What never reaches a recorded row is {@code RefusedActs}' to name.
 *
 * <h2>Fail closed, as a refused step-up does</h2>
 *
 * <p>Before the answer leaves. A refusal whose record cannot be written - the database unreachable - is logged and answered
 * {@code 500 api.InternalError}, never answered without its record. The record says what the door answered, not what became
 * of the act: where an earlier transaction of the request committed, that work may still complete whatever is recorded here.
 *
 * <p>Two codes are passed by. {@code identity.AssuranceRequired} has its own record ({@code StepUpRefusals}), written and
 * failed closed by the error contract, so a refused step-up is one row. {@code api.IdempotencyInProgress} is the same
 * request's retry while its first flight runs: no control refused anything, and the first flight has its own outcome.
 */
@RestControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class RefusedActAdvice implements ResponseBodyAdvice<Object> {

    private static final Set<String> NOT_RECORDED_HERE =
            Set.of(IdentityErrorCode.ASSURANCE_REQUIRED.code(), PlatformErrorCode.IDEMPOTENCY_IN_PROGRESS.code());

    /** A provider, so a context without the session beans - a slice rendering the contract alone - still builds this. */
    private final ObjectProvider<RefusedActs> refusedActs;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(body instanceof ProblemDetailBody problem) || !(request instanceof ServletServerHttpRequest servlet)) {
            return body;
        }
        HttpServletRequest raw = servlet.getServletRequest();
        RefusedActs recorder = refusedActs.getIfAvailable();
        if (recorder == null || !declaresRefusals(raw) || NOT_RECORDED_HERE.contains(problem.code())) {
            return body;
        }
        try {
            recorder.record(raw, problem.code(), problem.status());
            return body;
        } catch (RuntimeException unrecorded) {
            log.error("A refused act on {} could not be recorded; answered as a failure, not a {}",
                    raw.getMethod(), problem.status(), unrecorded);
            response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
            return ProblemDetailBody.from(ProblemDetail.of(PlatformErrorCode.INTERNAL_ERROR, problem.instance()));
        }
    }

    private static boolean declaresRefusals(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) instanceof HandlerMethod handler)) {
            return false;
        }
        return handler.getMethodAnnotation(RecordsRefusals.class) != null
                || handler.getBeanType().isAnnotationPresent(RecordsRefusals.class);
    }
}
