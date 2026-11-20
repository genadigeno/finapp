-- TEST-ONLY (P17-TSK-115). The KYC verdict for :'identity', a test identity the seed registered: its party's live customer, born
-- PENDING at registration, made ACTIVE as an approved case's decision makes it (app's DecisionRecording; INV-KYC-05). A card top-up
-- needs it: the payment credits the payer's own wallet, which only an ACTIVE customer opens. No door reaches the verdict on a test
-- stack - a case's checks go to a verification provider, and neither a local stack nor kind has one - so the kind rehearsal makes
-- its customers ACTIVE the same way. THE ONE PLANT THAT CHANGES A ROW (E2eSeedStaysTestOnlyTest names it): one status, of one
-- PENDING customer, along party's own machine edge, and nothing else. Applied by infra/scripts/e2e-seed.sh with psql's variable,
-- quoted as a literal by psql itself after the shell checked its shape, and proven by app's E2eSeedMerchantDatabaseTest: the
-- customer then opens a wallet through the accounts door.
UPDATE party.customer c
   SET status = 'ACTIVE', status_changed_at = GREATEST(clock_timestamp(), c.opened_at)
  FROM identity.identity i
 WHERE i.id = :'identity'::uuid AND c.party_id = i.party_id AND c.status = 'PENDING'
RETURNING c.id;
