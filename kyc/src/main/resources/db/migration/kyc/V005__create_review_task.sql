-- Review tasks: the explicit work item a non-clean check becomes (P2-TSK-010, ADR-0038).
--
-- A HIT IS RESOLVED BY A PERSON, NEVER BY SILENCE (INV-KYC-04)
--   A screening hit is a probability, not a verdict: silently cleared is a sanctions breach,
--   silently rejected is a person refused service by string similarity. The task is the shape
--   that impossibility takes - the case becomes work for a person, countable, and (P2-TSK-012)
--   resolvable with a reason. The other route here is exhaustion: an INDETERMINATE past its
--   retry budget, because silence resolves nothing in either direction.
--
-- ONE TASK PER CHECK, EVER - the UNIQUE (check_id) index below is TOTAL, not partial.
--   Resolution never frees the slot: the resolution of THAT question is permanent evidence,
--   a wrong resolution is a new review event on the case, and changed circumstances are a NEW
--   check, which brings its own task. Totality is also the concurrent-creation arbiter: N
--   instances routing one blocked case produce one row per raising check
--   (ON CONFLICT DO NOTHING, row count is the outcome).
--
-- THE STATUS CHECK IS GENERATED - ReviewTaskStatus.sqlValueList(); ReviewTaskMigrationTest
--   fails the build if this file and the enum disagree (the P0-TSK-022 pattern).
--
-- RESOLUTION COLUMNS ARE DELIBERATELY ABSENT. Who resolved, why, and with what verdict are
--   P2-TSK-012's design to shape; columns nothing populates are the schema drift this
--   repository refuses (the P0-TSK-017 precedent). They arrive with the capability, as does
--   the UPDATE grant.

CREATE TABLE kyc.review_task (
    -- UUIDv7, minted by the application (ADR-0013).
    id        uuid        PRIMARY KEY,

    -- FKs within this module's own schema, which ADR-0029 permits (the kyc_document precedent).
    case_id   uuid        NOT NULL REFERENCES kyc.kyc_case (id),
    check_id  uuid        NOT NULL REFERENCES kyc.verification_check (id),

    status    text        NOT NULL,

    -- Application-supplied from one injected Clock, never DEFAULT now().
    opened_at timestamptz NOT NULL,

    CONSTRAINT review_task_status_is_known
        CHECK (status IN ('OPEN', 'RESOLVED'))
);

-- ONE REVIEW TASK PER CHECK, EVER. Total on purpose - see the header. A partial index over
-- OPEN alone would let a resolved check grow a second task, and "what did the reviewer decide
-- about this question?" must have at most one answer for the decision to reference
-- (INV-KYC-02).
CREATE UNIQUE INDEX review_task_one_per_check ON kyc.review_task (check_id);

-- Serves "the tasks of this case": P2-TSK-012's exit predicate (IN_REVIEW -> READY_FOR_DECISION
-- is conditional on no OPEN task, in the statement) and its reviewer listing.
CREATE INDEX review_task_by_case ON kyc.review_task (case_id);

COMMENT ON TABLE kyc.review_task IS
    'The explicit work item a non-clean check becomes (INV-KYC-04): OPEN -> RESOLVED, terminal, '
    'no unresolve. One task per check EVER - changed circumstances are a new check. Created '
    'atomically with the case''s move to IN_REVIEW, so that state always has at least one task; '
    'resolution (reason, reviewer) arrives with P2-TSK-012.';

-- SELECT and INSERT and nothing else, for now: the resolution UPDATE arrives with the
-- capability that performs it (P2-TSK-012) - the grant-arrives-with-the-capability rule
-- (P1-TSK-030's V004 precedent). No DELETE ever: a task is the trail of what needed a person.
GRANT SELECT, INSERT ON kyc.review_task TO finapp_app;
