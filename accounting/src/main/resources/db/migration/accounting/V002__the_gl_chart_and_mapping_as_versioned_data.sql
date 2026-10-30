-- The GL chart and the purpose -> GL account mapping as versioned data, four-eyes (P14-TSK-003).
--
-- ADR-0113 decision 3, INV-ACC-06 (the activation half), INV-ACC-07, INV-AUD-04; PHASE_14_PLAN.md sections 6, 8.1 (T-map),
-- 9 and owner decision C2 (recommended default A, awaiting ratification): the mapping is keyed by LEDGER PURPOSE (+ owner
-- kind) - a counterparty is a dimension under one GL account, never a key - and `ledger.ledger_account.gl_code` stays
-- unwritten. A version holds its GL accounts (code, name, classification, statement section, normal balance) and its
-- rules; it is immutable from birth, activated by a person other than its proposer, effective from a stated calendar
-- month (C9) never earlier than a version already in force, and refused at activation unless total over the ledger's
-- purposes and classification-compatible with each purpose's ledger type (judged in the activation's transaction through
-- the `LedgerChart` port - the ledger's chart is not this schema's, so no CHECK here can name it).
--
-- NO MONEY: no table here holds an amount, a balance or a line. NO SEED: nothing activates a version by migration - the
-- reference chart in the test fixtures is a reference for qualified review (C2), never production data; production starts
-- with none and the first version is proposed and activated by two persons after deployment (ADR-0113, Consequences).
--
-- The lock (DISTRIBUTED_EXECUTION.md section 3, namespace 14): every proposal, activation and rejection takes advisory
-- namespace 14 on hashtext('mapping') exclusively, first (A2 of the Phase 14 lock order), then the version row FOR
-- UPDATE, then the ACTIVE row FOR UPDATE. The statements below hold no lock; the key is the code's
-- (JdbcGlMappingStore.LOCK_SQL, pinned by AccountingMappingMigrationTest). The partial unique and the CHECKs are the
-- backstops for every writer, the migrator included.

-- ---------------------------------------------------------------------------------------------
-- The machine row: no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.gl_mapping_version (
    id                   uuid        NOT NULL,
    version              integer     NOT NULL,
    status               text        NOT NULL,
    effective_from_period date       NOT NULL,
    effective_to_period  date,
    proposed_by          text        NOT NULL,
    proposed_by_type     text        NOT NULL,
    proposed_at          timestamptz NOT NULL,
    proposal_reason      text        NOT NULL,
    decided_by           text,
    decided_by_type      text,
    decided_at           timestamptz,
    decision_reason      text,
    retired_at           timestamptz,
    correlation_id       text        NOT NULL,
    CONSTRAINT gl_mapping_version_pk PRIMARY KEY (id),
    CONSTRAINT gl_mapping_version_number_unique UNIQUE (version),
    CONSTRAINT gl_mapping_version_number_positive CHECK (version >= 1),
    -- GlMappingVersionStatus.
    CONSTRAINT gl_mapping_version_status_is_known CHECK (status IN ('PROPOSED', 'ACTIVE', 'RETIRED', 'REJECTED')),
    -- Persons only: an authenticated human proposes and decides - never the platform, a service or a merchant
    -- (every operator session is a CUSTOMER-typed actor; EMPLOYEE is the platform's other person kind).
    CONSTRAINT gl_mapping_version_persons CHECK (
        proposed_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (decided_by_type IS NULL OR decided_by_type IN ('CUSTOMER', 'EMPLOYEE'))),
    CONSTRAINT gl_mapping_version_proposal_reasoned CHECK (char_length(proposal_reason) BETWEEN 1 AND 1000),
    CONSTRAINT gl_mapping_version_decision_coherent CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_by_type IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    CONSTRAINT gl_mapping_version_decision_reasoned CHECK (
        decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000),
    -- FOUR EYES, for every writer and with no seed exemption (INV-AUD-04, INV-ACC-07): whoever activated a version did
    -- not propose it.
    CONSTRAINT gl_mapping_version_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by),
    -- Calendar months (C9): a version takes effect from the first day of a month; a retired one ends where its successor
    -- begins, never before its own start (an equal start is a version superseded before any period pinned it).
    CONSTRAINT gl_mapping_version_effective_coherent CHECK (
        effective_from_period = date_trunc('month', effective_from_period)::date
        AND (status = 'RETIRED') = (effective_to_period IS NOT NULL)
        AND (status = 'RETIRED') = (retired_at IS NOT NULL)
        AND (effective_to_period IS NULL OR (effective_to_period = date_trunc('month', effective_to_period)::date
                AND effective_to_period >= effective_from_period)))
);

COMMENT ON TABLE accounting.gl_mapping_version IS
    'A GL mapping version''s machine (P14-TSK-003, ADR-0113 decision 3, INV-ACC-07): PROPOSED -> ACTIVE (a second person, total and classification-compatible against the ledger''s chart at activation) -> RETIRED (only beside its later successor, ending where it begins), or PROPOSED -> REJECTED. One ACTIVE; activator <> proposer by CHECK; persons only; identity frozen; effective from a month start never earlier than a version already in force; never activated by insert or by a migration. The content is accounting.gl_account and gl_mapping_rule, born with it and never changed. No money.';

-- One ACTIVE version - the backstop beneath namespace 14's key `mapping`.
CREATE UNIQUE INDEX gl_mapping_one_active ON accounting.gl_mapping_version (status) WHERE status = 'ACTIVE';

-- The edge trigger: every stamp clock_timestamp(), the instant written after the lock the act waited on.
CREATE OR REPLACE FUNCTION accounting.gl_mapping_version_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.decided_by_type IS NOT NULL
                OR NEW.decided_at IS NOT NULL OR NEW.decision_reason IS NOT NULL
                OR NEW.effective_to_period IS NOT NULL OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a GL mapping version is born PROPOSED and undecided: no version is activated by insert (P14-TSK-003)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a GL mapping version is never deleted (P14-TSK-003, INV-ACC-07)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.effective_from_period IS DISTINCT FROM OLD.effective_from_period
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_by_type IS DISTINCT FROM OLD.proposed_by_type
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a GL mapping version''s identity is frozen (P14-TSK-003, INV-ACC-07)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a GL mapping version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P14-TSK-003)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        IF NEW.effective_to_period IS NOT NULL OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a decision records no end: only a retirement does (P14-TSK-003)';
        END IF;
        -- NEVER RETROACTIVE (INV-ACC-07): an activation takes effect no earlier than any version already activated -
        -- the one in force (retired first, in this transaction) and every one before it. Periods (P14-TSK-005) add that
        -- the month is OPEN.
        IF NEW.status = 'ACTIVE' AND EXISTS (
                SELECT 1 FROM accounting.gl_mapping_version v
                 WHERE v.id <> NEW.id AND v.status IN ('ACTIVE', 'RETIRED')
                   AND v.effective_from_period > NEW.effective_from_period) THEN
            RAISE EXCEPTION 'a GL mapping version takes effect no earlier than a version already in force: % is retroactive (P14-TSK-003, INV-ACC-07)', NEW.effective_from_period;
        END IF;
        NEW.decided_at := clock_timestamp();
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
                OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P14-TSK-003)';
        END IF;
        NEW.retired_at := clock_timestamp();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gl_mapping_version_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.gl_mapping_version
    FOR EACH ROW
    EXECUTE FUNCTION accounting.gl_mapping_version_permits_only_machine_edges();

CREATE OR REPLACE FUNCTION accounting.gl_mapping_is_never_truncated()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'the GL mapping history is never truncated (P14-TSK-003, INV-ACC-07)';
END;
$$;

CREATE TRIGGER gl_mapping_version_is_never_truncated
    BEFORE TRUNCATE ON accounting.gl_mapping_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.gl_mapping_is_never_truncated();

-- A retirement commits only beside its successor: at COMMIT, a LATER version must be ACTIVE, starting where the retired
-- one ends - so the versions' month ranges meet and every month has exactly one version in force.
CREATE OR REPLACE FUNCTION accounting.gl_mapping_version_retires_only_beside_its_successor()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'RETIRED' AND NOT EXISTS (
            SELECT 1 FROM accounting.gl_mapping_version successor
             WHERE successor.status = 'ACTIVE' AND successor.version > NEW.version
               AND successor.effective_from_period = NEW.effective_to_period) THEN
        RAISE EXCEPTION 'GL mapping version % retired with no later ACTIVE successor starting where it ends (P14-TSK-003)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER gl_mapping_version_retires_only_beside_its_successor
    AFTER UPDATE ON accounting.gl_mapping_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting.gl_mapping_version_retires_only_beside_its_successor();

-- ---------------------------------------------------------------------------------------------
-- The content: born with its PROPOSED version, never changed. The GL accounts first.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.gl_account (
    version_id        uuid NOT NULL,
    code              text NOT NULL,
    name              text NOT NULL,
    classification    text NOT NULL,
    statement_section text NOT NULL,
    normal_balance    text NOT NULL,
    CONSTRAINT gl_account_pk PRIMARY KEY (version_id, code),
    CONSTRAINT gl_account_version_fk FOREIGN KEY (version_id) REFERENCES accounting.gl_mapping_version (id),
    CONSTRAINT gl_account_code_shape CHECK (code ~ '^[0-9]{4,8}$'),
    CONSTRAINT gl_account_name_bounded CHECK (char_length(name) BETWEEN 1 AND 120 AND name = btrim(name)),
    -- GlClassification: named exactly as the ledger's AccountType (AccountingLedgerVocabularyTest).
    CONSTRAINT gl_account_classification_is_known CHECK (classification IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    -- StatementSection.
    CONSTRAINT gl_account_section_is_known CHECK (statement_section IN ('CASH_AND_BANK', 'CLEARING_AND_SETTLEMENT_RECEIVABLES', 'LOANS_AND_BNPL_RECEIVABLES', 'OTHER_ASSETS', 'CUSTOMER_FUNDS', 'MERCHANT_PAYABLES', 'CLEARING_AND_SETTLEMENT_PAYABLES', 'OTHER_LIABILITIES', 'SUSPENSE', 'CAPITAL', 'FEE_AND_COMMISSION_INCOME', 'INTEREST_INCOME', 'FX_RESULT', 'OTHER_INCOME', 'PROCESSING_AND_DISPUTE_COSTS', 'CREDIT_LOSSES', 'OTHER_EXPENSES')),
    -- A section admits only its statement's classifications (StatementSection.admitted(), generated and held equal by
    -- AccountingMappingMigrationTest): a balance-sheet section never presents a revenue, an income-statement section
    -- never an asset.
    CONSTRAINT gl_account_section_admits_classification CHECK ((statement_section = 'CASH_AND_BANK' AND classification IN ('ASSET')) OR (statement_section = 'CLEARING_AND_SETTLEMENT_RECEIVABLES' AND classification IN ('ASSET')) OR (statement_section = 'LOANS_AND_BNPL_RECEIVABLES' AND classification IN ('ASSET')) OR (statement_section = 'OTHER_ASSETS' AND classification IN ('ASSET')) OR (statement_section = 'CUSTOMER_FUNDS' AND classification IN ('LIABILITY')) OR (statement_section = 'MERCHANT_PAYABLES' AND classification IN ('LIABILITY')) OR (statement_section = 'CLEARING_AND_SETTLEMENT_PAYABLES' AND classification IN ('LIABILITY')) OR (statement_section = 'OTHER_LIABILITIES' AND classification IN ('LIABILITY')) OR (statement_section = 'SUSPENSE' AND classification IN ('ASSET', 'LIABILITY')) OR (statement_section = 'CAPITAL' AND classification IN ('EQUITY')) OR (statement_section = 'FEE_AND_COMMISSION_INCOME' AND classification IN ('REVENUE')) OR (statement_section = 'INTEREST_INCOME' AND classification IN ('REVENUE')) OR (statement_section = 'FX_RESULT' AND classification IN ('REVENUE', 'EXPENSE')) OR (statement_section = 'OTHER_INCOME' AND classification IN ('REVENUE')) OR (statement_section = 'PROCESSING_AND_DISPUTE_COSTS' AND classification IN ('EXPENSE')) OR (statement_section = 'CREDIT_LOSSES' AND classification IN ('EXPENSE')) OR (statement_section = 'OTHER_EXPENSES' AND classification IN ('EXPENSE'))),
    -- The normal side is the classification's (GlClassification.debitNormal(), the ledger's AccountType.normalBalance()).
    CONSTRAINT gl_account_normal_balance_coherent CHECK (
        normal_balance IN ('DEBIT', 'CREDIT')
        AND (classification IN ('ASSET', 'EXPENSE')) = (normal_balance = 'DEBIT'))
);

COMMENT ON TABLE accounting.gl_account IS
    'A GL mapping version''s GL accounts (P14-TSK-003, ADR-0113 decision 3): code, name, classification (the ledger''s five account types by name), statement section (admitting only its statement''s classifications) and normal side. Reference values for qualified review (owner decision C2), not a statutory chart. Born with the version, INSERT-only. No money.';

-- The rules: one per (ledger purpose, owner kind) - every journal line maps to exactly one GL account under a version.
-- The purpose and the owner kind are the ledger's names as text: no cross-schema foreign key (ADR-0029), and no CHECK
-- naming the ledger's enum (a purpose the ledger adds must be mappable by a new version, not by a migration here) -
-- totality and compatibility are judged against the ledger's chart at activation.
CREATE TABLE accounting.gl_mapping_rule (
    version_id       uuid NOT NULL,
    ledger_purpose   text NOT NULL,
    owner_kind       text NOT NULL,
    gl_account_code  text NOT NULL,
    CONSTRAINT gl_mapping_rule_pk PRIMARY KEY (version_id, ledger_purpose, owner_kind),
    CONSTRAINT gl_mapping_rule_account_fk FOREIGN KEY (version_id, gl_account_code)
        REFERENCES accounting.gl_account (version_id, code),
    CONSTRAINT gl_mapping_rule_purpose_shape CHECK (ledger_purpose ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT gl_mapping_rule_owner_kind_shape CHECK (owner_kind ~ '^[A-Z][A-Z_]{0,31}$')
);

COMMENT ON TABLE accounting.gl_mapping_rule IS
    'A GL mapping version''s rules (P14-TSK-003, ADR-0113 decision 3, owner decision C2): ledger purpose + owner kind -> GL account of the same version. A counterparty is a dimension under the purpose''s GL account, never a key. Born with the version, INSERT-only.';

-- The content is immutable from insert, for every role, and born only with a PROPOSED version - in its proposing
-- transaction, never late: one function, two triggers.
CREATE OR REPLACE FUNCTION accounting.gl_mapping_content_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a GL mapping version''s content is immutable from insert, for every role (P14-TSK-003, INV-ACC-07)';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM accounting.gl_mapping_version v
                    WHERE v.id = NEW.version_id AND v.status = 'PROPOSED') THEN
        RAISE EXCEPTION 'a GL mapping''s content is born with its PROPOSED version (P14-TSK-003)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gl_account_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.gl_account
    FOR EACH ROW
    EXECUTE FUNCTION accounting.gl_mapping_content_is_born_once();

CREATE TRIGGER gl_mapping_rule_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.gl_mapping_rule
    FOR EACH ROW
    EXECUTE FUNCTION accounting.gl_mapping_content_is_born_once();

CREATE TRIGGER gl_account_is_never_truncated
    BEFORE TRUNCATE ON accounting.gl_account
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.gl_mapping_is_never_truncated();

CREATE TRIGGER gl_mapping_rule_is_never_truncated
    BEFORE TRUNCATE ON accounting.gl_mapping_rule
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.gl_mapping_is_never_truncated();

-- A version commits only whole: at COMMIT, at least one GL account and one rule.
CREATE OR REPLACE FUNCTION accounting.gl_mapping_version_has_its_content()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM accounting.gl_account a WHERE a.version_id = NEW.id)
            OR NOT EXISTS (SELECT 1 FROM accounting.gl_mapping_rule r WHERE r.version_id = NEW.id) THEN
        RAISE EXCEPTION 'GL mapping version % committed without its accounts and rules (P14-TSK-003)', NEW.version;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER gl_mapping_version_has_its_content
    AFTER INSERT ON accounting.gl_mapping_version
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION accounting.gl_mapping_version_has_its_content();

-- ---------------------------------------------------------------------------------------------
-- The history.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE accounting.gl_mapping_version_event (
    id              uuid        NOT NULL,
    version_id      uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    actor           text        NOT NULL,
    actor_type      text        NOT NULL,
    reason          text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT gl_mapping_version_event_pk PRIMARY KEY (id),
    CONSTRAINT gl_mapping_version_event_version_fk FOREIGN KEY (version_id)
        REFERENCES accounting.gl_mapping_version (id),
    -- GlMappingVersionStatus, and its only edges.
    CONSTRAINT gl_mapping_version_event_edge CHECK (
        (from_status IS NULL AND to_status = 'PROPOSED')
        OR (from_status = 'PROPOSED' AND to_status IN ('ACTIVE', 'REJECTED'))
        OR (from_status = 'ACTIVE' AND to_status = 'RETIRED')),
    CONSTRAINT gl_mapping_version_event_person CHECK (actor_type IN ('CUSTOMER', 'EMPLOYEE')),
    CONSTRAINT gl_mapping_version_event_reason_bounded CHECK (char_length(reason) BETWEEN 1 AND 1000)
);

CREATE INDEX gl_mapping_version_event_by_version ON accounting.gl_mapping_version_event (version_id, occurred_at);

COMMENT ON TABLE accounting.gl_mapping_version_event IS
    'A GL mapping version''s machine as history (P14-TSK-003): every edge, its actor, reason (RESTRICTED-PII, screened for instrument shapes by AccountingReasons) and correlation. Append-only for every writer.';

CREATE OR REPLACE FUNCTION accounting.gl_mapping_version_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a GL mapping version event is append-only (P14-TSK-003, INV-HIST-01)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER gl_mapping_version_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.gl_mapping_version_event
    FOR EACH ROW
    EXECUTE FUNCTION accounting.gl_mapping_version_event_is_append_only();

CREATE TRIGGER gl_mapping_version_event_is_never_truncated
    BEFORE TRUNCATE ON accounting.gl_mapping_version_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION accounting.gl_mapping_is_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The grants (accounting's first, written beside the tables they apply to - V001 sets no default privilege): the machine
-- row's decision and retirement columns; the content and the history INSERT-only; no DELETE for any role.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON accounting.gl_mapping_version TO finapp_app;
GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, effective_to_period, retired_at)
    ON accounting.gl_mapping_version TO finapp_app;
GRANT SELECT, INSERT ON accounting.gl_account TO finapp_app;
GRANT SELECT, INSERT ON accounting.gl_mapping_rule TO finapp_app;
GRANT SELECT, INSERT ON accounting.gl_mapping_version_event TO finapp_app;
