-- The corridor rail declares only what is true (P9-TSK-014; ADR-0080 section 1; INV-RAIL-01).
--
-- RefundMode.NONE - a rail that carries no pay-in, so no refund executes on it - is a compiled fact on
-- the rail's declaration and never a column, so it needs no CHECK. Routing's refusal of a PAY_IN judged
-- on such a rail IS stored: a decision step records it as the new RoutingRejection.DIRECTION_UNSUPPORTED.
-- The step's rejection CHECK is regenerated from RoutingRejection.sqlValueList() (V013 is applied
-- history, ADR-0011); PaymentsMigrationTest reconciles it against THIS file. The other step rules -
-- a rejection exactly on a non-CHOSEN step, abandonment only on NOTHING_SENT, a descriptor absent
-- exactly when UNDECLARED_BY_BUILD - stand unchanged.

ALTER TABLE payments.routing_decision_step
    DROP CONSTRAINT routing_decision_step_rejection_is_known,
    ADD CONSTRAINT routing_decision_step_rejection_is_known
        CHECK (rejection IN ('UNAVAILABLE', 'CURRENCY_UNSUPPORTED', 'AMOUNT_EXCEEDS_CEILING', 'MODEL_CANNOT_CARRY_INSTRUMENT', 'DESTINATION_UNREACHABLE', 'NOTHING_SENT', 'UNDECLARED_BY_BUILD', 'DIRECTION_UNSUPPORTED'));
