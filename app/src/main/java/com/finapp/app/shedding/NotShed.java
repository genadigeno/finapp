package com.finapp.app.shedding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler of the public surface is deliberately not shed (`P16-TSK-016`, ADR-0137), and why.
 *
 * <p>Today, the provider callbacks only. A delivery is the provider's, retried on the provider's schedule rather than the platform's; its
 * signature is verified before anything is written and its evidence is one short transaction; the inquiry a hint starts has its own
 * bound ({@code CallbackInquiryBulkhead}). Shedding it would turn the platform's overload into the provider's retries and delay the
 * evidence the sweeps would otherwise not have to ask for. Their bound is the edge's callback tier and the signature.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotShed {

    /** Why this door is not shed. Required, because the exemption must be stated. */
    String reason();
}
