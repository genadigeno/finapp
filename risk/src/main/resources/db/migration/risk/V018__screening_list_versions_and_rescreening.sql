-- Rescreening on screening-list updates (P13-TSK-022; PHASE_13_PLAN.md sections 7.1 T-list, 7.2, 7.5, 8, 9, 11, 12.10;
-- RISK_LIFECYCLES.md section 4.10; ROADMAP.md Refinement 3; OQ-8; INV-RSK-06, INV-RSK-02, INV-RSK-07, INV-RSK-08,
-- INV-AUD-04). The next free number at its integration: the entry's V012 went to P13-TSK-016 (V013-V016 to -017, -018,
-- -020, -021); built as V017 beside -023 and -025, renumbered V018 when -025's V017 (the collections case) merged first.
--
-- WHAT A LIST VERSION IS
--   An operator's fact: "version V of the sanctions (or PEP) list is in force". Risk holds no list content - kyc's
--   provider holds that (out of scope) - and no rule reads the list itself. Recorded by one person (RECORDED), activated
--   by a SECOND person (ACTIVE: screening_list_version_four_eyes, for every writer, not judged by the trigger, so the
--   CHECK is provable alone - INV-RSK-07, INV-AUD-04, PHASE_13_PLAN.md section 11 "under four eyes"), retired beside its
--   successor's activation in that transaction (RETIRED). One version per (list, label) - screening_list_version_once;
--   one in force per list - screening_list_version_one_active; a list's versions numbered in recording order without a
--   gap (ordinal, under the list's advisory lock, namespace 13) so "recorded before the one in force" is two integers.
--
-- THE RESCREENING IS ENQUEUED BY THE SWEEP, NOT BY THE ACTIVATION (the entry)
--   An activation writes one row whatever the population: it leaves the version's enqueue permit (enqueue_next_at) due.
--   RiskMonitoringSchedule's rescreening leg claims the version FOR UPDATE SKIP LOCKED, reads a page of ACTIVE parties
--   after the enqueue's cursor (party's store, through app's port), inserts one permit each ON CONFLICT DO NOTHING
--   (rescreening_permit_once), and advances the cursor - one batch, one transaction: a crash re-runs the batch from the
--   committed cursor into the same keys, and ten enqueuers take a version one batch at a time, so an activation enqueues
--   every ACTIVE party ONCE (INV-RSK-06). A retirement stops its version's enqueue; its permits run on.
--
-- A PERMIT - ONE PER (party, list version)
--   PENDING (born due at once) -> PENDING (the leg's claim: one attempt, the permit a lease ahead; or a deferral: kyc's
--   screening not yet current under the version, in flight, never decided, or kyc unavailable - re-permitted with its
--   cause) -> DONE (CLEARED | HIT: a screening kyc was ASKED after the version's activation - by the instances' clock
--   skew, kyc stamping its ask on an instance's clock - and has decided, recorded as the signal
--   SCREENING_CLEARED | SCREENING_HIT whose source_event_id IS the permit's id - rescreening_permit_signal_is_its_own;
--   SKIPPED: the party no longer ACTIVE) | FAILED (the bound reached, the cause kept; or UNCONCLUDED - a claim past the
--   bound with no conclusion behind it). Risk asks and kyc screens (INV-RSK-02): kyc's call is outside every risk
--   transaction, the conclusion conditional on PENDING and the claimed attempt.
--
-- THE SIGNAL GAINS TWO REFERENCES AND TWO KINDS
--   list_version_ref (the version the clearance was judged under) and screening_ref (kyc's screening, by id - never kyc's
--   row, its evidence or a name); SCREENING_CLEARED and SCREENING_HIT about the PARTY carry exactly those two, and every
--   kind's count is re-made over the seventeen attribute columns, so no older kind may carry them. The kinds are risk's
--   own - recorded by the leg, never consumed from an event. risk_signal_id_and_source (id, source_event_id) is the unique
--   the permit's composite foreign key needs. V003 and V011 are applied history and are never edited.
--
-- Persons are authenticated humans - CUSTOMER-typed as the platform's session establishes every operator, or EMPLOYEE -
-- never SYSTEM, SERVICE or MERCHANT (the meaning V012-V016 adopted). The stamps are the triggers' clock_timestamp(). No
-- money column. No DELETE for any role. The migration names no other schema.
--
-- DEPLOY-TIME LOCKS ON risk.risk_signal (DATA_MIGRATIONS.md section 4.4; the P13-TSK-022 review)
--   Section 2 holds risk_signal under ACCESS EXCLUSIVE for the migration's transaction: the two ADD COLUMNs are
--   catalogue-only (nullable, no default - no rewrite); ADD CONSTRAINT risk_signal_id_and_source builds a unique index
--   over every row; and the three re-made CHECKs validate every row. While it runs, the signal intake (T-sig, every
--   consumed event's handler) and every T-eval read of signals wait on the lock - nothing fails: the inbox retries a
--   delivery that times out, and the sweeps re-run their claims. The time is one index build plus three scans of the
--   table, linear in its rows. Deploy it when the signal intake is quiet, and with lock_timeout set so a long-running
--   reader makes the migration fail and retry rather than queue every writer behind it. The mechanism is kept (no
--   NOT VALID and VALIDATE split, no CONCURRENTLY build): the table is risk's young evidence, the unique index is what
--   the permit's composite foreign key needs, and a split would leave the CHECKs unproven for a window in which the new
--   kinds are already written. The runbook line is routed to P13-TSK-024.

-- ---------------------------------------------------------------------------------------------------------------------
-- 1. The list version.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.screening_list_version (
    id                    uuid        NOT NULL,
    list                  text        NOT NULL,
    version               text        NOT NULL,
    ordinal               integer     NOT NULL,
    status                text        NOT NULL,

    -- The two persons: the recorder and the activator.
    recorded_by           text        NOT NULL,
    recorded_by_type      text        NOT NULL,
    recorded_at           timestamptz NOT NULL,
    activated_by          text,
    activated_by_type     text,
    activated_at          timestamptz,
    retired_at            timestamptz,

    -- The activation's enqueue: its permit, its keyset cursor over party ids, its count, its completion.
    enqueue_next_at       timestamptz,
    enqueue_cursor        uuid,
    enqueued              bigint      NOT NULL DEFAULT 0,
    enqueue_completed_at  timestamptz,

    correlation_id        text        NOT NULL,

    CONSTRAINT screening_list_version_pk PRIMARY KEY (id),
    -- One version per (list, label): a second recording of a label converges on the first.
    CONSTRAINT screening_list_version_once UNIQUE (list, version),
    CONSTRAINT screening_list_version_ordinal_once UNIQUE (list, ordinal),

    -- The closed vocabularies ScreeningList and ScreeningListVersionStatus declare (ScreeningListMigrationTest).
    CONSTRAINT screening_list_version_list_is_known CHECK (list IN ('SANCTIONS', 'PEP')),
    CONSTRAINT screening_list_version_status_is_known CHECK (status IN ('RECORDED', 'ACTIVE', 'RETIRED')),
    -- A provider's label, never text: ScreeningListVersion.LABEL.
    CONSTRAINT screening_list_version_label_shaped CHECK (version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT screening_list_version_ordinal_positive CHECK (ordinal >= 1),

    -- Persons: authenticated humans, never the system.
    CONSTRAINT screening_list_version_by_persons CHECK (
        recorded_by_type IN ('CUSTOMER', 'EMPLOYEE')
        AND (activated_by_type IS NULL OR activated_by_type IN ('CUSTOMER', 'EMPLOYEE'))),

    -- FOUR EYES, for every writer: the activator is not the recorder. Not judged by the trigger - provable alone.
    CONSTRAINT screening_list_version_four_eyes CHECK (activated_by IS NULL OR activated_by <> recorded_by),

    -- The activator's slot whole, and present exactly past RECORDED; retired exactly when RETIRED.
    CONSTRAINT screening_list_version_activated_coherent CHECK (
        num_nonnulls(activated_by, activated_by_type, activated_at) IN (0, 3)
        AND (status <> 'RECORDED') = (activated_by IS NOT NULL)),
    CONSTRAINT screening_list_version_retired_coherent CHECK ((retired_at IS NOT NULL) = (status = 'RETIRED')),

    -- The enqueue: a permit only while in force; complete only with no permit; nothing of it before the activation.
    CONSTRAINT screening_list_version_enqueue_coherent CHECK (
        (enqueue_next_at IS NULL OR status = 'ACTIVE')
        AND (enqueue_completed_at IS NULL OR enqueue_next_at IS NULL)
        AND enqueued >= 0
        AND (status <> 'RECORDED' OR (enqueued = 0 AND enqueue_cursor IS NULL AND enqueue_next_at IS NULL
                                       AND enqueue_completed_at IS NULL))),

    CONSTRAINT screening_list_version_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- ONE VERSION IN FORCE PER LIST: the activation retires the predecessor first, under the list's lock.
CREATE UNIQUE INDEX screening_list_version_one_active ON risk.screening_list_version (list) WHERE status = 'ACTIVE';

-- The enqueue's claim: the versions whose census is incomplete, oldest permit first.
CREATE INDEX screening_list_version_enqueue_due ON risk.screening_list_version (enqueue_next_at, id)
    WHERE enqueue_next_at IS NOT NULL;

COMMENT ON TABLE risk.screening_list_version IS
    'A screening list version: the operator''s fact that version V of the sanctions or PEP list is in force (PHASE_13_PLAN.md section 12.10). RECORDED (a person, with a reason) -> ACTIVE (a SECOND person, MULTI_FACTOR; screening_list_version_four_eyes) -> RETIRED (beside its successor''s activation). One per (list, label); one ACTIVE per list. The activation leaves its enqueue due; RiskMonitoringSchedule''s rescreening leg enqueues one rescreening permit per ACTIVE party in bounded batches. No list content. P13-TSK-022.';
COMMENT ON COLUMN risk.screening_list_version.activated_at IS
    'When the version came into force, on the database clock: the line a kyc screening is judged current against (asked after it, by the instances'' clock skew), and the hour a hit''s SCREENING_HIT alert speaks for.';
COMMENT ON COLUMN risk.screening_list_version.enqueue_cursor IS
    'The last party the enqueue reached: a keyset over party ids, moved forward only, committed with the batch of permits it bore.';

CREATE FUNCTION risk.screening_list_version_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        -- For EVERY writer, the migrator included: a list version is the record of what was in force.
        RAISE EXCEPTION 'a screening list version is never deleted or truncated (P13-TSK-022)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'RECORDED' OR NEW.enqueued <> 0
                OR num_nonnulls(NEW.activated_by, NEW.activated_by_type, NEW.activated_at, NEW.retired_at,
                                NEW.enqueue_next_at, NEW.enqueue_cursor, NEW.enqueue_completed_at) <> 0 THEN
            RAISE EXCEPTION 'a screening list version is born RECORDED, unactivated and enqueuing nothing (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Numbered in the list's recording order without a gap: the list's lock serialises its writers, the ordinal's
        -- unique refuses any that bypassed it.
        IF NEW.ordinal <> (SELECT coalesce(max(v.ordinal), 0) + 1 FROM risk.screening_list_version v
                            WHERE v.list = NEW.list) THEN
            RAISE EXCEPTION 'a list''s versions are numbered in recording order without a gap (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.recorded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. The fact and its recorder are frozen from birth.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.list IS DISTINCT FROM OLD.list
            OR NEW.version IS DISTINCT FROM OLD.version OR NEW.ordinal IS DISTINCT FROM OLD.ordinal
            OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by
            OR NEW.recorded_by_type IS DISTINCT FROM OLD.recorded_by_type
            OR NEW.recorded_at IS DISTINCT FROM OLD.recorded_at THEN
        RAISE EXCEPTION 'a screening list version''s fact never changes (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'RETIRED' THEN
        RAISE EXCEPTION 'a retired screening list version never moves (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status = 'RECORDED' THEN
        -- The activation: a person (four eyes is screening_list_version_four_eyes' - provable alone), the enqueue due at
        -- once from nothing.
        IF NEW.status <> 'ACTIVE' OR NEW.activated_by IS NULL OR NEW.activated_by_type IS NULL
                OR NEW.enqueued <> 0 OR NEW.enqueue_cursor IS NOT NULL OR NEW.enqueue_completed_at IS NOT NULL
                OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a recorded list version is activated by a person, its enqueue starting from nothing'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.activated_at := clock_timestamp();
        NEW.enqueue_next_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- ACTIVE: the activation, the correlation and the census so far are frozen.
    IF NEW.activated_by IS DISTINCT FROM OLD.activated_by
            OR NEW.activated_by_type IS DISTINCT FROM OLD.activated_by_type
            OR NEW.activated_at IS DISTINCT FROM OLD.activated_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a list version''s activation never changes (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'RETIRED' THEN
        -- Retired beside its successor's activation: the enqueue stops where it stood.
        IF NEW.enqueue_cursor IS DISTINCT FROM OLD.enqueue_cursor OR NEW.enqueued <> OLD.enqueued
                OR NEW.enqueue_completed_at IS DISTINCT FROM OLD.enqueue_completed_at THEN
            RAISE EXCEPTION 'a retirement moves no part of the census (P13-TSK-022)' USING ERRCODE = 'check_violation';
        END IF;
        NEW.retired_at := clock_timestamp();
        NEW.enqueue_next_at := NULL;
        RETURN NEW;
    END IF;

    IF NEW.status = 'ACTIVE' THEN
        -- The enqueue's advance, inside its claim: only a permit that has come, the cursor forward, the count up, the
        -- permit due again at once or cleared with the census complete.
        IF OLD.enqueue_next_at IS NULL OR OLD.enqueue_next_at > clock_timestamp() THEN
            RAISE EXCEPTION 'a list version''s enqueue advances only within a claim of its due permit (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.enqueued < OLD.enqueued
                OR (OLD.enqueue_cursor IS NOT NULL
                    AND (NEW.enqueue_cursor IS NULL OR NEW.enqueue_cursor < OLD.enqueue_cursor)) THEN
            RAISE EXCEPTION 'a list version''s enqueue only moves forward (P13-TSK-022)' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.enqueue_next_at IS NULL THEN
            NEW.enqueue_completed_at := clock_timestamp();
        ELSE
            NEW.enqueue_next_at := clock_timestamp();
            NEW.enqueue_completed_at := NULL;
        END IF;
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a screening list version moves only along its machine (P13-TSK-022)'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER screening_list_version_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.screening_list_version
    FOR EACH ROW
    EXECUTE FUNCTION risk.screening_list_version_permits_only_machine_edges();

CREATE TRIGGER screening_list_version_is_never_truncated
    BEFORE TRUNCATE ON risk.screening_list_version
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.screening_list_version_permits_only_machine_edges();

-- ---------------------------------------------------------------------------------------------------------------------
-- 2. The signal: two references and risk's own two kinds.
-- ---------------------------------------------------------------------------------------------------------------------

ALTER TABLE risk.risk_signal ADD COLUMN list_version_ref uuid;

ALTER TABLE risk.risk_signal ADD COLUMN screening_ref uuid;

-- The unique the permit's composite foreign key references: a permit names exactly the signal born of it.
ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_id_and_source UNIQUE (id, source_event_id);

ALTER TABLE risk.risk_signal DROP CONSTRAINT risk_signal_kind_is_known;

ALTER TABLE risk.risk_signal DROP CONSTRAINT risk_signal_subject_matches_kind;

ALTER TABLE risk.risk_signal DROP CONSTRAINT risk_signal_attributes_match_kind;

-- SignalKind, in its declaration order.
ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_kind_is_known CHECK (kind IN (
    'CREDENTIAL_CHANGED', 'RECOVERY_INITIATED', 'RECOVERY_COMPLETED', 'IDENTITY_SUSPENDED', 'ACCOUNT_OPENED',
    'BENEFICIARY_REGISTERED', 'BENEFICIARY_ACTIVATED', 'BENEFICIARY_BLOCKED', 'MERCHANT_ONBOARDED',
    'LOAN_DELINQUENCY_CHANGED', 'LOAN_DEFAULTED', 'BNPL_DELINQUENCY_CHANGED', 'BNPL_DEFAULTED', 'REFUND_COMPLETED',
    'CHARGEBACK_RECEIVED', 'BNPL_APPLICATION_SUBMITTED', 'SCREENING_CLEARED', 'SCREENING_HIT'));

-- Each kind is about one subject kind.
ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_subject_matches_kind CHECK (subject_kind = CASE kind
    WHEN 'CREDENTIAL_CHANGED'         THEN 'IDENTITY'
    WHEN 'RECOVERY_INITIATED'         THEN 'IDENTITY'
    WHEN 'RECOVERY_COMPLETED'         THEN 'IDENTITY'
    WHEN 'IDENTITY_SUSPENDED'         THEN 'IDENTITY'
    WHEN 'ACCOUNT_OPENED'             THEN 'ACCOUNT'
    WHEN 'BENEFICIARY_REGISTERED'     THEN 'BENEFICIARY'
    WHEN 'BENEFICIARY_ACTIVATED'      THEN 'BENEFICIARY'
    WHEN 'BENEFICIARY_BLOCKED'        THEN 'BENEFICIARY'
    WHEN 'MERCHANT_ONBOARDED'         THEN 'MERCHANT'
    WHEN 'LOAN_DELINQUENCY_CHANGED'   THEN 'CREDIT_AGREEMENT'
    WHEN 'LOAN_DEFAULTED'             THEN 'CREDIT_AGREEMENT'
    WHEN 'BNPL_DELINQUENCY_CHANGED'   THEN 'CREDIT_AGREEMENT'
    WHEN 'BNPL_DEFAULTED'             THEN 'CREDIT_AGREEMENT'
    WHEN 'REFUND_COMPLETED'           THEN 'PARTY'
    WHEN 'CHARGEBACK_RECEIVED'        THEN 'PARTY'
    WHEN 'BNPL_APPLICATION_SUBMITTED' THEN 'PARTY'
    WHEN 'SCREENING_CLEARED'          THEN 'PARTY'
    WHEN 'SCREENING_HIT'              THEN 'PARTY'
    END);

-- Each kind carries exactly its attributes: the named ones non-null, and nothing else - the count over every attribute
-- column (now seventeen) equals the kind's count. ELSE false keeps the CHECK from passing on a NULL case result.
ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_attributes_match_kind CHECK (CASE kind
    WHEN 'CREDENTIAL_CHANGED' THEN
        num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                     business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                     merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 0
    WHEN 'RECOVERY_INITIATED' THEN
        request_ref IS NOT NULL AND channel_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 2
    WHEN 'RECOVERY_COMPLETED' THEN
        request_ref IS NOT NULL AND channel_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 2
    WHEN 'IDENTITY_SUSPENDED' THEN
        status IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 1
    WHEN 'ACCOUNT_OPENED' THEN
        product IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 1
    WHEN 'BENEFICIARY_REGISTERED' THEN
        country IS NOT NULL AND currency IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 2
    WHEN 'BENEFICIARY_ACTIVATED' THEN
        country IS NOT NULL AND currency IS NOT NULL AND previous_status IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 3
    WHEN 'BENEFICIARY_BLOCKED' THEN
        country IS NOT NULL AND currency IS NOT NULL AND previous_status IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 3
    WHEN 'MERCHANT_ONBOARDED' THEN
        currency IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 1
    WHEN 'LOAN_DELINQUENCY_CHANGED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND previous_bucket IS NOT NULL AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 7
    WHEN 'LOAN_DEFAULTED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 6
    WHEN 'BNPL_DELINQUENCY_CHANGED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND previous_bucket IS NOT NULL AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 7
    WHEN 'BNPL_DEFAULTED' THEN
        product IS NOT NULL AND business_date IS NOT NULL AND days_past_due IS NOT NULL AND bucket IS NOT NULL
        AND defaulted IS NOT NULL AND agreement_version IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 6
    WHEN 'REFUND_COMPLETED' THEN
        merchant_subject_ref IS NOT NULL AND intent_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 2
    WHEN 'CHARGEBACK_RECEIVED' THEN
        merchant_subject_ref IS NOT NULL AND intent_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 2
    WHEN 'BNPL_APPLICATION_SUBMITTED' THEN
        product IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 1
    WHEN 'SCREENING_CLEARED' THEN
        list_version_ref IS NOT NULL AND screening_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 2
    WHEN 'SCREENING_HIT' THEN
        list_version_ref IS NOT NULL AND screening_ref IS NOT NULL
        AND num_nonnulls(product, status, previous_status, bucket, previous_bucket, country, currency,
                         business_date, days_past_due, defaulted, agreement_version, channel_ref, request_ref,
                         merchant_subject_ref, intent_ref, list_version_ref, screening_ref) = 2
    ELSE false
    END);

-- A version's clearances - the census's read (permits DONE with a clearance = signals naming the version).
CREATE INDEX risk_signal_by_list_version ON risk.risk_signal (list_version_ref, kind)
    WHERE list_version_ref IS NOT NULL;

COMMENT ON COLUMN risk.risk_signal.list_version_ref IS
    'The screening list version a SCREENING_CLEARED or SCREENING_HIT clearance was judged current under (P13-TSK-022) - an identifier; NULL for every other kind.';
COMMENT ON COLUMN risk.risk_signal.screening_ref IS
    'kyc''s screening a SCREENING_CLEARED or SCREENING_HIT clearance records, by its id (P13-TSK-022) - never kyc''s row, its evidence or a name; NULL for every other kind.';

-- ---------------------------------------------------------------------------------------------------------------------
-- 3. The rescreening permit.
-- ---------------------------------------------------------------------------------------------------------------------

CREATE TABLE risk.rescreening_permit (
    id               uuid        NOT NULL,
    party_id         uuid        NOT NULL,
    list_version_id  uuid        NOT NULL,
    status           text        NOT NULL,
    outcome          text,
    cause            text,
    signal_id        uuid,
    next_attempt_at  timestamptz,
    attempts         integer     NOT NULL DEFAULT 0,
    enqueued_at      timestamptz NOT NULL,
    concluded_at     timestamptz,
    correlation_id   text        NOT NULL,

    CONSTRAINT rescreening_permit_pk PRIMARY KEY (id),
    -- ONE PERMIT PER (party, list version): the arbiter every enqueuer meets (INV-RSK-06).
    CONSTRAINT rescreening_permit_once UNIQUE (party_id, list_version_id),
    CONSTRAINT rescreening_permit_version_fk FOREIGN KEY (list_version_id) REFERENCES risk.screening_list_version (id),
    -- A permit names exactly the clearance born of it: the signal whose source_event_id is the permit's id.
    CONSTRAINT rescreening_permit_signal_is_its_own FOREIGN KEY (signal_id, id)
        REFERENCES risk.risk_signal (id, source_event_id),

    -- The closed vocabularies RescreeningPermit declares (ScreeningListMigrationTest).
    CONSTRAINT rescreening_permit_status_is_known CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT rescreening_permit_outcome_is_known CHECK (outcome IS NULL OR outcome IN ('CLEARED', 'HIT', 'SKIPPED')),
    CONSTRAINT rescreening_permit_cause_is_known CHECK (
        cause IS NULL OR cause IN ('STALE', 'IN_FLIGHT', 'NOT_SCREENED', 'UNAVAILABLE', 'UNCONCLUDED')),

    -- The permit exactly while PENDING; the outcome exactly when DONE; a cause when FAILED and none when DONE; the signal
    -- exactly for a clearance; concluded exactly when terminal; an attempt behind every conclusion.
    CONSTRAINT rescreening_permit_permit_coherent CHECK ((next_attempt_at IS NOT NULL) = (status = 'PENDING')),
    CONSTRAINT rescreening_permit_outcome_coherent CHECK ((outcome IS NOT NULL) = (status = 'DONE')),
    CONSTRAINT rescreening_permit_cause_coherent CHECK (
        (status <> 'FAILED' OR cause IS NOT NULL) AND (status <> 'DONE' OR cause IS NULL)),
    CONSTRAINT rescreening_permit_signal_coherent CHECK (
        (signal_id IS NOT NULL) = coalesce(outcome IN ('CLEARED', 'HIT'), false)),
    CONSTRAINT rescreening_permit_concluded_coherent CHECK ((concluded_at IS NOT NULL) = (status <> 'PENDING')),
    CONSTRAINT rescreening_permit_attempts_coherent CHECK (
        attempts BETWEEN 0 AND 1000000 AND (status = 'PENDING' OR attempts >= 1)),

    CONSTRAINT rescreening_permit_correlation_bounded CHECK (char_length(correlation_id) BETWEEN 1 AND 200)
);

-- The leg's claim: the due permits, oldest first.
CREATE INDEX rescreening_permit_due ON risk.rescreening_permit (next_attempt_at, id) WHERE status = 'PENDING';

-- A version's census: its permits by status.
CREATE INDEX rescreening_permit_of_version ON risk.rescreening_permit (list_version_id, status);

COMMENT ON TABLE risk.rescreening_permit IS
    'One party''s rescreening under one screening list version (PHASE_13_PLAN.md section 12.10): born PENDING by the activation''s enqueue, one per (party, version) (rescreening_permit_once); claimed by RiskMonitoringSchedule''s rescreening leg FOR UPDATE SKIP LOCKED (an attempt, the permit a lease ahead), kyc asked outside every risk transaction under the key risk.rescreen:<id>, concluded once at the claimed attempt: DONE (CLEARED | HIT - a kyc screening asked after the version''s activation and decided, recorded as the signal born of this permit; SKIPPED - the party no longer ACTIVE) or FAILED at the bound, the cause kept. Risk asks; kyc screens (INV-RSK-02). P13-TSK-022.';
COMMENT ON COLUMN risk.rescreening_permit.cause IS
    'Why the permit waits (the last deferral''s cause) or why it failed: STALE (kyc''s latest decided screening was asked before the version''s activation), IN_FLIGHT, NOT_SCREENED, UNAVAILABLE, or - failing only - UNCONCLUDED (the bound passed with no conclusion recorded; failed by the next claim). NULL once DONE.';

CREATE FUNCTION risk.rescreening_permit_permits_only_machine_edges() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'a rescreening permit is never deleted or truncated (P13-TSK-022, INV-RSK-06)';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.attempts <> 0
                OR num_nonnulls(NEW.outcome, NEW.cause, NEW.signal_id, NEW.concluded_at) <> 0 THEN
            RAISE EXCEPTION 'a rescreening permit is born PENDING, unclaimed and unconcluded (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Enqueued only for a version in force.
        IF NOT EXISTS (SELECT 1 FROM risk.screening_list_version v
                        WHERE v.id = NEW.list_version_id AND v.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'a rescreening permit is enqueued for a screening list version in force (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.enqueued_at := clock_timestamp();
        NEW.next_attempt_at := clock_timestamp();
        RETURN NEW;
    END IF;

    -- UPDATE. Who and under what are frozen from birth.
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.party_id IS DISTINCT FROM OLD.party_id
            OR NEW.list_version_id IS DISTINCT FROM OLD.list_version_id
            OR NEW.enqueued_at IS DISTINCT FROM OLD.enqueued_at
            OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id THEN
        RAISE EXCEPTION 'a rescreening permit''s party and version never change (P13-TSK-022)'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a concluded rescreening permit never moves (P13-TSK-022)' USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'PENDING' THEN
        IF NEW.outcome IS NOT NULL OR NEW.signal_id IS NOT NULL OR NEW.next_attempt_at IS NULL
                OR NEW.next_attempt_at <= clock_timestamp() THEN
            RAISE EXCEPTION 'a pending rescreening permit keeps a permit ahead and no outcome (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.attempts = OLD.attempts + 1 THEN
            -- The claim: only a permit that has come; nothing of the answer moves.
            IF OLD.next_attempt_at > clock_timestamp() OR NEW.cause IS DISTINCT FROM OLD.cause THEN
                RAISE EXCEPTION 'a rescreening permit is claimed only when its permit has come, recording no answer'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.attempts = OLD.attempts THEN
            -- The deferral: after a claim, with the answer's cause.
            IF OLD.attempts < 1 OR NEW.cause IS NULL THEN
                RAISE EXCEPTION 'a rescreening permit is deferred after a claim, with its cause (P13-TSK-022)'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'a claim counts exactly one attempt (P13-TSK-022)' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.status IN ('DONE', 'FAILED') THEN
        -- The conclusion, once, after a claim, counting no attempt; the instant the database's.
        IF NEW.attempts <> OLD.attempts OR OLD.attempts < 1 THEN
            RAISE EXCEPTION 'a rescreening permit is concluded after a claim, counting no attempt (P13-TSK-022)'
                USING ERRCODE = 'check_violation';
        END IF;
        NEW.next_attempt_at := NULL;
        NEW.concluded_at := clock_timestamp();
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'a rescreening permit moves only along its machine (P13-TSK-022)' USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER rescreening_permit_permits_only_machine_edges
    BEFORE INSERT OR UPDATE OR DELETE ON risk.rescreening_permit
    FOR EACH ROW
    EXECUTE FUNCTION risk.rescreening_permit_permits_only_machine_edges();

CREATE TRIGGER rescreening_permit_is_never_truncated
    BEFORE TRUNCATE ON risk.rescreening_permit
    FOR EACH STATEMENT
    EXECUTE FUNCTION risk.rescreening_permit_permits_only_machine_edges();

-- ---------------------------------------------------------------------------------------------------------------------
-- 4. The grants: SELECT and INSERT; a column-scoped UPDATE of each machine's columns alone - never a fact, never a stamp
--    (the triggers'). No DELETE for any role.
-- ---------------------------------------------------------------------------------------------------------------------

GRANT SELECT, INSERT ON risk.screening_list_version TO finapp_app;
GRANT UPDATE (status, activated_by, activated_by_type, enqueue_next_at, enqueue_cursor, enqueued, correlation_id)
    ON risk.screening_list_version TO finapp_app;
GRANT SELECT, INSERT ON risk.rescreening_permit TO finapp_app;
GRANT UPDATE (status, outcome, cause, signal_id, next_attempt_at, attempts) ON risk.rescreening_permit TO finapp_app;
