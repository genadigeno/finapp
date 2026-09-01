package com.finapp.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.platform.api.ProblemDetail;

/**
 * The JSON body of an {@code application/problem+json} response, member for member.
 *
 * <p><strong>Why this exists rather than serialising {@link ProblemDetail} directly.</strong>
 * The wire format of a published contract must be <em>specified</em>, not whatever a
 * serialiser's defaults produce from a domain type. Letting Jackson reflect over the platform's
 * record gave both of the failures below on the first run, and neither would have failed a test
 * that checked only the fields it expected to find:
 *
 * <ul>
 *   <li>{@code "correlationId":{}} — an {@code Optional<CorrelationId>} became an empty object,
 *       because {@code CorrelationId.value()} is not a bean getter and Jackson found no
 *       properties to write. The identifier a client is meant to quote was silently absent while
 *       the member was present.
 *   <li>{@code "detail":null} — an absent member rendered as an explicit null. RFC 9457 members
 *       are optional; a client cannot distinguish "no detail" from "detail is the null value",
 *       and every response carries bytes that say nothing.
 * </ul>
 *
 * <p>The deeper reason is coupling. With direct serialisation, adding a field to the platform's
 * record publishes it to every client — no review, no compile error, no test. This record is the
 * one place the wire format is decided, so extending the contract is a deliberate edit here, and
 * {@code ApiErrorHandlerTest} asserts the exact member set.
 *
 * <p>It lives in {@code app} because rendering is {@code app}'s responsibility
 * ({@code MODULE_ARCHITECTURE.md} §M10) and because it is the only part of the contract that is
 * allowed to know a serialisation library exists.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetailBody(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String instance,
        String correlationId) {

    /** Flattens the value type to its wire members, omitting whatever is absent. */
    public static ProblemDetailBody from(ProblemDetail problem) {
        return new ProblemDetailBody(
                problem.type(),
                problem.title(),
                problem.status(),
                problem.code(),
                problem.detail().orElse(null),
                problem.instance().orElse(null),
                problem.correlationId().map(correlation -> correlation.value()).orElse(null));
    }
}
