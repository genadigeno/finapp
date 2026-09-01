package com.finapp.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The published shape of an API failure. */
@SuppressWarnings("try") // A correlation Scope is used for its close side effect.
class ProblemDetailTest {

    @Test
    @DisplayName("everything rendered comes from the error code, not from anything at runtime")
    void theShapeIsBuiltFromTheCode() {
        ProblemDetail problem = ProblemDetail.of(PlatformErrorCode.NOT_FOUND, "/transfers/7");

        assertThat(problem.code()).isEqualTo("api.NotFound");
        assertThat(problem.status()).isEqualTo(404);
        assertThat(problem.title()).isEqualTo(PlatformErrorCode.NOT_FOUND.title());
        assertThat(problem.type()).isEqualTo(ProblemDetail.TYPE_PREFIX + "api.NotFound");
        assertThat(problem.instance()).contains("/transfers/7");
        assertThat(problem.detail()).isEmpty();
    }

    @Test
    @DisplayName("there is no way to build one from an exception")
    void noConstructorTakesAThrowable() {
        // Asserted by reflection rather than by reading, because the property is an ABSENCE and
        // absences are what get added back later by someone solving a different problem. A
        // factory taking a Throwable would be used with e.getMessage() within a week, and
        // nothing would fail - which is exactly how internal detail reaches a client.
        boolean anyAcceptsThrowable =
                java.util.Arrays.stream(ProblemDetail.class.getDeclaredMethods())
                        .flatMap(method -> java.util.Arrays.stream(method.getParameterTypes()))
                        .anyMatch(Throwable.class::isAssignableFrom);
        boolean anyConstructorAcceptsThrowable =
                java.util.Arrays.stream(ProblemDetail.class.getDeclaredConstructors())
                        .flatMap(constructor -> java.util.Arrays.stream(constructor.getParameterTypes()))
                        .anyMatch(Throwable.class::isAssignableFrom);

        assertThat(anyAcceptsThrowable).isFalse();
        assertThat(anyConstructorAcceptsThrowable).isFalse();
    }

    @Test
    @DisplayName("the correlation of the current flow is carried, so a client can quote it")
    void correlationIsCarried() {
        // The only correlation sink a customer ever sees. Without it, "I got an error at about
        // three o'clock" is all support has to go on.
        CorrelationId flow = CorrelationId.of("api-flow-1");

        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(flow))) {
            assertThat(ProblemDetail.of(PlatformErrorCode.INTERNAL_ERROR, "/x").correlationId())
                    .contains(flow);
        }
    }

    @Test
    @DisplayName("no correlation in scope is absent, not invented")
    void correlationIsNeverInvented() {
        // A fresh identifier here would be worse than none: it looks like a flow the client
        // could quote, and it matches nothing in any log.
        assertThat(ProblemDetail.of(PlatformErrorCode.INTERNAL_ERROR, "/x").correlationId())
                .isEmpty();
    }

    @Test
    @DisplayName("authored detail is carried; blank detail is refused")
    void detailIsOptionalButNeverBlank() {
        assertThat(ProblemDetail.of(PlatformErrorCode.VALIDATION_FAILED, "/x", "amount is required")
                        .detail())
                .contains("amount is required");

        // Blank rather than absent would render an empty "detail" member, which tells a client
        // there was something to say and then does not say it.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                new ProblemDetail(
                                        "t", "T", 400, "c", Optional.of("  "), Optional.empty(),
                                        Optional.empty()));
    }

    @Test
    @DisplayName("an implausible status is refused rather than rendered")
    void statusMustBeAnHttpStatus() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                new ProblemDetail(
                                        "t", "T", 42, "c", Optional.empty(), Optional.empty(),
                                        Optional.empty()));
    }

    @Test
    @DisplayName("optional members must be Optional, never null")
    void optionalsAreNotNullable() {
        assertThatNullPointerException()
                .isThrownBy(
                        () ->
                                new ProblemDetail(
                                        "t", "T", 400, "c", null, Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("an ApiException keeps its log message away from the client")
    void theLogMessageIsNotTheClientDetail() {
        // The separation the class exists for. The message names the account; the client is told
        // only what the code says.
        ApiException exception =
                new ApiException(
                        PlatformErrorCode.CONFLICT,
                        "idempotency key reuse on scope=transfers.create account=ACC-99812",
                        "This request has already been submitted with different content.");

        assertThat(exception.getMessage()).contains("ACC-99812");
        assertThat(exception.clientDetail())
                .contains("This request has already been submitted with different content.");

        ProblemDetail problem =
                ProblemDetail.of(
                        exception.errorCode(), "/transfers", exception.clientDetail().orElse(null));

        assertThat(problem.detail()).isPresent();
        assertThat(problem.toString()).doesNotContain("ACC-99812");
    }

    @Test
    @DisplayName("an ApiException with no client detail says only what its code says")
    void detailIsOptionalOnTheException() {
        ApiException exception =
                new ApiException(PlatformErrorCode.FORBIDDEN, "role=viewer attempted posting");

        assertThat(exception.clientDetail()).isEmpty();
        assertThat(ProblemDetail.of(exception.errorCode(), "/x").detail()).isEmpty();
    }
}
