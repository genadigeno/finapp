-- The saved destination (P4-TSK-006, PHASE_4_PLAN.md section 8).
--
-- A CONVENIENCE WITH A LIFECYCLE, NEVER A TRUST DECISION. A beneficiary authorizes nothing:
-- the transfer's own execution resolves and judges the destination at decision time, so a row
-- here is a person's saved address-book entry - and removal is the removal of a convenience,
-- which is why removal racing a transfer is the plan's accepted race (section 7), bounded by
-- the destination's own postability at posting.
--
-- EVERY GENERATED LIST BELOW HAS ONE DEFINITION. The status CHECK comes from
-- BeneficiaryStatus.sqlValueList() and the one-live index predicate from
-- BeneficiaryStatus.sqlTerminalValueList(); BeneficiaryMigrationTest fails the build if this
-- file and the enum disagree (the P0-TSK-022 pattern), so a state added in code without
-- deciding whether it frees the one-live slot cannot land quietly.
--
-- NO CROSS-SCHEMA FOREIGN KEYS, DELIBERATELY. party_id references party.party and
-- destination_account_id references accounts.customer_account - both by value (ADR-0029's
-- rule): a cross-schema FK is coupling Gradle and ArchUnit cannot see. What guarantees the
-- destination exists is the creating command, which resolves it through the resolution port
-- from authoritative state (BeneficiaryCreation).

CREATE TABLE transfers.beneficiary (
    -- UUIDv7, minted by the application (ADR-0013).
    id                     uuid        PRIMARY KEY,

    -- The owning PARTY - a saved destination is a person's, not a commercial relationship's,
    -- and it outlives any one customer role (the consent-record precedent). The ownership
    -- predicate's column (ADR-0031; P4-TSK-007's surface).
    party_id               uuid        NOT NULL,

    -- Free text a person writes about a person (RESTRICTED-PII at the register). The domain
    -- rule is Beneficiary's constructor; these CHECKs are DELIBERATELY NARROWER - the length
    -- bound, and the POSIX control-character class, which expresses the C0/C1 ranges exactly
    -- and not the domain's five Unicode categories (the party V003 precedent, stated rather
    -- than dressed as parity).
    display_name           text        NOT NULL
        CONSTRAINT beneficiary_display_name_is_bounded
            CHECK (length(display_name) <= 200),
    CONSTRAINT beneficiary_display_name_has_no_control_characters
        CHECK (display_name !~ '[[:cntrl:]]'),

    -- The destination product (accounts.customer_account.id), by value; see the header.
    destination_account_id uuid        NOT NULL,

    -- Generated from BeneficiaryStatus.sqlValueList(). The machine lives on the enum and in
    -- the aggregate (INV-LIFE-02); this CHECK bounds what any writer can store, and the
    -- trigger below bounds how a stored value may move.
    status                 text        NOT NULL
        CONSTRAINT beneficiary_status_is_known
            CHECK (status IN ('ACTIVE', 'REMOVED')),

    -- Application-supplied from the injected Clock, never DEFAULT now() (P0-TSK-015's rule).
    created_at             timestamptz NOT NULL,
    removed_at             timestamptz,

    -- The status and the removal instant are one fact, unsplittable for any writer (the
    -- hold_release_instant_matches_status shape).
    CONSTRAINT beneficiary_removal_instant_matches_status
        CHECK ((status = 'ACTIVE') = (removed_at IS NULL)),

    CONSTRAINT beneficiary_removal_follows_creation
        CHECK (removed_at IS NULL OR removed_at >= created_at)
);

-- ONE LIVE ROW PER (PARTY, DESTINATION) - the concurrency arbiter (P4-TSK-006's distributed
-- requirement): "save this destination" is one saved row however many instances say it, and
-- the store's createOrConverge hands the loser the winner's row. PARTIAL, over the
-- non-terminal states - the predicate generated from sqlTerminalValueList() - because removal
-- frees the slot: saving the destination again after removing it is legitimate and is a NEW
-- aggregate (INV-LIFE-04, the customer_account asymmetry rather than the login-identifier
-- one), while the removed row survives as evidence.
CREATE UNIQUE INDEX beneficiary_one_live_per_party_destination
    ON transfers.beneficiary (party_id, destination_account_id)
    WHERE status NOT IN ('REMOVED');

-- Serves "this party's live beneficiaries" - P4-TSK-007's listing, named now rather than
-- discovered by that task (the V004 index-the-read precedent).
CREATE INDEX beneficiary_by_party ON transfers.beneficiary (party_id);

-- REMOVED is terminal for every writer, the migrator included (INV-LIFE-04 at DB-CONSTRAINT
-- rank; the ledger V008 hold shape). Exactly one edge exists: ACTIVE -> REMOVED, with the
-- removal instant arriving in the same statement; identity and name are immutable in any
-- state - a beneficiary is renamed by removing it and saving a new one, never by edit. This
-- is what makes "raw SQL cannot resurrect a REMOVED row" true rather than asserted: the
-- column-narrowed grant below binds the application role, and this binds everyone.
CREATE OR REPLACE FUNCTION transfers.beneficiary_permits_only_removal()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id <> NEW.id
        OR OLD.party_id <> NEW.party_id
        OR OLD.display_name <> NEW.display_name
        OR OLD.destination_account_id <> NEW.destination_account_id
        OR OLD.created_at <> NEW.created_at THEN
        RAISE EXCEPTION 'a beneficiary''s identity and name are immutable: renaming is remove-and-recreate, never an edit (P4-TSK-006)';
    END IF;
    IF NOT (OLD.status = 'ACTIVE' AND NEW.status IN ('REMOVED')) THEN
        RAISE EXCEPTION 'the only edge a beneficiary has is ACTIVE -> REMOVED (INV-LIFE-04)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER beneficiary_permits_only_removal
    BEFORE UPDATE ON transfers.beneficiary
    FOR EACH ROW
    EXECUTE FUNCTION transfers.beneficiary_permits_only_removal();

COMMENT ON TABLE transfers.beneficiary IS
    'A party''s saved destination: a display name resolving to an internal customer product (PHASE_4_PLAN.md section 5). A convenience, never a trust decision - the transfer''s own execution judges the destination at decision time. REMOVED is terminal; the row survives as evidence, and saving the destination again is a new row through the freed slot.';
COMMENT ON COLUMN transfers.beneficiary.party_id IS
    'party.party.id by value; no cross-schema FK by design (ADR-0029''s rule). A beneficiary belongs to the PARTY: it outlives any one customer relationship.';
COMMENT ON COLUMN transfers.beneficiary.destination_account_id IS
    'accounts.customer_account.id by value. Existence validated through the resolution port at creation; postability deliberately re-judged only by each transfer (the accepted race, plan section 7).';

-- THE GRANTS ARRIVE WITH THE TABLE (P4-TSK-001's floor made this available; PHASE_4_PLAN.md
-- section 8 names this exact set). SELECT and INSERT for the row's life; UPDATE narrowed to
-- exactly the removal transition's columns - identity columns (id, party_id, display_name,
-- destination_account_id, created_at) are facts, not fields, and stay unwritable by the
-- application role. No DELETE: a beneficiary's end is a status, never an absence.
GRANT SELECT, INSERT ON transfers.beneficiary TO finapp_app;
GRANT UPDATE (status, removed_at) ON transfers.beneficiary TO finapp_app;
