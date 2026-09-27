# ADR-0063 — The order of an aggregate's facts is the database's; a business stamp never contradicts it

Status: Proposed
Date: 2026-09-27
Phase: cross-cutting (`X-TSK-005` decided it; `X-TSK-006` implements it)
Context: every module that stamps a later fact on an aggregate: Identity · Party · KYC · Accounts ·
Ledger · Transfers · Payment Methods · Platform · Merchant · Checkout · Payments
Supersedes: nothing. Refines ADR-0014 (business stamps under N instances) and ADR-0057 §4 (what a
renewer whose clock trails the previous permit does).

## Context

Business timestamps are system time, read from the acting instance's injected `Clock`
(`P0-TSK-013`, `DOMAIN_MODEL.md` §Time). Every service runs as N instances (ADR-0014), and ADR-0014
says two things about their clocks: coordination uses the database's clock, and *"clock skew
between instances is bounded by NTP but never zero, and no correctness argument may depend on it
being small."*

**Twenty-one tables in eleven modules order two such stamps anyway.** Each holds a `CHECK` that a
later fact's stamp does not precede an earlier one, and ten aggregates restate it in their
constructors (`Merchant`, `CheckoutSession`, `MerchantApiKey`, `PayoutDestination`,
`MfaEnrolment`, `PaymentMethod`, `Beneficiary`, `MerchantPayout`, `Withdrawal`, `PaymentAttempt`):

| Module | Later stamp ≥ earlier stamp |
|---|---|
| identity | `identity.status_changed_at ≥ created_at`; `credential.superseded_at ≥ created_at`; `mfa_enrolment.confirmed_at`, `discarded_at ≥ created_at`; `role_assignment.revoked_at ≥ assigned_at` |
| party, kyc, accounts | `customer.status_changed_at ≥ opened_at`; `kyc_case.status_changed_at ≥ opened_at`; `customer_account.status_changed_at ≥ opened_at` |
| ledger, transfers | `ledger_account.status_changed_at ≥ created_at`; `adjustment_proposal.decided_at ≥ proposed_at`; `beneficiary.removed_at ≥ created_at` |
| paymentmethods, platform | `payment_method.detached_at ≥ created_at`; `idempotency_record.completed_at ≥ created_at` |
| merchant, checkout | `merchant.status_changed_at ≥ created_at`; `merchant_api_key.revoked_at ≥ issued_at`; `payout_destination.approved_at`, `ended_at ≥ proposed_at` and `superseded_at ≥ effective_at`; `merchant_payout.last_dispatched_at ≥ created_at`; `checkout_session.status_changed_at ≥ created_at` |
| payments | `refund.last_dispatched_at`, `withdrawal.last_dispatched_at`, `payment_attempt.last_dispatched_at ≥ created_at`; `dispute_response.send_permit ≥ created_at` |

Instance A stamps the earlier fact from its clock and instance B the later one from its own. If B
trails A by more than the time between the two facts, the aggregate throws
`IllegalArgumentException`, which surfaces as an unmodelled `500`, or as the rollback of the whole
enclosing transaction. That can be a capture's: a checkout's completion rides the capture. The
`CHECK` refuses every other writer the same way. The refusal fails closed and moves no money
wrongly, but it is a single-instance assumption: correct only while the instances agree about the
time more closely than the platform's fastest successive facts, and the fastest pairs are
machine-driven (a webhook completing a checkout opened a moment before, a takeover re-driving a
dispatch). `DISTRIBUTED_EXECUTION.md` §5's audit of 2026-09-02 concluded that no client clock
decides anything across instances any more. That is true of coordination and not of these checks.

**It is not hypothetical, because the database tier runs a second clock.** `P7-TSK-014`'s gate
failed `MerchantPayoutDatabaseTest#onlyASettledMerchantCanBeClosed` with *"statusChangedAt must not
precede createdAt"*: the fixture stamped the merchant with the database's `now()` and `close()`
stamped from the JVM's clock. Measured (`P7-TSK-014`, then `X-TSK-005`): the local Docker VM's clock
gains 55–61 ms a second on a steady host clock and is stepped back, once by 1.7 s at a time,
reading from 650 ms ahead of the host to more than a second behind. Production's NTP keeps the skew
to milliseconds, so the window there is small. It is not zero, and ADR-0014 forbids resting on its
size.

**The platform already answers this, three ways.** `PayoutDestination.supersede` clamps (*"never
before it took effect, whatever the two instances' clocks said"*). The refund's and the dispute
response's send-permit renewals clamp in SQL, `GREATEST(permit, ?)`, because *"a refusal here would
be a failed re-drive, not a safer one."* The payout's, withdrawal's and pay-in's renewals are
conditional and quietly refuse a trailing renewer: nothing is re-sent and the sweep resolves it
later. Every other transition throws. And `kyc.review_task` dropped its ordering constraint for this
very reason (`P2-TSK-012`). One question, three shapes, and no record of which is the rule.

## Decision

1. **The order of an aggregate's facts is decided by the database**: by its row lock, its
   conditional transition, or its sequence, never by comparing two instances' clocks. A business
   stamp records *when* a fact happened; it does not decide *which came first*. This is ADR-0014's
   coordination rule applied to business stamps, with `consent.consent_record`'s `seq` as the
   precedent: no instance's clock decides which of two facts is later.
2. **A stamp never contradicts that order.** Each business stamp is still read from the acting
   instance's injected `Clock` (`P0-TSK-013`, unchanged). But a stamp for a later fact is the later
   of that reading and the latest stamp the aggregate already carries: `max(now, latest)`. That
   covers every transition, every terminal stamp and every permit renewal. Where the latest stamp
   lives only in the row (a renewal written in SQL), the database clamps it, `GREATEST(column, ?)`,
   in the refund's form.
3. **The ordering `CHECK`s stay**, as the every-writer rank against a corrupt or raw write, and so
   do the constructors' checks (a corrupt row is refused at read). Under (2) they hold by
   construction for every domain writer on every instance, instead of holding only while the
   instances agree.
4. **Judgements stay bounded-skew premises.** Where one instance's clock *judges* a stamp another
   wrote, the margin is the argument: minutes to days against NTP-disciplined milliseconds, stated
   where each judgement lives. That covers the checkout's expiry, the cooling-off, the send-permit
   sweeps (ADR-0057 §4), the card sweep's bounds, a dispute's respond-by, session liveness, webhook
   freshness and a version's effective-from. A clamp cannot apply to a judgement: an effect before
   its cooling-off is a rule broken, not a stamp adjusted.

## Alternatives Considered

### Option A — Record a bounded-skew premise and keep refusing
Pros: no code changes; the refusal fails closed, and NTP makes it rare.
Cons: it contradicts ADR-0014's own sentence. The refusal lands as an unmodelled `500`, or as a
rolled-back capture, on exactly the fastest, machine-driven paths. It leaves three clamps as
unexplained exceptions and the five permit renewals with two different answers. And it rests on a
premise nothing monitors.

### Option B — Stamp business time with the database's clock
Pros: one clock for every stamp, so the ordering holds without a clamp.
Cons: it reverses `P0-TSK-013` and `DOMAIN_MODEL.md` §Time. The aggregate could no longer know its
own stamps before the write, so the domain could not judge its own coherence. And the database's
clock is not monotonic either: it steps (measured on the local VM, and possible under NTP stepping).

### Option C — Drop the ordering `CHECK`s (`kyc.review_task`'s answer)
Pros: nothing can refuse; `P2-TSK-012` is a precedent.
Cons: it loses a corruption rank on twenty-one tables and the in-order history. Under the decision
the constraint no longer buys flaky refusals, so the reason to drop it is gone.

### Option D — A hybrid logical clock, or a cluster time service
Pros: the textbook answer to causally ordered time.
Cons: it is machinery for an order the database already decides. The half of a hybrid logical
clock that matters here, the maximum of the local reading and the last observed stamp, is
decision (2); the rest buys nothing.

## Consequences

Positive:
- No legitimate transition is refused because two instances disagree about the time, and the
  ordering `CHECK`s become true by construction.
- An aggregate's history reads in order, whichever instances wrote it.
- One rule replaces three shapes, and ADR-0014 holds as written.

Negative:
- A stamp can be later than the acting instance's own reading: by at most the skew, and only within
  the skew of the fact before it. A stamp now means "no earlier than the previous fact", and it was
  never more precise than the skew anyway.
- **The clamp removes the only signal a badly skewed instance produced.** An instance far behind
  would stamp each transition at its predecessor's time, leaving history that looks frozen rather
  than raising an error. Clock offset therefore needs its own signal (Follow-up).
- One line in every later-fact transition across the class, each with a test. The payout's,
  withdrawal's and pay-in's renewals change from a quiet refusal to a clamped renewal: a liveness
  change on rails that move money. It is safe under ADR-0057 §4's premise, because a later permit
  only postpones a `NEVER_RECEIVED` conclusion, and it is to be probed, not assumed.
- Test fixtures must stamp a row's times from the clock that will judge them. The convention is in
  `DISTRIBUTED_EXECUTION.md` §5 (`X-TSK-005`).

Operational impact:
- Instances stay NTP-disciplined; nothing here survives an unsynchronised host any better than
  before, it only stops refusing inside the skew.

Security impact:
- None directly. Audit records keep each instance's own reading (`occurred_at`) unclamped: the
  trail says what each instance observed, which is what an investigator needs, and the
  aggregate's stamp and its audit record's can differ by at most the skew.

Financial impact:
- None on amounts, postings or accounting dates: posting and value dates are domain inputs, never
  clock reads (`DOMAIN_MODEL.md` §Time), and no money moves differently. A capture whose checkout
  completion a skewed clock would have refused, rolling the capture back, now commits.

## Invariants / Constraints

- `INV-LIFE-02`: the machine still decides the edges. The clamp touches stamps, never states.
- `INV-HIST-01`: history stays immutable, and under the decision it also reads in order.
- ADR-0014 (N instances, skew never zero); `P0-TSK-013` (stamps from the injected `Clock`).
- ADR-0057 §4's premise, that the dispatched bound exceeds the gap plus the skew, is unchanged. It
  is what makes a clamped renewal safe.

## Follow-up

- `X-TSK-006` implements decision (2) across the class, together with a test per aggregate on a
  trailing clock and the probes. It waits on this ADR's acceptance and displaces no phase task.
- A per-instance clock-offset signal (the instance's clock minus the database's `now()`), owned by
  Phase 15's observability, because the clamp no longer surfaces skew.
- `kyc.review_task` could take its ordering constraint back under this decision. That is not
  required, and it is recorded so the omission stays a choice.
