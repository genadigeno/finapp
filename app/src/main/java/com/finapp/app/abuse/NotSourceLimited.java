package com.finapp.app.abuse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that an {@code @Unauthenticated} handler is deliberately not counted per source (`P15-TSK-022`), and why.
 *
 * <p>Today, the provider callbacks only. Their signature is verified before anything is written, so a forged delivery costs reading
 * its body into memory, up to the 1 MiB request limit, and an HMAC over it, and no database work (INV-PAY-01). Counting them would add a
 * database write per forged delivery, which is the one thing that door is built not to do. Their bound is the edge's callback tier and
 * the signature.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotSourceLimited {

    /** Why this door is not counted. Required, because the exemption must be stated. */
    String reason();
}
