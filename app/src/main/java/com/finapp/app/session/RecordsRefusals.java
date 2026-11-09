package com.finapp.app.session;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a door whose every refused or failed act is recorded with its outcome (`P15-TSK-039`; {@code door.ActRefused};
 * {@code THREAT_MODEL.md} T38; {@code INV-AUD-03}).
 *
 * <p>An operator's or a merchant's door of Phases 7 to 10 carries it, on the handler or on the class when every handler
 * of the class is such a door. The record is {@link RefusedActs}', written by the error contract, so a door declares it
 * and does nothing else, and a new way for one to be refused is recorded with no edit. {@code RefusedActsAreRecordedTest}
 * reads the controllers and fails the build on a door without it.
 *
 * <p>Read from the method first and then the class, the {@code RequiresSession} idiom.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RecordsRefusals {}
