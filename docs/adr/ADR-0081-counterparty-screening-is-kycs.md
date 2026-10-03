# ADR-0081 — Counterparty screening is kyc's: every outcome is a recorded decision, an unverified payee always meets a person, and unavailable means unpayable

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: KYC · Cross-Border · Identity · Security
Supersedes: nothing. Extends ADR-0035 (kyc owns the verification decision) and ADR-0038 (a
provider verdict is evidence; the decision is ours) from onboarding to transaction-time
counterparty screening. Extends `INV-KYC-01` and `INV-KYC-04` to counterparty screenings.
Settles D24 and owner decision O4.

## Context

A cross-border payment introduces a party the platform has never onboarded: the beneficiary.
Sanctions obligations attach to paying one, so the beneficiary must be screened — but every
property of onboarding screening that ADR-0035 and ADR-0038 settled recurs here with the signs
changed:

1. **The beneficiary cannot consent, appear, or complete a case.** They are not a customer.
   The subject is a name, a country and an entity type handed over by the registering
   customer and attested (in part) by the corridor provider.
2. **A review takes hours; a rate lock lasts 60 seconds.** If compliance review were a
   payment state, every held review would sit on a locked rate the platform is covering —
   platform market risk growing with the review queue (the reason the brief's
   `COMPLIANCE_REVIEW` payment state is refused, ADR-0079).
3. **The payee check changes what a `CLEAR` means.** The corridor provider's beneficiary
   exchange answers whether the typed name matches the account (`MATCH` | `NO_MATCH` |
   `UNAVAILABLE`). A screening provider's `CLEAR` on a name that could not be tied to the
   account says nothing about the real recipient: the customer may have typed a clean name
   over a sanctioned account.
4. **Screening the same subject twice must not produce two authorities.** `crossborder` needs
   the answer; `kyc` owns screening, its adapter, its credential and its reviewer population
   (`INV-KYC-05`). A second screening client in `crossborder` would duplicate the credential,
   the evidence regime and the review door.
5. **Tipping off.** Nothing customer-facing may reveal that a beneficiary is under review or
   blocked.

## Decision

1. **`kyc` owns counterparty screening** (D24, `INV-KYC-05`). A new aggregate,
   `kyc.counterparty_screening`, on a new port `CounterpartyScreeningProvider` implemented by
   the **existing** `ScreeningAdapter` and credential, with a counterparty subject
   (`name, country, entityType` — **no bank identifier**) beside its case-bound one. The name
   is stored **only in kyc**, encrypted with AAD bound to the screening id (RESTRICTED-PII,
   kyc's evidence regime); it transits `crossborder` for the exchange and the screening call
   and is never stored there (the needle test asserts it absent from every non-kyc column,
   log, event, audit body and response).

2. **Every outcome is a recorded decision, never the provider's verdict alone** (`INV-KYC-01`
   extended, ADR-0038). The machine: `REQUESTED → CLEAR | IN_REVIEW | UNAVAILABLE`;
   `UNAVAILABLE → CLEAR | IN_REVIEW` (retry); `IN_REVIEW → RELEASED | BLOCKED` (a person,
   with a reason). Every outcome — `CLEAR` included — records `decision_basis`
   (`AUTOMATIC` | `REVIEWER`), the kyc `policy_version` and `decided_at`, with the onboarding
   `CHECK` reused (`REVIEWER` ⇔ a deciding person, a reason required) and one new `CHECK`:
   **an `AUTOMATIC` `CLEAR` exists only with a payee `MATCH`** (the stored payee verdict is
   part of the decision basis).

3. **The hold sits on the beneficiary, before pricing** (O4). Screening runs synchronously at
   registration, and again at cross-border quote time when the clearance is older than the
   corridor's `screening_validity` (7 days, O7) — judged in-lock in the quote's first
   transaction, so a lapsed clearance refuses before any provider call. Funds are
   instructed, and offers priced, only for a beneficiary that is `ACTIVE` with a current
   `CLEAR` or person-`RELEASED` screening (`INV-XB-02`); payability is checked `FOR SHARE` at
   both doors.

4. **A hit, an indeterminate result or an unverified payee always meets a person — never
   auto-cleared, never auto-rejected** (`INV-KYC-04` extended). `HIT` or `INDETERMINATE`
   moves the screening `IN_REVIEW` and the beneficiary with it (T-e, one transaction, through
   the `ScreeningOutcomeListener` port). **An unverified payee is handled as a hit**: when the
   payee check is `NO_MATCH` or `UNAVAILABLE`, the provider's `CLEAR` is retained as
   evidence, but the screening goes `IN_REVIEW`, reason `PAYEE_UNVERIFIED`, at registration
   and at every re-screen. The customer's acknowledgement of a non-matching payee guards
   against misdirection; **it never stands in for screening**. A person holding
   `COUNTERPARTY_SCREENING_REVIEW` (granted to `KYC_REVIEWER`, the onboarding-hit population)
   releases or blocks, with a reason code and narrative. Four-eyes clearance is recorded as a
   Phase 13 policy option, not taken here.

5. **Fail safe: unavailable means unpayable, and nothing is held.** Screening `UNAVAILABLE`
   leaves the beneficiary unpayable (`PENDING_VERIFICATION` to the customer), retried by
   `CounterpartyScreeningRetrySchedule`; a quote needing a re-screen answers
   `503 crossborder.ScreeningUnavailable`, with nothing priced and nothing held.

6. **Revocation works from every state, and reveals nothing.** The customer can revoke a
   beneficiary from `PENDING_SCREENING`, `IN_REVIEW`, `BLOCKED` or `ACTIVE`, with one
   byte-identical response; the kyc screening and any review run on, their outcome recorded
   in kyc only (the listener is a no-op on a `REVOKED` beneficiary). Quotes and payments
   answer the generic `crossborder.BeneficiaryNotPayable`, byte-identical across
   `PENDING_SCREENING`, `IN_REVIEW`, `BLOCKED` and `REVOKED`; the shaped statuses are
   ADR-0080's. Tests compare the responses byte for byte across screening, review, block and
   revocation at every door.

7. **No new consent purpose.** Counterparty screening rests on the platform's **legal
   obligation** (a non-customer beneficiary cannot consent); the originator minimum sent to
   the corridor provider (display name + opaque customer reference) is the minimum needed to
   execute the customer's own instruction (**contract**). Both bases and their egress are
   recorded in `DATA_CLASSIFICATION.md` and here. Consent never stands in for authorization
   (`INV-IDN-04`).

8. **The Phase 13 seams are reserved now, as required parameters.** `CrossBorderLimitCheck`
   and `CrossBorderRiskDecision` (`PermitAllUntilPhase13`, reserved codes
   `422 crossborder.LimitRefused` / `422 crossborder.RiskRefused`) are consulted in-lock in
   the authorization's first transaction, risk **before** the quote is accepted so `REFUSE`
   consumes nothing. Velocity limits, scoring, monitoring, KYC tiers and residence attributes
   are Phase 13's, each a recorded debt row with its trigger.

## Alternatives Considered

### Screen in `crossborder`, with its own adapter and credential
Pros:
- No cross-module port; the module that needs the answer owns the call.

Cons:
- Two screening authorities (`INV-KYC-05` broken), two credentials for one provider, two
  evidence regimes, and a reviewer population split across modules.
- kyc's decision-basis discipline (`INV-KYC-01`) would have to be duplicated or, worse,
  diverge.

kyc owns it; `crossborder` asks through a port (D24).

### A `COMPLIANCE_REVIEW` state on the payment
Pros:
- The review is visible exactly where the customer is waiting.

Cons:
- A review lasting hours cannot sit behind a 60 s rate lock: every held payment is a locked
  rate the platform is covering, so the review queue becomes an open market position.
- The payment would need a re-quote rule on release — Phase 13's transaction-level hold
  problem, recorded as its input, not solved by accident here.

Screening precedes pricing and holds the beneficiary (O4).

### Auto-clear on the screening provider's `CLEAR`, whatever the payee check said
Pros:
- Fewer reviews; the provider said clear.

Cons:
- A `CLEAR` on a name the corridor provider could not tie to the account verifies the wrong
  thing: the customer's typing, not the recipient. A clean alias over a sanctioned account
  would pass unexamined.
- ADR-0038 already decided the verdict is evidence, not the decision.

An `AUTOMATIC` `CLEAR` exists only with a payee `MATCH`, held by a `CHECK` (point 2).

### Fail open when the screening provider is down
Pros:
- Availability; registrations never block on a third party.

Cons:
- An unscreened beneficiary paid during an outage is exactly the case the obligation exists
  for, and no later re-screen can recall the money (`FINAL_ON_ACCEPTANCE`).

Unavailable means unpayable, retried, with nothing held or priced (point 5).

## Consequences

Positive:
- One screening authority, one adapter, one credential, one reviewer population — and every
  outcome, automatic or human, carries its basis, policy version and time, replayable.
- The sanctioned-account-behind-a-clean-name hole is closed structurally: no automatic
  clearance exists without the provider having tied the name to the account.
- Screening can take hours without costing the platform a locked rate.
- Revocation and refusal surfaces reveal nothing (tipping-off), proven byte for byte.

Negative:
- Every `NO_MATCH`/`UNAVAILABLE` payee goes to a person: the review queue is a real
  operational load, gauged (`finapp.kyc.counterparty.review.pending`/`.age`) and alerted
  past 24 h.
- A screening outage blocks new beneficiaries and stale-clearance quotes outright — the
  deliberate cost of fail-safe.
- Re-screening at quote time adds a provider call to the quote path whenever the clearance
  lapsed (bounded by the 7-day validity).

Operational impact: `finapp.kyc.counterparty.screening{outcome}`, the review gauges and the
retry schedule's sweeper-enabled gauge; the review door is
`POST /v1/operator/kyc/counterparty-screenings/{id}/decision` under
`COUNTERPARTY_SCREENING_REVIEW`; every decision is audited with its reason in the act's
transaction.
Security impact: the name is RESTRICTED-PII ciphertext in kyc only, AAD-bound; no bank
identifier is ever a screening subject; the reviewer permission joins the pairwise-disjoint
role model; the needle test walks the whole chain.
Financial impact: none directly — screening gates whether money may be instructed, and
touches no posting.

## Invariants / Constraints

`INV-KYC-01` (extended: a counterparty screening's provider verdict is evidence; every
outcome records basis, policy version and time), `INV-KYC-04` (extended: a hit or an
unverified payee goes to a person), `INV-KYC-05` (kyc is the only verification authority),
`INV-XB-02` (payability in-lock at both doors; the decision-basis `CHECK`s), `INV-IDN-04`
(consent never stands in for authorization), `INV-AUD-01`/`-03` (the decision audited in its
own transaction, with a reason), `INV-CON-01`/`-02` (screening answers and reviews meet on
row conditionals; T-e).

## Follow-up

- `P9-TSK-016` builds the aggregate, the port over `ScreeningAdapter`, the review door, the
  retry schedule and the Phase 13 seams; `-017` wires registration, the payee verdict
  hand-over and the listener; `-018` the quote-time re-screen and its T-e refusal.
- Phase 13 owns: rescreening the book, list-change sweeps, ongoing monitoring, four-eyes
  clearance as a policy option, KYC tiers, residence, velocity limits and risk scoring —
  each recorded with its trigger.
- The Phase 9 review (`P9-DOC-001`) reads this ADR against the code before accepting it.
