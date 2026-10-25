-- The queues and the suppression's persons (P13-TSK-018; PHASE_13_PLAN.md sections 9, 11; RISK_LIFECYCLES.md section
-- 4.5; ADR-0110 decision 1; INV-RSK-07, INV-RSK-08, INV-AUD-04).
--
-- 1. THE SUPPRESSION'S PERSONS, WIDENED BY NAME (the defect P13-TSK-016's gate routed here)
--   V010's risk_alert_suppression_by_persons admitted only an EMPLOYEE-typed proposer and decider, while the platform's
--   session interceptor establishes EVERY operator - a risk analyst included - as a CUSTOMER-typed actor: the
--   suppression door this task builds would have refused every analyst. A person is an authenticated human - CUSTOMER-
--   typed as the session establishes every identity, or EMPLOYEE - never SYSTEM, SERVICE or MERCHANT: the meaning
--   P13-TSK-016 (V012, the case's persons) and P13-TSK-017 (V013, the action's) adopted. The constraint is dropped and
--   re-made under its own name; V010 is never edited (Flyway, outOfOrder=false). Every row V010 admitted names an
--   EMPLOYEE or no one, so the widened CHECK holds them all - nothing to backfill. Four eyes is unchanged
--   (risk_alert_suppression_four_eyes, for every writer).
--
-- 2. THE KEYSET INDEXES THE QUEUES READ (found missing by the design)
--   A queue page is oldest first by (raised_at | opened_at, id), keyset-paged after a cursor - both keys immutable, the
--   stamps the triggers' clock_timestamp(). V010's risk_alert_open_queue (raised_at, id) WHERE status = 'OPEN' serves
--   the alert queue's default; a page of a named status, the case file's attached alerts (attached_case_id had no
--   index), a page of the open cases (the case queue's default and the backlog gauges) and a page of a named case status
--   had none. The kind filter is a residual predicate over a closed vocabulary; a keyset page stops at its LIMIT.
--
-- No table, column, function, trigger or grant is created; the reads use V010's and V012's SELECT. The migration names
-- no other schema.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The persons: an authenticated human, never the system.
-- ---------------------------------------------------------------------------------------------------------------------

ALTER TABLE risk.risk_alert DROP CONSTRAINT risk_alert_suppression_by_persons;

ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_suppression_by_persons CHECK (
    (suppression_proposed_by_type IS NULL OR suppression_proposed_by_type IN ('CUSTOMER', 'EMPLOYEE'))
    AND (suppression_decided_by_type IS NULL OR suppression_decided_by_type IN ('CUSTOMER', 'EMPLOYEE')));

COMMENT ON CONSTRAINT risk_alert_suppression_by_persons ON risk.risk_alert IS
    'A suppression''s proposer and decider are persons - authenticated humans, CUSTOMER-typed as the platform''s session establishes every identity, or EMPLOYEE - never SYSTEM, SERVICE or MERCHANT: the system never suppresses (its dedupe is ATTACHED). Widened from EMPLOYEE alone by P13-TSK-018 (V014), the meaning V012 and V013 adopted.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The keyset indexes.
-- ---------------------------------------------------------------------------------------------------------------------

-- The alert queue of a named status, oldest first.
CREATE INDEX risk_alert_queue ON risk.risk_alert (status, raised_at, id);

-- A case file's alerts, oldest first.
CREATE INDEX risk_alert_of_case ON risk.risk_alert (attached_case_id, raised_at, id)
    WHERE attached_case_id IS NOT NULL;

-- The case queue of a named status, oldest first.
CREATE INDEX risk_case_queue ON risk.risk_case (status, opened_at, id);

-- The open cases, oldest first: the case queue's default and the backlog gauges' grouped read.
CREATE INDEX risk_case_open_queue ON risk.risk_case (opened_at, id) WHERE status <> 'DISPOSED';
