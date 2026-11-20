-- Credit evidence names its media type (P17-TSK-116, finapp-admin-panel B8.7 item 4: "today it comes as bytes with no media type").
-- finapp-contract: first-conforming-release=ANY; waives=CHECK; reason=no release before this one names media_type, so its inserts leave it NULL, which the CHECK admits; this release writes NULL or a media type the CHECK's own shape, CreditEvidence.mediaTypeOf, produced
--
-- V027: credit V025 is P17-TSK-042's (the review queue's index) and V026 P17-TSK-035's (the decision request list's);
-- merge both before this one, or Flyway's order check refuses them on a database that already holds V027.
--
-- WHAT
--   One column, media_type: the Content-Type the provider's adapter received with the bytes, as it arrived (bounded and
--   shape-checked: a type/subtype, parameters allowed), NULL for every row written before this release and for every
--   consent-withdrawn arrival (no payload). A NULL reads application/octet-stream - the honest name for bytes of unknown
--   kind. It is metadata, never the content: the content stays reachable only through credit.read_evidence with a reason.
--   The investigator's door reads it through a column grant beside V012's (id, data_request_id, attempt, ...), so no
--   SECURITY DEFINER body is replaced (MigrationLint refuses any replacement of a released function body).
--   The insert grant is the table's (V004) and covers it; the born-once trigger fires on INSERT and stamps recorded_at,
--   unchanged - no UPDATE ever writes this column, so INV-HIST-02 holds.
--
-- LOCKS
--   ADD COLUMN without a default takes ACCESS EXCLUSIVE for its moment and rewrites nothing; no row trigger fires. ADD
--   CONSTRAINT ... NOT VALID takes the same lock and scans no row: every existing row is NULL, which it admits, and every
--   insert from now on is judged - the old rows are left to a later release's VALIDATE (DATA_MIGRATIONS.md section 4.1).
--   Expand-only: the previous release neither reads nor writes the column, and its inserts leave it NULL.

ALTER TABLE credit.credit_evidence
    ADD COLUMN media_type text;

ALTER TABLE credit.credit_evidence
    ADD CONSTRAINT credit_evidence_media_type_is_a_media_type CHECK (
        media_type IS NULL
        OR (char_length(media_type) BETWEEN 3 AND 255
            AND media_type ~ '^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*( *;[ -~]*)?$')) NOT VALID;

COMMENT ON COLUMN credit.credit_evidence.media_type IS
    'P17-TSK-116: the Content-Type the provider''s adapter received with the bytes, as it arrived; NULL - every row before V027 and every consent-withdrawn arrival - reads application/octet-stream. INTERNAL: metadata, never the content.';

GRANT SELECT (media_type) ON credit.credit_evidence TO finapp_app;
