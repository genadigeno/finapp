package com.finapp.app.api;

import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.api.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
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
 * No operator read of a customer is served unrecorded (`P17-TSK-025`; finapp-admin-panel ADR-C132 decision 5): the guard that
 * every handler under {@link RecordedReads#PERMISSIONS} records its read - and, since `P17-TSK-029`, every handler marked
 * {@link RecordedRead}.
 *
 * <p>Every answer passes through here on its way to the wire. One that serves something - any body but a problem - from a
 * handler declaring one of those permissions, in a request where {@link RecordedReads#record} wrote nothing, is refused: logged
 * and answered {@code 500 api.InternalError}, and the body it would have served is dropped. Fail closed, as
 * {@link RefusedActAdvice} is for a refusal it cannot record. A problem answer serves nothing and passes; so does an answer of no
 * body. An answer that is not JSON - a KYC document's bytes, since `P17-TSK-125` - is refused by an exception the error contract
 * answers {@code 500}, its converter being unable to write the problem in the body's place.
 *
 * <p>No state of its own: the mark is the request's, written by the thread that ran the read, so ten replicas each hold it for
 * their own requests and nothing is shared.
 */
@RestControllerAdvice
@Slf4j
public class UnrecordedReadAdvice implements ResponseBodyAdvice<Object> {

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
        if (body == null || body instanceof ProblemDetailBody || !(request instanceof ServletServerHttpRequest servlet)) {
            return body;
        }
        HttpServletRequest raw = servlet.getServletRequest();
        if (!(raw.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE) instanceof HandlerMethod handler)
                || !RecordedReads.recordsEveryRead(handler)
                || RecordedReads.recorded(raw)) {
            return body;
        }
        log.error("{}#{} answered a read under a recorded permission without recording it; answered as a failure, nothing served",
                handler.getBeanType().getSimpleName(), handler.getMethod().getName());
        if (selectedContentType == null || !selectedContentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                && !"json".equals(selectedContentType.getSubtypeSuffix())) {
            // A body that is not JSON - a document's bytes (P17-TSK-125) - goes through a converter that cannot write the problem
            // in its place. Refused by throwing instead: the body is dropped and the error contract answers 500 api.InternalError.
            throw new IllegalStateException("an unrecorded read under a recorded permission is not served");
        }
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        // The error contract's media type, as every problem answer carries it (ApiErrorHandler.render).
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return ProblemDetailBody.from(ProblemDetail.of(PlatformErrorCode.INTERNAL_ERROR, raw.getRequestURI()));
    }
}
