# ADR-0075 — The rate chain and the quote: an independent reference, a firm provider lock, a database-clock window

Status: Proposed (2026-10-02, the Phase 8 → 9 transition)
Date: 2026-10-02
Phase: 9
Context: FX · Identity · Platform
Supersedes: nothing. Resolves `DELIVERY_PLAN.md` §Phase 9.14's anticipated "ADR on FX
quote/rate-lock model". The arithmetic the quote freezes is ADR-0074's; the accounting the
accepted quote posts is ADR-0076's; the cover that hedges it is ADR-0077's. The pricing policy's
four-eyes administration follows ADR-0071's authority discipline and Phase 8's "migration
provenance is V002's alone" rule (D26).

## Context

A quote is a price the platform promises to execute. Everything dangerous about FX concentrates
there:

1. **A rate has to come from somewhere, and that somewhere can lie.** A single rate source is a
   single point of forgery and staleness: a compromised or stuck provider could pass its own
   rate as plausible. The platform needs an *independent* reference that is never executable,
   and a *firm* provider quote that is never trusted for plausibility.
2. **A displayed rate must not be executable indefinitely.** Phase 8 proved that only the
   database clock can be trusted across N instances (`statement_timestamp()` everywhere a
   window is judged). Provider skew, network time and instance clocks must be unable to
   lengthen a lock.
3. **A quote request crosses a provider boundary.** ADR-0046 forbids a transaction spanning a
   provider call, and ten same-key requests must make one RFQ (`INV-IDEM-01`-family
   discipline, the `IdempotentExecutor` shape).
4. **Prices are policy, and policy is money.** Spread, markup, scales, roundings, windows,
   bands and bounds decide every margin the platform earns. Phase 8 decided that versioned
   financial policy activates four-eyes with no seeded version (D26, `INV-AUD-04`,
   `INV-HIST-04`).
5. **An open quote is a free option against the platform.** Unbounded, a customer could farm
   firm quotes and execute only the ones that moved in their favour; even bounded, the cap must
   hold for every writer under ten instances.

## Decision

1. **The rate chain is reference → provider → internal → customer → executed →
   cover-executed, with every link stored** (D8).
   - **The reference** comes from the `RateSource` port's `simulated-reference` adapter —
     deliberately a *different* party than the FX provider, so the plausibility band is an
     independent check. Snapshots land in `fx.rate_snapshot` (`NUMERIC(20,10)`, ADR-0074's
     column type) with the source's `observed_at` and the database's `received_at`, inserted
     `ON CONFLICT (source, pair, observed_at) DO NOTHING` and only when newer than the pair's
     latest — a stuck feed replaying an old observation never looks fresh. Fetching is
     leaderless on every instance, paced by a `rate_fetch_permit` row renewed conditionally and
     strictly forward (the `pull_permit` shape).
   - *As built (`P9-TSK-005`):* the newer-than-latest rule is `fx V002`'s `BEFORE INSERT` trigger,
     serialised per (source, pair) on advisory namespace 6 and forcing `received_at` to
     `statement_timestamp()`, so it holds for every writer and under racing inserts (a Java
     pre-check could not); pairs are the declaration's ten canonical directions, the inverse
     never stored; an observation more than a minute ahead of the database is refused (a source
     clock running ahead would otherwise freeze its pair); the permit is stamped inside its own
     statement. The `simulated-reference` wire is a strict line grammar, never JSON numbers.
   - **Freshness is judged in SQL**: `received_at > statement_timestamp() − reference_max_age`.
     **A stale reference fails closed**: no quote, `503 fx.RateUnavailable`, the cause
     (`REFERENCE_STALE`) counted and never shown. **No in-process rate cache takes part in any
     decision.** The reference is used for plausibility and disclosure only, and is never
     executable.
   - **Plausibility is exact, with no division**: in the pair's direction
     `|rp − ref| ≤ band × ref`; in the inverse direction `|rp × ref − 1| ≤ band`. An
     implausible provider rate is refused, counted and alerted, and the next provider is tried.
     It is never executed.
2. **The provider's firm quote is the lock.** The provider states `rp`, a counter amount, a
   reference and a validity *duration*. The customer's `ISSUED` quote **is** the platform-side
   lock: exclusive to its owner (another principal's id answers a uniform `404`), single-use
   (one `ACCEPTED` edge, `UNIQUE (fx.trade.quote_id)`), and bounded on the database clock.
3. **Validity is computed from durations on the database clock, minus the cover margin** (D9):
   `expires_at = least(requested_at + provider_valid_for − cover_margin, issued_at + window)`,
   where `requested_at` is the claim transaction's `statement_timestamp()` committed *before*
   the provider call, and `provider_valid_for` is the provider's stated duration — never its
   absolute expiry converted. Network time and provider skew can only shorten the window. With
   under 5 s left, no quote is issued. No path accepts a quote without the clock comparison
   inside the transitioning statement, so an instance with a skewed clock can neither accept
   late nor refuse early.
4. **Creation is keyed and two-transaction: claim, wire, insert** (D10).
   - **Tx1** (`IdempotentExecutor.begin`, scope `fx.quote:<actorType>:<actorId>`, fingerprint
     over owner, pair, fixed side, amount, purpose): owner `ACTIVE`; the **`ACTIVE` pricing
     version read and pinned** on `fx.quote_request` (`QR`, `requested_at`); the pair enabled
     and unsuspended in it; the amount exact and the fixed leg within the pair's notional
     bounds; and the **cap pre-checked** (advisory) so an owner at the cap is refused
     `429 fx.TooManyOpenQuotes` with the claim completed `FAILED` **before any provider call** —
     an owner at the cap cannot farm provider RFQs. A takeover after a crash converges on the
     same `QR` and its pinned version.
   - **Wire, holding no connection** (ADR-0046): for each candidate provider in the pinned
     version's order that is declared, available and covering the pair,
     `FxProvider.firmQuote(QR, …)` with a 2 s budget, 5 s total. A firm quote moves no money, so
     **failover is safe** and dispatch-before-call is not needed. Each outcome becomes a row of
     `fx.quote_sourcing_step` (`QUOTED` | `DECLINED` | `UNAVAILABLE` | `INCOHERENT` |
     `IMPLAUSIBLE` | `NOTHING_SENT` | `INDETERMINATE` | `CHOSEN`, with the declaration version)
     — the `routing_decision_step` shape, so the selection is recomputable.
   - **Tx2**: re-read the pinned version — if a successor activated in between,
     `complete(FAILED, 409 fx.PolicyStale)` and issue nothing (a new key prices under the
     successor); read the latest snapshot, fresh on the database clock; band and coherence;
     `ConversionPlan.compute` under the **pinned** pair (ADR-0074); `expires_at`, refusing below
     5 s; insert `fx.quote` with its sourcing steps, `quote_event (ISSUED)` and the outbox
     `fx.FxQuoteIssued`, in one transaction; `complete(201)`. All candidates failed ⇒
     `complete(FAILED, 503 fx.RateUnavailable)`, retryable with a new key, **never a fallback
     to a cached or older rate**.
   - Ten same-key requests make **one** provider call; the others replay or answer
     `409 platform.IdempotencyInProgress`. The cross-border quote (`P9-TSK-018`) runs the same
     two transactions under `crossborder`'s claim through `CrossBorderFx.beginQuote`, with both
     versions pinned and either staleness answering its own `PolicyStale` (ADR-0079).
5. **The quote machine has six states** (D11): `ISSUED`, `ACCEPTED`, `EXECUTED`, `ABANDONED`,
   `EXPIRED`, `CANCELLED` — no state without a producer.
   - `ISSUED → ACCEPTED` by the conversion door or cross-border Tx1, on the locked row, with
     `expires_at > statement_timestamp()`, owner and purpose matching, the pair unsuspended,
     the subject stored. `ACCEPTED` is **earned**: a cross-border quote holds it from
     authorization until the corridor provider accepts or fails; a conversion passes through it
     inside one transaction, writing both history rows.
   - `ACCEPTED → EXECUTED` by the trade's booking (`UNIQUE (fx.trade.quote_id)`; the edge
     trigger refuses `EXECUTED` without a trade); `ACCEPTED → ABANDONED` by the outbound
     credit's `FAILED` applier. `EXECUTED`, `ABANDONED`, `EXPIRED` and `CANCELLED` are terminal.
   - **Expiry is a modelled event, written exactly once.** `FxQuoteExpirySchedule` runs on
     every instance with no lease, executing the conditional
     `UPDATE … SET status='EXPIRED' WHERE status='ISSUED' AND expires_at <= statement_timestamp()
     RETURNING id` in pages and writing one `fx.FxQuoteExpired` per returned row in the same
     transaction. An acceptance that finds the quote past expiry performs the same conditional
     transition, writes the same event (`detectedBy` `ACCEPTANCE`), records the claim's failed
     outcome and answers `409 fx.QuoteExpired`. The event is written by whichever writer's
     conditional matched; the row lock serialises them. A suspended pair refuses acceptance
     (`409 fx.PairSuspended`) and lets the quote expire — the kill switch beats the outstanding
     quote.
   - The three-layer discipline holds the machine for every writer: a generated `CHECK`, the
     every-writer edge trigger `quote_edge_is_legal`, and the domain.
6. **The live-quote cap holds for every writer.** A `BEFORE INSERT` trigger on `fx.quote` takes
   `pg_advisory_xact_lock(5, hashtext(owner_party_id))` — advisory **namespace 5, which
   arbitrates**, unlike namespace 4, which only orders — and counts the owner's **live** quotes:
   `status='ISSUED' AND expires_at > statement_timestamp()`. A lapsed quote the sweeper has not
   yet written never causes a spurious `429`; at the cap (v1: 5) the insert is refused for every
   writer. Tx1's pre-check only spares provider calls; the trigger is the arbiter.
7. **The pricing policy is versioned, four-eyes, with no seed** (D26). `fx
   pricing_policy_version` / `pricing_pair` carry, per pair: purpose, ordered providers, spread
   and markup (`CHECK (spread + markup > 0)`), the per-pair rate scale (`BETWEEN 0 AND 10`), the
   three rounding names under enum-generated `CHECK` lists, window, cover margin, band,
   reference maximum age, the fixed leg's notional bounds, and the open-quote cap. One `ACTIVE`
   version (partial unique); proposal, approval and rejection are distinct audited acts with
   approver ≠ proposer held at the domain and by `CHECK`, each rank proven alone; retirement
   commits only beside its successor; **no version is seeded by migration** — v1 (O7's values)
   is activated by two `FX_CONTROLLER`s (identity `V019`, permission `FX_ADMINISTER`), in
   fixtures and the runbook alike. Activation after issue changes nothing: every quote carries
   its pinned version.
8. **Availability is a kill switch down, a proposal up.** `pair_availability` and
   `provider_availability` let one controller disable instantly; re-enabling goes through
   `availability_enable_request` (`PROPOSED → APPROVED | REJECTED`, four-eyes `CHECK`,
   every-writer trigger, one live proposal per subject) — turning money-moving capability back
   on is a two-person act.
9. **No client influence on price, and no conversion fee.** No `fx` or `crossborder` request
   body has a rate field; strict deserialisation answers `422 VALIDATION_FAILED` to any unknown
   field, `rate` included; `RatesAreNeverClientSuppliedTest` proves it statically over every
   request record and an OpenAPI guard over every request schema (`INV-FX-02`, amended: a
   client rate is *refused*, not ignored). The quote carries **no fee** (O10): Phase 9's
   conversion is priced by margin alone, and the cross-border transfer fee lives on
   `crossborder.payment_offer` (ADR-0079), never on the FX quote.

## Alternatives Considered

### One rate source: price off the provider's own rate without a reference
Pros: one integration, no band to tune.
Cons: a compromised or malfunctioning provider passes its own forged rate as the check of
itself; staleness has no independent witness. The band is only meaningful against a source the
provider does not control (D8).

### Convert the provider's absolute expiry timestamp
Pros: honours the provider's intent directly.
Cons: an absolute timestamp crosses two clocks (the provider's and ours) and a network delay;
skew in either direction lengthens or shortens the lock unpredictably. Durations anchored to
`requested_at` on the database clock can only shorten it. Rejected as the distributed design's
defect (D9).

### Claim after the provider call
Pros: no claim row for requests that never reach a provider.
Cons: ten same-key requests make ten RFQs before the first claim lands, and a crash between the
call and the claim leaves an unowned firm quote. The claim-first shape converges takeovers on
one `QR` and one pinned version. Rejected as the distributed design's defect (D10).

### `WITHDRAWN` / `LAPSED` states for accepted quotes
Pros: a terminal state for every corner.
Cons: no producer exists for them — an `ACCEPTED` quote's cover has already executed
(ADR-0077), so waiting is the platform's risk, never the customer's, and abandonment is the
failure applier's edge. A state nobody can write is a liability in every `CHECK` (D11).

### The cap held only by the issuing transaction's read
Pros: no advisory lock, no trigger.
Cons: two instances that both read 4 both insert the 5th and 6th; the cap silently becomes
advisory exactly when it matters. The `BEFORE INSERT` trigger under namespace 5 holds it for
every writer, raw SQL included. Rejected as the accounting design's defect.

### Seed pricing policy v1 by migration
Pros: the platform prices from first boot.
Cons: it widens the Phase 8 rule that migration provenance is V002's alone, and puts the first
margin schedule into history with no named approver. Four-eyes activation of v1 costs one
runbook step and keeps `INV-AUD-04` whole (D26).

## Consequences

Positive:
- A displayed rate is never executable indefinitely, on any instance, under any skew.
- A forged or stale rate fails closed at three independent gates: freshness in SQL, the band
  against an independent reference, and coherence (ADR-0074).
- Every issued price is replayable to its inputs: pinned policy version, provider quote,
  reference snapshot and sourcing steps are all rows.
- Ten instances agree by construction: one RFQ per key, one cap arbiter, one expiry event,
  one acceptance.

Negative:
- Two transactions and a wire per quote is more machinery than a single-transaction read —
  accepted, because ADR-0046 is not negotiable and takeovers need the pinned `QR`.
- A reference outage stops quoting entirely (fail closed). That is the designed behaviour, and
  it is loud: the age gauge alerts long before customers see `503`s.
- The cap trigger serialises an owner's concurrent quote inserts on namespace 5 — a per-owner
  cost, invisible fleet-wide.

Operational impact: `finapp.fx.quote{pair, outcome}` (issued and the five refusal causes),
`finapp.fx.quote.closed{outcome}`, `finapp.fx.quote.open`, `finapp.fx.rate.age{pair}` (alerting
past the maximum age), `finapp.fx.provider.quote.latency{provider, outcome}`, and the two
sweeper-enabled gauges (`finapp.fx.rate.sweeper.enabled`,
`finapp.fx.quote.expiry.sweeper.enabled`). Counts, ages and verdicts only — never an amount or a
rate (ADR-0072, D32).
Security impact: the reference adapter and provider adapter run under confined keys
(`FINAPP_FX_REFERENCE_KEY`, `FINAPP_FX_PROVIDER_KEY`) behind the transport guard; a client
cannot name a rate anywhere (statically proven); the provider's raw rate never appears in a
customer response; quote reads are owner-scoped with uniform `404`s; `FX_CONTROLLER` is a
dedicated role and every policy and availability act is audited with a reason.
Financial impact: none posted at issue — a quote is a promise, not a hold and not a posting
(ADR-0076 posts; ADR-0048's discipline). What this ADR fixes financially is the *price*: margin
policy under four-eyes, and a lock window the platform can always cover within (the cover
margin, ADR-0077).

## Invariants / Constraints

`INV-FX-02` (amended: the plausibility band against an independent fresh reference; validity
from durations on the database clock; a client rate *refused* (`422`), not ignored), `INV-FX-04`
(the quote's single use and modelled expiry; the frozen plan itself is ADR-0076's), `INV-FX-05`
(the provenance chain stored at issue), `INV-AUD-04` (pricing policy and enable proposals
four-eyes, no seed), `INV-HIST-04` (pricing policy as a versioned, pinned subject), `INV-MON-03`
(no default rounding anywhere in the policy), ADR-0046 (no transaction spans the RFQ).

## Follow-up

- `P9-TSK-005`: the `RateSource` port, the `simulated-reference` adapter, `fx V002`
  (`rate_snapshot`, `rate_fetch_permit`), `FxRateFetchSchedule`, staleness judged in SQL.
- *As built (`P9-TSK-006`):* the port answers sealed verdicts mapped totally by the adapter
  (only a refused connection is `NothingSent`; an over-precise rate is `Indeterminate`, never
  rounded); the evidence table is `fx.fx_provider_evidence`, keyed by our reference; the simulator
  lives in test scope with the contract battery, deduping on `T` before validity.
- `P9-TSK-006`: the `FxProvider` port, `FxProviderDeclaration`, the `fx-sim-a` simulator
  (firm quotes with stated counters and `validFor`, dedupe on `T` before judging validity),
  `fx V003` encrypted provider evidence.
- `P9-TSK-007`: identity `V019` (`FX_CONTROLLER`, `FX_ADMINISTER`); `fx V004` (the pricing
  policy, pair and provider availability, the enable proposal); v1 activated four-eyes per O7.
- *As built (`P9-TSK-007`):* the open-quote cap lives on the version, not the pair; the band is
  stored as a fraction (`0.015` is 150 bps); availability is an append-only FACT per change, not
  a machine - no fact means available, a disable is one person at once, an enabling fact must
  name an APPROVED request for exactly its subject (trigger, every writer) - its writers ordered
  by advisory namespace `7`; every reason column refuses a card-number or account-identifier
  shape (`fx V004`'s twin of `InstrumentShapes`, the settlement `V012` precedent). v1 is proposed
  through the API by two controllers (OPERATIONS_RUNBOOK §2), never seeded.
- `P9-TSK-008`: `fx V005` (the quote with its `CHECK`s, freeze, edge and cap triggers;
  `quote_event`; `quote_request`; `quote_sourcing_step`); creation, read, cancel;
  `FxQuoteExpirySchedule` and lazy expiry; `RatesAreNeverClientSuppliedTest` and the OpenAPI
  guard; `GET /v1/me/fx/pairs`; namespace 5 registered.
- `P9-TSK-018`: the cross-border purpose through `CrossBorderFx`, both policy versions pinned
  (ADR-0079).
- `P9-TST-001` / `P9-TST-002`: the storm's skewed-clock races over quote, cover and payment;
  golden replay of every quote.
- Until `P9-TSK-005` lands, nothing in this ADR is implemented: every statement is the decided
  design, to be corrected by the tasks that build it.
- The Phase 9 review reads this ADR against the code before accepting it (`P9-DOC-001`).
