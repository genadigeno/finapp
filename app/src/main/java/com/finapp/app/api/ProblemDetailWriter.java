package com.finapp.app.api;

import tools.jackson.databind.ObjectMapper;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Writes a problem detail straight to the response, for failures that never reach a controller.
 *
 * <p><strong>Why this is needed at all.</strong> {@code @ExceptionHandler} is a
 * {@code DispatcherServlet} mechanism: an exception thrown in a <em>filter</em> runs before the
 * dispatcher and never reaches {@link ApiErrorHandler}. A filter that rejected a request by
 * throwing would therefore produce the container's default error page — the framework's own
 * shape, on a path the contract is supposed to cover, which is the same failure P0-TSK-024
 * existed to close one layer further in.
 *
 * <p>So the filters that reject a request render the contract themselves, through here, and the
 * body is built the same way as everywhere else: from the {@link ErrorCode}, never from an
 * exception.
 */
@Component
public class ProblemDetailWriter {

    private final ObjectMapper json;

    public ProblemDetailWriter(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Renders {@code errorCode} as the whole response.
     *
     * @param detail authored text safe to show a stranger, or {@code null}
     */
    public void write(
            HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode, String detail)
            throws IOException {

        ProblemDetail problem = ProblemDetail.of(errorCode, request.getRequestURI(), detail);
        response.setStatus(problem.status());
        response.setContentType(ApiErrorHandler.PROBLEM_JSON.toString());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), ProblemDetailBody.from(problem));
        // Committed deliberately: a filter that rejected a request must not let the chain
        // continue and write a second body over this one.
        response.flushBuffer();
    }
}
