-- The outbound credit's destination reference is never a bank identifier (the Phase 9 -> 10 transition, 2026-10-07;
-- INV-RAIL-03 at DB-CONSTRAINT rank).
--
-- V025 gave payments.outbound_credit.destination_reference the provider-reference charset only
-- (outbound_credit_destination_is_opaque), while the wallet withdrawal's destination carried the V003 trio since V016:
-- the charset, no letterless value, and no international-identifier shape within an IBAN's own 34-character bound. The
-- outbound credit copies the beneficiary's provider-attested reference, which crossborder screens (crossborder V003 and
-- Beneficiaries.exchange) - but the table that sends money took any writer's word. The two missing CHECKs, verbatim
-- from V016, close it for every writer.
ALTER TABLE payments.outbound_credit
    ADD CONSTRAINT outbound_credit_destination_has_a_letter
        CHECK (destination_reference !~ '^[0-9_.:-]+$'),
    ADD CONSTRAINT outbound_credit_destination_is_not_an_account_identifier
        CHECK (NOT (char_length(destination_reference) <= 34
                AND destination_reference ~ '^[A-Za-z]{2}[0-9]{2}[A-Za-z0-9]{1,30}$'));
