package com.finapp.app.surface;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a door at which a role-holding identity is refused on the public surface (`P15-TSK-042`; {@code THREAT_MODEL.md}
 * T1, T3, T31): staff prove, strengthen and recover their access on the operator surface alone.
 *
 * <p><strong>Who enforces it.</strong> On a door that needs a session, the session interceptor: it reads the identity's live
 * roles with the session, in one transaction, and refuses a role-holder {@code 401 identity.AuthenticationFailed} before the
 * handler runs, so nothing is counted, rotated or enrolled. On a door that needs none - sign-in, recovery - the handler passes
 * the surface to its service, which decides after the work an ordinary failure would have done and before anything is counted.
 * {@code OperatorsRefusedOnPublicDoorsTest} holds which handlers carry this, read from what each reaches, and that every
 * unauthenticated one reads the surface.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OperatorsRefusedOnPublic {

    /** Which door this is: its audit operation and its meter tag. */
    SurfaceDoor value();
}
