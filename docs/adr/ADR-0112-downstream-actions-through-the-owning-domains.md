# ADR-0112 — Downstream actions as keyed commands through the owning domains: risk never mutates authoritative state and never reverses a transaction

Status: Accepted (2026-10-27, `P13-DOC-001` — read against the code and corrected first)
Date: 2026-10-22
Phase: 13
Context: Risk · Identity · Accounts · Merchant · Cross-border · Payments (withdrawals) · KYC · Audit
Supersedes: nothing. Applies ADR-0107 (`risk` writes no other module's row; the action ports), ADR-0108 (the override
records the action it issues; a `HOLD` is the withdrawal's own state), ADR-0110 (a disposition that issues an action
is two persons' act) and ADR-0062 §6 (the withdrawal is hold-then-dispatch; its release and denial are payments'
commands). Rests on `PHASE_13_PLAN.md` §1 ("an action is a keyed command through the owning domain's existing door,
recorded on both sides"), §2.2 (C10), §3.1 (Risk Action), §4, §5, §7.1 (T-act), §7.2 (R6), §7.3 (Actions), §7.4,
§7.5, §9, §10, §12.8, §13.2 (scenarios 4, 7, 10, 11), §14, §20 and `INV-RSK-02`, `-06`, `-07`.

## Context

1. **A judgement needs an effect, and every effect has an owner.** Suspending an identity, revoking its sessions,
   suspending an account or a merchant, blocking a beneficiary, releasing or denying a held withdrawal, requesting a
   rescreening — each is an existing command of `identity`, `accounts`, `merchant`, `crossborder`, `payments` or
   `kyc`, keyed, audited and lifecycle-checked by its owner (plan §7.3).
2. **No shared mutable ownership** (`CLAUDE.md`; `MODULE_ARCHITECTURE.md`). If `risk` wrote `identity.status` the
   identity would have two writers with two lifecycles and two audit trails; the suspension would bypass the owner's
   own invariants and its own four eyes.
3. **Ten approvers, duplicate dispatches, a domain that times out.** An action approved on one instance and
   dispatched by a schedule on another must execute once; a re-drive after a timeout must replay, not repeat; a
   domain's refusal (an identity already suspended) must be an outcome, not an error loop.
4. **Account takeover needs a fast, bounded response** (C10). The owner's decision: a confirmed ATO case may propose
   `REVOKE_SESSIONS` and `SUSPEND_IDENTITY`, both through identity's existing commands under four eyes; the platform
   never resets a credential.
5. **Reversal is not risk's.** A transaction already committed is reversed only by the owning domain's four-eyes act;
   `risk` moves no money (`INV-RSK-02`; plan §1, §20).

## Decision

1. **The Risk Action is a record in `risk` and an effect in the owning domain.** `risk_action` (`risk V010`,
   `P13-TSK-017`): `PROPOSED → APPROVED` (a second person) `| REJECTED`; `APPROVED → DISPATCHED → DONE | FAILED`; one
   live action per `UNIQUE (case_id, kind, target_ref)`; a `FAILED` action is re-proposable by a person and is never
   re-dispatched by itself. The record names the case, the kind, the target kind and reference, the proposer, the
   approver, the dispatch stamp, the owning domain's outcome and the correlation; it holds no money and no PII.

2. **The kinds and their owning commands** (plan §12.8):

   | `ActionKind` | Owning domain and command | Notes |
   |---|---|---|
   | `SUSPEND_IDENTITY`, `REINSTATE_IDENTITY` | identity — `IdentityAdministration.suspend` / reinstate | C10: the ATO response |
   | `REVOKE_SESSIONS` | identity — the existing revoke-all | C10; immediate on every instance because sessions are server-side and authoritative (`INV-IDN-03`) |
   | `SUSPEND_ACCOUNT`, `REINSTATE_ACCOUNT` | accounts — the existing suspension | |
   | `SUSPEND_MERCHANT` | merchant — `MerchantAdministration.suspend` | |
   | `BLOCK_BENEFICIARY` | crossborder — the beneficiary block | |
   | `RELEASE_HELD_WITHDRAWAL`, `DENY_HELD_WITHDRAWAL` | payments — the held withdrawal's release / denial (`P13-TSK-009`, `-012`) | the only kinds an **override** issues (ADR-0108) |
   | `CAPTURE_HELD_PAYMENT`, `VOID_HELD_PAYMENT` | payments — the held card payment's capture / void, `HeldPayments.captureHeld` / `voidHeld` behind the port `HeldPaymentActions` (`P13-TSK-010` slice 2; *dated amendment, 2026-10-24*) | an override of a card `HOLD` issues the capture; a disposition the void |
   | `RESCREEN_PARTY` | kyc — through its screening ports (`P13-TSK-022`) | |

   The vocabulary is closed in code; a new kind is a new row here and a new port method, never a generic "execute
   this" command. There is **no** credential reset, no identity mutation beyond the existing suspend / reinstate /
   revoke, no posting, no hold placed by `risk`, and no reversal (plan §20).
   *(P13-DOC-001, 2026-10-27, the table as built (`ActionKind`, `ActionRules`, `ActionDispatch`): eight kinds are
   proposable and dispatched - identity's three, `SUSPEND_MERCHANT` and payments' four held doors. `SUSPEND_ACCOUNT`,
   `REINSTATE_ACCOUNT` and `BLOCK_BENEFICIARY` are unavailable - no owning command (the follow-up's amendment (a);
   `PHASE_13_PLAN.md` §1.2 item 24). **`RESCREEN_PARTY` is not built**: it reads available, but no door proposes it
   (`ActionRules`: "the rescreening sweep's, never a case's"), `ActionDispatch` throws on it, and the rescreening leg
   asks kyc through `PartyRescreening` with no `risk_action` row (ADR-0111 decision 8's note) - dead vocabulary, and
   on-demand rescreening from a case not built (items 93, 50).)*

3. **Proposed and approved in T-case, under four eyes.** A `RISK_INVESTIGATE` holder proposes an action on a case
   (`POST /v1/operator/risk/cases/{id}/risk-actions`, keyed `risk.case:<actor>`, under the case row R4 and its
   `revision`); a second holder with `RISK_CASE_APPROVE` approves (`…/approval`) or rejects (`…/rejection`); the
   proposer never approves (`403 risk.SelfApprovalRefused`, a four-eyes `CHECK`, recorded `FAILED`; `INV-RSK-07`). An
   override-issued action (ADR-0108) is born `APPROVED` by the override's own approval, which is itself four eyes at
   the threshold. Self-dealing fails closed on both acts. The approval answers `202`: the effect is the schedule's.
   *(P13-DOC-001, 2026-10-27, as built: the acts are keyed per principal, `risk.case:<type>:<id>`; a disposition
   issues no action (the follow-up's amendment (a)). The effect is first the approval door's own - after its commit,
   and again on every replay of its key, `RiskActionDesk` drives the action through its owner's door as the platform
   (`RiskActionDriver.driveIfDue`) - and `RiskActionSchedule` re-drives whatever that left; the `202` stands. The
   platform as executor and no disposition issuing an action await ratification (`PHASE_13_PLAN.md` §1.2 item 26).)*

4. **Dispatched by `RiskActionSchedule` in T-act, on the owning domain's lock order, keyed.** The schedule claims
   `APPROVED` actions by their `next_attempt_at` permit (`SKIP LOCKED`, leaderless, off in test contexts). T-act takes
   the action row `FOR UPDATE` (R6) and moves it `DISPATCHED`, then calls the owning domain's command **through the
   action port** on that domain's own lock order with no other risk row held — never R4 — under idempotency key
   `risk.action:<id>`, then records the outcome by a conditional edge: `DONE`, `DONE(ALREADY)` when the domain reports
   the state already held (an identity already suspended — a recorded outcome, not an error), or `FAILED` with the
   domain's reason code. The domain audits its own act under its own action name with the actor the port carries;
   `risk` audits `risk.ActionDispatched` and `risk.ActionConcluded`; both sides record the correlation.

5. **A timeout or a lost response is resolved by the key, never by a second effect.** An action that reached the
   domain's door but whose outcome was lost stays `DISPATCHED` awaiting an outcome; the schedule's second leg claims
   it by its permit and re-drives the same command under the same key, and the owning domain's idempotency replays its
   committed outcome (`RiskActionDatabaseTest#aReDriveReplays`; scenario 4: a risk action times out at the owning
   domain and is retried — one effect). A domain that cannot be reached at all leaves the action `DISPATCHED` with
   its permit advanced and `finapp.risk.action.failed{kind}` alerted past a bound.

6. **The owning domain's outcome is final; `risk` retries nothing of its own accord** (scenario 7). A downstream
   operation that fails after a risk decision — the held withdrawal's dispatch failing at its rail, a suspension
   refused because the identity is closed — is the domain's committed outcome; the decision stands, the action
   concludes with that outcome, and a person re-proposes if the case still needs an effect.

7. **The held withdrawal is payments'.** `RELEASE_HELD_WITHDRAWAL` runs payments' own `HELD → DISPATCHED` under the
   withdrawal row and the wallet account's lock in payments' order (ADR-0062 §6), keeping the hold until the dispatch;
   `DENY_HELD_WITHDRAWAL` runs `HELD → FAILED(RISK_REFUSED)` and releases the ledger hold in the same transaction
   (`INV-BAL-04`). `risk` never touches the hold. An approved release racing the held withdrawal's expiry (`held_until`, payments'
   `WithdrawalResolutionSchedule` leg) leaves exactly one outcome — the withdrawal row lock arbitrates, the action is
   keyed, the loser is a recorded `DONE(ALREADY)` (`WithdrawalHoldDatabaseTest#releaseAndExpiryLeaveExactlyOne`; scenario 10).

8. **Risk never reverses a transaction.** A reversal of a transfer, a capture, a payout or a disbursement is the
   owning domain's four-eyes act on its own door; a case may only recommend it — an investigator action of
   disposition `REFERRED` naming the record (ADR-0110). No `ActionKind` reverses, refunds or posts.

9. **Events and meters.** `RiskActionRequested` (on approval) and `RiskActionCompleted` (on conclusion) carry the
   action id, kind, target kind and reference, and the outcome code — no reason text, no PII (`INV-RSK-08`);
   `finapp.risk.action{kind,outcome}`, `action.failed{kind}`; the schedule's gauge and its claim-failure meter from the
   first task (the Phase 12 → 13 gate's N5); `LEASE_PROTECTED_SCHEDULERS` counts it (29 → 31 with the monitoring
   schedule).

10. **Would this remain correct with ten instances?** Ten approvers of one action produce one `APPROVED` edge
    under the action row lock and the conditional transition, and one dispatch
    (`RiskActionDatabaseTest#tenApproversOneDispatch`); ten schedule instances claim one `APPROVED` action once by
    `SKIP LOCKED`; a duplicated downstream command replays by the owning domain's key `risk.action:<id>`
    (`#aReDriveReplays`); the lock order cannot cycle — T-act holds (R6), which no money transaction ever takes, and
    then enters the domain's command on that domain's own order, so the domain's locks are taken by a transaction
    holding no risk row a money transaction could wait on (plan §7.3); the ATO sequence (credential change → new
    payee → withdrawal) is held and cased, and its actions dispatched once (scenario 11). Permits are the database's
    clock; the schedule is leaderless.
    *(P13-DOC-001, 2026-10-27: the named tests exist and ran green in the owner-authorised database pass (2026-10-27,
    after test-only repairs); the storm's counted run of scenarios 4, 7, 10 and 11 is postponed (`POSTPONED_ISSUES.md`
    PI-004). As built T-act holds no risk row at all while the owner works - the claim on (R6) commits first (the
    follow-up's amendment (c)).)*

## Alternatives Considered

### `risk` writes the target row directly (a `risk → identity`, `→ accounts`, … edge)
Pros: one transaction, one place to look.
Cons: two writers of one authoritative state, bypassing the owner's lifecycle, invariants, four eyes and audit;
`RiskModuleIsolationTest` would have to admit every business module. Refused: `INV-RSK-02`; ADR-0107's edges.

### `risk` publishes an event the owning domain consumes
Pros: no port, no schedule in `risk`.
Cons: an action needs an outcome recorded on the case; at-least-once delivery still requires the consumer to be
keyed, so the key is needed anyway; and Kafka would be the mechanism of a privileged effect, which the platform
refuses (ADR-0024's lineage: events are hints). Refused: a keyed command through a port, the event an announcement.

### Dispatch inside T-case, holding the case row
Cons: a transaction holding a risk row (R4) would enter another domain's lock order; a money transaction that later
takes R1–R3 cannot wait on R4, but the shape invites a future cycle and makes the case's commit depend on a
domain's latency. Refused: T-act takes (R6) alone, then the domain's order.

### Risk reverses or refunds the transaction it judged
Refused: a reversal is the owning domain's four-eyes act; `REFERRED` recommends it. Plan §20.

### Reset the credential on a confirmed ATO
Refused (C10): the platform never resets a credential; sessions are revoked and the identity suspended through the
existing commands under four eyes, and the customer recovers through identity's own flow.

### Automatic actions on a decision without a person
Cons: a rule would suspend a customer with no investigator, no reason and no second person. Refused: every action
is proposed and approved (`INV-RSK-07`); the only automatic effects are the owning domain's own committed outcomes
of a seam's verdict (ADR-0108).

### `risk` retries a failed domain command until it succeeds
Cons: a refusal with a reason (closed identity, closed merchant) would be retried for ever and an operator would
never see it. Refused: `FAILED` is a conclusion; a person re-proposes. Only a lost outcome is re-driven, by key.

## Consequences

Positive:
- Every effect of a risk judgement is the owning domain's own keyed, audited command with its own four eyes where it
  has them; `risk` can be read as a record of requests and outcomes, and the domain's history is one writer's.
- Duplicates, timeouts and refusals are each a recorded outcome; nothing is executed twice or silently dropped.
- Account takeover has a bounded, reviewable response with no new identity capability.

Negative:
- Two transactions and a schedule between an approval and its effect — the `202`; an operator reads the outcome on
  the case, not in the approval's response.
- One port per owning domain in `app`, each a thin adapter over an existing command; the vocabulary grows by ADR
  amendment, not by a generic command.
- A `FAILED` action waits for a person; the alert is the only escalation.

Operational impact: `RiskActionSchedule` with its gauge and claim-failure meter; `finapp.risk.action{kind,outcome}`,
`action.failed{kind}`; the alert "an action failed"; the action's outcome visible on the case and in the case report.
Security impact: `RISK_INVESTIGATE` proposes, `RISK_CASE_APPROVE` approves, never the same person; self-dealing fails
closed; the identity commands keep their own permissions and audit; no credential is ever reset by the platform.
Financial impact: none by `risk`. The held withdrawal's release and denial move or release money only inside
payments' own transaction under its own locks (`INV-BAL-04`).

## Invariants / Constraints

`INV-RSK-02` (no money moved, no other domain's row written; every effect the owning domain's committed outcome or a
keyed command through its door), `INV-RSK-07` (every downstream action is two persons' act; self-approval recorded
`FAILED`), `INV-RSK-06` (a replay creates no second action), `INV-RSK-08`, `INV-IDEM-01` (keyed commands), `INV-AUD-04`,
`INV-BAL-04`, `INV-IDN-03` (revocation immediate on every instance), ADR-0062 §6, ADR-0107, ADR-0108, ADR-0110.

## Follow-up

- `P13-TSK-017` — the action machine, the ports per owning domain, `RiskActionSchedule`, T-act and the two re-drive
  tests (`risk V010`) *(built 2026-10-25, `risk V013` - the next free number: `risk_action` with decision 1's machine,
  `RiskActions`, `ActionRules`, `ActionDispatch`, `app`'s `RiskActionController` / `RiskActionDesk`, `RiskActionDriver`,
  `RiskActionSchedule`, `IdentityRiskActions`, `MerchantRiskActions`. **Dated amendments, 2026-10-25:** (a) decision 2's
  table - accounts has **no suspension command** and crossborder **no beneficiary block**, so `SUSPEND_ACCOUNT`,
  `REINSTATE_ACCOUNT` and `BLOCK_BENEFICIARY` are declared unavailable (`ActionKind.available()`), never proposed and
  never dispatched, their ports `AccountActions` / `BeneficiaryActions` unimplemented - a kind becomes available with its
  owner's command and a row here, never by a mutation `risk` adds to the owner; and **no disposition issues an action**
  (the table's "a disposition the void"; `DESIGN-017` D11): every case action is proposed on the case and decided by a
  second person on its own row, a disposition waiting while one stands `PROPOSED`. (b) Decision 4's "the actor the port
  carries" is **the platform** - T-act runs the owner's command under `SecurityContext.enterSystem()`
  (`RiskActionDriver.asThePlatform`, enumerated in `SystemActorCallSitesAreEnumeratedTest`), the deciding persons
  recorded on the action, in risk's audit and, for identity's not-self rule, as the decider; `P13-TSK-016`'s gate found
  the override door's dispatch under an operator's `CUSTOMER`-typed session refused by payments, and the fix is this,
  not a wider payments door. (c) Decision 4's claim is **committed before** the owner's command: one statement on R6
  `FOR UPDATE SKIP LOCKED` moves the row `DISPATCHED` with a lease-long permit and an attempt counted, so no risk row at
  all is held while the owner works; the conclusion is a second, conditional edge - `DONE` for the owner's `DONE` or
  `ALREADY` (`DONE(ALREADY)`), `FAILED` for its `REFUSED` (the outcome code, the owner having no reason code to hand
  back). (d) Identity's and merchant's commands are keyed by `app`'s adapters through the platform's `IdempotentExecutor`
  in the owner's own transaction (scopes `identity.risk-action`, `merchant.risk-action`) - their doors had no key - so a
  re-drive replays the first answer, as payments' own claims do. (e) `-012`'s issued action is **folded**: the
  override's approval issues it into `risk_action` under the override's own id, born `APPROVED` (decision 3, as
  written), its pending keys `risk.action:<override id>` kept by `V013`'s copy, the leg on `RiskMonitoringSchedule`
  moved to `RiskActionSchedule`.)*; `P13-TSK-012` — the override's `RELEASE_HELD_WITHDRAWAL` / `DENY_HELD_WITHDRAWAL`
  through payments; `P13-TSK-009` — payments' `HELD` state and its release and denial commands (payments `V033`);
  *(built 2026-10-24: `Withdrawals.releaseHeld(key, id, reasonCode)` — the claim `payments.withdrawal.release`, the
  withdrawal row `FOR UPDATE`, `HELD → DISPATCHED` within `held_until` on the database clock, the send after the commit,
  the outcome the platform's — and `Withdrawals.denyHeld(key, id, reasonCode)` — one transaction, `FAILED(RISK_REFUSED)`
  and the hold released; each `DONE`/`ALREADY`/`REFUSED`; risk's `HeldWithdrawalActions` reshaped by its first
  implementation, `app`'s `PaymentsHeldWithdrawalActions` — **no unit of work** (the release sends, so the door runs in
  payments' own transactions: decision 3's T-act shape, the domain's command in its own transaction) and **the reason a
  code**, never a note (`INV-RSK-08`). The loser of a release racing the expiry is `ALREADY` (the expiry won) or `REFUSED`
  (the release arrived past `held_until` before the sweep — the expiry then commits): exactly one edge out of `HELD`
  either way. Unwired to any risk caller until `-012`/`-017`.)*
  `P13-TSK-010` — the held card payment's capture and void (payments `V034`, slice 2) *(built 2026-10-24: the port
  `HeldPaymentActions` declared - none existed - on `HeldWithdrawalActions`' shape, `app`'s `PaymentsHeldPaymentActions`
  over `HeldPayments.captureHeld(key, intent, reasonCode)` and `voidHeld(key, intent, reasonCode)`: each one transaction
  under the caller's key - the capture hold row `FOR UPDATE`, its conditional resolution (`CAPTURE` only within
  `held_until` on the database clock), the caller's audited act - then the money handed to payments' existing
  `PaymentCapture` or `PaymentVoid`, the provider called holding no connection; `DONE`/`ALREADY`/`REFUSED` mapped word for
  word; an authorisation still in flight thrown for the action machine to re-drive; the deadline payments' own leg.
  `ActionKind.CAPTURE_HELD_PAYMENT`/`VOID_HELD_PAYMENT` are available. Unwired to any risk caller until `-012`/`-017`.)*
  `P13-TSK-012` — the override's issued action *(built 2026-10-24: `OverrideActions` over both payments ports - their
  first risk caller - `RELEASE_HELD_WITHDRAWAL` / `DENY_HELD_WITHDRAWAL` for a held withdrawal and, **a dated amendment
  to decision 2's table**, `CAPTURE_HELD_PAYMENT` / `VOID_HELD_PAYMENT` for a held card payment: an override may issue
  the void too, as an approved override's `REFUSE` (the table read "a disposition the void"). Decision 3's "an
  override-issued action is born `APPROVED` by the override's own approval" holds on the override row itself until
  `-017`: the approval records the action and a permit; decision 4's T-act runs from the approval door after the
  commit with **no risk row held at all** (stronger than "only R6": no action row exists yet), keyed
  `risk.action:<override id>` with the override's frozen reason code; decision 5's re-drive is the door's own retry and
  a leg on `RiskMonitoringSchedule` claiming the row's permit `SKIP LOCKED` - `RiskActionSchedule` takes it over at
  `-017`; the outcomes `DONE`, `ALREADY`, `REFUSED` recorded once (`risk.ActionConcluded`), a thrown dispatch recording
  nothing; `risk.RiskActionRequested` / `risk.RiskActionCompleted` carry the override's id with `source=OVERRIDE`.)*
  `P13-TSK-013` — the ATO rules that propose C10's actions; `P13-TSK-022` — `RESCREEN_PARTY` through kyc.
  *(P13-DOC-001, 2026-10-27: `P13-TSK-013`'s rules propose nothing - they raise the `ACCOUNT_TAKEOVER` alert and hold
  or case the movement; C10's actions are a person's proposal on the case. `P13-TSK-022` built the rescreening without
  `RESCREEN_PARTY` - decision 2's note, items 50, 93.)*
- `P13-TST-001` runs scenarios 4, 7, 10 and 11 with counts and asserts the lock wait on R6 and on the withdrawal row
  in both orders. *(P13-DOC-001, 2026-10-27: postponed by the owner until after the project's phases (PI-004) -
  decision 10's note.)*
- `RISK_LIFECYCLES.md` §Risk action is the machine's record; `AUDITABLE_ACTIONS.md` gains
  `risk.ActionProposed/Approved/Rejected/Dispatched/Concluded`; each owning domain's register keeps its own action
  names unchanged.
- A new `ActionKind` is a dated amendment here naming its owning command and port method.
- **Acceptance.** **As built** (`P13-DOC-001`, 2026-10-27): `Proposed` at the Phase 12 → 13 transition (2026-10-22);
  the Phase 13 review read this ADR against the code, corrected it in place above with dated notes, and accepted it.
  The as-built choices awaiting the owner carry their `PHASE_13_PLAN.md` §1.2 item numbers (items 14, 24-26, 50, 93).
