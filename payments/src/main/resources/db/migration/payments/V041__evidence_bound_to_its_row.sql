-- Provider and dispute evidence bound to its row (X-TSK-010; THREAT_MODEL.md T35; INV-HIST-02, INV-DSP-03).
-- finapp-contract: first-conforming-release=ANY; waives=CHECK; reason=no release before this one names cipher_format, so its inserts take the default 1, which each CHECK admits; this release writes only 1 or 2
--
-- WHY
--   EvidenceCipher bound no associated data: a ciphertext the key genuinely wrote, copied into another row, decrypted
--   cleanly there, and only the checksum held beside it could notice - and a writer copying the ciphertext copies the
--   checksum with it. A write now names the row as AES-GCM associated data, so a ciphertext moved to another row fails to
--   decrypt: for payments.provider_evidence its id, its six subjects (attempt, refund, withdrawal, dispute response,
--   unmatched confirmation, outbound credit; an absent one the empty value) and its kind; for payments.dispute_evidence
--   its id, its dispute and its kind. Every ciphertext written before this one bound nothing, and stays readable: the read
--   follows the format stored with the row.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One column on each table, cipher_format: 1, unbound - every existing row, by the default, and every row written while
--   finapp.ciphers.bind-associated-data is off - or 2, bound. A bound ciphertext read as unbound fails its tag exactly as
--   one under the wrong row does, so the column is no downgrade path. Each table's insert grant is the table's (V005's and
--   V022's GRANT SELECT, INSERT, no column list) and covers it; neither table has an UPDATE grant, and provider_evidence's
--   append-only trigger refuses one for every writer, so the column is set once, at insert.
--   Each CHECK, provider_evidence_cipher_format_is_known and dispute_evidence_cipher_format_is_known, is added NOT VALID:
--   every existing row holds the default, which it admits, and every insert from now on is judged; the old rows are left to
--   a later release's VALIDATE (DATA_MIGRATIONS.md section 4.1), as the tightening rule asks of a constraint over a table an
--   earlier release wrote.
--
-- LOCKS
--   ADD COLUMN with a constant default takes ACCESS EXCLUSIVE on its table for its moment and rewrites nothing (PostgreSQL
--   11 onward); no row trigger fires - provider_evidence's append-only trigger is BEFORE UPDATE OR DELETE and its truncate
--   trigger BEFORE TRUNCATE, and dispute_evidence has none. ADD CONSTRAINT ... NOT VALID takes the same lock and scans no
--   row. All are held to this transaction's end; the migrator's session bounds the wait with lock_timeout and fails rather
--   than queues. Expand-only: the previous release neither reads nor writes the column, and its inserts take the default,
--   which is what it writes.
--
-- WHAT IT DOES NOT TOUCH
--   No existing row, ciphertext, trigger or grant. It moves no money.

ALTER TABLE payments.provider_evidence
    ADD COLUMN cipher_format smallint NOT NULL DEFAULT 1;

ALTER TABLE payments.provider_evidence
    ADD CONSTRAINT provider_evidence_cipher_format_is_known CHECK (cipher_format IN (1, 2)) NOT VALID;

COMMENT ON COLUMN payments.provider_evidence.cipher_format IS
    'Whether content_ciphertext is bound to this row (X-TSK-010): 1 unbound, written before X-TSK-010 or while bound writes are off; 2 bound to the row''s id, its six subjects and its kind as AES-GCM associated data.';

ALTER TABLE payments.dispute_evidence
    ADD COLUMN cipher_format smallint NOT NULL DEFAULT 1;

ALTER TABLE payments.dispute_evidence
    ADD CONSTRAINT dispute_evidence_cipher_format_is_known CHECK (cipher_format IN (1, 2)) NOT VALID;

COMMENT ON COLUMN payments.dispute_evidence.cipher_format IS
    'Whether content_ciphertext is bound to this row (X-TSK-010): 1 unbound, written before X-TSK-010 or while bound writes are off; 2 bound to the row''s id, its dispute and its kind as AES-GCM associated data.';
