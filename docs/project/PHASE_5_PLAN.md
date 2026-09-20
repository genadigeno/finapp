# Phase 5 — Payment Infrastructure

Written by the Phase 4 → 5 transition (2026-09-20). Decisions in
[ADR-0045](../adr/ADR-0045-payment-intent-and-attempt.md) (intent/attempt and the three
machines), [ADR-0046](../adr/ADR-0046-no-transaction-spans-a-provider-call.md) (the dispatch
discipline, `UNKNOWN`, reconciliation by query),
[ADR-0047](../adr/ADR-0047-webhook-ingestion.md) (webhooks),
[ADR-0048](../adr/ADR-0048-authorization-is-not-a-posting.md) (the accounting treatment —
unresolved question 6 closed), and
[ADR-0049](../adr/ADR-0049-first-provider-simulated-card-psp.md) (the first provider and its
finality — question 9 closed). The domain-facing statement of the model is
[`PAYMENT_LIFECYCLES.md`](../domain/PAYMENT_LIFECYCLES.md), rewritten by the same transition.

## 1. Objective

**Introduce the external world**: money movement whose outcome is decided by an unreliable
third party, with unknown outcomes modelled rather than assumed. Concretely: a verified
customer funds their platform wallet from a tokenised external instrument through a
simulated card-style PSP — intent, attempt, authorization, capture with its posting,
webhooks, refunds, and the reconciliation-by-query sweeper that resolves ambiguity.

Phase 4 deliberately held one variable fixed: both legs internal, one transaction, no third
party. Phase 5 changes exactly that variable. ADR-0043's boundary clause is binding here in
the negative — *its atomicity answer must not be inherited by analogy* — and `INV-LIFE-03`
goes live for the first time.

## 2. Why this phase is shaped by decisions already taken

- **ADR-0046 inverts ADR-0043.** Transfers earned one transaction because no boundary
  crossed it; a provider call is a boundary atomicity cannot cross at any price, so the
  discipline becomes dispatch-before-call, two transactions, and durable intermediate
  states — the exact states ADR-0044 refused for transfers, each now earned by a producer.
- **The seams built earlier are consumed, not rebuilt**: `PostingService` joins the capture
  outcome transaction the way it joined the transfer's; the Phase 3 hold carries the
  refund's in-flight funds (`P3-TSK-015`'s recorded remainder); the `PSP_CLEARING` and
  suspense accounts have stood seeded per currency since `P3-TSK-003`; the `P0-TSK-037`
  provider harness gets the caller it was built for; `P2-TSK-011`'s signed-callback door is
  the webhook template.
- **The asynchronous-outcome API shape is already the contract** (`P4-TSK-008`): a status
  on the response and a status query endpoint — Phase 5 is the first phase whose answers
  are genuinely pending.

## 3. Bounded contexts and modules

**Payments** (`payments`, context 9) and **Payment Methods** (`paymentmethods`, context 10)
— two new modules, the second existing to hold the PCI boundary
(`MODULE_ARCHITECTURE.md` M7). Consumed: Ledger (postings, holds — through the command
APIs), Accounts (the wallet product resolution port pattern), Identity (session, step-up),
Party (ownership chains). Seams only: Settlement (evidence, provider references — Phase 8),
Disputes (identifiers — Phase 7).

**Build-graph edges**: `payments → ledger` declared (postings are commanded, never
written — `INV-LED-04`); `payments → paymentmethods` **refused** — the instrument resolves
through a port `app` implements (`InstrumentResolution`, the `AccountHolderVerification`
shape), so the PCI module stays invisible to the module that talks to providers, and the
boundary "no raw card data crosses this line" is a review of one module's surface.

## 4. Aggregates and commands

| Aggregate | Module | Commands | Idempotency |
|---|---|---|---|
| `PaymentIntent` | `payments` | create, confirm, cancel | Create keyed (`payment.create`); confirm/cancel converge by machine (one-way edges) |
| `PaymentAttempt` | `payments` | dispatch-authorization, apply-outcome, dispatch-capture | Born inside confirm's transaction; outcomes are conditional transitions; provider side per `INV-PAY-04` |
| `Refund` | `payments` | create (privileged), apply-outcome | Create keyed (`payment.refund`); outcomes conditional |
| `PaymentMethod` | `paymentmethods` | attach, detach | Attach converges on (party, token); detach converges by row count |

One attempt per intent in Phase 5 (ADR-0045 §4), with the one-live-attempt partial index as
the arbiter the schema keeps for the day N arrives.

## 5. The lifecycles (ADR-0045)

Stated in full in `PAYMENT_LIFECYCLES.md` §2–§4: the intent's five states, the attempt's
seven (both `*_DISPATCHED` and both `*_UNKNOWN` durable on purpose), the refund's four.
Every machine gets the established three-layer enforcement: exhaustive aggregate sweep,
generated schema `CHECK` + transition trigger binding every writer, append-only history.

## 6. Financial invariants Phase 5 must preserve

The in-scope set is **whatever the catalogue marks `Phase: 5`, token-parsed** — the standing
rule. At planning time that is **eleven**:

- `INV-HIST-02` (providers element) — every provider payload retained verbatim.
- `INV-IDEM-01` (payments element) — same command, same key, one financial effect.
- `INV-IDEM-04` (webhooks element) — duplicate inbound events, one effect.
- `INV-LIFE-03` — unknown external state is a modelled state. **Live for the first time;
  the phase exists for it.**
- `INV-REV-02` (refunds element) — reversal bounded by the original.
- `INV-SET-01` — internal completion is not settlement.
- `INV-PAY-01…05` — the new group the transition catalogued (authenticated provider
  outcomes; no raw card data; provider vocabulary confined; provider-side idempotency;
  capture/refund bounds). The platform stands at **87 invariants**.

The standing families (`INV-LIFE-01/-02/-04` "4 onward", `INV-EVT-*`, `INV-AUD-*`,
`INV-MON-*`, `INV-LED-*`, `INV-BAL-*`) apply as always; the three new machines are
`INV-LIFE-01`'s subjects two through four.

## 7. Multi-instance architecture

Ten instances execute everything concurrently; every contended decision names its
PostgreSQL arbiter:

| Contention | Arbiter |
|---|---|
| Duplicate create/confirm commands, cross-instance | The idempotency claim's unique constraint (`INV-IDEM-01`) |
| Two instances confirming one intent | The intent's conditional `REQUIRES_CONFIRMATION → PROCESSING` row count; the loser converges |
| Concurrent attempt creation | Partial unique one-live-attempt-per-intent index |
| Outcome races — sync response vs webhook vs sweeper | **Conditional transitions**: exactly one resolver's write lands; the rest are evidence (ADR-0046 §4, ADR-0047 §4) |
| Duplicate webhooks | Inbox `(consumer, provider event id)` primary key (`INV-IDEM-04`) |
| Concurrent sweepers | None needed — queries are read-only and idempotent at the provider; the conditional transition arbitrates. **No lease, no leader, recorded in `DISTRIBUTED_EXECUTION.md` §3** |
| Capture posting racing a duplicate outcome | The posting's own claim (`payment-capture:<attemptId>`) — a second winner is structurally impossible |
| Concurrent partial refunds | Lock-then-look on the attempt row + the in-trigger sum bound (`INV-PAY-05`, the `V009` pattern) |
| Refund vs wallet spending | The Phase 3 account lock: the hold is placed under it, availability derived inside it |
| Concurrent attach of one instrument | Partial unique one-live per (party, token); savepoint converge |
| Provider request duplication (our retry, their view) | The stored per-operation idempotency reference (`INV-PAY-04`) |

Nothing lives in process memory; the sweeper schedule follows the relay's registered
pattern (every instance polls, property-gated).

## 8. Data architecture

Schema `payments` (owner `finapp_migrator`, default-deny floor first):

- `payment_intent` — party/customer refs, wallet ledger-account ref, instrument ref (token
  reference id, never anything reconstructable), `MoneyColumns` shape, status +
  generated `CHECK`s, timestamps by injected clock. History table, append-only.
- `payment_attempt` — intent FK, status (7 values, generated), **provider idempotency
  references per operation (`NOT NULL` before dispatch, `UNIQUE`)**, provider references
  (nullable until the provider answers, unique per provider when present), mapped failure
  reason (`NOT NULL` ⇔ `FAILED`), authorized/captured amounts with the coherence `CHECK`s
  (`INV-PAY-05`'s representable half), one-live partial index per intent. History table.
- `refund` — attempt FK, amount, status, hold reference, reason (`NOT NULL` — privileged
  act), provider references; the in-trigger sum bound against the attempt's captured
  amount.
- `provider_evidence` — verbatim bytes of every request, response, query result and
  webhook, checksummed, append-only at the privilege (`INV-HIST-02`), classified at the
  ceiling and encrypted under the generalised key mechanism (`P5-TSK-002`) — provider
  payloads may quote masked instrument data.
- `webhook dedupe` rides the platform inbox (no new table).

Schema `paymentmethods`: `payment_method` — party ref, token reference, instrument
metadata (brand, provider-supplied display suffix, expiry month **and year** — this line
said "expiry month" until `P5-TSK-004` met it: a month without a year is not display
metadata anyone can render, corrected with provenance rather than silently widened) —
display metadata only, nothing reconstructable (`INV-PAY-02` column sweep), one-live
partial index, append-only history discipline as for beneficiaries.

Grants per table in the migration that creates it; frozen columns by every-writer trigger
per the established ceremony.

## 9. API architecture

| Operation | Path | Auth | Idempotency |
|---|---|---|---|
| Create intent | `POST /v1/payments` | session | `@RequiresIdempotencyKey` |
| Confirm | `POST /v1/payments/{id}/confirmation` | session + ownership | machine convergence |
| Cancel | `DELETE /v1/payments/{id}` | session + ownership | machine convergence |
| Status | `GET /v1/payments/{id}` | session + ownership | — |
| List | `GET /v1/payments` | session | — |
| Refund | `POST /v1/payments/{id}/refund` | `@RequiresPermission(PAYMENT_REFUND)` + reason | `@RequiresIdempotencyKey` |
| Attach method | `POST /v1/me/payment-methods` | session; **step-up when a factor is enrolled** (the beneficiary precedent) | converge |
| Detach / list methods | `DELETE /v1/me/payment-methods/{id}` / `GET …` | session + ownership | converge |
| Webhook | `POST /v1/providers/payments/webhooks` | signature + freshness window (`INV-PAY-01`) | inbox dedupe |

The confirm answer carries the intent's real state — honestly `PROCESSING` when it is —
the asynchronous-outcome shape `P4-TSK-008` established. Error vocabulary: payment-domain
codes only, provider codes never (`INV-PAY-03`); refusal shapes follow the established
oracle disciplines (one answer for unknown/not-yours/malformed).

## 10. Event architecture

Producer `payments`: `payments.PaymentIntentCreated`, `payments.PaymentAuthorized`,
`payments.PaymentCaptured`, `payments.PaymentFailed`, `payments.PaymentStateUnknown`,
`payments.RefundInitiated`, `payments.RefundCompleted`, `payments.RefundFailed` *(added to
the module register with provenance by the transition: terminal facts publish — ADR-0044's
doctrine — and a refund's failure is one)*. Producer `paymentmethods`:
`PaymentMethodAttached`, `PaymentMethodDetached`. All through the outbox in the transaction
that commits the fact; `RefundInitiated` is legitimate where `TransferInitiated` was not,
because dispatch commits durably before its own outcome exists. Payloads: identifiers and
enumerated names, never amounts, never provider vocabulary.

## 11. Security and audit

- **Credentials**: the provider API key and per-provider webhook keys join the
  externalised-secret regime through the **generalised per-credential confinement**
  (`P5-TSK-002`) — the debt row fired at `P2-TSK-011` and owed to this phase.
- **PCI boundary**: `INV-PAY-02`; the `paymentmethods` isolation; attach fails when the
  tokenisation provider is down, never falls back.
- **Step-up**: attaching an instrument requires `MULTI_FACTOR` when a factor is enrolled —
  the conditional domain check, `P4-TSK-007`'s pattern verbatim.
- **Privileged actions**: `PAYMENT_REFUND` on `LEDGER_OPERATOR` (one money-operating
  population until a trust decision splits it), reason required, negatively tested.
- **Auditable actions** (declared with the designs that fix them, the deliberately-few
  licence): intent confirmation and cancellation as the person; capture and outcome
  application as the platform (enumerated `enterSystem()` sites — a provider's answer has
  no session); refund with its reason naming the operator; attach/detach as the person.
- **Webhook surface**: machine-facing, signature-first (`INV-PAY-01`), the
  `SIGNED_CALLBACK` ownership class.

## 12. Reconciliation

The phase's whole output is Phase 8's raw material: every provider interaction retains
verbatim evidence with checksums (`INV-HIST-02`); every operation carries our idempotency
reference and the provider's reference, both durable, both unique; captured-but-unsettled
is continuously the `PSP_CLEARING` balance per currency (`INV-SET-01`); the chain intent →
attempt → provider references → journal entry → wallet balance is walkable by stored
identifier in both directions. Internal "captured" is explicitly not "settled", and
nothing in Phase 5 records settlement.

## 13. Testing strategy

The tiers as established; per task: hermetic machine sweeps, schema tests against raw SQL
from scratch, database races counted in tables, contract tests over the `SimulatedProvider`
harness (every outbound mode incl. received-before-lost), webhook tests through real HTTP
with real signatures, and the mutation discipline (every invariant row demonstrated to
fail). The phase's composition demonstrations: `P5-TST-001` (the ambiguity storm —
timeout-then-success resolved to exactly one financial effect), `P5-TST-003` (conservation
under concurrent captures and refunds against the trial-balance and projection sweeps).

## 14. Failure scenarios

1. Provider times out but **did** authorise → `AUTH_UNKNOWN`; sweeper query resolves to
   `AUTHORIZED`; exactly one subsequent capture.
2. Provider succeeds and the response is lost mid-transport → same as 1, by construction
   (dispatch committed first).
3. Provider returns success **after** the sweeper resolved failure → conditional edge
   refuses; the late answer is evidence; alert meter.
4. Webhook arrives before the synchronous response is processed → conditional transitions
   make arrival order irrelevant.
5. Webhook never arrives → the sweeper is the resolution path.
6. Unrecognised provider state → indeterminate, never success (`INV-PAY-03`); evidence
   retained.
7. Duplicate webhook (same event id) → inbox absorbs; evidence retained.
8. Out-of-order webhooks (capture report before auth report) → illegal edge refused,
   evidence retained, meter.
9. Crash mid-provider-call → stranded `*_DISPATCHED`, visible, swept (ADR-0046 §3).
10. Crash between outcome commit and event publication → the outbox's standing guarantee.
11. Duplicate capture command / our retry after timeout → the provider sees one operation
    (`INV-PAY-04`); the posting claim makes a second entry impossible.
12. Concurrent partial refunds exceeding the capture → the in-trigger bound refuses the
    overrun for every writer (`INV-PAY-05`).
13. Refund completes after the customer spent the wallet → impossible: the hold reserved
    the funds at dispatch (ADR-0048 §4).
14. Provider connection refused before send → `FAILED(PROVIDER_UNAVAILABLE)` — knowledge,
    not ambiguity.
15. Tokenisation provider down during attach → the attach fails; nothing raw is ever
    stored (`INV-PAY-02`).

## 15. Observability

| Meter | Kind | Notes |
|---|---|---|
| `finapp.payments.attempt` | counter by `outcome` | acting judgements only; replays/converges never counted |
| `finapp.payments.provider.latency` | timer by `provider`, `operation` | every outcome, injected clock, `finally` |
| `finapp.payments.unknown.active` | gauge | over the database; **NaN when unreadable, never zero** |
| `finapp.payments.unknown.age` | gauge (max seconds) | the stuck-payment alert's series |
| `finapp.payments.webhook` | counter by `outcome` (processed / duplicate / refused / unmappable) | a rise in refused or unmappable is a probe or an integration break |
| `finapp.payments.refund` | counter by `outcome` | |

`provider` and `operation` join `ALLOWED_TAG_KEYS` as bounded compile-time sets (the
`purpose`/`currency` precedent — the designed edit-forces-decision path). All eager from a
plain context; a dashboard row whose queries resolve against a live scrape; the derived
`PlannedMetersExistTest` guard takes this table over at the flip.

## 16. Milestones

| # | Milestone | Items | Acceptance |
|---|---|---|---|
| M5.1 | Foundations | `P5-TSK-001…003` | Both modules and schemas exist with their floors; the credential confinement is one mechanism; the provider port speaks every harness failure mode |
| M5.2 | The instrument | `P5-TSK-004…005` | An instrument is attached under step-up, listed, detached; nothing reconstructable stored |
| M5.3 | The intent and the attempt | `P5-TSK-006…008` | Three machines pinned in code and schema; every invalid transition refused at both layers |
| M5.4 | Money arrives | `P5-TSK-009…011` | A confirm authorises and captures through the simulated provider end to end over HTTP; the capture's posting moves the wallet; ambiguity commits `*_UNKNOWN` |
| M5.5 | Webhooks | `P5-TSK-012…013` | Duplicate, out-of-order, early and late webhooks each produce exactly one effect; unauthenticated ones produce nothing |
| M5.6 | The unknown state | `P5-TSK-014`, `P5-TST-001` | Timeout-then-success resolves to exactly one financial effect, counted; concurrent sweepers race harmlessly |
| M5.7 | Refunds | `P5-TSK-015…016` | Partial refunds bounded under a ten-way race; the hold reserves the funds; privileged, reasoned, audited |
| M5.8 | Observability and demonstration | `P5-TSK-017`, `P5-TST-002`, `P5-TST-003` | Meters from a fresh instance; every `Phase: 5` catalogue row landed; conservation holds under the capture/refund storm |
| M5.9 | The gate | `P5-DOC-001` | The exit review's own verdict flips the phase |

## 17. What Phase 5 must NOT implement

Merchants, checkout and fees (Phase 6 — question 8 stays open there); disputes,
chargebacks, a second rail, rail routing (Phase 7); settlement ingestion and matching
(Phase 8); FX (Phase 9); customer-initiated refunds as a product surface (a product
decision owed a phase with a product owner); automatic attempt retry and `REQUIRES_ACTION`
challenges (no producer yet — ADR-0045); real provider connectivity (never).

## 18. Risks

- **Assuming a timeout means failure** — the phase's defining risk; closed structurally by
  ADR-0046 and demonstrated by `P5-TST-001`.
- **Provider vocabulary leaking** — `INV-PAY-03`'s boundary test, and the adapter review.
- **A webhook handler with non-idempotent financial effects** — every effect is a
  conditional transition plus a claimed posting; `P5-TSK-013` proves it.
- **Capturing to the ledger before the money is capturable** — ADR-0048: the first ledger
  touch is capture, and settlement stays open (`INV-SET-01`).
- **The first rail shaping the port** — ADR-0049's mitigation: the port is designed to the
  lifecycle distinctions and deliberately stays one provider wide until Phase 7.
- **The simulated provider flattering the design** — the harness drives every failure mode
  deliberately, including the ones a sandbox produces only by accident.
