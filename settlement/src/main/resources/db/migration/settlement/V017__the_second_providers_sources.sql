-- The second providers' settlement sources (P9-TSK-026, M9.8; ADR-0082, PHASE_9_PLAN.md section 12.9.2;
-- INV-SET-05).
--
-- THE SOURCE ROWS
--   fx-sim-b.trade-report (FX_PROVIDER_REPORT, the SIM_FX_CSV format) and corridor-sim-b.settlement
--   (PAYOUT_PROVIDER_REPORT, the SIM_CORRIDOR_CSV format), seeded like V015's and V016's (hand-minted UUIDv7
--   literals stamped 2026-09-27T12:00:00Z, below the seed ceiling). Each one's position, counterparty and
--   settled currencies are compiled facts on its descriptor, read off its provider's declaration in app -
--   never columns here (ADR-0064) - so each discharges only its own counterparty's position: one source per
--   (purpose, counterparty), never netted with its sibling. No vocabulary changes: both reuse their formats.

INSERT INTO settlement.source (id, code, kind, status, next_sequence) VALUES
    ('01a0e2bc-8200-7005-8000-000000000007', 'fx-sim-b.trade-report', 'FX_PROVIDER_REPORT', 'ACTIVE', 1),
    ('01a0e2bc-8200-7005-8000-000000000008', 'corridor-sim-b.settlement', 'PAYOUT_PROVIDER_REPORT', 'ACTIVE', 1);
