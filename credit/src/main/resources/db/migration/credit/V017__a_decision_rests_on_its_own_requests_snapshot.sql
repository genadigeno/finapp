-- The Phase 10 to 11 transition: a decision, its assessment and its evaluation rest on their OWN request's snapshot,
-- sealed to it (INV-CRD-06, INV-CRD-07, INV-HIST-04; the transition audit's finding "a decision's link to its own
-- request's snapshot is not enforced").
--
-- V007, V009 and V011 tied each row to its input by a plain foreign key on the input's id alone, and copied the input's
-- SHA-256 and pinned versions beside it unchecked: a writer could record request A's decision on request B's snapshot,
-- or an assessment whose hash or pins disagree with the snapshot it names, and every constraint held. The domain never
-- did; nothing beneath it refused it, and DecisionReplayer compared the seal but not whose seal it was.
--
-- THE CHAIN, BY COMPOSITE FOREIGN KEY: (snapshot_id, decision_request_id) -> decision_snapshot (id, decision_request_id)
-- for the assessment and the decision; (assessment_id, decision_request_id) -> credit_assessment for the evaluation;
-- (basis_evaluation_id, decision_request_id) -> policy_evaluation for the review case (V013's birth trigger judged it;
-- now the key holds it for every writer, its trigger disabled or not). Each parent gains the UNIQUE (id,
-- decision_request_id) a composite key needs - implied by its primary key, so it constrains nothing new there.
--
-- THE SEAL, BY TRIGGER at birth (every writer, the owner included - a trigger disabled is a migration, reviewed): an
-- assessment's hash and pinned policy, model and engine versions are its snapshot's; an evaluation's policy and engine
-- are its assessment's; a decision's hash and pins are its snapshot's AND (once pinned) its request's, and the
-- snapshot is the request's LATEST (the deciding transaction decides from the latest - a successor when the exposure
-- moved - so a decision on a superseded snapshot is no decision the platform makes).

ALTER TABLE credit.decision_snapshot
    ADD CONSTRAINT decision_snapshot_id_with_its_request UNIQUE (id, decision_request_id);

ALTER TABLE credit.credit_assessment
    ADD CONSTRAINT credit_assessment_id_with_its_request UNIQUE (id, decision_request_id);

ALTER TABLE credit.policy_evaluation
    ADD CONSTRAINT policy_evaluation_id_with_its_request UNIQUE (id, decision_request_id);

ALTER TABLE credit.credit_assessment
    ADD CONSTRAINT credit_assessment_snapshot_of_its_request_fk FOREIGN KEY (snapshot_id, decision_request_id)
        REFERENCES credit.decision_snapshot (id, decision_request_id);

ALTER TABLE credit.policy_evaluation
    ADD CONSTRAINT policy_evaluation_assessment_of_its_request_fk FOREIGN KEY (assessment_id, decision_request_id)
        REFERENCES credit.credit_assessment (id, decision_request_id);

ALTER TABLE credit.credit_decision
    ADD CONSTRAINT credit_decision_snapshot_of_its_request_fk FOREIGN KEY (snapshot_id, decision_request_id)
        REFERENCES credit.decision_snapshot (id, decision_request_id);

ALTER TABLE credit.underwriting_case
    ADD CONSTRAINT underwriting_case_basis_of_its_request_fk FOREIGN KEY (basis_evaluation_id, decision_request_id)
        REFERENCES credit.policy_evaluation (id, decision_request_id);

-- ---------------------------------------------------------------------------------------------
-- The assessment is sealed to its snapshot.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION credit.credit_assessment_is_sealed_to_its_snapshot()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM credit.decision_snapshot s
             WHERE s.id = NEW.snapshot_id
               AND s.decision_request_id = NEW.decision_request_id
               AND s.content_sha256 = NEW.snapshot_sha256
               AND s.policy_version_id = NEW.policy_version_id
               AND s.model_version_id = NEW.model_version_id
               AND s.engine_version = NEW.engine_version) THEN
        RAISE EXCEPTION 'a credit assessment carries its own request''s snapshot''s hash and pinned versions (INV-CRD-06)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_assessment_is_sealed_to_its_snapshot
    BEFORE INSERT ON credit.credit_assessment
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_assessment_is_sealed_to_its_snapshot();

-- ---------------------------------------------------------------------------------------------
-- The evaluation is sealed to its assessment.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION credit.policy_evaluation_is_sealed_to_its_assessment()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
            SELECT 1 FROM credit.credit_assessment a
             WHERE a.id = NEW.assessment_id
               AND a.decision_request_id = NEW.decision_request_id
               AND a.policy_version_id = NEW.policy_version_id
               AND a.engine_version = NEW.engine_version) THEN
        RAISE EXCEPTION 'a policy evaluation carries its own request''s assessment''s pinned policy and engine (INV-CRD-06)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER policy_evaluation_is_sealed_to_its_assessment
    BEFORE INSERT ON credit.policy_evaluation
    FOR EACH ROW
    EXECUTE FUNCTION credit.policy_evaluation_is_sealed_to_its_assessment();

-- ---------------------------------------------------------------------------------------------
-- The decision is sealed to its request's latest snapshot and its request's pinned versions.
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION credit.credit_decision_is_sealed_to_its_snapshot()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    sealed_sequence integer;
BEGIN
    SELECT s.sequence INTO sealed_sequence
      FROM credit.decision_snapshot s
      JOIN credit.decision_request r ON r.id = s.decision_request_id
     WHERE s.id = NEW.snapshot_id
       AND s.decision_request_id = NEW.decision_request_id
       AND s.content_sha256 = NEW.snapshot_sha256
       AND s.policy_version_id = NEW.policy_version_id
       AND s.model_version_id = NEW.model_version_id
       AND s.engine_version = NEW.engine_version
       -- a request is pinned at SUBMITTED -> COLLECTING (V010); once pinned, the decision carries its pins
       AND (r.pinned_policy_version_id IS NULL OR (r.pinned_policy_version_id = NEW.policy_version_id
            AND r.pinned_model_version_id = NEW.model_version_id
            AND r.pinned_engine_version = NEW.engine_version));
    IF sealed_sequence IS NULL THEN
        RAISE EXCEPTION 'a credit decision carries its own request''s snapshot''s hash and the request''s pinned versions (INV-CRD-06)';
    END IF;
    IF EXISTS (
            SELECT 1 FROM credit.decision_snapshot later
             WHERE later.decision_request_id = NEW.decision_request_id AND later.sequence > sealed_sequence) THEN
        RAISE EXCEPTION 'a credit decision rests on its request''s latest snapshot (INV-CRD-06, PHASE_10_PLAN.md section 12.7)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_decision_is_sealed_to_its_snapshot
    BEFORE INSERT ON credit.credit_decision
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_decision_is_sealed_to_its_snapshot();
