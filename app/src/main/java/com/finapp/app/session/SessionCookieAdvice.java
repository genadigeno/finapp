package com.finapp.app.session;

import jakarta.servlet.http.HttpServletRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Moves an issued session token from the body to the cookie when the request opted in on the public surface (ADR-0156
 * decision 3, `X-TSK-029`).
 *
 * <p>Every door that issues a token answers an {@link IssuesSessionToken}; this advice is the one place that decides its
 * carrier, so rotation replaces the cookie wherever ADR-0030 rotates the token. Without the opt-in the body is today's.
 */
@RestControllerAdvice(basePackages = "com.finapp")
@RequiredArgsConstructor
public class SessionCookieAdvice implements ResponseBodyAdvice<Object> {

    @NonNull private final SessionCookieCarrier carrier;

    @Override
    public boolean supports(
            MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return IssuesSessionToken.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(body instanceof IssuesSessionToken issued)
                || issued.sessionToken() == null
                || !(request instanceof ServletServerHttpRequest servlet)) {
            return body;
        }
        HttpServletRequest http = servlet.getServletRequest();
        if (!carrier.issuesCookie(http)) {
            return body;
        }
        response.getHeaders().add(HttpHeaders.SET_COOKIE, SessionCookieCarrier.issued(issued.sessionToken()));
        return issued.withoutSessionToken();
    }
}
