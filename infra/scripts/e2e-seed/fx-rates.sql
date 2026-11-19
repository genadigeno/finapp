-- TEST-ONLY (P17-TSK-112). A month's end as the simulated reference feed would have observed it: the four pairs a valuation rate
-- set values (EUR against GBP, USD, JPY and BHD - every supported currency but the functional one), each observed at :'observed',
-- the day before the month's end, inside the set's 96-hour bound. A local stack has no reference feed (finapp.fx.reference.url is
-- unset), so a past month's rates exist nowhere else; this is the one way to capture its set. Applied by
-- infra/scripts/e2e-seed.sh with psql's variable :'observed', quoted as a literal by psql itself after the shell checked its shape,
-- and proven unchanged by app's E2eSeedFinanceDatabaseTest: the set it values is captured, approved and closes its month.
--
-- fx V002's trigger drops, silently, an observation not newer than the pair's latest: on a stack whose feed has observed anything
-- later this writes nothing, and the shell, counting the four rows it returns, refuses rather than going on without rates. The
-- rates are round test values, never a market's.
INSERT INTO fx.rate_snapshot (id, source, base_currency, quote_currency, rate, observed_at)
SELECT uuidv7(), 'simulated-reference', 'EUR', pair.quote, pair.rate, :'observed'::timestamptz
FROM (VALUES ('GBP', 0.8600000000), ('USD', 1.0800000000), ('JPY', 162.0000000000), ('BHD', 0.4070000000)) AS pair (quote, rate)
RETURNING quote_currency;
