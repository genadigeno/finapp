-- The projection rebuild's record (P14-TSK-026; ADR-0119 decision 1, ADR-0041 rule 2; INV-BAL-01, INV-BAL-02, INV-LED-03,
-- INV-LED-04, INV-AUD-04, INV-LIFE-01; PHASE_14_PLAN.md section 13.5's PROJECTION_VERIFIED).
--
-- WHAT IT IS
--   One row per four-eyes rebuild of ONE account's balance projection (ledger.account_balance, V006) from its journal lines
--   and its ACTIVE holds. A person proposes, recording the drift they observed; a second person approves, and the approval
--   recomputes under the posting's own locks and writes what IT computed - never what the proposal saw. The journal is never
--   touched: no table here references a journal line, and the rebuild writes only the four columns V006 already grants
--   (posted_minor, holds_minor, last_entry_seq, updated_at), or inserts a missing row. This table is the evidence ADR-0041
--   said a self-correcting ledger would destroy: what the proposer saw, what the approval found under the lock, what it wrote.
--
-- THE FIGURES ARE THE PROJECTION'S, AT ONE SCALE
--   Every *_minor column is in the account's currency at the row's scale (the derived scale when the row was absent): the
--   projection's own representation (V006), never re-scaled (INV-MON-03). The *_seq columns are applied-entry counts
--   (COUNT(DISTINCT entry_id) over the account's lines - V006's watermark), never an ordering.
--
-- THE MACHINE: PROPOSED -> APPLIED | UNCHANGED | REJECTED, each terminal. APPLIED: the row moved; UNCHANGED: the approval
--   found nothing to correct and wrote nothing to the projection; REJECTED: declined or withdrawn. APPLIED and UNCHANGED are
--   approvals, so a second person's: decided_by <> proposed_by, for every writer (INV-AUD-04).
--
-- ONE STANDING REBUILD PER ACCOUNT: a partial unique index - two proposers racing for one account meet on it (the second's
--   insert waits on the first's uncommitted tuple, then 23505), so two rebuilds of one account never stand together.
--
-- THE LOCKS (DISTRIBUTED_EXECUTION.md section 3): a proposal takes nothing but its own insert (the account FK's FOR KEY SHARE);
--   an approval takes this row FOR UPDATE, then the account FOR KEY SHARE, then the projection row FOR UPDATE - the posting's
--   order, the account's key share before the projection row - and no advisory lock.

CREATE TABLE ledger.projection_rebuild (
    id                            uuid        NOT NULL,
    ledger_account_id             uuid        NOT NULL,
    currency                      char(3)     NOT NULL,
    scale                         smallint    NOT NULL,
    status                        text        NOT NULL,
    reason                        text        NOT NULL,
    proposed_by                   text        NOT NULL,
    -- Application-supplied from one injected Clock, never DEFAULT now() (P0-TSK-015).
    proposed_at                   timestamptz NOT NULL,

    -- What the proposer observed, unlocked, by the verification's seq-bracketed protocol: the row (absent when
    -- observed_row is false) and what the journal and the ACTIVE holds derived.
    observed_row                  boolean     NOT NULL,
    observed_posted_minor         bigint,
    observed_holds_minor          bigint,
    observed_seq                  bigint,
    observed_derived_posted_minor bigint      NOT NULL,
    observed_derived_holds_minor  bigint      NOT NULL,
    observed_derived_seq          bigint      NOT NULL,

    decided_by                    text,
    decided_at                    timestamptz,

    -- What the approval found under the locks (before; absent when before_row is false) and what it wrote or found
    -- already there (after). Present exactly for APPLIED and UNCHANGED.
    before_row                    boolean,
    before_posted_minor           bigint,
    before_holds_minor            bigint,
    before_seq                    bigint,
    after_posted_minor            bigint,
    after_holds_minor             bigint,
    after_seq                     bigint,

    CONSTRAINT projection_rebuild_pk PRIMARY KEY (id),
    -- The V005 kind-binding mechanism: the rebuild's currency IS its account's, for every writer (INV-MON-02).
    CONSTRAINT projection_rebuild_currency_matches_account
        FOREIGN KEY (ledger_account_id, currency) REFERENCES ledger.ledger_account (id, currency),
    CONSTRAINT projection_rebuild_status_is_known
        CHECK (status IN ('PROPOSED', 'APPLIED', 'UNCHANGED', 'REJECTED')),
    CONSTRAINT projection_rebuild_currency_is_iso4217_shaped CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT projection_rebuild_scale_is_bounded CHECK (scale BETWEEN 0 AND 9),
    CONSTRAINT projection_rebuild_reason_is_bounded CHECK (length(btrim(reason)) BETWEEN 1 AND 1000),
    CONSTRAINT projection_rebuild_persons_are_bounded
        CHECK (length(proposed_by) BETWEEN 1 AND 200 AND (decided_by IS NULL OR length(decided_by) BETWEEN 1 AND 200)),

    -- FOUR EYES, for every writer (INV-AUD-04): whoever approved did not propose. A rejection may be the proposer's
    -- withdrawal.
    CONSTRAINT projection_rebuild_approver_is_not_proposer
        CHECK (status NOT IN ('APPLIED', 'UNCHANGED') OR decided_by <> proposed_by),

    -- The decision is one fact with the status.
    CONSTRAINT projection_rebuild_decision_matches_status
        CHECK ((status = 'PROPOSED') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    CONSTRAINT projection_rebuild_decision_follows_proposal
        CHECK (decided_at IS NULL OR decided_at >= proposed_at),

    -- The observation: the row's three figures present exactly when a row was observed; counts and holds not negative.
    CONSTRAINT projection_rebuild_observed_row_is_whole
        CHECK (observed_row = (observed_posted_minor IS NOT NULL)
            AND observed_row = (observed_holds_minor IS NOT NULL)
            AND observed_row = (observed_seq IS NOT NULL)),
    CONSTRAINT projection_rebuild_observed_counts
        CHECK ((observed_holds_minor IS NULL OR observed_holds_minor >= 0) AND (observed_seq IS NULL OR observed_seq >= 1)
            AND observed_derived_holds_minor >= 0 AND observed_derived_seq >= 0),

    -- The approval's figures present exactly for an approval; the row's half exactly when a row was found.
    CONSTRAINT projection_rebuild_approval_is_whole
        CHECK ((status IN ('APPLIED', 'UNCHANGED')) = (before_row IS NOT NULL)
            AND (before_row IS NOT NULL) = (after_posted_minor IS NOT NULL)
            AND (before_row IS NOT NULL) = (after_holds_minor IS NOT NULL)
            AND (before_row IS NOT NULL) = (after_seq IS NOT NULL)
            AND (coalesce(before_row, false)) = (before_posted_minor IS NOT NULL)
            AND (coalesce(before_row, false)) = (before_holds_minor IS NOT NULL)
            AND (coalesce(before_row, false)) = (before_seq IS NOT NULL)),
    CONSTRAINT projection_rebuild_approval_counts
        CHECK ((after_holds_minor IS NULL OR after_holds_minor >= 0) AND (after_seq IS NULL OR after_seq >= 1)
            AND (before_holds_minor IS NULL OR before_holds_minor >= 0) AND (before_seq IS NULL OR before_seq >= 1)),
    -- UNCHANGED: a row was found and it already said what the journal says. APPLIED: it did not, or none was found.
    CONSTRAINT projection_rebuild_unchanged_found_agreement
        CHECK (status <> 'UNCHANGED' OR (before_row AND before_posted_minor = after_posted_minor
            AND before_holds_minor = after_holds_minor AND before_seq = after_seq)),
    CONSTRAINT projection_rebuild_applied_moved_the_row
        CHECK (status <> 'APPLIED' OR NOT before_row OR before_posted_minor <> after_posted_minor
            OR before_holds_minor <> after_holds_minor OR before_seq <> after_seq)
);

COMMENT ON TABLE ledger.projection_rebuild IS
    'A four-eyes rebuild of one account''s balance projection from its journal lines and ACTIVE holds (P14-TSK-026, ADR-0119, '
    'ADR-0041 rule 2): what the proposer observed, what the approval found under the posting''s locks and what it wrote; '
    'PROPOSED -> APPLIED | UNCHANGED | REJECTED, terminal; approver <> proposer; one PROPOSED per account. Never touches a '
    'journal line; every figure in the account''s currency at the projection row''s scale.';

CREATE UNIQUE INDEX projection_rebuild_one_standing_per_account
    ON ledger.projection_rebuild (ledger_account_id)
    WHERE status = 'PROPOSED';

CREATE INDEX projection_rebuild_by_account ON ledger.projection_rebuild (ledger_account_id, proposed_at);

-- The payload is frozen and the only edges are the machine's, for every writer (the V010 shape).
CREATE OR REPLACE FUNCTION ledger.projection_rebuild_permits_only_decision()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger_projection_rebuild_is_kept: a projection rebuild''s record is never deleted (P14-TSK-026, INV-HIST-01)'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.ledger_account_id IS DISTINCT FROM OLD.ledger_account_id
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.observed_row IS DISTINCT FROM OLD.observed_row
            OR NEW.observed_posted_minor IS DISTINCT FROM OLD.observed_posted_minor
            OR NEW.observed_holds_minor IS DISTINCT FROM OLD.observed_holds_minor
            OR NEW.observed_seq IS DISTINCT FROM OLD.observed_seq
            OR NEW.observed_derived_posted_minor IS DISTINCT FROM OLD.observed_derived_posted_minor
            OR NEW.observed_derived_holds_minor IS DISTINCT FROM OLD.observed_derived_holds_minor
            OR NEW.observed_derived_seq IS DISTINCT FROM OLD.observed_derived_seq THEN
        RAISE EXCEPTION 'ledger_projection_rebuild_is_frozen: a rebuild''s proposal is immutable - the approver decides what they read (P14-TSK-026, INV-AUD-04)'
            USING ERRCODE = '23514';
    END IF;
    IF NOT (OLD.status = 'PROPOSED' AND NEW.status IN ('APPLIED', 'UNCHANGED', 'REJECTED')) THEN
        RAISE EXCEPTION 'ledger_projection_rebuild_edges: a rebuild moves PROPOSED -> APPLIED | UNCHANGED | REJECTED, once; % -> % is not an edge (INV-LIFE-01)',
                OLD.status, NEW.status
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER projection_rebuild_permits_only_decision
    BEFORE UPDATE OR DELETE ON ledger.projection_rebuild
    FOR EACH ROW
    EXECUTE FUNCTION ledger.projection_rebuild_permits_only_decision();

CREATE OR REPLACE FUNCTION ledger.projection_rebuild_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger_projection_rebuild_is_kept: the projection rebuilds are never truncated (P14-TSK-026, INV-HIST-01)'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER projection_rebuild_is_never_truncated
    BEFORE TRUNCATE ON ledger.projection_rebuild
    FOR EACH STATEMENT
    EXECUTE FUNCTION ledger.projection_rebuild_is_never_truncated();

-- The grants: born by INSERT, read by SELECT, decided by an UPDATE of the decision and approval columns only; no DELETE.
-- ledger.account_balance's grants are V006's, unchanged: the rebuild writes posted_minor, holds_minor, last_entry_seq and
-- updated_at, and inserts a missing row. The journal's grants are V004's, unchanged: SELECT and INSERT only, and the
-- rebuild uses SELECT alone.
GRANT SELECT, INSERT ON ledger.projection_rebuild TO finapp_app;
GRANT UPDATE (status, decided_by, decided_at, before_row, before_posted_minor, before_holds_minor, before_seq,
              after_posted_minor, after_holds_minor, after_seq)
    ON ledger.projection_rebuild TO finapp_app;
