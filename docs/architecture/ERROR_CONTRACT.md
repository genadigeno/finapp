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

### `ledger` — `LedgerErrorCode`

| Code | Status | Meaning |
|---|---|---|
| `ledger.UnbalancedAdjustment` | 422 | The adjustment's debits and credits must be equal per currency, at one scale. |
| `ledger.UnknownAccount` | 422 | A line names an unknown ledger account, or a currency foreign to it. |
| `ledger.AccountNotPostable` | 409 | The account no longer accepts postings. |
| `ledger.SelfApprovalRefused` | 409 | An adjustment requires a second approver distinct from its initiator. |
| `ledger.ProposalNotOpen` | 409 | The adjustment proposal is already decided; a new adjustment is a new proposal. |

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
places (the DTO, `V004`'s `CHECK`, `AuditRecord`).

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
