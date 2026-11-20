-- TEST-ONLY (P17-TSK-115). A KYB-verified organisation: an ORGANISATION party and its live customer, ACTIVE - what onboarding a
-- merchant asks of its party (app's VerifiedMerchantOrganisation: an organisation whose live customer is ACTIVE, INV-KYC-05's
-- projection of the KYB decision). No door reaches that verdict on a test stack: a case's checks go to a verification provider,
-- and neither a local stack nor kind has one, so the kind rehearsal plants the same two rows. The console onboards the party, or the
-- seed's own merchant administrator does, through the onboarding door. Applied by infra/scripts/e2e-seed.sh, and proven by app's
-- E2eSeedMerchantDatabaseTest: the onboarding door admits what it wrote.
--
-- It writes party's own rows as the KYB decision leaves them, minus the case: party V002's checks hold every value; it takes none.
WITH registered AS (
    INSERT INTO party.party (id, kind, display_name, registered_at)
    VALUES (uuidv7(), 'ORGANISATION', 'E2E seed organisation', clock_timestamp())
    RETURNING id, registered_at)
INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)
SELECT uuidv7(), id, 'ACTIVE', registered_at, registered_at FROM registered
RETURNING party_id;
