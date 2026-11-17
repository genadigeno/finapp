-- Document content and provider evidence bound to their rows (X-TSK-010; THREAT_MODEL.md T35; INV-HIST-02, INV-KYC-06).
-- finapp-contract: first-conforming-release=ANY; waives=CHECK; reason=no release before this one names cipher_format, so its inserts take the default 1, which each CHECK admits; this release writes only 1 or 2
--
-- WHY
--   DocumentCipher bound no associated data: a ciphertext the key genuinely wrote - a passport, or a provider's answer -
--   copied into another row decrypted cleanly there, and only the checksum held beside it could notice, and not even that
--   when the whole row was copied onto another case or check. A write now names the row as AES-GCM associated data - a
--   document's id, case and document type; an evidence row's id and check - so a ciphertext moved to another row fails to
--   decrypt. Every ciphertext written before this one bound nothing, and stays readable: the read follows the format stored
--   with the row.
--
-- WHAT IT ADDS, AND ONLY THAT
--   One column on each of kyc.kyc_document and kyc.verification_evidence, cipher_format: 1, unbound - every existing row, by
--   the default, and every row written while finapp.ciphers.bind-associated-data is off - or 2, bound. A bound ciphertext
--   read as unbound fails its tag exactly as one under the wrong row does, so the column is no downgrade path. Each table's
--   insert grant is the table's (V003, V004: GRANT SELECT, INSERT ON the table), not a column list, and covers it.
--   Each CHECK is added NOT VALID: new writes are judged at once, and the old rows, which all hold the default 1, are left
--   to a later release's VALIDATE CONSTRAINT (DATA_MIGRATIONS.md section 4.1), so this file scans no row.
--
-- LOCKS
--   ADD COLUMN with a constant default takes ACCESS EXCLUSIVE for its moment and rewrites nothing (PostgreSQL 11 onward), and
--   a NOT VALID CHECK scans nothing; no row trigger fires, and neither table has one. Expand-only: the previous release
--   neither reads nor writes the column, and its inserts take the default, which is what it writes.
--
-- WHAT IT DOES NOT TOUCH
--   No existing row, ciphertext, trigger or grant. It moves no money.

ALTER TABLE kyc.kyc_document
    ADD COLUMN cipher_format smallint NOT NULL DEFAULT 1;

ALTER TABLE kyc.kyc_document
    ADD CONSTRAINT kyc_document_cipher_format_is_known CHECK (cipher_format IN (1, 2)) NOT VALID;

COMMENT ON COLUMN kyc.kyc_document.cipher_format IS
    'Whether content_ciphertext is bound to this row (X-TSK-010): 1 unbound, written before X-TSK-010 or while bound writes are off; 2 bound to the row''s id, case and document type as AES-GCM associated data.';

ALTER TABLE kyc.verification_evidence
    ADD COLUMN cipher_format smallint NOT NULL DEFAULT 1;

ALTER TABLE kyc.verification_evidence
    ADD CONSTRAINT verification_evidence_cipher_format_is_known CHECK (cipher_format IN (1, 2)) NOT VALID;

COMMENT ON COLUMN kyc.verification_evidence.cipher_format IS
    'Whether content_ciphertext is bound to this row (X-TSK-010): 1 unbound, written before X-TSK-010 or while bound writes are off; 2 bound to the row''s id and check as AES-GCM associated data.';
