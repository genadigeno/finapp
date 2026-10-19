-- BNPL disputes and the servicing hold (P12-TSK-023; ADR-0106; PHASE_12_PLAN.md sections 5.8, 7.1 T-dsp, 7.2, 7.4, 7.5,
-- 12.6, 12.7 and 12.8; BNPL_LIFECYCLES.md sections 4.10 and 5; OQ-10; INV-BNPL-08, INV-BNPL-07, INV-BNPL-06, INV-LIFE-01
-- ... -04, INV-AUD-04, INV-HIST-01, INV-MER-01).
--
-- bnpl V015 is this task's - the Phase 12 wave F board's reservation (P12-TSK-024 takes V016; Flyway runs with out-of-order
-- off, so this merges first).
--
-- A customer contests a BNPL purchase with the platform - there is no card network on it, so no chargeback. The disputed
-- amount D (0 < D <= G - refunded, judged under the purchase's lock B5) is HELD while the dispute is open: the instalments a
-- refund of D would cancel now, latest first (ADR-0105's allocator run as a dry run under B4), are suspended - no auto-
-- collection, no days past due, no late fee on them. The dispute resolves as a refund - the merchant's acceptance, the
-- merchant's silence past the pinned response window (OQ-10: 14 days), or an operator's decision after the merchant
-- answered - or as upheld (an operator's), or is withdrawn by the customer. A refund outcome IS a BnplRefund (door DISPUTE,
-- T-ref's body) in the resolving transaction, under the same B5 lock and the same INV-BNPL-07 bound, so a dispute and a
-- refund can never double-credit (INV-BNPL-08); no merchant or operator refund is admitted while a dispute is open. In this
-- migration:
--
--     bnpl_dispute           - the machine row, MONEY-FREE (BnplSchemaHasNoMutableMoneyTest: a table with a column-scoped
--                              UPDATE carries no *_minor column): the purchase, obligation, merchant, party and agreement by
--                              value, the pinned terms version, the status, the reason (category and RESTRICTED-PII text),
--                              the customer's key (UNIQUE (party_id, request_key)), the database-stamped opening, its
--                              business date, the response deadline and the sweep's permit, the merchant's statement, the
--                              resolution (who, why, when, its business date), the refund and the approved proposal. At
--                              most one OPEN or MERCHANT_RESPONDED per purchase (a partial unique). Its edge trigger holds
--                              the machine for every writer; a status CHECK and a shape CHECK beside it.
--     bnpl_dispute_claim     - the amount disputed and what was refunded before it, INSERT-only, born with its dispute; its
--                              trigger re-takes B5 then B4 and judges the bound for every writer.
--     bnpl_dispute_hold      - one row per held instalment (sequence, due date, the instalment of the version in force, the
--                              held principal and whether it was billed), INSERT-only, born with its dispute.
--     bnpl_dispute_proposal  - the operator's four-eyes resolution above the pinned threshold: PROPOSED -> APPROVED |
--                              REJECTED, never approved by its proposer (the CHECK), one live per dispute.
--     bnpl_dispute_event     - the dispute's history, one row per state, INSERT-only.
--
-- The hold is a CONDITION, never a state of the obligation, and it is read from the dispute rows - never a once-per-date
-- condition row (BNPL_LIFECYCLES.md section 8 (d)): an instalment is held on a date when a dispute holding its due date was
-- opened on or before that date and is not resolved by it; after the lift (any resolution) its days-past-due and its late
-- fee's day count from the later of its due date and the lift's business date - a good-faith contest never makes the
-- customer delinquent retroactively (ADR-0106 decision 3).
--
-- Replaced, the body verbatim but for what is said (posted on the wave F board, rule 3):
--
--     bnpl.days_past_due            - V013's, but an instalment the hold covers on the date is not past due, and every
--                                     instalment's clock starts at bnpl.dispute_clock_start (its due date when no dispute
--                                     ever held it - V013's exactly).
--
-- Added beside the old - nothing else replaced: bnpl.dispute_holds and bnpl.dispute_clock_start (the derivations,
-- BnplDisputeHold's twin); a BEFORE INSERT trigger on bnpl_fee_assessment (no fee on a held instalment, its day counted from
-- the clock start), one on bnpl_collection_attempt (no attempt on a held instalment) and one on bnpl_refund (no refund
-- beside an open dispute but that dispute's own); bnpl_refund.dispute_id with the door DISPUTE and its requester shape.
--
-- No bnpl trigger reads a ledger table (the P12-TSK-013 refinement). No cross-schema foreign key (ADR-0029). Every instant
-- the database's, clock_timestamp() after the act's locks.

-- ---------------------------------------------------------------------------------------------
-- The dispute: the machine row, no money.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_dispute (
    id                      uuid        NOT NULL,
    purchase_id             uuid        NOT NULL,
    obligation_id           uuid        NOT NULL,
    merchant_id             uuid        NOT NULL,
    party_id                uuid        NOT NULL,
    agreement_id            uuid        NOT NULL,
    terms_version_id        uuid        NOT NULL,
    status                  text        NOT NULL,
    reason_category         text        NOT NULL,
    reason                  text        NOT NULL,
    request_key             text        NOT NULL,
    opened_at               timestamptz NOT NULL,
    opened_business_date    date        NOT NULL,
    response_deadline_at    timestamptz NOT NULL,
    next_sweep_at           timestamptz NOT NULL,
    merchant_statement      text,
    merchant_responded_at   timestamptz,
    resolved_by             text,
    resolution_reason       text,
    resolved_at             timestamptz,
    resolved_business_date  date,
    refund_id               uuid,
    proposal_id             uuid,
    correlation_id          text        NOT NULL,
    CONSTRAINT bnpl_dispute_pk PRIMARY KEY (id),
    -- One dispute per customer's key (INV-IDEM-01): beneath the door's claim, the arbiter behind a lapsed one.
    CONSTRAINT bnpl_dispute_request_unique UNIQUE (party_id, request_key),
    -- One dispute, at most one refund (ADR-0106 decision 6); one approved proposal resolves one dispute.
    CONSTRAINT bnpl_dispute_refund_unique UNIQUE (refund_id),
    CONSTRAINT bnpl_dispute_proposal_unique UNIQUE (proposal_id),
    CONSTRAINT bnpl_dispute_purchase_fk FOREIGN KEY (purchase_id) REFERENCES bnpl.purchase (id),
    CONSTRAINT bnpl_dispute_obligation_fk FOREIGN KEY (obligation_id) REFERENCES bnpl.bnpl_obligation (id),
    CONSTRAINT bnpl_dispute_terms_version_fk FOREIGN KEY (terms_version_id) REFERENCES bnpl.bnpl_terms_version (id),
    -- BnplDisputeStatus - a membership list of the machine's states.
    CONSTRAINT bnpl_dispute_status_is_known CHECK (
        status IN ('OPEN', 'MERCHANT_RESPONDED', 'RESOLVED_REFUND', 'RESOLVED_UPHELD', 'WITHDRAWN')),
    -- BnplDisputeReason.
    CONSTRAINT bnpl_dispute_reason_category_is_known CHECK (
        reason_category IN ('NOT_RECEIVED', 'NOT_AS_DESCRIBED', 'RETURNED_NOT_REFUNDED', 'OTHER')),
    -- The shape of each state: resolved exactly when terminal, by someone, on a business date; a refund exactly for
    -- RESOLVED_REFUND; the merchant's statement with its instant, and always present once the merchant answered (an upheld
    -- dispute was answered); a proposal only on an operator's resolution.
    CONSTRAINT bnpl_dispute_shape CHECK (
        (status IN ('OPEN', 'MERCHANT_RESPONDED')) = (resolved_at IS NULL)
        AND (resolved_at IS NULL) = (resolved_by IS NULL)
        AND (resolved_at IS NULL) = (resolved_business_date IS NULL)
        AND (status = 'RESOLVED_REFUND') = (refund_id IS NOT NULL)
        AND (merchant_statement IS NULL) = (merchant_responded_at IS NULL)
        AND (status NOT IN ('MERCHANT_RESPONDED', 'RESOLVED_UPHELD') OR merchant_statement IS NOT NULL)
        AND (proposal_id IS NULL OR status IN ('RESOLVED_REFUND', 'RESOLVED_UPHELD'))
        AND (resolution_reason IS NULL OR resolved_at IS NOT NULL)
        AND response_deadline_at > opened_at),
    -- Who resolved it: the system (the deadline), the merchant over its key, the customer (a withdrawal), an operator.
    CONSTRAINT bnpl_dispute_resolver_shape CHECK (
        resolved_by IS NULL OR resolved_by = 'SYSTEM' OR resolved_by = 'MERCHANT:' || merchant_id::text
        OR resolved_by = 'CUSTOMER:' || party_id::text OR resolved_by ~ '^OPERATOR:.{1,200}$'),
    CONSTRAINT bnpl_dispute_key_bounded CHECK (char_length(request_key) BETWEEN 1 AND 200),
    -- The texts a person wrote: required where present, bounded, never an instrument's shape (RESTRICTED-PII, INV-AUD-02).
    CONSTRAINT bnpl_dispute_reason_bounded CHECK (
        char_length(reason) BETWEEN 1 AND 1000
        AND (merchant_statement IS NULL OR char_length(merchant_statement) BETWEEN 1 AND 1000)
        AND (resolution_reason IS NULL OR char_length(resolution_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT bnpl_dispute_reason_no_instrument_shape CHECK (
        NOT bnpl.holds_instrument_shape(reason)
        AND (merchant_statement IS NULL OR NOT bnpl.holds_instrument_shape(merchant_statement))
        AND (resolution_reason IS NULL OR NOT bnpl.holds_instrument_shape(resolution_reason))),
    CONSTRAINT bnpl_dispute_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_dispute IS
    'A BNPL dispute (P12-TSK-023; ADR-0106; BNPL_LIFECYCLES.md section 4.10): a customer''s contest of a BNPL purchase with the platform - no card network, no chargeback. OPEN -> MERCHANT_RESPONDED (the merchant''s evidence before the deadline) -> RESOLVED_REFUND | RESOLVED_UPHELD (an operator, four-eyes above the pinned threshold); OPEN -> RESOLVED_REFUND (the merchant accepts before the deadline, or its silence at it - OQ-10, the deadline stamped opened_at + the pinned window); OPEN | MERCHANT_RESPONDED -> WITHDRAWN (the customer). A refund outcome is a BnplRefund (door DISPUTE) in the resolving transaction. At most one open per purchase. Money-free: the amount is bnpl_dispute_claim''s; the hold is bnpl_dispute_hold''s. The reason, the merchant''s statement and the resolution reason RESTRICTED-PII; the rest CONFIDENTIAL.';

-- At most one open dispute per purchase (ADR-0106 decision 1): two instances opening one each - one commits, the other
-- meets this and is refused 409 bnpl.DisputeOpen.
CREATE UNIQUE INDEX bnpl_dispute_one_open_per_purchase ON bnpl.bnpl_dispute (purchase_id)
    WHERE status IN ('OPEN', 'MERCHANT_RESPONDED');
-- The deadline sweep's claim: OPEN disputes whose permit has come.
CREATE INDEX bnpl_dispute_due ON bnpl.bnpl_dispute (next_sweep_at, id) WHERE status = 'OPEN';
CREATE INDEX bnpl_dispute_by_merchant ON bnpl.bnpl_dispute (merchant_id, opened_at);
CREATE INDEX bnpl_dispute_by_obligation ON bnpl.bnpl_dispute (obligation_id);

-- ---------------------------------------------------------------------------------------------
-- The four-eyes proposal: the operator's resolution above the pinned threshold.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_dispute_proposal (
    id               uuid        NOT NULL,
    dispute_id       uuid        NOT NULL,
    outcome          text        NOT NULL,
    status           text        NOT NULL,
    proposed_by      text        NOT NULL,
    proposal_reason  text        NOT NULL,
    proposed_at      timestamptz NOT NULL,
    decided_by       text,
    decision_reason  text,
    decided_at       timestamptz,
    correlation_id   text        NOT NULL,
    CONSTRAINT bnpl_dispute_proposal_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_dispute_proposal_dispute_fk FOREIGN KEY (dispute_id) REFERENCES bnpl.bnpl_dispute (id),
    CONSTRAINT bnpl_dispute_proposal_outcome_is_known CHECK (outcome IN ('RESOLVED_REFUND', 'RESOLVED_UPHELD')),
    CONSTRAINT bnpl_dispute_proposal_status_is_known CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    -- Decided exactly when no longer PROPOSED.
    CONSTRAINT bnpl_dispute_proposal_decided_shape CHECK (
        (status = 'PROPOSED') = (decided_by IS NULL)
        AND (decided_by IS NULL) = (decided_at IS NULL)
        AND (decided_by IS NULL) = (decision_reason IS NULL)),
    -- FOUR-EYES (INV-AUD-04, ADR-0106 decision 5): an approval is never its proposer's - a proposer may only withdraw it
    -- (a rejection).
    CONSTRAINT bnpl_dispute_proposal_four_eyes CHECK (status <> 'APPROVED' OR decided_by <> proposed_by),
    CONSTRAINT bnpl_dispute_proposal_actor_shape CHECK (
        proposed_by ~ '^OPERATOR:.{1,200}$' AND (decided_by IS NULL OR decided_by ~ '^OPERATOR:.{1,200}$')),
    CONSTRAINT bnpl_dispute_proposal_reason_bounded CHECK (
        char_length(proposal_reason) BETWEEN 1 AND 1000
        AND (decision_reason IS NULL OR char_length(decision_reason) BETWEEN 1 AND 1000)),
    CONSTRAINT bnpl_dispute_proposal_reason_no_instrument_shape CHECK (
        NOT bnpl.holds_instrument_shape(proposal_reason)
        AND (decision_reason IS NULL OR NOT bnpl.holds_instrument_shape(decision_reason))),
    CONSTRAINT bnpl_dispute_proposal_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_dispute_proposal IS
    'An operator''s proposed resolution of a BNPL dispute above its pinned four-eyes threshold (P12-TSK-023; ADR-0106 decision 5): PROPOSED -> APPROVED (by another operator, the CHECK) | REJECTED (by any, the proposer''s withdrawal included); at most one live (PROPOSED or APPROVED) per dispute; the approved one named by the dispute''s resolution. Money-free; the reasons RESTRICTED-PII.';

-- One live proposal per dispute: two operators proposing at once - one commits, the other is 409 bnpl.ProposalPending.
CREATE UNIQUE INDEX bnpl_dispute_proposal_one_live ON bnpl.bnpl_dispute_proposal (dispute_id)
    WHERE status IN ('PROPOSED', 'APPROVED');

ALTER TABLE bnpl.bnpl_dispute ADD CONSTRAINT bnpl_dispute_proposal_fk
    FOREIGN KEY (proposal_id) REFERENCES bnpl.bnpl_dispute_proposal (id);

-- ---------------------------------------------------------------------------------------------
-- The claim: the amount disputed, INSERT-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_dispute_claim (
    dispute_id             uuid     NOT NULL,
    currency               char(3)  NOT NULL,
    scale                  smallint NOT NULL,
    amount_minor           bigint   NOT NULL,
    refunded_before_minor  bigint   NOT NULL,
    CONSTRAINT bnpl_dispute_claim_pk PRIMARY KEY (dispute_id),
    CONSTRAINT bnpl_dispute_claim_dispute_fk FOREIGN KEY (dispute_id) REFERENCES bnpl.bnpl_dispute (id),
    CONSTRAINT bnpl_dispute_claim_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_dispute_claim_amounts CHECK (amount_minor > 0 AND refunded_before_minor >= 0)
);

COMMENT ON TABLE bnpl.bnpl_dispute_claim IS
    'A BNPL dispute''s claim (P12-TSK-023; ADR-0106 decision 1): the amount D disputed, 0 < D <= G - refunded, and what the purchase''s refunds totalled at the opening - judged under the purchase''s lock (B5) for every writer. Born once with its dispute; INSERT-only (RESTRICTED-FINANCIAL). A refund outcome refunds exactly D (INV-BNPL-08).';

CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_claim_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    dispute bnpl.bnpl_dispute%ROWTYPE;
    financing bnpl.merchant_financing%ROWTYPE;
    refunded bigint;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL dispute''s claim is immutable from insert, for every role (P12-TSK-023, INV-HIST-01)';
    END IF;
    SELECT * INTO dispute FROM bnpl.bnpl_dispute d WHERE d.id = NEW.dispute_id;
    -- (B5) then (B4), for every writer - the bound judged under the lock every refund and dispute takes first.
    PERFORM 1 FROM bnpl.purchase p WHERE p.id = dispute.purchase_id FOR UPDATE;
    PERFORM 1 FROM bnpl.bnpl_obligation o WHERE o.id = dispute.obligation_id FOR UPDATE;
    SELECT * INTO financing FROM bnpl.merchant_financing f WHERE f.purchase_id = dispute.purchase_id;
    IF dispute.id IS NULL OR dispute.status <> 'OPEN' OR NEW.currency <> financing.currency
            OR NEW.scale <> financing.scale THEN
        RAISE EXCEPTION 'a BNPL dispute''s claim is its OPEN dispute''s, in its purchase''s currency and scale (P12-TSK-023)';
    END IF;
    -- INV-BNPL-07's bound, shared (INV-BNPL-08): what was refunded before, exactly, and the claim within the remainder.
    SELECT coalesce(sum(r.amount_minor), 0) INTO refunded FROM bnpl.bnpl_refund r WHERE r.purchase_id = dispute.purchase_id;
    IF NEW.refunded_before_minor <> refunded OR refunded + NEW.amount_minor > financing.gross_minor THEN
        RAISE EXCEPTION 'a BNPL dispute claims at most what remains of its purchase: 0 < D <= G - refunded (P12-TSK-023, INV-BNPL-07, INV-BNPL-08)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_dispute_claim_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_dispute_claim
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_dispute_claim_is_born_once();

CREATE TRIGGER bnpl_dispute_claim_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_dispute_claim
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The dispute's edges, for every writer.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    purchase bnpl.purchase%ROWTYPE;
    obligation bnpl.bnpl_obligation%ROWTYPE;
    terms bnpl.bnpl_terms%ROWTYPE;
    proposal bnpl.bnpl_dispute_proposal%ROWTYPE;
    disputed bigint;
    now_at timestamptz;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL dispute is never deleted (P12-TSK-023, INV-HIST-01)';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.merchant_statement IS NOT NULL OR NEW.merchant_responded_at IS NOT NULL
                OR NEW.resolved_by IS NOT NULL OR NEW.resolution_reason IS NOT NULL OR NEW.resolved_at IS NOT NULL
                OR NEW.resolved_business_date IS NOT NULL OR NEW.refund_id IS NOT NULL OR NEW.proposal_id IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute is born OPEN, unanswered and unresolved (P12-TSK-023)';
        END IF;
        -- (B5) then (B4), for every writer: T-dsp holds both already; re-taking them is free.
        SELECT * INTO purchase FROM bnpl.purchase p WHERE p.id = NEW.purchase_id FOR UPDATE;
        SELECT * INTO obligation FROM bnpl.bnpl_obligation o WHERE o.purchase_id = NEW.purchase_id FOR UPDATE;
        IF purchase.id IS NULL OR purchase.status <> 'FINANCED' OR purchase.merchant_id <> NEW.merchant_id
                OR purchase.party_id <> NEW.party_id OR obligation.id IS DISTINCT FROM NEW.obligation_id
                OR obligation.agreement_id <> NEW.agreement_id THEN
            RAISE EXCEPTION 'a BNPL dispute contests a FINANCED purchase of its own party and merchant, against its own obligation and agreement (P12-TSK-023, INV-MER-01)';
        END IF;
        -- The terms version the agreement in force pins: its response window (OQ-10) and its four-eyes threshold.
        IF NEW.terms_version_id <> (SELECT a.terms_version_id FROM bnpl.bnpl_agreement a
                                     WHERE a.obligation_id = obligation.id ORDER BY a.version DESC LIMIT 1) THEN
            RAISE EXCEPTION 'a BNPL dispute names the terms version its agreement in force pins (P12-TSK-023, INV-HIST-04)';
        END IF;
        SELECT * INTO terms FROM bnpl.bnpl_terms t WHERE t.terms_version_id = NEW.terms_version_id;
        -- The database stamps the opening after the locks, its business date in the pinned zone, the deadline and the
        -- sweep's permit at it.
        NEW.opened_at := clock_timestamp();
        NEW.opened_business_date := bnpl.business_date(NEW.opened_at, obligation.servicing_zone);
        NEW.response_deadline_at := NEW.opened_at + make_interval(days => terms.dispute_response_days);
        NEW.next_sweep_at := NEW.response_deadline_at;
        RETURN NEW;
    END IF;
    -- UPDATE: the identity, the claim's context and the opening are frozen from birth.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.purchase_id IS DISTINCT FROM OLD.purchase_id
            OR NEW.obligation_id IS DISTINCT FROM OLD.obligation_id OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.party_id IS DISTINCT FROM OLD.party_id OR NEW.agreement_id IS DISTINCT FROM OLD.agreement_id
            OR NEW.terms_version_id IS DISTINCT FROM OLD.terms_version_id
            OR NEW.reason_category IS DISTINCT FROM OLD.reason_category OR NEW.reason IS DISTINCT FROM OLD.reason
            OR NEW.request_key IS DISTINCT FROM OLD.request_key OR NEW.opened_at IS DISTINCT FROM OLD.opened_at
            OR NEW.opened_business_date IS DISTINCT FROM OLD.opened_business_date
            OR NEW.response_deadline_at IS DISTINCT FROM OLD.response_deadline_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL dispute''s identity, reason and deadline are frozen from birth (P12-TSK-023)';
    END IF;
    -- A permit-only move: only an OPEN dispute has a permit, and nothing but the permit moves.
    IF NEW.status = OLD.status THEN
        IF OLD.status <> 'OPEN' OR NEW.next_sweep_at IS NOT DISTINCT FROM OLD.next_sweep_at
                OR NEW.merchant_statement IS DISTINCT FROM OLD.merchant_statement
                OR NEW.merchant_responded_at IS DISTINCT FROM OLD.merchant_responded_at
                OR NEW.resolved_by IS DISTINCT FROM OLD.resolved_by
                OR NEW.resolution_reason IS DISTINCT FROM OLD.resolution_reason
                OR NEW.resolved_at IS DISTINCT FROM OLD.resolved_at
                OR NEW.resolved_business_date IS DISTINCT FROM OLD.resolved_business_date
                OR NEW.refund_id IS DISTINCT FROM OLD.refund_id OR NEW.proposal_id IS DISTINCT FROM OLD.proposal_id THEN
            RAISE EXCEPTION 'only an OPEN BNPL dispute has a permit to move, and only its permit; % is not (P12-TSK-023)', OLD.status;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status NOT IN ('OPEN', 'MERCHANT_RESPONDED') THEN
        RAISE EXCEPTION 'a % BNPL dispute is terminal (P12-TSK-023, INV-LIFE-04)', OLD.status;
    END IF;
    IF NEW.next_sweep_at IS DISTINCT FROM OLD.next_sweep_at THEN
        RAISE EXCEPTION 'a BNPL dispute''s edge moves no permit (P12-TSK-023)';
    END IF;
    SELECT o.* INTO obligation FROM bnpl.bnpl_obligation o WHERE o.id = OLD.obligation_id;
    SELECT c.amount_minor INTO disputed FROM bnpl.bnpl_dispute_claim c WHERE c.dispute_id = OLD.id;
    SELECT * INTO terms FROM bnpl.bnpl_terms t WHERE t.terms_version_id = OLD.terms_version_id;
    now_at := clock_timestamp();
    -- The merchant's answer, before the deadline: its statement recorded, the hold standing.
    IF NEW.status = 'MERCHANT_RESPONDED' THEN
        IF OLD.status <> 'OPEN' OR NEW.merchant_statement IS NULL OR OLD.merchant_statement IS NOT NULL
                OR OLD.response_deadline_at <= now_at OR NEW.resolved_by IS NOT NULL OR NEW.refund_id IS NOT NULL
                OR NEW.proposal_id IS NOT NULL OR NEW.resolution_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute moves OPEN -> MERCHANT_RESPONDED only by the merchant''s statement, before the response deadline on the database clock (P12-TSK-023, OQ-10)';
        END IF;
        NEW.merchant_responded_at := now_at;
        RETURN NEW;
    END IF;
    IF NEW.status = 'RESOLVED_REFUND' AND OLD.status = 'OPEN' THEN
        -- The merchant's acceptance before the deadline, or its silence at it: complementary conditionals on one clock,
        -- judged under the dispute's row lock - exactly one of the response and the sweep wins (OQ-10).
        IF NEW.resolved_by = 'MERCHANT:' || OLD.merchant_id::text THEN
            IF OLD.response_deadline_at <= now_at OR NEW.merchant_statement IS NULL THEN
                RAISE EXCEPTION 'a merchant accepts a BNPL dispute only before the response deadline, with its statement (P12-TSK-023, OQ-10)';
            END IF;
            NEW.merchant_responded_at := now_at;
        ELSIF NEW.resolved_by = 'SYSTEM' THEN
            IF OLD.response_deadline_at > now_at OR NEW.merchant_statement IS NOT NULL THEN
                RAISE EXCEPTION 'a BNPL dispute lapses to a refund only once its response deadline has passed on the database clock (P12-TSK-023, OQ-10)';
            END IF;
        ELSE
            RAISE EXCEPTION 'an OPEN BNPL dispute resolves as a refund only by its merchant or at its deadline (P12-TSK-023)';
        END IF;
        IF NEW.proposal_id IS NOT NULL OR NEW.refund_id IS NULL OR NEW.resolution_reason IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute resolved as a refund names its refund, and no proposal (P12-TSK-023, INV-BNPL-08)';
        END IF;
    ELSIF NEW.status IN ('RESOLVED_REFUND', 'RESOLVED_UPHELD') THEN
        -- The operator's decision after the merchant answered, reasoned - four-eyes above the pinned threshold.
        IF OLD.status <> 'MERCHANT_RESPONDED' OR NEW.resolved_by !~ '^OPERATOR:.{1,200}$'
                OR NEW.resolution_reason IS NULL
                OR NEW.merchant_statement IS DISTINCT FROM OLD.merchant_statement
                OR NEW.merchant_responded_at IS DISTINCT FROM OLD.merchant_responded_at THEN
            RAISE EXCEPTION 'a BNPL dispute the merchant answered is resolved by an operator, reasoned (P12-TSK-023, ADR-0106 decision 5)';
        END IF;
        IF disputed > terms.dispute_four_eyes_threshold_minor THEN
            SELECT * INTO proposal FROM bnpl.bnpl_dispute_proposal x WHERE x.id = NEW.proposal_id;
            IF proposal.id IS NULL OR proposal.dispute_id <> OLD.id OR proposal.status <> 'APPROVED'
                    OR proposal.outcome <> NEW.status OR proposal.decided_by <> NEW.resolved_by
                    OR proposal.proposed_by = proposal.decided_by THEN
                RAISE EXCEPTION 'a BNPL dispute above its four-eyes threshold is resolved by a proposal another operator approved (P12-TSK-023, INV-AUD-04)';
            END IF;
        ELSIF NEW.proposal_id IS NOT NULL THEN
            RAISE EXCEPTION 'a BNPL dispute at or below its four-eyes threshold is resolved by one operator, no proposal (P12-TSK-023)';
        END IF;
    ELSIF NEW.status = 'WITHDRAWN' THEN
        IF NEW.resolved_by <> 'CUSTOMER:' || OLD.party_id::text OR NEW.refund_id IS NOT NULL
                OR NEW.proposal_id IS NOT NULL OR NEW.merchant_statement IS DISTINCT FROM OLD.merchant_statement
                OR NEW.merchant_responded_at IS DISTINCT FROM OLD.merchant_responded_at THEN
            RAISE EXCEPTION 'a BNPL dispute is withdrawn only by its own customer, refunding nothing (P12-TSK-023)';
        END IF;
    ELSE
        RAISE EXCEPTION 'a BNPL dispute moves % -> %: not an edge (P12-TSK-023, INV-LIFE-02)', OLD.status, NEW.status;
    END IF;
    -- The resolution's instant and business date: the database's, after the act's locks.
    NEW.resolved_at := GREATEST(now_at, OLD.opened_at);
    NEW.resolved_business_date := bnpl.business_date(NEW.resolved_at, obligation.servicing_zone);
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_dispute_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_dispute
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_dispute_permits_only_machine_edges();

CREATE TRIGGER bnpl_dispute_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_dispute
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_proposal_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    dispute bnpl.bnpl_dispute%ROWTYPE;
    disputed bigint;
    threshold bigint;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a BNPL dispute proposal is never deleted (P12-TSK-023, INV-HIST-01)';
    END IF;
    SELECT * INTO dispute FROM bnpl.bnpl_dispute d WHERE d.id = NEW.dispute_id;
    IF TG_OP = 'INSERT' THEN
        SELECT c.amount_minor INTO disputed FROM bnpl.bnpl_dispute_claim c WHERE c.dispute_id = NEW.dispute_id;
        SELECT t.dispute_four_eyes_threshold_minor INTO threshold FROM bnpl.bnpl_terms t
         WHERE t.terms_version_id = dispute.terms_version_id;
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR dispute.id IS NULL
                OR dispute.status <> 'MERCHANT_RESPONDED' OR disputed <= threshold THEN
            RAISE EXCEPTION 'a BNPL dispute proposal is born PROPOSED, for a dispute the merchant answered whose amount is above its four-eyes threshold (P12-TSK-023)';
        END IF;
        NEW.proposed_at := clock_timestamp();
        RETURN NEW;
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.dispute_id IS DISTINCT FROM OLD.dispute_id
            OR NEW.outcome IS DISTINCT FROM OLD.outcome OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a BNPL dispute proposal''s identity is frozen from birth (P12-TSK-023)';
    END IF;
    IF OLD.status <> 'PROPOSED' OR NEW.status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'a BNPL dispute proposal moves PROPOSED -> APPROVED | REJECTED only (P12-TSK-023, INV-LIFE-02)';
    END IF;
    IF NEW.status = 'APPROVED' AND dispute.status <> 'MERCHANT_RESPONDED' THEN
        RAISE EXCEPTION 'a BNPL dispute proposal is approved only while its dispute awaits resolution (P12-TSK-023)';
    END IF;
    NEW.decided_at := GREATEST(clock_timestamp(), OLD.proposed_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_dispute_proposal_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_dispute_proposal
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_dispute_proposal_permits_only_machine_edges();

CREATE TRIGGER bnpl_dispute_proposal_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_dispute_proposal
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The hold: the instalments a refund of D would cancel at the opening, latest first. INSERT-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_dispute_hold (
    id                    uuid     NOT NULL,
    dispute_id            uuid     NOT NULL,
    obligation_id         uuid     NOT NULL,
    sequence              smallint NOT NULL,
    instalment_id         uuid     NOT NULL,
    due_date              date     NOT NULL,
    kind                  text     NOT NULL,
    currency              char(3)  NOT NULL,
    scale                 smallint NOT NULL,
    held_principal_minor  bigint   NOT NULL,
    CONSTRAINT bnpl_dispute_hold_pk PRIMARY KEY (id),
    -- Each instalment once per dispute (the dry run walks each once, latest first).
    CONSTRAINT bnpl_dispute_hold_sequence_unique UNIQUE (dispute_id, sequence),
    CONSTRAINT bnpl_dispute_hold_dispute_fk FOREIGN KEY (dispute_id) REFERENCES bnpl.bnpl_dispute (id),
    CONSTRAINT bnpl_dispute_hold_instalment_fk FOREIGN KEY (instalment_id, obligation_id)
        REFERENCES bnpl.bnpl_instalment (id, obligation_id),
    -- RefundAllocation.Cancellation.Kind: the principal still to bill, or billed and unpaid.
    CONSTRAINT bnpl_dispute_hold_kind_is_known CHECK (kind IN ('UNBILLED', 'BILLED_UNPAID')),
    CONSTRAINT bnpl_dispute_hold_currency_is_the_products CHECK (currency = 'EUR' AND scale BETWEEN 0 AND 9),
    CONSTRAINT bnpl_dispute_hold_amounts CHECK (sequence >= 1 AND held_principal_minor > 0)
);

COMMENT ON TABLE bnpl.bnpl_dispute_hold IS
    'A BNPL dispute''s hold of one instalment (P12-TSK-023; ADR-0106 decision 3, A20): the instalments a refund of the disputed amount would cancel at the opening - ADR-0105''s latest-first allocator run as a dry run under the obligation''s lock (B4) - each with the principal it holds and whether it was billed. While the dispute is OPEN or MERCHANT_RESPONDED the instalment (by its due date, stable across schedule versions) takes no auto-collection, no days past due and no late fee; after the lift its clock starts at the later of its due date and the lift''s business date. Born once with its dispute; INSERT-only (RESTRICTED-FINANCIAL).';

CREATE INDEX bnpl_dispute_hold_by_obligation ON bnpl.bnpl_dispute_hold (obligation_id, due_date);

CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_hold_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    dispute bnpl.bnpl_dispute%ROWTYPE;
    instalment bnpl.bnpl_instalment%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL dispute hold is immutable from insert, for every role (P12-TSK-023, INV-HIST-01)';
    END IF;
    SELECT * INTO dispute FROM bnpl.bnpl_dispute d WHERE d.id = NEW.dispute_id;
    SELECT * INTO instalment FROM bnpl.bnpl_instalment i WHERE i.id = NEW.instalment_id;
    -- Born with its OPEN dispute, on its obligation, naming its instalment's own sequence and due date.
    IF dispute.id IS NULL OR dispute.status <> 'OPEN' OR dispute.merchant_responded_at IS NOT NULL
            OR dispute.obligation_id <> NEW.obligation_id OR instalment.sequence <> NEW.sequence
            OR instalment.due_date <> NEW.due_date OR instalment.currency <> NEW.currency
            OR instalment.scale <> NEW.scale THEN
        RAISE EXCEPTION 'a BNPL dispute hold is its OPEN dispute''s, of an instalment of its obligation by its own sequence and due date (P12-TSK-023)';
    END IF;
    -- Billed-unpaid names a billed due date; unbilled one not yet billed.
    IF (NEW.kind = 'BILLED_UNPAID') <> EXISTS (SELECT 1 FROM bnpl.bnpl_billing b
                                                WHERE b.obligation_id = NEW.obligation_id
                                                  AND b.value_date = NEW.due_date) THEN
        RAISE EXCEPTION 'a BNPL dispute holds a billed instalment as BILLED_UNPAID and an unbilled one as UNBILLED (P12-TSK-023)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_dispute_hold_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_dispute_hold
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_dispute_hold_is_born_once();

CREATE TRIGGER bnpl_dispute_hold_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_dispute_hold
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The history: one row per state, INSERT-only.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE bnpl.bnpl_dispute_event (
    id              uuid        NOT NULL,
    dispute_id      uuid        NOT NULL,
    from_status     text,
    to_status       text        NOT NULL,
    actor           text        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    correlation_id  text        NOT NULL,
    CONSTRAINT bnpl_dispute_event_pk PRIMARY KEY (id),
    CONSTRAINT bnpl_dispute_event_dispute_fk FOREIGN KEY (dispute_id) REFERENCES bnpl.bnpl_dispute (id),
    -- Each state entered once: the machine never returns to a state.
    CONSTRAINT bnpl_dispute_event_once_per_state UNIQUE (dispute_id, to_status),
    CONSTRAINT bnpl_dispute_event_status_is_known CHECK (
        to_status IN ('OPEN', 'MERCHANT_RESPONDED', 'RESOLVED_REFUND', 'RESOLVED_UPHELD', 'WITHDRAWN')
        AND (from_status IS NULL OR from_status IN ('OPEN', 'MERCHANT_RESPONDED'))
        AND (from_status IS NULL) = (to_status = 'OPEN')),
    CONSTRAINT bnpl_dispute_event_actor_bounded CHECK (char_length(actor) BETWEEN 1 AND 210),
    CONSTRAINT bnpl_dispute_event_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

COMMENT ON TABLE bnpl.bnpl_dispute_event IS
    'A BNPL dispute''s history (P12-TSK-023): one row per state it entered - the birth (from NULL to OPEN) and each edge - with who moved it (CUSTOMER:<party>, MERCHANT:<merchant>, OPERATOR:<identity>, SYSTEM) and the database''s instant. INSERT-only; CONFIDENTIAL.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_event_is_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a BNPL dispute''s history is append-only, for every role (P12-TSK-023, INV-HIST-01)';
    END IF;
    -- The state it records is the dispute's own, written in the edge's transaction after the edge.
    IF NOT EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d WHERE d.id = NEW.dispute_id AND d.status = NEW.to_status) THEN
        RAISE EXCEPTION 'a BNPL dispute''s history records the state its dispute is in (P12-TSK-023)';
    END IF;
    NEW.occurred_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_dispute_event_is_append_only
    BEFORE INSERT OR UPDATE OR DELETE ON bnpl.bnpl_dispute_event
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_dispute_event_is_append_only();

CREATE TRIGGER bnpl_dispute_event_is_never_truncated
    BEFORE TRUNCATE ON bnpl.bnpl_dispute_event
    FOR EACH STATEMENT
    EXECUTE FUNCTION bnpl.acceptance_rows_are_never_truncated();

-- ---------------------------------------------------------------------------------------------
-- The refund, extended: the door DISPUTE, one refund per dispute.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE bnpl.bnpl_refund ADD COLUMN dispute_id uuid;
ALTER TABLE bnpl.bnpl_refund ADD CONSTRAINT bnpl_refund_dispute_fk
    FOREIGN KEY (dispute_id) REFERENCES bnpl.bnpl_dispute (id);
ALTER TABLE bnpl.bnpl_refund ADD CONSTRAINT bnpl_refund_dispute_unique UNIQUE (dispute_id);
ALTER TABLE bnpl.bnpl_refund DROP CONSTRAINT bnpl_refund_door_is_known;
ALTER TABLE bnpl.bnpl_refund ADD CONSTRAINT bnpl_refund_door_is_known CHECK (door IN ('MERCHANT', 'OPERATOR', 'DISPUTE'));
ALTER TABLE bnpl.bnpl_refund DROP CONSTRAINT bnpl_refund_requester_shape;
-- A dispute's refund is requested by its dispute, under the dispute's id as its key: one dispute, one refund.
ALTER TABLE bnpl.bnpl_refund ADD CONSTRAINT bnpl_refund_requester_shape CHECK (
    (door = 'MERCHANT' AND requested_by = 'MERCHANT:' || merchant_id::text AND dispute_id IS NULL)
    OR (door = 'OPERATOR' AND requested_by ~ '^OPERATOR:.{1,200}$' AND dispute_id IS NULL)
    OR (door = 'DISPUTE' AND dispute_id IS NOT NULL AND requested_by = 'DISPUTE:' || dispute_id::text
        AND refund_key = dispute_id::text));

-- The dispute names its refund: born in the resolving transaction before the edge that names it.
ALTER TABLE bnpl.bnpl_dispute ADD CONSTRAINT bnpl_dispute_refund_fk
    FOREIGN KEY (refund_id) REFERENCES bnpl.bnpl_refund (id);

COMMENT ON COLUMN bnpl.bnpl_refund.dispute_id IS
    'The dispute a refund of door DISPUTE resolves (P12-TSK-023; ADR-0106 decision 6): one dispute, at most one refund, requested by DISPUTE:<dispute> under the dispute''s id as its key. NULL for the merchant''s and the operator''s doors.';

-- No refund beside an open dispute (ADR-0106 decision 7): a merchant or operator refund of a purchase with an open dispute is
-- refused - the merchant settles the contest by accepting it - and a dispute's own refund is its OPEN dispute's, of exactly
-- its claim. Fires after bnpl_refund_is_born_once (names in order), which has taken B5 and B4.
CREATE OR REPLACE FUNCTION bnpl.bnpl_refund_is_not_beside_an_open_dispute()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d
                WHERE d.purchase_id = NEW.purchase_id AND d.status IN ('OPEN', 'MERCHANT_RESPONDED')
                  AND d.id IS DISTINCT FROM NEW.dispute_id) THEN
        RAISE EXCEPTION 'a BNPL purchase with an open dispute takes no refund but its dispute''s own (P12-TSK-023, ADR-0106 decision 7, INV-BNPL-08)';
    END IF;
    IF NEW.dispute_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_dispute d JOIN bnpl.bnpl_dispute_claim c ON c.dispute_id = d.id
             WHERE d.id = NEW.dispute_id AND d.purchase_id = NEW.purchase_id
               AND d.status IN ('OPEN', 'MERCHANT_RESPONDED') AND c.amount_minor = NEW.amount_minor) THEN
        RAISE EXCEPTION 'a dispute''s refund refunds exactly its open dispute''s claim, on its purchase (P12-TSK-023, INV-BNPL-08)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_refund_is_not_beside_an_open_dispute
    BEFORE INSERT ON bnpl.bnpl_refund
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_refund_is_not_beside_an_open_dispute();

-- ---------------------------------------------------------------------------------------------
-- The dispute at commit: its claim, a hold within it, its refund of exactly D.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_is_whole()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    dispute bnpl.bnpl_dispute%ROWTYPE;
    claim bnpl.bnpl_dispute_claim%ROWTYPE;
    held bigint;
BEGIN
    SELECT * INTO dispute FROM bnpl.bnpl_dispute d WHERE d.id = NEW.id;
    SELECT * INTO claim FROM bnpl.bnpl_dispute_claim c WHERE c.dispute_id = NEW.id;
    IF claim.dispute_id IS NULL THEN
        RAISE EXCEPTION 'a BNPL dispute commits beside its claim (P12-TSK-023)';
    END IF;
    SELECT coalesce(sum(h.held_principal_minor), 0) INTO held FROM bnpl.bnpl_dispute_hold h WHERE h.dispute_id = NEW.id;
    IF held > claim.amount_minor THEN
        RAISE EXCEPTION 'a BNPL dispute holds at most the principal it disputes (P12-TSK-023, ADR-0106 decision 3)';
    END IF;
    IF dispute.status = 'RESOLVED_REFUND' AND NOT EXISTS (
            SELECT 1 FROM bnpl.bnpl_refund r WHERE r.id = dispute.refund_id AND r.dispute_id = dispute.id
                                               AND r.door = 'DISPUTE' AND r.amount_minor = claim.amount_minor) THEN
        RAISE EXCEPTION 'a BNPL dispute resolved as a refund commits beside its refund of exactly its claim (P12-TSK-023, INV-BNPL-08)';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER bnpl_dispute_is_whole
    AFTER INSERT OR UPDATE ON bnpl.bnpl_dispute
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_dispute_is_whole();

-- ---------------------------------------------------------------------------------------------
-- The hold's derivations - one rule, BnplDisputeHold's twin.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.dispute_holds(obligation uuid, due date, on_date date)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM bnpl.bnpl_dispute_hold h JOIN bnpl.bnpl_dispute d ON d.id = h.dispute_id
                    WHERE h.obligation_id = obligation AND h.due_date = due AND d.opened_business_date <= on_date
                      AND (d.resolved_business_date IS NULL OR d.resolved_business_date > on_date))
$$;

COMMENT ON FUNCTION bnpl.dispute_holds(uuid, date, date) IS
    'Whether an open BNPL dispute holds an obligation''s instalment of a due date on a date (P12-TSK-023; ADR-0106 decision 3): a dispute holding the due date opened on or before the date and not resolved by it. BnplDisputeHold.heldOn is the same rule.';

CREATE OR REPLACE FUNCTION bnpl.dispute_clock_start(obligation uuid, due date, on_date date)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT GREATEST(due, coalesce((SELECT max(d.resolved_business_date)
                                     FROM bnpl.bnpl_dispute_hold h JOIN bnpl.bnpl_dispute d ON d.id = h.dispute_id
                                    WHERE h.obligation_id = obligation AND h.due_date = due
                                      AND d.resolved_business_date <= on_date), due))
$$;

COMMENT ON FUNCTION bnpl.dispute_clock_start(uuid, date, date) IS
    'Where an obligation''s instalment''s days-past-due and late-fee day count from on a date (P12-TSK-023; ADR-0106 decision 3): the later of its due date and the business date of the latest lift, by that date, of a dispute that held it - its due date when none ever did. BnplDisputeHold.clockFrom is the same rule.';

-- ---------------------------------------------------------------------------------------------
-- Days past due, replaced: V013's body, the hold beside it.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.days_past_due(obligation uuid, on_date date)
    RETURNS integer
    LANGUAGE sql
    STABLE
AS $$
    SELECT coalesce(on_date - min(bnpl.dispute_clock_start(obligation, b.value_date, on_date)), 0)
      FROM bnpl.bnpl_billing b
     WHERE b.obligation_id = obligation
       AND bnpl.dispute_clock_start(obligation, b.value_date, on_date) < on_date
       AND NOT bnpl.dispute_holds(obligation, b.value_date, on_date)
       AND (SELECT sum(bnpl.billing_component_billed(b.id, c.component) - bnpl.billing_component_paid(b.id, c.component))
              FROM (VALUES ('FEES'), ('INTEREST'), ('PRINCIPAL')) AS c (component)) > 0
$$;

COMMENT ON FUNCTION bnpl.days_past_due(uuid, date) IS
    'An obligation''s days past due on a date (P12-TSK-020; the dispute hold P12-TSK-023): the date less the oldest clock start (bnpl.dispute_clock_start - the due date when no dispute ever held it) of a billed instalment, not held on the date (bnpl.dispute_holds), still owing anything (fees, interest or principal) at the end of it; 0 when none. A difference of dates, never a counter. BnplDelinquencyState.derive is the same rule.';

-- ---------------------------------------------------------------------------------------------
-- No late fee on a held instalment; its day counted from its clock start - for every writer.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_fee_assessment_respects_the_dispute_hold()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    billing bnpl.bnpl_billing%ROWTYPE;
    late_day integer;
BEGIN
    SELECT b.* INTO billing FROM bnpl.bnpl_billing b
     WHERE b.instalment_id = NEW.instalment_id AND b.obligation_id = NEW.obligation_id;
    SELECT t.late_fee_day INTO late_day FROM bnpl.bnpl_terms t
      JOIN bnpl.bnpl_agreement a ON a.terms_version_id = t.terms_version_id
     WHERE a.obligation_id = NEW.obligation_id AND a.version = NEW.agreement_version;
    IF bnpl.dispute_holds(NEW.obligation_id, billing.value_date, NEW.business_date)
            OR NEW.business_date - bnpl.dispute_clock_start(NEW.obligation_id, billing.value_date, NEW.business_date)
               < late_day THEN
        RAISE EXCEPTION 'a BNPL late fee is never assessed on a held instalment, its day counted from the hold''s lift (P12-TSK-023, ADR-0106 decision 3)';
    END IF;
    RETURN NEW;
END;
$$;

-- Fires after bnpl_fee_assessment_is_born_once (names in order), which has taken B4 and judged the instalment.
CREATE TRIGGER bnpl_fee_assessment_respects_the_dispute_hold
    BEFORE INSERT ON bnpl.bnpl_fee_assessment
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_fee_assessment_respects_the_dispute_hold();

-- ---------------------------------------------------------------------------------------------
-- No auto-collection attempt on a held instalment - for every writer.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.bnpl_collection_attempt_respects_the_dispute_hold()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    due date;
BEGIN
    SELECT i.due_date INTO due FROM bnpl.bnpl_instalment i WHERE i.id = NEW.instalment_id;
    IF bnpl.dispute_holds(NEW.obligation_id, due, NEW.attempt_date) THEN
        RAISE EXCEPTION 'a held BNPL instalment is never collected automatically (P12-TSK-023, ADR-0106 decision 3)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_collection_attempt_respects_the_dispute_hold
    BEFORE INSERT ON bnpl.bnpl_collection_attempt
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_collection_attempt_respects_the_dispute_hold();

-- ---------------------------------------------------------------------------------------------
-- The grants: INSERT-only facts; the machine rows' status, permit and decision columns alone.
-- ---------------------------------------------------------------------------------------------
GRANT SELECT, INSERT ON bnpl.bnpl_dispute TO finapp_app;
GRANT UPDATE (status, next_sweep_at, merchant_statement, merchant_responded_at, resolved_by, resolution_reason, resolved_at,
              resolved_business_date, refund_id, proposal_id) ON bnpl.bnpl_dispute TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_dispute_claim TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_dispute_hold TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_dispute_event TO finapp_app;
GRANT SELECT, INSERT ON bnpl.bnpl_dispute_proposal TO finapp_app;
GRANT UPDATE (status, decided_by, decision_reason, decided_at) ON bnpl.bnpl_dispute_proposal TO finapp_app;
