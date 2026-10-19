-- A merchant API key's scopes (P12-TSK-021; ADR-0105 decision 9, ADR-0052's issuance door extended; INV-MER-01).
--
-- The first scoped merchant act is the BNPL refund (POST /v1/merchant/bnpl-purchases/{id}/refunds): a key may refund a
-- BNPL purchase only when it was ISSUED with the BNPL_REFUND scope. A scope is an insert-time fact, like the hash: chosen by
-- the operator who issued the key, frozen thereafter for every role - to change what a key may do, issue another and
-- revoke this one. Keys issued before this migration carry none and FAIL CLOSED: the column's default is the empty set,
-- so no existing credential gains a money-moving power by a schema change.
--
-- THE LIST HAS ONE DEFINITION: MerchantApiKeyScope.sqlValueList() - MerchantApiKeyMigrationTest holds this file to it.

ALTER TABLE merchant.merchant_api_key
    ADD COLUMN scopes text[] NOT NULL DEFAULT '{}'::text[];

ALTER TABLE merchant.merchant_api_key
    ADD CONSTRAINT merchant_api_key_scopes_are_known
        CHECK (scopes <@ ARRAY['BNPL_REFUND']::text[] AND array_position(scopes, NULL) IS NULL);

COMMENT ON COLUMN merchant.merchant_api_key.scopes IS
    'The acts beyond the merchant''s own reads and payouts this key may perform (P12-TSK-021): BNPL_REFUND - refund a BNPL purchase made at the merchant (ADR-0105 decision 9). Chosen at issuance, frozen for every role; a key issued before P12-TSK-021 carries none (fails closed).';

-- The frozen columns now include the scopes: V003's function verbatim but for that one column.
CREATE OR REPLACE FUNCTION merchant.merchant_api_key_permits_only_machine_edges() RETURNS trigger
LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
            OR NEW.secret_hash IS DISTINCT FROM OLD.secret_hash
            OR NEW.algorithm IS DISTINCT FROM OLD.algorithm
            OR NEW.issued_at IS DISTINCT FROM OLD.issued_at
            OR NEW.issued_by IS DISTINCT FROM OLD.issued_by
            OR NEW.scopes IS DISTINCT FROM OLD.scopes THEN
        RAISE EXCEPTION 'a merchant api key''s identity, secret and scopes are frozen: only status and revoked_at ever change (INV-IDN-01, P12-TSK-021)';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT (OLD.status = 'ACTIVE' AND NEW.status IN ('REVOKED')) THEN
        RAISE EXCEPTION 'a merchant api key moves only along the machine''s one edge: ACTIVE -> {REVOKED}, and REVOKED is terminal (INV-LIFE-02/-04)';
    END IF;
    RETURN NEW;
END;
$$;
