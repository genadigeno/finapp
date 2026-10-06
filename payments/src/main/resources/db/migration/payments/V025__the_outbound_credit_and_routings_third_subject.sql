-- =============================================================================================
-- P9-TSK-019 - the outbound credit, routing's third subject and the sixth evidence subject
-- (ADR-0080 sections 2 and 5b, the lifecycle document 3.6; INV-PAY-04, INV-RAIL-02, INV-XB-03).
--
-- An outbound credit is a cross-border payment's instruction to its corridor rail: born DISPATCHED in
-- the authorization's transaction with our end-to-end reference E minted and stored before any send,
-- the first permit stamped by the database, and the instructed and held amounts, the hold, the rail,
-- the destination and the subject frozen. Its machine (DISPATCHED -> RECEIVED | COMPLETED | FAILED |
-- UNKNOWN; UNKNOWN -> RECEIVED | COMPLETED | FAILED; RECEIVED -> COMPLETED | FAILED) is held here for
-- every writer.
--
-- Routing gains its third subject (intent XOR withdrawal XOR outbound credit), the destination
-- country and the per-candidate reachability it judged, and a rule matcher requiring a destination
-- country - default false, so every older version recomputes unchanged.
-- =============================================================================================

ALTER TABLE payments.routing_rule
    ADD COLUMN requires_destination_country boolean NOT NULL DEFAULT false;

CREATE TABLE payments.outbound_credit (
    id                     uuid        PRIMARY KEY,
    customer_party_id      uuid        NOT NULL,
    subject_id             uuid        NOT NULL,
    dispatch_key           text        NOT NULL,
    rail                   text        NOT NULL,
    destination_reference  text        NOT NULL,
    amount_minor           bigint      NOT NULL,
    amount_currency        text        NOT NULL,
    amount_scale           smallint    NOT NULL,
    held_minor             bigint      NOT NULL,
    held_currency          text        NOT NULL,
    held_scale             smallint    NOT NULL,
    hold_id                uuid        NOT NULL,
    end_to_end_reference   text        NOT NULL,
    status                 text        NOT NULL,
    failure_reason         text,
    provider_reference     text,
    delivered_at           timestamptz,
    recall_requested_at    timestamptz,
    recall_outcome         text,
    created_at             timestamptz NOT NULL,
    last_dispatched_at     timestamptz NOT NULL,
    CONSTRAINT outbound_credit_one_per_subject UNIQUE (subject_id),
    CONSTRAINT outbound_credit_one_per_reference UNIQUE (end_to_end_reference),
    CONSTRAINT outbound_credit_one_per_dispatch_key UNIQUE (customer_party_id, dispatch_key),
    CONSTRAINT outbound_credit_dispatch_key_is_bounded CHECK (char_length(dispatch_key) BETWEEN 1 AND 400),
    CONSTRAINT outbound_credit_rail_is_shaped CHECK (rail ~ '^[a-z][a-z0-9-]{0,31}$'),
    CONSTRAINT outbound_credit_destination_is_opaque CHECK (destination_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT outbound_credit_reference_is_shaped CHECK (end_to_end_reference ~ '^[A-Za-z0-9-]{1,35}$'),
    CONSTRAINT outbound_credit_amount_is_positive CHECK (amount_minor > 0),
    CONSTRAINT outbound_credit_held_is_positive CHECK (held_minor > 0),
    CONSTRAINT outbound_credit_currencies_are_alpha3 CHECK (amount_currency ~ '^[A-Z]{3}$' AND held_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT outbound_credit_scales_are_bounded CHECK (amount_scale BETWEEN 0 AND 3 AND held_scale BETWEEN 0 AND 3),
    CONSTRAINT outbound_credit_status_is_known
        CHECK (status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED', 'COMPLETED', 'FAILED')),
    CONSTRAINT outbound_credit_failure_reason_is_known
        CHECK (failure_reason IN ('DECLINED', 'PROVIDER_UNAVAILABLE', 'NEVER_RECEIVED', 'RECALLED')),
    CONSTRAINT outbound_credit_failure_reason_iff_failed CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT outbound_credit_provider_reference_is_shaped
        CHECK (provider_reference IS NULL OR provider_reference ~ '^[A-Za-z0-9_.:-]{1,128}$'),
    CONSTRAINT outbound_credit_recall_outcome_is_known CHECK (recall_outcome IN ('RECALLED', 'REFUSED')),
    CONSTRAINT outbound_credit_recall_outcome_needs_a_request
        CHECK (recall_outcome IS NULL OR recall_requested_at IS NOT NULL)
);

CREATE INDEX outbound_credit_due ON payments.outbound_credit (last_dispatched_at)
    WHERE status IN ('DISPATCHED', 'UNKNOWN', 'RECEIVED');

COMMENT ON TABLE payments.outbound_credit IS
    'A cross-border payment''s instruction to its corridor rail (P9-TSK-019, the lifecycle document 3.6): '
    'born DISPATCHED with E stored before any send and the first permit stamped by the database; E, the rail, '
    'the destination, the instructed and held amounts, the hold and the subject frozen (INV-XB-03); the '
    'permit strictly forward; DISPATCHED -> RECEIVED | COMPLETED | FAILED | UNKNOWN, UNKNOWN -> RECEIVED | '
    'COMPLETED | FAILED, RECEIVED -> COMPLETED | FAILED, for every writer.';

CREATE OR REPLACE FUNCTION payments.outbound_credit_machine_is_legal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    now TIMESTAMPTZ := statement_timestamp();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DISPATCHED' THEN
            RAISE EXCEPTION 'an outbound credit is born DISPATCHED (P9-TSK-019)';
        END IF;
        NEW.created_at := now;
        NEW.last_dispatched_at := now;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an outbound credit is never deleted (P9-TSK-019)';
    END IF;
    IF (to_jsonb(NEW) - 'status' - 'failure_reason' - 'provider_reference' - 'delivered_at'
            - 'recall_requested_at' - 'recall_outcome' - 'last_dispatched_at')
            IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'failure_reason' - 'provider_reference' - 'delivered_at'
            - 'recall_requested_at' - 'recall_outcome' - 'last_dispatched_at') THEN
        RAISE EXCEPTION 'an outbound credit''s instruction is frozen (P9-TSK-019, INV-XB-03)';
    END IF;
    IF OLD.provider_reference IS NOT NULL AND NEW.provider_reference IS DISTINCT FROM OLD.provider_reference THEN
        RAISE EXCEPTION 'an outbound credit''s provider reference is set once (P9-TSK-019)';
    END IF;
    IF OLD.delivered_at IS NOT NULL AND NEW.delivered_at IS DISTINCT FROM OLD.delivered_at THEN
        RAISE EXCEPTION 'an outbound credit''s delivery is set once (P9-TSK-019)';
    END IF;
    IF NEW.last_dispatched_at IS DISTINCT FROM OLD.last_dispatched_at THEN
        -- The send permit: stamped by the database, strictly forward, for every writer.
        NEW.last_dispatched_at := GREATEST(OLD.last_dispatched_at + INTERVAL '1 microsecond', now);
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
            (OLD.status = 'DISPATCHED' AND NEW.status IN ('RECEIVED', 'COMPLETED', 'FAILED', 'UNKNOWN'))
            OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('RECEIVED', 'COMPLETED', 'FAILED'))
            OR (OLD.status = 'RECEIVED' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'an outbound credit moves % -> % only along its machine (P9-TSK-019)', OLD.status, NEW.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbound_credit_machine_is_legal
    BEFORE INSERT OR UPDATE OR DELETE ON payments.outbound_credit
    FOR EACH ROW
    EXECUTE FUNCTION payments.outbound_credit_machine_is_legal();

-- Routing's third subject: exactly one of intent, withdrawal and outbound credit. No FK on the credit,
-- as on the withdrawal: a refused decision is recorded before any credit row exists.
ALTER TABLE payments.routing_decision
    ADD COLUMN outbound_credit_id uuid,
    ADD COLUMN destination_country text,
    ADD COLUMN reachable_rails text[],
    DROP CONSTRAINT routing_decision_has_exactly_one_subject,
    ADD CONSTRAINT routing_decision_has_exactly_one_subject
        CHECK (num_nonnulls(intent_id, withdrawal_id, outbound_credit_id) = 1),
    ADD CONSTRAINT routing_decision_destination_country_is_alpha2
        CHECK (destination_country IS NULL OR destination_country ~ '^[A-Z]{2}$');

CREATE UNIQUE INDEX routing_decision_one_chosen_per_outbound_credit
    ON payments.routing_decision (outbound_credit_id) WHERE chosen_rail IS NOT NULL AND outbound_credit_id IS NOT NULL;
CREATE INDEX routing_decision_by_outbound_credit ON payments.routing_decision (outbound_credit_id)
    WHERE outbound_credit_id IS NOT NULL;

-- The sixth evidence subject: an outbound credit's provider answers, retained verbatim (INV-HIST-02).
ALTER TABLE payments.provider_evidence
    ADD COLUMN outbound_credit_id uuid REFERENCES payments.outbound_credit (id),
    DROP CONSTRAINT provider_evidence_has_at_most_one_subject,
    ADD CONSTRAINT provider_evidence_has_at_most_one_subject
        CHECK (num_nonnulls(attempt_id, refund_id, withdrawal_id, dispute_response_id,
                            unmatched_confirmation_id, outbound_credit_id) <= 1);

CREATE INDEX provider_evidence_by_outbound_credit ON payments.provider_evidence (outbound_credit_id)
    WHERE outbound_credit_id IS NOT NULL;

GRANT SELECT, INSERT ON payments.outbound_credit TO finapp_app;
GRANT UPDATE (status, failure_reason, provider_reference, delivered_at, recall_requested_at, recall_outcome,
              last_dispatched_at)
    ON payments.outbound_credit TO finapp_app;

-- ----------------------------------------------------------------- seed: policy version 5

-- The cross-border credit must route on a fresh database exactly as the standing subjects do
-- (V013/V016/V017/V019's seeding argument verbatim: INV-HIST-04's NOT NULL refuses an unpinned
-- decision). A version is WHOLE (the newest-effective resolution), so the standing rules carry
-- forward byte for byte - the card pay-in stays rule 0 - and rule 1, ahead of the domestic bank
-- pay-out, is the corridor credit: a bank pay-out WITH a destination country, onto the corridor
-- rail. The first matching rule decides, so a domestic pay-out (no destination country) skips it
-- and meets the instant rail as before; a cross-border credit meets the corridor rail, its
-- reachability judged per candidate. created_by names the migration.
INSERT INTO payments.routing_policy_version
    (id, version, effective_from, created_at, created_by, reason)
VALUES
    ('019a0019-0000-7000-8000-000000000050', 5, now(), now(), 'V025',
     'the standing routes carried forward, plus the cross-border credit onto the corridor rail ahead of the domestic bank pay-out (P9-TSK-019, ADR-0080 section 2)');

INSERT INTO payments.routing_rule
    (id, policy_version_id, rule_index, direction, instrument_kind, currency,
     ceiling_amount_minor, ceiling_currency, ceiling_scale, requires_destination_country)
VALUES
    ('019a0019-0000-7000-8000-000000000051', '019a0019-0000-7000-8000-000000000050',
     0, 'PAY_IN', 'CARD_TOKEN', NULL, NULL, NULL, NULL, false),
    ('019a0019-0000-7000-8000-000000000052', '019a0019-0000-7000-8000-000000000050',
     1, 'PAY_OUT', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL, true),
    ('019a0019-0000-7000-8000-000000000053', '019a0019-0000-7000-8000-000000000050',
     2, 'PAY_OUT', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL, false),
    ('019a0019-0000-7000-8000-000000000054', '019a0019-0000-7000-8000-000000000050',
     3, 'PAY_IN', 'BANK_ACCOUNT', NULL, NULL, NULL, NULL, false),
    ('019a0019-0000-7000-8000-000000000055', '019a0019-0000-7000-8000-000000000050',
     4, 'PAY_IN', 'WALLET', NULL, NULL, NULL, NULL, false);

INSERT INTO payments.routing_rule_rail (rule_id, position, rail)
VALUES
    ('019a0019-0000-7000-8000-000000000051', 0, 'card'),
    ('019a0019-0000-7000-8000-000000000052', 0, 'corridor-sim-a'),
    ('019a0019-0000-7000-8000-000000000053', 0, 'instant'),
    ('019a0019-0000-7000-8000-000000000054', 0, 'instant'),
    ('019a0019-0000-7000-8000-000000000055', 0, 'book');
