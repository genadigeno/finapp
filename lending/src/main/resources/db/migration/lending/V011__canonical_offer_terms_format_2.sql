-- Canonical offer terms format 2 (P11-TSK-031; PHASE_11_PLAN.md section 12.1, ADR-0092 Follow-up; INV-LND-05,
-- INV-HIST-04).
--
-- The defect: lending V003 and LoanTerms admit a servicing zone of UTC or a fixed offset +HH:MM / -HH:MM, but format 1
-- of the canonical offer terms (CanonicalOfferTerms, lending V005 and V006) spells every token in [A-Za-z0-9_.:-] -
-- no '+'. A terms version whose zone lies east of UTC could be proposed and four-eyes activated, and then every offer
-- under it failed at rendering: origination for the product stopped until another version was activated.
--
-- The fix is a format, not a narrower domain (decided with the owner, 2026-10-12): format 2 is format 1 with '+' added
-- to the token set and nothing else changed. Every row already stored is format 1 and stays so - its bytes and hash are
-- immutable (INV-LND-05) and CanonicalOfferTerms re-renders them byte for byte for replay (P11-TSK-029). New offers are
-- format 2; an agreement copies its offer's format with its bytes (lending V006's trigger already compares the two).
--
-- Each table's canonical-shape CHECK is replaced, for EVERY writer, by:
--     the column IS the bytes' own first token  - {"format":"<canonical_format>", - so a stored format cannot disagree
--                                                 with the bytes it describes;
--     format 1                                  - exactly V005/V006's character set (every existing row passes);
--     format 2                                  - that set and '+';
--     any other format                          - refused.
-- The hash CHECKs (terms_sha256 = sha256(canonical_terms)) are untouched.
--
-- Migration number: P11-TSK-015 holds lending V009 and P11-TSK-016 V010 on their branches; this merges after both
-- (outOfOrder is false - a lower number arriving after V011 on a database would fail validation).

ALTER TABLE lending.loan_offer_terms DROP CONSTRAINT loan_offer_terms_canonical_shape;

ALTER TABLE lending.loan_offer_terms ADD CONSTRAINT loan_offer_terms_canonical_shape CHECK (
    char_length(canonical_terms) BETWEEN 2 AND 16384
    AND starts_with(canonical_terms, '{"format":"' || canonical_format || '",')
    AND ((canonical_format = 1 AND canonical_terms ~ '^\{[A-Za-z0-9_.:",{}\[\]-]*\}$')
        OR (canonical_format = 2 AND canonical_terms ~ '^\{[A-Za-z0-9_.:+",{}\[\]-]*\}$')));

ALTER TABLE lending.loan_agreement DROP CONSTRAINT loan_agreement_canonical_shape;

ALTER TABLE lending.loan_agreement ADD CONSTRAINT loan_agreement_canonical_shape CHECK (
    char_length(canonical_terms) BETWEEN 2 AND 16384
    AND starts_with(canonical_terms, '{"format":"' || canonical_format || '",')
    AND ((canonical_format = 1 AND canonical_terms ~ '^\{[A-Za-z0-9_.:",{}\[\]-]*\}$')
        OR (canonical_format = 2 AND canonical_terms ~ '^\{[A-Za-z0-9_.:+",{}\[\]-]*\}$')));

COMMENT ON TABLE lending.loan_offer_terms IS
    'A loan offer''s terms (P11-TSK-011, A20, A5): exactly the approved principal or limit and term, the pinned terms version''s rate, engines and template, the loan''s illustrative schedule figures or the line''s minimum-payment rule, the canonical terms (format 1, or format 2 from P11-TSK-031 - format 1 with ''+'' in a token) with their SHA-256 - held to the bytes by CHECK, the format to the bytes'' own first token - and the SHA-256 of the offer document shown. Born once beside an OFFERED offer, INSERT-only and immutable for every role (INV-LND-05): it carries money (RESTRICTED-FINANCIAL). The agreement (P11-TSK-012) copies the canonical terms, their format and their hash.';

COMMENT ON TABLE lending.loan_agreement IS
    'A loan agreement''s versions (P11-TSK-012, ADR-0092 point 5, INV-LND-05): version 1 born in the acceptance as an exact copy of its offer''s terms - the canonical terms (in the offer''s format, 1 or 2) and their SHA-256, held to the bytes by CHECK, with the principal or limit, term, rate, day, engines and template beside them; later versions are amendments'' (P11-TSK-027). INSERT-only and immutable for every role: it carries money (RESTRICTED-FINANCIAL). Every servicing row will name the version it was computed under.';
