package com.finapp.app.abuse;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a handler is one of the doors counted per source (`P15-TSK-022`, {@code THREAT_MODEL.md} T1).
 *
 * <p>Next to {@code @Unauthenticated}, and for its reason: a limit that is an absence is one a new route quietly lacks.
 * {@code EveryPublicDoorIsDeclaredTest} refuses an {@code @Unauthenticated} handler carrying neither this nor {@link NotSourceLimited}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SourceLimited {

    /** Which door this is. */
    Door value();
}
