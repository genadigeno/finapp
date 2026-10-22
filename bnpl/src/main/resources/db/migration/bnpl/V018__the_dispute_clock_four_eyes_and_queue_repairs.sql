-- The dispute repairs of the Phase 12 -> 13 transition gate (repair R1; FINDINGS A5-F2, A5-F7, A7-2; ADR-0106 decisions
-- 3 and 5 as amended; BNPL_LIFECYCLES.md section 4.10; INV-LND-12 by analogy, INV-AUD-04, A13).
--
-- bnpl V018 is R1's - the gate's repair board reservation (R2 takes V019 if it needs one, R3 V020; Flyway runs with
-- out-of-order off, so this merges first). Nothing here writes money, adds a column or touches a row.
--
-- 1. A customer's withdrawal no longer restarts the clock (A5-F2). V015's bnpl.dispute_clock_start counted the business date
--    of ANY lift as the instalment's new clock start, so a customer who opened a dispute, withdrew it and opened another -
--    each withdrawal restarting days past due and the late-fee day - could keep an unpaid instalment from ever reaching
--    the default threshold. The safe default, built here: a WITHDRAWN dispute's lift counts for nothing - DPD and the
--    fee day are judged from the due date as if the instalment had never been held. A lift by a refund or an upheld
--    outcome keeps V015's rule (a good-faith contest the platform decided never makes the customer delinquent
--    retroactively). The hold itself (bnpl.dispute_holds) is unchanged: while a dispute is open its instalments take no
--    collection, no DPD and no fee.
--
-- 1b. A withdrawal starts a cooldown (A5-F2, the gate's follow-up): a customer who withdrew and re-opened at once could keep
--    an instalment held for ever. After its customer withdraws a dispute, the purchase takes no new dispute for
--    bnpl.dispute_withdrawal_cooldown() (30 days, BnplDisputes.WITHDRAWAL_COOLDOWN - the owner's to change, or later a
--    terms field) from the withdrawal's own stamp (resolved_at), judged at the opening's stamp - after the edge trigger
--    has re-taken B5, under which every withdrawal moved. A dispute a merchant or an operator resolved starts none.
--
-- 2. The four-eyes threshold is judged on the purchase's cumulative refunds by dispute (A5-F7). V015 compared each
--    dispute's own claim with the pinned threshold, so a large contest split into sequential disputes each at or below
--    it was resolved by one operator each time. Judged now against bnpl.dispute_four_eyes_amount: the dispute's claim
--    plus every refund an EARLIER dispute of the same purchase made (door DISPUTE, another dispute) - read under the
--    purchase's lock B5, which every refund and every dispute act takes first, and which the edge and the proposal
--    triggers below are reached under (T-dsp holds B5 before B6). Both triggers are replaced: the edge's (V015's body,
--    the comparison alone changed) and the proposal's (likewise).
--
-- 3. The contested queue (A7-2): an index for the operator's queue read and the contested-age gauge - the
--    MERCHANT_RESPONDED disputes by the instant the merchant answered.
--
-- No bnpl trigger reads a ledger table. No cross-schema foreign key (ADR-0029).

-- ---------------------------------------------------------------------------------------------
-- 1. The clock start, replaced: V015's body, a WITHDRAWN dispute's lift excluded.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.dispute_clock_start(obligation uuid, due date, on_date date)
    RETURNS date
    LANGUAGE sql
    STABLE
AS $$
    SELECT GREATEST(due, coalesce((SELECT max(d.resolved_business_date)
                                     FROM bnpl.bnpl_dispute_hold h JOIN bnpl.bnpl_dispute d ON d.id = h.dispute_id
                                    WHERE h.obligation_id = obligation AND h.due_date = due
                                      AND d.status <> 'WITHDRAWN'
                                      AND d.resolved_business_date <= on_date), due))
$$;

COMMENT ON FUNCTION bnpl.dispute_clock_start(uuid, date, date) IS
    'Where an obligation''s instalment''s days-past-due and late-fee day count from on a date (P12-TSK-023; ADR-0106 decision 3; bnpl V018, the Phase 12 -> 13 gate''s A5-F2): the later of its due date and the business date of the latest lift, by that date, of a dispute that held it and was RESOLVED - a refund or upheld; a customer''s withdrawal counts for nothing, so its due date when no resolved dispute ever held it. BnplDisputeHold.clockFrom is the same rule.';

-- ---------------------------------------------------------------------------------------------
-- 1b. The withdrawal's cooldown, for every writer.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.dispute_withdrawal_cooldown()
    RETURNS interval
    LANGUAGE sql
    IMMUTABLE
AS $$
    SELECT interval '30 days'
$$;

COMMENT ON FUNCTION bnpl.dispute_withdrawal_cooldown() IS
    'How long a BNPL purchase takes no new dispute after its customer withdrew one (bnpl V018, the Phase 12 -> 13 gate''s A5-F2): 30 days, the owner''s to change. BnplDisputes.WITHDRAWAL_COOLDOWN is the same figure.';

CREATE OR REPLACE FUNCTION bnpl.bnpl_dispute_respects_the_withdrawal_cooldown()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Fires after bnpl_dispute_permits_only_machine_edges (names in order), which has re-taken B5 and stamped opened_at.
    IF EXISTS (SELECT 1 FROM bnpl.bnpl_dispute d
                WHERE d.purchase_id = NEW.purchase_id AND d.status = 'WITHDRAWN'
                  AND NEW.opened_at < d.resolved_at + bnpl.dispute_withdrawal_cooldown()) THEN
        RAISE EXCEPTION 'a BNPL purchase whose dispute its customer withdrew is not disputed again within the cooldown (bnpl V018, the Phase 12 -> 13 gate''s A5-F2)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER bnpl_dispute_respects_the_withdrawal_cooldown
    BEFORE INSERT ON bnpl.bnpl_dispute
    FOR EACH ROW
    EXECUTE FUNCTION bnpl.bnpl_dispute_respects_the_withdrawal_cooldown();

-- ---------------------------------------------------------------------------------------------
-- 2. The amount the four-eyes threshold is judged against: the dispute's claim and every earlier dispute's refund.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION bnpl.dispute_four_eyes_amount(dispute uuid)
    RETURNS bigint
    LANGUAGE sql
    STABLE
AS $$
    SELECT c.amount_minor + coalesce((SELECT sum(r.amount_minor)
                                        FROM bnpl.bnpl_refund r
                                       WHERE r.purchase_id = d.purchase_id AND r.door = 'DISPUTE'
                                         AND r.dispute_id IS DISTINCT FROM d.id), 0)
      FROM bnpl.bnpl_dispute d JOIN bnpl.bnpl_dispute_claim c ON c.dispute_id = d.id
     WHERE d.id = dispute
$$;

COMMENT ON FUNCTION bnpl.dispute_four_eyes_amount(uuid) IS
    'The amount a BNPL dispute''s four-eyes threshold is judged against (bnpl V018, the Phase 12 -> 13 gate''s A5-F7; ADR-0106 decision 5 as amended): its claim plus the refunds every other dispute of its purchase already made (door DISPUTE) - the purchase''s cumulative refunded-by-dispute amount, this one included - so a contest split into sequential disputes is judged as one. Read under the purchase''s lock (B5). BnplDisputes.fourEyesAmount is the same rule.';

-- The edge trigger, replaced: V015's body verbatim but for the four-eyes comparison (`disputed` is now the cumulative).
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
    -- V018 (A5-F7): the cumulative refunded-by-dispute amount of the purchase, this dispute's claim included.
    disputed := bnpl.dispute_four_eyes_amount(OLD.id);
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
                RAISE EXCEPTION 'a BNPL dispute above its four-eyes threshold - its purchase''s refunds by dispute and its own claim - is resolved by a proposal another operator approved (P12-TSK-023, bnpl V018, INV-AUD-04)';
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

-- The proposal trigger, replaced: V015's body verbatim but for the threshold's amount (the cumulative).
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
        -- V018 (A5-F7): the cumulative refunded-by-dispute amount of the purchase, this dispute's claim included.
        disputed := bnpl.dispute_four_eyes_amount(NEW.dispute_id);
        SELECT t.dispute_four_eyes_threshold_minor INTO threshold FROM bnpl.bnpl_terms t
         WHERE t.terms_version_id = dispute.terms_version_id;
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR dispute.id IS NULL
                OR dispute.status <> 'MERCHANT_RESPONDED' OR disputed <= threshold THEN
            RAISE EXCEPTION 'a BNPL dispute proposal is born PROPOSED, for a dispute the merchant answered whose amount - its purchase''s refunds by dispute and its own claim - is above its four-eyes threshold (P12-TSK-023, bnpl V018)';
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

-- ---------------------------------------------------------------------------------------------
-- 3. The contested queue: the answered disputes by the instant the merchant answered (A7-2).
-- ---------------------------------------------------------------------------------------------
CREATE INDEX bnpl_dispute_contested ON bnpl.bnpl_dispute (merchant_responded_at, id) WHERE status = 'MERCHANT_RESPONDED';
