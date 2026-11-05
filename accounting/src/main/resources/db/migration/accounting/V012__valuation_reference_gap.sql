-- A reference gap's way on (P14-TSK-026; ADR-0119 decision 2, superseding ADR-0116 decision 2 for this case; INV-ACC-11,
-- INV-FX-05, INV-AUD-04).
--
-- WHAT CHANGES
--   ADR-0116's capture admitted, per valued pair, only the fx reference's last observation strictly before the period end
--   AND within the set's staleness bound; one pair older than the bound refused the whole capture, and a reference silent
--   for longer left the month with no set and no door. A four-eyes capture exception now admits, for a pair a person names
--   with its snapshot id, the reference's REAL last observation before the end although it is older than the bound -
--   flagged on the rate (beyond_bound) and counted on the set (reference_gaps), its provenance (source, snapshot id,
--   observed_at, received_at) kept exactly as for any rate. A typed rate is not admitted: there is no column for one.
--
-- WHAT THIS MIGRATION HOLDS FOR EVERY WRITER
--   A flagged rate is born only with a PROPOSED set that declares gaps, only up to that count, only strictly before the
--   set's as_of and only OLDER than its bound (a flagged rate inside the bound would be a gap that is none). An unflagged
--   rate keeps V005's window exactly. At COMMIT a set holds exactly rate_count rates (V005) and exactly reference_gaps of
--   them flagged. A set declaring gaps carries its capture reason. reference_gaps never moves after birth. Which observation
--   is the reference's LAST before the end - so nobody picks an older one - is the domain's judgement at capture and again
--   at approval (accounting.ValuationRateSet); fx.rate_snapshot is another module's table and no constraint here reads it.
--
-- WHAT IT DOES NOT TOUCH
--   V005's file (applied, never edited): its two functions below are REPLACED by CREATE OR REPLACE, their triggers
--   unchanged and still bound to them; every check of V005's own is restated verbatim in the replacements. No grant:
--   the application role's UPDATE on valuation_rate_set is V005's decision columns, so it can write neither new column
--   after the insert; valuation_rate stays INSERT-only for every role. The approval's MULTI_FACTOR is the route's
--   (app's ValuationRateSetController); four eyes stays V005's valuation_rate_set_four_eyes.

ALTER TABLE accounting.valuation_rate_set
    ADD COLUMN reference_gaps integer NOT NULL DEFAULT 0;

-- Every existing row holds 0, so the validating scans of the two CHECKs below refuse nothing.
ALTER TABLE accounting.valuation_rate_set
    ADD CONSTRAINT valuation_rate_set_reference_gaps_counted CHECK (reference_gaps BETWEEN 0 AND rate_count),
    ADD CONSTRAINT valuation_rate_set_reference_gap_reasoned CHECK (reference_gaps = 0 OR capture_reason IS NOT NULL);

COMMENT ON COLUMN accounting.valuation_rate_set.reference_gaps IS
    'How many of the set''s rates a four-eyes reference-gap exception admitted older than max_age_hours - the reference''s '
    'real last observation of the pair before as_of, flagged valuation_rate.beyond_bound (P14-TSK-026, ADR-0119). 0 for an '
    'ordinary capture; frozen from birth; a set declaring gaps carries its capture_reason.';

ALTER TABLE accounting.valuation_rate
    ADD COLUMN beyond_bound boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN accounting.valuation_rate.beyond_bound IS
    'True for a rate a reference-gap exception admitted: observed strictly before the set''s as_of and older than its '
    'max_age_hours, within the set''s reference_gaps (P14-TSK-026, ADR-0119). Every translated report pinning the set '
    'discloses the pair and its age.';

-- reference_gaps is frozen from birth, for every writer (the application role cannot write it at all; this binds the rest).
CREATE OR REPLACE FUNCTION accounting.valuation_rate_set_reference_gaps_are_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reference_gaps IS DISTINCT FROM OLD.reference_gaps THEN
        RAISE EXCEPTION 'a valuation rate set''s reference gaps are frozen from its capture (P14-TSK-026, INV-ACC-11)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER valuation_rate_set_reference_gaps_are_frozen
    BEFORE UPDATE ON accounting.valuation_rate_set
    FOR EACH ROW
    EXECUTE FUNCTION accounting.valuation_rate_set_reference_gaps_are_frozen();

-- V005's rate trigger, replaced: everything it held, and the flagged rate's window and count.
CREATE OR REPLACE FUNCTION accounting.valuation_rate_is_born_with_its_set()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_status text;
    held_as_of  timestamptz;
    held_hours  integer;
    held_count  integer;
    held_gaps   integer;
    held_rates  integer;
    held_flagged integer;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'a valuation rate is immutable from insert, for every role (P14-TSK-014, INV-ACC-11)';
    END IF;
    -- The set row locked: every writer of one set's rates serialises on it, so the counts below are exact.
    SELECT s.status, s.as_of, s.max_age_hours, s.rate_count, s.reference_gaps
      INTO held_status, held_as_of, held_hours, held_count, held_gaps
      FROM accounting.valuation_rate_set s WHERE s.id = NEW.set_id FOR UPDATE;
    IF NOT FOUND OR held_status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'a valuation rate is born with its PROPOSED set (P14-TSK-014)';
    END IF;
    SELECT count(*) INTO held_rates FROM accounting.valuation_rate r WHERE r.set_id = NEW.set_id;
    IF held_rates >= held_count THEN
        RAISE EXCEPTION 'a valuation rate set holds exactly its % rates: none is added late (P14-TSK-014, INV-ACC-11)', held_count;
    END IF;
    IF NOT NEW.beyond_bound THEN
        -- THE WINDOW (V005): observed strictly before the period's end, within the set's staleness bound.
        IF NOT (NEW.observed_at < held_as_of AND NEW.observed_at >= held_as_of - make_interval(hours => held_hours)) THEN
            RAISE EXCEPTION 'a valuation rate is observed before its set''s period end and within its % hour bound (P14-TSK-014)', held_hours;
        END IF;
    ELSE
        -- A REFERENCE GAP (P14-TSK-026): strictly before the end and OLDER than the bound, within the set's declared count.
        IF NOT (NEW.observed_at < held_as_of AND NEW.observed_at < held_as_of - make_interval(hours => held_hours)) THEN
            RAISE EXCEPTION 'a rate flagged beyond the bound is observed before its set''s period end and older than its % hour bound (P14-TSK-026)', held_hours;
        END IF;
        SELECT count(*) INTO held_flagged FROM accounting.valuation_rate r WHERE r.set_id = NEW.set_id AND r.beyond_bound;
        IF held_flagged >= held_gaps THEN
            RAISE EXCEPTION 'a valuation rate set flags exactly its % reference gaps beyond the bound (P14-TSK-026, INV-ACC-11)', held_gaps;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- V005's commit-whole check, replaced: exactly rate_count rates, and exactly reference_gaps of them flagged.
CREATE OR REPLACE FUNCTION accounting.valuation_rate_set_commits_whole()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    held_rates   integer;
    held_flagged integer;
BEGIN
    SELECT count(*), count(*) FILTER (WHERE r.beyond_bound) INTO held_rates, held_flagged
      FROM accounting.valuation_rate r WHERE r.set_id = NEW.id;
    IF held_rates <> NEW.rate_count THEN
        RAISE EXCEPTION 'valuation rate set % committed with % of its % rates (P14-TSK-014)', NEW.id, held_rates, NEW.rate_count;
    END IF;
    IF held_flagged <> NEW.reference_gaps THEN
        RAISE EXCEPTION 'valuation rate set % committed with % of its % reference gaps flagged (P14-TSK-026)', NEW.id, held_flagged, NEW.reference_gaps;
    END IF;
    RETURN NULL;
END;
$$;
