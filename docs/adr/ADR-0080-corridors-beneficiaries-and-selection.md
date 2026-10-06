# ADR-0080 — The corridor rail declares only what is true, a beneficiary lives by provider reference, and the provider is selected twice because two questions are asked

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: Cross-Border · Payments · Payment Methods · Ledger
Supersedes: nothing. Pays ADR-0059 §1's recorded seam (a second rail of one model needs
per-rail operation lookup) and ADR-0060 §2's ("the phase that makes reachability real stores
it"). Affirms ADR-0060 §6 unamended (price never varies with the rail). Restates `INV-RAIL-03`
(provider-attested beneficiary attributes are admitted; account identifiers and names still
never enter). Settles D17–D20 and the corridor half of D26.

## Context

Phase 7 made rails declarative: `RailCapabilities` is a frozen fact per rail id, routing is a
versioned, pinned, explainable policy (ADR-0060), and bank details and aliases never enter the
platform (ADR-0062, `INV-RAIL-03`). Phase 9 adds the first rail whose destination is *abroad*:
a corridor provider that tokenises a beneficiary, accepts an outbound credit irrevocably, and
settles on its own clearing position (ADR-0078).

The forces:

1. **The corridor rail is unlike the push rail.** It carries no pay-in, so no refund can ever
   execute on it; its return window and decision deadline are corridor facts no existing rail
   has; and reusing `PushRail` for it would hand it operations it must refuse.
2. **A beneficiary is not an alias.** The corridor provider issues an opaque
   `destination_reference` in exchange for the beneficiary's details; the reference is not
   portable across providers, so whichever provider tokenised the beneficiary is the only one
   that can pay it.
3. **Two different selection questions exist**, and conflating them (one judge's finding) either
   records the choice twice or pretends one decision answers both: *who tokenises this
   beneficiary* (asked once, at registration) and *which rail carries this payment* (asked per
   payment, and answerable only by the issuing rail).
4. **Corridors are policy data.** (source currency, destination currency, destination country),
   ordered candidate rails, fee schedule, per-payment maximum, screening validity and a
   required-data flag — versioned, and activated by two people (D26), because a corridor
   decides where customer money can go and at what fee.
5. **ADR-0060's own decisions bind.** Routing stays pinned and explainable; §6's rule that the
   price never varies with the rail must survive a world where corridors have fees; and the
   routing policy's activation door is ADR-0060's single-person operator door, by that ADR's
   own decision.

## Decision

1. **The corridor rail declares only what is true, within one new enum value** (D17). Its
   `RailCapabilities`: `PUSH`; `FINAL_ON_ACCEPTANCE`; reversals `{}`; **`RefundMode.NONE`**
   (new: the rail carries no pay-in, so no refund executes on it); `DEFERRED_VIA_CLEARING`;
   `outcomeDeadline` 10 min; disputes `NONE`; currencies `{USD, JPY, BHD}`; per-currency
   maxima at each currency's scale; `clearingPurpose CORRIDOR_CLEARING`. The corridor-specific
   facts — coverage `{(country, currency)}`, return window 30 d, decision deadline 4 h,
   delivery estimate, charge bearer `OUR` — live in the rail's own **`CorridorDeclaration`**,
   so `RailCapabilities` keeps its shape and **existing declarations are unchanged**. A new
   coherence rule holds platform-wide: `NONE` ⇔ `PUSH` and no pay-in, and routing refuses a
   `PAY_IN` input on a `NONE` rail with the new `RoutingRejection.DIRECTION_UNSUPPORTED`.
   `RailMoneySemanticsArePinnedTest` freezes the tuple; the `CLEARING_POSITIONS` rule is
   amended so a counterparty-owned purpose may be named by several declaring adapters, each
   with its own counterparty code (ADR-0078).

2. **Rail operations are a directory, not a wired singleton** (pays ADR-0059 §1). `app`
   composes `RailOperations`: `RailId → PushRail` for the instant rail, `RailId → CorridorRail`
   for corridor rails. `Withdrawals`' wired-rail guard and `PaymentConfirmation`'s single
   `Optional<PushRail>` become lookups, and each **refuses, with nothing sent**, a routed rail
   that lacks the operation it needs. A planted routing rule naming the corridor rail for a
   withdrawal is refused, by test.

3. **A beneficiary is held by provider reference, with provider-attested attributes, and no
   name or account identifier stored outside kyc** (`INV-RAIL-03` restated). Registration
   exchanges the customer's input for the provider's opaque `destination_reference` through a
   single-use grant (`CorridorDirectory`, no connection held), and stores: the reference, the
   4-char suffix, the payee check (`MATCH` | `NO_MATCH` | `UNAVAILABLE` — a close match or any
   unmapped answer maps to `NO_MATCH`, never `MATCH`), the customer's acknowledgement when the
   check is not `MATCH`, the provider-attested `destination_country`, `destination_currency`
   and entity type, the issuing rail (frozen), and the customer's nickname (screened for PAN
   and IBAN shapes). The beneficiary's name transits to kyc for screening and is stored only
   there, encrypted (ADR-0081). The machine:
   `PENDING_SCREENING → ACTIVE | IN_REVIEW`; `IN_REVIEW → ACTIVE | BLOCKED`;
   `ACTIVE → IN_REVIEW` (a re-screen hit or an unverified payee); and **revocation by the
   customer from every non-terminal state, answering one identical response** — `REVOKED` is
   terminal, a later screening outcome leaves it `REVOKED`, and re-registration is a new
   beneficiary with a new screening. Customer-facing statuses are shaped for tipping-off
   (`PENDING_VERIFICATION` covers screening and review; `UNAVAILABLE` stands for `BLOCKED`),
   and quotes for and payments to a non-payable beneficiary answer the byte-identical
   `crossborder.BeneficiaryNotPayable` whatever the reason.

4. **Corridor policy is versioned and four-eyes, with no seed exemption** (D26).
   `crossborder.corridor` rows live under a `corridor_policy_version` with the
   `PROPOSED → ACTIVE | REJECTED` machine, `ACTIVE → RETIRED` only beside a successor, the
   four-eyes `CHECK`, and **no migration-seeded version**: v1 itself is activated by two
   people (O7 holds the defaults: EUR→USD/US, EUR→JPY/JP, USD→BHD/BH, GBP→USD/US; transfer
   fees EUR 2.50 / GBP 2.00 / USD 3.00 + 0 bps; maxima USD 10,000.00 / JPY 1,500,000 /
   BHD 4,000.000; screening validity 7 days). Availability is asymmetric: disabling a corridor
   takes one person and a reason, written at once; enabling goes through the
   `corridor_enable_request` proposal, four-eyes. A corridor whose policy needs data the
   platform does not hold (the `required_data` flag) cannot be activated.

5. **The provider is selected twice, each answering a different question** (D20).
   - **(a) Tokenisation, at registration.** `crossborder.corridor_selection` records the
     corridor policy version, the inputs (country, currency, entity type), every candidate
     rail in policy order with its rejection reason (`NOT_DECLARED`, `CURRENCY_UNSUPPORTED`,
     `NO_COVERAGE`, `UNAVAILABLE`, `UNDECLARED_BY_BUILD`) or `CHOSEN`, and the declaration
     version judged. Recomputing the pinned version over the stored inputs reproduces it —
     `INV-RAIL-02`'s determinism test at a new subject.
   - **(b) Carriage, per payment.** `payments` routes every outbound credit through ADR-0060
     as routing's **third subject** (intent XOR withdrawal XOR outbound credit).
     `RoutingInputs` gains `Optional<CountryCode> destinationCountry`, and reachability
     becomes **per-candidate** (`ANY` | `only(Set<RailId>)` | not applicable), stored on the
     decision and its steps; old decisions recompute unchanged as `ANY`. Routing policy v5
     adds one rule — `PAY_OUT`, `BANK_ACCOUNT`, destination country present → the corridor
     rails in order — ahead of the domestic rule, on a **new rule matcher**
     (`requires_destination_country`, default `false`, so every old version recomputes
     unchanged). Only the beneficiary's issuing rail is reachable, so a re-route is
     impossible, and an ineligible issuing rail refuses the payment before Tx1 commits
     anything but the claim's failed outcome.
   - **Routing policy v5 is activated through ADR-0060's existing single-person operator
     door, by that ADR's own decision** — one actor, a reason, effective forward, audited,
     never a migration. Raising routing activation to four-eyes would be a superseding ADR,
     recorded as an owner question, not taken here. The single-person act cannot redirect
     money to a rail the customer was not priced on, because price never varies with the rail
     (point 6) and only the issuing rail is reachable.

6. **Fees are per corridor, never per rail** (ADR-0060 §6 affirmed unamended). The transfer
   fee is the corridor's (fixed `Money` + `Margin` × the customer source amount, a named
   rounding, computed once at quote time on `Sc` for a destination-fixed offer, frozen on the
   offer); the margin is the pricing policy's (ADR-0075). Nothing in routing reads a price,
   and no rail choice can change what the customer pays.

7. **Capability discovery is the declarations, read, not probed.** `GET /v1/me/fx/pairs`
   (enabled pairs with bounds) and `GET /v1/me/cross-border/corridors` (active, available
   corridors whose rail is declared by the running build and covers the destination). A live
   capability probe is deferred.

## Alternatives Considered

### Reuse `PushRail` for the corridor
Pros:
- No new port; the dispatch machinery is shared anyway.

Cons:
- The corridor needs `exchangeBeneficiary` and `recall`, which the push rail must not have,
  and must not have `RETURN_PAYMENT`-style refund operations, which the push rail does.
- The port's contract would be a union type where every implementation refuses half of it —
  the shape ADR-0059 §1 warned about.

A separate `CorridorRail` port, and the `RailOperations` directory for both (point 2).

### Declare `RETURN_PAYMENT` "never exercised", plus a new finality and `returnWindow` on `RailCapabilities`
Pros:
- One declaration record for everything.

Cons:
- The declaration would say things that are not true of the rail (a refund mode it cannot
  execute), and `RailMoneySemanticsArePinnedTest` would freeze the lie.
- Widening `RailCapabilities` forces every existing rail to answer questions that do not
  apply to it.

One truthful new enum value (`RefundMode.NONE`) and a corridor-owned `CorridorDeclaration`
(D17).

### One selection decision, recorded at registration and reused for carriage
Pros:
- Simpler: the beneficiary's rail is the payment's rail, so why decide twice?

Cons:
- Routing would no longer be ADR-0060's one explainable door for every outbound money
  movement; the outbound credit would be the one payment subject with no pinned routing
  decision, no policy version and no stored rejection steps.
- The reachability fact ("only the issuing rail") would be implicit instead of stored, which
  is exactly the seam ADR-0060 §2 recorded for the phase that makes reachability real.

Two decisions, each pinned and recomputable, answering two questions (D20).

### Raise routing activation to four-eyes alongside the new policies
Pros:
- Uniformity: every Phase 9 policy activation names two people.

Cons:
- ADR-0060 decided the routing door deliberately: one operator, a reason, effective forward,
  audited. Amending it inside an unrelated ADR would be a silent supersession.
- The risk four-eyes guards against — one person redirecting money — is structurally absent:
  price never varies with the rail, and only the issuing rail is reachable.

Routing v5 uses the existing door; the question is recorded for the owner (D26).

## Consequences

Positive:
- Every rail declaration remains a fact a test can freeze, and the first untruthful
  declaration the platform almost acquired (`RETURN_PAYMENT` "never exercised") was refused
  at design time.
- Two recorded seams (ADR-0059 §1, ADR-0060 §2) are paid in the phase that needed them, not
  patched around.
- A beneficiary can be registered, screened, held, paid, revoked and re-registered without
  the platform ever storing an account number or a name outside kyc's ciphertext.
- Selection and routing are both recomputable from stored rows, so "why this provider?" has
  a stored answer at both decision points.

Negative:
- A beneficiary is reachable on exactly one rail: if its issuing provider is disabled, the
  beneficiary is unpayable until re-registered with another (M9.8's overlapping coverage
  makes this real and tested).
- The corridor policy's four-eyes activation means no corridor works until two people act —
  loud by design (`rule_set.missing`'s sibling refusals), and a runbook entry.
- `RoutingInputs` and the routing tables grow a country and per-candidate reachability;
  every recompute test must prove old decisions unchanged.

Operational impact: corridor availability acts are audited one-person-disable /
four-eyes-enable; `finapp.crossborder.payment{corridor}` bounds its tag by the policy; the
corridors discovery route answers only what the running build declares.
Security impact: `INV-RAIL-03` restated, with its guard extended; the nickname and customer
reference are screened for PAN/IBAN shapes; the needle test carries a name and grant through
registration and asserts them absent from every non-kyc column, log, event and response;
step-up applies to registration.
Financial impact: none directly — the corridor fee schedule is disclosure and offer data
until completion posts it (ADR-0079); the rail's clearing position is ADR-0078's.

## Invariants / Constraints

`INV-RAIL-02` (selection pinned at registration; the outbound credit routed once, with
per-candidate reachability stored), `INV-RAIL-03` (restated: provider-attested country,
currency and entity type admitted; names only in kyc, encrypted; account identifiers never),
`INV-RAIL-04` and `INV-SET-05` (per-counterparty positions, ADR-0078), `INV-XB-02` (only an
`ACTIVE`, screened beneficiary is priced or paid), `INV-HIST-04` (corridor policy versions as
replayable subjects), `INV-AUD-04` (corridor activation names two persons; availability
disable names one and a reason), `INV-PAY-03` (provider vocabulary confined to the adapter).

## Follow-up

- `P9-TSK-014` builds the rail, its declarations, `RailOperations` and the source; `-015` the
  corridor policy, availability and the enable proposal; `-017` beneficiaries and selection;
  `-019` routing's third subject, the v5 rule and per-candidate reachability; `-026` the
  second corridor provider with overlapping coverage (cut first, O8).
- The transition annotates ADR-0059 §1 and ADR-0060 §2 as paid, and ADR-0060 §6 as affirmed
  unamended; the owner question on routing activation authority is recorded in DECISIONS.
- The live capability probe stays deferred until a provider's declared coverage proves
  unreliable.
- The Phase 9 review (`P9-DOC-001`) reads this ADR against the code before accepting it.
- *As built by `P9-TSK-014` (2026-10-05):* points 1 and 2 are implemented - `RefundMode.NONE` (coherent
  only on a push rail) and `RoutingRejection.DIRECTION_UNSUPPORTED` judged first among the refusals
  (payments `V024`); `CorridorDeclaration` beside the unchanged `RailCapabilities`, its counterparty
  named by its rail id; the `CorridorRail` port and `SimulatedCorridorAdapter` (`corridor-sim-a`, frozen
  in `RailMoneySemanticsArePinnedTest`); `RailOperations` composed in `app`, verified against the
  declared rails at startup, through which `Withdrawals` and `PaymentConfirmation` look up their routed
  rail - a planted corridor rule for a withdrawal is refused inside Tx1 with nothing written or sent.
  The `CLEARING_POSITIONS` amendment is held by `CounterpartyClearingIsNamedByDeclarationsTest`, which
  permits the corridor adapter's declaration alone to name `CORRIDOR_CLEARING`.
- *As built by `P9-TSK-015` (2026-10-05):* point 4 is implemented - crossborder `V002`'s versioned, four-eyes
  corridor policy with no seed exemption (v1 per O7 activated by two controllers, `CorridorPolicyV1` and the
  runbook), the asymmetric availability (one person disables at once; enabling is a `corridor_enable_request` a
  second person approves) keyed by the corridor's stable code across versions, and the `required_data` rule as a
  set of named data the platform must hold (`BENEFICIARY_NAME` and `ENTITY_TYPE` today), refused with every
  undeclared or non-covering rail at proposal and again at approval.
- *As built by `P9-TSK-017` (2026-10-06):* points 3 and 5(a) are implemented - crossborder `V003`'s
  `beneficiary` (provider reference, suffix, payee check and its acknowledgement, attested attributes,
  a screened nickname; the machine held by an edge trigger, `REVOKED` final), its append-only
  `beneficiary_status_event`, `beneficiary_registration` keyed by an exchange reference derived from the
  owner and the grant (the same grant converges; the provider dedupes the exchange on it), and the
  append-only `corridor_selection` with its steps, recomputable from the pinned version, the stored inputs
  and the corridors observed available. The registration is two transactions around the exchange, kyc's
  screening requested inside the second; the listener moves the beneficiary in kyc's T-e and is a no-op on
  `REVOKED`; revocation answers the same `200 {"status":"REVOKED"}` from every state. **Deviations:** the
  selection's `NOT_DECLARED` and `UNDECLARED_BY_BUILD` are one fact at this subject (the build declares no
  corridor rail by that name), so only `UNDECLARED_BY_BUILD` is recorded, and `UNAVAILABLE` means a declared,
  covering rail with no operable adapter in this deployment (a disabled corridor yields no candidates at
  all); declarations carry no version, so none is recorded.
