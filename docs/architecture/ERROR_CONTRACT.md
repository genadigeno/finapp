# Error Contract

Every API failure is returned as an RFC 9457 problem detail with a stable, machine-readable code.
One shape, on every path, including the ones the framework raises before our code runs.

**This document and the code are one definition.**
[`ErrorCodeRegistryTest`](../../app/src/test/java/com/finapp/app/api/ErrorCodeRegistryTest.java)
fails the build when they disagree in *either* direction.

---

The conventions every endpoint follows - versioning, correlation, request limits, idempotency,
pagination, deprecation - are in [`API_CONVENTIONS.md`](API_CONVENTIONS.md). This document is the
error half of that, and owns the code catalogue: §3 below is the only place the codes are listed,
and `ApiConventionsAreAccurateTest` fails the build if the conventions document restates them.

## 1. The shape

`Content-Type: application/problem+json`

| Member | Always present | What it is |
|---|---|---|
| `type` | yes | A URI identifying the problem kind, `https://finapp.example/problems/<code>` |
| `title` | yes | A short human summary, fixed per code |
| `status` | yes | The HTTP status, fixed per code |
| `code` | yes | **The machine-readable identifier a client switches on** |
| `detail` | no | Authored specifics, when the throw site supplied any |
| `instance` | no | The request path |
| `correlationId` | no | The flow's identifier, when a request scope established one |

**Absent members are absent, not null.** A `"detail": null` tells a client there was something to
say and then does not say it, and every response pays bytes for it.

**Clients switch on `code`, not on `title` or `status`.** Titles are prose and may be reworded;
several codes share a status. The code is the contract.

## 2. What is never in a response

Exception messages, stack traces, class names, SQL fragments, provider payloads, file paths.
`INV-AUD-02` forbids these in API responses as firmly as in logs, and the design enforces it
structurally rather than by discipline:

- `ProblemDetail` has **no** constructor or factory taking a `Throwable` — asserted by test,
  because an absence is what gets added back later by someone solving a different problem.
- `title` comes from the error code and is fixed at compile time.
- `detail` is authored text a developer wrote knowing a stranger would read it.
- `ApiException` keeps the log message and the client detail in **separate fields**, so the
  unsafe default is unreachable: `getMessage()` has nowhere to go.

What a client does get is the `correlationId`, which is the one thing that usefully connects a
person reporting a problem to the record of what happened.

## 3. The codes

### `platform` — `PlatformErrorCode`

Failures of the *protocol*, raised before any business module is reached.

| Code | Status | Meaning |
|---|---|---|
| `api.MalformedRequest` | 400 | The request body could not be parsed. |
| `api.Unauthenticated` | 401 | Authentication is required. |
| `api.Forbidden` | 403 | Authenticated, and not permitted. |
| `api.NotFound` | 404 | No route matches. |
| `api.MethodNotAllowed` | 405 | The route exists; the method does not. |
| `api.NotAcceptable` | 406 | No representation this endpoint produces is acceptable. |
| `api.Conflict` | 409 | The request conflicts with current state. |
| `api.IdempotencyInProgress` | 409 | An identical request is still being processed. |
| `api.PayloadTooLarge` | 413 | The request body exceeded the accepted size. |
| `api.UnsupportedMediaType` | 415 | The body's media type is not read here. |
| `api.ValidationFailed` | 422 | Well-formed, and not valid. |
| `api.IdempotencyKeyRequired` | 422 | An operation requiring `Idempotency-Key` was called without one. |
| `api.InternalError` | 500 | Something failed that the client cannot act on. |

### `party` — `PartyErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `party.RegistrationRefused` | 422 | This registration could not be completed. |

**One code for every reason a registration can fail, and that coarseness is the design**
(`P1-TSK-006`, `INV-IDN-07`). The commonest reason is that the login identifier is already in use,
and saying so would make `POST /v1/registrations` an account-existence oracle: anyone could submit
identifiers and read the answer off the status code. A collision and any other domain refusal
therefore produce a byte-identical response, and a legitimate caller who picks a taken identifier
gets no explanation. That cost is real and accepted; it is the same trade every serious platform
makes for an email address.

**422 rather than 409.** A 409 says *this conflicts with something that exists*, which is precisely
the fact that must not be disclosed.

**The two idempotency codes are different answers to different questions.** `api.Conflict` means
*you sent a different request under a key you already used* — the request will never succeed and a
retry is pointless. `api.IdempotencyInProgress` means *the request you sent is running and its
outcome is not yet known*, and the correct client behaviour is to retry the identical request
shortly. Collapsing them would leave a client library no way to tell "stop" from "wait", and the
second is never reported as a failure: assuming a command failed because its outcome is unknown is
the assumption `INV-LIFE-03` exists to forbid.

**400 versus 422** is the distinction between "your serialiser is wrong" and "your data is
wrong", and it is worth keeping: a client can act on the second and only a developer can act on
the first.

**A client's mistake is never reported as ours.** Spring's web exceptions carry the status they
mean, and the renderer maps that status to a code rather than letting the catch-all turn it into
a 500. This is not a cosmetic concern: a client may retry a 500 forever on a request that can
never succeed, and a spike of malformed requests would otherwise be indistinguishable from an
outage. An unmapped 4xx becomes `api.MalformedRequest` and is logged as a warning, so the gap is
visible rather than quietly approximated.


### `identity` — `IdentityErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `identity.AuthenticationFailed` | 401 | Authentication failed. |
| `identity.AssuranceRequired` | 403 | This operation requires a stronger authentication. |
| `identity.VerifiedChannelAlreadyExists` | 409 | The account already has a verified contact channel of this kind. |

**One code for every reason an authentication can fail** (`P1-TSK-010`, `INV-IDN-07`): unknown
identity, wrong password, suspended identity, and an identity that has no credential yet all
produce byte-identical responses. The response body is only half of it — `P1-TSK-008` made every
one of those paths perform a full Argon2id verification, so they are indistinguishable by *timing*
as well. A suspended account answering instantly would tell an attacker both that it exists and
that it is suspended, which is the invariant lost through the channel that is harder to notice.

**Not `api.Unauthenticated`, deliberately.** That code means *authentication is required* — a
protected route reached with no credentials at all. Here credentials **were** presented and were not
accepted. They are different facts, a client handles them differently, and giving one string two
meanings is what §4 forbids.

**401 and not 403**: 403 means authenticated and not permitted, which presupposes an established
identity — and presupposing one here would disclose that there is one.

**A second verified contact channel is refused, not replaced, and not a server fault**
(`X-TSK-004`, `INV-IDN-06`). `POST /v1/me/channels/verification` answers every reason a *token* is
refused — unknown, spent, expired, a lost race — with one `api.Forbidden`, so that a caller without
a live token cannot learn whether a verification is pending. `identity.VerifiedChannelAlreadyExists`
is not one of those reasons: the token was live, and the identity already has a verified channel of
that kind, which recovery keeps. It is reachable only by presenting a live token, so it discloses
nothing the uniform refusal protects. **It answered `500 api.InternalError` until `X-TSK-004`**: the
one-verified-per-kind index refused the write, and the store reported the database's answer as the
platform's failure — a `500` for a request that can never succeed by being retried.

### `kyc` — `KycErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `kyc.NoOpenCase` | 409 | You have no open verification case for this to apply to. |
| `kyc.OwnerNotEligible` | 422 | This party cannot be declared as a beneficial owner. |
| `kyc.OwnerAlreadyDeclared` | 409 | This party is already declared on the ownership graph. |
| `kyc.CaseNotAcceptingOwners` | 409 | The case is no longer accepting owner declarations. |
| `kyc.StakeExceedsWhole` | 422 | The declared stakes would exceed the whole of the organisation. |

**A distinct code because it is actionable** (`P1-TSK-018`'s test for earning one): the caller's
case was decided, changed circumstances are a *new* case (`INV-LIFE-04`), so the remedy is opening
one rather than retrying the upload. No enumeration concern applies — the caller is the
authenticated owner asking about their own case, the opposite of `party.RegistrationRefused`'s
stranger, so telling them the truth discloses nothing they do not already own.

**The four KYB codes** (`P2-TSK-016`) split the same way. `kyc.OwnerAlreadyDeclared`,
`kyc.CaseNotAcceptingOwners` and `kyc.StakeExceedsWhole` are about the caller's *own* graph and
are specific, because specificity there discloses nothing and each calls for different behaviour
(stop, too late, correct the stake). `kyc.OwnerNotEligible` is deliberately the opposite: it is
**one refusal for three internally distinct causes** — the named party does not exist, is itself
an organisation, or is not a registered customer with a verification case — because
`ownerPartyId` names a *third party*, and distinguishing the refusals would make the declaration
endpoint an oracle over other people's registrations (`INV-IDN-07`'s reasoning applied to a body
field). A malformed identifier lands on the same code: a value that can name nobody is just
another party that cannot be declared.

### `consent` — `ConsentErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `consent.ReconsentRequired` | 409 | The consent text has changed and requires re-consent; grant against the current version. |
| `consent.UnknownTextVersion` | 422 | No consent text with this version exists for this purpose. |
| `consent.ConsentRequired` | 409 | No current consent basis exists for this purpose; grant consent and retry. |

**Two codes concern the grant, one is the gate's, and there is deliberately no withdrawal code**
(`P2-TSK-018`): a withdrawal must not be refusable by anything but authentication, so there is no
withdrawal failure for a code to name. The two grant refusals are distinct because they demand
different client behaviour — `consent.ReconsentRequired` is actionable (fetch
`GET /v1/me/consents`, present the current words, grant against the current version), while
`consent.UnknownTextVersion` is a client defect to fix, since no amount of re-presenting text
produces a version that was never published. `consent.ConsentRequired` is the enforcement gate's
refusal (`INV-CNS-01`, declared by the first gated surface — `POST /v1/me/kyc`, `P2-TSK-006`):
**one code for three causes, on purpose** — no history, a latest withdrawal, and a grant lapsed
by a re-consent-demanding version are indistinguishable to every caller, so the code names the
remedy and never the cause. No enumeration concern applies to any of the three: consent texts
are the platform's most public artefact — the words shown to every customer — and the purposes
are a closed enum shared by everyone.

### `accounts` — `AccountsErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `accounts.AccountOpeningRefused` | 409 | The caller is not eligible to hold accounts; complete verification and retry. |
| `accounts.UnsupportedCurrency` | 422 | The platform does not operate accounts in this currency. |
| `accounts.AccountNotEmpty` | 409 | The account still holds a non-zero balance; empty it and retry. |

**The opening refusal is cause-blind, on purpose** (`P3-TSK-012`/`P3-TSK-013`): no customer
relationship, a verification still pending and a terminal one are one refusal — the
`consent.ConsentRequired` shape, actionable (complete verification and retry) and never an
oracle over why. **There is deliberately no accounts not-found code**: an unknown, not-yours or
malformed account identifier on the balance endpoint is `api.NotFound`, byte-identical across
its causes (the `P1-TSK-016` session reasoning — a distinct answer would confirm the identifier
belongs to somebody). `accounts.UnsupportedCurrency` names its subject because the currency is a
value the caller chose and must be able to correct, and the supported set is published by every
account the platform opens. `accounts.AccountNotEmpty` (`P3-TSK-014`) is the close's one refusal
with a code of its own — actionable (empty the account and retry), and its title and detail name
**no amount and no currency**: which balance refused is the caller's own to read from the balance
endpoint they already own, and a refusal that quoted the number would put a
`RESTRICTED-FINANCIAL` value into a response family that is also rendered into logs
(`INV-AUD-02`). A `SUSPENDED` agreement asked to close answers the generic `api.Conflict`:
suspension has no producer this phase, and unreachable surfaces do not earn vocabulary.

### `transfers` — `TransfersErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `transfers.UnknownDestination` | 422 | The destination does not resolve to a platform account that can receive funds. |
| `transfers.UnknownSource` | 422 | The source does not resolve to an account of the caller's that can send funds. |
| `transfers.NotReversible` | 409 | The transfer is not in a state that can be reversed. |

**One code for unknown and malformed alike, on purpose** (`P4-TSK-007`): a beneficiary's
destination identifier names a **third party's** product, so the refusal is byte-identical
across its causes (malformed-equals-absent, the `kyc.OwnerNotEligible` shape) — a split would
make the creation endpoint an oracle over which identifiers are well-formed-but-unknown. What
the code discloses is bounded to "no product with a customer wallet answers to this
identifier", behind an unguessable UUIDv7 — exactly what a transfer naming the same destination
would disclose through its own judgement. `P4-TSK-008` widened the code's service, not its
shape: on `POST /v1/transfers` it also answers the **beneficiary arm** — unknown, a
stranger's, malformed and **removed** one byte-identical refusal (a removed beneficiary
refuses new transfers, M4.3's fourth clause; "does not resolve to an account that can receive
funds" is literally the title). **There is deliberately no beneficiary not-found code**: an
unknown, not-yours or malformed beneficiary identifier on the removal endpoint is
`api.NotFound`, byte-identical across its causes (the `P1-TSK-016` reasoning), while the
caller's own already-removed row converges on `204`. The step-up refusal is **not** a transfers
code: it is `identity.AssuranceRequired`, the assurance vocabulary's own (`P1-TSK-018`),
because "step up and retry" is the identity concern however many surfaces demand it.

**`transfers.UnknownSource` is a 422, not a 404** (`P4-TSK-008`): the source is a body field —
a 404 describes the request URI, and `POST /v1/transfers` exists — and it is the caller's own
correctable value. One code for unknown, not-yours and malformed alike, because the resolution
port answers all three with one empty (`TransferParticipants.sourceOwnedBy`, `INV-IDN-07`'s
reasoning at a port); distinct from `UnknownDestination` because the remedies differ — a
different field to fix. **A `FAILED` judgement is not an error code at all**: insufficient
funds, an unpostable side, a currency mismatch, a self-transfer — and, from `P4-TSK-010`,
a limit or risk seam's refusal (`LIMIT_REFUSED`/`RISK_REFUSED`, reserved so Phase 13's
implementations change no contract) — are committed domain outcomes answered as `201` with
the reason in the body (ADR-0043/0044 — the asynchronous-outcome contract shape), never
members of this vocabulary.

**`transfers.NotReversible` is one 409 for every machine refusal** (`P4-TSK-009`): a `FAILED`
transfer moved no money and has nothing to reverse, an already-`REVERSED` one is already
corrected, and the loser of two concurrent reversals resumes onto the winner's committed row —
one code, named for what is *checked* (the machine's edge, the `NOT_ACTIVE` lesson) rather than
for the commonest cause. The caller is a `TRANSFER_REVERSE` holder reading a state their `GET`
already discloses, so no oracle is opened; the remedy is the same in every case — read the
transfer. An unknown or malformed identifier on the reversal endpoint is `api.NotFound`,
byte-identical across its causes. A destination product closed since the transfer surfaces the
ledger's own `ledger.AccountNotPostable` (409) with nothing written — the recorded corner, not a
transfers code, because the refusing mechanism is `V007`'s trigger and the vocabulary is the
ledger's.

### `paymentmethods` — `PaymentmethodsErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `paymentmethods.TokenisationUnavailable` | 503 | The instrument could not be tokenised right now; retry later. |
| `paymentmethods.InstrumentNotTokenised` | 422 | The tokenisation grant was refused; obtain a fresh grant and retry. |
| `paymentmethods.GrantExchangeRefused` | 422 | The rail provider refused the grant; obtain a fresh grant and retry. |
| `paymentmethods.GrantExchangeUnavailable` | 503 | The bank account could not be registered right now; retry later. |
| `paymentmethods.PayeeCheckNoMatch` | 409 | The payee check found no match; registering needs the customer's explicit acknowledgement. |

`P7-TSK-007` adds the bank door's three (ADR-0062 §2): the refused/unavailable pair restates
the attach's remedy split at the grant exchange — with the recorded asymmetry that the bank
grant is single-use, so the 503's retry is a new keyed request and may need a fresh grant —
and `PayeeCheckNoMatch` (409) is the consent gate: the registration as asked conflicts with a
recorded judgement that requires the customer's explicit say-so, and the acknowledged retry
is a different request under a new key.

The attach's two refusals, split by remedy (`P5-TSK-005`): the 503 is the platform's first —
and deliberate — service-unavailable domain code, because a tokenisation outage is not the
caller's fault (422 would blame their grant), not a state conflict (409), and not our defect
(500 says *our* side broke, §3); the attach genuinely cannot proceed and **never falls back to
holding raw detail** (`INV-PAY-02`), so retry-later is the honest answer, one code across every
unavailable cause. The 422 is an *explicit parsed refusal* of the caller's own grant — renew
and retry. **There is deliberately no payment-method not-found code**: the detach's unknown,
not-yours and malformed are one `api.NotFound` (the beneficiary reasoning, verbatim).

### `payments` — `PaymentsErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `payments.NoWallet` | 422 | You have no account that can receive a payment. |
| `payments.UnknownInstrument` | 422 | The payment method does not resolve to an active instrument of yours. |
| `payments.CurrencyMismatch` | 422 | The payment currency must match your account's currency. |
| `payments.NotConfirmable` | 409 | The payment is not awaiting confirmation. |
| `payments.NotCancellable` | 409 | The payment can no longer be cancelled. |
| `payments.ProviderUnavailable` | 503 | Payments are temporarily unavailable. |
| `payments.NotRefundable` | 409 | The payment has no captured amount to refund. |
| `payments.RefundExceedsCaptured` | 422 | The refund would exceed the captured amount. |
| `payments.RefundUnfunded` | 409 | The account cannot fund this refund right now. |
| `payments.NoEligibleRail` | 422 | No payment rail can carry this payment right now. |
| `payments.UnknownRail` | 422 | The named payment rail is not declared by this platform. |
| `payments.RoutingPolicyNotForward` | 422 | A routing policy version takes effect forward, never backward. |
| `payments.ReversalNotSupported` | 409 | This payment's rail does not support reversal. |
| `payments.WithdrawalUnfunded` | 422 | The wallet's available balance cannot cover this withdrawal. |
| `payments.WalletPaymentUnfunded` | 422 | The wallet's available balance cannot cover this payment. (`P7-TSK-011`: judged under the wallet's lock; nothing written, the same confirmation succeeds after a top-up.) |
| `payments.WithdrawalCurrencyMismatched` | 422 | A withdrawal is priced in its wallet's own currency. |
| `payments.DisputeNotRespondable` | 409 | This dispute takes no evidence or answer at its current stage. (`P7-TSK-014`, ADR-0061 section 7: only a `CHARGED_BACK` dispute takes one; an inquiry has nothing to contest, a represented or resolved dispute takes no answer - nothing written.) |
| `payments.DisputeDeadlinePassed` | 409 | The network's deadline to answer this dispute has passed. (`P7-TSK-014`: the platform refuses its own late dispatch; the outcome stays the network's.) |
| `payments.DisputeAlreadyAnswered` | 409 | This dispute already has an answer in progress or taken. (`P7-TSK-014`: one live answer per dispute; the evidence set froze with it.) |
| `payments.DisputeEvidenceRequired` | 422 | A representment needs at least one evidence document. (`P7-TSK-014`) |
| `payments.DisputeEvidenceLimitReached` | 422 | This dispute already holds the most evidence documents one answer can carry. (`P7-TSK-014`: five, the whole set rides one outbound submission.) |
| `payments.DisputeAnsweredByItsMerchant` | 409 | This payment's merchant answers its own dispute. (`P7-TSK-014`, ADR-0061 section 7: the operator acts only for a payment with no merchant.) |

The payment surface's vocabulary (`P5-TSK-011`) is **the refusals only** — requests the
platform declined to judge, with nothing written. A *judged* failure is never an error code: a
declined card is a `200` whose body says `FAILED` with the **mapped** `failureReason`
(`INV-PAY-03`: the provider's own vocabulary lives in the retained evidence and appears in no
response — needle-tested). The three 422s are values the caller supplied or standing the
caller owns, decided **before the idempotency claim is consumed**, so a corrected request
retries under the same key. `payments.UnknownInstrument` is one code for unknown, not-yours,
malformed *and detached-since-creation* alike — a split would make the payment endpoints an
oracle over other people's instruments (the `transfers.UnknownSource` fold). The two 409s are
named for the machine edge that is *checked*, never the commonest cause (the `NOT_ACTIVE`
lesson), and the remedy for both is `GET /v1/payments/{id}`, whose view carries the state.
`payments.ProviderUnavailable` (503) is the `paymentmethods.TokenisationUnavailable` decision
verbatim: an **unconfigured deployment** declines to judge, distinct in kind from the in-body
`FAILED(PROVIDER_UNAVAILABLE)` — a refused connection to a *configured* provider is knowledge,
a judged outcome. **There is deliberately no payment not-found code**: unknown, not-yours and
malformed on every `{id}` route are one `api.NotFound` (the `P1-TSK-016` reasoning).

The refund's three (`P5-TSK-015`) are the privileged surface's refusals, decided **before the
wire call** with nothing dispatched. `payments.NotRefundable` (409) is the machine edge — no
`CAPTURED` attempt exists to refund against, whatever the commonest cause. `payments.
RefundExceedsCaptured` (422) is the domain bound (`INV-PAY-05`): the requested amount
plus every non-`FAILED` refund of the attempt would exceed the captured amount — judged under
the attempt row lock, with `V004`'s trigger beneath, so a *sequential* over-refund is this
honest 422 and never the schema's own `23514`. **Its meaning extended by `P7-TSK-013`**
(`INV-DSP-01`, ADR-0061 §3), the code and title unchanged: the standing chargebacks'
attributions count beside the refunds, so a refund after a chargeback has taken the value back
is refused past what the capture left — the same lock, `V021`'s re-stated trigger beneath. `payments.RefundUnfunded` (409) is
`INV-BAL-04` at the surface: the hold that reserves what the refund will take cannot be
placed because the available balance no longer covers it — a conflict with the account's
*current state*, retriable when funds return, which is why it is a 409 and not a 422. For a
checkout payment the account is the merchant's payable and the hold is the refund's **net**,
not its gross (`P6-TSK-015`, ADR-0054): the code means the payable cannot fund that net even
counting the one credit a merchant is extended, the fee share the platform retained
(`INV-MER-07`). The merchant's next captures are what fund it. The refund's 404
folds into `api.NotFound` exactly as above; the operator learns nothing a customer would not.

### `merchant.*` — the counterparty surface (`P6-TSK-003`)

| Code | Status | Meaning |
|---|---|---|
| `merchant.NotEligible` | 422 | The party cannot be onboarded as a merchant. |
| `merchant.IllegalTransition` | 409 | The merchant's current status does not permit this change. |
| `merchant.UnsupportedCurrency` | 422 | The settlement currency is not supported. |
| `merchant.NotKeyable` | 409 | A closed merchant cannot be issued an API key. |
| `merchant.FeeScheduleNotForward` | 422 | A fee schedule version takes effect forward; it cannot be backdated. |
| `merchant.FeeCurrencyMismatch` | 422 | The fee schedule's currency does not match. |
| `merchant.SelfApprovalRefused` | 409 | A payout destination change requires a second approver distinct from its proposer. |
| `merchant.DestinationChangePending` | 409 | A payout destination change is already open for this merchant; withdraw it first. |
| `merchant.DestinationChangeNotOpen` | 409 | The payout destination change is no longer open to this decision. |
| `merchant.DestinationNotTokenised` | 422 | The destination grant was refused; obtain a fresh grant and retry. |
| `merchant.DestinationTokenisationUnavailable` | 503 | The destination could not be tokenised right now; retry later. |
| `merchant.PayoutUnfunded` | 409 | The payable cannot fund this payout. |
| `merchant.NoEffectiveDestination` | 409 | The merchant has no effective payout destination. |
| `merchant.NotTrading` | 409 | This merchant cannot initiate payouts while suspended or closed. |
| `merchant.NotSettled` | 409 | This merchant is still owed money or has a payment in flight, so it cannot be closed. |
| `merchant.PayoutCurrencyMismatch` | 422 | A payout must be in the merchant's settlement currency. |
| `merchant.PayoutProviderUnavailable` | 503 | Payouts are unavailable right now; retry later. |

`merchant.FeeScheduleNotForward` is `INV-MER-03`'s refusal, and it is the one an operator
actually meets: *"make this effective from the first of the month"* is a natural thing to type
on the second of the month, and it is a repricing of every capture in between. A `422` rather
than a `409`, because the request is coherent and the remedy is the caller's.

`merchant.FeeCurrencyMismatch` covers **both** boundaries — a version whose fixed part is in
the wrong currency, and an assignment to a merchant that settles in another — because both say
the same thing to the same reader: this schedule does not price that money. Cross-currency fees
are Phase 9's. There is no fee-schedule not-found code: unknown and malformed identifiers are
one `api.NotFound`, the merchant surface's standing rule.

**The payout destination's five codes (`P6-TSK-011`, ADR-0056).**
- `merchant.SelfApprovalRefused` is `INV-AUD-04` refusing the proposer's own approval — a `409`,
  the ledger's `SelfApprovalRefused` status for the same control: the request is well formed and
  the change still waits for a second person. It is the one refusal here whose `DENIED` audit
  record commits before the response is written.
- `merchant.DestinationChangePending` is the one-open-change rule: withdraw the open change first.
- `merchant.DestinationChangeNotOpen` is every decision asked of a change that has moved on —
  approved, rejected, withdrawn or taken effect. It is not `merchant.IllegalTransition`, whose
  title speaks of the *merchant's* status.
- `merchant.DestinationNotTokenised` (`422`) and `merchant.DestinationTokenisationUnavailable`
  (`503`) are the exchange's refusal and its absence, the `paymentmethods` pair for bank data. A
  grant shaped like an account number never reaches either: it is `api.ValidationFailed` naming
  `destinationToken`, never the value.
- Unknown, malformed and another merchant's destination identifiers are one `api.NotFound`.

**The payout's five codes (`P6-TSK-012`, ADR-0051, ADR-0057).**
- `merchant.PayoutUnfunded` is `INV-MER-05` refusing a payout the payable cannot fund, judged
  inside the payable account's lock with every in-flight payout and refund already held — the
  refund's `payments.RefundUnfunded` for the merchant's own money. A payable left negative by a
  retained fee (ADR-0054) refuses every amount. Nothing is written, the key included, so a later
  retry may fit.
- `merchant.NoEffectiveDestination` is ADR-0056 §9's refusal: a proposal or a cooling-off
  changes nothing until the platform effects it, so there is nowhere yet to pay.
- `merchant.NotTrading` is suspension gating new dispatches, checkout's `checkout.NotTrading`
  vocabulary for the same fact. A suspended merchant's key already fails authentication, so over
  the merchant route this is the race's refusal; over the operator route it is the answer.
- `merchant.PayoutCurrencyMismatch` (`422`): a merchant has one payable, in its settlement
  currency; multi-currency payouts are Phase 9's.
- `merchant.PayoutProviderUnavailable` (`503`) is a deployment with no payout provider
  configured — nothing claimed, nothing held. A provider that is configured but unreachable is
  NOT this code: that is an honest `201` whose payout is `FAILED` (`PROVIDER_UNAVAILABLE`) when
  nothing was sent, or `UNKNOWN` with its hold standing when something may have been.
- Unknown, malformed and another merchant's payout identifiers are one `api.NotFound`; an
  operator naming an unknown merchant is the same.

**The close's code (the Phase 6 → 7 transition).**
- `merchant.NotSettled` (`409`) refuses a close while the merchant's payable is non-zero, a hold
  stands on it, or a payment in flight will credit it — judged under the merchant row's and then
  the payable's lock. A closed merchant can be paid out by nothing, so closing one still owed money
  left a liability the platform could never settle. Phase 3's `accounts.AccountNotEmpty` is the
  precedent. Nothing is written.

`merchant.NotKeyable` refuses only a **closed** merchant. A `SUSPENDED` one may still be
issued keys: suspension is reversible, its keys already refuse at authentication because the
lookup joins the merchant's standing (`P6-TSK-002`), and refusing issuance too would make an
operator repeat the step when the suspension lifts. There is no merchant-API-key not-found
code either — unknown, malformed and *another merchant's* key are one `api.NotFound`, because
the tenant rides in the statement (`INV-MER-01`) and all three produce the same empty answer.
**Authentication failures are one `api.Unauthenticated`**: unknown key, wrong secret, revoked
key, suspended merchant and malformed credential are indistinguishable, because a door that
says which is an oracle over other companies' integrations.

`merchant.NotEligible` deliberately conflates its causes — no such party, a person party, no
customer relationship, one still under verification — because an onboarding surface that
distinguishes them is an oracle over parties and their compliance standing (the
`INV-IDN-07` reasoning at a new boundary). There is no merchant not-found code: unknown and
malformed are the one `api.NotFound`, and another tenant's cannot even be asked for — since
`P6-TSK-002` a merchant key's routes take the tenant from the key and no path names a merchant,
while the operator routes that do name one act across tenants by permission (`INV-MER-01`).
*(This said "when `P6-TSK-002`'s tenant scoping arrives" until the Phase 6 review,
`P6-DOC-001`; it has arrived.)*

### `checkout.*` — the purchase experience (`P6-TSK-007`)

| Code | Status | Meaning |
|---|---|---|
| `checkout.NotPriceable` | 422 | This merchant has no fee schedule for this currency, so a checkout cannot be priced. |
| `checkout.SaleBelowFee` | 422 | This amount does not cover the merchant's fee, so it cannot be sold. |
| `checkout.NotTrading` | 409 | This merchant is not trading, so a checkout cannot be opened or paid. |
| `checkout.SessionExpired` | 409 | This checkout session has expired. |
| `checkout.NotConfirmable` | 409 | This checkout session is not awaiting confirmation. |
| `checkout.NotAbandonable` | 409 | This checkout session cannot be withdrawn. |

`checkout.NotPriceable`, `checkout.SaleBelowFee` and `checkout.NotTrading` are refused **at
session creation** rather than discovered at the capture. That is the whole reason they exist
as codes: an unpriced or untraded session would fail inside the transaction that moves money,
*after* the customer had paid — the difference between a merchant fixing their configuration
and a customer's money needing a refund.

The offer is **priced at creation** (`P6-TST-001`, ADR-0058), under the version the session
will carry, and two refusals come out of that pricing. `checkout.NotPriceable` also answers an
offer in a currency the merchant's schedule does not price: a schedule prices one currency, and
the fee arithmetic refuses a foreign gross by name. `checkout.SaleBelowFee` answers an amount
whose fee meets or exceeds it — a sale that would net the merchant nothing or less, drive its
payable below zero at capture, and leave the sale's refund waiting on the merchant's other
sales. Its detail names no amount. The same rule is re-asserted when the confirmation pins the fee, so a session opened
before the rule existed is refused there with the same code, its transaction rolled back.
Both refusals come before the idempotency claim: nothing is written and the key is not spent.

**At creation, `checkout.NotTrading` is the race's refusal, not the ordinary one**, and the
distinction is worth stating because a reader will otherwise expect to see it. A suspended or
closed merchant's API key does not authenticate at all: the key lookup carries the merchant's
standing *in the join* (`P6-TSK-002`), so the ordinary answer to a suspended merchant is `401`,
with no tenant resolved and therefore no tenant to refuse. What `NotTrading` guards is the
interleaving where a suspension commits **between** that authentication read and the command's
own authoritative one — which is exactly why the command reads standing again rather than
trusting the credential that got it here. It is proved by driving the command directly, because
HTTP cannot produce the interleaving on demand. **At the customer's confirmation it is the
ordinary answer**: the customer presents a session and a token, not the merchant's key, so
nothing at authentication asks the merchant's standing and the confirmation reads it itself — an
offer made before a suspension cannot be paid after it, while a payment already admitted still
lands. *(The confirmation has refused since the Phase 6 review, `P6-DOC-001`, which found it
never asked.)*

`checkout.SessionExpired` and `checkout.NotConfirmable` are deliberately distinct, because
they say different things to the customer looking at the page: one means *too late*, the
other means *already done*. Expiry is answered whether the sweeper has arrived or not — the
aggregate checks the clock as well as the state (ADR-0053 §5).

`checkout.NotAbandonable` (`P6-TSK-008`) is what a missing edge looks like at the surface. A
merchant may withdraw an offer nobody has paid for; it may **not** withdraw one whose payment is
already in flight, because that would leave money moving toward a purchase with no commercial
home — the state `INV-MER-06` exists to prevent. The machine has no
`PAYMENT_PENDING → ABANDONED` edge at all, so the aggregate refuses it, the transition trigger
refuses it, and this code is the third rank. The same code answers a session already expired,
abandoned or paid, because the remedy for all of them is the same read.

**There is no checkout not-found code.** An unknown session id, a malformed one, another
merchant's, and a token that opens nothing are one `api.NotFound`. For the merchant surface
that is `INV-MER-01`'s tenancy oracle; for the customer it is stronger still, because a
checkout token is *guessed at* rather than typed, and telling a guesser that a session exists
but is not theirs is the only bit they need.

**The confirmation also answers the payments codes it inherits, and two reached a customer
wrongly until the Phase 6 → 7 transition.** An instrument that is unknown, detached or not the
payer's is `payments.UnknownInstrument` (422), the create door's own refusal - nothing written,
the session still payable; it was a `500`. And a second holder of the token on a session
**mid-payment** reached the payments surface's own `404`, whose detail - *no such payment* -
told them the token was live and the session being paid; it now gets the session's one `404`,
the answer a token that opens nothing gets. A confirmation that loses the open to a concurrent
one converges rather than answering `checkout.NotConfirmable`: a double-click is not a refusal.

### `ledger` — `LedgerErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `ledger.UnbalancedAdjustment` | 422 | The adjustment's debits and credits must be equal per currency, at one scale. |
| `ledger.UnknownAccount` | 422 | A line names an unknown ledger account, or a currency foreign to it. |
| `ledger.AccountNotPostable` | 409 | The account no longer accepts postings. |
| `ledger.SelfApprovalRefused` | 409 | An adjustment requires a second approver distinct from its initiator. |
| `ledger.ProposalNotOpen` | 409 | The adjustment proposal is already decided; a new adjustment is a new proposal. |
| `ledger.AdjustmentOnReconciledPosition` | 422 | A reconciled position cannot be adjusted here; value there moves only through a reconciliation break's resolution. |
| `ledger.AdjustmentOriginMismatch` | 409 | The proposal belongs to another origin's door and cannot be decided here. |

The ledger's one public surface is the adjustment (`P3-TSK-017` — plan §9: posting is an
internal API), so its vocabulary is the adjustment's refusals. **No title or detail ever names
an amount** (`INV-AUD-02`): which numbers were involved is the caller's own request.
`ledger.UnbalancedAdjustment` and `ledger.UnknownAccount` are 422s — values the caller chose
and must correct (`P1-TSK-026`'s reasoning), decided **before any idempotency claim is
consumed**, so the operator fixes the request and retries under the same key.
`ledger.UnknownAccount` is one code for two causes (an unknown account, a currency foreign to
it) because the remedy is one and an operator holding `LEDGER_ADJUST` reads the chart anyway —
no oracle is opened. `ledger.AccountNotPostable` (409) is `P3-TSK-014`'s rule meeting this
surface: actionable in the `P1-TSK-018` sense — adjust a different account, not this one
again. **The four-eyes pair arrived with `P3-TSK-021`**: `ledger.SelfApprovalRefused`
(409) is `INV-AUD-04`'s named negative made actionable — the proposal is fine and still
standing, so the remedy is a second authorised person, and nothing was written;
`ledger.ProposalNotOpen` (409) is one code for both decision surfaces because the remedy is
one — read the proposal's outcome, and raise a *new* proposal. The converging cases (the
same approver's retry, a repeated rejection) never produce either code, and an unknown or
malformed proposal identifier is the ordinary `api.NotFound`, one answer for both causes
(`P1-TSK-016`). A missing reason is the ordinary `api.ValidationFailed`, because the boundary's bean
validation owns required-field refusals; the reason's **bound** lives in three reconciled
places (the DTO, `V004`'s `CHECK`, `AuditRecord`). **The closure pair arrived with
`P8-TSK-006`** (ADR-0071): `ledger.AdjustmentOnReconciledPosition` (422) refuses a `MANUAL`
line on a clearing or suspense purpose — decided before the claim, so the key survives the
refusal, and again at a legacy proposal's approval; the detail names the remedy (a break
resolution) and never the account. `ledger.AdjustmentOriginMismatch` (409) is each door
refusing the other origin's proposals: a reconciliation proposal is decided by the
resolution flow that also moves the break, a manual one by the generic four-eyes door — the
proposal is fine and still standing, which is what makes the 409 actionable.

### `settlement` — `SettlementErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `settlement.SourceUnknown` | 422 | No declared settlement source has this code. |
| `settlement.SourceRetired` | 409 | This settlement source is retired and accepts no deliveries. |
| `settlement.FileTooLarge` | 413 | The delivery exceeds the settlement file bounds. |
| `settlement.DeliveryRefused` | 422 | The delivery was refused by the door screen; only metadata was recorded. |
| `settlement.FileNotFound` | 404 | No settlement file has this identifier. |
| `settlement.FileNotAttestable` | 409 | This settlement file cannot be attested. |
| `settlement.AttestationBySubmitter` | 409 | The uploader cannot attest their own file; a second person must. |
| `settlement.BatchNotFound` | 404 | No settlement batch has this identifier. |

The evidence surfaces' refusals (`P8-TSK-003`, ADR-0066). **No title or detail ever carries a
value from the file** (`INV-PAY-02`, `INV-RAIL-03`): `settlement.DeliveryRefused` (422) names
the finding's line and field and nothing else — the refusal wrote its metadata row and audit
record, and recovery is re-presentation of a clean file. `settlement.FileTooLarge` (413) is
the decoded-content bound (8 MiB, 50,000 records), decided by the **domain** behind the
route's own transport carve-out, so an over-bound delivery leaves its audit record rather
than dying anonymously at a filter. `settlement.SourceUnknown` (422) is a body field the
caller can correct, refused before anything is claimed or written;
`settlement.SourceRetired` (409) is operational state. `settlement.FileNotFound` (404) is a
deliberate departure from the payments rule that an unknown identifier is the anonymous
`api.NotFound`: every settlement route sits behind an operator permission, so the surface is
no oracle over anyone else's resources — unknown and malformed ids are still ONE answer, and
a guessed id records nothing. The attestation pair are 409s — the caller holds the
permission; the file's own facts refuse the act: `settlement.AttestationBySubmitter` is
`INV-SET-07`'s named negative made actionable (the remedy is a second person, and the
database `CHECK` stands behind the domain refusal), and `settlement.FileNotAttestable` is one
code for the remaining causes (not an upload; another person's attestation already stands;
since `P8-TSK-008`, a terminal file — which also answers a repeat decline, the machine being
the record) because the remedy is one — read the file. The same attester's retry converges
and produces neither. `settlement.BatchNotFound` (404, `P8-TSK-008`) is the `FileNotFound`
departure's reasoning at the batch read: unknown and malformed ids one answer, nothing
recorded.

### `reconciliation` — `ReconciliationErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `reconciliation.RunNotFound` | 404 | No reconciliation run has this identifier. |
| `reconciliation.DecisionNotFound` | 404 | No match decision has this identifier. |
| `reconciliation.AllocationNotFound` | 404 | No allocation has this identifier. |

The matcher's explanation doors (`P8-TSK-011`, ADR-0068 §7). All three follow the
`settlement.FileNotFound` departure: every route sits behind
`RECONCILIATION_INVESTIGATE`, so the surface is no oracle over anyone else's resources,
and an investigator chasing a break is told plainly that the id is wrong — unknown and
malformed ids are still ONE answer, and a guessed id records nothing.

## 3a. Rejection at the boundary

Untrusted input is refused before any domain code runs (`P0-TSK-025`).

| Rejected | Code | Where |
|---|---|---|
| Body fails declared constraints | `api.ValidationFailed` (422) | Bean Validation, before the handler is entered |
| No `Idempotency-Key` where one is required | `api.IdempotencyKeyRequired` (422) | An interceptor, before the handler is entered |
| `Idempotency-Key` present and unusable | `api.ValidationFailed` (422) | The same interceptor - the client supplied one and must fix it |
| Body exceeds the size limit | `api.PayloadTooLarge` (413) | A filter, before the body is read |
| Body will not parse | `api.MalformedRequest` (400) | The message converter |

**Validation failures never reach domain code**, and that is a claim about *where*, not about the
response — a 422 returned after the handler ran and did half the work looks identical from
outside. The tests count handler entries rather than reading the response.

**The size limit closes both routes.** A declared `Content-Length` over the limit is refused
without reading a byte. A chunked request declares no length at all — which is precisely how a
caller opts out of a header check — so the body is also wrapped in a counting stream that stops
at the limit. Default 1 MiB, `finapp.api.max-request-bytes`.

**Every validation path gives the same answer.** A constraint produces `api.ValidationFailed`
(422) whether it is declared on a request body, on a method parameter, or on a method parameter
of a `@Validated` bean. Spring validates those on three different mechanisms, and left to their
defaults they returned 422, 400 and **500** respectively — a client cannot write error handling
against that, and the 500 tells the caller our side failed for something only they can fix.

**Rejected values are never echoed.** A validation detail names the field and the constraint,
both of which are ours. The value is the caller's, and reflecting untrusted bytes into a response
is how an error message becomes a vector (`INV-AUD-02`).

### Correlation at the boundary

Every request is given a correlation identifier by a filter at the highest precedence, and every
response carries it in the `X-Correlation-Id` header as well as in the problem detail.

The filter's ordering is a **requirement, not a preference**: its scope must wrap error handling,
not merely the handler. A scope entered inside a controller closes before the exception it raised
reaches an exception handler, so the renderer would run with nothing in scope and the identifier
would reach the log and never the client.

A client may supply the header so its logs and ours can be joined. That value is untrusted:
`CorrelationId` validates it against a default-deny charset and **rejects rather than sanitises**,
because a silently rewritten identifier breaks the client's own correlation without telling
anyone — they log one value, we log another, and the two can never be joined. A rejected header
does not fail the request; a malformed diagnostic hint is not a reason to decline a payment.

## 4. Codes are permanent

A client's error handling is written against these strings. Renaming one silently changes the
meaning of every client's `switch` — a breaking change wearing a refactor's clothes, and unlike a
broken build it fails at the customer's end. **Add a new code and deprecate the old one.**

Codes are namespaced by module (`api.NotFound`, `transfers.InsufficientFunds`) so two modules
cannot collide on a bare name and give one string two meanings. Enforced by the registry test.

## 5. Why the status lives with the code

An HTTP status in a module that knows nothing about HTTP looks misplaced. The alternative is
worse: a code-to-status mapping maintained in the rendering layer, far from the code it
describes, where two modules can disagree about whether the same failure is a 409 or a 422. The
status is part of what the code *means* — whether the caller did something wrong, and whether
retrying could help — so it belongs with the code. It is an `int`, so nothing about this couples
the platform to a web stack.

## 6. Where the pieces live

| Piece | Module | Why |
|---|---|---|
| `ErrorCode`, `PlatformErrorCode`, `ProblemDetail`, `ApiException` | `platform` | The contract is published and outlives any web stack; it must not be a function of one |
| `ApiErrorHandler`, `ProblemDetailBody` | `app` | Routing, content negotiation and error rendering are `app`'s (`MODULE_ARCHITECTURE.md` §M10) |

`ProblemDetailBody` is the **one place the JSON is decided**. Serialising the platform's record
directly was tried and produced `"correlationId":{}` and `"detail":null` — the identifier a
client is meant to quote silently absent while its member was present. It also meant a field
added to the record would publish itself to every client with no review and no failing test.

## 6a. The machine-readable contract

Everything above is also published as OpenAPI, at [`docs/api/openapi.json`](../api/openapi.json).
That document is **generated from the running application on every build** and compared byte for
byte against the committed copy, so a change to the error contract cannot reach a client without a
human seeing a build failure that names it (ADR-0015).

Each code is a reusable response component keyed by the code itself, and each pins three things as
data rather than prose: the `status` it always arrives with, the `code` a client switches on, and
its stable problem `type`. Publishing the status only inside an English sentence was a real gap -
found by deliberately changing `api.Conflict` from 409 to 422 and watching the change be reported
as a harmless rewording.

Every route the platform publishes is served under `/v1`. Error responses are not exempt: a 404
from an unknown path under `/v1` and a 404 from a path outside it are the same contract.

## 7. Adding a code

1. Add the constant to the owning module's `ErrorCode` enum, with a namespaced code, a status
   that reflects what the caller should do, and a title safe to show anyone.
2. Add the row to §3.
3. Raise it with `ApiException`, putting diagnostics in the log message and only
   stranger-safe text in the client detail.
4. Regenerate the OpenAPI document and commit it. Adding a code is a *compatible* change and the
   build will say so; the step exists because the contract is only published once it is committed.

Steps 1 and 2 are checked against each other by the build, and step 4 is what the build asks for
once they agree.
