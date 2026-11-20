-- finapp-contract: first-conforming-release=ANY; waives=DROP,CHECK; reason=the replaced status check admits every status the old one did and AWAITING_CUSTOMER besides, so no row a release has written and no write a release makes is refused
--
-- A KYC case awaits the customer (P17-TSK-126; ADR-0155; finapp-admin-panel docs/FINAPP_BACKEND_WORK.md B6.6 item 3, gap G-63).
--
-- WHY
--   A reviewer who cannot judge what a check raised without the customer - an unreadable document, details that differ from it,
--   a missing proof of address - asks the customer, with a deadline. The case waits on the customer (AWAITING_CUSTOMER) and
--   returns to IN_REVIEW when the customer answers or the deadline passes on the database clock.
--
-- WHAT IT CHANGES
--   1. kyc.kyc_case's status check, replaced by one admitting AWAITING_CUSTOMER too (KycCaseStatus.sqlValueList(), reconciled by
--      KycCaseMigrationTest). NOT VALID: every existing row passed the old check, which the new one contains, so none can fail
--      it, and a check judged over old rows is the lint's TIGHTENED_OVER_OLD_ROWS (DATA_MIGRATIONS.md section 4.1). A later
--      release may VALIDATE it under SHARE UPDATE EXCLUSIVE. AWAITING_CUSTOMER is not terminal, so V002's one-open-case index and
--      V015's review queue index already hold such a case: neither predicate changes.
--   2. kyc.information_request, new: a reviewer's request on a case, OPEN -> ANSWERED | EXPIRED, at most one OPEN per case. A
--      case is AWAITING_CUSTOMER exactly while it has one OPEN request; every edge writes both rows in one transaction.
--
-- THE REQUEST'S RULES, HELD HERE TOO
--   Identity and text immutable; a request closes once, ANSWERED with the customer's answer or EXPIRED without one, its
--   closed_at never before it was asked (a trigger, the review_task V006 idiom). The text is screened for tipping-off in the
--   domain (CustomerRequestText); its bound is held here as well. The deadline follows the asking.
--
-- LOCKS
--   A brief ACCESS EXCLUSIVE on kyc.kyc_case for the constraint's swap, no scan (NOT VALID). The new table is empty.

ALTER TABLE kyc.kyc_case
    DROP CONSTRAINT kyc_case_status_is_known;

ALTER TABLE kyc.kyc_case
    ADD CONSTRAINT kyc_case_status_is_known
        CHECK (status IN ('OPEN', 'CHECKS_IN_PROGRESS', 'IN_REVIEW', 'AWAITING_CUSTOMER', 'READY_FOR_DECISION', 'APPROVED', 'REJECTED')) NOT VALID;

CREATE TABLE kyc.information_request (
    -- UUIDv7, minted by the application (ADR-0013).
    id             uuid        PRIMARY KEY,

    case_id        uuid        NOT NULL REFERENCES kyc.kyc_case (id),

    -- Internal: why the reviewer asked, named for what the customer must supply. Never shown to the customer.
    reason_code    text        NOT NULL,

    -- What the customer reads. Screened for tipping-off before it is written (CustomerRequestText).
    request_text   text        NOT NULL,

    -- The reviewer's IdentityId.
    requested_by   uuid        NOT NULL,

    -- Application-supplied from the injected Clock (DOMAIN_MODEL.md section Time); the deadline is fourteen days later
    -- (InformationRequest.ANSWER_WINDOW). Both doors judge the deadline on statement_timestamp().
    requested_at   timestamptz NOT NULL,
    due_at         timestamptz NOT NULL,

    -- The asking flow's correlation, carried to the expiry's audit record.
    correlation_id text        NOT NULL,

    status         text        NOT NULL,

    -- The database clock that judged the close.
    closed_at      timestamptz,

    answer_text    text,
    answered_by    uuid,

    CONSTRAINT information_request_status_is_known
        CHECK (status IN ('OPEN', 'ANSWERED', 'EXPIRED')),

    CONSTRAINT information_request_reason_is_known
        CHECK (reason_code IN ('DOCUMENT_UNREADABLE', 'DOCUMENT_EXPIRED', 'DOCUMENT_MISSING', 'PROOF_OF_ADDRESS', 'DETAILS_DIFFER', 'SOURCE_OF_FUNDS', 'OTHER')),

    CONSTRAINT information_request_text_is_bounded
        CHECK (char_length(request_text) BETWEEN 20 AND 1000),

    CONSTRAINT information_request_answer_is_bounded
        CHECK (answer_text IS NULL OR char_length(answer_text) BETWEEN 1 AND 2000),

    CONSTRAINT information_request_correlation_is_bounded
        CHECK (char_length(correlation_id) BETWEEN 1 AND 128),

    CONSTRAINT information_request_due_after_asked
        CHECK (due_at > requested_at),

    CONSTRAINT information_request_close_is_coherent
        CHECK (
            ((status = 'OPEN') = (closed_at IS NULL))
            AND ((status = 'ANSWERED') = (answer_text IS NOT NULL))
            AND ((status = 'ANSWERED') = (answered_by IS NOT NULL))
            AND (closed_at IS NULL OR closed_at >= requested_at)
        )
);

-- One open request per case: the database's arbiter behind the case's conditional move.
CREATE UNIQUE INDEX information_request_one_open_per_case
    ON kyc.information_request (case_id)
    WHERE status = 'OPEN';

-- The expiry sweep's range: the open requests, earliest due first, the id breaking a tie.
CREATE INDEX information_request_open_by_due
    ON kyc.information_request (due_at, id)
    WHERE status = 'OPEN';

-- The case file's history.
CREATE INDEX information_request_by_case ON kyc.information_request (case_id, requested_at, id);

CREATE OR REPLACE FUNCTION kyc.information_request_closes_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.case_id IS DISTINCT FROM OLD.case_id
        OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
        OR NEW.request_text IS DISTINCT FROM OLD.request_text
        OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR NEW.due_at IS DISTINCT FROM OLD.due_at
        OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
    THEN
        RAISE EXCEPTION 'an information request''s identity and text are immutable: only its close may be written (P17-TSK-126)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status <> 'OPEN' OR NEW.status NOT IN ('ANSWERED', 'EXPIRED') THEN
        RAISE EXCEPTION 'an information request moves from OPEN to ANSWERED or EXPIRED, once (P17-TSK-126, INV-LIFE-04)'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER information_request_closes_once
    BEFORE UPDATE ON kyc.information_request
    FOR EACH ROW
    EXECUTE FUNCTION kyc.information_request_closes_once();

GRANT SELECT, INSERT ON kyc.information_request TO finapp_app;
GRANT UPDATE (status, closed_at, answer_text, answered_by) ON kyc.information_request TO finapp_app;

COMMENT ON TABLE kyc.information_request IS
    'A reviewer''s request to a KYC case''s customer for more information (P17-TSK-126, ADR-0155): OPEN -> ANSWERED | EXPIRED, '
    'terminal; at most one OPEN per case, and a case is AWAITING_CUSTOMER exactly while it has one. Never deleted.';

COMMENT ON COLUMN kyc.information_request.request_text IS
    'CONFIDENTIAL. Written by a reviewer for the customer to read, screened for tipping-off (CustomerRequestText): it names '
    'what is needed, never a screening, a list, a suspicion, an investigation, an internal identifier or a link.';

COMMENT ON COLUMN kyc.information_request.answer_text IS
    'RESTRICTED-PII ceiling. The customer''s own words about themselves: shown to reviewers only (the case file), never logged '
    'and never in an event.';

COMMENT ON COLUMN kyc.information_request.answered_by IS
    'RESTRICTED-PII ceiling. The customer''s IdentityId - who answered.';

COMMENT ON COLUMN kyc.information_request.requested_by IS
    'RESTRICTED-PII ceiling. The reviewer''s IdentityId - who asked.';
