# Roadmap

This document is the **phase sequence and sequencing rationale**.

The full per-phase engineering plan (objective, capabilities, contexts, dependencies,
architecture/data/API/event/security/observability/testing/failure work, reconciliation
implications, deliverables, exit criteria, risks, and explicit out-of-scope) lives in
[`docs/project/DELIVERY_PLAN.md`](../project/DELIVERY_PLAN.md).

Gate definitions and the phase status model live in
[`docs/project/PHASE_GATES.md`](../project/PHASE_GATES.md).

---

## Phase Sequence

| # | Phase | Primary Outcome |
|---|-------|-----------------|
| 0 | Domain and Architecture Foundation | Buildable modular-monolith skeleton, financial kernel, platform seams |
| 1 | Identity and Customer Foundation | Party/Customer, authentication, authorization, session, audit |
| 2 | KYC/KYB and Consent | Verification lifecycle, screening adapters, consent lifecycle |
| 3 | Accounts and Financial Ledger | Chart of accounts, double-entry ledger, balances, holds |
| 4 | Internal Transfers | First end-to-end money movement on the ledger |
| 5 | Payment Infrastructure | Payment intent/attempt, provider adapters, auth/capture, refunds, webhooks |
| 6 | Checkout and Merchant Platform | Merchants, checkout sessions, fees, merchant payouts |
| 7 | Cards, Wallets, A2A and Instant Payments | Multi-rail abstraction, disputes/chargebacks |
| 8 | Settlement and Reconciliation | Settlement ingestion, matching, breaks, suspense, investigation |
| 9 | FX and Cross-Border Payments | Quotes, rate locks, multi-currency conversion, cross-border workflow |
| 10 | Credit Decisioning | Credit profile, bureau adapters, versioned policy, explainable decisions |
| 11 | Lending | Applications, offers, disbursement, schedules, repayment, delinquency |
| 12 | BNPL | Merchant-financed instalments, merchant settlement, refund interaction |
| 13 | Risk, Fraud and AML | Signals, rules, decisions, cases, ongoing monitoring |
| 14 | Accounting and Financial Reporting | GL mapping, trial balance, period close, reporting abstraction |
| 15 | Production Hardening | Security hardening, SLOs, runbooks, operational readiness |
| 16 | Scale, Resilience and Disaster Recovery | Load characterisation, degradation modes, backup/restore, DR |

**Current position (2026-09-04).** **Phase 0 is `COMPLETE`** — backlog 62 of 62, all twelve exit
criteria, and CI green on a runner. **Phase 1 is `READY`** and not started: planned in full in
`PHASE_1_PLAN.md`, with `ADR-0029`…`ADR-0032`, the `INV-IDN-01`…`07` invariant group, and 25
backlog items at task granularity. The first task is `P1-TSK-001`, the data-access ADR.

---

## Sequencing Rationale

The requested ordering is **retained**. Three refinements are applied, each justified below.

### Refinement 1 — Phase 0 is not a documentation phase

The original roadmap treated Phase 0 as "fintech foundations" (glossary and concepts).
That is insufficient as a gate. Financial correctness depends on primitives that are
extremely expensive to retrofit once money-moving code exists:

- monetary representation
- idempotency
- transactional outbox
- audit trail
- correlation/causation propagation
- module boundary enforcement

Phase 0 therefore delivers a **buildable, tested platform skeleton with a financial
kernel and no business capability**. This is deliberate: it is the only phase where these
primitives can be introduced without migrating existing financial history.

### Refinement 2 — Cross-phase seams instead of reordering

Several capabilities are conceptually "later" but structurally needed earlier. Rather than
reorder phases (which would collapse domain boundaries), each early phase must expose an
explicit **seam**: a named extension point with a no-op or trivially-configured default,
owned by the earlier phase and implemented by the later one.

| Seam | Introduced | Implemented |
|------|-----------|-------------|
| Limit / velocity check | Phase 4 | Phase 13 |
| Risk decision hook on money movement | Phase 4 | Phase 13 |
| Suspense account + break record | Phase 3 | Phase 8 |
| GL account mapping | Phase 3 | Phase 14 |
| Provider settlement evidence capture | Phase 5 | Phase 8 |
| Multi-currency account/posting support | Phase 3 | Phase 9 |
| Dispute/chargeback financial effect | Phase 5 | Phase 7 |

A seam is a documented interface, not a stub implementation of the later domain.
Introducing a seam does **not** authorise implementing the later phase's logic.

### Refinement 3 — Screening is split across two phases

Sanctions/PEP screening appears in both Phase 2 and Phase 13. These are different
capabilities and must not be merged:

- **Phase 2** owns *onboarding-time* screening of a party against sanctions/PEP lists as
  part of the KYC/KYB decision.
- **Phase 13** owns *ongoing* transaction monitoring, rescreening on list updates, and
  behavioural AML detection.

They share adapter infrastructure but have different owners, lifecycles and audit
obligations.

---

## Dependency Graph

```
0 ──┬──> 1 ──> 2
    │         │
    └──> 3 ────┴──> 4 ──> 5 ──┬──> 6 ──> 12
                              │
                              ├──> 7 ──> 8 ──> 14
                              │         │
                              └──> 9 ───┘
    1 ──> 10 ──> 11 ──> 12
    4,5 ──> 13
    3,4,5,8 ──> 14
    all ──> 15 ──> 16
```

Hard dependencies (a phase cannot enter its gate without these):

- 3 requires 0 (money kernel, outbox, audit)
- 4 requires 3 (ledger) and 1 (actor identity for audit)
- 5 requires 4 (internal money movement proven end to end)
- 8 requires 5 (external evidence exists to reconcile against)
- 9 requires 3 (multi-currency ledger) and 5 (payment lifecycle)
- 11 requires 3 (ledger), 10 (decisioning)
- 12 requires 6 (merchant) and 11 (lending mechanics)
- 14 requires 3, 4, 5, 8 (there must be real postings to report on)

---

## Non-Goals for the Whole Programme

- Full regulatory compliance for any specific jurisdiction. The core is
  jurisdiction-neutral; country specifics sit behind policy/configuration/adapters.
- Real connectivity to real payment networks, bureaus, or banks. All external providers
  are simulated behind adapters with contract tests and fault injection.
- Handling real cardholder data. Card data is tokenised at the boundary; the platform is
  designed to stay out of PCI DSS scope wherever possible.
- Being a production system carrying real customer funds.
