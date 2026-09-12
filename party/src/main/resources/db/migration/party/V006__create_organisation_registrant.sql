-- Who may act for an organisation (P2-TSK-016).
--
-- An ORGANISATION cannot log in: it holds no Identity, so "the organisation's acting person"
-- is a fact this table records rather than something a session can prove. Phase 2's deliberate
-- minimum (the backlog's own words): the REGISTERING person acts for the organisation, and
-- nobody else - delegated access, multiple representatives and registrant replacement are
-- Phase 6+'s, which is why the grants below admit no UPDATE and no DELETE: who registered an
-- organisation is history, and history is not edited (INV-HIST-01's shape applied to a
-- relationship).
--
-- ONE ORGANISATION PER REGISTRANT, EVER - the TOTAL unique index on registrant_party_id.
--   It is three things at once: the Phase 2 scope bound (a person acts for at most one
--   organisation, so /v1/me/kyb is unambiguous with no identifier in the request), the
--   concurrency arbiter (ten instances registering for one person produce one organisation;
--   the losers converge behind a savepoint, the one-open-case idiom), and the convergence key
--   (a lost-response retry finds the row this index protected). Deliberately total rather
--   than partial: a rejected organisation does NOT free the slot in Phase 2, and re-onboarding
--   an organisation is recorded as Phase 6's decision rather than hidden in a predicate
--   (the P2-TSK-005 lesson, taken the other way on purpose and said out loud).
--
-- registrant_party_id REFERENCES party.party: same-schema, so the FK costs no module coupling
--   (ADR-0029 forbids only CROSS-schema references). The kind bound - the registrant is a
--   PERSON - is the orchestration's: party.kind is not in any UNIQUE constraint here and
--   inventing one for a CHECK would be schema surgery for a rule the authenticated chain
--   already enforces (only a logged-in person, who is a PERSON party, can reach the endpoint).

CREATE TABLE party.organisation_registrant (
    customer_id uuid NOT NULL,
    registrant_party_id uuid NOT NULL,
    registered_at timestamptz NOT NULL,

    CONSTRAINT organisation_registrant_pkey PRIMARY KEY (customer_id),
    CONSTRAINT organisation_registrant_customer
        FOREIGN KEY (customer_id) REFERENCES party.customer (id),
    CONSTRAINT organisation_registrant_party
        FOREIGN KEY (registrant_party_id) REFERENCES party.party (id)
);

-- One organisation per registrant, ever (see the header for why this is total).
CREATE UNIQUE INDEX organisation_one_registration_per_registrant
    ON party.organisation_registrant (registrant_party_id);

COMMENT ON TABLE party.organisation_registrant IS
    'Who acts for an organisation (P2-TSK-016): the person who registered it, and in Phase 2 '
    'nobody else. Append-only; delegation is Phase 6+.';

-- Append-only to the application role: who acts for an organisation changes by a future
-- delegation capability writing new facts, never by editing this one.
GRANT SELECT, INSERT ON party.organisation_registrant TO finapp_app;
