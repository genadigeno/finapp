package com.finapp.payments;

/**
 * Routing chose a push rail this deployment has not configured (`P7-TSK-009`): the rail is
 * declared by the build and available by routing's fact, but no adapter bean exists —
 * {@code finapp.payments.instant.url} unset. Thrown inside Tx1, so the whole dispatch rolls
 * back with <strong>nothing written and nothing sent</strong>, and the intent still awaits
 * confirmation; the surface answers the honest 503 (the {@code ObjectProvider} decision,
 * recorded in {@code PaymentBeans}, at its second occurrence).
 */
public final class PushRailUnavailableException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public PushRailUnavailableException() {
        super("the chosen push rail has no configured adapter in this deployment");
    }
}
