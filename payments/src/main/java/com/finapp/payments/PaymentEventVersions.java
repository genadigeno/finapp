package com.finapp.payments;

import java.util.Map;
import java.util.Objects;

/**
 * One contract version per payments event type (`P15-TSK-027`, the Phase 7 residue #49).
 *
 * <p>Every payments event used to take its version from one constant, so changing one type's
 * contract would have moved every other type's version on the wire with it. A consumer that refuses
 * a version it does not know would then stop on events that had not changed at all.
 *
 * <p>Each construction site passes its own type to {@link #of}, and {@code EventCatalogueTest} reads
 * that from the compiled code: a site naming another type's version, or a bare number, is refused.
 * Every version here is still the one already on the wire, so a replay or a rollback parses what is
 * there.
 */
public final class PaymentEventVersions {

    private static final Map<String, Integer> VERSIONS =
            Map.ofEntries(
                    Map.entry(PaymentCreation.CREATED_EVENT_TYPE, 1),
                    Map.entry(PaymentConfirmation.CAPTURE_DEFERRED_EVENT_TYPE, 1),
                    Map.entry(PaymentConfirmation.RAIL_SELECTED_EVENT_TYPE, 1),
                    Map.entry(PaymentClearing.CLEARED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.AUTHORIZED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.CAPTURED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.FAILED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.UNKNOWN_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.EXECUTED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.VOIDED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.REFUND_INITIATED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.REFUND_COMPLETED_EVENT_TYPE, 1),
                    Map.entry(PaymentOutcomes.REFUND_FAILED_EVENT_TYPE, 1),
                    Map.entry(WithdrawalOutcomes.INITIATED_EVENT_TYPE, 1),
                    Map.entry(WithdrawalOutcomes.COMPLETED_EVENT_TYPE, 1),
                    Map.entry(WithdrawalOutcomes.FAILED_EVENT_TYPE, 1),
                    Map.entry(WithdrawalOutcomes.HELD_EVENT_TYPE, 1),
                    Map.entry(WithdrawalOutcomes.RELEASED_EVENT_TYPE, 1),
                    Map.entry(DisputeNotifications.OPENED_EVENT_TYPE, 1),
                    Map.entry(DisputeNotifications.CHARGEBACK_EVENT_TYPE, 1),
                    Map.entry(DisputeNotifications.RESOLVED_EVENT_TYPE, 1),
                    Map.entry(DisputeResponseOutcomes.SUBMITTED_EVENT_TYPE, 1));

    private PaymentEventVersions() {}

    /**
     * The version {@code eventType} is written at.
     *
     * @throws IllegalArgumentException for a type with no entry here: {@code EventCatalogueTest} runs
     *     this for every type the code writes, so the build refuses the omission before a write can
     */
    public static int of(String eventType) {
        Integer version = VERSIONS.get(Objects.requireNonNull(eventType, "eventType must not be null"));
        if (version == null) {
            throw new IllegalArgumentException("No version is declared for " + eventType);
        }
        return version;
    }
}
