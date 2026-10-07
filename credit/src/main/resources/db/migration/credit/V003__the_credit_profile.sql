-- The credit profile (P10-TSK-004; ADR-0087 section 5, PHASE_10_PLAN.md section 7, INV-CRD-09).
--
-- ONE ROW PER PARTY, AND NOTHING ELSE. The profile is the row every deciding transaction for a
-- party locks FIRST (the Phase 10 lock order, element (1), DISTRIBUTED_EXECUTION.md section 3):
-- two approvals racing for one party on any two instances serialise here, and the second re-reads
-- the reserved exposure the first has just committed - which is what keeps concurrent approvals
-- within the policy's exposure limit (INV-CRD-09). It holds NO figure, score or limit
-- (INV-CRD-04): a stored figure would be a second answer free to disagree with the records that
-- explain it. CreditProfileDatabaseTest#theProfileHasNoFigureColumn holds the column set.
--
-- BORN ONCE PER PARTY by `UNIQUE (party_id)`: ensure() is INSERT ... ON CONFLICT DO NOTHING, so
-- ten instances ensuring one party leave one row and return one id. The party is a reference,
-- never a cross-schema foreign key (ADR-0029): credit has no edge to `party`, and the party's
-- standing is judged by the decision request (P10-TSK-014), not here.
--
-- created_at IS THE DATABASE'S: stamped by the trigger from statement_timestamp() whatever a
-- writer supplies, so no instance's clock dates a profile.

CREATE TABLE credit.credit_profile (
    id          uuid        PRIMARY KEY,
    party_id    uuid        NOT NULL,
    created_at  timestamptz NOT NULL,

    CONSTRAINT credit_profile_one_per_party UNIQUE (party_id)
);

COMMENT ON TABLE credit.credit_profile IS
    'One row per party, holding no figure (INV-CRD-04): the lock target every deciding transaction for the party takes first, so concurrent decisions serialise on it (INV-CRD-09). Born once by UNIQUE (party_id); never updated, deleted or truncated by any role; created_at stamped by the database.';

CREATE OR REPLACE FUNCTION credit.credit_profile_is_born_once()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a credit profile is a lock target with nothing to change: it is never updated, deleted or truncated (P10-TSK-004)';
    END IF;
    NEW.created_at := statement_timestamp();
    RETURN NEW;
END;
$$;

CREATE TRIGGER credit_profile_is_born_once
    BEFORE INSERT OR UPDATE OR DELETE ON credit.credit_profile
    FOR EACH ROW
    EXECUTE FUNCTION credit.credit_profile_is_born_once();

-- TRUNCATE fires no row trigger; a statement trigger refuses it.
CREATE TRIGGER credit_profile_is_never_truncated
    BEFORE TRUNCATE ON credit.credit_profile
    FOR EACH STATEMENT
    EXECUTE FUNCTION credit.credit_profile_is_born_once();

-- THE GRANTS. Read and born, and the one column-level UPDATE that exists ONLY so a row lock is
-- takeable: PostgreSQL refuses SELECT ... FOR UPDATE (42501) without UPDATE privilege on at least
-- one column - the constraint merchant V004 records and designed around, because a fee schedule
-- needed no lock. The profile IS a lock, so the privilege is granted on `party_id` alone and the
-- trigger above refuses every actual UPDATE for every role: the row stays immutable at trigger
-- rank, and FOR UPDATE - which fires no UPDATE trigger - stays legal. No DELETE, no TRUNCATE.
GRANT SELECT, INSERT ON credit.credit_profile TO finapp_app;
GRANT UPDATE (party_id) ON credit.credit_profile TO finapp_app;
