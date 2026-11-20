package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every handler under the operator's customer reads records its read (`P17-TSK-025`; finapp-admin-panel ADR-C132 decision 5).
 *
 * <p>Two halves. <strong>At run time</strong> {@link UnrecordedReadAdvice} refuses an unrecorded answer from any such handler -
 * {@link RecordedReadsTest} holds its behaviour - and this guard holds that it is on the application's answer path, so the
 * refusal is real and not a class nobody registered. <strong>At review</strong> the handlers under {@link RecordedReads#PERMISSIONS}
 * are exactly {@link #REGISTER}'s, each beside the record it writes, so a new read reaches a reviewer with the action its database
 * test proves. Empty at `P17-TSK-025`, which serves no route: the guard holds from the first one (`P17-TSK-026`).
 */
@Tag("slice")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("every handler under a customer read records its read, and the refusal of one that does not is on the answer path"
        + " (P17-TSK-025)")
class EveryCustomerReadIsRecordedTest {

    /** {@code Controller#method} → the {@code <module>.<Thing>Read} it records. */
    static final Map<String, String> REGISTER = Map.of(
            // P17-TSK-032: the payment reads under PAYMENT_READ.
            "PaymentReadController#listOperatorPayments", "payments.PaymentsListed",
            "PaymentReadController#viewOperatorPayment", "payments.PaymentRead",
            "PaymentReadController#listPartyPaymentMethods", "paymentmethods.PaymentMethodsRead");
    // P17-TSK-039: the payments report moved to PLATFORM_OVERVIEW_READ - its counts name no customer - and is recorded there.

    @Autowired private ApplicationContext context;

    /** The MVC mapping, by name: actuator registers a second one ({@code EveryEndpointDeclaresARuleTest}'s note). */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("the guard is a controller advice the application's answers pass through")
    void theGuardIsOnTheAnswerPath() {
        assertThat(ControllerAdviceBean.findAnnotatedBeans(context))
                .as("UnrecordedReadAdvice is discovered as the request mapping adapter discovers every body advice")
                .anySatisfy(advice -> assertThat(advice.getBeanType()).isEqualTo(UnrecordedReadAdvice.class));
    }

    @Test
    @DisplayName("the handlers under the four reads are exactly the register's, each naming the record it writes")
    void theHandlersAreTheRegisters() {
        Map<String, Boolean> served = new TreeMap<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            if (method.getBeanType().getName().startsWith("com.finapp.") && RecordedReads.recordsEveryRead(method)) {
                served.put(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName(), true);
                // The guard sees an answer only as a body on its way through the converters: a handler that returns nothing,
                // streams, or writes the response itself would pass it by.
                assertThat(method.getReturnType().getParameterType())
                        .as("%s answers with a body the advice sees", method)
                        .isNotIn(void.class, Void.class, Object.class,
                                org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody.class,
                                org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class,
                                org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
            }
        });
        assertThat(served.keySet())
                .as("a handler under CUSTOMER_READ, ACCOUNT_READ, TRANSFER_READ or PAYMENT_READ is named here with its record")
                .isEqualTo(new TreeSet<>(REGISTER.keySet()));
        Set<String> actions = REGISTER.values().stream().collect(Collectors.toSet());
        assertThat(actions).as("each a module's declared code: <module>.<Thing>Read, or a list's or a lookup's name")
                .allMatch(code -> code.matches("[a-z]+\\.[A-Z][A-Za-z]+"));
    }
}
