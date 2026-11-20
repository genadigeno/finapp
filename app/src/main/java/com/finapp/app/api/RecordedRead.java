package com.finapp.app.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Every answer of this handler is a recorded read, held by {@link UnrecordedReadAdvice} as the reads under
 * {@link RecordedReads#PERMISSIONS} are (`P17-TSK-029`).
 *
 * <p>For a read of customers under a permission that also guards acts - {@code KYC_REVIEW}, {@code COUNTERPARTY_SCREENING_REVIEW}
 * - where holding every handler under the permission would hold the acts too. The mark is opt-in, so it is backed by a rule
 * that does not depend on remembering it: {@code EveryCustomerReadIsRecordedTest} requires every operator {@code GET} naming a
 * customer to be recorded, whatever its permission, and names each marked handler with the record it writes.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RecordedRead {}
