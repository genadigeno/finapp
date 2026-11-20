package com.finapp.kyc;

import java.util.Objects;

/**
 * The customer's answer to a reviewer's request for information, in their own words (`P17-TSK-126`, ADR-0155 decision 6).
 *
 * <p>RESTRICTED-PII: the customer may write anything about themselves, so it is shown to reviewers only (the case file), never
 * logged, never in an event. Bounded as kyc {@code V016}'s {@code information_request_answer_is_bounded}: 1 to 2,000 characters
 * (code points), not blank, and no control character but a line break or a tab. Not screened for vocabulary: the customer is not
 * the one who could tip themselves off.
 *
 * @param value the answer as the customer wrote it
 */
public record CustomerAnswer(String value) {

    /** The most characters an answer may run to. */
    public static final int MAX_LENGTH = 2000;

    public CustomerAnswer {
        Objects.requireNonNull(value, "value must not be null");
        int length = value.codePointCount(0, value.length());
        if (value.isBlank() || length > MAX_LENGTH) {
            throw new IllegalArgumentException("an answer is 1 to " + MAX_LENGTH + " characters and not blank");
        }
        if (value.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL && c != '\n' && c != '\t')) {
            throw new IllegalArgumentException("an answer carries no control character but a line break or a tab");
        }
    }

    /** Never the answer: it is the customer's own words about themselves. */
    @Override
    public String toString() {
        return "CustomerAnswer[" + value.codePointCount(0, value.length()) + " characters]";
    }
}
