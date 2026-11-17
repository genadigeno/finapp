-- FX provider evidence bound to its row (X-TSK-010; THREAT_MODEL.md T35; INV-HIST-02).
-- finapp-contract: first-conforming-release=ANY; waives=CHECK; reason=no release before this one names cipher_format, so its inserts take the default 1, which the CHECK admits; this release writes only 1 or 2
--
-- WHY
--   FxEvidenceCipher bound no associated data: a ciphertext the key genuinely wrote, copied into another row, decrypted
--   cleanly there, and only the checksum held beside it could notice. A write now names the row - its id, provider,
--   client reference and kind - as AES-GCM associated data, so a ciphertext moved to another row fails to decrypt. Every
--   ciphertext written before this one bound nothing, and stays readable: the read follows the format stored with the row.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One column, cipher_format: 1, unbound - every existing row, by the default, and every row written while
--   finapp.ciphers.bind-associated-data is off - or 2, bound. A bound ciphertext read as unbound fails its tag exactly as one
--   under the wrong row does, so the column is no downgrade path. The insert grant is the table's and covers it.
--   Its CHECK, fx_provider_evidence_cipher_format_is_known, is added NOT VALID: every existing row holds the default, which it
--   admits, and every insert from now on is judged; the old rows are left to a later release's VALIDATE (DATA_MIGRATIONS.md
--   section 4.1), as the tightening rule asks of a constraint over a table an earlier release wrote.
--
-- LOCKS
--   ADD COLUMN with a constant default takes ACCESS EXCLUSIVE for its moment and rewrites nothing (PostgreSQL 11 onward); no
--   row trigger fires. ADD CONSTRAINT ... NOT VALID takes the same lock and scans no row. Both are held to this transaction's
--   end; the migrator's session bounds the wait with lock_timeout and fails rather than queues. Expand-only: the previous
--   release neither reads nor writes the column, and its inserts take the default, which is what it writes.
--
-- WHAT IT DOES NOT TOUCH
--   No existing row, ciphertext, trigger or grant. It moves no money.

ALTER TABLE fx.fx_provider_evidence
    ADD COLUMN cipher_format smallint NOT NULL DEFAULT 1;

ALTER TABLE fx.fx_provider_evidence
    ADD CONSTRAINT fx_provider_evidence_cipher_format_is_known CHECK (cipher_format IN (1, 2)) NOT VALID;

COMMENT ON COLUMN fx.fx_provider_evidence.cipher_format IS
    'Whether content_ciphertext is bound to this row (X-TSK-010): 1 unbound, written before X-TSK-010 or while bound writes are off; 2 bound to the row''s id, provider, client reference and kind as AES-GCM associated data.';
