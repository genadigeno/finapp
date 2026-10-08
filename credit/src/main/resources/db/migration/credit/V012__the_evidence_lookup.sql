-- The evidence lookup (P10-TSK-017; ADR-0085 point 7, G9; INV-AUD-01).
--
-- The investigator's evidence read starts from a credit record - the row an explanation names - and reaches its
-- evidence through credit.read_evidence(evidence_id, reason), the one way the application touches a ciphertext. To
-- name the evidence row the application may now read its identity and flags - never its content: a column-level
-- SELECT on the columns that say which attempt delivered what, while content_ciphertext, content_nonce, the checksum
-- and the length stay unreadable to it, as V004 decided.

GRANT SELECT (id, data_request_id, attempt, duplicate, consent_withdrawn) ON credit.credit_evidence TO finapp_app;
