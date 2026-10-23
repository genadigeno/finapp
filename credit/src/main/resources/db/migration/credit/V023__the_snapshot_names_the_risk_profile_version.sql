-- The decision snapshot names the risk profile version (P13-TSK-007; PHASE_13_PLAN.md section 12.5, ADR-0084 point 5's
-- follow-up; INV-RSK-10, INV-CRD-01, INV-HIST-04).
--
-- Risk's CreditRiskSignal seam version 2 answers a party's customer risk rating together with the version of the risk
-- profile it was read from, and the freezer records both: the rating as RISK_SIGNAL's value, the seam version and the
-- profile version as its provenance - the port's answer and the record it came from. Naming a record in a port's
-- provenance is a change to the canonical form, so it is CanonicalSnapshot's format 2: format 1 plus an optional
-- "recordVersion" on a port's provenance, nothing else. Format 1 is kept for replay - every stored format-1 text parses
-- and re-renders byte-identically, and a decision sealed under seam version 1 replays IDENTICAL with NOT_ASSESSED.
--
-- The only schema change: the format CHECK admits 2 beside 1 (and the table's comment says so). No row is touched - a frozen snapshot is never updated
-- (V005's trigger), and none needs to be: its format is the one that wrote it. The constraint keeps its name, so the
-- name stays unique in the schema.

ALTER TABLE credit.decision_snapshot DROP CONSTRAINT decision_snapshot_format_is_known;

ALTER TABLE credit.decision_snapshot
    ADD CONSTRAINT decision_snapshot_format_is_known CHECK (snapshot_format IN (1, 2));

COMMENT ON TABLE credit.decision_snapshot IS
    'A frozen decision input (P10-TSK-008, INV-CRD-07): the canonical text - format 1, or format 2 since P13-TSK-007 (a port''s provenance may name the record it answered from: the risk seam''s profile version) - and its SHA-256 (recomputed by a CHECK), the pinned policy, model and engine versions; born once per (decision request, sequence), never updated, deleted or truncated by any role. RESTRICTED-FINANCIAL.';
