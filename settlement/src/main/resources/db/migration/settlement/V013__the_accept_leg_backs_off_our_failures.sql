-- The Phase 8 -> 9 transition: the accept leg backs off our own failures, as the parse leg always
-- has (MI-7; ADR-0066 section 9, INV-SET-07).
--
-- WHAT THE TRANSITION'S GATE FOUND
--   The accept leg read its candidates oldest first, LIMIT filesPerSweep, and a file whose
--   acceptance threw - our defect, such as a currency whose position the chart never seeded -
--   was rolled back whole and stayed PARSED, which is right, and stayed FIRST, which is not. As
--   many always-failing files as the sweep's batch size held every candidate window on every
--   instance, every tick: no later file of ANY source was ever accepted again.
--
-- WHAT THIS MIGRATION ADDS
--   accept_failures and next_accept_at, the twins of V002's parse_failures and next_parse_at.
--   BatchAcceptance counts its own failure and backs the file off in a second, small transaction
--   (written only while the file is still PARSED, under its row lock), and its candidate read
--   skips a file not yet due - so a poisoned file leaves the window instead of holding it. Pacing
--   only, never correctness: the conditional PARSED -> ACCEPTED and the uniques beneath it remain
--   the arbiters, and neither column is frozen by file_permits_only_machine_edges.

ALTER TABLE settlement.file
    ADD COLUMN accept_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN next_accept_at TIMESTAMPTZ,
    ADD CONSTRAINT file_accept_failures_counted CHECK (accept_failures >= 0);

COMMENT ON COLUMN settlement.file.accept_failures IS
    'How often our accept leg failed on this file - our defect''s counter, never the evidence''s verdict (the Phase 8 -> 9 transition, MI-7).';
COMMENT ON COLUMN settlement.file.next_accept_at IS
    'The accept leg''s back-off: the file is no acceptance candidate before this instant.';

GRANT UPDATE (accept_failures, next_accept_at) ON settlement.file TO finapp_app;
