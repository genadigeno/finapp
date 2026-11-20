# API Conventions

How every HTTP endpoint on this platform behaves, so that a client author learns the rules once
rather than per module.

**This document and the code are held to each other.**
[`ApiConventionsAreAccurateTest`](../../app/src/test/java/com/finapp/app/api/ApiConventionsAreAccurateTest.java)
fails the build when a factual claim below stops matching the implementation.

---

## 0. How to read this

Phase 0 publishes **no business endpoint**. Some conventions below are therefore in force today
and some are decisions waiting for their first endpoint. Confusing the two is how a document
becomes untrustworthy, so every section carries one of these labels:

| Label | Meaning |
|---|---|
| **Implemented** | The platform behaves this way now. The class and the test that prove it are named. |
| **Decided, not yet implemented** | The convention is settled; nothing exercises it yet. The owning task is named. |

A section with no label is a defect, and the guard test treats it as one.

**Why decide a convention before there is anything to apply it to.** Because the alternative is
deciding it five times. Pagination invented independently by `accounts`, `transfers` and
`payments` gives three shapes a client must learn and no way back — a published API cannot be
retrofitted into consistency without a version bump. That is the cost `P0-DOC-003` exists to
avoid; the risk it carries is a convention that turns out to be wrong on contact with a real
endpoint, which is cheap to correct while nothing implements it.

**Where the reasoning lives.** This document states the rules. The arguments for them are in
[ADR-0015](../adr/ADR-0015-api-versioning-and-contract-publication.md) (versioning, contract
publication, deprecation), [ADR-0016](../adr/ADR-0016-health-liveness-and-readiness.md)
(operational endpoints), [ADR-0004](../adr/ADR-0004-idempotency-strategy.md) (idempotency) and
[`ERROR_CONTRACT.md`](ERROR_CONTRACT.md) (errors).

---

## 1. Versioning — **Implemented**

Every route this platform publishes is served under **`/v1`**.

Controllers declare no version. `ApiVersionConfiguration` applies the prefix once, in the
composition root, to every handler under `com.finapp.` — so a controller cannot forget it, and an
unversioned route cannot be published by accident. An unversioned route is not a cosmetic slip: it
can never be changed, because there is no second version to move its clients to.

The number increments **only** for a change that breaks a client. There is no minor component: a
minor version that never breaks anything is a number clients would have to send and could never
act on. Everything compatible — a new endpoint, a new optional field, a new error code — happens
inside the current version.

**Operational endpoints are deliberately unversioned** (ADR-0016): `/actuator/health/liveness`,
`/actuator/health/readiness` and `/actuator/info`. They are consumed by orchestrators and scrapers
whose configuration is deployment-scoped, not by clients holding a contract.

*Proven by `ApiVersioningTest`, including that the unprefixed path is **not** also served.*

## 2. The published contract — **Implemented**

[`docs/api/openapi.json`](../api/openapi.json) is the contract. It is generated from the running
application on every build and compared byte for byte against the committed copy; any difference
fails the build, and each difference is labelled `BREAKING` or `COMPATIBLE`.

Nothing about OpenAPI is deployed. The generator is test-scope, so a running instance serves no
`/v3/api-docs`.

*Proven by `OpenApiContractTest`.*

## 3. Errors — **Implemented**

Every failure is an RFC 9457 problem detail with media type **`application/problem+json`**.

| Member | Always present |
|---|---|
| `type`, `title`, `status`, `code` | yes |
| `detail`, `instance`, `correlationId` | when there is something to say |

**Clients switch on `code`**, never on `title` (prose, may be reworded) and never on `status`
alone (several codes share one). Absent members are absent, not null.

The catalogue of codes is in [`ERROR_CONTRACT.md`](ERROR_CONTRACT.md) §3 and is deliberately
**not** repeated here — two copies of a list is one copy too many, and the catalogue there is
already reconciled with the code by `ErrorCodeRegistryTest`.

Codes are permanent. Renaming one is a breaking change wearing a refactor's clothes.

## 4. Correlation — **Implemented**

Every response carries **`X-Correlation-Id`**, on success and on failure, and the same value
appears in the problem detail's `correlationId`. It is the one thing that usefully connects a
person reporting a problem to the record of what happened, so a client should log it and quote it.

**The platform mints it, always.** A client **may** supply `X-Correlation-Id`, but that value is
**never adopted as the flow's identifier** (ADR-0034). It is echoed back in
**`X-Client-Correlation-Id`** and carried nowhere else — not a log line, not a span, not a stored
column, not the problem detail.

That value is untrusted, and is validated before it is echoed:

- allowed characters are `A-Z a-z 0-9 . _ : @ / + = -`
- maximum length 128
- a value failing either rule is **dropped, not sanitised**, and the request still succeeds

**Why it is not adopted.** A correlation identifier reaches every log line, every span, four
durable columns and every problem-detail body. A caller could therefore place personal or financial
data into systems with different access control and months of retention (`INV-AUD-02`) —
`jane.doe@example.com`, `acct:GB29NWBK60161331926819`, `customer-1990-05-14` and `+447700900123`
were all confirmed accepted by probe. Narrowing the charset does not fix it: a date of birth, a
phone number and an account number are alphanumeric, and a charset narrow enough to exclude them
cannot carry a UUID or a trace value.

**You can still join your logs to ours.** Log the `X-Correlation-Id` we return — it identifies the
flow on our side — and use `X-Client-Correlation-Id` to match a response to a request you no longer
hold a connection for. What you cannot do is search our logs by a value you chose, which is
precisely the property that made the disclosure possible.

Dropping rather than sanitising is deliberate: a silently rewritten identifier would come back
subtly different from what was sent, which is worse than not coming back at all. And a malformed
diagnostic hint is never a reason to decline a payment.

*Proven by `CallerCorrelationIsNotPropagatedTest` and `RequestValidationTest`; charset and bound
live in `CorrelationId`.*

## 5. Request limits and validation — **Implemented**

| Rule | Behaviour |
|---|---|
| Body size | Default **1048576** bytes, configurable by `finapp.api.max-request-bytes` |
| Over the limit | `api.PayloadTooLarge` (413) |
| Body fails declared constraints | `api.ValidationFailed` (422) |
| Body will not parse | `api.MalformedRequest` (400) |
| A public door's per-source limit reached | `api.TooManyRequests` (429), with `Retry-After` in whole seconds |
| A public door's count could not be taken | `api.TemporarilyUnavailable` (503), the request not served |
| The instance shed the request before its handler | `api.Overloaded` (503), with `Retry-After` in whole seconds: nothing was done (§5a) |

**400 versus 422** is the distinction between "your serialiser is wrong" and "your data is
wrong": a client can act on the second, and only a developer can act on the first.

The limit closes both routes — a declared `Content-Length` over the limit is refused without
reading a byte, and a chunked body, which declares no length at all, is bounded by a counting
stream. A limit that only reads the header is one a caller opts out of by sending chunked.

**One route carries its own bound** (`P8-TSK-003`, ADR-0066 §4): `POST
/v1/operator/settlement/files` is bounded at **12582912** bytes (`finapp.api.settlement-upload-max-request-bytes`),
because a settlement upload's base64 envelope of up to 8 MiB decoded is ~11.2 MiB on the wire.
A per-route carve-out, never a raised global limit — every other surface keeps the 1 MiB bound,
and the decoded-content rule (8 MiB, 50,000 records) still belongs to the domain, which refuses
it as `settlement.FileTooLarge` (413) with the refusal's audit record written.

**Rejected values are never echoed.** A validation detail names the field and the constraint, both
of which are ours; the value is the caller's, and reflecting untrusted bytes into a response is how
an error message becomes a vector (`INV-AUD-02`).

*Proven by `RequestValidationTest`, which counts handler entries rather than reading the response —
a 422 returned after the handler ran and did half the work looks identical from outside.*

**The per-source limits** (`P15-TSK-022`) are on the five doors that need no session and no signature: `POST /v1/registrations`, `POST
/v1/authentications`, `POST /v1/recoveries`, its completion, and `POST /v1/me/channels/verification`. One count per source and fixed
window, across every replica, held in PostgreSQL. A source is the connection's address, an IPv4 address whole or an IPv6 address by its
/64; a forwarded header never chooses it (`SourceLimitInterceptorTest`). A window's end is the database's, so a retry after `Retry-After` falls in
a new window whichever replica takes it. Two sources behind one carrier's NAT are one source. A provider's signed callback is not counted:
its signature is checked first, and nothing is written for a forged one. **A retry counts**: a count comes after the `Idempotency-Key`
header's shape is checked and before its record is read, so a same-key retry past the limit is answered `429` where the stored `201`
would have been replayed, and gets that stored answer once the window has ended.

## 5a. Load shedding — **Implemented** (`P16-TSK-016`, ADR-0137)

**A request an instance cannot serve in time is refused before its handler, never after.** Every financial effect, and the
idempotency claim that guards it, is made inside a handler, so a request refused in front of it did nothing: no claim, no row, no
posting. The client's retry under the same `Idempotency-Key` is therefore a first attempt, and the answer says so.

| Rule | Behaviour |
|---|---|
| Where it is decided | An interceptor ahead of every other one: before the `Idempotency-Key` check, the session's and the merchant key's authentication and the per-source count, each of which may borrow a database connection. A second check after both authentications, before the count. Never inside a handler, and never after a claim |
| The pool | Every request is shed while the instance's writer pool already has `finapp.shedding.pool-waiters` threads waiting for a connection |
| The command permits | A command - every method but `GET`, `HEAD`, `OPTIONS` and `TRACE`, which move no money (§6) - needs one of the instance's `finapp.shedding.command-permits`, fewer than its pool's connections. Past them it is shed at once: never queued |
| The deadline | After authentication, a command with less than one connection's wait left of `finapp.shedding.deadline` is shed before its claim |
| The answer | `api.Overloaded` (503), the same body for every caller and route, naming nothing about why or how busy; `Retry-After` of whole seconds, `finapp.shedding.retry-after` plus a random 0 to `finapp.shedding.retry-after-spread` |
| What the client does | Waits `Retry-After`, then sends the same request under the same key. It is a first attempt, or, if another copy got through meanwhile, that copy's answer replayed |
| Not shed | The operator surface (the edges' marker), so staff can act on a shedding platform; the provider callbacks, each declaring `@NotShed` with its reason |
| Authentication | Never bypassed: the door only refuses, and what it admits goes on to every check behind it unchanged. An unauthenticated caller can be answered `503` under load; it learns that the instance is busy and nothing else |

Per instance: each instance bounds its own threads and pool, and nothing financial rests on it - the claim in PostgreSQL still
arbitrates a retry that lands on another instance. A shed is a `5xx` on its route and spends that route's availability objective
(`SLOS.md`). *Proven by `ShedBeforeTheClaimTest`, which counts claims and effects rather than reading the response, and wired as
`SheddingIsWiredTest` reads the served routes.*

## 6. Idempotency — **Implemented** (`P0-TSK-017`; first *used* by `P1-TSK-006`)

**One endpoint declares it: `POST /v1/registrations`** (`P1-TSK-006`), which is the mechanism's
first real user and arrived two phases earlier than expected. Registration **moves no money**, so
`INV-IDEM-01`'s money-moving clause is vacuous for it — and it is made idempotent anyway, because a
retried registration that creates a second Party is a duplicate person, which is expensive in a
different way and unpickable later for the same reason ADR-0029 gives. Phase 4's `POST /transfers`
is still the first money-moving one.

**Every money-moving command requires `Idempotency-Key`.** Safe methods (`GET`, `HEAD`, and also
`OPTIONS`/`TRACE`) ignore it; a command that can move money and does not require it is a defect,
not a relaxation.

An endpoint says so with **`@RequiresIdempotencyKey`**, on the handler or on its controller. A
declaration rather than a default: requiring the header everywhere would force it onto reads, where
it means nothing and would train clients to send a value nobody uses. The annotation is also the
greppable list of endpoints claiming to move money, which is a list worth being able to review.

Enforcement is an **interceptor**, not a filter, and that is load-bearing twice: a filter runs
before the dispatcher has chosen a handler, so it could not know whether *this* endpoint declares
the requirement without a second copy of the routing table; and a filter runs outside
`@ExceptionHandler`, so its rejection would be the container's default page rather than the error
contract. The rejection happens **before the handler is entered** — asserted by counting handler
entries, because a rejection issued after a command did half its work looks identical from outside.

| Rule | Behaviour |
|---|---|
| Missing on an endpoint that requires it | `api.IdempotencyKeyRequired` (422). The request is not attempted |
| Present and unusable — blank, or over the bound | `api.ValidationFailed` (422). A different code on purpose: the client supplied one and must fix it, which is a different remediation from "generate one" |
| Same key, same request | The **original** outcome is returned. Exactly one financial effect (`INV-IDEM-01`) |
| Same key, materially different request | `api.Conflict` (409). Never a silent second effect, never the first response (`INV-IDEM-03`) |
| Key still in progress | Reported as in progress; the caller retries. Never assumed failed |
| Key format | Client-generated, bounded, and validated at the boundary against **`IdempotencyKey`'s own limits**, so a request cannot pass the boundary and fail three layers down on a `CHECK` constraint. A UUID is the expected shape |
| A rejected value | **Never echoed** in the response. It is the caller's own input, and reflecting it is how a header becomes a reflection vector (`INV-AUD-02`). The caller already knows what it sent |
| A valid value | **Never rewritten.** A key the platform silently adjusted would not match the caller's retry, which is the one thing that must never happen: the retry would create a second financial effect |

**The key is scoped, not global.** Two callers must not collide by both choosing `1`, so the
uniqueness constraint covers a scope (the operation, and the authenticated principal where there is
one) alongside the key. See ADR-0004.

**Registration is permanently the exception to the principal half**, and that is a property of what
it does rather than an omission: it is the endpoint that *creates* a principal, so there is none to
scope by. The residual is stated rather than glossed — an attacker who holds a key *and* knows the
exact login identifier and display name can obtain a replay. What bounds it is that the endpoint
returns **no body** and no replay indication, so a replay discloses only that the request succeeded;
no session, no credential and no identifier are handed over. This is inherent to making an
unauthenticated endpoint idempotent, and it is bounded by the retention window.

**What a command hashes into its fingerprint is the command's decision** (`RequestFingerprint`), and
registration's is worth recording: the **semantically significant fields only**, and — when
`P1-TSK-007` adds one — **never the credential**. `request_fingerprint` is a durable single-round
SHA-256, so hashing a body containing a password would store an offline-crackable derivation of it,
which is `INV-IDN-01` violated by the idempotency mechanism.

**It is enforced by a database constraint**, never by a cache or an HTTP filter — a filter
deduplicates requests, and what must be deduplicated is *financial effects*.

**Keys expire.** Retention is a correctness bound rather than housekeeping: too short and a client
retrying after an outage gets a second effect; too long and the table grows without limit
(`DATA_MIGRATIONS.md` §8). A retry after expiry is a new request.

**Logging:** the key is **not sensitive and is not redacted** — `secretsAreWrapped` deliberately
excludes `key` from its vocabulary (`P0-TSK-030`), because a rule with false positives is a rule
somebody turns off. An accepted key passes through intact; a *rejected* one is never echoed back.

It is validated against a **default-deny charset**, the same one the correlation identifier uses.
`IdempotencyKey` bounds length and blankness because those are the `CHECK` constraints on the
table, and the table has no charset — so without this a caller could put CR/LF in a value the
platform logs and stores, which is a forged log line (`INV-AUD-02`). The limit: a key arriving by
some future non-HTTP path gets no charset check from the HTTP boundary and needs its own.

**Recording it on the audit record is *not yet implemented*** — owned by Phase 4, the first phase
with an audited money-moving action. `AuditRecord` has no field for it and nothing in Phase 0
writes an audit record in an HTTP flow, so there is nothing to record it *on*. When there is, the
key travels as a normal command parameter rather than as ambient state, so no context mechanism is
needed for it (the distinction `P0-TSK-032` drew for the actor, in the opposite direction).

## 7. Pagination — **Implemented** for operator lists (`P17-TSK-107`, `P17-TSK-042`; finapp-admin-panel ADR-C133)

*(Read "Decided, not yet implemented (first collection endpoint, Phase 3+)" and "No endpoint returns a collection today" until
`P17-TSK-107`, 2026-11-18; and "**Implemented** for the operator collections that follow it" until `P17-TSK-042`, 2026-11-20.)*
The operator collections that implement this section exactly, the first two from `P17-TSK-107`:

| Endpoint | Permission | Declared total order | `limit` default / maximum | Filters |
|---|---|---|---|---|
| `GET /v1/operator/settlement/batches` | `RECONCILIATION_INVESTIGATE` | business date descending, batch id descending | 50 / 200 | `source` (a declared code); `from`, `to` (business dates, inclusive; `to` today UTC and `from` 30 days before unless named; 92 days at most) |
| `GET /v1/operator/reconciliation/resolutions` | `RECONCILIATION_RESOLVE` | proposed at ascending, resolution id ascending | 50 / 200 | `status` (`PROPOSED` only: the queue) |
| `GET /v1/operator/identities` (`P17-TSK-014`) | `IDENTITY_READ`, on a `MULTI_FACTOR` session | created at descending, identity id descending | 50 / 200 | `role` (a live holding of a `RoleName`), `status` (`ACTIVE`, `SUSPENDED`, `CLOSED`) |
| `GET /v1/operator/audit-records` (`P17-TSK-017`) | `AUDIT_READ`, on a `MULTI_FACTOR` session | occurred at descending, audit id descending | 50 / 200 | `from`, `to` (instants, `[from, to)`, required, 31 days at most); `actor`, `operation`, `targetType`, `targetId`, `correlationId`, `outcome` (exact) |
| `GET /v1/operator/risk/assessments` (`P17-TSK-020`) | `RISK_INVESTIGATE` | assessed at descending, assessment id descending | 50 / 200 | `from`, `to` (instants, `[from, to)`, required, 31 days at most); `partyId`, `operation`, `decision` (exact). The reader's own party's assessments withheld; naming that party `403 risk.SelfDealingRefused` |
| `GET /v1/operator/risk/policies/versions` (`P17-TSK-020`) | `RISK_POLICY_ADMINISTER` | proposed at descending, version id descending | 50 / 200 | `operation`, `status` (`PROPOSED` is the approver's queue) |
| `GET /v1/operator/risk/screening-lists` (`P17-TSK-020`) | `RISK_POLICY_ADMINISTER` | recorded at descending, version id descending | 50 / 200 | `list` (`SANCTIONS`, `PEP`); metadata only, never an entry |
| `GET /v1/operator/ledger/accounts` (`P17-TSK-022`) | `ACCOUNTING_READ` | purpose ascending, currency ascending, account id ascending | 50 / 200 | `purpose` (an `AccountPurpose`), `currency` (ISO 4217). An owner is a reference, never a name; every page recorded as the audit action ledger.AccountsListed |
| `GET /v1/operator/ledger/entries` (`P17-TSK-022`) | `ACCOUNTING_READ` | posting date descending, entry id descending | 50 / 200 | `from`, `to` (posting dates, inclusive, required, 31 at most); `reference` (identifier-shaped, matched exactly, never served), `accountId`. Every page recorded as the audit action ledger.EntriesSearched; a read past its statement timeout `503 api.TemporarilyUnavailable` |
| `GET /v1/ledger/adjustments` (`P17-TSK-022`) | `LEDGER_ADJUST` | proposed at ascending, proposal id ascending | 50 / 200 | `status` (`PROPOSED` only: the queue of `MANUAL` proposals, the only origin that door decides) |
| `GET /v1/ledger/projection-rebuilds` (`P17-TSK-022`) | `LEDGER_ADJUST` | proposed at ascending, rebuild id ascending | 50 / 200 | `status` (`PROPOSED` only: the queue). Summarised by which figures drifted, never the proposer's reason |
| `GET /v1/operator/accounting/report-runs` (`P17-TSK-023`) | `ACCOUNTING_READ` | claimed at descending, run id descending | 50 / 200 | `periodId`, `status` (a run status). Each run's head as its own read serves it, never its content; every page recorded as the audit action accounting.ReportRunsListed |
| `GET /v1/operator/accounting/report-runs/final-statements` (`P17-TSK-023`) | `ACCOUNTING_CLOSE_APPROVE` | claimed at descending, run id descending | 50 / 200 | `periodId`. Exactly the runs the controller's statement door serves - FINAL balance sheets and income statements; every page recorded as the audit action accounting.FinalStatementsListed |
| `GET /v1/operator/accounting/mapping-versions` (`P17-TSK-023`) | `ACCOUNTING_MAPPING_APPROVE` | version descending | 50 / 200 | `status` (`PROPOSED` is the approver's queue); each row marks whether the caller proposed it |
| `GET /v1/operator/accounting/valuation-rate-sets` (`P17-TSK-023`) | `ACCOUNTING_CLOSE_APPROVE` | captured at descending, set id descending | 50 / 200 | `periodId`, `status` (`PROPOSED` is the approver's queue); each row marks whether the caller captured it; never a rate |
| `GET /v1/operator/accounting/valuation-rate-sets/mine` (`P17-TSK-023`) | `ACCOUNTING_CLOSE_PROPOSE` | captured at descending, set id descending | 50 / 200 | `status`. The sets the caller captured, every period's |
| `GET /v1/operator/fx/pricing-policies` (`P17-TSK-118`) | `FX_ADMINISTER` | version descending | 50 / 100 | None. Each version with its pairs and its proposer; the maximum is the capped list's old bound, so no page reads more than it did. Retrofitted in place from `{ versions, truncated }` (ADR-C133 decision 2), whose flag was never set |
| `GET /v1/operator/merchants` (`P17-TSK-033`) | `MERCHANT_ADMINISTER` | onboarded at descending, merchant id descending | 50 / 200 | `status` (`ACTIVE`, `SUSPENDED`, `CLOSED`); `name` (a legal or display name, matched exactly - never a pattern or a prefix). Unrecorded, as one merchant's read is |
| `GET /v1/operator/merchants/{merchantId}/payouts` (`P17-TSK-033`) | `MERCHANT_ADMINISTER` | created at descending, payout id descending | 50 / 200 | none: one merchant's payouts, each as the merchant's own read renders it. Every page recorded as the audit action merchant.PayoutsListed |
| `GET /v1/operator/merchants/{merchantId}/transactions` (`P17-TSK-033`) | `MERCHANT_ADMINISTER` | posting date descending, journal entry id descending, currency ascending | 50 / 200 | `from`, `to` (posting dates, inclusive; `to` today UTC and `from` thirty days before it unless named; 31 days at most). A list, not a statement: no opening, closing or sum across pages. Every page recorded as the audit action merchant.TransactionsListed |
| `GET /v1/operator/parties/{id}/consents` (`P17-TSK-026`) | `CUSTOMER_READ`, on a `MULTI_FACTOR` session | the history's `seq` descending (server-assigned, unique) | 50 / 200 | none; the party in the path. Every page recorded as the audit action consent.HistoryRead; an unknown party `404` |
| `GET /v1/operator/customers/{id}/accounts` (`P17-TSK-027`) | `ACCOUNT_READ`, on a `MULTI_FACTOR` session | opened at descending, agreement id descending | 50 / 200 | none; the customer in the path, every status. Every page recorded as the audit action accounts.CustomerAccountsListed; an unknown customer `404` |
| `GET /v1/operator/accounts/{id}/entries` (`P17-TSK-027`) | `ACCOUNT_READ`, on a `MULTI_FACTOR` session | entry id descending (a UUIDv7), the line's place in its entry descending | 50 / 200 | `currency`, required: one ledger account of the agreement. Every page recorded as the audit action ledger.AccountLinesListed; an unknown agreement or currency `404` |
| `GET /v1/operator/transfers` (`P17-TSK-027`) | `TRANSFER_READ`, on a `MULTI_FACTOR` session | initiated at descending, transfer id descending | 50 / 200 | `customerId`, `from` and `to`, required (the window `[from, to)` at most 92 days); `status`. Every page recorded as the audit action transfers.TransfersListed; an unknown customer `404` |
| `GET /v1/operator/kyc/cases` (`P17-TSK-029`) | `KYC_REVIEW`, on a `MULTI_FACTOR` session | opened at ascending, case id ascending | 50 / 200 | `status` (one open status: `OPEN`, `CHECKS_IN_PROGRESS`, `IN_REVIEW`, `READY_FOR_DECISION`; every open one when absent, a decided one refused). The review queue; each case's head, its file `GET /v1/kyc/cases/{id}`'s; every page recorded as the audit action kyc.ReviewQueueRead |
| `GET /v1/operator/kyc/counterparty-screenings` (`P17-TSK-029`) | `COUNTERPARTY_SCREENING_REVIEW`, on a `MULTI_FACTOR` session | raised at ascending, screening id ascending | 50 / 200 | `status` (`IN_REVIEW` only: the screenings awaiting a person). A row is the screening's id, status and when it was raised, never its subject (tipping-off); every page recorded as the audit action kyc.ScreeningBacklogRead |
| `GET /v1/operator/payments` (`P17-TSK-032`) | `PAYMENT_READ` | created at descending, intent id descending | 50 / 200 | `from`, `to` (instants, `[from, to)`, required, 31 days at most); `partyId` or `merchantId` (never both: a merchant is its payables, resolved by the ledger), `status` (a payment status), `rail` (a declared rail). Every page recorded as the audit action payments.PaymentsListed |
| `GET /v1/operator/disputes` (`P17-TSK-032`) | `DISPUTE_ADMINISTER` | the network's deadline ascending - an unstated one last - dispute id ascending | 50 / 200 | `stage` (a dispute stage), `deadlineBefore` (an instant; a dispute with no deadline is never before one). Every page recorded as the audit action payments.DisputeQueueRead |
| `GET /v1/operator/held-payments` (`P17-TSK-032`) | `RISK_INVESTIGATE` | the hold's deadline ascending, intent id ascending | 50 / 200 | none: the open capture holds, the reader's own party's withheld; read-only - the doors stay risk's. Every page recorded as the audit action payments.HeldPaymentsListed |
| `GET /v1/operator/routing-policy/versions` (`P17-TSK-032`) | `PAYMENT_ROUTING_ADMINISTER` | version number descending | 50 / 200 | none: every version with its rules; one by its number at `…/versions/{version}`. Configuration, unrecorded |
| `GET /v1/operator/reconciliation/breaks` (`P17-TSK-042`, G-121) | `RECONCILIATION_INVESTIGATE` | raised at descending, break id descending | 50 / 200 | `type`, `status`, `severity`, `source`, `assignee`, `agedOver`, as before. Unrecorded, as the desk's other reads |
| `GET /v1/operator/events/abandoned` (`P17-TSK-042`) | `EVENT_OPERATE` | abandoned at ascending, event id ascending | 50 / 200 | none. Every page recorded as the audit action deadletter.Read against its queue; never a payload |
| `GET /v1/operator/events/parked` (`P17-TSK-042`) | `EVENT_OPERATE` | parked at ascending, park id ascending | 50 / 200 | none. Every page recorded, as the abandoned events' |
| `GET /v1/operator/events/discards` (`P17-TSK-042`) | `EVENT_OPERATE` | proposed at ascending, discard id ascending | 50 / 200 | `status` (`PROPOSED` only: the discards awaiting a second person). Every page recorded |
| `GET /v1/operator/settlement/files` (`P17-TSK-042`) | `RECONCILIATION_INVESTIGATE` | received at descending, file id descending | 50 / 200 | none. Metadata, never content |
| `GET /v1/operator/settlement/refused-deliveries` (`P17-TSK-042`) | `RECONCILIATION_INVESTIGATE` | refused at descending, refusal id descending | 50 / 200 | none. The reason and position, never the value |
| `GET /v1/operator/access-review/factorless-holdings` (`P17-TSK-042`) | `ROLE_ASSIGN`, on a `MULTI_FACTOR` session | assigned at ascending, identity id ascending, role ascending | 50 / 200 | none. Every page recorded as the audit action identity.FactorlessHoldingsRead |
| `GET /v1/operator/credit/review-cases` (`P17-TSK-042`) | `CREDIT_UNDERWRITE` | opened at ascending, case id ascending | 20 / 50 | `status`. Each case with its basis, so the page stays small; every page recorded as the audit action credit.ReviewCasesRead |
| `GET /v1/operator/risk/alerts` (`P17-TSK-042`) | `RISK_INVESTIGATE` | raised at ascending, alert id ascending | 20 / 50 | `status` (`OPEN` unless named), `kind`. The reader's own party's alerts withheld; every page recorded as the audit action risk.AlertRead; a cursor naming no alert `422 risk.QueueRequestInvalid` |
| `GET /v1/operator/risk/cases` (`P17-TSK-042`) | `RISK_INVESTIGATE` | opened at ascending, case id ascending | 20 / 50 | `status` (every open one unless named), `kind`. As the alert queue, recorded as the audit action risk.CaseRead |
| `GET /v1/operator/accounting/periods` (`P17-TSK-042`) | `ACCOUNTING_READ` | month descending | 24 / 120 | none. Unrecorded, as before |
| `GET /v1/operator/fx/enable-requests` (`P17-TSK-037`) | `FX_ADMINISTER` | proposed at descending, request id descending | 50 / 200 | `status` (`PROPOSED`, `APPROVED` or `REJECTED` - the machine's words; `PROPOSED` is the approver's queue). Configuration, unrecorded |
| `GET /v1/operator/fx/trades` (`P17-TSK-037`) | `FX_INVESTIGATE` | booked at descending, trade id descending | 50 / 200 | `from`, `to` (instants, `[from, to)`, required, 31 days at most); `pair` (`SOURCE-DESTINATION`), `status` (`BOOKED` or `REVERSED`). Every page recorded as the audit action fx.TradesListed |
| `GET /v1/operator/cross-border/corridor-enable-requests` (`P17-TSK-037`) | `CROSSBORDER_ADMINISTER` | proposed at descending, request id descending | 50 / 200 | `status` (`PROPOSED`, `APPROVED` or `REJECTED`). Configuration, unrecorded |
| `GET /v1/operator/cross-border/payments` (`P17-TSK-037`) | `FX_INVESTIGATE` | created at descending, payment id descending | 50 / 200 | `from`, `to` (instants, `[from, to)`, required, 31 days at most); `corridor` (`S-D-CC`), `status` (a payment status). Every page recorded as the audit action crossborder.CrossBorderPaymentsListed |
| `GET /v1/operator/cross-border/corridor-policies` (`P17-TSK-122`) | `CROSSBORDER_ADMINISTER` | version descending | 20 / 100 | none. Each version with its corridors. Was `{ versions, truncated }`, its flag never set |
| `GET /v1/operator/access-review/sod-conflicts` (`P17-TSK-122`) | `ROLE_ASSIGN`, on a `MULTI_FACTOR` session | conflicting since ascending, identity id, first role, second role ascending | 50 / 200 | none. Every page recorded. Was `{ conflicts, truncated }` at 200 |
| `GET /v1/operator/reconciliation/runs` (`P17-TSK-122`) | `RECONCILIATION_INVESTIGATE` | created at descending, run id descending (the tiebreak it lacked) | 50 / 200 | none. Was `{ runs, truncated }` at 100 |
| `GET /v1/operator/reconciliation/expectations` (`P17-TSK-122`) | `RECONCILIATION_INVESTIGATE` | opened at descending, expectation id descending | 50 / 200 | `status`, `overdue`, `source`, as before. Was `{ expectations, truncated }` at 100 |
| `GET /v1/operator/reconciliation/rule-sets` (`P17-TSK-122`) | `RECONCILIATION_ADMINISTER` | version descending, within the required `source` | 20 / 50 | `source` (required). Was `{ versions, truncated }` at 20 |
| `GET /v1/operator/regulatory/obligations` (`P17-TSK-122`) | `REGULATORY_REPORT_PREPARE` | proposed at descending, obligation id descending | 50 / 100 | none. Recorded, as before. Was a bare array capped at 100 with nothing saying so |
| `GET /v1/operator/accounting/breaks` (`P17-TSK-122`) | `ACCOUNTING_READ` | break id ascending (a UUIDv7: birth order) | 100 / 500 | `period` (required), `status`. Was paged by `after`, its `next` set on any full page |
| `GET /v1/operator/bnpl-disputes` (`P17-TSK-122`) | `BNPL_DISPUTE_RESOLVE` | entered the status ascending (opened, or the merchant's response), dispute id ascending | 50 / 100 | `status` (required: `OPEN`, `MERCHANT_RESPONDED`); a cursor names its status. The operator's own party's disputes withheld and every page recorded, as before. Was paged by `after` at a fixed 100 |
| `GET /v1/operator/regulatory/submissions` (`P17-TSK-122`) | `REGULATORY_REPORT_PREPARE` | recorded order ascending, submission id ascending | 50 / 100 | none. Was paged by `after` with `nextAfter` |
| `GET /v1/operator/credit/decision-requests` (`P17-TSK-035`) | `CREDIT_INVESTIGATE` | submitted at descending, request id descending | 50 / 200 | `status` (a request status), `product`. Each request's party, product, door, status, amount asked, pins and decision - never a declared figure, a score or an attribute; every page recorded as the audit action credit.DecisionRequestsRead |
| `GET /v1/operator/credit/policies/versions` (`P17-TSK-035`) | `CREDIT_INVESTIGATE` | proposed at descending, version id descending | 50 / 200 | `product`, `status` (`PROPOSED` is the approver's queue); each row names its proposer and marks the caller's own, never the decider; every page recorded as credit.PolicyRead. One version with its whole policy at `.../versions/{versionId}` |
| `GET /v1/operator/credit/scorecards` (`P17-TSK-035`) | `CREDIT_INVESTIGATE` | proposed at descending, version id descending | 50 / 200 | `status`; as the policy versions, recorded as credit.ScorecardRead. One version with its points table at `.../scorecards/{versionId}` |
| `GET /v1/operator/loan-applications` (`P17-TSK-035`) | `LENDING_INVESTIGATE` | submitted at descending, application id descending | 50 / 200 | `status`. The reader's own party withheld; every page recorded as lending.ApplicationsRead |
| `GET /v1/operator/loans` (`P17-TSK-035`) | `LENDING_INVESTIGATE` | opened at descending, loan id descending | 50 / 200 | `partyId`, `state` (a loan status), `bucket` (`CURRENT` or a band, as the servicing step last recorded it - an account in servicing). The reader's own party withheld, naming it `403 lending.SelfDealingRefused`; every page recorded as lending.LoansRead |
| `GET /v1/operator/loans/servicing-requests` (`P17-TSK-035`) | `LOAN_SERVICE_APPROVE` | proposed at ascending, proposal id ascending | 50 / 200 | `status` (`PROPOSED` only: the queue of waivers, amendments and repayment reversals), ADR-C133's pending-decision shape. The reader's own party's loans withheld; every page recorded as lending.ServicingRequestsRead |
| `GET /v1/operator/lending/terms/versions` (`P17-TSK-035`) | `LENDING_INVESTIGATE` | proposed at descending, version id descending | 50 / 200 | `product`, `status` (`PROPOSED` is the approver's queue); each row names its proposer and marks the caller's own. Configuration: unrecorded, as the terms in force are |
| `GET /v1/operator/bnpl-applications` (`P17-TSK-035`) | `BNPL_INVESTIGATE` | submitted at descending, application id descending | 50 / 200 | `status`. The reader's own party withheld; every page recorded as bnpl.ApplicationsRead |
| `GET /v1/operator/bnpl-agreements` (`P17-TSK-035`) | `BNPL_INVESTIGATE` | opened at descending, obligation id descending | 50 / 200 | `status` (the obligation's), `merchantId`. The reader's own party withheld; every page recorded as bnpl.AgreementsRead |
| `GET /v1/operator/bnpl-purchases/{id}/refunds` (`P17-TSK-035`) | `BNPL_INVESTIGATE` | created at ascending, refund id ascending | 50 / 200 | none: one purchase's refunds; the reader's own party's purchase `403 bnpl.SelfDealingRefused`; every page recorded as bnpl.RefundsRead |
| `GET /v1/operator/bnpl-agreements/servicing-requests` (`P17-TSK-035`) | `BNPL_SERVICE_APPROVE` | proposed at ascending, proposal id ascending | 50 / 200 | `status` (`PROPOSED` only: the queue of waivers and repayment reversals), ADR-C133's pending-decision shape. The reader's own party's agreements withheld; every page recorded as bnpl.ServicingRequestsRead |

All are keyset reads. `app`'s `OperatorCollection` is the one implementation of the request rules: the limit is refused above
its maximum rather than reduced, and the cursor names its collection, so one collection's cursor is refused by another. Every
refusal is `422 api.ValidationFailed`. A page record carries `nextCursor` only when there is a next page.

**The retrofit** (`P17-TSK-042`, ADR-C133 decision 2, gaps G-03 and G-121). The operator lists built before this section was
implemented - the last eleven rows above - were capped with `truncated`, capped with nothing saying so, or paged by `after`/`limit`.
Each was replaced in place by the collection its row describes: the operator surface is internal and the Operator Console is its
one client (§8). The dead-letter queues, which one `GET /v1/operator/events/dead-letters` answered together, are three
collections, one per queue. `OperatorListsAreCollectionsTest` holds the operator surface to this section from the routes the
application serves: no `GET` there answers a bare array, a top-level `truncated` or takes `after` unless its register names the
route with its reason - a list bounded by what it is (a closed vocabulary, one subject's children, one period's report), or one
this section has not reached yet, named there so it is owed - and the table above lists exactly the routes answering a page.
The FX pricing policy list was retrofitted first, by `P17-TSK-118`: its old shape replaced in place, the operator surface being internal.

**The second batch** (`P17-TSK-122`, proposed at `P17-TSK-042`'s gate). The nine rows after the retrofit's eleven are the lists `P17-TSK-042` registered as not reached: capped with `truncated`, capped with nothing saying so, or paged by `after`. Each was replaced in place by its row's collection, its next page decided by a look-ahead row rather than by a full page. A GL account's activity (`GET /v1/operator/accounting/gl/accounts/{code}/activity`) and a report run's lines (`GET /v1/operator/accounting/report-runs/{runId}/lines`) are not lists but one period's figure with its lines - one subject's children - so they keep their view; each takes this section's `limit` (100 / 500) and opaque `cursor` in place of `after` and answers `nextCursor` only when there is a next page. No operator `GET` takes `after` any more.

**Cursor-based, never offset.** `?limit=&cursor=`. The reason is correctness before performance:
an offset re-reads a moving set, so when rows are inserted or removed between page 1 and page 2, a
row is **silently skipped or repeated**. For a customer's transaction history that means a payment
missing from an exported statement, with nothing anywhere reporting an error. Offset is also
O(offset) on the database, which matters on a ledger, but the skipping is the disqualifying part.

| Rule | Behaviour |
|---|---|
| Request | `limit` (optional) and `cursor` (opaque, omitted for the first page) |
| Response | `{ "items": [ ... ], "nextCursor": "..." }` |
| End of results | `nextCursor` **absent**, not null and not an empty string |
| `limit` default and maximum | Each endpoint declares both in its OpenAPI schema; a request over the maximum is `api.ValidationFailed` (422), never silently reduced |
| Ordering | Every paginated endpoint declares a **total** order. Where the sort key is not unique, the aggregate's identifier breaks the tie |
| Total counts | **Not returned by default.** Counting a large financial table is expensive and the answer is stale before the client reads it. An endpoint that genuinely needs one exposes it separately and says how stale it may be |

**The cursor is opaque.** Clients must never construct, parse or persist one beyond the immediate
sequence: it encodes the sort key, and encoding it visibly would make the internal ordering a
published contract that could not then be changed.

A total order is what makes this well-defined at all, and ADR-0013 already supplies it — aggregate
identifiers are UUIDv7 and therefore time-ordered, which is why keyset pagination is natural here
rather than a compromise.

### 7a. Every operator report states its as-of (`P17-TSK-039`, finapp-admin-panel ADR-C133 decision 5, gap G-04)

An operator report - a `GET` under `/v1/operator/reports/`, the dead-letter queues and the accounting operational reports - answers a record with **`asOf`**, an ISO-8601 instant: **the database's**, never the serving instance's clock (ADR-0063). It is read as the report transaction's first statement (`DatabaseTime.now`, `statement_timestamp()`), so in a `REPEATABLE READ` snapshot it is the snapshot's instant and in a `READ COMMITTED` report every figure is at least that fresh; the risk reports and the operational reports read their own database instant. The console shows it on every tile of the overview. `EveryOperatorReportStatesItsAsOfTest` holds the reports to a register, each answering a record with a `String asOf`. The payments report's `generatedAt`, an instance's clock, was replaced in place by `asOf`; the FX position's `asOf` now reads the database.

**The overview** is three reports under `PLATFORM_OVERVIEW_READ` (`OPERATIONS_VIEWER`'s one permission, gap G-151): `GET /v1/operator/reports/customers?date=` - every lifecycle status's standing count now, and the UTC day's openings and status changes after an opening (the platform keeps each customer's current status only, so a past day's standing counts are not served); `GET /v1/operator/reports/transfers?date=` - the UTC day's transfers by status and failure reason, counted and totalled exactly per currency at its stored scale, the day an identifier range on the UUIDv7 primary key; and `GET /v1/operator/reports/payments?date=` (`P17-TSK-032`'s, moved from `PAYMENT_READ`). `date` is required, an ISO date from 2000-01-01, else `422`. Each serving is recorded.

## 8. Deprecation — **Decided, not yet implemented** (first deprecation)

Within a version, evolution is additive. Nothing is deprecated today, so no mechanism is built;
building one now would be untested machinery.

| Step | What happens |
|---|---|
| Mark | `deprecated: true` in the OpenAPI document, so the deprecation is part of the published contract rather than an announcement someone may miss |
| Announce | `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) response headers, plus `Link rel="deprecation"` to the replacement |
| Wait | At least **6 months** from the first `Deprecation` header to removal. A superseded **version** is served at least **12 months** after its successor is generally available |
| Remove | Only in the next version, never inside the current one |

**The operator surface's one recorded exception** (`P17-TSK-042`, finapp-admin-panel ADR-C133 decision 2). The operator lists
`P17-TSK-042` retrofitted to section 7 were replaced in place, not versioned or deprecated: the operator surface is internal
(§8a), reached only from the internal network, and the Operator Console - which generates its types from the published contract -
is its one client, changed in the same milestone. Each change is a documented breaking change, listed in the task's record and in
section 7's table: the routes keep their paths and permissions, and their answers became `{ items, nextCursor }` - except
`GET /v1/operator/events/dead-letters`, which became three collections, one per queue. Nothing on the public surface changed,
and this is not a precedent for it: a public route still evolves additively and is retired only by the steps above.

**A money-moving endpoint carries one further obligation:** before removal, the reconciliation and
audit consequences are considered explicitly. Records referencing it remain, and operations that
must still be explainable outlive the endpoint that created them.

## 8a. The two surfaces — **Implemented** (`P15-TSK-011`)

Two edge hosts serve the one API, and which route lives where is derived from what the route declares
(`EdgeRoutes`, held by `EdgeAllowListTest`):

| Surface | Reached from | Routes |
|---|---|---|
| Public | The internet | Every route no operator permission guards and not under `/v1/operator`: a customer's, a merchant's and a provider's |
| Operator | The internal network | `/v1/operator/**`, every `@RequiresPermission` route outside that prefix, and the doors an operator needs for a session (`POST /v1/authentications` and `/v1/authentications/mfa`, the `/v1/sessions` routes, `/v1/me/mfa` and `POST /v1/me/credential`) and, since `P15-TSK-042`, to recover an account (`/v1/recoveries` and its completion, `/v1/me/channels` and its verification) |

The generated lists, `deploy/edge/base/public-routes.yaml` and `operator-routes.yaml`, are never edited. A route added or
moved shows up as a failing `EdgeAllowListTest` until the regenerated file is copied over, so the change is a reviewed line.

**One operator route is not the API's** *(`X-TSK-020`, 2026-11-14, for finapp-admin-panel `P17-TSK-004`)*. `GET /console` and
`GET /console/**` are the Operator Console's static assets: the operator file's `operator-console` router, generated with the rest
but stated once in `EdgeRoutes` rather than derived, sends them to the console's own Service (`deploy/console`) under the console's own
headers (`console-headers`: the console's Content-Security-Policy with Trusted Types, HSTS, `nosniff`, `no-referrer`, `frameDeny`; no
`Cache-Control`, which the console's server sets per path). Every API route keeps `edge-headers` and `default-src 'none'`; the public
host never serves `/console`; and no API route may be served under `/console`, which the generator refuses.

**Operator answers are never stored** *(`P17-TSK-101`, 2026-11-17; gap G-05; finapp-admin-panel ADR-C131 decision 5)*. Every answer the application gives on the operator surface - a handler's, a refusal, a problem document, the container's own error dispatch - carries `Cache-Control: no-store`, so no browser, shared workstation or intermediary keeps what a session read after it ends. One filter sets it from the edge's marker (`OperatorResponsesAreNotStored`, next after the correlation filter, before the chain runs); where nothing is deployed the one port is the operator surface too and carries it. The public surface's answers carry no `Cache-Control`: its caching is not decided here. A route's author sets no `Cache-Control` of their own and never resets a response - `OperatorResponsesAreNotStoredTest` refuses both - and `/console/**` keeps the console server's per-path caching.

**For a route's author.** A route is listed by method and whole path, so a new method on an existing path is a new line. A
path variable's value is letters, digits, `_` and `-`, with a dot only between other characters: an identifier, a code, an enum
name, a settlement source's dotted code. A value carrying `;`, `%` or `/`, or a segment of `.` or `..`, never reaches the
application, because those are how a path allow-list is walked around.

**What the edge answers itself.** These are the proxy's plain responses, not the error contract's problem documents, because the
application was never reached: `404` for a method and path not on the list (or the other surface's route); `400` or `404` for a
path with a refused encoded character; `413` for a body far over the application's own limit; `429` for a coarse per-source limit;
`403` for the operator host from outside the internal ranges; `500` for a body past 64 KiB while the edge's spool is full, or a response over the edge's cap. Those the application could have answered carry HSTS and the security headers; a `404` or `400` no route matched does not, because no route's headers ran. The application's own `api.PayloadTooLarge` and validation
answers are unchanged, and the edge's body cap sits above them, so a request at the application's limit still gets the application's
answer. The per-source limits are coarse: one bucket for each source across each tier's routes (the sign-in and registration doors, a session's routes, a merchant's, a provider's), per controller replica. The limits that count are the application's.

**The client's address** the application records is the one the edge set from the connection. A client-supplied forwarded header
never becomes it (`ForwardedAddressTest`).

**Staff use the operator surface for their own access** (`P15-TSK-042`). Each edge states which surface a request arrived on in
`X-Finapp-Surface` (`public` or `operator`), replacing whatever a client sent, so it is never a client's to set and is not part of the
published contract. A role-holding identity signs in, steps up, enrols and confirms a factor, changes its password, names a recovery
channel and recovers on the operator surface alone; on the public surface each of those doors answers it with its ordinary failure
(`ERROR_CONTRACT.md`, the identity section), counts nothing against its lockout and changes nothing. Customers are answered on both
surfaces as before. Behind the edge, a request with no usable marker is treated as the public surface's; where nothing is deployed in
front of the application (no edge network named and its database on loopback) the one port serves both, as it did before the edge. A
role-holder is any identity holding a live role, read from the grants on every request, so a staff member who is also a customer with
the same login signs in on the operator surface only, their own accounts included.

## 9. What is deliberately not decided here

Recorded so their absence is not mistaken for an oversight.

| Not decided | Owning phase |
|---|---|
| Authentication and authorization at the boundary — schemes, token shape, `401` versus `403` in practice | Phase 1 (`P0-EPIC-10` provides the Phase 0 baseline) |
| Filtering and sorting parameter grammar | The first endpoint that needs one |
| Bulk and batch request shapes | The first capability that needs one |
| Asynchronous request/response (`202` and its polling contract) | Phase 5, with payment lifecycles |
| Webhook delivery, signing and retry semantics | Phase 5 |
