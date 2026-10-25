-- The monitoring rules' counterparty read (P13-TSK-020; PHASE_13_PLAN.md section 12.7; ADR-0111 decision 3;
-- DESIGN-020 F3, D12; INV-RSK-09).
--
-- WHY AN INDEX IS MISSING (found by the design, not assumed)
--   A monitored transaction is born once per journal entry with ONE subject: the first customer line's account
--   (P13-TSK-019's MonitoringClassification). In an internal transfer the receiving customer's account is therefore
--   only the row's counterparty (counterparty_kind = 'CUSTOMER', counterparty_ref = its customer account). The
--   monitoring rules judge a PARTY over all its accounts, its inflows from other customers among them - so a party's
--   movements are the rows whose subject is one of its accounts (V004's monitored_transaction_by_subject) AND the rows
--   whose customer counterparty is one of them, read here by (counterparty_ref, value_date) for a window of value dates
--   and for the last movement before a window. Without it each evaluation scans the table.
--
-- Partial over CUSTOMER counterparties: no other kind's reference is a customer account, and only these are read.
-- No table, column, constraint, function, trigger or grant is created; the reads use V004's SELECT. The migration names
-- no other schema.

CREATE INDEX monitored_transaction_by_counterparty
    ON risk.monitored_transaction (counterparty_ref, value_date)
    WHERE counterparty_kind = 'CUSTOMER';
