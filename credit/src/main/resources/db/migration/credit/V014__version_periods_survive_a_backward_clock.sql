-- A credit version's effective period never contradicts the order of its versions, whatever the database clock does
-- (X-TSK-017; ADR-0086 section 5, ADR-0063 decision 2, X-TSK-013's permit form; INV-CRD-05).
--
-- V006 and V008 stamped the effective period with transaction_timestamp(): an activation's effective_from and its
-- predecessor's effective_to were both the activating transaction's start. The database clock is not monotonic - an
-- NTP or hypervisor correction steps it back (measured on the local Docker VM, 2026-10-09: stepped back about every
-- 30 s; ADR-0063 measured 1.7 s at a time) - so an activation whose transaction started on a clock still behind its
-- predecessor's effective_from stamped an effective_to before that start, and effective_coherent refused the
-- retirement (23514): a four-eyes activation lost to an opaque storage failure, for as long as the clock stood behind.
-- UnderwritingCaseDatabaseTest met it as an intermittent failure under load.
--
-- The stamp is now the later of the transaction's start and one microsecond after the predecessor's start - the form
-- of X-TSK-013's database-stamped permits, GREATEST(permit + 1 us, statement_timestamp()):
--
--     retirement:  effective_to   := GREATEST(transaction_timestamp(), OLD.effective_from + 1 microsecond)
--     activation:  effective_from := GREATEST(transaction_timestamp(), the scope's latest effective_to)
--
-- The domain retires the predecessor FIRST in the activating transaction (and the partial unique refuses any other
-- order), so the scope's latest effective_to IS that retirement's stamp, and the successor starts exactly where its
-- predecessor ends: the continuity trigger's equality is untouched, effective_to >= effective_from holds by
-- construction, and every activated version keeps a period of at least one microsecond, so the version active at
-- any instant stays answerable from the rows (INV-CRD-05). On a clock that is not behind, GREATEST is the
-- transaction's start and nothing changes. The CHECKs and both deferred triggers stay, verbatim, as the rank against
-- a writer that disables this trigger. The scope's writers are serialised by the activation's row locks (the proposal
-- FOR UPDATE, the active version FOR UPDATE), so the latest effective_to is never another transaction's.
--
-- Only the two effective-period assignments change; each function is otherwise V006's and V008's, verbatim.

CREATE OR REPLACE FUNCTION credit.scorecard_model_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.effective_from IS NOT NULL
                OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a scorecard model version is born PROPOSED and undecided: no model is activated by insert (P10-TSK-011)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a scorecard model version is never deleted (P10-TSK-011)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.family IS DISTINCT FROM OLD.family
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.base_points IS DISTINCT FROM OLD.base_points
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a scorecard model version''s identity and content are frozen (P10-TSK-011, INV-CRD-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a scorecard model version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P10-TSK-011)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := transaction_timestamp();
        -- X-TSK-017: never before the family's latest end - its predecessor's, retired first in this transaction.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM credit.scorecard_model_version v WHERE v.family = NEW.family)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P10-TSK-011)';
        END IF;
        -- X-TSK-017: never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION credit.credit_policy_permits_only_machine_edges()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PROPOSED' OR NEW.decided_by IS NOT NULL OR NEW.effective_from IS NOT NULL
                OR NEW.effective_to IS NOT NULL THEN
            RAISE EXCEPTION 'a credit policy version is born PROPOSED and undecided: no policy is activated by insert (P10-TSK-012)';
        END IF;
        NEW.proposed_at := transaction_timestamp();
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a credit policy version is never deleted (P10-TSK-012)';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.product IS DISTINCT FROM OLD.product
            OR NEW.version IS DISTINCT FROM OLD.version
            OR NEW.currency IS DISTINCT FROM OLD.currency
            OR NEW.scale IS DISTINCT FROM OLD.scale
            OR NEW.assessment_rate_bps IS DISTINCT FROM OLD.assessment_rate_bps
            OR NEW.minimum_disposable_minor IS DISTINCT FROM OLD.minimum_disposable_minor
            OR NEW.minimum_payment_ratio_bps IS DISTINCT FROM OLD.minimum_payment_ratio_bps
            OR NEW.maximum_exposure_minor IS DISTINCT FROM OLD.maximum_exposure_minor
            OR NEW.max_data_age_bureau_seconds IS DISTINCT FROM OLD.max_data_age_bureau_seconds
            OR NEW.max_data_age_financial_data_seconds IS DISTINCT FROM OLD.max_data_age_financial_data_seconds
            OR NEW.unavailable_fallback IS DISTINCT FROM OLD.unavailable_fallback
            OR NEW.auto_approval_ceiling_minor IS DISTINCT FROM OLD.auto_approval_ceiling_minor
            OR NEW.proposed_by IS DISTINCT FROM OLD.proposed_by
            OR NEW.proposed_at IS DISTINCT FROM OLD.proposed_at
            OR NEW.proposal_reason IS DISTINCT FROM OLD.proposal_reason THEN
        RAISE EXCEPTION 'a credit policy version''s identity and parameters are frozen (P10-TSK-012, INV-CRD-05)';
    END IF;
    IF NOT ((OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')) THEN
        RAISE EXCEPTION 'a credit policy version moves PROPOSED -> ACTIVE | REJECTED, ACTIVE -> RETIRED; % -> % is not an edge (P10-TSK-012)', OLD.status, NEW.status;
    END IF;
    IF OLD.status = 'PROPOSED' THEN
        NEW.decided_at := transaction_timestamp();
        -- X-TSK-017: never before the product's latest end - its predecessor's, retired first in this transaction.
        NEW.effective_from := CASE WHEN NEW.status = 'ACTIVE' THEN GREATEST(transaction_timestamp(),
                (SELECT max(v.effective_to) FROM credit.credit_policy_version v WHERE v.product = NEW.product)) END;
        NEW.effective_to := NULL;
    ELSE
        IF NEW.decided_by IS DISTINCT FROM OLD.decided_by OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
                OR NEW.decision_reason IS DISTINCT FROM OLD.decision_reason
                OR NEW.effective_from IS DISTINCT FROM OLD.effective_from THEN
            RAISE EXCEPTION 'an activation''s decision is frozen; a retirement records only its end (P10-TSK-012)';
        END IF;
        -- X-TSK-017: never at or before its own start, whatever the clock says.
        NEW.effective_to := GREATEST(transaction_timestamp(), OLD.effective_from + interval '1 microsecond');
    END IF;
    RETURN NEW;
END;
$$;
