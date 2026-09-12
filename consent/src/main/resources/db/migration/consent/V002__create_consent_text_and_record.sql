-- Consent: the versioned texts and the append-only history (P2-TSK-017, ADR-0037).
--
-- THE HISTORY IS THE STORE. A consent record is an immutable fact - a grant or a withdrawal,
--   purpose-scoped, bound to the version of the text it was given against - and the current
--   basis is DERIVED from the latest fact, never stored (the P1-TSK-013 shape: a stored
--   status would be a second answer free to disagree with the history that produced it).
--
-- APPEND-ONLY AT DB-PRIVILEGE (INV-CNS-02): the application role holds SELECT and INSERT on
--   the record table and nothing else - the audit_record model, so the privilege IS the
--   immutability and no freeze trigger is needed. "Was there a basis on the day it happened?"
--   is answerable only from history, and an updated row has destroyed the evidence.
--
-- TEXTS ARE UNWRITABLE BY THE APPLICATION ENTIRELY: a consent text is a reviewed, versioned
--   platform artefact, and a forward-only migration (ADR-0011) is exactly the reviewed,
--   immutable channel such an artefact arrives through - a new version is a new row in a new
--   migration, never an edit. The application role gets SELECT only, which is text
--   immutability at the strongest available rank.
--
-- THE PURPOSE AND ACTION CHECKS ARE GENERATED - ConsentPurpose.sqlValueList() and
--   ConsentAction.sqlValueList(); ConsentMigrationTest fails the build if this file and the
--   enums disagree (the P0-TSK-022 pattern).

CREATE TABLE consent.consent_text (
    -- ConsentPurpose.sqlValueList(); a free-string purpose is a vocabulary nobody controls.
    purpose            text        NOT NULL
        CONSTRAINT consent_text_purpose_is_known
            CHECK (purpose IN ('KYC_PROCESSING', 'SCREENING')),

    -- Versions count from 1 per purpose; the pair is the identity a record pins.
    version            integer     NOT NULL
        CONSTRAINT consent_text_version_is_positive
            CHECK (version >= 1),

    -- What the person actually agreed to. The artefact itself, retained verbatim - a grant
    -- referencing a version whose words nobody kept proves nothing (INV-CNS-04).
    body               text        NOT NULL
        CONSTRAINT consent_text_body_is_not_blank
            CHECK (length(trim(body)) > 0),

    -- Whether grants against EARLIER versions lapse when this version exists: a recorded
    -- property of the version, never a guess (INV-CNS-04). The derivation reads it.
    requires_reconsent boolean     NOT NULL,

    -- Application-supplied where an application writes; here the seed states its date
    -- explicitly. Never DEFAULT now().
    published_at       timestamptz NOT NULL,

    CONSTRAINT consent_text_pkey PRIMARY KEY (purpose, version)
);

COMMENT ON TABLE consent.consent_text IS
    'Versioned, immutable consent texts (INV-CNS-04). Written only by migrations - the '
    'application role holds SELECT alone - because the text a person agreed to is evidence, '
    'and a new version is a new row in a new migration, never an edit (ADR-0011).';

GRANT SELECT ON consent.consent_text TO finapp_app;

-- The first version of each purpose's text, jurisdiction-neutral (PRODUCT_VISION.md). A
-- later regime's wording is a NEW version in a later migration, with requires_reconsent
-- deciding - as a property of that version - whether existing grants lapse. Version 1 states
-- requires_reconsent = false because there is no earlier version for it to lapse.
INSERT INTO consent.consent_text (purpose, version, body, requires_reconsent, published_at)
VALUES
    ('KYC_PROCESSING', 1,
     'I consent to the processing of my identity data and submitted documents for the '
     'purpose of verifying my identity, as required to open and operate my account.',
     false, TIMESTAMPTZ '2026-09-12 00:00:00+00'),
    ('SCREENING', 1,
     'I consent to my identity data being screened against sanctions, politically exposed '
     'person and adverse media sources for the purpose of regulatory compliance.',
     false, TIMESTAMPTZ '2026-09-12 00:00:00+00');

-- The history: one row per fact, ever.
CREATE TABLE consent.consent_record (
    -- UUIDv7, minted by the application (ADR-0013).
    id            uuid        PRIMARY KEY,

    -- The person, as a Party: a value, never a cross-schema FK (ADR-0029, the
    -- kyc_case.customer_id pattern).
    party_id      uuid        NOT NULL,

    -- ConsentPurpose.sqlValueList(); ConsentMigrationTest reconciles.
    purpose       text        NOT NULL
        CONSTRAINT consent_record_purpose_is_known
            CHECK (purpose IN ('KYC_PROCESSING', 'SCREENING')),

    -- ConsentAction.sqlValueList(); ConsentMigrationTest reconciles.
    action        text        NOT NULL
        CONSTRAINT consent_record_action_is_known
            CHECK (action IN ('GRANT', 'WITHDRAWAL')),

    -- The version of the text this fact was recorded against - NOT NULL on BOTH kinds
    -- (INV-CNS-04's constraint is unconditional): a grant pins what was agreed to, a
    -- withdrawal pins what was current when the person withdrew. The COMPOSITE FK is the
    -- V008 lesson: the pinned version must be a version OF THIS PURPOSE - a record pinning
    -- another purpose's text would satisfy two single-column FKs while referencing an
    -- artefact the person was never shown.
    text_version  integer     NOT NULL,

    -- When the fact was recorded, from one injected Clock, never DEFAULT now(). This column
    -- is INFORMATIONAL: the order of facts is seq below, because a timestamp written by N
    -- instances' clocks cannot totally order concurrent facts (P0-TST-009's lesson).
    recorded_at   timestamptz NOT NULL,

    -- THE ORDER OF THE HISTORY, assigned by the server. GENERATED ALWAYS refuses
    -- client-supplied values, so no instance's clock or counter can ever decide which of two
    -- racing facts is later - the derivation takes the highest seq, and every reader agrees.
    seq           bigint      GENERATED ALWAYS AS IDENTITY,

    CONSTRAINT consent_record_seq_is_the_total_order UNIQUE (seq),

    CONSTRAINT consent_record_pins_its_purposes_text
        FOREIGN KEY (purpose, text_version)
            REFERENCES consent.consent_text (purpose, version)
);

-- The derivation's path: the latest fact for (party, purpose).
CREATE INDEX consent_record_latest_first
    ON consent.consent_record (party_id, purpose, seq DESC);

COMMENT ON TABLE consent.consent_record IS
    'The append-only consent history (INV-CNS-02, ADR-0037): grants and withdrawals as '
    'immutable facts, each pinning the text version it was recorded against (INV-CNS-04). '
    'The current basis is derived from the highest seq - a server-assigned total order - and '
    'absence is indistinguishable from withdrawal to every caller (INV-CNS-01).';

-- SELECT and INSERT and NOTHING ELSE: the privilege is the immutability (INV-CNS-02).
GRANT SELECT, INSERT ON consent.consent_record TO finapp_app;
