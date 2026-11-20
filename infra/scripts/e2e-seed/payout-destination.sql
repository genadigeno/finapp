-- TEST-ONLY (P17-TSK-115). A PROPOSED payout destination of :'merchant', proposed by :'proposer' - the seed's own merchant
-- administrator - so a different administrator, the console's, approves it (merchant V006: approver <> proposer, at the database).
-- The proposal door exchanges the operator's grant at the payout destination tokenisation provider
-- (FINAPP_MERCHANT_PAYOUTDESTINATION_TOKENISATION_URL), which no test stack configures - a local stack has no provider, and kind's
-- simulator serves the path but the rehearsal names no such URL - so the door answers that the exchange is unavailable and this
-- writes what it would have: the provider's opaque reference (:'reference', a fresh token, never bank data) and its displayable
-- suffix. Applied by infra/scripts/e2e-seed.sh with psql's variables - each quoted as a literal by psql itself after the shell checked
-- its shape - and proven by app's E2eSeedMerchantDatabaseTest: the approval door refuses its proposer and admits another.
--
-- It writes merchant's own row as the proposal leaves it, minus its audit record and its event: V006's checks and its one-open index
-- hold every value.
INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference, display_suffix, status, proposed_by, proposed_at,
    proposal_reason)
VALUES (uuidv7(), :'merchant'::uuid, :'reference', '3000', 'PROPOSED', :'proposer', clock_timestamp(),
    'e2e seed: a destination proposed for the console to approve')
RETURNING id;
